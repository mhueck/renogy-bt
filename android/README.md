# SolStation Android App

An Android application (Kotlin + Jetpack Compose) that replicates the Python `renogy-bt` solar monitoring daemon on Android, with:
- **Multi-device BLE Polling** (non-fatal with automatic GATT cache recovery and UI status/staleness indicators):
  - **Renogy DC-DC / MPPT Charger (`DCChargerClient`)** via Modbus-over-BLE (`0000ffd0-...`)
  - **Eco-Worthy Battery BMS (`EcoWorthyClient`)** (`0000ff00-...`, basic info + optional cell voltages)
  - **Dometic CFX Cooler (`DometicClient`)** supporting both **DDM1** (CFX3, `537a0300-...`) and **DDM2** (CFX2/CFX5, `537a0400-...`) pub/sub protocols, BLE bonding, heartbeat pings, and rolling 1-hour Watt-hour integration (`EnergyWindow`)
  - **ESP32 GPS + BME680 Sensor (`BleEspClient`)** (`4fafc201-...`) over BLE only (persisting and retaining the last known good location whenever the ESP32 is disconnected or has no satellite lock, avoiding inaccurate Starlink Wi-Fi geolocation)
- **Open-Meteo Weather Polling**:
  - Polls current temperature, WMO weather code, and 3-day forecast every 60 seconds using resolved GPS coordinates
- **BLE GATT Server (`SolarBLE`) for ESP32 Display**:
  - Advertises primary service `0000ff10-0000-1000-8000-00805f9b34fb` so the ESP32 display can connect and poll read-only characteristics on its schedule:
    - `0000ff11-0000-1000-8000-00805f9b34fb` — **Battery** (9 bytes, `<BhHhh`)
    - `0000ff12-0000-1000-8000-00805f9b34fb` — **Charger** (4 bytes, `<HH`)
    - `0000ff13-0000-1000-8000-00805f9b34fb` — **Weather** (28 bytes, `<IhBhhBHhhBHhhBH`)
    - `0000ff14-0000-1000-8000-00805f9b34fb` — **Fridge** (19 bytes, `<hhhhhHHBBBH`, with live `age` calculated on read; returns 0 bytes before first fridge publish)
- **InfluxDB 2.0 Integration**:
  - Writes measurements in InfluxDB v2 Line Protocol over HTTP `POST /api/v2/write?org=<org>&bucket=<bucket>&precision=s` (with `Authorization: Token <token>`), tagging string fields and recording numeric fields.
- **Foreground Service & Live UI**:
  - Runs as a persistent Android Foreground Service (`connectedDevice`)
  - Includes a **Dashboard** tab, **Settings** tab (pre-populated from `config.ini` with a built-in BLE device scanner), and a **Logs** tab.

---

## Build & Installation Instructions

The Android SDK (`platforms/android-36`, `build-tools/36.0.0`, and `platform-tools` / `adb`) is installed in `~/Android/Sdk` and configured in `local.properties`.

### 1. Install JDK 17 (CachyOS / Arch Linux)
Gradle requires JDK 17+. Install OpenJDK 17:

```bash
sudo pacman -S jdk17-openjdk
```

*(If multiple Java versions are installed, check with `archlinux-java status` and select Java 17 if needed via `sudo archlinux-java set java-17-openjdk`.)*

*(If building on another machine without the Android SDK packages installed yet, you can install them via the `android` CLI: `android sdk install platforms/android-36 build-tools/36.0.0 platform-tools`.)*

### 2. Build the Debug APK
From the `android/` directory, run the Gradle wrapper:

```bash
cd /home/mhueck/dev/git/renogy-bt/android
./gradlew assembleDebug
```

This generates the APK at:
`android/app/build/outputs/apk/debug/app-debug.apk`

### 3. Prepare Your Android Device
1. Open **Settings → About phone** and tap **Build number** 7 times to unlock Developer Options.
2. Open **Settings → System → Developer options** and enable **USB debugging** (or **Wireless debugging**).
3. Connect the phone via USB and tap **Allow** on the USB debugging authorization prompt.

### 4. Install & Launch the App
Verify your phone is detected and install the APK using `adb`:

```bash
# Check connected device
~/Android/Sdk/platform-tools/adb devices

# Install (or update) the APK
~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Alternatives:
- Build and install in one step: `./gradlew installDebug`
- Install and launch with the Android CLI: `android run --apks=app/build/outputs/apk/debug/app-debug.apk`

### 5. Recommended Van Setup on Android
1. Open **SolStation** and grant the Bluetooth (Scan / Connect / Advertise) and Notification permissions.
2. On Android, open **Settings → Apps → SolStation → App battery usage** and set it to **Unrestricted** so Android never throttles continuous background BLE polling or `SolarBLE` GATT server advertising.
3. Open the **Settings** tab in the app, verify your BLE MAC addresses and InfluxDB 2.0 URL / Org / Bucket / Token, tap **Save Settings & Apply**, and tap **Start**.
