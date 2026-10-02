package de.tbrbd.onradiotv.airplay

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "AirPlayRenderer"

/** AirPlay (RAOP) output, mirroring UpnpRendererService/CastRendererService's
 * shape (discover/select/play/stop/volume) - backed by RaopClient (a
 * from-scratch RTSP+RTP implementation) and AirPlayDiscoveryManager (plain
 * NsdManager/mDNS). See RaopClient's doc comment for the first-cut
 * simplifications (no encryption, PCM not ALAC, no timing exchange) most
 * likely to need revisiting once this meets real hardware's quirks. */
class AirPlayRendererService(context: Context) {
    private val discovery = AirPlayDiscoveryManager(context)
    val devices: StateFlow<List<AirPlayDevice>> = discovery.devices

    private var client: RaopClient? = null
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    fun startDiscovery() = discovery.start()
    fun stopDiscovery() = discovery.stop()

    /** Connects (RTSP handshake) on a background thread - there's no
     * synchronous "connected" return, same as the Cast output's async
     * connect flow; isConnected reports the outcome once known. */
    fun selectDevice(routeId: String) {
        val target = devices.value.find { it.routeId == routeId } ?: return
        client?.disconnect()
        _isConnected.value = false
        // RaopClient.connect() always (re-)applies this safe volume to the
        // device itself on every new connection - keep our own tracked
        // value in sync so it doesn't drift from whatever was left over
        // from a previous, possibly-adjusted session.
        volumePercent = INITIAL_SAFE_VOLUME_PERCENT

        val newClient = RaopClient(target.host, target.port)
        client = newClient

        Thread(
            {
                try {
                    newClient.connect()
                    if (client === newClient) _isConnected.value = true
                } catch (exc: Exception) {
                    Log.w(TAG, "Connecting to ${target.name} failed: $exc")
                    if (client === newClient) {
                        client = null
                        _isConnected.value = false
                    }
                }
            },
            "RaopConnect",
        ).start()
    }

    fun playStream(streamUrl: String) {
        client?.startStreaming(streamUrl)
    }

    /** Stops streaming but leaves the RTSP session connected (same reasoning
     * as CastRendererService.stop(): switching back to this device shortly
     * after should be instant, no new handshake needed). */
    fun stop() {
        client?.stopStreaming()
    }

    // RAOP's SET_PARAMETER for volume is fire-and-forget in this minimal
    // client - there's no readback, unlike Cast's RECEIVER_STATUS - so this
    // just tracks what we last told the device, optimistically.
    @Volatile private var volumePercent: Int = INITIAL_SAFE_VOLUME_PERCENT

    fun getVolumePercent(): Int = volumePercent

    fun setVolumePercent(percent: Int) {
        volumePercent = percent.coerceIn(0, 100)
        client?.setVolume(volumePercent)
    }
}
