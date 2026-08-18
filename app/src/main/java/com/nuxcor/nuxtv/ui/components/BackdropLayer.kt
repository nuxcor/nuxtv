package com.nuxcor.nuxtv.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuxcor.nuxtv.ui.theme.NuxColors
import com.nuxcor.nuxtv.ui.theme.NuxMotion
import com.nuxcor.nuxtv.ui.theme.Space

/**
 * Grows the layer past its parent's bounds so ambient art can reach the screen
 * edge from inside a padded pane.
 *
 * Every browse and detail screen is composed inside the TV-safe gutter, so a
 * backdrop laid out normally stopped 58dp short on the right and 32dp short at
 * the bottom — and with nothing feathering those edges it read as a
 * translucent rectangle pasted onto the page, seams and all. Nothing here
 * clips, so drawing outside the bounds is safe; the layer still *reports* the
 * parent's size, so it changes no layout.
 */
private fun Modifier.bleed(horizontal: Dp, vertical: Dp) = layout { measurable, constraints ->
    val h = horizontal.roundToPx()
    val v = vertical.roundToPx()
    val placeable = measurable.measure(
        Constraints.fixed(
            width = (constraints.maxWidth + h * 2).coerceAtLeast(0),
            height = (constraints.maxHeight + v * 2).coerceAtLeast(0),
        )
    )
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(-h, -v) }
}

/**
 * Ambient artwork behind a content pane: the image is cropped to the trailing
 * side and faded into [NuxColors.Background] so text on the leading side stays
 * fully legible. Used by the detail screens and the browse heroes.
 *
 * Draw it as the first child of a full-size Box, under the content. [bleedX]
 * and [bleedY] default to the TV-safe gutter, which is what a pane composed
 * inside [Space] margins needs to reach the screen edge;
 * pass zero when the caller already sits outside that padding.
 */
@Composable
fun BoxScope.BackdropLayer(
    imageUrl: String?,
    widthFraction: Float = 0.7f,
    bleedX: Dp = Space.gutter,
    bleedY: Dp = Space.gutterVertical,
) {
    // Crossfade on the URL: a fresh image node per URL means a request that
    // 404s can never leave the previous item's artwork on screen (the same
    // stale-pixels rule Artwork documents).
    Crossfade(
        targetState = imageUrl,
        animationSpec = tween(NuxMotion.StandardMs, easing = NuxMotion.StandardEasing),
        label = "backdrop",
        modifier = Modifier.matchParentSize().bleed(bleedX, bleedY),
    ) { url ->
        if (!url.isNullOrBlank()) {
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(url)
                        .crossfade(NuxMotion.ImageCrossfadeMs)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth(widthFraction)
                        .fillMaxHeight()
                        .align(Alignment.TopEnd),
                )
                // Two scrims, because one was never enough.
                //
                // Horizontal alone left the art running at ~65% at the trailing
                // edge: the backdrop's own lettering read plainly through the
                // poster grid and, on the detail screens, collided with the
                // review copy. It also did nothing about the bottom, where the
                // image was at full strength directly behind the densest
                // content on the screen.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    NuxColors.Background,
                                    NuxColors.Background.copy(alpha = 0.96f),
                                    NuxColors.Background.copy(alpha = 0.72f),
                                )
                            )
                        )
                )
                // Vertical falloff: ambient at the top where the hero text
                // sits, opaque by the content band below it.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to NuxColors.Background.copy(alpha = 0.10f),
                                0.45f to NuxColors.Background.copy(alpha = 0.45f),
                                1f to NuxColors.Background,
                            )
                        )
                )
            }
        }
    }
}
