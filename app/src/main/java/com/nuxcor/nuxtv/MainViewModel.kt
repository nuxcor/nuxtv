package com.nuxcor.nuxtv

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nuxcor.nuxtv.data.ContentBundle
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
import com.nuxcor.nuxtv.data.ScheduledRecording
import com.nuxcor.nuxtv.data.Series
import com.nuxcor.nuxtv.recording.ActiveRecording
import com.nuxcor.nuxtv.recording.Recording
import com.nuxcor.nuxtv.recording.RecordingManager
import com.nuxcor.nuxtv.recording.RecordingScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
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
    private val updateManager = com.nuxcor.nuxtv.data.UpdateManager(app, repo.http)

    private val _updateState =
        MutableStateFlow<com.nuxcor.nuxtv.data.UpdateManager.State>(
            com.nuxcor.nuxtv.data.UpdateManager.State.Idle
        )
    val updateState: StateFlow<com.nuxcor.nuxtv.data.UpdateManager.State> = _updateState

    /** null until the persisted sources have been read. */
    val sources: StateFlow<List<PlaylistSource>?> = repo.sources
        .map { it as List<PlaylistSource>? }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val activeSource: StateFlow<PlaylistSource?> = repo.activeSource
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val content: StateFlow<ContentState> = repo.content

    val engine: StateFlow<EngineChoice> = playerPrefs.engine
        .stateIn(viewModelScope, SharingStarted.Eagerly, EngineChoice.EXO)

    val favorites: StateFlow<Set<String>> = playerPrefs.favorites
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val hidden: StateFlow<Set<String>> = playerPrefs.hidden
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val epgOverrideUrl: StateFlow<String?> = playerPrefs.epgOverrideUrl
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val tmdbKey: StateFlow<String?> = playerPrefs.tmdbKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val resumePositions: StateFlow<Map<String, Long>> = playerPrefs.resumePositions
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val parentalPin: StateFlow<String?> = playerPrefs.parentalPin
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val mergeDuplicates: StateFlow<Boolean> = playerPrefs.mergeDuplicates
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setMergeDuplicates(enabled: Boolean) =
        viewModelScope.launch { playerPrefs.setMergeDuplicates(enabled) }

    /** Locked categories stay hidden until the PIN is entered this session. */
    var parentalUnlocked by mutableStateOf(false)
        private set

    fun tryUnlock(pin: String): Boolean {
        val ok = pin == parentalPin.value
        if (ok) parentalUnlocked = true
        return ok
    }

    private val adultPattern = Regex("""(?i)(xxx|adult|porn|18\+|erotic)""")

    fun isLockedCategory(name: String?): Boolean =
        parentalPin.value != null && !parentalUnlocked &&
            name != null && adultPattern.containsMatchIn(name)

    val schedules: StateFlow<List<ScheduledRecording>> = playerPrefs.schedules
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val epgState: StateFlow<ContentRepository.EpgState> = repo.epg

    val activeRecording: StateFlow<ActiveRecording?> = RecordingManager.active

    /** Now/next per channel id, recomputed once a minute off the main thread. */
    data class NowNext(val now: EpgProgram?, val next: EpgProgram?)

    private val minuteTicker = kotlinx.coroutines.flow.flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }

    val nowNext: StateFlow<Map<String, NowNext>> =
        kotlinx.coroutines.flow.combine(content, epgState, minuteTicker) { c, _, now ->
            val bundle = (c as? ContentState.Ready)?.bundle ?: return@combine emptyMap()
            bundle.channels.associate { channel ->
                val programs = repo.programsFor(channel)
                channel.id to NowNext(
                    now = programs.firstOrNull { now in it.startMs until it.endMs },
                    next = programs.firstOrNull { it.startMs >= now },
                )
            }
        }
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Channels after hidden/parental filtering and optional duplicate merging,
     * computed off the main thread instead of inside composition.
     */
    val displayChannels: StateFlow<List<LiveChannel>> =
        kotlinx.coroutines.flow.combine(
            content,
            playerPrefs.hidden,
            playerPrefs.mergeDuplicates,
            playerPrefs.parentalPin,
        ) { c, hiddenSet, merge, pin ->
            val bundle = (c as? ContentState.Ready)?.bundle ?: return@combine emptyList()
            val lockedIds = if (pin != null && !parentalUnlocked) {
                bundle.liveCategories.filter { isLockedCategory(it.name) }.map { it.id }.toSet()
            } else emptySet()
            val visible = bundle.channels
                .filterNot { it.url in hiddenSet }
                .filterNot { it.categoryId in lockedIds }
            if (merge) com.nuxcor.nuxtv.data.QualityTag.mergeBestQuality(visible) else visible
        }
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var playback by mutableStateOf<PlaybackRequest?>(null)
        private set

    var addState by mutableStateOf<AddState>(AddState.Idle)
        private set

    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    val recordings: StateFlow<List<Recording>> = _recordings

    init {
        viewModelScope.launch { repo.ensureLoaded() }
        refreshRecordings()
        // Reload the guide when a playlist loads or the EPG override changes,
        // fill in missing channel logos, and keep schedules' alarms registered.
        viewModelScope.launch {
            repo.content.collect {
                if (it is ContentState.Ready) {
                    repo.loadEpg(playerPrefs.epgOverrideUrl.first())
                    repo.enrichLogos()
                }
            }
        }
        viewModelScope.launch {
            playerPrefs.epgOverrideUrl.drop(1).collect { override ->
                if (content.value is ContentState.Ready) repo.loadEpg(override)
            }
        }
        // Auto-refresh the guide every 6 hours while the app is running.
        viewModelScope.launch {
            while (true) {
                delay(6L * 3600 * 1000)
                if (content.value is ContentState.Ready) repo.loadEpg(playerPrefs.epgOverrideUrl.first())
            }
        }
        viewModelScope.launch {
            RecordingScheduler.rescheduleAll(getApplication(), playerPrefs)
        }
        // Silent update check shortly after launch. Never stomps an
        // in-progress manual check/download.
        viewModelScope.launch {
            delay(8_000)
            val result = updateManager.check()
            if (result is com.nuxcor.nuxtv.data.UpdateManager.State.Available &&
                _updateState.value is com.nuxcor.nuxtv.data.UpdateManager.State.Idle
            ) {
                _updateState.value = result
            }
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            _updateState.value = com.nuxcor.nuxtv.data.UpdateManager.State.Checking
            _updateState.value = updateManager.check()
        }
    }

    fun downloadAndInstallUpdate() {
        when (val current = _updateState.value) {
            is com.nuxcor.nuxtv.data.UpdateManager.State.Downloading -> return // already running
            is com.nuxcor.nuxtv.data.UpdateManager.State.Ready -> {
                if (!updateManager.install(current.file)) {
                    _updateState.value = com.nuxcor.nuxtv.data.UpdateManager.State.Error(
                        "Couldn't start the installer — allow \"install unknown apps\" for Dzidzi, then check again"
                    )
                }
                return
            }
            else -> Unit
        }
        val available = _updateState.value as? com.nuxcor.nuxtv.data.UpdateManager.State.Available
            ?: return
        viewModelScope.launch {
            runCatching {
                _updateState.value = com.nuxcor.nuxtv.data.UpdateManager.State.Downloading(0)
                val file = updateManager.download(available.apkUrl) { pct ->
                    _updateState.value = com.nuxcor.nuxtv.data.UpdateManager.State.Downloading(pct)
                }
                _updateState.value =
                    com.nuxcor.nuxtv.data.UpdateManager.State.Ready(available.version, file)
                if (!updateManager.install(file)) {
                    _updateState.value = com.nuxcor.nuxtv.data.UpdateManager.State.Error(
                        "Couldn't start the installer — allow \"install unknown apps\" for Dzidzi, then check again"
                    )
                }
            }.onFailure { e ->
                _updateState.value =
                    com.nuxcor.nuxtv.data.UpdateManager.State.Error(e.message ?: "Download failed")
            }
        }
    }

    fun setEpgOverrideUrl(url: String?) = viewModelScope.launch { playerPrefs.setEpgOverrideUrl(url) }

    fun setTmdbKey(key: String?) = viewModelScope.launch { playerPrefs.setTmdbKey(key) }

    fun setParentalPin(pin: String?) = viewModelScope.launch { playerPrefs.setParentalPin(pin) }

    fun scheduleReminder(channel: LiveChannel, program: EpgProgram) {
        RecordingScheduler.scheduleReminder(getApplication(), channel.name, program)
    }

    fun toggleHidden(channel: LiveChannel) {
        viewModelScope.launch { playerPrefs.toggleHidden(channel.url) }
    }

    /** Channels with the hidden set removed and (optionally) duplicates merged. */
    fun visibleChannels(channels: List<LiveChannel>): List<LiveChannel> {
        val hiddenSet = hidden.value
        val visible =
            if (hiddenSet.isEmpty()) channels else channels.filterNot { it.url in hiddenSet }
        return if (mergeDuplicates.value) {
            com.nuxcor.nuxtv.data.QualityTag.mergeBestQuality(visible)
        } else visible
    }

    // --- backup / restore -----------------------------------------------------

    fun exportBackup(onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val path = runCatching {
                val text = playerPrefs.snapshot(sources.value.orEmpty())
                val file = java.io.File(
                    getApplication<Application>().getExternalFilesDir(null),
                    "dzidzi-backup.json",
                )
                file.writeText(text)
                file.absolutePath
            }.getOrNull()
            onDone(path)
        }
    }

    fun importBackup(onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching {
                val file = java.io.File(
                    getApplication<Application>().getExternalFilesDir(null),
                    "dzidzi-backup.json",
                )
                val restoredSources = playerPrefs.restore(file.readText())
                repo.restoreSources(restoredSources)
                RecordingScheduler.rescheduleAll(getApplication(), playerPrefs)
                true
            }.getOrDefault(false)
            onDone(ok)
        }
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

    fun addM3u(name: String, url: String, epgUrl: String, onSuccess: () -> Unit) {
        addSource(
            PlaylistSource.M3u(
                id = ContentRepository.newSourceId(),
                name = name.ifBlank { "M3U playlist" },
                url = url.trim(),
                epgUrl = epgUrl.trim().takeIf { it.isNotBlank() },
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

    /** id → channel index, rebuilt when the bundle changes (O(1) player lookups). */
    private var channelIndex: Pair<ContentBundle, Map<String, LiveChannel>>? = null

    fun movieById(id: String): Movie? = bundle?.movies?.firstOrNull { it.id == id }
    fun seriesById(id: String): Series? = bundle?.series?.firstOrNull { it.id == id }

    fun channelById(id: String): LiveChannel? {
        val b = bundle ?: return null
        val cached = channelIndex
        val index = if (cached != null && cached.first === b) {
            cached.second
        } else {
            b.channels.associateBy { it.id }.also { channelIndex = b to it }
        }
        return index[id]
    }

    suspend fun movieDetails(movie: Movie): Movie = repo.movieDetails(movie, tmdbKey.value)
    suspend fun seriesDetails(series: Series): Series = repo.seriesDetails(series, tmdbKey.value)
    suspend fun episodesFor(series: Series): List<Episode> = repo.episodesFor(series)
    suspend fun epgFor(channel: LiveChannel): List<EpgProgram> = repo.epgFor(channel)
    suspend fun catchupUrl(channel: LiveChannel, program: EpgProgram): String? =
        repo.catchupUrl(channel, program)

    fun programsFor(channel: LiveChannel): List<EpgProgram> = repo.programsFor(channel)

    fun toggleFavorite(channel: LiveChannel) {
        viewModelScope.launch { playerPrefs.toggleFavorite(channel.url) }
    }

    fun scheduleRecording(channel: LiveChannel, program: EpgProgram): Boolean {
        val recordUrl = channel.recordUrl ?: return false
        RecordingScheduler.schedule(
            getApplication(),
            playerPrefs,
            ScheduledRecording(
                id = "${channel.url}#${program.startMs}",
                channelName = channel.name,
                recordUrl = recordUrl,
                title = program.title,
                startMs = program.startMs,
                endMs = program.endMs,
            ),
        )
        return true
    }

    fun cancelSchedule(id: String) {
        RecordingScheduler.cancel(getApplication(), playerPrefs, id)
    }

    data class SearchResults(
        val channels: List<LiveChannel> = emptyList(),
        val movies: List<Movie> = emptyList(),
        val series: List<Series> = emptyList(),
    )

    fun search(query: String): SearchResults {
        val q = query.trim()
        if (q.length < 2) return SearchResults()
        val b = bundle ?: return SearchResults()
        val lockedLive = b.liveCategories.filter { isLockedCategory(it.name) }.map { it.id }.toSet()
        val lockedMovie = b.movieCategories.filter { isLockedCategory(it.name) }.map { it.id }.toSet()
        val lockedSeries = b.seriesCategories.filter { isLockedCategory(it.name) }.map { it.id }.toSet()
        return SearchResults(
            channels = b.channels
                .filter { it.categoryId !in lockedLive && it.name.contains(q, ignoreCase = true) }
                .take(30),
            movies = b.movies
                .filter { it.categoryId !in lockedMovie && it.name.contains(q, ignoreCase = true) }
                .take(30),
            series = b.series
                .filter { it.categoryId !in lockedSeries && it.name.contains(q, ignoreCase = true) }
                .take(30),
        )
    }

    // --- resume positions -----------------------------------------------------

    suspend fun resumePositionFor(url: String): Long = playerPrefs.resumePositionFor(url)

    fun saveResumePosition(url: String, positionMs: Long, durationMs: Long) {
        viewModelScope.launch { playerPrefs.saveResumePosition(url, positionMs, durationMs) }
    }

    // --- recordings -----------------------------------------------------------

    fun refreshRecordings() {
        // listFiles + length + lastModified is disk I/O — never on the main thread.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _recordings.value = RecordingManager.list(getApplication())
        }
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
