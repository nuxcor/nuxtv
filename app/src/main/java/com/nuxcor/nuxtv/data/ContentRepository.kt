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
    private val appContext = context.applicationContext

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val logos by lazy { LogoRepository(appContext, http) }

    val sources: Flow<List<PlaylistSource>> = store.sources
    val activeSource: Flow<PlaylistSource?> =
        combine(store.sources, store.activeId) { list, id -> list.firstOrNull { it.id == id } }

    private val _content = MutableStateFlow<ContentState>(ContentState.Empty)
    val content: StateFlow<ContentState> = _content

    sealed class EpgState {
        data object Idle : EpgState()
        data object Loading : EpgState()
        data class Ready(val data: XmltvData) : EpgState()
        data class Error(val message: String) : EpgState()
    }

    private val _epg = MutableStateFlow<EpgState>(EpgState.Idle)
    val epg: StateFlow<EpgState> = _epg

    /** Raw playlist text of the last M3U load, kept to read its url-tvg header. */
    private var lastM3uText: String? = null

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
            lastM3uText = text
            withContext(Dispatchers.Default) {
                ContentClassifier.classify(M3uParser.parse(text))
            }
        }
    }

    // --- EPG ------------------------------------------------------------------

    /**
     * Loads the XMLTV guide. A user-set override URL (e.g. an epgshare01
     * pack) wins; otherwise Xtream's xmltv.php or the M3U url-tvg/epgUrl.
     */
    suspend fun loadEpg(overrideUrl: String? = null) {
        val source = activeSource.first() ?: return
        val url = overrideUrl?.takeIf { it.isNotBlank() } ?: when (source) {
            is PlaylistSource.Xtream -> xtreamClient(source).xmltvUrl
            is PlaylistSource.M3u ->
                source.epgUrl?.takeIf { it.isNotBlank() }
                    ?: lastM3uText?.let { M3uParser.tvgUrl(it) }
        }
        if (url == null) {
            _epg.value = EpgState.Error("No EPG source configured for this playlist")
            return
        }
        _epg.value = EpgState.Loading
        _epg.value = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url).header("User-Agent", "NuxTV/1.0").build()
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("Guide server returned HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("Empty guide response")
                    EpgState.Ready(XmltvParser.parse(body.byteStream()))
                }
            }.getOrElse { e -> EpgState.Error(e.message ?: "Failed to load the guide") }
        }
    }

    /**
     * Programmes for a channel from the loaded XMLTV data, matched by tvg-id
     * first and display name second.
     */
    fun programsFor(channel: LiveChannel): List<EpgProgram> {
        val data = (_epg.value as? EpgState.Ready)?.data ?: return emptyList()
        channel.epgId?.lowercase()?.let { id ->
            data.programmes[id]?.let { return it }
        }
        val wanted = channel.name.trim().lowercase()
        val byNameId = data.channelNames.entries
            .firstOrNull { (_, name) -> name.trim().lowercase() == wanted }?.key?.lowercase()
        return byNameId?.let { data.programmes[it] } ?: emptyList()
    }

    /** Fills missing channel logos from the tv-logos repo; no-op on failure. */
    suspend fun enrichLogos() {
        val ready = _content.value as? ContentState.Ready ?: return
        val enriched = runCatching { logos.enrich(ready.bundle) }.getOrNull() ?: return
        // Only publish if the playlist hasn't been swapped underneath us.
        if (_content.value === ready) _content.value = ContentState.Ready(enriched)
    }

    /** Re-adds sources from a backup and reloads the active one. */
    suspend fun restoreSources(sources: List<PlaylistSource>) {
        sources.forEach { store.add(it) }
        loadedSourceId = null
        ensureLoaded()
    }

    // --- lazy detail loading --------------------------------------------------

    suspend fun movieDetails(movie: Movie, tmdbKey: String? = null): Movie {
        var enriched = movie
        (activeSource.first() as? PlaylistSource.Xtream)?.let { source ->
            enriched = runCatching { xtreamClient(source).movieDetails(enriched) }.getOrDefault(enriched)
        }
        if (tmdbKey != null) {
            runCatching { TmdbClient(http, tmdbKey).lookup("movie", enriched.name, enriched.year) }
                .getOrNull()?.let { tmdb ->
                    enriched = enriched.copy(
                        rating = enriched.rating ?: tmdb.rating,
                        voteCount = tmdb.voteCount,
                        plot = enriched.plot ?: tmdb.overview,
                        poster = enriched.poster ?: tmdb.posterUrl,
                        reviews = tmdb.reviews,
                    )
                }
        }
        return enriched
    }

    suspend fun seriesDetails(series: Series, tmdbKey: String? = null): Series {
        if (tmdbKey == null) return series
        val tmdb = runCatching { TmdbClient(http, tmdbKey).lookup("tv", series.name, series.year) }
            .getOrNull() ?: return series
        return series.copy(
            rating = series.rating ?: tmdb.rating,
            voteCount = tmdb.voteCount,
            plot = series.plot ?: tmdb.overview,
            poster = series.poster ?: tmdb.posterUrl,
            reviews = tmdb.reviews,
        )
    }

    suspend fun episodesFor(series: Series): List<Episode> {
        series.episodes?.let { return it }
        val source = activeSource.first() as? PlaylistSource.Xtream ?: return emptyList()
        val id = series.xtreamId ?: return emptyList()
        return runCatching { xtreamClient(source).seriesEpisodes(id) }.getOrDefault(emptyList())
    }

    /** EPG for a live channel; empty for M3U sources or channels without an Xtream id. */
    suspend fun epgFor(channel: LiveChannel): List<EpgProgram> {
        val source = activeSource.first() as? PlaylistSource.Xtream ?: return emptyList()
        val id = channel.xtreamId ?: return emptyList()
        return runCatching { xtreamClient(source).epg(id) }.getOrDefault(emptyList())
    }

    /** Catch-up stream URL for an archived programme, or null when unsupported. */
    suspend fun catchupUrl(channel: LiveChannel, program: EpgProgram): String? {
        val source = activeSource.first() as? PlaylistSource.Xtream ?: return null
        val id = channel.xtreamId ?: return null
        val durationMin = ((program.endMs - program.startMs) / 60_000).coerceAtLeast(1)
        return xtreamClient(source).catchupUrl(id, program.startMs, durationMin)
    }

    private fun xtreamClient(source: PlaylistSource.Xtream) =
        XtreamClient(http, source.serverUrl, source.username, source.password)

    companion object {
        fun newSourceId(): String = UUID.randomUUID().toString()
    }
}
