package com.nuxcor.nuxtv

import com.nuxcor.nuxtv.data.Category
import com.nuxcor.nuxtv.data.CategoryCleaner
import com.nuxcor.nuxtv.data.ContentBundle
import com.nuxcor.nuxtv.data.LiveChannel
import com.nuxcor.nuxtv.data.Series
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryCleanerTest {

    private fun ch(id: String, name: String, categoryId: String?) = LiveChannel(
        id = id, name = name, logo = null, url = "http://x/$id", categoryId = categoryId,
    )

    @Test
    fun `decoration variants merge, regions stay separate`() {
        val bundle = ContentBundle(
            liveCategories = listOf(
                Category("1", "US | SPORTS"),
                Category("2", "US| SPORT HD"),
                Category("3", "UK| SPORTS"),
            ),
            channels = listOf(
                ch("a", "ESPN", "1"),
                ch("b", "ESPN 2", "2"),
                ch("c", "Sky Sports", "3"),
            ),
        )
        val cleaned = CategoryCleaner.clean(bundle)
        assertEquals(listOf("US Sports", "UK Sports"), cleaned.liveCategories.map { it.name })
        // Both US variants' channels now share the kept category id.
        assertEquals("1", cleaned.channels[0].categoryId)
        assertEquals("1", cleaned.channels[1].categoryId)
        assertEquals("3", cleaned.channels[2].categoryId)
    }

    @Test
    fun `hash-wrapped names unwrap and merge with the plain one`() {
        val bundle = ContentBundle(
            liveCategories = listOf(
                Category("1", "#### SPORTS ####"),
                Category("2", "Sports"),
            ),
            channels = listOf(ch("a", "ESPN", "1"), ch("b", "beIN", "2")),
        )
        val cleaned = CategoryCleaner.clean(bundle)
        assertEquals(1, cleaned.liveCategories.size)
        assertEquals("Sports", cleaned.liveCategories.single().name)
        assertEquals(setOf("1"), cleaned.channels.map { it.categoryId }.toSet())
    }

    @Test
    fun `letterless junk categories are pruned and their channels surface under All`() {
        val bundle = ContentBundle(
            liveCategories = listOf(Category("1", "──────"), Category("2", "News")),
            channels = listOf(ch("a", "CNN", "1"), ch("b", "BBC", "2")),
        )
        val cleaned = CategoryCleaner.clean(bundle)
        assertEquals(listOf("News"), cleaned.liveCategories.map { it.name })
        assertNull(cleaned.channels[0].categoryId)
        assertEquals("2", cleaned.channels[1].categoryId)
    }

    @Test
    fun `categories empty after remap are dropped`() {
        val bundle = ContentBundle(
            liveCategories = listOf(Category("1", "Ghost Town")),
            channels = listOf(ch("a", "CNN", null)),
        )
        val cleaned = CategoryCleaner.clean(bundle)
        assertEquals(emptyList<Category>(), cleaned.liveCategories)
    }

    @Test
    fun `NEWS is not depluralized into a NEW merge`() {
        assertEquals("us news", CategoryCleaner.categoryKey("US | NEWS"))
        org.junit.Assert.assertNotEquals(
            CategoryCleaner.categoryKey("US NEWS"),
            CategoryCleaner.categoryKey("US NEW"),
        )
    }

    @Test
    fun `labels prettify but brand casing survives`() {
        assertEquals("US Sports", CategoryCleaner.displayName("US | SPORTS HD"))
        assertEquals("News", CategoryCleaner.displayName("#### NEWS ####"))
        assertEquals("beIN Sports", CategoryCleaner.displayName("beIN Sports"))
        assertEquals("Documentaries", CategoryCleaner.displayName("DOCUMENTARIES"))
    }

    @Test
    fun `real provider shelf names clean up`() {
        // Names straight off a real panel: ordering index, "Billing"/"VOD"
        // namespace on every row, emoji stars, pseudo-quality words.
        val bundle = ContentBundle(
            liveCategories = listOf(
                Category("1", "1 - Billing - 4K UHD 3840P\u2b50"),
                Category("2", "4 - Billing - USA ULTIMATE"),
                Category("3", "6 - Billing - UK ULTIMATE"),
                Category("4", "10 - Billing - BEIN SPORT FULL\u26bd"),
                Category("5", "13 - Billing - UEFA CHAMPIONS LEAGUE \u26bd"),
            ),
            channels = listOf("1", "2", "3", "4", "5").map {
                LiveChannel(id = it, name = "C$it", logo = null, url = "http://x/$it", categoryId = it)
            },
        )
        val cleaned = CategoryCleaner.clean(bundle)
        assertEquals(
            listOf("4K UHD 3840p", "USA Ultimate", "UK Ultimate", "BEIN Sport Full",
                "UEFA Champions League"),
            cleaned.liveCategories.map { it.name },
        )
    }

    @Test
    fun `VOD quality variants of one brand merge to the brand`() {
        // Straight from a real panel's series list: three Netflix shelves
        // that are the same catalog at different qualities.
        val bundle = ContentBundle(
            seriesCategories = listOf(
                Category("1", "3 - VOD - NETFLIX SERIES 4K 3840P Dolby Vision\u2b50"),
                Category("2", "4 - VOD - NETFLIX DOLBY AUDIO \u2b50"),
                Category("3", "5 - VOD - NETFLIX\u2b50"),
                Category("4", "8 - VOD - DISNEY+ SERIES 4K 3840P Dolby Vision\u2b50"),
                Category("5", "10 - VOD - DISNEY+ SERIES\u2b50"),
            ),
            series = listOf("1", "2", "3", "4", "5").map {
                Series(id = "s$it", name = "S$it", poster = null, categoryId = it)
            },
        )
        val cleaned = CategoryCleaner.clean(bundle)
        assertEquals(listOf("Netflix", "Disney+"), cleaned.seriesCategories.map { it.name })
        assertEquals(
            setOf("1", "4"),
            cleaned.series.map { it.categoryId }.toSet(),
        )
    }

    @Test
    fun `index prefixes are ordering, not identity`() {
        // Same shelf numbered differently after a provider reorder must merge.
        assertEquals(
            CategoryCleaner.categoryKey("3 - SPORTS"),
            CategoryCleaner.categoryKey("7 - SPORTS"),
        )
    }

    @Test
    fun `a quality-tier shelf keeps its name instead of collapsing to residue`() {
        assertEquals("4K UHD 3840p", CategoryCleaner.displayName("1 - 4K UHD 3840P\u2b50"))
    }

    @Test
    fun `plural and singular shelves merge`() {
        assertEquals(
            CategoryCleaner.categoryKey("US | SPORTS HD"),
            CategoryCleaner.categoryKey("US| SPORT"),
        )
        assertEquals(
            CategoryCleaner.categoryKey("MOVIES"),
            CategoryCleaner.categoryKey("Movie"),
        )
    }
}
