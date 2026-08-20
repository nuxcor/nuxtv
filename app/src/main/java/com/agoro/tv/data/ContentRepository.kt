package com.agoro.tv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.coroutines.sync.withLock
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
    private val manifests by lazy { ManifestRepository(appContext, http) }
    private val bundleJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun cacheFile(sourceId: String) =
        java.io.File(appContext.filesDir, "bundle-$sourceId.json".replace("$sourceId", sourceId))

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun readCache(sourceId: String): ContentBundle? = runCatching {
        cacheFile(sourceId).takeIf { it.exists() }?.inputStream()?.buffered()?.use { stream ->
            bundleJson.decodeFromStream<ContentBundle>(stream)
        }
        // Caches written before the cleaner existed get cleaned on read, so a
        // warm start doesn't show the raw mess until the next refresh. Ones
        // written since say so and are left alone — re-cleaning a curated
        // bundle rewrites its shelf labels, so cache and network disagreed
        // about what the same catalogue is called. See [ContentBundle.cleaned].
    }.getOrNull()?.let { if (it.cleaned) it else CategoryCleaner.clean(it) }

    /**
     * The #EXTM3U url-tvg header, persisted beside the playlist cache. It only
     * exists in the playlist text, so it used to live solely in memory: a warm
     * start published the cached bundle, the guide loaded against a null URL
     * and failed as "No EPG source configured" — and the background refresh
     * that then learned the URL re-produced an equal bundle, which StateFlow
     * deduped, so nothing ever asked for the guide again until the 6-hour loop.
     */
    private fun tvgFile(sourceId: String) =
        java.io.File(appContext.filesDir, "tvg-$sourceId.txt")

    private fun readTvgUrl(sourceId: String): String? = runCatching {
        tvgFile(sourceId).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun writeTvgUrl(sourceId: String, url: String?) {
        runCatching {
            // A removed header must not leave a stale URL to resurrect.
            if (url.isNullOrBlank()) tvgFile(sourceId).delete()
            else tvgFile(sourceId).writeText(url)
        }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun writeCache(sourceId: String, bundle: ContentBundle) {
        runCatching {
            cacheFile(sourceId).outputStream().buffered().use { stream ->
                bundleJson.encodeToStream(bundle, stream)
            }
        }
    }

    /**
     * The merged guide, persisted so the next start publishes it instantly
     * instead of holding a spinner through the multi-pack download. Gzipped:
     * a merged guide serializes to tens of MB of JSON. Keyed by the guide URL
     * inside the file so a swapped playlist never reads another's guide.
     */
    @Serializable
    private class EpgCacheFile(val url: String, val savedAtMs: Long, val data: XmltvData)

    private fun epgCacheFile() = java.io.File(appContext.filesDir, "epg-cache.json.gz")

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun readEpgCache(): EpgCacheFile? = runCatching {
        epgCacheFile().takeIf { it.exists() }?.let { f ->
            java.util.zip.GZIPInputStream(f.inputStream().buffered()).use { stream ->
                bundleJson.decodeFromStream<EpgCacheFile>(stream)
            }
        }
    }.getOrNull()

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun writeEpgCache(url: String, data: XmltvData) {
        runCatching {
            java.util.zip.GZIPOutputStream(epgCacheFile().outputStream().buffered()).use { stream ->
                bundleJson.encodeToStream(EpgCacheFile(url, System.currentTimeMillis(), data), stream)
            }
        }
    }

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

    /** url-tvg header value from the last M3U load. */
    @Volatile
    private var lastM3uTvgUrl: String? = null

    /** What the last guide request was asked to prefer, for repo-initiated retries. */
    private var lastEpgOverride: String? = null

    private var loadedSourceId: String? = null

    private val epgMutex = Mutex()
    private val publishMutex = Mutex()
    private var lastEpgUrl: String? = null
    private var lastEpgLoadedAt: Long = 0

    /**
     * Loads the active source. A cached copy of the parsed playlist is
     * published instantly for fast starts, then refreshed from the network
     * in the background.
     */
    suspend fun ensureLoaded() {
        val source = activeSource.first() ?: run {
            _content.value = ContentState.Empty
            return
        }
        if (source.id == loadedSourceId && _content.value is ContentState.Ready) return
        val cached = withContext(Dispatchers.IO) { readCache(source.id) }
        if (cached != null && !cached.isEmpty) {
            // Restored before the bundle is published, because publishing is
            // what triggers the guide load that needs it.
            if (source is PlaylistSource.M3u && lastM3uTvgUrl == null) {
                lastM3uTvgUrl = withContext(Dispatchers.IO) { readTvgUrl(source.id) }
            }
            loadedSourceId = source.id
            _content.value = ContentState.Ready(cached)
            load(source, quiet = true)
        } else {
            load(source)
        }
    }

    suspend fun refresh() {
        activeSource.first()?.let { load(it) }
    }

    /**
     * Background catalog refresh: no Loading state, current library stays on
     * screen, failures keep the cache. For the periodic cycle — a TV app can
     * stay open for days, and without this a provider's added channels only
     * appeared after a relaunch or a manual refresh.
     */
    suspend fun refreshQuiet() {
        if (_content.value !is ContentState.Ready) return
        activeSource.first()?.let { load(it, quiet = true) }
    }

    /**
     * Quiet refresh, gated on the catalog actually being old. The cache
     * file's mtime is the persisted "last successful refresh" stamp —
     * [writeCache] rewrites the file on every successful load, so it can't
     * drift from the truth and survives process death, which the in-memory
     * 12-hour timer never did: the countdown restarted from zero on every
     * launch, so a playlist opened for an hour a day was never refreshed.
     */
    suspend fun refreshIfStale(maxAgeMs: Long) {
        val source = activeSource.first() ?: return
        val ageMs = withContext(Dispatchers.IO) {
            val stamp = cacheFile(source.id).lastModified()
            if (stamp == 0L) Long.MAX_VALUE else System.currentTimeMillis() - stamp
        }
        if (ageMs >= maxAgeMs) refreshQuiet()
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
                publishMutex.withLock {
                    store.add(source)
                    loadedSourceId = source.id
                    _content.value = ContentState.Ready(bundle)
                }
                withContext(Dispatchers.IO) { writeCache(source.id, bundle) }
                Result.success(Unit)
            },
            onFailure = { e ->
                _content.value = previous
                Result.failure(e)
            },
        )
    }

    /**
     * Same validation as [validateAndAdd], for a source that already exists: the
     * edit only lands if the new details actually load, so a mistyped password
     * can't leave you with a playlist that no longer works either way.
     *
     * Editing a playlist you aren't currently watching leaves the screen alone —
     * only its cache is refreshed, ready for the next time you switch to it.
     */
    suspend fun validateAndUpdate(source: PlaylistSource): Result<Unit> {
        val isActive = activeSource.first()?.id == source.id
        val previous = _content.value
        if (isActive) _content.value = ContentState.Loading("Connecting to ${source.name}…")
        val result = runCatching {
            val bundle = fetch(source)
            if (bundle.isEmpty) throw IOException("The playlist loaded but contains no content.")
            bundle
        }
        return result.fold(
            onSuccess = { bundle ->
                publishMutex.withLock {
                    store.update(source)
                    if (isActive) {
                        loadedSourceId = source.id
                        _content.value = ContentState.Ready(bundle)
                    }
                }
                withContext(Dispatchers.IO) { writeCache(source.id, bundle) }
                Result.success(Unit)
            },
            onFailure = { e ->
                if (isActive) _content.value = previous
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
        runCatching { cacheFile(sourceId).delete() }
        runCatching { tvgFile(sourceId).delete() }
        if (loadedSourceId == sourceId) {
            loadedSourceId = null
            ensureLoaded()
        }
    }

    private suspend fun load(source: PlaylistSource, quiet: Boolean = false) {
        if (!quiet) _content.value = ContentState.Loading("Loading ${source.name}…")
        runCatching { fetch(source) }
            .onSuccess { bundle ->
                if (bundle.isEmpty) {
                    // A server that authenticates but returns error objects for
                    // the catalogs must not blank a working library or cache.
                    android.util.Log.w("Agoro", "Refresh returned an empty catalog; keeping current library")
                    if (!quiet && _content.value !is ContentState.Ready) {
                        _content.value = ContentState.Error("The playlist loaded but contains no content.")
                    }
                    return
                }
                publishMutex.withLock {
                    // Drop the result if the user switched sources while we fetched.
                    if (activeSource.first()?.id != source.id) return
                    loadedSourceId = source.id
                    _content.value = ContentState.Ready(bundle)
                }
                withContext(Dispatchers.IO) { writeCache(source.id, bundle) }
                // The refresh may have just learned something the failed guide
                // load didn't have — an url-tvg header on the first run with no
                // side file yet — and an unchanged bundle is deduped upstream,
                // so nobody else will retry. Freshness inside loadEpg keeps
                // this from re-downloading a guide that is already Ready.
                if (_epg.value !is EpgState.Ready) loadEpg(lastEpgOverride)
            }
            .onFailure { e ->
                android.util.Log.w("Agoro", "Playlist load failed: ${e.message}")
                // Never clobber a working library with an error screen.
                if (!quiet && _content.value !is ContentState.Ready) {
                    _content.value = ContentState.Error(e.message ?: "Failed to load playlist")
                }
            }
    }

    // Category cleanup then manifest curation, both at bundle build time —
    // caches, EPG resolution, duplicate merging and every screen see only the
    // finished model. The manifest is provider-specific judgement (what to
    // drop, which section a channel really belongs to) and is absent for
    // sources it wasn't written for, in which case the bundle passes through.
    private suspend fun fetch(source: PlaylistSource): ContentBundle {
        val cleaned = CategoryCleaner.clean(fetchRaw(source))
        val manifest = manifests.load() ?: return cleaned
        if (!manifestApplies(source, manifest)) return cleaned
        return withContext(Dispatchers.Default) { ManifestCuration.apply(cleaned, manifest) }
    }

    /** A manifest describes one provider; applying it to another would gut the library. */
    private fun manifestApplies(source: PlaylistSource, manifest: CatalogueManifest): Boolean {
        val host = manifest.provider.host.takeIf { it.isNotBlank() } ?: return false
        val sourceHost = when (source) {
            is PlaylistSource.Xtream -> source.serverUrl
            is PlaylistSource.M3u -> source.url
        }
        return sourceHost.contains(host, ignoreCase = true)
    }

    private suspend fun fetchRaw(source: PlaylistSource): ContentBundle = when (source) {
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
            // Parse line-by-line straight off the socket: giant provider
            // playlists never exist as one big string in memory.
            val parsed = withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(source.url)
                    .header("User-Agent", "Agoro/2.1")
                    .build()
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("Server returned HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("Empty playlist")
                    body.charStream().buffered().useLines { lines ->
                        M3uParser.parseLines(lines)
                    }
                }
            }
            if (parsed.entries.isEmpty() && !parsed.sawHeader) {
                throw IOException("That URL doesn't look like an M3U playlist")
            }
            lastM3uTvgUrl = parsed.tvgUrl
            withContext(Dispatchers.IO) { writeTvgUrl(source.id, parsed.tvgUrl) }
            withContext(Dispatchers.Default) {
                ContentClassifier.classify(parsed.entries)
            }
        }
    }

    // --- EPG ------------------------------------------------------------------

    /**
     * The guide feeds the manifest's channel ids belong to, most-used first.
     *
     * The ids come from a curated pack split across ~21 files; two of them
     * carry 70% of our bindings, so we fetch the few that matter rather than
     * the whole set. Empty when no manifest applies to this source.
     */
    private suspend fun manifestGuideUrls(source: PlaylistSource, limit: Int = 4): List<String> {
        val manifest = manifests.load() ?: return emptyList()
        if (!manifestApplies(source, manifest)) return emptyList()
        val base = manifest.epg.sources.firstOrNull { it.key == "repo" }?.base ?: return emptyList()
        // Rank the pack files by how many of our channels they actually answer.
        val weight = manifest.epg.channelMap.values
            .filter { it.src == "repo" && it.feed.isNotBlank() }
            .groupingBy { it.feed }.eachCount()
        val packs = weight.entries.sortedByDescending { it.value }.take(limit)
            .map { "$base${it.key}.xml.gz" }
        // Then the region-targeted epgshare01 feeds. The curated packs are
        // split arbitrarily and leave gaps exactly where this catalogue is
        // weakest — US local affiliates, US sport, UK — and epgshare01
        // publishes a feed per region that covers precisely those.
        val regional = manifest.keptRegions.flatMap { EPGSHARE_BY_REGION[it].orEmpty() }
            .distinct().map { "$EPGSHARE_BASE$it.xml.gz" }
        return packs + regional
    }



    /**
     * Downloads several XMLTV packs and folds them into one guide. A pack that
     * fails is skipped rather than failing the load — a partial guide beats an
     * empty one.
     *
     * Each pack is parsed, merged, and dropped before the next is requested.
     * The old shape accumulated with a pairwise merge that rebuilt every map
     * per pack, so the cost grew with the square of the pack count over a
     * dozen packs — one of which is 55 MB — on a device with a TV box's heap.
     * [XmltvMerger] holds one set of maps for the whole fold.
     */
    private fun fetchAndMerge(urls: List<String>, onPartial: (XmltvData) -> Unit = {}): XmltvData {
        val now = System.currentTimeMillis()
        val merger = XmltvMerger()
        var latest: XmltvData? = null
        for (u in urls) {
            val one = runCatching {
                val request = Request.Builder().url(u).header("User-Agent", "Agoro/2.1").build()
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("empty")
                    // Straight through: the parser sniffs the gzip magic bytes
                    // itself, so gating on a ".gz" suffix added nothing and
                    // could take it away — OkHttp transparently inflates a body
                    // whenever it set Accept-Encoding itself, and wrapping an
                    // already-inflated stream in GZIPInputStream throws, losing
                    // a pack that was downloaded intact.
                    body.byteStream().use {
                        XmltvParser.parse(
                            it,
                            windowStartMs = now - 30L * 3600 * 1000,
                            windowEndMs = now + 48L * 3600 * 1000,
                        )
                    }
                }
            }.getOrElse { e ->
                android.util.Log.w("Agoro", "EPG pack failed ($u): ${e.message}")
                null
            }
            if (one != null) {
                merger.add(one)
                // Publish after every pack: the first one carries most of the
                // bindings, so the grid fills within seconds of it landing
                // instead of waiting out the whole queue.
                latest = merger.build()
                latest?.let(onPartial)
            }
        }
        return latest ?: throw IOException("No guide pack could be loaded")
    }


    /**
     * Loads the XMLTV guide. A user-set override URL (e.g. an epgshare01
     * pack) wins; otherwise Xtream's xmltv.php or the M3U url-tvg/epgUrl.
     */
    suspend fun loadEpg(overrideUrl: String? = null) {
        lastEpgOverride = overrideUrl
        val source = activeSource.first() ?: return
        // A manifest names the guide feeds its channel ids came from. Without
        // them the ids resolve to nothing and every row reads "No information",
        // so they take precedence over the provider's own guide — which is
        // where they'd otherwise land, and which this provider fills sparsely.
        val manifestFeeds = if (overrideUrl.isNullOrBlank()) manifestGuideUrls(source) else emptyList()
        val urls = when {
            !overrideUrl.isNullOrBlank() -> listOf(overrideUrl)
            manifestFeeds.isNotEmpty() -> manifestFeeds
            else -> listOfNotNull(
                when (source) {
                    is PlaylistSource.Xtream -> xtreamClient(source).xmltvUrl
                    is PlaylistSource.M3u ->
                        source.epgUrl?.takeIf { it.isNotBlank() } ?: lastM3uTvgUrl
                }
            )
        }
        val url = urls.firstOrNull()
        if (url == null) {
            _epg.value = EpgState.Error("No EPG source configured for this playlist")
            return
        }
        // One download at a time, and don't re-fetch the same guide within 15 min
        // (content republishes — e.g. logo enrichment — would otherwise re-trigger it).
        epgMutex.withLock {
            val fresh = url == lastEpgUrl &&
                System.currentTimeMillis() - lastEpgLoadedAt < 15 * 60_000 &&
                _epg.value is EpgState.Ready
            if (fresh) return@withLock
            // The last run's merged guide, published instantly: a slightly
            // stale grid beats minutes of spinner while a dozen packs download.
            // Younger than the app's own 6-hour refresh cycle there is nothing
            // to fetch at all; older, the download still runs behind it. The
            // 48h ceiling is the guide window — beyond it the cache holds
            // nothing that is still on air.
            if (_epg.value !is EpgState.Ready) {
                val cached = withContext(Dispatchers.IO) { readEpgCache() }
                if (cached != null && cached.url == url) {
                    val age = System.currentTimeMillis() - cached.savedAtMs
                    if (age < 48 * 3600_000L) {
                        _epg.value = EpgState.Ready(cached.data)
                        lastEpgUrl = url
                        lastEpgLoadedAt = cached.savedAtMs
                        if (age < 6 * 3600_000L) return@withLock
                    }
                }
            }
            // Progressive publishing is for a cold start only: with a full
            // cached guide on screen, a one-pack partial would briefly shrink
            // the grid before the fold catches back up.
            val publishPartials = _epg.value !is EpgState.Ready
            if (publishPartials) _epg.value = EpgState.Loading
            withContext(Dispatchers.IO) {
                runCatching {
                    // Several feeds merge into one guide: the manifest's ids are
                    // spread across a handful of packs, and a viewer wants one
                    // grid, not a source picker.
                    if (urls.size > 1) return@runCatching fetchAndMerge(urls) { partial ->
                        if (publishPartials) _epg.value = EpgState.Ready(partial)
                    }
                    val request = Request.Builder().url(url).header("User-Agent", "Agoro/2.1").build()
                    http.newCall(request).execute().use { resp ->
                        // Plain language for the viewer, the status for the
                        // bug report. The banner shows neither of these now
                        // (GuideNoticeBar writes its own single line), so the
                        // code is carried purely so logcat can tell 403
                        // "account expired" from 502 "guide server down" —
                        // which the prose alone cannot, and the comment here
                        // used to claim it did.
                        if (!resp.isSuccessful) throw IOException(
                            if (resp.code == 404) "Your provider isn't publishing a guide. (HTTP 404)"
                            else "Your provider's guide server didn't respond. (HTTP ${resp.code})"
                        )
                        val body = resp.body ?: throw IOException("Empty guide response")
                        val now = System.currentTimeMillis()
                        XmltvParser.parse(
                            body.byteStream(),
                            windowStartMs = now - 30L * 3600 * 1000,
                            windowEndMs = now + 48L * 3600 * 1000,
                        )
                    }
                }.onSuccess { data ->
                    _epg.value = EpgState.Ready(data)
                    lastEpgUrl = url
                    lastEpgLoadedAt = System.currentTimeMillis()
                    // Written only on a real fetch — re-stamping the file when a
                    // refresh fails would disguise stale data as fresh.
                    writeEpgCache(url, data)
                }.onFailure { e ->
                    android.util.Log.w("Agoro", "EPG load failed: ${e.message}")
                    // Keep an existing guide rather than replacing it with an error.
                    if (_epg.value !is EpgState.Ready) {
                        _epg.value = EpgState.Error(e.message ?: "Failed to load the guide")
                    }
                }
            }
        }
    }

    private class ResolvedEpg(
        val bundle: ContentBundle,
        val data: XmltvData,
        val resolution: EpgMatcher.Resolution,
    )

    @Volatile
    private var resolvedEpg: ResolvedEpg? = null

    /**
     * The channel→guide resolution for the current (bundle, guide) pair,
     * computed on first request and cached by reference identity — the same
     * discipline as the view model's nowNext cache. Call off the main
     * thread; [programsFor] only ever READS the cache, because it is called
     * per channel from composition.
     */
    fun resolveEpg(): EpgMatcher.Resolution? {
        val bundle = (_content.value as? ContentState.Ready)?.bundle ?: return null
        val data = (_epg.value as? EpgState.Ready)?.data ?: return null
        resolvedEpg?.let { if (it.bundle === bundle && it.data === data) return it.resolution }
        val resolution = EpgMatcher.resolve(bundle.channels, data)
        resolvedEpg = ResolvedEpg(bundle, data, resolution)
        return resolution
    }

    /**
     * Programmes for a channel from the loaded XMLTV data. Served from the
     * fuzzy resolution when it is warm; before that, the original exact
     * lookups (tvg-id, then display name) keep the guide working — this path
     * must never compute the resolution itself.
     */
    fun programsFor(channel: LiveChannel): List<EpgProgram> {
        val data = (_epg.value as? EpgState.Ready)?.data ?: return emptyList()
        val bundle = (_content.value as? ContentState.Ready)?.bundle
        resolvedEpg?.let { cache ->
            if (cache.bundle === bundle && cache.data === data) {
                return cache.resolution.byChannelId[channel.id]
                    ?.let { data.programmes[it] }
                    ?: emptyList()
            }
        }
        channel.epgId?.lowercase()?.let { id ->
            data.programmes[id]?.let { return it }
        }
        val byNameId = data.nameToId[channel.name.trim().lowercase()]
        return byNameId?.let { data.programmes[it] } ?: emptyList()
    }

    /**
     * Fills channel logos the manifest didn't supply, matching names against
     * the tv-logos repo. Runs after manifest curation and only touches
     * channels still without art, so the build-time matches win — the live
     * name matcher resolves ~1% of this provider's names, the manifest ~55%.
     */
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
                        backdrop = tmdb.backdropUrl,
                        reviews = tmdb.reviews,
                        cast = enriched.cast ?: tmdb.cast,
                        director = enriched.director ?: tmdb.director,
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
            backdrop = tmdb.backdropUrl,
            reviews = tmdb.reviews,
            cast = series.cast ?: tmdb.cast,
            director = series.director ?: tmdb.director,
        )
    }

    /**
     * Just the art TMDB has for a title — the cheap half of [movieDetails],
     * for filling a grid of provider entries that shipped no images. Returns
     * [ArtEntry.empty] for a title TMDB doesn't know, so the caller can record
     * the miss and stop asking; null only when the request itself failed.
     */
    suspend fun artworkFor(kind: String, title: String, year: Int?, tmdbKey: String): ArtEntry? =
        runCatching { TmdbClient(http, tmdbKey).art(kind, title, year) }.getOrNull()

    /** Episodes for [series]; empty = the provider has none, null = the fetch failed. */
    suspend fun episodesFor(series: Series): List<Episode>? {
        series.episodes?.let { return it }
        val source = activeSource.first() as? PlaylistSource.Xtream ?: return emptyList()
        // Caches written before the xtreamId field existed deserialize it as
        // null, which made every series "No episodes found" until a successful
        // refresh. The numeric id also lives inside the series id ("series:123")
        // — recover it from there.
        val id = series.xtreamId
            ?: series.id.removePrefix("series:").toIntOrNull()
            ?: return emptyList()
        return try {
            xtreamClient(source).seriesEpisodes(id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // runCatching here used to swallow cancellation too, which latched
            // an empty list into the detail screen with no way to retry.
            throw e
        } catch (e: Exception) {
            android.util.Log.w("Agoro", "Episode load failed for ${series.name}: ${e.message}")
            null
        }
    }

    /** Provider account health, or null for M3U sources. */
    suspend fun accountInfo(): XtreamClient.AccountInfo? {
        val source = activeSource.first() as? PlaylistSource.Xtream ?: return null
        return xtreamClient(source).accountInfo()
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

        const val EPGSHARE_BASE = "https://epgshare01.online/epgshare01/epg_ripper_"
        /**
         * Region -> the feeds worth fetching for it, smallest useful first.
         * US_LOCALS1 is 55 MB and is listed last so a slow line still gets the
         * general feeds; it is the only source for affiliate schedules.
         */
        val EPGSHARE_BY_REGION = mapOf(
            "US" to listOf("US2", "US_SPORTS1", "US_LOCALS1"),
            "UK" to listOf("UK1", "IE1"),
            "CA" to listOf("CA2"),
            "AFR" to listOf("ZA1", "NG1", "KE1"),
        )
        }
}
