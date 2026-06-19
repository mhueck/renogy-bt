# BLE Client Specification

## Connection

| Parameter | Value |
|---|---|
| Device name | `SolarBLE` (configurable) |
| Service UUID | `0000ff10-0000-1000-8000-00805f9b34fb` |
| Mode | Read-only (client polls on its own schedule) |
| Byte order | Little-endian |

## Characteristics

### Battery — `0000ff11-0000-1000-8000-00805f9b34fb`

9 bytes, struct format `<BhHhh`:

| Offset | Size | Type | Field | Unit | Notes |
|---|---|---|---|---|---|
| 0 | 1 | uint8 | percentage | % | 0–100 |
| 1 | 2 | int16 | pct_change_1h | %×10 | Signed. Divide by 10 for actual change. Positive = charging, negative = discharging. 0 if no history available. |
| 3 | 2 | uint16 | voltage | V×100 | Divide by 100. e.g. 2650 = 26.50V |
| 5 | 2 | int16 | power | W×10 | Signed. Divide by 10. Negative = discharging, positive = charging. |
| 7 | 2 | int16 | temperature | °C×10 | Signed. Divide by 10. e.g. 235 = 23.5°C |

Total: 9 bytes

### Charger — `0000ff12-0000-1000-8000-00805f9b34fb`

4 bytes, struct format `<HH`:

| Offset | Size | Type | Field | Unit | Notes |
|---|---|---|---|---|---|
| 0 | 2 | uint16 | solar_power | W×10 | Divide by 10. Recalculated as pv_voltage × pv_current. |
| 2 | 2 | uint16 | alternator_power | W×10 | Divide by 10. Recalculated as alt_voltage × alt_current. |

### Weather — `0000ff13-0000-1000-8000-00805f9b34fb`

Reserved for future use. Currently empty (0 bytes).

## ESP32 Decoding Example (Arduino/C++)

```cpp
#include <BLEDevice.h>

static BLEUUID serviceUUID("0000ff10-0000-1000-8000-00805f9b34fb");
static BLEUUID batteryCharUUID("0000ff11-0000-1000-8000-00805f9b34fb");
static BLEUUID chargerCharUUID("0000ff12-0000-1000-8000-00805f9b34fb");
static BLEUUID weatherCharUUID("0000ff13-0000-1000-8000-00805f9b34fb");

struct __attribute__((packed)) BatteryData {
    uint8_t percentage;
    int16_t pct_change_1h_x10;
    uint16_t voltage_x100;
    int16_t power_x10;
    int16_t temperature_x10;
};

struct __attribute__((packed)) ChargerData {
    uint16_t solar_power_x10;
    uint16_t alternator_power_x10;
};

// After reading the characteristic value into `value` (std::string):
void decodeBattery(std::string &value) {
    if (value.length() < sizeof(BatteryData)) return;
    BatteryData *d = (BatteryData *)value.data();

    float percentage = d->percentage;
    float pct_change_1h = d->pct_change_1h_x10 / 10.0f;
    float voltage = d->voltage_x100 / 100.0f;
    float power = d->power_x10 / 10.0f;
    float temperature = d->temperature_x10 / 10.0f;
}

void decodeCharger(std::string &value) {
    if (value.length() < sizeof(ChargerData)) return;
    ChargerData *d = (ChargerData *)value.data();

    float solar_power = d->solar_power_x10 / 10.0f;
    float alternator_power = d->alternator_power_x10 / 10.0f;
}
```

## Polling Notes

- Server updates data approximately every 57 seconds.
- Recommended client poll interval: 60 seconds or longer.
- If battery characteristic reads all zeros, the server has not yet completed its first poll cycle.
- `pct_change_1h` will be 0 until the server has accumulated ~60 minutes of history.

## Future: Weather Characteristic

The weather characteristic (`ff13`) will be populated in a future update. The ESP32 client should handle a 0-length read gracefully (skip decoding if length is 0).
