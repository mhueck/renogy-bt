# Renogy BT
![256924763-940c205e-738d-4a68-982f-1695c80bfed5](https://github.com/cyrils/renogy-bt/assets/5549113/bcdef6ec-efc9-44fd-af70-67165cf6862e)

Cross-platform Python library to read Renogy¹ Solar Charge Controllers and Smart Batteries using  [BT-1](https://www.renogy.com/bt-1-bluetooth-module-new-version/) or [BT-2](https://www.renogy.com/bt-2-bluetooth-module/) type (RS232 or RS485)  bluetooth modules. Tested with **Rover** / **Wanderer** series charge controllers, but it might also work with other  "SRNE like" devices like Rich Solar, PowMr etc. See the list of [compatible devices](#compatibility). It can also upload data to local **MQTT** broker, **PVOutput** cloud or your own custom server.

## Dependencies
You will need [Python](https://www.python.org/downloads/) 3.6 or above in your system. In some platforms you may have to create python virtual environment. Then install dependencies by running the command:
```sh
python3 -m pip install -r requirements.txt
```
This library should work on any modern Linux/Windows/Mac platforms that supports [Bleak](https://github.com/hbldh/bleak). 

## Example
Each device needs a separate [config.ini](https://github.com/cyrils/renogy-bt1/blob/main/config.ini) file. Update  config file with correct values for `mac_addr`, `alias` and `type` and run the following command:

```sh
python3 ./example.py config.ini
```

**How to get mac address?**

The library will automatically list possible compatible devices discovered nearby, just run `example.py`. You can alternatively use apps like [BLE Scanner](https://play.google.com/store/apps/details?id=com.macdom.ble.blescanner).

**Output**

```
INFO:root:Init RoverClient: BT-TH-B00FXXXX => 80:6F:B0:0F:XX:XX
INFO:root:Adapter status - Powered: True
INFO:root:Starting discovery...
INFO:root:Devices found: 5
INFO:root:Found matching device BT-TH-B00FXXXX => [80:6F:B0:0F:XX:XX]
INFO:root:[80:6f:b0:0f:XX:XX] Discovered, alias = BT-TH-B00FXXXX
INFO:root:[80:6F:B0:0F:XX:XX] Connected
INFO:root:[80:6F:B0:0F:XX:XX] Resolved services
INFO:root:found write characteristic 0000ffd1-0000-1000-8000-00805f9b34fb
INFO:root:subscribed to notification 0000fff1-0000-1000-8000-00805f9b34fb
INFO:root:resolved services
INFO:root:reading params
DEBUG:root:create_read_request 256 => [255, 3, 1, 0, 0, 34, 209, 241]
INFO:root:characteristic_write_value_succeeded
INFO:root:characteristic_enable_notifications_succeeded
INFO:root:on_data_received: response for read operation
DEBUG:root:BT-TH-B00FXXXX => {'function': 'READ', 'model': 'RNG-CTRL-WND10', 'battery_percentage': 87, 'battery_voltage': 12.9, 'battery_current': 2.58, 'battery_temperature': 25, 'controller_temperature': 33, 'load_status': 'off', 'load_voltage': 0.0,'load_current': 0.0, 'load_power': 0, 'pv_voltage': 17.1, 'pv_current': 2.04, 'pv_power': 35, 'max_charging_power_today': 143, 'max_discharging_power_today': 0, 'charging_amp_hours_today': 34, 'discharging_amp_hours_today': 34, 'power_generation_today': 432, 'power_consumption_today': 0, 'power_generation_total': 426038, 'charging_status': 'mppt', 'battery_type': 'lithium', 'device_id': 97}
INFO:root:Exit: Disconnecting device: BT-TH-B00FXXXX [80:6F:B0:0F:XX:XX]
```
```
# Rover historical data (7 days summary)
DEBUG:root:BT-TH-30A3XXXX => {'function': 'READ', 'daily_power_generation': [1754, 1907, 1899, 1804, 1841, 1630, 1344],'daily_charge_ah': [135, 147, 147, 139, 142, 125, 102], 'daily_max_power': [234, 344, 360, 335, 331, 307, 290]}
```
```
# Battery output
DEBUG:root:BT-TH-161EXXXX => {'function': 'READ', 'model': 'RBT100LFP12S-G', 'cell_count': 4, 'cell_voltage_0': 3.6, 'cell_voltage_1': 3.6, 'cell_voltage_2': 3.6, 'cell_voltage_3': 3.6, 'sensor_count': 4, 'temperature_0': 21.0, 'temperature_1': 21.0, 'temperature_2': 21.0, 'temperature_3': 21.0, 'current': 1.4, 'voltage': 14.5, 'remaining_charge': 99.941, 'capacity': 100.0, 'device_id': 48} 
```
```
# Inverter output
DEBUG:root:BTRIC13400XXXX => {'function': 'READ', 'input_voltage': 124.9, 'input_current': 2.2, 'output_voltage': 124.9, 'output_current': 1.19, 'output_frequency': 59.97, 'battery_voltage': 14.4, 'temperature': 30.0, 'input_frequency': 59.97, 'device_id': 32, 'model': 'RIV1230RCH-SPS', 'battery_percentage': 100, 'charging_current': 0.7, 'solar_voltage': 0.0, 'solar_current': 0.0, 'solar_power': 0, 'charging_status': 'deactivated', 'charging_power': 10, 'load_curent': 1.2, 'load_active_power': 108, 'load_apparent_power': 150, 'line_charging_current': 0.0, 'load_percentage': 5, '__device': 'BTRIC13400XXXX', '__client': 'InverterClient'}
```

```
# DC Charger output
INFO:root:BT-TH-XXXXXXXX => {'function': 'READ', 'model': 'RBC50D1S-G1', 'device_id': 96, 'battery_percentage': 100, 'battery_voltage': 13.2, 'combined_charge_current': 0.0, 'controller_temperature': 18, 'battery_temperature': 25, 'alternator_voltage': 12.9, 'alternator_current': 0.0, 'alternator_power': 0, 'pv_voltage': 0.0, 'pv_current': 0.0, 'pv_power': 0, 'battery_min_voltage_today': 13.2, 'battery_max_voltage_today': 13.3, 'battery_max_current_today': 17.02, 'max_charging_power_today': 238, 'charging_amp_hours_today': 25, 'power_generation_today': 336, 'total_working_days': 703, 'count_battery_overdischarged': 0, 'count_battery_fully_charged': 1435, 'battery_ah_total_accumulated': 5607, 'power_generation_total': 76580, 'charging_status': 'current limiting', 'error': 'battery_over_discharge', 'battery_type': None, '__device': 'BT-TH-XXXXXXXX', '__client': 'DCChargerClient'}
```

**Have multiple devices in Hub mode?**

If you have multiple devices connected to a single BT-2 module (daisy chained or using [Communication Hub](https://www.renogy.com/communication-hub/)), you need to find out the individual device Id (aka address) of each of these devices. Below are some of the usual suspects:

|  | Stand-alone | Daisy-chained | Hub mode |
| :-------- | :-------- | :-------- | :-------- |
|  Controller | 255, 17 | 16, 17 | 96, 97 |
|  Battery | 255 | 33, 34, 35 | 48, 49, 50 |
|  Inverter | 255, 32 | 32 | 32 |

 If you receive no response or garbled data with above ids, connect a single device to the Hub at a time and use the default broadcast address of 255 in `config.ini` to find out the actual `device_id` from output log. Then use this device Id to connect in Hub mode.

## Compatibility
| Device | Type | Adapter | Supported |
| -------- | :-------- | :--------: | :--------: |
| Renogy Rover/Wanderer/Adventurer | Controller |  BT-1 | ✅ |
| Renogy Rover Elite RCC40RVRE | Controller | BT-2 |  ✅ |
| Renogy DC-DC Charger DCC50S | Controller | BT-2 |  ✅ |
| SRNE ML24/ML48 Series | Controller | BT-1 | ✅ |
| RICH SOLAR 20/40/60 | Controller | BT-1 | ✅ |
| Renogy RBT100LFP12S / RBT50LFP48S | Battery | BT-2 | ✅ |
| Renogy RBT100LFP12-BT / RBT200LFP12-BT (Built-in BLE) | Battery | - | ✅ |
| Renogy RBT12100LFP-BT / RBT12200LFP-BT (Pro Series) | Battery | - | ✅ |
| Renogy RIV4835CSH1S | Inverter | BT-2 | ✅ |
| Renogy Rego RIV1230RCH (Built-in BLE) | Inverter | - | ✅ |
| Renogy Smart Shunt | Shunt | - | ❌ |

## Dometic CFX fridge

A Dometic CFX portable fridge can be polled alongside the solar gear. It reports
per-compartment temperatures, supply voltage, current draw, and a locally
integrated watt-hour total for the trailing hour, all of which are forwarded to
the BLE display on characteristic `ff14` (see [BLE_CLIENT_SPEC.md](BLE_CLIENT_SPEC.md)).

**Which protocol does my fridge speak?** Dometic ships two incompatible
generations of its "DDM" pub/sub protocol, and the model badge does not tell you
which one you have — a 75DZ exists as both a CFX3 and a CFX5. They differ in GATT
service, action bytes, topic addressing and value encoding, with no overlap:

| Generation | Models | GATT service |
| :-- | :-- | :-- |
| DDM1 | CFX3 | `537a0300-0995-481f-926c-1604e23fd515` |
| DDM2 | CFX2, CFX5 (firmware `MC1`/`MC2`/`MC3`) | `537a0400-0995-481f-926c-1604e23fd515` |

Both are implemented here and `protocol = auto` picks whichever service the
cooler actually exposes, so you normally do not need to care.

**Setup.**

1. Put the fridge into Bluetooth **PAIR** mode (hold the Bluetooth button until
   the symbol blinks — the window is about 60 seconds). The cooler exposes *no*
   GATT services until it is bonded, and it accepts only **one** BLE connection,
   so close the Dometic phone app first.
2. Find the fridge and confirm its protocol:
   ```sh
   python3 tools/dometic_probe.py                      # scan
   python3 tools/dometic_probe.py AA:BB:CC:DD:EE:FF    # connect and dump frames
   ```
   The probe prints the detected protocol, every topic it receives, and the
   computed power draw. CFX3 units advertise as `CFX3_...`; CFX5 units as
   `MC1_<mac tail>`.
3. Fill in the `[fridge]` section of `config.ini` with the `mac_addr`.

**Known limitations.**

- **There is no watt or watt-hour reading in either protocol.** Neither
  generation exposes a W, Wh, kWh or Ah parameter for the cooler, so power is
  computed as voltage × current and the hourly energy total is derived here.
  - On **DDM2** it is integrated locally by trapezoid over a 3600 s window. It
    therefore under-reports until the program has been running an hour, and gaps
    while the fridge was disconnected contribute nothing rather than being
    extrapolated.
  - On **DDM1** it is taken from the fridge's own hour-history array instead —
    six 10-minute buckets of average amps are exactly one hour, which is more
    accurate than integrating the stale bucket value as if it were live.
  - Either way, bit 7 of the display flags byte says whether the figure covers a
    genuine full hour. Treat it as provisional until that bit is set.
- **CFX3 has no live current topic at all.** On DDM1 the instantaneous draw is
  the newest bucket of the hour history: an average over 10 minutes, and up to
  10 minutes stale. DDM2/CFX5 does expose a live current reading.
- **Dual-zone support is unverified on real hardware.** The upstream projects
  hardware-validated single-zone CFX5 units only and explicitly mark dual-zone
  experimental. A CFX5 95DZ owner did confirm the DDM2 service UUIDs, but the
  per-compartment array handling has not been checked on a DZ box. Run the probe
  and confirm both compartments report sensible temperatures.
- The bond is held by BlueZ and does not reliably survive a host reboot on some
  setups; if the fridge stops reconnecting, put it back into PAIR mode.
- Losing the fridge is non-fatal: it retries with backoff and the rest of the
  program keeps running. If the cooler goes quiet for longer than `max_silence`
  the connection is rebuilt rather than serving stale readings.

**Provenance.** No vendor specification for this protocol is public. Every topic
id, UUID and encoding here comes from third-party projects that reverse-engineered
decompiled Dometic Android apps — see the references below. Details marked as
unverified in those projects are unverified here too, and the tables should be
confirmed against your own hardware with `tools/dometic_probe.py`.

## Data logging

Supports logging data to local MQTT brokers like [Mosquitto](https://mosquitto.org/) or [Home Assistant](https://www.home-assistant.io/) dashboards. You can also log it to third party cloud services like [PVOutput](https://pvoutput.org/). See [config.ini](https://github.com/cyrils/renogy-bt1/blob/main/config.ini) for more details. Note that free PVOutput accounts have a cap of one request per minute.

Example config to add to your home assistant `configuration.yaml`:
```yaml
mqtt:
  sensor:
    - name: "Solar Power"
      state_topic: "solar/state"
      device_class: "power"
      unit_of_measurement: "W"
      value_template: "{{ value_json.pv_power }}"
    - name: "Battery SOC"
      state_topic: "solar/state"
      device_class: "battery"
      unit_of_measurement: "%"
      value_template: "{{ value_json.battery_percentage }}"
# check output log for more fields
```

**Custom logging**

Should you choose to upload to your own server, the json data is posted as body of the HTTP POST call. The optional `auth_header` is sent as http header `Authorization: Bearer <auth-header>`

Example php code at the server:
```php
$headers = getallheaders();
if ($headers['Authorization'] != "Bearer 123456789") {
    header( 'HTTP/1.0 403 Forbidden', true, 403 );
    die('403 Forbidden');
}
$json_data = json_decode(file_get_contents('php://input'), true);
```

**How to get continues output?**

 The best way to get continues data is to schedule a cronjob by running `crontab -e` and insert the following command:
```sh
*/5 * * * * python3 /path/to/renogy-bt/example.py config.ini #runs every 5 mins
```
If you want to monitor real-time data, turn on polling in `config.ini` for continues streaming (default interval is 60 secs). You may also register it as a [service](https://github.com/cyrils/renogy-bt/issues/77) for added reliability.

### Disclaimer

¹This is not an official library endorsed by the device manufacturer. Renogy and all other trademarks in this repo are the property of their respective owners and their use herein does not imply any sponsorship or endorsement.

## References

**Dometic CFX / DDM protocol** — all reverse-engineered from decompiled Dometic
Android apps; there is no vendor specification.

 - [philippe-a11y/home-assistant-dometic-cfx](https://github.com/philippe-a11y/home-assistant-dometic-cfx) — implements both DDM1 and DDM2; source of the DDM2 GATT UUIDs, the `(param, 0, 0, 0x1A)` cooler-class topic scheme, the firmware-id family table, and the `power = voltage × current` derivation. Also documents the Linux/BlueZ bonding problems.
 - [icodeforyou/ha-dometic-ddm](https://github.com/icodeforyou/ha-dometic-ddm) — the `pyddm` library, its frame-format documentation, and `ddm2_parameters.json`, a parameter dictionary extracted from the Dometic Power app giving param id, unit, scaling factor and enum labels for every DDM2 parameter. Also carries the richer 86-topic DDM1 table and a real CFX3 session capture.
 - [mlamoure/dometic-ddmp](https://github.com/mlamoure/dometic-ddmp) — DDM2 library verified against a CFX5 25 on firmware `MC1_1.0.2`.
 - [philippe-a11y/esphome-dometic-cfx5](https://github.com/philippe-a11y/esphome-dometic-cfx5) — ESPHome DDM2 component.
 - [andrewbackway/esphome-dometic_cfx_ble](https://github.com/andrewbackway/esphome-dometic_cfx_ble) — ESPHome DDM1 component; its `protocol.md` is the clearest DDM1 write-up and it ships a runnable bleak test script.
 - [keshavdv/dometic-cfx3](https://github.com/keshavdv/dometic-cfx3) — DDM1 over WiFi TCP rather than BLE; source of the original 64-topic table.
 - [phil-gao/cfx3-ble-logger](https://github.com/phil-gao/cfx3-ble-logger) — documents the BlueZ traps when talking to a bonded cooler from plain Linux.

**Renogy**

 - [Olen/solar-monitor](https://github.com/Olen/solar-monitor)
 - [corbinbs/solarshed](https://github.com/corbinbs/solarshed)
 - [Renogy modbus documentation](https://github.com/cyrils/renogy-bt/discussions/94)
 - [mavenius/renogy-bt-esphome](//github.com/mavenius/renogy-bt-esphome) - ESPHome port of this project
