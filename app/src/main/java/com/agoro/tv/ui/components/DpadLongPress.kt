package com.agoro.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A D-pad long-press that does not depend on the remote auto-repeating.
 *
 * tv-material3's `Surface(onClick, onLongClick)` detects a hold by counting
 * key auto-repeats: on `ACTION_DOWN` it fires `onLongClick` only once
 * `repeatCount` reaches a threshold, and on `ACTION_UP` with no long-press it
 * fires `onClick`. A great many Android TV and Google TV remotes send a single
 * `ACTION_DOWN` and then `ACTION_UP` with NO repeats for the select key, so
 * the threshold is never reached, `onLongClick` never fires, and a hold opens
 * the item exactly as a tap would — the "long-press does nothing, it just
 * opens the page" a viewer reports when a card's context menu is unreachable.
 *
 * This measures the hold with a clock instead. On the first key-down it starts
 * a timer for the platform long-press timeout; if the key is still held when
 * the timer elapses, [onLongClick] fires. The release that follows a fired
 * long-press is swallowed so the underlying surface does not also treat it as a
 * click. A release BEFORE the timeout is left alone, so the surface's own
 * `onClick` still opens the item on a tap.
 *
 * Apply it to the same surface that carries `onClick`, and pass that surface
 * `onLongClick = null` so this is the sole source — otherwise a box that DOES
 * auto-repeat would fire the menu from both. A null [onLongClick] here returns
 * the receiver unchanged, so a card with no menu is byte-for-byte as before.
 */
@Composable
fun Modifier.dpadLongPress(onLongClick: (() -> Unit)?): Modifier {
    if (onLongClick == null) return this
    val timeoutMs = LocalViewConfiguration.current.longPressTimeoutMillis
    val scope = rememberCoroutineScope()
    val holder = remember { LongPressHolder() }
    DisposableEffect(Unit) {
        onDispose {
            holder.job?.cancel()
            holder.job = null
        }
    }
    return this.onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (native.keyCode !in DpadCenterKeyCodes) return@onPreviewKeyEvent false
        val isKeyDown = native.action == android.view.KeyEvent.ACTION_DOWN
        when (longPressAction(isKeyDown, native.repeatCount, holder.fired)) {
            LongPressAction.Start -> {
                holder.fired = false
                holder.job?.cancel()
                holder.job = scope.launch {
                    delay(timeoutMs)
                    holder.job = null
                    holder.fired = true
                    onLongClick()
                }
                false
            }
            // A held key's later downs, and any down while a press is already
            // being timed: nothing to start, nothing to consume.
            LongPressAction.Ignore -> false
            // The long-press already fired on this hold; swallow its release
            // so the surface underneath does not also click.
            LongPressAction.SwallowUp -> {
                holder.fired = false
                holder.job?.cancel()
                holder.job = null
                true
            }
            // Released before the timeout: a tap. Cancel the timer and let the
            // surface's own onClick run.
            LongPressAction.PassUp -> {
                holder.job?.cancel()
                holder.job = null
                false
            }
        }
    }
}

/** Per-node long-press timer and whether it has fired; not Compose state — reading it must not recompose. */
private class LongPressHolder {
    @Volatile var job: Job? = null
    @Volatile var fired: Boolean = false
}

/** The select keys a TV remote sends for "OK". Matches the dialog guard's set. */
private val DpadCenterKeyCodes = intArrayOf(
    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
    android.view.KeyEvent.KEYCODE_ENTER,
    android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
)

/** What [dpadLongPress] does with one select-key event; the whole decision, with no Android in it. */
internal enum class LongPressAction { Start, Ignore, SwallowUp, PassUp }

/**
 * @param isKeyDown true for ACTION_DOWN, false for ACTION_UP.
 * @param repeatCount the event's repeat count; a fresh press is 0.
 * @param fired whether the hold's long-press timer has already elapsed.
 */
internal fun longPressAction(isKeyDown: Boolean, repeatCount: Int, fired: Boolean): LongPressAction =
    when {
        isKeyDown && repeatCount == 0 -> LongPressAction.Start
        isKeyDown -> LongPressAction.Ignore
        fired -> LongPressAction.SwallowUp
        else -> LongPressAction.PassUp
    }
