package com.agoro.tv

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agoro.tv.data.userMessage
import com.agoro.tv.data.ArtEntry
import com.agoro.tv.data.Category
import com.agoro.tv.data.ContentBundle
import com.agoro.tv.data.ContentRepository
import com.agoro.tv.data.ContentState
import com.agoro.tv.data.EpgProgram
import com.agoro.tv.data.Episode
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.data.Movie
import com.agoro.tv.data.PlayableItem
import com.agoro.tv.data.PlaybackRequest
import com.agoro.tv.data.PlayerPrefs
import com.agoro.tv.data.PlaylistSource
import com.agoro.tv.data.indexAnswering
import com.agoro.tv.data.ScheduledRecording
import com.agoro.tv.data.Series
import com.agoro.tv.recording.ActiveRecording
import com.agoro.tv.recording.Recording
import com.agoro.tv.recording.RecordingManager
import com.agoro.tv.recording.RecordingScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed class AddState {
    data object Idle : AddState()

    /** [step] narrates what setup is downloading right now. */
    data class Loading(val step: String = "Connecting to your provider…") : AddState()
    data class Error(val message: String) : AddState()
}

private const val BACKUP_FILE = "agoro-backup.json"
private const val LEGACY_BACKUP_FILE = "dzidzi-backup.json"

/** How old the cached catalog may grow before a quiet refresh re-fetches it. */
private const val PLAYLIST_MAX_AGE_MS = com.agoro.tv.data.ContentRepository.PLAYLIST_MAX_AGE_MS

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: ContentRepository = (app as NuxTvApp).repository
    private val playerPrefs = PlayerPrefs(app)
    private val updateManager = com.agoro.tv.data.UpdateManager(app, repo.http)

    private val _updateState =
        MutableStateFlow<com.agoro.tv.data.UpdateManager.State>(
            com.agoro.tv.data.UpdateManager.State.Idle
        )
    val updateState: StateFlow<com.agoro.tv.data.UpdateManager.State> = _updateState

    /** null until the persisted sources have been read. */
    val sources: StateFlow<List<PlaylistSource>?> = repo.sources
        .map { it as List<PlaylistSource>? }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val activeSource: StateFlow<PlaylistSource?> = repo.activeSource
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val content: StateFlow<ContentState> = repo.content


    val favorites: StateFlow<Set<String>> = playerPrefs.favorites
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val hidden: StateFlow<Set<String>> = playerPrefs.hidden
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /**
     * Titles the viewer pushed off Home with "Not interested". Home's catalogue
     * rows are the only thing that reads it — the title stays in Movies, Shows
     * and search, because hiding a row is not the same as deleting a film.
     */
    /**
     * The leagues the Sport destination carries. Read once — it is build-time
     * curation, not something that changes while the app is open.
     */
    val sport: StateFlow<com.agoro.tv.data.Sport?> =
        kotlinx.coroutines.flow.flow { emit(repo.manifest()?.sport) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Plays a fixture on its best slot, with the lower-tier slots carrying the
     * same match behind it — the same match is routinely on four at once, and
     * the best one is not always the one that opens.
     */
    fun playEvent(streamId: Int, alternates: List<Int> = emptyList()) {
        val slots = content.value.let { it as? ContentState.Ready }?.bundle?.events ?: return
        val best = slots.firstOrNull { it.xtreamId == streamId } ?: return
        val fallbacks = alternates.mapNotNull { alt ->
            slots.firstOrNull { it.xtreamId == alt }?.url
        }
        playChannels(listOf(best.copy(fallbackUrls = fallbacks)), 0)
    }

    val hiddenTitles: StateFlow<Set<String>> = playerPrefs.hiddenTitles
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())


    /** Stream URLs of recently watched live channels, newest first. */
    val recentChannels: StateFlow<List<String>> = playerPrefs.recentChannels
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
                // One indexed aggregate over the stored guide. Walking every
                // programme in memory to find the largest end was both the
                // slowest part of this combine and, now that programmes live
                // in the table, an answer of zero.
                lastProgramEndMs = repo.lastProgrammeEndMs(),
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
    @Volatile private var nowNextCacheRevision: Int = -1

    val nowNext: StateFlow<Map<String, NowNext>> =
        kotlinx.coroutines.flow.combine(
            content,
            epgState,
            minuteTicker,
            repo.guideWindowRevision,
        ) { c, epg, now, revision ->
            val bundle = (c as? ContentState.Ready)?.bundle ?: return@combine emptyMap()
            // Rescanning every channel's whole programme list once a minute is
            // a CPU and allocation spike that lands as micro-stutter during
            // playback. A channel's now/next only changes when its current
            // programme ends, so almost every entry survives a tick untouched.
            // Reference identity, not hashes: a hash collision would silently
            // serve one playlist's guide data for another's.
            val previous =
                if (nowNextCacheBundle === bundle && nowNextCacheEpg === epg &&
                    nowNextCacheRevision == revision
                ) nowNextCache
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
            nowNextCacheRevision = revision
            // Returning the previous instance when nothing moved lets StateFlow
            // dedupe the emission, so an idle minute costs no recomposition.
            val result = if (changed) refreshed else previous
            nowNextCache = result
            result
        }
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Channels after hidden/parental filtering and duplicate merging, computed
     * off the main thread instead of inside composition.
     *
     * Merging and provider order are not choices. The manifest has already
     * collapsed the variants it knows about and dropped SD outright; what
     * reaches here is whatever a raw M3U or an un-manifested source still
     * ships twice over, and nobody wants that listed four times. Provider
     * order is the numbering the guide and the number keys are built on, so
     * an alphabetical or quality sort would leave channel 101 in the middle
     * of the list.
     */
    val displayChannels: StateFlow<List<LiveChannel>> =
        kotlinx.coroutines.flow.combine(
            content,
            // distinctUntilChanged on every preference source. DataStore
            // re-emits the WHOLE Preferences object to every collector on any
            // write, and combine re-runs on any emission regardless of value —
            // so saving a borrowed poster (one per TMDB lookup, two dozen in
            // flight while a grid scrolls) rebuilt the entire channel list,
            // twice, at ~8 regex per channel. None of these values changed.
            playerPrefs.hidden.distinctUntilChanged(),
            // Folded into one source so the unlock is something this combine
            // can see.
            kotlinx.coroutines.flow.combine(playerPrefs.parentalPin, _parentalUnlocked) { pin, unlocked ->
                pin.takeIf { !unlocked }
            }.distinctUntilChanged(),
        ) { c, hiddenSet, effectivePin ->
            val bundle = (c as? ContentState.Ready)?.bundle
                ?: return@combine emptyList<LiveChannel>()
            val lockedIds = if (effectivePin != null) {
                bundle.liveCategories.filter { isLockedCategory(it.name) }.map { it.id }.toSet()
            } else emptySet()
            bundle.channels
                .filterNot { it.url in hiddenSet }
                .filterNot { it.categoryId in lockedIds }
        }
            // Separate combine, and the decoded-quality overlay runs BEFORE
            // the merge: which variant is best has to be decided on the truth,
            // not on whatever tag the provider typed into the stream name.
            .combine(playerPrefs.knownQualities.distinctUntilChanged()) { visible, known ->
                val corrected =
                    if (known.isEmpty()) visible
                    else visible.map { ch ->
                        val real = known[ch.url]
                        if (real == null || real == ch.quality) ch else ch.copy(quality = real)
                    }
                com.agoro.tv.data.QualityTag.mergeBestQuality(corrected, known.keys)
            }
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
            // Long enough to outlast navigation. At 5s a trip between tabs
            // tore the combine down, and the next screen re-collected all six
            // upstream flows — re-parsing their JSON — and redid both merges
            // and the sort, which is precisely the "slow to navigate" path.
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(60_000), emptyList())

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
        displayChannels.map { channels ->
            com.agoro.tv.data.QualityTag.mergeBestQuality(
                channels,
                keyOf = { com.agoro.tv.data.EpgMatcher.normalizeKey(it.name) },
            )
        }
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(60_000), emptyList())

    // --- Catalogue indexes ---------------------------------------------------
    //
    // The VOD and live catalogues, indexed once per bundle off the main
    // thread, the way [displayChannels] already folds the channel list. The
    // browse tabs used to do this work in composition — Home, Movies and
    // Shows each walked the 23,000-title catalogue on every bundle publish
    // and every return to the tab, and a category chip cost a filter over
    // all of it. On a quad-core A53 those were the frames the viewer waited
    // through with nothing on screen. The joins themselves live in
    // HomeRows.kt beside their tests; this is the one place they run.

    /**
     * [displayChannels] grouped by provider category, so a category switch
     * in Live TV is a lookup rather than a pass over every channel.
     */
    val channelsByCategory: StateFlow<com.agoro.tv.ui.screens.LiveCategoryIndex> =
        displayChannels.map { com.agoro.tv.ui.screens.LiveCategoryIndex.of(it) }
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(60_000),
                com.agoro.tv.ui.screens.LiveCategoryIndex.empty,
            )

    /**
     * Reuses the last index when neither the bundle nor the lock changed.
     * WhileSubscribed tears the combine down a minute after the last browse
     * tab leaves, and on the next visit every upstream StateFlow replays its
     * current value — which would rebuild an index of the very same bundle.
     * Identity, not equality: comparing two 23,000-title bundles is the cost
     * this exists to avoid.
     */
    @Volatile private var catalogIndexCache: com.agoro.tv.ui.screens.CatalogIndex? = null
    @Volatile private var catalogIndexCachePin: String? = null

    /**
     * The open catalogue and the personal shelves over it — see
     * [com.agoro.tv.ui.screens.CatalogIndex] and [com.agoro.tv.ui.screens.Catalog].
     *
     * Two stages on purpose. The index (parental filter, per-category and
     * per-url maps, the dated titles sorted) depends only on the bundle and
     * the lock; the shelves over it depend on resume positions, which are
     * written every few seconds of playback. One combine would have rebuilt
     * the whole index on every one of those writes.
     *
     * Null until the first index lands: the tabs draw nothing for that beat
     * rather than a catalogue built from the previous playlist.
     */
    internal val catalog: StateFlow<com.agoro.tv.ui.screens.Catalog?> =
        kotlinx.coroutines.flow.combine(
            content,
            kotlinx.coroutines.flow.combine(playerPrefs.parentalPin, _parentalUnlocked) { pin, unlocked ->
                pin.takeIf { !unlocked }
            }.distinctUntilChanged(),
        ) { c, effectivePin ->
            val bundle = (c as? ContentState.Ready)?.bundle ?: return@combine null
            val cached = catalogIndexCache
            if (cached != null && cached.bundle === bundle && catalogIndexCachePin == effectivePin) {
                cached
            } else {
                com.agoro.tv.ui.screens.buildCatalogIndex(bundle) { name ->
                    effectivePin != null && adultPattern.containsMatchIn(name)
                }.also {
                    catalogIndexCache = it
                    catalogIndexCachePin = effectivePin
                }
            }
        }
            .let { index ->
                kotlinx.coroutines.flow.combine(
                    index, resumePositions, resumeProgress, episodeOrigins, hiddenTitles,
                ) { idx, positions, progress, origins, hidden ->
                    idx?.let {
                        com.agoro.tv.ui.screens.buildCatalog(it, positions, progress, origins, hidden)
                    }
                }
            }
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
            // A minute, like displayChannels: long enough that a trip to the
            // player and back does not rebuild the index.
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(60_000), null)

    /**
     * How long a fixture parse stays good. The parse reads kick-off times
     * relative to the clock it was given — a slot more than a day out is
     * dropped as noise — so one taken this morning is missing tonight's late
     * additions by the evening. An hour keeps it honest without re-reading
     * six thousand slots on every visit.
     */
    private val sportParseTtlMs = 60L * 60 * 1000

    @Volatile private var sportCacheEvents: List<LiveChannel>? = null
    @Volatile private var sportCacheSport: com.agoro.tv.data.Sport? = null
    @Volatile private var sportCacheAtMs: Long = 0L
    @Volatile private var sportCache: List<com.agoro.tv.data.SportsEvent>? = null

    /**
     * The Sport destination's fixtures, parsed out of the PPV slots.
     *
     * Parsed OFF the main thread, and kept: this reads six thousand slots,
     * several regexes each, against every club of every league. It used to
     * live in the tab as a produceState, which meant the parse was thrown
     * away every time the tab left composition and redone on the next visit
     * — and re-keyed on the events list, so a republished bundle re-ran it
     * even when the slots had not changed. Cached by the identity of the
     * slot list, it runs once per playlist load and once an hour after that.
     *
     * Null until the first parse lands; an empty list when the manifest
     * carries no leagues.
     */
    val sportFixtures: StateFlow<List<com.agoro.tv.data.SportsEvent>?> =
        kotlinx.coroutines.flow.combine(content, sport) { c, s ->
            val bundle = (c as? ContentState.Ready)?.bundle ?: return@combine null
            val leagues = s?.leagues.orEmpty()
            if (leagues.isEmpty()) return@combine emptyList()
            val now = System.currentTimeMillis()
            val cached = sportCache
            if (cached != null && sportCacheEvents === bundle.events && sportCacheSport === s &&
                now - sportCacheAtMs < sportParseTtlMs
            ) {
                return@combine cached
            }
            com.agoro.tv.data.SportsParser.parseAll(
                bundle.events.mapNotNull { ch -> ch.xtreamId?.let { it to ch.name } },
                now, leagues, s?.ambiguous.orEmpty().toSet(),
            ).also {
                sportCacheEvents = bundle.events
                sportCacheSport = s
                sportCacheAtMs = now
                sportCache = it
            }
        }
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(60_000), null)

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
        // The same age rule the hourly loop applies, so a launch is never a
        // refresh the loop would have refused a minute later.
        viewModelScope.launch { repo.ensureLoaded(PLAYLIST_MAX_AGE_MS) }
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
        // Before anything composes: the shell holds the boot background until
        // this answers, so it must not wait on anything an install with no
        // channel to resume doesn't already have.
        resolveStartTarget()
        // Load the guide when a playlist becomes readable and whenever the
        // playlist it belongs to changes — NOT on every publish of content.
        // A cold start publishes the catalogue at least twice (the cache,
        // then the refresh behind it), and each publish used to be a guide
        // request; the repository's debounce was meant to fold those into
        // one and, for reasons written on planGuideRefresh, did not. Keyed
        // on the source id, the second publish of the same playlist is not
        // a request at all.
        viewModelScope.launch {
            guideSourceKey().collect { sourceId ->
                if (sourceId != null) {
                    // Beside the guide, not after it: a cold fold holds
                    // loadEpg for minutes, and a cache from before logos
                    // were part of the fetch would have sat bare that long.
                    launch { repo.enrichLogos() }
                    repo.loadEpg()
                }
            }
        }
        // Auto-refresh the guide every 6 hours while the app is running.
        viewModelScope.launch {
            while (true) {
                delay(6L * 3600 * 1000)
                if (content.value is ContentState.Ready) repo.loadEpg()
            }
        }
        viewModelScope.launch {
            RecordingScheduler.rescheduleAll(getApplication(), playerPrefs)
        }
        viewModelScope.launch {
            delay(3_000)
            _accountInfo.value = repo.accountInfo()
        }
        // Silent update check shortly after launch, and again every few hours
        // for as long as the process lives.
        //
        // It used to run exactly once. A TV box is not a phone: it is left on,
        // and this app's process routinely outlives several releases — so a
        // version published an hour after launch was invisible until something
        // killed the app, which on a box that is never swiped away could be
        // days. The check is one HEAD request that reads a redirect header, so
        // repeating it costs close to nothing.
        viewModelScope.launch {
            delay(UPDATE_FIRST_CHECK_MS)
            while (true) {
                val result = updateManager.check()
                if (result is com.agoro.tv.data.UpdateManager.State.Available &&
                    _updateState.value.acceptsSilentUpdate()
                ) {
                    _updateState.value = result
                }
                delay(UPDATE_RECHECK_MS)
            }
        }
    }

    /**
     * Whether a silent check may write over this state.
     *
     * Idle is the untouched one. UpToDate and Error have to be here too, and
     * their absence was the second half of the same bug: a MANUAL check from
     * Settings leaves one of them behind, and the old guard read anything but
     * Idle as "busy" — so pressing "Check for updates" once, on a box with no
     * update yet, permanently silenced every later check in that process.
     *
     * Checking, Downloading and Ready are the states that mean something is
     * genuinely in flight or waiting on the viewer, and those are never
     * disturbed. Available is left alone as well: it already says what this
     * would say.
     */
    private fun com.agoro.tv.data.UpdateManager.State.acceptsSilentUpdate(): Boolean = when (this) {
        is com.agoro.tv.data.UpdateManager.State.Idle,
        is com.agoro.tv.data.UpdateManager.State.UpToDate,
        is com.agoro.tv.data.UpdateManager.State.Error -> true
        else -> false
    }

    /**
     * The id of the playlist whose catalogue is on screen, null while there
     * is none. Distinct, so the catalogue republishing — a refresh behind
     * the cache, a logo fill — is not a new value; only a different playlist
     * becoming readable is.
     */
    private fun guideSourceKey(): kotlinx.coroutines.flow.Flow<String?> =
        combine(repo.content, repo.activeSource) { c, source ->
            if (c is ContentState.Ready) source?.id else null
        }.distinctUntilChanged()

    fun checkForUpdates() {
        viewModelScope.launch {
            _updateState.value = com.agoro.tv.data.UpdateManager.State.Checking
            _updateState.value = updateManager.check()
        }
    }

    private companion object {
        /** Long enough to stay clear of the catalogue fetch that start-up is really for. */
        const val UPDATE_FIRST_CHECK_MS = 8_000L

        /**
         * Three hours. Eight checks a day on a box left on, which surfaces a
         * release within an afternoon of it landing without anyone thinking
         * about it. Shorter buys responsiveness nobody asked for; the manual
         * button in Settings is there for the moment someone does.
         */
        const val UPDATE_RECHECK_MS = 3L * 60 * 60 * 1000

        const val INSTALL_BLOCKED =
            "Couldn't start the installer — allow \"install unknown apps\" for Agoro, then press Install"

        /** Poll `active_cons` this often while waiting for a live slot to free. */
        const val AWAIT_SLOT_POLL_MS = 3_000L
        /** Give up waiting for the slot after this and reconnect anyway. */
        const val AWAIT_SLOT_TIMEOUT_MS = 45_000L
        /** Only wait for a slot on a line this tightly capped; above it there is room to spare. */
        const val CONNECTION_CAP_TO_GATE = 2

        /**
         * How long a launch with a channel to resume waits for the catalogue
         * before giving up and opening Home. Long enough for a cache read on a
         * slow box, short enough that a dead playlist doesn't hold the app on
         * a blank screen — the splash is showing for the first part of it.
         */
        const val RESUME_CATALOGUE_WAIT_MS = 4_000L

        /**
         * And how long to then wait for [displayChannels] to fill. It is
         * derived from the catalogue plus the hidden and parental filters, so
         * it lands a beat after the catalogue settles — but a playlist whose
         * every channel is hidden or locked never fills it at all.
         */
        const val RESUME_FILTER_WAIT_MS = 2_000L

        /**
         * A live or catch-up tune this soon after a preview let go of its
         * connection is the same slot changing hands, so it waits for the
         * count to fall.
         * Later than this and the handover is over — whatever holds the line
         * now is something else, and the tune should not sit on it.
         */
        const val SLOT_HANDOVER_WINDOW_MS = 15_000L

        /**
         * Give up waiting for a handover and open anyway after this — much
         * sooner than a reconnect's [AWAIT_SLOT_TIMEOUT_MS], because here the
         * viewer has just pressed OK and is watching the connecting screen.
         * Trying and letting the ladder recover beats a longer black wait.
         */
        const val SLOT_HANDOVER_TIMEOUT_MS = 12_000L
    }

    fun downloadAndInstallUpdate() {
        when (val current = _updateState.value) {
            is com.agoro.tv.data.UpdateManager.State.Downloading -> return // already running
            is com.agoro.tv.data.UpdateManager.State.Ready -> {
                if (!updateManager.install(current.file)) {
                    _updateState.value = current.copy(note = INSTALL_BLOCKED)
                }
                return
            }
            else -> Unit
        }
        val available = _updateState.value as? com.agoro.tv.data.UpdateManager.State.Available
            ?: return
        viewModelScope.launch {
            runCatching {
                _updateState.value = com.agoro.tv.data.UpdateManager.State.Downloading(0)
                val file = updateManager.download(available.apkUrl) { pct ->
                    _updateState.value = com.agoro.tv.data.UpdateManager.State.Downloading(pct)
                }
                val ready = com.agoro.tv.data.UpdateManager.State.Ready(available.version, file)
                _updateState.value = ready
                // Still Ready on a refused install: the APK is on disk, and
                // the viewer who now enables "install unknown apps" should
                // press Install, not download it all again.
                if (!updateManager.install(file)) {
                    _updateState.value = ready.copy(note = INSTALL_BLOCKED)
                }
            }.onFailure { e ->
                _updateState.value = com.agoro.tv.data.UpdateManager.State.Error(
                    "Download failed — ${e.userMessage()}"
                )
            }
        }
    }

    fun setParentalPin(pin: String?) = viewModelScope.launch { playerPrefs.setParentalPin(pin) }

    fun scheduleReminder(channel: LiveChannel, program: EpgProgram) {
        RecordingScheduler.scheduleReminder(getApplication(), channel.displayName, program)
    }

    fun toggleHidden(channel: LiveChannel) {
        viewModelScope.launch { playerPrefs.toggleHidden(channel.url) }
    }

    fun hideFromHome(key: String) {
        viewModelScope.launch { playerPrefs.toggleHiddenTitle(key) }
    }

    fun showHiddenTitlesAgain() {
        viewModelScope.launch { playerPrefs.clearHiddenTitles() }
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
                name = name.ifBlank { playlistNameFor(server) },
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
                name = name.ifBlank { playlistNameFor(server) },
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

    /**
     * A playlist nobody named is named after its server, not after its
     * protocol. Onboarding stopped asking for a name and no longer asks for a
     * URL either, so every playlist came out called "Xtream playlist" — a
     * heading that repeated the word beside it and told the viewer nothing,
     * above a subtitle that already read "Xtream · pro.example.online".
     */
    private fun playlistNameFor(server: String): String =
        server.substringAfter("://").substringBefore('/').substringBefore(':')
            .ifBlank { "Xtream playlist" }

    private fun addSource(source: PlaylistSource, onSuccess: () -> Unit) =
        saveSource(source, onSuccess, existing = false)

    private fun saveSource(source: PlaylistSource, onSuccess: () -> Unit, existing: Boolean) {
        addState = AddState.Loading("Downloading channels, movies and series…")
        viewModelScope.launch {
            // The guide starts NOW, beside the catalog, not after it: the
            // manifest names its packs without needing the catalog or the
            // credentials, and the two downloads share nothing but
            // bandwidth. By the time the library lands, the first pack —
            // which carries most of the bindings — usually has too.
            val epgJob = launch {
                runCatching {
                    repo.loadEpg(sourceHint = source)
                }
            }
            val outcome =
                if (existing) repo.validateAndUpdate(source) else repo.validateAndAdd(source)
            outcome.fold(
                onSuccess = {
                    // Setup finishes with everything the first screen needs —
                    // but "everything" is the FIRST pack, not the whole fold:
                    // the guide publishes progressively and the remaining
                    // packs keep landing behind the open app. The timeout
                    // covers a guide that can't load at all; setup must not
                    // hang on it, the app retries EPG on its own cycle.
                    addState = AddState.Loading("Downloading the TV guide…")
                    kotlinx.coroutines.withTimeoutOrNull(45_000) {
                        repo.epg.first { it is ContentRepository.EpgState.Ready }
                    }
                    addState = AddState.Idle
                    onSuccess()
                },
                onFailure = { e ->
                    // A login that failed must not keep downloading a guide
                    // for it.
                    epgJob.cancel()
                    addState = AddState.Error(e.userMessage("Could not load the playlist"))
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
        MutableStateFlow<com.agoro.tv.data.XtreamClient.AccountInfo?>(null)
    val accountInfo: StateFlow<com.agoro.tv.data.XtreamClient.AccountInfo?> = _accountInfo

    /** Starts true so the drawer hint never flashes for installs that saw it. */
    val menuHintSeen: StateFlow<Boolean> = playerPrefs.menuHintSeen
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun markMenuHintSeen() {
        viewModelScope.launch { playerPrefs.setMenuHintSeen() }
    }

    /**
     * The preview is on, and yields to a recording. "Connections" meter
     * simultaneous STREAMS, not logins — and while the guide is open nothing
     * else is streaming, so even a single-stream plan has its slot free; the
     * guide releases the preview before fullscreen opens — and the tune that
     * follows waits for the panel to agree, see [awaitLiveSlotAfterHandover].
     * This held up against a real 1-connection line. The one thing that
     * genuinely occupies the slot is an active recording, so the preview
     * stands down for it —
     * unless the plan has a second connection to spare.
     *
     * This was Auto/On/Off. Off was a preference for a thing that costs
     * nothing when it is free and stops on its own when it isn't, and On was
     * the same as Auto except that it would take a recording's only stream —
     * an option whose whole content was "break my recording".
     */
    val guidePreview: StateFlow<Boolean> =
        combine(
            accountInfo,
            com.agoro.tv.recording.RecordingManager.active,
        ) { account, recording ->
            recording == null || (account?.maxConnections ?: 1) >= 2
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun refreshAccountInfo() {
        viewModelScope.launch { _accountInfo.value = repo.accountInfo() }
    }

    /**
     * Waits for the provider to free a live slot before a reconnect asks for
     * one, on a line that caps concurrent streams at one or two. When a live
     * stream drops, the panel keeps counting the just-ended slot as open for
     * many seconds, so an immediate reconnect asks for a second connection
     * the line does not allow and is refused (403). Polling `active_cons` is
     * an API call, not a stream, so it does not itself use the slot — the app
     * can watch the count fall and reconnect the instant it does, which is
     * both faster than a fixed wait when the panel releases quickly and more
     * patient when it does not.
     *
     * Returns true when the wait was taken (reconnect at once); false when
     * the plan reports room to spare (or no cap at all), leaving the caller's
     * own backoff to apply. Refreshes [accountInfo] as it goes, so the
     * Settings meter counts down with it.
     */
    suspend fun awaitFreeLiveSlot(): Boolean = pollForFreeLiveSlot(AWAIT_SLOT_TIMEOUT_MS)

    /**
     * The poll itself, with the deadline the caller can afford. A reconnect
     * can wait a long time — nothing is on screen but an error it is trying to
     * avoid — while a viewer who has just pressed OK cannot.
     */
    private suspend fun pollForFreeLiveSlot(timeoutMs: Long): Boolean {
        val max = accountInfo.value?.maxConnections ?: return false
        if (max > CONNECTION_CAP_TO_GATE) return false
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val info = repo.accountInfo() ?: return true
            _accountInfo.value = info
            if ((info.activeConnections ?: 0) < (info.maxConnections ?: max)) return true
            kotlinx.coroutines.delay(AWAIT_SLOT_POLL_MS)
        }
        return true
    }

    /** When the guide preview last gave a live connection back. See [noteLiveSlotHandover]. */
    @Volatile
    private var slotHandoverAtMs = 0L

    /**
     * The guide preview has released its stream and a tune is expected to
     * follow. The client has let go, but a capped panel keeps counting the
     * slot for seconds afterwards — see [awaitLiveSlotAfterHandover].
     */
    fun noteLiveSlotHandover() {
        slotHandoverAtMs = android.os.SystemClock.elapsedRealtime()
    }

    /**
     * Waits, once, for the slot a just-released preview still occupies on the
     * panel's books before the player asks for it.
     *
     * The preview already stands down before fullscreen opens, so the two are
     * never deliberately open at once; this covers the gap that ordering
     * cannot close, which is the panel's own lag in freeing what the app has
     * already dropped. Without it the tune the viewer actually asked for is
     * the one refused, and the recovery is a reconnect ladder unwinding on
     * screen. Costs nothing on an uncapped line, or on any tune no preview
     * preceded: the stamp is consumed here, so a zap that follows does not
     * wait on a connection the player itself is holding.
     */
    suspend fun awaitLiveSlotAfterHandover() {
        val handoverAt = slotHandoverAtMs
        if (handoverAt == 0L) return
        slotHandoverAtMs = 0L
        val since = android.os.SystemClock.elapsedRealtime() - handoverAt
        if (since > SLOT_HANDOVER_WINDOW_MS) return
        pollForFreeLiveSlot(SLOT_HANDOVER_TIMEOUT_MS)
    }

    fun selectSource(id: String) = viewModelScope.launch { repo.selectSource(id) }

    fun removeSource(id: String) = viewModelScope.launch { repo.removeSource(id) }


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

    /** The guide grid's read — see [ContentRepository.programsIn] for why it is separate. */
    fun programsIn(channel: LiveChannel, fromMs: Long, toMs: Long): List<EpgProgram> =
        repo.programsIn(channel, fromMs, toMs)

    /**
     * Puts the hours [fromMs, toMs] in memory. The guide grid calls this when
     * the day it is showing changes; everything else rides the now-window the
     * repository keeps loaded.
     */
    suspend fun ensureGuideWindow(fromMs: Long, toMs: Long) = repo.ensureGuideWindow(fromMs, toMs)

    /** Bumped whenever the resident guide window changes; see [ensureGuideWindow]. */
    val guideWindowRevision: StateFlow<Int> = repo.guideWindowRevision

    /** The synopsis for one programme, read when it is the one on screen. */
    suspend fun descriptionFor(programId: String): String? = repo.descriptionFor(programId)

    /** One channel's schedule, synopses included — for the schedule sheet. */
    suspend fun scheduleFor(channel: LiveChannel, fromMs: Long, toMs: Long): List<EpgProgram> =
        repo.scheduleFor(channel, fromMs, toMs)

    /** Latest learned real tiers, for synchronous consumers (search). */
    private val knownQualitiesNow: StateFlow<Map<String, String>> =
        playerPrefs.knownQualities.stateIn(
            viewModelScope, SharingStarted.Eagerly, emptyMap(),
        )

    /** The tier a stream was last seen to decode at ("4K", "FHD", …), or null if never watched. */
    fun knownTierOf(url: String): String? = knownQualitiesNow.value[url]

    /** Remember what a stream really decodes at, so lists stop repeating the name's lie. */
    fun recordDecodedQuality(url: String, height: Int) {
        val tier = com.agoro.tv.data.QualityTag.tierOf(height) ?: return
        // Already known at this tier: nothing to write, and — more to the
        // point — nothing to re-emit. A write here fans out to a JSON decode,
        // a prefs rewrite and a full re-sort of displayChannels, so the
        // cheapest version of that is the one that never starts.
        if (knownQualitiesNow.value[url] == tier) return
        viewModelScope.launch { playerPrefs.setKnownQuality(url, tier) }
    }

    /** Latest learned HDR flavours, for the synchronous read the engine needs. */
    private val knownHdrNow: StateFlow<Map<String, String>> =
        playerPrefs.knownHdr.stateIn(
            viewModelScope, SharingStarted.Eagerly, emptyMap(),
        )

    /** The HDR flavour a stream was last seen to decode with, or null if SDR or never watched. */
    fun knownHdrOf(url: String): com.agoro.tv.player.HdrType? =
        com.agoro.tv.player.HdrType.byName(knownHdrNow.value[url])

    /**
     * Remember whether a stream decodes HDR, so its next visit opens straight
     * onto the tunnelled decoder rather than switching to it after the first
     * frame. Unlike [recordDecodedQuality] this records SDR too — a channel
     * the provider has moved off HDR must stop claiming the tunnel.
     */
    fun recordDecodedHdr(url: String, hdr: com.agoro.tv.player.HdrType?) {
        if (knownHdrNow.value[url] == hdr?.name) return
        viewModelScope.launch { playerPrefs.setKnownHdr(url, hdr?.name) }
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

    /**
     * The channel the player most recently tuned, set the moment it tunes —
     * no dwell. Recents wait eight seconds so a zap past twenty channels does
     * not record all twenty, but the guide's return landing needs the channel
     * the viewer actually left, including the one they backed out of after
     * two seconds because it was dead. Session-only by design.
     */
    private val _lastTunedUrl = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val lastTunedUrl: StateFlow<String?> = _lastTunedUrl
    fun noteTuned(url: String) {
        _lastTunedUrl.value = url
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
        //
        // One row per title per channel — its next airing. A rolling news
        // channel repeats "News Live Weekends" every half hour, and listing
        // each airing filled all twenty slots with one programme on one
        // channel before the search had reached the second channel.
        val now = System.currentTimeMillis()
        val programs = ArrayList<ProgramHit>()
        outer@ for (channel in b.channels) {
            if (channel.categoryId in lockedLive) continue
            val seenTitles = HashSet<String>()
            for (program in repo.programsFor(channel)) {
                if (program.endMs <= now) continue
                if (!seenTitles.add(program.title.lowercase())) continue
                if (searchRank(program.title, q, tokens) != null) {
                    programs.add(ProgramHit(channel, program))
                    if (programs.size >= 60) break@outer
                }
            }
        }
        programs.sortBy { it.program.startMs }
        if (programs.size > 20) programs.subList(20, programs.size).clear()

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

    // --- launch target --------------------------------------------------------

    private val _startTarget = MutableStateFlow(StartTarget.Pending)

    /**
     * Whether this launch opens on the player or on Home.
     *
     * Resolved once, before anything is drawn, so the shell can hold the boot
     * background rather than flashing Home on its way to a channel. An install
     * with nothing to resume settles this on the first read and waits for
     * nothing — only a launch that HAS a channel to reopen waits for the
     * catalogue, and then only until [RESUME_CATALOGUE_WAIT_MS].
     */
    val startTarget: StateFlow<StartTarget> = _startTarget

    private fun resolveStartTarget() {
        viewModelScope.launch {
            // Nothing here may leave the target Pending: the shell holds a
            // bare background until this answers, so a throw would be an
            // unrecoverable black screen rather than a lost convenience.
            val target = runCatching { decideStartTarget() }
                .getOrElse { StartTarget.Home }
            _startTarget.value = target
        }
    }

    private suspend fun decideStartTarget(): StartTarget {
        val url = withTimeoutOrNull(RESUME_CATALOGUE_WAIT_MS) {
            playerPrefs.resumeLiveChannel.first()
        }
        // Seeded whether or not anything is resumed, so the first clear from
        // the player is not mistaken for a no-op — see [rememberLiveResume].
        rememberedLiveUrl = url
        if (url == null) return StartTarget.Home

        // Any settled state, not Ready specifically: a playlist that comes
        // back Empty or Error has answered, and waiting out the full timeout
        // for an answer already given would hold the viewer on a black screen
        // for no reason. Only a genuinely slow load waits.
        val settled = withTimeoutOrNull(RESUME_CATALOGUE_WAIT_MS) {
            content.first { it !is ContentState.Loading }
        }
        // displayChannels, never bundle.channels. The raw catalogue still
        // contains the channels the viewer hid and — the reason this matters —
        // the ones a parental PIN is meant to be keeping from them. Locking in
        // this app is enforced by filtering, with no PIN gate inside the
        // player, so resuming out of the raw list would open a locked channel
        // full-screen on launch with nothing asked. It is also the list every
        // other route into the player uses, so a resumed launch zaps through
        // the same lineup rather than a private one full of SD/HD duplicates.
        // Null when the visible list never filled — which is NOT the same as
        // the channel being absent from it, and must not be read as one.
        val channels = if (settled is ContentState.Ready) {
            withTimeoutOrNull(RESUME_FILTER_WAIT_MS) {
                displayChannels.first { it.isNotEmpty() }
            }
        } else null

        val index = channels?.indexAnswering(url)
        return when (resumeOutcome(settled, index)) {
            ResumeOutcome.OpenPlayer -> {
                playChannels(channels.orEmpty(), index ?: 0)
                StartTarget.Player
            }
            ResumeOutcome.ForgetAndOpenHome -> {
                // The catalogue answered and the channel is not in it, so the
                // url is dead rather than merely unreachable. Forget it, or
                // every launch from here pays this wait again and lands on
                // Home anyway.
                rememberLiveResume(null)
                StartTarget.Home
            }
            // Kept, not forgotten: the catalogue never arrived, which says
            // nothing about whether the channel is still there.
            ResumeOutcome.OpenHome -> StartTarget.Home
        }
    }

    /**
     * Remember [url] as the channel to reopen on the next cold start, or
     * forget whatever was remembered when it is null. See
     * PlayerPrefs.resumeLiveChannel for what does and does not qualify.
     */
    fun rememberLiveResume(url: String?) {
        // Writing what is already written is a whole DataStore file rewrite
        // that re-emits the Preferences object to every collector in the app.
        // The clear in particular is fired on every index change of a series
        // binge, and all but the first of those say nothing new.
        if (url == rememberedLiveUrl) return
        rememberedLiveUrl = url
        viewModelScope.launch { playerPrefs.setResumeLiveChannel(url) }
    }

    /** Last value handed to [rememberLiveResume], to keep it from re-writing it. */
    @Volatile
    private var rememberedLiveUrl: String? = null

    // --- playback -------------------------------------------------------------

    fun playChannels(channels: List<LiveChannel>, startIndex: Int) {
        playback = PlaybackRequest(
            items = LiveItems(channels),
            startIndex = startIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0)),
            isLive = true,
        )
    }

    /**
     * A channel list seen as playable items, mapped on read.
     *
     * OK on a channel used to map the whole list it came from into items
     * before anything navigated — and from "All channels" that list is every
     * channel the playlist has, thousands of them, each read through
     * [LiveChannel.displayName], which is six regex passes the first time.
     * A pause of most of a second between the press and the picture, spent
     * building a playlist the player reads one entry of. The player asks by
     * index, so that is when an entry is built; the rest never are unless
     * the viewer actually walks to them. An entry once built is kept, so a
     * walk that does happen — a digit tune searching the list for a channel
     * — costs what the old eager map did, once, and nothing after.
     *
     * Equality is the underlying channels', never an element walk: the
     * request lives in a Compose state that compares old and new on every
     * assignment, and a structural compare of two lazy views would be the
     * full map twice over — the exact cost this exists to avoid.
     */
    private class LiveItems(private val channels: List<LiveChannel>) : AbstractList<PlayableItem>() {
        private val built = arrayOfNulls<PlayableItem>(channels.size)

        override val size: Int get() = channels.size

        override fun get(index: Int): PlayableItem = built[index] ?: channels[index].let {
            PlayableItem(
                url = it.url,
                title = it.displayName,
                subtitle = "Live",
                artwork = it.logo,
                channelId = it.id,
                recordUrl = it.recordUrl,
                fallbackUrls = it.fallbackUrls,
            )
        }.also { built[index] = it }

        override fun equals(other: Any?): Boolean =
            if (other is LiveItems) channels == other.channels else super.equals(other)

        override fun hashCode(): Int = channels.hashCode()
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
private fun foldForSearch(text: String): String = com.agoro.tv.data.EpgMatcher.fold(text)

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

/**
 * Where a launch lands. [Pending] is the brief moment before the answer is
 * known, and the shell shows the boot background for it rather than guessing.
 */
enum class StartTarget { Pending, Home, Player }

/** What a launch does with a remembered channel; see [resumeOutcome]. */
enum class ResumeOutcome { OpenHome, OpenPlayer, ForgetAndOpenHome }

/**
 * Whether a remembered channel is resumed, and whether it is still worth
 * remembering, given how far the catalogue got and where the channel was found.
 *
 * The distinction that matters is between a channel the catalogue says is GONE
 * and one the catalogue never described. The first should be forgotten — kept,
 * it makes every later launch wait for a catalogue only to land on Home anyway.
 * The second must be kept: a playlist that failed to load, or a visible list
 * that had not finished filtering yet, says nothing about whether the channel
 * is still there. [index] is null for exactly that case — no list to look in —
 * as against -1, which is a list that was looked in and did not have it.
 */
fun resumeOutcome(settled: ContentState?, index: Int?): ResumeOutcome = when {
    settled !is ContentState.Ready -> ResumeOutcome.OpenHome
    index == null -> ResumeOutcome.OpenHome
    index < 0 -> ResumeOutcome.ForgetAndOpenHome
    else -> ResumeOutcome.OpenPlayer
}
