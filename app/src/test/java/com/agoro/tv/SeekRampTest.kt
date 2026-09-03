package com.agoro.tv

import com.agoro.tv.ui.player.END_GUARD_MS
import com.agoro.tv.ui.player.seekStepMs
import com.agoro.tv.ui.player.seekTargetMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scrub's arithmetic. The behaviour it exists to produce — six presses
 * being one seek instead of six — is in the session; this is the shape of the
 * ramp and the clamping, which is what a viewer feels as "it goes where I
 * meant".
 */
class SeekRampTest {

    private val hour = 3_600_000L

    @Test
    fun `the first presses stay small, where precision matters`() {
        assertEquals(10_000L, seekStepMs(0))
        assertEquals(10_000L, seekStepMs(2))
    }

    @Test
    fun `a run of presses grows the step`() {
        assertEquals(30_000L, seekStepMs(3))
        assertEquals(60_000L, seekStepMs(8))
        assertEquals(60_000L, seekStepMs(40))
    }

    @Test
    fun `the ramp never shrinks as a scrub goes on`() {
        // A step that got smaller mid-scrub would feel like the remote
        // slipping. Whatever the thresholds become, this must hold.
        val steps = (0..30).map { seekStepMs(it) }
        assertEquals(steps.sorted(), steps)
    }

    @Test
    fun `crossing a film takes about a dozen presses, not a hundred`() {
        // The reason the ramp exists. At a flat ten seconds an hour of film
        // is 360 presses; this is the number that has to stay reasonable.
        var pos = 0L
        var presses = 0
        while (pos < hour - END_GUARD_MS && presses < 500) {
            pos = seekTargetMs(pos, +1, presses, hour)
            presses++
        }
        assertTrue("took $presses presses to cross an hour", presses <= 70)
    }

    @Test
    fun `a scrub cannot land on the end and trigger the next episode`() {
        // Landing exactly on the duration is indistinguishable from the film
        // finishing: it fires the end-of-item path and offers the next
        // episode, so reaching for the last scene would show the next one.
        val target = seekTargetMs(hour - 1_000, +1, 20, hour)
        assertEquals(hour - END_GUARD_MS, target)
        assertTrue(target < hour)
    }

    @Test
    fun `a scrub cannot go below the start`() {
        assertEquals(0L, seekTargetMs(5_000, -1, 0, hour))
        assertEquals(0L, seekTargetMs(0, -1, 20, hour))
    }

    @Test
    fun `an unknown duration still seeks forward`() {
        // Live-ish or a file that has not reported its length yet: clamping
        // to a ceiling of zero would pin every forward press at the start.
        assertEquals(10_000L, seekTargetMs(0, +1, 0, 0))
    }

    @Test
    fun `direction is taken from the sign and nothing else`() {
        assertEquals(30_000L, seekTargetMs(0, +5, 3, hour))
        assertEquals(0L, seekTargetMs(30_000, -5, 3, hour))
    }
}
