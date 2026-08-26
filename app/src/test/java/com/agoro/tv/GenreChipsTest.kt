package com.agoro.tv

import com.agoro.tv.ui.screens.genreKey
import com.agoro.tv.ui.screens.splitGenres
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The genre strip is only worth as much as the provider's genre strings are,
 * and they are free text: one field per title, two separators, inconsistent
 * casing, and the odd French spelling. Every case here is taken from the
 * panel's own series listing.
 */
class GenreChipsTest {

    @Test
    fun `a multi-genre title splits on the separator the panel actually uses`() {
        assertEquals(
            listOf("Animation", "Comedy", "Sci-Fi & Fantasy"),
            splitGenres("Animation / Comedy / Sci-Fi & Fantasy"),
        )
        // The movie enrichment writes commas instead; both have to work, and
        // neither may split an ampersand name down the middle.
        assertEquals(listOf("Action & Adventure", "Crime"), splitGenres("Action & Adventure, Crime"))
    }

    @Test
    fun `nothing in, nothing out`() {
        assertEquals(emptyList<String>(), splitGenres(null))
        assertEquals(emptyList<String>(), splitGenres(""))
        // A trailing separator is the panel's, not a genre.
        assertEquals(listOf("Drama"), splitGenres("Drama /"))
        assertEquals(listOf("Drama"), splitGenres("  Drama  "))
    }

    @Test
    fun `one genre spelled two ways is one chip`() {
        // 4,514 shows say "Drama" and one says "DRama". Left apart, the stray
        // is its own genre — and takes its title out of the chip a viewer
        // presses.
        assertEquals(genreKey("Drama"), genreKey("DRama"))
        // "Drame" is twelve French-language shows, enough to earn a chip of
        // its own beside Drama's four and a half thousand. Folded by name.
        assertEquals(listOf("Drama"), splitGenres("Drame"))
        assertEquals(listOf("Drama"), splitGenres("DRAME"))
    }

    @Test
    fun `strays and near-synonyms fold onto the chip that carries them`() {
        // Each of these earns a chip leading almost nowhere on its own.
        assertEquals(listOf("Drama"), splitGenres("Soap"))
        assertEquals(listOf("Drama"), splitGenres("Romance"))
        assertEquals(listOf("Action & Adventure"), splitGenres("Western"))
        assertEquals(listOf("Action & Adventure"), splitGenres("War & Politics"))
        assertEquals(listOf("Documentary"), splitGenres("Talk"))
        assertEquals(listOf("Documentary"), splitGenres("Podcast"))
        // One chip everywhere else in television.
        assertEquals(listOf("Kids & Family"), splitGenres("Kids"))
        assertEquals(listOf("Kids & Family"), splitGenres("Family"))
    }

    @Test
    fun `a title that names one chip twice is indexed under it once`() {
        // "Kids / Family" both fold to Kids & Family. Indexed twice, the show
        // appears in that grid as two identical posters.
        assertEquals(listOf("Kids & Family"), splitGenres("Kids / Family"))
        assertEquals(listOf("Drama"), splitGenres("Drama / Romance / Soap"))
        // And the fold must not swallow the genres either side of it.
        assertEquals(
            listOf("Comedy", "Kids & Family", "Animation"),
            splitGenres("Comedy / Kids / Family / Animation"),
        )
    }

    @Test
    fun `nothing above a thousand titles loses its own chip`() {
        // The tempting merge, deliberately not made: a viewer reaching for
        // Mystery already knows it is not Crime.
        assertEquals(listOf("Crime"), splitGenres("Crime"))
        assertEquals(listOf("Mystery"), splitGenres("Mystery"))
        assertEquals(listOf("Crime", "Mystery"), splitGenres("Crime / Mystery"))
        // And Animation stays out of Kids & Family: much of it is anime and
        // adult animation, which that chip would misdescribe.
        assertEquals(listOf("Animation"), splitGenres("Animation"))
        assertEquals(listOf("Animation", "Kids & Family"), splitGenres("Animation / Kids"))
    }

    @Test
    fun `a genre that is not an alias is left exactly as the panel wrote it`() {
        // The fold is a named list, not a guess: anything not on it keeps its
        // own spelling, ampersand, hyphen and all.
        assertTrue(splitGenres("Sci-Fi & Fantasy").single() == "Sci-Fi & Fantasy")
        assertTrue(splitGenres("Action & Adventure").single() == "Action & Adventure")
        assertEquals(listOf("Reality"), splitGenres("Reality"))
        // A typo is left alone too — GENRE_MIN_TITLES is what keeps it off
        // the strip, not a guess at what it was meant to say.
        assertEquals(listOf("Mistery"), splitGenres("Mistery"))
    }
}
