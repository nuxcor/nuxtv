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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
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
import androidx.compose.foundation.layout.Arrangement as LayoutArrangement
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
import com.nuxcor.nuxtv.data.QualityTag
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
    var inPip by remember { mutableStateOf(false) }
    // Track PiP from the activity callback, not a poll.
    DisposableEffect(context) {
        val activity = context as? androidx.activity.ComponentActivity
        val listener = androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { info ->
            inPip = info.isInPictureInPictureMode
        }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity?.removeOnPictureInPictureModeChangedListener(listener) }
    }
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
    var qualityLabel by remember { mutableStateOf<String?>(null) }

    var controlsVisible by remember { mutableStateOf(false) } // banner first, not the transport bar
    var bannerTick by remember { mutableIntStateOf(0) }
    var previousIndex by remember { mutableIntStateOf(-1) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var catchupOpen by remember { mutableStateOf(false) }
    var tracksOpen by remember { mutableStateOf(false) }
    var miniGuideOpen by remember { mutableStateOf(false) }
    var digitBuffer by remember { mutableStateOf("") }
    var retriesLeft by remember { mutableIntStateOf(2) }
    var sleepMinutes by remember { mutableIntStateOf(0) }
    var scaleMode by remember { mutableIntStateOf(0) }
    var speed by remember { mutableStateOf(1f) }
    val scope = rememberCoroutineScope()
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
                when {
                    engineChoice == EngineChoice.EXO && !autoFallbackUsed -> {
                        autoFallbackUsed = true
                        statusMessage = "Stream failed on ExoPlayer — retrying with VLC"
                        engineChoice = EngineChoice.VLC
                    }

                    request.isLive && retriesLeft > 0 -> {
                        retriesLeft--
                        statusMessage = "Stream error — reconnecting…"
                        scope.launch {
                            delay(3_000)
                            engine.playAt(engine.currentIndex)
                        }
                    }

                    else -> errorMessage = "Playback failed — $message"
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
            request.isCatchup -> 0L // never inherit a position from the previous stream
            request.isLive -> 0L // live streams restart at the live edge after a swap
            positionMs > 0 -> positionMs // engine swap mid-stream: continue where we were
            isVod ->
                request.items.getOrNull(startIndex)?.url?.let { vm.resumePositionFor(it) } ?: 0L
            else -> 0L
        }
        engine.prepare(request.items, startIndex, resume)
        // A recreated engine starts at defaults; re-apply the user's choices.
        if (speed != 1f) engine.setSpeed(speed)
        if (scaleMode != 0) engine.setScaleMode(scaleMode)
        if (resume > 0 && positionMs == 0L) statusMessage = "Resumed from ${formatTime(resume)}"
    }

    // Poll only while the chrome that displays these values is on screen;
    // a permanent 2Hz poll recomposes the whole player during playback.
    LaunchedEffect(engine, controlsVisible, miniGuideOpen) {
        while (controlsVisible || miniGuideOpen) {
            positionMs = engine.positionMs
            durationMs = engine.durationMs
            qualityLabel = engine.videoResolution?.let { (w, h) -> QualityTag.ofResolution(w, h) }
            delay(500)
        }
    }

    // Pause when the app leaves the foreground, unless we're in PiP —
    // otherwise audio keeps playing invisibly behind the launcher.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, engine) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                val pip = android.os.Build.VERSION.SDK_INT >= 24 &&
                    (context as? android.app.Activity)?.isInPictureInPictureMode == true
                if (!pip && engine.isPlaying) engine.playPause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Auto-hide controls.
    LaunchedEffect(interactionTick, playing) {
        if (playing) {
            delay(5_000)
            controlsVisible = false
        }
    }

    fun zap(delta: Int) {
        val count = request.items.size
        if (count <= 1) return
        previousIndex = engine.currentIndex
        engine.playAt(((engine.currentIndex + delta) % count + count) % count)
        bannerTick++
    }

    fun jumpTo(index: Int) {
        if (index !in request.items.indices) return
        previousIndex = engine.currentIndex
        engine.playAt(index)
        bannerTick++
    }

    // Channel-number entry: digits collect briefly, then jump.
    LaunchedEffect(digitBuffer) {
        if (digitBuffer.isNotEmpty()) {
            delay(1_600)
            val n = digitBuffer.toIntOrNull()
            digitBuffer = ""
            if (n != null) {
                // Match the channel number shown in the lists; fall back to position.
                val byNumber = request.items.indexOfFirst { item ->
                    item.channelId?.let { id -> vm.channelById(id)?.number } == n
                }
                jumpTo(if (byNumber >= 0) byNumber else n - 1)
            }
        }
    }

    // Sleep timer.
    LaunchedEffect(sleepMinutes) {
        if (sleepMinutes > 0) {
            delay(sleepMinutes * 60_000L)
            if (engine.isPlaying) engine.playPause()
            statusMessage = "Sleep timer: playback paused"
            sleepMinutes = 0
        }
    }

    // Transient status toast.
    LaunchedEffect(statusMessage) {
        if (statusMessage != null) {
            delay(4_000)
            statusMessage = null
        }
    }

    BackHandler(enabled = controlsVisible || catchupOpen || tracksOpen || miniGuideOpen) {
        when {
            miniGuideOpen -> miniGuideOpen = false
            tracksOpen -> tracksOpen = false
            catchupOpen -> catchupOpen = false
            else -> controlsVisible = false
        }
    }

    // When the controls hide, their focused button leaves the composition and
    // focus would be lost — park it on the root so D-pad events keep arriving.
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(controlsVisible, catchupOpen, tracksOpen, miniGuideOpen) {
        if (!controlsVisible && !catchupOpen && !tracksOpen && !miniGuideOpen) {
            runCatching { rootFocus.requestFocus() }
        }
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
                    AndroidKeyEvent.KEYCODE_CHANNEL_UP ->
                        if (!catchupOpen && !tracksOpen && !miniGuideOpen) { zap(+1); true } else false
                    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN ->
                        if (!catchupOpen && !tracksOpen && !miniGuideOpen) { zap(-1); true } else false
                    AndroidKeyEvent.KEYCODE_INFO ->
                        if (!catchupOpen && !tracksOpen && !miniGuideOpen) { bannerTick++; true } else false
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                        if (!catchupOpen && !tracksOpen && !miniGuideOpen) {
                            engine.playPause(); poke(); true
                        } else false
                    AndroidKeyEvent.KEYCODE_DPAD_UP ->
                        if (request.isLive && !controlsVisible && !catchupOpen && !tracksOpen && !miniGuideOpen) { zap(+1); true } else false
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN ->
                        if (request.isLive && !controlsVisible && !catchupOpen && !tracksOpen && !miniGuideOpen) { zap(-1); true } else false
                    AndroidKeyEvent.KEYCODE_LAST_CHANNEL, AndroidKeyEvent.KEYCODE_PROG_RED ->
                        if (request.isLive && previousIndex >= 0) { jumpTo(previousIndex); true } else false
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER ->
                        if (!controlsVisible && !catchupOpen && !tracksOpen && !miniGuideOpen) { poke(); true } else false
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT ->
                        if (!controlsVisible && !catchupOpen && !tracksOpen && !miniGuideOpen) {
                            if (request.isLive && request.items.size > 1) miniGuideOpen = true else poke()
                            true
                        } else false
                    in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 ->
                        if (request.isLive && !catchupOpen && !tracksOpen && !miniGuideOpen) {
                            digitBuffer += (event.key.nativeKeyCode - AndroidKeyEvent.KEYCODE_0).toString()
                            true
                        } else false
                    else -> { if (!catchupOpen && !tracksOpen && !miniGuideOpen) poke(); false }
                }
            }
    ) {
        // key() forces a fresh surface when the engine is swapped — AndroidView's
        // factory runs once per node, so without this the new engine would render
        // into a view that was already released (black screen after fallback).
        androidx.compose.runtime.key(engineChoice) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { engine.createView(it) })
        }

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
            if (digitBuffer.isNotEmpty()) {
                PlayerBadge(text = "Channel $digitBuffer", color = NuxColors.FocusBorder)
            }
            if (!request.isLive && durationMs > 0 &&
                durationMs - positionMs in 1_000..15_000 &&
                currentIndex < request.items.size - 1
            ) {
                PlayerBadge(
                    text = "Up next: ${request.items[currentIndex + 1].subtitle ?: request.items[currentIndex + 1].title}",
                    color = NuxColors.Primary,
                )
            }
            if (sleepMinutes > 0) {
                PlayerBadge(text = "Sleep in ${sleepMinutes}m", color = NuxColors.OnSurfaceDim)
            }
        }

        AnimatedVisibility(
            visible = controlsVisible && !catchupOpen && !tracksOpen && !miniGuideOpen && !inPip,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            PlayerControls(
                title = item?.title.orEmpty(),
                subtitle = item?.subtitle,
                qualityLabel = qualityLabel,
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
                onChannels = { controlsVisible = false; miniGuideOpen = true },
                onEngineSwap = {
                    positionMs = engine.positionMs // survive the swap
                    engineChoice =
                        if (engineChoice == EngineChoice.EXO) EngineChoice.VLC else EngineChoice.EXO
                    poke()
                },
                onPip = {
                    inPip = true // hide chrome immediately; the poll confirms
                    (context as? android.app.Activity)?.let { activity ->
                        if (android.os.Build.VERSION.SDK_INT >= 26) {
                            runCatching {
                                activity.enterPictureInPictureMode(
                                    android.app.PictureInPictureParams.Builder()
                                        .setAspectRatio(android.util.Rational(16, 9))
                                        .build()
                                )
                            }
                        }
                    }
                },
                onInteraction = { poke() },
            )
        }

        if (miniGuideOpen && !inPip) {
            MiniGuide(
                vm = vm,
                items = request.items,
                currentIndex = currentIndex,
                onSelect = { index ->
                    miniGuideOpen = false
                    jumpTo(index)
                },
                onDismiss = { miniGuideOpen = false },
            )
        }

        if (tracksOpen && !inPip) {
            TracksOverlay(
                engine = engine,
                isVod = !request.isLive,
                scaleMode = scaleMode,
                onScaleMode = { mode -> scaleMode = mode; engine.setScaleMode(mode) },
                speed = speed,
                onSpeed = { sp -> speed = sp; engine.setSpeed(sp) },
                sleepMinutes = sleepMinutes,
                onSleep = { minutes -> sleepMinutes = minutes },
                onDismiss = { tracksOpen = false },
            )
        }

        if (catchupOpen && channel != null && !inPip) {
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
    qualityLabel: String?,
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
    onChannels: () -> Unit,
    onEngineSwap: () -> Unit,
    onPip: () -> Unit,
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                qualityLabel?.let {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(NuxColors.Primary.copy(alpha = 0.22f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = NuxColors.FocusBorder,
                        )
                    }
                }
            }
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
                ControlButton(Icons.Default.PictureInPictureAlt, "Picture in picture", onPip)
                if (hasPlaylist) {
                    ControlButton(Icons.AutoMirrored.Filled.List, "Channels", onChannels)
                }
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
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }

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
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = false),
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
private fun TracksOverlay(
    engine: PlayerEngine,
    isVod: Boolean,
    scaleMode: Int,
    onScaleMode: (Int) -> Unit,
    speed: Float,
    onSpeed: (Float) -> Unit,
    sleepMinutes: Int,
    onSleep: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var audio by remember { mutableStateOf(engine.audioTracks()) }
    var text by remember { mutableStateOf(engine.textTracks()) }
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { initialFocus.requestFocus() } }

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
                text = "Playback options",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
            )
            Spacer(Modifier.height(16.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                            options = speeds.map { if (it == 1f) "1x" else "${it}x" },
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
                modifier = Modifier.widthIn(min = 120.dp).focusRequester(initialFocus),
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
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (index == selectedIndex) NuxColors.Primary.copy(alpha = 0.2f)
                        else NuxColors.Surface.copy(alpha = 0.6f),
                        focusedContainerColor = NuxColors.Primary,
                        contentColor = if (index == selectedIndex) NuxColors.FocusBorder else NuxColors.OnSurface,
                        focusedContentColor = NuxColors.OnAccent,
                    ),
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

/** TiviMate-style channel list overlay inside the player, with now/next. */
@Composable
private fun MiniGuide(
    vm: MainViewModel,
    items: List<com.nuxcor.nuxtv.data.PlayableItem>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val epgState by vm.epgState.collectAsState()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val firstFocus = remember { FocusRequester() }
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            nowTick = System.currentTimeMillis()
        }
    }

    LaunchedEffect(Unit) {
        listState.scrollToItem(currentIndex.coerceAtLeast(0))
        // The target row composes a frame after the scroll; retry briefly.
        repeat(5) {
            if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(60)
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .width(430.dp)
                .fillMaxHeight()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Black.copy(alpha = 0.96f), Color.Black.copy(alpha = 0.85f))
                    )
                )
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        event.key.nativeKeyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT
                    ) {
                        onDismiss()
                        true
                    } else false
                }
                .padding(start = 22.dp, top = 22.dp, end = 14.dp)
        ) {
            Column {
                Text(
                    text = "Channels",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                )
                Spacer(Modifier.height(10.dp))
                androidx.compose.foundation.lazy.LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxHeight(),
                ) {
                    items(items.size, key = { it }) { index ->
                        val item = items[index]
                        val channel = item.channelId?.let { vm.channelById(it) }
                        val nowNext = remember(channel?.id, epgState, nowTick) {
                            channel?.let { ch ->
                                val programs = vm.programsFor(ch)
                                val current = programs.firstOrNull { nowTick in it.startMs until it.endMs }
                                val next = programs.firstOrNull { it.startMs >= nowTick }
                                current to next
                            } ?: (null to null)
                        }
                        Surface(
                            onClick = { onSelect(index) },
                            modifier = if (index == currentIndex) {
                                Modifier.fillMaxWidth().focusRequester(firstFocus)
                            } else {
                                Modifier.fillMaxWidth()
                            },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (index == currentIndex) {
                                    NuxColors.Primary.copy(alpha = 0.18f)
                                } else Color.Transparent,
                                focusedContainerColor = NuxColors.SurfaceVariant,
                                contentColor = NuxColors.OnSurface,
                                focusedContentColor = NuxColors.OnSurface,
                            ),
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(
                                    text = "${index + 1}  ${item.title}",
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                nowNext.first?.let { now ->
                                    Text(
                                        text = "Now: ${now.title}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NuxColors.FocusBorder,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                nowNext.second?.let { next ->
                                    Text(
                                        text = "Next: ${next.title}",
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
        }
        // Clicking the exposed video area closes the guide.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        event.key.nativeKeyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT
                    ) {
                        onDismiss()
                        true
                    } else false
                }
        )
    }
}

/**
 * TiviMate-style channel banner: what you're watching, what's on now with a
 * progress bar, and what's next. Shown on every channel change so zapping is
 * never blind.
 */
@Composable
private fun ChannelBanner(
    vm: MainViewModel,
    item: com.nuxcor.nuxtv.data.PlayableItem?,
    channel: LiveChannel?,
    isLive: Boolean,
    qualityLabel: String?,
    engineName: String,
    liftAboveControls: Boolean,
) {
    val epgState by vm.epgState.collectAsState()
    val favorites by vm.favorites.collectAsState()
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMs = System.currentTimeMillis()
        }
    }
    val nowNext = remember(channel?.id, epgState, nowMs) {
        channel?.let { ch ->
            val programs = vm.programsFor(ch)
            val current = programs.firstOrNull { nowMs in it.startMs until it.endMs }
            val next = programs.firstOrNull { it.startMs >= nowMs }
            current to next
        } ?: (null to null)
    }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 40.dp, end = 40.dp, bottom = if (liftAboveControls) 150.dp else 44.dp)
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.82f))
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (isLive && channel != null) {
                com.nuxcor.nuxtv.ui.components.Artwork(
                    imageUrl = channel.logo,
                    title = channel.name,
                    modifier = Modifier
                        .size(width = 86.dp, height = 54.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    channel?.number?.let { number ->
                        Text(
                            text = number.toString(),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = NuxColors.Primary,
                        )
                    }
                    Text(
                        text = channel?.name ?: item?.title.orEmpty(),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (channel != null && channel.url in favorites) {
                        Text("★", style = MaterialTheme.typography.titleMedium, color = NuxColors.Primary)
                    }
                    qualityLabel?.let {
                        com.nuxcor.nuxtv.ui.components.MetaChip(it, accent = true)
                    }
                }
                val current = nowNext.first
                if (current != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${timeFmt.format(Date(current.startMs))}  ${current.title}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    val progress = ((nowMs - current.startMs).toFloat() /
                        (current.endMs - current.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.22f))
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
                nowNext.second?.let { next ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Next  ${timeFmt.format(Date(next.startMs))}  ${next.title}",
                        style = MaterialTheme.typography.labelMedium,
                        color = NuxColors.OnSurfaceDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = engineName,
                style = MaterialTheme.typography.labelSmall,
                color = NuxColors.OnSurfaceDim,
            )
        }
    }
}
