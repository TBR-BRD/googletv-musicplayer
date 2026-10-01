package de.tbrbd.onradiotv.data

import android.content.Context
import android.util.Log
import de.tbrbd.onradiotv.R
import de.tbrbd.onradiotv.model.Station
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

private const val TAG = "StationRepository"
private const val REMOTE_STATIONS_URL =
    "https://raw.githubusercontent.com/TBR-BRD/radiostations/main/stations.json"

/**
 * Loads the station catalog.
 *
 * [loadBundledStations] reads the snapshot bundled as res/raw/stations.json
 * (generated from onradio-cover-bridge's app/stations.py at build time) -
 * this always works offline and is used immediately on app start.
 *
 * [fetchRemoteStations] then tries to fetch the current catalog from the
 * radiostations repo, which a scheduled GitHub Action keeps in sync with
 * the source project automatically - this is how the app picks up new
 * stations without needing an app update. If it fails (no network, GitHub
 * unreachable, ...), the bundled snapshot keeps being used; this call is a
 * best-effort upgrade, not a requirement for the app to work.
 */
class StationRepository(
    private val context: Context,
    private val client: OkHttpClient,
) {

    fun loadBundledStations(): List<Station> {
        val text = context.resources.openRawResource(R.raw.stations)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        return parseStations(JSONArray(text))
    }

    fun fetchRemoteStations(): List<Station>? {
        return try {
            val request = Request.Builder().url(REMOTE_STATIONS_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Remote station catalog fetch failed: HTTP ${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                val stations = parseStations(JSONArray(body))
                if (stations.isEmpty()) null else stations
            }
        } catch (exc: Exception) {
            Log.w(TAG, "Remote station catalog fetch failed: $exc")
            null
        }
    }

    private fun parseStations(array: JSONArray): List<Station> {
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val aliases = obj.optJSONArray("metadataStationAliases")
            Station(
                id = obj.getString("id"),
                name = obj.getString("name"),
                group = obj.optString("group", "Other stations"),
                homepageUrl = obj.optString("homepageUrl", ""),
                audioUrl = obj.getString("audioUrl"),
                audioMode = obj.optString("audioMode", "direct"),
                metadataUrl = obj.getString("metadataUrl"),
                metadataMode = obj.getString("metadataMode"),
                metadataStationLabel = obj.optString("metadataStationLabel", null),
                metadataStationAliases = aliases?.let { arr ->
                    (0 until arr.length()).map { j -> arr.getString(j) }
                } ?: emptyList(),
                metadataStationId = if (obj.isNull("metadataStationId")) null else obj.optInt("metadataStationId"),
            )
        }
    }
}
