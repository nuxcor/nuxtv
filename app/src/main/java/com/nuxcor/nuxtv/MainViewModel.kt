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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class AddState {
    data object Idle : AddState()
    data object Loading : AddState()
    data class Error(val message: String) : AddState()
}

private const val BACKUP_FILE = "agoro-backup.json"
private const val LEGACY_BACKUP_FILE = "dzidzi-backup.json"

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

    val guidePreview: StateFlow<Boolean> = playerPrefs.guidePreview
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Stream URLs of recently watched live channels, newest first. */
    val recentChannels: StateFlow<List<String>> = playerPrefs.recentChannels
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val epgOverrideUrl: StateFlow<String?> = playerPrefs.epgOverrideUrl
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val resumePositions: StateFlow<Map<String, Long>> = playerPrefs.resumePositions
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * url → how far through it the viewer is, 0..1. Only contains entries whose
     * duration is known, so a Continue Watching card either shows a true
     * progress bar or none at all — never an invented one.
     */
    val resumeProgress: StateFlow<Map<String, Float>> =
        kotlinx.coroutines.flow.combine(
            playerPrefs.resumePositions,
            playerPrefs.resumeDurations,
        ) { positions, durations ->
            positions.mapNotNull { (url, position) ->
                val duration = durations[url] ?: return@mapNotNull null
                if (duration <= 0) null else url to (position.toFloat() / duration).coerceIn(0f, 1f)
            }.toMap()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val parentalPin: StateFlow<String?> = playerPrefs.parentalPin
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val mergeDuplicates: StateFlow<Boolean> = playerPrefs.mergeDuplicates
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setMergeDuplicates(enabled: Boolean) =
        viewModelScope.launch { playerPrefs.setMergeDuplicates(enabled) }

    /** 0 = provider order, 1 = A–Z, 2 = quality first. */
    val channelOrder: StateFlow<Int> = playerPrefs.channelOrder
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    fun setChannelOrder(mode: Int) = viewModelScope.launch { playerPrefs.setChannelOrder(mode) }

    val videoQuality: StateFlow<Int> = playerPrefs.videoQuality
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1)

    fun setVideoQuality(mode: Int) = viewModelScope.launch { playerPrefs.setVideoQuality(mode) }

    /**
     * Locked categories stay hidden until the PIN is entered this session.
     *
     * A StateFlow, not Compose state: displayChannels reads this inside a Flow
     * combine, and a combine only re-runs when one of its sources emits.
     * Snapshot state is invisible to it, so entering the correct PIN made the
     * locked category selectable — the UI reads recomposed — while the channel
     * list it opened onto stayed filtered until the playlist reloaded.
     */
    private val _parentalUnlocked = MutableStateFlow(false)
    val parentalUnlocked: StateFlow<Boolean> = _parentalUnlocked

    fun tryUnlock(pin: String): Boolean {
        val ok = pin == parentalPin.value
        if (ok) _parentalUnlocked.value = true
        return ok
    }

    private val adultPattern = Regex("""(?i)(xxx|adult|porn|18\+|erotic)""")

    fun isLockedCategory(name: String?): Boolean =
        parentalPin.value != null && !_parentalUnlocked.value &&
            name != null && adultPattern.containsMatchIn(name)

    /**
     * Every category the parental filter matches, whether or not a PIN is set
     * and whether or not this session is unlocked.
     *
     * Settings needs this to say which categories it will actually hide.
     * [isLockedCategory] can't answer that — it folds the match together with
     * "is the lock currently armed", so it reports nothing at all while the
     * viewer is deciding whether to set a PIN, which is exactly when they want
     * to know what the setting covers.
     */
    fun restrictedCategoryNames(bundle: ContentBundle?): List<String> {
        val b = bundle ?: return emptyList()
        return (b.liveCategories + b.movieCategories + b.seriesCategories)
            .map { it.name }
            .filter { adultPattern.containsMatchIn(it) }
            .distinct()
            .sorted()
    }

    val schedules: StateFlow<List<ScheduledRecording>> = playerPrefs.schedules
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val epgState: StateFlow<ContentRepository.EpgState> = repo.epg

    /**
     * What the loaded guide actually covers.
     *
     * [ContentRepository.EpgState.Ready] only means the XMLTV downloaded and
     * parsed — it says nothing about whether any of it belongs to this
     * playlist. A tvg-id mismatch between playlist and guide is the most common
     * EPG failure in IPTV and it produces a Ready state whose every row reads
     * "No information", with no message and no route to a fix.
     */
    data class GuideCoverage(
        /** False when the guide parsed but shares no channel with the playlist. */
        val matchesPlaylist: Boolean,
        /** End of the last programme in the guide; the ceiling for day paging. */
        val lastProgramEndMs: Long,
    )

    val guideCoverage: StateFlow<GuideCoverage> =
        kotlinx.coroutines.flow.combine(content, epgState) { c, epg ->
            val unknown = GuideCoverage(matchesPlaylist = true, lastProgramEndMs = Long.MAX_VALUE)
            val data = (epg as? ContentRepository.EpgState.Ready)?.data ?: return@combine unknown
            val bundle = (c as? ContentState.Ready)?.bundle ?: return@combine unknown
            GuideCoverage(
                // Short-circuits on the first hit, so a healthy guide costs one
                // map lookup rather than a walk of the whole channel list.
                matchesPlaylist = bundle.channels.any { repo.programsFor(it).isNotEmpty() },
                lastProgramEndMs = data.programmes.values
                    .maxOfOrNull { list -> list.maxOfOrNull { it.endMs } ?: 0L } ?: 0L,
            )
        }
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
            // Optimistic until it resolves: never flash "no guide" over a guide
            // that is about to draw, and never disable day paging prematurely.
            //
            // Eagerly, not WhileSubscribed: the cached value survives leaving
            // Live TV, so after fixing a mismatched EPG in Settings the guide
            // briefly re-rendered the old playlist's "none of its channels
            // match" verdict — on the very return trip made to check the fix.
            // Kept warm, the verdict is recomputed the moment content or EPG
            // change, and the walk is cheap and off the main thread.
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                GuideCoverage(true, Long.MAX_VALUE),
            )

    val activeRecording: StateFlow<ActiveRecording?> = RecordingManager.active

    /** Now/next per channel id, recomputed once a minute off the main thread. */
    data class NowNext(val now: EpgProgram?, val next: EpgProgram?)

    private val minuteTicker = kotlinx.coroutines.flow.flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }

    // Previous result, reused between ticks. Written only by the combine below,
    // but successive emissions can land on different Dispatchers.Default
    // threads, so these need to be volatile to be seen.
    @Volatile private var nowNextCache: Map<String, NowNext> = emptyMap()
    @Volatile private var nowNextCacheBundle: ContentBundle? = null
    @Volatile private var nowNextCacheEpg: Any? = null

    val nowNext: StateFlow<Map<String, NowNext>> =
        kotlinx.coroutines.flow.combine(content, epgState, minuteTicker) { c, epg, now ->
            val bundle = (c as? ContentState.Ready)?.bundle ?: return@combine emptyMap()
            // Rescanning every channel's whole programme list once a minute is
            // a CPU and allocation spike that lands as micro-stutter during
            // playback. A channel's now/next only changes when its current
            // programme ends, so almost every entry survives a tick untouched.
            // Reference identity, not hashes: a hash collision would silently
            // serve one playlist's guide data for another's.
            val previous =
                if (nowNextCacheBundle === bundle && nowNextCacheEpg === epg) nowNextCache
                else emptyMap()
            var changed = previous.size != bundle.channels.size
            val refreshed = HashMap<String, NowNext>(bundle.channels.size)
            bundle.channels.forEach { channel ->
                val cached = previous[channel.id]
                val stillCurrent = cached?.now?.let { now < it.endMs } == true
                if (stillCurrent) {
                    refreshed[channel.id] = cached!!
                } else {
                    val programs = repo.programsFor(channel)
                    val fresh = NowNext(
                        now = programs.firstOrNull { now in it.startMs until it.endMs },
                        next = programs.firstOrNull { it.startMs >= now },
                    )
                    if (fresh != cached) changed = true
                    refreshed[channel.id] = fresh
                }
            }
            nowNextCacheBundle = bundle
            nowNextCacheEpg = epg
            // Returning the previous instance when nothing moved lets StateFlow
            // dedupe the emission, so an idle minute costs no recomposition.
            val result = if (changed) refreshed else previous
            nowNextCache = result
            result
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
            // Folded into one source so the unlock is something this combine can
            // see; the typed combine overloads stop at five flows.
            kotlinx.coroutines.flow.combine(playerPrefs.parentalPin, _parentalUnlocked) { pin, unlocked ->
                pin.takeIf { !unlocked }
            },
            playerPrefs.channelOrder,
        ) { c, hiddenSet, merge, effectivePin, order ->
            val bundle = (c as? ContentState.Ready)?.bundle
                ?: return@combine Triple(emptyList<LiveChannel>(), false, 0)
            val lockedIds = if (effectivePin != null) {
                bundle.liveCategories.filter { isLockedCategory(it.name) }.map { it.id }.toSet()
            } else emptySet()
            val visible = bundle.channels
                .filterNot { it.url in hiddenSet }
                .filterNot { it.categoryId in lockedIds }
            Triple(visible, merge, order)
        }
            // Separate combine: the typed overloads stop at five flows. The
            // decoded-quality overlay runs before merge/sort so duplicate
            // merging and the quality ordering act on the truth, not on
            // whatever tag the provider typed into the stream name.
            .combine(playerPrefs.knownQualities) { (visible, merge, order), known ->
                val corrected =
                    if (known.isEmpty()) visible
                    else visible.map { ch ->
                        val real = known[ch.url]
                        if (real == null || real == ch.quality) ch else ch.copy(quality = real)
                    }
                val merged =
                    if (merge) com.nuxcor.nuxtv.data.QualityTag.mergeBestQuality(corrected)
                    else corrected
                when (order) {
                    1 -> merged.sortedBy { it.name.lowercase() }
                    2 -> merged.sortedWith(
                        compareByDescending<LiveChannel> {
                            com.nuxcor.nuxtv.data.QualityTag.rank(it.quality)
                        }.thenBy { it.name.lowercase() }
                    )
                    else -> merged
                }
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
        // Periodic quiet playlist refresh, mirroring the EPG's 6h cycle at a
        // gentler cadence — catalogs change daily, guides hourly.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(12 * 60 * 60 * 1000L)
                runCatching { repo.refreshQuiet() }
            }
        }
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
        viewModelScope.launch {
            delay(3_000)
            _accountInfo.value = repo.accountInfo()
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
                        "Couldn't start the installer — allow \"install unknown apps\" for Agoro, then check again"
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
                        "Couldn't start the installer — allow \"install unknown apps\" for Agoro, then check again"
                    )
                }
            }.onFailure { e ->
                _updateState.value =
                    com.nuxcor.nuxtv.data.UpdateManager.State.Error(e.message ?: "Download failed")
            }
        }
    }

    fun setEpgOverrideUrl(url: String?) = viewModelScope.launch { playerPrefs.setEpgOverrideUrl(url) }


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
                    BACKUP_FILE,
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
                // Falls back to the pre-rename filename so a backup exported by
                // an older build still restores.
                val dir = getApplication<Application>().getExternalFilesDir(null)
                val file = java.io.File(dir, BACKUP_FILE).takeIf { it.exists() }
                    ?: java.io.File(dir, LEGACY_BACKUP_FILE)
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

    /** The existing playlist an edit is working on, or null while adding a new one. */
    fun sourceById(id: String?): PlaylistSource? =
        id?.let { wanted -> sources.value?.firstOrNull { it.id == wanted } }

    fun updateXtream(
        id: String,
        name: String,
        server: String,
        username: String,
        password: String,
        onSuccess: () -> Unit,
    ) {
        saveSource(
            PlaylistSource.Xtream(
                id = id,
                name = name.ifBlank { "Xtream playlist" },
                serverUrl = server.trim(),
                username = username.trim(),
                password = password.trim(),
            ),
            onSuccess,
            existing = true,
        )
    }

    fun updateM3u(id: String, name: String, url: String, epgUrl: String, onSuccess: () -> Unit) {
        saveSource(
            PlaylistSource.M3u(
                id = id,
                name = name.ifBlank { "M3U playlist" },
                url = url.trim(),
                epgUrl = epgUrl.trim().takeIf { it.isNotBlank() },
            ),
            onSuccess,
            existing = true,
        )
    }

    private fun addSource(source: PlaylistSource, onSuccess: () -> Unit) =
        saveSource(source, onSuccess, existing = false)

    private fun saveSource(source: PlaylistSource, onSuccess: () -> Unit, existing: Boolean) {
        addState = AddState.Loading
        viewModelScope.launch {
            val outcome =
                if (existing) repo.validateAndUpdate(source) else repo.validateAndAdd(source)
            outcome.fold(
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

    private val _accountInfo =
        MutableStateFlow<com.nuxcor.nuxtv.data.XtreamClient.AccountInfo?>(null)
    val accountInfo: StateFlow<com.nuxcor.nuxtv.data.XtreamClient.AccountInfo?> = _accountInfo

    fun refreshAccountInfo() {
        viewModelScope.launch { _accountInfo.value = repo.accountInfo() }
    }

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

    /**
     * Bundled at build time — there is no in-app setting for it. Null when the
     * build had no key, in which case enrichment is simply off rather than
     * asking the viewer to go and register for one.
     */
    private val tmdbApiKey: String? = BuildConfig.TMDB_API_KEY.takeIf { it.isNotBlank() }

    suspend fun movieDetails(movie: Movie): Movie = repo.movieDetails(movie, tmdbApiKey)
    suspend fun seriesDetails(series: Series): Series = repo.seriesDetails(series, tmdbApiKey)
    suspend fun episodesFor(series: Series): List<Episode> = repo.episodesFor(series)
    suspend fun epgFor(channel: LiveChannel): List<EpgProgram> = repo.epgFor(channel)
    suspend fun catchupUrl(channel: LiveChannel, program: EpgProgram): String? =
        repo.catchupUrl(channel, program)

    fun programsFor(channel: LiveChannel): List<EpgProgram> = repo.programsFor(channel)

    /** Latest learned real tiers, for synchronous consumers (search). */
    private val knownQualitiesNow: StateFlow<Map<String, String>> =
        playerPrefs.knownQualities.stateIn(
            viewModelScope, SharingStarted.Eagerly, emptyMap(),
        )

    /** Remember what a stream really decodes at, so lists stop repeating the name's lie. */
    fun recordDecodedQuality(url: String, height: Int) {
        val tier = com.nuxcor.nuxtv.data.QualityTag.tierOf(height) ?: return
        viewModelScope.launch { playerPrefs.setKnownQuality(url, tier) }
    }

    fun toggleFavorite(channel: LiveChannel) {
        viewModelScope.launch { playerPrefs.toggleFavorite(channel.url) }
    }

    /**
     * Records a live channel as watched. Called from the player once a channel
     * has been on screen long enough to count as watched rather than zapped
     * past — see the dwell in PlayerScreen.
     */
    fun recordChannelVisit(url: String) {
        viewModelScope.launch { playerPrefs.recordChannelVisit(url) }
    }

    fun setGuidePreview(enabled: Boolean) {
        viewModelScope.launch { playerPrefs.setGuidePreview(enabled) }
    }

    fun clearRecentChannels() {
        viewModelScope.launch { playerPrefs.clearRecentChannels() }
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
        /** Current and upcoming programmes whose titles match. */
        val programs: List<ProgramHit> = emptyList(),
    )

    data class ProgramHit(val channel: LiveChannel, val program: EpgProgram)

    fun search(query: String): SearchResults {
        val q = foldForSearch(query.trim())
        if (q.length < 2) return SearchResults()
        val tokens = q.split(' ').filter { it.isNotBlank() }
        val b = bundle ?: return SearchResults()
        val lockedLive = b.liveCategories.filter { isLockedCategory(it.name) }.map { it.id }.toSet()
        val lockedMovie = b.movieCategories.filter { isLockedCategory(it.name) }.map { it.id }.toSet()
        val lockedSeries = b.seriesCategories.filter { isLockedCategory(it.name) }.map { it.id }.toSet()

        // Rank, then stable-sort: prefix beats word-start beats substring, and
        // within a rank the playlist's own order survives.
        fun <T> rankAndTake(items: List<T>, name: (T) -> String, allowed: (T) -> Boolean): List<T> =
            items.mapNotNull { item ->
                if (!allowed(item)) return@mapNotNull null
                searchRank(name(item), q, tokens)?.let { rank -> rank to item }
            }.sortedBy { it.first }.map { it.second }.take(30)

        val known = knownQualitiesNow.value
        val channels = rankAndTake(b.channels, { it.name }) { it.categoryId !in lockedLive }
            .map { ch -> known[ch.url]?.let { real -> ch.copy(quality = real) } ?: ch }

        // Programme titles, the guide's other half: what is ON, not just what
        // the channel is called. Current and upcoming only — a finished
        // programme isn't something search can offer to watch.
        val now = System.currentTimeMillis()
        val programs = ArrayList<ProgramHit>()
        outer@ for (channel in b.channels) {
            if (channel.categoryId in lockedLive) continue
            for (program in repo.programsFor(channel)) {
                if (program.endMs <= now) continue
                if (searchRank(program.title, q, tokens) != null) {
                    programs.add(ProgramHit(channel, program))
                    if (programs.size >= 20) break@outer
                }
            }
        }
        programs.sortBy { it.program.startMs }

        return SearchResults(
            channels = channels,
            movies = rankAndTake(b.movies, { it.name }) { it.categoryId !in lockedMovie },
            series = rankAndTake(b.series, { it.name }) { it.categoryId !in lockedSeries },
            programs = programs,
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

    fun playMovie(movie: Movie, startOver: Boolean = false) {
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
            ignoreResume = startOver,
        )
    }

    fun playEpisodes(
        series: Series,
        episodes: List<Episode>,
        startIndex: Int,
        startOver: Boolean = false,
    ) {
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
            ignoreResume = startOver,
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


/** Case- and diacritic-insensitive text for matching ("Télé" matches "tele"). */
private fun foldForSearch(text: String): String {
    val normalized = java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
    return normalized.filterNot { it.code in 0x300..0x36F }
}

/**
 * Null when [name] doesn't match; otherwise a rank — 0 name starts with the
 * query, 1 a word starts with the first token, 2 plain substring. Every token
 * must appear somewhere, in any order, so "one cinema" finds "Cinema One".
 */
private fun searchRank(name: String, query: String, tokens: List<String>): Int? {
    val folded = foldForSearch(name)
    if (!tokens.all { folded.contains(it) }) return null
    return when {
        folded.startsWith(query) -> 0
        folded.split(' ', '-', '.', '(', '[').any { it.startsWith(tokens.first()) } -> 1
        else -> 2
    }
}
