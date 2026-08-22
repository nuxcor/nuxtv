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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxMotion
import com.agoro.tv.ui.theme.NuxShape
import com.agoro.tv.ui.theme.Space

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
