package net.mhu.solstation.ble

import android.content.Context
import net.mhu.solstation.service.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

data class ModbusSection(
    val register: Int,
    val words: Int,
    val parser: (ByteArray) -> Unit
)

sealed class ModbusFrameResult {
    data class Incomplete(val buffered: ByteArray) : ModbusFrameResult()
    data class Success(val frame: ByteArray, val remaining: ByteArray) : ModbusFrameResult()
    data class ExceptionResponse(val exceptionCode: Int, val remaining: ByteArray) : ModbusFrameResult()
}

open class RenogyModbusClient(
    protected val context: Context,
    val macAddr: String,
    val alias: String,
    val deviceId: Int,
    val measurementName: String,
    protected val writeServiceUuid: UUID = UUID.fromString("0000ffd0-0000-1000-8000-00805f9b34fb"),
    protected val notifyCharUuid: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"),
    protected val writeCharUuid: UUID = UUID.fromString("0000ffd1-0000-1000-8000-00805f9b34fb"),
    protected val allowDirectConnectFallback: Boolean = true,
    protected val scanTimeoutMs: Long = BleConnectionManager.DEFAULT_DISCOVERY_TIMEOUT_MS
) {
    companion object {
        private const val ATTEMPT_1_TIMEOUT_MS = 6_500L
        private const val ATTEMPT_2_TIMEOUT_MS = 8_500L
        const val READ_SUCCESS = 3
        const val READ_ERROR = 131

        fun createReadRequest(deviceId: Int, function: Int, regAddr: Int, readWords: Int): ByteArray {
            val header = byteArrayOf(
                (deviceId and 0xFF).toByte(),
                (function and 0xFF).toByte(),
                BleUtils.intToBytes(regAddr, 0),
                BleUtils.intToBytes(regAddr, 1),
                BleUtils.intToBytes(readWords, 0),
                BleUtils.intToBytes(readWords, 1)
            )
            val crc = BleUtils.crc16Modbus(header)
            return header + crc
        }

        /**
         * Reassembles fragmented BLE notifications into a complete Modbus RTU response frame
         * (`[slaveId, 0x03, byteCount, <2*words bytes>, crcHi, crcLo]`), discarding any leading
         * stale overflow bytes from an earlier timed-out transfer.
         */
        fun extractModbusFrame(buffer: ByteArray, expectedWords: Int): ModbusFrameResult {
            if (buffer.isEmpty()) {
                return ModbusFrameResult.Incomplete(buffer)
            }
            val expectedByteCount = expectedWords * 2
            val expectedFrameSize = expectedByteCount + 5

            var startIdx = 0
            while (startIdx < buffer.size) {
                val available = buffer.size - startIdx
                if (available < 2) {
                    return ModbusFrameResult.Incomplete(buffer.copyOfRange(startIdx, buffer.size))
                }

                val op = buffer[startIdx + 1].toInt() and 0xFF
                if (op == READ_SUCCESS) {
                    if (available < 3) {
                        return ModbusFrameResult.Incomplete(buffer.copyOfRange(startIdx, buffer.size))
                    }
                    val byteCount = buffer[startIdx + 2].toInt() and 0xFF
                    if (byteCount == expectedByteCount) {
                        if (available < expectedFrameSize) {
                            return ModbusFrameResult.Incomplete(buffer.copyOfRange(startIdx, buffer.size))
                        }
                        val frame = buffer.copyOfRange(startIdx, startIdx + expectedFrameSize)
                        val rest = buffer.copyOfRange(startIdx + expectedFrameSize, buffer.size)
                        return ModbusFrameResult.Success(frame, rest)
                    }
                } else if (op == READ_ERROR) {
                    if (available < 5) {
                        return ModbusFrameResult.Incomplete(buffer.copyOfRange(startIdx, buffer.size))
                    }
                    val candidate = buffer.copyOfRange(startIdx, startIdx + 5)
                    val expectedCrc = BleUtils.crc16Modbus(candidate.copyOfRange(0, 3))
                    if (candidate[3] == expectedCrc[0] && candidate[4] == expectedCrc[1]) {
                        val excCode = candidate[2].toInt() and 0xFF
                        val rest = buffer.copyOfRange(startIdx + 5, buffer.size)
                        return ModbusFrameResult.ExceptionResponse(excCode, rest)
                    }
                }

                startIdx += 1
            }

            return ModbusFrameResult.Incomplete(ByteArray(0))
        }
    }

    private class SectionWaiter(
        val expectedWords: Int,
        var buffer: ByteArray = ByteArray(0),
        val deferred: CompletableDeferred<ByteArray> = CompletableDeferred()
    )

    protected val sections = mutableListOf<ModbusSection>()
    protected val data = LinkedHashMap<String, Any?>()
    private val lock = Mutex()
    private val rxLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var bleManager: BleConnectionManager? = null
    private var notifyJob: Job? = null
    private var activeWaiter: SectionWaiter? = null

    open suspend fun connect() {
        lock.withLock {
            val current = bleManager
            if (current != null && current.isActuallyConnected()) {
                return
            }
            disconnectInternal(refreshCache = false)
            val manager = BleConnectionManager(context, macAddr, alias)
            manager.connect(
                tryDirectFirst = false,
                requestBond = false,
                allowDirectFallback = allowDirectConnectFallback,
                scanTimeoutMs = scanTimeoutMs
            )
            try {
                manager.enableNotifications(notifyCharUuid)
            } catch (e: Exception) {
                // The link is up but not yet owned by this client, so nothing else would close it:
                // a leaked GATT keeps the peripheral's single connection and stops it advertising.
                withContext(NonCancellable) { manager.disconnect() }
                throw e
            }
            notifyJob = scope.launch {
                manager.notifications.collect { (uuid, payload) ->
                    if (uuid == notifyCharUuid) {
                        onNotificationChunk(payload)
                    }
                }
            }
            bleManager = manager
            AppLogger.info("Connected to $alias ($macAddr, MTU=${manager.currentMtu})")
        }
    }

    open suspend fun read(): Map<String, Any?> {
        return lock.withLock {
            val manager = bleManager
            if (manager == null || !manager.isActuallyConnected()) {
                disconnectInternal(refreshCache = false)
                throw Exception("$alias is not connected")
            }

            data.clear()
            try {
                for ((idx, section) in sections.withIndex()) {
                    if (idx > 0) {
                        delay(500L)
                    }
                    val frame = requestSectionWithRetry(manager, section)
                    section.parser(frame)
                }

                if (data.isEmpty()) {
                    throw Exception("Empty Modbus data from $alias")
                }

                data["__device"] = alias
                data["__client"] = this::class.java.simpleName
                data["__name"] = measurementName
                LinkedHashMap(data)
            } catch (e: Exception) {
                synchronized(rxLock) {
                    activeWaiter = null
                }
                currentCoroutineContext().ensureActive()
                disconnectInternal(refreshCache = false)
                throw e
            }
        }
    }

    private suspend fun requestSectionWithRetry(
        manager: BleConnectionManager,
        section: ModbusSection
    ): ByteArray {
        val request = createReadRequest(deviceId, 3, section.register, section.words)
        val timeouts = longArrayOf(ATTEMPT_1_TIMEOUT_MS, ATTEMPT_2_TIMEOUT_MS)

        var lastError: Exception? = null
        for ((attempt, timeoutMs) in timeouts.withIndex()) {
            if (!manager.isActuallyConnected()) {
                throw Exception("$alias disconnected during Modbus read")
            }
            val waiter = SectionWaiter(expectedWords = section.words)
            synchronized(rxLock) {
                activeWaiter = waiter
            }

            try {
                if (attempt > 0) {
                    AppLogger.debug("Retrying Modbus read on $alias (reg=${section.register}, words=${section.words})")
                }
                manager.writeCharacteristic(
                    charUuid = writeCharUuid,
                    payload = request,
                    withResponse = false,
                    serviceUuid = writeServiceUuid,
                    settleMs = 200L
                )
                val response = withTimeoutOrNull(timeoutMs) {
                    waiter.deferred.await()
                }
                if (response != null) {
                    return response
                }
                lastError = Exception("Timed out waiting for Modbus response from $alias (${section.words * 2 + 5} bytes)")
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                lastError = e
                break
            } finally {
                synchronized(rxLock) {
                    if (activeWaiter === waiter) {
                        activeWaiter = null
                    }
                }
            }
        }

        throw (lastError ?: Exception("Modbus read failed on $alias"))
    }

    open suspend fun disconnect(refreshCache: Boolean = false) {
        lock.withLock {
            disconnectInternal(refreshCache = refreshCache)
        }
    }

    private suspend fun disconnectInternal(refreshCache: Boolean = false) {
        synchronized(rxLock) {
            activeWaiter = null
        }
        notifyJob?.cancel()
        notifyJob = null
        bleManager?.disconnect(refreshCache = refreshCache)
        bleManager = null
    }

    private fun onNotificationChunk(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        synchronized(rxLock) {
            val waiter = activeWaiter ?: return
            if (waiter.deferred.isCompleted) return

            val combined = waiter.buffer + chunk
            when (val res = extractModbusFrame(combined, waiter.expectedWords)) {
                is ModbusFrameResult.Incomplete -> {
                    waiter.buffer = res.buffered
                }
                is ModbusFrameResult.Success -> {
                    waiter.buffer = res.remaining
                    waiter.deferred.complete(res.frame)
                }
                is ModbusFrameResult.ExceptionResponse -> {
                    waiter.buffer = res.remaining
                    waiter.deferred.completeExceptionally(
                        Exception("$alias returned Modbus exception code ${res.exceptionCode}")
                    )
                }
            }
        }
    }
}
