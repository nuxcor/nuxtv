package com.nuxcor.nuxtv

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nuxcor.nuxtv.data.ArtEntry
import com.nuxcor.nuxtv.data.Category
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
import kotlinx.coroutines.sync.withPermit
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

    /** [step] narrates what setup is downloading right now. */
    data class Loading(val step: String = "Connecting to your provider…") : AddState()
    data class Error(val message: String) : AddState()
}

private const val BACKUP_FILE = "agoro-backup.json"
private const val LEGACY_BACKUP_FILE = "dzidzi-backup.json"

/** How old the cached catalog may grow before a quiet refresh re-fetches it. */
private const val PLAYLIST_MAX_AGE_MS = 12 * 60 * 60 * 1000L

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

    /** Episode stream URL → series id, Continue Watching's climb back to the series. */
    val episodeOrigins: StateFlow<Map<String, String>> = playerPrefs.episodeOrigins
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

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
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

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
        /** False when the guide parsed but barely matches this playlist. */
        val matchesPlaylist: Boolean,
        /** Channels the guide resolved / total in the playlist. */
        val matched: Int = 0,
        val total: Int = 0,
        /** End of the last programme in the guide; the ceiling for day paging. */
        val lastProgramEndMs: Long,
    )

    val guideCoverage: StateFlow<GuideCoverage> =
        kotlinx.coroutines.flow.combine(content, epgState) { c, epg ->
            val unknown = GuideCoverage(matchesPlaylist = true, lastProgramEndMs = Long.MAX_VALUE)
            val data = (epg as? ContentRepository.EpgState.Ready)?.data ?: return@combine unknown
            if (c !is ContentState.Ready) return@combine unknown
            // This is the warm-up site: Eagerly + Dispatchers.Default, keyed
            // on exactly (content, epg) — by the time any UI walks the guide,
            // the fuzzy channel→guide resolution is computed and cached.
            val resolution = repo.resolveEpg() ?: return@combine unknown
            GuideCoverage(
                // A real ratio, not `any {}`: one lucky channel out of 4,000
                // used to pass, leaving a grid of "No information" rows with
                // no recovery path.
                matchesPlaylist = resolution.matched > 0 &&
                    resolution.matched * 5 >= resolution.total,
                matched = resolution.matched,
                total = resolution.total,
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
                GuideCoverage(matchesPlaylist = true, lastProgramEndMs = Long.MAX_VALUE),
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
                    if (merge) {
                        com.nuxcor.nuxtv.data.QualityTag.mergeBestQuality(corrected, known.keys)
                    } else corrected
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

    /**
     * What the "All channels" shelf shows: [displayChannels] with duplicates
     * collapsed ACROSS categories, so a channel filed under five shelves lists
     * once. [displayChannels] can only merge within a category — that is the
     * right scope for a shelf, and the wrong one for All.
     *
     * It lives here rather than in the four screens that show it because the
     * merge is a regex pass over every channel — 12ms on a desktop JVM at 6k
     * channels, several times that on TV hardware. Each screen was running it
     * inside composition, on the main thread, and re-running it on every
     * emission of [displayChannels]: playing a channel learns its real
     * quality, which re-emits, which re-merged the entire catalogue mid-zap.
     */
    val allChannelsView: StateFlow<List<LiveChannel>> =
        displayChannels.combine(playerPrefs.mergeDuplicates) { channels, merge ->
            if (!merge) channels
            else com.nuxcor.nuxtv.data.QualityTag.mergeBestQuality(
                channels,
                keyOf = { com.nuxcor.nuxtv.data.EpgMatcher.normalizeKey(it.name) },
            )
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
        // Before anything reads URL-keyed prefs: live URLs changed .m3u8 → .ts
        // and favorites/hidden/learned-quality keys must follow them.
        viewModelScope.launch { playerPrefs.migrateLiveUrlsToTs() }
        viewModelScope.launch { repo.ensureLoaded() }
        // Periodic quiet playlist refresh, mirroring the EPG's 6h cycle at a
        // gentler cadence — catalogs change daily, guides hourly. Checked
        // hourly against the persisted cache age rather than delaying a full
        // cycle: the old form only fired after 12h of *continuous* runtime,
        // which a TV app that is opened for an evening never accumulates.
        viewModelScope.launch {
            while (true) {
                runCatching { repo.refreshIfStale(PLAYLIST_MAX_AGE_MS) }
                kotlinx.coroutines.delay(60 * 60 * 1000L)
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
        RecordingScheduler.scheduleReminder(getApplication(), channel.displayName, program)
    }

    fun toggleHidden(channel: LiveChannel) {
        viewModelScope.launch { playerPrefs.toggleHidden(channel.url) }
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
        addState = AddState.Loading("Downloading channels, movies and series…")
        viewModelScope.launch {
            val outcome =
                if (existing) repo.validateAndUpdate(source) else repo.validateAndAdd(source)
            outcome.fold(
                onSuccess = {
                    // Setup finishes with everything the first screen needs:
                    // the guide used to load after onboarding closed, so a
                    // fresh install opened onto an empty, still-loading Live
                    // tab. A guide failure doesn't block setup — the app
                    // retries EPG on its own cycle.
                    addState = AddState.Loading("Downloading the TV guide…")
                    runCatching { repo.loadEpg(playerPrefs.epgOverrideUrl.first()) }
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

    /** Resume-time catch-up: re-fetch the catalog only if it has gone stale. */
    fun refreshIfStale() = viewModelScope.launch {
        runCatching { repo.refreshIfStale(PLAYLIST_MAX_AGE_MS) }
    }

    private val _accountInfo =
        MutableStateFlow<com.nuxcor.nuxtv.data.XtreamClient.AccountInfo?>(null)
    val accountInfo: StateFlow<com.nuxcor.nuxtv.data.XtreamClient.AccountInfo?> = _accountInfo

    val guidePreviewMode: StateFlow<String> = playerPrefs.guidePreviewMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, "auto")

    /**
     * Auto decides from the account's connection limit: on when the provider
     * allows a second connection, off on single-connection plans and M3U
     * links. The manual override exists because playlist middlemen
     * (IPTVEditor) report a cosmetic max_connections that defaults to 1 —
     * auto can never turn on for those accounts even when the real plan
     * allows more.
     */
    val guidePreview: StateFlow<Boolean> =
        combine(playerPrefs.guidePreviewMode, accountInfo) { mode, account ->
            when (mode) {
                "on" -> true
                "off" -> false
                else -> (account?.maxConnections ?: 1) >= 2
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setGuidePreviewMode(mode: String) {
        viewModelScope.launch { playerPrefs.setGuidePreviewMode(mode) }
    }

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

    // --- borrowed artwork ------------------------------------------------------

    /**
     * Catalogue id → art borrowed from TMDB, for the entries a provider ships
     * with no images. Persisted, so the lookups happen once per title and not
     * once per app start.
     */
    val artwork: StateFlow<Map<String, ArtEntry>> = playerPrefs.artwork
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val artInFlight = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * At most this many lookups running or queued. A catalogue scrolled fast
     * would otherwise queue a request per card it flew past, and TMDB answers
     * a burst like that with 429s. Anything refused here simply asks again the
     * next time its card is on screen, which is the moment it matters.
     */
    private val artConcurrency = kotlinx.coroutines.sync.Semaphore(3)
    private val artQueueLimit = 24

    /**
     * Fills in one catalogue entry's artwork if TMDB has any.
     *
     * Returns false ONLY when the queue was full, meaning "ask me again" —
     * every other outcome (no key, answer already known including a known
     * "TMDB has nothing", request already in flight) returns true because
     * there is nothing further to do. The distinction matters because the
     * caller fires once per card: a wide grid can expire ~28 dwell timers
     * together, and silently dropping the overflow left those cells as
     * monograms until the viewer scrolled them off screen and back.
     */
    fun requestArtwork(id: String, kind: String, title: String, year: Int?): Boolean {
        val key = tmdbApiKey ?: return true
        if (id in artwork.value) return true
        if (artInFlight.size >= artQueueLimit) return false
        if (!artInFlight.add(id)) return true
        viewModelScope.launch {
            val entry = artConcurrency.withPermit { repo.artworkFor(kind, title, year, key) }
            if (entry != null) {
                playerPrefs.putArtwork(id, entry)
            } else {
                // Unreachable, not "no such title" — hold the id back briefly
                // so a dead network can't turn one visible row into a retry
                // storm while the viewer sits on it.
                delay(10_000)
            }
            artInFlight.remove(id)
        }
        return true
    }
    /**
     * Session cache of fetched episode lists, so a series browsed once opens
     * instantly ever after. Bounded LRU: 100 series of episodes is a few
     * hundred KB, not a leak.
     */
    private val episodesCache = object : LinkedHashMap<String, List<Episode>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Episode>>) =
            size > 100
    }
    private val episodesInFlight = mutableSetOf<String>()

    /**
     * Focus-time warm-up. On curated proxies (IPTVEditor and kin) the first
     * get_series_info is what triggers the upstream episode build, so firing
     * it while the poster is focused means the list is often ready before OK
     * is pressed. Strictly once per series per session — a full prefetch at
     * setup (thousands of requests) is the pattern providers rate-limit and
     * ban, and per-scroll refetches are the pattern that 429s TiviMate.
     */
    fun prefetchEpisodes(series: Series) {
        if (series.episodes != null) return
        synchronized(episodesCache) {
            if (episodesCache.containsKey(series.id) || !episodesInFlight.add(series.id)) return
        }
        viewModelScope.launch {
            val result = runCatching { repo.episodesFor(series) }.getOrNull()
            synchronized(episodesCache) {
                if (!result.isNullOrEmpty()) episodesCache[series.id] = result
                episodesInFlight.remove(series.id)
            }
        }
    }

    /** Episodes for [series]; empty = the provider has none, null = the fetch failed. */
    suspend fun episodesFor(series: Series): List<Episode>? {
        synchronized(episodesCache) { episodesCache[series.id] }?.let { return it }
        val result = repo.episodesFor(series)
        if (!result.isNullOrEmpty()) {
            synchronized(episodesCache) { episodesCache[series.id] = result }
        }
        return result
    }
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
                channelName = channel.displayName,
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

        /**
         * The categories whose own name matches, which their contents inherit.
         *
         * A channel's shelf is part of how a viewer names it. Panels file
         * pay-per-view under "PPV EVENTS" and then name the channels after the
         * fight, so searching "ppv" — the only word the viewer has — matched
         * nothing at all. The same holds for a "Documentaries" shelf whose
         * films are all named something else.
         */
        fun matchingCategories(categories: List<Category>, locked: Set<String>): Set<String> =
            categories.mapNotNullTo(HashSet()) { category ->
                category.id.takeIf {
                    it !in locked && searchRank(category.name, q, tokens) != null
                }
            }

        val liveCatHits = matchingCategories(b.liveCategories, lockedLive)
        val movieCatHits = matchingCategories(b.movieCategories, lockedMovie)
        val seriesCatHits = matchingCategories(b.seriesCategories, lockedSeries)

        // Rank, then stable-sort: prefix beats word-start beats substring, and
        // within a rank the playlist's own order survives. A category hit
        // ranks below every name hit, so inheriting a shelf's name can never
        // push a directly-named result out of the results.
        fun <T> rankAndTake(
            items: List<T>,
            name: (T) -> String,
            categoryId: (T) -> String?,
            categoryHits: Set<String>,
            locked: Set<String>,
        ): List<T> =
            items.mapNotNull { item ->
                val category = categoryId(item)
                if (category in locked) return@mapNotNull null
                val rank = searchRank(name(item), q, tokens)
                    ?: CATEGORY_MATCH_RANK.takeIf { category in categoryHits }
                    ?: return@mapNotNull null
                rank to item
            }.sortedBy { it.first }.map { it.second }.take(30)

        val known = knownQualitiesNow.value
        val channels =
            rankAndTake(b.channels, { it.name }, { it.categoryId }, liveCatHits, lockedLive)
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
            movies = rankAndTake(
                b.movies, { it.name }, { it.categoryId }, movieCatHits, lockedMovie,
            ),
            series = rankAndTake(
                b.series, { it.name }, { it.categoryId }, seriesCatHits, lockedSeries,
            ),
            programs = programs,
        )
    }

    // --- resume positions -----------------------------------------------------

    suspend fun resumePositionFor(url: String): Long = playerPrefs.resumePositionFor(url)

    fun saveResumePosition(url: String, positionMs: Long, durationMs: Long) {
        viewModelScope.launch { playerPrefs.saveResumePosition(url, positionMs, durationMs) }
    }

    /** Drops a movie from Continue watching. */
    fun forgetResume(url: String) {
        viewModelScope.launch { playerPrefs.clearResume(listOf(url)) }
    }

    /**
     * Drops a whole series from Continue watching. A series card stands for
     * whichever episode was last watched, so forgetting it has to forget every
     * episode of it — otherwise the card returns, pointing at an older one.
     */
    fun forgetSeriesResume(series: Series) {
        val urls = episodeOrigins.value.filterValues { it == series.id }.keys
        viewModelScope.launch { playerPrefs.clearResume(urls) }
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
                    title = it.displayName,
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
        // Episode URLs don't encode their series; remember the link now — the
        // only moment both sides are known — so a resume position saved later
        // can climb back to its Series card.
        viewModelScope.launch {
            playerPrefs.recordEpisodeOrigins(series.id, episodes.map { it.url })
        }
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
                    subtitle = "Catch-up • ${channel.displayName}",
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


/**
 * The rank a result gets for matching its category's name rather than its own.
 * Below every [searchRank] a name can earn, so it only ever adds to the tail.
 */
private const val CATEGORY_MATCH_RANK = 3

/** Case- and diacritic-insensitive text for matching — shared with the EPG matcher. */
private fun foldForSearch(text: String): String = com.nuxcor.nuxtv.data.EpgMatcher.fold(text)

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
