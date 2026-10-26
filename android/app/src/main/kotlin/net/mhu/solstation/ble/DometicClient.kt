package net.mhu.solstation.ble

import android.content.Context
import net.mhu.solstation.model.FridgeDeviceConfig
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

data class TopicKey(val b0: Int, val b1: Int, val b2: Int, val b3: Int) {
    fun toBytes(): ByteArray = byteArrayOf(b0.toByte(), b1.toByte(), b2.toByte(), b3.toByte())
}

class EnergyWindow(
    private val windowSeconds: Double = 3600.0,
    private val maxGapSeconds: Double = 300.0
) {
    private val samples = ArrayDeque<Pair<Double, Double>>()

    @Synchronized
    fun add(timestampSeconds: Double, watts: Double) {
        val last = samples.peekLast()
        if (last != null && timestampSeconds <= last.first) {
            return
        }
        samples.addLast(timestampSeconds to watts)
        prune(timestampSeconds)
    }

    @Synchronized
    fun reset() {
        samples.clear()
    }

    private fun prune(now: Double) {
        val start = now - windowSeconds
        while (samples.size >= 2) {
            val iter = samples.iterator()
            iter.next()
            val second = iter.next()
            if (second.first <= start) {
                samples.removeFirst()
            } else {
                break
            }
        }
    }

    @Synchronized
    fun wattHours(nowSeconds: Double = System.nanoTime() / 1_000_000_000.0): Double {
        val start = nowSeconds - windowSeconds
        var totalWattSeconds = 0.0
        val list = samples.toList()
        for (i in 0 until list.size - 1) {
            val (t0, w0) = list[i]
            val (t1, w1) = list[i + 1]
            if (t1 <= start) continue
            val span = t1 - t0
            if (span <= 0.0 || span > maxGapSeconds) continue
            var leftT = t0
            var leftW = w0
            if (t0 < start) {
                leftT = start
                leftW = w0 + (w1 - w0) * ((start - t0) / span)
            }
            totalWattSeconds += ((leftW + w1) / 2.0) * (t1 - leftT)
        }
        return totalWattSeconds / 3600.0
    }

    @Synchronized
    fun coverageSeconds(nowSeconds: Double = System.nanoTime() / 1_000_000_000.0): Double {
        val start = nowSeconds - windowSeconds
        var covered = 0.0
        val list = samples.toList()
        for (i in 0 until list.size - 1) {
            val (t0, _) = list[i]
            val (t1, _) = list[i + 1]
            if (t1 <= start) continue
            val span = t1 - t0
            if (span <= 0.0 || span > maxGapSeconds) continue
            covered += t1 - max(t0, start)
        }
        return covered
    }
}

class DometicClient(
    private val context: Context,
    private val config: FridgeDeviceConfig
) {
    companion object {
        val DDM1_SERVICE_UUID: UUID = UUID.fromString("537a0300-0995-481f-926c-1604e23fd515")
        val DDM1_WRITE_UUID: UUID = UUID.fromString("537a0301-0995-481f-926c-1604e23fd515")
        val DDM1_NOTIFY_UUID: UUID = UUID.fromString("537a0302-0995-481f-926c-1604e23fd515")

        val DDM2_SERVICE_UUID: UUID = UUID.fromString("537a0400-0995-481f-926c-1604e23fd515")
        val DDM2_WRITE_UUID: UUID = UUID.fromString("537a0401-0995-481f-926c-1604e23fd515")
        val DDM2_NOTIFY_UUID: UUID = UUID.fromString("537a0402-0995-481f-926c-1604e23fd515")

        const val D1_PUB = 0x00
        const val D1_SUB = 0x01
        const val D1_PING = 0x02
        const val D1_HELLO = 0x03
        const val D1_ACK = 0x04
        const val D1_NAK = 0x05
        const val D1_NOP = 0x06

        const val D2_HELLO = 0x03
        const val D2_ACK = 0x04
        const val D2_NAK = 0x05
        const val D2_NOP = 0x06
        const val D2_PUBLISH = 0x10
        const val D2_SET = 0x11
        const val D2_SUBSCRIBE = 0x12
        const val D2_FRAGMENT = 0x14

        private const val HANDSHAKE_TIMEOUT_MS = 6_000L
        private const val FIRST_DATA_TIMEOUT_MS = 20_000L
        private const val WRITE_SETTLE_MS = 200L
        private const val D1_IDLE_PING_SECONDS = 3.0
        private const val ENERGY_MIN_SAMPLE_INTERVAL = 5.0
        private const val D1_NO_VALUE_RAW: Short = -32768
        private const val D1_NO_VALUE = -3276.8
        private const val D1_HISTORY_BUCKET_SECONDS = 600.0

        private val POWER_SOURCES = mapOf(
            "ddm1" to mapOf(0 to "ac", 1 to "dc", 2 to "solar"),
            "ddm2" to mapOf(0 to "ac", 1 to "dc", 2 to "battery")
        )
        private val BATTERY_PROTECTION_LEVELS = mapOf(
            0 to "low",
            1 to "medium",
            2 to "high"
        )
        private val D2_PRODUCT_TYPES = mapOf(
            0 to "unconfigured",
            1 to "single_zone",
            2 to "single_zone_icemaker",
            3 to "dual_zone",
            4 to "deli_box"
        )

        val D1_TOPICS: Map<TopicKey, Pair<String, String>> = mapOf(
            TopicKey(1, 0, 0, 129) to ("subscribe_app_sz" to "empty"),
            TopicKey(2, 0, 0, 129) to ("subscribe_app_szi" to "empty"),
            TopicKey(3, 0, 0, 129) to ("subscribe_app_dz" to "empty"),
            TopicKey(0, 193, 0, 0) to ("serial_number" to "str"),
            TopicKey(0, 128, 0, 1) to ("compartment_count" to "u8"),
            TopicKey(0, 129, 0, 1) to ("icemaker_count" to "u8"),
            TopicKey(0, 0, 1, 1) to ("compartment_power_0" to "bool"),
            TopicKey(16, 0, 1, 1) to ("compartment_power_1" to "bool"),
            TopicKey(0, 1, 1, 1) to ("temperature_0" to "decideg"),
            TopicKey(16, 1, 1, 1) to ("temperature_1" to "decideg"),
            TopicKey(0, 2, 1, 1) to ("set_temperature_0" to "decideg"),
            TopicKey(16, 2, 1, 1) to ("set_temperature_1" to "decideg"),
            TopicKey(0, 4, 1, 1) to ("zone_control_mode" to "u8"),
            TopicKey(0, 8, 1, 1) to ("door_open_0" to "bool"),
            TopicKey(16, 8, 1, 1) to ("door_open_1" to "bool"),
            TopicKey(0, 0, 3, 1) to ("cooler_power" to "bool"),
            TopicKey(0, 1, 3, 1) to ("voltage" to "decivolt"),
            TopicKey(0, 2, 3, 1) to ("battery_protection_level" to "u8"),
            TopicKey(0, 3, 3, 1) to ("compressor_power" to "bool"),
            TopicKey(0, 5, 3, 1) to ("power_source_id" to "u8"),
            TopicKey(0, 6, 3, 1) to ("icemaker_power" to "bool"),
            TopicKey(0, 64, 3, 1) to ("dc_current_history_hour" to "history"),
            TopicKey(0, 0, 6, 1) to ("device_name" to "str")
        )

        val D1_SUBSCRIBE = listOf(
            TopicKey(0, 128, 0, 1), TopicKey(0, 0, 1, 1), TopicKey(16, 0, 1, 1),
            TopicKey(0, 1, 1, 1), TopicKey(16, 1, 1, 1), TopicKey(0, 2, 1, 1),
            TopicKey(16, 2, 1, 1), TopicKey(0, 8, 1, 1), TopicKey(16, 8, 1, 1),
            TopicKey(0, 0, 3, 1), TopicKey(0, 1, 3, 1), TopicKey(0, 2, 3, 1),
            TopicKey(0, 3, 3, 1), TopicKey(0, 5, 3, 1), TopicKey(0, 64, 3, 1),
            TopicKey(0, 0, 6, 1), TopicKey(0, 193, 0, 0)
        )

        val D1_BULK_SUBSCRIBE = mapOf(
            "sz" to TopicKey(1, 0, 0, 129),
            "szi" to TopicKey(2, 0, 0, 129),
            "dz" to TopicKey(3, 0, 0, 129)
        )

        private fun mccc(param: Int) = TopicKey(param, 0x00, 0x00, 0x1A)

        val D2_TOPICS: Map<TopicKey, Pair<String, String>> = mapOf(
            mccc(0x01) to ("product_type_id" to "i32"),
            mccc(0x02) to ("compartment_count" to "i32"),
            mccc(0x03) to ("compartment_power" to "bool_array"),
            mccc(0x04) to ("temperature" to "milli_array"),
            mccc(0x05) to ("set_temperature" to "milli_array"),
            mccc(0x06) to ("active_compartment" to "i32"),
            mccc(0x07) to ("door_open" to "bool_array"),
            mccc(0x0B) to ("cooler_power" to "bool"),
            mccc(0x0C) to ("voltage" to "milli"),
            mccc(0x0D) to ("battery_protection_level" to "i32"),
            mccc(0x0E) to ("compressor_power" to "bool"),
            mccc(0x0F) to ("current" to "milli"),
            mccc(0x10) to ("power_source_id" to "i32"),
            mccc(0x11) to ("icemaker_power" to "bool"),
            mccc(0x12) to ("error_state" to "u16_array"),
            mccc(0x13) to ("serial_number" to "str"),
            mccc(0x14) to ("sku" to "str"),
            mccc(0x15) to ("firmware_version" to "str"),
            TopicKey(0x01, 0x00, 0x00, 0x1C) to ("product_name" to "str"),
            TopicKey(0x03, 0x00, 0x00, 0x1C) to ("cms_sku" to "str"),
            TopicKey(0x07, 0x00, 0x01, 0x00) to ("firmware_id" to "str")
        )

        val D2_SUBSCRIBE_TOPICS = listOf(
            mccc(0x01), mccc(0x02), mccc(0x03), mccc(0x04), mccc(0x05),
            mccc(0x07), mccc(0x0B), mccc(0x0C), mccc(0x0D), mccc(0x0E),
            mccc(0x0F), mccc(0x10), mccc(0x12), mccc(0x13), mccc(0x15),
            TopicKey(0x01, 0x00, 0x00, 0x1C),
            TopicKey(0x03, 0x00, 0x00, 0x1C),
            TopicKey(0x07, 0x00, 0x01, 0x00)
        )

        fun decodePayload(payload: ByteArray, kind: String): Any? {
            return try {
                when (kind) {
                    "empty" -> null
                    "bool" -> {
                        if (payload.size >= 4) {
                            ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int != 0
                        } else if (payload.isNotEmpty()) {
                            payload[0].toInt() != 0
                        } else {
                            null
                        }
                    }
                    "u8" -> if (payload.isNotEmpty()) (payload[0].toInt() and 0xFF) else null
                    "i32" -> if (payload.size >= 4) {
                        ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    } else null
                    "milli" -> if (payload.size >= 4) {
                        val raw = ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        BigDecimal.valueOf(raw.toLong())
                            .divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_EVEN)
                            .toDouble()
                    } else null
                    "decideg" -> if (payload.size >= 2) {
                        val raw = ByteBuffer.wrap(payload, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short
                        if (raw == D1_NO_VALUE_RAW) null else raw / 10.0
                    } else null
                    "decivolt" -> if (payload.size >= 2) {
                        val raw = ByteBuffer.wrap(payload, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                        raw / 10.0
                    } else null
                    "str" -> {
                        val nullIdx = payload.indexOf(0.toByte())
                        val slice = if (nullIdx >= 0) payload.copyOfRange(0, nullIdx) else payload
                        String(slice, Charsets.UTF_8).trim()
                    }
                    "history" -> if (payload.size >= 15) {
                        val list = ArrayList<Double>(8)
                        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until 7) {
                            list.add(buf.getShort(i * 2) / 10.0)
                        }
                        list.add((payload[14].toInt() and 0xFF).toDouble())
                        list
                    } else null
                    "milli_array" -> {
                        val count = payload.size / 4
                        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                        (0 until count).map { i ->
                            BigDecimal.valueOf(buf.getInt(i * 4).toLong())
                                .divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_EVEN)
                                .toDouble()
                        }
                    }
                    "bool_array" -> {
                        val count = payload.size / 4
                        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                        (0 until count).map { i -> buf.getInt(i * 4) != 0 }
                    }
                    "u16_array" -> {
                        val count = payload.size / 2
                        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                        (0 until count).map { i -> buf.getShort(i * 2).toInt() and 0xFFFF }
                    }
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bleManager: BleConnectionManager? = null
    private var notifyJob: Job? = null
    private var heartbeatJob: Job? = null

    private var protocol: String? = null
    private var writeUuid: UUID? = null
    private var notifyUuid: UUID? = null

    val values = ConcurrentHashMap<String, Any>()
    private val energy = EnergyWindow()

    @Volatile
    private var lastFrameAtSeconds: Double = 0.0
    @Volatile
    private var lastPublishAtSeconds: Double = 0.0
    @Volatile
    private var lastEnergySampleAtMonotonic: Double = 0.0

    private var ackDeferred: CompletableDeferred<Unit>? = null
    private var firstDataDeferred: CompletableDeferred<Unit>? = null

    suspend fun connect() {
        lock.withLock {
            val current = bleManager
            if (current != null && current.isActuallyConnected()) {
                return
            }
            teardown()
            val firstData = CompletableDeferred<Unit>()
            firstDataDeferred = firstData

            val manager = BleConnectionManager(context, config.macAddr, config.alias)
            manager.connect(tryDirectFirst = true, requestBond = config.pair)
            bleManager = manager

            // Tear down on any failure from here on: an unbonded cooler exposes no DDM service,
            // and leaking the live link would hold its single BLE connection on every retry.
            try {
                detectProtocol(manager)

                val nUuid = notifyUuid ?: throw Exception("Notify UUID not resolved for Dometic")
                manager.enableNotifications(nUuid)
                notifyJob = scope.launch {
                    manager.notifications.collect { (uuid, frame) ->
                        if (uuid == nUuid) {
                            onNotify(frame)
                        }
                    }
                }

                subscribe()
                try {
                    withTimeout(FIRST_DATA_TIMEOUT_MS) {
                        firstData.await()
                    }
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    throw Exception(
                        "Dometic subscribed but published nothing. Verify bonding or ensure no other app holds the BLE connection."
                    )
                }
            } catch (e: Exception) {
                // Refresh the GATT cache too: a table discovered before bonding lacks the DDM service
                withContext(NonCancellable) { teardown(refreshCache = true) }
                throw e
            }
            AppLogger.info("Dometic (${config.alias}) connected using protocol $protocol")
        }
    }

    private fun detectProtocol(manager: BleConnectionManager) {
        val available = manager.getServices().map { it.uuid }.toSet()
        val forced = config.protocol.trim().lowercase().ifEmpty { "auto" }
        val hasDdm1 = available.contains(DDM1_SERVICE_UUID)
        val hasDdm2 = available.contains(DDM2_SERVICE_UUID)

        if (!hasDdm1 && !hasDdm2) {
            throw Exception(
                "No Dometic DDM service found on ${config.macAddr}. Put the cooler in Bluetooth PAIR mode and retry."
            )
        }

        protocol = when (forced) {
            "auto" -> if (hasDdm2) "ddm2" else "ddm1"
            "ddm1", "ddm2" -> forced
            else -> throw Exception("Invalid protocol '$forced' in fridge config")
        }

        if (protocol == "ddm1") {
            writeUuid = DDM1_WRITE_UUID
            notifyUuid = DDM1_NOTIFY_UUID
        } else {
            writeUuid = DDM2_WRITE_UUID
            notifyUuid = DDM2_NOTIFY_UUID
        }
    }

    private suspend fun subscribe() {
        if (protocol == "ddm1") {
            ddm1Handshake()
            val group = config.productType.trim().lowercase().ifEmpty { "dz" }
            val bulk = D1_BULK_SUBSCRIBE[group]
            if (bulk != null) {
                write(byteArrayOf(D1_SUB.toByte()) + bulk.toBytes())
            }
            for (topic in D1_SUBSCRIBE) {
                write(byteArrayOf(D1_SUB.toByte()) + topic.toBytes())
            }
            heartbeatJob = scope.launch {
                while (isActive && bleManager?.isConnected == true) {
                    delay(1000L)
                    val nowSec = System.currentTimeMillis() / 1000.0
                    if (nowSec - lastFrameAtSeconds > D1_IDLE_PING_SECONDS) {
                        try {
                            write(byteArrayOf(D1_PING.toByte()))
                        } catch (_: Exception) {
                            break
                        }
                    }
                }
            }
        } else {
            for (topic in D2_SUBSCRIBE_TOPICS) {
                write(byteArrayOf(D2_SUBSCRIBE.toByte()) + topic.toBytes())
            }
        }
    }

    private suspend fun ddm1Handshake() {
        val deferred = CompletableDeferred<Unit>()
        ackDeferred = deferred
        write(byteArrayOf(D1_PING.toByte()))
        try {
            withTimeout(HANDSHAKE_TIMEOUT_MS) {
                deferred.await()
            }
        } catch (_: Exception) {
            throw Exception("Dometic DDM1 handshake timed out (no ACK to PING)")
        }
    }

    private suspend fun write(payload: ByteArray, settleMs: Long = WRITE_SETTLE_MS) {
        val manager = bleManager ?: throw Exception("Dometic not connected")
        val wUuid = writeUuid ?: throw Exception("Dometic write UUID not set")
        manager.writeCharacteristic(
            charUuid = wUuid,
            payload = payload,
            withResponse = true,
            settleMs = settleMs
        )
    }

    private suspend fun ack() {
        write(byteArrayOf(D1_ACK.toByte()), settleMs = 0L)
    }

    private suspend fun onNotify(frame: ByteArray) {
        if (frame.isEmpty()) return
        lastFrameAtSeconds = System.currentTimeMillis() / 1000.0
        try {
            if (protocol == "ddm1") {
                onDdm1Frame(frame)
            } else {
                onDdm2Frame(frame)
            }
        } catch (e: Exception) {
            AppLogger.error("Error handling Dometic frame", e)
        }
    }

    private suspend fun onDdm1Frame(frame: ByteArray) {
        val action = frame[0].toInt() and 0xFF
        when (action) {
            D1_ACK -> ackDeferred?.complete(Unit)
            D1_NAK -> AppLogger.warn("Dometic returned NAK")
            D1_PUB -> {
                if (frame.size >= 5) {
                    val topic = TopicKey(
                        frame[1].toInt() and 0xFF,
                        frame[2].toInt() and 0xFF,
                        frame[3].toInt() and 0xFF,
                        frame[4].toInt() and 0xFF
                    )
                    store(topic, frame.copyOfRange(5, frame.size), D1_TOPICS)
                }
                ack()
            }
            D1_PING, D1_SUB, D1_HELLO, D1_NOP -> ack()
        }
    }

    private fun onDdm2Frame(frame: ByteArray) {
        val action = frame[0].toInt() and 0xFF
        when (action) {
            D2_NAK -> AppLogger.warn("Dometic returned NAK")
            D2_FRAGMENT -> Unit
            D2_PUBLISH -> {
                if (frame.size >= 5) {
                    val topic = TopicKey(
                        frame[1].toInt() and 0xFF,
                        frame[2].toInt() and 0xFF,
                        frame[3].toInt() and 0xFF,
                        frame[4].toInt() and 0xFF
                    )
                    store(topic, frame.copyOfRange(5, frame.size), D2_TOPICS)
                }
            }
        }
    }

    private fun store(
        topic: TopicKey,
        payload: ByteArray,
        table: Map<TopicKey, Pair<String, String>>
    ) {
        val entry = table[topic] ?: return
        val (name, kind) = entry
        val decoded = decodePayload(payload, kind)
        if (decoded == null && kind != "empty") return
        if (decoded != null) {
            values[name] = decoded
        }
        lastPublishAtSeconds = System.currentTimeMillis() / 1000.0
        firstDataDeferred?.complete(Unit)
        if (name == "voltage" || name == "current" || name == "dc_current_history_hour") {
            samplePower()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun historyBuckets(): List<Double?> {
        val history = values["dc_current_history_hour"] as? List<Double> ?: return emptyList()
        if (history.size < 8) return emptyList()
        return history.take(7).map { v -> if (v == D1_NO_VALUE) null else v }
    }

    private fun instantCurrent(): Double? {
        if (protocol == "ddm2") {
            return (values["current"] as? Number)?.toDouble()
        }
        return historyBuckets().firstOrNull { it != null }
    }

    private fun samplePower() {
        if (protocol != "ddm2") return
        val voltage = (values["voltage"] as? Number)?.toDouble() ?: return
        val current = (values["current"] as? Number)?.toDouble() ?: return
        val nowMono = System.nanoTime() / 1_000_000_000.0
        if (nowMono - lastEnergySampleAtMonotonic < ENERGY_MIN_SAMPLE_INTERVAL) return
        lastEnergySampleAtMonotonic = nowMono
        energy.add(nowMono, voltage * current)
    }

    private fun energyLastHour(): Pair<Double, Double> {
        if (protocol == "ddm2") {
            val nowMono = System.nanoTime() / 1_000_000_000.0
            return energy.wattHours(nowMono) to energy.coverageSeconds(nowMono)
        }
        val voltage = (values["voltage"] as? Number)?.toDouble() ?: return 0.0 to 0.0
        val buckets = historyBuckets().take(6)
        val present = buckets.filterNotNull()
        if (present.isEmpty()) return 0.0 to 0.0
        val ampHours = present.sum() * (D1_HISTORY_BUCKET_SECONDS / 3600.0)
        return (voltage * ampHours) to (present.size * D1_HISTORY_BUCKET_SECONDS)
    }

    private fun compartment(base: String, index: Int): Any? {
        if (protocol == "ddm1") {
            return values["${base}_$index"]
        }
        val list = values[base] as? List<*> ?: return null
        return list.getOrNull(index)
    }

    private fun asInt(value: Any?): Int? {
        return when (value) {
            null -> null
            is Boolean -> if (value) 1 else 0
            is Number -> if (value.toInt() != 0) 1 else 0
            else -> null
        }
    }

    fun read(): Map<String, Any?> {
        if (bleManager?.isActuallyConnected() != true) {
            throw Exception("Dometic not connected")
        }
        samplePower()

        val nowSec = System.currentTimeMillis() / 1000.0
        val silence = if (lastPublishAtSeconds > 0) nowSec - lastPublishAtSeconds else null
        if (silence != null && silence > config.maxSilenceSeconds) {
            throw Exception("Dometic silent for ${silence.toInt()}s, forcing reconnect")
        }
        if (lastPublishAtSeconds <= 0.0) {
            throw Exception("Dometic connected but no data published yet")
        }

        val (energyWh, energyCovered) = energyLastHour()
        val voltage = (values["voltage"] as? Number)?.toDouble()
        val current = instantCurrent()
        val power = if (voltage != null && current != null) {
            BigDecimal.valueOf(voltage * current).setScale(2, RoundingMode.HALF_EVEN).toDouble()
        } else null

        val result = LinkedHashMap<String, Any?>()
        result["protocol"] = protocol
        result["compartment_count"] = (values["compartment_count"] as? Number)?.toInt()
        result["temperature_0"] = (compartment("temperature", 0) as? Number)?.toDouble()
        result["temperature_1"] = (compartment("temperature", 1) as? Number)?.toDouble()
        result["set_temperature_0"] = (compartment("set_temperature", 0) as? Number)?.toDouble()
        result["set_temperature_1"] = (compartment("set_temperature", 1) as? Number)?.toDouble()
        result["compartment_power_0"] = asInt(compartment("compartment_power", 0))
        result["compartment_power_1"] = asInt(compartment("compartment_power", 1))
        result["door_open_0"] = asInt(compartment("door_open", 0))
        result["door_open_1"] = asInt(compartment("door_open", 1))
        result["cooler_power"] = asInt(values["cooler_power"])
        result["compressor_power"] = asInt(values["compressor_power"])
        result["icemaker_power"] = asInt(values["icemaker_power"])
        result["voltage"] = voltage
        result["current"] = current
        result["power"] = power
        result["energy_wh_1h"] = BigDecimal.valueOf(energyWh).setScale(3, RoundingMode.HALF_EVEN).toDouble()
        result["energy_window_seconds"] = energyCovered.toInt()
        result["battery_protection_level"] = (values["battery_protection_level"] as? Number)?.toInt()
        result["seconds_since_update"] = (nowSec - lastPublishAtSeconds).toInt()

        val sourceId = (values["power_source_id"] as? Number)?.toInt()
        result["power_source_id"] = sourceId
        if (sourceId != null) {
            val labels = POWER_SOURCES[protocol] ?: POWER_SOURCES["ddm2"]!!
            result["power_source"] = labels[sourceId] ?: sourceId.toString()
        }

        val level = (values["battery_protection_level"] as? Number)?.toInt()
        if (level != null) {
            result["battery_protection"] = BATTERY_PROTECTION_LEVELS[level] ?: level.toString()
        }

        val errors = values["error_state"] as? List<*>
        if (errors != null) {
            result["error_count"] = errors.count { (it as? Number)?.toInt() != 0 }
        } else if (protocol == "ddm1") {
            result["error_count"] = null
        }

        val productTypeId = (values["product_type_id"] as? Number)?.toInt()
        if (productTypeId != null) {
            result["product_type"] = D2_PRODUCT_TYPES[productTypeId] ?: productTypeId.toString()
        }

        for (key in listOf("device_name", "serial_number", "firmware_version", "firmware_id", "product_name", "cms_sku")) {
            val v = values[key] as? String
            if (!v.isNullOrEmpty()) {
                result[key] = v
            }
        }

        result["__device"] = config.alias
        result["__client"] = "DometicClient"
        result["__name"] = config.name
        return result
    }

    suspend fun disconnect(refreshCache: Boolean = false) {
        lock.withLock {
            teardown(refreshCache = refreshCache)
        }
    }

    private suspend fun teardown(refreshCache: Boolean = false) {
        heartbeatJob?.cancel()
        heartbeatJob = null
        notifyJob?.cancel()
        notifyJob = null
        values.remove("voltage")
        values.remove("current")
        lastEnergySampleAtMonotonic = 0.0
        bleManager?.disconnect(refreshCache = refreshCache)
        bleManager = null
        protocol = null
    }
}
