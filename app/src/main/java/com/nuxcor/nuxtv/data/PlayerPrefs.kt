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

    val tmdbKey: Flow<String?> = context.playerDataStore.data.map { prefs ->
        prefs[tmdbKeyKey]?.takeIf { it.isNotBlank() }
    }

    val parentalPin: Flow<String?> = context.playerDataStore.data.map { prefs ->
        prefs[pinKey]?.takeIf { it.isNotBlank() }
    }

    suspend fun setParentalPin(pin: String?) {
        context.playerDataStore.edit { prefs ->
            if (pin.isNullOrBlank()) prefs.remove(pinKey) else prefs[pinKey] = pin.trim()
        }
    }

    suspend fun setTmdbKey(key: String?) {
        context.playerDataStore.edit { prefs ->
            if (key.isNullOrBlank()) prefs.remove(tmdbKeyKey) else prefs[tmdbKeyKey] = key.trim()
        }
    }

    // --- backup / restore -----------------------------------------------------

    @kotlinx.serialization.Serializable
    data class Backup(
        val favorites: Set<String> = emptySet(),
        val hidden: Set<String> = emptySet(),
        val engine: String = "EXO",
        val epgOverrideUrl: String? = null,
        val tmdbKey: String? = null,
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
            prefs[schedulesKey] = json.encodeToString(backup.schedules)
        }
        return backup.sources
    }
}
