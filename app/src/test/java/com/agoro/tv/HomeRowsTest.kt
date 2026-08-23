package com.agoro.tv

import com.agoro.tv.data.Category
import com.agoro.tv.data.ContentBundle
import com.agoro.tv.data.EpgProgram
import com.agoro.tv.data.Episode
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.data.Movie
import com.agoro.tv.data.Series
import com.agoro.tv.data.mergeEpisodeOrigins
import com.agoro.tv.ui.screens.CatalogCard
import com.agoro.tv.ui.screens.ContinueCard
import com.agoro.tv.ui.screens.VOD_MORE
import com.agoro.tv.ui.screens.buildCatalog
import com.agoro.tv.ui.screens.buildCatalogIndex
import com.agoro.tv.ui.screens.buildContinueWatching
import com.agoro.tv.ui.screens.buildRecentlyAdded
import com.agoro.tv.ui.screens.channelHero
import com.agoro.tv.ui.screens.ratingChip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRowsTest {

    private fun movie(id: String, categoryId: String? = null) =
        Movie(id = id, name = "Movie $id", poster = null, url = "http://x/movie/$id", categoryId = categoryId)

    private fun series(id: String, categoryId: String? = null) =
        Series(id = id, name = "Series $id", poster = null, categoryId = categoryId)

    // LinkedHashMap literal: insertion order is the recency order under test.
    private fun positions(vararg urls: String): Map<String, Long> =
        LinkedHashMap<String, Long>().apply { urls.forEach { put(it, 60_000L) } }

    /** The row as Home builds it: through the index's maps, never the lists. */
    private fun buildContinueWatching(
        movies: List<Movie>,
        series: List<Series>,
        episodeOrigins: Map<String, String>,
        resumePositions: Map<String, Long>,
        resumeProgress: Map<String, Float>,
    ): List<ContinueCard> {
        val index = buildCatalogIndex(ContentBundle(movies = movies, series = series)) { false }
        return buildContinueWatching(
            index.movieByUrl, index.seriesById, episodeOrigins, resumePositions, resumeProgress,
        )
    }

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

    @Test
    fun `recently added reads the same from the index's pre-sorted lists`() {
        // The index hands buildRecentlyAdded its dated titles newest-first;
        // the bounded insertion must give the same answer it gives an
        // unsorted walk, and the cap must still hold.
        val movies = (1..30).map { dated("m$it", it.toLong()) }.shuffled(java.util.Random(7))
        val shows = (1..30).map { datedSeries("s$it", it.toLong() + 100) }.shuffled(java.util.Random(9))
        val index = buildCatalogIndex(ContentBundle(movies = movies, series = shows)) { false }
        val fromIndex = buildRecentlyAdded(index.newMovies, index.newSeries)
        val fromLists = buildRecentlyAdded(movies, shows)
        assertEquals(fromLists, fromIndex)
        assertEquals(20, fromIndex.size)
        assertEquals("s30", (fromIndex.first() as CatalogCard.SeriesCard).series.id)
    }

    // --- The catalogue index ------------------------------------------------

    private val adults = Category("xxx", "XXX Adult")
    private val drama = Category("drama", "Drama")

    private fun bundle(vararg movies: Movie, series: List<Series> = emptyList()) = ContentBundle(
        movieCategories = listOf(drama, adults),
        movies = movies.toList(),
        seriesCategories = listOf(drama, adults),
        series = series,
    )

    @Test
    fun `locked categories and their titles leave the index`() {
        val b = bundle(movie("a", "drama"), movie("b", "xxx"), movie("c"))
        val index = buildCatalogIndex(b) { it.contains("Adult") }
        assertEquals(listOf("drama"), index.movieCategories.map { it.id })
        assertEquals(listOf("a", "c"), index.movies.map { it.id })
        assertNull(index.movieByUrl["http://x/movie/b"])
        assertFalse(index.moviesByCategory.containsKey("xxx"))
    }

    @Test
    fun `an unlocked index hands back the bundle's own lists`() {
        // Screens key remembers on these; a copy per rebuild would defeat that.
        val b = bundle(movie("a", "drama"), movie("b", "xxx"))
        val index = buildCatalogIndex(b) { false }
        assertSame(b.movies, index.movies)
        assertSame(b.movieCategories, index.movieCategories)
        assertSame(b, index.bundle)
    }

    @Test
    fun `titles under an undeclared category are filed under More`() {
        val b = bundle(movie("a", "drama"), movie("b", "ghost"), movie("c", null))
        val index = buildCatalogIndex(b) { false }
        assertEquals(listOf("a"), index.moviesByCategory["drama"]!!.map { it.id })
        assertEquals(listOf("b", "c"), index.moviesByCategory[VOD_MORE]!!.map { it.id })
    }

    @Test
    fun `dated titles sort newest first and undated ones stay out of new`() {
        val b = bundle(dated("old", 1), movie("undated"), dated("new", 9), dated("mid", 5))
        val index = buildCatalogIndex(b) { false }
        assertEquals(listOf("new", "mid", "old"), index.newMovies.map { it.id })
        assertEquals(4, index.movies.size)
    }

    @Test
    fun `the catalogue's resumed lists read newest first and the starters retire together`() {
        val b = bundle(
            movie("m1", "drama"), movie("m2", "drama"), movie("m3", "drama"),
            series = listOf(series("s1", "drama"), series("s2", "drama")),
        )
        val index = buildCatalogIndex(b) { false }
        val fresh = buildCatalog(
            index,
            resumePositions = emptyMap(),
            resumeProgress = emptyMap(),
            episodeOrigins = emptyMap(),
            hiddenTitles = setOf("m:m2"),
            starterLength = 2,
        )
        // Day one: starters, filtered before the cut so the hidden title
        // pulls the next one up rather than leaving a gap.
        assertTrue(fresh.continueWatching.isEmpty())
        assertEquals(listOf("m1", "m3"), fresh.starterMovies.map { it.id })
        assertEquals(listOf("s1", "s2"), fresh.starterSeries.map { it.id })

        val watched = buildCatalog(
            index,
            resumePositions = positions("http://x/movie/m1", "http://x/ep/1", "http://x/movie/m3"),
            resumeProgress = mapOf("http://x/ep/1" to 0.4f),
            episodeOrigins = mapOf("http://x/ep/1" to "s2"),
            hiddenTitles = emptySet(),
        )
        assertEquals(listOf("m3", "m1"), watched.resumedMovies.map { it.id })
        assertEquals(listOf("s2"), watched.resumedSeries.map { it.id })
        assertEquals(0.4f, watched.seriesProgress["s2"])
        assertEquals(3, watched.continueWatching.size)
        // Resuming anything retires the catalogue starters.
        assertTrue(watched.starterMovies.isEmpty())
        assertTrue(watched.starterSeries.isEmpty())
    }

    @Test
    fun `a series with inline episodes is resumed without an origin record`() {
        val m3u = series("s", "drama").copy(
            episodes = listOf(Episode(id = "e1", title = "1", season = 1, episodeNum = 1, url = "http://x/ep/1")),
        )
        val index = buildCatalogIndex(bundle(series = listOf(m3u))) { false }
        val catalog = buildCatalog(
            index,
            resumePositions = positions("http://x/ep/1"),
            resumeProgress = emptyMap(),
            episodeOrigins = emptyMap(),
            hiddenTitles = emptySet(),
        )
        assertEquals(listOf("s"), catalog.resumedSeries.map { it.id })
    }

    @Test
    fun `not interested hides a title from Home's recently added only`() {
        val b = bundle(dated("a", 4), dated("b", 3), dated("c", 2), dated("d", 1))
        val index = buildCatalogIndex(b) { false }
        val catalog = buildCatalog(
            index,
            resumePositions = emptyMap(),
            resumeProgress = emptyMap(),
            episodeOrigins = emptyMap(),
            hiddenTitles = setOf("m:b"),
        )
        assertEquals(
            listOf("a", "c", "d"),
            catalog.recentlyAdded.map { (it as CatalogCard.MovieCard).movie.id },
        )
        // The Movies tab's own Recently added keeps it.
        assertEquals(listOf("a", "b", "c", "d"), index.newMovies.map { it.id })
    }

    // --- ratingChip ----------------------------------------------------------

    @Test
    fun `rating chips round to a tenth without a formatter`() {
        assertEquals("★ 7.5", ratingChip(7.5))
        assertEquals("★ 7.0", ratingChip(7.0))
        assertEquals("★ 7.3", ratingChip(7.25))
        assertEquals("★ 10.0", ratingChip(10.0))
        assertEquals("★ 0.0", ratingChip(0.0))
        assertEquals("★ 6.8", ratingChip(6.84))
    }
}
