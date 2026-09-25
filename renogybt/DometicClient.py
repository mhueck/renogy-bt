import asyncio
import logging
import struct
import time
from collections import deque

from bleak import BleakClient, BleakScanner

from .BLEManager import BLEManager

# Dometic coolers speak one of two generations of the "DDM" pub/sub protocol.
# Which one is decided by the firmware, not the model badge, so we detect it
# from whichever GATT service the device actually exposes.
#   DDM1 => CFX3
#   DDM2 => CFX2, CFX5 (firmware ids MC1/MC2/MC3)
DDM1_SERVICE_UUID = "537a0300-0995-481f-926c-1604e23fd515"
DDM1_WRITE_UUID = "537a0301-0995-481f-926c-1604e23fd515"
DDM1_NOTIFY_UUID = "537a0302-0995-481f-926c-1604e23fd515"

DDM2_SERVICE_UUID = "537a0400-0995-481f-926c-1604e23fd515"
DDM2_WRITE_UUID = "537a0401-0995-481f-926c-1604e23fd515"
DDM2_NOTIFY_UUID = "537a0402-0995-481f-926c-1604e23fd515"

# DDM1 action bytes
D1_PUB = 0x00
D1_SUB = 0x01
D1_PING = 0x02
D1_HELLO = 0x03
D1_ACK = 0x04
D1_NAK = 0x05
D1_NOP = 0x06

# DDM2 action bytes (note: no PING, and publishes must NOT be acked)
D2_HELLO = 0x03
D2_ACK = 0x04
D2_NAK = 0x05
D2_NOP = 0x06
D2_PUBLISH = 0x10
D2_SET = 0x11
D2_SUBSCRIBE = 0x12
D2_FRAGMENT = 0x14

CONNECT_TIMEOUT = 25
DISCOVERY_TIMEOUT = 10
HANDSHAKE_TIMEOUT = 6
WRITE_SETTLE = 0.2
D1_IDLE_PING_SECONDS = 3.0
# How long to wait after subscribing for the cooler's first publish. Without
# this, connect() would return before any data arrived and the first read()
# would fail, tearing down a perfectly good connection.
FIRST_DATA_TIMEOUT = 20.0
# Floor on the spacing of energy samples. The cooler can publish voltage and
# current far more often than is useful here, and the integration window would
# otherwise grow without bound.
ENERGY_MIN_SAMPLE_INTERVAL = 5.0
# If the cooler stops publishing for this long while the BLE link is still up,
# force a reconnect rather than keep serving stale readings.
MAX_SILENCE_SECONDS = 900.0

# A raw int16 of 0x8000 is the "no sample" sentinel in DDM1 history and
# temperature topics, surfacing as -3276.8 once scaled.
D1_NO_VALUE_RAW = -32768
D1_NO_VALUE = -3276.8
# Each slot of the DDM1 hour history averages the current over 10 minutes.
D1_HISTORY_BUCKET_SECONDS = 600

ENERGY_WINDOW_SECONDS = 3600
# Ignore gaps longer than this when integrating energy: we genuinely do not
# know what the fridge drew while we were disconnected, so do not invent it.
ENERGY_MAX_GAP_SECONDS = 300

# Value 2 is named differently by the two sources: the DDM2 parameter
# dictionary calls it Battery, while the DDM1 references call it Solar and
# concede the value was never actually observed on hardware.
POWER_SOURCES = {'ddm1': {0: 'ac', 1: 'dc', 2: 'solar'},
                 'ddm2': {0: 'ac', 1: 'dc', 2: 'battery'}}
BATTERY_PROTECTION_LEVELS = {0: 'low', 1: 'medium', 2: 'high'}

# --- DDM1 topic table (subset we need), keyed by the 4 param bytes -----------
# name, decoder key
D1_TOPICS = {
    (1, 0, 0, 129): ('subscribe_app_sz', 'empty'),
    (2, 0, 0, 129): ('subscribe_app_szi', 'empty'),
    (3, 0, 0, 129): ('subscribe_app_dz', 'empty'),
    (0, 193, 0, 0): ('serial_number', 'str'),
    (0, 128, 0, 1): ('compartment_count', 'u8'),
    (0, 129, 0, 1): ('icemaker_count', 'u8'),
    (0, 0, 1, 1): ('compartment_power_0', 'bool'),
    (16, 0, 1, 1): ('compartment_power_1', 'bool'),
    (0, 1, 1, 1): ('temperature_0', 'decideg'),
    (16, 1, 1, 1): ('temperature_1', 'decideg'),
    (0, 2, 1, 1): ('set_temperature_0', 'decideg'),
    (16, 2, 1, 1): ('set_temperature_1', 'decideg'),
    # Not in the older 64-topic table lifted from the Mobile Cooling app; it
    # was identified on a CFX3 DZ75 as the zone selector (0=large, 1=small,
    # 2=both). Decode-only, deliberately not subscribed.
    (0, 4, 1, 1): ('zone_control_mode', 'u8'),
    (0, 8, 1, 1): ('door_open_0', 'bool'),
    (16, 8, 1, 1): ('door_open_1', 'bool'),
    (0, 0, 3, 1): ('cooler_power', 'bool'),
    (0, 1, 3, 1): ('voltage', 'decivolt'),
    (0, 2, 3, 1): ('battery_protection_level', 'u8'),
    # From the 86-topic table in the newer Dometic Power app, and observed
    # publishing on a real CFX3. Absent from the older 64-topic table that the
    # other reference implementations use.
    (0, 3, 3, 1): ('compressor_power', 'bool'),
    (0, 5, 3, 1): ('power_source_id', 'u8'),
    (0, 6, 3, 1): ('icemaker_power', 'bool'),
    (0, 64, 3, 1): ('dc_current_history_hour', 'history'),
    (0, 0, 6, 1): ('device_name', 'str'),
}

# Topics we explicitly subscribe to on DDM1, on top of the bulk group subscribe.
D1_SUBSCRIBE = [
    (0, 128, 0, 1), (0, 0, 1, 1), (16, 0, 1, 1), (0, 1, 1, 1), (16, 1, 1, 1),
    (0, 2, 1, 1), (16, 2, 1, 1), (0, 8, 1, 1), (16, 8, 1, 1), (0, 0, 3, 1),
    (0, 1, 3, 1), (0, 2, 3, 1), (0, 3, 3, 1), (0, 5, 3, 1), (0, 64, 3, 1),
    (0, 0, 6, 1), (0, 193, 0, 0),
]

D1_BULK_SUBSCRIBE = {
    'sz': (1, 0, 0, 129),
    'szi': (2, 0, 0, 129),
    'dz': (3, 0, 0, 129),
}

# --- DDM2 topic table -------------------------------------------------------
# On DDM2 every scalar is a little-endian int32 and physical units are scaled
# by 1000. Per-compartment values arrive as an int32 array, one element per
# compartment, instead of DDM1's one-topic-per-compartment scheme.
def _mccc(param):
    """Topic in the Mobile Cooling Controller class (0x1A)."""
    return (param, 0x00, 0x00, 0x1A)


D2_TOPICS = {
    _mccc(0x01): ('product_type_id', 'i32'),
    _mccc(0x02): ('compartment_count', 'i32'),
    _mccc(0x03): ('compartment_power', 'bool_array'),
    _mccc(0x04): ('temperature', 'milli_array'),
    _mccc(0x05): ('set_temperature', 'milli_array'),
    _mccc(0x06): ('active_compartment', 'i32'),
    _mccc(0x07): ('door_open', 'bool_array'),
    _mccc(0x0B): ('cooler_power', 'bool'),
    _mccc(0x0C): ('voltage', 'milli'),
    _mccc(0x0D): ('battery_protection_level', 'i32'),
    _mccc(0x0E): ('compressor_power', 'bool'),
    _mccc(0x0F): ('current', 'milli'),
    _mccc(0x10): ('power_source_id', 'i32'),
    _mccc(0x11): ('icemaker_power', 'bool'),
    _mccc(0x12): ('error_state', 'u16_array'),
    _mccc(0x13): ('serial_number', 'str'),
    _mccc(0x14): ('sku', 'str'),
    _mccc(0x15): ('firmware_version', 'str'),
    (0x01, 0x00, 0x00, 0x1C): ('product_name', 'str'),
    (0x03, 0x00, 0x00, 0x1C): ('cms_sku', 'str'),
    (0x07, 0x00, 0x01, 0x00): ('firmware_id', 'str'),
}

# DDM2 has no bulk-subscribe topic, so each one is requested individually.
D2_SUBSCRIBE_TOPICS = [
    _mccc(0x01), _mccc(0x02), _mccc(0x03), _mccc(0x04), _mccc(0x05),
    _mccc(0x07), _mccc(0x0B), _mccc(0x0C), _mccc(0x0D), _mccc(0x0E),
    _mccc(0x0F), _mccc(0x10), _mccc(0x12), _mccc(0x13), _mccc(0x15),
    (0x01, 0x00, 0x00, 0x1C), (0x03, 0x00, 0x00, 0x1C),
    (0x07, 0x00, 0x01, 0x00),
]

D2_PRODUCT_TYPES = {
    0: 'unconfigured', 1: 'single_zone', 2: 'single_zone_icemaker',
    3: 'dual_zone', 4: 'deli_box',
}


class EnergyWindow:
    """Rolling watt-hour total over a trailing window, by trapezoidal integration.

    The fridge exposes no energy counter of any kind, so watt-hours have to be
    accumulated here from the power samples as they arrive.
    """

    def __init__(self, window_seconds=ENERGY_WINDOW_SECONDS, max_gap_seconds=ENERGY_MAX_GAP_SECONDS):
        self.window_seconds = window_seconds
        self.max_gap_seconds = max_gap_seconds
        self.samples = deque()

    def add(self, timestamp, watts):
        if self.samples and timestamp <= self.samples[-1][0]:
            return
        self.samples.append((timestamp, watts))
        self._prune(timestamp)

    def reset(self):
        self.samples.clear()

    def _prune(self, now):
        start = now - self.window_seconds
        # Keep one sample older than the window so the leading edge can be
        # interpolated rather than truncated.
        while len(self.samples) >= 2 and self.samples[1][0] <= start:
            self.samples.popleft()

    def watt_hours(self, now=None):
        now = time.time() if now is None else now
        start = now - self.window_seconds
        total_watt_seconds = 0.0
        pairs = zip(self.samples, list(self.samples)[1:])
        for (t0, w0), (t1, w1) in pairs:
            if t1 <= start:
                continue
            span = t1 - t0
            if span <= 0 or span > self.max_gap_seconds:
                continue
            left_t, left_w = t0, w0
            if t0 < start:
                left_t = start
                left_w = w0 + (w1 - w0) * ((start - t0) / span)
            total_watt_seconds += (left_w + w1) / 2.0 * (t1 - left_t)
        return total_watt_seconds / 3600.0

    def coverage_seconds(self, now=None):
        """How much of the window is actually backed by samples."""
        now = time.time() if now is None else now
        start = now - self.window_seconds
        covered = 0.0
        pairs = zip(self.samples, list(self.samples)[1:])
        for (t0, _), (t1, _) in pairs:
            if t1 <= start:
                continue
            span = t1 - t0
            if span <= 0 or span > self.max_gap_seconds:
                continue
            covered += t1 - max(t0, start)
        return covered


class DometicClient:
    """Reads a Dometic CFX cooler over BLE.

    Unlike the Renogy/EcoWorthy clients this holds a persistent connection:
    the DDM protocol is push based, so we subscribe once and then consume
    notifications. Re-subscribing periodically is known to provoke a
    communication fault on the cooler, so read() only snapshots what has
    already arrived.
    """

    def __init__(self, config):
        self.config = config
        self.client = None
        self.protocol = None
        self.write_uuid = None
        self.notify_uuid = None
        self.values = {}
        self.data = {}
        self.connected = False
        self._lock = asyncio.Lock()
        self._write_lock = asyncio.Lock()
        self._ack_event = asyncio.Event()
        self._first_data_event = asyncio.Event()
        self._energy = EnergyWindow()
        self._last_frame_at = 0.0
        self._last_publish_at = 0.0
        self._last_energy_sample_at = 0.0
        self._heartbeat_task = None
        self.max_silence = float(self.config.get('max_silence', MAX_SILENCE_SECONDS) or MAX_SILENCE_SECONDS)
        logging.info(f"Init DometicClient: {self.config['alias']} => {self.config['mac_addr']}")

    # --- lifecycle ---------------------------------------------------------

    async def connect(self):
        async with self._lock:
            if self.client and self.client.is_connected:
                self.connected = True
                return
            await self._teardown()
            self._first_data_event.clear()
            self._ack_event.clear()
            async with BLEManager.connection_lock:
                await self._connect_locked()
            await self._subscribe()
            try:
                await asyncio.wait_for(self._first_data_event.wait(), FIRST_DATA_TIMEOUT)
            except asyncio.TimeoutError:
                raise Exception(
                    "Dometic subscribed but published nothing. The cooler is "
                    "probably not bonded, or another client (the Dometic app) "
                    "holds its single BLE connection."
                )
            self.connected = True

    async def _connect_locked(self):
        mac = self.config['mac_addr']
        # Connect by address first. A bonded cooler advertises very sparsely, so
        # scanning usually fails anyway, and this whole method runs under the
        # shared BLE lock - a 10s scan here stalls the other devices' loops.
        try:
            self.client = BleakClient(mac)
            await self.client.connect(timeout=CONNECT_TIMEOUT)
        except Exception as e:
            logging.debug(f"Dometic connect by address failed ({e}), scanning")
            self.client = None
            device = await BleakScanner.find_device_by_address(mac, timeout=DISCOVERY_TIMEOUT)
            if device is None:
                raise Exception(
                    f"Dometic {mac} not found. Check the address, and that the "
                    "cooler is powered and its Bluetooth is enabled."
                )
            self.client = BleakClient(device)
            await self.client.connect(timeout=CONNECT_TIMEOUT)

        if not self.client.is_connected:
            raise Exception(f"Cannot connect to Dometic {mac}")
        logging.info(f"Connected to Dometic {mac}")

        if self.config.getboolean('pair', fallback=True):
            try:
                await self.client.pair()
                logging.info("Dometic pairing/bond confirmed")
            except Exception as e:
                # Already bonded, or the backend does not implement pair().
                logging.debug(f"Dometic pair() skipped: {e}")

        self._detect_protocol()
        await self.client.start_notify(self.notify_uuid, self._on_notify)

    def _detect_protocol(self):
        available = {s.uuid.lower() for s in self.client.services}
        forced = (self.config.get('protocol', 'auto') or 'auto').strip().lower()
        if forced not in ('auto', 'ddm1', 'ddm2'):
            raise Exception(f"Invalid protocol '{forced}' in [fridge]; use auto, ddm1 or ddm2")

        services = {'ddm1': DDM1_SERVICE_UUID, 'ddm2': DDM2_SERVICE_UUID}
        present = [name for name, uuid in services.items() if uuid in available]

        if not present:
            raise Exception(
                "No Dometic DDM service found. The cooler exposes no GATT "
                "services until it is bonded - put it in Bluetooth PAIR mode "
                f"and retry. Services seen: {sorted(available)}"
            )

        if forced == 'auto':
            # Prefer DDM2 when both are somehow advertised; it is the newer
            # generation and carries a live current reading.
            self.protocol = 'ddm2' if 'ddm2' in present else 'ddm1'
        else:
            self.protocol = forced
            if forced not in present:
                logging.warning(
                    f"Configured protocol '{forced}' but the cooler advertises "
                    f"{present} - honouring the config, expect it to fail. Set "
                    "protocol = auto to detect it instead."
                )

        if self.protocol == 'ddm1':
            self.write_uuid, self.notify_uuid = DDM1_WRITE_UUID, DDM1_NOTIFY_UUID
        else:
            self.write_uuid, self.notify_uuid = DDM2_WRITE_UUID, DDM2_NOTIFY_UUID
        logging.info(f"Dometic protocol detected: {self.protocol}")

    async def _subscribe(self):
        if self.protocol == 'ddm1':
            await self._ddm1_handshake()
            group = (self.config.get('product_type', 'dz') or 'dz').strip().lower()
            bulk = D1_BULK_SUBSCRIBE.get(group)
            if bulk:
                await self._write(bytes([D1_SUB]) + bytes(bulk))
            else:
                logging.warning(f"Unknown product_type '{group}', skipping bulk subscribe")
            for topic in D1_SUBSCRIBE:
                await self._write(bytes([D1_SUB]) + bytes(topic))
            self._heartbeat_task = asyncio.create_task(self._ddm1_heartbeat())
        else:
            for topic in D2_SUBSCRIBE_TOPICS:
                await self._write(bytes([D2_SUBSCRIBE]) + bytes(topic))

    async def _ddm1_handshake(self):
        self._ack_event.clear()
        await self._write(bytes([D1_PING]))
        try:
            await asyncio.wait_for(self._ack_event.wait(), HANDSHAKE_TIMEOUT)
        except asyncio.TimeoutError:
            raise Exception("Dometic DDM1 handshake timed out (no ACK to PING)")

    async def _ddm1_heartbeat(self):
        # The cooler stops publishing if the link goes quiet, so nudge it.
        try:
            while self.client and self.client.is_connected:
                await asyncio.sleep(1.0)
                if time.time() - self._last_frame_at > D1_IDLE_PING_SECONDS:
                    try:
                        await self._write(bytes([D1_PING]))
                    except Exception as e:
                        logging.debug(f"Dometic heartbeat write failed: {e}")
                        return
        except asyncio.CancelledError:
            raise

    async def _teardown(self):
        if self._heartbeat_task:
            task, self._heartbeat_task = self._heartbeat_task, None
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                # Only swallow the child's own cancellation. If this coroutine
                # is itself being cancelled the exception must propagate, or
                # shutdown hangs waiting on a task that will never finish.
                if not task.cancelled():
                    raise
            except Exception as e:
                logging.debug(f"Dometic heartbeat ended with {e}")
        # Voltage and current are paired when computing power, so drop them on
        # teardown: after a reconnect the re-subscribe burst publishes them one
        # at a time, and a fresh voltage must not be multiplied by a current
        # measured before the dropout.
        self.values.pop('voltage', None)
        self.values.pop('current', None)
        self._last_energy_sample_at = 0.0
        if self.client:
            try:
                if self.client.is_connected:
                    await self.client.disconnect()
            except Exception as e:
                logging.warning(f"Error disconnecting Dometic: {e}")
            finally:
                self.client = None
        self.connected = False
        self.protocol = None

    async def disconnect(self):
        async with self._lock:
            await self._teardown()

    # --- transport ---------------------------------------------------------

    async def _write(self, payload, settle=WRITE_SETTLE):
        async with self._write_lock:
            # The DDM write characteristic is write-with-response; the
            # reference implementations attribute communication faults to
            # fire-and-forget writes.
            await self.client.write_gatt_char(self.write_uuid, payload, response=True)
            if settle:
                await asyncio.sleep(settle)

    async def _ack(self):
        # Acks must not be rate limited: a DDM1 cooler dumps ~30 frames on a
        # bulk subscribe and pings every 2s, and it stops publishing until each
        # one is acknowledged. Settling here would stall the notify handler.
        await self._write(bytes([D1_ACK]), settle=0)

    async def _on_notify(self, _characteristic, data):
        try:
            if not data:
                return
            self._last_frame_at = time.time()
            if self.protocol == 'ddm1':
                await self._on_ddm1_frame(bytes(data))
            else:
                await self._on_ddm2_frame(bytes(data))
        except Exception as e:
            logging.error(f"Error handling Dometic frame: {e}")

    async def _on_ddm1_frame(self, frame):
        action = frame[0]
        if action == D1_ACK:
            self._ack_event.set()
            return
        if action == D1_NAK:
            logging.warning("Dometic returned NAK")
            return
        if action == D1_PUB:
            if len(frame) >= 5:
                self._store(tuple(frame[1:5]), frame[5:], D1_TOPICS)
            # Every publish must be acknowledged or the cooler goes quiet.
            await self._ack()
            return
        if action in (D1_PING, D1_SUB, D1_HELLO, D1_NOP):
            await self._ack()
            return
        logging.debug(f"Unhandled DDM1 action 0x{action:02X}")

    async def _on_ddm2_frame(self, frame):
        action = frame[0]
        if action == D2_NAK:
            logging.warning("Dometic returned NAK")
            return
        if action == D2_FRAGMENT:
            logging.debug("Ignoring DDM2 FRAGMENT frame")
            return
        if action == D2_PUBLISH:
            if len(frame) >= 5:
                self._store(tuple(frame[1:5]), frame[5:], D2_TOPICS)
            # DDM2 must not be acked.
            return
        if action in (D2_ACK, D2_HELLO, D2_NOP):
            return
        logging.debug(f"Unhandled DDM2 action 0x{action:02X}")

    def _store(self, topic, payload, table):
        entry = table.get(topic)
        if entry is None:
            logging.debug(f"Unknown Dometic topic {topic} payload={payload.hex()}")
            return
        name, kind = entry
        value = _decode(payload, kind)
        if value is None and kind != 'empty':
            return
        self.values[name] = value
        self._last_publish_at = time.time()
        self._first_data_event.set()
        if name in ('voltage', 'current', 'dc_current_history_hour'):
            self._sample_power()

    # --- derived values ----------------------------------------------------

    def _history_buckets(self):
        """The DDM1 hour history as amps per 10-minute bucket, newest first.

        Slots with no sample yet come back as None rather than the raw -3276.8
        sentinel.
        """
        history = self.values.get('dc_current_history_hour')
        if not history or len(history) < 8:
            return []
        return [None if v is None or v == D1_NO_VALUE else v for v in history[:7]]

    def _instant_current(self):
        if self.protocol == 'ddm2':
            return self.values.get('current')
        # DDM1 has no live current topic at all. The nearest thing is the newest
        # bucket of the fridge's own hour history, which is an average over 10
        # minutes and up to 10 minutes stale.
        for bucket in self._history_buckets():
            if bucket is not None:
                return bucket
        return None

    def _sample_power(self):
        # Only DDM2 is integrated locally. On DDM1 the "current" reading is a
        # 10-minute bucket average that only changes every 10 minutes, so
        # integrating it as though it were an instantaneous sample holds a
        # stale plateau and materially over-reports; that path uses the
        # fridge's own buckets instead. See _energy_last_hour.
        if self.protocol != 'ddm2':
            return
        voltage = self.values.get('voltage')
        current = self.values.get('current')
        if voltage is None or current is None:
            return
        # A monotonic clock, so an NTP correction or a manual clock change
        # cannot freeze or rewind the integration.
        now = time.monotonic()
        if now - self._last_energy_sample_at < ENERGY_MIN_SAMPLE_INTERVAL:
            return
        self._last_energy_sample_at = now
        self._energy.add(now, voltage * current)

    def _energy_last_hour(self):
        """Watt-hours over the trailing hour, and how much of it is real data.

        Returns (watt_hours, covered_seconds).
        """
        if self.protocol == 'ddm2':
            now = time.monotonic()
            return self._energy.watt_hours(now), self._energy.coverage_seconds(now)

        # DDM1: the fridge already measured this. Six 10-minute buckets are
        # exactly one hour, and each holds the average amps for its bucket, so
        # the energy is voltage x sum(buckets) x 10/60 hours.
        voltage = self.values.get('voltage')
        buckets = self._history_buckets()[:6]
        if voltage is None or not buckets:
            return 0.0, 0.0
        present = [b for b in buckets if b is not None]
        if not present:
            return 0.0, 0.0
        amp_hours = sum(present) * (D1_HISTORY_BUCKET_SECONDS / 3600.0)
        return voltage * amp_hours, len(present) * D1_HISTORY_BUCKET_SECONDS

    def _compartment(self, base, index):
        """Read a per-compartment value from either protocol's shape."""
        if self.protocol == 'ddm1':
            return self.values.get(f'{base}_{index}')
        array = self.values.get(base)
        if isinstance(array, list) and index < len(array):
            return array[index]
        return None

    async def read(self):
        if not self.client or not self.client.is_connected:
            self.connected = False
            raise Exception("Dometic not connected")

        # The cooler only publishes on change, so a fridge drawing a steady
        # current can go a long time without a new frame. Sample here too so
        # the energy integration stays continuous rather than treating the
        # quiet period as an unknown gap.
        self._sample_power()

        now = time.time()
        silence = now - self._last_publish_at if self._last_publish_at else None
        if silence is not None and silence > self.max_silence:
            # The link is nominally up but the cooler has gone quiet. Fail so
            # the loop reconnects and re-subscribes rather than serving stale
            # readings to the display indefinitely.
            raise Exception(f"Dometic silent for {int(silence)}s, forcing reconnect")

        energy_wh, energy_covered = self._energy_last_hour()
        voltage = self.values.get('voltage')
        current = self._instant_current()
        power = None if voltage is None or current is None else round(voltage * current, 2)

        data = {
            'protocol': self.protocol,
            'compartment_count': self.values.get('compartment_count'),
            'temperature_0': self._compartment('temperature', 0),
            'temperature_1': self._compartment('temperature', 1),
            'set_temperature_0': self._compartment('set_temperature', 0),
            'set_temperature_1': self._compartment('set_temperature', 1),
            'compartment_power_0': _as_int(self._compartment('compartment_power', 0)),
            'compartment_power_1': _as_int(self._compartment('compartment_power', 1)),
            'door_open_0': _as_int(self._compartment('door_open', 0)),
            'door_open_1': _as_int(self._compartment('door_open', 1)),
            'cooler_power': _as_int(self.values.get('cooler_power')),
            'compressor_power': _as_int(self.values.get('compressor_power')),
            'icemaker_power': _as_int(self.values.get('icemaker_power')),
            'voltage': voltage,
            'current': current,
            'power': power,
            'energy_wh_1h': round(energy_wh, 3),
            'energy_window_seconds': int(energy_covered),
            'battery_protection_level': self.values.get('battery_protection_level'),
            'seconds_since_update': None if not self._last_publish_at else int(now - self._last_publish_at),
        }

        source_id = self.values.get('power_source_id')
        data['power_source_id'] = source_id
        if source_id is not None:
            labels = POWER_SOURCES.get(self.protocol, POWER_SOURCES['ddm2'])
            data['power_source'] = labels.get(source_id, str(source_id))

        level = self.values.get('battery_protection_level')
        if level is not None:
            data['battery_protection'] = BATTERY_PROTECTION_LEVELS.get(level, str(level))

        errors = self.values.get('error_state')
        if isinstance(errors, list):
            data['error_count'] = sum(1 for e in errors if e)
        elif self.protocol == 'ddm1':
            data['error_count'] = None

        product_type_id = self.values.get('product_type_id')
        if product_type_id is not None:
            data['product_type'] = D2_PRODUCT_TYPES.get(product_type_id, str(product_type_id))

        for key in ('device_name', 'serial_number', 'firmware_version', 'firmware_id',
                    'product_name', 'cms_sku'):
            if self.values.get(key):
                data[key] = self.values[key]

        if not self._last_publish_at:
            raise Exception("Dometic connected but no data published yet")

        data['__device'] = self.config['alias']
        data['__client'] = 'DometicClient'
        data['__name'] = self.config['name']
        self.data = data
        return data


def _as_int(value):
    return None if value is None else int(bool(value))


def _decode(payload, kind):
    try:
        if kind == 'empty':
            return None
        if kind == 'bool':
            if len(payload) >= 4:
                return bool(struct.unpack('<i', payload[:4])[0])
            return bool(payload[0]) if payload else None
        if kind == 'u8':
            return payload[0] if payload else None
        if kind == 'i32':
            return struct.unpack('<i', payload[:4])[0] if len(payload) >= 4 else None
        if kind == 'milli':
            return round(struct.unpack('<i', payload[:4])[0] / 1000.0, 3) if len(payload) >= 4 else None
        if kind == 'decideg':
            if len(payload) < 2:
                return None
            raw = struct.unpack('<h', payload[:2])[0]
            # 0x8000 means "no reading", e.g. a powered-off compartment. Treat
            # it as absent rather than letting -3276.8 reach the display.
            return None if raw == D1_NO_VALUE_RAW else raw / 10.0
        if kind == 'decivolt':
            return struct.unpack('<H', payload[:2])[0] / 10.0 if len(payload) >= 2 else None
        if kind == 'str':
            end = payload.find(b'\x00')
            return payload[:end if end >= 0 else len(payload)].decode('utf-8', errors='replace')
        if kind == 'history':
            if len(payload) < 15:
                return None
            return [struct.unpack('<h', payload[i:i + 2])[0] / 10.0 for i in range(0, 14, 2)] + [payload[14]]
        if kind == 'milli_array':
            count = len(payload) // 4
            return [round(struct.unpack('<i', payload[i * 4:i * 4 + 4])[0] / 1000.0, 3) for i in range(count)]
        if kind == 'bool_array':
            count = len(payload) // 4
            return [bool(struct.unpack('<i', payload[i * 4:i * 4 + 4])[0]) for i in range(count)]
        if kind == 'u16_array':
            count = len(payload) // 2
            return [struct.unpack('<H', payload[i * 2:i * 2 + 2])[0] for i in range(count)]
    except Exception as e:
        logging.debug(f"Dometic decode failed ({kind}, {payload.hex()}): {e}")
    return None
