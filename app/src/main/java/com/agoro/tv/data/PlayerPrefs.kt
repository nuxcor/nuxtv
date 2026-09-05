package com.agoro.tv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

@kotlinx.serialization.Serializable
/**
 * A scheduled recording. Recording was removed on 2026-08-27 and nothing
 * creates one any more.
 *
 * Kept ONLY because it is a field in the backup format: a backup written by an
 * older build carries a `schedules` array, and deleting the type would make
 * those backups fail to restore rather than restore an empty list. The backup
 * path reads and writes that key directly, so the DataStore flow and the
 * add/remove helpers that used to sit here are gone — nothing read them once
 * scheduling was removed, and leaving them would have implied a feature.
 */
data class ScheduledRecording(
    val id: String,
    val channelName: String,
    val recordUrl: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
)

/**
 * Artwork borrowed from TMDB for a catalogue entry the provider shipped bare.
 * Both fields null is a recorded miss, not an unknown — see [PlayerPrefs.artwork].
 */
@kotlinx.serialization.Serializable
data class ArtEntry(
    val poster: String? = null,
    val backdrop: String? = null,
) {
    val isEmpty: Boolean get() = poster == null && backdrop == null

    companion object {
        val empty = ArtEntry()
    }
}

// Corruption resets these preferences instead of crash-looping the app —
// losing learned qualities and favorites to a power cut is recoverable;
// an app that dies on launch until its data is cleared is not.
private val Context.playerDataStore: DataStore<Preferences> by preferencesDataStore(
    // Pre-rename name, kept deliberately: this file holds favourites, resume
    // positions and every learned quality tier. A rename empties all of it.
    name = "nuxtv_player",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
        androidx.datastore.preferences.core.emptyPreferences()
    },
)

/**
 * How many recently-watched channels to keep. Deep enough to cover an evening
 * of flipping, short enough that the list still reads as "what I was just on"
 * rather than a history.
 */
const val RECENT_CHANNEL_LIMIT = 20

/** How long borrowed-art answers pool before they are written; see [PlayerPrefs.putArtwork]. */
private const val ARTWORK_FLUSH_MS = 2_000L

/** A screenful of answers is written without waiting out the pause. */
private const val ARTWORK_BATCH = 24

/** Player-related preferences: VOD resume positions, learned stream facts, viewer choices. */
/**
 * How many finished titles the watch history keeps.
 *
 * Deeper than the 200 resume positions on purpose: positions are a handful of
 * things in flight, while this is what "next episode" is read from, and one
 * box set watched to the end of season four is sixty entries of a single show.
 * Written only on completion, so its size costs nothing on the per-second save
 * path.
 */
private const val WATCHED_HISTORY = 2_000

/**
 * How many shows keep an episode count.
 *
 * One small entry per SHOW, not per episode, and read on every catalogue
 * build — so this is generous where the url-keyed maps have to be careful.
 * Newest kept, as everywhere else here.
 */
private const val SERIES_SIZE_CAP = 500

class PlayerPrefs(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val positionsKey = stringPreferencesKey("resume_positions")
    private val favoritesKey = stringPreferencesKey("favorite_channels")
    private val schedulesKey = stringPreferencesKey("scheduled_recordings")
    private val hiddenKey = stringPreferencesKey("hidden_channels")
    private val hiddenTitlesKey = stringPreferencesKey("hidden_titles")
    private val tmdbKeyKey = stringPreferencesKey("tmdb_api_key")
    private val pinKey = stringPreferencesKey("parental_pin")
    private val durationsKey = stringPreferencesKey("resume_durations")

    /**
     * url → when it was finished. Its own entry, like [durationsKey], so an
     * install predating it decodes its positions exactly as before and simply
     * has no watch history yet.
     */
    private val watchedKey = stringPreferencesKey("watched_at")
    private val videoQualityKey = stringPreferencesKey("video_quality")
    private val recentChannelsKey = stringPreferencesKey("recent_channels")
    private val aspectModeKey = stringPreferencesKey("aspect_mode")
    private val aspectOverridesKey = stringPreferencesKey("aspect_overrides")
    private val audioLangKey = stringPreferencesKey("preferred_audio_lang")
    private val subtitleLangKey = stringPreferencesKey("preferred_subtitle_lang")
    private val vodSpeedKey = stringPreferencesKey("vod_speed")
    private val keyHintsVersionKey = stringPreferencesKey("key_hints_version")
    private val menuHintSeenKey = stringPreferencesKey("menu_hint_seen")
    private val knownQualitiesKey = stringPreferencesKey("known_qualities")
    private val knownHdrKey = stringPreferencesKey("known_hdr")
    private val liveTsMigratedKey = stringPreferencesKey("live_ts_migrated")
    private val episodeOriginsKey = stringPreferencesKey("episode_origins")
    private val seriesSizesKey = stringPreferencesKey("series_sizes")
    private val artworkKey = stringPreferencesKey("tmdb_artwork")
    private val resumeLiveChannelKey = stringPreferencesKey("resume_live_channel")

    /**
     * One decode per distinct blob, shared by every collector.
     *
     * DataStore re-emits the whole Preferences object to every collector on
     * any write, and each JSON-backed flow below used to decode its own blob
     * from scratch on each of those — so one artwork lookup landing made
     * twelve collectors re-parse twelve blobs, eleven of them unchanged. A
     * slot remembers the last raw string it saw and the value it decoded
     * from it; an unchanged blob is the same String instance in the next
     * Preferences map, so the check is an identity compare and the decode
     * happens exactly once per actual change. Decoding is the only work
     * these flows do, so this is what [flowOn] was paying for.
     */
    private class JsonSlot<T : Any>(private val empty: T, private val decode: (String) -> T) {
        @Volatile private var lastRaw: String? = null
        @Volatile private var lastValue: T = empty

        fun read(raw: String?): T {
            if (raw == null) return empty
            val seen = lastRaw
            if (seen != null && (seen === raw || seen == raw)) return lastValue
            // Two collectors can race the first read of a new blob and both
            // decode it; they agree on the answer, so last writer wins.
            val value = runCatching { decode(raw) }.getOrNull() ?: empty
            lastValue = value
            lastRaw = raw
            return value
        }
    }

    private val knownQualitiesSlot = JsonSlot<Map<String, String>>(emptyMap()) { json.decodeFromString(it) }
    private val knownHdrSlot = JsonSlot<Map<String, String>>(emptyMap()) { json.decodeFromString(it) }
    private val episodeOriginsSlot = JsonSlot<Map<String, String>>(emptyMap()) { json.decodeFromString(it) }
    private val seriesSizesSlot = JsonSlot<Map<String, Int>>(emptyMap()) { json.decodeFromString(it) }
    private val artworkSlot = JsonSlot<Map<String, ArtEntry>>(emptyMap()) { json.decodeFromString(it) }
    private val resumePositionsSlot = JsonSlot<Map<String, Long>>(emptyMap()) { json.decodeFromString(it) }
    private val resumeDurationsSlot = JsonSlot<Map<String, Long>>(emptyMap()) { json.decodeFromString(it) }
    private val watchedSlot = JsonSlot<Map<String, Long>>(emptyMap()) { json.decodeFromString(it) }
    private val favoritesSlot = JsonSlot<Set<String>>(emptySet()) { json.decodeFromString(it) }
    private val recentChannelsSlot = JsonSlot<List<String>>(emptyList()) { json.decodeFromString(it) }
    private val hiddenSlot = JsonSlot<Set<String>>(emptySet()) { json.decodeFromString(it) }
    private val hiddenTitlesSlot = JsonSlot<Set<String>>(emptySet()) { json.decodeFromString(it) }

    /**
     * Live playback URLs changed from the panel's .m3u8 endpoint to the raw
     * .ts mux (the .m3u8 re-wrap is what capped picture quality). Everything
     * URL-keyed — favorites, hidden, recents, learned qualities, aspect
     * overrides — would have been orphaned by that; re-key it once.
     */
    suspend fun migrateLiveUrlsToTs() {
        context.playerDataStore.edit { prefs ->
            if (prefs[liveTsMigratedKey] != null) return@edit
            prefs[liveTsMigratedKey] = "1"
            val live = Regex("""^(.+/live/.+)\.m3u8$""")
            fun mig(url: String) = live.matchEntire(url)?.let { "${it.groupValues[1]}.ts" } ?: url
            for (key in listOf(favoritesKey, hiddenKey)) {
                prefs[key]?.let { raw ->
                    runCatching { json.decodeFromString<Set<String>>(raw) }.getOrNull()?.let {
                        prefs[key] = json.encodeToString(it.map(::mig).toSet())
                    }
                }
            }
            prefs[recentChannelsKey]?.let { raw ->
                runCatching { json.decodeFromString<List<String>>(raw) }.getOrNull()?.let {
                    prefs[recentChannelsKey] = json.encodeToString(it.map(::mig))
                }
            }
            prefs[knownQualitiesKey]?.let { raw ->
                runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrNull()?.let {
                    prefs[knownQualitiesKey] = json.encodeToString(it.mapKeys { (k, _) -> mig(k) })
                }
            }
            prefs[aspectOverridesKey]?.let { raw ->
                runCatching { json.decodeFromString<Map<String, Int>>(raw) }.getOrNull()?.let {
                    prefs[aspectOverridesKey] = json.encodeToString(it.mapKeys { (k, _) -> mig(k) })
                }
            }
        }
    }

    /**
     * Real decoded quality per stream URL, learned during playback. Providers
     * routinely mislabel streams ("4K" names decoding at 720p); once a channel
     * has actually been played, its true tier replaces the advertised tag in
     * every list. Newest 500 kept.
     */
    val knownQualities: Flow<Map<String, String>> = context.playerDataStore.data.map { prefs ->
        knownQualitiesSlot.read(prefs[knownQualitiesKey])
    }.flowOn(Dispatchers.Default)

    suspend fun setKnownQuality(url: String, tier: String) {
        context.playerDataStore.edit { prefs ->
            val map = prefs[knownQualitiesKey]?.let {
                runCatching { json.decodeFromString<LinkedHashMap<String, String>>(it) }.getOrNull()
            } ?: LinkedHashMap()
            if (map[url] == tier) return@edit
            map.remove(url) // re-inserting moves the entry to the newest slot
            map[url] = tier
            val trimmed =
                if (map.size > 500) map.entries.drop(map.size - 500).associate { it.toPair() }
                else map
            prefs[knownQualitiesKey] = json.encodeToString(trimmed)
        }
    }

    /**
     * Real decoded HDR flavour per stream URL ("HDR10", "HLG", …), learned
     * during playback, absent for streams last seen in SDR.
     *
     * Kept apart from [knownQualities] rather than folded into its tier
     * because the tier is a height and HDR is not: a 1080p HLG channel — the
     * commonest shape of HDR in IPTV — has nothing a resolution tier can
     * record. What it buys is the first frame: a stream already known to be
     * HDR opens on the tunnelled decoder instead of re-initialising onto it
     * once the format arrives, which is a black beat the viewer sees on every
     * first visit of every session. Newest 500 kept, as with qualities.
     */
    val knownHdr: Flow<Map<String, String>> = context.playerDataStore.data.map { prefs ->
        knownHdrSlot.read(prefs[knownHdrKey])
    }.flowOn(Dispatchers.Default)

    /** @param type null for a stream that decoded SDR, which drops any stale entry. */
    suspend fun setKnownHdr(url: String, type: String?) {
        context.playerDataStore.edit { prefs ->
            val map = prefs[knownHdrKey]?.let {
                runCatching { json.decodeFromString<LinkedHashMap<String, String>>(it) }.getOrNull()
            } ?: LinkedHashMap()
            if (map[url] == type) return@edit
            map.remove(url) // re-inserting moves the entry to the newest slot
            if (type != null) map[url] = type
            val trimmed =
                if (map.size > 500) map.entries.drop(map.size - 500).associate { it.toPair() }
                else map
            prefs[knownHdrKey] = json.encodeToString(trimmed)
        }
    }

    /**
     * Episode stream URL → series id. Xtream episode URLs don't encode their
     * series, so Continue Watching couldn't climb from a resume position back
     * to a Series card without this. Written whenever episodes are handed to
     * the player; newest 500 kept.
     */
    val episodeOrigins: Flow<Map<String, String>> = context.playerDataStore.data.map { prefs ->
        episodeOriginsSlot.read(prefs[episodeOriginsKey])
    }.flowOn(Dispatchers.Default)

    /**
     * Series id → how many episodes the show had when it was last played.
     *
     * The one thing Continue watching could not know: whether a show with a
     * finished episode has anything LEFT. Episode lists are fetched per show
     * on its own page, so the row — which is built off two url-keyed maps —
     * had no count to compare its watch marks against, and a show watched
     * through to its finale stayed on the shelf forever.
     *
     * Written from the playlist the player is handed, which is the whole show
     * ([recordSeriesPlaylist]). Absent for a show not played since that became
     * true, and absence means "don't know", never "nothing left": the row
     * keeps such a show exactly as it always did. A new season re-writes the
     * count the next time the show is played.
     */
    val seriesSizes: Flow<Map<String, Int>> = context.playerDataStore.data.map { prefs ->
        seriesSizesSlot.read(prefs[seriesSizesKey])
    }.flowOn(Dispatchers.Default)

    /**
     * Records the playlist a show was handed to the player as: where each
     * episode url points, and how many of them there were.
     *
     * One edit, because they are one fact written at one moment — the only
     * moment the app holds a show and its episode list together.
     */
    suspend fun recordSeriesPlaylist(seriesId: String, episodeUrls: List<String>) {
        // A show with no episodes is a list that failed to load, not a show
        // of length zero — and a recorded size of zero would read as "seen
        // out" and take the show off Continue watching. Nothing to record.
        if (episodeUrls.isEmpty()) return
        context.playerDataStore.edit { prefs ->
            val existing = prefs[episodeOriginsKey]?.let {
                runCatching { json.decodeFromString<LinkedHashMap<String, String>>(it) }.getOrNull()
            } ?: LinkedHashMap()
            prefs[episodeOriginsKey] =
                json.encodeToString(mergeEpisodeOrigins(existing, seriesId, episodeUrls))
            val sizes = prefs[seriesSizesKey]?.let {
                runCatching { json.decodeFromString<LinkedHashMap<String, Int>>(it) }.getOrNull()
            } ?: LinkedHashMap()
            sizes.remove(seriesId) // re-inserting moves the entry to the newest slot
            sizes[seriesId] = episodeUrls.size
            prefs[seriesSizesKey] = json.encodeToString(
                if (sizes.size > SERIES_SIZE_CAP) {
                    sizes.entries.drop(sizes.size - SERIES_SIZE_CAP).associate { it.toPair() }
                } else sizes,
            )
        }
    }

    // --- borrowed artwork -----------------------------------------------------

    /**
     * Catalogue id → the art TMDB had for it, for the many providers that ship
     * a library with no images at all. Persisted because the alternative is
     * re-running the same few hundred lookups on every cold start, and a miss
     * is stored too ([ArtEntry.empty]) so a title TMDB doesn't know is asked
     * about once, not forever. Newest 800 kept.
     */
    /**
     * Lookups answered but not yet written. Each answer used to be its own
     * DataStore edit — decode the 800-entry map, re-encode it, fsync — and a
     * screenful of bare cards produces up to 24 of them in a burst, during
     * exactly the scroll the art is meant to decorate. Now they pool here
     * and go to disk together, after a short pause or once the pool is a
     * screenful, whichever comes first. The flow above reads this too, so
     * nothing on screen waits for the write.
     */
    private val artworkPending = MutableStateFlow<Map<String, ArtEntry>>(emptyMap())
    private val artworkLock = Any()
    private var artworkFlush: Job? = null
    private val artworkFlushMutex = Mutex()
    private val artworkScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // flowOn, here and on every other JSON-backed flow below: DataStore
    // re-emits the WHOLE Preferences object to every collector on any write,
    // and MainViewModel collects these with stateIn(viewModelScope, ...) —
    // Dispatchers.Main.immediate. Without the hop, one artwork lookup landing
    // mid-scroll made artwork, resume positions, favorites, hidden, episode
    // origins, schedules and sources all re-parse their JSON on the main
    // thread, during exactly the scroll borrowed art exists to decorate.
    //
    // What is on disk plus what is waiting to go there: a lookup shows on
    // its card the moment it lands, while the write behind it is batched.
    val artwork: Flow<Map<String, ArtEntry>> = combine(
        context.playerDataStore.data.map { prefs -> artworkSlot.read(prefs[artworkKey]) },
        artworkPending,
    ) { stored, pending -> if (pending.isEmpty()) stored else stored + pending }
        .flowOn(Dispatchers.Default)

    suspend fun putArtwork(id: String, entry: ArtEntry) {
        val flushNow = synchronized(artworkLock) {
            artworkPending.value = artworkPending.value + (id to entry)
            val full = artworkPending.value.size >= ARTWORK_BATCH
            if (full) {
                artworkFlush?.cancel()
                artworkFlush = null
            } else if (artworkFlush == null) {
                artworkFlush = artworkScope.launch {
                    delay(ARTWORK_FLUSH_MS)
                    flushArtwork()
                }
            }
            full
        }
        if (flushNow) flushArtwork()
    }

    private suspend fun flushArtwork(): Unit = artworkFlushMutex.withLock {
        val batch = synchronized(artworkLock) {
            artworkFlush = null
            artworkPending.value
        }
        if (batch.isEmpty()) return@withLock
        context.playerDataStore.edit { prefs ->
            val map = prefs[artworkKey]?.let {
                runCatching { json.decodeFromString<LinkedHashMap<String, ArtEntry>>(it) }.getOrNull()
            } ?: LinkedHashMap()
            batch.forEach { (id, entry) ->
                map.remove(id) // re-inserting moves the entry to the newest slot
                map[id] = entry
            }
            val trimmed =
                if (map.size > 800) map.entries.drop(map.size - 800).associate { it.toPair() }
                else map
            prefs[artworkKey] = json.encodeToString(trimmed)
        }
        // Only what was written leaves the pool; an answer that arrived
        // during the edit is still waiting for the next one.
        synchronized(artworkLock) {
            artworkPending.value = artworkPending.value.filterNot { (id, entry) -> batch[id] === entry }
            if (artworkPending.value.isNotEmpty() && artworkFlush == null) {
                artworkFlush = artworkScope.launch {
                    delay(ARTWORK_FLUSH_MS)
                    flushArtwork()
                }
            }
        }
    }

    private suspend fun positions(): MutableMap<String, Long> =
        context.playerDataStore.data.first()[positionsKey]?.let {
            runCatching { json.decodeFromString<MutableMap<String, Long>>(it) }.getOrNull()
        } ?: mutableMapOf()

    suspend fun resumePositionFor(url: String): Long = positions()[url] ?: 0L

    /** url → position, for Continue Watching rows. */
    val resumePositions: Flow<Map<String, Long>> = context.playerDataStore.data.map { prefs ->
        resumePositionsSlot.read(prefs[positionsKey])
    }.flowOn(Dispatchers.Default)

    /**
     * url → total duration, written alongside the position. Kept in its own
     * entry rather than folded into [resumePositions] so existing installs
     * don't fail to decode their saved positions and lose them; entries
     * predating this simply have no duration and show no progress bar.
     */
    val resumeDurations: Flow<Map<String, Long>> = context.playerDataStore.data.map { prefs ->
        resumeDurationsSlot.read(prefs[durationsKey])
    }.flowOn(Dispatchers.Default)

    /**
     * url → when it was watched to the end.
     *
     * The app used to record only where you were PART-WAY through: reaching
     * the end deleted the entry and nothing took its place. For a film that is
     * right — it is finished, and it leaves Continue watching. For a series it
     * threw away the only thing the next visit needed: finish an episode and
     * the show vanished from Continue watching, the detail page's button fell
     * back from "Resume S3E7" to "Play" (meaning S1E1), and the season strip
     * reset to Season 1. Stopping at an episode boundary — the ordinary way to
     * stop watching a series — was the one way to make the app forget you.
     *
     * Written for every VOD url, films included; only the series page and
     * Continue watching read it today.
     */
    val watchedAt: Flow<Map<String, Long>> = context.playerDataStore.data
        // DataStore re-emits the whole preference set on ANY write — a
        // favourite, a hidden title, an artwork batch, a channel tier learned
        // mid-playback. This is the longest map here (up to WATCHED_HISTORY),
        // and decoding it for a write that did not touch it, then walking it
        // again to see it had not changed, is work a 2GB box does not need.
        // The raw string is the cheap thing to compare.
        .map { prefs -> prefs[watchedKey] }
        .distinctUntilChanged()
        .map { watchedSlot.read(it) }
        .flowOn(Dispatchers.Default)

    /** Saves (or clears, when near the end) a VOD resume position. Keeps the newest 200. */
    suspend fun saveResumePosition(url: String, positionMs: Long, durationMs: Long) {
        context.playerDataStore.edit { prefs ->
            // Read-modify-write inside a single edit so concurrent saves can't
            // clobber each other.
            val map = prefs[positionsKey]?.let {
                runCatching { json.decodeFromString<LinkedHashMap<String, Long>>(it) }.getOrNull()
            } ?: LinkedHashMap()
            val nearEnd = durationMs > 0 && positionMs > durationMs * 95 / 100
            map.remove(url) // re-inserting moves the entry to the newest slot
            if (positionMs >= 30_000 && !nearEnd) map[url] = positionMs
            // Only when it actually finishes. This runs every few seconds of
            // playback and the watch history is the one map here that wants to
            // be long, so it is decoded and re-encoded on completion alone —
            // never on the ordinary position tick.
            if (nearEnd) {
                val watched = prefs[watchedKey]?.let {
                    runCatching { json.decodeFromString<LinkedHashMap<String, Long>>(it) }.getOrNull()
                } ?: LinkedHashMap()
                watched.remove(url)
                watched[url] = System.currentTimeMillis()
                // Deeper than the 200 positions: this is what "next episode"
                // is read from, and a box set watched to the end of season
                // four is 60 entries of one show. Newest kept, like positions.
                val trimmedWatched =
                    if (watched.size > WATCHED_HISTORY) {
                        watched.entries.drop(watched.size - WATCHED_HISTORY)
                            .associate { it.toPair() }
                    } else watched
                prefs[watchedKey] = json.encodeToString(trimmedWatched)
            }
            val trimmed =
                if (map.size > 200) map.entries.drop(map.size - 200).associate { it.toPair() } else map
            prefs[positionsKey] = json.encodeToString(trimmed)

            // Durations track the same key set so the two never drift.
            val durations = prefs[durationsKey]?.let {
                runCatching { json.decodeFromString<MutableMap<String, Long>>(it) }.getOrNull()
            } ?: mutableMapOf()
            if (durationMs > 0 && trimmed.containsKey(url)) durations[url] = durationMs
            durations.keys.retainAll(trimmed.keys)
            prefs[durationsKey] = json.encodeToString(durations)
        }
    }

    /**
     * Forgets one title's place, so "Remove from Continue watching" actually
     * removes it. Durations follow the positions they belong to; leaving an
     * orphan behind would resurrect a progress bar if the same URL came back.
     *
     * The watch mark goes too, and that is why this no longer gives up when
     * there are no positions to clear: a show is now kept in Continue watching
     * by a FINISHED episode as much as by a part-watched one, and that show
     * has no position at all.
     */
    suspend fun clearResume(urls: Collection<String>) {
        if (urls.isEmpty()) return
        context.playerDataStore.edit { prefs ->
            val map = prefs[positionsKey]?.let {
                runCatching { json.decodeFromString<LinkedHashMap<String, Long>>(it) }.getOrNull()
            } ?: LinkedHashMap()
            val drop = urls.toSet()
            val forgotPosition = map.keys.removeAll(drop)
            if (forgotPosition) {
                prefs[positionsKey] = json.encodeToString(map)
                prefs[durationsKey]?.let { raw ->
                    runCatching { json.decodeFromString<MutableMap<String, Long>>(raw) }.getOrNull()
                        ?.let { durations ->
                            durations.keys.retainAll(map.keys)
                            prefs[durationsKey] = json.encodeToString(durations)
                        }
                }
            }
            // A show kept in the row by a finished episode has no position to
            // clear, so this is the half that actually removes it.
            prefs[watchedKey]?.let { raw ->
                runCatching { json.decodeFromString<LinkedHashMap<String, Long>>(raw) }.getOrNull()
                    ?.let { watched ->
                        if (watched.keys.removeAll(drop)) {
                            prefs[watchedKey] = json.encodeToString(watched)
                        }
                    }
            }
        }
    }

    // --- favorites (keyed by stream URL, stable across playlist reloads) ------

    val favorites: Flow<Set<String>> = context.playerDataStore.data.map { prefs ->
        favoritesSlot.read(prefs[favoritesKey])
    }.flowOn(Dispatchers.Default)

    suspend fun toggleFavorite(channelUrl: String) {
        context.playerDataStore.edit { prefs ->
            val current = prefs[favoritesKey]?.let {
                runCatching { json.decodeFromString<Set<String>>(it) }.getOrNull()
            } ?: emptySet()
            val updated = if (channelUrl in current) current - channelUrl else current + channelUrl
            prefs[favoritesKey] = json.encodeToString(updated)
        }
    }

    // --- recently watched channels -------------------------------------------
    // Keyed by stream URL like favorites and hidden, so the list survives a
    // playlist reload that renumbers or re-ids everything. Newest first.

    val recentChannels: Flow<List<String>> = context.playerDataStore.data.map { prefs ->
        recentChannelsSlot.read(prefs[recentChannelsKey])
    }.flowOn(Dispatchers.Default)

    /**
     * Records a channel as watched, moving it to the front if it was already
     * there. Capped at [RECENT_CHANNEL_LIMIT]: this is a shortcut back to what
     * you were just watching, and a list longer than a screen stops being one.
     */
    suspend fun recordChannelVisit(channelUrl: String) {
        context.playerDataStore.edit { prefs ->
            val current = prefs[recentChannelsKey]?.let {
                runCatching { json.decodeFromString<List<String>>(it) }.getOrNull()
            } ?: emptyList()
            val updated = (listOf(channelUrl) + current.filterNot { it == channelUrl })
                .take(RECENT_CHANNEL_LIMIT)
            prefs[recentChannelsKey] = json.encodeToString(updated)
        }
    }

    suspend fun clearRecentChannels() {
        context.playerDataStore.edit { it[recentChannelsKey] = json.encodeToString(emptyList<String>()) }
    }

    /**
     * The live channel a cold start reopens on, or null to open Home.
     *
     * A television comes back on the channel it went off on. A film does not:
     * it is a thing you chose to sit down for, and starting one unbidden
     * because the box woke up is not resuming, it is interrupting. So this is
     * written only while a live channel is playing, and cleared the moment the
     * viewer leaves the player or plays anything that isn't live — it says
     * "was watching", not "watched", which is why it cannot just read
     * [recentChannels].
     *
     * Keyed by stream URL like [recentChannels] and favourites, and read back
     * through LiveChannel.answersTo for the same reason: the feed the viewer
     * tuned is frequently the one that later LOST a merge.
     */
    val resumeLiveChannel: Flow<String?> = context.playerDataStore.data.map { prefs ->
        prefs[resumeLiveChannelKey]
    }

    suspend fun setResumeLiveChannel(url: String?) {
        context.playerDataStore.edit { prefs ->
            // Removed rather than blanked: an empty string is a value, and one
            // the reader would then have to filter back out on every read.
            if (url == null) prefs.remove(resumeLiveChannelKey)
            else prefs[resumeLiveChannelKey] = url
        }
    }

    // --- scheduled recordings -------------------------------------------------

    // --- hidden channels ------------------------------------------------------

    val hidden: Flow<Set<String>> = context.playerDataStore.data.map { prefs ->
        hiddenSlot.read(prefs[hiddenKey])
    }.flowOn(Dispatchers.Default)

    suspend fun toggleHidden(channelUrl: String) {
        context.playerDataStore.edit { prefs ->
            val current = prefs[hiddenKey]?.let {
                runCatching { json.decodeFromString<Set<String>>(it) }.getOrNull()
            } ?: emptySet()
            val updated = if (channelUrl in current) current - channelUrl else current + channelUrl
            prefs[hiddenKey] = json.encodeToString(updated)
        }
    }

    // --- titles hidden from Home ----------------------------------------------
    //
    // Kept apart from [hidden], which is channels. That set is what the channel
    // manager enumerates to offer an unhide, so a movie url dropped into it
    // would be hidden with no screen able to list it again.
    //
    // Keyed "m:<id>" / "s:<id>" rather than by url: a movie's url carries the
    // stream id and the provider re-issues those, and a title that came back
    // under a new id would quietly un-hide itself.

    val hiddenTitles: Flow<Set<String>> = context.playerDataStore.data.map { prefs ->
        hiddenTitlesSlot.read(prefs[hiddenTitlesKey])
    }.flowOn(Dispatchers.Default)

    suspend fun toggleHiddenTitle(key: String) {
        context.playerDataStore.edit { prefs ->
            val current = prefs[hiddenTitlesKey]?.let {
                runCatching { json.decodeFromString<Set<String>>(it) }.getOrNull()
            } ?: emptySet()
            prefs[hiddenTitlesKey] =
                json.encodeToString(if (key in current) current - key else current + key)
        }
    }

    suspend fun clearHiddenTitles() {
        context.playerDataStore.edit { prefs -> prefs.remove(hiddenTitlesKey) }
    }

    // --- TMDB key -------------------------------------------------------------

    /**
     * 0 = adapt to bandwidth, 1 = always the top rung. Adapting is the default
     * and is not a Settings choice: pinning the top rung on a line that can't
     * carry it doesn't look sharper, it breaks up, and no viewer can be
     * expected to know which of those their connection is. The player's own
     * quality sheet still offers the pin for anyone who wants it, and this
     * remembers that pick — it lives next to the picture it changes.
     */
    val videoQuality: Flow<Int> = context.playerDataStore.data.map { prefs ->
        prefs[videoQualityKey]?.toIntOrNull() ?: 0
    }

    suspend fun setVideoQuality(mode: Int) {
        context.playerDataStore.edit { it[videoQualityKey] = mode.toString() }
    }

    // --- player picture/audio memory ------------------------------------------
    // What the viewer sets in the player's options sheet used to evaporate on
    // exit; these keep it. Aspect is a global default plus per-channel
    // overrides (a 4:3 archive channel wants Stretch; nothing else does).

    /** 0 = fit, 1 = stretch, 2 = zoom — the default for streams with no override. */
    val aspectMode: Flow<Int> = context.playerDataStore.data.map { prefs ->
        prefs[aspectModeKey]?.toIntOrNull() ?: 0
    }

    suspend fun setAspectMode(mode: Int) {
        context.playerDataStore.edit { it[aspectModeKey] = mode.toString() }
    }

    /** The aspect mode for [url]: its override if set, else the global default. */
    suspend fun aspectModeFor(url: String): Int {
        val prefs = context.playerDataStore.data.first()
        val overrides = prefs[aspectOverridesKey]?.let {
            runCatching { json.decodeFromString<Map<String, Int>>(it) }.getOrNull()
        } ?: emptyMap()
        return overrides[url] ?: prefs[aspectModeKey]?.toIntOrNull() ?: 0
    }

    /** Remembers an aspect override per stream URL. Keeps the newest 200. */
    suspend fun setAspectOverride(url: String, mode: Int) {
        context.playerDataStore.edit { prefs ->
            val map = prefs[aspectOverridesKey]?.let {
                runCatching { json.decodeFromString<LinkedHashMap<String, Int>>(it) }.getOrNull()
            } ?: LinkedHashMap()
            map.remove(url) // re-inserting moves the entry to the newest slot
            map[url] = mode
            val trimmed =
                if (map.size > 200) map.entries.drop(map.size - 200).associate { it.toPair() }
                else map
            prefs[aspectOverridesKey] = json.encodeToString(trimmed)
        }
    }

    /** Language code or name from the last audio track the viewer picked. */
    val preferredAudioLanguage: Flow<String?> = context.playerDataStore.data.map { prefs ->
        prefs[audioLangKey]?.takeIf { it.isNotBlank() }
    }

    suspend fun setPreferredAudioLanguage(language: String?) {
        context.playerDataStore.edit { prefs ->
            if (language.isNullOrBlank()) prefs.remove(audioLangKey)
            else prefs[audioLangKey] = language
        }
    }

    /** Preferred subtitle language; null means "off unless asked". */
    val preferredSubtitleLanguage: Flow<String?> = context.playerDataStore.data.map { prefs ->
        prefs[subtitleLangKey]?.takeIf { it.isNotBlank() }
    }

    suspend fun setPreferredSubtitleLanguage(language: String?) {
        context.playerDataStore.edit { prefs ->
            if (language.isNullOrBlank()) prefs.remove(subtitleLangKey)
            else prefs[subtitleLangKey] = language
        }
    }

    /** Playback speed for VOD; live always plays at 1x. */
    val vodSpeed: Flow<Float> = context.playerDataStore.data.map { prefs ->
        prefs[vodSpeedKey]?.toFloatOrNull() ?: 1f
    }

    suspend fun setVodSpeed(speed: Float) {
        context.playerDataStore.edit { it[vodSpeedKey] = speed.toString() }
    }

    /**
     * The key-map generation whose banner hints this install has already been
     * shown. The player bumps its own constant when the key model changes, so
     * the hints re-teach once and then retire — across sessions, not per one.
     */
    val keyHintsVersion: Flow<Int> = context.playerDataStore.data.map { prefs ->
        prefs[keyHintsVersionKey]?.toIntOrNull() ?: 0
    }

    suspend fun setKeyHintsVersion(version: Int) {
        context.playerDataStore.edit { it[keyHintsVersionKey] = version.toString() }
    }

    /**
     * One-time teach for the summoned nav drawer: the rail is invisible until
     * called, so the first session says where it went — once, then retires.
     */
    val menuHintSeen: Flow<Boolean> = context.playerDataStore.data.map { prefs ->
        prefs[menuHintSeenKey] == "1"
    }

    suspend fun setMenuHintSeen() {
        context.playerDataStore.edit { it[menuHintSeenKey] = "1" }
    }

    val parentalPin: Flow<String?> = context.playerDataStore.data.map { prefs ->
        prefs[pinKey]?.takeIf { it.isNotBlank() }
    }

    suspend fun setParentalPin(pin: String?) {
        context.playerDataStore.edit { prefs ->
            if (pin.isNullOrBlank()) prefs.remove(pinKey) else prefs[pinKey] = pin.trim()
        }
    }


    // --- backup / restore -----------------------------------------------------

    @kotlinx.serialization.Serializable
    data class Backup(
        val favorites: Set<String> = emptySet(),
        val hidden: Set<String> = emptySet(),
        // Retained so backups written before the key was bundled still restore.
        // Nothing reads the restored value — the key comes from BuildConfig now.
        val tmdbKey: String? = null,
        val schedules: List<ScheduledRecording> = emptyList(),
        val sources: List<PlaylistSource> = emptyList(),
        // Defaulted so backups written before these existed still restore.
        val aspectMode: Int = 0,
        val aspectOverrides: Map<String, Int> = emptyMap(),
        val preferredAudioLang: String? = null,
        val preferredSubtitleLang: String? = null,
        val vodSpeed: Float = 1f,
    )

    suspend fun snapshot(sources: List<PlaylistSource>): String {
        val prefs = context.playerDataStore.data.first()
        val backup = Backup(
            favorites = prefs[favoritesKey]?.let {
                runCatching { json.decodeFromString<Set<String>>(it) }.getOrNull()
            } ?: emptySet(),
            hidden = prefs[hiddenKey]?.let {
                runCatching { json.decodeFromString<Set<String>>(it) }.getOrNull()
            } ?: emptySet(),
            tmdbKey = prefs[tmdbKeyKey],
            schedules = prefs[schedulesKey]?.let {
                runCatching { json.decodeFromString<List<ScheduledRecording>>(it) }.getOrNull()
            } ?: emptyList(),
            sources = sources,
            aspectMode = prefs[aspectModeKey]?.toIntOrNull() ?: 0,
            aspectOverrides = prefs[aspectOverridesKey]?.let {
                runCatching { json.decodeFromString<Map<String, Int>>(it) }.getOrNull()
            } ?: emptyMap(),
            preferredAudioLang = prefs[audioLangKey]?.takeIf { it.isNotBlank() },
            preferredSubtitleLang = prefs[subtitleLangKey]?.takeIf { it.isNotBlank() },
            vodSpeed = prefs[vodSpeedKey]?.toFloatOrNull() ?: 1f,
        )
        return json.encodeToString(backup)
    }

    /** Applies a backup's preference portion; returns its playlist sources. */
    suspend fun restore(text: String): List<PlaylistSource> {
        val backup = json.decodeFromString<Backup>(text)
        context.playerDataStore.edit { prefs ->
            prefs[favoritesKey] = json.encodeToString(backup.favorites)
            prefs[hiddenKey] = json.encodeToString(backup.hidden)
            backup.tmdbKey?.let { prefs[tmdbKeyKey] = it } ?: prefs.remove(tmdbKeyKey)
            prefs[schedulesKey] = json.encodeToString(backup.schedules)
            prefs[aspectModeKey] = backup.aspectMode.toString()
            prefs[aspectOverridesKey] = json.encodeToString(backup.aspectOverrides)
            backup.preferredAudioLang?.let { prefs[audioLangKey] = it }
                ?: prefs.remove(audioLangKey)
            backup.preferredSubtitleLang?.let { prefs[subtitleLangKey] = it }
                ?: prefs.remove(subtitleLangKey)
            prefs[vodSpeedKey] = backup.vodSpeed.toString()
        }
        return backup.sources
    }
}

/**
 * Re-inserts every episode url pointing at [seriesId]; insertion order is
 * recency (the resume-positions trick), newest [cap] kept.
 *
 * The cap is 2000 rather than the 500 it was because the player is now handed
 * a whole show at a time, not the season the viewer picked from — so one
 * long-running series records several hundred urls in a single sitting, and
 * at the old cap the second such show quietly evicted the first one's. These
 * are what let a resume position climb back to its Series card; losing them
 * drops a part-watched show out of Continue watching altogether.
 */
internal fun mergeEpisodeOrigins(
    existing: Map<String, String>,
    seriesId: String,
    episodeUrls: List<String>,
    cap: Int = 2000,
): Map<String, String> {
    val map = LinkedHashMap(existing)
    for (url in episodeUrls) {
        map.remove(url) // re-inserting moves the entry to the newest slot
        map[url] = seriesId
    }
    return if (map.size > cap) map.entries.drop(map.size - cap).associate { it.toPair() }
    else map
}
