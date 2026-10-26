package com.example.renogybt.network

import com.example.renogybt.ble.BleUtils
import com.example.renogybt.model.InfluxDb2Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object InfluxDb2Client {

    /**
     * Converts a measurement map into InfluxDB v2 Line Protocol:
     * `<measurement>[,<tag_key>=<tag_val>...] <field_key>=<field_val>[,...] [timestamp]`
     * Mirrors Python's `log_influxdb3` logic:
     * - String values -> tags
     * - Numeric values -> fields
     */
    fun toLineProtocol(measurement: String, jsonData: Map<String, Any?>): String? {
        if (measurement.isBlank()) return null

        val tags = mutableListOf<String>()
        val fields = mutableListOf<String>()

        for ((key, value) in jsonData) {
            if (value == null || key.isBlank()) continue
            when (value) {
                is String -> {
                    if (value.isNotEmpty()) {
                        tags.add("${escapeTagKeyOrValue(key)}=${escapeTagKeyOrValue(value)}")
                    }
                }
                is Int, is Long, is Short, is Byte -> {
                    fields.add("${escapeTagKeyOrValue(key)}=${(value as Number).toLong()}i")
                }
                is Double, is Float -> {
                    val d = (value as Number).toDouble()
                    if (d.isFinite()) {
                        fields.add("${escapeTagKeyOrValue(key)}=$d")
                    }
                }
                is Number -> {
                    val d = value.toDouble()
                    if (d.isFinite()) {
                        fields.add("${escapeTagKeyOrValue(key)}=$d")
                    }
                }
            }
        }

        if (fields.isEmpty()) return null

        val escapedMeasurement = measurement
            .replace("\\", "\\\\")
            .replace(",", "\\,")
            .replace(" ", "\\ ")

        val tagPart = if (tags.isNotEmpty()) "," + tags.sorted().joinToString(",") else ""
        val fieldPart = fields.joinToString(",")
        return "$escapedMeasurement$tagPart $fieldPart"
    }

    private fun escapeTagKeyOrValue(input: String): String {
        return input
            .replace("\\", "\\\\")
            .replace(",", "\\,")
            .replace("=", "\\=")
            .replace(" ", "\\ ")
            .replace("\n", "_")
    }

    suspend fun writeData(config: InfluxDb2Config, rawData: Map<String, Any?>) = withContext(Dispatchers.IO) {
        val filtered = BleUtils.filterFields(rawData, config.fieldsFilter)
        val measurement = (filtered["__name"] as? String)
            ?: (rawData["__name"] as? String)
            ?: "solar_data"

        val line = toLineProtocol(measurement, filtered)
            ?: throw Exception("No numeric fields to write for measurement $measurement")

        val baseUrl = config.url.trim().trimEnd('/')
        val orgEncoded = URLEncoder.encode(config.org.trim(), "UTF-8")
        val bucketEncoded = URLEncoder.encode(config.bucket.trim(), "UTF-8")
        val writeEndpoint = "$baseUrl/api/v2/write?org=$orgEncoded&bucket=$bucketEncoded&precision=s"

        val conn = (URL(writeEndpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            if (config.token.isNotBlank()) {
                setRequestProperty("Authorization", "Token ${config.token.trim()}")
            }
        }

        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(line)
                writer.flush()
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val errBody = try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: Exception) {
                    ""
                }
                throw Exception("InfluxDB2 HTTP $code: $errBody")
            }
        } finally {
            conn.disconnect()
        }
    }
}
