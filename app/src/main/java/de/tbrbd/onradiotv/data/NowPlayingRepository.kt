package de.tbrbd.onradiotv.data

import android.util.Log
import de.tbrbd.onradiotv.model.NowPlaying
import de.tbrbd.onradiotv.model.Station
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.CodingErrorAction

private const val TAG = "NowPlayingRepository"

private val STREAM_TITLE_RE = Regex("""StreamTitle=['"]([^'"]*)['"];""", RegexOption.IGNORE_CASE)
private val ARTIST_KEYS = listOf("artist", "artist_name", "artistName", "interpret")
private val TITLE_KEYS = listOf("title", "song_title", "songTitle", "track")
private val CURRENT_KEYS = listOf("current", "now", "now_playing", "playing")

/**
 * Ports the relevant parts of app/playlist_fetcher.py. "0nradio_json",
 * "icy_stream" and "80s80s_api" (together covering the entire current
 * station catalog) are fully implemented; any future mode not covered here
 * degrades gracefully to just the station name instead of a real title.
 */
class NowPlayingRepository(
    private val client: OkHttpClient,
    private val audioStreamResolver: AudioStreamResolver,
) {

    fun fetch(station: Station): NowPlaying {
        return try {
            when (station.metadataMode) {
                "0nradio_json" -> fetchOnRadioJson(station)
                "icy_stream" -> fetchIcyStream(station)
                "80s80s_api" -> fetch80s80sApi(station)
                else -> fallback(station)
            }
        } catch (exc: Exception) {
            Log.w(TAG, "Metadata fetch failed for ${station.id} (${station.metadataMode}): $exc")
            fallback(station)
        }
    }

    private fun fallback(station: Station) = NowPlaying(artist = station.name, title = "Livestream")

    private fun fetchOnRadioJson(station: Station): NowPlaying {
        val request = Request.Builder().url(station.metadataUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return fallback(station)
            val body = response.body?.string() ?: return fallback(station)
            val payload = JSONObject(body)
            val current = extractOnRadioCurrentNode(payload) ?: return fallback(station)
            val artist = firstText(current, ARTIST_KEYS)
            val title = firstText(current, TITLE_KEYS)
            return if (artist.isNotBlank() && title.isNotBlank()) {
                NowPlaying(artist = artist, title = title)
            } else {
                fallback(station)
            }
        }
    }

    private fun extractOnRadioCurrentNode(payload: JSONObject): JSONObject? {
        payload.optJSONObject("items")?.let { items ->
            for (key in CURRENT_KEYS) {
                items.optJSONObject(key)?.let { return it }
            }
        }
        for (key in CURRENT_KEYS) {
            payload.optJSONObject(key)?.let { return it }
        }
        return findTrackLikeNode(payload)
    }

    /** Generic fallback: walk the whole JSON tree for any object that looks
     * like a track (has both an artist-ish and a title-ish field). */
    private fun findTrackLikeNode(value: Any?): JSONObject? {
        when (value) {
            is JSONObject -> {
                val artist = firstText(value, ARTIST_KEYS)
                val title = firstText(value, TITLE_KEYS)
                if (artist.isNotBlank() && title.isNotBlank()) return value
                for (key in value.keys()) {
                    findTrackLikeNode(value.opt(key))?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    findTrackLikeNode(value.opt(i))?.let { return it }
                }
            }
            else -> Unit
        }
        return null
    }

    private fun firstText(node: JSONObject, keys: List<String>): String {
        for (key in keys) {
            val value = node.opt(key) ?: continue
            val text = value.toString().trim()
            if (text.isNotEmpty() && text != "null") return text
        }
        return ""
    }

    /** The 80s80s API serves every one of their streams' now-playing info in
     * a single shared JSON blob, keyed by an arbitrary/irrelevant string -
     * find this station's own entry by its stable metadataStationId. */
    private fun fetch80s80sApi(station: Station): NowPlaying {
        val stationId = station.metadataStationId ?: return fallback(station)
        val request = Request.Builder().url(station.metadataUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return fallback(station)
            val body = response.body?.string() ?: return fallback(station)
            val payload = JSONObject(body)
            val match = payload.keys().asSequence()
                .mapNotNull { payload.optJSONObject(it) }
                .find { it.optInt("station_id", -1) == stationId }
                ?: return fallback(station)

            val artist = firstText(match, listOf("artist_name", "artist", "artistName"))
            val title = firstText(match, listOf("song_title", "title", "songTitle"))
            if (artist.isBlank() || title.isBlank()) return fallback(station)

            return NowPlaying(artist = artist, title = title, providerCoverUrl = extract80s80sCoverUrl(match))
        }
    }

    private fun extract80s80sCoverUrl(node: JSONObject): String? {
        val covers = node.optJSONObject("covers") ?: return null
        for (key in listOf(
            "cover_art_url_xxl",
            "cover_art_url_xl",
            "cover_art_url_l",
            "cover_art_url_m",
            "cover_art_url_s",
            "cover_art_url_xs",
        )) {
            val url = covers.optString(key).trim()
            if (url.isNotEmpty()) return url
        }
        return null
    }

    private fun fetchIcyStream(station: Station): NowPlaying {
        val resolvedUrl = audioStreamResolver.resolve(station)
        val request = Request.Builder()
            .url(resolvedUrl)
            .header("Icy-MetaData", "1")
            .header("Accept", "*/*")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "${station.id}: HTTP ${response.code} from $resolvedUrl")
                return fallback(station)
            }
            val metaInt = response.header("icy-metaint")?.toIntOrNull()
            if (metaInt == null) {
                Log.w(TAG, "${station.id}: no icy-metaint header (headers: ${response.headers})")
                return fallback(station)
            }
            if (metaInt <= 0 || metaInt > 1_048_576) {
                Log.w(TAG, "${station.id}: implausible icy-metaint=$metaInt")
                return fallback(station)
            }

            val input = response.body?.byteStream() ?: return fallback(station)
            repeat(3) {
                if (!skipFully(input, metaInt)) {
                    Log.w(TAG, "${station.id}: stream ended while skipping audio block")
                    return fallback(station)
                }
                val lengthByte = input.read()
                if (lengthByte < 0) {
                    Log.w(TAG, "${station.id}: stream ended while reading metadata length byte")
                    return fallback(station)
                }
                val metadataLength = lengthByte * 16
                if (metadataLength <= 0) return@repeat
                val metadataBytes = ByteArray(metadataLength)
                if (!readFully(input, metadataBytes)) {
                    Log.w(TAG, "${station.id}: stream ended while reading metadata block")
                    return fallback(station)
                }
                val decoded = decodeIcyMetadata(metadataBytes).trimEnd('\u0000')
                Log.d(TAG, "${station.id}: raw ICY metadata = ${decoded.take(200)}")
                val match = STREAM_TITLE_RE.find(decoded) ?: return@repeat
                val streamTitle = match.groupValues[1].trim()
                if (streamTitle.isEmpty()) return@repeat
                return splitStreamTitle(streamTitle, station)
            }
        }
        return fallback(station)
    }

    /** ICY StreamTitle bytes are usually Latin-1 (most Shoutcast/Icecast
     * servers), occasionally UTF-8 - there's no header announcing which.
     * Try strict UTF-8 first since it rejects anything that isn't valid
     * UTF-8, and only reinterpret as Latin-1 when that fails. */
    private fun decodeIcyMetadata(bytes: ByteArray): String {
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    private fun splitStreamTitle(streamTitle: String, station: Station): NowPlaying {
        for (separator in listOf(" - ", " – ", " — ", " | ", " ~ ")) {
            val index = streamTitle.indexOf(separator)
            if (index <= 0) continue
            val artist = streamTitle.substring(0, index).trim()
            val title = streamTitle.substring(index + separator.length).trim()
            if (artist.isNotEmpty() && title.isNotEmpty()) {
                return NowPlaying(artist = artist, title = title)
            }
        }
        return NowPlaying(artist = station.name, title = streamTitle)
    }

    private fun skipFully(input: java.io.InputStream, count: Int): Boolean {
        var remaining = count
        val buffer = ByteArray(minOf(count, 8192))
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(remaining, buffer.size))
            if (read < 0) return false
            remaining -= read
        }
        return true
    }

    private fun readFully(input: java.io.InputStream, dest: ByteArray): Boolean {
        var offset = 0
        while (offset < dest.size) {
            val read = input.read(dest, offset, dest.size - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }
}
