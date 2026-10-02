package de.tbrbd.onradiotv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.tbrbd.onradiotv.airplay.AirPlayDevice
import de.tbrbd.onradiotv.airplay.AirPlayRendererService
import de.tbrbd.onradiotv.cast.CastDevice
import de.tbrbd.onradiotv.cast.CastRendererService
import de.tbrbd.onradiotv.data.AppPreferences
import de.tbrbd.onradiotv.data.AudioStreamResolver
import de.tbrbd.onradiotv.data.CoverArtRepository
import de.tbrbd.onradiotv.data.NowPlayingRepository
import de.tbrbd.onradiotv.data.StationRepository
import de.tbrbd.onradiotv.data.UpnpRenderer
import de.tbrbd.onradiotv.data.UpnpRendererService
import de.tbrbd.onradiotv.data.WeatherRepository
import de.tbrbd.onradiotv.model.NowPlaying
import de.tbrbd.onradiotv.model.Station
import de.tbrbd.onradiotv.model.WeatherState
import de.tbrbd.onradiotv.player.RadioPlayer
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

data class TvUiState(
    val stations: List<Station> = emptyList(),
    val currentStationId: String? = null,
    val favoriteIds: Set<String> = emptySet(),
    val nowPlaying: NowPlaying? = null,
    val coverUrl: String? = null,
    val weather: WeatherState? = null,
    val weatherLocationName: String = "",
    // null = playing locally on this TV's own speaker/output.
    val upnpRenderers: List<UpnpRenderer> = emptyList(),
    val castDevices: List<CastDevice> = emptyList(),
    val airPlayDevices: List<AirPlayDevice> = emptyList(),
    val isDiscoveringUpnp: Boolean = false,
    // "upnp:<id>", "cast:<routeId>" or "airplay:<routeId>" - see the
    // CAST_PREFIX/AIRPLAY_PREFIX dispatch in activateOutput() below.
    val activeOutputRendererId: String? = null,
    val activeOutputVolume: Int? = null,
    val outputError: String? = null,
)

private const val METADATA_REFRESH_MS = 15_000L
private const val WEATHER_REFRESH_MS = 10 * 60_000L
private const val CAST_PREFIX = "cast:"
private const val AIRPLAY_PREFIX = "airplay:"

class TvViewModel(application: Application) : AndroidViewModel(application) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val stationRepository = StationRepository(application, client)
    private val audioStreamResolver = AudioStreamResolver(client)
    private val nowPlayingRepository = NowPlayingRepository(client, audioStreamResolver)
    private val coverArtRepository = CoverArtRepository(client)
    private val weatherRepository = WeatherRepository(client)
    private val upnpRendererService = UpnpRendererService(application, client)
    private val castRendererService = CastRendererService(application)
    private val airPlayRendererService = AirPlayRendererService(application)
    private val player = RadioPlayer(application)
    private val prefs = AppPreferences(application)

    private val _state = MutableStateFlow(TvUiState())
    val state: StateFlow<TvUiState> = _state.asStateFlow()

    private var metadataJob: Job? = null
    private var weatherJob: Job? = null

    init {
        // Bundled snapshot first, so the app works immediately and offline -
        // then try to upgrade to the live catalog from the radiostations repo in
        // the background (kept in sync with the source project by a
        // scheduled GitHub Action). If that fetch fails, the bundled
        // snapshot just keeps being used; this is a best-effort refresh,
        // never a requirement for the app to work.
        val stations = stationRepository.loadBundledStations()
        _state.update {
            it.copy(
                stations = stations,
                favoriteIds = prefs.favoriteIds(),
                weatherLocationName = prefs.weatherLocationName(),
            )
        }

        // Resume where the viewer left off last time, if that station still
        // exists in the catalog; otherwise just start from the top.
        val resumeId = prefs.lastPlayedStationId()?.let { id -> stations.find { it.id == id }?.id }
        (resumeId ?: stations.firstOrNull()?.id)?.let { selectStation(it) }

        refreshWeatherLoop()
        refreshStationCatalog()

        viewModelScope.launch {
            castRendererService.devices.collect { devices -> _state.update { it.copy(castDevices = devices) } }
        }
        viewModelScope.launch {
            castRendererService.isConnected.collect { connected -> onCastConnectionChanged(connected) }
        }
        viewModelScope.launch {
            airPlayRendererService.devices.collect { devices -> _state.update { it.copy(airPlayDevices = devices) } }
        }
        viewModelScope.launch {
            airPlayRendererService.isConnected.collect { connected -> onAirPlayConnectionChanged(connected) }
        }
    }

    /** Fires when RaopClient finishes its RTSP handshake (or disconnects) -
     * same async-connect pattern as onCastConnectionChanged() above. */
    private fun onAirPlayConnectionChanged(connected: Boolean) {
        val activeOutput = _state.value.activeOutputRendererId
        if (activeOutput == null || !activeOutput.startsWith(AIRPLAY_PREFIX)) return
        val station = _state.value.stations.find { it.id == _state.value.currentStationId }

        if (connected) {
            if (station == null) return
            viewModelScope.launch {
                val resolvedUrl = withContext(Dispatchers.IO) {
                    try {
                        audioStreamResolver.resolve(station)
                    } catch (_: Exception) {
                        station.audioUrl
                    }
                }
                airPlayRendererService.playStream(resolvedUrl)
                _state.update { it.copy(activeOutputVolume = airPlayRendererService.getVolumePercent(), outputError = null) }
            }
        } else {
            _state.update { it.copy(activeOutputRendererId = null, activeOutputVolume = null) }
            if (station != null) {
                viewModelScope.launch {
                    val resolvedUrl = withContext(Dispatchers.IO) {
                        try {
                            audioStreamResolver.resolve(station)
                        } catch (_: Exception) {
                            station.audioUrl
                        }
                    }
                    player.play(resolvedUrl)
                }
            }
        }
    }

    /** Fires when CastV2Client finishes connecting (or disconnects) -
     * selectOutput() only kicks off the connection for a Cast device, since
     * that's asynchronous; actually loading the stream once connected
     * happens here. */
    private fun onCastConnectionChanged(connected: Boolean) {
        val activeOutput = _state.value.activeOutputRendererId
        if (activeOutput == null || !activeOutput.startsWith(CAST_PREFIX)) return
        val station = _state.value.stations.find { it.id == _state.value.currentStationId }

        if (connected) {
            if (station == null) return
            viewModelScope.launch {
                val resolvedUrl = withContext(Dispatchers.IO) {
                    try {
                        audioStreamResolver.resolve(station)
                    } catch (_: Exception) {
                        station.audioUrl
                    }
                }
                castRendererService.playStream(resolvedUrl, title = station.name, artist = "")
                _state.update { it.copy(activeOutputVolume = castRendererService.getVolumePercent(), outputError = null) }
            }
        } else {
            // The session ended on its own (device turned off, network
            // hiccup, ...) - fall back to the TV's own speaker.
            _state.update { it.copy(activeOutputRendererId = null, activeOutputVolume = null) }
            if (station != null) {
                viewModelScope.launch {
                    val resolvedUrl = withContext(Dispatchers.IO) {
                        try {
                            audioStreamResolver.resolve(station)
                        } catch (_: Exception) {
                            station.audioUrl
                        }
                    }
                    player.play(resolvedUrl)
                }
            }
        }
    }

    private fun refreshStationCatalog() {
        viewModelScope.launch {
            val remote = withContext(Dispatchers.IO) { stationRepository.fetchRemoteStations() }
            if (remote != null) {
                _state.update { it.copy(stations = remote) }
            }
        }
    }

    fun selectStation(stationId: String) {
        val station = _state.value.stations.find { it.id == stationId } ?: return
        prefs.setLastStationForGroup(station.group, stationId)
        prefs.setLastPlayedStationId(stationId)
        _state.update {
            it.copy(currentStationId = stationId, nowPlaying = null, coverUrl = null)
        }

        viewModelScope.launch {
            // Resolve (network I/O for pls/m3u stations) on IO, but ExoPlayer
            // itself must only ever be touched from the thread that created
            // it (the main thread here) - so play() happens after switching
            // back.
            val resolvedUrl = withContext(Dispatchers.IO) {
                try {
                    audioStreamResolver.resolve(station)
                } catch (_: Exception) {
                    station.audioUrl
                }
            }
            activateOutput(_state.value.activeOutputRendererId, station, resolvedUrl)
        }

        startMetadataLoop(station)
    }

    /** Starts playback of resolvedUrl on whichever output is currently
     * selected - this TV's own speaker, a UPnP renderer on the LAN (Sonos,
     * Denon, ...), or a Google Cast device. Called both when switching
     * stations and when the viewer switches outputs for the station already
     * playing. */
    private suspend fun activateOutput(rendererId: String?, station: Station, resolvedUrl: String) {
        when {
            rendererId == null -> player.play(resolvedUrl)
            rendererId.startsWith(CAST_PREFIX) -> activateCastOutput(rendererId.removePrefix(CAST_PREFIX), station, resolvedUrl)
            rendererId.startsWith(AIRPLAY_PREFIX) -> activateAirPlayOutput(rendererId.removePrefix(AIRPLAY_PREFIX), resolvedUrl)
            else -> activateUpnpOutput(rendererId, station, resolvedUrl)
        }
    }

    private fun activateAirPlayOutput(routeId: String, resolvedUrl: String) {
        player.stop()
        if (airPlayRendererService.isConnected.value) {
            airPlayRendererService.playStream(resolvedUrl)
            _state.update { it.copy(activeOutputVolume = airPlayRendererService.getVolumePercent(), outputError = null) }
        } else {
            // Not connected yet - onAirPlayConnectionChanged() plays once
            // RaopClient reports the RTSP handshake is done.
            airPlayRendererService.selectDevice(routeId)
        }
    }

    private fun activateCastOutput(routeId: String, station: Station, resolvedUrl: String) {
        player.stop()
        if (castRendererService.isConnected.value) {
            castRendererService.playStream(resolvedUrl, title = station.name, artist = "")
            _state.update { it.copy(activeOutputVolume = castRendererService.getVolumePercent(), outputError = null) }
        } else {
            // Not connected yet - onCastConnectionChanged() plays once
            // CastV2Client reports the connection is up.
            castRendererService.selectDevice(routeId)
        }
    }

    private suspend fun activateUpnpOutput(rendererId: String, station: Station, resolvedUrl: String) {
        player.stop()
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val renderer = upnpRendererService.getRenderer(rendererId)
                    ?: throw RuntimeException("WLAN-Lautsprecher nicht gefunden")
                upnpRendererService.playStream(renderer, resolvedUrl, stationName = station.name)
                upnpRendererService.getVolume(renderer)
            }
        }
        outcome.fold(
            onSuccess = { volume -> _state.update { it.copy(activeOutputVolume = volume, outputError = null) } },
            onFailure = { error ->
                // Fall back to the TV's own speaker rather than going silent.
                player.play(resolvedUrl)
                _state.update {
                    it.copy(activeOutputRendererId = null, activeOutputVolume = null, outputError = error.message)
                }
            },
        )
    }

    fun refreshUpnpRenderers() {
        castRendererService.startDiscovery()
        airPlayRendererService.startDiscovery()

        viewModelScope.launch {
            _state.update { it.copy(isDiscoveringUpnp = true) }
            val renderers = withContext(Dispatchers.IO) {
                try {
                    upnpRendererService.listRenderers(forceRefresh = true, timeoutSeconds = 4)
                } catch (_: Exception) {
                    emptyList()
                }
            }
            _state.update { it.copy(upnpRenderers = renderers, isDiscoveringUpnp = false) }
        }
    }

    /** rendererId null switches back to this TV's own speaker; otherwise
     * "upnp:<id>" or "cast:<routeId>" (see CAST_PREFIX). */
    fun selectOutput(rendererId: String?) {
        val previousRendererId = _state.value.activeOutputRendererId
        val currentStation = _state.value.stations.find { it.id == _state.value.currentStationId }
        _state.update { it.copy(activeOutputRendererId = rendererId, activeOutputVolume = null, outputError = null) }

        // Stop the TV's own speaker right here, synchronously, before any of
        // the network round-trips below - those (resolving the stream URL,
        // reaching the renderer) can take a second or more, and leaving the
        // old audio running until they finish meant both outputs were
        // audible at once for that whole stretch.
        if (rendererId != null) {
            player.stop()
        }

        if (previousRendererId != null && previousRendererId != rendererId) {
            stopOutput(previousRendererId)
        }

        val station = currentStation ?: return
        viewModelScope.launch {
            val resolvedUrl = withContext(Dispatchers.IO) {
                try {
                    audioStreamResolver.resolve(station)
                } catch (_: Exception) {
                    station.audioUrl
                }
            }
            activateOutput(rendererId, station, resolvedUrl)
        }
    }

    private fun stopOutput(rendererId: String) {
        when {
            rendererId.startsWith(CAST_PREFIX) -> castRendererService.stop()
            rendererId.startsWith(AIRPLAY_PREFIX) -> airPlayRendererService.stop()
            else -> viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    runCatching { upnpRendererService.getRenderer(rendererId)?.let { upnpRendererService.stop(it) } }
                }
            }
        }
    }

    fun adjustActiveOutputVolume(deltaPercent: Int) {
        val rendererId = _state.value.activeOutputRendererId ?: return
        if (rendererId.startsWith(CAST_PREFIX)) {
            val current = castRendererService.getVolumePercent() ?: 50
            castRendererService.setVolumePercent(current + deltaPercent)
            _state.update { it.copy(activeOutputVolume = castRendererService.getVolumePercent()) }
            return
        }
        if (rendererId.startsWith(AIRPLAY_PREFIX)) {
            airPlayRendererService.setVolumePercent(airPlayRendererService.getVolumePercent() + deltaPercent)
            _state.update { it.copy(activeOutputVolume = airPlayRendererService.getVolumePercent()) }
            return
        }
        viewModelScope.launch {
            val newVolume = withContext(Dispatchers.IO) {
                runCatching {
                    val renderer = upnpRendererService.getRenderer(rendererId) ?: return@runCatching null
                    val current = upnpRendererService.getVolume(renderer) ?: 50
                    upnpRendererService.setVolume(renderer, current + deltaPercent)
                }.getOrNull()
            }
            if (newVolume != null) {
                _state.update { it.copy(activeOutputVolume = newVolume) }
            }
        }
    }

    fun toggleFavorite(stationId: String) {
        val updated = prefs.toggleFavorite(stationId)
        _state.update { it.copy(favoriteIds = updated) }
    }

    fun lastStationForGroup(group: String): String? = prefs.lastStationForGroup(group)

    fun setWeatherLocationName(name: String) {
        prefs.setWeatherLocationName(name)
        _state.update { it.copy(weatherLocationName = prefs.weatherLocationName(), weather = null) }
        refreshWeatherLoop()
    }

    private fun startMetadataLoop(station: Station) {
        metadataJob?.cancel()
        metadataJob = viewModelScope.launch {
            while (isActive) {
                val nowPlaying = withContext(Dispatchers.IO) { nowPlayingRepository.fetch(station) }
                _state.update { if (it.currentStationId == station.id) it.copy(nowPlaying = nowPlaying) else it }

                val cover = nowPlaying.providerCoverUrl ?: withContext(Dispatchers.IO) {
                    coverArtRepository.findCoverUrl(nowPlaying.artist, nowPlaying.title)
                }
                _state.update { if (it.currentStationId == station.id) it.copy(coverUrl = cover) else it }

                delay(METADATA_REFRESH_MS)
            }
        }
    }

    private fun refreshWeatherLoop() {
        weatherJob?.cancel()
        weatherJob = viewModelScope.launch {
            while (isActive) {
                val locationName = _state.value.weatherLocationName
                val weather = withContext(Dispatchers.IO) { weatherRepository.fetchWeather(locationName) }
                _state.update { it.copy(weather = weather) }
                delay(WEATHER_REFRESH_MS)
            }
        }
    }

    /** Called when the screen turns off (TV standby/power-off) - stops
     * audio instead of leaving it playing unattended in the background,
     * whether that's the TV's own speaker or a WLAN-Lautsprecher output. */
    fun stopPlayback() {
        player.stop()
        _state.value.activeOutputRendererId?.let { stopOutput(it) }
    }

    override fun onCleared() {
        player.release()
        castRendererService.stopDiscovery()
        airPlayRendererService.stopDiscovery()
        super.onCleared()
    }
}
