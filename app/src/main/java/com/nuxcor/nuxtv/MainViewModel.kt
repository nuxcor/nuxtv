package com.nuxcor.nuxtv

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nuxcor.nuxtv.data.ContentRepository
import com.nuxcor.nuxtv.data.ContentState
import com.nuxcor.nuxtv.data.EngineChoice
import com.nuxcor.nuxtv.data.EpgProgram
import com.nuxcor.nuxtv.data.Episode
import com.nuxcor.nuxtv.data.LiveChannel
import com.nuxcor.nuxtv.data.Movie
import com.nuxcor.nuxtv.data.PlayableItem
import com.nuxcor.nuxtv.data.PlaybackRequest
import com.nuxcor.nuxtv.data.PlayerPrefs
import com.nuxcor.nuxtv.data.PlaylistSource
import com.nuxcor.nuxtv.data.Series
import com.nuxcor.nuxtv.recording.ActiveRecording
import com.nuxcor.nuxtv.recording.Recording
import com.nuxcor.nuxtv.recording.RecordingManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class AddState {
    data object Idle : AddState()
    data object Loading : AddState()
    data class Error(val message: String) : AddState()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: ContentRepository = (app as NuxTvApp).repository
    private val playerPrefs = PlayerPrefs(app)

    /** null until the persisted sources have been read. */
    val sources: StateFlow<List<PlaylistSource>?> = repo.sources
        .map { it as List<PlaylistSource>? }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val activeSource: StateFlow<PlaylistSource?> = repo.activeSource
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val content: StateFlow<ContentState> = repo.content

    val engine: StateFlow<EngineChoice> = playerPrefs.engine
        .stateIn(viewModelScope, SharingStarted.Eagerly, EngineChoice.EXO)

    val activeRecording: StateFlow<ActiveRecording?> = RecordingManager.active

    var playback by mutableStateOf<PlaybackRequest?>(null)
        private set

    var addState by mutableStateOf<AddState>(AddState.Idle)
        private set

    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    val recordings: StateFlow<List<Recording>> = _recordings

    init {
        viewModelScope.launch { repo.ensureLoaded() }
        refreshRecordings()
    }

    fun resetAddState() {
        addState = AddState.Idle
    }

    fun addXtream(name: String, server: String, username: String, password: String, onSuccess: () -> Unit) {
        addSource(
            PlaylistSource.Xtream(
                id = ContentRepository.newSourceId(),
                name = name.ifBlank { "Xtream playlist" },
                serverUrl = server.trim(),
                username = username.trim(),
                password = password.trim(),
            ),
            onSuccess,
        )
    }

    fun addM3u(name: String, url: String, onSuccess: () -> Unit) {
        addSource(
            PlaylistSource.M3u(
                id = ContentRepository.newSourceId(),
                name = name.ifBlank { "M3U playlist" },
                url = url.trim(),
            ),
            onSuccess,
        )
    }

    private fun addSource(source: PlaylistSource, onSuccess: () -> Unit) {
        addState = AddState.Loading
        viewModelScope.launch {
            repo.validateAndAdd(source).fold(
                onSuccess = {
                    addState = AddState.Idle
                    onSuccess()
                },
                onFailure = { e ->
                    addState = AddState.Error(e.message ?: "Could not load the playlist")
                },
            )
        }
    }

    fun refresh() = viewModelScope.launch { repo.refresh() }

    fun selectSource(id: String) = viewModelScope.launch { repo.selectSource(id) }

    fun removeSource(id: String) = viewModelScope.launch { repo.removeSource(id) }

    fun setEngine(choice: EngineChoice) = viewModelScope.launch { playerPrefs.setEngine(choice) }

    // --- lookups --------------------------------------------------------------

    private val bundle get() = (content.value as? ContentState.Ready)?.bundle

    fun movieById(id: String): Movie? = bundle?.movies?.firstOrNull { it.id == id }
    fun seriesById(id: String): Series? = bundle?.series?.firstOrNull { it.id == id }
    fun channelById(id: String): LiveChannel? = bundle?.channels?.firstOrNull { it.id == id }

    suspend fun movieDetails(movie: Movie): Movie = repo.movieDetails(movie)
    suspend fun episodesFor(series: Series): List<Episode> = repo.episodesFor(series)
    suspend fun epgFor(channel: LiveChannel): List<EpgProgram> = repo.epgFor(channel)
    suspend fun catchupUrl(channel: LiveChannel, program: EpgProgram): String? =
        repo.catchupUrl(channel, program)

    data class SearchResults(
        val channels: List<LiveChannel> = emptyList(),
        val movies: List<Movie> = emptyList(),
        val series: List<Series> = emptyList(),
    )

    fun search(query: String): SearchResults {
        val q = query.trim()
        if (q.length < 2) return SearchResults()
        val b = bundle ?: return SearchResults()
        return SearchResults(
            channels = b.channels.filter { it.name.contains(q, ignoreCase = true) }.take(30),
            movies = b.movies.filter { it.name.contains(q, ignoreCase = true) }.take(30),
            series = b.series.filter { it.name.contains(q, ignoreCase = true) }.take(30),
        )
    }

    // --- resume positions -----------------------------------------------------

    suspend fun resumePositionFor(url: String): Long = playerPrefs.resumePositionFor(url)

    fun saveResumePosition(url: String, positionMs: Long, durationMs: Long) {
        viewModelScope.launch { playerPrefs.saveResumePosition(url, positionMs, durationMs) }
    }

    // --- recordings -----------------------------------------------------------

    fun refreshRecordings() {
        _recordings.value = RecordingManager.list(getApplication())
    }

    fun startRecording(item: PlayableItem) {
        val url = item.recordUrl ?: return
        RecordingManager.start(getApplication(), url, item.title)
    }

    fun stopRecording() {
        RecordingManager.stop(getApplication())
        refreshRecordings()
    }

    fun deleteRecording(recording: Recording) {
        RecordingManager.delete(recording)
        refreshRecordings()
    }

    fun playRecording(recording: Recording) {
        playback = PlaybackRequest(
            items = listOf(
                PlayableItem(
                    url = "file://${recording.file.absolutePath}",
                    title = recording.name,
                    subtitle = "Recording",
                )
            ),
            startIndex = 0,
            isLive = false,
        )
    }

    // --- playback -------------------------------------------------------------

    fun playChannels(channels: List<LiveChannel>, startIndex: Int) {
        playback = PlaybackRequest(
            items = channels.map {
                PlayableItem(
                    url = it.url,
                    title = it.name,
                    subtitle = "Live",
                    artwork = it.logo,
                    channelId = it.id,
                    recordUrl = it.recordUrl,
                )
            },
            startIndex = startIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0)),
            isLive = true,
        )
    }

    fun playMovie(movie: Movie) {
        playback = PlaybackRequest(
            items = listOf(
                PlayableItem(
                    url = movie.url,
                    title = movie.name,
                    subtitle = movie.year?.toString(),
                    artwork = movie.poster,
                )
            ),
            startIndex = 0,
            isLive = false,
        )
    }

    fun playEpisodes(series: Series, episodes: List<Episode>, startIndex: Int) {
        playback = PlaybackRequest(
            items = episodes.map {
                PlayableItem(
                    url = it.url,
                    title = series.name,
                    subtitle = "S${it.season} E${it.episodeNum} • ${it.title}",
                    artwork = it.poster ?: series.poster,
                )
            },
            startIndex = startIndex.coerceIn(0, (episodes.size - 1).coerceAtLeast(0)),
            isLive = false,
        )
    }

    fun playCatchup(channel: LiveChannel, program: EpgProgram, url: String) {
        playback = PlaybackRequest(
            items = listOf(
                PlayableItem(
                    url = url,
                    title = program.title,
                    subtitle = "Catch-up • ${channel.name}",
                    artwork = channel.logo,
                    channelId = channel.id,
                )
            ),
            startIndex = 0,
            isLive = false,
            isCatchup = true,
        )
    }

    fun clearPlayback() {
        playback = null
    }
}
