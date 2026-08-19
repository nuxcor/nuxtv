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

    /**
     * The fullscreen player: playback, zapping, and the three overlays.
     *
     * Collected separately from [journeys] on purpose. Reaching the player
     * depends on a stream actually starting, which is the one step here that
     * can fail for reasons outside the app; keeping it apart means a bad
     * stream costs this profile and not the browse one.
     */
    @Test
    fun player() = rule.collect(
        packageName = PACKAGE,
        // Capped hard. The default fifteen iterations kept a fullscreen video
        // decoding into a 3840x2160 surface for the better part of an hour and
        // wedged the emulator outright — adb reported the device present while
        // its shell no longer answered. Three passes is ample for CLASS
        // coverage, which is all a profile needs; stability matters for the
        // startup profile, not for enumerating the player's code paths.
        maxIterations = 3,
        stableIterations = 2,
    ) {
        pressHome()
        startActivityAndWait()
        awaitContent()

        openTab(LIVE)
        // openTab already left focus in the content lane, on the channel
        // column. A further RIGHT walked past it into the programme lane,
        // where CENTER opens a programme instead of starting playback.
        device.pressDPadCenter()
        // Streams open over the network; this is the wait that decides whether
        // anything below profiles the player at all.
        settle(6_000)

        zap()
        openPlayerOverlays()

        // Out of the player the way a viewer leaves it. Guarded like the rest:
        // the benchmark kills the process itself between iterations, so this
        // exists to profile the teardown, not to tidy up.
        backInApp()
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

    /**
     * Channel changing — the path reported slowest on real hardware.
     *
     * UP and DOWN are Zap only from bare playback with no overlay showing
     * (PlayerKeyHandler's zapFromBare), so this runs before any overlay is
     * opened. Each change tears down and rebuilds the whole media pipeline,
     * so the settles are generous rather than polite.
     */
    private fun MacrobenchmarkScope.zap() {
        repeat(2) {
            device.pressDPadUp()
            settle(3_500)
        }
        device.pressDPadDown()
        settle(3_500)
    }

    /** The three overlays that live over playback, each opened and dismissed. */
    private fun MacrobenchmarkScope.openPlayerOverlays() {
        // Channel list (LEFT from bare playback).
        device.pressDPadLeft()
        settle(2_000)
        repeat(6) { device.pressDPadDown() }
        settle(1_500)
        backInApp()

        // Mini guide — a dedicated key, so it opens from anywhere in the player.
        device.pressKeyCode(KeyEvent.KEYCODE_GUIDE)
        settle(2_500)
        repeat(4) { device.pressDPadDown() }
        repeat(3) { device.pressDPadRight() }
        settle(1_500)
        backInApp()

        // Transport controls and the info banner.
        device.pressDPadCenter()
        settle(1_500)
        device.pressKeyCode(KeyEvent.KEYCODE_INFO)
        settle(1_500)
        backInApp()

        // Options menu (MENU is the no-key-repeat fallback into it).
        device.pressKeyCode(KeyEvent.KEYCODE_MENU)
        settle(2_000)
        repeat(3) { device.pressDPadDown() }
        settle(1_000)
        backInApp()
    }

    // --- navigation -----------------------------------------------------------

    /**
     * Opens a nav-rail destination by its position in the rail.
     *
     * Two things here were each learned the hard way, so neither is arbitrary.
     *
     * The LEFT count is 12, not 4. Four is enough only from a row that has not
     * scrolled — and browseShelves scrolls every row six cards deep on purpose.
     * The extra presses were then spent walking back along the row instead of
     * reaching the rail, so the journey wandered Home, collected a respectable
     * pile of ui/screens rules, and never opened the screen it was aiming at.
     * Overshooting is free: the rail absorbs LEFT at its own edge.
     *
     * Position, not label. The rail collapses to icons until focused and its
     * labels are not reliably findable through UiAutomator even once expanded;
     * a text-driven version navigated nowhere at all and produced a profile
     * barely half the size. Entering the rail always lands on the SELECTED
     * item, so the only stable address is: walk to the top, then count down.
     */
    private fun MacrobenchmarkScope.openTab(index: Int) {
        repeat(RAIL_ENTRY_STEPS) {
            device.pressDPadLeft()
            device.waitForIdle()
        }
        settle(1_000)
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
        device.pressDPadRight()
        settle(2_000)
    }

    private fun MacrobenchmarkScope.awaitContent() {
        // The splash hands over to either the library or onboarding; either is
        // a legitimate first frame to profile, so this waits for the process to
        // go quiet rather than for one specific view.
        device.waitForIdle()
        settle(3_000)
    }

    /**
     * BACK, but only while the app still owns the screen.
     *
     * An unguarded pressBack is how a journey destroys its own profile: if an
     * earlier step didn't land where it thought (a slow launch, a missed
     * focus), the backs walk out of the app instead of closing overlays, the
     * process ends, and the run dies with "never flushed profiles in any
     * process" — pointing at the collection rather than at the navigation that
     * actually went wrong.
     */
    private fun MacrobenchmarkScope.backInApp() {
        if (device.currentPackageName != PACKAGE) return
        device.pressBack()
        settle(1_500)
    }

    private fun MacrobenchmarkScope.settle(ms: Long = 1_000) {
        device.waitForIdle()
        Thread.sleep(ms)
    }

    private companion object {
        const val PACKAGE = "com.nuxcor.nuxtv"

        /**
         * Enough LEFT presses to cross the widest content row this app builds
         * and still reach the rail. It is an upper bound, not a count — the
         * loop stops the moment the rail names itself.
         */
        // Rail order, Search excluded (it lives on Home's pill).
        const val HOME = 0
        const val LIVE = 1
        const val MOVIES = 2
        const val RAIL_SIZE = 6

        /** Enough LEFT presses to cross the widest scrolled row and still arrive. */
        const val RAIL_ENTRY_STEPS = 12

        /** NuxMotion.FocusDwellMs, plus room for the emulator to keep up. */
        const val FOCUS_DWELL_MS = 500L
    }
}
