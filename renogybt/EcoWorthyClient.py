import asyncio
import logging

from .BLEManager import BLEManager
from .Utils import bytes_to_int

WRITE_SERVICE_UUID = "0000ff00-0000-1000-8000-00805f9b34fb"
NOTIFY_CHAR_UUID = "0000ff01-0000-1000-8000-00805f9b34fb"
WRITE_CHAR_UUID = "0000ff02-0000-1000-8000-00805f9b34fb"
READ_TIMEOUT = 15
COMMAND_READ_BASIC = b'\xdd\xa5\x03\x00\xff\xfd\x77'
COMMAND_READ_CELLV = b'\xdd\xa5\x04\x00\xff\xfc\x77'
OPERATION_BASIC_INFO = 3
OPERATION_CELLV_INFO = 4
FRAME_HEADER = b'\xDD'[0]
FRAME_END = b'\x77'[0]


class EcoWorthyClient:
    def __init__(self, config):
        self.config = config
        self.ble_manager = None
        self.data = {}
        self.read_done_event = None
        self.read_error = False
        self.connected = False
        self._lock = asyncio.Lock()
        self.fetched_basics = False
        self.fetched_cellv = False
        self.frame = None
        logging.info(f"Init EcoWorthyClient: {self.config['alias']} => {self.config['mac_addr']}")

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
            self.data = {}
            self.fetched_basics = False
            self.fetched_cellv = False
            await self._fetch_next()
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
            frame_header = response[0]
            frame_end = response[-1]

            if frame_header != FRAME_HEADER and self.frame:
                self.frame += response
                logging.debug(f"Adding {len(response)} bytes to existing frame.")
            elif frame_header == FRAME_HEADER:
                self.frame = response
                logging.debug(f"Received new frame, length: {len(response)}")

            if frame_end == FRAME_END:
                operation = bytes_to_int(self.frame, 1, 1)
                data_length = bytes_to_int(self.frame, 3, 1)
                payload = self.frame[4:-3]

                if operation == OPERATION_BASIC_INFO:
                    data = {}
                    data['voltage'] = bytes_to_int(payload, 0, 2, signed=False, scale=0.01)
                    data['current'] = bytes_to_int(payload, 2, 2, signed=True, scale=0.01)
                    data['capacity_remaining'] = bytes_to_int(payload, 4, 2, signed=False, scale=0.01)
                    data['capacity'] = bytes_to_int(payload, 4, 2, signed=False, scale=0.01)
                    data['temperature'] = bytes_to_int(payload, 23, 2, signed=False, scale=0.1) - 273.1
                    data['power'] = data['voltage'] * data['current']
                    data['percentage'] = 0 if data['capacity'] == 0 else 100.0 * data['capacity_remaining'] / data['capacity']
                    self.data.update(data)
                    self.fetched_basics = True
                elif operation == OPERATION_CELLV_INFO:
                    data = {}
                    no_cells = int(data_length / 2)
                    for cell in range(1, no_cells + 1):
                        data[f'voltage_cell{cell}'] = bytes_to_int(payload, 2 * (cell - 1), 2, signed=False, scale=0.001)
                    self.data.update(data)
                    self.fetched_cellv = True
                else:
                    logging.warning(f"on_data_received: unknown operation={operation}")

                self.frame = None
                await self._fetch_next()
        except Exception as e:
            logging.error(f"Error in on_data_received: {e}")
            self._on_read_failed()

    async def _fetch_next(self):
        try:
            await asyncio.sleep(0.5)
            if not self.fetched_basics:
                await self.ble_manager.characteristic_write_bytes(COMMAND_READ_BASIC)
            elif not self.fetched_cellv and self.config.get("read_cellv"):
                await self.ble_manager.characteristic_write_bytes(COMMAND_READ_CELLV)
            else:
                self._on_read_complete()
        except Exception as e:
            logging.error(f"Error in _fetch_next: {e}")
            self._on_read_failed()

    def _on_read_complete(self):
        self.data['__device'] = self.config['alias']
        self.data['__client'] = 'EcoWorthyClient'
        self.data['__name'] = self.config['name']
        self.read_error = False
        if self.read_done_event:
            self.read_done_event.set()

    def _on_read_failed(self):
        self.data = {}
        self.read_error = True
        if self.read_done_event:
            self.read_done_event.set()

