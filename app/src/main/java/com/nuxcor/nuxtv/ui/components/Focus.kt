package com.nuxcor.nuxtv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.delay

/**
 * Request focus, retrying across the next few frames.
 *
 * The target of an arrival focus request routinely composes a frame or two
 * after the effect that wants to focus it (lazy lists, saveable-state
 * restoration, dialogs). A single `requestFocus()` throws or lands nowhere;
 * a short retry loop absorbs the gap. Returns true once focus was requested
 * without throwing.
 */
suspend fun FocusRequester.requestFocusRetrying(
    retries: Int = 5,
    intervalMs: Long = 60,
): Boolean {
    repeat(retries) { attempt ->
        val ok = runCatching { requestFocus() }.isSuccess
        if (ok) return true
        if (attempt < retries - 1) delay(intervalMs)
    }
    return false
}

/**
 * False while the viewer's focus is somewhere the shell owns — the nav rail —
 * and a pane must not pull it away.
 *
 * Tabs switch as focus travels the rail, so a tab whose content grabs focus on
 * arrival yanks the cursor out of the rail mid-journey: UP and DOWN then do
 * nothing and the only way back is LEFT, which nothing on screen says. Empty
 * tabs were the worst of it (their status pane focuses its action), but Search
 * focusing its field has the same shape. Panes still claim focus normally when
 * the viewer arrives any other way.
 */
val LocalArrivalFocusAllowed = androidx.compose.runtime.compositionLocalOf { true }

/**
 * The common "focus this on arrival" case: a requester whose target is focused
 * once per [keys] change, with the standard retry loop.
 *
 * Honours [LocalArrivalFocusAllowed]: while the shell holds focus the request
 * is skipped, and armed for the moment the viewer moves into the content.
 *
 * ARRIVAL, not "whenever the shell lets go". The allowed flag is driven off
 * the rail holding focus, so it flips false→true every time the viewer comes
 * back from the rail — and firing on that edge dragged focus off wherever
 * they had been: on Search, going LEFT into the rail and RIGHT back landed
 * on the query field instead of the result they left. The request is spent
 * once per [keys] change and stays spent until the keys actually change.
 */
@Composable
fun rememberInitialFocus(vararg keys: Any?): FocusRequester {
    val requester = remember { FocusRequester() }
    val allowed = LocalArrivalFocusAllowed.current
    // A plain holder rather than mutableStateOf: spending the request must
    // not itself invalidate composition, and nothing reads this during it.
    val pending = remember(*keys) { booleanArrayOf(true) }
    LaunchedEffect(allowed, *keys) {
        if (allowed && pending[0]) {
            pending[0] = false
            requester.requestFocusRetrying()
        }
    }
    return requester
}
