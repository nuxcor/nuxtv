package com.agoro.tv

import android.os.Looper
import com.agoro.tv.data.PlayableItem
import com.agoro.tv.data.PlaybackRequest
import com.agoro.tv.player.PlaybackFault
import com.agoro.tv.ui.player.PlayerLayer
import com.agoro.tv.ui.player.PlayerSession
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The failure ladder's budget: what a live drop spends, and what earns it
 * back.
 *
 * The bug these pin down is the one viewers meet as "it used to recover, now
 * it just shows Retry after a while". The budget only ever fell — nothing gave
 * it back for a stream that dropped, reconnected and then played perfectly for
 * an hour — so a channel left on all evening arrived at the error card with no
 * reconnect attempted at all.
 *
 * The urls here deliberately do NOT match the Xtream live form, so the format
 * and source rungs both decline and every error lands on the reconnect: this
 * is about the budget, not about the rungs above it.
 */
@RunWith(RobolectricTestRunner::class)
// Same pin, same reason as GuideStoreTest: Robolectric's API 35+ image needs
// Java 21 and CI runs 17. Nothing here is sdk-sensitive.
@Config(sdk = [34])
class LadderBudgetTest {

    private lateinit var scope: CoroutineScope

    /** Robolectric's main looper is paused, so delays only advance on demand. */
    private fun elapse(seconds: Long) =
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    private fun session(): PlayerSession = PlayerSession(
        context = RuntimeEnvironment.getApplication(),
        scope = scope,
        initialRequest = PlaybackRequest(
            items = listOf(PlayableItem(url = "http://provider.example/opaque", title = "One")),
            startIndex = 0,
            isLive = true,
        ),
        onSaveResume = { _, _, _ -> },
    )

    private fun PlayerSession.drop() = listener.onError("the connection dropped", PlaybackFault.TRANSIENT)

    private fun PlayerSession.plays() = listener.onPlayingChanged(playing = true, buffering = false)

    private fun PlayerSession.stalls() = listener.onPlayingChanged(playing = false, buffering = true)

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Main)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `each drop names the attempt, and the ladder's end is the card`() {
        val session = session()
        // Live has three rungs; each spends one and says which it is, because
        // the whole of a reconnect used to be a blank screen.
        session.drop()
        assertEquals(1, session.reconnectAttempt)
        assertEquals(3, session.reconnectTotal)
        assertNull(session.errorMessage)

        session.drop()
        assertEquals(2, session.reconnectAttempt)
        assertNull(session.errorMessage)

        session.drop()
        assertEquals(3, session.reconnectAttempt)
        assertNull(session.errorMessage)

        // Spent: now, and only now, the viewer gets the card and their remote.
        session.drop()
        assertEquals(0, session.reconnectAttempt)
        assertEquals("the connection dropped", session.errorMessage)
        assertEquals(PlayerLayer.Error, session.layer)
    }

    @Test
    fun `a minute of clean playback gives the budget back`() {
        val session = session()
        repeat(3) { session.drop() }

        // The picture returns and holds. Before this, that counted for
        // nothing: the next drop, however far away, went straight to the card.
        session.plays()
        elapse(61)

        session.drop()
        assertNull(session.errorMessage)
        assertEquals(1, session.reconnectAttempt)
    }

    @Test
    fun `playback that keeps breaking does not`() {
        val session = session()
        repeat(3) { session.drop() }

        // Half a minute of picture, then it stalls again. Only UNBROKEN
        // playing time counts, or a stream that limps for hours would hold a
        // full ladder it has never earned and reconnect for ever.
        session.plays()
        elapse(30)
        session.stalls()
        // Past the minute from when the run began, but the run was broken —
        // and short of the death watchdog, which is not what is under test.
        elapse(35)

        session.drop()
        assertNotNull(session.errorMessage)
        assertEquals(PlayerLayer.Error, session.layer)
    }
}
