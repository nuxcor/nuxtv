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

    /**
     * Regression: the container extension was read from the last dot of the
     * whole URL, so any dotted hostname yielded a bogus non-empty ext
     * ("example.com/vod/9001" → "com/vod/9001"). That made the extensionless
     * branch unreachable and dropped a provider's whole VOD shelf into Live TV.
     */
    @Test
    fun `extensionless VOD urls are movies, not live channels`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 group-title="VOD | Action",Hard Boiled (1992)
            http://example.com/vod/9001
            #EXTINF:-1 group-title="News",Sky News
            http://example.com/hls/9002
        """.trimIndent()
        val bundle = ContentClassifier.classify(M3uParser.parse(playlist))
        assertEquals(1, bundle.movies.size)
        assertEquals("Hard Boiled", bundle.movies.first().name)
        assertEquals(1, bundle.channels.size)
    }

    /** A query string must not be mistaken for the container. */
    @Test
    fun `token query strings do not become the extension`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 group-title="VOD | Action",Heat (1995)
            http://example.com/files/heat.mkv?token=a.b
        """.trimIndent()
        val bundle = ContentClassifier.classify(M3uParser.parse(playlist))
        assertEquals(1, bundle.movies.size)
    }
}

class QualityMergeTest {
    private fun ch(name: String) = com.nuxcor.nuxtv.data.LiveChannel(
        id = name, name = name, logo = null, url = name, categoryId = null,
        quality = com.nuxcor.nuxtv.data.QualityTag.of(name),
    )

    @org.junit.Test
    fun `duplicates collapse to best quality`() {
        val merged = com.nuxcor.nuxtv.data.QualityTag.mergeBestQuality(
            listOf(ch("CNN SD"), ch("CNN FHD"), ch("CNN HD"), ch("BBC One"), ch("BBC One 4K"))
        )
        org.junit.Assert.assertEquals(2, merged.size)
        org.junit.Assert.assertEquals("CNN FHD", merged[0].name)
        org.junit.Assert.assertEquals("BBC One 4K", merged[1].name)
    }

    @org.junit.Test
    fun `separator junk entries are dropped`() {
        val bundle = com.nuxcor.nuxtv.data.ContentClassifier.classify(
            com.nuxcor.nuxtv.data.M3uParser.parse(
                "#EXTM3U\n#EXTINF:-1,##### SPORTS #####\nhttp://x/1.ts\n#EXTINF:-1,Real Channel\nhttp://x/2.ts"
            )
        )
        org.junit.Assert.assertEquals(1, bundle.channels.size)
        org.junit.Assert.assertEquals("Real Channel", bundle.channels[0].name)
    }
}
