package net.mhu.solstation.ble

import android.content.Context
import net.mhu.solstation.model.ChargerDeviceConfig

class DCChargerClient(
    context: Context,
    config: ChargerDeviceConfig
) : RenogyModbusClient(
    context = context,
    macAddr = config.macAddr,
    alias = config.alias,
    deviceId = config.deviceId,
    measurementName = config.name
) {
    companion object {
        private val FUNCTION_MAP = mapOf(
            3 to "READ",
            6 to "WRITE"
        )

        fun parseChargingInfoBytes(bs: ByteArray): Map<String, Any?> {
            val parsed = LinkedHashMap<String, Any?>()
            parsed["function"] = FUNCTION_MAP[BleUtils.bytesToInt(bs, 1, 1).toInt()]
            parsed["battery_percentage"] = BleUtils.bytesToInt(bs, 3, 2)
            parsed["battery_voltage"] = BleUtils.bytesToDouble(bs, 5, 2, scale = 0.1)
            parsed["combined_charge_current"] = BleUtils.bytesToDouble(bs, 7, 2, scale = 0.01)
            parsed["controller_temperature"] = BleUtils.parseTemperature(BleUtils.bytesToInt(bs, 9, 1).toInt(), "C")
            parsed["battery_temperature"] = BleUtils.parseTemperature(BleUtils.bytesToInt(bs, 10, 1).toInt(), "C")
            parsed["alternator_voltage"] = BleUtils.bytesToDouble(bs, 11, 2, scale = 0.1)
            parsed["alternator_current"] = BleUtils.bytesToDouble(bs, 13, 2, scale = 0.01)
            parsed["alternator_power"] = BleUtils.bytesToInt(bs, 15, 2)
            parsed["pv_voltage"] = BleUtils.bytesToDouble(bs, 17, 2, scale = 0.1)
            parsed["pv_current"] = BleUtils.bytesToDouble(bs, 19, 2, scale = 0.01)
            parsed["pv_power"] = BleUtils.bytesToInt(bs, 21, 2)
            parsed["battery_min_voltage_today"] = BleUtils.bytesToDouble(bs, 25, 2, scale = 0.1)
            parsed["battery_max_voltage_today"] = BleUtils.bytesToDouble(bs, 27, 2, scale = 0.1)
            parsed["battery_max_current_today"] = BleUtils.bytesToDouble(bs, 29, 2, scale = 0.01)
            parsed["max_charging_power_today"] = BleUtils.bytesToInt(bs, 33, 2)
            parsed["charging_amp_hours_today"] = BleUtils.bytesToInt(bs, 37, 2)
            parsed["power_generation_today"] = BleUtils.bytesToInt(bs, 41, 2)
            parsed["total_working_days"] = BleUtils.bytesToInt(bs, 45, 2)
            parsed["count_battery_overdischarged"] = BleUtils.bytesToInt(bs, 47, 2)
            parsed["count_battery_fully_charged"] = BleUtils.bytesToInt(bs, 49, 2)
            parsed["battery_ah_total_accumulated"] = BleUtils.bytesToInt(bs, 51, 4)
            parsed["power_generation_total"] = BleUtils.bytesToInt(bs, 59, 4)
            return parsed
        }
    }

    init {
        sections.add(
            ModbusSection(
                register = 256,
                words = 30,
                parser = { bs ->
                    data.putAll(parseChargingInfoBytes(bs))
                }
            )
        )
    }
}
