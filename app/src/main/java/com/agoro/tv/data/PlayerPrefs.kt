package com.agoro.tv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

enum class EngineChoice { EXO, VLC }

@kotlinx.serialization.Serializable
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

private val Context.playerDataStore: DataStore<Preferences> by preferencesDataStore(name = "nuxtv_player")

/**
 * How many recently-watched channels to keep. Deep enough to cover an evening
 * of flipping, short enough that the list still reads as "what I was just on"
 * rather than a history.
 */
const val RECENT_CHANNEL_LIMIT = 20

/** Player-related preferences: default engine and VOD resume positions. */
class PlayerPrefs(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val engineKey = stringPreferencesKey("engine")
    private val positionsKey = stringPreferencesKey("resume_positions")
    private val favoritesKey = stringPreferencesKey("favorite_channels")
    private val schedulesKey = stringPreferencesKey("scheduled_recordings")
    private val hiddenKey = stringPreferencesKey("hidden_channels")
    private val epgOverrideKey = stringPreferencesKey("epg_override_url")
    private val tmdbKeyKey = stringPreferencesKey("tmdb_api_key")
    private val pinKey = stringPreferencesKey("parental_pin")
    private val mergeDupesKey = stringPreferencesKey("merge_duplicate_channels")
    private val channelOrderKey = stringPreferencesKey("channel_order")
    private val durationsKey = stringPreferencesKey("resume_durations")
    private val videoQualityKey = stringPreferencesKey("video_quality")
    private val recentChannelsKey = stringPreferencesKey("recent_channels")
    private val aspectModeKey = stringPreferencesKey("aspect_mode")
    private val aspectOverridesKey = stringPreferencesKey("aspect_overrides")
    private val audioLangKey = stringPreferencesKey("preferred_audio_lang")
    private val subtitleLangKey = stringPreferencesKey("preferred_subtitle_lang")
    private val vodSpeedKey = stringPreferencesKey("vod_speed")
    private val keyHintsVersionKey = stringPreferencesKey("key_hints_version")
    private val knownQualitiesKey = stringPreferencesKey("known_qualities")
    private val guidePreviewModeKey = stringPreferencesKey("guide_preview_mode")
    private val liveTsMigratedKey = stringPreferencesKey("live_ts_migrated")
    private val episodeOriginsKey = stringPreferencesKey("episode_origins")
    private val artworkKey = stringPreferencesKey("tmdb_artwork")

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
        prefs[knownQualitiesKey]?.let {
            runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull()
        } ?: emptyMap()
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
     * Episode stream URL → series id. Xtream episode URLs don't encode their
     * series, so Continue Watching couldn't climb from a resume position back
     * to a Series card without this. Written whenever episodes are handed to
     * the player; newest 500 kept.
     */
    val episodeOrigins: Flow<Map<String, String>> = context.playerDataStore.data.map { prefs ->
        prefs[episodeOriginsKey]?.let {
            runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull()
        } ?: emptyMap()
    }.flowOn(Dispatchers.Default)

    suspend fun recordEpisodeOrigins(seriesId: String, episodeUrls: List<String>) {
        context.playerDataStore.edit { prefs ->
            val existing = prefs[episodeOriginsKey]?.let {
                runCatching { json.decodeFromString<LinkedHashMap<String, String>>(it) }.getOrNull()
            } ?: LinkedHashMap()
            prefs[episodeOriginsKey] =
                json.encodeToString(mergeEpisodeOrigins(existing, seriesId, episodeUrls))
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
    // flowOn, here and on every other JSON-backed flow below: DataStore
    // re-emits the WHOLE Preferences object to every collector on any write,
    // and MainViewModel collects these with stateIn(viewModelScope, ...) —
    // Dispatchers.Main.immediate. Without the hop, one artwork lookup landing
    // mid-scroll made artwork, resume positions, favorites, hidden, episode
    // origins, schedules and sources all re-parse their JSON on the main
    // thread, during exactly the scroll borrowed art exists to decorate.
    val artwork: Flow<Map<String, ArtEntry>> = context.playerDataStore.data.map { prefs ->
        prefs[artworkKey]?.let {
            runCatching { json.decodeFromString<Map<String, ArtEntry>>(it) }.getOrNull()
        } ?: emptyMap()
    }.flowOn(Dispatchers.Default)

    suspend fun putArtwork(id: String, entry: ArtEntry) {
        context.playerDataStore.edit { prefs ->
            val map = prefs[artworkKey]?.let {
                runCatching { json.decodeFromString<LinkedHashMap<String, ArtEntry>>(it) }.getOrNull()
            } ?: LinkedHashMap()
            map.remove(id) // re-inserting moves the entry to the newest slot
            map[id] = entry
            val trimmed =
                if (map.size > 800) map.entries.drop(map.size - 800).associate { it.toPair() }
                else map
            prefs[artworkKey] = json.encodeToString(trimmed)
        }
    }

    val engine: Flow<EngineChoice> = context.playerDataStore.data.map { prefs ->
        runCatching { EngineChoice.valueOf(prefs[engineKey] ?: "EXO") }.getOrDefault(EngineChoice.EXO)
    }

    suspend fun setEngine(choice: EngineChoice) {
        context.playerDataStore.edit { it[engineKey] = choice.name }
    }

    private suspend fun positions(): MutableMap<String, Long> =
        context.playerDataStore.data.first()[positionsKey]?.let {
            runCatching { json.decodeFromString<MutableMap<String, Long>>(it) }.getOrNull()
        } ?: mutableMapOf()

    suspend fun resumePositionFor(url: String): Long = positions()[url] ?: 0L

    /** url → position, for Continue Watching rows. */
    val resumePositions: Flow<Map<String, Long>> = context.playerDataStore.data.map { prefs ->
        prefs[positionsKey]?.let {
            runCatching { json.decodeFromString<Map<String, Long>>(it) }.getOrNull()
        } ?: emptyMap()
    }.flowOn(Dispatchers.Default)

    /**
     * url → total duration, written alongside the position. Kept in its own
     * entry rather than folded into [resumePositions] so existing installs
     * don't fail to decode their saved positions and lose them; entries
     * predating this simply have no duration and show no progress bar.
     */
    val resumeDurations: Flow<Map<String, Long>> = context.playerDataStore.data.map { prefs ->
        prefs[durationsKey]?.let {
            runCatching { json.decodeFromString<Map<String, Long>>(it) }.getOrNull()
        } ?: emptyMap()
    }.flowOn(Dispatchers.Default)

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
     */
    suspend fun clearResume(urls: Collection<String>) {
        if (urls.isEmpty()) return
        context.playerDataStore.edit { prefs ->
            val map = prefs[positionsKey]?.let {
                runCatching { json.decodeFromString<LinkedHashMap<String, Long>>(it) }.getOrNull()
            } ?: return@edit
            if (!map.keys.removeAll(urls.toSet())) return@edit
            prefs[positionsKey] = json.encodeToString(map)
            prefs[durationsKey]?.let { raw ->
                runCatching { json.decodeFromString<MutableMap<String, Long>>(raw) }.getOrNull()
                    ?.let { durations ->
                        durations.keys.retainAll(map.keys)
                        prefs[durationsKey] = json.encodeToString(durations)
                    }
            }
        }
    }

    // --- favorites (keyed by stream URL, stable across playlist reloads) ------

    val favorites: Flow<Set<String>> = context.playerDataStore.data.map { prefs ->
        prefs[favoritesKey]?.let {
            runCatching { json.decodeFromString<Set<String>>(it) }.getOrNull()
        } ?: emptySet()
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
        prefs[recentChannelsKey]?.let {
            runCatching { json.decodeFromString<List<String>>(it) }.getOrNull()
        } ?: emptyList()
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

    // --- scheduled recordings -------------------------------------------------

    val schedules: Flow<List<ScheduledRecording>> = context.playerDataStore.data.map { prefs ->
        prefs[schedulesKey]?.let {
            runCatching { json.decodeFromString<List<ScheduledRecording>>(it) }.getOrNull()
        } ?: emptyList()
    }.flowOn(Dispatchers.Default)

    suspend fun addSchedule(item: ScheduledRecording) {
        context.playerDataStore.edit { prefs ->
            val current = prefs[schedulesKey]?.let {
                runCatching { json.decodeFromString<List<ScheduledRecording>>(it) }.getOrNull()
            } ?: emptyList()
            prefs[schedulesKey] = json.encodeToString(current.filterNot { it.id == item.id } + item)
        }
    }

    suspend fun removeSchedule(id: String) {
        context.playerDataStore.edit { prefs ->
            val current = prefs[schedulesKey]?.let {
                runCatching { json.decodeFromString<List<ScheduledRecording>>(it) }.getOrNull()
            } ?: emptyList()
            prefs[schedulesKey] = json.encodeToString(current.filterNot { it.id == id })
        }
    }

    // --- hidden channels ------------------------------------------------------

    val hidden: Flow<Set<String>> = context.playerDataStore.data.map { prefs ->
        prefs[hiddenKey]?.let {
            runCatching { json.decodeFromString<Set<String>>(it) }.getOrNull()
        } ?: emptySet()
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

    // --- EPG override + TMDB key ----------------------------------------------

    val epgOverrideUrl: Flow<String?> = context.playerDataStore.data.map { prefs ->
        prefs[epgOverrideKey]?.takeIf { it.isNotBlank() }
    }

    suspend fun setEpgOverrideUrl(url: String?) {
        context.playerDataStore.edit { prefs ->
            if (url.isNullOrBlank()) prefs.remove(epgOverrideKey) else prefs[epgOverrideKey] = url.trim()
        }
    }

    /**
     * When on, SD/HD/FHD variants of the same channel collapse to the best
     * one. Defaults ON: raw provider playlists ship the same channel three
     * and four times over, and a first run that lists them all reads as a
     * broken app rather than as a setting waiting to be found. Anyone who
     * wants every variant can still say so, and that choice persists.
     */
    val mergeDuplicates: Flow<Boolean> = context.playerDataStore.data.map { prefs ->
        prefs[mergeDupesKey] != "false"
    }

    suspend fun setMergeDuplicates(enabled: Boolean) {
        context.playerDataStore.edit { it[mergeDupesKey] = enabled.toString() }
    }

    /**
     * "auto" (default) = preview on when the account reports a spare
     * connection; "on"/"off" override it — playlist middlemen (IPTVEditor)
     * report a cosmetic max_connections of 1, which would pin auto off for
     * accounts that genuinely allow more.
     */
    val guidePreviewMode: Flow<String> = context.playerDataStore.data.map { prefs ->
        prefs[guidePreviewModeKey] ?: "auto"
    }

    suspend fun setGuidePreviewMode(mode: String) {
        context.playerDataStore.edit { it[guidePreviewModeKey] = mode }
    }

    /** 0 = provider order, 1 = A-Z, 2 = quality first. */
    val channelOrder: Flow<Int> = context.playerDataStore.data.map { prefs ->
        prefs[channelOrderKey]?.toIntOrNull() ?: 0
    }

    suspend fun setChannelOrder(mode: Int) {
        context.playerDataStore.edit { it[channelOrderKey] = mode.toString() }
    }

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
        val engine: String = "EXO",
        val epgOverrideUrl: String? = null,
        // Retained so backups written before the key was bundled still restore.
        // Nothing reads the restored value — the key comes from BuildConfig now.
        val tmdbKey: String? = null,
        // Matches the live default, so a backup that predates the key
        // restores to merging rather than silently turning it off.
        val mergeDuplicates: Boolean = true,
        val channelOrder: Int = 0,
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
            engine = prefs[engineKey] ?: "EXO",
            epgOverrideUrl = prefs[epgOverrideKey],
            tmdbKey = prefs[tmdbKeyKey],
            mergeDuplicates = prefs[mergeDupesKey] != "false",
            channelOrder = prefs[channelOrderKey]?.toIntOrNull() ?: 0,
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
            prefs[engineKey] = backup.engine
            backup.epgOverrideUrl?.let { prefs[epgOverrideKey] = it } ?: prefs.remove(epgOverrideKey)
            backup.tmdbKey?.let { prefs[tmdbKeyKey] = it } ?: prefs.remove(tmdbKeyKey)
            prefs[mergeDupesKey] = backup.mergeDuplicates.toString()
            prefs[channelOrderKey] = backup.channelOrder.toString()
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
 */
internal fun mergeEpisodeOrigins(
    existing: Map<String, String>,
    seriesId: String,
    episodeUrls: List<String>,
    cap: Int = 500,
): Map<String, String> {
    val map = LinkedHashMap(existing)
    for (url in episodeUrls) {
        map.remove(url) // re-inserting moves the entry to the newest slot
        map[url] = seriesId
    }
    return if (map.size > cap) map.entries.drop(map.size - cap).associate { it.toPair() }
    else map
}
