package com.agoro.tv

import com.agoro.tv.data.Movie
import com.agoro.tv.data.Series
import com.agoro.tv.ui.screens.ACCLAIM_CEILING
import com.agoro.tv.ui.screens.ACCLAIM_FLOOR
import com.agoro.tv.ui.screens.acclaimedMovies
import com.agoro.tv.ui.screens.genreKey
import com.agoro.tv.ui.screens.genreShelf
import com.agoro.tv.ui.screens.movieHomeKey
import com.agoro.tv.ui.screens.topGenres
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home's catalogue shelves. The ratings here are the ones the real panel
 * ships: a TMDB vote average with no vote count beside it, which is why the
 * head of the list needs defending against as much as the tail.
 */
class HomeShelvesTest {

    private fun film(
        id: String,
        rating: Double?,
        poster: String? = "http://art/$id.jpg",
        addedMs: Long? = null,
    ) = Movie(
        id = id, name = "Film $id", poster = poster,
        url = "http://x/movie/$id", categoryId = null, rating = rating, addedMs = addedMs,
    )

    private fun show(
        id: String,
        rating: Double?,
        poster: String? = "http://art/$id.jpg",
        addedMs: Long? = null,
    ) = Series(
        id = id, name = "Show $id", poster = poster,
        categoryId = null, rating = rating, addedMs = addedMs,
    )

    /**
     * 309 films on this panel sit at a flat 10.0 and they are festival shorts
     * nobody has scored. A shelf headed "highly rated" that opens on one of
     * those is worse than no shelf at all.
     */
    @Test
    fun `the unvoted head of the ratings is not the top of the shelf`() {
        val shelf = acclaimedMovies(
            listOf(film("obscure", 10.0), film("pulp", 8.5), film("goodfellas", 8.4))
        )
        assertEquals(listOf("pulp", "goodfellas"), shelf.map { it.id })
    }

    /** And the tail: an unrated title is not a well-reviewed one. */
    @Test
    fun `unrated and poorly rated titles stay off it`() {
        val shelf = acclaimedMovies(
            listOf(film("none", null), film("weak", 4.2), film("good", 8.0))
        )
        assertEquals(listOf("good"), shelf.map { it.id })
    }

    /** A poster shelf with no poster on it is a row of grey boxes. */
    @Test
    fun `a title with no artwork is not shelved`() {
        val shelf = acclaimedMovies(listOf(film("art", 8.0), film("none", 8.2, poster = null)))
        assertEquals(listOf("art"), shelf.map { it.id })
    }

    /**
     * The whole reason this function exists: Home's film row was
     * `movies.take(20)` — the provider's insertion order, which means nothing.
     * Rating decides, and equal ratings break on what arrived most recently
     * rather than on that same insertion order.
     */
    @Test
    fun `equal ratings lead with what arrived most recently`() {
        val shelf = acclaimedMovies(
            listOf(film("old", 8.0, addedMs = 1_000), film("new", 8.0, addedMs = 9_000))
        )
        assertEquals(listOf("new", "old"), shelf.map { it.id })
    }

    @Test
    fun `a hidden title is not shelved`() {
        val hide = film("hidden", 8.3)
        val shelf = acclaimedMovies(listOf(hide, film("keep", 8.0)), setOf(movieHomeKey(hide)))
        assertEquals(listOf("keep"), shelf.map { it.id })
    }

    @Test
    fun `the band is the band`() {
        assertTrue(ACCLAIM_FLOOR < ACCLAIM_CEILING)
        val shelf = acclaimedMovies(
            listOf(film("under", ACCLAIM_FLOOR - 0.1), film("over", ACCLAIM_CEILING + 0.1))
        )
        assertTrue(shelf.isEmpty())
    }

    /**
     * Series ratings on this panel are coarse — whole and half points, with
     * 244 shows at a flat 10.0 — so a genre shelf keeps everything in the
     * genre and only changes the order. What it will not do is lead with a
     * score nobody gave: above the band ranks with the unrated, not above the
     * best real one.
     */
    @Test
    fun `a genre shelf keeps the unvoted but does not lead with them`() {
        val shelf = genreShelf(
            listOf(
                show("unvoted", 10.0, addedMs = 1),
                show("great", 8.0, addedMs = 2),
                show("fine", 6.0, addedMs = 3),
                show("unrated", null, addedMs = 4),
            )
        )
        assertEquals(listOf("great", "fine", "unrated", "unvoted"), shelf.map { it.id })
    }

    @Test
    fun `a genre shelf still needs artwork`() {
        val shelf = genreShelf(listOf(show("none", 8.0, poster = null), show("art", 7.0)))
        assertEquals(listOf("art"), shelf.map { it.id })
    }

    /** Home shows a few genres; the biggest are the ones worth scrolling. */
    @Test
    fun `the biggest genres lead`() {
        val byGenre = mapOf(
            genreKey("Drama") to List(40) { show("d$it", 7.0) },
            genreKey("Comedy") to List(20) { show("c$it", 7.0) },
            genreKey("Mystery") to List(5) { show("m$it", 7.0) },
        )
        assertEquals(
            listOf("Drama", "Comedy"),
            topGenres(listOf("Comedy", "Drama", "Mystery"), byGenre, 2),
        )
    }

    /** Equal sizes settle alphabetically rather than on map order. */
    @Test
    fun `a tie between genres is stable`() {
        val byGenre = mapOf(
            genreKey("Drama") to List(10) { show("d$it", 7.0) },
            genreKey("Comedy") to List(10) { show("c$it", 7.0) },
        )
        assertEquals(
            listOf("Comedy", "Drama"),
            topGenres(listOf("Drama", "Comedy"), byGenre, 2),
        )
    }

    @Test
    fun `asking for more genres than exist is not an error`() {
        assertTrue(topGenres(emptyList(), emptyMap(), 3).isEmpty())
        assertFalse(topGenres(listOf("Drama"), emptyMap(), 3).isEmpty())
    }
}
