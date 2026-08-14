package com.nuxcor.nuxtv

import com.nuxcor.nuxtv.data.ContentClassifier
import com.nuxcor.nuxtv.data.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentClassifierTest {

    private val samplePlaylist = """
        #EXTM3U
        #EXTINF:-1 tvg-id="cnn.us" tvg-logo="http://logo/cnn.png" group-title="News",CNN HD
        http://example.com/live/user/pass/1001.m3u8
        #EXTINF:-1 group-title="Sports",ESPN
        http://example.com/stream/2002.ts
        #EXTINF:-1 tvg-logo="http://logo/bb.jpg" group-title="VOD | Movies",Breaking Point (2023) 1080p
        http://example.com/movie/user/pass/3003.mkv
        #EXTINF:-1 group-title="Series | Drama",The Crown S01E01
        http://example.com/series/user/pass/4001.mp4
        #EXTINF:-1 group-title="Series | Drama",The Crown S01E02
        http://example.com/series/user/pass/4002.mp4
        #EXTINF:-1 group-title="Series | Drama",The Crown S02 E01
        http://example.com/series/user/pass/4003.mp4
        #EXTINF:-1 group-title="Shows",Dark Matter 2x05
        http://example.com/vod/5005.mp4
        #EXTINF:-1,Random Movie File (2019)
        http://example.com/files/random.mp4
        #EXTINF:-1 group-title="VOD | Drama",Sintel (2010) 4K
        http://example.com/files/sintel.mp4
    """.trimIndent()

    @Test
    fun `parses entries with attributes and commas`() {
        val entries = M3uParser.parse(samplePlaylist)
        assertEquals(9, entries.size)
        assertEquals("CNN HD", entries[0].title)
        assertEquals("News", entries[0].group)
        assertEquals("http://logo/cnn.png", entries[0].logo)
    }

    @Test
    fun `classifies live, movies and series correctly`() {
        val bundle = ContentClassifier.classify(M3uParser.parse(samplePlaylist))

        assertEquals(2, bundle.channels.size)
        assertEquals("CNN HD", bundle.channels[0].name)

        assertEquals(3, bundle.movies.size)
        assertTrue(bundle.movies.any { it.name == "Breaking Point" && it.year == 2023 })
        assertTrue(bundle.movies.any { it.name == "Random Movie File" && it.year == 2019 })
        // "VOD | Drama" contains both a movie keyword and a series-ish genre word —
        // the explicit VOD marker must win.
        assertTrue(bundle.movies.any { it.name == "Sintel" && it.year == 2010 })

        assertEquals(2, bundle.series.size)
        val crown = bundle.series.first { it.name == "The Crown" }
        assertEquals(3, crown.episodes!!.size)
        assertEquals(1, crown.episodes!![0].season)
        assertEquals(1, crown.episodes!![0].episodeNum)
        assertEquals(2, crown.episodes!![2].season)

        val darkMatter = bundle.series.first { it.name == "Dark Matter" }
        assertEquals(2, darkMatter.episodes!![0].season)
        assertEquals(5, darkMatter.episodes!![0].episodeNum)
    }

    @Test
    fun `episode parsing extracts show name and numbering`() {
        val (name, season, ep) = ContentClassifier.parseEpisode("Better Call Saul S03E07 FHD")
        assertEquals("Better Call Saul", name)
        assertEquals(3, season)
        assertEquals(7, ep)
    }

    @Test
    fun `title cleaning strips noise`() {
        assertEquals("Inception", ContentClassifier.cleanTitle("EN - Inception (2010) 1080p"))
        assertEquals("Oppenheimer", ContentClassifier.cleanTitle("Oppenheimer [4K] HEVC"))
    }
}
