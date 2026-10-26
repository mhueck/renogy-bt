package net.mhu.solstation.network

import net.mhu.solstation.model.DailyForecastItem
import net.mhu.solstation.model.WeatherState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object WeatherClient {

    suspend fun fetchWeather(lat: Double, lon: Double): WeatherState = withContext(Dispatchers.IO) {
        val urlStr =
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum" +
                "&current=temperature_2m,weather_code&timezone=auto&forecast_days=3&timeformat=unixtime"

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
        }

        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw Exception("Open-Meteo HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parseWeatherJson(body)
        } finally {
            conn.disconnect()
        }
    }

    fun parseWeatherJson(jsonStr: String): WeatherState {
        val root = JSONObject(jsonStr)
        val current = root.optJSONObject("current")
        val currentTemp = current?.optDouble("temperature_2m", 0.0) ?: 0.0
        val currentCode = current?.optInt("weather_code", 0) ?: 0

        val daily = root.optJSONObject("daily")
        val tempMins = daily?.optJSONArray("temperature_2m_min")
        val tempMaxs = daily?.optJSONArray("temperature_2m_max")
        val codes = daily?.optJSONArray("weather_code")
        val precs = daily?.optJSONArray("precipitation_sum")

        fun getDouble(arr: JSONArray?, idx: Int, default: Double): Double {
            if (arr == null || idx >= arr.length() || arr.isNull(idx)) return default
            return arr.optDouble(idx, default)
        }

        fun getInt(arr: JSONArray?, idx: Int, default: Int): Int {
            if (arr == null || idx >= arr.length() || arr.isNull(idx)) return default
            return arr.optInt(idx, default)
        }

        val forecastList = (0 until 3).map { i ->
            DailyForecastItem(
                tempMin = getDouble(tempMins, i, 0.0),
                tempMax = getDouble(tempMaxs, i, 0.0),
                weatherCode = getInt(codes, i, 0),
                precipitationSum = getDouble(precs, i, 0.0)
            )
        }

        return WeatherState(
            currentTemp = currentTemp,
            currentWeatherCode = currentCode,
            forecast = forecastList,
            updatedAtMillis = System.currentTimeMillis()
        )
    }

    fun wmoDescription(code: Int): String {
        return when (code) {
            0 -> "Clear sky"
            1, 2, 3 -> "Partly cloudy"
            45, 48 -> "Fog"
            51, 53, 55 -> "Drizzle"
            56, 57 -> "Freezing drizzle"
            61, 63, 65 -> "Rain"
            66, 67 -> "Freezing rain"
            71, 73, 75 -> "Snow"
            77 -> "Snow grains"
            80, 81, 82 -> "Rain showers"
            85, 86 -> "Snow showers"
            95 -> "Thunderstorm"
            96, 99 -> "Thunderstorm with hail"
            else -> "Code $code"
        }
    }
}
