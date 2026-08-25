package com.agoro.tv

import com.agoro.tv.ui.player.reconnectDelayMs
import com.agoro.tv.ui.player.reconnectDelaysMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A one-connection provider line holds the just-dropped slot for many seconds,
 * so live reconnects must wait across that window; a film keeps the quick pair.
 */
class ReconnectBackoffTest {

    @Test
    fun `live backs off far longer than vod`() {
        // The whole point: an immediate live retry collides with the slot the
        // panel has not yet freed, so even the first live wait is seconds, and
        // the later ones straddle a typical release window.
        assertEquals(6_000L, reconnectDelayMs(isLive = true, attempt = 0))
        assertEquals(20_000L, reconnectDelayMs(isLive = true, attempt = 1))
        assertEquals(40_000L, reconnectDelayMs(isLive = true, attempt = 2))
    }

    @Test
    fun `vod keeps the quick pair`() {
        assertEquals(3_000L, reconnectDelayMs(isLive = false, attempt = 0))
        assertEquals(6_000L, reconnectDelayMs(isLive = false, attempt = 1))
    }

    @Test
    fun `live gets more attempts than vod`() {
        assertTrue(reconnectDelaysMs(isLive = true).size > reconnectDelaysMs(isLive = false).size)
    }

    @Test
    fun `an overrun attempt clamps to the last step rather than crashing`() {
        assertEquals(40_000L, reconnectDelayMs(isLive = true, attempt = 9))
        assertEquals(6_000L, reconnectDelayMs(isLive = false, attempt = 9))
    }
}
