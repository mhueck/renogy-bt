package net.mhu.solstation.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.SystemClock
import net.mhu.solstation.service.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

data class ScannedBleDevice(
    val name: String,
    val address: String,
    val rssi: Int
)

/**
 * Android BLE GATT Client connection manager with a shared connection Mutex,
 * mirroring Python's BLEManager + BleakClient (including ATT MTU negotiation,
 * strict FIFO notification ordering, and clean link teardown).
 */
@SuppressLint("MissingPermission")
class BleConnectionManager(
    private val context: Context,
    val macAddress: String,
    val alias: String
) {
    companion object {
        val connectionLock = Mutex()
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val DEFAULT_DISCOVERY_TIMEOUT_MS = 10_000L
        private const val CONNECTION_TIMEOUT_MS = 25_000L
        private const val TARGET_MTU = 517
        private const val BOND_TIMEOUT_MS = 15_000L
        private const val BOND_START_GRACE_MS = 1_500L

        @SuppressLint("MissingPermission")
        suspend fun scanDevices(context: Context, durationMs: Long = 5000L): List<ScannedBleDevice> {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = btManager?.adapter ?: return emptyList()
            val scanner = adapter.bluetoothLeScanner ?: return emptyList()
            val found = LinkedHashMap<String, ScannedBleDevice>()

            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device ?: return
                    val address = device.address ?: return
                    val name = result.scanRecord?.deviceName ?: device.name ?: "Unknown"
                    found[address] = ScannedBleDevice(name = name, address = address, rssi = result.rssi)
                }
            }

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            return try {
                scanner.startScan(null, settings, callback)
                delay(durationMs)
                found.values.sortedByDescending { it.rssi }
            } catch (e: Exception) {
                AppLogger.warn("BLE scan error: ${e.message}")
                found.values.toList()
            } finally {
                try {
                    scanner.stopScan(callback)
                } catch (_: Exception) {
                }
            }
        }
    }

    private var gatt: BluetoothGatt? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    var currentMtu: Int = 23
        private set

    private var connectDeferred: CompletableDeferred<Boolean>? = null
    private var disconnectDeferred: CompletableDeferred<Unit>? = null
    private var servicesDeferred: CompletableDeferred<List<BluetoothGattService>>? = null
    private var mtuDeferred: CompletableDeferred<Int>? = null
    private var writeDeferred: CompletableDeferred<Int>? = null
    private var descriptorWriteDeferred: CompletableDeferred<Int>? = null
    private val writeMutex = Mutex()

    // Emit synchronously from Android's single-threaded GATT Binder callback to preserve
    // strict packet arrival order when multi-chunk Modbus / JBD BMS frames arrive back-to-back.
    private val _notifications = MutableSharedFlow<Pair<UUID, ByteArray>>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val notifications: SharedFlow<Pair<UUID, ByteArray>> = _notifications.asSharedFlow()

    fun isActuallyConnected(): Boolean {
        val g = gatt ?: return false
        if (!isConnected) return false
        return try {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val state = btManager?.getConnectionState(g.device, BluetoothProfile.GATT)
            state == BluetoothProfile.STATE_CONNECTED
        } catch (_: Exception) {
            isConnected
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                isConnected = true
                connectDeferred?.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                disconnectDeferred?.complete(Unit)
                connectDeferred?.completeExceptionally(
                    Exception("Disconnected from $macAddress (status=$status)")
                )
                servicesDeferred?.completeExceptionally(
                    Exception("Disconnected during service discovery ($macAddress)")
                )
                mtuDeferred?.complete(23)
                descriptorWriteDeferred?.completeExceptionally(
                    Exception("Disconnected during descriptor write ($macAddress)")
                )
                writeDeferred?.completeExceptionally(
                    Exception("Disconnected during write ($macAddress)")
                )
                try {
                    g.close()
                } catch (_: Exception) {
                }
                if (gatt === g) {
                    gatt = null
                }
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                isConnected = false
                disconnectDeferred?.complete(Unit)
                connectDeferred?.completeExceptionally(
                    Exception("GATT connection error status=$status for $macAddress")
                )
                try {
                    g.close()
                } catch (_: Exception) {
                }
                if (gatt === g) {
                    gatt = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                servicesDeferred?.complete(g.services.orEmpty())
            } else {
                servicesDeferred?.completeExceptionally(
                    Exception("Service discovery failed with status=$status")
                )
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                currentMtu = mtu
            }
            mtuDeferred?.complete(mtu)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeDeferred?.complete(status)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            descriptorWriteDeferred?.complete(status)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                val bytes = characteristic.value?.copyOf() ?: return
                _notifications.tryEmit(characteristic.uuid to bytes)
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val bytes = value.copyOf()
            _notifications.tryEmit(characteristic.uuid to bytes)
        }
    }

    suspend fun connect(
        tryDirectFirst: Boolean = false,
        requestBond: Boolean = false,
        allowDirectFallback: Boolean = true,
        scanTimeoutMs: Long = DEFAULT_DISCOVERY_TIMEOUT_MS
    ) {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: throw Exception("BluetoothManager unavailable")
        val adapter = btManager.adapter
            ?: throw Exception("BluetoothAdapter unavailable")
        if (!adapter.isEnabled) {
            throw Exception("Bluetooth is disabled")
        }

        val normalizedMac = macAddress.trim().uppercase()
        if (!BluetoothAdapter.checkBluetoothAddress(normalizedMac)) {
            throw Exception("Invalid MAC address: $normalizedMac")
        }

        // Perform BLE advertisement scan outside the global GATT connectionLock so scanning
        // for an offline device (e.g. ignition-switched GPS when parked) never blocks other devices.
        val preScannedDevice: BluetoothDevice? = if (!tryDirectFirst) {
            val found = findDeviceByAddress(adapter, normalizedMac, scanTimeoutMs)
            if (found == null && !allowDirectFallback) {
                throw Exception("$alias ($normalizedMac) not advertising")
            }
            found
        } else {
            null
        }

        connectionLock.withLock {
            connectLocked(
                adapter = adapter,
                normalizedMac = normalizedMac,
                preScannedDevice = preScannedDevice,
                tryDirectFirst = tryDirectFirst,
                requestBond = requestBond,
                allowDirectFallback = allowDirectFallback,
                scanTimeoutMs = scanTimeoutMs
            )
        }
    }

    private suspend fun connectLocked(
        adapter: BluetoothAdapter,
        normalizedMac: String,
        preScannedDevice: BluetoothDevice?,
        tryDirectFirst: Boolean,
        requestBond: Boolean,
        allowDirectFallback: Boolean,
        scanTimeoutMs: Long
    ) {
        var device: BluetoothDevice? = preScannedDevice
        if (tryDirectFirst) {
            try {
                val direct = adapter.getRemoteDevice(normalizedMac)
                device = direct
                establishGatt(direct, timeoutMs = CONNECTION_TIMEOUT_MS)
            } catch (e: Exception) {
                AppLogger.debug("Direct connect to $alias ($normalizedMac) failed (${e.message}), scanning...")
                closeInternal(refreshCache = false, waitDisconnect = false)
                device = null
            }
        }

        if (!isConnected) {
            val target = device ?: if (tryDirectFirst) {
                findDeviceByAddress(adapter, normalizedMac, scanTimeoutMs)
            } else {
                null
            }

            if (target != null) {
                device = target
                establishGatt(target, timeoutMs = CONNECTION_TIMEOUT_MS)
            } else if (allowDirectFallback) {
                val remote = adapter.getRemoteDevice(normalizedMac)
                device = remote
                try {
                    establishGatt(remote, timeoutMs = 10_000L)
                } catch (e: Exception) {
                    closeInternal(refreshCache = true, waitDisconnect = false)
                    throw Exception("$alias ($normalizedMac) unreachable (${e.message ?: "no advertisement"})")
                }
            } else {
                throw Exception("$alias ($normalizedMac) not found")
            }
        }

        try {
            if (requestBond && device != null) {
                awaitBond(device, normalizedMac)
            }
            discoverServices()
            negotiateMtu(TARGET_MTU)
        } catch (e: Exception) {
            closeInternal(refreshCache = true, waitDisconnect = true)
            throw e
        }
    }

    /**
     * Like Bleak's pair(), waits for the bond to finish before service discovery: a Dometic
     * exposes no services until bonded, so discovering mid-pairing comes back empty.
     */
    private suspend fun awaitBond(device: BluetoothDevice, normalizedMac: String) {
        try {
            if (device.bondState == BluetoothDevice.BOND_NONE) {
                // createBond() also returns false when the peer's Security Request already started it
                if (!device.createBond() && device.bondState != BluetoothDevice.BOND_BONDING) {
                    AppLogger.debug("createBond not started for $alias ($normalizedMac)")
                    return
                }
                AppLogger.info("Initiated BLE bonding with $alias ($normalizedMac)")
            }
            val startedAt = SystemClock.elapsedRealtime()
            val bonded = withTimeoutOrNull(BOND_TIMEOUT_MS) {
                var sawBonding = false
                var state = device.bondState
                // This runs under connectionLock, so stop as soon as pairing can no longer succeed:
                // NONE after BONDING (or after the grace period, since a fast rejection can slip
                // between polls) means it was rejected, and a dropped link means it cannot finish.
                while (state != BluetoothDevice.BOND_BONDED && isConnected &&
                    !(state == BluetoothDevice.BOND_NONE &&
                        (sawBonding || SystemClock.elapsedRealtime() - startedAt >= BOND_START_GRACE_MS))
                ) {
                    if (state == BluetoothDevice.BOND_BONDING) sawBonding = true
                    delay(250L)
                    state = device.bondState
                }
                state == BluetoothDevice.BOND_BONDED
            } ?: false
            if (!bonded) {
                AppLogger.warn("Bonding with $alias ($normalizedMac) not completed (bondState=${device.bondState})")
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLogger.debug("createBond skipped for $alias: ${e.message}")
        }
    }

    private suspend fun establishGatt(device: BluetoothDevice, timeoutMs: Long = CONNECTION_TIMEOUT_MS) {
        closeInternal(refreshCache = false, waitDisconnect = false)
        currentMtu = 23
        val deferred = CompletableDeferred<Boolean>()
        connectDeferred = deferred
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
        try {
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (e: Exception) {
            closeInternal(refreshCache = false, waitDisconnect = false)
            throw e
        }
    }

    private suspend fun discoverServices(): List<BluetoothGattService> {
        val g = gatt ?: throw Exception("Not connected to $macAddress")
        val deferred = CompletableDeferred<List<BluetoothGattService>>()
        servicesDeferred = deferred
        if (!g.discoverServices()) {
            throw Exception("Failed to start service discovery on $macAddress")
        }
        return withTimeout(CONNECTION_TIMEOUT_MS) {
            deferred.await()
        }
    }

    private suspend fun negotiateMtu(targetMtu: Int) {
        val g = gatt ?: return
        val deferred = CompletableDeferred<Int>()
        mtuDeferred = deferred
        val initiated = try {
            g.requestMtu(targetMtu)
        } catch (_: Exception) {
            false
        }
        if (initiated) {
            val negotiated = withTimeoutOrNull(2_500L) {
                deferred.await()
            }
            if (negotiated != null) {
                AppLogger.debug("BLE MTU for $alias ($macAddress): $negotiated bytes")
            }
            delay(50L)
        }
    }

    private suspend fun findDeviceByAddress(
        adapter: BluetoothAdapter,
        address: String,
        timeoutMs: Long
    ): BluetoothDevice? {
        val scanner = adapter.bluetoothLeScanner ?: return null
        val deferred = CompletableDeferred<BluetoothDevice>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                if (dev.address.equals(address, ignoreCase = true)) {
                    deferred.complete(dev)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                AppLogger.debug("BLE scan for $alias ($address) failed with code $errorCode")
                deferred.completeExceptionally(Exception("BLE scan failed with code $errorCode"))
            }
        }
        val filter = ScanFilter.Builder().setDeviceAddress(address).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        return try {
            scanner.startScan(listOf(filter), settings, callback)
            withTimeoutOrNull(timeoutMs) {
                deferred.await()
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                scanner.stopScan(callback)
            } catch (_: Exception) {
            }
        }
    }

    fun getServices(): List<BluetoothGattService> = gatt?.services.orEmpty()

    fun findCharacteristic(serviceUuid: UUID?, charUuid: UUID): BluetoothGattCharacteristic? {
        val services = gatt?.services ?: return null
        if (serviceUuid != null) {
            services.firstOrNull { it.uuid == serviceUuid }
                ?.getCharacteristic(charUuid)
                ?.let { return it }
        }
        for (service in services) {
            for (characteristic in service.characteristics) {
                if (characteristic.uuid == charUuid) {
                    return characteristic
                }
            }
        }
        return null
    }

    suspend fun enableNotifications(charUuid: UUID, serviceUuid: UUID? = null) {
        val g = gatt ?: throw Exception("Not connected to $alias")
        val characteristic = findCharacteristic(serviceUuid, charUuid)
            ?: throw Exception("Notify characteristic $charUuid not found on $alias")

        if (!g.setCharacteristicNotification(characteristic, true)) {
            throw Exception("setCharacteristicNotification failed on $alias for $charUuid")
        }
        val cccd = characteristic.getDescriptor(CCCD_UUID)
        if (cccd != null) {
            val deferred = CompletableDeferred<Int>()
            descriptorWriteDeferred = deferred
            // Match Bleak: prefer NOTIFICATION (0x0001) whenever PROPERTY_NOTIFY is present;
            // only fall back to INDICATION (0x0002) if PROPERTY_NOTIFY is absent.
            val props = characteristic.properties
            val value = if ((props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else if ((props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            val initiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            if (!initiated) {
                throw Exception("CCCD writeDescriptor initiation failed on $alias")
            }
            val status = withTimeout(5_000L) {
                deferred.await()
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                throw Exception("CCCD writeDescriptor error status=$status on $alias")
            }
            delay(100L)
        }
    }

    suspend fun writeCharacteristic(
        charUuid: UUID,
        payload: ByteArray,
        withResponse: Boolean = false,
        serviceUuid: UUID? = null,
        settleMs: Long = 200L
    ) {
        writeMutex.withLock {
            val g = gatt ?: throw Exception("Not connected to $alias")
            val characteristic = findCharacteristic(serviceUuid, charUuid)
                ?: throw Exception("Write characteristic $charUuid not found on $alias")

            val deferred = CompletableDeferred<Int>()
            writeDeferred = deferred

            val props = characteristic.properties
            val writeType = if (withResponse) {
                if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
            } else {
                if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
            }

            val initiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(characteristic, payload, writeType) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = writeType
                @Suppress("DEPRECATION")
                characteristic.value = payload
                @Suppress("DEPRECATION")
                g.writeCharacteristic(characteristic)
            }

            if (!initiated) {
                throw Exception("writeCharacteristic initiation failed on $alias")
            }

            withTimeout(5_000L) {
                val status = deferred.await()
                if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT &&
                    status != BluetoothGatt.GATT_SUCCESS
                ) {
                    throw Exception("GATT write error status=$status on $alias")
                }
            }
            if (settleMs > 0) {
                delay(settleMs)
            }
        }
    }

    suspend fun disconnect(refreshCache: Boolean = false) {
        connectionLock.withLock {
            val hadGatt = gatt != null
            closeInternal(refreshCache = refreshCache, waitDisconnect = true)
            if (hadGatt) {
                // Brief cooldown so Android's BLE stack releases clientIf cleanly
                delay(500L)
            }
        }
    }

    private fun refreshDeviceCache(g: BluetoothGatt) {
        try {
            val refreshMethod = g.javaClass.getMethod("refresh")
            val refreshed = refreshMethod.invoke(g) as? Boolean ?: false
            if (refreshed) {
                AppLogger.debug("Refreshed Android BLE GATT cache for $alias ($macAddress)")
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun closeInternal(refreshCache: Boolean, waitDisconnect: Boolean) {
        val g = gatt
        val wasConnected = isConnected
        isConnected = false
        if (g != null) {
            if (refreshCache) {
                refreshDeviceCache(g)
            }
            if (wasConnected && waitDisconnect) {
                val disc = CompletableDeferred<Unit>()
                disconnectDeferred = disc
                try {
                    g.disconnect()
                    withTimeoutOrNull(1_500L) {
                        disc.await()
                    }
                } catch (_: Exception) {
                }
            } else {
                try {
                    g.disconnect()
                } catch (_: Exception) {
                }
            }
            try {
                g.close()
            } catch (_: Exception) {
            }
            if (gatt === g) {
                gatt = null
            }
        }
    }
}
