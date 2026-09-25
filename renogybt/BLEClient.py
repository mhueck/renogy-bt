import logging
import struct
import time
import asyncio
from collections import deque
from bleak import BleakClient, BleakScanner
from .BLEManager import BLEManager

SERVICE_UUID = "0000ff10-0000-1000-8000-00805f9b34fb"
BATTERY_CHAR_UUID = "0000ff11-0000-1000-8000-00805f9b34fb"
CHARGER_CHAR_UUID = "0000ff12-0000-1000-8000-00805f9b34fb"
WEATHER_CHAR_UUID = "0000ff13-0000-1000-8000-00805f9b34fb"
FRIDGE_CHAR_UUID = "0000ff14-0000-1000-8000-00805f9b34fb"

HISTORY_SIZE = 70
HOUR_SECONDS = 3600

# Fridge flag bits, see BLE_CLIENT_SPEC.md
FRIDGE_FLAG_COOLER_ON = 1 << 0
FRIDGE_FLAG_COMPARTMENT_0_ON = 1 << 1
FRIDGE_FLAG_COMPARTMENT_1_ON = 1 << 2
FRIDGE_FLAG_COMPRESSOR_ON = 1 << 3
FRIDGE_FLAG_DOOR_0_OPEN = 1 << 4
FRIDGE_FLAG_DOOR_1_OPEN = 1 << 5
FRIDGE_FLAG_ERROR = 1 << 6
FRIDGE_FLAG_ENERGY_WINDOW_FULL = 1 << 7


def _clamp(value, low, high):
    return max(low, min(high, int(value)))


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
        self.fridge_head = None
        self.fridge_updated_at = None
        self.available_chars = set()

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
        await self._safe_disconnect()
        logging.info("BLE client stopped")

    async def _safe_disconnect(self):
        if self.client:
            try:
                async with BLEManager.connection_lock:
                    if self.client:
                        logging.info("Disconnecting BLEClient from BLE server...")
                        await self.client.disconnect()
            except Exception as e:
                logging.warning(f"Error during BLEClient disconnect: {e}")
            finally:
                self.client = None
                self.available_chars = set()

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

    def update_fridge(self, fridge_data):
        """Pack the latest Dometic fridge reading for the display.

        The age field is appended at write time rather than here, so a stale
        cached payload still reports an honest age.
        """
        def temp(key):
            value = fridge_data.get(key)
            if value is None:
                return 0
            # Clamp like every other field here: a garbled or sentinel reading
            # from the fridge must not make struct.pack raise, because the
            # caller would mistake that for a dead fridge link and back off.
            return _clamp(round(value * 10), -32768, 32767)

        flags = 0
        if fridge_data.get('cooler_power'):
            flags |= FRIDGE_FLAG_COOLER_ON
        if fridge_data.get('compartment_power_0'):
            flags |= FRIDGE_FLAG_COMPARTMENT_0_ON
        if fridge_data.get('compartment_power_1'):
            flags |= FRIDGE_FLAG_COMPARTMENT_1_ON
        if fridge_data.get('compressor_power'):
            flags |= FRIDGE_FLAG_COMPRESSOR_ON
        if fridge_data.get('door_open_0'):
            flags |= FRIDGE_FLAG_DOOR_0_OPEN
        if fridge_data.get('door_open_1'):
            flags |= FRIDGE_FLAG_DOOR_1_OPEN
        if fridge_data.get('error_count'):
            flags |= FRIDGE_FLAG_ERROR
        if (fridge_data.get('energy_window_seconds') or 0) >= HOUR_SECONDS * 0.95:
            flags |= FRIDGE_FLAG_ENERGY_WINDOW_FULL

        power = fridge_data.get('power') or 0.0
        energy = fridge_data.get('energy_wh_1h') or 0.0
        voltage = fridge_data.get('voltage') or 0.0

        self.fridge_head = struct.pack(
            '<hhhhhHHBBB',
            temp('temperature_0'),
            temp('temperature_1'),
            temp('set_temperature_0'),
            temp('set_temperature_1'),
            _clamp(int(round(power * 10)), -32768, 32767),
            _clamp(int(round(energy * 10)), 0, 65535),
            _clamp(int(round(voltage * 100)), 0, 65535),
            flags,
            _clamp(fridge_data.get('compartment_count') or 0, 0, 255),
            _clamp(fridge_data.get('power_source_id'), 0, 255) if fridge_data.get('power_source_id') is not None else 255,
        )
        # Age must date from the fridge's last publish, not from this call, or a
        # cooler that has gone quiet over a live BLE link would look fresh.
        self.fridge_updated_at = time.time() - (fridge_data.get('seconds_since_update') or 0)

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

    def _cache_characteristics(self):
        """Note which characteristics this display actually exposes.

        An older display firmware will not have the fridge characteristic. A
        write to a missing characteristic raises before it ever reaches the
        radio, and the send loop treats any exception as a dead link, so
        without this check one absent characteristic would tear the connection
        down every cycle and take the battery and charger data with it.
        """
        self.available_chars = set()
        try:
            for service in self.client.services:
                for char in service.characteristics:
                    self.available_chars.add(char.uuid.lower())
        except Exception as e:
            logging.warning(f"Could not enumerate BLE server characteristics: {e}")
            return
        if FRIDGE_CHAR_UUID not in self.available_chars:
            logging.warning(
                "BLE display does not expose the fridge characteristic "
                f"({FRIDGE_CHAR_UUID}); fridge data will not be sent. Update the "
                "display firmware - see BLE_CLIENT_SPEC.md."
            )

    async def _write_char(self, uuid, payload, label):
        if self.available_chars and uuid.lower() not in self.available_chars:
            logging.debug(f"Skipping {label} write, characteristic not on this display")
            return
        logging.info(f"Writing {label} data to BLE server...")
        await self.client.write_gatt_char(uuid, payload, response=False)

    async def _send_loop(self):
        while self.running:
            try:
                if not self.client or not self.client.is_connected:
                    target_identifier = self.mac_addr if self.mac_addr else self.name
                    
                    async with BLEManager.connection_lock:
                        logging.info(f"Connecting to BLE server: {target_identifier}")
                        
                        if self.mac_addr:
                            device = await BleakScanner.find_device_by_address(self.mac_addr, timeout=10.0)
                        else:
                            device = await BleakScanner.find_device_by_name(self.name, timeout=10.0)

                        if device:
                            self.client = BleakClient(device)
                            await self.client.connect(timeout=15.0)
                            logging.info(f"Connected to BLE server: {device}")
                            self._cache_characteristics()
                        else:
                            logging.warning(f"BLE server device '{target_identifier}' not found")

                if self.client and self.client.is_connected:
                    if self.battery_data:
                        await self._write_char(BATTERY_CHAR_UUID, self.battery_data, 'battery')
                    if self.charger_data:
                        await self._write_char(CHARGER_CHAR_UUID, self.charger_data, 'charger')
                    if self.weather_data:
                        await self._write_char(WEATHER_CHAR_UUID, self.weather_data, 'weather')
                    if self.fridge_head:
                        # Age is stamped at write time, not pack time, so a
                        # cached payload still reports honestly.
                        age = int(min(65535, max(0, time.time() - self.fridge_updated_at)))
                        await self._write_char(
                            FRIDGE_CHAR_UUID, self.fridge_head + struct.pack('<H', age), 'fridge'
                        )
            except Exception as e:
                logging.error(f"Error in BLEClient send loop: {e}")
                await self._safe_disconnect()

            await asyncio.sleep(60.0)
