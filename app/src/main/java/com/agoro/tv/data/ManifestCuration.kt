package com.agoro.tv.data

/**
 * Applies a [CatalogueManifest] to a freshly fetched bundle.
 *
 * Runs after [CategoryCleaner], which has already merged near-duplicate
 * shelves and prettified their labels. This pass is the curation on top:
 * what to drop, which section a channel really belongs to, and which streams
 * are the same channel at different quality.
 *
 * Everything keys off the provider's stream id ([LiveChannel.xtreamId]), so an
 * M3U source — which has no stream ids — passes through untouched.
 */
object ManifestCuration {

    /** Section keys become the category model the rest of the app already speaks. */
    fun apply(bundle: ContentBundle, manifest: CatalogueManifest): ContentBundle {
        val liveCats = LinkedHashMap<String, Category>()
        val channels = ArrayList<LiveChannel>(bundle.channels.size)

        for (channel in bundle.channels) {
            val id = channel.xtreamId ?: run { channels += channel; continue }
            if (id in manifest.dropped) continue
            // Folded into another tile: its primary carries it as a fallback.
            if (id in manifest.collapsedAway) continue

            // Fail open. An unclassified channel used to be DELETED here —
            // no view could reach it — which made the manifest's coverage of
            // the provider's category list a correctness requirement rather
            // than a curation nicety. The moment the provider adds a category
            // the manifest has never seen, or a viewer's package differs from
            // the one it was built against, those channels simply vanished.
            // Now they keep the provider's own category: no curated shelf, but
            // present in "All channels", in search, and in the guide.
            // A tile that resolved its own shelf outranks the channel's
            // provider category — see [CatalogueManifest.tileShelf]. Without
            // it, a channel whose 4K variant was folded in natively came back
            // filed under region "4K", matched no kept territory, and was
            // deleted for owning the very source the fold added.
            val tile = manifest.tileShelf[id]
            val section = tile?.section ?: manifest.sectionFor(id, channel.categoryId)
            val region = tile?.region
                ?: section?.let { manifest.regionFor(id, channel.categoryId) }
            if (section != null) {
                if (section in manifest.hiddenSections) continue
                if (manifest.keptRegionSet.isNotEmpty() &&
                    region != null && region !in manifest.keptRegionSet
                ) continue
            }

            // One shelf per region+section. The label must carry the region:
            // four territories each have a "Sports" and a "News", and a tab bar
            // reading "News · Sports · News · Sports" tells a viewer nothing.
            // A shelf needs BOTH halves once the manifest works in
            // territories. A channel whose territory could not be resolved
            // keeps its provider category and stays reachable through All
            // channels, but opens no shelf of its own — three tier-filed
            // channels were otherwise enough to put a bare "Sports" tab in
            // the strip beside "Sports · DSTV", which reads as two different
            // things and sorts between the shelves it duplicates.
            val shelfId = when {
                section == null -> null
                region != null -> "$region|$section"
                manifest.keptRegions.isEmpty() -> section
                else -> null
            }
            val catId = shelfId ?: channel.categoryId
            if (shelfId != null) {
                liveCats.getOrPut(shelfId) {
                    Category(id = shelfId, name = shelfLabel(manifest, section!!, region))
                }
            }
            // Manifest artwork wins over the provider's: it was matched against
            // tv-logo/tv-logos at build time, where the in-app name matcher
            // resolves ~1% of this provider's channels and the manifest ~55%.
            val art = manifest.logo.channelLogo[id.toString()]
                ?: channel.logo?.takeIf { it.isNotBlank() }
            // The guide id the manifest resolved at build time. EpgMatcher's
            // first stage only accepts an epgId that the loaded XMLTV actually
            // carries, so pointing at a guide these ids don't belong to costs
            // nothing — resolution simply falls through to its later stages.
            val guideId = manifest.epg.channelMap[id.toString()]?.id
                ?.takeIf { it.isNotBlank() } ?: channel.epgId

            // The sources this tile folded away, as playable URLs. Collapsing
            // ~1,180 alternates into one tile only makes sense if the tile can
            // still reach them, so the player gets the same ladder the
            // manifest's "best quality first" ordering describes. Derived from
            // this channel's own URL because the folded streams are gone from
            // the bundle by now and only differ by stream id.
            val alternates = manifest.fallbacks[id].orEmpty()
                .mapNotNull { alt -> channel.url.replaceStreamId(id, alt) }

            channels += channel.copy(
                categoryId = catId,
                name = manifest.displayName[id.toString()] ?: channel.name,
                logo = art,
                epgId = guideId,
                fallbackUrls = alternates,
            )
        }

        val cleaner = manifest.vodNameRules?.let { VodNameCleaner(it) }

        // Movies: drop the folded duplicates, clean the name, and re-shelve on
        // the manifest's sections — the provider's 71 shelves ("TOP Kids",
        // "Netflix Docu") are brand bookkeeping, not a way to browse.
        val movieCats = LinkedHashMap<String, Category>()
        val movies = ArrayList<Movie>(bundle.movies.size)
        for (movie in bundle.movies) {
            val id = movie.xtreamId
            if (id != null && id in manifest.vodDropped) continue
            val section = manifest.categories.movies[movie.categoryId]?.section
            if (section != null && section in manifest.hiddenMovieSections) continue
            val catId = section ?: movie.categoryId
            if (section != null) {
                movieCats.getOrPut(section) { Category(id = section, name = manifest.label(section)) }
            }
            movies += movie.copy(
                categoryId = catId,
                name = cleaner?.clean(movie.name) ?: movie.name,
            )
        }

        // Series: recency and rating are the shelves; genre is the filter.
        val seriesCats = LinkedHashMap<String, Category>()
        val series = ArrayList<Series>(bundle.series.size)
        for (show in bundle.series) {
            val key = show.xtreamId?.toString()
            val section = key?.let { manifest.seriesSection[it] }
            val catId = section ?: show.categoryId
            if (section != null) {
                seriesCats.getOrPut(section) {
                    Category(id = section, name = SERIES_LABELS[section] ?: section)
                }
            }
            series += show.copy(
                categoryId = catId,
                name = cleaner?.clean(show.name) ?: show.name,
            )
        }

        // Sections render in the manifest's order, not discovery order. A
        // region-less shelf sorts by its section like any other rather than
        // being flung to the end: "SPORTS" has no '|', so asking for the
        // region ahead of it returned the whole key, matched no territory,
        // and scored 99.
        val ordered = liveCats.values.sortedWith(
            compareBy(
                { cat ->
                    val region = cat.id.substringBefore('|').takeIf { cat.id.contains('|') }
                    if (region == null) 0 else manifest.keptRegions.indexOf(region)
                        .takeIf { i -> i >= 0 } ?: 99
                },
                { manifest.sectionOrder.indexOf(it.id.substringAfter('|')).takeIf { i -> i >= 0 } ?: 99 },
            )
        )
        // The region suffix earns its place only when this viewer's catalogue
        // actually spans regions. [shelfLabel] can only gate on the manifest's
        // kept_regions — a constant four — so a package that resolves to one
        // territory got "· DSTV" stamped on every shelf, which distinguishes
        // nothing. The surviving set is only known here, after the pass.
        val regionsPresent = liveCats.keys.mapNotNull { key ->
            key.substringBefore('|').takeIf { key.contains('|') }
        }.toSet()
        val labelled = if (regionsPresent.size >= 2) ordered else ordered.map { cat ->
            cat.copy(name = manifest.label(cat.id.substringAfter('|')))
        }
        return bundle.copy(
            liveCategories = labelled,
            channels = channels,
            movieCategories = movieCats.values.sortedBy { movieOrder(manifest, it.id) }
                .ifEmpty { bundle.movieCategories },
            movies = movies,
            seriesCategories = seriesCats.values.sortedBy { SERIES_ORDER.indexOf(it.id) }
                .ifEmpty { bundle.seriesCategories },
            series = series,
        )
    }

    private val SERIES_LABELS = mapOf("NEW" to "Recently added", "TOP" to "Top rated", "ALL" to "All shows")
    private val SERIES_ORDER = listOf("NEW", "TOP", "ALL")

    /** "Sports" alone is ambiguous across four territories; "Sports · UK" is not. */
    private fun shelfLabel(manifest: CatalogueManifest, section: String, region: String?): String {
        val base = manifest.label(section)
        if (region == null || manifest.keptRegions.size < 2) return base
        val regionName = manifest.regionLabels[region] ?: region
        return "$base · $regionName"
    }

    /**
     * Swaps the stream id in an Xtream live URL ("…/live/user/pass/123.ts").
     * Null when the URL isn't that shape or doesn't carry the id we expect —
     * guessing a URL for a stream we cannot address is worse than no failover.
     */
    private fun String.replaceStreamId(from: Int, to: Int): String? {
        val head = substringBeforeLast('/', "")
        val tail = substringAfterLast('/', "")
        if (head.isEmpty() || tail.isEmpty()) return null
        val ext = tail.substringAfter('.', "")
        val stem = tail.substringBefore('.')
        if (stem.toIntOrNull() != from) return null
        return if (ext.isEmpty()) "$head/$to" else "$head/$to.$ext"
    }

    private fun movieOrder(manifest: CatalogueManifest, key: String): Int =
        manifest.sections.movies.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: 99

    /**
     * "4K-NF - Mating Season (2026) (US)" -> "Mating Season (2026)".
     * The prefix strip repeats: providers stack them ("4K-TOP - ").
     */
    class VodNameCleaner(rules: CatalogueManifest.VodNameRules) {
        private val prefix = rules.stripPrefix.takeIf { it.isNotBlank() }?.let { Regex(it) }
        private val quality = rules.stripQuality.takeIf { it.isNotBlank() }
            ?.let { Regex(it, RegexOption.IGNORE_CASE) }
        private val country = rules.stripCountry.takeIf { it.isNotBlank() }?.let { Regex(it) }
        private val repeat = rules.repeat.coerceIn(1, 5)
        private val spaces = Regex("""\s{2,}""")

        fun clean(raw: String): String {
            var t = raw
            prefix?.let {
                repeat(repeat) {
                    val next = t.replaceFirst(prefix, "")
                    if (next == t) return@repeat
                    t = next
                }
            }
            quality?.let { t = t.replace(it, "") }
            country?.let { t = t.replace(it, "") }
            return t.replace(spaces, " ").trim(' ', '-', '_', '|').ifBlank { raw }
        }
    }
}
