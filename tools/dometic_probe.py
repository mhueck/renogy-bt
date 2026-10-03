#!/usr/bin/env python3
"""Standalone probe for a Dometic CFX cooler over BLE.

Run this on the machine with the Bluetooth adapter before enabling the fridge
in config.ini. It scans for candidates, connects, reports which DDM protocol
generation the cooler speaks, subscribes, and dumps every frame it receives
with the decoded value alongside the raw bytes.

    python3 tools/dometic_probe.py                 # scan only
    python3 tools/dometic_probe.py AA:BB:CC:DD:EE:FF
    python3 tools/dometic_probe.py AA:BB:CC:DD:EE:FF --seconds 180 --raw

Put the cooler into Bluetooth PAIR mode first (hold its Bluetooth button until
the symbol blinks). It exposes no GATT services at all until bonded, and it
serves only one connection, so close the Dometic phone app.
"""
import argparse
import asyncio
import logging
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from bleak import BleakClient, BleakScanner  # noqa: E402

from renogybt.DometicClient import (  # noqa: E402
    D1_ACK, D1_HELLO, D1_NAK, D1_NOP, D1_PING, D1_PUB, D1_SUB,
    D1_BULK_SUBSCRIBE, D1_NO_VALUE, D1_SUBSCRIBE, D1_TOPICS,
    D2_ACK, D2_FRAGMENT, D2_HELLO, D2_NAK, D2_NOP, D2_PUBLISH, D2_SUBSCRIBE,
    D2_SUBSCRIBE_TOPICS, D2_TOPICS,
    DDM1_NOTIFY_UUID, DDM1_SERVICE_UUID, DDM1_WRITE_UUID,
    DDM2_NOTIFY_UUID, DDM2_SERVICE_UUID, DDM2_WRITE_UUID,
    _decode,
)

D1_ACTION_NAMES = {D1_PUB: 'PUB', D1_SUB: 'SUB', D1_PING: 'PING',
                   D1_HELLO: 'HELLO', D1_ACK: 'ACK', D1_NAK: 'NAK', D1_NOP: 'NOP'}
D2_ACTION_NAMES = {D2_HELLO: 'HELLO', D2_ACK: 'ACK', D2_NAK: 'NAK', D2_NOP: 'NOP',
                   D2_PUBLISH: 'PUBLISH', 0x11: 'SET', D2_SUBSCRIBE: 'SUBSCRIBE',
                   D2_FRAGMENT: 'FRAGMENT'}

# Advertised local-name prefixes seen across the range. CFX3 units advertise
# "CFX3_...", CFX5 units advertise "MC1_<mac tail>".
NAME_PREFIXES = ('CFX', 'MC1', 'MC2', 'MC3')


async def scan(seconds):
    print(f"Scanning {seconds}s for Dometic coolers...\n")
    found = {}

    def callback(device, adv):
        name = adv.local_name or device.name or ''
        uuids = [u.lower() for u in (adv.service_uuids or [])]
        is_dometic = (
            DDM1_SERVICE_UUID in uuids
            or DDM2_SERVICE_UUID in uuids
            or any(name.upper().startswith(p) for p in NAME_PREFIXES)
        )
        if not is_dometic or device.address in found:
            return
        found[device.address] = True
        if DDM1_SERVICE_UUID in uuids:
            hint = 'DDM1/CFX3 (service UUID advertised)'
        elif DDM2_SERVICE_UUID in uuids:
            hint = 'DDM2/CFX2-CFX5 (service UUID advertised)'
        elif name.upper().startswith(('MC1', 'MC2', 'MC3')):
            hint = 'DDM2/CFX2-CFX5 (name match)'
        elif name.upper().startswith('CFX3'):
            hint = 'DDM1/CFX3 (name match)'
        else:
            hint = 'Dometic candidate (name match)'
        print(f"  {device.address}  rssi={adv.rssi:>5}  name={name!r}  -> {hint}")
        print(f"uuids: {uuids}")
        if adv.service_uuids:
            print(f"      service_uuids: {adv.service_uuids}")
        if adv.manufacturer_data:
            for key, value in adv.manufacturer_data.items():
                print(f"      manufacturer_data[0x{key:04X}]: {value.hex()}")

    scanner = BleakScanner(detection_callback=callback)
    await scanner.start()
    await asyncio.sleep(seconds)
    await scanner.stop()

    if not found:
        print("  nothing found.\n")
        print("A bonded cooler advertises very sparsely. If you already know the")
        print("MAC, pass it directly - the probe will connect by address.")
    print()
    return list(found)


async def probe(mac, seconds, show_raw, no_pair):
    print(f"Connecting to {mac}...")
    device = await BleakScanner.find_device_by_address(mac, timeout=10.0)
    if device is None:
        print("  not seen in scan, connecting by address (normal when bonded)")

    client = BleakClient(device if device else mac)
    await client.connect(timeout=25.0)
    print(f"  connected: {client.is_connected}")
    # The cooler serves exactly one BLE connection, and BlueZ does not drop a
    # link just because the process that opened it died. Always disconnect.
    try:
        await _probe_connected(client, seconds, show_raw, no_pair)
    finally:
        try:
            await client.disconnect()
        except Exception as e:
            print(f"  disconnect failed: {e}")


async def _probe_connected(client, seconds, show_raw, no_pair):
    if not no_pair:
        try:
            await client.pair()
            print("  bond confirmed")
        except Exception as e:
            print(f"  pair() skipped: {e}")

    services = {s.uuid.lower(): s for s in client.services}
    print("\nGATT services:")
    for uuid, service in services.items():
        print(f"  {uuid}")
        for char in service.characteristics:
            print(f"      {char.uuid}  {','.join(char.properties)}")

    if DDM2_SERVICE_UUID in services:
        protocol, write_uuid, notify_uuid = 'ddm2', DDM2_WRITE_UUID, DDM2_NOTIFY_UUID
    elif DDM1_SERVICE_UUID in services:
        protocol, write_uuid, notify_uuid = 'ddm1', DDM1_WRITE_UUID, DDM1_NOTIFY_UUID
    else:
        print("\nNo Dometic DDM service present.")
        print("The cooler hides its GATT table until bonded. Put it in Bluetooth")
        print("PAIR mode (60s window) and run this again.")
        return

    print(f"\n=> protocol: {protocol}")
    print(f"   set 'protocol = {protocol}' in the [fridge] section of config.ini\n")

    seen = {}
    ack_event = asyncio.Event()
    last_frame = [time.time()]

    async def write(payload):
        await client.write_gatt_char(write_uuid, payload, response=True)
        await asyncio.sleep(0.2)

    def report(topic, payload, table):
        entry = table.get(topic)
        stamp = time.strftime('%H:%M:%S')
        if entry is None:
            print(f"[{stamp}] PUB unknown topic {list(topic)} raw={payload.hex()}")
            return
        name, kind = entry
        value = _decode(payload, kind)
        first = '  (new)' if name not in seen else ''
        seen[name] = value
        suffix = f"  raw={payload.hex()}" if show_raw else ''
        print(f"[{stamp}] {name} = {value}{suffix}{first}")

    async def on_notify(_char, data):
        data = bytes(data)
        if not data:
            return
        last_frame[0] = time.time()
        action = data[0]
        if protocol == 'ddm1':
            if action == D1_ACK:
                ack_event.set()
                return
            if action == D1_NAK:
                print("<- NAK")
                return
            if action == D1_PUB and len(data) >= 5:
                report(tuple(data[1:5]), data[5:], D1_TOPICS)
                await write(bytes([D1_ACK]))
                return
            if action in (D1_PING, D1_SUB, D1_HELLO, D1_NOP):
                await write(bytes([D1_ACK]))
                return
            print(f"<- {D1_ACTION_NAMES.get(action, hex(action))} {data.hex()}")
        else:
            if action == D2_PUBLISH and len(data) >= 5:
                report(tuple(data[1:5]), data[5:], D2_TOPICS)
                return
            if action == D2_NAK:
                print("<- NAK")
                return
            if action not in (D2_ACK, D2_HELLO, D2_NOP):
                print(f"<- {D2_ACTION_NAMES.get(action, hex(action))} {data.hex()}")

    await client.start_notify(notify_uuid, on_notify)

    if protocol == 'ddm1':
        print("-> PING (DDM1 handshake)")
        await write(bytes([D1_PING]))
        try:
            await asyncio.wait_for(ack_event.wait(), 6.0)
            print("<- ACK, handshake complete\n")
        except asyncio.TimeoutError:
            print("!! no ACK within 6s - the cooler may not be bonded\n")
        for group, topic in D1_BULK_SUBSCRIBE.items():
            print(f"-> bulk SUB {group}")
            await write(bytes([D1_SUB]) + bytes(topic))
        for topic in D1_SUBSCRIBE:
            await write(bytes([D1_SUB]) + bytes(topic))

        async def heartbeat():
            while client.is_connected:
                await asyncio.sleep(1.0)
                if time.time() - last_frame[0] > 3.0:
                    try:
                        await write(bytes([D1_PING]))
                    except Exception:
                        return

        heartbeat_task = asyncio.create_task(heartbeat())
    else:
        heartbeat_task = None
        for topic in D2_SUBSCRIBE_TOPICS:
            await write(bytes([D2_SUBSCRIBE]) + bytes(topic))

    print(f"Listening {seconds}s. Ctrl+C to stop early.\n")
    try:
        await asyncio.sleep(seconds)
    except asyncio.CancelledError:
        pass
    finally:
        if heartbeat_task:
            heartbeat_task.cancel()
            try:
                await heartbeat_task
            except asyncio.CancelledError:
                pass
        try:
            await client.stop_notify(notify_uuid)
        except Exception:
            pass

    print("\n--- summary of topics seen ---")
    if not seen:
        print("NOTHING received. Most likely the cooler is not bonded.")
    for name in sorted(seen):
        print(f"  {name:32} = {seen[name]}")

    voltage, current = seen.get('voltage'), seen.get('current')
    if current is None:
        # DDM1 has no live current topic. Walk the hour history newest-first,
        # skipping the 0x8000 "no sample yet" slots.
        buckets = [None if v == D1_NO_VALUE else v
                   for v in (seen.get('dc_current_history_hour') or [])[:7]]
        current = next((b for b in buckets if b is not None), None)
        if current is not None:
            print(f"\n  (DDM1 has no live current topic; newest usable hour bucket = {current} A)")
        elif buckets:
            print("\n  (DDM1 hour history has no samples yet - leave the fridge running longer)")
        filled = [b for b in buckets[:6] if b is not None]
        if voltage is not None and filled:
            wh = voltage * sum(filled) * (600 / 3600.0)
            print(f"  energy over the last {len(filled) * 10} min = {round(wh, 2)} Wh"
                  f"{'' if len(filled) == 6 else ' (partial hour)'}")
    if voltage is not None and current is not None:
        print(f"\n  power draw = {voltage} V x {current} A = {round(voltage * current, 1)} W")
    else:
        print("\n  could not compute power: missing voltage and/or current")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('mac', nargs='?', help='cooler MAC address; omit to scan only')
    parser.add_argument('--seconds', type=int, default=120, help='listen duration (default 120)')
    parser.add_argument('--scan-seconds', type=int, default=15, help='scan duration (default 15)')
    parser.add_argument('--raw', action='store_true', help='also print raw payload bytes')
    parser.add_argument('--no-pair', action='store_true', help='skip the pair() attempt')
    parser.add_argument('--debug', action='store_true')
    args = parser.parse_args()

    logging.basicConfig(level=logging.DEBUG if args.debug else logging.WARNING)

    if not args.mac:
        asyncio.run(scan(args.scan_seconds))
        return
    try:
        asyncio.run(probe(args.mac, args.seconds, args.raw, args.no_pair))
    except KeyboardInterrupt:
        print("\ninterrupted")


if __name__ == '__main__':
    main()
