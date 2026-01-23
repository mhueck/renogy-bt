import asyncio
import configparser
import logging
import traceback
from .BLEManager import BLEManager
from .Utils import bytes_to_int, crc16_modbus, int_to_bytes

# Base class that works with all Renogy family devices
# Should be extended by each client with its own parsers and section definitions
# Section example: {'register': 5000, 'words': 8, 'parser': self.parser_func}

ALIAS_PREFIXES = ['BT-TH', 'RNGRBP', 'BTRIC']
WRITE_SERVICE_UUID = "0000ffd0-0000-1000-8000-00805f9b34fb"
NOTIFY_CHAR_UUID = "0000fff1-0000-1000-8000-00805f9b34fb"
WRITE_CHAR_UUID  = "0000ffd1-0000-1000-8000-00805f9b34fb"
READ_TIMEOUT = 15 # (seconds)
READ_SUCCESS = 3
READ_ERROR = 131

class BaseClient:
    def __init__(self, config):
        self.config: configparser.ConfigParser = config
        self.ble_manager = None
        self.device = None
        self.poll_timer = None
        self.read_timeout = None
        self.data = {}
        self.device_id = self.config['device'].getint('device_id')
        self.sections = []
        self.section_index = 0
        self.read_timeout_task = None
        self._stop_event = None
        self._running = False
        logging.info(f"Init {self.__class__.__name__}: {self.config['device']['alias']} => {self.config['device']['mac_addr']}")

    def start(self):
        """Start the client using high-level asyncio APIs."""
        try:
            # Use asyncio.run() for proper event loop management
            asyncio.run(self._run_with_timeout())
        except KeyboardInterrupt:
            logging.warning("KeyboardInterrupt received")
            self.__on_error("KeyboardInterrupt")
        except Exception as e:
            self.__on_error(e)
    
    async def _run_with_timeout(self):
        """Run the main task with timeout using high-level asyncio APIs."""
        try:
            # Use asyncio.wait_for for timeout handling
            await asyncio.wait_for(self._main_task(), timeout=60.0)
        except asyncio.TimeoutError:
            logging.error("Application timeout after 60 seconds")
            self.__on_error("Application timeout")
        except Exception as e:
            logging.error(f"Error in main task: {e}")
            self.__on_error(e)
    
    async def _main_task(self):
        """Main async task that handles the connection and operation lifecycle."""
        self._running = True
        self._stop_event = asyncio.Event()
        try:
            await self.connect()
            # Keep the task running until explicitly stopped
            await self._stop_event.wait()
        finally:
            await self._cleanup()
            self._running = False

    async def connect(self):
        try:
            self.ble_manager = BLEManager(mac_address=self.config['device']['mac_addr'], alias=self.config['device']['alias'], on_data=self.on_data_received, on_connect_fail=self.__on_connect_fail, notify_char_uuid=NOTIFY_CHAR_UUID, write_char_uuid=WRITE_CHAR_UUID, write_service_uuid=WRITE_SERVICE_UUID)

            await self.ble_manager.connect()
            if self.ble_manager.client and self.ble_manager.client.is_connected:
                await self.read_section()
            else:
                logging.warning("Was not able to connect to MAC prior to discover - going long route.")

                await self.ble_manager.discover()

                if not self.ble_manager.device:
                    logging.error(f"Device not found: {self.config['device']['alias']} => {self.config['device']['mac_addr']}, please check the details provided.")
                    for dev in self.ble_manager.discovered_devices:
                        if dev.name != None and dev.name.startswith(tuple(ALIAS_PREFIXES)):
                            logging.info(f"Possible device found! ====> {dev.name} > [{dev.address}]")
                    self.__on_error("Device not found after discovery")
                    return
                else:
                    await self.ble_manager.connect()
                    if self.ble_manager.client and self.ble_manager.client.is_connected: 
                        await self.read_section()
                    else:
                        self.__on_error("Failed to connect after discovery")
                        return
        except Exception as e:
            logging.error(f"Connection failed with exception: {e}")
            self.__on_error(e)

    async def disconnect(self):
        if self.ble_manager:
            await self.ble_manager.disconnect()

    async def on_data_received(self, response):
        # Cancel timeout task if it exists
        if hasattr(self, 'read_timeout_task') and not self.read_timeout_task.done():
            self.read_timeout_task.cancel()
        operation = bytes_to_int(response, 1, 1)

        if operation == READ_SUCCESS or operation == READ_ERROR:
            if (operation == READ_SUCCESS and
                self.section_index < len(self.sections) and
                self.sections[self.section_index]['parser'] != None and
                self.sections[self.section_index]['words'] * 2 + 5 == len(response)):
                # call the parser and update data
                logging.debug(f"on_data_received: read operation success")
                self.__safe_parser(self.sections[self.section_index]['parser'], response)
            else:
                logging.warning(f"on_data_received: read operation failed: {response.hex()}")

            if self.section_index >= len(self.sections) - 1: # last section, read complete
                self.section_index = 0
                self.on_read_operation_complete()
                self.data = {}
                await self.check_polling()
            else:
                self.section_index += 1
                await asyncio.sleep(0.5)
                await self.read_section()
        else:
            logging.warning("on_data_received: unknown operation={}".format(operation))

    def on_read_operation_complete(self):
        logging.debug("on_read_operation_complete")
        self.data['__device'] = self.config['device']['alias']
        self.data['__client'] = self.__class__.__name__
        self.__safe_callback(self.on_data_callback, self.data)

    async def _check_timeout(self):
        """Check for read timeout using high-level asyncio APIs."""
        try:
            await asyncio.sleep(READ_TIMEOUT)
            logging.error("on_read_timeout => Timed out! Please check your device_id!")
            self.__on_error("Read timeout")
        except asyncio.CancelledError:
            # Timeout was cancelled, which is normal
            pass

    async def check_polling(self):
        if self.config['data'].getboolean('enable_polling'):
            poll_interval = self.config['data'].getint('poll_interval')
            await asyncio.sleep(poll_interval)
            await self.read_section()

    async def read_section(self):
        try:
            index = self.section_index
            if self.device_id is None or len(self.sections) == 0:
                logging.error("BaseClient cannot be used directly")
                self.__on_error("BaseClient cannot be used directly")
                return

            # Start timeout task for read response
            self.read_timeout_task = asyncio.create_task(
                self._check_timeout()
            )
            request = self.create_generic_read_request(
                self.device_id, 3, 
                self.sections[index]['register'], 
                self.sections[index]['words']
            )
            await self.ble_manager.characteristic_write_value(request)
        except Exception as e:
            logging.error(f"Error in read_section: {e}")
            self.__on_error(e)

    def create_generic_read_request(self, device_id, function, regAddr, readWrd):                             
        data = None                                
        if regAddr != None and readWrd != None:
            data = []
            data.append(device_id)
            data.append(function)
            data.append(int_to_bytes(regAddr, 0))
            data.append(int_to_bytes(regAddr, 1))
            data.append(int_to_bytes(readWrd, 0))
            data.append(int_to_bytes(readWrd, 1))

            crc = crc16_modbus(bytes(data))
            data.append(crc[0])
            data.append(crc[1])
            logging.debug("{} {} => {}".format("create_request_payload", regAddr, data))
        return data

    def __on_error(self, error=None):
        logging.error(f"Exception occured: {error}")
        self.__safe_callback(self.on_error_callback, error)
        # With asyncio.run(), we can simply raise an exception to terminate
        if error and str(error) not in ["KeyboardInterrupt"]:
            raise RuntimeError(f"Client error: {error}")

    def __on_connect_fail(self, error):
        logging.error(f"Connection failed: {error}")
        self.__safe_callback(self.on_error_callback, error)
        raise RuntimeError(f"Connection failed: {error}")

    def stop(self):
        """Stop the client and clean up resources."""
        if self._running and self._stop_event:
            self._stop_event.set()

    async def _cleanup(self):
        """Cleanup resources."""
        try:
            if self.ble_manager:
                await self.ble_manager.disconnect()
        except Exception as e:
            logging.error(f"Error during cleanup: {e}")

    def __safe_callback(self, calback, param):
        if calback is not None:
            try:
                calback(self, param)
            except Exception as e:
                logging.error(f"__safe_callback => exception in callback! {e}")
                traceback.print_exc()

    def __safe_parser(self, parser, param):
        if parser is not None:
            try:
                parser(param)
            except Exception as e:
                logging.error(f"exception in parser! {e}")
                traceback.print_exc()
