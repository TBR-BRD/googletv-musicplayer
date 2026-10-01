package de.tbrbd.onradiotv.data

import de.tbrbd.onradiotv.model.WeatherDay
import de.tbrbd.onradiotv.model.WeatherState
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

private val WEATHER_CODE_MAP: Map<Int, String> = mapOf(
    0 to "Sonnig", 1 to "Meist klar", 2 to "Teilweise bewölkt", 3 to "Bewölkt",
    45 to "Nebel", 48 to "Raureifnebel",
    51 to "Leichter Nieselregen", 53 to "Nieselregen", 55 to "Starker Nieselregen",
    56 to "Leichter gefrierender Nieselregen", 57 to "Gefrierender Nieselregen",
    61 to "Leichter Regen", 63 to "Regen", 65 to "Starker Regen",
    66 to "Leichter gefrierender Regen", 67 to "Gefrierender Regen",
    71 to "Leichter Schneefall", 73 to "Schneefall", 75 to "Starker Schneefall", 77 to "Schneegriesel",
    80 to "Leichte Schauer", 81 to "Schauer", 82 to "Starke Schauer",
    85 to "Leichte Schneeschauer", 86 to "Schneeschauer",
    95 to "Gewitter", 96 to "Gewitter mit Hagel", 99 to "Starkes Gewitter",
)

/**
 * Ports app/weather_service.py's Open-Meteo integration (public API, no key
 * needed). Location is fixed for now - see README for how to change it.
 */
class WeatherRepository(
    private val client: OkHttpClient,
    private val locationName: String = "Falkensee",
    private val countryCode: String = "DE",
) {
    private var cachedLatLon: Pair<Double, Double>? = null

    fun fetchWeather(): WeatherState? {
        return try {
            val (lat, lon) = cachedLatLon ?: geocode()?.also { cachedLatLon = it } ?: return null
            fetchForecast(lat, lon)
        } catch (_: Exception) {
            null
        }
    }

    private fun geocode(): Pair<Double, Double>? {
        val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", locationName)
            .addQueryParameter("count", "1")
            .addQueryParameter("format", "json")
            .addQueryParameter("language", "de")
            .addQueryParameter("countryCode", countryCode)
            .build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val results = JSONObject(body).optJSONArray("results") ?: return null
            if (results.length() == 0) return null
            val first = results.getJSONObject(0)
            return first.getDouble("latitude") to first.getDouble("longitude")
        }
    }

    private fun fetchForecast(lat: Double, lon: Double): WeatherState? {
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", lat.toString())
            .addQueryParameter("longitude", lon.toString())
            .addQueryParameter("timezone", "Europe/Berlin")
            .addQueryParameter("forecast_days", "3")
            .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min")
            .addQueryParameter("current", "temperature_2m,weather_code")
            .addQueryParameter("temperature_unit", "celsius")
            .build()

        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val payload = JSONObject(body)
            val current = payload.optJSONObject("current")
            val daily = payload.optJSONObject("daily")

            val currentCode = current?.optInt("weather_code", -1) ?: -1
            val currentTemp = current?.optDouble("temperature_2m")?.takeUnless { it.isNaN() }

            val labels = listOf("Heute", "Morgen", "Übermorgen")
            val times = daily?.optJSONArray("time")
            val codes = daily?.optJSONArray("weather_code")
            val tempMax = daily?.optJSONArray("temperature_2m_max")
            val tempMin = daily?.optJSONArray("temperature_2m_min")
            val count = minOf(
                times?.length() ?: 0, codes?.length() ?: 0,
                tempMax?.length() ?: 0, tempMin?.length() ?: 0, labels.size,
            )
            val days = (0 until count).map { i ->
                WeatherDay(
                    label = labels[i],
                    tempMaxC = tempMax?.optDouble(i)?.takeUnless { it.isNaN() },
                    tempMinC = tempMin?.optDouble(i)?.takeUnless { it.isNaN() },
                    condition = WEATHER_CODE_MAP[codes?.optInt(i)] ?: "Wetter",
                )
            }

            return WeatherState(
                location = locationName,
                temperatureC = currentTemp,
                condition = WEATHER_CODE_MAP[currentCode] ?: "Wetter",
                days = days,
            )
        }
    }
}
