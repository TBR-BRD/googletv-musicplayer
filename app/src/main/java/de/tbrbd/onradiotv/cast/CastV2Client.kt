package de.tbrbd.onradiotv.cast

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import org.json.JSONObject

private const val TAG = "CastV2Client"
private const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
private const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
private const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
private const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
private const val DEFAULT_MEDIA_RECEIVER_APP_ID = "CC1AD845"
private const val SENDER_ID = "sender-0"
private const val RECEIVER_ID = "receiver-0"

/** A from-scratch CASTV2 sender - talks straight to a Chromecast/Google
 * Cast device's own TLS socket on port 8009 using the same reverse
 * engineered (but widely relied upon - pychromecast, node-castv2,
 * go-chromecast, ...) wire protocol those libraries use, instead of
 * Google's official Cast SDK. Built because that SDK's CastContext throws
 * ModuleUnavailableException on at least one real device tested (Play
 * Services there lacks the Cast framework "dynamite" module entirely -
 * likely gated to Google-TV-certified hardware) even though the device
 * can open a plain TLS socket to the receiver just fine. */
class CastV2Client(private val host: String, private val port: Int) {

    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var input: InputStream? = null
    @Volatile private var running = false
    private var requestIdCounter = 1000

    // loadMedia()/setVolume()/stopMedia() get called from TvViewModel on
    // Main (same as the official SDK's calls were, which were safe there) -
    // but writing to a socket is network I/O, which Android throws
    // NetworkOnMainThreadException for outside a background thread. A
    // single-threaded executor both fixes that and serializes writes so two
    // commands issued close together can't interleave their bytes on the
    // wire.
    private val writerExecutor = Executors.newSingleThreadExecutor()

    @Volatile var transportId: String? = null
        private set
    @Volatile private var sessionId: String? = null
    // The *media* session id (distinct from the app-level sessionId above) -
    // a new one is assigned by the receiver on every LOAD, and STOP/PAUSE/
    // SEEK are required to target it explicitly. Omitting it (as an earlier
    // version of this did) meant the receiver had no way to tell which
    // playback to act on and silently ignored STOP.
    @Volatile private var mediaSessionId: Int? = null
    @Volatile var currentVolumePercent: Int? = null

    @Volatile var onDisconnected: (() -> Unit)? = null

    /** Blocking - caller must run this off the main thread. Returns once
     * the TLS handshake and initial CONNECT handshake message are sent;
     * the receiver's actual response arrives later on the reader thread. */
    fun connect() {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        // Cast devices use a self-signed cert with no public CA chain -
        // every CASTV2 client trusts it blind the same way; the TLS layer
        // here is just for transport privacy on the LAN, not identity.
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustAll), SecureRandom())
        val tlsSocket = sslContext.socketFactory.createSocket(InetAddress.getByName(host), port) as SSLSocket
        tlsSocket.startHandshake()
        socket = tlsSocket
        output = tlsSocket.outputStream
        input = tlsSocket.inputStream
        running = true

        send(NS_CONNECTION, RECEIVER_ID, JSONObject().put("type", "CONNECT"))

        thread(name = "CastV2Reader") { readLoop() }
    }

    fun launchMediaReceiver() {
        send(
            NS_RECEIVER, RECEIVER_ID,
            JSONObject()
                .put("type", "LAUNCH")
                .put("appId", DEFAULT_MEDIA_RECEIVER_APP_ID)
                .put("requestId", nextRequestId()),
        )
    }

    fun loadMedia(streamUrl: String, title: String, artist: String) {
        val destination = transportId ?: run {
            Log.w(TAG, "loadMedia called before the receiver app reported a transportId")
            return
        }
        val metadata = JSONObject()
            .put("metadataType", 0)
            .put("title", title)
            .put("artist", artist)
        val media = JSONObject()
            .put("contentId", streamUrl)
            .put("contentType", "audio/mpeg")
            // BUFFERED (not LIVE) trades a little latency for a bigger
            // buffer that rides out WiFi jitter much better - matches
            // app/cast_renderer.py's default on the Pi for the same reason.
            .put("streamType", "BUFFERED")
            .put("metadata", metadata)
        val payload = JSONObject()
            .put("type", "LOAD")
            .put("requestId", nextRequestId())
            .put("media", media)
            .put("autoplay", true)
            .put("currentTime", 0)
        sessionId?.let { payload.put("sessionId", it) }
        Log.d(TAG, "loadMedia: destination=$destination url=$streamUrl")
        send(NS_MEDIA, destination, payload)
    }

    fun stopMedia() {
        val destination = transportId ?: return
        val payload = JSONObject().put("type", "STOP").put("requestId", nextRequestId())
        sessionId?.let { payload.put("sessionId", it) }
        // Required for STOP to actually target the right playback - without
        // it the receiver has no way to know which media session to act on
        // (a new one is assigned on every LOAD) and just ignores the
        // command, leaving the previous station still audibly playing.
        mediaSessionId?.let { payload.put("mediaSessionId", it) }
        Log.d(TAG, "stopMedia: destination=$destination mediaSessionId=$mediaSessionId")
        send(NS_MEDIA, destination, payload)
    }

    fun setVolume(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        // Set optimistically so a getVolumePercent() right after this call
        // reflects it immediately, rather than whatever stale value was
        // last reported - corrected anyway by the next RECEIVER_STATUS
        // broadcast if the device actually applied something different.
        currentVolumePercent = clamped
        send(
            NS_RECEIVER, RECEIVER_ID,
            JSONObject()
                .put("type", "SET_VOLUME")
                .put("requestId", nextRequestId())
                .put("volume", JSONObject().put("level", clamped / 100.0)),
        )
    }

    fun disconnect() {
        running = false
        try {
            send(NS_CONNECTION, RECEIVER_ID, JSONObject().put("type", "CLOSE"))
        } catch (_: Exception) {
            // Best-effort - the socket may already be half-closed.
        }
        // Give that CLOSE message a brief window to actually go out before
        // the socket closes out from under it, then stop accepting more.
        writerExecutor.execute {
            try {
                socket?.close()
            } catch (_: Exception) {
                // Nothing useful to do if closing fails.
            }
        }
        writerExecutor.shutdown()
    }

    private fun readLoop() {
        try {
            while (running) {
                val stream = input ?: break
                val body = readFramedMessage(stream) ?: break
                handleIncoming(decodeCastMessage(body))
            }
        } catch (exc: Exception) {
            if (running) Log.w(TAG, "Read loop ended: $exc")
        } finally {
            running = false
            onDisconnected?.invoke()
        }
    }

    private fun handleIncoming(message: DecodedCastMessage) {
        when (message.namespace) {
            NS_HEARTBEAT -> {
                val type = runCatching { JSONObject(message.payload).optString("type") }.getOrNull()
                if (type == "PING") send(NS_HEARTBEAT, RECEIVER_ID, JSONObject().put("type", "PONG"))
            }
            NS_RECEIVER -> handleReceiverStatus(message.payload)
            NS_MEDIA -> handleMediaStatus(message.payload)
            else -> Unit
        }
    }

    private fun handleMediaStatus(payloadJson: String) {
        val json = runCatching { JSONObject(payloadJson) }.getOrNull() ?: return
        if (json.optString("type") != "MEDIA_STATUS") return
        val status = json.optJSONArray("status") ?: return
        if (status.length() == 0) return
        val id = status.optJSONObject(0)?.optInt("mediaSessionId", -1) ?: -1
        if (id >= 0) mediaSessionId = id
    }

    private fun handleReceiverStatus(payloadJson: String) {
        val json = runCatching { JSONObject(payloadJson) }.getOrNull() ?: return
        if (json.optString("type") != "RECEIVER_STATUS") return
        val status = json.optJSONObject("status") ?: return

        status.optJSONObject("volume")?.let { volume ->
            val level = volume.optDouble("level", -1.0)
            if (level in 0.0..1.0) currentVolumePercent = (level * 100).toInt()
        }

        val apps = status.optJSONArray("applications") ?: return
        for (i in 0 until apps.length()) {
            val app = apps.optJSONObject(i) ?: continue
            if (app.optString("appId") != DEFAULT_MEDIA_RECEIVER_APP_ID) continue
            val newTransportId = app.optString("transportId").takeIf { it.isNotEmpty() } ?: continue
            if (newTransportId != transportId) {
                transportId = newTransportId
                sessionId = app.optString("sessionId").takeIf { it.isNotEmpty() }
                // A CASTV2 "virtual connection" has to be opened to this
                // specific transportId too, separately from the one to
                // receiver-0, before it will accept media commands.
                send(NS_CONNECTION, newTransportId, JSONObject().put("type", "CONNECT"))
            }
        }
    }

    private fun nextRequestId(): Int = ++requestIdCounter

    private fun send(namespace: String, destinationId: String, payload: JSONObject) {
        val out = output ?: return
        try {
            writerExecutor.execute {
                try {
                    writeFramedMessage(out, encodeCastMessage(namespace, SENDER_ID, destinationId, payload.toString()))
                } catch (exc: Exception) {
                    Log.w(TAG, "send failed on $namespace: $exc")
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Executor already shut down (disconnect() in progress) -
            // dropping a trailing command here is fine.
        }
    }
}
