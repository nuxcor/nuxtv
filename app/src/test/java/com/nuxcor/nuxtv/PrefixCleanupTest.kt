package com.nuxcor.nuxtv

import com.nuxcor.nuxtv.data.Category
import com.nuxcor.nuxtv.data.CategoryCleaner
import com.nuxcor.nuxtv.data.ContentBundle
import com.nuxcor.nuxtv.data.ContentClassifier
import com.nuxcor.nuxtv.data.LiveChannel
import com.nuxcor.nuxtv.data.Movie
import com.nuxcor.nuxtv.data.Series
import org.junit.Assert.assertEquals
import org.junit.Test

class PrefixCleanupTest {

    @Test
    fun `cleanTitle strips every provider prefix shape the panel ships`() {
        val cases = mapOf(
            "4K-AMZ - The Boys (US)" to "The Boys",
            "AMZ - The Boys" to "The Boys",
            "4K-EN - Dark" to "Dark",
            "EN - Dark" to "Dark",
            "4K-A+ - Severance" to "Severance",
            "A+ - Severance" to "Severance",
            "4K-NF -NF - Stranger Things" to "Stranger Things",
            "NF - Ozark" to "Ozark",
            "MAX - The Wire" to "The Wire",
            "DSC+ - Gold Rush" to "Gold Rush",
            "PCOCK - Poker Face" to "Poker Face",
            "SHWT | Dexter" to "Dexter",
            "D+ - Loki" to "Loki",
            "P+ - Halo (US)" to "Halo",
            "Top Gear (GB)" to "Top Gear",
            "Kurtlar (TR)" to "Kurtlar",
        )
        for ((raw, expected) in cases) {
            assertEquals(raw, expected, ContentClassifier.cleanTitle(raw))
        }
    }

    @Test
    fun `channel displayName drops region prefixes and quality, keeps identity`() {
        val channel = LiveChannel(
            id = "1", name = "US| ESPN FHD", logo = null, url = "http://x/1",
            categoryId = null, quality = "FHD",
        )
        assertEquals("ESPN", channel.displayName)
        assertEquals("US| ESPN FHD", channel.name) // grouping and EPG identity untouched

        val uk = channel.copy(name = "UK| Sky Sports 1")
        assertEquals("Sky Sports 1", uk.displayName)

        val bare = channel.copy(name = "beIN Sports")
        assertEquals("beIN Sports", bare.displayName)
    }

    @Test
    fun `clean re-cleans stale cached movie and series names`() {
        val bundle = ContentBundle(
            movieCategories = listOf(Category("20", "VOD")),
            movies = listOf(
                Movie(id = "m1", name = "4K-NF - Extraction (US)", poster = null,
                    url = "http://x/m1", categoryId = "20"),
            ),
            seriesCategories = listOf(Category("10", "Drama")),
            series = listOf(
                Series(id = "s1", name = "AMZ - The Boys (US)", poster = null, categoryId = "10"),
            ),
        )
        val cleaned = CategoryCleaner.clean(bundle)
        assertEquals("Extraction", cleaned.movies.single().name)
        assertEquals("The Boys", cleaned.series.single().name)
        // Ids survive, so resume positions and origins keep resolving.
        assertEquals("m1", cleaned.movies.single().id)
        assertEquals("s1", cleaned.series.single().id)
    }
}
