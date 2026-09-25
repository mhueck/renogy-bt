#!/usr/bin/env python3
"""Tests for the Dometic CFX fridge client.

No hardware and no network: synthetic DDM1 and DDM2 wire frames are fed through
the real notify handler, and the display payload is unpacked back out again.

    python3 tests/test_dometic.py
"""
import asyncio
import os
import struct
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from renogybt.BLEClient import (  # noqa: E402
    FRIDGE_CHAR_UUID, FRIDGE_FLAG_COMPRESSOR_ON, FRIDGE_FLAG_COOLER_ON,
    FRIDGE_FLAG_ENERGY_WINDOW_FULL, FRIDGE_FLAG_ERROR, BLEClient,
)
from renogybt.DometicClient import (  # noqa: E402
    D1_ACK, D1_PING, D1_PUB, D1_SUB, D1_SUBSCRIBE, D1_TOPICS,
    D2_NAK, D2_PUBLISH, D2_SUBSCRIBE, D2_SUBSCRIBE_TOPICS, D2_TOPICS,
    DDM1_NOTIFY_UUID, DDM1_SERVICE_UUID, DDM1_WRITE_UUID,
    DDM2_NOTIFY_UUID, DDM2_SERVICE_UUID, DDM2_WRITE_UUID,
    ENERGY_MIN_SAMPLE_INTERVAL, DometicClient, EnergyWindow, _decode,
)

FAILURES = []


def check(label, got, want):
    ok = got == want
    print(f"{'ok  ' if ok else 'FAIL'} {label}" + ('' if ok else f": {got!r} != {want!r}"))
    if not ok:
        FAILURES.append(label)


def section(title):
    print(f"\n--- {title} ---")


class FakeBleak:
    """Minimal stand-in for BleakClient that records writes."""

    def __init__(self, chars=None):
        self.is_connected = True
        self.writes = []
        self._chars = chars

    async def write_gatt_char(self, uuid, payload, response=False):
        assert response is True, "DDM writes must use write-with-response"
        self.writes.append(bytes(payload))


class Cfg(dict):
    def getboolean(self, key, fallback=None):
        return self.get(key, fallback)

    def get(self, key, fallback=None):
        return dict.get(self, key, fallback)


CFG = Cfg(mac_addr='AA:BB:CC:DD:EE:FF', alias='DOMETIC_CFX', name='DOMETIC')


def mccc(param):
    return (param, 0x00, 0x00, 0x1A)


def d1_pub(topic, payload=b''):
    return bytes([D1_PUB]) + bytes(topic) + payload


def d2_pub(topic, payload=b''):
    return bytes([D2_PUBLISH]) + bytes(topic) + payload


def client(protocol):
    c = DometicClient(CFG)
    c.protocol = protocol
    c.write_uuid = 'w'
    c.client = FakeBleak()
    return c


# ---------------------------------------------------------------------------
section("constants and tables")

check("DDM1 and DDM2 services differ", DDM1_SERVICE_UUID != DDM2_SERVICE_UUID, True)
check("DDM1 service uuid", DDM1_SERVICE_UUID, "537a0300-0995-481f-926c-1604e23fd515")
check("DDM1 write uuid", DDM1_WRITE_UUID, "537a0301-0995-481f-926c-1604e23fd515")
check("DDM1 notify uuid", DDM1_NOTIFY_UUID, "537a0302-0995-481f-926c-1604e23fd515")
check("DDM2 service uuid", DDM2_SERVICE_UUID, "537a0400-0995-481f-926c-1604e23fd515")
check("DDM2 write uuid", DDM2_WRITE_UUID, "537a0401-0995-481f-926c-1604e23fd515")
check("DDM2 notify uuid", DDM2_NOTIFY_UUID, "537a0402-0995-481f-926c-1604e23fd515")
# D2_SUBSCRIBE is the action byte; the topic list must not shadow it.
check("D2_SUBSCRIBE is action 0x12", D2_SUBSCRIBE, 0x12)
check("D2_SUBSCRIBE_TOPICS is a list", isinstance(D2_SUBSCRIBE_TOPICS, list), True)
check("every subscribed DDM1 topic is decodable",
      [t for t in D1_SUBSCRIBE if t not in D1_TOPICS], [])
check("every subscribed DDM2 topic is decodable",
      [t for t in D2_SUBSCRIBE_TOPICS if t not in D2_TOPICS], [])
check("all topic keys are 4 bytes",
      [t for t in list(D1_TOPICS) + list(D2_TOPICS) if len(t) != 4], [])
check("all topic bytes fit in a byte",
      [t for t in list(D1_TOPICS) + list(D2_TOPICS) if any(not 0 <= b <= 255 for b in t)], [])
check("DDM2 current is param 0x0F", D2_TOPICS[mccc(0x0F)], ('current', 'milli'))
check("DDM2 voltage is param 0x0C", D2_TOPICS[mccc(0x0C)], ('voltage', 'milli'))
check("DDM2 temps are an array", D2_TOPICS[mccc(0x04)], ('temperature', 'milli_array'))

# ---------------------------------------------------------------------------
section("decoders")

check("DDM2 milli amps", _decode(struct.pack('<i', 2450), 'milli'), 2.45)
check("DDM2 milli volts", _decode(struct.pack('<i', 12780), 'milli'), 12.78)
check("DDM2 milli negative", _decode(struct.pack('<i', -1200), 'milli'), -1.2)
check("DDM2 dual-zone temps", _decode(struct.pack('<ii', 4000, -18000), 'milli_array'), [4.0, -18.0])
check("DDM2 bool array", _decode(struct.pack('<ii', 1, 0), 'bool_array'), [True, False])
check("DDM2 int32 bool", _decode(struct.pack('<i', 1), 'bool'), True)
check("DDM2 u16 array", _decode(struct.pack('<HHH', 0, 0, 5), 'u16_array'), [0, 0, 5])
check("DDM1 decidegrees", _decode(struct.pack('<h', -185), 'decideg'), -18.5)
check("DDM1 decivolts", _decode(struct.pack('<H', 132), 'decivolt'), 13.2)
check("nul-padded string", _decode(b'CFX575DZ\x00\x00', 'str'), 'CFX575DZ')
check("truncated payloads decode to None", _decode(b'\x01', 'i32'), None)
check("empty payload decodes to None", _decode(b'', 'u8'), None)

# Regression: the 0x8000 sentinel must not surface as a -3276.8 C temperature.
check("DDM1 temperature sentinel becomes None", _decode(b'\x00\x80', 'decideg'), None)
check("DDM1 real temperature still decodes", _decode(struct.pack('<h', -220), 'decideg'), -22.0)

hist = struct.pack('<hhhhhhh', 25, 4, 0, -32768, -32768, -32768, -32768) + b'\xb3'
check("DDM1 history decodes newest-first with trailer",
      _decode(hist, 'history'), [2.5, 0.4, 0.0, -3276.8, -3276.8, -3276.8, -3276.8, 179])

# ---------------------------------------------------------------------------
section("DDM2 session: dual-zone CFX5")


async def ddm2_session():
    c = client('ddm2')
    frames = [
        d2_pub(mccc(0x01), struct.pack('<i', 3)),               # dual zone
        d2_pub(mccc(0x02), struct.pack('<i', 2)),
        d2_pub(mccc(0x04), struct.pack('<ii', 4200, -18500)),
        d2_pub(mccc(0x05), struct.pack('<ii', 4000, -18000)),
        d2_pub(mccc(0x03), struct.pack('<ii', 1, 1)),
        d2_pub(mccc(0x07), struct.pack('<ii', 0, 0)),
        d2_pub(mccc(0x0B), struct.pack('<i', 1)),
        d2_pub(mccc(0x0C), struct.pack('<i', 12780)),
        d2_pub(mccc(0x0E), struct.pack('<i', 1)),
        d2_pub(mccc(0x0F), struct.pack('<i', 3450)),
        d2_pub(mccc(0x10), struct.pack('<i', 1)),
        d2_pub(mccc(0x12), struct.pack('<HHH', 0, 0, 0)),
        d2_pub((0x01, 0, 0, 0x1C), b'CFX575DZ\x00\x00'),
        d2_pub((0x07, 0, 0x01, 0x00), b'MC1\x00'),
    ]
    for frame in frames:
        await c._on_notify(None, frame)

    check("DDM2 sends no acks at all", c.client.writes, [])
    check("DDM2 first data event set", c._first_data_event.is_set(), True)

    data = await c.read()
    check("DDM2 fridge compartment temp", data['temperature_0'], 4.2)
    check("DDM2 freezer compartment temp", data['temperature_1'], -18.5)
    check("DDM2 fridge setpoint", data['set_temperature_0'], 4.0)
    check("DDM2 freezer setpoint", data['set_temperature_1'], -18.0)
    check("DDM2 compartment count", data['compartment_count'], 2)
    check("DDM2 voltage", data['voltage'], 12.78)
    check("DDM2 current", data['current'], 3.45)
    check("DDM2 power is V*A", data['power'], round(12.78 * 3.45, 2))
    check("DDM2 compressor running", data['compressor_power'], 1)
    check("DDM2 doors shut", (data['door_open_0'], data['door_open_1']), (0, 0))
    check("DDM2 product type", data['product_type'], 'dual_zone')
    check("DDM2 product name", data['product_name'], 'CFX575DZ')
    check("DDM2 firmware id", data['firmware_id'], 'MC1')
    check("DDM2 power source 1 is dc", data['power_source'], 'dc')
    check("DDM2 no errors", data['error_count'], 0)
    check("DDM2 metadata device", data['__device'], 'DOMETIC_CFX')
    check("DDM2 metadata client", data['__client'], 'DometicClient')
    check("DDM2 metadata name", data['__name'], 'DOMETIC')
    # Everything must be a scalar so filter_fields and the influx Point work.
    check("no containers leak into read()",
          [k for k, v in data.items() if isinstance(v, (list, dict, tuple, set))], [])

    await c._on_notify(None, d2_pub(mccc(0x12), struct.pack('<HH', 0, 7)))
    check("DDM2 error is counted", (await c.read())['error_count'], 1)

    # Robustness: none of these may raise or corrupt state.
    before = dict(c.values)
    await c._on_notify(None, bytes([D2_NAK]))
    await c._on_notify(None, d2_pub((0x99, 0x99, 0x99, 0x99), b'\x01\x02\x03\x04'))
    await c._on_notify(None, bytes([D2_PUBLISH, 0x0F]))
    await c._on_notify(None, b'')
    check("DDM2 junk frames leave values intact", c.values, before)

    # A single-zone unit sends a one-element array.
    c.values['temperature'] = [4.0]
    data = await c.read()
    check("single-zone second compartment is absent", data['temperature_1'], None)


asyncio.run(ddm2_session())

# ---------------------------------------------------------------------------
section("DDM1 session: CFX3")


async def ddm1_session():
    c = client('ddm1')

    await c._on_notify(None, bytes([D1_ACK]))
    check("DDM1 handshake ack observed", c._ack_event.is_set(), True)
    check("DDM1 ack frame is not itself acked", c.client.writes, [])

    frames = [
        d1_pub((0, 128, 0, 1), b'\x02'),
        d1_pub((0, 1, 1, 1), struct.pack('<h', 42)),
        d1_pub((16, 1, 1, 1), struct.pack('<h', -185)),
        d1_pub((0, 2, 1, 1), struct.pack('<h', 40)),
        d1_pub((16, 2, 1, 1), struct.pack('<h', -180)),
        d1_pub((0, 0, 1, 1), b'\x01'),
        d1_pub((16, 0, 1, 1), b'\x01'),
        d1_pub((0, 8, 1, 1), b'\x00'),
        d1_pub((16, 8, 1, 1), b'\x00'),
        d1_pub((0, 0, 3, 1), b'\x01'),
        d1_pub((0, 1, 3, 1), struct.pack('<H', 132)),
        d1_pub((0, 3, 3, 1), b'\x01'),
        d1_pub((0, 5, 3, 1), b'\x01'),
        d1_pub((0, 0, 6, 1), b'CFX3_ABC123\x00'),
        d1_pub((0, 64, 3, 1), hist),
    ]
    for frame in frames:
        await c._on_notify(None, frame)

    # The cooler stops publishing unless every publish is acknowledged.
    check("DDM1 acked every publish", len(c.client.writes), len(frames))
    check("DDM1 acks are a bare ACK byte", set(c.client.writes), {bytes([D1_ACK])})

    n = len(c.client.writes)
    await c._on_notify(None, bytes([D1_PING]))
    check("DDM1 acked inbound ping", len(c.client.writes) - n, 1)
    n = len(c.client.writes)
    await c._on_notify(None, bytes([D1_SUB]))
    check("DDM1 acked inbound sub echo", len(c.client.writes) - n, 1)

    data = await c.read()
    check("DDM1 fridge compartment temp", data['temperature_0'], 4.2)
    check("DDM1 freezer compartment temp", data['temperature_1'], -18.5)
    check("DDM1 compartment count", data['compartment_count'], 2)
    check("DDM1 voltage", data['voltage'], 13.2)
    check("DDM1 current from newest bucket", data['current'], 2.5)
    check("DDM1 power", data['power'], round(13.2 * 2.5, 2))
    check("DDM1 device name", data['device_name'], 'CFX3_ABC123')
    check("DDM1 compressor state decoded", data['compressor_power'], 1)
    # DDM1 references call power source 2 Solar, DDM2 calls it Battery.
    await c._on_notify(None, d1_pub((0, 5, 3, 1), b'\x02'))
    check("DDM1 power source 2 is solar", (await c.read())['power_source'], 'solar')

    # Energy comes from the fridge's own buckets: 3 filled buckets of 2.5/0.4/0.0 A
    # at 13.2 V over 10 minutes each.
    expected = 13.2 * (2.5 + 0.4 + 0.0) * (600 / 3600.0)
    check("DDM1 energy from history buckets", round(data['energy_wh_1h'], 3), round(expected, 3))
    check("DDM1 coverage is 3 buckets", data['energy_window_seconds'], 1800)

    # A completely empty history must not fabricate a reading.
    await c._on_notify(None, d1_pub((0, 64, 3, 1),
                                    struct.pack('<hhhhhhh', *([-32768] * 7)) + b'\x00'))
    data = await c.read()
    check("DDM1 empty history gives no current", data['current'], None)
    check("DDM1 empty history gives no power", data['power'], None)
    check("DDM1 empty history gives no energy", data['energy_wh_1h'], 0.0)
    check("DDM1 empty history has no coverage", data['energy_window_seconds'], 0)

    # A full hour of buckets marks the window complete.
    full = struct.pack('<hhhhhhh', 20, 20, 20, 20, 20, 20, 20) + b'\x00'
    await c._on_notify(None, d1_pub((0, 64, 3, 1), full))
    data = await c.read()
    check("DDM1 full history covers the hour", data['energy_window_seconds'], 3600)
    check("DDM1 full-hour energy", round(data['energy_wh_1h'], 2), round(13.2 * 2.0, 2))


asyncio.run(ddm1_session())

# ---------------------------------------------------------------------------
section("DDM1 does not integrate bucket averages locally")


async def ddm1_no_local_integration():
    c = client('ddm1')
    await c._on_notify(None, d1_pub((0, 1, 3, 1), struct.pack('<H', 128)))
    await c._on_notify(None, d1_pub((0, 64, 3, 1),
                                    struct.pack('<hhhhhhh', 40, -32768, -32768,
                                                -32768, -32768, -32768, -32768) + b'\x00'))
    # Integrating a stale 10-minute average as an instantaneous sample was
    # measured to report ~8 Wh when the true figure was 0.
    check("DDM1 keeps the local integrator empty", len(c._energy.samples), 0)
    data = await c.read()
    check("DDM1 read() still does not integrate", len(c._energy.samples), 0)
    check("DDM1 energy is one bucket only",
          round(data['energy_wh_1h'], 3), round(12.8 * 4.0 * (600 / 3600.0), 3))
    check("DDM1 coverage is one bucket", data['energy_window_seconds'], 600)


asyncio.run(ddm1_no_local_integration())

# ---------------------------------------------------------------------------
section("EnergyWindow (DDM2 integration)")

T0 = 1_000_000.0

w = EnergyWindow()
for i in range(0, 3601, 60):
    w.add(T0 + i, 50.0)
check("steady 50 W for an hour is 50 Wh", round(w.watt_hours(T0 + 3600), 2), 50.0)
check("coverage is the full hour", round(w.coverage_seconds(T0 + 3600)), 3600)

w = EnergyWindow()
for i in range(0, 7201, 60):
    w.add(T0 + i, 50.0)
check("window slides, two hours still reads 50 Wh", round(w.watt_hours(T0 + 7200), 1), 50.0)
check("samples stay bounded", len(w.samples) <= 63, True)

w = EnergyWindow()
for minute in range(0, 21):
    w.add(T0 + minute * 60, 45.0)
for minute in range(21, 61):
    w.add(T0 + minute * 60, 0.0)
duty = w.watt_hours(T0 + 3600)
check("20 min at 45 W is about 15 Wh", 15.0 <= duty <= 15.8, True)

w = EnergyWindow()
w.add(T0, 100.0)
w.add(T0 + 3000, 100.0)
check("a gap beyond the limit adds nothing", w.watt_hours(T0 + 3000), 0.0)
check("a gap beyond the limit has no coverage", w.coverage_seconds(T0 + 3000), 0.0)

w = EnergyWindow()
w.add(T0, 60.0)
w.add(T0 + 120, 60.0)
check("a short gap is integrated", round(w.watt_hours(T0 + 120), 3), 2.0)

check("an empty window is zero", EnergyWindow().watt_hours(T0), 0.0)
w = EnergyWindow()
w.add(T0, 99.0)
check("a single sample is zero", w.watt_hours(T0), 0.0)

w = EnergyWindow()
w.add(T0 + 100, 10.0)
w.add(T0 + 50, 999.0)
check("a backwards sample is dropped", len(w.samples), 1)

# ---------------------------------------------------------------------------
section("energy sampling is rate limited and clock-safe")


async def energy_sampling():
    c = client('ddm2')
    await c._on_notify(None, d2_pub(mccc(0x0C), struct.pack('<i', 12000)))
    for _ in range(50):
        await c._on_notify(None, d2_pub(mccc(0x0F), struct.pack('<i', 2000)))
    check("a burst of publishes yields one sample", len(c._energy.samples), 1)
    check("minimum sample spacing", ENERGY_MIN_SAMPLE_INTERVAL, 5.0)

    c._last_energy_sample_at = 0.0
    n = len(c._energy.samples)
    await c.read()
    check("read() keeps the integration going", len(c._energy.samples), n + 1)

    # Energy must not be keyed on the wall clock, or an NTP step freezes it.
    monotonic_based = all(t < time.time() - 86400 * 365 for t, _ in c._energy.samples)
    check("energy timestamps are monotonic, not wall clock", monotonic_based, True)


asyncio.run(energy_sampling())

# ---------------------------------------------------------------------------
section("reconnect hygiene")


async def reconnect_hygiene():
    c = client('ddm2')
    await c._on_notify(None, d2_pub(mccc(0x0C), struct.pack('<i', 12800)))
    await c._on_notify(None, d2_pub(mccc(0x0F), struct.pack('<i', 4700)))
    check("voltage and current cached", ('voltage' in c.values, 'current' in c.values), (True, True))

    await c._teardown()
    # A fresh voltage must not be paired with a pre-dropout current.
    check("teardown drops voltage", 'voltage' in c.values, False)
    check("teardown drops current", 'current' in c.values, False)
    check("teardown keeps temperatures", c.values.get('temperature', 'absent'), 'absent')

    c.client = FakeBleak()
    c.protocol = 'ddm2'
    await c._on_notify(None, d2_pub(mccc(0x0C), struct.pack('<i', 12800)))
    check("a lone voltage makes no energy sample", len(c._energy.samples), 1)


asyncio.run(reconnect_hygiene())

# ---------------------------------------------------------------------------
section("read() failure modes")


async def read_failures():
    c = client('ddm2')
    c.client = None
    try:
        await c.read()
        check("read() rejects a missing client", False, True)
    except Exception as e:
        check("read() rejects a missing client", 'not connected' in str(e), True)

    c = client('ddm2')
    try:
        await c.read()
        check("read() rejects an empty snapshot", False, True)
    except Exception as e:
        check("read() rejects an empty snapshot", 'no data published' in str(e), True)

    # A live link with a silent cooler must force a reconnect, not serve stale data.
    c = client('ddm2')
    await c._on_notify(None, d2_pub(mccc(0x04), struct.pack('<ii', 4000, -18000)))
    c._last_publish_at = time.time() - 4 * 3600
    try:
        await c.read()
        check("read() rejects a silent cooler", False, True)
    except Exception as e:
        check("read() rejects a silent cooler", 'silent' in str(e), True)

    # Within the silence budget it is fine.
    c._last_publish_at = time.time() - 60
    data = await c.read()
    check("read() tolerates a brief quiet spell", data['temperature_0'], 4.0)
    check("silence is reported", data['seconds_since_update'], 60)


asyncio.run(read_failures())

# ---------------------------------------------------------------------------
section("protocol detection")


class FakeService:
    def __init__(self, uuid):
        self.uuid = uuid
        self.characteristics = []


def detect(forced, services):
    cfg = Cfg(CFG)
    cfg['protocol'] = forced
    c = DometicClient(cfg)
    c.client = type('C', (), {'services': [FakeService(u) for u in services]})()
    c._detect_protocol()
    return c.protocol, c.notify_uuid


check("auto picks DDM1", detect('auto', [DDM1_SERVICE_UUID]), ('ddm1', DDM1_NOTIFY_UUID))
check("auto picks DDM2", detect('auto', [DDM2_SERVICE_UUID]), ('ddm2', DDM2_NOTIFY_UUID))
check("auto prefers DDM2 when both present",
      detect('auto', [DDM1_SERVICE_UUID, DDM2_SERVICE_UUID])[0], 'ddm2')
check("auto ignores unrelated services",
      detect('auto', ['0000180a-0000-1000-8000-00805f9b34fb', DDM2_SERVICE_UUID])[0], 'ddm2')
check("explicit ddm1 honoured", detect('ddm1', [DDM1_SERVICE_UUID])[0], 'ddm1')

for bad in ('ddm3', 'DDM_2', '2', 'nonsense'):
    try:
        detect(bad, [DDM2_SERVICE_UUID])
        check(f"invalid protocol {bad!r} is rejected", False, True)
    except Exception as e:
        check(f"invalid protocol {bad!r} is rejected", 'Invalid protocol' in str(e), True)

try:
    detect('auto', ['0000180a-0000-1000-8000-00805f9b34fb'])
    check("an unbonded cooler is diagnosed", False, True)
except Exception as e:
    check("an unbonded cooler is diagnosed", 'PAIR mode' in str(e), True)

# ---------------------------------------------------------------------------
section("display payload (ff14)")


async def display_payload():
    c = client('ddm2')
    for frame in [
        d2_pub(mccc(0x02), struct.pack('<i', 2)),
        d2_pub(mccc(0x04), struct.pack('<ii', 4200, -18500)),
        d2_pub(mccc(0x05), struct.pack('<ii', 4000, -18000)),
        d2_pub(mccc(0x0B), struct.pack('<i', 1)),
        d2_pub(mccc(0x0C), struct.pack('<i', 12780)),
        d2_pub(mccc(0x0E), struct.pack('<i', 1)),
        d2_pub(mccc(0x0F), struct.pack('<i', 3450)),
        d2_pub(mccc(0x10), struct.pack('<i', 1)),
    ]:
        await c._on_notify(None, frame)
    now = time.monotonic()
    c._energy = EnergyWindow()
    for i in range(0, 3601, 60):
        c._energy.add(now - 3600 + i, 44.09)
    return await c.read()


data = asyncio.run(display_payload())

ble = BLEClient()
ble.update_fridge(data)
check("fridge payload prefix is 17 bytes", len(ble.fridge_head), 17)
payload = ble.fridge_head + struct.pack('<H', 12)
check("full fridge payload is 19 bytes", len(payload), 19)
check("documented struct size matches", struct.calcsize('<hhhhhHHBBBH'), 19)

t0, t1, s0, s1, power, energy, volts, flags, count, source, age = struct.unpack('<hhhhhHHBBBH', payload)
check("wire fridge temp", t0 / 10.0, 4.2)
check("wire freezer temp", t1 / 10.0, -18.5)
check("wire fridge setpoint", s0 / 10.0, 4.0)
check("wire freezer setpoint", s1 / 10.0, -18.0)
check("wire power", power / 10.0, round(round(12.78 * 3.45, 2) * 10) / 10.0)
check("wire energy", round(energy / 10.0, 1), round(data['energy_wh_1h'], 1))
check("wire voltage", volts / 100.0, 12.78)
check("wire compartment count", count, 2)
check("wire power source", source, 1)
check("wire age", age, 12)
check("wire cooler flag", bool(flags & FRIDGE_FLAG_COOLER_ON), True)
check("wire compressor flag", bool(flags & FRIDGE_FLAG_COMPRESSOR_ON), True)
check("wire error flag clear", bool(flags & FRIDGE_FLAG_ERROR), False)
check("wire energy-window-full flag", bool(flags & FRIDGE_FLAG_ENERGY_WINDOW_FULL), True)

section("display payload is defensive")

ble.update_fridge({})
check("an empty reading still packs", len(ble.fridge_head), 17)
*_, source = struct.unpack('<hhhhhHHBBB', ble.fridge_head)
check("unknown power source is 255", source, 255)

# Regression: an out-of-range temperature must clamp, not raise. A struct.error
# here would be misread as a dead fridge and wedge the retry loop.
ble.update_fridge({'temperature_0': 2147483.647, 'temperature_1': -2147483.648,
                   'power': 99999.0, 'energy_wh_1h': 99999.0, 'voltage': 9999.0,
                   'compartment_count': 900, 'power_source_id': 900, 'error_count': 3})
t0, t1, _, _, power, energy, volts, flags, count, source = struct.unpack('<hhhhhHHBBB', ble.fridge_head)
check("absurd high temperature clamps", t0, 32767)
check("absurd low temperature clamps", t1, -32768)
check("power clamps", power, 32767)
check("energy clamps", energy, 65535)
check("voltage clamps", volts, 65535)
check("compartment count clamps", count, 255)
check("power source clamps", source, 255)
check("error flag set", bool(flags & FRIDGE_FLAG_ERROR), True)

ble.update_fridge({'power': -5000.0, 'energy_wh_1h': -1.0})
_, _, _, _, power, energy, *_ = struct.unpack('<hhhhhHHBBB', ble.fridge_head)
check("negative power stays signed", power, -32768)
check("negative energy floors at zero", energy, 0)

# Regression: age must date from the fridge's publish, not from this call.
ble.update_fridge({'temperature_0': 4.0, 'seconds_since_update': 600})
age_now = time.time() - ble.fridge_updated_at
check("age reflects fridge silence, not pack time", 590 <= age_now <= 610, True)

section("existing characteristics unchanged")

ble2 = BLEClient()
ble2.update_battery(percentage=87, power=-120.5, voltage=26.5, temperature=23.5)
check("battery payload is still 9 bytes", len(ble2.battery_data), 9)
ble2.update_charger(pv_voltage=17.1, pv_current=2.04,
                    alternator_voltage=12.9, alternator_current=0.0)
check("charger payload is still 4 bytes", len(ble2.charger_data), 4)
check("fridge payload starts empty", ble2.fridge_head, None)

section("a display without ff14 is tolerated")


async def missing_characteristic():
    class Char:
        def __init__(self, uuid):
            self.uuid = uuid
            self.properties = ['write']

    class Svc:
        def __init__(self, uuids):
            self.uuid = '0000ff10-0000-1000-8000-00805f9b34fb'
            self.characteristics = [Char(u) for u in uuids]

    class OldDisplay:
        is_connected = True

        def __init__(self):
            self.services = [Svc([
                '0000ff11-0000-1000-8000-00805f9b34fb',
                '0000ff12-0000-1000-8000-00805f9b34fb',
                '0000ff13-0000-1000-8000-00805f9b34fb',
            ])]
            self.written = []

        async def write_gatt_char(self, uuid, payload, response=False):
            if uuid.lower() not in {c.uuid for s in self.services for c in s.characteristics}:
                raise Exception(f"Characteristic {uuid} was not found!")
            self.written.append(uuid.lower())

    b = BLEClient()
    b.client = OldDisplay()
    b._cache_characteristics()
    b.update_battery(percentage=50, power=0, voltage=13.0, temperature=20.0)
    b.update_fridge({'temperature_0': 4.0})
    await b._write_char('0000ff11-0000-1000-8000-00805f9b34fb', b.battery_data, 'battery')
    await b._write_char(FRIDGE_CHAR_UUID, b.fridge_head + struct.pack('<H', 0), 'fridge')
    check("battery still written to an old display",
          '0000ff11-0000-1000-8000-00805f9b34fb' in b.client.written, True)
    check("missing fridge characteristic is skipped, not raised",
          FRIDGE_CHAR_UUID in b.client.written, False)


asyncio.run(missing_characteristic())

# ---------------------------------------------------------------------------
print("\n" + "=" * 56)
if FAILURES:
    print(f"{len(FAILURES)} FAILED: {FAILURES}")
    sys.exit(1)
print("all dometic tests passed")
