package de.tbrbd.onradiotv.model

data class NowPlaying(
    val artist: String,
    val title: String,
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
    val days: List<WeatherDay>,
)
