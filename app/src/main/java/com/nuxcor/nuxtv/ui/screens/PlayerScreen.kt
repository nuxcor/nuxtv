@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuxcor.nuxtv.ui.screens

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuxcor.nuxtv.MainViewModel
import com.nuxcor.nuxtv.data.EngineChoice
import com.nuxcor.nuxtv.data.EpgProgram
import com.nuxcor.nuxtv.data.LiveChannel
import com.nuxcor.nuxtv.player.ExoEngine
import com.nuxcor.nuxtv.player.PlayerEngine
import com.nuxcor.nuxtv.player.Track
import com.nuxcor.nuxtv.player.VlcEngine
import com.nuxcor.nuxtv.ui.theme.NuxColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
fun PlayerScreen(vm: MainViewModel, onExit: () -> Unit) {
    val request = vm.playback
    if (request == null) {
        LaunchedEffect(Unit) { onExit() }
        return
    }

    val context = LocalContext.current
    val defaultEngine by vm.engine.collectAsState()
    val activeRecording by vm.activeRecording.collectAsState()

    var engineChoice by remember { mutableStateOf(defaultEngine) }
    var autoFallbackUsed by remember { mutableStateOf(false) }

    var currentIndex by remember(request) { mutableIntStateOf(request.startIndex) }
    var playing by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }

    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var catchupOpen by remember { mutableStateOf(false) }
    var tracksOpen by remember { mutableStateOf(false) }
    val favorites by vm.favorites.collectAsState()

    val item = request.items.getOrNull(currentIndex)
    val channel: LiveChannel? = item?.channelId?.let { vm.channelById(it) }
    val isVod = !request.isLive

    fun poke() {
        controlsVisible = true
        interactionTick++
    }

    // Engine lives for as long as engineChoice does; swapping recreates it.
    val engine: PlayerEngine = remember(engineChoice) {
        if (engineChoice == EngineChoice.VLC) VlcEngine(context) else ExoEngine(context)
    }

    DisposableEffect(engine) {
        engine.listener = object : PlayerEngine.Listener {
            override fun onItemChanged(index: Int) {
                currentIndex = index
                errorMessage = null
            }

            override fun onPlayingChanged(p: Boolean, b: Boolean) {
                playing = p
                buffering = b
            }

            override fun onError(message: String) {
                if (engineChoice == EngineChoice.EXO && !autoFallbackUsed) {
                    autoFallbackUsed = true
                    statusMessage = "Stream failed on ExoPlayer — retrying with VLC"
                    engineChoice = EngineChoice.VLC
                } else {
                    errorMessage = "Playback failed — $message"
                }
            }
        }
        onDispose {
            // Persist resume position for single-item VOD before teardown.
            val url = request.items.getOrNull(engine.currentIndex)?.url
            if (isVod && !request.isCatchup && url != null && engine.durationMs > 0) {
                vm.saveResumePosition(url, engine.positionMs, engine.durationMs)
            }
            engine.release()
        }
    }

    // (Re)prepare when the engine or the playback request changes.
    LaunchedEffect(engine, request) {
        val startIndex = currentIndex.coerceIn(0, request.items.size - 1)
        val resume = when {
            positionMs > 0 -> positionMs // engine swap mid-stream: continue where we were
            isVod && !request.isCatchup ->
                request.items.getOrNull(startIndex)?.url?.let { vm.resumePositionFor(it) } ?: 0L
            else -> 0L
        }
        engine.prepare(request.items, startIndex, resume)
        if (resume > 0 && positionMs == 0L) statusMessage = "Resumed from ${formatTime(resume)}"
    }

    // Poll position/duration for the seek bar.
    LaunchedEffect(engine) {
        while (true) {
            positionMs = engine.positionMs
            durationMs = engine.durationMs
            delay(500)
        }
    }

    // Auto-hide controls.
    LaunchedEffect(interactionTick, playing) {
        if (playing) {
            delay(5_000)
            controlsVisible = false
        }
    }

    // Transient status toast.
    LaunchedEffect(statusMessage) {
        if (statusMessage != null) {
            delay(4_000)
            statusMessage = null
        }
    }

    fun zap(delta: Int) {
        val count = request.items.size
        if (count <= 1) return
        engine.playAt(((engine.currentIndex + delta) % count + count) % count)
    }

    BackHandler(enabled = controlsVisible || catchupOpen || tracksOpen) {
        when {
            tracksOpen -> tracksOpen = false
            catchupOpen -> catchupOpen = false
            else -> controlsVisible = false
        }
    }

    // When the controls hide, their focused button leaves the composition and
    // focus would be lost — park it on the root so D-pad events keep arriving.
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(controlsVisible, catchupOpen, tracksOpen) {
        if (!controlsVisible && !catchupOpen && !tracksOpen) runCatching { rootFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_CHANNEL_UP -> { zap(+1); true }
                    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { engine.playPause(); poke(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_UP ->
                        if (request.isLive && !controlsVisible && !catchupOpen && !tracksOpen) { zap(+1); true } else false
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN ->
                        if (request.isLive && !controlsVisible && !catchupOpen && !tracksOpen) { zap(-1); true } else false
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER ->
                        if (!controlsVisible && !catchupOpen && !tracksOpen) { poke(); true } else false
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT ->
                        if (!controlsVisible && !catchupOpen && !tracksOpen) { poke(); true } else false
                    else -> { if (!catchupOpen && !tracksOpen) poke(); false }
                }
            }
    ) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { engine.createView(it) })

        if (buffering && errorMessage == null) {
            CircularProgressIndicator(
                color = NuxColors.Primary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp),
            )
        }

        // Top status chips: REC + transient messages + errors.
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(24.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val rec = activeRecording
            if (rec != null) {
                PlayerBadge(text = "REC ${rec.channelName} • ${rec.bytesWritten / (1024 * 1024)} MB", color = NuxColors.Error)
            }
            statusMessage?.let { PlayerBadge(text = it, color = NuxColors.Secondary) }
            errorMessage?.let { PlayerBadge(text = it, color = NuxColors.Error) }
        }

        AnimatedVisibility(
            visible = controlsVisible && !catchupOpen && !tracksOpen,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            PlayerControls(
                title = item?.title.orEmpty(),
                subtitle = item?.subtitle,
                engineName = engine.name,
                playing = playing,
                positionMs = positionMs,
                durationMs = durationMs,
                hasPlaylist = request.items.size > 1,
                canRecord = request.isLive && item?.recordUrl != null,
                isRecording = activeRecording != null,
                hasCatchup = request.isLive && (channel?.archiveDays ?: 0) > 0,
                isFavoritable = request.isLive && channel != null,
                isFavorite = channel != null && channel.url in favorites,
                onFavoriteToggle = { channel?.let { vm.toggleFavorite(it) }; poke() },
                onTracks = { tracksOpen = true },
                onPlayPause = { engine.playPause(); poke() },
                onSeekBy = { delta -> engine.seekTo(engine.positionMs + delta); poke() },
                onPrevious = { engine.previous(); poke() },
                onNext = { engine.next(); poke() },
                onRecordToggle = {
                    if (activeRecording != null) vm.stopRecording()
                    else item?.let { vm.startRecording(it) }
                    poke()
                },
                onCatchup = { catchupOpen = true },
                onEngineSwap = {
                    positionMs = engine.positionMs // survive the swap
                    engineChoice =
                        if (engineChoice == EngineChoice.EXO) EngineChoice.VLC else EngineChoice.EXO
                    poke()
                },
                onInteraction = { poke() },
            )
        }

        if (tracksOpen) {
            TracksOverlay(engine = engine, onDismiss = { tracksOpen = false })
        }

        if (catchupOpen && channel != null) {
            CatchupOverlay(
                vm = vm,
                channel = channel,
                onDismiss = { catchupOpen = false },
                onPlay = { program, url ->
                    catchupOpen = false
                    positionMs = 0
                    vm.playCatchup(channel, program, url)
                },
            )
        }
    }
}

@Composable
private fun PlayerBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(NuxColors.Scrim)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun PlayerControls(
    title: String,
    subtitle: String?,
    engineName: String,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    hasPlaylist: Boolean,
    canRecord: Boolean,
    isRecording: Boolean,
    hasCatchup: Boolean,
    isFavoritable: Boolean,
    isFavorite: Boolean,
    onFavoriteToggle: () -> Unit,
    onTracks: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRecordToggle: () -> Unit,
    onCatchup: () -> Unit,
    onEngineSwap: () -> Unit,
    onInteraction: () -> Unit,
) {
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

    Box(modifier = Modifier.fillMaxSize()) {
        // Top scrim + title.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent))
                )
                .padding(horizontal = 32.dp, vertical = 22.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
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

        // Bottom scrim + transport.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))
                )
                .padding(horizontal = 32.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (durationMs > 0) {
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

                if (isFavoritable) {
                    ControlButton(
                        icon = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        label = if (isFavorite) "Remove favorite" else "Add favorite",
                        onClick = onFavoriteToggle,
                        tint = if (isFavorite) NuxColors.Primary else Color.White,
                    )
                }
                ControlButton(Icons.Default.Subtitles, "Audio & subtitles", onTracks)
                if (hasCatchup) ControlButton(Icons.Default.History, "Catch-up", onCatchup)
                if (canRecord || isRecording) {
                    ControlButton(
                        icon = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                        label = if (isRecording) "Stop recording" else "Record",
                        onClick = onRecordToggle,
                        tint = NuxColors.Error,
                    )
                }
                ControlButton(Icons.Default.SwapHoriz, engineName, onEngineSwap, showLabel = true)
            }
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
    tint: Color = Color.White,
    showLabel: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(if (showLabel) RoundedCornerShape(22.dp) else CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (prominent) Color.White.copy(alpha = 0.14f) else Color.Transparent,
            focusedContainerColor = NuxColors.Primary,
            contentColor = tint,
            focusedContentColor = NuxColors.OnAccent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
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
        Text(formatTime(positionMs), style = MaterialTheme.typography.labelMedium, color = Color.White)
        Box(
            modifier = Modifier
                .weight(1f)
                .height(if (focused) 8.dp else 5.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(alpha = 0.25f))
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
        Text(formatTime(durationMs), style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

@Composable
private fun CatchupOverlay(
    vm: MainViewModel,
    channel: LiveChannel,
    onDismiss: () -> Unit,
    onPlay: (EpgProgram, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var programs by remember(channel.id) { mutableStateOf<List<EpgProgram>?>(null) }

    LaunchedEffect(channel.id) {
        val now = System.currentTimeMillis()
        val oldest = now - channel.archiveDays * 24L * 3600 * 1000
        programs = vm.epgFor(channel)
            .filter { it.hasArchive && it.endMs < now && it.startMs > oldest }
            .sortedByDescending { it.startMs }
    }

    val timeFmt = remember { SimpleDateFormat("EEE d MMM • HH:mm", Locale.getDefault()) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f))
            .padding(horizontal = 64.dp, vertical = 40.dp)
    ) {
        Column {
            Text(
                text = "Catch-up — ${channel.name}",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
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
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(programs!!, key = { it.id }) { program ->
                        Surface(
                            onClick = {
                                scope.launch {
                                    val url = vm.catchupUrl(channel, program)
                                    if (url != null) onPlay(program, url)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = NuxColors.Surface.copy(alpha = 0.6f),
                                focusedContainerColor = NuxColors.SurfaceVariant,
                                contentColor = NuxColors.OnSurface,
                                focusedContentColor = NuxColors.OnSurface,
                            ),
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Text(
                                    text = program.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = timeFmt.format(Date(program.startMs)) +
                                        " – ${formatTime(program.endMs - program.startMs)}",
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
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = NuxColors.SurfaceVariant,
                    focusedContainerColor = NuxColors.Primary,
                    contentColor = NuxColors.OnSurface,
                    focusedContentColor = NuxColors.OnAccent,
                ),
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
private fun TracksOverlay(engine: PlayerEngine, onDismiss: () -> Unit) {
    var audio by remember { mutableStateOf(engine.audioTracks()) }
    var text by remember { mutableStateOf(engine.textTracks()) }

    fun refresh() {
        audio = engine.audioTracks()
        text = engine.textTracks()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f))
            .padding(horizontal = 64.dp, vertical = 40.dp)
    ) {
        Column {
            Text(
                text = "Audio & subtitles",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
            )
            Spacer(Modifier.height(16.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        refresh()
                    }
                }
                items(text, key = { "t:${it.id}" }) { track ->
                    TrackRow(track = track) {
                        engine.selectTextTrack(track.id)
                        refresh()
                    }
                }
                if (audio.isEmpty() && text.isEmpty()) {
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
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = NuxColors.SurfaceVariant,
                    focusedContainerColor = NuxColors.Primary,
                    contentColor = NuxColors.OnSurface,
                    focusedContentColor = NuxColors.OnAccent,
                ),
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
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (track.selected) NuxColors.Primary.copy(alpha = 0.16f)
            else NuxColors.Surface.copy(alpha = 0.6f),
            focusedContainerColor = NuxColors.SurfaceVariant,
            contentColor = if (track.selected) NuxColors.FocusBorder else NuxColors.OnSurface,
            focusedContentColor = NuxColors.OnSurface,
        ),
    ) {
        Text(
            text = (if (track.selected) "✓  " else "") + track.label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}
