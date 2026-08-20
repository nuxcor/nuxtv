package com.agoro.tv

import com.agoro.tv.data.EpgProgram
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.data.Movie
import com.agoro.tv.data.Series
import com.agoro.tv.data.mergeEpisodeOrigins
import com.agoro.tv.ui.screens.CatalogCard
import com.agoro.tv.ui.screens.ContinueCard
import com.agoro.tv.ui.screens.buildContinueWatching
import com.agoro.tv.ui.screens.buildRecentlyAdded
import com.agoro.tv.ui.screens.channelHero
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRowsTest {

    private fun movie(id: String) =
        Movie(id = id, name = "Movie $id", poster = null, url = "http://x/movie/$id", categoryId = null)

    private fun series(id: String) =
        Series(id = id, name = "Series $id", poster = null, categoryId = null)

    // LinkedHashMap literal: insertion order is the recency order under test.
    private fun positions(vararg urls: String): Map<String, Long> =
        LinkedHashMap<String, Long>().apply { urls.forEach { put(it, 60_000L) } }

    @Test
    fun `cards come out newest first`() {
        val row = buildContinueWatching(
            movies = listOf(movie("1"), movie("2")),
            series = emptyList(),
            episodeOrigins = emptyMap(),
            resumePositions = positions("http://x/movie/1", "http://x/movie/2"),
            resumeProgress = emptyMap(),
        )
        assertEquals(listOf("2", "1"), row.map { (it as ContinueCard.MovieCard).movie.id })
    }

    @Test
    fun `movies and series interleave in true recency order`() {
        val row = buildContinueWatching(
            movies = listOf(movie("m")),
            series = listOf(series("s")),
            episodeOrigins = mapOf("http://x/ep/1" to "s"),
            resumePositions = positions("http://x/ep/1", "http://x/movie/m"),
            resumeProgress = emptyMap(),
        )
        assertEquals(2, row.size)
        assertEquals("m", (row[0] as ContinueCard.MovieCard).movie.id)
        assertEquals("s", (row[1] as ContinueCard.SeriesCard).series.id)
    }

    @Test
    fun `a series appears once, at its newest episode, with that episode's progress`() {
        val row = buildContinueWatching(
            movies = listOf(movie("m")),
            series = listOf(series("s")),
            episodeOrigins = mapOf("http://x/ep/1" to "s", "http://x/ep/2" to "s"),
            // ep1 watched first, then the movie, then ep2: the series card
            // must outrank the movie because ep2 is the newest entry.
            resumePositions = positions("http://x/ep/1", "http://x/movie/m", "http://x/ep/2"),
            resumeProgress = mapOf("http://x/ep/1" to 0.9f, "http://x/ep/2" to 0.3f),
        )
        assertEquals(2, row.size)
        val seriesCard = row[0] as ContinueCard.SeriesCard
        assertEquals("s", seriesCard.series.id)
        assertEquals(0.3f, seriesCard.progress)
        assertEquals("m", (row[1] as ContinueCard.MovieCard).movie.id)
    }

    @Test
    fun `urls that resolve to nothing are skipped silently`() {
        val row = buildContinueWatching(
            movies = listOf(movie("m")),
            series = listOf(series("s")),
            episodeOrigins = mapOf("http://x/ep/gone" to "renumbered-away"),
            resumePositions = positions(
                "http://x/ep/no-origin", // played before origins were recorded
                "http://x/ep/gone", // origin points at a series no longer in the bundle
                "http://x/movie/dropped", // movie gone from the playlist
                "http://x/movie/m",
            ),
            resumeProgress = emptyMap(),
        )
        assertEquals(listOf("m"), row.map { (it as ContinueCard.MovieCard).movie.id })
    }

    @Test
    fun `limit caps the row and progress passes through only when known`() {
        val movies = (1..30).map { movie("$it") }
        val row = buildContinueWatching(
            movies = movies,
            series = emptyList(),
            episodeOrigins = emptyMap(),
            resumePositions = positions(*movies.map { it.url }.toTypedArray()),
            resumeProgress = mapOf("http://x/movie/30" to 0.5f),
        )
        assertEquals(20, row.size)
        assertEquals(0.5f, row.first().progress)
        assertNull(row[1].progress)
    }

    // --- mergeEpisodeOrigins --------------------------------------------------

    @Test
    fun `origins append and re-recording moves entries to the newest slot`() {
        val first = mergeEpisodeOrigins(emptyMap(), "s1", listOf("u1", "u2"))
        assertEquals(mapOf("u1" to "s1", "u2" to "s1"), first)

        val again = mergeEpisodeOrigins(first, "s2", listOf("u1"))
        assertEquals(listOf("u2", "u1"), again.keys.toList())
        assertEquals("s2", again["u1"])
    }

    @Test
    fun `cap evicts the oldest entries`() {
        val full = mergeEpisodeOrigins(emptyMap(), "s1", (1..5).map { "u$it" }, cap = 5)
        val merged = mergeEpisodeOrigins(full, "s2", listOf("u6", "u7"), cap = 5)
        assertEquals(listOf("u3", "u4", "u5", "u6", "u7"), merged.keys.toList())
        assertEquals("s1", merged["u3"])
        assertEquals("s2", merged["u7"])
    }

    // --- channelHero ----------------------------------------------------------

    private val channel = LiveChannel(
        id = "c1", name = "beIN Sports FHD", logo = "http://x/logo.png",
        url = "http://x/c1", categoryId = null, quality = "FHD",
    )

    @Test
    fun `channel hero degrades to bare channel without a guide`() {
        val hero = channelHero(channel, null)
        assertEquals("beIN Sports", hero.title)
        assertEquals(listOf("Live", "FHD"), hero.chips)
        assertNull(hero.plot)
    }

    @Test
    fun `channel hero carries the current programme`() {
        val now = EpgProgram(
            id = "p", title = "Match of the Day", description = "Highlights",
            startMs = 0L, endMs = 1L, hasArchive = false,
        )
        val hero = channelHero(channel, MainViewModel.NowNext(now = now, next = null))
        assertEquals(listOf("Live", "FHD", "Match of the Day"), hero.chips)
        assertEquals("Highlights", hero.plot)
    }

    // --- Recently added --------------------------------------------------------

    private fun dated(id: String, addedMs: Long) = movie(id).copy(addedMs = addedMs)

    private fun datedSeries(id: String, addedMs: Long) = series(id).copy(addedMs = addedMs)

    @Test
    fun `recently added is newest first across movies and series`() {
        val row = buildRecentlyAdded(
            movies = listOf(dated("old", 1_000), dated("new", 4_000)),
            series = listOf(datedSeries("mid", 3_000), datedSeries("older", 2_000)),
        )
        val ids = row.map {
            when (it) {
                is CatalogCard.MovieCard -> it.movie.id
                is CatalogCard.SeriesCard -> it.series.id
            }
        }
        assertEquals(listOf("new", "mid", "older", "old"), ids)
    }

    @Test
    fun `entries the provider never dated are left out`() {
        val row = buildRecentlyAdded(
            movies = listOf(dated("a", 4), dated("b", 3), dated("c", 2), dated("d", 1), movie("undated")),
            series = emptyList(),
        )
        assertEquals(4, row.size)
        assertTrue(row.none { (it as CatalogCard.MovieCard).movie.id == "undated" })
    }

    @Test
    fun `a row too thin to mean anything is no row at all`() {
        val row = buildRecentlyAdded(
            movies = listOf(dated("a", 2), dated("b", 1)),
            series = emptyList(),
        )
        assertEquals(emptyList<CatalogCard>(), row)
    }

    @Test
    fun `the row is capped`() {
        val row = buildRecentlyAdded(
            movies = (1..40).map { dated("m$it", it.toLong()) },
            series = emptyList(),
            limit = 20,
        )
        assertEquals(20, row.size)
        assertEquals("m40", (row.first() as CatalogCard.MovieCard).movie.id)
    }
}
