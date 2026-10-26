package com.example.renogybt.ble

import android.content.Context
import com.example.renogybt.model.BatteryDeviceConfig
import com.example.renogybt.service.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

sealed class JbdFrameResult {
    data class Incomplete(val buffered: ByteArray) : JbdFrameResult()
    data class Success(val frame: ByteArray, val remaining: ByteArray) : JbdFrameResult()
    data class ErrorResponse(val status: Int, val remaining: ByteArray) : JbdFrameResult()
}

class EcoWorthyClient(
    private val context: Context,
    private val config: BatteryDeviceConfig
) {
    companion object {
        val WRITE_SERVICE_UUID: UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
        val NOTIFY_CHAR_UUID: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
        val WRITE_CHAR_UUID: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")

        private const val ATTEMPT_1_TIMEOUT_MS = 6_500L
        private const val ATTEMPT_2_TIMEOUT_MS = 8_500L

        val COMMAND_READ_BASIC = byteArrayOf(
            0xDD.toByte(), 0xA5.toByte(), 0x03, 0x00, 0xFF.toByte(), 0xFD.toByte(), 0x77
        )
        val COMMAND_READ_CELLV = byteArrayOf(
            0xDD.toByte(), 0xA5.toByte(), 0x04, 0x00, 0xFF.toByte(), 0xFC.toByte(), 0x77
        )

        const val OPERATION_BASIC_INFO = 3
        const val OPERATION_CELLV_INFO = 4
        private const val FRAME_HEADER: Byte = 0xDD.toByte()
        private const val FRAME_END: Byte = 0x77.toByte()

        /**
         * Reassembles fragmented BLE chunks into a complete JBD BMS frame using the explicit
         * payload length in byte 3 (`expectedFrameSize = dataLen + 7`).
         *
         * Unlike checking `first == 0xDD` / `last == 0x77` on raw 20-byte chunks, this never
         * truncates early when a payload byte happens to equal `0x77` and never discards
         * Chunk 1 when a continuation chunk happens to start with `0xDD`.
         */
        fun extractJbdFrame(buffer: ByteArray, expectedOperation: Int? = null): JbdFrameResult {
            var startIdx = 0
            while (startIdx < buffer.size) {
                if (buffer[startIdx] != FRAME_HEADER) {
                    startIdx += 1
                    continue
                }

                val available = buffer.size - startIdx
                if (available < 4) {
                    return JbdFrameResult.Incomplete(buffer.copyOfRange(startIdx, buffer.size))
                }

                val op = buffer[startIdx + 1].toInt() and 0xFF
                val status = buffer[startIdx + 2].toInt() and 0xFF
                val dataLen = buffer[startIdx + 3].toInt() and 0xFF

                val validOp = if (expectedOperation != null) {
                    op == expectedOperation
                } else {
                    op == OPERATION_BASIC_INFO || op == OPERATION_CELLV_INFO
                }

                if (!validOp || dataLen > 128) {
                    startIdx += 1
                    continue
                }

                val expectedFrameSize = dataLen + 7
                if (available < expectedFrameSize) {
                    return JbdFrameResult.Incomplete(buffer.copyOfRange(startIdx, buffer.size))
                }

                if (buffer[startIdx + expectedFrameSize - 1] == FRAME_END) {
                    val frame = buffer.copyOfRange(startIdx, startIdx + expectedFrameSize)
                    val rest = buffer.copyOfRange(startIdx + expectedFrameSize, buffer.size)
                    return if (status == 0) {
                        JbdFrameResult.Success(frame, rest)
                    } else {
                        JbdFrameResult.ErrorResponse(status, rest)
                    }
                }

                // Byte at expectedFrameSize - 1 wasn't 0x77; resync on next 0xDD
                startIdx += 1
            }

            return JbdFrameResult.Incomplete(ByteArray(0))
        }

        fun parseFrame(frame: ByteArray): Pair<Int, Map<String, Any?>> {
            val operation = BleUtils.bytesToInt(frame, 1, 1).toInt()
            val dataLength = BleUtils.bytesToInt(frame, 3, 1).toInt()
            if (frame.size < dataLength + 7) return operation to emptyMap()
            val payload = frame.copyOfRange(4, 4 + dataLength)
            val parsed = LinkedHashMap<String, Any?>()

            when (operation) {
                OPERATION_BASIC_INFO -> {
                    if (payload.size < 25) {
                        throw Exception("Incomplete EcoWorthy basic info payload (${payload.size} bytes)")
                    }
                    val voltage = BleUtils.bytesToDouble(payload, 0, 2, signed = false, scale = 0.01)
                    val current = BleUtils.bytesToDouble(payload, 2, 2, signed = true, scale = 0.01)
                    val capacityRemaining = BleUtils.bytesToDouble(payload, 4, 2, signed = false, scale = 0.01)
                    val capacity = BleUtils.bytesToDouble(payload, 6, 2, signed = false, scale = 0.01)
                    val temperature = BigDecimal.valueOf(
                        BleUtils.bytesToDouble(payload, 23, 2, signed = false, scale = 0.1) - 273.1
                    ).setScale(2, RoundingMode.HALF_EVEN).toDouble()
                    val power = voltage * current
                    val percentage = if (capacity == 0.0) 0.0 else 100.0 * capacityRemaining / capacity

                    parsed["voltage"] = voltage
                    parsed["current"] = current
                    parsed["capacity_remaining"] = capacityRemaining
                    parsed["capacity"] = capacity
                    parsed["temperature"] = temperature
                    parsed["power"] = power
                    parsed["percentage"] = percentage
                }
                OPERATION_CELLV_INFO -> {
                    val noCells = dataLength / 2
                    for (cell in 1..noCells) {
                        parsed["voltage_cell$cell"] = BleUtils.bytesToDouble(
                            payload,
                            2 * (cell - 1),
                            2,
                            signed = false,
                            scale = 0.001
                        )
                    }
                }
            }
            return operation to parsed
        }
    }

    private class FrameWaiter(
        val expectedOp: Int,
        var buffer: ByteArray = ByteArray(0),
        val deferred: CompletableDeferred<ByteArray> = CompletableDeferred()
    )

    private val lock = Mutex()
    private val rxLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bleManager: BleConnectionManager? = null
    private var notifyJob: Job? = null
    private var activeWaiter: FrameWaiter? = null

    suspend fun connect() {
        lock.withLock {
            val current = bleManager
            if (current != null && current.isActuallyConnected()) {
                return
            }
            disconnectInternal(refreshCache = false)
            val manager = BleConnectionManager(context, config.macAddr, config.alias)
            manager.connect(
                tryDirectFirst = false,
                requestBond = false,
                allowDirectFallback = true
            )
            manager.enableNotifications(NOTIFY_CHAR_UUID, WRITE_SERVICE_UUID)
            notifyJob = scope.launch {
                manager.notifications.collect { (uuid, chunk) ->
                    if (uuid == NOTIFY_CHAR_UUID) {
                        onNotificationChunk(chunk)
                    }
                }
            }
            bleManager = manager
            AppLogger.info("Connected to ${config.alias} (${config.macAddr}, MTU=${manager.currentMtu})")
        }
    }

    suspend fun read(): Map<String, Any?> {
        return lock.withLock {
            val manager = bleManager
            if (manager == null || !manager.isActuallyConnected()) {
                disconnectInternal(refreshCache = false)
                throw Exception("${config.alias} is not connected")
            }

            val result = LinkedHashMap<String, Any?>()
            try {
                // Match Python EcoWorthyClient._fetch_next pacing before writing to ff02
                delay(300L)
                val basicFrame = requestFrameWithRetry(
                    manager = manager,
                    command = COMMAND_READ_BASIC,
                    expectedOp = OPERATION_BASIC_INFO
                )
                val (_, basicParsed) = parseFrame(basicFrame)
                result.putAll(basicParsed)

                if (config.readCellVoltages) {
                    delay(500L)
                    val cellFrame = requestFrameWithRetry(
                        manager = manager,
                        command = COMMAND_READ_CELLV,
                        expectedOp = OPERATION_CELLV_INFO
                    )
                    val (_, cellParsed) = parseFrame(cellFrame)
                    result.putAll(cellParsed)
                }

                result["__device"] = config.alias
                result["__client"] = "EcoWorthyClient"
                result["__name"] = config.name
                result
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

    private suspend fun requestFrameWithRetry(
        manager: BleConnectionManager,
        command: ByteArray,
        expectedOp: Int
    ): ByteArray {
        val timeouts = longArrayOf(ATTEMPT_1_TIMEOUT_MS, ATTEMPT_2_TIMEOUT_MS)
        var lastError: Exception? = null

        for ((attempt, timeoutMs) in timeouts.withIndex()) {
            if (!manager.isActuallyConnected()) {
                throw Exception("${config.alias} disconnected during BMS read")
            }
            val waiter = FrameWaiter(expectedOp = expectedOp)
            synchronized(rxLock) {
                activeWaiter = waiter
            }

            try {
                if (attempt > 0) {
                    AppLogger.debug("Retrying EcoWorthy BMS command op=$expectedOp on ${config.alias}")
                }
                manager.writeCharacteristic(
                    charUuid = WRITE_CHAR_UUID,
                    payload = command,
                    withResponse = false,
                    serviceUuid = WRITE_SERVICE_UUID,
                    settleMs = 200L
                )
                val frame = withTimeoutOrNull(timeoutMs) {
                    waiter.deferred.await()
                }
                if (frame != null) {
                    return frame
                }
                lastError = Exception("Timed out waiting for EcoWorthy BMS frame (op=$expectedOp)")
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

        throw (lastError ?: Exception("EcoWorthy BMS read failed on ${config.alias}"))
    }

    suspend fun disconnect(refreshCache: Boolean = false) {
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
            when (val res = extractJbdFrame(combined, expectedOperation = waiter.expectedOp)) {
                is JbdFrameResult.Incomplete -> {
                    waiter.buffer = res.buffered
                }
                is JbdFrameResult.Success -> {
                    waiter.buffer = res.remaining
                    waiter.deferred.complete(res.frame)
                }
                is JbdFrameResult.ErrorResponse -> {
                    waiter.buffer = res.remaining
                    waiter.deferred.completeExceptionally(
                        Exception("EcoWorthy BMS returned error status=0x${res.status.toString(16)}")
                    )
                }
            }
        }
    }
}
