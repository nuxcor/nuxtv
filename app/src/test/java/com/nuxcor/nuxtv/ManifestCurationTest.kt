package com.nuxcor.nuxtv

import com.nuxcor.nuxtv.data.CatalogueManifest
import com.nuxcor.nuxtv.data.ContentBundle
import com.nuxcor.nuxtv.data.LiveChannel
import com.nuxcor.nuxtv.data.ManifestCuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The curation pass decides what the viewer can reach at all, so its failure
 * mode is a channel that exists on the provider and nowhere in the app. These
 * cover the four ways that was happening.
 */
class ManifestCurationTest {

    private fun channel(id: Int, category: String?) = LiveChannel(
        id = "live:$id",
        name = "Channel $id",
        logo = null,
        url = "http://host/live/u/p/$id.ts",
        categoryId = category,
        xtreamId = id,
    )

    private fun manifest(
        categories: Map<String, CatalogueManifest.CategoryRule> = emptyMap(),
        collapse: Map<String, CatalogueManifest.CollapseTile> = emptyMap(),
        drop: List<Int> = emptyList(),
        keptRegions: List<String> = listOf("US"),
    ) = CatalogueManifest(
        sections = CatalogueManifest.Sections(
            live = listOf(CatalogueManifest.Section(key = "NEWS", label = "News")),
        ),
        categories = CatalogueManifest.Categories(live = categories),
        keptRegions = keptRegions,
        regionLabels = mapOf("US" to "United States"),
        dropStreamIds = drop,
        collapse = CatalogueManifest.Collapse(live = collapse),
    )

    @Test
    fun `a channel the manifest cannot classify survives`() {
        // Finding 1: this used to `continue`, deleting the channel outright —
        // no view could reach it, so any category the manifest had not seen
        // silently removed its channels.
        val bundle = ContentBundle(channels = listOf(channel(1, "unknown-category")))
        val out = ManifestCuration.apply(bundle, manifest())
        assertEquals(1, out.channels.size)
        // Kept where the provider filed it, so All channels and search find it.
        assertEquals("unknown-category", out.channels[0].categoryId)
        // But it earns no curated shelf.
        assertTrue(out.liveCategories.isEmpty())
    }

    @Test
    fun `a classified channel still gets its shelf`() {
        val bundle = ContentBundle(channels = listOf(channel(1, "42")))
        val out = ManifestCuration.apply(
            bundle,
            manifest(categories = mapOf("42" to CatalogueManifest.CategoryRule("NEWS", "US"))),
        )
        assertEquals("US|NEWS", out.channels[0].categoryId)
        assertEquals(1, out.liveCategories.size)
    }

    @Test
    fun `collapse does not depend on tile order`() {
        // Finding 6: sources were added then the primary removed, per tile.
        // Tile B listing tile A's primary among its sources re-added it after
        // A had already removed it, and nothing removed it again — so A's
        // channel vanished purely because of map iteration order.
        val m = manifest(
            categories = mapOf("42" to CatalogueManifest.CategoryRule("NEWS", "US")),
            collapse = mapOf(
                "a" to CatalogueManifest.CollapseTile(primary = 1, sources = listOf(1, 2)),
                "b" to CatalogueManifest.CollapseTile(primary = 3, sources = listOf(3, 1)),
            ),
        )
        assertTrue("primary 1 must still render", 1 !in m.collapsedAway)
        assertTrue("primary 3 must still render", 3 !in m.collapsedAway)
        assertTrue("folded source stays folded", 2 in m.collapsedAway)
    }

    @Test
    fun `a dropped primary promotes the next source instead of erasing the tile`() {
        // Finding 6, second half: 165 tiles in the shipped manifest name a
        // primary that drop_stream_ids also kills. With no promotion the whole
        // tile renders nothing — primary dropped, sources folded behind it.
        val m = manifest(
            categories = mapOf("42" to CatalogueManifest.CategoryRule("NEWS", "US")),
            collapse = mapOf(
                "a" to CatalogueManifest.CollapseTile(primary = 1, sources = listOf(1, 2, 3)),
            ),
            drop = listOf(1),
        )
        assertTrue("promoted primary renders", 2 !in m.collapsedAway)
        assertTrue("remaining source stays folded", 3 in m.collapsedAway)
        assertEquals(listOf(3), m.fallbacks[2])
    }

    @Test
    fun `folded sources become playable fallback urls`() {
        // Finding 7: fallbacks was computed and never read, so collapsing a
        // channel's several streams into one tile deleted its failover.
        val m = manifest(
            categories = mapOf("42" to CatalogueManifest.CategoryRule("NEWS", "US")),
            collapse = mapOf(
                "a" to CatalogueManifest.CollapseTile(primary = 1, sources = listOf(1, 2, 3)),
            ),
        )
        val out = ManifestCuration.apply(ContentBundle(channels = listOf(channel(1, "42"))), m)
        assertEquals(
            listOf("http://host/live/u/p/2.ts", "http://host/live/u/p/3.ts"),
            out.channels[0].fallbackUrls,
        )
    }

    @Test
    fun `dropped alternates are never offered as fallbacks`() {
        val m = manifest(
            categories = mapOf("42" to CatalogueManifest.CategoryRule("NEWS", "US")),
            collapse = mapOf(
                "a" to CatalogueManifest.CollapseTile(primary = 1, sources = listOf(1, 2, 3)),
            ),
            drop = listOf(2),
        )
        val out = ManifestCuration.apply(ContentBundle(channels = listOf(channel(1, "42"))), m)
        assertEquals(listOf("http://host/live/u/p/3.ts"), out.channels[0].fallbackUrls)
    }

    @Test
    fun `the region suffix only appears when regions actually differ`() {
        val single = manifest(
            categories = mapOf("42" to CatalogueManifest.CategoryRule("NEWS", "US")),
            keptRegions = listOf("US", "UK"),
        )
        val out = ManifestCuration.apply(ContentBundle(channels = listOf(channel(1, "42"))), single)
        // kept_regions has two entries, but only one survived into the
        // catalogue, so "News · United States" would distinguish nothing.
        assertEquals("News", out.liveCategories[0].name)
    }
}
