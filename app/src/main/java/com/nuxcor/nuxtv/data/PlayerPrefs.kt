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

    /** Saves (or clears, when near the end) a VOD resume position. Keeps the newest 200. */
    suspend fun saveResumePosition(url: String, positionMs: Long, durationMs: Long) {
        val map = positions()
        val nearEnd = durationMs > 0 && positionMs > durationMs * 95 / 100
        if (positionMs < 30_000 || nearEnd) map.remove(url) else map[url] = positionMs
        val trimmed = if (map.size > 200) map.entries.drop(map.size - 200).associate { it.toPair() } else map
        context.playerDataStore.edit { it[positionsKey] = json.encodeToString(trimmed) }
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
}
