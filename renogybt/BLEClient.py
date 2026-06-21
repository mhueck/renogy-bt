import logging
import struct
import time
import asyncio
from collections import deque
from bleak import BleakClient, BleakScanner

SERVICE_UUID = "0000ff10-0000-1000-8000-00805f9b34fb"
BATTERY_CHAR_UUID = "0000ff11-0000-1000-8000-00805f9b34fb"
CHARGER_CHAR_UUID = "0000ff12-0000-1000-8000-00805f9b34fb"
WEATHER_CHAR_UUID = "0000ff13-0000-1000-8000-00805f9b34fb"

HISTORY_SIZE = 70
HOUR_SECONDS = 3600


class BLEClient:
    def __init__(self, mac_addr=None, name="SolarBLE"):
        self.mac_addr = mac_addr
        self.name = name
        self.client = None
        self.running = False
        self.send_task = None
        self.pct_history = deque(maxlen=HISTORY_SIZE)
        self.battery_data = None
        self.charger_data = None
        self.weather_data = None

    async def start(self):
        self.running = True
        self.send_task = asyncio.create_task(self._send_loop())
        logging.info("BLE client started")

    async def stop(self):
        self.running = False
        if self.send_task:
            self.send_task.cancel()
            try:
                await self.send_task
            except asyncio.CancelledError:
                pass
            self.send_task = None
        if self.client:
            try:
                await self.client.disconnect()
            except Exception:
                pass
            self.client = None
        logging.info("BLE client stopped")

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

    def update_charger(self, pv_voltage, pv_current, alternator_voltage, alternator_current):
        solar_power = pv_voltage * pv_current
        alt_power = alternator_voltage * alternator_current

        self.charger_data = struct.pack(
            '<HH',
            int(round(solar_power * 10)),
            int(round(alt_power * 10)),
        )

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

    def _calc_pct_change_1h(self, now, current_pct):
        if not self.pct_history:
            return 0.0
        target_time = now - HOUR_SECONDS
        closest = min(self.pct_history, key=lambda entry: abs(entry[0] - target_time))
        if abs(closest[0] - target_time) > HOUR_SECONDS * 0.5:
            return 0.0
        return current_pct - closest[1]

    async def _send_loop(self):
        while self.running:
            try:
                if not self.client or not self.client.is_connected:
                    target_identifier = self.mac_addr if self.mac_addr else self.name
                    logging.info(f"Connecting to BLE server: {target_identifier}")
                    if self.mac_addr:
                        device = await BleakScanner.find_device_by_address(self.mac_addr, timeout=15.0)
                    else:
                        device = await BleakScanner.find_device_by_name(self.name, timeout=15.0)

                    if device:
                        self.client = BleakClient(device)
                        await self.client.connect(timeout=15.0)
                        logging.info(f"Connected to BLE server: {device}")
                    else:
                        logging.warning(f"BLE server device '{target_identifier}' not found")

                if self.client and self.client.is_connected:
                    if self.battery_data:
                        logging.info("Writing battery data to BLE server...")
                        await self.client.write_gatt_char(BATTERY_CHAR_UUID, self.battery_data, response=False)
                    if self.charger_data:
                        logging.info("Writing charger data to BLE server...")
                        await self.client.write_gatt_char(CHARGER_CHAR_UUID, self.charger_data, response=False)
                    if self.weather_data:
                        logging.info("Writing weather data to BLE server...")
                        await self.client.write_gatt_char(WEATHER_CHAR_UUID, self.weather_data, response=False)
            except Exception as e:
                logging.error(f"Error in BLEClient send loop: {e}")
                if self.client:
                    try:
                        await self.client.disconnect()
                    except Exception:
                        pass
                    self.client = None

            await asyncio.sleep(60.0)
