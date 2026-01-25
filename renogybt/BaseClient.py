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
        self.device_id = self.config.getint('device_id')
        self.sections = []
        self.section_index = 0
        self.read_timeout_task = None
        self.read_error = False
        logging.info(f"Init {self.__class__.__name__}: {self.config['alias']} => {self.config['mac_addr']}")

    async def connect(self):
        self.ble_manager = BLEManager(mac_address=self.config['mac_addr'], alias=self.config['alias'], on_data=self.on_data_received, on_connect_fail=self.__on_connect_fail, notify_char_uuid=NOTIFY_CHAR_UUID, write_char_uuid=WRITE_CHAR_UUID, write_service_uuid=WRITE_SERVICE_UUID)

        await self.ble_manager.connect()
        if self.ble_manager.client and self.ble_manager.client.is_connected:
            return
        raise Exception("Connect error")

    async def read(self):
        self.read_done_event = asyncio.Event()
        await self.read_section()
        await self.read_done_event.wait()
        if self.read_error:
            raise Exception("Some read error occurred")

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
                self.on_read_operation_complete()
                self._reset()
            else:
                self.section_index += 1
                await asyncio.sleep(0.5)
                await self.read_section()
        else:
            logging.warning("on_data_received: unknown operation={}".format(operation))

    def on_read_operation_complete(self):
        logging.debug("on_read_operation_complete")
        self.data['__device'] = self.config['alias']
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

    def _reset(self):
        self.section_index = 0
        self.data = {}
        if self.read_done_event:
            self.read_done_event.set()

    def __on_error(self, error=None):
        logging.error(f"Exception occured: {error}")
        self.__safe_callback(self.on_error_callback, error)
        self.read_error = True
        self._reset()
    
    def __on_connect_fail(self, error):
        logging.error(f"Connection failed: {error}")
        self.__safe_callback(self.on_error_callback, error)
        raise RuntimeError(f"Connection failed: {error}")

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
