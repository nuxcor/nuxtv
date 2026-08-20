@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.agoro.tv.ui.player

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.agoro.tv.MainViewModel
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.data.PlayableItem
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxShape
import java.util.Date
import kotlinx.coroutines.delay

/**
 * TiviMate-style channel banner on the bottom gradient: number and logo on
 * the left, name / now-programme / progress / next in the middle, clock and
 * status chips on the right. Shown on every channel change so zapping is
 * never blind. The gradient itself belongs to the scaffold's bottom column,
 * which stacks this above the transport bar — their spacing is layout, not a
 * hardcoded lift.
 */
@Composable
internal fun ChannelBanner(
    vm: MainViewModel,
    item: PlayableItem?,
    channel: LiveChannel?,
    isLive: Boolean,
    resolution: Pair<Int, Int>?,
    hdrFormat: String?,
    audioFormatLabel: String?,
    isRecording: Boolean,
    showKeyHints: Boolean = false,
) {
    val nowNextMap by vm.nowNext.collectAsState()
    val favorites by vm.favorites.collectAsState()
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMs = System.currentTimeMillis()
        }
    }
    val nowNext = channel?.id?.let { nowNextMap[it] }
    val timeFmt = com.agoro.tv.ui.components.rememberClockFormat()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 40.dp, end = 40.dp, top = 24.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // --- left: logo card (no channel number — dead weight on screen;
        // digits still tune) -----------------------------------------------
        if (isLive && channel != null) {
            Box(
                modifier = Modifier
                    .clip(PlayerTheme.ChipShape)
                    .background(PlayerTheme.RowFill)
                    .padding(6.dp),
            ) {
                // Fit, not the Crop default: channel logos are arbitrary aspect
                // ratios and Crop fills the box by slicing the sides off — a
                // wide wordmark came out reading "CTRUM EWS". Crop is right for
                // posters, which is why it is the default, but never for logos.
                com.agoro.tv.ui.components.Artwork(
                    imageUrl = channel.logo,
                    title = channel.displayName,
                    modifier = Modifier.size(width = 78.dp, height = 46.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
            }
        }

        // --- middle: name, now, progress, next ---------------------------
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = channel?.displayName ?: item?.title.orEmpty(),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = NuxColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (channel != null && channel.url in favorites) {
                    Text("★", style = MaterialTheme.typography.titleSmall, color = NuxColors.Primary)
                }
            }
            val current = nowNext?.now
            if (current != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${timeFmt.format(Date(current.startMs))} – " +
                        "${timeFmt.format(Date(current.endMs))}   ${current.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuxColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                val progress = ((nowMs - current.startMs).toFloat() /
                    (current.endMs - current.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(NuxShape.Track)
                        .background(PlayerTheme.TrackBackground)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress)
                            .background(NuxColors.Primary)
                    )
                }
            } else if (!item?.subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item?.subtitle.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuxColors.OnSurfaceDim,
                    maxLines = 1,
                )
            }
            nowNext?.next?.let { next ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Next  ${timeFmt.format(Date(next.startMs))}  ${next.title}",
                    style = MaterialTheme.typography.labelMedium,
                    color = NuxColors.OnSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showKeyHints) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "OK Options  ·  ◀ Channels  ·  ▲▼ Change channel  ·  INFO Info",
                    style = MaterialTheme.typography.labelSmall,
                    color = NuxColors.OnSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // --- right: clock + status chips ---------------------------------
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = timeFmt.format(Date(nowMs)),
                style = MaterialTheme.typography.labelLarge,
                color = NuxColors.OnSurface,
                maxLines = 1,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // What is actually being decoded, not what the stream name
                // advertises — the two disagree more often than not.
                // Tier only — "HD", not "720p HD". The precise numbers live in
                // the options sheet for whoever wants them. HDR and the audio
                // format join it when the stream actually carries them.
                StreamBadges(resolution, hdrFormat, audioFormatLabel)
                if (isRecording) {
                    PlayerBadge(text = "REC", color = NuxColors.Error)
                }
                // No engine-name chip here: which decoder is playing is
                // diagnostics, not viewing information — it lives in the
                // options sheet instead.
            }
        }
    }
}

/**
 * What tuning looks like: the channel's name breathing over the dimmed last
 * frame, with a light sweeping a thin line beneath it — identity plus motion,
 * no card, no logo tile, no spinner. The boxy scrim card this replaces put a
 * letterboxed logo and a stock spinner in the middle of every channel change,
 * which read as chrome interrupting the picture rather than the picture
 * changing. Shown from the moment a tune is requested until the new stream
 * renders; mid-stream stalls get only a corner chip.
 */
@Composable
internal fun TuneCard(
    channel: LiveChannel?,
    item: PlayableItem?,
    modifier: Modifier = Modifier,
) {
    val motion = androidx.compose.animation.core.rememberInfiniteTransition(label = "tune")
    // One light sweeping left-to-right, restarting — a scanner bounce reads
    // as retro, a single direction reads as progress.
    val sweep by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(
                1_100,
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            ),
            androidx.compose.animation.core.RepeatMode.Restart,
        ),
        label = "sweep",
    )
    val trackWidth = 200.dp
    val glowWidth = 72.dp
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = channel?.displayName ?: item?.title.orEmpty(),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.SemiBold,
                // Legibility without a scrim card: the text floats over
                // whatever frame the zap left behind.
                shadow = androidx.compose.ui.graphics.Shadow(
                    color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.7f),
                    blurRadius = 18f,
                ),
            ),
            color = NuxColors.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 720.dp),
        )
        Spacer(Modifier.height(14.dp))
        Box(
            modifier = Modifier
                .width(trackWidth)
                .height(3.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                .background(NuxColors.OnSurface.copy(alpha = 0.16f)),
        ) {
            Box(
                modifier = Modifier
                    // The glow starts fully off the left edge and exits fully
                    // right, so the loop point is invisible.
                    .offset(x = (trackWidth + glowWidth) * sweep - glowWidth)
                    .width(glowWidth)
                    .fillMaxHeight()
                    .background(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            listOf(
                                androidx.compose.ui.graphics.Color.Transparent,
                                NuxColors.Primary,
                                androidx.compose.ui.graphics.Color.Transparent,
                            )
                        )
                    ),
            )
        }
    }
}
