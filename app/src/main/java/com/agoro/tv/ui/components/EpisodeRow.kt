@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.agoro.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.agoro.tv.ui.theme.NuxBorders
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxFocus
import com.agoro.tv.ui.theme.NuxShape

/**
 * One episode in a season's list.
 *
 * Its own component rather than another parameter on [WideItem], which serves
 * the channel and settings lists: those are single-line rows built around a
 * logo, and every option added to make one of them carry a 16:9 still, a
 * runtime on the far right, a two-line synopsis and a progress bar came out
 * of the same layout the other callers use. The two shapes had stopped being
 * the same row some time ago.
 *
 * The shape is the one every streaming app converged on, for the reason they
 * converged on it: the still is the biggest thing in the row and sits at the
 * leading edge, because a frame from an episode is what a viewer recognises
 * an episode by — long before they read its name.
 */
@Composable
fun EpisodeRow(
    /** "1. Did you know Seahorses are fish?" — number included. */
    title: String,
    /** The still, 16:9. Null draws the monogram; the series poster must not stand in. */
    imageUrl: String?,
    /** Far right of the title line: "54m", or "Resume from 12:04". */
    meta: String? = null,
    /** Up to three dim lines under the title. */
    synopsis: String? = null,
    /** 0..1, drawn across the foot of the still. Null or 0 draws nothing. */
    progress: Float? = null,
    /** Finished, and not part-way through: a tick, not a word. */
    watched: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().dpadLongPress(onLongClick),
        shape = ClickableSurfaceDefaults.shape(NuxShape.Card),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuxColors.Surface,
            focusedContainerColor = NuxFocus.container,
            contentColor = NuxColors.OnSurface,
            focusedContentColor = NuxColors.OnSurface,
        ),
        // No scale. A row this wide grows into its neighbours rather than
        // toward the viewer, and the list is dense enough that the fill and
        // the ring already carry it.
        scale = ClickableSurfaceDefaults.scale(focusedScale = NuxFocus.RowScale),
        border = ClickableSurfaceDefaults.border(
            border = NuxBorders.restingCard,
            focusedBorder = NuxFocus.ring,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            // Centred, and safe to centre: the still is 90dp and the text
            // beside it cannot exceed 75 (one line of title, three of
            // synopsis, both capped), so the still sets every row's height
            // and every row is the same height. Top-aligned, the text sat
            // against the top of a 90dp still with 32dp of nothing under it.
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Artwork(
                    imageUrl = imageUrl,
                    title = title,
                    modifier = Modifier
                        .size(width = 160.dp, height = 90.dp)
                        .clip(NuxShape.Chip),
                    monogramStyle = MaterialTheme.typography.titleMedium,
                )
                // On the still, the way a thumbnail carries its own progress
                // everywhere else — under the row it was a third dim line
                // competing with the synopsis for the same strip of pixels.
                if (progress != null && progress > 0f) Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.Black.copy(alpha = 0.55f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .background(NuxColors.Primary),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // Takes the room, so the runtime and the tick stay
                        // pinned to the trailing edge whatever the title does.
                        modifier = Modifier.weight(1f),
                    )
                    if (watched) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Watched",
                            tint = NuxColors.OnSurfaceDim,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    meta?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = NuxColors.OnSurfaceDim,
                            maxLines = 1,
                        )
                    }
                }
                synopsis?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuxColors.OnSurfaceDim,
                        // Three, to stand level with a 90dp still. At two the
                        // text was two thirds the height of the picture next
                        // to it and the row read as half empty; the third
                        // line is also most of what a TMDB episode overview
                        // needs to finish its sentence.
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
