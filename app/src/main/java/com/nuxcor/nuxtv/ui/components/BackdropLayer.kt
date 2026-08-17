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
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuxcor.nuxtv.ui.theme.NuxColors
import com.nuxcor.nuxtv.ui.theme.NuxMotion

/**
 * Ambient artwork behind a content pane: the image is cropped to the trailing
 * side and faded into [NuxColors.Background] so text on the leading side stays
 * fully legible. Used by the detail screens and the browse heroes.
 *
 * Draw it as the first child of a full-size Box, under the content.
 */
@Composable
fun BoxScope.BackdropLayer(
    imageUrl: String?,
    widthFraction: Float = 0.7f,
) {
    // Crossfade on the URL: a fresh image node per URL means a request that
    // 404s can never leave the previous item's artwork on screen (the same
    // stale-pixels rule Artwork documents).
    Crossfade(
        targetState = imageUrl,
        animationSpec = tween(NuxMotion.StandardMs, easing = NuxMotion.StandardEasing),
        label = "backdrop",
        modifier = Modifier.matchParentSize(),
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    NuxColors.Background,
                                    NuxColors.Background.copy(alpha = 0.94f),
                                    NuxColors.Background.copy(alpha = 0.35f),
                                )
                            )
                        )
                )
            }
        }
    }
}
