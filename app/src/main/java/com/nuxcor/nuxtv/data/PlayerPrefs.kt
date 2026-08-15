package com.nuxcor.nuxtv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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

private val Context.playerDataStore: DataStore<Preferences> by preferencesDataStore(name = "nuxtv_player")

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
    }

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
    }

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

    // --- favorites (keyed by stream URL, stable across playlist reloads) ------

    val favorites: Flow<Set<String>> = context.playerDataStore.data.map { prefs ->
        prefs[favoritesKey]?.let {
            runCatching { json.decodeFromString<Set<String>>(it) }.getOrNull()
        } ?: emptySet()
    }

    suspend fun toggleFavorite(channelUrl: String) {
        context.playerDataStore.edit { prefs ->
            val current = prefs[favoritesKey]?.let {
                runCatching { json.decodeFromString<Set<String>>(it) }.getOrNull()
            } ?: emptySet()
            val updated = if (channelUrl in current) current - channelUrl else current + channelUrl
            prefs[favoritesKey] = json.encodeToString(updated)
        }
    }

    // --- scheduled recordings -------------------------------------------------

    val schedules: Flow<List<ScheduledRecording>> = context.playerDataStore.data.map { prefs ->
        prefs[schedulesKey]?.let {
            runCatching { json.decodeFromString<List<ScheduledRecording>>(it) }.getOrNull()
        } ?: emptyList()
    }

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
    }

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

    /** When on, SD/HD/FHD variants of the same channel collapse to the best one. */
    val mergeDuplicates: Flow<Boolean> = context.playerDataStore.data.map { prefs ->
        prefs[mergeDupesKey] == "true"
    }

    suspend fun setMergeDuplicates(enabled: Boolean) {
        context.playerDataStore.edit { it[mergeDupesKey] = enabled.toString() }
    }

    /** 0 = provider order, 1 = A-Z, 2 = quality first. */
    val channelOrder: Flow<Int> = context.playerDataStore.data.map { prefs ->
        prefs[channelOrderKey]?.toIntOrNull() ?: 0
    }

    suspend fun setChannelOrder(mode: Int) {
        context.playerDataStore.edit { it[channelOrderKey] = mode.toString() }
    }

    /**
     * 0 = adapt to bandwidth, 1 = always the top rung. Highest looks sharper
     * immediately but never drops when the connection sags, so it turns a soft
     * picture into rebuffering — which of those is the lesser evil depends on
     * the line, not on us.
     */
    val videoQuality: Flow<Int> = context.playerDataStore.data.map { prefs ->
        prefs[videoQualityKey]?.toIntOrNull() ?: 1
    }

    suspend fun setVideoQuality(mode: Int) {
        context.playerDataStore.edit { it[videoQualityKey] = mode.toString() }
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
        val mergeDuplicates: Boolean = false,
        val channelOrder: Int = 0,
        val schedules: List<ScheduledRecording> = emptyList(),
        val sources: List<PlaylistSource> = emptyList(),
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
            mergeDuplicates = prefs[mergeDupesKey] == "true",
            channelOrder = prefs[channelOrderKey]?.toIntOrNull() ?: 0,
            schedules = prefs[schedulesKey]?.let {
                runCatching { json.decodeFromString<List<ScheduledRecording>>(it) }.getOrNull()
            } ?: emptyList(),
            sources = sources,
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
        }
        return backup.sources
    }
}
