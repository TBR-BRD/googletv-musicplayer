package de.tbrbd.onradiotv.data

import de.tbrbd.onradiotv.model.Station
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Ports app/audio_resolver.py: most stations point directly at a playable
 * stream URL ("direct"), but some hand out a .pls or .m3u playlist file that
 * has to be fetched once and parsed for the real stream URL.
 */
class AudioStreamResolver(private val client: OkHttpClient) {

    private val cache = mutableMapOf<String, String>()

    fun resolve(station: Station): String {
        return when (station.audioMode) {
            "direct" -> station.audioUrl
            "pls" -> cache.getOrPut(station.id) { fetchAndParse(station.audioUrl, ::parsePls) }
            "m3u" -> cache.getOrPut(station.id) { fetchAndParse(station.audioUrl, ::parseM3u) }
            else -> station.audioUrl
        }
    }

    private fun fetchAndParse(url: String, parser: (String) -> String?): String {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            return parser(body) ?: url
        }
    }

    private fun parsePls(text: String): String? {
        val regex = Regex("^File\\d+=(.+)$", RegexOption.IGNORE_CASE)
        for (line in text.lineSequence()) {
            val match = regex.find(line.trim()) ?: continue
            val url = match.groupValues[1].trim()
            if (url.isNotEmpty()) return url
        }
        return null
    }

    private fun parseM3u(text: String): String? {
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("http://") || line.startsWith("https://")) return line
        }
        return null
    }
}
