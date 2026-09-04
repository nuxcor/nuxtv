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
        val events = ArrayList<LiveChannel>()

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
            // A tile carries its own section and would otherwise skip the
            // fold that sectionFor applies.
            val section = tile?.section?.let(manifest::foldSection)
                ?: manifest.sectionFor(id, channel.categoryId)
            val region = tile?.region
                ?: section?.let { manifest.regionFor(id, channel.categoryId) }
            if (section != null) {
                // A hidden section opens no shelf. PPV is kept anyway, on its
                // own list, because the Sport destination reads the fixtures
                // out of these slots' names — dropping them here is what left
                // live sport with nothing to show.
                if (section in manifest.hiddenSections) {
                    if (section == "PPV") events += channel
                    continue
                }
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
                // The merged territories share one shelf per genre. Four of
                // them each opening a News and a Sports put fourteen chips in
                // the strip, most of them the same word twice, and the same
                // channel sat on several of those rows at once. The build
                // folded those duplicates into one tile; this puts them on one
                // row. A territory outside the merge — DSTV — still opens its
                // own, because what it carries is genuinely its own.
                region != null && region in manifest.mergedRegions -> section
                region != null -> "$region|$section"
                manifest.keptRegions.isEmpty() -> section
                else -> null
            }
            val catId = shelfId ?: channel.categoryId
            if (shelfId != null) {
                liveCats.getOrPut(shelfId) {
                    // A merged shelf names the genre and nothing else — the
                    // territory stopped being what the row is about.
                    val shelfRegion = region.takeIf { shelfId != section }
                    Category(id = shelfId, name = shelfLabel(manifest, section!!, shelfRegion))
                }
            }
            // Manifest artwork wins over the provider's: it was matched against
            // tv-logo/tv-logos at build time, where the in-app name matcher
            // resolves ~1% of this provider's channels and the manifest ~55%.
            val art = manifest.logo.channelLogo[id.toString()]
                // A tile's own sources before the provider's icon: see
                // [CatalogueManifest.borrowedLogo] for why the primary is the
                // one member of a fold most likely to be missing a binding.
                ?: manifest.borrowedLogo(id)
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
            // The broadcaster's own feed leads when the tile names one, and
            // the provider's primary drops into the ladder right behind it:
            // still there for the day the public url rotates, and still the
            // url a favourite or a resume was saved under, which answersTo
            // finds in fallbackUrls. Recording keeps the provider url — the
            // recorder speaks Xtream, not HLS from an origin.
            val direct = manifest.directFeeds[id].orEmpty()
            val ladder = if (direct.isEmpty()) alternates
                         else direct.drop(1) + channel.url + alternates

            channels += channel.copy(
                categoryId = catId,
                name = manifest.displayName[id.toString()] ?: channel.name,
                logo = art,
                epgId = guideId,
                url = direct.firstOrNull() ?: channel.url,
                fallbackUrls = ladder,
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
            if (show.xtreamId != null && show.xtreamId in manifest.seriesDropped) continue
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
                genre = mergedGenre(show.genre, manifest.seriesGenreAdd[key].orEmpty()),
            )
        }

        // Every shelf gets a readable order — alphabetical, with the metro
        // locals grouped by market. See [orderChannels].
        orderChannels(channels, manifest)

        // Genre first, territory second. Sections render in the manifest's
        // order, not discovery order; a region-less shelf sorts by its section
        // like any other rather than being flung to the end ("SPORTS" has no
        // '|', so asking for the region ahead of it returned the whole key,
        // matched no territory, and scored 99).
        //
        // Territory used to be the primary key, which sent every shelf a
        // territory kept for itself to the end of the strip — DStv landed past
        // Streaming Networks and 24/7, rows a viewer reaches for far less
        // often. What the strip reads as is genres, so a territory's row
        // belongs beside the genre it holds: DStv sits directly after the
        // merged Entertainment it is the counterpart to. kept_regions still
        // decides the order among territories, as the ORDERED list it is
        // documented to be — it is now the tie-break within a genre rather
        // than the top-level grouping.
        val ordered = liveCats.values.sortedWith(
            compareBy(
                { manifest.sectionOrder.indexOf(it.id.substringAfter('|')).takeIf { i -> i >= 0 } ?: 99 },
                { cat ->
                    val region = cat.id.substringBefore('|').takeIf { cat.id.contains('|') }
                    if (region == null) 0 else manifest.keptRegions.indexOf(region)
                        .takeIf { i -> i >= 0 } ?: 99
                },
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
        // A merged shelf carries no territory in its id. Where one exists, the
        // territories that kept their own shelf must keep their suffix too —
        // stripping it would put a bare "Sports" beside the merged "Sports".
        val hasMergedShelf = liveCats.keys.any { !it.contains('|') }
        // The other half of the same rule, from the other end. A territory
        // that opens exactly ONE shelf is named by the territory alone: the
        // genre half of "Entertainment · DSTV" distinguishes it from nothing,
        // because there is no second DStv row to tell it apart from, and the
        // word is already sitting three chips to its left on the merged
        // Entertainment shelf. What the viewer is choosing there is the
        // territory, so that is what the chip should say.
        //
        // Counted per territory rather than assumed: DStv is one row today
        // because its News and Sports shelves were folded away, and a lineup
        // that opens two again should get its suffix back without anyone
        // remembering to come here.
        val soleShelfRegions = regionsPresent.filter { region ->
            liveCats.keys.count { it.contains('|') && it.substringBefore('|') == region } == 1
        }.toSet()
        val labelled = if (hasMergedShelf || regionsPresent.size >= 2) {
            ordered.map { cat ->
                val region = cat.id.substringBefore('|').takeIf { cat.id.contains('|') }
                if (region != null && region in soleShelfRegions) {
                    cat.copy(name = manifest.regionLabels[region] ?: region)
                } else {
                    cat
                }
            }
        } else ordered.map { cat ->
            cat.copy(name = manifest.label(cat.id.substringAfter('|')))
        }
        return bundle.copy(
            liveCategories = labelled,
            channels = channels,
            events = events,
            movieCategories = movieCats.values.sortedBy { movieOrder(manifest, it.id) }
                .ifEmpty { bundle.movieCategories },
            movies = movies,
            seriesCategories = seriesCats.values.sortedBy { SERIES_ORDER.indexOf(it.id) }
                .ifEmpty { bundle.seriesCategories },
            series = series,
        )
    }

    private val SERIES_LABELS = mapOf("NEW" to "Recently added", "TOP" to "Top rated", "ALL" to "All series")
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

/**
 * The panel's genre string with the manifest's additions folded in.
 *
 * Case-insensitively deduplicated, because "Romance" arriving beside a
 * hand-typed "romance" would index the show under the chip twice and draw it
 * twice in that grid. The separator is " / ", which is what the panel uses on
 * the 3,943 entries that carry more than one genre, and what [splitGenres]
 * reads back.
 */
internal fun mergedGenre(base: String?, add: List<String>): String? {
    if (add.isEmpty()) return base
    val had = base?.split('/', ',', '&')?.map { it.trim().lowercase() }?.toSet().orEmpty()
    val extra = add.filter { it.trim().lowercase() !in had }
    if (extra.isEmpty()) return base
    return if (base.isNullOrBlank()) extra.joinToString(" / ")
    else (listOf(base) + extra).joinToString(" / ")
}

/**
 * Reorders metro-local channels into market groups, in place.
 *
 * Internal and free-standing so the ordering can be tested without a
 * manifest fetch or a provider: getting it wrong is invisible in review and
 * shows up as a Locals shelf that reads like a shuffled deck.
 */
internal fun orderChannels(channels: MutableList<LiveChannel>, manifest: CatalogueManifest) {
    // Shelf by shelf, and only within a shelf. Channels never cross a category
    // boundary, so a shelf's contents and the order the shelves appear in are
    // both untouched — and since numbers are POSITIONAL, only the numbers
    // inside a shelf move. Sorting the whole list at once would renumber the
    // entire catalogue.
    channels.indices.groupBy { channels[it].categoryId }.forEach { (_, slots) ->
        if (slots.size < 2) return@forEach
        val ordered = slots.map { channels[it] }.sortedWith(SHELF_ORDER(manifest))
        slots.forEachIndexed { i, slot -> channels[slot] = ordered[i] }
    }
}

/**
 * How one shelf reads top to bottom.
 *
 * Alphabetical, with the metro locals grouped by market ahead of it. The
 * provider's fetch order is arbitrary — it is neither the order the channels
 * were added nor anything a viewer can predict — and with numbers assigned by
 * position that arbitrariness became the channel numbers too. Alphabetical is
 * learnable: a viewer finds a channel by scanning to where its name belongs,
 * and its number stops moving between refreshes for no reason.
 *
 * Locals sort first by market, because "my city's stations" is the question
 * that shelf answers; alphabetical across markets would interleave them.
 */
private fun SHELF_ORDER(manifest: CatalogueManifest): Comparator<LiveChannel> = compareBy(
    { manifest.metroRank(manifest.metroOf[it.xtreamId]) },
    { manifest.metroOf[it.xtreamId].orEmpty() },
    // Within a market the networks keep one order everywhere, so the same
    // station sits in the same place in every city's run.
    //
    // INSIDE A MARKET, and nowhere else. Applied to every shelf, this rule
    // reached channels that are networks by name and national by nature: the
    // News shelf opened ABC News Live, NBC News NOW, CNBC, Fox News, Fox
    // Weather, Fox Business, LiveNOW from FOX — seven of its seventeen rows,
    // in an order no viewer can predict — and only then began the alphabet at
    // BBC News. A shelf that is alphabetical after its first seven rows reads
    // as a shelf with no order at all, which is what it was reported as.
    // Locals are the one shelf where the network run means something, because
    // there the question is "which of MY stations", asked city by city.
    {
        if (manifest.metroOf[it.xtreamId] == null) NETWORK_ORDER.size
        else NETWORK_ORDER.indexOf(networkOf(it.name)).takeIf { i -> i >= 0 }
            ?: NETWORK_ORDER.size
    },
    // Case- and accent-insensitive, so "beIN" files under B and "Á" under A
    // rather than both being flung to one end by raw code-point order.
    { EpgMatcher.fold(it.displayName) },
    { it.displayName },
)

private val NETWORK_ORDER = listOf("ABC", "CBS", "NBC", "FOX", "CW", "PBS", "UNIVISION")

private val NETWORK_RE =
    Regex("""\b(ABC|CBS|NBC|FOX|CW|PBS|UNIVISION)\d*\b""", RegexOption.IGNORE_CASE)

private fun networkOf(name: String): String? =
    NETWORK_RE.find(name)?.groupValues?.get(1)?.uppercase()
