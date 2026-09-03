@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.agoro.tv.ui.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import com.agoro.tv.ui.theme.NuxShape

/**
 * The transport bar. Slimmed to transport + navigation — favourite, record,
 * catch-up, engine and sleep live in the channel options menu now, where they
 * have names. Composes inside the scaffold's bottom column, under the banner,
 * so their stacking is layout rather than a hardcoded lift.
 */
@Composable
internal fun PlayerControls(
    isLive: Boolean,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    hasPlaylist: Boolean,
    canPip: Boolean,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onChannels: () -> Unit,
    onGuide: () -> Unit,
    onOptions: () -> Unit,
    onPip: () -> Unit,
    onInteraction: () -> Unit,
) {
    // Initial focus goes to the play/pause button — the one control
    // guaranteed to be in the row on both live and VOD.
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { playFocus.requestFocusRetrying() }

    // No background of its own: the scaffold's bottom column paints one
    // gradient behind the banner + transport stack.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!isLive && durationMs > 0) {
            SeekBar(
                positionMs = positionMs,
                durationMs = durationMs,
                onSeekBy = onSeekBy,
                onInteraction = onInteraction,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Live gets play/pause but no seeking: many providers buffer a
            // little timeshift, and resuming a long pause re-tunes to the
            // live edge rather than replaying a stale buffer.
            if (!isLive) {
                if (hasPlaylist) ControlButton(Icons.Default.SkipPrevious, "Previous", onPrevious)
                if (durationMs > 0) {
                    ControlButton(Icons.Default.FastRewind, "Back 10s", onClick = { onSeekBy(-10_000) })
                }
                ControlButton(
                    icon = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    label = if (playing) "Pause" else "Play",
                    onClick = onPlayPause,
                    modifier = Modifier.focusRequester(playFocus),
                    prominent = true,
                )
                if (durationMs > 0) {
                    ControlButton(Icons.Default.FastForward, "Forward 10s", onClick = { onSeekBy(10_000) })
                }
                if (hasPlaylist) ControlButton(Icons.Default.SkipNext, "Next", onNext)
                Spacer(Modifier.weight(1f))
            }

            // Live TV's row has room to label every action — and bare icons
            // at 10 feet tell you nothing. VOD keeps icons so the transport
            // stays the focus.
            val labelled = isLive
            // No pause on live: pausing a broadcast only ever resumed at the
            // live edge anyway, and remotes that alias the centre button to
            // PLAY_PAUSE made OK's behaviour look random. But a live stream
            // that IS paused — the sleep timer, a CEC pause — needs a way
            // back, so the bar leads with Play exactly then.
            if (isLive && !playing) {
                ControlButton(
                    icon = Icons.Default.PlayArrow,
                    label = "Play",
                    onClick = onPlayPause,
                    prominent = true,
                    showLabel = true,
                )
            }
            if (hasPlaylist) {
                ControlButton(
                    Icons.AutoMirrored.Filled.List,
                    "Channels",
                    onChannels,
                    modifier = if (isLive) Modifier.focusRequester(playFocus) else Modifier,
                    showLabel = labelled,
                )
            }
            if (isLive) {
                ControlButton(
                    Icons.Default.GridView,
                    "Guide",
                    onGuide,
                    modifier = if (hasPlaylist) Modifier else Modifier.focusRequester(playFocus),
                    showLabel = labelled,
                )
            }
            ControlButton(Icons.Default.Tune, "Options", onOptions, showLabel = true)
            // Only on devices whose system actually offers PiP — the button
            // used to render everywhere and silently do nothing.
            if (canPip) {
                ControlButton(Icons.Default.PictureInPictureAlt, "PiP", onPip, showLabel = labelled)
            }
        }
    }
}

/**
 * What the stream actually turned out to be: picture tier, HDR flavour, audio
 * format. Read from the decoder, never from the title — a provider writing "4K
 * HDR" into a filename is not evidence of either.
 *
 * Everything here is absent until it is known, and ordinary SDR stereo says
 * nothing at all. A row of badges that appears on every stream stops carrying
 * information.
 */
@Composable
internal fun StreamBadges(
    resolution: Pair<Int, Int>?,
    hdrFormat: String?,
    audioFormatLabel: String?,
) {
    val tier = resolution?.let { (_, h) -> com.agoro.tv.data.QualityTag.tierOf(h) }
    if (tier == null && hdrFormat == null && audioFormatLabel == null) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tier?.let { com.agoro.tv.ui.components.MetaChip(it, accent = true) }
        hdrFormat?.let { com.agoro.tv.ui.components.MetaChip(it, accent = true) }
        audioFormatLabel?.let { com.agoro.tv.ui.components.MetaChip(it) }
    }
}

/** The VOD title header — live playback names itself through the banner. */
@Composable
internal fun VodTitleHeader(
    title: String,
    subtitle: String?,
    resolution: Pair<Int, Int>? = null,
    hdrFormat: String? = null,
    audioFormatLabel: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PlayerTheme.TopGradient)
            .padding(horizontal = 32.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = NuxColors.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = NuxColors.OnSurfaceDim,
                    maxLines = 1,
                )
            }
        }
        // Movies and series had no quality readout anywhere on screen — the
        // live banner's chip has no VOD counterpart — so a 4K film announced
        // itself only in the options sheet, two presses away.
        StreamBadges(resolution, hdrFormat, audioFormatLabel)
    }
}

/**
 * The seek readout for VOD's bare LEFT/RIGHT scrubbing: where it will land,
 * how far that is from where it started, the duration, and a thin bar. No
 * focusable chrome; auto-hidden by the scaffold.
 *
 * [deltaMs] is non-null only while a scrub is pending. It is what turns a
 * bare timestamp into steering — "24:15" says where you would be, "+1:30"
 * says what this press did, and a viewer holding the key needs the second one
 * to know the ramp has kicked in.
 */
@Composable
internal fun SeekFlash(positionMs: Long, durationMs: Long, deltaMs: Long? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PlayerTheme.BottomGradient)
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                formatPlayerTime(positionMs),
                style = MaterialTheme.typography.labelLarge,
                color = NuxColors.OnSurface,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(5.dp)
                    .clip(NuxShape.Track)
                    .background(PlayerTheme.TrackBackground)
            ) {
                if (durationMs > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth((positionMs.toFloat() / durationMs).coerceIn(0f, 1f))
                            .background(NuxColors.Primary)
                    )
                }
            }
            Text(
                formatPlayerTime(durationMs),
                style = MaterialTheme.typography.labelLarge,
                color = NuxColors.OnSurfaceDim,
            )
        }
        // Under the bar rather than beside the clock: the row above is a
        // fixed shape and a delta that appears and disappears inside it would
        // shove the timeline sideways on every press.
        if (deltaMs != null && deltaMs != 0L) {
            Text(
                text = (if (deltaMs > 0) "+" else "-") + formatPlayerTime(kotlin.math.abs(deltaMs)),
                style = MaterialTheme.typography.labelLarge,
                color = NuxColors.Primary,
            )
        }
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    tint: Color = NuxColors.OnSurface,
    showLabel: Boolean = false,
) {
    // Same focus language as the rest of the app: a white ring over a lifted
    // surface. This used to fill solid gold with no ring — the player taught the
    // opposite of what every browse screen taught, and gold is the brand colour,
    // not the focus colour. The ring reads over video; a fill alone did not.
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(if (showLabel) PlayerTheme.PillShape else CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (prominent) PlayerTheme.ProminentFill else Color.Transparent,
            focusedContainerColor = NuxFocus.container,
            contentColor = tint,
            focusedContentColor = NuxColors.OnSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = NuxFocus.ButtonScale),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = if (showLabel) NuxFocus.ring22 else NuxFocus.ringCircle,
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(if (prominent) 14.dp else 10.dp),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(if (prominent) 28.dp else 22.dp))
            if (showLabel) {
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeekBy: (Long) -> Unit,
    onInteraction: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            formatPlayerTime(positionMs),
            style = MaterialTheme.typography.labelMedium,
            color = NuxColors.OnSurface,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(if (focused) 8.dp else 5.dp)
                .clip(NuxShape.Track)
                .background(PlayerTheme.TrackBackground)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key.nativeKeyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { onSeekBy(-10_000); onInteraction(); true }
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { onSeekBy(10_000); onInteraction(); true }
                        else -> false
                    }
                }
                .onFocusChanged { focused = it.isFocused }
                .focusable()
        ) {
            val fraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(if (focused) NuxColors.FocusBorder else NuxColors.Primary)
            )
        }
        Text(
            formatPlayerTime(durationMs),
            style = MaterialTheme.typography.labelMedium,
            color = NuxColors.OnSurface,
        )
    }
}
