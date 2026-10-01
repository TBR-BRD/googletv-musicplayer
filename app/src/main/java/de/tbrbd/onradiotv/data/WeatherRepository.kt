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

// Mirrors app/weather_service.py's WEATHER_CODE_MAP icon slugs, used to pick
// a matching emoji for the current condition (see weatherEmojiFor() in
// TvScreen.kt) instead of bundling the Pi's SVG icon set.
private val WEATHER_ICON_SLUG_MAP: Map<Int, String> = mapOf(
    0 to "sunny", 1 to "mostly-clear", 2 to "partly-cloudy", 3 to "cloudy",
    45 to "fog", 48 to "fog",
    51 to "drizzle", 53 to "drizzle", 55 to "rain",
    56 to "freezing", 57 to "freezing",
    61 to "drizzle", 63 to "rain", 65 to "rain",
    66 to "freezing", 67 to "freezing",
    71 to "snow", 73 to "snow", 75 to "snow", 77 to "snow",
    80 to "drizzle", 81 to "rain", 82 to "rain",
    85 to "snow", 86 to "snow",
    95 to "thunder", 96 to "thunder", 99 to "thunder",
)

private const val PRESSURE_TREND_DELTA_HPA = 1

/**
 * Ports app/weather_service.py's Open-Meteo integration (public API, no key
 * needed). The location name is passed into fetchWeather() each call (it's
 * user-configurable, stored in AppPreferences) rather than fixed at
 * construction, so geocoding is re-done whenever it changes.
 */
class WeatherRepository(
    private val client: OkHttpClient,
    private val countryCode: String = "DE",
) {
    private var cachedGeocode: Pair<String, GeocodeResult>? = null
    private var previousPressureHpa: Int? = null

    fun fetchWeather(locationName: String): WeatherState? {
        return try {
            val cached = cachedGeocode
            val geocodeResult = if (cached != null && cached.first == locationName) {
                cached.second
            } else {
                geocode(locationName)?.also { cachedGeocode = locationName to it } ?: return null
            }
            fetchForecast(geocodeResult)
        } catch (_: Exception) {
            null
        }
    }

    // The input name is whatever the viewer typed, which may not match
    // official capitalization/spelling (e.g. "berlin") - the API's own
    // "name" field in the result is the canonical form, and is what gets
    // displayed, not the raw input.
    private data class GeocodeResult(val displayName: String, val latitude: Double, val longitude: Double)

    private fun geocode(locationName: String): GeocodeResult? {
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
            return GeocodeResult(
                displayName = first.optString("name").ifBlank { locationName },
                latitude = first.getDouble("latitude"),
                longitude = first.getDouble("longitude"),
            )
        }
    }

    private fun fetchForecast(geocode: GeocodeResult): WeatherState? {
        val lat = geocode.latitude
        val lon = geocode.longitude
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", lat.toString())
            .addQueryParameter("longitude", lon.toString())
            .addQueryParameter("timezone", "Europe/Berlin")
            .addQueryParameter("forecast_days", "3")
            .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min")
            .addQueryParameter("current", "temperature_2m,surface_pressure,weather_code")
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
            val currentPressure = current?.optDouble("surface_pressure")
                ?.takeUnless { it.isNaN() }
                ?.let { Math.round(it).toInt() }
            val pressureTrend = pressureTrend(previousPressureHpa, currentPressure)
            previousPressureHpa = currentPressure

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
                    iconSlug = WEATHER_ICON_SLUG_MAP[codes?.optInt(i)],
                )
            }

            return WeatherState(
                location = geocode.displayName,
                temperatureC = currentTemp,
                condition = WEATHER_CODE_MAP[currentCode] ?: "Wetter",
                iconSlug = WEATHER_ICON_SLUG_MAP[currentCode],
                pressureHpa = currentPressure,
                pressureTrend = pressureTrend,
                days = days,
            )
        }
    }

    private fun pressureTrend(previous: Int?, current: Int?): String? {
        if (previous == null || current == null) return null
        val delta = current - previous
        return when {
            delta >= PRESSURE_TREND_DELTA_HPA -> "up"
            delta <= -PRESSURE_TREND_DELTA_HPA -> "down"
            else -> "steady"
        }
    }
}
