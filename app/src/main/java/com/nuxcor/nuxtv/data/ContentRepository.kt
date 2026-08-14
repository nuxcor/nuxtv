package com.nuxcor.nuxtv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class ContentRepository(context: Context) {

    private val store = SourceStore(context.applicationContext)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    val sources: Flow<List<PlaylistSource>> = store.sources
    val activeSource: Flow<PlaylistSource?> =
        combine(store.sources, store.activeId) { list, id -> list.firstOrNull { it.id == id } }

    private val _content = MutableStateFlow<ContentState>(ContentState.Empty)
    val content: StateFlow<ContentState> = _content

    private var loadedSourceId: String? = null

    /** Loads the active source if it isn't already loaded. */
    suspend fun ensureLoaded() {
        val source = activeSource.first() ?: run {
            _content.value = ContentState.Empty
            return
        }
        if (source.id == loadedSourceId && _content.value is ContentState.Ready) return
        load(source)
    }

    suspend fun refresh() {
        activeSource.first()?.let { load(it) }
    }

    /** Validates a new source by fully loading it, then persists it as active. */
    suspend fun validateAndAdd(source: PlaylistSource): Result<Unit> {
        val previous = _content.value
        _content.value = ContentState.Loading("Connecting to ${source.name}…")
        val result = runCatching {
            val bundle = fetch(source)
            if (bundle.isEmpty) throw IOException("The playlist loaded but contains no content.")
            bundle
        }
        return result.fold(
            onSuccess = { bundle ->
                store.add(source)
                loadedSourceId = source.id
                _content.value = ContentState.Ready(bundle)
                Result.success(Unit)
            },
            onFailure = { e ->
                _content.value = previous
                Result.failure(e)
            },
        )
    }

    suspend fun selectSource(sourceId: String) {
        store.setActive(sourceId)
        val source = sources.first().firstOrNull { it.id == sourceId } ?: return
        load(source)
    }

    suspend fun removeSource(sourceId: String) {
        store.remove(sourceId)
        if (loadedSourceId == sourceId) {
            loadedSourceId = null
            ensureLoaded()
        }
    }

    private suspend fun load(source: PlaylistSource) {
        _content.value = ContentState.Loading("Loading ${source.name}…")
        runCatching { fetch(source) }
            .onSuccess { bundle ->
                loadedSourceId = source.id
                _content.value = ContentState.Ready(bundle)
            }
            .onFailure { e ->
                _content.value = ContentState.Error(e.message ?: "Failed to load playlist")
            }
    }

    private suspend fun fetch(source: PlaylistSource): ContentBundle = when (source) {
        is PlaylistSource.Xtream -> {
            val client = xtreamClient(source)
            client.authenticate()
            ContentBundle(
                liveCategories = client.liveCategories(),
                channels = client.liveStreams(),
                movieCategories = client.vodCategories(),
                movies = client.vodStreams(),
                seriesCategories = client.seriesCategories(),
                series = client.series(),
            )
        }

        is PlaylistSource.M3u -> {
            val text = withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(source.url)
                    .header("User-Agent", "NuxTV/1.0")
                    .build()
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("Server returned HTTP ${resp.code}")
                    resp.body?.string() ?: throw IOException("Empty playlist")
                }
            }
            if (!text.contains("#EXTINF")) throw IOException("That URL doesn't look like an M3U playlist")
            withContext(Dispatchers.Default) {
                ContentClassifier.classify(M3uParser.parse(text))
            }
        }
    }

    // --- lazy detail loading --------------------------------------------------

    suspend fun movieDetails(movie: Movie): Movie {
        val source = activeSource.first() as? PlaylistSource.Xtream ?: return movie
        return runCatching { xtreamClient(source).movieDetails(movie) }.getOrDefault(movie)
    }

    suspend fun episodesFor(series: Series): List<Episode> {
        series.episodes?.let { return it }
        val source = activeSource.first() as? PlaylistSource.Xtream ?: return emptyList()
        val id = series.xtreamId ?: return emptyList()
        return runCatching { xtreamClient(source).seriesEpisodes(id) }.getOrDefault(emptyList())
    }

    private fun xtreamClient(source: PlaylistSource.Xtream) =
        XtreamClient(http, source.serverUrl, source.username, source.password)

    companion object {
        fun newSourceId(): String = UUID.randomUUID().toString()
    }
}
