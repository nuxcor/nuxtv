package com.nuxcor.nuxtv

import com.nuxcor.nuxtv.data.Category
import com.nuxcor.nuxtv.data.CategoryCleaner
import com.nuxcor.nuxtv.data.ContentBundle
import com.nuxcor.nuxtv.data.LiveChannel
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
        assertEquals(listOf("US | SPORTS", "UK| SPORTS"), cleaned.liveCategories.map { it.name })
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
        assertEquals("SPORTS", cleaned.liveCategories.single().name)
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
