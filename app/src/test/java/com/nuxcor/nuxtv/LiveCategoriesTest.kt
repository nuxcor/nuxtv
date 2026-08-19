package com.nuxcor.nuxtv

import com.nuxcor.nuxtv.data.Category
import com.nuxcor.nuxtv.data.ContentBundle
import com.nuxcor.nuxtv.data.LiveChannel
import com.nuxcor.nuxtv.ui.screens.CATEGORY_ALL
import com.nuxcor.nuxtv.ui.screens.CATEGORY_FAVORITES
import com.nuxcor.nuxtv.ui.screens.CATEGORY_RECENT
import com.nuxcor.nuxtv.ui.screens.channelsInCategory
import com.nuxcor.nuxtv.ui.screens.liveCategoryList
import com.nuxcor.nuxtv.ui.screens.resolveCategoryId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCategoriesTest {

    private fun channel(id: String, categoryId: String? = "sport") =
        LiveChannel(id = id, name = "Channel $id", logo = null, url = "http://x/$id", categoryId = categoryId)

    private val sport = Category("sport", "Sport")
    private val news = Category("news", "News")
    private val channels = listOf(channel("1"), channel("2"), channel("3", "news"))
    private val bundle = ContentBundle(liveCategories = listOf(sport, news), channels = channels)

    @Test
    fun `All dedups cross-category duplicates only when asked`() {
        val dupes = listOf(
            LiveChannel(id = "a", name = "US| CNN HD", logo = null, url = "http://x/a", categoryId = "news"),
            LiveChannel(id = "b", name = "CNN FHD", logo = null, url = "http://x/b", categoryId = "sport",
                quality = "FHD"),
            LiveChannel(id = "c", name = "BBC", logo = null, url = "http://x/c", categoryId = "news"),
        )
        // The merge itself now runs off the main thread in the view model;
        // what this asserts is the contract between the two — All shows the
        // list it is handed, and nothing else is affected by it.
        val deduped = com.nuxcor.nuxtv.data.QualityTag.mergeBestQuality(
            dupes,
            keyOf = { com.nuxcor.nuxtv.data.EpgMatcher.normalizeKey(it.name) },
        )
        // One CNN survives (the FHD variant outranks the HD one) plus BBC.
        assertEquals(listOf("b", "c"), deduped.map { it.id })

        val all = channelsInCategory(CATEGORY_ALL, dupes, emptySet(), emptyList(), allChannels = deduped)
        assertEquals(listOf("b", "c"), all.map { it.id })
        // Merging off: All is the full list, which is also the default.
        assertEquals(3, channelsInCategory(CATEGORY_ALL, dupes, emptySet(), emptyList()).size)
        // Per-category views untouched even with dedup on.
        assertEquals(2, channelsInCategory("news", dupes, emptySet(), emptyList(), allChannels = deduped).size)
        // A favorited deduped-away variant still appears under Favorites.
        val favs = channelsInCategory(
            CATEGORY_FAVORITES, dupes, setOf("http://x/a"), emptyList(), allChannels = deduped,
        )
        assertEquals(listOf("a"), favs.map { it.id })
    }

    @Test
    fun `shortcuts are hidden until they hold something`() {
        val bare = liveCategoryList(bundle, channels, favorites = emptySet(), recents = emptyList())
        assertEquals(listOf(CATEGORY_ALL, "sport", "news"), bare.map { it.id })

        val withBoth = liveCategoryList(
            bundle, channels,
            favorites = setOf("http://x/1"),
            recents = listOf("http://x/2"),
        )
        assertEquals(listOf(CATEGORY_ALL, CATEGORY_RECENT, CATEGORY_FAVORITES, "sport", "news"),
            withBoth.map { it.id })
    }

    @Test
    fun `a recent channel no longer in the playlist does not conjure the shortcut`() {
        // Recents are stream URLs kept across reloads, so they outlive channels
        // that a refresh dropped.
        val list = liveCategoryList(
            bundle, channels,
            favorites = emptySet(),
            recents = listOf("http://x/gone"),
        )
        assertFalse(list.any { it.id == CATEGORY_RECENT })
    }

    @Test
    fun `recent keeps its own order, newest first`() {
        val recents = listOf("http://x/3", "http://x/1")
        val result = channelsInCategory(CATEGORY_RECENT, channels, emptySet(), recents)
        assertEquals(listOf("3", "1"), result.map { it.id })
    }

    @Test
    fun `recent drops urls with no matching channel`() {
        val recents = listOf("http://x/gone", "http://x/2")
        val result = channelsInCategory(CATEGORY_RECENT, channels, emptySet(), recents)
        assertEquals(listOf("2"), result.map { it.id })
    }

    @Test
    fun `other categories keep the order they were given`() {
        assertEquals(listOf("1", "2", "3"), channelsInCategory(CATEGORY_ALL, channels, emptySet(), emptyList()).map { it.id })
        assertEquals(listOf("3"), channelsInCategory("news", channels, emptySet(), emptyList()).map { it.id })
        assertEquals(
            listOf("2"),
            channelsInCategory(CATEGORY_FAVORITES, channels, setOf("http://x/2"), emptyList()).map { it.id },
        )
    }

    @Test
    fun `a selection that stops existing falls back to All`() {
        val categories = liveCategoryList(bundle, channels, emptySet(), emptyList())
        // The last favorite was un-starred, so the shortcut it named is gone.
        assertEquals(CATEGORY_ALL, resolveCategoryId(CATEGORY_FAVORITES, categories))
        assertEquals("news", resolveCategoryId("news", categories))
    }

    @Test
    fun `both views of live tv agree on every id`() {
        // The guide and the channel list share one selected id; this is the
        // property that stopped them meaning different things.
        val categories = liveCategoryList(
            bundle, channels, favorites = setOf("http://x/1"), recents = listOf("http://x/2"),
        )
        for (category in categories) {
            assertTrue(
                "no channels resolved for ${category.id}",
                channelsInCategory(category.id, channels, setOf("http://x/1"), listOf("http://x/2")).isNotEmpty(),
            )
        }
    }
}
