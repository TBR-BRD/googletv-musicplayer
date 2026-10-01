package de.tbrbd.onradiotv.model

/**
 * Mirrors app/stations.py's Station dataclass on the Python/Pi side. The
 * concrete catalog (see StationRepository) is generated once from that same
 * source of truth and shipped as res/raw/stations.json, so this app needs no
 * server to know what stations exist.
 */
data class Station(
    val id: String,
    val name: String,
    val group: String,
    val homepageUrl: String,
    val audioUrl: String,
    val audioMode: String, // "direct" | "pls" | "m3u"
    val metadataUrl: String,
    val metadataMode: String, // "0nradio_json" | "icy_stream" | "80s80s_api" | ...
    val metadataStationLabel: String?,
    val metadataStationAliases: List<String>,
    val metadataStationId: Int?,
)
