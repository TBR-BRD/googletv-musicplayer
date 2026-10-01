package de.tbrbd.onradiotv.model

data class NowPlaying(
    val artist: String,
    val title: String,
    // Some metadata sources (e.g. the 80s80s API) hand back a cover image
    // directly alongside the track info - more reliable than an iTunes
    // search guess, so use it as-is when present instead of searching.
    val providerCoverUrl: String? = null,
)

data class WeatherDay(
    val label: String,
    val tempMaxC: Double?,
    val tempMinC: Double?,
    val condition: String,
)

data class WeatherState(
    val location: String,
    val temperatureC: Double?,
    val condition: String,
    val iconSlug: String?,
    val pressureHpa: Int?,
    // "up" | "down" | "steady" | null (null until a second reading exists
    // to compare against) - mirrors app/weather_service.py's trend field.
    val pressureTrend: String?,
    val days: List<WeatherDay>,
)
