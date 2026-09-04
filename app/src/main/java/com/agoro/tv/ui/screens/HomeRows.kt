package com.agoro.tv.ui.screens

import com.agoro.tv.MainViewModel
import com.agoro.tv.data.ArtworkUrl
import com.agoro.tv.data.Category
import com.agoro.tv.data.ContentBundle
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.data.Movie
import com.agoro.tv.data.QualityTag
import com.agoro.tv.data.Series

/**
 * The Home lounge's row contents, as pure functions so the joins and their
 * ordering rules are unit-testable. The composable only renders what these
 * return.
 */

/** One card in the Continue Watching row, newest first. */
internal sealed interface ContinueCard {
    val progress: Float?

    data class MovieCard(val movie: Movie, override val progress: Float?) : ContinueCard
    data class SeriesCard(val series: Series, override val progress: Float?) : ContinueCard
}

/**
 * Joins resume positions with the bundle. [resumePositions] decodes from a
 * JSON LinkedHashMap, so key order is insertion order — oldest first; walked
 * reversed here so the row reads newest-first. A series appears once, at the
 * position of its most recently watched episode. URLs that resolve to nothing
 * (a movie gone from the playlist, an episode played before origins were
 * recorded) are skipped silently.
 *
 * Takes the catalogue's maps, not its lists: this used to `associateBy` the
 * 23,000-title movie list on every call, and it was called in composition on
 * every bundle publish. [CatalogIndex] builds those maps once.
 */
internal fun buildContinueWatching(
    movieByUrl: Map<String, Movie>,
    seriesById: Map<String, Series>,
    episodeOrigins: Map<String, String>,
    resumePositions: Map<String, Long>,
    resumeProgress: Map<String, Float>,
    /**
     * Episode url → when it finished; see [com.agoro.tv.data.PlayerPrefs.watchedAt].
     *
     * A show whose episode the viewer FINISHED belongs in this row — that is
     * the moment they are most likely to want the next one — but finishing is
     * exactly what deletes a resume position, so before this the show simply
     * dropped out. Films are the opposite and are deliberately not read from
     * here: a finished film is finished, and putting it back would be the row
     * refusing to let anything go.
     */
    watchedAt: Map<String, Long> = emptyMap(),
    limit: Int = 20,
): List<ContinueCard> {
    val seenSeries = HashSet<String>()
    return buildList {
        // Part-way through first, newest first. A thing the viewer is in the
        // middle of is a stronger call to action than one they finished, and
        // positions carry no timestamp to interleave the two by anyway —
        // their recency is the map's own insertion order.
        for (url in resumePositions.keys.toList().asReversed()) {
            if (size >= limit) break
            val movie = movieByUrl[url]
            if (movie != null) {
                add(ContinueCard.MovieCard(movie, resumeProgress[url]))
                continue
            }
            val fromSeries = episodeOrigins[url]?.let { seriesById[it] } ?: continue
            if (seenSeries.add(fromSeries.id)) {
                add(ContinueCard.SeriesCard(fromSeries, resumeProgress[url]))
            }
        }
        if (size >= limit || watchedAt.isEmpty()) return@buildList
        // Then the shows whose last episode ran to the end, most recently
        // finished first. No progress bar: the episode behind the card is
        // done, and a full bar would say "nearly finished" about a show the
        // viewer is in the middle of. Which episode comes next is the series
        // page's answer — the episode list is fetched per show and is not
        // known here.
        for ((seriesId, _) in newestFinishBySeries(watchedAt, episodeOrigins)) {
            if (size >= limit) break
            if (seriesId in seenSeries) continue
            val show = seriesById[seriesId] ?: continue
            seenSeries.add(seriesId)
            add(ContinueCard.SeriesCard(show, null))
        }
    }
}

/**
 * Series id → when its most recently finished episode finished, newest first.
 *
 * Both shelves that surface a finished show — Home's Continue watching row and
 * the Shows tab's own — need exactly this, and had it written out twice. A
 * recency cutoff or a cap would have been added to one of them.
 */
internal fun newestFinishBySeries(
    watchedAt: Map<String, Long>,
    episodeOrigins: Map<String, String>,
): List<Pair<String, Long>> =
    watchedAt.asSequence()
        .mapNotNull { (url, at) -> episodeOrigins[url]?.let { it to at } }
        .groupingBy { it.first }
        .fold(0L) { newest, (_, at) -> maxOf(newest, at) }
        .toList()
        .sortedByDescending { it.second }

/** One card in a plain catalogue row — Recently added, and the day-one shelves. */
internal sealed interface CatalogCard {
    data class MovieCard(val movie: Movie) : CatalogCard
    data class SeriesCard(val series: Series) : CatalogCard
}

/**
 * Films and box sets the provider most recently put in its library, newest
 * first. Built from Xtream's `added`/`last_modified`, so an M3U playlist — and
 * a panel that leaves the field blank — simply has no such row rather than one
 * ordered by nothing.
 *
 * [minimum] guards against the half-populated case: a shelf carrying two cards
 * next to shelves carrying twenty reads as a bug, and "recently added" drawn
 * from three timestamps isn't telling the truth about the library anyway.
 */
internal fun buildRecentlyAdded(
    movies: List<Movie>,
    series: List<Series>,
    limit: Int = 20,
    minimum: Int = 4,
): List<CatalogCard> {
    // Bounded insertion rather than sorting the catalogue. This walks every
    // title it is given — the whole dated catalogue, 20,000 on an ordinary
    // playlist — to keep twenty of them, so the old "pair up all of them,
    // sort all of them, take 20" cost both a full n log n and an allocation
    // per dated title. It runs off the main thread now (see [buildCatalog]),
    // but a walk that allocates nothing for the titles it rejects is still
    // the difference between a pass over 20,000 longs and 20,000 objects.
    //
    // Ties keep insertion order (the scan stops at the first entry that is
    // not strictly older), so movies still precede series at an identical
    // timestamp exactly as the stable sort left them.
    val topAdded = ArrayList<Long>(limit)
    val topCards = ArrayList<CatalogCard>(limit)
    var dated = 0

    /**
     * Where [added] would sit in the top list, or -1 when it would not make
     * it. Asked BEFORE the card exists: building the card first meant one
     * allocation per dated title in the catalogue for the twenty that were
     * kept, and on a playlist where every film carries a date that was the
     * whole catalogue.
     */
    fun slotFor(added: Long): Int {
        dated++
        if (topCards.size == limit && added <= topAdded[limit - 1]) return -1
        var at = topCards.size
        while (at > 0 && topAdded[at - 1] < added) at--
        return if (at >= limit) -1 else at
    }

    fun insert(at: Int, added: Long, card: CatalogCard) {
        topAdded.add(at, added)
        topCards.add(at, card)
        if (topCards.size > limit) {
            topAdded.removeAt(limit)
            topCards.removeAt(limit)
        }
    }

    movies.forEach { m ->
        val added = m.addedMs ?: return@forEach
        val at = slotFor(added)
        if (at >= 0) insert(at, added, CatalogCard.MovieCard(m))
    }
    series.forEach { s ->
        val added = s.addedMs ?: return@forEach
        val at = slotFor(added)
        if (at >= 0) insert(at, added, CatalogCard.SeriesCard(s))
    }
    if (dated < minimum) return emptyList()
    return topCards
}

/**
 * How a title is named in the hidden-from-Home set.
 *
 * The catalogue id, not the stream url: a url carries the provider's stream id
 * and those get re-issued, so a title that came back under a new id would
 * quietly reappear on Home after being dismissed.
 */
internal fun movieHomeKey(movie: Movie) = "m:${movie.id}"
internal fun seriesHomeKey(series: Series) = "s:${series.id}"

// --- The catalogue index ----------------------------------------------------

/**
 * The open catalogue, indexed once per bundle.
 *
 * Built off the main thread by [MainViewModel.catalog]; Home, Movies and
 * Shows read it instead of walking the bundle themselves. Each of the three
 * used to re-derive its own view in composition: two filterNot passes over
 * the 23,000-title catalogue for the parental lock, an associateBy over it
 * for Continue watching, a filter over it on every category switch and a
 * sort of it for "Recently added" — on the main thread, and again on every
 * bundle publish and every return to the tab. Indexed once, a category is a
 * map lookup and a resumed film is one.
 */
internal class CatalogIndex(
    /**
     * The bundle this was built from. Screens compare by IDENTITY: the index
     * lands a beat after the bundle, and one built from the previous bundle
     * is not theirs to draw.
     */
    val bundle: ContentBundle,
    /** Categories outside the parental lock, in playlist order. */
    val movieCategories: List<Category>,
    val seriesCategories: List<Category>,
    /**
     * Titles outside locked categories, in playlist order, one card per
     * title at its best rung; see [foldVariants]. Every list on this index
     * is folded — only [movieByUrl] still knows the variants, because a
     * resume position points at the exact stream that was played.
     */
    val movies: List<Movie>,
    val series: List<Series>,
    val movieByUrl: Map<String, Movie>,
    val seriesById: Map<String, Series>,
    /**
     * Category id → its titles, folded. Anything filed under a category the
     * playlist never declared sits under [VOD_MORE], so it stays reachable.
     *
     * Folded per category, not once across the catalogue: a film the
     * provider filed under two categories belongs on both shelves, and it
     * is only a duplicate when it appears twice on the SAME one.
     */
    val moviesByCategory: Map<String, List<Movie>>,
    val seriesByCategory: Map<String, List<Series>>,
    /**
     * The titles added recently AND released recently, newest first — the
     * "Recently added" category in full; see [isRecentRelease] for why the
     * second half is there.
     */
    val newMovies: List<Movie>,
    val newSeries: List<Series>,
    /**
     * Genre → its titles, and the genre names worth a chip, alphabetically.
     *
     * Alphabetical rather than by size on purpose: the strip is something a
     * viewer walks along, and a chip that moves because a category grew is a
     * chip they have to look for every time.
     *
     * Empty for movies on a provider that does not send a genre with its VOD
     * listing — which is this one, today. The strip simply grows no genre
     * chips then, the same way "Continue watching" stays away until there is
     * something in it; run tools/manifest/enrich_vod.py and they appear.
     */
    val movieGenres: List<String>,
    val seriesGenres: List<String>,
    val moviesByGenre: Map<String, List<Movie>>,
    val seriesByGenre: Map<String, List<Series>>,
)

/**
 * How many titles a genre needs before it is worth a chip of its own.
 *
 * The provider's genre strings are free text and the long tail is one-offs —
 * a single film filed "Sport / Talk" earns a chip that leads to itself. The
 * titles are still reachable from their category and from search.
 */
internal const val GENRE_MIN_TITLES = 8

/**
 * How many genre shelves Home carries. Three: enough that the page has
 * something to browse below the personal rows, few enough that it does not
 * turn into the Series tab with extra steps.
 */
internal const val HOME_GENRE_SHELVES = 3

/**
 * How many titles a "Continue watching" shelf carries. Home's row has always
 * had a limit; the tabs' own shelves were bounded only by however many resume
 * positions existed, which stopped being a bound once a FINISHED episode
 * could hold a show there too.
 */
internal const val CONTINUE_SHELF_LIMIT = 20

/**
 * The chip a genre is shown under, when it is not shown under its own name.
 *
 * The provider writes seventeen genres and that is too many to walk along on a
 * remote, so the strip carries ten. Two kinds of entry, and no third:
 *
 *  - **The same genre spelled twice.** "Drame" is twelve French-language shows
 *    that would otherwise sit beside Drama's four and a half thousand as if it
 *    were a different thing. (Typos and single strays — "Mistery", "DRama" —
 *    need no entry: casing is folded by [genreKey] and [GENRE_MIN_TITLES]
 *    keeps the rest off the strip.)
 *
 *  - **A stray or a near-synonym folded into its parent.** Soap (67), Romance
 *    (10), Western (52), Talk (42) and Podcast (25) each earn a chip that
 *    leads almost nowhere; War & Politics (177) is what Action & Adventure
 *    already means on a listings panel; and Kids (319) and Family (462) are
 *    one chip everywhere else in television.
 *
 * What is deliberately NOT folded: nothing above a thousand titles loses its
 * own chip. Crime (1,648) and Mystery (1,067) are the tempting pair and they
 * stay apart — merging them would be the strip deciding for a viewer who
 * already knows which one they want. Animation (717) stays out of Kids &
 * Family for a different reason: a good deal of it is anime and adult
 * animation, and filing that under Kids says something untrue about it.
 */
private val GENRE_FOLD = mapOf(
    // one genre, two spellings
    "drame" to "Drama",
    // strays and near-synonyms
    "soap" to "Drama",
    "romance" to "Drama",
    "western" to "Action & Adventure",
    "war & politics" to "Action & Adventure",
    "talk" to "Documentary",
    "podcast" to "Documentary",
    "kids" to "Kids & Family",
    "family" to "Kids & Family",
)

/**
 * The genres in one provider genre string, normalised.
 *
 * Multi-genre titles arrive as one field with a separator that is not agreed
 * on: this panel writes "Animation / Comedy / Sci-Fi & Fantasy" for series and
 * the movie enrichment writes commas. Split on both, and keep "Sci-Fi &
 * Fantasy" whole — the ampersand is part of a name, not a separator.
 *
 * Case is folded for GROUPING and the first spelling seen wins the label,
 * because the provider is not consistent about it: "Drama" and "DRama" are one
 * genre spelled twice, and left apart the stray spelling takes titles with it
 * out of the chip a viewer actually presses. Genres are then folded onto the
 * chip that carries them; see [GENRE_FOLD].
 */
internal fun splitGenres(raw: String?): List<String> =
    raw?.split('/', ',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.map { g -> GENRE_FOLD[g.lowercase()] ?: g }
        // After the fold, and this is load-bearing: a show tagged
        // "Kids / Family" — or "Drama / Romance" — now names one chip twice,
        // and without this it would be indexed under it twice and appear in
        // that grid as two identical posters.
        ?.distinctBy { it.lowercase() }
        .orEmpty()

/** The key two spellings of one genre share; see [splitGenres]. */
internal fun genreKey(genre: String): String = genre.lowercase()

/**
 * The rating band a shelf of well-reviewed films is drawn from.
 *
 * Both ends matter, and the top one more than the bottom. The panel's ratings
 * are TMDB's vote average with no vote COUNT beside them, so the head of the
 * list is not the best films in the catalogue — it is the ones almost nobody
 * has scored. Counted on this catalogue: 309 titles sit at a flat 10.0 and
 * they are festival shorts and single-vote obscurities, while 7.8 to 8.5 holds
 * Pulp Fiction, The Return of the King, GoodFellas, Forrest Gump, Across the
 * Spider-Verse. A shelf headed "highly rated" that opens on a film nobody has
 * heard of is worse than no shelf.
 *
 * The floor keeps the row from running out into merely-average titles when the
 * band above it is thin.
 */
internal const val ACCLAIM_FLOOR = 7.4
internal const val ACCLAIM_CEILING = 8.7

/** How many titles a catalogue shelf carries. */
internal const val SHELF_LENGTH = 24

/**
 * The films worth a shelf of their own, best first.
 *
 * This exists because Home's film row was `movies.take(20)` — the first twenty
 * titles in the order the provider happened to list them, which is the whole of
 * "the rows feel random". Nothing about that order means anything: it is the
 * panel's insertion sequence.
 *
 * Ties break on recency rather than on playlist order, so a shelf of equally
 * rated films leads with the ones that arrived most recently instead of the
 * ones that were imported first.
 */
internal fun acclaimedMovies(
    movies: List<Movie>,
    hidden: Set<String> = emptySet(),
    limit: Int = SHELF_LENGTH,
): List<Movie> = movies.asSequence()
    .filter { it.rating != null && it.rating in ACCLAIM_FLOOR..ACCLAIM_CEILING }
    .filter { it.poster != null }
    .filterNot { movieHomeKey(it) in hidden }
    .sortedWith(compareByDescending<Movie> { it.rating }.thenByDescending { it.addedMs ?: 0L })
    .take(limit)
    .toList()

/**
 * A genre's shows, best first.
 *
 * A genre shelf is a browsing surface rather than a chart, so unlike
 * [acclaimedMovies] it does not drop what it cannot rank — everything in the
 * genre is eligible and the order is what changes. What it will not do is LEAD
 * with a score nobody gave: series ratings on this panel are coarse, whole and
 * half points with 244 shows at a flat 10.0, and a shelf that sorts on the raw
 * number opens on the same unvoted obscurities every time.
 *
 * So a rating above the band is treated as no rating at all rather than as the
 * best one. It sorts with the unrated, at the end, where recency orders it.
 */
internal fun genreShelf(
    shows: List<Series>,
    hidden: Set<String> = emptySet(),
    limit: Int = SHELF_LENGTH,
): List<Series> = shows.asSequence()
    .filter { it.poster != null }
    .filterNot { seriesHomeKey(it) in hidden }
    .sortedWith(
        compareByDescending<Series> { it.rating?.takeIf { r -> r <= ACCLAIM_CEILING } ?: 0.0 }
            .thenByDescending { it.addedMs ?: 0L }
    )
    .take(limit)
    .toList()

/**
 * The genres big enough to lead a Home shelf, biggest first.
 *
 * Home shows a fixed few and the browse tab shows them all, so this only has
 * to answer "which ones". Sorted by how much is behind them, because a shelf
 * the viewer can scroll for a while is worth more on a home screen than an
 * alphabetically-first one with nine titles in it.
 */
internal fun topGenres(
    genres: List<String>,
    byGenre: Map<String, List<Series>>,
    count: Int = Int.MAX_VALUE,
): List<String> = genres
    .sortedWith(compareByDescending<String> { byGenre[genreKey(it)]?.size ?: 0 }.thenBy { it })
    .take(count)

/**
 * How many years back a release still reads as new: this year and last.
 *
 * A film from late last year reaches streaming this year; anything older
 * reaching the row is the panel's doing, not the studio's.
 */
internal const val RECENT_RELEASE_YEARS = 1

/**
 * Whether a title's release year is recent enough for "Recently added".
 *
 * The provider's `added` is the panel's import time, not the film's release.
 * A provider bulk-importing a back-catalogue dates a thousand 1990s films
 * today, and a row ordered on `added` alone then reads "Recently added:
 * Speed, Twister, Ghostbusters II" — which is not what anyone opening that
 * row wanted to know. So the row asks both questions: added recently, and
 * released this year or last. A title with no year at all is out too — an
 * unscraped import is exactly the shape of a bulk dump, and "new" cannot be
 * claimed for a title that cannot say when it is from.
 *
 * The cost is honest: a genuinely new addition of an old classic, or a new
 * season landing on a long-running show (series carry the show's year, not
 * the season's), stays out of this one row. Both are still in their
 * categories, in search, and in Continue watching once played.
 */
internal fun isRecentRelease(year: Int?, nowYear: Int): Boolean =
    year != null && year >= nowYear - RECENT_RELEASE_YEARS

internal fun currentYear(): Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)

/**
 * One entry per (title, year), keeping the best advertised quality.
 *
 * Providers list the same title several times — a 4K rung beside an HD one,
 * an SD copy beside both — each a distinct stream with its own id. Live has
 * always folded those ([QualityTag.mergeBestQuality]); VOD folded only
 * "Recently added", so every category grid, every genre chip and the All
 * view showed the pile, with the 4K copy and the SD copy sitting next to
 * each other as two cards. Worse, whichever one the playlist happened to
 * list first is the one a viewer reached for.
 *
 * The survivor keeps the FIRST variant's POSITION, so whatever order the
 * caller built — playlist order, newest-added first — is untouched; the
 * fold changes only which stream the card at that spot opens. A list with
 * nothing to fold is returned as itself, so the common case allocates
 * nothing and a screen keying a remember on the list still sees the same
 * instance across rebuilds.
 *
 * The title is compared case- and space-insensitively. [Movie.name] and
 * [Series.name] are already the cleaned title, with the quality token and
 * the year stripped into their own fields ([ContentClassifier.cleanTitle]),
 * so "Title 4K" and "Title HD" reduce to one key. The year disambiguates a
 * remake from its original — two different films that share a name are both
 * kept — and an unknown year folds with an unknown year, which for a
 * catalogue that repeats a title is what it almost always means.
 *
 * Ranked by [QualityTag.rank], which puts an unknown quality BELOW SD: a
 * variant whose name never said what it was loses to one that did, and only
 * wins when it is the sole copy. Ties keep the first, so a fold decides
 * nothing it has no evidence for — and where the year is unknown too, a tie
 * keeps BOTH, because then nothing at all distinguishes a second rung of one
 * show from a second show of the same name.
 *
 * The survivor keeps its own STREAM and borrows the group's best ARTWORK. The
 * two do not come from the same place: the panel hands its top rung a poster
 * from a mirror that paints "4K UltraHD" and a gold "8K" onto every image it
 * serves, while the HD rung of the same title — folded away, invisible, and
 * about to be discarded — carries TMDB's clean one. Winning the quality
 * ranking is what put the badged poster on screen, so the fold that caused it
 * is where it is cheapest to undo: no network, no key, no second pass over
 * the catalogue, and it covers 399 series and 1,085 films on this panel.
 * Only ever upward — a clean poster is never traded for a badged one.
 */
internal inline fun <T> List<T>.foldVariants(
    name: (T) -> String,
    year: (T) -> Int?,
    quality: (T) -> String?,
    poster: (T) -> String?,
    withPoster: (T, String) -> T,
): List<T> {
    if (size < 2) return this
    // Key -> where its survivor sits in [out], so a better rung can replace
    // the one standing there without a second pass or a list per group.
    val at = HashMap<String, Int>(size * 2)
    // Key -> the best unbadged poster anyone in the group had, survivor or
    // not. Collected on the way past, because the variant carrying it is
    // usually the one the fold is in the middle of discarding.
    val clean = HashMap<String, String>()
    val out = ArrayList<T>(size)
    // Parallel to [out]: which group each survivor belongs to, so the artwork
    // pass below can find its group without re-deriving the key.
    val keyOf = ArrayList<String>(size)
    for (item in this) {
        val releaseYear = year(item)
        val key = name(item).trim().lowercase(java.util.Locale.ROOT) + "|" + (releaseYear ?: 0)
        poster(item)?.takeIf { it.isNotBlank() && !ArtworkUrl.isDoctored(it) }
            ?.let { clean.putIfAbsent(key, it) }
        val held = at[key]
        if (held == null) {
            at[key] = out.size
            out.add(item)
            keyOf.add(key)
            continue
        }
        val challenger = QualityTag.rank(quality(item))
        val standing = QualityTag.rank(quality(out[held]))
        when {
            challenger > standing -> out[held] = item
            challenger < standing -> Unit // folded away; the better rung stands
            // Same name, same year, same rung: a duplicate listing.
            releaseYear != null -> Unit
            // Same name, same rung, and NO year on either — there is nothing
            // here that says these are one title. The provider strips region
            // tags into the cleaned name, so "The Office (US)" and "The
            // Office (UK)" arrive as one name with no year between them, and
            // folding would take a whole show off the shelf. The key keeps
            // pointing at the first, so a genuine 4K rung arriving later
            // still upgrades it.
            else -> {
                out.add(item)
                keyOf.add(key)
            }
        }
    }
    // The artwork pass. Separate, and after: which variant survives is not
    // known until the whole list has been walked, and a poster handed to a
    // survivor that is then replaced by a better rung would be lost with it.
    var repainted = false
    for (i in out.indices) {
        val better = clean[keyOf[i]] ?: continue
        val had = poster(out[i])
        if (had.isNullOrBlank() || ArtworkUrl.isDoctored(had)) {
            out[i] = withPoster(out[i], better)
            repainted = true
        }
    }
    return if (out.size == size && !repainted) this else out
}

/** [foldVariants] over films; see there for the rule. */
internal fun List<Movie>.foldMovieVariants(): List<Movie> =
    foldVariants({ it.name }, { it.year }, { it.quality }, { it.poster },
        { m, art -> m.copy(poster = art) })

/** [foldVariants] over box sets; see there for the rule. */
internal fun List<Series>.foldSeriesVariants(): List<Series> =
    foldVariants({ it.name }, { it.year }, { it.quality }, { it.poster },
        { s, art -> s.copy(poster = art) })

internal fun buildCatalogIndex(
    bundle: ContentBundle,
    nowYear: Int = currentYear(),
    isLockedCategory: (String) -> Boolean,
): CatalogIndex {
    val lockedMovieIds = bundle.movieCategories
        .filter { isLockedCategory(it.name) }.mapTo(HashSet()) { it.id }
    val lockedSeriesIds = bundle.seriesCategories
        .filter { isLockedCategory(it.name) }.mapTo(HashSet()) { it.id }
    val movieCategories =
        if (lockedMovieIds.isEmpty()) bundle.movieCategories
        else bundle.movieCategories.filterNot { it.id in lockedMovieIds }
    val seriesCategories =
        if (lockedSeriesIds.isEmpty()) bundle.seriesCategories
        else bundle.seriesCategories.filterNot { it.id in lockedSeriesIds }
    // The bundle's own lists when nothing is locked, so a screen that keys a
    // remember on the list sees the same instance across rebuilds.
    val movies =
        if (lockedMovieIds.isEmpty()) bundle.movies
        else bundle.movies.filterNot { it.categoryId in lockedMovieIds }
    val series =
        if (lockedSeriesIds.isEmpty()) bundle.series
        else bundle.series.filterNot { it.categoryId in lockedSeriesIds }

    // One pass per list builds all three indexes; three passes over 23,000
    // titles is the kind of thing that is fine on a desktop and not on an A53.
    val knownMovieCategories = movieCategories.mapTo(HashSet()) { it.id }
    val movieByUrl = HashMap<String, Movie>(movies.size * 2)
    val moviesByCategory = HashMap<String, ArrayList<Movie>>()
    val moviesByGenre = HashMap<String, ArrayList<Movie>>()
    val movieGenreLabels = HashMap<String, String>()
    val newMovies = ArrayList<Movie>()
    for (movie in movies) {
        movieByUrl[movie.url] = movie
        val category = movie.categoryId?.takeIf { it in knownMovieCategories } ?: VOD_MORE
        moviesByCategory.getOrPut(category) { ArrayList() }.add(movie)
        for (g in splitGenres(movie.genre)) {
            movieGenreLabels.putIfAbsent(genreKey(g), g)
            moviesByGenre.getOrPut(genreKey(g)) { ArrayList() }.add(movie)
        }
        if (movie.addedMs != null && isRecentRelease(movie.year, nowYear)) newMovies.add(movie)
    }
    // Stable: titles the provider dated identically keep playlist order.
    newMovies.sortByDescending { it.addedMs }

    val knownSeriesCategories = seriesCategories.mapTo(HashSet()) { it.id }
    val seriesById = HashMap<String, Series>(series.size * 2)
    val seriesByCategory = HashMap<String, ArrayList<Series>>()
    val seriesByGenre = HashMap<String, ArrayList<Series>>()
    val seriesGenreLabels = HashMap<String, String>()
    val newSeries = ArrayList<Series>()
    for (show in series) {
        seriesById[show.id] = show
        val category = show.categoryId?.takeIf { it in knownSeriesCategories } ?: VOD_MORE
        seriesByCategory.getOrPut(category) { ArrayList() }.add(show)
        for (g in splitGenres(show.genre)) {
            seriesGenreLabels.putIfAbsent(genreKey(g), g)
            seriesByGenre.getOrPut(genreKey(g)) { ArrayList() }.add(show)
        }
        if (show.addedMs != null && isRecentRelease(show.year, nowYear)) newSeries.add(show)
    }
    newSeries.sortByDescending { it.addedMs }

    // One card per title, at its best rung — everywhere, not only in
    // "Recently added". Folded per SHELF rather than once over the
    // catalogue: a film filed under two categories belongs on both, and a
    // single global fold would take it off one of them. Within any one list
    // it now appears once, and the card opens the 4K copy rather than
    // whichever rung the playlist happened to name first. See [foldVariants].
    //
    // Done here, off the main thread with the rest of the index, so no
    // screen pays for it per category switch — the folds are linear and the
    // buckets were already built.
    val foldedMovies = movies.foldMovieVariants()
    val foldedSeries = series.foldSeriesVariants()
    val foldedMoviesByCategory = moviesByCategory.mapValues { it.value.foldMovieVariants() }
    val foldedSeriesByCategory = seriesByCategory.mapValues { it.value.foldSeriesVariants() }
    val foldedMoviesByGenre = moviesByGenre.mapValues { it.value.foldMovieVariants() }
    val foldedSeriesByGenre = seriesByGenre.mapValues { it.value.foldSeriesVariants() }

    return CatalogIndex(
        bundle = bundle,
        movieCategories = movieCategories,
        seriesCategories = seriesCategories,
        movies = foldedMovies,
        series = foldedSeries,
        // Keyed on EVERY variant's url, folded-away ones included: a resume
        // position was recorded against the stream the viewer actually
        // played, and folding a shelf must not lose them their place in it.
        movieByUrl = movieByUrl,
        seriesById = seriesById,
        moviesByCategory = foldedMoviesByCategory,
        seriesByCategory = foldedSeriesByCategory,
        newMovies = newMovies.foldMovieVariants(),
        newSeries = newSeries.foldSeriesVariants(),
        // Counted on the folded lists: a genre earns a chip on how many
        // titles it holds, not on how many times the provider listed them.
        movieGenres = foldedMoviesByGenre.keys
            .filter { foldedMoviesByGenre.getValue(it).size >= GENRE_MIN_TITLES }
            .map { movieGenreLabels.getValue(it) }
            .sorted(),
        seriesGenres = foldedSeriesByGenre.keys
            .filter { foldedSeriesByGenre.getValue(it).size >= GENRE_MIN_TITLES }
            .map { seriesGenreLabels.getValue(it) }
            .sorted(),
        moviesByGenre = foldedMoviesByGenre,
        seriesByGenre = foldedSeriesByGenre,
    )
}

/**
 * The personal shelves over a [CatalogIndex]: what was resumed, what was
 * just added, what a day-one viewer is greeted with. Cheap joins, but they
 * re-run on every resume-position write — once every few seconds of
 * playback — so they live beside the index rather than in composition.
 */
internal class Catalog(
    val index: CatalogIndex,
    /** Home's Continue watching row, newest first. */
    val continueWatching: List<ContinueCard>,
    /** Home's Recently added shelf, with "Not interested" titles removed. */
    val recentlyAdded: List<CatalogCard>,
    /**
     * The day-one rows: empty once the viewer has resumed anything. Filtered
     * before the cut, or hiding a title would leave a gap in the row rather
     * than pulling the next one up into it.
     */
    val starterMovies: List<Movie>,
    val starterSeries: List<Series>,
    /**
     * Home's permanent catalogue shelves — the ones that are there whether or
     * not anything has been watched.
     *
     * Before these, Home after your first film was Continue watching, your
     * channels and Recently added, and nothing else: every row was a record of
     * what you had already done. There was nothing to BROWSE, which is what a
     * home screen is mostly for.
     */
    val acclaimedMovies: List<Movie>,
    /** Heading -> that genre's shows. Ordered; see [topGenres]. */
    val genreShelves: List<Pair<String, List<Series>>>,
    /** Resumed films, newest first — the Movies tab's Continue watching. */
    val resumedMovies: List<Movie>,
    /** Series id → progress of its most recently watched episode. */
    val seriesProgress: Map<String, Float?>,
    /** Series with a watched episode, newest first — the Shows tab's Continue watching. */
    val resumedSeries: List<Series>,
)

internal fun buildCatalog(
    index: CatalogIndex,
    resumePositions: Map<String, Long>,
    resumeProgress: Map<String, Float>,
    episodeOrigins: Map<String, String>,
    hiddenTitles: Set<String>,
    /** Episode url → when it finished; see [buildContinueWatching]. */
    watchedAt: Map<String, Long> = emptyMap(),
    starterLength: Int = STARTER_ROW_LENGTH,
): Catalog {
    val continueWatching = buildContinueWatching(
        index.movieByUrl, index.seriesById, episodeOrigins, resumePositions, resumeProgress,
        watchedAt = watchedAt,
    )
    // Resume positions run oldest-first; both resumed lists read newest-first,
    // like Home's shelf — not playlist order.
    val newestFirst = resumePositions.keys.toList().asReversed()
    val resumedMovies = newestFirst.mapNotNull { index.movieByUrl[it] }

    // Series id → progress of the most recently watched episode. Insertion
    // order is oldest-first; the last write wins, so the newest episode's
    // progress is what the poster shows.
    val seriesProgress = HashMap<String, Float?>()
    for (url in resumePositions.keys) {
        val seriesId = episodeOrigins[url] ?: continue
        seriesProgress[seriesId] = resumeProgress[url]
    }
    val fromOrigins = newestFirst.mapNotNull { episodeOrigins[it] }.distinct()
        .mapNotNull { index.seriesById[it] }
    // M3U series carry their episodes inline, so a watched episode can be
    // found without an origin record. Read from seriesById, which is every
    // show: index.series is FOLDED, so a variant that lost the fold would
    // take its own episodes' resume positions off this shelf with it.
    val fromEpisodes = index.seriesById.values.filter { show ->
        show.id !in seriesProgress &&
            show.episodes?.any { it.url in resumePositions } == true
    }
    // And the shows whose last episode finished, which have no position left
    // to be found by — the Shows tab's own Continue watching had the same
    // hole as Home's row. Newest finish first, and never one already listed.
    //
    // Capped, unlike the two lists above. Those are bounded by the 200 resume
    // positions; the watch history holds 2,000 marks, so without this a
    // viewer who has finished an episode of 150 shows got a 150-card
    // "Continue watching" shelf — which is a library, not a shortcut.
    val listed = (fromOrigins + fromEpisodes).mapTo(HashSet()) { it.id }
    val fromFinished = newestFinishBySeries(watchedAt, episodeOrigins)
        .asSequence()
        .mapNotNull { (seriesId, _) -> seriesId.takeIf { it !in listed }?.let { index.seriesById[it] } }
        .take((CONTINUE_SHELF_LIMIT - listed.size).coerceAtLeast(0))
        .toList()

    val recentlyAdded = buildRecentlyAdded(index.newMovies, index.newSeries)
        .filterNot { card ->
            when (card) {
                is CatalogCard.MovieCard -> movieHomeKey(card.movie) in hiddenTitles
                is CatalogCard.SeriesCard -> seriesHomeKey(card.series) in hiddenTitles
            }
        }
    // A sequence, so the walk stops at the twentieth kept title instead of
    // filtering the whole catalogue to take twenty off the front.
    val watchedCatalogue = continueWatching.isNotEmpty()
    val starterMovies =
        if (watchedCatalogue) emptyList()
        else index.movies.asSequence()
            .filterNot { movieHomeKey(it) in hiddenTitles }.take(starterLength).toList()
    val starterSeries =
        if (watchedCatalogue) emptyList()
        else index.series.asSequence()
            .filterNot { seriesHomeKey(it) in hiddenTitles }.take(starterLength).toList()

    return Catalog(
        index = index,
        continueWatching = continueWatching,
        recentlyAdded = recentlyAdded,
        starterMovies = starterMovies,
        starterSeries = starterSeries,
        acclaimedMovies = acclaimedMovies(index.movies, hiddenTitles),
        // Rank, then build, then KEEP, then cut — in that order. Cutting to
        // three first and filtering after meant a thin genre deleted its slot
        // instead of yielding it: [topGenres] ranks on the raw bucket, while
        // [genreShelf] then drops what has no artwork or has been hidden, so a
        // third-placed genre with nine shows and three missing posters left Home
        // with two shelves while a forty-strong genre sat unused behind it.
        //
        // A sequence, so the walk stops at the third shelf that stands up rather
        // than sorting every genre in the catalogue. This runs on every
        // resume-position write.
        genreShelves = topGenres(index.seriesGenres, index.seriesByGenre)
            .asSequence()
            .map { genre ->
                genre to genreShelf(index.seriesByGenre[genreKey(genre)].orEmpty(), hiddenTitles)
            }
            .filter { it.second.size >= GENRE_MIN_TITLES }
            .take(HOME_GENRE_SHELVES)
            .toList(),
        resumedMovies = resumedMovies,
        seriesProgress = seriesProgress,
        resumedSeries = fromOrigins + fromEpisodes + fromFinished,
    )
}

/**
 * The hero a focused channel tile projects: the current programme when the
 * guide knows it, just the channel otherwise. Poster and backdrop stay null —
 * a channel logo blown up to ambient art reads as a broken image, not a hero.
 */
internal fun channelHero(channel: LiveChannel, nowNext: MainViewModel.NowNext?): HeroInfo {
    val now = nowNext?.now
    return HeroInfo(
        title = channel.displayName,
        poster = null,
        backdrop = null,
        chips = listOfNotNull("Live", channel.quality, now?.title),
        plot = now?.description,
        // Synopses live in the guide table, so the hero names the programme
        // it wants and the screen fills the text once it settles on one.
        plotKey = now?.id,
    )
}
