import asyncio
import configparser
import logging
import traceback
from .BLEManager import BLEManager
from .Utils import bytes_to_int, crc16_modbus, format_temperature, int_to_bytes


ALIAS_PREFIXES = ['DP']
WRITE_SERVICE_UUID = "0000ff00-0000-1000-8000-00805f9b34fb"
NOTIFY_CHAR_UUID = "0000ff01-0000-1000-8000-00805f9b34fb"
WRITE_CHAR_UUID  = "0000ff02-0000-1000-8000-00805f9b34fb"
READ_TIMEOUT = 15 # (seconds)
STATUS_READ_SUCCESS = 0
COMMAND_READ_BASIC = b'\xdd\xa5\x03\x00\xff\xfd\x77'
COMMAND_READ_CELLV = b'\xdd\xa5\x04\x00\xff\xfc\x77'
OPERATION_BASIC_INFO = 3
OPERATION_CELLV_INFO = 4
FRAME_HEADER = b'\xDD'[0]
FRAME_END = b'\x77'[0]


class EcoWorthyClient:
    def __init__(self, config, on_data_callback=None, on_error_callback=None):
        self.on_data_callback = on_data_callback
        self.on_error_callback = on_error_callback
        self.config: configparser.ConfigParser = config
        self.ble_manager = None
        self.device = None
        self.poll_timer = None
        self.read_timeout = None
        self.data = {}
        self.device_id = self.config['device'].getint('device_id')
        self.fetched_basics = False
        self.fetched_cellv = False
        self.frame = None
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
            logging.info("KeyboardInterrupt received")
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
                await self.fetch_next()
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
                        await self.fetch_next()
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
        frame_len = len(response)
        frame_header = response[0]
        frame_end = response[-1]

        if frame_header != FRAME_HEADER and self.frame:
            self.frame += response
            logging.info(f"Adding {frame_len} bytes to existing frame.")
        elif frame_header == FRAME_HEADER:
            operation = bytes_to_int(response, 1, 1)
            status = bytes_to_int(response, 2, 1)
            data_length = bytes_to_int(response, 3, 1)
            self.frame = response
            logging.info(f"Received new frame, frame header: {frame_header}, operation: {operation}, status: {status}, data length: {data_length}, frame length: {frame_len}")

        if frame_end == FRAME_END:
            operation = bytes_to_int(self.frame, 1, 1)
            data_length = bytes_to_int(self.frame, 3, 1)
            payload = self.frame[4:-3]
            logging.info(f"Payload size is {len(payload)}, expecting {data_length}")
            if operation == OPERATION_BASIC_INFO:

                data = {}
                data['voltage'] = bytes_to_int(payload, 0, 2, signed=False, scale=0.01)
                data['current'] = bytes_to_int(payload, 2, 2, signed=True, scale=0.01)
                data['capacity_remaining'] = bytes_to_int(payload, 4, 2, signed=False, scale=0.01)
                data['capacity'] = bytes_to_int(payload, 4, 2, signed=False, scale=0.01)
                data['temperature'] = bytes_to_int(payload, 23, 2, signed=False, scale=0.1) - 273.1
                temp_unit = self.config['data']['temperature_unit'].strip()
                if temp_unit == "F":
                    data['temperature'] = format_temperature(data['temperature'])
                data['power'] = data['voltage'] * data['current']
                data['percentage'] = 0 if data['capacity'] == 0 else 100.0 * data['capacity_remaining'] / data['capacity']

                self.data.update(data)
                self.fetched_basics = True
                await self.fetch_next()
            elif operation == OPERATION_CELLV_INFO:

                data = {}
                no_cells = int(data_length / 2)
                for cell in range(1, no_cells+1):
                    data[f'voltage_cell{cell}'] = bytes_to_int(payload, 2*(cell-1), 2, signed=False, scale=0.001)

                self.data.update(data)
                self.fetched_cellv = True
                await self.fetch_next()
            else:
                logging.warning("on_data_received: unknown operation={}".format(operation))
            self.frame = None
        else:
            logging.info("Still waiting for frame end.")

    async def fetch_next(self):
        try:
            await asyncio.sleep(0.5)

            if not self.fetched_basics:
                # Start timeout task for read response
                self.read_timeout_task = asyncio.create_task(
                    self._check_timeout()
                )
                await self.ble_manager.characteristic_write_bytes(
                    COMMAND_READ_BASIC
                )
            elif not self.fetched_cellv and self.config["data"].get("read_cellv"):
                # Start timeout task for read response  
                self.read_timeout_task = asyncio.create_task(
                    self._check_timeout()
                )
                await self.ble_manager.characteristic_write_bytes(
                    COMMAND_READ_CELLV
                )
            else:
                # all done!
                self.__safe_callback(self.on_data_callback, self.data)
                # and reset in case this is running in a loop
                self.fetched_basics = False
                self.fetched_cellv = False
                self.data = {}
                await self.check_polling()
        except Exception as e:
            logging.error(f"Error in fetch_next: {e}")
            self.__on_error(e)

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
            await self.fetch_next()

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
