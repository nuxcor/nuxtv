package com.agoro.tv

import com.agoro.tv.ui.components.DialogCenterKey
import com.agoro.tv.ui.components.dialogCenterKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A dialog opened by a long press must not act on the release of the press
 * that opened it — that release belongs to the card behind the scrim.
 */
class DialogCenterKeyTest {

    /** Replays one key stream through the gate, carrying the armed flag. */
    private fun run(vararg events: Pair<Boolean, Int>): List<DialogCenterKey> {
        var armed = false
        return events.map { (isKeyDown, repeatCount) ->
            dialogCenterKey(isKeyDown, repeatCount, armed).also {
                if (it == DialogCenterKey.Arm) armed = true
            }
        }
    }

    @Test
    fun `the hold that opened the dialog is swallowed whole`() {
        // What a menu sees after a card's long press fired at repeat 1: the
        // rest of the repeats, then the release.
        assertEquals(
            listOf(DialogCenterKey.Swallow, DialogCenterKey.Swallow, DialogCenterKey.Swallow),
            run(true to 2, true to 3, false to 0),
        )
    }

    @Test
    fun `a press of the dialog's own still clicks`() {
        assertEquals(
            listOf(DialogCenterKey.Arm, DialogCenterKey.Pass),
            run(true to 0, false to 0),
        )
    }

    @Test
    fun `the dialog's own press survives the opening hold`() {
        assertEquals(
            listOf(
                // ...the tail of the hold that opened it,
                DialogCenterKey.Swallow, DialogCenterKey.Swallow,
                // then a fresh press on the action the viewer chose.
                DialogCenterKey.Arm, DialogCenterKey.Pass,
            ),
            run(true to 2, false to 0, true to 0, false to 0),
        )
    }

    @Test
    fun `a hold inside the dialog stays whole once armed`() {
        // Nothing is swallowed after arming, so a Surface's own long-press
        // handling still sees repeat 1.
        assertEquals(
            listOf(DialogCenterKey.Arm, DialogCenterKey.Pass, DialogCenterKey.Pass),
            run(true to 0, true to 1, false to 0),
        )
    }
}
