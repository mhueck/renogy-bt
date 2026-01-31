import logging
import struct
from typing import Dict
from renogybt.RenogyClient import RenogyClient
from .Utils import bytes_to_int, parse_temperature


REG_LAT_HIGH = 0    # Latitude high word
REG_LAT_LOW = 1     # Latitude low word  
REG_LON_HIGH = 2    # Longitude high word
REG_LON_LOW = 3     # Longitude low word
REG_ALTITUDE = 4    # Altitude (meters)
REG_SPEED = 5       # Speed (km/h)
REG_SATELLITES = 6  # Satellite count
REG_TEMPERATURE = 7  # Temperature (°C)
REG_HUMIDITY = 8     # Humidity (%)
REG_PRESSURE = 9     # Pressure (hPa)
REG_GAS_RESISTANCE = 10  # Gas resistance (Ohms)

class BleEspClient(RenogyClient):
    def __init__(self, config, on_data_callback=None, on_error_callback=None):
        super().__init__(config, on_data_callback=on_data_callback, on_error_callback=on_error_callback)
        self.sections = [
            # {'register': 12, 'words': 8, 'parser': self.parse_device_info},
            # {'register': 26, 'words': 1, 'parser': self.parse_device_address},
            {'register': 0, 'words': 11, 'parser': self.parse_data},
            # {'register': 288, 'words': 3, 'parser': self.parse_state},
            # {'register': 57348, 'words': 1, 'parser': self.parse_battery_type}
        ]

    @property
    def write_service_uuid(self):
        return "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
    @property
    def notify_char_uuid(self):
        return "beb5483e-36e1-4688-b7f5-ea07361b26a8"

    @property
    def write_char_uuid(self):
        return "1c95d5e3-d8f7-413a-bf3d-7a2e5d7be87e"

    def parse_data(self, bs):
        print(f"parse_data: {bs.hex()}")
        data = {}

        registers = self.decode_registers(bs[3:-2], start_register=0)
        # Reconstruct 32-bit latitude (scaled by 10^7)
        lat_high = registers.get(REG_LAT_HIGH, 0)
        lat_low = registers.get(REG_LAT_LOW, 0)
        lat_scaled = struct.unpack('>i', struct.pack('>HH', lat_high, lat_low))[0]
        data['lat'] = lat_scaled / 10000000.0
        
        # Reconstruct 32-bit longitude (scaled by 10^7)
        lon_high = registers.get(REG_LON_HIGH, 0)
        lon_low = registers.get(REG_LON_LOW, 0)
        lon_scaled = struct.unpack('>i', struct.pack('>HH', lon_high, lon_low))[0]
        data['lon'] = lon_scaled / 10000000.0
        
        # Decode altitude (16-bit signed, scaled by 1)
        alt_raw = registers.get(REG_ALTITUDE, 0)
        data["alt"] = struct.unpack('>h', struct.pack('>H', alt_raw))[0]  # Convert to signed
        
        # Decode speed (16-bit unsigned, scaled by 10)
        speed_raw = registers.get(REG_SPEED, 0)
        data["speed"] = speed_raw / 10.0
        
        # Decode satellite count (16-bit unsigned)
        satellites = registers.get(REG_SATELLITES, 0)
        data["satellites"] = satellites

        # Decode temperature (16-bit signed, scaled by 10)
        temp_raw = registers.get(REG_TEMPERATURE, 0)
        data["temperature"] = temp_raw / 100.0

        # Decode humidity (16-bit unsigned, scaled by 10)
        humidity_raw = registers.get(REG_HUMIDITY, 0)
        data["humidity"] = humidity_raw / 100.0

        # Decode pressure (16-bit unsigned, scaled by 10)
        pressure_raw = registers.get(REG_PRESSURE, 0)
        data["pressure"] = pressure_raw / 10.0

        # Decode gas resistance (32-bit unsigned)
        gas_raw = registers.get(REG_GAS_RESISTANCE, 0)
        data["gas_resistance"] = gas_raw

        self.data.update(data)

    def decode_registers(self, register_data: bytes, start_register: int = 0) -> Dict[int, int]:
        """
        Decode register values from MODBUS response data
        
        Args:
            register_data: Raw register data (without MODBUS header/CRC)
            start_register: Starting register address
            
        Returns:
            Dictionary mapping register addresses to values
        """
        registers = {}
        
        # Each register is 2 bytes, big-endian
        for i in range(0, len(register_data), 2):
            if i + 1 < len(register_data):
                reg_addr = start_register + (i // 2)
                # MODBUS uses big-endian byte order for register values
                reg_value = struct.unpack('>H', register_data[i:i+2])[0]
                registers[reg_addr] = reg_value
        
        return registers
    
