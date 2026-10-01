package de.tbrbd.onradiotv.cast

import android.content.Context
import android.util.Log
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "CastRenderer"

data class CastDevice(val routeId: String, val name: String)

/** Google Cast counterpart to UpnpRendererService: same job (discover
 * renderers on the LAN, play/stop a stream, control volume), but almost
 * everything here is the official Cast SDK doing the actual mDNS discovery
 * and Cast v2 protocol work - androidx.mediarouter.MediaRouter surfaces the
 * discovered devices, and CastContext's SessionManager handles connecting
 * and loading media, unlike UpnpRendererService's hand-rolled SSDP/SOAP
 * client for UPnP renderers. */
class CastRendererService(context: Context) {
    private val appContext = context.applicationContext
    private val mediaRouter = MediaRouter.getInstance(appContext)
    private val castContext: CastContext? = try {
        CastContext.getSharedInstance(appContext)
    } catch (exc: Exception) {
        // Missing/outdated Google Play services, or no Cast receiver ever
        // configured on this build - Cast output just won't be offered.
        Log.w(TAG, "CastContext unavailable: $exc")
        null
    }

    private val routeSelector = MediaRouteSelector.Builder()
        .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
        .build()

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()

    private val _activeSession = MutableStateFlow<CastSession?>(null)
    val activeSession: StateFlow<CastSession?> = _activeSession.asStateFlow()

    private var discovering = false

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshDevices()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshDevices()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshDevices()
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            _activeSession.value = session
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            _activeSession.value = session
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            if (_activeSession.value === session) _activeSession.value = null
        }

        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Log.w(TAG, "Session start failed: error=$error")
        }
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit
        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
    }

    init {
        castContext?.sessionManager?.addSessionManagerListener(sessionListener, CastSession::class.java)
        castContext?.sessionManager?.currentCastSession?.let { _activeSession.value = it }
    }

    fun startDiscovery() {
        if (castContext == null || discovering) return
        discovering = true
        mediaRouter.addCallback(routeSelector, routerCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        refreshDevices()
    }

    fun stopDiscovery() {
        if (!discovering) return
        discovering = false
        mediaRouter.removeCallback(routerCallback)
    }

    private fun refreshDevices() {
        _devices.value = mediaRouter.routes
            .filter { !it.isDefault && it.matchesSelector(routeSelector) }
            .map { CastDevice(routeId = it.id, name = it.name) }
    }

    /** Selecting the route hands control to the Cast SDK, which connects and
     * fires the SessionManagerListener callbacks above asynchronously -
     * activeSession reflects the result, there's nothing to await here. */
    fun selectDevice(routeId: String) {
        val route = mediaRouter.routes.find { it.id == routeId } ?: return
        mediaRouter.selectRoute(route)
    }

    fun playStream(streamUrl: String, title: String, artist: String) {
        val remoteMediaClient = _activeSession.value?.remoteMediaClient ?: return
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, title)
            putString(MediaMetadata.KEY_ARTIST, artist)
        }
        val mediaInfo = MediaInfo.Builder(streamUrl)
            // BUFFERED (not LIVE) trades a little latency for a bigger
            // buffer, which rides out WiFi jitter much better - matches
            // app/cast_renderer.py's default on the Pi for the same reason.
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType("audio/mpeg")
            .setMetadata(metadata)
            .build()
        val request = MediaLoadRequestData.Builder().setMediaInfo(mediaInfo).setAutoplay(true).build()
        remoteMediaClient.load(request)
    }

    fun stop() {
        _activeSession.value?.remoteMediaClient?.stop()
        castContext?.sessionManager?.endCurrentSession(true)
    }

    fun getVolumePercent(): Int? = _activeSession.value?.volume?.let { (it * 100).toInt() }

    fun setVolumePercent(percent: Int) {
        _activeSession.value?.volume = percent.coerceIn(0, 100) / 100.0
    }
}
