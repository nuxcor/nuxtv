package com.agoro.tv

import com.agoro.tv.data.CatalogueManifest
import com.agoro.tv.data.ContentBundle
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.data.ManifestCuration
import com.agoro.tv.data.Movie
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

    @Test
    fun `a tile's own shelf beats the provider category its primary sits in`() {
        // Finding 5, the real shape. The build pass already resolves a 4K/8K
        // tier to the channel's true territory and folds the tier in as a
        // SOURCE — every shipped tile carries US/UK/CA/AFR, never a tier. The
        // app was ignoring that and re-reading the primary's provider
        // category, which still says "4K", so the whole channel was deleted
        // for owning the very source the fold had just added.
        val m = manifest(
            categories = mapOf("662" to CatalogueManifest.CategoryRule("SPORTS", "4K")),
            collapse = mapOf(
                "espn|US" to CatalogueManifest.CollapseTile(
                    section = "NEWS", region = "US", primary = 1, sources = listOf(1, 2),
                ),
            ),
            keptRegions = listOf("US", "UK"),
        )
        val out = ManifestCuration.apply(ContentBundle(channels = listOf(channel(1, "662"))), m)
        assertEquals(1, out.channels.size)
        // The tile's US/NEWS, not the category's 4K/SPORTS.
        assertEquals("US|NEWS", out.channels[0].categoryId)
    }

    @Test
    fun `a tier region on a channel with no tile opens no shelf of its own`() {
        // The residual case: a tier-filed stream that never collapsed, so no
        // tile resolved its territory. It is kept — a tier is not a country we
        // refuse to serve — but it earns no shelf: three such channels were
        // enough to put a bare "Sports" tab beside "Sports · DSTV", which
        // reads as two different things and sorts between what it duplicates.
        val m = manifest(
            categories = mapOf("662" to CatalogueManifest.CategoryRule("NEWS", "4K")),
            keptRegions = listOf("US", "UK"),
        )
        val out = ManifestCuration.apply(ContentBundle(channels = listOf(channel(1, "662"))), m)
        assertEquals(1, out.channels.size)
        assertEquals("662", out.channels[0].categoryId)
        assertTrue(out.liveCategories.isEmpty())
    }

    @Test
    fun `a manifest that works without regions still shelves by section`() {
        val m = manifest(
            categories = mapOf("42" to CatalogueManifest.CategoryRule("NEWS")),
            keptRegions = emptyList(),
        )
        val out = ManifestCuration.apply(ContentBundle(channels = listOf(channel(1, "42"))), m)
        assertEquals("NEWS", out.channels[0].categoryId)
        assertEquals(listOf("News"), out.liveCategories.map { it.name })
    }

    @Test
    fun `a real territory outside kept_regions is still filtered out`() {
        val m = manifest(
            categories = mapOf("9" to CatalogueManifest.CategoryRule("NEWS", "IE")),
            keptRegions = listOf("US", "UK"),
        )
        val out = ManifestCuration.apply(ContentBundle(channels = listOf(channel(1, "9"))), m)
        assertTrue(out.channels.isEmpty())
    }

    @Test
    fun `a blank region never becomes an empty one`() {
        // Finding 13: CategoryRule.region defaults to "", which produced the
        // category id "|NEWS" and the shelf label "News · ". Whatever else
        // happens, neither of those may appear.
        val m = manifest(categories = mapOf("42" to CatalogueManifest.CategoryRule("NEWS")))
        val out = ManifestCuration.apply(ContentBundle(channels = listOf(channel(1, "42"))), m)
        assertTrue(out.channels.none { it.categoryId?.startsWith("|") == true })
        assertTrue(out.liveCategories.none { it.id.startsWith("|") || it.name.endsWith("· ") })
    }

    @Test
    fun `a section the manifest never declares is not a shelf`() {
        // Finding 4: uk_reassign points 19 ids at "ALWAYS_ON" where the
        // declared key is "24/7". An undeclared key has no label and no place
        // in the order, so it surfaced as a shelf titled "ALWAYS_ON · United
        // Kingdom" sorted last. The channel falls through to its category.
        val m = manifest(
            categories = mapOf("42" to CatalogueManifest.CategoryRule("NEWS", "UK")),
            keptRegions = listOf("UK"),
        ).copy(
            regionLabels = mapOf("UK" to "United Kingdom"),
            ukReassign = mapOf("1" to "ALWAYS_ON"),
        )
        val out = ManifestCuration.apply(ContentBundle(channels = listOf(channel(1, "42"))), m)
        assertTrue(out.liveCategories.none { "ALWAYS_ON" in it.id || "ALWAYS_ON" in it.name })
        assertEquals("UK|NEWS", out.channels[0].categoryId)
    }

    @Test
    fun `a hidden movie section is dropped, as a hidden live one is`() {
        // Finding 12: hidden_by_default was declared on both lists and read on
        // neither but live, so PPV's live categories were removed while the
        // identically flagged movie shelf rendered.
        val m = manifest().copy(
            sections = CatalogueManifest.Sections(
                live = listOf(CatalogueManifest.Section("NEWS", "News")),
                movies = listOf(CatalogueManifest.Section("PPV", "Events", hidden = true)),
            ),
            categories = CatalogueManifest.Categories(
                movies = mapOf("99" to CatalogueManifest.CategoryRule("PPV")),
            ),
        )
        val movie = Movie(id = "m1", name = "Fight", poster = null, url = "u", categoryId = "99")
        val out = ManifestCuration.apply(ContentBundle(movies = listOf(movie)), m)
        assertTrue(out.movies.isEmpty())
    }

    @Test
    fun `a reassigned channel inherits the territory its table implies`() {
        // Finding 13: a channel moved by uk_reassign whose provider category
        // carries no rule came out region-less and opened a second, unlabelled
        // "News" shelf beside "News · United Kingdom".
        val m = manifest(
            categories = mapOf("42" to CatalogueManifest.CategoryRule("NEWS", "UK")),
            keptRegions = listOf("UK", "US"),
        ).copy(
            regionLabels = mapOf("UK" to "United Kingdom", "US" to "United States"),
            ukReassign = mapOf("7" to "NEWS"),
        )
        val bundle = ContentBundle(channels = listOf(channel(1, "42"), channel(7, "no-rule")))
        val out = ManifestCuration.apply(bundle, m)
        // Both land on the same UK shelf rather than one opening a twin.
        assertEquals(listOf("UK|NEWS", "UK|NEWS"), out.channels.map { it.categoryId })
        assertEquals(1, out.liveCategories.size)
    }

    @Test
    fun `cleaning marks the bundle so a cache read does not repeat it`() {
        // Finding 3: re-cleaning a curated bundle rewrote its shelf labels, so
        // a warm start disagreed with the network load that wrote the cache.
        val cleaned = com.agoro.tv.data.CategoryCleaner.clean(ContentBundle())
        assertTrue(cleaned.cleaned)
        assertTrue(!ContentBundle().cleaned)
    }
}
