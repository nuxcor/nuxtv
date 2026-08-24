package com.agoro.tv

import com.agoro.tv.data.ContentClassifier
import com.agoro.tv.data.M3uParser
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
        // Streaming-platform tags, stacked tags, and country suffixes.
        assertEquals("The Boys", ContentClassifier.cleanTitle("AMZ - The Boys"))
        assertEquals("Severance", ContentClassifier.cleanTitle("A+ - Severance"))
        assertEquals("Yellowstone", ContentClassifier.cleanTitle("PCOCK - Yellowstone (US)"))
        assertEquals("Dexter", ContentClassifier.cleanTitle("SHWT - Dexter"))
        assertEquals("Mating Season", ContentClassifier.cleanTitle("4K-NF - Mating Season (2026) (US)"))
        assertEquals("Loki", ContentClassifier.cleanTitle("D+ - EN - Loki"))
        // Real first words must survive: no tag without its separator.
        assertEquals("Maximum Security", ContentClassifier.cleanTitle("Maximum Security"))
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
    private fun ch(name: String) = com.agoro.tv.data.LiveChannel(
        id = name, name = name, logo = null, url = name, categoryId = null,
        quality = com.agoro.tv.data.QualityTag.of(name),
    )

    @org.junit.Test
    fun `duplicates collapse to best quality`() {
        val merged = com.agoro.tv.data.QualityTag.mergeBestQuality(
            listOf(ch("CNN SD"), ch("CNN FHD"), ch("CNN HD"), ch("BBC One"), ch("BBC One 4K"))
        )
        org.junit.Assert.assertEquals(2, merged.size)
        org.junit.Assert.assertEquals("CNN FHD", merged[0].name)
        org.junit.Assert.assertEquals("BBC One 4K", merged[1].name)
    }

    @org.junit.Test
    fun `a merged variant survives as a fallback source`() {
        // The lighter feeds are what the player steps down to when the line
        // cannot carry the best one. Dropping them turned a merge into a
        // deletion and left a mid-programme stall with nowhere to go.
        val merged = com.agoro.tv.data.QualityTag.mergeBestQuality(
            listOf(ch("CNN HD"), ch("CNN 4K"), ch("CNN FHD"))
        )
        org.junit.Assert.assertEquals(1, merged.size)
        org.junit.Assert.assertEquals("CNN 4K", merged[0].name)
        // Best first, so the first step down is the smallest one that helps.
        org.junit.Assert.assertEquals(
            listOf("CNN FHD", "CNN HD"),
            merged[0].fallbackUrls,
        )
    }

    @org.junit.Test
    fun `manifest fallbacks keep their place ahead of merged ones`() {
        val winner = ch("CNN 4K").copy(fallbackUrls = listOf("manifest-alt"))
        val merged = com.agoro.tv.data.QualityTag.mergeBestQuality(
            listOf(winner, ch("CNN HD"))
        )
        org.junit.Assert.assertEquals(
            listOf("manifest-alt", "CNN HD"),
            merged[0].fallbackUrls,
        )
    }

    @org.junit.Test
    fun `a channel with no duplicates is returned untouched`() {
        val only = ch("BBC One")
        val merged = com.agoro.tv.data.QualityTag.mergeBestQuality(listOf(only))
        org.junit.Assert.assertSame(only, merged[0])
        org.junit.Assert.assertTrue(merged[0].fallbackUrls.isEmpty())
    }

    @org.junit.Test
    fun `on equal rank a measured variant beats a name-tagged one`() {
        // Both claim FHD; only the second has actually decoded at FHD.
        val claimed = ch("CNN FHD")
        val proven = ch("CNN 1080p")
        val merged = com.agoro.tv.data.QualityTag.mergeBestQuality(
            listOf(claimed, proven),
            measured = setOf(proven.url),
        )
        org.junit.Assert.assertEquals(listOf(proven.name), merged.map { it.name })
        // A measured lower tier still loses to a higher claimed tier — the
        // overlay has already downgraded liars before merge sees them.
        val merged2 = com.agoro.tv.data.QualityTag.mergeBestQuality(
            listOf(ch("CNN 4K"), proven),
            measured = setOf(proven.url),
        )
        org.junit.Assert.assertEquals(listOf("CNN 4K"), merged2.map { it.name })
    }

    @org.junit.Test
    fun `separator junk entries are dropped`() {
        val bundle = com.agoro.tv.data.ContentClassifier.classify(
            com.agoro.tv.data.M3uParser.parse(
                "#EXTM3U\n#EXTINF:-1,##### SPORTS #####\nhttp://x/1.ts\n#EXTINF:-1,Real Channel\nhttp://x/2.ts"
            )
        )
        org.junit.Assert.assertEquals(1, bundle.channels.size)
        org.junit.Assert.assertEquals("Real Channel", bundle.channels[0].name)
    }
}

/**
 * Providers file series under a VOD-prefixed shelf far more often than not, and
 * matching the movie keyword first put every one of those episodes in Movies —
 * leaving the Series tab reading "No series" on a playlist full of them.
 */
class SeriesGroupTest {

    private fun classify(playlist: String) =
        com.agoro.tv.data.ContentClassifier.classify(
            com.agoro.tv.data.M3uParser.parse(playlist)
        )

    @org.junit.Test
    fun `a VOD-prefixed series shelf is still series`() {
        val bundle = classify(
            """
            #EXTM3U
            #EXTINF:-1 group-title="VOD - SERIES | Drama",Kingdom of Ash - Bolum 1
            http://example.com/9001.mp4
            #EXTINF:-1 group-title="VOD - SERIES | Drama",Kingdom of Ash - Bolum 2
            http://example.com/9002.mp4
            """.trimIndent()
        )
        org.junit.Assert.assertEquals(0, bundle.movies.size)
        org.junit.Assert.assertEquals(1, bundle.series.size)
        org.junit.Assert.assertEquals(2, bundle.series[0].episodes?.size)
    }

    @org.junit.Test
    fun `a genre word does not turn a movie shelf into series`() {
        val bundle = classify(
            """
            #EXTM3U
            #EXTINF:-1 group-title="VOD | Drama",Sintel (2010) 4K
            http://example.com/files/sintel.mp4
            """.trimIndent()
        )
        org.junit.Assert.assertEquals(1, bundle.movies.size)
        org.junit.Assert.assertEquals(0, bundle.series.size)
    }

    @org.junit.Test
    fun `an episode marker still wins over any group name`() {
        val bundle = classify(
            """
            #EXTM3U
            #EXTINF:-1 group-title="VOD | Movies",The Crown S01E01
            http://example.com/files/crown1.mkv
            """.trimIndent()
        )
        org.junit.Assert.assertEquals(0, bundle.movies.size)
        org.junit.Assert.assertEquals(1, bundle.series.size)
    }
}
