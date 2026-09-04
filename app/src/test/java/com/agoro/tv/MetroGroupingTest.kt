package com.agoro.tv

import com.agoro.tv.data.CatalogueManifest
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.data.orderChannels
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Locals shelf reads as "my city's stations", which it only does when a
 * market's channels sit together. The provider's fetch order interleaves them.
 */
class MetroGroupingTest {

    private fun ch(id: Int, name: String) = LiveChannel(
        id = "live:$id", name = name, logo = null, url = "http://x/$id",
        categoryId = "US|LOCALS", xtreamId = id,
    )

    private fun manifest(vararg tiles: Triple<String, String, List<Int>>) =
        CatalogueManifest(
            topMetros = listOf("NEW YORK", "LOS ANGELES", "CHICAGO"),
            metroLocals = tiles.associate { (metro, net, ids) ->
                "$metro|$net" to CatalogueManifest.MetroLocal(
                    metro = metro, network = net, primary = ids.first(), sources = ids,
                )
            },
        )

    @Test
    fun `markets are grouped, in the manifest's order`() {
        val m = manifest(
            Triple("CHICAGO", "ABC", listOf(1)),
            Triple("NEW YORK", "CBS", listOf(2)),
            Triple("LOS ANGELES", "FOX", listOf(3)),
            Triple("NEW YORK", "ABC", listOf(4)),
        )
        val channels = mutableListOf(
            ch(1, "US: ABC 7 CHICAGO"),
            ch(2, "US: CBS 2 NEW YORK"),
            ch(3, "US: FOX 11 LOS ANGELES"),
            ch(4, "US: ABC 7 NEW YORK"),
        )
        orderChannels(channels, m)
        assertEquals(
            listOf("US: ABC 7 NEW YORK", "US: CBS 2 NEW YORK",
                   "US: FOX 11 LOS ANGELES", "US: ABC 7 CHICAGO"),
            channels.map { it.name },
        )
    }

    /** Within one market the networks keep a fixed order, in every city. */
    @Test
    fun `networks keep one order inside a market`() {
        val m = manifest(
            Triple("NEW YORK", "FOX", listOf(1)),
            Triple("NEW YORK", "ABC", listOf(2)),
            Triple("NEW YORK", "NBC", listOf(3)),
        )
        val channels = mutableListOf(
            ch(1, "US: FOX 5 NEW YORK"),
            ch(2, "US: ABC 7 NEW YORK"),
            ch(3, "US: NBC 4 NEW YORK"),
        )
        orderChannels(channels, m)
        assertEquals(listOf("ABC", "NBC", "FOX"), channels.map { it.name.split(" ")[1] })
    }

    /**
     * Channels never cross a shelf boundary. Everything shares one categoryId
     * here, so the whole run sorts together; the point is that the run keeps
     * the same slots and nothing escapes into another shelf.
     */
    @Test
    fun `channels stay within their own shelf`() {
        val m = manifest(
            Triple("CHICAGO", "ABC", listOf(10)),
            Triple("NEW YORK", "ABC", listOf(20)),
        )
        val channels = mutableListOf(
            ch(99, "US: CNN"),
            ch(10, "US: ABC 7 CHICAGO"),
            ch(98, "US: ESPN"),
            ch(20, "US: ABC 7 NEW YORK"),
            ch(97, "US: TNT"),
        )
        orderChannels(channels, m)
        // Metro locals lead (market order), then the rest alphabetically.
        assertEquals(
            listOf("US: ABC 7 NEW YORK", "US: ABC 7 CHICAGO", "US: CNN", "US: ESPN", "US: TNT"),
            channels.map { it.name },
        )
    }

    /** Every other shelf reads alphabetically. */
    @Test
    fun `a shelf with no locals sorts by name`() {
        val channels = mutableListOf(
            ch(1, "US: TNT"), ch(2, "US: ESPN"), ch(3, "US: CNN"), ch(4, "US: beIN SPORTS"),
        )
        orderChannels(channels, manifest())
        assertEquals(
            listOf("US: beIN SPORTS", "US: CNN", "US: ESPN", "US: TNT"),
            channels.map { it.name },
        )
    }

    /**
     * The network run is a LOCALS rule. On any other shelf a channel that
     * merely has a network in its name is just a channel, and the News shelf
     * opened with seven of them before the alphabet started.
     */
    @Test
    fun `a national shelf ignores the network order`() {
        val channels = mutableListOf(
            ch(1, "US: FOX NEWS CHANNEL"), ch(2, "BBC NEWS"), ch(3, "PRIME: ABC NEWS LIVE"),
            ch(4, "US: AL JAZEERA ENGLISH"), ch(5, "US: NBC CNBC"),
        )
        orderChannels(channels, manifest())
        assertEquals(
            listOf(
                "PRIME: ABC NEWS LIVE", "US: AL JAZEERA ENGLISH", "BBC NEWS",
                "US: NBC CNBC", "US: FOX NEWS CHANNEL",
            ),
            channels.map { it.name },
        )
    }

    /** Shelves are ordered independently; one never bleeds into another. */
    @Test
    fun `each shelf sorts on its own`() {
        fun c(id: Int, name: String, cat: String) = LiveChannel(
            id = "live:$id", name = name, logo = null, url = "http://x/$id",
            categoryId = cat, xtreamId = id,
        )
        val channels = mutableListOf(
            c(1, "US: TNT", "US|SPORTS"),
            c(2, "UK: SKY NEWS", "UK|NEWS"),
            c(3, "US: ESPN", "US|SPORTS"),
            c(4, "UK: BBC NEWS", "UK|NEWS"),
        )
        orderChannels(channels, manifest())
        assertEquals(
            listOf("US: ESPN", "UK: BBC NEWS", "US: TNT", "UK: SKY NEWS"),
            channels.map { it.name },
        )
    }

    @Test
    fun `a shelf with nothing to group is left alone`() {
        val channels = mutableListOf(ch(1, "US: CNN"), ch(2, "US: ESPN"))
        orderChannels(channels, manifest())
        assertEquals(listOf("US: CNN", "US: ESPN"), channels.map { it.name })
    }
}
