import asyncio
import configparser
import logging
import traceback

from renogybt.BaseClient import BaseClient
from .BLEManager import BLEManager
from .Utils import bytes_to_int, crc16_modbus, format_temperature, int_to_bytes


WRITE_SERVICE_UUID = "0000ff00-0000-1000-8000-00805f9b34fb"
NOTIFY_CHAR_UUID = "0000ff01-0000-1000-8000-00805f9b34fb"
WRITE_CHAR_UUID = "0000ff02-0000-1000-8000-00805f9b34fb"
STATUS_READ_SUCCESS = 0
COMMAND_READ_BASIC = b'\xdd\xa5\x03\x00\xff\xfd\x77'
COMMAND_READ_CELLV = b'\xdd\xa5\x04\x00\xff\xfc\x77'
OPERATION_BASIC_INFO = 3
OPERATION_CELLV_INFO = 4
FRAME_HEADER = b'\xDD'[0]
FRAME_END = b'\x77'[0]


class EcoWorthyClient(BaseClient):
    def __init__(self, config, on_data_callback=None, on_error_callback=None):
        super().__init__(config, on_data_callback=on_data_callback, on_error_callback=on_error_callback)

        self.fetched_basics = False
        self.fetched_cellv = False
        self.frame = None
        logging.info(f"Init {self.__class__.__name__}: {self.config['alias']} => {self.config['mac_addr']}")

    @property
    def write_service_uuid(self):
        return WRITE_SERVICE_UUID

    @property
    def notify_char_uuid(self):
        return NOTIFY_CHAR_UUID

    @property
    def write_char_uuid(self):
        return WRITE_CHAR_UUID

    async def start_read(self):
        self.section_index = 0
        self.data = {}
        await self.read_section()

    async def on_data_received(self, response):
        try:
            frame_len = len(response)
            frame_header = response[0]
            frame_end = response[-1]

            if frame_header != FRAME_HEADER and self.frame:
                self.frame += response
                logging.debug(f"Adding {frame_len} bytes to existing frame.")
            elif frame_header == FRAME_HEADER:
                operation = bytes_to_int(response, 1, 1)
                status = bytes_to_int(response, 2, 1)
                data_length = bytes_to_int(response, 3, 1)
                self.frame = response
                logging.debug(f"Received new frame, frame header: {frame_header}, operation: {operation}, status: {status}, data length: {data_length}, frame length: {frame_len}")

            if frame_end == FRAME_END:
                operation = bytes_to_int(self.frame, 1, 1)
                data_length = bytes_to_int(self.frame, 3, 1)
                payload = self.frame[4:-3]
                logging.debug(f"Payload size is {len(payload)}, expecting {data_length}")
                if operation == OPERATION_BASIC_INFO:

                    data = {}
                    data['voltage'] = bytes_to_int(payload, 0, 2, signed=False, scale=0.01)
                    data['current'] = bytes_to_int(payload, 2, 2, signed=True, scale=0.01)
                    data['capacity_remaining'] = bytes_to_int(payload, 4, 2, signed=False, scale=0.01)
                    data['capacity'] = bytes_to_int(payload, 4, 2, signed=False, scale=0.01)
                    data['temperature'] = bytes_to_int(payload, 23, 2, signed=False, scale=0.1) - 273.1
                    # temp_unit = self.config['data']['temperature_unit'].strip()
                    # if temp_unit == "F":
                    #    data['temperature'] = format_temperature(data['temperature'])
                    data['power'] = data['voltage'] * data['current']
                    data['percentage'] = 0 if data['capacity'] == 0 else 100.0 * data['capacity_remaining'] / data['capacity']

                    self.data.update(data)
                    self.fetched_basics = True
                elif operation == OPERATION_CELLV_INFO:

                    data = {}
                    no_cells = int(data_length / 2)
                    for cell in range(1, no_cells+1):
                        data[f'voltage_cell{cell}'] = bytes_to_int(payload, 2*(cell-1), 2, signed=False, scale=0.001)

                    self.data.update(data)
                    self.fetched_cellv = True
                else:
                    logging.warning("on_data_received: unknown operation={}".format(operation))
                self.frame = None
                await self.fetch_next()
            else:
                logging.debug("Still waiting for frame end.")
        except Exception as e:
            logging.error(f"Error in on_data_received: {e}")
            self.on_read_failed()

    async def fetch_next(self):
        try:
            await asyncio.sleep(0.5)

            if not self.fetched_basics:
                await self.ble_manager.characteristic_write_bytes(
                    COMMAND_READ_BASIC
                )
            elif not self.fetched_cellv and self.config.get("read_cellv"):
                await self.ble_manager.characteristic_write_bytes(
                    COMMAND_READ_CELLV
                )
            else:
                # all done!
                self.on_read_complete()
        except Exception as e:
            logging.error(f"Error in fetch_next: {e}")
            self.on_read_failed()
