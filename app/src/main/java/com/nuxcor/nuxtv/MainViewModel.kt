package com.nuxcor.nuxtv

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nuxcor.nuxtv.data.ContentRepository
import com.nuxcor.nuxtv.data.ContentState
import com.nuxcor.nuxtv.data.Episode
import com.nuxcor.nuxtv.data.LiveChannel
import com.nuxcor.nuxtv.data.Movie
import com.nuxcor.nuxtv.data.PlayableItem
import com.nuxcor.nuxtv.data.PlaybackRequest
import com.nuxcor.nuxtv.data.PlaylistSource
import com.nuxcor.nuxtv.data.Series
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

    /** null until the persisted sources have been read. */
    val sources: StateFlow<List<PlaylistSource>?> = repo.sources
        .map { it as List<PlaylistSource>? }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val activeSource: StateFlow<PlaylistSource?> = repo.activeSource
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val content: StateFlow<ContentState> = repo.content

    var playback by mutableStateOf<PlaybackRequest?>(null)
        private set

    var addState by mutableStateOf<AddState>(AddState.Idle)
        private set

    init {
        viewModelScope.launch { repo.ensureLoaded() }
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

    // --- lookups --------------------------------------------------------------

    private val bundle get() = (content.value as? ContentState.Ready)?.bundle

    fun movieById(id: String): Movie? = bundle?.movies?.firstOrNull { it.id == id }
    fun seriesById(id: String): Series? = bundle?.series?.firstOrNull { it.id == id }

    suspend fun movieDetails(movie: Movie): Movie = repo.movieDetails(movie)
    suspend fun episodesFor(series: Series): List<Episode> = repo.episodesFor(series)

    // --- playback -------------------------------------------------------------

    fun playChannels(channels: List<LiveChannel>, startIndex: Int) {
        playback = PlaybackRequest(
            items = channels.map {
                PlayableItem(url = it.url, title = it.name, subtitle = "Live", artwork = it.logo)
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

    fun clearPlayback() {
        playback = null
    }
}
