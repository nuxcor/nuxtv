@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.agoro.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.agoro.tv.MainViewModel
import com.agoro.tv.data.EpgProgram
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.player.ExoEngine
import com.agoro.tv.player.PlayerEngine
import com.agoro.tv.player.Track
import com.agoro.tv.ui.components.requestFocusRetrying
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxFocus
import com.agoro.tv.ui.theme.NuxShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun PlayerBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(PlayerTheme.ChipShape)
            .background(NuxColors.Scrim)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            // A corner pill, not a paragraph: "Recording scheduled: <long
            // programme title>" wrapped into three lines over the picture.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 420.dp),
        )
    }
}

/**
 * Channel-number entry readout: the digits collected so far, large, in a
 * scrim pill — sized to be read from the couch mid-type, where [PlayerBadge]'s
 * label type is annotation-sized. Never a focus target: digits arrive through
 * the scaffold's key routing, and the pill must not disturb whatever chrome
 * is up. [dim] is the "No channel 481" verdict — an answer, not an error.
 */
@Composable
internal fun DigitEntryPill(text: String, dim: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(PlayerTheme.PillShape)
            .background(NuxColors.Scrim)
            .padding(horizontal = 22.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (dim) NuxColors.OnSurfaceDim else NuxColors.OnSurface,
            maxLines = 1,
        )
    }
}

@Composable
internal fun CatchupOverlay(
    vm: MainViewModel,
    channel: LiveChannel,
    onDismiss: () -> Unit,
    onPlay: (EpgProgram, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var programs by remember(channel.id) { mutableStateOf<List<EpgProgram>?>(null) }
    val closeFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }

    LaunchedEffect(channel.id) {
        val now = System.currentTimeMillis()
        val oldest = now - channel.archiveDays * 24L * 3600 * 1000
        programs = vm.epgFor(channel)
            .filter { it.hasArchive && it.endMs < now && it.startMs > oldest }
            .sortedByDescending { it.startMs }
    }
    // Focus opens on the newest programme — "what did I just miss" is the
    // question this sheet answers — and only falls back to Close while the
    // list is loading or empty. On Close, the list's first row was N presses
    // of UP away through everything older.
    LaunchedEffect(programs) {
        val list = programs
        if (list.isNullOrEmpty() || !listFocus.requestFocusRetrying()) {
            closeFocus.requestFocusRetrying()
        }
    }

    val dayFmt = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    val clockFmt = com.agoro.tv.ui.components.rememberClockFormat()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PlayerTheme.ScrimStrong)
            .focusGroup()
            .padding(horizontal = 64.dp, vertical = 40.dp)
    ) {
        Column {
            Text(
                text = "Catch-up — ${channel.displayName}",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = NuxColors.OnSurface,
            )
            Text(
                text = "${channel.archiveDays} day archive",
                style = MaterialTheme.typography.labelMedium,
                color = NuxColors.OnSurfaceDim,
            )
            Spacer(Modifier.height(18.dp))
            when {
                programs == null -> CircularProgressIndicator(color = NuxColors.Primary)
                programs!!.isEmpty() -> Text(
                    "No archived programmes found for this channel.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuxColors.OnSurfaceDim,
                )
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .focusRequester(listFocus)
                        .focusRestorer(),
                ) {
                    items(programs!!, key = { it.id }) { program ->
                        Surface(
                            onClick = {
                                scope.launch {
                                    val url = vm.catchupUrl(channel, program)
                                    if (url != null) onPlay(program, url)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = ClickableSurfaceDefaults.shape(PlayerTheme.PanelShape),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = PlayerTheme.RowFill,
                                focusedContainerColor = NuxFocus.container,
                                contentColor = NuxColors.OnSurface,
                                focusedContentColor = NuxColors.OnSurface,
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = NuxFocus.RowScale),
                            border = ClickableSurfaceDefaults.border(focusedBorder = NuxFocus.ring12),
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Text(
                                    text = program.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    // A time RANGE — "11:00 AM – 12:00 PM".
                                    // The old third segment printed the raw
                                    // duration ("1:00:00") after the dash,
                                    // which read as a nonsense end time.
                                    text = "${dayFmt.format(Date(program.startMs))} • " +
                                        "${clockFmt.format(Date(program.startMs))}" +
                                        " – ${clockFmt.format(Date(program.endMs))}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NuxColors.OnSurfaceDim,
                                )
                                if (!program.description.isNullOrBlank()) {
                                    Text(
                                        text = program.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NuxColors.OnSurfaceDim,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Surface(
                onClick = onDismiss,
                shape = ClickableSurfaceDefaults.shape(PlayerTheme.PanelShape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = NuxColors.SurfaceVariant,
                    focusedContainerColor = NuxFocus.container,
                    contentColor = NuxColors.OnSurface,
                    focusedContentColor = NuxColors.OnSurface,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = NuxFocus.ButtonScale),
                border = ClickableSurfaceDefaults.border(focusedBorder = NuxFocus.ring12),
                modifier = Modifier.widthIn(min = 120.dp).focusRequester(closeFocus),
            ) {
                Text(
                    "Close",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
internal fun TracksOverlay(
    engine: PlayerEngine,
    isVod: Boolean,
    scaleMode: Int,
    onScaleMode: (Int) -> Unit,
    speed: Float,
    onSpeed: (Float) -> Unit,
    sleepMinutes: Int,
    onSleep: (Int) -> Unit,
    onAudioSelected: (Track) -> Unit,
    onSubtitleSelected: (Track?) -> Unit,
    /** 0 adapt to bandwidth, 1 pin the top rung — remembered across channels. */
    onVideoQuality: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var audio by remember { mutableStateOf(engine.audioTracks()) }
    var text by remember { mutableStateOf(engine.textTracks()) }
    var video by remember { mutableStateOf(engine.videoTracks()) }
    var decoded by remember { mutableStateOf(engine.videoResolution) }
    // Only ExoPlayer exposes a bitrate ladder; VLC resolves it internally.
    var forcingHighest by remember {
        mutableStateOf((engine as? ExoEngine)?.isForcingHighest ?: false)
    }
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { initialFocus.requestFocusRetrying() }

    // Tracks appear a beat after the stream opens, so keep looking while the
    // sheet is up rather than showing "no alternate tracks" forever.
    LaunchedEffect(engine) {
        repeat(20) {
            delay(500)
            audio = engine.audioTracks()
            text = engine.textTracks()
            video = engine.videoTracks()
            decoded = engine.videoResolution
        }
    }

    fun refresh() {
        audio = engine.audioTracks()
        text = engine.textTracks()
        video = engine.videoTracks()
        forcingHighest = (engine as? ExoEngine)?.isForcingHighest ?: false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PlayerTheme.ScrimStrong)
            .focusGroup()
            .padding(horizontal = 64.dp, vertical = 40.dp)
    ) {
        Column {
            Text(
                text = "Playback options",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = NuxColors.OnSurface,
            )
            decoded?.let { (w, h) ->
                Text(
                    text = "Now decoding ${com.agoro.tv.player.qualityLabel(w, h)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = NuxColors.OnSurfaceDim,
                )
            }
            Spacer(Modifier.height(16.dp))

            // Bounded: inside a Column the list measured with ALL the
            // remaining height, so the Close button after it was laid out
            // below the screen on any stream with more than a few rows —
            // and it was the initial focus, so the sheet opened with no
            // visible cursor. Focus opens on the first option instead.
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .weight(1f, fill = false)
                    .focusRequester(initialFocus)
                    .focusRestorer(),
            ) {
                if (video.isNotEmpty()) {
                    item(key = "video-header") {
                        Text(
                            "Video quality",
                            style = MaterialTheme.typography.titleSmall,
                            color = NuxColors.OnSurfaceDim,
                        )
                    }
                    item(key = "video-highest") {
                        TrackRow(
                            track = Track(
                                com.agoro.tv.player.HIGHEST_QUALITY,
                                "Highest available",
                                forcingHighest,
                            )
                        ) {
                            engine.selectVideoTrack(com.agoro.tv.player.HIGHEST_QUALITY)
                            onVideoQuality(1)
                            refresh()
                        }
                    }
                    item(key = "video-auto") {
                        TrackRow(
                            track = Track(
                                "auto",
                                "Auto — adapt to bandwidth",
                                !forcingHighest && video.none { it.selected },
                            )
                        ) {
                            engine.selectVideoTrack(null)
                            onVideoQuality(0)
                            refresh()
                        }
                    }
                    items(video, key = { "v:${it.id}" }) { track ->
                        TrackRow(track = track) {
                            engine.selectVideoTrack(track.id)
                            refresh()
                        }
                    }
                    item(key = "video-gap") { Spacer(Modifier.height(10.dp)) }
                }
                item(key = "aspect") {
                    OptionChips(
                        label = "Aspect ratio",
                        options = listOf("Fit", "Stretch", "Zoom"),
                        selectedIndex = scaleMode,
                        onSelect = onScaleMode,
                    )
                }
                if (isVod) {
                    item(key = "speed") {
                        val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                        OptionChips(
                            label = "Speed",
                            options = speeds.map { speed ->
                                // "2x", not "2.0x": trailing zeros trimmed the way 1x already was.
                                val text = speed.toString().trimEnd('0').trimEnd('.')
                                "${text}x"
                            },
                            selectedIndex = speeds.indexOf(speed).coerceAtLeast(0),
                            onSelect = { onSpeed(speeds[it]) },
                        )
                    }
                }
                item(key = "sleep") {
                    val choices = listOf(0, 30, 60, 90)
                    OptionChips(
                        label = "Sleep timer",
                        options = choices.map { if (it == 0) "Off" else "${it}m" },
                        selectedIndex = choices.indexOf(sleepMinutes).coerceAtLeast(0),
                        onSelect = { onSleep(choices[it]) },
                    )
                }
                if (audio.isNotEmpty()) {
                    item(key = "audio-header") {
                        Text(
                            "Audio",
                            style = MaterialTheme.typography.titleSmall,
                            color = NuxColors.OnSurfaceDim,
                        )
                    }
                    items(audio, key = { "a:${it.id}" }) { track ->
                        TrackRow(track = track) {
                            engine.selectAudioTrack(track.id)
                            onAudioSelected(track)
                            refresh()
                        }
                    }
                }
                item(key = "subs-header") {
                    Text(
                        "Subtitles",
                        style = MaterialTheme.typography.titleSmall,
                        color = NuxColors.OnSurfaceDim,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                item(key = "subs-off") {
                    TrackRow(track = Track("off", "Off", selected = text.none { it.selected })) {
                        engine.selectTextTrack(null)
                        onSubtitleSelected(null)
                        refresh()
                    }
                }
                items(text, key = { "t:${it.id}" }) { track ->
                    TrackRow(track = track) {
                        engine.selectTextTrack(track.id)
                        onSubtitleSelected(track)
                        refresh()
                    }
                }
                if (audio.isEmpty() && text.isEmpty() && video.isEmpty()) {
                    item(key = "none") {
                        Text(
                            "No alternate tracks in this stream.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuxColors.OnSurfaceDim,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Surface(
                onClick = onDismiss,
                shape = ClickableSurfaceDefaults.shape(PlayerTheme.PanelShape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = NuxColors.SurfaceVariant,
                    focusedContainerColor = NuxFocus.container,
                    contentColor = NuxColors.OnSurface,
                    focusedContentColor = NuxColors.OnSurface,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = NuxFocus.ButtonScale),
                border = ClickableSurfaceDefaults.border(focusedBorder = NuxFocus.ring12),
                modifier = Modifier.widthIn(min = 120.dp),
            ) {
                Text(
                    "Close",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun TrackRow(track: Track, onClick: () -> Unit) {
    Surface(
        // Still focusable when unsupported so it can be read, but selecting it
        // does nothing — pinning a rung the decoder rejects blacks out video.
        onClick = { if (track.supported) onClick() },
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(PlayerTheme.ChipShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (track.selected) PlayerTheme.SelectionTint
            else PlayerTheme.RowFill,
            focusedContainerColor = NuxFocus.container,
            contentColor = when {
                !track.supported -> NuxColors.OnSurfaceDim
                track.selected -> NuxColors.FocusBorder
                else -> NuxColors.OnSurface
            },
            focusedContentColor = if (track.supported) NuxColors.OnSurface else NuxColors.OnSurfaceDim,
        ),
        // Was inheriting tv-material3's 1.1 default and drawing no ring at all,
        // so focus here was a background shift of about five points of lightness
        // on a full-width row that also grew 10%. Selection is the gold tint;
        // focus is the ring.
        scale = ClickableSurfaceDefaults.scale(focusedScale = NuxFocus.RowScale),
        border = ClickableSurfaceDefaults.border(focusedBorder = NuxFocus.ring8),
    ) {
        Text(
            text = (if (track.selected) "✓  " else "") + track.label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun OptionChips(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = NuxColors.OnSurfaceDim,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { index, option ->
                Surface(
                    onClick = { onSelect(index) },
                    shape = ClickableSurfaceDefaults.shape(PlayerTheme.ChipShape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (index == selectedIndex) PlayerTheme.SelectionTint
                        else PlayerTheme.RowFill,
                        focusedContainerColor = NuxFocus.container,
                        contentColor = if (index == selectedIndex) NuxColors.FocusBorder else NuxColors.OnSurface,
                        focusedContentColor = NuxColors.OnSurface,
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = NuxFocus.ButtonScale),
                    border = ClickableSurfaceDefaults.border(focusedBorder = NuxFocus.ring8),
                ) {
                    Text(
                        text = option,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Actionable failure state. A corner toast leaves the user staring at a black
 * screen with nothing to press.
 */
@Composable
internal fun PlaybackErrorCard(
    title: String,
    message: String,
    canSwapEngine: Boolean,
    hasNext: Boolean,
    /** Names the Next button: a channel on live, an episode in a box set. */
    isLive: Boolean = true,
    onRetry: () -> Unit,
    onSwapEngine: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { retryFocus.requestFocusRetrying() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PlayerTheme.ScrimStrong)
            .focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .clip(NuxShape.Dialog)
                .background(NuxColors.Surface)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Can't play $title",
                style = MaterialTheme.typography.titleLarge,
                color = NuxColors.OnSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = plainLanguage(message),
                style = MaterialTheme.typography.bodyMedium,
                color = NuxColors.OnSurfaceDim,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.tv.material3.Button(
                    onClick = onRetry,
                    modifier = Modifier.focusRequester(retryFocus),
                ) { Text("Retry") }
                if (canSwapEngine) {
                    // "Playback engine" is what the options menu calls it.
                    androidx.tv.material3.OutlinedButton(onClick = onSwapEngine) {
                        Text("Try other engine")
                    }
                }
                if (hasNext) {
                    androidx.tv.material3.OutlinedButton(onClick = onNext) {
                        Text(if (isLive) "Next channel" else "Next episode")
                    }
                }
                androidx.tv.material3.OutlinedButton(onClick = onBack) { Text("Back") }
            }
        }
    }
}

/** Turns engine error codes into something a viewer can act on. */
private fun plainLanguage(raw: String): String = when {
    raw.contains("403", true) || raw.contains("AUTHENTICATION", true) ->
        "The provider refused the connection. Your account may be at its connection limit, or the stream is no longer available."
    raw.contains("404", true) || raw.contains("NOT_FOUND", true) ->
        "The provider no longer has this stream. Try refreshing the playlist in Settings."
    raw.contains("TIMEOUT", true) || raw.contains("UNSPECIFIED_IO", true) ->
        "The stream didn't respond. This is usually the provider or the network."
    raw.contains("DECODER", true) || raw.contains("DECODING", true) ->
        "This TV couldn't decode the stream. Try the other engine."
    // Already a sentence from the engine's own rewrite; make sure it reads
    // as one (a period, no stray capital mid-line).
    else -> raw.trim().trimEnd('.').let { if (it.isEmpty()) "The stream stopped." else "$it." }
}
