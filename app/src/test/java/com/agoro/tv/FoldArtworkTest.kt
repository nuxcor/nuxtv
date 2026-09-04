package com.agoro.tv

import com.agoro.tv.data.Movie
import com.agoro.tv.data.Series
import com.agoro.tv.ui.screens.foldMovieVariants
import com.agoro.tv.ui.screens.foldSeriesVariants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The panel lists one title at several rungs and hands the top one artwork
 * from a mirror that paints "4K UltraHD" and "8K" onto it, while the rung
 * below carries TMDB's clean copy. The fold picks the top rung's STREAM; it
 * must not pick the top rung's stickers with it.
 *
 * Counted on the panel's dumps: 399 series and 1,085 films have a badged
 * cover with a clean sibling standing right beside it.
 */
class FoldArtworkTest {

    private val badged = "http://photo-tmdb.com/stalker_portal/screenshots/171/17039.jpg"
    private val clean = "https://image.tmdb.org/t/p/w600_and_h900_bestv2/yhpmaJLMgG4e0f5Gq1aus3ywJr2.jpg"

    private fun series(id: String, quality: String?, poster: String?) =
        Series(id = id, name = "Lady in the Lake", poster = poster, categoryId = "c",
            year = 2024, quality = quality)

    private fun movie(id: String, quality: String?, poster: String?) =
        Movie(id = id, name = "Heat", poster = poster, url = "http://x/$id", categoryId = "c",
            year = 1995, quality = quality)

    @Test
    fun `the 4K rung survives wearing the HD rung's clean poster`() {
        val out = listOf(
            series("4k", "4K", badged),
            series("hd", "HD", clean),
        ).foldSeriesVariants()
        assertEquals(1, out.size)
        // The stream is the 4K one...
        assertEquals("4k", out[0].id)
        // ...and the poster is not the one with the stickers on it.
        assertEquals(clean, out[0].poster)
    }

    /** Order does not matter: the clean copy may be listed first or last. */
    @Test
    fun `the clean poster is found whichever way round the panel listed them`() {
        val out = listOf(
            series("hd", "HD", clean),
            series("4k", "4K", badged),
        ).foldSeriesVariants()
        assertEquals(listOf("4k"), out.map { it.id })
        assertEquals(clean, out[0].poster)
    }

    /** Only ever upward. A clean poster is never traded for a badged one. */
    @Test
    fun `a clean survivor keeps its own poster`() {
        val out = listOf(
            series("4k", "4K", clean),
            series("hd", "HD", badged),
        ).foldSeriesVariants()
        assertEquals(clean, out[0].poster)
    }

    /** A variant that shipped no art at all is filled from its sibling too. */
    @Test
    fun `a survivor with no poster borrows one`() {
        val out = listOf(
            movie("4k", "4K", null),
            movie("hd", "HD", clean),
        ).foldMovieVariants()
        assertEquals(clean, out[0].poster)
    }

    /** No clean copy anywhere: the badged one stays, rather than nothing. */
    @Test
    fun `a badged poster with no clean sibling is left alone`() {
        val out = listOf(
            series("4k", "4K", badged),
            series("hd", "HD", badged),
        ).foldSeriesVariants()
        assertEquals(1, out.size)
        assertEquals(badged, out[0].poster)
    }

    /**
     * Nothing to fold and nothing to repaint returns the same instance —
     * screens key a remember on the list, and a fresh copy per rebuild would
     * throw their caches away.
     */
    @Test
    fun `a list with nothing to do is returned as itself`() {
        val list = listOf(series("a", "HD", clean), series("b", "HD", clean).copy(name = "Other"))
        assertSame(list, list.foldSeriesVariants())
    }

    /** Two different films sharing a name do not lend each other artwork. */
    @Test
    fun `a different year is a different title`() {
        val out = listOf(
            movie("new", "4K", badged).copy(year = 2015),
            movie("old", "HD", clean).copy(year = 1995),
        ).foldMovieVariants()
        assertEquals(2, out.size)
        assertEquals(badged, out.first { it.id == "new" }.poster)
    }
}
