package de.tbrbd.onradiotv.cast

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "CastRenderer"
private const val CONNECT_TIMEOUT_MS = 8_000L

data class CastDevice(val routeId: String, val name: String, val host: String, val port: Int)

/** Google Cast output, backed by CastV2Client (a from-scratch protocol
 * implementation) and CastDiscoveryManager (plain NsdManager/mDNS) rather
 * than the official Cast SDK - see CastV2Client's doc comment for why.
 * Mirrors UpnpRendererService's shape (discover/select/play/stop/volume) so
 * TvViewModel can treat both outputs uniformly. */
class CastRendererService(context: Context) {
    private val discovery = CastDiscoveryManager(context)
    val devices: StateFlow<List<CastDevice>> = discovery.devices

    private var client: CastV2Client? = null
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    fun startDiscovery() = discovery.start()
    fun stopDiscovery() = discovery.stop()

    /** Connects on a background thread (blocking socket I/O) and reports
     * success/failure via isConnected once the receiver app's transportId
     * is known - there's no synchronous "connected" return here, same as
     * the official SDK's session callbacks were async. */
    fun selectDevice(routeId: String) {
        val target = devices.value.find { it.routeId == routeId } ?: return
        client?.disconnect()
        _isConnected.value = false

        val newClient = CastV2Client(target.host, target.port)
        newClient.onDisconnected = {
            if (client === newClient) {
                client = null
                _isConnected.value = false
            }
        }
        client = newClient

        Thread(
            {
                try {
                    newClient.connect()
                    newClient.launchMediaReceiver()
                    val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
                    while (newClient.transportId == null && System.currentTimeMillis() < deadline) {
                        Thread.sleep(100)
                    }
                    if (client === newClient) {
                        _isConnected.value = newClient.transportId != null
                    }
                } catch (exc: Exception) {
                    Log.w(TAG, "Connecting to ${target.name} failed: $exc")
                    if (client === newClient) {
                        client = null
                        _isConnected.value = false
                    }
                }
            },
            "CastV2Connect",
        ).start()
    }

    fun playStream(streamUrl: String, title: String, artist: String) {
        client?.loadMedia(streamUrl, title = title, artist = artist)
    }

    /** Halts media but deliberately leaves the CASTV2 connection itself
     * open and isConnected unchanged (mirrors app/cast_renderer.py's
     * stop(), which also only calls media_controller.stop() without
     * disconnecting) - immediately tearing the connection down right after
     * sending STOP raced the receiver actually processing it before the
     * socket closed, so playback often kept going anyway. Staying connected
     * also means switching back to this device later is instant, with
     * activateCastOutput() reusing it directly instead of reconnecting. */
    fun stop() {
        client?.stopMedia()
    }

    fun getVolumePercent(): Int? = client?.currentVolumePercent

    fun setVolumePercent(percent: Int) {
        client?.setVolume(percent)
    }
}
