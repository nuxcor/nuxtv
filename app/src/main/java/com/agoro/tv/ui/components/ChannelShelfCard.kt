@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.agoro.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.agoro.tv.data.EpgProgram
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxFocus
import com.agoro.tv.ui.theme.NuxShape

/**
 * A live channel as a 16:9 shelf card: logo on its neutral chip, the current
 * programme underneath with how far through it is. Degrades to logo + name
 * when no guide covers the channel.
 *
 * Shared, not Home's private card, because a channel is the same object
 * wherever it is offered — a search result that reads as a bare list row while
 * the same channel is a picture card one screen away tells the viewer they are
 * looking at two different apps.
 */
@Composable
fun ChannelShelfCard(
    channel: LiveChannel,
    now: EpgProgram?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onFocus: () -> Unit = {},
    /** Applied to the clickable surface — the node that takes focus. */
    modifier: Modifier = Modifier,
) {
    val progress = now?.let {
        val span = it.endMs - it.startMs
        if (span <= 0) null
        else ((System.currentTimeMillis() - it.startMs).toFloat() / span).coerceIn(0f, 1f)
    }
    // Caption lives OUTSIDE the clickable surface: inside it, the focus glow
    // pools behind the text rows and reads as a stain instead of a halo.
    Column(modifier = Modifier.width(240.dp)) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier.onFocusChanged { if (it.isFocused) onFocus() },
            shape = ClickableSurfaceDefaults.shape(NuxShape.Card),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                contentColor = NuxColors.OnSurface,
                focusedContentColor = NuxColors.OnSurface,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = NuxFocus.CardScale),
            border = ClickableSurfaceDefaults.border(focusedBorder = NuxFocus.ring16),
            glow = ClickableSurfaceDefaults.glow(focusedGlow = NuxFocus.cardGlow),
        ) {
            Box {
                Artwork(
                    imageUrl = channel.logo,
                    title = channel.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(NuxShape.Card),
                    monogramStyle = MaterialTheme.typography.headlineSmall,
                )
                channel.quality?.let { tier ->
                    Text(
                        text = tier,
                        style = MaterialTheme.typography.labelMedium,
                        color = NuxColors.OnSurfaceDim,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(NuxShape.Chip)
                            .background(NuxColors.Scrim)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                if (progress != null && progress > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.White.copy(alpha = 0.25f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress)
                                .background(NuxColors.Primary),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = channel.displayName,
            style = MaterialTheme.typography.titleSmall,
            // Explicit: the caption sits outside the Surface (see above), so
            // it inherits no content colour and tv-material3's default is
            // black — the name rendered invisible on the page while the
            // dimmer programme line under it was readable.
            color = NuxColors.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Text(
            text = now?.title ?: "Live",
            style = MaterialTheme.typography.labelMedium,
            color = NuxColors.OnSurfaceDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}
