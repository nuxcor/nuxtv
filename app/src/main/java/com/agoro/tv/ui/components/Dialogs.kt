@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.agoro.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxMotion
import com.agoro.tv.ui.theme.NuxShape
import com.agoro.tv.ui.theme.Space

/** What OK does to the keycodes androidx.tv's Surface acts on: 23, 66, 160. */
private val CenterKeys = intArrayOf(
    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
    android.view.KeyEvent.KEYCODE_ENTER,
    android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
)

/** [dialogCenterKey]'s verdict on one OK event. */
internal enum class DialogCenterKey { Arm, Swallow, Pass }

/**
 * Whether a dialog may let the focused action see this OK event yet.
 *
 * A dialog opened by a LONG press is focused while OK is still held down, so
 * the release lands on the action the dialog has just focused. androidx.tv's
 * Surface fires onClick on the key-UP and suppresses it only when the SAME
 * surface saw the long press — which a menu row composed a moment ago never
 * did. Every long-press menu in the app therefore ran its first action the
 * instant the viewer let go: a hold on a Continue watching card opened the
 * menu and left for the series before it could be read. The player already
 * acts on the release for exactly this reason; dialogs had no such guard.
 *
 * So a dialog is deaf to OK until it has seen a press of its own — a key-down
 * at repeat 0. What comes before that (the tail of the hold's repeats, and
 * its release) belongs to the card behind the scrim and is swallowed.
 */
internal fun dialogCenterKey(
    isKeyDown: Boolean,
    repeatCount: Int,
    armed: Boolean,
): DialogCenterKey = when {
    armed -> DialogCenterKey.Pass
    isKeyDown && repeatCount == 0 -> DialogCenterKey.Arm
    else -> DialogCenterKey.Swallow
}

/**
 * The one dialog chrome: scrim, dialog-radius panel, stroke, padding — with the
 * standard entrance (scrim fade, panel fade + slight scale). Every dialog and
 * sheet in the app composes its content inside this instead of re-building the
 * chrome by hand.
 */
@Composable
fun DialogScaffold(
    onDismiss: () -> Unit,
    width: Dp? = null,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    contentAlignment: Alignment = Alignment.Center,
    padding: Dp = Space.xl,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onDismiss)
    // Set by the first OK press this dialog owns; see [dialogCenterKey]. A
    // plain holder: nothing in composition reads it, and arming it must not
    // invalidate anything.
    val centerArmed = remember { booleanArrayOf(false) }
    // One-shot entrance; exits are instant because the caller removes the
    // dialog from composition (animating that would need state the callers
    // don't have — not worth the plumbing on a 10-foot UI).
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(NuxMotion.FastMs, easing = NuxMotion.StandardEasing))
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            // The fade animates the scrim's own color, not a layer alpha over
            // the whole subtree: the layer form made the panel itself
            // translucent for as long as the entrance was in flight, and on
            // hardware where the animation stalled the dialog stayed stuck
            // half-transparent with the page text bleeding through it. The
            // panel below is opaque from the first frame no matter what the
            // clock does.
            .drawBehind {
                drawRect(NuxColors.Scrim.copy(alpha = NuxColors.Scrim.alpha * progress.value))
            }
            // Before the focus group, so it previews every key on its way
            // down to the action that has focus.
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.keyCode !in CenterKeys) return@onPreviewKeyEvent false
                when (
                    dialogCenterKey(
                        isKeyDown = event.type == KeyEventType.KeyDown,
                        repeatCount = native.repeatCount,
                        armed = centerArmed[0],
                    )
                ) {
                    DialogCenterKey.Arm -> { centerArmed[0] = true; false }
                    DialogCenterKey.Swallow -> true
                    DialogCenterKey.Pass -> false
                }
            }
            // A focus group alone does not contain the D-pad: when nothing
            // inside the panel lies in the pressed direction, Compose's
            // search escalates to the ancestors and lands on whatever sits
            // under the scrim — a settings chip, a poster, or the shell's
            // edge catcher, which then slid the drawer open over the dialog.
            // Cancelling the exit keeps the remote on the dialog until the
            // dialog itself closes, which is what a scrim promises.
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup(),
        contentAlignment = contentAlignment,
    ) {
        Column(
            modifier = Modifier
                .then(if (width != null) Modifier.width(width) else Modifier)
                .graphicsLayer {
                    val scale = 0.96f + 0.04f * progress.value
                    scaleX = scale
                    scaleY = scale
                }
                .clip(NuxShape.Dialog)
                .background(NuxColors.Surface)
                .border(1.dp, NuxColors.Stroke, NuxShape.Dialog)
                .padding(padding),
            horizontalAlignment = horizontalAlignment,
        ) {
            content()
        }
    }
}

/** The one set of text-field colors — previously copy-pasted with drift. */
object NuxFieldDefaults {
    @Composable
    fun colors() = OutlinedTextFieldDefaults.colors(
        focusedTextColor = NuxColors.OnSurface,
        unfocusedTextColor = NuxColors.OnSurface,
        focusedContainerColor = NuxColors.SurfaceVariant,
        unfocusedContainerColor = NuxColors.SurfaceVariant,
        focusedBorderColor = NuxColors.Primary,
        unfocusedBorderColor = NuxColors.Stroke,
        focusedLabelColor = NuxColors.Primary,
        unfocusedLabelColor = NuxColors.OnSurfaceDim,
        cursorColor = NuxColors.Primary,
    )
}
