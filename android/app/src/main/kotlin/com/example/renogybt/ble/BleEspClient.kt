package com.example.renogybt.ble

import android.content.Context
import com.example.renogybt.model.GpsDeviceConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class BleEspClient(
    context: Context,
    config: GpsDeviceConfig
) : RenogyModbusClient(
    context = context,
    macAddr = config.macAddr,
    alias = config.alias,
    deviceId = config.deviceId,
    measurementName = config.name,
    writeServiceUuid = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b"),
    notifyCharUuid = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8"),
    writeCharUuid = UUID.fromString("1c95d5e3-d8f7-413a-bf3d-7a2e5d7be87e"),
    allowDirectConnectFallback = false,
    scanTimeoutMs = 6_000L
) {
    companion object {
        private const val REG_LAT_HIGH = 0
        private const val REG_LAT_LOW = 1
        private const val REG_LON_HIGH = 2
        private const val REG_LON_LOW = 3
        private const val REG_ALTITUDE = 4
        private const val REG_SPEED = 5
        private const val REG_SATELLITES = 6
        private const val REG_TEMPERATURE = 7
        private const val REG_HUMIDITY = 8
        private const val REG_PRESSURE = 9
        private const val REG_GAS_RESISTANCE = 10

        fun parseEspDataBytes(bs: ByteArray): Map<String, Any?> {
            val parsed = LinkedHashMap<String, Any?>()
            if (bs.size < 5) return parsed
            val registerData = bs.copyOfRange(3, bs.size - 2)
            val registers = decodeRegisters(registerData, startRegister = 0)

            val latHigh = registers[REG_LAT_HIGH] ?: 0
            val latLow = registers[REG_LAT_LOW] ?: 0
            val latScaled = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                .putShort(latHigh.toShort())
                .putShort(latLow.toShort())
                .also { it.flip() }
                .int
            parsed["lat"] = latScaled / 10000000.0

            val lonHigh = registers[REG_LON_HIGH] ?: 0
            val lonLow = registers[REG_LON_LOW] ?: 0
            val lonScaled = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                .putShort(lonHigh.toShort())
                .putShort(lonLow.toShort())
                .also { it.flip() }
                .int
            parsed["lon"] = lonScaled / 10000000.0

            val altRaw = registers[REG_ALTITUDE] ?: 0
            parsed["alt"] = altRaw.toShort().toInt()

            val speedRaw = registers[REG_SPEED] ?: 0
            parsed["speed"] = speedRaw / 10.0

            parsed["satellites"] = registers[REG_SATELLITES] ?: 0

            val tempRaw = registers[REG_TEMPERATURE] ?: 0
            parsed["temperature"] = tempRaw / 100.0

            val humidityRaw = registers[REG_HUMIDITY] ?: 0
            parsed["humidity"] = humidityRaw / 100.0

            val pressureRaw = registers[REG_PRESSURE] ?: 0
            parsed["pressure"] = pressureRaw / 10.0

            parsed["gas_resistance"] = registers[REG_GAS_RESISTANCE] ?: 0
            return parsed
        }

        private fun decodeRegisters(registerData: ByteArray, startRegister: Int = 0): Map<Int, Int> {
            val registers = HashMap<Int, Int>()
            var i = 0
            while (i + 1 < registerData.size) {
                val regAddr = startRegister + (i / 2)
                val regValue = ((registerData[i].toInt() and 0xFF) shl 8) or
                    (registerData[i + 1].toInt() and 0xFF)
                registers[regAddr] = regValue
                i += 2
            }
            return registers
        }
    }

    init {
        sections.add(
            ModbusSection(
                register = 0,
                words = 11,
                parser = { bs ->
                    data.putAll(parseEspDataBytes(bs))
                }
            )
        )
    }
}
