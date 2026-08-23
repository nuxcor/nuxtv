@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.agoro.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.agoro.tv.ui.components.requestFocusRetrying
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxFocus

/**
 * TiviMate-style channel options: everything you might do to *this* channel,
 * on a compact right-side panel summoned by MENU or a long press of OK. This
 * is what slimmed the transport bar down to transport + navigation — actions
 * moved here where they're named, not iconified.
 */
@Composable
internal fun ChannelOptionsMenu(
    channelName: String,
    isFavoritable: Boolean,
    isFavorite: Boolean,
    canRecord: Boolean,
    isRecording: Boolean,
    hasCatchup: Boolean,
    aspectLabel: String,
    sleepLabel: String,
    canHide: Boolean,
    onFavoriteToggle: () -> Unit,
    onRecordToggle: () -> Unit,
    onCatchup: () -> Unit,
    onTracks: () -> Unit,
    onAspectCycle: () -> Unit,
    onSleepCycle: () -> Unit,
    onHide: () -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocusRetrying() }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(PlayerTheme.CategoryWidth)
                .fillMaxHeight()
                .background(PlayerTheme.ScrimStrong)
                .focusGroup()
                .padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 22.dp),
        ) {
            Column {
                Text(
                    text = channelName,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = NuxColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Channel options",
                    style = MaterialTheme.typography.labelMedium,
                    color = NuxColors.OnSurfaceDim,
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxHeight(),
                ) {
                    if (isFavoritable) {
                        item(key = "favorite") {
                            OptionRow(
                                icon = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                                label = if (isFavorite) "Remove favorite" else "Favorite",
                                iconTint = if (isFavorite) NuxColors.Primary else NuxColors.OnSurface,
                                onClick = onFavoriteToggle,
                                modifier = Modifier.focusRequester(firstFocus),
                            )
                        }
                    }
                    if (canRecord || isRecording) {
                        item(key = "record") {
                            OptionRow(
                                icon = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                                label = if (isRecording) "Stop recording" else "Record",
                                iconTint = NuxColors.Error,
                                onClick = onRecordToggle,
                            )
                        }
                    }
                    if (hasCatchup) {
                        item(key = "catchup") {
                            OptionRow(
                                icon = Icons.Default.History,
                                label = "Catch-up",
                                onClick = onCatchup,
                            )
                        }
                    }
                    item(key = "tracks") {
                        OptionRow(
                            icon = Icons.Default.Subtitles,
                            label = "Playback options",
                            onClick = onTracks,
                            modifier = if (isFavoritable) Modifier else Modifier.focusRequester(firstFocus),
                        )
                    }
                    item(key = "aspect") {
                        OptionRow(
                            icon = Icons.Default.AspectRatio,
                            label = "Aspect ratio",
                            value = aspectLabel,
                            onClick = onAspectCycle,
                        )
                    }
                    item(key = "sleep") {
                        OptionRow(
                            icon = Icons.Default.Bedtime,
                            label = "Sleep timer",
                            value = sleepLabel,
                            onClick = onSleepCycle,
                        )
                    }
                    if (canHide) {
                        item(key = "hide") {
                            OptionRow(
                                icon = Icons.Default.VisibilityOff,
                                label = "Hide this channel",
                                onClick = onHide,
                            )
                        }
                    }
                    item(key = "close") {
                        Spacer(Modifier.height(8.dp))
                        OptionRow(label = "Close", onClick = onDismiss)
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: androidx.compose.ui.graphics.Color = NuxColors.OnSurface,
    value: String? = null,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(PlayerTheme.ChipShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = PlayerTheme.RowFill,
            focusedContainerColor = NuxFocus.container,
            contentColor = NuxColors.OnSurface,
            focusedContentColor = NuxColors.OnSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = NuxFocus.RowScale),
        border = ClickableSurfaceDefaults.border(focusedBorder = NuxFocus.ring8),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelMedium,
                    color = NuxColors.Primary,
                    maxLines = 1,
                )
            }
        }
    }
}
