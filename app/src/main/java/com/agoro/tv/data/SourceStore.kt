package com.agoro.tv.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

// The corruption handler is the difference between "sign in again" and a
// permanent crash loop: TV boxes lose power mid-write routinely, and a
// corrupted preferences file otherwise throws into every reader on every
// launch until the user clears app data.
private val Context.dataStore by preferencesDataStore(
    // The FILE on disk, not the product. It keeps the pre-rename name because
    // renaming it is not a rename — it is a new, empty store, and every viewer
    // opens the app signed out of a provider they have to enter again from a
    // remote control. See PlayerPrefs for the other one.
    name = "nuxtv",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
        androidx.datastore.preferences.core.emptyPreferences()
    },
)

/** Persists configured playlist sources and the active selection. */
class SourceStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val sourcesKey = stringPreferencesKey("sources")
    private val activeKey = stringPreferencesKey("active_source_id")

    private fun Preferences.readSources(): List<PlaylistSource> =
        this[sourcesKey]?.let {
            runCatching { json.decodeFromString<List<PlaylistSource>>(it) }.getOrNull()
        } ?: emptyList()

    val sources: Flow<List<PlaylistSource>> =
        context.dataStore.data.map { prefs -> prefs.readSources() }

    val activeId: Flow<String?> = context.dataStore.data.map { prefs -> prefs[activeKey] }

    suspend fun add(source: PlaylistSource) {
        context.dataStore.edit { prefs ->
            val updated = prefs.readSources().filterNot { it.id == source.id } + source
            prefs[sourcesKey] = json.encodeToString(updated)
            prefs[activeKey] = source.id
        }
    }

    /**
     * Replaces a source's details in place. Unlike [add] this keeps its position
     * in the list and leaves the active selection alone, so correcting a typo in
     * a playlist you aren't watching doesn't yank you over to it.
     */
    suspend fun update(source: PlaylistSource) {
        context.dataStore.edit { prefs ->
            val updated = prefs.readSources().map { if (it.id == source.id) source else it }
            prefs[sourcesKey] = json.encodeToString(updated)
        }
    }

    suspend fun remove(sourceId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs.readSources()
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
