package com.agoro.tv

import com.agoro.tv.data.ContentBundle
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.data.renumberChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Channel numbers are the guide's row order, assigned once on the finished
 * bundle. Everything that speaks a number — the guide's labels, the banner,
 * digit tuning in the player and the lists — reads this one mapping, so these
 * pin down that it is dense, 1-based, and deaf to whatever the provider sent.
 */
class ChannelNumberingTest {

    private fun channel(id: String, number: Int? = null) = LiveChannel(
        id = id,
        name = "Channel $id",
        logo = null,
        url = "http://host/live/u/p/$id.ts",
        categoryId = null,
        number = number,
    )

    @Test
    fun `numbers are 1-based positions in the finished list`() {
        val out = renumberChannels(
            ContentBundle(channels = listOf(channel("a"), channel("b"), channel("c")))
        )
        assertEquals(listOf(1, 2, 3), out.channels.map { it.number })
    }

    @Test
    fun `provider numbers are overwritten, so curation gaps cannot leak through`() {
        // Xtream `num` fields and pre-curation ordinals arrive with holes where
        // channels were dropped or collapsed; a typed number must never miss.
        val out = renumberChannels(
            ContentBundle(channels = listOf(channel("a", 7), channel("b", 411), channel("c", 2)))
        )
        assertEquals(listOf(1, 2, 3), out.channels.map { it.number })
    }

    @Test
    fun `an already-numbered bundle passes through by identity`() {
        // Runs on every publish, cache reads included — an equal-but-new bundle
        // would defeat the reference-identity caches built on top of it.
        val bundle = ContentBundle(
            channels = listOf(channel("a", 1), channel("b", 2)),
        )
        assertSame(bundle, renumberChannels(bundle))
    }
}
