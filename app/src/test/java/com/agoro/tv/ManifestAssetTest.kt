package com.agoro.tv

import com.agoro.tv.data.CatalogueManifest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shipped manifest itself, checked against the invariants the build is
 * supposed to guarantee. These are the ones whose failure is invisible in the
 * pipeline and obvious on the shelf.
 */
class ManifestAssetTest {

    private val manifest: CatalogueManifest by lazy {
        val file = File("src/main/assets/catalogue-manifest.json")
        assertTrue("shipped manifest is missing at ${file.absolutePath}", file.exists())
        Json { ignoreUnknownKeys = true; isLenient = true }
            .decodeFromString(CatalogueManifest.serializer(), file.readText())
    }

    /** Two folds over the same streams disagreeing about which one represents
     *  the channel is what put NBC 4 New York on the Locals shelf twice: the
     *  app treats every tile primary as a survivor, so a stream folded away by
     *  one fold came back as the other's primary. */
    @Test
    fun `no stream is the primary of two different tiles`() {
        val owners = HashMap<Int, MutableList<String>>()
        manifest.collapse.live.forEach { (key, tile) ->
            owners.getOrPut(tile.primary) { mutableListOf() } += "collapse:$key"
        }
        manifest.metroLocals.forEach { (key, tile) ->
            owners.getOrPut(tile.primary) { mutableListOf() } += "metro:$key"
        }
        val shared = owners.filterValues { it.size > 1 }
        assertTrue("streams claimed by more than one tile: $shared", shared.isEmpty())
    }

    /** A stream folded into a metro tile must not also be a collapse member:
     *  the metro fold owns anything with a market. */
    @Test
    fun `metro members do not also sit in quality tiles`() {
        val metro = manifest.metroLocals.values
            .flatMap { listOf(it.primary) + it.sources }.toSet()
        val overlap = manifest.collapse.live
            .filterValues { tile -> tile.sources.any { it in metro } }
        assertTrue("collapse tiles holding metro members: ${overlap.keys}", overlap.isEmpty())
    }

    /** The metro fold picks its primary on measured picture, which is
     *  routinely the copy with the least useful name — so every metro tile
     *  has to carry the label the pipeline composed for it. */
    @Test
    fun `every metro tile names its primary`() {
        val unnamed = manifest.metroLocals.values
            .map { it.primary }
            .filter { manifest.displayName[it.toString()].isNullOrBlank() }
        assertTrue("metro primaries with no display name: $unnamed", unnamed.isEmpty())
    }

    /** Canada left the catalogue on 2026-08-22 — see KEEP_REGIONS in
     *  tools/manifest/build_manifest.py. A territory that comes back has to
     *  come back deliberately, in the build, not by a stale asset. */
    /**
     * A broadcaster feed is a literal url the player opens as-is. The build
     * checks each one the day it is added; this checks none ships malformed,
     * and that the two news tiles the feeds exist for still carry them.
     */
    @Test
    fun `broadcaster feeds are https playlists on the news tiles`() {
        manifest.collapse.live.forEach { (key, tile) ->
            tile.direct.forEach { url ->
                val path = url.substringBefore('?')
                assertTrue("$key: $url", url.startsWith("https://") && path.endsWith(".m3u8"))
            }
        }
        for (key in listOf("abcnews|US", "nbcnewsnow|US")) {
            assertTrue("$key carries its broadcaster feed",
                manifest.collapse.live[key]?.direct.orEmpty().isNotEmpty())
        }
    }

    @Test
    fun `canada is not a kept territory`() {
        assertFalse("CA is back in kept_regions", "CA" in manifest.keptRegions)
        assertFalse("CA is back in region_labels", "CA" in manifest.regionLabels.keys)
        val tiles = manifest.collapse.live.values.count { it.region == "CA" }
        assertEquals("collapse tiles still filed under CA", 0, tiles)
    }

    /** Every territory that opens a shelf needs a label, or the strip shows a
     *  bare region code. */
    @Test
    fun `every kept territory has a label`() {
        val missing = manifest.keptRegions.filterNot { manifest.regionLabels.containsKey(it) }
        assertTrue("territories with no label: $missing", missing.isEmpty())
    }
}
