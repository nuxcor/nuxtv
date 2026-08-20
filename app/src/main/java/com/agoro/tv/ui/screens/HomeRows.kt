package com.agoro.tv.ui.screens

import com.agoro.tv.MainViewModel
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
 */
internal fun buildContinueWatching(
    movies: List<Movie>,
    series: List<Series>,
    episodeOrigins: Map<String, String>,
    resumePositions: Map<String, Long>,
    resumeProgress: Map<String, Float>,
    limit: Int = 20,
): List<ContinueCard> {
    val movieByUrl = movies.associateBy { it.url }
    val seriesById = series.associateBy { it.id }
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
    // title the playlist has — 20,000 is an ordinary size — to keep twenty of
    // them, and it runs during Home's first composition after each bundle
    // load, so the old "pair up all of them, sort all of them, take 20" cost
    // both a full n log n and an allocation per dated title on the critical
    // path of the app's first frame.
    //
    // Ties keep insertion order (the scan stops at the first entry that is
    // not strictly older), so movies still precede series at an identical
    // timestamp exactly as the stable sort left them.
    val topAdded = ArrayList<Long>(limit)
    val topCards = ArrayList<CatalogCard>(limit)
    var dated = 0

    fun offer(added: Long, card: CatalogCard) {
        dated++
        if (topCards.size == limit && added <= topAdded[limit - 1]) return
        var at = topCards.size
        while (at > 0 && topAdded[at - 1] < added) at--
        if (at >= limit) return
        topAdded.add(at, added)
        topCards.add(at, card)
        if (topCards.size > limit) {
            topAdded.removeAt(limit)
            topCards.removeAt(limit)
        }
    }

    movies.forEach { m -> m.addedMs?.let { offer(it, CatalogCard.MovieCard(m)) } }
    series.forEach { s -> s.addedMs?.let { offer(it, CatalogCard.SeriesCard(s)) } }
    if (dated < minimum) return emptyList()
    return topCards
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
    )
}
