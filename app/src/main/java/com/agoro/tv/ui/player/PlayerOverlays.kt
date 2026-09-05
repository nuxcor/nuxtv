@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.agoro.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.agoro.tv.MainViewModel
import com.agoro.tv.data.EpgProgram
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.player.PlayerEngine
import com.agoro.tv.player.Track
import com.agoro.tv.ui.components.focusTrap
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
            // Contained, not merely grouped — see Modifier.focusTrap.
            .focusTrap()
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
    var forcingHighest by remember {
        mutableStateOf(engine.isForcingHighest)
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
        forcingHighest = engine.isForcingHighest
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PlayerTheme.ScrimStrong)
            // Contained, not merely grouped — see Modifier.focusTrap.
            .focusTrap()
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
 *
 * The card only — the scrim under it is the scaffold's [FadingScrim]. The
 * two used to be one full-screen box inside the scale-and-fade, which made
 * the fade a screen-sized offscreen layer; sized to the card, the layer is
 * a 640dp dialog.
 */
@Composable
internal fun PlaybackErrorCard(
    title: String,
    message: String,
    canRetryTolerant: Boolean,
    hasNext: Boolean,
    /** Names the Next button: a channel on live, an episode in a box set. */
    isLive: Boolean = true,
    onRetry: () -> Unit,
    onRetryTolerant: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { retryFocus.requestFocusRetrying() }
    Column(
        modifier = Modifier
            .widthIn(max = 640.dp)
            .clip(NuxShape.Dialog)
            .background(NuxColors.Surface)
            // Contained, not merely grouped — see Modifier.focusTrap.
            .focusTrap()
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
            if (canRetryTolerant) {
                // Says what it does, not which component does it. The viewer
                // has no model of demuxers and decoders, but "software" they
                // know — and it sets the right expectation about the picture
                // they may get back.
                androidx.tv.material3.OutlinedButton(onClick = onRetryTolerant) {
                    Text("Try software decoding")
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

/** Turns engine error codes into something a viewer can act on. */
private fun plainLanguage(raw: String): String = when {
    raw.contains("403", true) || raw.contains("AUTHENTICATION", true) ->
        "The provider refused the connection. Your account may be at its connection limit, or the stream is no longer available."
    raw.contains("404", true) || raw.contains("NOT_FOUND", true) ->
        "The provider no longer has this stream. Try refreshing the playlist in Settings."
    raw.contains("TIMEOUT", true) || raw.contains("UNSPECIFIED_IO", true) ->
        "The stream didn't respond. This is usually the provider or the network."
    raw.contains("DECODER", true) || raw.contains("DECODING", true) ->
        "This TV's hardware couldn't decode the stream."
    // Already a sentence from the engine's own rewrite; make sure it reads
    // as one (a period, no stray capital mid-line).
    else -> raw.trim().trimEnd('.').let { if (it.isEmpty()) "The stream stopped." else "$it." }
}

/**
 * What is on after this one: in the corner while the episode runs out, and
 * the same card counting itself down once it has ended.
 *
 * It replaced two half-measures — a one-line text badge in the top-right
 * status stack, too small and too late to be an offer, and a centred panel
 * that arrived after the picture had already gone. This is the shape every
 * streaming service converged on, for the reason they converged on it: a
 * still of what is next is what a viewer recognises an episode by, and the
 * corner is the one place on a 16:9 frame that is reliably not the picture.
 *
 * The hierarchy is the episode's NAME first, because it is the only thing
 * here the viewer does not already know. The series and the address go under
 * it in the dim, and "UP NEXT" is a small tracked eyebrow above — a label,
 * not a headline. The reference this was drawn from leads on the series name
 * instead; that reads well on a phone, where you may not know what is
 * playing, and reads as a repetition on a television forty minutes into an
 * episode of it.
 *
 * ONE card in two states, because it is one moment. [secondsLeft] null is the
 * peek: the episode is still playing and this is an offer to leave it early —
 * OK takes the next one, BACK puts the card away. Non-null is the offer at the
 * end — the episode has finished, the count is running, OK takes it now and
 * BACK stays on the last frame.
 *
 * The pill is in BOTH states, and the label under it names the key. The peek
 * used to carry neither, on the reasoning that a filled pill on a card no key
 * activates is a control that lies — which was right, and the wrong half to
 * fix. A card in the corner where every service puts its next-episode button
 * IS read as a button; the viewer pressed OK at it and got a transport bar.
 * Now the key does what the card looks like it does, and the card says so.
 *
 * Still not a menu. There are exactly two answers and the remote has a key
 * for each.
 */
@Composable
internal fun UpNextCard(
    /** The episode's own name — the headline. */
    heading: String,
    /** "S1 E2  ·  Lady in the Lake" — the address, under the name. */
    meta: String,
    /** The next episode's still, 16:9. Null draws the monogram. */
    artwork: String?,
    /**
     * Seconds until it starts by itself, or null while the current episode is
     * still playing — the difference between a notice and an offer.
     */
    secondsLeft: Int?,
    /** [secondsLeft] as 0..1 of the whole count, for the draining track. */
    countdownFraction: Float = 0f,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(UP_NEXT_CARD_WIDTH)
            // Shadow before the background, so it falls outside the shape
            // rather than under a transparent fill. The card sits on video of
            // no known brightness: the shadow separates it from a light frame
            // and the hairline from a dark one, and between them it never
            // dissolves into whatever is behind it.
            .shadow(18.dp, UpNextShape, clip = false)
            .clip(UpNextShape)
            .background(NuxColors.Surface.copy(alpha = 0.97f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), UpNextShape),
    ) {
        Row(
            modifier = Modifier.padding(UpNextPad),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.agoro.tv.ui.components.Artwork(
                imageUrl = artwork,
                title = heading,
                modifier = Modifier
                    .width(UpNextStill)
                    .aspectRatio(16f / 9f)
                    .clip(PlayerTheme.ChipShape),
                background = NuxColors.SurfaceVariant,
            )
            Spacer(Modifier.width(UpNextGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "UP NEXT",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.6.sp,
                    ),
                    color = NuxColors.Primary,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = heading,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = NuxColors.OnSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelMedium,
                    color = NuxColors.OnSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = UpNextPad),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(PlayerTheme.PillShape)
                    .background(NuxColors.Primary)
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    // "Watch now" against a finished episode; "Play next"
                    // against one that is still running, where "now" would be
                    // asking the viewer what they think they are doing.
                    text = if (secondsLeft != null) "▶  Watch now" else "▶  Play next",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = NuxColors.OnAccent,
                    maxLines = 1,
                )
            }
            if (secondsLeft != null) {
                Spacer(Modifier.width(14.dp))
                Text(
                    // Seconds live OUTSIDE the pill. Inside, the number moves
                    // as it narrows from two digits to one and takes the
                    // label with it; a pill whose text shuffles once a second
                    // is the thing the eye watches instead of the title.
                    text = "${secondsLeft}s",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = NuxColors.OnSurfaceDim,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            // Both keys, both states — the second one is the important one on
            // the peek, where the card arrived uninvited and the viewer needs
            // to know it can be sent away.
            text = if (secondsLeft != null) "OK to start  ·  BACK to stay"
            else "OK to play  ·  BACK to hide",
            style = MaterialTheme.typography.labelSmall,
            color = NuxColors.OnSurfaceDim,
            modifier = Modifier.padding(horizontal = UpNextPad),
        )
        if (secondsLeft != null) {
            Spacer(Modifier.height(8.dp))
            // The same count as the number, drawn rather than read. It drains
            // along the foot of the card, which is the one edge where a
            // moving element cannot land on anything.
            Box(
                modifier = Modifier
                    .padding(horizontal = UpNextPad)
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(NuxShape.Track)
                    .background(Color.White.copy(alpha = 0.15f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(countdownFraction.coerceIn(0f, 1f))
                        .background(NuxColors.Primary),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * The card's corner, shared by its shadow, its fill and its hairline.
 *
 * [NuxShape.Dialog], not a radius of its own: this is the player's other
 * large surface, and the tracks sheet 240 lines up is already drawn on it.
 */
private val UpNextShape = NuxShape.Dialog

/** The card's one inset — its row, its pill, its hint and its track. */
private val UpNextPad = 16.dp

/**
 * The still, and the gap between it and the words.
 *
 * The still is the card's adjustable part. It shrank from 172dp because at
 * that size it was the tallest thing in the row and set the card's height
 * from a thumbnail rather than from the text — 124dp is still a recognisable
 * frame, and the words now govern. Both are on the 4dp scale.
 */
private val UpNextStill = 124.dp
private val UpNextGap = 12.dp

/**
 * The measured quantity, and the reason the card is the width it is.
 *
 * Two lines of a real episode title have to fit beside the still, and the
 * longest title this catalogue carries — "It has to do with the search for
 * the marvelous" — is what set the number: at a narrower column it ran out
 * of room mid-phrase. 224dp is the room it needs at titleSmall.
 *
 * This is stored rather than the card's total width because the total is the
 * derived thing. When the card came down from 448dp the cost was taken off
 * the still and the padding, and had the width stayed the literal it was,
 * the two of them would have quietly eaten 2dp of the room measured here —
 * which is a whole word at a wrap boundary, not 2dp of slack. Trade the
 * still and the gap freely; this constant is the one that cannot move
 * without measuring a title against it again.
 */
private val UpNextTextColumn = 224.dp

/** Derived — never tune this directly, tune [UpNextStill]. */
private val UP_NEXT_CARD_WIDTH =
    UpNextTextColumn + UpNextStill + UpNextGap + UpNextPad * 2
