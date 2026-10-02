package de.tbrbd.onradiotv.data

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

private const val TAG = "UpnpRenderer"
private const val SSDP_HOST = "239.255.255.250"
private const val SSDP_PORT = 1900
private const val MEDIA_RENDERER_ST = "urn:schemas-upnp-org:device:MediaRenderer:1"
private const val RENDERER_CACHE_MS = 30_000L

private val CONTENT_TYPE_BY_SUFFIX = mapOf(
    ".aac" to "audio/aac", ".aacp" to "audio/aac", ".flac" to "audio/flac",
    ".m4a" to "audio/mp4", ".mp3" to "audio/mpeg", ".mp4" to "audio/mp4",
    ".oga" to "audio/ogg", ".ogg" to "audio/ogg", ".opus" to "audio/ogg", ".wav" to "audio/wav",
)
private val FALLBACK_METADATA_MIME_TYPES = listOf("audio/mpeg", "audio/aac", "audio/flac", "audio/ogg")

/** Ports app/upnp_renderer.py: SSDP discovery of UPnP MediaRenderer devices
 * (Sonos, Denon and similar) on the LAN, plus SOAP calls against their
 * AVTransport/RenderingControl services - no cloud account or app needed,
 * these are all local-network calls straight to the speaker's own HTTP
 * server (the same one Sonos's own app/the Pi both use). */
data class UpnpRenderer(
    val id: String,
    val udn: String,
    val friendlyName: String,
    val location: String,
    val host: String,
    val avTransportUrl: String?,
    val avTransportType: String?,
    val renderingControlUrl: String?,
    val renderingControlType: String?,
)

class UpnpSoapException(
    val action: String,
    val statusCode: Int?,
    val errorCode: String?,
    val errorDescription: String?,
) : RuntimeException(buildMessage(action, statusCode, errorCode, errorDescription)) {
    companion object {
        private fun buildMessage(action: String, statusCode: Int?, errorCode: String?, errorDescription: String?): String {
            val detail = friendlyFaultDetail(errorCode, errorDescription, null)
            return when {
                detail.isNotEmpty() -> "UPnP $action: $detail"
                statusCode != null -> "UPnP $action fehlgeschlagen (HTTP $statusCode)"
                else -> "UPnP $action fehlgeschlagen"
            }
        }
    }
}

class UpnpRendererService(
    private val context: Context,
    private val client: OkHttpClient,
) {
    private var cachedRenderers: List<UpnpRenderer> = emptyList()
    private var cacheExpiresAt: Long = 0L

    fun listRenderers(forceRefresh: Boolean = false, timeoutSeconds: Int = 3): List<UpnpRenderer> {
        if (!forceRefresh && cachedRenderers.isNotEmpty() && System.currentTimeMillis() < cacheExpiresAt) {
            return cachedRenderers
        }
        val discovered = discover(timeoutSeconds)
        cachedRenderers = discovered
        cacheExpiresAt = System.currentTimeMillis() + RENDERER_CACHE_MS
        return discovered
    }

    fun getRenderer(rendererId: String): UpnpRenderer? {
        val normalized = normalizeId(rendererId)
        listRenderers(forceRefresh = false, timeoutSeconds = 2).find {
            normalizeId(it.id) == normalized || normalizeId(it.udn) == normalized
        }?.let { return it }
        return listRenderers(forceRefresh = true, timeoutSeconds = 4).find {
            normalizeId(it.id) == normalized || normalizeId(it.udn) == normalized
        }
    }

    fun getVolume(renderer: UpnpRenderer): Int? {
        val url = renderer.renderingControlUrl ?: return null
        val type = renderer.renderingControlType ?: return null
        val root = soapAction(url, type, "GetVolume", linkedMapOf("InstanceID" to "0", "Channel" to "Master"))
        val value = findDescendantText(root, "CurrentVolume") ?: return null
        return value.toFloatOrNull()?.toInt()?.coerceIn(0, 100)
    }

    fun setVolume(renderer: UpnpRenderer, percent: Int): Int? {
        val url = renderer.renderingControlUrl ?: throw RuntimeException("Lautstärke wird von diesem WLAN-Lautsprecher nicht unterstützt")
        val type = renderer.renderingControlType ?: throw RuntimeException("Lautstärke wird von diesem WLAN-Lautsprecher nicht unterstützt")
        val clamped = percent.coerceIn(0, 100)
        soapAction(
            url, type, "SetVolume",
            linkedMapOf("InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to clamped.toString()),
        )
        return getVolume(renderer)
    }

    fun getMute(renderer: UpnpRenderer): Boolean? {
        val url = renderer.renderingControlUrl ?: return null
        val type = renderer.renderingControlType ?: return null
        val root = soapAction(url, type, "GetMute", linkedMapOf("InstanceID" to "0", "Channel" to "Master"))
        val value = findDescendantText(root, "CurrentMute") ?: return null
        return value.trim() in setOf("1", "true", "True", "yes", "on")
    }

    fun setMute(renderer: UpnpRenderer, muted: Boolean): Boolean? {
        val url = renderer.renderingControlUrl ?: throw RuntimeException("Stummschaltung wird von diesem WLAN-Lautsprecher nicht unterstützt")
        val type = renderer.renderingControlType ?: throw RuntimeException("Stummschaltung wird von diesem WLAN-Lautsprecher nicht unterstützt")
        soapAction(
            url, type, "SetMute",
            linkedMapOf("InstanceID" to "0", "Channel" to "Master", "DesiredMute" to if (muted) "1" else "0"),
        )
        return getMute(renderer)
    }

    fun getTransportState(renderer: UpnpRenderer): String? {
        val url = renderer.avTransportUrl ?: return null
        val type = renderer.avTransportType ?: return null
        val root = soapAction(url, type, "GetTransportInfo", linkedMapOf("InstanceID" to "0"))
        return findDescendantText(root, "CurrentTransportState")
    }

    fun playStream(
        renderer: UpnpRenderer,
        streamUrl: String,
        title: String = "Radio Stream",
        artist: String = "",
        stationName: String = "",
    ) {
        val avUrl = renderer.avTransportUrl ?: throw RuntimeException("Wiedergabe wird von diesem WLAN-Lautsprecher nicht unterstützt")
        val avType = renderer.avTransportType ?: throw RuntimeException("Wiedergabe wird von diesem WLAN-Lautsprecher nicht unterstützt")

        try {
            stop(renderer)
        } catch (_: Exception) {
            // Best-effort - some renderers error on Stop when already idle.
        }

        val metadataCandidates = buildMetadataCandidates(
            streamUrl = streamUrl,
            title = title,
            artist = artist,
            stationName = stationName,
            probedMimeType = probeStreamContentType(streamUrl),
        )

        var lastError: Exception? = null
        for (metadata in metadataCandidates) {
            try {
                soapAction(
                    avUrl, avType, "SetAVTransportURI",
                    linkedMapOf("InstanceID" to "0", "CurrentURI" to streamUrl, "CurrentURIMetaData" to metadata),
                )
                Thread.sleep(150)
                soapAction(avUrl, avType, "Play", linkedMapOf("InstanceID" to "0", "Speed" to "1"))
                return
            } catch (exc: UpnpSoapException) {
                lastError = exc
                if (exc.action == "SetAVTransportURI") continue
                throw RuntimeException(friendlyPlayError(renderer, exc), exc)
            } catch (exc: Exception) {
                lastError = exc
                break
            }
        }
        throw RuntimeException(
            lastError?.let { friendlyPlayError(renderer, it) } ?: "${renderer.friendlyName} konnte den Stream nicht starten",
            lastError,
        )
    }

    fun stop(renderer: UpnpRenderer) {
        val url = renderer.avTransportUrl ?: throw RuntimeException("Stopp wird von diesem WLAN-Lautsprecher nicht unterstützt")
        val type = renderer.avTransportType ?: throw RuntimeException("Stopp wird von diesem WLAN-Lautsprecher nicht unterstützt")
        soapAction(url, type, "Stop", linkedMapOf("InstanceID" to "0"))
    }

    /** Many station URLs have no file extension (e.g. ".../mp3-192/homepage/"),
     * so guessing from the URL alone often fails - a quick HEAD (falling
     * back to GET) reveals the real Content-Type most servers send. */
    private fun probeStreamContentType(url: String): String? {
        contentTypeFromUrl(url)?.let { return it }
        for (method in listOf("HEAD", "GET")) {
            try {
                val requestBuilder = Request.Builder().url(url)
                if (method == "GET") requestBuilder.header("Range", "bytes=0-0")
                val request = if (method == "HEAD") requestBuilder.head().build() else requestBuilder.build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    cleanContentType(response.header("Content-Type"))?.let { return it }
                }
            } catch (_: Exception) {
                // Try the next method.
            }
        }
        return null
    }

    // --- Discovery -----------------------------------------------------

    private fun discover(timeoutSeconds: Int): List<UpnpRenderer> {
        val locations = LinkedHashSet<String>()
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifiManager?.createMulticastLock("$TAG-discovery")
        multicastLock?.setReferenceCounted(true)
        try {
            multicastLock?.acquire()
        } catch (exc: Exception) {
            Log.w(TAG, "Could not acquire multicast lock: $exc")
        }

        try {
            val mx = timeoutSeconds.coerceIn(1, 5)
            val payload = (
                "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: $SSDP_HOST:$SSDP_PORT\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: $mx\r\n" +
                    "ST: $MEDIA_RENDERER_ST\r\n" +
                    "USER-AGENT: onradiotv/1.0 UPnP/1.1 Android\r\n" +
                    "\r\n"
                ).toByteArray(Charsets.UTF_8)

            val socket = DatagramSocket()
            try {
                socket.soTimeout = 500
                val multicastAddress = InetAddress.getByName(SSDP_HOST)
                repeat(2) {
                    try {
                        socket.send(DatagramPacket(payload, payload.size, InetSocketAddress(multicastAddress, SSDP_PORT)))
                    } catch (exc: Exception) {
                        Log.w(TAG, "M-SEARCH send failed: $exc")
                    }
                }

                val buffer = ByteArray(65535)
                val deadline = System.currentTimeMillis() + timeoutSeconds.coerceAtLeast(1) * 1000L
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val headers = parseSsdpResponse(String(packet.data, 0, packet.length, Charsets.UTF_8))
                        headers["location"]?.let { locations.add(it) }
                    } catch (_: SocketTimeoutException) {
                        // Expected - just keep polling until the deadline.
                    }
                }
                Log.d(TAG, "Discovery found ${locations.size} candidate device(s)")
            } finally {
                socket.close()
            }
        } finally {
            try {
                if (multicastLock?.isHeld == true) multicastLock.release()
            } catch (_: Exception) {
                // Ignore - nothing useful to do if releasing fails.
            }
        }

        return locations.mapNotNull { location ->
            try {
                fetchRendererDescription(location)
            } catch (exc: Exception) {
                Log.w(TAG, "Failed to fetch renderer description from $location: $exc")
                null
            }
        }.sortedBy { it.friendlyName.lowercase() }
    }

    private fun fetchRendererDescription(location: String): UpnpRenderer? {
        val request = Request.Builder().url(location).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.bytes() ?: return null
            val document = parseXml(body) ?: return null

            val baseUrl = findDirectChildText(document.documentElement, "URLBase")?.trim()?.ifBlank { null }
                ?: baseUrlFromLocation(location)
            val device = findMediaRendererDevice(document) ?: return null

            val friendlyName = findDirectChildText(device, "friendlyName")?.trim()?.ifBlank { null } ?: "WLAN-Lautsprecher"
            val udn = findDirectChildText(device, "UDN")?.trim()?.ifBlank { null } ?: location
            val avTransport = findService(device, "AVTransport")
            val renderingControl = findService(device, "RenderingControl")

            val uri = URI(location)
            val host = uri.host ?: friendlyName
            return UpnpRenderer(
                id = "upnp:${normalizeIdentifier(udn)}",
                udn = udn,
                friendlyName = friendlyName,
                location = location,
                host = host,
                avTransportUrl = avTransport?.second?.let { resolveUrl(baseUrl, it) },
                avTransportType = avTransport?.first,
                renderingControlUrl = renderingControl?.second?.let { resolveUrl(baseUrl, it) },
                renderingControlType = renderingControl?.first,
            )
        }
    }

    // --- SOAP ------------------------------------------------------------

    private fun soapAction(controlUrl: String, serviceType: String, action: String, arguments: Map<String, String>): Element {
        val envelope = buildSoapEnvelope(serviceType, action, arguments)
        val request = Request.Builder()
            .url(controlUrl)
            .addHeader("SOAPAction", "\"$serviceType#$action\"")
            .post(envelope.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes()
            val document = bytes?.let { parseXml(it) }
            val fault = document?.let { extractSoapFault(it.documentElement) }
            if (!response.isSuccessful || fault != null) {
                Log.w(TAG, "soapAction $action -> HTTP ${response.code}, fault=$fault")
                throw UpnpSoapException(
                    action = action,
                    statusCode = response.code,
                    errorCode = fault?.first,
                    errorDescription = fault?.second,
                )
            }
            val root = document?.documentElement ?: throw RuntimeException("UPnP $action lieferte keine gültige XML-Antwort")
            return root
        }
    }
}

// --- Free functions (XML/SSDP/DIDL helpers, no instance state needed) ------

private fun normalizeId(value: String): String {
    var normalized = value.trim().lowercase()
    if (normalized.startsWith("upnp:")) normalized = normalized.substring(5)
    return normalized
}

private fun normalizeIdentifier(value: String): String {
    val builder = StringBuilder()
    for (char in value.trim().lowercase()) {
        if (char.isLetterOrDigit() || char == '-' || char == '_') builder.append(char)
    }
    return builder.toString().ifEmpty { "renderer" }
}

private fun parseSsdpResponse(text: String): Map<String, String> {
    val headers = mutableMapOf<String, String>()
    text.lineSequence().drop(1).forEach { line ->
        val index = line.indexOf(':')
        if (index <= 0) return@forEach
        val key = line.substring(0, index).trim().lowercase()
        val value = line.substring(index + 1).trim()
        headers[key] = value
    }
    return headers
}

private fun baseUrlFromLocation(location: String): String {
    val uri = URI(location)
    return "${uri.scheme}://${uri.authority}/"
}

private fun resolveUrl(baseUrl: String, relative: String): String = URI(baseUrl).resolve(relative).toString()

private fun parseXml(bytes: ByteArray): Document? = try {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    factory.newDocumentBuilder().parse(StringReader(String(bytes, Charsets.UTF_8)).let {
        org.xml.sax.InputSource(it)
    })
} catch (_: Exception) {
    null
}

/** Direct child (not descendant) lookup by local name, namespace-agnostic -
 * mirrors Python's `./{*}tagName` ElementTree query. */
private fun findDirectChildText(element: Element, localName: String): String? {
    val children = element.childNodes
    for (i in 0 until children.length) {
        val node = children.item(i)
        if (node.nodeType == Node.ELEMENT_NODE && node.localName == localName) {
            return node.textContent
        }
    }
    return null
}

/** Any-depth descendant lookup by local name, namespace-agnostic - mirrors
 * Python's `.//{*}tagName` ElementTree query. */
private fun findDescendantText(element: Element, localName: String): String? {
    val matches = element.getElementsByTagNameNS("*", localName)
    if (matches.length == 0) return null
    return matches.item(0).textContent?.trim()?.ifBlank { null }
}

private fun findMediaRendererDevice(document: Document): Element? {
    val devices = document.getElementsByTagNameNS("*", "device")
    for (i in 0 until devices.length) {
        val device = devices.item(i) as? Element ?: continue
        val deviceType = findDirectChildText(device, "deviceType") ?: ""
        if (deviceType.contains("MediaRenderer")) return device
    }
    return null
}

/** (serviceType, controlURL) for the first service of this device whose
 * serviceType contains serviceName (e.g. "AVTransport"). */
private fun findService(device: Element, serviceName: String): Pair<String, String>? {
    val services = device.getElementsByTagNameNS("*", "service")
    for (i in 0 until services.length) {
        val service = services.item(i) as? Element ?: continue
        val serviceType = findDirectChildText(service, "serviceType") ?: ""
        if (!serviceType.contains(serviceName)) continue
        val controlUrl = findDirectChildText(service, "controlURL")?.trim()
        if (controlUrl.isNullOrEmpty()) continue
        return serviceType to controlUrl
    }
    return null
}

private fun xmlEscape(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

private fun buildSoapEnvelope(serviceType: String, action: String, arguments: Map<String, String>): String {
    val argumentsXml = arguments.entries.joinToString("") { (key, value) -> "<$key>${xmlEscape(value)}</$key>" }
    return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
        "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
        "<s:Body>" +
        "<u:$action xmlns:u=\"${xmlEscape(serviceType)}\">$argumentsXml</u:$action>" +
        "</s:Body>" +
        "</s:Envelope>"
}

/** (errorCode, errorDescription) if the SOAP response contains a Fault. */
private fun extractSoapFault(root: Element): Pair<String, String>? {
    val faults = root.getElementsByTagNameNS("*", "Fault")
    if (faults.length == 0) return null
    val fault = faults.item(0) as Element
    val errorCode = findDescendantText(fault, "errorCode") ?: ""
    val errorDescription = findDescendantText(fault, "errorDescription") ?: ""
    return errorCode to errorDescription
}

private fun friendlyFaultDetail(errorCode: String?, errorDescription: String?, rawDetail: String?): String {
    val code = errorCode?.trim() ?: ""
    val description = errorDescription?.trim() ?: ""
    val descriptionLower = description.lowercase()
    return when {
        code == "714" || descriptionLower.contains("mime") -> "Der Lautsprecher lehnt das Stream-Format ab"
        code == "701" -> "Der Stream konnte vom Lautsprecher nicht gefunden oder geöffnet werden"
        code == "702" -> "Der Lautsprecher blockiert den Stream im Moment"
        code == "716" -> "Der Lautsprecher akzeptiert diese Stream-Adresse nicht"
        code == "718" -> "Der Lautsprecher meldet eine ungültige Wiedergabeinstanz"
        description.isNotEmpty() -> description
        !rawDetail.isNullOrEmpty() -> rawDetail.take(180)
        else -> ""
    }
}

private fun friendlyPlayError(renderer: UpnpRenderer, error: Throwable): String {
    if (error is UpnpSoapException) {
        val suffix = friendlyFaultDetail(error.errorCode, error.errorDescription, null)
        return if (suffix.isNotEmpty()) {
            "${renderer.friendlyName}: $suffix."
        } else {
            "${renderer.friendlyName}: UPnP-Wiedergabe konnte nicht gestartet werden."
        }
    }
    return "${renderer.friendlyName}: ${error.message ?: error}"
}

private fun contentTypeFromUrl(url: String): String? {
    val path = try {
        URI(url).path ?: ""
    } catch (_: Exception) {
        ""
    }
    val lowered = path.lowercase()
    return CONTENT_TYPE_BY_SUFFIX.entries.find { (suffix, _) -> lowered.endsWith(suffix) }?.value
}

private fun cleanContentType(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val cleaned = value.split(";", limit = 2)[0].trim().lowercase()
    if (cleaned.isEmpty() || cleaned == "application/octet-stream" || cleaned == "binary/octet-stream") return null
    if (!cleaned.startsWith("audio/")) return null
    return if (cleaned == "audio/aacp") "audio/aac" else cleaned
}

/** Some renderers (certain Denon AVRs among them, by community report) are
 * strict about `protocolInfo` and reject the generic `http-get:*:<mime>:*`
 * wildcard outright - a proper DLNA.ORG_PN profile string satisfies them
 * instead. OP=00 (no seek support) because a radio stream has no known
 * length to seek within; the FLAGS value is the standard bitmask
 * (sender-paced, background transfer, connection-stalling, DLNA v1.5) most
 * DLNA servers use for unbounded live audio. Returns null for mime types
 * with no well-known DLNA profile name, which just skips this variant. */
private fun dlnaProtocolInfoFor(mimeType: String): String? {
    val clean = cleanContentType(mimeType) ?: return null
    val profile = when (clean) {
        "audio/mpeg" -> "MP3"
        "audio/aac" -> "AAC_ADTS"
        else -> null
    } ?: return null
    return "http-get:*:$clean:DLNA.ORG_PN=$profile;DLNA.ORG_OP=00;DLNA.ORG_FLAGS=01700000000000000000000000000000"
}

private fun buildDidlMetadata(
    streamUrl: String,
    title: String,
    artist: String,
    stationName: String,
    protocolInfo: String,
    itemClass: String,
): String {
    val safeTitle = title.ifBlank { stationName.ifBlank { "Radio Stream" } }
    val safeStation = stationName.ifBlank { "Radio" }
    val safeArtist = artist.ifBlank { safeStation }
    return "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
        "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
        "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">" +
        "<item id=\"0\" parentID=\"0\" restricted=\"0\">" +
        "<dc:title>${xmlEscape(safeTitle)}</dc:title>" +
        "<dc:creator>${xmlEscape(safeArtist)}</dc:creator>" +
        "<upnp:artist>${xmlEscape(safeArtist)}</upnp:artist>" +
        "<upnp:album>${xmlEscape(safeStation)}</upnp:album>" +
        "<upnp:class>${xmlEscape(itemClass)}</upnp:class>" +
        "<res protocolInfo=\"${xmlEscape(protocolInfo)}\">${xmlEscape(streamUrl)}</res>" +
        "</item>" +
        "</DIDL-Lite>"
}

private fun buildMetadataCandidates(
    streamUrl: String,
    title: String,
    artist: String,
    stationName: String,
    probedMimeType: String?,
): List<String> {
    val mimeCandidates = buildList {
        probedMimeType?.let { add(it) }
        addAll(FALLBACK_METADATA_MIME_TYPES)
    }

    val candidates = mutableListOf("")
    val seen = mutableSetOf("")
    for (mimeType in mimeCandidates) {
        val clean = cleanContentType(mimeType) ?: mimeType
        // Try the stricter DLNA-profile protocolInfo first (more likely to
        // satisfy a picky renderer), generic wildcard second.
        val protocolInfoCandidates = listOfNotNull(dlnaProtocolInfoFor(mimeType), "http-get:*:$clean:*")
        for (protocolInfo in protocolInfoCandidates) {
            for (itemClass in listOf("object.item.audioItem.audioBroadcast", "object.item.audioItem.musicTrack")) {
                val metadata = buildDidlMetadata(streamUrl, title, artist, stationName, protocolInfo, itemClass)
                if (seen.add(metadata)) candidates.add(metadata)
            }
        }
    }
    return candidates
}
