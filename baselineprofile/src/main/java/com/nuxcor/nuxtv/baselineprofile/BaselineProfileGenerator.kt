package com.nuxcor.nuxtv.baselineprofile

import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Walks the app the way a viewer does, so ART can be told ahead of time which
 * classes and methods to compile.
 *
 * The journeys here are not arbitrary — they are the three places the app was
 * reported slow on real TV hardware: moving focus through channel lists, the
 * in-player channel switch, and opening the guide and search. Anything a
 * journey never reaches gets no profile, so the value of this file is entirely
 * in how much of the real UI it manages to touch.
 *
 * Everything is D-pad driven. A TV app has no touch path worth profiling, and
 * focus traversal is itself a large part of what recomposes.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /**
     * Startup only, collected separately so it can also become the *startup*
     * profile — a smaller, ordered subset that ART pre-compiles before the
     * first frame. Mixing the browsing journeys into it would dilute it.
     */
    @Test
    fun startup() = rule.collect(
        packageName = PACKAGE,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        awaitContent()
    }

    /** Everything after the first frame: shelves, channel lists, guide, search. */
    @Test
    fun journeys() = rule.collect(packageName = PACKAGE) {
        pressHome()
        startActivityAndWait()
        awaitContent()

        browseShelves()
        openTab(LIVE)
        walkChannelList()
        openSearch()
        openTab(LIVE)
        openTab(MOVIES)
        settle(2_000)
    }

    // --- journeys -------------------------------------------------------------

    /** Home's rows: down through the shelves, right along each one. */
    private fun MacrobenchmarkScope.browseShelves() {
        repeat(4) {
            repeat(6) { device.pressDPadRight() }
            device.pressDPadDown()
            device.waitForIdle()
        }
        settle()
    }

    /**
     * The channel list, at the speed a viewer actually holds the button down.
     * This is the path that was recomposing the whole catalogue on the main
     * thread; profiling it is the point of the exercise.
     */
    private fun MacrobenchmarkScope.walkChannelList() {
        repeat(40) { device.pressDPadDown() }
        settle()
        repeat(15) { device.pressDPadUp() }
        settle()
        // Sideways into the category rail and back, which re-filters the list.
        device.pressDPadLeft()
        repeat(3) { device.pressDPadDown() }
        device.pressDPadRight()
        settle()
    }

    /**
     * Search is deliberately absent from the nav rail — it is reached from the
     * pill at the top right of Home, and UP from Home's first row is what gets
     * there. Walking the rail for it would silently profile nothing.
     */
    private fun MacrobenchmarkScope.openSearch() {
        openTab(HOME)
        repeat(3) {
            device.pressDPadUp()
            device.waitForIdle()
        }
        val pill = device.wait(Until.findObject(By.text("Search")), 3_000)
        if (pill != null) pill.click() else device.pressDPadCenter()
        settle(2_000)
        typeSearch()
    }

    private fun MacrobenchmarkScope.typeSearch() {
        // Search focuses its field on entry, so this lands straight in it.
        listOf(KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_O).forEach {
            device.pressKeyCode(it)
            device.waitForIdle()
        }
        settle(2_000)
        repeat(5) { device.pressDPadDown() }
        settle()
    }

    // --- navigation -----------------------------------------------------------

    /**
     * The rail selects by FOCUS, not by click: landing on an item and waiting
     * out the dwell switches the tab. Entering it always lands on whichever
     * tab is currently selected, so the only reliable address is an index —
     * walk to the top, then count down. Reading the labels does not work, and
     * failed silently: the rail renders icons until it has focus, so a text
     * lookup found nothing and the blind fallback walked the whole rail down
     * to Settings, profiling the one screen nobody complained about.
     *
     * Search is absent from the rail by design, so the indices below are the
     * rail's own order, not HomeTab's.
     */
    private fun MacrobenchmarkScope.openTab(index: Int) {
        // Into the rail, wherever focus currently is.
        repeat(4) {
            device.pressDPadLeft()
            device.waitForIdle()
        }
        // Up to Home, then down to the target. Overshooting up is safe: the
        // first item absorbs it.
        repeat(RAIL_SIZE) {
            device.pressDPadUp()
            device.waitForIdle()
        }
        repeat(index) {
            device.pressDPadDown()
            device.waitForIdle()
            // The dwell is what commits the selection; moving faster than it
            // skims past every tab and selects only the last.
            Thread.sleep(FOCUS_DWELL_MS)
        }
        settle(1_500)
        // Back out of the rail into the content lane it just opened.
        device.pressDPadRight()
        settle(1_500)
    }

    private fun MacrobenchmarkScope.awaitContent() {
        // The splash hands over to either the library or onboarding; either is
        // a legitimate first frame to profile, so this waits for the process to
        // go quiet rather than for one specific view.
        device.waitForIdle()
        settle(3_000)
    }

    private fun MacrobenchmarkScope.settle(ms: Long = 1_000) {
        device.waitForIdle()
        Thread.sleep(ms)
    }

    private companion object {
        const val PACKAGE = "com.nuxcor.nuxtv"

        // Rail order, Search excluded (it lives on Home's pill).
        const val HOME = 0
        const val LIVE = 1
        const val MOVIES = 2
        const val RAIL_SIZE = 6

        /** NuxMotion.FocusDwellMs, plus room for the emulator to keep up. */
        const val FOCUS_DWELL_MS = 500L
    }
}
