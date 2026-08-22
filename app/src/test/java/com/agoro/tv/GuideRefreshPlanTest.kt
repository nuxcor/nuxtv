package com.agoro.tv

import com.agoro.tv.data.GuideRefreshPlan
import com.agoro.tv.data.planGuideRefresh
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The guide used to re-download itself on almost every launch. The decision
 * that let it is now a pure function, and these are the launches it got
 * wrong.
 */
class GuideRefreshPlanTest {

    private val hour = 3600_000L
    private val now = 1_800_000_000_000L

    private val nothing = GuideRefreshPlan(publishCache = false, fold = false)
    private val cacheOnly = GuideRefreshPlan(publishCache = true, fold = false)
    private val cacheThenFold = GuideRefreshPlan(publishCache = true, fold = true)
    private val foldOnly = GuideRefreshPlan(publishCache = false, fold = true)

    @Test
    fun `a cold start with an hour-old guide on disk publishes it and fetches nothing`() {
        assertEquals(
            cacheOnly,
            planGuideRefresh(
                nowMs = now, sameUrl = false, guideReady = false,
                loadedAtMs = 0, guideSavedAtMs = now - hour,
            ),
        )
    }

    /**
     * The bug. The first request of a start accepted the cache and stamped
     * the CACHE'S age as the load time; the second request — content always
     * publishes at least twice — found that stamp outside the debounce, saw
     * a Ready guide, and folded thirteen packs behind a guide an hour old.
     * Now the second request sees the same hour-old guide and leaves it.
     */
    @Test
    fun `the second request of a start does not refold a fresh guide`() {
        val accepted = now - 5 * 60_000L
        assertEquals(
            nothing,
            planGuideRefresh(
                nowMs = now, sameUrl = true, guideReady = true,
                loadedAtMs = accepted, guideSavedAtMs = now - hour,
            ),
        )
        // And an hour later, past the debounce, still nothing: the gate is
        // the guide's own age, not how long ago this process looked at it.
        assertEquals(
            nothing,
            planGuideRefresh(
                nowMs = now + hour, sameUrl = true, guideReady = true,
                loadedAtMs = accepted, guideSavedAtMs = now - hour,
            ),
        )
    }

    @Test
    fun `a stale guide on disk is shown first and refolded behind`() {
        assertEquals(
            cacheThenFold,
            planGuideRefresh(
                nowMs = now, sameUrl = false, guideReady = false,
                loadedAtMs = 0, guideSavedAtMs = now - 7 * hour,
            ),
        )
    }

    @Test
    fun `a guide too old to be worth showing is not shown`() {
        assertEquals(
            foldOnly,
            planGuideRefresh(
                nowMs = now, sameUrl = false, guideReady = false,
                loadedAtMs = 0, guideSavedAtMs = now - 49 * hour,
            ),
        )
    }

    @Test
    fun `no guide at all means fold`() {
        assertEquals(
            foldOnly,
            planGuideRefresh(
                nowMs = now, sameUrl = false, guideReady = false,
                loadedAtMs = 0, guideSavedAtMs = null,
            ),
        )
    }

    /** The 6-hour loop, on a guide the app folded itself six hours ago. */
    @Test
    fun `the periodic refresh refolds once the guide has aged`() {
        assertEquals(
            foldOnly,
            planGuideRefresh(
                nowMs = now, sameUrl = true, guideReady = true,
                loadedAtMs = now - 6 * hour, guideSavedAtMs = now - 6 * hour,
            ),
        )
    }

    /**
     * A progressive partial whose fold then died leaves a Ready guide with
     * no stamp. It must not be mistaken for a fresh one on the next ask —
     * that is exactly the state that has to try again.
     */
    @Test
    fun `an unstamped guide on screen is refolded`() {
        assertEquals(
            foldOnly,
            planGuideRefresh(
                nowMs = now, sameUrl = true, guideReady = true,
                loadedAtMs = now - 20 * 60_000L, guideSavedAtMs = null,
            ),
        )
    }

    @Test
    fun `switching to another guide url always folds`() {
        assertEquals(
            foldOnly,
            planGuideRefresh(
                nowMs = now, sameUrl = false, guideReady = true,
                loadedAtMs = now, guideSavedAtMs = null,
            ),
        )
    }

    /** A retry storm is still one request: a burst inside the debounce is ignored. */
    @Test
    fun `a burst of requests inside the debounce is one request`() {
        assertEquals(
            nothing,
            planGuideRefresh(
                nowMs = now, sameUrl = true, guideReady = true,
                loadedAtMs = now - 60_000L, guideSavedAtMs = now - 8 * hour,
            ),
        )
    }
}
