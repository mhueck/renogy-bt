import asyncio
import configparser
import logging
import traceback
from .BLEManager import BLEManager
from .Utils import bytes_to_int, crc16_modbus, int_to_bytes
from abc import ABC, abstractmethod

# Base class that works with all Renogy family devices
# Should be extended by each client with its own parsers and section definitions
# Section example: {'register': 5000, 'words': 8, 'parser': self.parser_func}

WRITE_SERVICE_UUID = "0000ffd0-0000-1000-8000-00805f9b34fb"
NOTIFY_CHAR_UUID = "0000fff1-0000-1000-8000-00805f9b34fb"
WRITE_CHAR_UUID = "0000ffd1-0000-1000-8000-00805f9b34fb"
READ_TIMEOUT = 15
READ_SUCCESS = 3
READ_ERROR = 131


class BaseClient(ABC):
    def __init__(self, config, on_data_callback=None, on_error_callback=None):
        self.on_data_callback = on_data_callback
        self.on_error_callback = on_error_callback
        self.config: configparser.ConfigParser = config
        self.ble_manager = None
        self.data = {}
        self.read_done_event = None
        self.read_error = False
        logging.info(f"Init {self.__class__.__name__}: {self.config['alias']} => {self.config['mac_addr']}")

    @abstractmethod
    async def start_read(self):
        pass

    @abstractmethod
    async def on_data_received(self, response):
        pass

    @property
    @abstractmethod
    def write_service_uuid(self):
        pass

    @property
    @abstractmethod
    def notify_char_uuid(self):
        pass

    @property
    @abstractmethod
    def write_char_uuid(self):
        pass

    async def connect(self):
        self.ble_manager = BLEManager(mac_address=self.config['mac_addr'], alias=self.config['alias'], on_data=self.on_data_received,
                                      on_connect_fail=self.__on_connect_fail, notify_char_uuid=self.notify_char_uuid, write_char_uuid=self.write_char_uuid, write_service_uuid=self.write_service_uuid)

        await self.ble_manager.connect()
        if self.ble_manager.client and self.ble_manager.client.is_connected:
            return
        raise Exception("Connect error")

    async def read(self):
        self.read_done_event = asyncio.Event()
        await self.read_next()
        await asyncio.wait_for(self.read_done_event.wait(), READ_TIMEOUT)
        if self.read_error:
            raise Exception("Some read error occurred")

    def on_read_complete(self):
        self.data['__device'] = self.config['alias']
        self.data['__client'] = self.__class__.__name__
        self.__safe_callback(self.on_data_callback, self.data)
        self.data = {}
        self.read_error = False
        if self.read_done_event:
            self.read_done_event.set()

    def on_read_failed(self):
        self.__safe_callback(self.on_error_callback)
        self.data = {}
        self.read_error = True
        if self.read_done_event:
            self.read_done_event.set()

    async def disconnect(self):
        if self.ble_manager:
            await self.ble_manager.disconnect()
    
    def __on_connect_fail(self, error):
        logging.error(f"Connection failed: {error}")
        self.__safe_callback(self.on_error_callback, error)
        raise RuntimeError(f"Connection failed: {error}")

    def __safe_callback(self, calback, param):
        if calback is not None:
            try:
                calback(self, param)
            except Exception as e:
                logging.exception(f"__safe_callback => exception in callback! {e}")

