package com.agoro.tv.ui.screens

import com.agoro.tv.MainViewModel
import com.agoro.tv.data.Category
import com.agoro.tv.data.ContentBundle
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.data.Movie
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
    limit: Int = 20,
): List<ContinueCard> {
    val seenSeries = HashSet<String>()
    return buildList {
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
    }
}

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
    /** Titles outside locked categories, in playlist order. */
    val movies: List<Movie>,
    val series: List<Series>,
    val movieByUrl: Map<String, Movie>,
    val seriesById: Map<String, Series>,
    /**
     * Category id → its titles. Anything filed under a category the playlist
     * never declared sits under [VOD_MORE], so it stays reachable.
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
 * One entry per (title, year), keeping the first — used on a list already in
 * newest-first order so the survivor is the newest. The title is compared
 * case- and space-insensitively; the year disambiguates a remake from its
 * original, so two different films that share a name are both kept.
 */
internal inline fun <T> List<T>.dedupeByTitle(
    name: (T) -> String,
    year: (T) -> Int?,
): List<T> {
    val seen = HashSet<String>(size)
    val out = ArrayList<T>(size)
    for (item in this) {
        val key = name(item).trim().lowercase(java.util.Locale.ROOT) + "|" + (year(item) ?: 0)
        if (seen.add(key)) out.add(item)
    }
    return out
}

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
    // One card per title. Providers list the same film several times — a
    // 4K rung beside an HD one, the same movie dropped into two categories —
    // each a distinct stream with its own id, all dated within days. Recently
    // added is the one row that walks the flat list newest-first, so it was
    // the one that showed the pile. The name is already the cleaned title
    // (quality and year live in their own fields) and every entry here has a
    // year, so (name, year) folds the variants and keeps the newest-added.
    val dedupedMovies = newMovies.dedupeByTitle({ it.name }, { it.year })

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
    val dedupedSeries = newSeries.dedupeByTitle({ it.name }, { it.year })

    return CatalogIndex(
        bundle = bundle,
        movieCategories = movieCategories,
        seriesCategories = seriesCategories,
        movies = movies,
        series = series,
        movieByUrl = movieByUrl,
        seriesById = seriesById,
        moviesByCategory = moviesByCategory,
        seriesByCategory = seriesByCategory,
        newMovies = dedupedMovies,
        newSeries = dedupedSeries,
        movieGenres = moviesByGenre.keys
            .filter { moviesByGenre.getValue(it).size >= GENRE_MIN_TITLES }
            .map { movieGenreLabels.getValue(it) }
            .sorted(),
        seriesGenres = seriesByGenre.keys
            .filter { seriesByGenre.getValue(it).size >= GENRE_MIN_TITLES }
            .map { seriesGenreLabels.getValue(it) }
            .sorted(),
        moviesByGenre = moviesByGenre,
        seriesByGenre = seriesByGenre,
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
    starterLength: Int = STARTER_ROW_LENGTH,
): Catalog {
    val continueWatching = buildContinueWatching(
        index.movieByUrl, index.seriesById, episodeOrigins, resumePositions, resumeProgress,
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
    // found without an origin record.
    val fromEpisodes = index.series.filter { show ->
        show.id !in seriesProgress &&
            show.episodes?.any { it.url in resumePositions } == true
    }

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
        resumedMovies = resumedMovies,
        seriesProgress = seriesProgress,
        resumedSeries = fromOrigins + fromEpisodes,
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
