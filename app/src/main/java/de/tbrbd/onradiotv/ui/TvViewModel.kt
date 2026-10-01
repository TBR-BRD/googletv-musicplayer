package de.tbrbd.onradiotv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.tbrbd.onradiotv.data.AppPreferences
import de.tbrbd.onradiotv.data.AudioStreamResolver
import de.tbrbd.onradiotv.data.CoverArtRepository
import de.tbrbd.onradiotv.data.NowPlayingRepository
import de.tbrbd.onradiotv.data.StationRepository
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
)

private const val METADATA_REFRESH_MS = 15_000L
private const val WEATHER_REFRESH_MS = 10 * 60_000L

class TvViewModel(application: Application) : AndroidViewModel(application) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val stationRepository = StationRepository(application)
    private val audioStreamResolver = AudioStreamResolver(client)
    private val nowPlayingRepository = NowPlayingRepository(client, audioStreamResolver)
    private val coverArtRepository = CoverArtRepository(client)
    private val weatherRepository = WeatherRepository(client)
    private val player = RadioPlayer(application)
    private val prefs = AppPreferences(application)

    private val _state = MutableStateFlow(TvUiState())
    val state: StateFlow<TvUiState> = _state.asStateFlow()

    private var metadataJob: Job? = null

    init {
        val stations = stationRepository.loadStations()
        _state.update { it.copy(stations = stations, favoriteIds = prefs.favoriteIds()) }

        // Resume where the viewer left off last time, if that station still
        // exists in the catalog; otherwise just start from the top.
        val resumeId = prefs.lastPlayedStationId()?.let { id -> stations.find { it.id == id }?.id }
        (resumeId ?: stations.firstOrNull()?.id)?.let { selectStation(it) }

        refreshWeatherLoop()
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
            player.play(resolvedUrl)
        }

        startMetadataLoop(station)
    }

    fun toggleFavorite(stationId: String) {
        val updated = prefs.toggleFavorite(stationId)
        _state.update { it.copy(favoriteIds = updated) }
    }

    fun lastStationForGroup(group: String): String? = prefs.lastStationForGroup(group)

    private fun startMetadataLoop(station: Station) {
        metadataJob?.cancel()
        metadataJob = viewModelScope.launch {
            while (isActive) {
                val nowPlaying = withContext(Dispatchers.IO) { nowPlayingRepository.fetch(station) }
                _state.update { if (it.currentStationId == station.id) it.copy(nowPlaying = nowPlaying) else it }

                val cover = withContext(Dispatchers.IO) {
                    coverArtRepository.findCoverUrl(nowPlaying.artist, nowPlaying.title)
                }
                _state.update { if (it.currentStationId == station.id) it.copy(coverUrl = cover) else it }

                delay(METADATA_REFRESH_MS)
            }
        }
    }

    private fun refreshWeatherLoop() {
        viewModelScope.launch {
            while (isActive) {
                val weather = withContext(Dispatchers.IO) { weatherRepository.fetchWeather() }
                _state.update { it.copy(weather = weather) }
                delay(WEATHER_REFRESH_MS)
            }
        }
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}
