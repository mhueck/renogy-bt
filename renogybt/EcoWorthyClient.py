import asyncio
import configparser
import logging
import traceback
from .BLEManager import BLEManager
from .Utils import bytes_to_int, crc16_modbus, int_to_bytes


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
        self.loop = None
        self.active_operation = None
        self.payload = None
        logging.info(f"Init {self.__class__.__name__}: {self.config['device']['alias']} => {self.config['device']['mac_addr']}")

    def start(self):
        try:
            self.loop = asyncio.get_event_loop()
            self.loop.create_task(self.connect())
            self.future = self.loop.create_future()
            self.loop.run_until_complete(self.future)
        except Exception as e:
            self.__on_error(e)
        except KeyboardInterrupt:
            self.loop = None
            self.__on_error("KeyboardInterrupt")

    async def connect(self):
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
                self.stop()
            else:
                await self.ble_manager.connect()
                if self.ble_manager.client and self.ble_manager.client.is_connected: 
                    await self.fetch_next()

    async def disconnect(self):
        await self.ble_manager.disconnect()
        self.future.set_result('DONE')

    async def on_data_received(self, response):
        if self.read_timeout and not self.read_timeout.cancelled(): self.read_timeout.cancel()
        frame_len = len(response)
        frame_header = response[0]
        frame_end = response[-1]

        if frame_header != FRAME_HEADER and self.active_operation:
            self.payload += response
            logging.info(f"Adding {frame_len} bytes to existing frame for operation {self.active_operation}")
        elif frame_header == FRAME_HEADER:
            operation = bytes_to_int(response, 1, 1)
            status = bytes_to_int(response, 2, 1)
            data_length = bytes_to_int(response, 3, 1)
            self.payload = response[4:]
            self.active_operation = operation
            logging.info(f"Received new frame, frame header: {frame_header}, operation: {operation}, status: {status}, data length: {data_length}, frame length: {frame_len}")

        if frame_end == FRAME_END:
            if self.active_operation == OPERATION_BASIC_INFO:

                data = {}
                data['voltage'] = bytes_to_int(self.payload, 0, 2, signed=False, scale=0.01)
                data['current'] = bytes_to_int(self.payload, 2, 4, signed=True, scale=0.01)
                data['capacity_remaining'] = bytes_to_int(self.payload, 4, 6, signed=False, scale=0.01)
                data['capacity'] = bytes_to_int(self.payload, 4, 6, signed=False, scale=0.01)
                data['temperature'] = bytes_to_int(self.payload, 23, 25, signed=False, scale=0.1) - 273.1

                data['power'] = data['voltage'] * data['current']
                data['percentage'] = 0 if data['capacity'] == 0 else 100.0 * data['capacity_remaining'] / data['capacity']

                self.data.update(data)
                self.fetched_basics = True
                await self.fetch_next()
            elif self.active_operation == OPERATION_CELLV_INFO:

                data = {}
                no_cells = int(data_length / 2)
                for cell in range(1, no_cells+1):
                    data[f'voltage_cell{cell}'] = bytes_to_int(self.payload, 2*(cell-1), 2*cell, signed=False, scale=0.001)

                self.data.update(data)
                self.fetched_cellv = True
                await self.fetch_next()
            else:
                logging.warning("on_data_received: unknown operation={}".format(operation))
            self.active_operation = None
            self.payload = None
        else:
            logging.info("Still waiting for frame end.")

    async def fetch_next(self):
        await asyncio.sleep(0.5)

        if not self.fetched_basics:
            self.read_timeout = self.loop.call_later(READ_TIMEOUT, self.on_read_timeout)
            await self.ble_manager.characteristic_write_bytes(COMMAND_READ_BASIC)
        elif not self.fetched_cellv and self.config["data"].get("read_cellv"):
            self.read_timeout = self.loop.call_later(READ_TIMEOUT, self.on_read_timeout)
            await self.ble_manager.characteristic_write_bytes(COMMAND_READ_CELLV)
        else:
            # all done!
            self.__safe_callback(self.on_data_callback, self.data)
            # and reset in case this is running in a loop
            self.fetched_basics = False
            self.fetched_cellv = False
            self.data = {}
            await self.check_polling()

    def on_read_timeout(self):
        logging.error("on_read_timeout => Timed out! Please check your device_id!")
        self.stop()

    async def check_polling(self):
        if self.config['data'].getboolean('enable_polling'): 
            await asyncio.sleep(self.config['data'].getint('poll_interval'))
            await self.fetch_next()

    def __on_error(self, error = None):
        logging.error(f"Exception occured: {error}")
        self.__safe_callback(self.on_error_callback, error)
        self.stop()

    def __on_connect_fail(self, error):
        logging.error(f"Connection failed: {error}")
        self.__safe_callback(self.on_error_callback, error)
        self.stop()

    def stop(self):
        if self.read_timeout and not self.read_timeout.cancelled(): self.read_timeout.cancel()
        if self.loop is None:
            self.loop = asyncio.get_event_loop()
            self.loop.create_task(self.disconnect())
            self.future = self.loop.create_future()
            self.loop.run_until_complete(self.future)
        else:
            self.loop.create_task(self.disconnect())

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
