package com.agoro.tv

import com.agoro.tv.player.StallMonitor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule behind "it says it's live and the screen is black".
 *
 * Two watchdog false positives have shipped from this monitor's neighbours —
 * a tunnel veto that read a seek as a HAL freeze, and a format-change verdict
 * that called a picture which never stopped a black screen. Both were rules
 * about a sequence of readings, checked only on hardware. This one is checked
 * here, and the healthy shapes get as many tests as the broken one.
 */
class StallMonitorTest {

    private val grace = 6_000L
    private val poll = 2_000L

    @Test
    fun `a stream whose clock keeps moving is never stuck`() {
        val monitor = StallMonitor(grace)
        var position = 0L
        for (tick in 1..20) {
            position += poll
            assertFalse(monitor.sample(position, tick * poll, settling = false))
        }
    }

    @Test
    fun `a clock standing still past the grace is stuck`() {
        val monitor = StallMonitor(grace)
        // Playing normally, then the pipeline stops dead at 10s.
        assertFalse(monitor.sample(8_000L, 8_000L, settling = false))
        assertFalse(monitor.sample(10_000L, 10_000L, settling = false))
        // Three polls of the same position: the verdict lands as the grace
        // closes, not a whole grace period later.
        assertFalse(monitor.sample(10_000L, 12_000L, settling = false))
        assertFalse(monitor.sample(10_000L, 14_000L, settling = false))
        assertTrue(monitor.sample(10_000L, 16_000L, settling = false))
    }

    @Test
    fun `a stall shorter than the grace is not a verdict`() {
        val monitor = StallMonitor(grace)
        assertFalse(monitor.sample(10_000L, 10_000L, settling = false))
        assertFalse(monitor.sample(10_000L, 12_000L, settling = false))
        assertFalse(monitor.sample(10_000L, 14_000L, settling = false))
        // It came back before the grace closed; the run starts over.
        assertFalse(monitor.sample(12_000L, 16_000L, settling = false))
        assertFalse(monitor.sample(12_000L, 20_000L, settling = false))
        assertFalse(monitor.sample(12_000L, 21_000L, settling = false))
    }

    @Test
    fun `the first reading is never a verdict`() {
        // No baseline means no evidence: a monitor armed on a stream that has
        // been sitting at 0 must not convict it on sight.
        val monitor = StallMonitor(grace)
        assertFalse(monitor.sample(0L, 500_000L, settling = false))
    }

    @Test
    fun `a settling window is time the clock is allowed to stand still`() {
        // A decoder re-initialisation, a track change, a surface handed back:
        // each empties the renderers and refills them, and the clock really
        // does stop while it happens.
        val monitor = StallMonitor(grace)
        assertFalse(monitor.sample(10_000L, 10_000L, settling = false))
        for (tick in 1..10) {
            assertFalse(monitor.sample(10_000L, 10_000L + tick * poll, settling = true))
        }
    }

    @Test
    fun `a seek backwards is not a stopped clock`() {
        // The one thing that moves the clock the wrong way. It marks a
        // reconfiguration on its way past, so the reading after it settles the
        // baseline rather than counting against it.
        val monitor = StallMonitor(grace)
        assertFalse(monitor.sample(600_000L, 10_000L, settling = false))
        assertFalse(monitor.sample(60_000L, 12_000L, settling = true))
        assertFalse(monitor.sample(62_000L, 14_000L, settling = false))
        assertFalse(monitor.sample(64_000L, 16_000L, settling = false))
    }

    @Test
    fun `a verdict is raised once, not once per poll`() {
        // The engine goes on polling until stop() takes the callback away;
        // one dead stream must not spend the session's whole retry budget.
        val monitor = StallMonitor(grace)
        assertFalse(monitor.sample(10_000L, 10_000L, settling = false))
        assertTrue(monitor.sample(10_000L, 16_000L, settling = false))
        assertFalse(monitor.sample(10_000L, 18_000L, settling = false))
        assertFalse(monitor.sample(10_000L, 20_000L, settling = false))
    }

    @Test
    fun `reset drops the run so a re-tune starts clean`() {
        val monitor = StallMonitor(grace)
        assertFalse(monitor.sample(10_000L, 10_000L, settling = false))
        monitor.reset()
        // Same position, well past the grace — but the run it belonged to is
        // gone, so this is a first reading again.
        assertFalse(monitor.sample(10_000L, 30_000L, settling = false))
        assertFalse(monitor.sample(10_000L, 32_000L, settling = false))
    }

    @Test
    fun `a paused-then-resumed stream is judged on playing time only`() {
        // The caller polls only while the player says it is playing, so a gap
        // in the readings is a gap in wall clock the stream never owed. What
        // it must not do is convict on the first reading after the gap.
        val monitor = StallMonitor(grace)
        assertFalse(monitor.sample(10_000L, 10_000L, settling = false))
        monitor.reset()
        assertFalse(monitor.sample(10_000L, 900_000L, settling = false))
        assertFalse(monitor.sample(12_000L, 902_000L, settling = false))
    }
}
