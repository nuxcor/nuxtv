package com.nuxcor.nuxtv.ui.screens

import com.nuxcor.nuxtv.MainViewModel
import com.nuxcor.nuxtv.data.LiveChannel
import com.nuxcor.nuxtv.data.Movie
import com.nuxcor.nuxtv.data.Series

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
