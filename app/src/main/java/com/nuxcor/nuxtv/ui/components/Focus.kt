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
 * The common "focus this on arrival" case: a requester whose target is focused
 * once per [keys] change, with the standard retry loop.
 */
@Composable
fun rememberInitialFocus(vararg keys: Any?): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(*keys) { requester.requestFocusRetrying() }
    return requester
}
