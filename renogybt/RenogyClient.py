import asyncio
import configparser
import logging
import traceback

from renogybt.BaseClient import BaseClient
from .Utils import bytes_to_int, crc16_modbus, int_to_bytes

# Base class that works with all Renogy family devices
# Should be extended by each client with its own parsers and section definitions
# Section example: {'register': 5000, 'words': 8, 'parser': self.parser_func}

ALIAS_PREFIXES = ['BT-TH', 'RNGRBP', 'BTRIC']
WRITE_SERVICE_UUID = "0000ffd0-0000-1000-8000-00805f9b34fb"
NOTIFY_CHAR_UUID = "0000fff1-0000-1000-8000-00805f9b34fb"
WRITE_CHAR_UUID = "0000ffd1-0000-1000-8000-00805f9b34fb"
READ_SUCCESS = 3
READ_ERROR = 131


class RenogyClient(BaseClient):
    def __init__(self, config, on_data_callback=None, on_error_callback=None):
        super().__init__(config, on_data_callback=on_data_callback, on_error_callback=on_error_callback)
        self.device_id = self.config.getint('device_id')
        self.sections = []
        self.section_index = 0
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
                    self.on_read_complete()
                else:
                    self.section_index += 1
                    await asyncio.sleep(0.5)
                    await self.read_section()
            else:
                logging.warning("on_data_received: unknown operation={}".format(operation))
        except Exception as e:
            logging.error(f"Error in on_data_received: {e}")
            self.on_read_failed()

    async def read_section(self):
        try:
            index = self.section_index
            if self.device_id is None or len(self.sections) == 0:
                logging.error("BaseClient cannot be used directly")
                self.__on_error("BaseClient cannot be used directly")
                return
            request = self.create_generic_read_request(
                self.device_id, 3, 
                self.sections[index]['register'], 
                self.sections[index]['words']
            )
            await self.ble_manager.characteristic_write_value(request)
        except Exception as e:
            logging.error(f"Error in read_section: {e}")
            self.on_read_failed()

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


    def __safe_parser(self, parser, param):
        if parser is not None:
            try:
                parser(param)
            except Exception as e:
                logging.error(f"exception in parser! {e}")
                traceback.print_exc()
