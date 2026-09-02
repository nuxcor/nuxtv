package com.agoro.tv

import com.agoro.tv.data.LastExit
import com.agoro.tv.data.exitLabel
import com.agoro.tv.data.sentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The reason table and the sentence a viewer reads out.
 *
 * The reason codes are the framework's own `ApplicationExitInfo.REASON_*`
 * values, written as literals so this stays JVM-testable; the cases that must
 * stay SILENT matter most, because a scary line in Settings after an ordinary
 * evening is worse than no line at all.
 */
class LastExitTest {

    private val utc = ZoneId.of("UTC")

    @Test
    fun `an ordinary close is never reported`() {
        // EXIT_SELF, USER_REQUESTED, SIGNALED, USER_STOPPED, PACKAGE_UPDATED,
        // OTHER — backing out, an update landing, the system trimming a
        // backgrounded app on a 2GB box. All of these happen constantly.
        for (reason in listOf(0, 1, 2, 7, 8, 10, 11, 12, 13)) {
            assertNull("reason $reason must stay silent", exitLabel(reason))
        }
    }

    @Test
    fun `the three causes worth telling apart each get their own words`() {
        assertEquals("the box ran out of memory", exitLabel(3))   // LOW_MEMORY
        assertEquals("the app crashed", exitLabel(4))             // CRASH
        assertEquals("the player crashed", exitLabel(5))          // CRASH_NATIVE
        assertEquals("the app stopped responding", exitLabel(6))  // ANR
        assertEquals("the app used too much memory", exitLabel(9))
    }

    @Test
    fun `the sentence names the fault, the day and the time`() {
        val exit = LastExit(
            label = "the app stopped responding",
            atMs = 1_788_364_800_000, // 2026-09-02 16:00 UTC
            pssKb = 0,
            detail = null,
        )
        assertEquals("The app stopped responding on 2 Sep at 16:00", exit.sentence(utc))
    }

    @Test
    fun `memory is quoted where memory is the question`() {
        val anr = LastExit("the app stopped responding", 1_788_364_800_000, 422_400, null)
        assertTrue(anr.sentence(utc), anr.sentence(utc).endsWith(", using 412 MB"))

        val oom = LastExit("the box ran out of memory", 1_788_364_800_000, 422_400, null)
        assertTrue(oom.sentence(utc).endsWith(", using 412 MB"))
    }

    @Test
    fun `memory is left out of a plain crash, where it only invites a wrong theory`() {
        val crash = LastExit("the app crashed", 1_788_364_800_000, 422_400, null)
        assertEquals("The app crashed on 2 Sep at 16:00", crash.sentence(utc))
    }

    @Test
    fun `an unreported memory figure is not printed as zero`() {
        val anr = LastExit("the app stopped responding", 1_788_364_800_000, 0, null)
        assertEquals("The app stopped responding on 2 Sep at 16:00", anr.sentence(utc))
    }
}
