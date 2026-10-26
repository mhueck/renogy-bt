package net.mhu.solstation.model

enum class ConnectionPhase {
    DISABLED,
    IDLE,
    STANDBY,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class DeviceHealth(
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val lastSuccessMillis: Long? = null,
    val lastAttemptMillis: Long? = null,
    val consecutiveFailures: Int = 0,
    val lastError: String? = null
)

data class DailyForecastItem(
    val tempMin: Double,
    val tempMax: Double,
    val weatherCode: Int,
    val precipitationSum: Double
)

data class WeatherState(
    val currentTemp: Double = 0.0,
    val currentWeatherCode: Int = 0,
    val forecast: List<DailyForecastItem> = emptyList(),
    val updatedAtMillis: Long? = null,
    val lastError: String? = null
)

data class BleServerState(
    val advertising: Boolean = false,
    val serverName: String = "SolarBLE",
    val connectedDevicesCount: Int = 0,
    val lastReadCharacteristic: String? = null,
    val lastReadTimeMillis: Long? = null,
    val error: String? = null
)

data class InfluxStatusState(
    val lastWriteTimeMillis: Long? = null,
    val totalWrites: Int = 0,
    val lastError: String? = null
)

data class SolarMonitorState(
    val serviceRunning: Boolean = false,
    val bleServer: BleServerState = BleServerState(),
    val influxStatus: InfluxStatusState = InfluxStatusState(),
    val batteryHealth: DeviceHealth = DeviceHealth(),
    val batteryData: Map<String, Any?> = emptyMap(),
    val batteryPctChange1h: Double = 0.0,
    val batteryUpdatedAt: Long? = null,
    val chargerHealth: DeviceHealth = DeviceHealth(),
    val chargerData: Map<String, Any?> = emptyMap(),
    val chargerUpdatedAt: Long? = null,
    val fridgeHealth: DeviceHealth = DeviceHealth(),
    val fridgeData: Map<String, Any?> = emptyMap(),
    val fridgeUpdatedAt: Long? = null,
    val gpsHealth: DeviceHealth = DeviceHealth(),
    val gpsEnvData: Map<String, Any?> = emptyMap(),
    val gpsSource: String = "None",
    val gpsUpdatedAt: Long? = null,
    val currentLat: Double? = null,
    val currentLon: Double? = null,
    val weather: WeatherState? = null
)
