package com.agoro.tv

import com.agoro.tv.data.TmdbClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Provider titles are full of tags TMDB can't match; the search title must be
 * the bare name, with the year recovered separately.
 */
class TmdbTitleTest {

    @Test
    fun `quality and codec junk is stripped`() {
        assertEquals(
            "Avengers Endgame",
            TmdbClient.searchTitle("Avengers Endgame (2019) 4K HEVC 10bit"),
        )
        assertEquals("Oppenheimer", TmdbClient.searchTitle("Oppenheimer 1080p WEB-DL x265"))
    }

    @Test
    fun `language tags and prefixes are stripped`() {
        assertEquals("The Bear", TmdbClient.searchTitle("EN - The Bear [MULTI]"))
        assertEquals("Lupin", TmdbClient.searchTitle("|FR| Lupin HD"))
    }

    @Test
    fun `plain titles pass through unchanged`() {
        assertEquals("Breaking Bad", TmdbClient.searchTitle("Breaking Bad"))
    }

    @Test
    fun `the year is recovered from the raw title`() {
        assertEquals(2019, TmdbClient.yearIn("Avengers Endgame (2019) 4K"))
        assertEquals(null, TmdbClient.yearIn("Breaking Bad"))
    }
}
