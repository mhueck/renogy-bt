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

28 bytes, struct format `<IhBhhBHhhBHhhBH`:

| Offset | Size | Type | Field | Unit | Notes |
|---|---|---|---|---|---|
| 0 | 4 | uint32 | current_time_min | minutes | Unix timestamp / 60 |
| 4 | 2 | int16 | current_temp | °C×10 | Signed. Divide by 10. |
| 6 | 1 | uint8 | current_weather_code | WMO code | 0–99 |
| 7 | 2 | int16 | today_temp_min | °C×10 | Signed. |
| 9 | 2 | int16 | today_temp_max | °C×10 | Signed. |
| 11 | 1 | uint8 | today_weather_code | WMO code | 0–99 |
| 12 | 2 | uint16 | today_precipitation | mm×10 | |
| 14 | 2 | int16 | tomorrow_temp_min | °C×10 | Signed. |
| 16 | 2 | int16 | tomorrow_temp_max | °C×10 | Signed. |
| 18 | 1 | uint8 | tomorrow_weather_code | WMO code | 0–99 |
| 19 | 2 | uint16 | tomorrow_precipitation | mm×10 | |
| 21 | 2 | int16 | day_after_temp_min | °C×10 | Signed. |
| 23 | 2 | int16 | day_after_temp_max | °C×10 | Signed. |
| 25 | 1 | uint8 | day_after_weather_code | WMO code | 0–99 |
| 26 | 2 | uint16 | day_after_precipitation | mm×10 | |


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

struct __attribute__((packed)) DailyForecast {
    int16_t temp_min_x10;
    int16_t temp_max_x10;
    uint8_t weather_code;
    uint16_t precipitation_x10;
};

struct __attribute__((packed)) WeatherData {
    uint32_t current_time_min;
    int16_t current_temp_x10;
    uint8_t current_weather_code;
    DailyForecast forecast[3];
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

void decodeWeather(std::string &value) {
    if (value.length() < sizeof(WeatherData)) return;
    WeatherData *d = (WeatherData *)value.data();

    uint32_t current_time_min = d->current_time_min;
    float current_temp = d->current_temp_x10 / 10.0f;
    uint8_t current_weather_code = d->current_weather_code;

    for (int i = 0; i < 3; i++) {
        float temp_min = d->forecast[i].temp_min_x10 / 10.0f;
        float temp_max = d->forecast[i].temp_max_x10 / 10.0f;
        uint8_t weather_code = d->forecast[i].weather_code;
        float precipitation = d->forecast[i].precipitation_x10 / 10.0f;
    }
}
```

## Polling Notes

- Server updates battery/charger data approximately every 57 seconds.
- Recommended client poll interval: 60 seconds or longer.
- If battery characteristic reads all zeros, the server has not yet completed its first poll cycle.
- `pct_change_1h` will be 0 until the server has accumulated ~60 minutes of history.

## Weather Characteristic

The weather characteristic (`ff13`) is updated every 10 minutes based on GPS data fetched from Open-Meteo. The ESP32 client should verify the length is 28 bytes before decoding.

