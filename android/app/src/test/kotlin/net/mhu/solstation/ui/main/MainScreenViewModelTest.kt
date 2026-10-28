package net.mhu.solstation.ui.main

import net.mhu.solstation.ble.BleEspClient
import net.mhu.solstation.ble.BleUtils
import net.mhu.solstation.ble.DCChargerClient
import net.mhu.solstation.ble.DometicClient
import net.mhu.solstation.ble.EcoWorthyClient
import net.mhu.solstation.ble.EnergyWindow
import net.mhu.solstation.ble.JbdFrameResult
import net.mhu.solstation.ble.ModbusFrameResult
import net.mhu.solstation.ble.RenogyModbusClient
import net.mhu.solstation.network.InfluxDb2Client
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainScreenViewModelTest {

    @Test
    fun modbusReadRequest_matchesPythonCrc16() {
        val request = RenogyModbusClient.createReadRequest(255, 3, 256, 30)
        assertEquals(8, request.size)
        assertEquals(255.toByte(), request[0])
        assertEquals(3.toByte(), request[1])
        assertEquals(1.toByte(), request[2])
        assertEquals(0.toByte(), request[3])
        assertEquals(0.toByte(), request[4])
        assertEquals(30.toByte(), request[5])
    }

    @Test
    fun batteryCharacteristicPacking_matchesSpec() {
        val packed = BleUtils.packBatteryCharacteristic(
            percentage = 85.0,
            pctChange1h = -2.5,
            voltage = 26.50,
            power = -125.4,
            temperature = 23.5
        )
        assertEquals(9, packed.size)
        val buf = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(85, buf.get().toInt() and 0xFF)
        assertEquals(-25, buf.short.toInt())
        assertEquals(2650, buf.short.toInt() and 0xFFFF)
        assertEquals(-1254, buf.short.toInt())
        assertEquals(235, buf.short.toInt())
    }

    @Test
    fun chargerCharacteristicPacking_matchesSpec() {
        val packed = BleUtils.packChargerCharacteristic(
            pvVoltage = 20.0,
            pvCurrent = 5.5,
            alternatorVoltage = 14.0,
            alternatorCurrent = 10.0
        )
        assertEquals(4, packed.size)
        val buf = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1100, buf.short.toInt() and 0xFFFF) // 110.0 W * 10
        assertEquals(1400, buf.short.toInt() and 0xFFFF) // 140.0 W * 10
    }

    @Test
    fun fridgeCharacteristicPacking_matchesSpec19Bytes() {
        val map = mapOf(
            "temperature_0" to 4.2,
            "temperature_1" to -18.5,
            "set_temperature_0" to 4.0,
            "set_temperature_1" to -18.0,
            "power" to 42.3,
            "energy_wh_1h" to 18.6,
            "voltage" to 13.25,
            "cooler_power" to 1,
            "compartment_power_0" to 1,
            "compartment_power_1" to 1,
            "compressor_power" to 1,
            "door_open_0" to 0,
            "door_open_1" to 0,
            "error_count" to 0,
            "energy_window_seconds" to 3600,
            "compartment_count" to 2,
            "power_source_id" to 1
        )
        val head = BleUtils.packFridgeHead(map)
        assertEquals(17, head.size)
        val full = BleUtils.appendFridgeAge(head, 12)
        assertEquals(19, full.size)

        val buf = ByteBuffer.wrap(full).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(42, buf.short.toInt())
        assertEquals(-185, buf.short.toInt())
        assertEquals(40, buf.short.toInt())
        assertEquals(-180, buf.short.toInt())
        assertEquals(423, buf.short.toInt())
        assertEquals(186, buf.short.toInt() and 0xFFFF)
        assertEquals(1325, buf.short.toInt() and 0xFFFF)
        val flags = buf.get().toInt() and 0xFF
        assertEquals(0x01 or 0x02 or 0x04 or 0x08 or 0x80, flags)
        assertEquals(2, buf.get().toInt() and 0xFF)
        assertEquals(1, buf.get().toInt() and 0xFF)
        assertEquals(12, buf.short.toInt() and 0xFFFF)
    }

    @Test
    fun dometicDecode_handlesSentinelAndMilliArray() {
        // 0x8000 sentinel in DDM1 decideg returns null
        val sentinelBytes = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((-32768).toShort()).array()
        assertNull(DometicClient.decodePayload(sentinelBytes, "decideg"))

        // Normal decideg
        val tempBytes = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(45.toShort()).array()
        assertEquals(4.5, DometicClient.decodePayload(tempBytes, "decideg") as Double, 0.001)

        // EnergyWindow integration
        val win = EnergyWindow()
        win.add(0.0, 36.0)
        win.add(100.0, 36.0)
        assertEquals(1.0, win.wattHours(100.0), 0.01)
    }

    @Test
    fun influxLineProtocol_formatsTagsAndFields() {
        val data = linkedMapOf<String, Any?>(
            "__name" to "EW_BAT",
            "__device" to "DP04S007L4S200A",
            "voltage" to 13.4,
            "percentage" to 92
        )
        val line = InfluxDb2Client.toLineProtocol("EW_BAT", data)
        assertTrue(line != null && line.startsWith("EW_BAT,__device=DP04S007L4S200A,__name=EW_BAT "))
        assertTrue(line!!.contains("voltage=13.4"))
        assertTrue(line.contains("percentage=92i"))
    }

    @Test
    fun modbusFrameReassembly_handles20ByteChunksAndStaleOverflow() {
        // Construct a realistic 65-byte Renogy DCC response (30 words = 60 data bytes)
        val raw = ByteArray(63)
        raw[0] = 0xFF.toByte() // slave id
        raw[1] = 0x03 // READ_SUCCESS
        raw[2] = 60   // byte count (30 * 2)
        // battery_percentage = 95 (offset 3..4)
        raw[3] = 0; raw[4] = 95
        // battery_voltage = 13.4V => 134 (offset 5..6)
        raw[5] = 0; raw[6] = 134.toByte()
        // pv_voltage = 19.5V => 195 (offset 17..18), pv_current = 8.25A => 825 (0x0339) (offset 19..20)
        raw[17] = 0; raw[18] = 195.toByte()
        raw[19] = 0x03; raw[20] = 0x39
        // Put 147 (0x93) at offset 21 (chunk 2 byte 1!) and 0 at offset 41 (chunk 3 byte 1!)
        raw[21] = 147.toByte()
        raw[41] = 0
        val crc = BleUtils.crc16Modbus(raw)
        val fullFrame = raw + crc
        assertEquals(65, fullFrame.size)

        // Prepend stale trailing bytes from a prior timed-out transfer (including 147 and 0)
        val staleOverflow = byteArrayOf(0x10, 147.toByte(), 0x00, 0x00, 0x55)

        val chunks = listOf(
            staleOverflow,
            fullFrame.copyOfRange(0, 20),
            fullFrame.copyOfRange(20, 40),
            fullFrame.copyOfRange(40, 60),
            fullFrame.copyOfRange(60, 65)
        )

        var buffer = ByteArray(0)
        var assembled: ByteArray? = null
        for (i in chunks.indices) {
            val res = RenogyModbusClient.extractModbusFrame(buffer + chunks[i], expectedWords = 30)
            if (i < chunks.lastIndex) {
                assertTrue("Expected Incomplete at chunk $i", res is ModbusFrameResult.Incomplete)
                buffer = (res as ModbusFrameResult.Incomplete).buffered
            } else {
                assertTrue("Expected Success on final chunk", res is ModbusFrameResult.Success)
                assembled = (res as ModbusFrameResult.Success).frame
            }
        }

        assertArrayEquals(fullFrame, assembled)
        val parsed = DCChargerClient.parseChargingInfoBytes(assembled!!)
        assertEquals(13.4, parsed["battery_voltage"] as Double, 0.01)
        assertEquals(19.5, parsed["pv_voltage"] as Double, 0.01)
        assertEquals(8.25, parsed["pv_current"] as Double, 0.01)
    }

    @Test
    fun ecoWorthyFrameReassembly_handlesInterior77AndContinuationDD() {
        // Construct a 34-byte JBD BMS basic info frame (dataLen = 27)
        // where byte 19 (end of 20-byte chunk 1) is 0x77 AND byte 20 (start of chunk 2) is 0xDD!
        val dataLen = 27
        val frame = ByteArray(dataLen + 7)
        frame[0] = 0xDD.toByte()
        frame[1] = 0x03 // OPERATION_BASIC_INFO
        frame[2] = 0x00 // status OK
        frame[3] = dataLen.toByte()

        // Payload starts at offset 4:
        // voltage = 13.99V => 1399 = 0x0577 (contains 0x77!)
        frame[4] = 0x05; frame[5] = 0x77
        // current = -5.50A => -550 = 0xFDDA
        frame[6] = 0xFD.toByte(); frame[7] = 0xDA.toByte()
        // capacity_remaining = 180.00Ah => 18000 = 0x4650
        frame[8] = 0x46; frame[9] = 0x50
        // capacity = 200.00Ah => 20000 = 0x4E20
        frame[10] = 0x4E; frame[11] = 0x20
        // Force chunk boundary traps: byte 19 == 0x77 (end of chunk 1) & byte 20 == 0xDD (start of chunk 2)
        frame[19] = 0x77
        frame[20] = 0xDD.toByte()
        // temperature at payload[23..24] = frame[27..28]: 295.6 K => 2956 = 0x0B8C => 22.5°C
        frame[27] = 0x0B; frame[28] = 0x8C.toByte()
        // Checksum + 0x77 end byte
        frame[31] = 0xFA.toByte()
        frame[32] = 0x11
        frame[33] = 0x77

        val chunk1 = frame.copyOfRange(0, 20) // ends with 0x77!
        val chunk2 = frame.copyOfRange(20, 34) // starts with 0xDD!

        val step1 = EcoWorthyClient.extractJbdFrame(chunk1, expectedOperation = 3)
        assertTrue("Chunk 1 ending in 0x77 must still be Incomplete", step1 is JbdFrameResult.Incomplete)

        val bufAfter1 = (step1 as JbdFrameResult.Incomplete).buffered
        val step2 = EcoWorthyClient.extractJbdFrame(bufAfter1 + chunk2, expectedOperation = 3)
        assertTrue("Chunk 2 starting with 0xDD must complete frame", step2 is JbdFrameResult.Success)

        val (_, parsed) = EcoWorthyClient.parseFrame((step2 as JbdFrameResult.Success).frame)
        assertEquals(13.99, parsed["voltage"] as Double, 0.001)
        assertEquals(-5.50, parsed["current"] as Double, 0.001)
        assertEquals(90.0, parsed["percentage"] as Double, 0.01)
        assertEquals(22.5, parsed["temperature"] as Double, 0.01)
    }

    @Test
    fun innerWithTimeout_doesNotCancelOuterLoopCoroutine() = runTest {
        var iterationsCompleted = 0
        var caughtTimeouts = 0
        for (i in 1..3) {
            try {
                withTimeout(10L) {
                    kotlinx.coroutines.delay(50L)
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                caughtTimeouts += 1
            }
            iterationsCompleted += 1
        }
        assertEquals(3, caughtTimeouts)
        assertEquals(3, iterationsCompleted)
    }
}
