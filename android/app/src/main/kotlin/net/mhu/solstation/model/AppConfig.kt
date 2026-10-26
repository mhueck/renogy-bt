package net.mhu.solstation.model

import android.content.Context
import org.json.JSONObject

data class GpsDeviceConfig(
    val enabled: Boolean = true,
    val macAddr: String = "18:8B:0E:93:07:1E",
    val alias: String = "ESP32_MODBUS",
    val deviceId: Int = 255,
    val name: String = "BLE_ESP"
)

data class ChargerDeviceConfig(
    val enabled: Boolean = true,
    val macAddr: String = "CC:45:A5:AB:9E:CE",
    val alias: String = "BT-TH-A5AB9ECE",
    val deviceId: Int = 255,
    val name: String = "RNG_DCC"
)

data class BatteryDeviceConfig(
    val enabled: Boolean = true,
    val macAddr: String = "A5:C2:37:46:31:18",
    val alias: String = "DP04S007L4S200A",
    val name: String = "EW_BAT",
    val readCellVoltages: Boolean = false
)

data class FridgeDeviceConfig(
    val enabled: Boolean = true,
    val macAddr: String = "D4:8C:49:14:18:DE",
    val alias: String = "DOMETIC_CFX",
    val name: String = "DOMETIC",
    val protocol: String = "auto", // auto | ddm1 | ddm2
    val productType: String = "dz", // sz | szi | dz
    val pair: Boolean = true,
    val maxSilenceSeconds: Int = 900
)

data class BleServerConfig(
    val enabled: Boolean = true,
    val name: String = "SolarBLE"
)

data class InfluxDb2Config(
    val enabled: Boolean = false,
    val url: String = "http://localhost:8086",
    val org: String = "my-org",
    val bucket: String = "my_db",
    val token: String = "",
    val fieldsFilter: String = ""
)

data class AppConfig(
    val enablePolling: Boolean = true,
    val gps: GpsDeviceConfig = GpsDeviceConfig(),
    val charger: ChargerDeviceConfig = ChargerDeviceConfig(),
    val battery: BatteryDeviceConfig = BatteryDeviceConfig(),
    val fridge: FridgeDeviceConfig = FridgeDeviceConfig(),
    val bleServer: BleServerConfig = BleServerConfig(),
    val influxDb2: InfluxDb2Config = InfluxDb2Config()
)

class ConfigRepository(context: Context) {
    private val prefs = context.getSharedPreferences("renogy_bt_config", Context.MODE_PRIVATE)

    fun loadConfig(): AppConfig {
        return AppConfig(
            enablePolling = prefs.getBoolean("enable_polling", true),
            gps = GpsDeviceConfig(
                enabled = prefs.getBoolean("gps_enabled", true),
                macAddr = prefs.getString("gps_mac", "18:8B:0E:93:07:1E") ?: "18:8B:0E:93:07:1E",
                alias = prefs.getString("gps_alias", "ESP32_MODBUS") ?: "ESP32_MODBUS",
                deviceId = prefs.getInt("gps_device_id", 255),
                name = prefs.getString("gps_name", "BLE_ESP") ?: "BLE_ESP"
            ),
            charger = ChargerDeviceConfig(
                enabled = prefs.getBoolean("charger_enabled", true),
                macAddr = prefs.getString("charger_mac", "CC:45:A5:AB:9E:CE") ?: "CC:45:A5:AB:9E:CE",
                alias = prefs.getString("charger_alias", "BT-TH-A5AB9ECE") ?: "BT-TH-A5AB9ECE",
                deviceId = prefs.getInt("charger_device_id", 255),
                name = prefs.getString("charger_name", "RNG_DCC") ?: "RNG_DCC"
            ),
            battery = BatteryDeviceConfig(
                enabled = prefs.getBoolean("battery_enabled", true),
                macAddr = prefs.getString("battery_mac", "A5:C2:37:46:31:18") ?: "A5:C2:37:46:31:18",
                alias = prefs.getString("battery_alias", "DP04S007L4S200A") ?: "DP04S007L4S200A",
                name = prefs.getString("battery_name", "EW_BAT") ?: "EW_BAT",
                readCellVoltages = prefs.getBoolean("battery_read_cellv", false)
            ),
            fridge = FridgeDeviceConfig(
                enabled = prefs.getBoolean("fridge_enabled", true),
                macAddr = prefs.getString("fridge_mac", "D4:8C:49:14:18:DE") ?: "D4:8C:49:14:18:DE",
                alias = prefs.getString("fridge_alias", "DOMETIC_CFX") ?: "DOMETIC_CFX",
                name = prefs.getString("fridge_name", "DOMETIC") ?: "DOMETIC",
                protocol = prefs.getString("fridge_protocol", "auto") ?: "auto",
                productType = prefs.getString("fridge_product_type", "dz") ?: "dz",
                pair = prefs.getBoolean("fridge_pair", true),
                maxSilenceSeconds = prefs.getInt("fridge_max_silence", 900)
            ),
            bleServer = BleServerConfig(
                enabled = prefs.getBoolean("ble_server_enabled", true),
                name = prefs.getString("ble_server_name", "SolarBLE") ?: "SolarBLE"
            ),
            influxDb2 = InfluxDb2Config(
                enabled = prefs.getBoolean("influx_enabled", false),
                url = prefs.getString("influx_url", "http://localhost:8086") ?: "http://localhost:8086",
                org = prefs.getString("influx_org", "my-org") ?: "my-org",
                bucket = prefs.getString("influx_bucket", "my_db") ?: "my_db",
                token = prefs.getString("influx_token", "") ?: "",
                fieldsFilter = prefs.getString("influx_fields", "") ?: ""
            )
        )
    }

    fun saveConfig(config: AppConfig) {
        prefs.edit().apply {
            putBoolean("enable_polling", config.enablePolling)

            putBoolean("gps_enabled", config.gps.enabled)
            putString("gps_mac", config.gps.macAddr.trim())
            putString("gps_alias", config.gps.alias.trim())
            putInt("gps_device_id", config.gps.deviceId)
            putString("gps_name", config.gps.name.trim())

            putBoolean("charger_enabled", config.charger.enabled)
            putString("charger_mac", config.charger.macAddr.trim())
            putString("charger_alias", config.charger.alias.trim())
            putInt("charger_device_id", config.charger.deviceId)
            putString("charger_name", config.charger.name.trim())

            putBoolean("battery_enabled", config.battery.enabled)
            putString("battery_mac", config.battery.macAddr.trim())
            putString("battery_alias", config.battery.alias.trim())
            putString("battery_name", config.battery.name.trim())
            putBoolean("battery_read_cellv", config.battery.readCellVoltages)

            putBoolean("fridge_enabled", config.fridge.enabled)
            putString("fridge_mac", config.fridge.macAddr.trim())
            putString("fridge_alias", config.fridge.alias.trim())
            putString("fridge_name", config.fridge.name.trim())
            putString("fridge_protocol", config.fridge.protocol.trim())
            putString("fridge_product_type", config.fridge.productType.trim())
            putBoolean("fridge_pair", config.fridge.pair)
            putInt("fridge_max_silence", config.fridge.maxSilenceSeconds)

            putBoolean("ble_server_enabled", config.bleServer.enabled)
            putString("ble_server_name", config.bleServer.name.trim())

            putBoolean("influx_enabled", config.influxDb2.enabled)
            putString("influx_url", config.influxDb2.url.trim())
            putString("influx_org", config.influxDb2.org.trim())
            putString("influx_bucket", config.influxDb2.bucket.trim())
            putString("influx_token", config.influxDb2.token.trim())
            putString("influx_fields", config.influxDb2.fieldsFilter.trim())
            apply()
        }
    }

    fun loadSavedGpsCoords(): Pair<Double, Double>? {
        val raw = prefs.getString("saved_gps_coords", null) ?: return null
        return try {
            val obj = JSONObject(raw)
            if (obj.has("lat") && obj.has("lon") && !obj.isNull("lat") && !obj.isNull("lon")) {
                Pair(obj.getDouble("lat"), obj.getDouble("lon"))
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun saveGpsCoords(lat: Double, lon: Double) {
        val obj = JSONObject().apply {
            put("lat", lat)
            put("lon", lon)
        }
        prefs.edit().putString("saved_gps_coords", obj.toString()).apply()
    }
}
