package com.agoro.tv

import com.agoro.tv.ui.components.LongPressAction
import com.agoro.tv.ui.components.longPressAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The card long-press must fire from a clock, not from key auto-repeats, so a
 * remote that never repeats the select key can still reach a card's menu.
 */
class DpadLongPressTest {

    /** Replays a select-key stream, carrying the "timer fired" flag the way the modifier does. */
    private fun run(vararg events: Triple<Boolean, Int, Boolean>): List<LongPressAction> =
        events.map { (isKeyDown, repeatCount, fired) -> longPressAction(isKeyDown, repeatCount, fired) }

    @Test
    fun `a fresh press starts the timer`() {
        assertEquals(LongPressAction.Start, longPressAction(isKeyDown = true, repeatCount = 0, fired = false))
    }

    @Test
    fun `a tap released before the timeout falls through to onClick`() {
        // down, then up with the timer not yet fired: Start, then PassUp (not consumed).
        assertEquals(
            listOf(LongPressAction.Start, LongPressAction.PassUp),
            run(Triple(true, 0, false), Triple(false, 0, false)),
        )
    }

    @Test
    fun `a hold past the timeout swallows its own release`() {
        // down, timer fires (fired=true), then up: Start, then SwallowUp (consumed, no onClick).
        assertEquals(
            listOf(LongPressAction.Start, LongPressAction.SwallowUp),
            run(Triple(true, 0, false), Triple(false, 0, true)),
        )
    }

    @Test
    fun `auto-repeat downs are ignored, so the clock alone decides`() {
        // A box that DOES repeat must not restart the timer on every repeat.
        assertEquals(
            listOf(LongPressAction.Start, LongPressAction.Ignore, LongPressAction.Ignore),
            run(Triple(true, 0, false), Triple(true, 1, false), Triple(true, 2, false)),
        )
    }
}
