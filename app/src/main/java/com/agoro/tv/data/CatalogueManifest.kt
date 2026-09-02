package com.agoro.tv.data

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
data class Sport(
    /**
     * League name to its teams. Teams, not competitions: the packs write the
     * competition inconsistently and "Premier League" also names a cricket
     * league in the Caribbean and a football league in Dominica.
     */
    val leagues: Map<String, List<String>> = emptyMap(),
    @SerialName("cue_minutes") val cueMinutes: Int = 60,
    /** Nicknames more than one sport answers to; these need both sides. */
    val ambiguous: List<String> = emptyList(),
    /**
     * Club name -> crest URL, resolved at build time by crest_match.py against
     * two public repositories. Keyed by the ROSTER's spelling, which is what
     * the parser puts in [SportsEvent.home] and [SportsEvent.away], so a row
     * looks a crest up with the string it already holds.
     *
     * Not exhaustive, by design: 203 of 239 clubs. MLS has no crest source
     * worth using, so its 32 clubs — and three Champions League entrants from
     * leagues neither repository carries — resolve to null and their rows fall
     * back to a monogram. A row must never assume a crest exists.
     */
    @SerialName("club_crest") val clubCrest: Map<String, String> = emptyMap(),
)

@Serializable
data class CatalogueManifest(
    @SerialName("manifest_version") val version: Int = 1,
    /** Build timestamp (ISO 8601) — the content version. [version] is the
     *  schema number and is identical across rebuilds, so freshness must
     *  compare this instead. Lexicographic order is chronological order. */
    val generated: String = "",
    val provider: Provider = Provider(),
    val sections: Sections = Sections(),
    val categories: Categories = Categories(),
    @SerialName("kept_regions") val keptRegions: List<String> = emptyList(),
    /**
     * Territories that share one shelf per genre — a single News, a single
     * Sports — rather than opening a shelf each. Their duplicates were folded
     * into one tile at build time, so the row holds one copy of a channel at
     * the best measured quality of any territory's feed. Anything not listed
     * here keeps its own shelf: DSTV does, holding only what is unique to it.
     */
    @SerialName("merged_regions") val mergedRegions: List<String> = emptyList(),
    /**
     * Sections folded into another wherever they appear — Kids, Documentary and
     * Music all read as Entertainment. Applied to whatever section a channel
     * resolves to, because the per-channel [mergedSection] table can only cover
     * channels a build pass enumerated, and a handful it missed were enough to
     * reopen a shelf holding one channel.
     */
    @SerialName("section_fold") val sectionFold: Map<String, String> = emptyMap(),
    /** Leagues the Sport destination carries, and how early a fixture is cued. */
    val sport: Sport? = null,
    @SerialName("region_labels") val regionLabels: Map<String, String> = emptyMap(),
    @SerialName("drop_stream_ids") val dropStreamIds: List<Int> = emptyList(),
    @SerialName("name_section") val nameSection: Map<String, String> = emptyMap(),
    @SerialName("merged_section") val mergedSection: Map<String, String> = emptyMap(),
    @SerialName("region_fix") val regionFix: Map<String, String> = emptyMap(),
    @SerialName("afr_assign") val afrAssign: Map<String, AfrEntry> = emptyMap(),
    @SerialName("uk_reassign") val ukReassign: Map<String, String> = emptyMap(),
    val collapse: Collapse = Collapse(),
    @SerialName("metro_locals") val metroLocals: Map<String, MetroLocal> = emptyMap(),
    /** Market order for the Locals shelf; see [metroRank]. */
    @SerialName("top_metros") val topMetros: List<String> = emptyList(),
    @SerialName("vod_drop") val vodDrop: List<Int> = emptyList(),
    /** series id -> NEW | TOP | ALL. */
    /**
     * Shows the catalogue does not carry. The first thing here was the anime
     * shelf (2026-08-26); before it, series had no drop list at all — every
     * pass only ever re-shelved them — so a shelf the catalogue did not want
     * had nowhere to say so.
     */
    @SerialName("series_drop") val seriesDrop: List<Int> = emptyList(),
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
        /**
         * The broadcaster's own public feeds, best first, played BEFORE any
         * of [sources]. Set on tiles whose provider copies are restreams of
         * a free channel the network streams itself (ABC News Live, NBC News
         * NOW): the origin is a hop closer, is not metered against the
         * line's one connection, and a provider-side fault on the copies
         * never reaches the viewer. The provider sources stay behind it as
         * the recorded fallbacks, so a public url that rotates costs one
         * failed tune, not the channel.
         */
        val direct: List<String> = emptyList(),
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
    val seriesDropped: Set<Int> by lazy { seriesDrop.toSet() }
    val keptRegionSet: Set<String> by lazy { keptRegions.toSet() }
    val hiddenSections: Set<String> by lazy {
        sections.live.filter { it.hidden }.map { it.key }.toSet()
    }

    /**
     * The movies half of the same flag. It was declared on both lists and read
     * on neither but live, so PPV's 115 live categories were removed while the
     * identically flagged "Events" movie shelf rendered — one flag, two
     * opposite behaviours.
     */
    val hiddenMovieSections: Set<String> by lazy {
        sections.movies.filter { it.hidden }.map { it.key }.toSet()
    }
    val sectionOrder: List<String> by lazy { sections.live.map { it.key } }

    /**
     * Every metro-local stream → the market it serves.
     *
     * The Locals shelf is ordered by this. Left in the provider's fetch
     * order it interleaved markets — a New York ABC, then a Houston FOX,
     * then a Boston CBS — which is unreadable when what a viewer wants is
     * "my city's stations". Sources as well as primaries, because a fold can
     * promote a source when the declared primary is dropped.
     */
    val metroOf: Map<Int, String> by lazy {
        buildMap {
            metroLocals.values.forEach { t ->
                if (t.metro.isBlank()) return@forEach
                put(t.primary, t.metro)
                t.sources.forEach { put(it, t.metro) }
            }
        }
    }

    /** Where a market sorts. Unlisted markets trail the named ones, alphabetically. */
    fun metroRank(metro: String?): Int {
        if (metro == null) return Int.MAX_VALUE
        val i = topMetros.indexOf(metro)
        return if (i >= 0) i else topMetros.size
    }

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

    /** Where a tile belongs, as its own record rather than its category's. */
    data class Shelf(val section: String, val region: String?)

    /**
     * The shelf a tile resolved for itself, keyed by the stream that renders it.
     *
     * This is authoritative and beats anything [categories] says, because the
     * build pass had information the app does not. A quality tier is filed in
     * the provider's region field — "4K", "8K" — and the pass resolves it to
     * the channel's real territory from its name prefix and from where the
     * rest of that channel's sources live, then votes on a section across all
     * of them. Reading the category instead meant a US tile whose primary
     * happened to sit in a 4K category came back as region "4K", matched no
     * kept territory, and took the whole channel off the shelf — the tier
     * already folded in natively as a source, and the channel deleted for it.
     *
     * Metro locals carry no section of their own: they are US local
     * affiliates by construction, whatever prefix the provider gave them.
     */
    val tileShelf: Map<Int, Shelf> by lazy {
        buildMap {
            collapse.live.values.forEach { t ->
                val primary = effectivePrimary(t.primary, t.sources) ?: return@forEach
                if (t.section.isNotBlank()) {
                    put(primary, Shelf(t.section, t.region.takeIf { it.isNotBlank() }))
                }
            }
            metroLocals.values.forEach { t ->
                val primary = effectivePrimary(t.primary, t.sources) ?: return@forEach
                put(primary, Shelf(METRO_SECTION, METRO_REGION))
            }
        }
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

    /**
     * Rendering primary -> the broadcaster's own feeds for its tile, best
     * first. Keyed like [fallbacks], on the stream that actually renders, so
     * a tile whose declared primary was dropped still finds its feeds.
     */
    val directFeeds: Map<Int, List<String>> by lazy {
        buildMap {
            collapse.live.values.forEach { t ->
                val feeds = t.direct.filter { it.isNotBlank() }
                if (feeds.isEmpty()) return@forEach
                val effective = effectivePrimary(t.primary, t.sources) ?: return@forEach
                put(effective, feeds)
            }
        }
    }

    /** The section keys this manifest actually declares. */
    private val declaredSections: Set<String> by lazy { sections.live.map { it.key }.toSet() }

    /**
     * A channel's section, or null when the manifest does not place it.
     *
     * Only a DECLARED key counts. The per-channel tables are hand-maintained
     * and can name a section that [Sections.live] never defines — 19 ids point
     * at "ALWAYS_ON", where the declared key is "24/7" — and an undeclared key
     * has no label and no place in the order, so it surfaced as a shelf
     * literally titled "ALWAYS_ON · United Kingdom", sorted last. Falling
     * through to the channel's own category puts those channels on a real
     * shelf instead of inventing one out of a typo.
     */
    fun sectionFor(streamId: Int, categoryId: String?): String? {
        val key = streamId.toString()
        val override = mergedSection[key]
            ?: afrAssign[key]?.section?.takeIf { it.isNotBlank() }
            ?: ukReassign[key]
            ?: nameSection[key]
        override?.let { applyMerge(streamId, it) }
            ?.takeIf { it in declaredSections }
            ?.let { return it }
        return categories.live[categoryId]?.section
            ?.let { applyMerge(streamId, it) }
            ?.takeIf { it in declaredSections }
    }

    private fun applyMerge(streamId: Int, section: String) =
        foldSection(mergedSection[streamId.toString()] ?: section)

    /** [sectionFold], for the paths that resolve a section without this class. */
    fun foldSection(section: String): String = sectionFold[section] ?: section

    /**
     * The territory a channel belongs to, or null when it has none.
     *
     * Blank counts as none: [CategoryRule.region] defaults to `""`, and a rule
     * that omits it produced the category id "|SPORTS" and the shelf label
     * "Sports · ". A quality tier counts as none too — the manifest files a
     * few categories as "4K"/"8K" where a territory belongs, and testing
     * those against [keptRegions] deleted the provider's whole 4K and 8K
     * lineup as if it were a country we do not serve. A tier is not a place,
     * so those channels shelve under the plain section instead. Territories
     * genuinely outside [keptRegions] — IE, AR — are still excluded, which is
     * what that list is for.
     *
     * The two per-channel reassignment tables name their own territory: a
     * channel moved by [afrAssign] is African and one moved by [ukReassign] is
     * British, whatever their provider category says. Without that, any such
     * channel whose category had no rule came out region-less and opened a
     * second, unlabelled "Sports" shelf beside "Sports · United Kingdom".
     */
    fun regionFor(streamId: Int, categoryId: String?): String? {
        val key = streamId.toString()
        val raw = regionFix[key]
            ?: when {
                afrAssign.containsKey(key) -> "AFR"
                ukReassign.containsKey(key) -> "UK"
                else -> categories.live[categoryId]?.region
            }
        return raw?.takeIf { it.isNotBlank() && it.uppercase() !in QUALITY_TIERS }
    }

    fun label(sectionKey: String): String =
        sections.live.firstOrNull { it.key == sectionKey }?.label
            ?: sections.movies.firstOrNull { it.key == sectionKey }?.label
            ?: sectionKey

    companion object {
        /** Bundled copy, so a first run with no network still gets a clean catalogue. */
        const val ASSET = "catalogue-manifest.json"

        /**
         * Values found in the region field that name a picture quality rather
         * than a place. Kept explicit rather than inferred from
         * [regionLabels]: that map names only the territories we serve, so
         * inferring would have quietly re-admitted the ones we don't.
         */
        private val QUALITY_TIERS = setOf("4K", "8K", "2K", "UHD", "FHD", "HD", "SD")

        /** Metro locals are US affiliates by construction; see [tileShelf]. */
        private const val METRO_SECTION = "LOCALS"
        private const val METRO_REGION = "US"
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

    /**
     * One load at a time. Three callers ask for the manifest at start-up —
     * the catalogue fetch, the guide's pack list and the Sport tab — within
     * the same second, and before this each of them ran the whole thing:
     * three downloads racing one temp file, and three decodes of two 1.3 MB
     * documents on a box where one decode is a visible pause. The first
     * caller does the work; the rest wait for its answer.
     */
    private val loadMutex = Mutex()

    /** Null when no manifest is available; callers fall back to the raw bundle. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    suspend fun load(remoteUrl: String? = DEFAULT_REMOTE): CatalogueManifest? {
        cached?.let { return it }
        return loadMutex.withLock {
            cached?.let { return@withLock it }
            withContext(BackgroundWork.dispatcher) {
                if (remoteUrl != null) refreshIfStale(remoteUrl)
                // Newest content wins, not simply "cache beats asset". An app
                // update ships a newer bundled manifest than whatever the cache
                // last fetched — but comparing [version] couldn't see that: it is
                // the SCHEMA number, 1 on both sides of every rebuild, and the
                // tie sent every comparison to the cache. A cached remote from
                // before the update then shadowed the new bundle for a full TTL,
                // resurrecting channels the new manifest had dropped. The
                // [generated] build stamp is the content version.
                //
                // Decided from the STAMPS, read off the first kilobyte of each
                // file, and only the winner is decoded. Both used to be decoded
                // in full to compare two short strings at the top of them.
                val cacheStamp = readStamp { cacheFile.takeIf { it.exists() }?.inputStream() }
                val assetStamp = readStamp { context.assets.open(CatalogueManifest.ASSET) }
                val parsed = if (cacheStamp != null && assetStamp != null) {
                    // Equal stamps keep the cache, as they always have.
                    if (assetStamp > cacheStamp) readAsset() ?: readCache()
                    else readCache() ?: readAsset()
                } else {
                    newerOfBoth()
                }
                parsed?.also { cached = it }
            }
        }
    }

    /**
     * The full comparison, decoding both: the fallback for a file whose head
     * does not say when it was built. A missing cache lands here too, and
     * costs one decode, because there is only one file to read.
     */
    private fun newerOfBoth(): CatalogueManifest? {
        val fromCache = readCache()
        val fromAsset = readAsset()
        return when {
            fromCache == null -> fromAsset
            fromAsset == null -> fromCache
            Stamp(fromAsset.version, fromAsset.generated) >
                Stamp(fromCache.version, fromCache.generated) -> fromAsset
            else -> fromCache
        }
    }

    /**
     * (schema version, build stamp) from the head of a manifest, without
     * decoding it. Null when the head cannot be read or does not carry
     * them — a hand-edited file with the keys elsewhere — which the caller
     * treats as "unknown", never as "older".
     */
    private fun readStamp(open: () -> java.io.InputStream?): Stamp? = runCatching {
        val head = (open() ?: return null).use { stream ->
            val buf = ByteArray(STAMP_HEAD_BYTES)
            var read = 0
            while (read < buf.size) {
                val n = stream.read(buf, read, buf.size - read)
                if (n < 0) break
                read += n
            }
            String(buf, 0, read, Charsets.UTF_8)
        }
        val generated = GENERATED_KEY.find(head)?.groupValues?.get(1) ?: return null
        val version = VERSION_KEY.find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        Stamp(version, generated)
    }.getOrNull()

    /** Orders as [load] always has: schema first, then the build stamp. */
    private data class Stamp(val version: Int, val generated: String) : Comparable<Stamp> {
        override fun compareTo(other: Stamp): Int =
            compareValuesBy(this, other, { it.version }, { it.generated })
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

        /**
         * How much of a manifest's head carries its stamps. Both keys sit in
         * the first hundred bytes of the file the build writes; a kilobyte
         * leaves room for a reformat without leaving room for doubt.
         */
        private const val STAMP_HEAD_BYTES = 4096
        private val GENERATED_KEY = Regex(""""generated"\s*:\s*"([^"]*)"""")
        private val VERSION_KEY = Regex(""""manifest_version"\s*:\s*(\d+)""")
    }
}
