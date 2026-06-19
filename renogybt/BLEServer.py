import logging
import struct
import time
from collections import deque

from bless import BlessServer, BlessGATTCharacteristic, GATTCharacteristicProperties, GATTAttributePermissions

SERVICE_UUID = "0000ff10-0000-1000-8000-00805f9b34fb"
BATTERY_CHAR_UUID = "0000ff11-0000-1000-8000-00805f9b34fb"
CHARGER_CHAR_UUID = "0000ff12-0000-1000-8000-00805f9b34fb"
WEATHER_CHAR_UUID = "0000ff13-0000-1000-8000-00805f9b34fb"

HISTORY_SIZE = 70
HOUR_SECONDS = 3600


class BLEServer:
    def __init__(self, name="SolarBLE"):
        self.name = name
        self.server = None
        self.running = False
        self.pct_history = deque(maxlen=HISTORY_SIZE)
        self.battery_data = bytearray(9)
        self.charger_data = bytearray(4)
        self.weather_data = bytearray(0)

    async def start(self):
        self.server = BlessServer(name=self.name)
        self.server.on_read = self._on_read

        await self.server.add_new_service(SERVICE_UUID)

        await self.server.add_new_characteristic(
            SERVICE_UUID, BATTERY_CHAR_UUID, GATTCharacteristicProperties.read,
            self.battery_data, GATTAttributePermissions.readable
        )
        await self.server.add_new_characteristic(
            SERVICE_UUID, CHARGER_CHAR_UUID, GATTCharacteristicProperties.read,
            self.charger_data, GATTAttributePermissions.readable
        )
        await self.server.add_new_characteristic(
            SERVICE_UUID, WEATHER_CHAR_UUID, GATTCharacteristicProperties.read,
            self.weather_data, GATTAttributePermissions.readable
        )

        await self.server.start()
        self.running = True
        logging.info(f"BLE server '{self.name}' started")

    async def stop(self):
        if self.server:
            await self.server.stop()
            self.running = False
            logging.info("BLE server stopped")

    def update_battery(self, percentage, power, voltage, temperature):
        now = time.time()
        self.pct_history.append((now, percentage))
        pct_change = self._calc_pct_change_1h(now, percentage)

        self.battery_data = struct.pack(
            '<BhHhh',
            int(round(percentage)),
            int(round(pct_change * 10)),
            int(round(voltage * 100)),
            int(round(power * 10)),
            int(round(temperature * 10)),
        )
        self.server.get_characteristic(BATTERY_CHAR_UUID).value = self.battery_data
        self.server.update_value(SERVICE_UUID, BATTERY_CHAR_UUID)

    def update_charger(self, pv_voltage, pv_current, alternator_voltage, alternator_current):
        solar_power = pv_voltage * pv_current
        alt_power = alternator_voltage * alternator_current

        self.charger_data = struct.pack(
            '<HH',
            int(round(solar_power * 10)),
            int(round(alt_power * 10)),
        )
        self.server.get_characteristic(CHARGER_CHAR_UUID).value = self.charger_data
        self.server.update_value(SERVICE_UUID, CHARGER_CHAR_UUID)

    def _on_read(self, characteristic: BlessGATTCharacteristic, **kwargs):
        return characteristic.value

    def _calc_pct_change_1h(self, now, current_pct):
        if not self.pct_history:
            return 0.0
        target_time = now - HOUR_SECONDS
        closest = min(self.pct_history, key=lambda entry: abs(entry[0] - target_time))
        if abs(closest[0] - target_time) > HOUR_SECONDS * 0.5:
            return 0.0
        return current_pct - closest[1]
