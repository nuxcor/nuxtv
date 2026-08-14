package com.nuxcor.nuxtv.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "nuxtv")

/** Persists configured playlist sources and the active selection. */
class SourceStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val sourcesKey = stringPreferencesKey("sources")
    private val activeKey = stringPreferencesKey("active_source_id")

    val sources: Flow<List<PlaylistSource>> = context.dataStore.data.map { prefs ->
        prefs[sourcesKey]?.let {
            runCatching { json.decodeFromString<List<PlaylistSource>>(it) }.getOrNull()
        } ?: emptyList()
    }

    val activeId: Flow<String?> = context.dataStore.data.map { prefs -> prefs[activeKey] }

    suspend fun add(source: PlaylistSource) {
        context.dataStore.edit { prefs ->
            val current = prefs[sourcesKey]?.let {
                runCatching { json.decodeFromString<List<PlaylistSource>>(it) }.getOrNull()
            } ?: emptyList()
            val updated = current.filterNot { it.id == source.id } + source
            prefs[sourcesKey] = json.encodeToString(updated)
            prefs[activeKey] = source.id
        }
    }

    suspend fun remove(sourceId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[sourcesKey]?.let {
                runCatching { json.decodeFromString<List<PlaylistSource>>(it) }.getOrNull()
            } ?: emptyList()
            val updated = current.filterNot { it.id == sourceId }
            prefs[sourcesKey] = json.encodeToString(updated)
            if (prefs[activeKey] == sourceId) {
                val next = updated.firstOrNull()?.id
                if (next != null) prefs[activeKey] = next else prefs.remove(activeKey)
            }
        }
    }

    suspend fun setActive(sourceId: String) {
        context.dataStore.edit { prefs -> prefs[activeKey] = sourceId }
    }
}
