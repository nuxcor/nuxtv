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
import com.agoro.tv.ui.screens.currentYear
import com.agoro.tv.ui.screens.channelHero
import com.agoro.tv.ui.screens.isRecentRelease
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
        watchedAt: Map<String, Long> = emptyMap(),
    ): List<ContinueCard> {
        val index = buildCatalogIndex(ContentBundle(movies = movies, series = series)) { false }
        return buildContinueWatching(
            index.movieByUrl, index.seriesById, episodeOrigins, resumePositions, resumeProgress,
            watchedAt = watchedAt,
        )
    }

    // --- watch state ---------------------------------------------------------
    //
    // Finishing an episode DELETES its resume position (PlayerPrefs: near the
    // end clears the entry), so before watchedAt a show the viewer had watched
    // cleanly to an episode boundary left no trace anywhere.

    @Test
    fun `a show whose episode finished stays in continue watching`() {
        val row = buildContinueWatching(
            movies = emptyList(),
            series = listOf(series("s1")),
            episodeOrigins = mapOf("http://x/ep1" to "s1"),
            resumePositions = emptyMap(), // finished: the position is gone
            resumeProgress = emptyMap(),
            watchedAt = mapOf("http://x/ep1" to 5_000L),
        )
        assertEquals(1, row.size)
        assertEquals("s1", (row.first() as ContinueCard.SeriesCard).series.id)
        // No bar: the episode behind the card is done, and a full one would
        // say "nearly finished" about a show being carried on.
        assertNull(row.first().progress)
    }

    @Test
    fun `a finished film does not come back to continue watching`() {
        val row = buildContinueWatching(
            movies = listOf(movie("m1")),
            series = emptyList(),
            episodeOrigins = emptyMap(),
            resumePositions = emptyMap(),
            resumeProgress = emptyMap(),
            watchedAt = mapOf("http://x/movie/m1" to 5_000L),
        )
        assertTrue(row.isEmpty())
    }

    @Test
    fun `a part-watched show is listed once, not twice`() {
        // Episode 1 finished, episode 2 part-way: one card, and it comes from
        // the part-watched side so it keeps its progress bar.
        val row = buildContinueWatching(
            movies = emptyList(),
            series = listOf(series("s1")),
            episodeOrigins = mapOf("http://x/ep1" to "s1", "http://x/ep2" to "s1"),
            resumePositions = mapOf("http://x/ep2" to 60_000L),
            resumeProgress = mapOf("http://x/ep2" to 0.4f),
            watchedAt = mapOf("http://x/ep1" to 5_000L),
        )
        assertEquals(1, row.size)
        assertEquals(0.4f, row.first().progress)
    }

    @Test
    fun `part-watched titles come before finished ones`() {
        val row = buildContinueWatching(
            movies = listOf(movie("m1")),
            series = listOf(series("s1")),
            episodeOrigins = mapOf("http://x/ep1" to "s1"),
            resumePositions = positions("http://x/movie/m1"),
            resumeProgress = emptyMap(),
            watchedAt = mapOf("http://x/ep1" to 9_000L),
        )
        assertEquals(2, row.size)
        assertTrue(row.first() is ContinueCard.MovieCard)
        assertTrue(row[1] is ContinueCard.SeriesCard)
    }

    @Test
    fun `finished shows read newest finish first`() {
        val row = buildContinueWatching(
            movies = emptyList(),
            series = listOf(series("old"), series("new")),
            episodeOrigins = mapOf("http://x/a" to "old", "http://x/b" to "new"),
            resumePositions = emptyMap(),
            resumeProgress = emptyMap(),
            watchedAt = mapOf("http://x/a" to 1_000L, "http://x/b" to 9_000L),
        )
        assertEquals(listOf("new", "old"), row.map { (it as ContinueCard.SeriesCard).series.id })
    }

    @Test
    fun `the shows shelf keeps a show whose last episode finished`() {
        val index = buildCatalogIndex(
            ContentBundle(series = listOf(series("s1"), series("s2")))
        ) { false }
        val catalog = buildCatalog(
            index,
            resumePositions = emptyMap(),
            resumeProgress = emptyMap(),
            episodeOrigins = mapOf("http://x/ep1" to "s1"),
            hiddenTitles = emptySet(),
            watchedAt = mapOf("http://x/ep1" to 5_000L),
        )
        assertEquals(listOf("s1"), catalog.resumedSeries.map { it.id })
    }

    @Test
    fun `recently added folds a title's duplicate streams into one card`() {
        // The same film three times — a 4K rung, an HD rung, and the same
        // title dumped in a second category — each a distinct stream id,
        // all dated within the row.
        fun dupe(id: String, addedMs: Long) = Movie(
            id = id, name = "Oppenheimer", poster = null, url = "http://x/movie/$id",
            categoryId = null, year = currentYear(), addedMs = addedMs,
        )
        val index = buildCatalogIndex(
            ContentBundle(
                movies = listOf(dupe("a", 3_000L), dupe("b", 2_000L), dupe("c", 1_000L)),
            )
        ) { false }
        assertEquals(1, index.newMovies.size)
        // The survivor is the newest-added of the three.
        assertEquals("a", index.newMovies.first().id)
    }

    /** The same film at three rungs, as a provider actually lists it. */
    private fun variant(
        id: String,
        quality: String?,
        categoryId: String? = "drama",
        genre: String? = null,
    ) = Movie(
        id = id, name = "Heat", poster = null, url = "http://x/movie/$id",
        categoryId = categoryId, year = 1995, quality = quality, genre = genre,
    )

    @Test
    fun `a category grid keeps one card per title, at its best rung`() {
        // What the viewer reported: the 4K copy and the SD copy side by side.
        val b = bundle(variant("sd", "SD"), variant("uhd", "4K"), variant("hd", "HD"))
        val index = buildCatalogIndex(b) { false }
        assertEquals(listOf("uhd"), index.moviesByCategory["drama"]!!.map { it.id })
        // And the All view, which walks the flat list.
        assertEquals(listOf("uhd"), index.movies.map { it.id })
    }

    @Test
    fun `the survivor stands where the first variant stood`() {
        // Order is the caller's — playlist order here — and folding must
        // change which stream the card opens, never where the card sits.
        val b = bundle(movie("before"), variant("sd", "SD"), variant("uhd", "4K"), movie("after"))
        val index = buildCatalogIndex(b) { false }
        assertEquals(listOf("before", "uhd", "after"), index.movies.map { it.id })
    }

    @Test
    fun `a named rung beats an unnamed one and a tie keeps the first`() {
        // rank() puts an unknown quality below SD: a variant whose name never
        // said what it was only wins when it is the sole copy.
        val named = buildCatalogIndex(bundle(variant("unknown", null), variant("sd", "SD"))) { false }
        assertEquals(listOf("sd"), named.movies.map { it.id })
        val tied = buildCatalogIndex(bundle(variant("first", "HD"), variant("second", "HD"))) { false }
        assertEquals(listOf("first"), tied.movies.map { it.id })
    }

    @Test
    fun `two yearless titles of the same name at the same rung are both kept`() {
        // cleanTitle strips region tags, so "The Office (US)" and "The Office
        // (UK)" arrive as one name — and series often carry no year. With
        // nothing separating them, folding would take a whole show off the
        // shelf, so a tie without a year keeps both.
        fun show(id: String, quality: String? = null) =
            Series(id = id, name = "The Office", poster = null, categoryId = "drama", quality = quality)
        val index = buildCatalogIndex(bundle(series = listOf(show("us"), show("uk")))) { false }
        assertEquals(listOf("us", "uk"), index.series.map { it.id })
    }

    @Test
    fun `a yearless title still folds when the rungs differ`() {
        fun show(id: String, quality: String?) =
            Series(id = id, name = "The Office", poster = null, categoryId = "drama", quality = quality)
        val index = buildCatalogIndex(
            bundle(series = listOf(show("hd", "HD"), show("uhd", "4K")))
        ) { false }
        assertEquals(listOf("uhd"), index.series.map { it.id })
    }

    @Test
    fun `a known year folds a tie, because the year is the evidence`() {
        val index = buildCatalogIndex(bundle(variant("a", "HD"), variant("b", "HD"))) { false }
        assertEquals(listOf("a"), index.movies.map { it.id })
    }

    @Test
    fun `genre grids fold too`() {
        val b = bundle(
            variant("sd", "SD", genre = "Crime"),
            variant("uhd", "4K", genre = "Crime"),
        )
        val index = buildCatalogIndex(b) { false }
        assertEquals(listOf("uhd"), index.moviesByGenre["crime"]!!.map { it.id })
    }

    @Test
    fun `a film filed under two categories stays on both shelves`() {
        // Folded per shelf, not once over the catalogue: this is the same
        // title twice, but not a duplicate on either grid it appears on.
        val b = bundle(variant("inDrama", "HD", categoryId = "drama"), variant("inGhost", "HD", categoryId = "ghost"))
        val index = buildCatalogIndex(b) { false }
        assertEquals(listOf("inDrama"), index.moviesByCategory["drama"]!!.map { it.id })
        assertEquals(listOf("inGhost"), index.moviesByCategory[VOD_MORE]!!.map { it.id })
    }

    @Test
    fun `shows fold on their own quality rung`() {
        fun show(id: String, quality: String?) =
            Series(id = id, name = "The Wire", poster = null, categoryId = "drama", year = 2002, quality = quality)
        val b = bundle(series = listOf(show("hd", "HD"), show("uhd", "4K")))
        val index = buildCatalogIndex(b) { false }
        assertEquals(listOf("uhd"), index.series.map { it.id })
        assertEquals(listOf("uhd"), index.seriesByCategory["drama"]!!.map { it.id })
    }

    @Test
    fun `recently added takes the best rung, not merely the newest-added`() {
        // The row is newest-first, so the SD copy dated a day later used to
        // be the one card the row kept.
        fun rung(id: String, quality: String?, addedMs: Long) = Movie(
            id = id, name = "Oppenheimer", poster = null, url = "http://x/movie/$id",
            categoryId = null, year = currentYear(), quality = quality, addedMs = addedMs,
        )
        val index = buildCatalogIndex(
            ContentBundle(movies = listOf(rung("sd", "SD", 3_000L), rung("uhd", "4K", 2_000L)))
        ) { false }
        assertEquals(listOf("uhd"), index.newMovies.map { it.id })
    }

    @Test
    fun `two same-named titles from different years are both kept`() {
        // Both recent (this year and last), so the release-year gate keeps
        // them; only the (name, year) key tells them apart, and it does.
        fun film(id: String, yr: Int) = Movie(
            id = id, name = "Dune", poster = null, url = "http://x/movie/$id",
            categoryId = null, year = yr, addedMs = 1_000L,
        )
        val index = buildCatalogIndex(
            ContentBundle(movies = listOf(film("thisYear", currentYear()), film("lastYear", currentYear() - 1)))
        ) { false }
        assertEquals(2, index.newMovies.size)
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

    /** The year the tests run in; dated titles are released in it unless told otherwise. */
    private val thisYear = 2026

    private fun dated(id: String, addedMs: Long, year: Int? = thisYear) =
        movie(id).copy(addedMs = addedMs, year = year)

    private fun datedSeries(id: String, addedMs: Long, year: Int? = thisYear) =
        series(id).copy(addedMs = addedMs, year = year)

    @Test
    fun `this year and last are recent, older and unknown are not`() {
        assertTrue(isRecentRelease(2026, nowYear = 2026))
        assertTrue(isRecentRelease(2025, nowYear = 2026))
        assertFalse(isRecentRelease(2024, nowYear = 2026))
        assertFalse(isRecentRelease(1994, nowYear = 2026))
        assertFalse(isRecentRelease(null, nowYear = 2026))
    }

    @Test
    fun `a back-catalogue import dated today is not recently added`() {
        // The panel imported its 1990s shelf this morning: every title
        // carries today's `added`. Only the actual new releases may claim
        // the row, and a title that cannot say its year cannot claim it.
        val today = 1_700_000_000_000L
        val movies = listOf(
            dated("speed", today, year = 1994),
            dated("new", today - 1, year = 2026),
            dated("lastYear", today - 2, year = 2025),
            dated("unscraped", today, year = null),
            dated("old", today, year = 2019),
        )
        val shows = listOf(
            datedSeries("friends", today, year = 1994),
            datedSeries("newShow", today - 1, year = 2026),
        )
        val index = buildCatalogIndex(ContentBundle(movies = movies, series = shows), nowYear = 2026) { false }
        assertEquals(listOf("new", "lastYear"), index.newMovies.map { it.id })
        assertEquals(listOf("newShow"), index.newSeries.map { it.id })
        // The row and the tab category read from the same lists, so both agree.
        val row = buildRecentlyAdded(index.newMovies, index.newSeries, minimum = 1)
        assertEquals(listOf("new", "newShow", "lastYear"), row.map {
            when (it) { is CatalogCard.MovieCard -> it.movie.id; is CatalogCard.SeriesCard -> it.series.id }
        })
    }

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
        val index = buildCatalogIndex(ContentBundle(movies = movies, series = shows), nowYear = thisYear) { false }
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
