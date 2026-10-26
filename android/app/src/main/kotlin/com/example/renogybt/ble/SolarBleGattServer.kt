package com.example.renogybt.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import com.example.renogybt.model.BleServerState
import com.example.renogybt.model.DailyForecastItem
import com.example.renogybt.model.WeatherState
import com.example.renogybt.service.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.ArrayDeque
import java.util.Collections
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Android BLE GATT Server acting as `SolarBLE` so the ESP32 display can connect and poll
 * characteristics `ff11` (Battery), `ff12` (Charger), `ff13` (Weather), and `ff14` (Fridge),
 * matching `BLEServer.py` and `BLE_CLIENT_SPEC.md`.
 */
@SuppressLint("MissingPermission")
class SolarBleGattServer(
    private val context: Context,
    private val serverName: String = "SolarBLE"
) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000ff10-0000-1000-8000-00805f9b34fb")
        val BATTERY_CHAR_UUID: UUID = UUID.fromString("0000ff11-0000-1000-8000-00805f9b34fb")
        val CHARGER_CHAR_UUID: UUID = UUID.fromString("0000ff12-0000-1000-8000-00805f9b34fb")
        val WEATHER_CHAR_UUID: UUID = UUID.fromString("0000ff13-0000-1000-8000-00805f9b34fb")
        val FRIDGE_CHAR_UUID: UUID = UUID.fromString("0000ff14-0000-1000-8000-00805f9b34fb")

        private const val HISTORY_SIZE = 70
        private const val HOUR_SECONDS = 3600.0
    }

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var originalAdapterName: String? = null
    // stop() is final: each pipeline creates its own instance, so a late start()/restart() (e.g.
    // from the Bluetooth receiver racing a teardown) must not reopen one nobody references anymore.
    // Guarded by the instance monitor.
    private var stopped = false
    private val connectedDevices = Collections.synchronizedSet(HashSet<BluetoothDevice>())

    private val pctHistory = ArrayDeque<Pair<Double, Double>>()

    @Volatile
    private var batteryData: ByteArray = ByteArray(9)
    @Volatile
    private var chargerData: ByteArray = ByteArray(4)
    @Volatile
    private var weatherData: ByteArray = ByteArray(28)
    @Volatile
    private var fridgeHead: ByteArray? = null
    @Volatile
    private var fridgeUpdatedAtSec: Double = 0.0

    @Volatile
    var lastPctChange1h: Double = 0.0
        private set

    private val _state = MutableStateFlow(BleServerState(serverName = serverName))
    val state: StateFlow<BleServerState> = _state.asStateFlow()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            AppLogger.info("BLE GATT Server '$serverName' advertising started (UUID: $SERVICE_UUID)")
            _state.update { it.copy(advertising = true, error = null) }
        }

        override fun onStartFailure(errorCode: Int) {
            val errMsg = "BLE Advertising failed with code $errorCode"
            AppLogger.error(errMsg)
            _state.update { it.copy(advertising = false, error = errMsg) }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevices.add(device)
                AppLogger.info("ESP32 Display connected to GATT Server: ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevices.remove(device)
                AppLogger.info("ESP32 Display disconnected from GATT Server: ${device.address}")
            }
            _state.update { it.copy(connectedDevicesCount = connectedDevices.size) }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val server = gattServer ?: return
            val fullPayload: ByteArray = when (characteristic.uuid) {
                BATTERY_CHAR_UUID -> batteryData
                CHARGER_CHAR_UUID -> chargerData
                WEATHER_CHAR_UUID -> weatherData
                FRIDGE_CHAR_UUID -> {
                    val head = fridgeHead
                    if (head == null) {
                        // Per BLE_CLIENT_SPEC.md: absent or zero-length read means "no fridge configured"
                        ByteArray(0)
                    } else {
                        val nowSec = System.currentTimeMillis() / 1000.0
                        val age = min(65535, max(0, (nowSec - fridgeUpdatedAtSec).toInt()))
                        BleUtils.appendFridgeAge(head, age)
                    }
                }
                else -> {
                    server.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                    return
                }
            }

            val label = when (characteristic.uuid) {
                BATTERY_CHAR_UUID -> "Battery (ff11)"
                CHARGER_CHAR_UUID -> "Charger (ff12)"
                WEATHER_CHAR_UUID -> "Weather (ff13)"
                FRIDGE_CHAR_UUID -> "Fridge (ff14)"
                else -> characteristic.uuid.toString()
            }

            val slice = if (offset >= fullPayload.size) {
                ByteArray(0)
            } else {
                fullPayload.copyOfRange(offset, fullPayload.size)
            }

            server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            _state.update {
                it.copy(
                    lastReadCharacteristic = label,
                    lastReadTimeMillis = System.currentTimeMillis()
                )
            }
        }
    }

    @Synchronized
    fun start() {
        if (stopped) return
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: throw Exception("BluetoothManager unavailable")
        val adapter = btManager.adapter
            ?: throw Exception("BluetoothAdapter unavailable")
        if (!adapter.isEnabled) {
            throw Exception("Bluetooth is disabled")
        }

        try {
            // Only capture once, so a restart() never records our own server name as the original
            if (originalAdapterName == null) {
                originalAdapterName = adapter.name
            }
            if (serverName.isNotBlank() && adapter.name != serverName) {
                adapter.name = serverName
            }
        } catch (e: Exception) {
            AppLogger.warn("Could not set Bluetooth adapter name to '$serverName': ${e.message}")
        }

        // A second start() (e.g. restart() racing the pipeline's start) replaces rather than leaks
        if (gattServer != null || advertiser != null) {
            closeServerAndAdvertiser()
        }

        val server = btManager.openGattServer(context, gattServerCallback)
            ?: throw Exception("Failed to open BluetoothGattServer")

        val service = BluetoothGattService(
            SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val batteryChar = BluetoothGattCharacteristic(
            BATTERY_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val chargerChar = BluetoothGattCharacteristic(
            CHARGER_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val weatherChar = BluetoothGattCharacteristic(
            WEATHER_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val fridgeChar = BluetoothGattCharacteristic(
            FRIDGE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        service.addCharacteristic(batteryChar)
        service.addCharacteristic(chargerChar)
        service.addCharacteristic(weatherChar)
        service.addCharacteristic(fridgeChar)

        server.addService(service)
        gattServer = server

        val leAdvertiser = adapter.bluetoothLeAdvertiser
        if (leAdvertiser != null) {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build()

            val advertiseData = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()

            val scanResponse = AdvertiseData.Builder()
                .setIncludeTxPowerLevel(true)
                .build()

            advertiser = leAdvertiser
            leAdvertiser.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
        } else {
            val msg = "BLE Peripheral Advertising not supported on this hardware"
            AppLogger.warn(msg)
            _state.update { it.copy(advertising = false, error = msg) }
        }
    }

    /**
     * Reopens the GATT server and advertiser, keeping the cached payloads. Needed after a
     * Bluetooth off/on or stack restart, which silently kills both.
     */
    @Synchronized
    fun restart() {
        if (stopped) return
        closeServerAndAdvertiser()
        start()
    }

    private fun closeServerAndAdvertiser() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {
        }
        advertiser = null

        try {
            gattServer?.clearServices()
            gattServer?.close()
        } catch (_: Exception) {
        }
        gattServer = null
        connectedDevices.clear()
        _state.update { it.copy(advertising = false, connectedDevicesCount = 0) }
    }

    @Synchronized
    fun stop() {
        stopped = true
        closeServerAndAdvertiser()

        val origName = originalAdapterName
        if (origName != null) {
            try {
                val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                btManager?.adapter?.name = origName
            } catch (_: Exception) {
            }
        }
        AppLogger.info("BLE GATT Server stopped")
    }

    @Synchronized
    fun updateBattery(
        percentage: Double,
        power: Double,
        voltage: Double,
        temperature: Double
    ): Double {
        val nowSec = System.currentTimeMillis() / 1000.0
        if (pctHistory.size >= HISTORY_SIZE) {
            pctHistory.removeFirst()
        }
        pctHistory.addLast(nowSec to percentage)
        val pctChange = calcPctChange1h(nowSec, percentage)
        lastPctChange1h = pctChange

        batteryData = BleUtils.packBatteryCharacteristic(
            percentage = percentage,
            pctChange1h = pctChange,
            voltage = voltage,
            power = power,
            temperature = temperature
        )
        return pctChange
    }

    fun updateCharger(
        pvVoltage: Double,
        pvCurrent: Double,
        alternatorVoltage: Double,
        alternatorCurrent: Double
    ) {
        chargerData = BleUtils.packChargerCharacteristic(
            pvVoltage = pvVoltage,
            pvCurrent = pvCurrent,
            alternatorVoltage = alternatorVoltage,
            alternatorCurrent = alternatorCurrent
        )
    }

    fun updateFridge(fridgeDataMap: Map<String, Any?>) {
        fridgeHead = BleUtils.packFridgeHead(fridgeDataMap)
        val secondsSinceUpdate = (fridgeDataMap["seconds_since_update"] as? Number)?.toDouble() ?: 0.0
        fridgeUpdatedAtSec = (System.currentTimeMillis() / 1000.0) - secondsSinceUpdate
    }

    fun updateWeather(weatherState: WeatherState) {
        val currentTimeMin = (System.currentTimeMillis() / 1000L) / 60L
        val d0 = weatherState.forecast.getOrNull(0) ?: DailyForecastItem(0.0, 0.0, 0, 0.0)
        val d1 = weatherState.forecast.getOrNull(1) ?: DailyForecastItem(0.0, 0.0, 0, 0.0)
        val d2 = weatherState.forecast.getOrNull(2) ?: DailyForecastItem(0.0, 0.0, 0, 0.0)

        weatherData = BleUtils.packWeatherCharacteristic(
            currentTimeMin = currentTimeMin,
            currentTemp = weatherState.currentTemp,
            currentCode = weatherState.currentWeatherCode,
            d0Min = d0.tempMin, d0Max = d0.tempMax, d0Code = d0.weatherCode, d0Prec = d0.precipitationSum,
            d1Min = d1.tempMin, d1Max = d1.tempMax, d1Code = d1.weatherCode, d1Prec = d1.precipitationSum,
            d2Min = d2.tempMin, d2Max = d2.tempMax, d2Code = d2.weatherCode, d2Prec = d2.precipitationSum
        )
    }

    private fun calcPctChange1h(nowSec: Double, currentPct: Double): Double {
        if (pctHistory.isEmpty()) return 0.0
        val targetTime = nowSec - HOUR_SECONDS
        val closest = pctHistory.minByOrNull { abs(it.first - targetTime) } ?: return 0.0
        if (abs(closest.first - targetTime) > HOUR_SECONDS * 0.5) {
            return 0.0
        }
        return currentPct - closest.second
    }
}
