import asyncio
import logging
import traceback

from .BLEManager import BLEManager
from .Utils import bytes_to_int, crc16_modbus, int_to_bytes

WRITE_SERVICE_UUID = "0000ffd0-0000-1000-8000-00805f9b34fb"
NOTIFY_CHAR_UUID = "0000fff1-0000-1000-8000-00805f9b34fb"
WRITE_CHAR_UUID = "0000ffd1-0000-1000-8000-00805f9b34fb"
READ_TIMEOUT = 15
READ_SUCCESS = 3
READ_ERROR = 131


class RenogyClient:
    def __init__(self, config):
        self.config = config
        self.device_id = config.getint('device_id')
        self.ble_manager = None
        self.data = {}
        self.sections = []
        self.section_index = 0
        self.read_done_event = None
        self.read_error = False
        self.connected = False
        self._lock = asyncio.Lock()
        logging.info(f"Init {self.__class__.__name__}: {self.config['alias']} => {self.config['mac_addr']}")

    async def connect(self):
        async with self._lock:
            if self.ble_manager and getattr(self.ble_manager, 'client', None) and getattr(self.ble_manager.client, 'is_connected', False):
                self.connected = True
                return
            self.connected = False
            self.ble_manager = BLEManager(
                mac_address=self.config['mac_addr'], alias=self.config['alias'],
                on_data=self._on_data_received,
                notify_char_uuid=NOTIFY_CHAR_UUID, write_char_uuid=WRITE_CHAR_UUID,
                write_service_uuid=WRITE_SERVICE_UUID
            )
            await self.ble_manager.connect()
            if self.ble_manager.client and getattr(self.ble_manager.client, 'is_connected', False):
                self.connected = True
                return
            raise Exception("Connect error")

    async def read(self):
        self.read_done_event = asyncio.Event()
        async with self._lock:
            self.section_index = 0
            self.data = {}
            await self._read_section()
            await asyncio.wait_for(self.read_done_event.wait(), READ_TIMEOUT)
            if self.read_error:
                await self._disconnect_internal()
                raise Exception("Read error")
            return self.data

    async def _disconnect_internal(self):
        if self.ble_manager:
            await self.ble_manager.disconnect()
            self.ble_manager = None
        self.connected = False

    async def disconnect(self):
        async with self._lock:
            await self._disconnect_internal()

    async def _on_data_received(self, response):
        try:
            operation = bytes_to_int(response, 1, 1)

            if operation == READ_SUCCESS or operation == READ_ERROR:
                if (operation == READ_SUCCESS and
                    self.section_index < len(self.sections) and
                    self.sections[self.section_index]['parser'] is not None and
                    self.sections[self.section_index]['words'] * 2 + 5 == len(response)):
                    logging.debug("on_data_received: read operation success")
                    self._safe_parser(self.sections[self.section_index]['parser'], response)
                else:
                    logging.warning(f"on_data_received: read operation failed: {response.hex()}")

                if self.section_index >= len(self.sections) - 1:
                    self._on_read_complete()
                else:
                    self.section_index += 1
                    await asyncio.sleep(0.5)
                    await self._read_section()
            else:
                logging.warning(f"on_data_received: unknown operation={operation}")
        except Exception as e:
            logging.error(f"Error in on_data_received: {e}")
            self._on_read_failed()

    async def _read_section(self):
        try:
            index = self.section_index
            request = self._create_read_request(
                self.device_id, 3,
                self.sections[index]['register'],
                self.sections[index]['words']
            )
            await self.ble_manager.characteristic_write_value(request)
        except Exception as e:
            logging.error(f"Error in _read_section: {e}")
            self._on_read_failed()

    def _on_read_complete(self):
        self.data['__device'] = self.config['alias']
        self.data['__client'] = self.__class__.__name__
        self.data['__name'] = self.config['name']
        self.read_error = False
        if self.read_done_event:
            self.read_done_event.set()

    def _on_read_failed(self):
        self.data = {}
        self.read_error = True
        if self.read_done_event:
            self.read_done_event.set()

    def _create_read_request(self, device_id, function, regAddr, readWrd):
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
        logging.debug(f"_create_read_request {regAddr} => {data}")
        return data

    def _safe_parser(self, parser, param):
        try:
            parser(param)
        except Exception as e:
            logging.error(f"Exception in parser: {e}")
            traceback.print_exc()
