import logging
import struct
from typing import Dict

from .BLEManager import BLEManager
from .RenogyClient import RenogyClient
from .Utils import bytes_to_int

WRITE_SERVICE_UUID = "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
NOTIFY_CHAR_UUID = "beb5483e-36e1-4688-b7f5-ea07361b26a8"
WRITE_CHAR_UUID = "1c95d5e3-d8f7-413a-bf3d-7a2e5d7be87e"

REG_LAT_HIGH = 0
REG_LAT_LOW = 1
REG_LON_HIGH = 2
REG_LON_LOW = 3
REG_ALTITUDE = 4
REG_SPEED = 5
REG_SATELLITES = 6
REG_TEMPERATURE = 7
REG_HUMIDITY = 8
REG_PRESSURE = 9
REG_GAS_RESISTANCE = 10


class BleEspClient(RenogyClient):
    def __init__(self, config):
        super().__init__(config)
        self.sections = [
            {'register': 0, 'words': 11, 'parser': self.parse_data},
        ]

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

    def parse_data(self, bs):
        data = {}
        registers = self._decode_registers(bs[3:-2], start_register=0)

        lat_high = registers.get(REG_LAT_HIGH, 0)
        lat_low = registers.get(REG_LAT_LOW, 0)
        lat_scaled = struct.unpack('>i', struct.pack('>HH', lat_high, lat_low))[0]
        data['lat'] = lat_scaled / 10000000.0

        lon_high = registers.get(REG_LON_HIGH, 0)
        lon_low = registers.get(REG_LON_LOW, 0)
        lon_scaled = struct.unpack('>i', struct.pack('>HH', lon_high, lon_low))[0]
        data['lon'] = lon_scaled / 10000000.0

        alt_raw = registers.get(REG_ALTITUDE, 0)
        data["alt"] = struct.unpack('>h', struct.pack('>H', alt_raw))[0]

        speed_raw = registers.get(REG_SPEED, 0)
        data["speed"] = speed_raw / 10.0

        data["satellites"] = registers.get(REG_SATELLITES, 0)

        temp_raw = registers.get(REG_TEMPERATURE, 0)
        data["temperature"] = temp_raw / 100.0

        humidity_raw = registers.get(REG_HUMIDITY, 0)
        data["humidity"] = humidity_raw / 100.0

        pressure_raw = registers.get(REG_PRESSURE, 0)
        data["pressure"] = pressure_raw / 10.0

        data["gas_resistance"] = registers.get(REG_GAS_RESISTANCE, 0)

        self.data.update(data)

    def _decode_registers(self, register_data: bytes, start_register: int = 0) -> Dict[int, int]:
        registers = {}
        for i in range(0, len(register_data), 2):
            if i + 1 < len(register_data):
                reg_addr = start_register + (i // 2)
                reg_value = struct.unpack('>H', register_data[i:i+2])[0]
                registers[reg_addr] = reg_value
        return registers
