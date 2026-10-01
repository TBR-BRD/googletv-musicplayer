package de.tbrbd.onradiotv.data

import android.content.Context

private const val PREFS_NAME = "onradio_tv_prefs"
private const val KEY_FAVORITES = "favorite_station_ids"
private const val KEY_LAST_STATION_PREFIX = "last_station_for_group:"
private const val KEY_LAST_PLAYED = "last_played_station_id"
private const val KEY_WEATHER_LOCATION = "weather_location_name"
private const val DEFAULT_WEATHER_LOCATION = "Falkensee"

/**
 * Small local persistence layer (Android SharedPreferences - survives app
 * restarts, stored only on this device). Used for:
 *  - remembering which station was last picked within each category, so
 *    reopening a category doesn't always jump back to its first entry
 *  - a favorites list, and resuming the last-played station on next launch
 */
class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun favoriteIds(): Set<String> =
        prefs.getStringSet(KEY_FAVORITES, emptySet())?.toSet() ?: emptySet()

    fun toggleFavorite(stationId: String): Set<String> {
        val current = favoriteIds().toMutableSet()
        if (!current.add(stationId)) current.remove(stationId)
        prefs.edit().putStringSet(KEY_FAVORITES, current).apply()
        return current
    }

    fun lastStationForGroup(group: String): String? =
        prefs.getString(KEY_LAST_STATION_PREFIX + group, null)

    fun setLastStationForGroup(group: String, stationId: String) {
        prefs.edit().putString(KEY_LAST_STATION_PREFIX + group, stationId).apply()
    }

    fun lastPlayedStationId(): String? = prefs.getString(KEY_LAST_PLAYED, null)

    fun setLastPlayedStationId(stationId: String) {
        prefs.edit().putString(KEY_LAST_PLAYED, stationId).apply()
    }

    fun weatherLocationName(): String =
        prefs.getString(KEY_WEATHER_LOCATION, DEFAULT_WEATHER_LOCATION) ?: DEFAULT_WEATHER_LOCATION

    fun setWeatherLocationName(name: String) {
        prefs.edit().putString(KEY_WEATHER_LOCATION, name.trim().ifEmpty { DEFAULT_WEATHER_LOCATION }).apply()
    }
}
