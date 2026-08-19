package com.nuxcor.nuxtv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Provider-specific curation, shipped as data rather than compiled in.
 *
 * A raw playlist is not a catalogue: this provider ships 18,846 live streams
 * that reduce to ~790 real channels once regional duplicates, quality tiers,
 * dead event slots and channels for territories we don't serve are removed.
 * None of that is logic the app can derive — it is judgement about one
 * provider's lineup, so it lives in a manifest that changes without a release.
 *
 * The manifest never carries stream URLs or credentials. It is a set of
 * decisions keyed by the provider's own stream ids:
 *  - [dropStreamIds]   what never appears
 *  - [categories]      provider category -> section + region
 *  - [nameSection]     per-channel section override, where the category lies
 *  - [mergedSection]   section folded into another for a thin region
 *  - [regionFix]       channels the provider filed under the wrong territory
 *  - [collapse]        one tile, several sources, best quality first
 *  - [vodNameRules]    regexes that strip "4K-NF - " and friends
 */
@Serializable
data class CatalogueManifest(
    @SerialName("manifest_version") val version: Int = 1,
    val provider: Provider = Provider(),
    val sections: Sections = Sections(),
    val categories: Categories = Categories(),
    @SerialName("kept_regions") val keptRegions: List<String> = emptyList(),
    @SerialName("region_labels") val regionLabels: Map<String, String> = emptyMap(),
    @SerialName("drop_stream_ids") val dropStreamIds: List<Int> = emptyList(),
    @SerialName("name_section") val nameSection: Map<String, String> = emptyMap(),
    @SerialName("merged_section") val mergedSection: Map<String, String> = emptyMap(),
    @SerialName("region_fix") val regionFix: Map<String, String> = emptyMap(),
    @SerialName("afr_assign") val afrAssign: Map<String, AfrEntry> = emptyMap(),
    @SerialName("uk_reassign") val ukReassign: Map<String, String> = emptyMap(),
    val collapse: Collapse = Collapse(),
    @SerialName("metro_locals") val metroLocals: Map<String, MetroLocal> = emptyMap(),
    @SerialName("vod_drop") val vodDrop: List<Int> = emptyList(),
    /** series id -> NEW | TOP | ALL. */
    @SerialName("series_section") val seriesSection: Map<String, String> = emptyMap(),
    @SerialName("vod_name_rules") val vodNameRules: VodNameRules? = null,
    @SerialName("display_name") val displayName: Map<String, String> = emptyMap(),
    val logo: Logo = Logo(),
    val epg: Epg = Epg(),
) {
    @Serializable
    data class Epg(
        /** Guide feeds this manifest's ids come from, best first. */
        val sources: List<EpgSource> = emptyList(),
        /** stream id -> xmltv channel id, resolved at build time. */
        @SerialName("channel_map") val channelMap: Map<String, EpgBinding> = emptyMap(),
    )

    @Serializable
    data class EpgSource(
        val key: String = "",
        val label: String = "",
        val priority: Int = 99,
        val base: String? = null,
        val index: String? = null,
    )

    @Serializable
    data class EpgBinding(
        /** Which pack the id came from; ids are only valid within their pack. */
        val src: String = "",
        val id: String = "",
        /** The specific file inside that pack, e.g. "epg6". */
        val feed: String = "",
        val via: String = "",
    )

    @Serializable
    data class Logo(
        /** stream id -> artwork URL, matched at build time against tv-logo/tv-logos. */
        @SerialName("channel_logo") val channelLogo: Map<String, String> = emptyMap(),
        /** ABC/CBS/NBC… brand marks, for local affiliates with no station art. */
        @SerialName("network_fallback") val networkFallback: Map<String, String> = emptyMap(),
    )

    @Serializable data class Provider(val host: String = "", val protocol: String = "xtream")

    @Serializable
    data class Section(
        val key: String,
        val label: String,
        @SerialName("hidden_by_default") val hidden: Boolean = false,
    )

    @Serializable
    data class Sections(
        val live: List<Section> = emptyList(),
        val movies: List<Section> = emptyList(),
    )

    @Serializable data class CategoryRule(val section: String = "", val region: String = "")

    @Serializable
    data class Categories(
        val live: Map<String, CategoryRule> = emptyMap(),
        val movies: Map<String, CategoryRule> = emptyMap(),
    )

    @Serializable data class AfrEntry(val section: String = "")

    @Serializable
    data class CollapseTile(
        val section: String = "",
        val region: String = "",
        val primary: Int = 0,
        /** Best quality first; the player falls through on failure. */
        val sources: List<Int> = emptyList(),
    )

    @Serializable data class Collapse(val live: Map<String, CollapseTile> = emptyMap())

    @Serializable
    data class MetroLocal(
        val metro: String = "",
        val network: String = "",
        val primary: Int = 0,
        val label: String = "",
        val sources: List<Int> = emptyList(),
    )

    @Serializable
    data class VodNameRules(
        @SerialName("strip_prefix") val stripPrefix: String = "",
        @SerialName("strip_prefix_repeat") val repeat: Int = 3,
        @SerialName("strip_quality") val stripQuality: String = "",
        @SerialName("strip_trailing_country") val stripCountry: String = "",
    )

    // ---- lookups the curation pass needs, built once -----------------------

    val dropped: Set<Int> by lazy { dropStreamIds.toSet() }
    val vodDropped: Set<Int> by lazy { vodDrop.toSet() }
    val keptRegionSet: Set<String> by lazy { keptRegions.toSet() }
    val hiddenSections: Set<String> by lazy {
        sections.live.filter { it.hidden }.map { it.key }.toSet()
    }
    val sectionOrder: List<String> by lazy { sections.live.map { it.key } }

    /** (declared primary, every source) for collapse tiles and metro locals alike. */
    private val tiles: List<Pair<Int, List<Int>>> by lazy {
        collapse.live.values.map { it.primary to it.sources } +
            metroLocals.values.map { it.primary to it.sources }
    }

    /**
     * The stream that actually renders for each tile.
     *
     * The declared primary wins unless [dropStreamIds] also removes it — 165
     * tiles in the shipped manifest name a primary the drop list kills, and
     * with no promotion each of those tiles renders nothing at all: primary
     * dropped, every source folded away behind it. Promoting the best
     * surviving source keeps the channel on screen.
     */
    private fun effectivePrimary(primary: Int, sources: List<Int>): Int? =
        primary.takeIf { it !in dropped } ?: sources.firstOrNull { it !in dropped }

    val tilePrimaries: Set<Int> by lazy {
        tiles.mapNotNull { (primary, sources) -> effectivePrimary(primary, sources) }.toSet()
    }

    /**
     * Every stream folded into a tile, so only the primary renders.
     *
     * Collected in full before the primaries come back out. Removing each
     * tile's primary as that tile was folded made the result order-dependent:
     * a later tile listing an earlier tile's primary among its own sources
     * re-added it, and nothing removed it a second time — so 8 channels
     * disappeared depending only on map iteration order.
     */
    val collapsedAway: Set<Int> by lazy {
        buildSet {
            tiles.forEach { (_, sources) -> addAll(sources) }
            removeAll(tilePrimaries)
        }
    }

    /**
     * Rendering primary -> its other sources, best quality first, dropped ones
     * excluded. This is the failover the collapse pass promises: folding ~1,180
     * alternates into one tile is only safe if the tile can still reach them
     * when the primary is dead.
     */
    val fallbacks: Map<Int, List<Int>> by lazy {
        buildMap {
            tiles.forEach { (primary, sources) ->
                val effective = effectivePrimary(primary, sources) ?: return@forEach
                val alternates = sources.filter { it != effective && it !in dropped }
                if (alternates.isNotEmpty()) put(effective, alternates)
            }
        }
    }

    fun sectionFor(streamId: Int, categoryId: String?): String? {
        val key = streamId.toString()
        mergedSection[key]?.let { return it }
        afrAssign[key]?.section?.takeIf { it.isNotBlank() }?.let { return applyMerge(streamId, it) }
        ukReassign[key]?.let { return applyMerge(streamId, it) }
        nameSection[key]?.let { return applyMerge(streamId, it) }
        return categories.live[categoryId]?.section?.let { applyMerge(streamId, it) }
    }

    private fun applyMerge(streamId: Int, section: String) =
        mergedSection[streamId.toString()] ?: section

    fun regionFor(streamId: Int, categoryId: String?): String? =
        regionFix[streamId.toString()] ?: categories.live[categoryId]?.region

    fun label(sectionKey: String): String =
        sections.live.firstOrNull { it.key == sectionKey }?.label
            ?: sections.movies.firstOrNull { it.key == sectionKey }?.label
            ?: sectionKey

    companion object {
        /** Bundled copy, so a first run with no network still gets a clean catalogue. */
        const val ASSET = "catalogue-manifest.json"
    }
}

/**
 * Loads the manifest: bundled asset as the floor so the app is never without
 * one, and a cached remote copy on top, refreshed daily.
 *
 * The remote copy is the point of the whole design — the manifest is judgement
 * about one provider's lineup, and that lineup changes far more often than the
 * app ships. [DEFAULT_REMOTE] serves the same file the APK bundles, so
 * correcting a mis-shelved channel is a commit rather than a release.
 */
class ManifestRepository(
    private val context: Context,
    private val http: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cacheFile = File(context.cacheDir, "catalogue-manifest.json")
    @Volatile private var cached: CatalogueManifest? = null

    /** Null when no manifest is available; callers fall back to the raw bundle. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    suspend fun load(remoteUrl: String? = DEFAULT_REMOTE): CatalogueManifest? {
        cached?.let { return it }
        return withContext(Dispatchers.IO) {
            if (remoteUrl != null) refreshIfStale(remoteUrl)
            // Highest version wins, not simply "cache beats asset". An app
            // update ships a newer bundled manifest than whatever the cache
            // last fetched, and preferring the cache unconditionally let a
            // stale download shadow it until the TTL happened to expire.
            val fromCache = readCache()
            val fromAsset = readAsset()
            val parsed = when {
                fromCache == null -> fromAsset
                fromAsset == null -> fromCache
                fromAsset.version > fromCache.version -> fromAsset
                else -> fromCache
            }
            parsed?.also { cached = it }
        }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun readCache(): CatalogueManifest? = runCatching {
        if (!cacheFile.exists()) return null
        cacheFile.inputStream().use { json.decodeFromStream<CatalogueManifest>(it) }
    }.getOrNull()

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun readAsset(): CatalogueManifest? = runCatching {
        context.assets.open(CatalogueManifest.ASSET).use {
            json.decodeFromStream<CatalogueManifest>(it)
        }
    }.getOrNull()

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun refreshIfStale(url: String) {
        val fresh = cacheFile.exists() &&
            System.currentTimeMillis() - cacheFile.lastModified() < CACHE_TTL_MS
        if (fresh) return
        runCatching {
            val request = Request.Builder().url(url).header("User-Agent", "Agoro/2.1").build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return
                val body = resp.body ?: return
                // Write to a temp file first: a truncated download must not
                // replace a working manifest.
                val tmp = File(cacheFile.parentFile, "manifest.tmp")
                tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                // Parsed before it is trusted. Non-empty is not the same as
                // valid: a captive portal or an error page is a perfectly
                // long body, and renaming one over the cache would poison
                // every later start — the asset would still save us, but the
                // bad file would sit there looking fresh for a day at a time.
                val valid = runCatching {
                    tmp.inputStream().use { json.decodeFromStream<CatalogueManifest>(it) }
                }.getOrNull() != null
                if (valid) tmp.renameTo(cacheFile) else tmp.delete()
            }
        }
    }

    companion object {
        /**
         * The bundled asset's own path on the default branch, so the remote
         * copy and the shipped copy are literally the same file. Public repo,
         * so no credential is involved; any failure just leaves the asset in
         * charge.
         */
        const val DEFAULT_REMOTE =
            "https://raw.githubusercontent.com/nuxcor/nuxtv/main/" +
                "app/src/main/assets/catalogue-manifest.json"

        private const val CACHE_TTL_MS = 24L * 3600 * 1000
    }
}
