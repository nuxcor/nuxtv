package com.agoro.tv

import com.agoro.tv.data.CatalogueManifest
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.data.groupLocalsByMetro
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
        groupLocalsByMetro(channels, m)
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
        groupLocalsByMetro(channels, m)
        assertEquals(listOf("ABC", "NBC", "FOX"), channels.map { it.name.split(" ")[1] })
    }

    /**
     * Only the locals move, and only among the slots they already hold — a
     * sort that shuffled the whole list would renumber every other shelf.
     */
    @Test
    fun `channels that are not metro locals never move`() {
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
        groupLocalsByMetro(channels, m)
        assertEquals(
            listOf("US: CNN", "US: ABC 7 NEW YORK", "US: ESPN", "US: ABC 7 CHICAGO", "US: TNT"),
            channels.map { it.name },
        )
    }

    @Test
    fun `a shelf with nothing to group is left alone`() {
        val channels = mutableListOf(ch(1, "US: CNN"), ch(2, "US: ESPN"))
        groupLocalsByMetro(channels, manifest())
        assertEquals(listOf("US: CNN", "US: ESPN"), channels.map { it.name })
    }
}
