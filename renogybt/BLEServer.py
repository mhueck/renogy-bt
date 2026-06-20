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
    def __init__(self, name="SolarBLE", adapter=None):
        self.name = name
        self.adapter = adapter
        self.server = None
        self.running = False
        self.pct_history = deque(maxlen=HISTORY_SIZE)
        self.battery_data = bytearray(9)
        self.charger_data = bytearray(4)
        self.weather_data = bytearray(28)


    async def start(self):
        self.server = BlessServer(name=self.name, adapter=self.adapter)
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
        if self.server and self.running:
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

    def update_weather(self, weather_json):
        current = weather_json.get('current', {})
        current_time = int(time.time() / 60)
        current_temp = current.get('temperature_2m', 0.0)
        current_code = current.get('weather_code', 0)

        daily = weather_json.get('daily', {})
        temp_mins = daily.get('temperature_2m_min', [0.0, 0.0, 0.0])
        temp_maxs = daily.get('temperature_2m_max', [0.0, 0.0, 0.0])
        weather_codes = daily.get('weather_code', [0, 0, 0])
        precipitations = daily.get('precipitation_sum', [0.0, 0.0, 0.0])

        def get_element(lst, idx, default):
            if lst and idx < len(lst) and lst[idx] is not None:
                return lst[idx]
            return default

        d0_min = get_element(temp_mins, 0, 0.0)
        d0_max = get_element(temp_maxs, 0, 0.0)
        d0_code = get_element(weather_codes, 0, 0)
        d0_prec = get_element(precipitations, 0, 0.0)

        d1_min = get_element(temp_mins, 1, 0.0)
        d1_max = get_element(temp_maxs, 1, 0.0)
        d1_code = get_element(weather_codes, 1, 0)
        d1_prec = get_element(precipitations, 1, 0.0)

        d2_min = get_element(temp_mins, 2, 0.0)
        d2_max = get_element(temp_maxs, 2, 0.0)
        d2_code = get_element(weather_codes, 2, 0)
        d2_prec = get_element(precipitations, 2, 0.0)

        self.weather_data = struct.pack(
            '<IhBhhBHhhBHhhBH',
            current_time,
            int(round(current_temp * 10)),
            int(current_code),
            int(round(d0_min * 10)),
            int(round(d0_max * 10)),
            int(d0_code),
            int(round(d0_prec * 10)),
            int(round(d1_min * 10)),
            int(round(d1_max * 10)),
            int(d1_code),
            int(round(d1_prec * 10)),
            int(round(d2_min * 10)),
            int(round(d2_max * 10)),
            int(d2_code),
            int(round(d2_prec * 10)),
        )
        self.server.get_characteristic(WEATHER_CHAR_UUID).value = self.weather_data
        self.server.update_value(SERVICE_UUID, WEATHER_CHAR_UUID)

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
