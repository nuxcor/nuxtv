package com.nuxcor.nuxtv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import com.nuxcor.nuxtv.ui.theme.NuxShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.nuxcor.nuxtv.ui.theme.NuxColors
import com.nuxcor.nuxtv.ui.theme.NuxMotion
import com.nuxcor.nuxtv.ui.theme.Space

/** A focusable action offered by a [StatusPane]. */
data class StatusAction(val label: String, val onClick: () -> Unit)

/**
 * The one self-clearing status toast: scrim pill, slide+fade in, fade out.
 * Pass the nullable message state directly; the last text is kept so the exit
 * animation doesn't run on an empty pill. Position with the caller's modifier.
 */
@Composable
fun ToastBadge(
    message: String?,
    modifier: Modifier = Modifier,
    textColor: androidx.compose.ui.graphics.Color = NuxColors.Secondary,
) {
    var last by remember { mutableStateOf("") }
    LaunchedEffect(message) { if (message != null) last = message }
    androidx.compose.animation.AnimatedVisibility(
        visible = message != null,
        enter = androidx.compose.animation.fadeIn(
            tween(NuxMotion.StandardMs, easing = NuxMotion.StandardEasing)
        ) + androidx.compose.animation.slideInVertically(
            tween(NuxMotion.StandardMs, easing = NuxMotion.StandardEasing)
        ) { it / 2 },
        exit = androidx.compose.animation.fadeOut(
            tween(NuxMotion.FastMs, easing = NuxMotion.ExitEasing)
        ),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .clip(NuxShape.Row)
                .background(NuxColors.Scrim)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Text(text = last, style = MaterialTheme.typography.labelLarge, color = textColor)
        }
    }
}

/**
 * The one empty/error/loading surface for the whole app.
 *
 * Rules this component encodes (each learned from a fixed regression):
 * - Loading never coexists with actions or a terminal message — a spinner under
 *   "not found" announces the search is over while claiming it is still going.
 * - When a pane owns the whole screen it must offer at least one focusable
 *   control, or the remote has nowhere to land; panes inside screens that still
 *   have controls (a tab lane next to the nav rail) may pass no actions.
 * - The primary action receives focus on arrival, with the standard retry.
 */
@Composable
fun StatusPane(
    title: String,
    message: String? = null,
    icon: ImageVector? = null,
    loading: Boolean = false,
    primaryAction: StatusAction? = null,
    secondaryAction: StatusAction? = null,
    footnote: String? = null,
    modifier: Modifier = Modifier.fillMaxSize(),
    extras: (@Composable ColumnScope.() -> Unit)? = null,
) {
    // One-shot entrance so state changes don't pop. Snap-on-timeout: a pane
    // whose visibility waits on a starved frame clock is an invisible screen.
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateToOrSnap(
            1f,
            tween(NuxMotion.StandardMs, easing = NuxMotion.StandardEasing),
            timeoutMs = NuxMotion.StandardMs + 500L,
        )
    }
    val primaryFocus = if (!loading && primaryAction != null) {
        rememberInitialFocus(title)
    } else null

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 560.dp)
                .graphicsLayer {
                    alpha = progress.value
                    translationY = (1f - progress.value) * (NuxMotion.EntranceRise.toPx() / 2f)
                },
        ) {
            if (loading) {
                CircularProgressIndicator(color = NuxColors.Primary, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(Space.l))
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = NuxColors.OnSurfaceDim,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.height(Space.m))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = NuxColors.OnSurface,
                textAlign = TextAlign.Center,
            )
            if (message != null) {
                Spacer(Modifier.height(Space.s))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuxColors.OnSurfaceDim,
                    textAlign = TextAlign.Center,
                )
            }
            if (!loading && extras != null) {
                Spacer(Modifier.height(Space.m))
                extras()
            }
            if (!loading && (primaryAction != null || secondaryAction != null)) {
                Spacer(Modifier.height(Space.l))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    primaryAction?.let { action ->
                        Button(
                            onClick = action.onClick,
                            modifier = if (primaryFocus != null) {
                                Modifier.focusRequester(primaryFocus)
                            } else Modifier,
                        ) { Text(action.label) }
                    }
                    secondaryAction?.let { action ->
                        OutlinedButton(onClick = action.onClick) { Text(action.label) }
                    }
                }
            }
            if (footnote != null) {
                Spacer(Modifier.height(Space.m))
                Text(
                    text = footnote,
                    style = MaterialTheme.typography.labelMedium,
                    color = NuxColors.OnSurfaceDim,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
