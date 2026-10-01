package de.tbrbd.onradiotv.data

import android.content.Context
import de.tbrbd.onradiotv.R
import de.tbrbd.onradiotv.model.Station
import org.json.JSONArray

/** Loads the station catalog bundled as res/raw/stations.json. */
class StationRepository(private val context: Context) {

    fun loadStations(): List<Station> {
        val text = context.resources.openRawResource(R.raw.stations)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val array = JSONArray(text)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val aliases = obj.optJSONArray("metadataStationAliases")
            Station(
                id = obj.getString("id"),
                name = obj.getString("name"),
                group = obj.optString("group", "Weitere Sender"),
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
