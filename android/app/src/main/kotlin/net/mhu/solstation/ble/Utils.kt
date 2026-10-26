package net.mhu.solstation.ble

import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object BleUtils {

    private val CRC16_LOW_BYTES = intArrayOf(
        0x00, 0xC0, 0xC1, 0x01, 0xC3, 0x03, 0x02, 0xC2, 0xC6, 0x06, 0x07, 0xC7, 0x05, 0xC5, 0xC4, 0x04,
        0xCC, 0x0C, 0x0D, 0xCD, 0x0F, 0xCF, 0xCE, 0x0E, 0x0A, 0xCA, 0xCB, 0x0B, 0xC9, 0x09, 0x08, 0xC8,
        0xD8, 0x18, 0x19, 0xD9, 0x1B, 0xDB, 0xDA, 0x1A, 0x1E, 0xDE, 0xDF, 0x1F, 0xDD, 0x1D, 0x1C, 0xDC,
        0x14, 0xD4, 0xD5, 0x15, 0xD7, 0x17, 0x16, 0xD6, 0xD2, 0x12, 0x13, 0xD3, 0x11, 0xD1, 0xD0, 0x10,
        0xF0, 0x30, 0x31, 0xF1, 0x33, 0xF3, 0xF2, 0x32, 0x36, 0xF6, 0xF7, 0x37, 0xF5, 0x35, 0x34, 0xF4,
        0x3C, 0xFC, 0xFD, 0x3D, 0xFF, 0x3F, 0x3E, 0xFE, 0xFA, 0x3A, 0x3B, 0xFB, 0x39, 0xF9, 0xF8, 0x38,
        0x28, 0xE8, 0xE9, 0x29, 0xEB, 0x2B, 0x2A, 0xEA, 0xEE, 0x2E, 0x2F, 0xEF, 0x2D, 0xED, 0xEC, 0x2C,
        0xE4, 0x24, 0x25, 0xE5, 0x27, 0xE7, 0xE6, 0x26, 0x22, 0xE2, 0xE3, 0x23, 0xE1, 0x21, 0x20, 0xE0,
        0xA0, 0x60, 0x61, 0xA1, 0x63, 0xA3, 0xA2, 0x62, 0x66, 0xA6, 0xA7, 0x67, 0xA5, 0x65, 0x64, 0xA4,
        0x6C, 0xAC, 0xAD, 0x6D, 0xAF, 0x6F, 0x6E, 0xAE, 0xAA, 0x6A, 0x6B, 0xAB, 0x69, 0xA9, 0xA8, 0x68,
        0x78, 0xB8, 0xB9, 0x79, 0xBB, 0x7B, 0x7A, 0xBA, 0xBE, 0x7E, 0x7F, 0xBF, 0x7D, 0xBD, 0xBC, 0x7C,
        0xB4, 0x74, 0x75, 0xB5, 0x77, 0xB7, 0xB6, 0x76, 0x72, 0xB2, 0xB3, 0x73, 0xB1, 0x71, 0x70, 0xB0,
        0x50, 0x90, 0x91, 0x51, 0x93, 0x53, 0x52, 0x92, 0x96, 0x56, 0x57, 0x97, 0x55, 0x95, 0x94, 0x54,
        0x9C, 0x5C, 0x5D, 0x9D, 0x5F, 0x9F, 0x9E, 0x5E, 0x5A, 0x9A, 0x9B, 0x5B, 0x99, 0x59, 0x58, 0x98,
        0x88, 0x48, 0x49, 0x89, 0x4B, 0x8B, 0x8A, 0x4A, 0x4E, 0x8E, 0x8F, 0x4F, 0x8D, 0x4D, 0x4C, 0x8C,
        0x44, 0x84, 0x85, 0x45, 0x87, 0x47, 0x46, 0x86, 0x82, 0x42, 0x43, 0x83, 0x41, 0x81, 0x80, 0x40
    )

    private val CRC16_HIGH_BYTES = intArrayOf(
        0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41, 0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40,
        0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40, 0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41,
        0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40, 0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41,
        0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41, 0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40,
        0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40, 0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41,
        0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41, 0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40,
        0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41, 0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40,
        0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40, 0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41,
        0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40, 0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41,
        0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41, 0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40,
        0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41, 0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40,
        0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40, 0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41,
        0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41, 0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40,
        0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40, 0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41,
        0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40, 0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41,
        0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41, 0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40
    )

    /**
     * Exact port of Python's bytes_to_int(bs, offset, length, signed=False, scale=1)
     */
    fun bytesToInt(
        bs: ByteArray,
        offset: Int,
        length: Int,
        signed: Boolean = false,
        scale: Double = 1.0
    ): Number {
        if (bs.size < (offset + length)) {
            return 0
        }
        val bigEndian: Boolean
        val start: Int
        val end: Int
        if (length > 0) {
            bigEndian = true
            start = offset
            end = offset + length
        } else {
            bigEndian = false
            start = offset + length + 1
            end = offset + 1
        }
        if (start < 0 || end > bs.size || start >= end) {
            return 0
        }
        val slice = bs.copyOfRange(start, end)
        val orderedBytes = if (bigEndian) slice else slice.reversedArray()

        var rawValue: Long = if (signed && (orderedBytes[0].toInt() and 0x80) != 0) -1L else 0L
        for (b in orderedBytes) {
            rawValue = (rawValue shl 8) or (b.toLong() and 0xFFL)
        }

        if (scale == 1.0) {
            return rawValue
        }
        val scaled = BigDecimal.valueOf(rawValue)
            .multiply(BigDecimal.valueOf(scale))
            .setScale(2, RoundingMode.HALF_EVEN)
            .toDouble()
        return scaled
    }

    fun bytesToDouble(
        bs: ByteArray,
        offset: Int,
        length: Int,
        signed: Boolean = false,
        scale: Double = 1.0
    ): Double = bytesToInt(bs, offset, length, signed, scale).toDouble()

    fun intToBytes(i: Int, pos: Int = 0): Byte {
        return when (pos) {
            0 -> ((i shr 8) and 0xFF).toByte()
            1 -> (i and 0xFF).toByte()
            else -> 0
        }
    }

    fun parseTemperature(rawValue: Int, unit: String = "C"): Double {
        val sign = (rawValue and 0xFF) shr 7
        val celsius = if (sign == 1) -((rawValue and 0xFF) - 128) else (rawValue and 0xFF)
        return formatTemperature(celsius.toDouble(), unit)
    }

    fun formatTemperature(celsius: Double, unit: String = "F"): Double {
        return if (unit.trim() == "F") (celsius * 9.0 / 5.0) + 32.0 else celsius
    }

    fun filterFields(data: Map<String, Any?>, fieldsStr: String): Map<String, Any?> {
        val fields = if (fieldsStr.isNotBlank()) {
            fieldsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        if (fields.isNotEmpty() && data.keys.containsAll(fields)) {
            val result = LinkedHashMap<String, Any?>()
            for (key in fields) {
                result[key] = data[key]
            }
            if (data.containsKey("__name") && !result.containsKey("__name")) {
                result["__name"] = data["__name"]
            }
            return result
        }
        return data
    }

    fun crc16Modbus(data: ByteArray): ByteArray {
        var crcHigh = 0xFF
        var crcLow = 0xFF
        for (b in data) {
            val index = crcHigh xor (b.toInt() and 0xFF)
            crcHigh = crcLow xor CRC16_HIGH_BYTES[index]
            crcLow = CRC16_LOW_BYTES[index]
        }
        return byteArrayOf(crcHigh.toByte(), crcLow.toByte())
    }

    fun clamp(value: Int, low: Int, high: Int): Int {
        return max(low, min(high, value))
    }

    /**
     * Packs Battery BLE Characteristic (0000ff11-0000-1000-8000-00805f9b34fb)
     * 9 bytes, little-endian `<BhHhh`
     */
    fun packBatteryCharacteristic(
        percentage: Double,
        pctChange1h: Double,
        voltage: Double,
        power: Double,
        temperature: Double
    ): ByteArray {
        val buf = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(clamp(percentage.roundToInt(), 0, 255).toByte())
        buf.putShort(clamp((pctChange1h * 10.0).roundToInt(), -32768, 32767).toShort())
        buf.putShort(clamp((voltage * 100.0).roundToInt(), 0, 65535).toShort())
        buf.putShort(clamp((power * 10.0).roundToInt(), -32768, 32767).toShort())
        buf.putShort(clamp((temperature * 10.0).roundToInt(), -32768, 32767).toShort())
        return buf.array()
    }

    /**
     * Packs Charger BLE Characteristic (0000ff12-0000-1000-8000-00805f9b34fb)
     * 4 bytes, little-endian `<HH`
     */
    fun packChargerCharacteristic(
        pvVoltage: Double,
        pvCurrent: Double,
        alternatorVoltage: Double,
        alternatorCurrent: Double
    ): ByteArray {
        val solarPower = pvVoltage * pvCurrent
        val altPower = alternatorVoltage * alternatorCurrent
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(clamp((solarPower * 10.0).roundToInt(), 0, 65535).toShort())
        buf.putShort(clamp((altPower * 10.0).roundToInt(), 0, 65535).toShort())
        return buf.array()
    }

    /**
     * Packs Weather BLE Characteristic (0000ff13-0000-1000-8000-00805f9b34fb)
     * 28 bytes, little-endian `<IhBhhBHhhBHhhBH`
     */
    fun packWeatherCharacteristic(
        currentTimeMin: Long,
        currentTemp: Double,
        currentCode: Int,
        d0Min: Double, d0Max: Double, d0Code: Int, d0Prec: Double,
        d1Min: Double, d1Max: Double, d1Code: Int, d1Prec: Double,
        d2Min: Double, d2Max: Double, d2Code: Int, d2Prec: Double
    ): ByteArray {
        val buf = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(currentTimeMin.toInt())
        buf.putShort(clamp((currentTemp * 10.0).roundToInt(), -32768, 32767).toShort())
        buf.put(clamp(currentCode, 0, 255).toByte())

        fun putDay(minT: Double, maxT: Double, code: Int, prec: Double) {
            buf.putShort(clamp((minT * 10.0).roundToInt(), -32768, 32767).toShort())
            buf.putShort(clamp((maxT * 10.0).roundToInt(), -32768, 32767).toShort())
            buf.put(clamp(code, 0, 255).toByte())
            buf.putShort(clamp((prec * 10.0).roundToInt(), 0, 65535).toShort())
        }

        putDay(d0Min, d0Max, d0Code, d0Prec)
        putDay(d1Min, d1Max, d1Code, d1Prec)
        putDay(d2Min, d2Max, d2Code, d2Prec)
        return buf.array()
    }

    /**
     * Packs Fridge 17-byte head (`<hhhhhHHBBB`), leaving the trailing 2-byte `age` (`<H`)
     * to be appended at read/write time so the age is always current.
     */
    fun packFridgeHead(fridgeData: Map<String, Any?>): ByteArray {
        fun temp(key: String): Short {
            val v = (fridgeData[key] as? Number)?.toDouble() ?: return 0
            return clamp((v * 10.0).roundToInt(), -32768, 32767).toShort()
        }

        fun flagOn(key: String): Boolean {
            val v = fridgeData[key]
            return when (v) {
                is Boolean -> v
                is Number -> v.toInt() != 0
                else -> false
            }
        }

        var flags = 0
        if (flagOn("cooler_power")) flags = flags or (1 shl 0)
        if (flagOn("compartment_power_0")) flags = flags or (1 shl 1)
        if (flagOn("compartment_power_1")) flags = flags or (1 shl 2)
        if (flagOn("compressor_power")) flags = flags or (1 shl 3)
        if (flagOn("door_open_0")) flags = flags or (1 shl 4)
        if (flagOn("door_open_1")) flags = flags or (1 shl 5)
        if (flagOn("error_count")) flags = flags or (1 shl 6)
        val energyWindowSeconds = (fridgeData["energy_window_seconds"] as? Number)?.toDouble() ?: 0.0
        if (energyWindowSeconds >= 3600.0 * 0.95) {
            flags = flags or (1 shl 7)
        }

        val power = (fridgeData["power"] as? Number)?.toDouble() ?: 0.0
        val energy = (fridgeData["energy_wh_1h"] as? Number)?.toDouble() ?: 0.0
        val voltage = (fridgeData["voltage"] as? Number)?.toDouble() ?: 0.0
        val compartmentCount = (fridgeData["compartment_count"] as? Number)?.toInt() ?: 0
        val powerSourceId = (fridgeData["power_source_id"] as? Number)?.toInt()

        val buf = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(temp("temperature_0"))
        buf.putShort(temp("temperature_1"))
        buf.putShort(temp("set_temperature_0"))
        buf.putShort(temp("set_temperature_1"))
        buf.putShort(clamp((power * 10.0).roundToInt(), -32768, 32767).toShort())
        buf.putShort(clamp((energy * 10.0).roundToInt(), 0, 65535).toShort())
        buf.putShort(clamp((voltage * 100.0).roundToInt(), 0, 65535).toShort())
        buf.put((flags and 0xFF).toByte())
        buf.put(clamp(compartmentCount, 0, 255).toByte())
        buf.put((if (powerSourceId != null) clamp(powerSourceId, 0, 255) else 255).toByte())
        return buf.array()
    }

    fun appendFridgeAge(fridgeHead: ByteArray, ageSeconds: Int): ByteArray {
        val clampedAge = clamp(ageSeconds, 0, 65535)
        val buf = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(fridgeHead)
        buf.putShort(clampedAge.toShort())
        return buf.array()
    }
}
