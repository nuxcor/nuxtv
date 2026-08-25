@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.agoro.tv.ui.player

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.graphicsLayer
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
import com.agoro.tv.data.isFavorite

/**
 * TiviMate-style channel banner on the bottom gradient: logo on the left,
 * number and name / now-programme / progress / next in the middle, clock and
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
    /**
     * True while a zap chain is still running. The logo slot stays, empty,
     * so the banner doesn't reflow — but no image is asked for: a run
     * through twenty channels used to start twenty Coil requests, one per
     * channel skimmed, for logos that were on screen for a tenth of a second.
     */
    logoDeferred: Boolean = false,
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
        // --- left: logo card ----------------------------------------------
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
                if (logoDeferred) {
                    Spacer(Modifier.size(width = 78.dp, height = 46.dp))
                } else {
                    com.agoro.tv.ui.components.Artwork(
                        imageUrl = channel.logo,
                        title = channel.displayName,
                        modifier = Modifier.size(width = 78.dp, height = 46.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                }
            }
        }

        // --- middle: name, now, progress, next ---------------------------
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // The number the digit keys tune by, beside the name it means —
                // dim, because the name is what the viewer is reading.
                channel?.number?.let { number ->
                    Text(
                        text = number.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = NuxColors.OnSurfaceDim,
                        maxLines = 1,
                    )
                }
                Text(
                    text = channel?.displayName ?: item?.title.orEmpty(),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = NuxColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (channel != null && channel.isFavorite(favorites)) {
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
                    // INFO while the banner is up opens the controls, so
                    // that is what the hint says; "INFO Info" on the thing
                    // INFO had just opened said nothing.
                    text = "OK Options  ·  ◀ Channels  ·  ▲▼ Change channel  ·  INFO More",
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
 * The intentional "connecting" screen every tune opens on, so opening a
 * stream is never a flat black void waiting for the first frame — a fresh
 * open from a poster or a fixture, a pick from the channel-list panel, a zap.
 * A soft brand-gold glow breathing over the dark video canvas, under the
 * [TuneCard]'s name and sweep. The player clears its surface to a black
 * shutter on every re-tune, so there is never a live frame beneath this to
 * hide.
 *
 * The breath is read inside graphicsLayer, so each frame is a draw and
 * nothing recomposes; the glow is one radial brush drawn once.
 */
@Composable
internal fun TuningBackdrop(modifier: Modifier = Modifier) {
    val motion = androidx.compose.animation.core.rememberInfiniteTransition(label = "tuneBg")
    val breath by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(
                2_600,
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            ),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "breath",
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PlayerTheme.VideoCanvas),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Breathe the glow's size and strength together, about the
                // centre, so it reads as a slow pulse rather than a flicker.
                .graphicsLayer {
                    val scale = 0.92f + 0.16f * breath
                    scaleX = scale
                    scaleY = scale
                    alpha = 0.55f + 0.45f * breath
                }
                .background(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(
                            NuxColors.Primary.copy(alpha = 0.22f),
                            NuxColors.PrimaryDim.copy(alpha = 0.10f),
                            androidx.compose.ui.graphics.Color.Transparent,
                        ),
                    )
                ),
        )
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
                // whatever frame the zap left behind. A hard offset shadow,
                // not a blur: the blurred one was re-rasterised on every
                // frame of the sweep below, for the whole of every tune.
                shadow = androidx.compose.ui.graphics.Shadow(
                    color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.7f),
                    offset = androidx.compose.ui.geometry.Offset(0f, 2f),
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
                    // right, so the loop point is invisible. Read inside
                    // graphicsLayer, so each frame of the sweep is a draw and
                    // nothing more — as a Modifier.offset(x = …) parameter the
                    // animated value was read in composition, and every frame
                    // recomposed, re-measured and re-laid-out the card.
                    .graphicsLayer {
                        translationX = (trackWidth + glowWidth).toPx() * sweep - glowWidth.toPx()
                    }
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
