@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.agoro.tv.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Icon
import com.agoro.tv.MainViewModel
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.data.PlayerPrefs
import com.agoro.tv.player.DisplayModeSwitcher
import com.agoro.tv.player.findActivity
import com.agoro.tv.ui.theme.NuxColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Bump when the key map changes so the banner hints re-teach once. */
private const val KEY_HINTS_VERSION = 3

/**
 * How long the tune card may stand before the stream is declared dead. Long
 * enough to clear the failure ladder's own 3s + 6s backoff on a slow provider,
 * short enough that a hang never looks like patience rewarded.
 */
private const val TUNE_TIMEOUT_MS = 45_000L

/**
 * PiP params from the actual decoded size, not an assumed 16:9. The platform
 * rejects aspect ratios outside [0.418, 2.39]; clamp just inside the limits
 * and fall back to 16:9 for degenerate or unknown sizes.
 */
@androidx.annotation.RequiresApi(26)
private fun buildPipParams(
    videoSize: Pair<Int, Int>?,
    autoEnter: Boolean,
): android.app.PictureInPictureParams {
    val rational = videoSize
        ?.takeIf { (w, h) -> w > 0 && h > 0 }
        ?.let { (w, h) ->
            val ratio = w.toFloat() / h
            when {
                ratio < 0.42f -> android.util.Rational(42, 100)
                ratio > 2.38f -> android.util.Rational(238, 100)
                else -> android.util.Rational(w, h)
            }
        } ?: android.util.Rational(16, 9)
    val builder = android.app.PictureInPictureParams.Builder().setAspectRatio(rational)
    if (android.os.Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(autoEnter)
    return builder.build()
}

private val ASPECT_LABELS = listOf("Fit", "Stretch", "Zoom")
private val SLEEP_CHOICES = listOf(0, 30, 60, 90)

@Composable
fun PlayerScreen(vm: MainViewModel, onExit: () -> Unit) {
    val request = vm.playback
    if (request == null) {
        LaunchedEffect(Unit) { onExit() }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PlayerPrefs(context) }

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
    // The button only exists where the system can honour it.
    val pipSupported = remember {
        android.os.Build.VERSION.SDK_INT >= 26 &&
            context.packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE
            )
    }
    // Clear auto-enter when the player leaves, or the browse screens would
    // minimise into PiP on HOME too.
    DisposableEffect(pipSupported) {
        onDispose {
            if (pipSupported && android.os.Build.VERSION.SDK_INT >= 31) {
                (context as? android.app.Activity)?.let { activity ->
                    runCatching {
                        activity.setPictureInPictureParams(
                            android.app.PictureInPictureParams.Builder()
                                .setAutoEnterEnabled(false)
                                .build()
                        )
                    }
                }
            }
        }
    }

    val defaultEngine by vm.engine.collectAsState()
    val qualityPref by vm.videoQuality.collectAsState()
    val activeRecording by vm.activeRecording.collectAsState()
    val favorites by vm.favorites.collectAsState()

    val session = remember {
        PlayerSession(
            context = context,
            scope = scope,
            initialRequest = request,
            initialEngine = defaultEngine,
            onSaveResume = vm::saveResumePosition,
        )
    }
    // A replacement playlist re-primes the session the way the old screen's
    // remember(request) resets did — and returns the engine to the user's
    // default choice.
    remember(request) {
        session.onRequest(request, defaultEngine)
        true
    }

    val item = request.items.getOrNull(session.currentIndex)
    val channel: LiveChannel? = item?.channelId?.let { vm.channelById(it) }
    val isVod = !request.isLive

    // Engine lives for as long as engineChoice does; swapping recreates it.
    val engine = remember(session.engineChoice) { session.createEngine(qualityPref == 1) }
    DisposableEffect(engine) {
        engine.listener = session.listener
        // Media-session / CEC / assistant transport goes through the session,
        // which owns the live stale-buffer rejoin rule.
        engine.onTransportPlay = { session.transportPlay() }
        engine.onTransportPause = { session.transportPause() }
        onDispose { session.teardownEngine(engine) }
    }

    // (Re)prepare when the engine or the playback request changes.
    LaunchedEffect(engine, request) {
        session.tuning = true
        val startIndex = session.currentIndex.coerceIn(0, request.items.size - 1)
        val resume = when {
            request.isCatchup -> 0L // never inherit a position from the previous stream
            request.isLive -> 0L // live streams restart at the live edge after a swap
            // "Start over" only suppresses the *initial* lookup; an engine swap
            // mid-playback still continues from where we were.
            request.ignoreResume && session.positionMs == 0L -> 0L
            session.positionMs > 0 -> session.positionMs // engine swap mid-stream: continue where we were
            isVod ->
                request.items.getOrNull(startIndex)?.url?.let { vm.resumePositionFor(it) } ?: 0L
            else -> 0L
        }
        // The saved VOD speed loads once per player visit, then travels in the
        // session so an engine swap keeps whatever the viewer has set since.
        if (isVod && !session.vodSpeedLoaded) {
            session.vodSpeedLoaded = true
            prefs.vodSpeed.first().takeIf { it != 1f }?.let { session.speed = it }
        }
        engine.prepare(request.items, startIndex, resume, isLive = request.isLive)
        // A recreated engine starts at defaults; re-apply the user's choices.
        if (session.speed != 1f) engine.setSpeed(session.speed)
        if (session.scaleMode != 0) engine.setScaleMode(session.scaleMode)
        // Adaptive unless the viewer pinned the top rung in the quality sheet.
        // Doing this per-stream also drops any single rung pinned on the
        // previous channel, which meant nothing on this one.
        engine.selectVideoTrack(
            if (qualityPref == 1) com.agoro.tv.player.HIGHEST_QUALITY else null
        )
        if (resume > 0 && session.positionMs == 0L) {
            session.statusMessage = "Resumed from ${formatPlayerTime(resume)}"
        }
    }

    // Learn each live stream's REAL tier as it decodes, so the lists can
    // stop repeating whatever the provider typed into the stream name.
    LaunchedEffect(session.videoSize, session.currentIndex) {
        if (!request.isLive) return@LaunchedEffect
        val (_, h) = session.videoSize ?: return@LaunchedEffect
        request.items.getOrNull(session.currentIndex)?.url
            ?.let { vm.recordDecodedQuality(it, h) }
    }

    // Ask the TV for a mode that suits the stream — the panel's own refresh
    // and, where the output is smaller than the picture, its resolution.
    // Skipped in PiP: the window is a thumbnail there, and a mode change to
    // suit it would blank the app the viewer is actually looking at.
    val displayModes = remember(context) {
        context.findActivity()?.let { DisplayModeSwitcher(it) }
    }
    LaunchedEffect(displayModes, session.videoSize, session.videoFrameRate, inPip) {
        val switcher = displayModes ?: return@LaunchedEffect
        if (inPip) return@LaunchedEffect
        val height = session.videoSize?.second ?: 0
        // Nothing has decoded yet: switching on a guess would blank the screen
        // over the tune, and be wrong as often as not.
        if (height <= 0 && session.videoFrameRate == null) return@LaunchedEffect
        runCatching { switcher.apply(height, session.videoFrameRate) }
    }
    // The rest of the app has no business running at 24Hz.
    DisposableEffect(displayModes) {
        onDispose { runCatching { displayModes?.reset() } }
    }

    // Keep the activity's PiP params fresh so API 31+ auto-enters on HOME
    // with the real picture aspect, updated as the decoded size changes.
    LaunchedEffect(session.videoSize, pipSupported) {
        if (!pipSupported) return@LaunchedEffect
        (context as? android.app.Activity)?.let { activity ->
            runCatching {
                activity.setPictureInPictureParams(
                    buildPipParams(session.videoSize, autoEnter = true)
                )
            }
        }
    }

    // Aspect ratio follows the channel: a per-channel override where the
    // viewer has set one, the global default otherwise.
    LaunchedEffect(engine, session.currentIndex, request) {
        val url = request.items.getOrNull(session.currentIndex)?.url ?: return@LaunchedEffect
        val mode = prefs.aspectModeFor(url)
        if (mode != session.scaleMode) {
            session.scaleMode = mode
            engine.setScaleMode(mode)
        }
    }

    // Preferred audio/subtitle language, applied once per item as soon as the
    // stream announces its tracks (they appear a beat after it opens).
    LaunchedEffect(engine, session.currentIndex) {
        val prefAudio = prefs.preferredAudioLanguage.first()
        val prefText = prefs.preferredSubtitleLanguage.first()
        if (prefAudio == null && prefText == null) return@LaunchedEffect
        var audioDone = prefAudio == null
        var textDone = prefText == null
        repeat(20) {
            delay(500)
            if (!audioDone) {
                val tracks = engine.audioTracks()
                if (tracks.isNotEmpty()) {
                    tracks.firstOrNull { languageMatches(it.language, prefAudio) }
                        ?.takeIf { !it.selected }
                        ?.let { engine.selectAudioTrack(it.id) }
                    audioDone = true
                }
            }
            if (!textDone) {
                val tracks = engine.textTracks()
                if (tracks.isNotEmpty()) {
                    tracks.firstOrNull { languageMatches(it.language, prefText) }
                        ?.takeIf { !it.selected }
                        ?.let { engine.selectTextTrack(it.id) }
                    textDone = true
                }
            }
            if (audioDone && textDone) return@LaunchedEffect
        }
    }

    // The transient VOD seek chrome: shown by bare LEFT/RIGHT seeks, gone
    // shortly after the last press.
    var seekFlashTick by remember { mutableIntStateOf(0) }
    var seekFlashVisible by remember { mutableStateOf(false) }
    LaunchedEffect(seekFlashTick) {
        if (seekFlashTick == 0) return@LaunchedEffect
        seekFlashVisible = true
        delay(PlayerMotion.SeekFlashMs)
        seekFlashVisible = false
    }

    // 2Hz only while the chrome that displays these values is on screen — a
    // permanent fast poll recomposes the whole player during playback. But the
    // values must not freeze entirely on bare playback: the "Up next" badge,
    // PiP's real aspect, and error-recovery resume all read them. A lazy
    // background cadence keeps them honest at negligible cost.
    LaunchedEffect(engine, session.layer, session.bannerVisible, seekFlashVisible) {
        while (true) {
            session.positionMs = engine.positionMs
            session.durationMs = engine.durationMs
            session.videoSize = engine.videoResolution
            session.videoFrameRate = engine.videoFrameRate
            session.hdrFormat = engine.hdrFormat
            session.audioFormatLabel = engine.audioFormatLabel
            val chromeUp = session.layer == PlayerLayer.Controls ||
                session.layer == PlayerLayer.ChannelList ||
                session.layer == PlayerLayer.Tracks ||
                session.bannerVisible || seekFlashVisible
            delay(if (chromeUp) 500 else 5_000)
        }
    }

    // The channel banner: shown on every zap, on the INFO key, and once when a
    // live stream starts, so changing channel is never blind.
    //
    // bannerShows also drives the key hints. Nothing on this screen said that
    // OK opens the channel options, LEFT the channel list or UP/DOWN zap — and a
    // 10-foot UI has no hover, no tooltip and no menu key to fall back on, so an
    // undocumented model is an undiscoverable one. Shown for the first few
    // banners after a key-map change, then it gets out of the way for good
    // (the taught version persists in PlayerPrefs).
    LaunchedEffect(session.bannerTick, session.currentIndex, engine) {
        if (!request.isLive) return@LaunchedEffect
        session.bannerShows++
        session.bannerVisible = true
        delay(5_000)
        session.bannerVisible = false
    }
    var hintsVersionSeen by remember { mutableIntStateOf(Int.MAX_VALUE) }
    LaunchedEffect(Unit) { hintsVersionSeen = prefs.keyHintsVersion.first() }
    LaunchedEffect(session.bannerShows) {
        if (session.bannerShows >= 3 && hintsVersionSeen < KEY_HINTS_VERSION) {
            prefs.setKeyHintsVersion(KEY_HINTS_VERSION)
        }
    }

    // Recent channels. Hooked to currentIndex rather than to the play call, so
    // it catches every route onto a channel: opening one from Live TV or the
    // guide, zapping with CHANNEL/DPAD, the mini-guide, and typing a number on
    // the keypad. The dwell is what makes the list mean anything — zapping
    // through twenty channels to find something should record the one you
    // stopped on, not all twenty.
    LaunchedEffect(session.currentIndex, request) {
        if (!request.isLive) return@LaunchedEffect
        val url = request.items.getOrNull(session.currentIndex)?.url ?: return@LaunchedEffect
        // Immediately, for the guide's return landing; the dwell below is
        // only for the Recent shelf.
        vm.noteTuned(url)
        delay(8_000)
        vm.recordChannelVisit(url)
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
    LaunchedEffect(session.interactionTick, session.playing) {
        if (session.playing) {
            delay(5_000)
            if (session.layer == PlayerLayer.Controls) session.layer = PlayerLayer.None
        }
    }

    // The zap dwell: identity updates were instant; the stream opens only once
    // the chain rests, so holding CH+ doesn't open a connection per press.
    LaunchedEffect(session.pendingTuneIndex) {
        if (session.pendingTuneIndex == null) return@LaunchedEffect
        delay(PlayerMotion.ZapDwellMs)
        session.commitPendingTune()
    }

    // The full live lineup, for number entry. Collected here — not read off
    // the StateFlow at commit time — so the WhileSubscribed upstream stays
    // warm for as long as the player can be asked to tune by number.
    val liveChannels by vm.displayChannels.collectAsState()

    // A committed number that matched nothing; the pill shows it dim briefly.
    var noChannelNumber by remember { mutableStateOf<Int?>(null) }

    // Typed digits resolve against the FULL channel list. A number inside the
    // current zap playlist jumps within it; one outside retunes onto the full
    // list — the playlist is often one category, and a typed number must
    // reach everything the guide numbers. Named so OK can commit early.
    fun commitDigits() {
        val n = session.digitBuffer.toIntOrNull()
        session.digitBuffer = ""
        when (val tune = n?.let { resolveDigitTune(it, request.items, liveChannels) }) {
            is DigitTune.Jump -> {
                session.jumpTo(tune.itemIndex)
                // Typing a number means "watch it now" — drop the list.
                if (session.layer == PlayerLayer.ChannelList) session.layer = PlayerLayer.None
            }
            is DigitTune.Retune -> {
                // Same bookkeeping as tuning from the grid guide: the old
                // playlist's previous-index means nothing on the new one.
                session.layer = PlayerLayer.None
                session.previousIndex = -1
                session.positionMs = 0
                vm.playChannels(liveChannels, tune.channelIndex)
            }
            is DigitTune.Unknown -> noChannelNumber = tune.number
            null -> Unit
        }
    }

    // Channel-number entry: digits collect briefly, then tune. Four digits is
    // the cap — no playlist numbers past 9999 — and a full buffer commits at
    // once.
    LaunchedEffect(session.digitBuffer) {
        if (session.digitBuffer.isEmpty()) return@LaunchedEffect
        noChannelNumber = null // a new entry replaces the last miss
        if (session.digitBuffer.length < 4) delay(1_600)
        commitDigits()
    }
    // The miss dismisses itself; no error card for a number that isn't there.
    LaunchedEffect(noChannelNumber) {
        if (noChannelNumber == null) return@LaunchedEffect
        delay(1_600)
        noChannelNumber = null
    }

    // Sleep timer: a wall-clock deadline, so the badge can count down.
    LaunchedEffect(session.sleepDeadlineMs) {
        val deadline = session.sleepDeadlineMs
        if (deadline <= 0) return@LaunchedEffect
        delay((deadline - System.currentTimeMillis()).coerceAtLeast(0))
        session.engine?.let { if (it.isPlaying) it.playPause() }
        session.statusMessage = "Sleep timer: playback paused"
        session.sleepDeadlineMs = 0
        session.sleepChoiceMinutes = 0
    }
    var sleepNowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session.sleepDeadlineMs) {
        while (session.sleepDeadlineMs > 0) {
            sleepNowMs = System.currentTimeMillis()
            delay(30_000)
        }
    }

    // Transient status toast.
    LaunchedEffect(session.statusMessage) {
        if (session.statusMessage != null) {
            delay(4_000)
            session.statusMessage = null
        }
    }

    // Belt and braces on the tuning card: no engine failure, however exotic,
    // may leave the viewer on a spinner with no way forward. The failure
    // ladder is the real recovery path — this only catches a stream that
    // neither plays nor reports an error, which otherwise reads as a hang.
    //
    // Keyed on the CHANNEL, not the request: every ladder step rewrites the
    // request, and keying on it restarted this clock on each hop — a channel
    // with a deep fallback ladder could cascade for minutes with the viewer
    // pinned to "Tuning…" and no way out. The budget covers the whole
    // ladder; when it runs out mid-cascade the error card takes over, which
    // trades an automatic retry for the viewer getting their remote back.
    LaunchedEffect(session.tuning, session.currentIndex) {
        if (!session.tuning) return@LaunchedEffect
        delay(TUNE_TIMEOUT_MS)
        if (session.tuning && session.errorMessage == null) {
            session.failTuning("The stream didn't start.")
        }
    }

    // A mid-stream stall earns a corner chip, and only after a grace period —
    // tuning has its own card, and sub-second hiccups deserve nothing.
    var showBufferingChip by remember { mutableStateOf(false) }
    LaunchedEffect(session.buffering, session.tuning) {
        if (session.buffering && !session.tuning) {
            delay(PlayerMotion.BufferGraceMs)
            showBufferingChip = true
        } else {
            showBufferingChip = false
        }
    }

    // BACK closes whatever is open, and from bare playback it leaves. One
    // meaning, and the same one every time.
    //
    // It used to open the channel list on live TV instead, then need a second
    // press to arm an exit and a third to take it — three presses to get back
    // to the screen you came from, and a prompt explaining the middle one.
    // That existed because BACK opening the channel list would otherwise
    // toggle against bare playback forever with no way out. OK owns the
    // channel list now, so none of it is load-bearing: BACK can just go back.
    BackHandler {
        // Digits mid-entry: BACK cancels the number, nothing else moves.
        if (session.digitBuffer.isNotEmpty() || noChannelNumber != null) {
            session.digitBuffer = ""
            noChannelNumber = null
            return@BackHandler
        }
        when (session.layer) {
            PlayerLayer.Guide, PlayerLayer.ChannelList, PlayerLayer.Tracks,
            PlayerLayer.Catchup, PlayerLayer.Options, PlayerLayer.Controls ->
                // closePanel, not None: with an error pending, dropping to bare
                // video would strand a black screen with no chrome.
                session.closePanel()
            PlayerLayer.Error, PlayerLayer.None -> onExit()
        }
    }

    // When the chrome hides, its focused button leaves the composition and
    // focus would be lost — park it on the root so D-pad events keep arriving.
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(session.layer) {
        if (session.layer == PlayerLayer.None) {
            runCatching { rootFocus.requestFocus() }
        }
    }

    // Shared by the tracks sheet's chips and the options menu's cycle row.
    fun applyAspect(mode: Int) {
        session.scaleMode = mode
        engine.setScaleMode(mode)
        // Live remembers aspect per channel; VOD sets the default.
        val url = if (request.isLive) item?.url else null
        scope.launch {
            if (url != null) prefs.setAspectOverride(url, mode)
            else prefs.setAspectMode(mode)
        }
    }

    fun setSleepMinutes(minutes: Int) {
        session.sleepChoiceMinutes = minutes
        session.sleepDeadlineMs =
            if (minutes == 0) 0L else System.currentTimeMillis() + minutes * 60_000L
    }

    fun toggleRecording() {
        if (activeRecording != null) vm.stopRecording()
        else item?.let { vm.startRecording(it) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PlayerTheme.VideoCanvas)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                val result = playerKeyAction(
                    code = event.key.nativeKeyCode,
                    isKeyDown = event.type == KeyEventType.KeyDown,
                    isKeyUp = event.type == KeyEventType.KeyUp,
                    repeatCount = event.nativeKeyEvent.repeatCount,
                    layer = session.layer,
                    isLive = request.isLive,
                    hasMultipleItems = request.items.size > 1,
                    hasPreviousChannel = session.previousIndex >= 0,
                    bannerVisible = session.bannerVisible,
                    centerArmed = session.centerArmed,
                    centerLongPressFired = session.centerLongPressFired,
                    digitsPending = session.digitBuffer.isNotEmpty(),
                )
                when (val action = result.action) {
                    PlayerKeyAction.CenterArm -> session.centerArmed = true
                    PlayerKeyAction.CenterLongPress -> {
                        session.centerLongPressFired = true
                        session.layer =
                            if (request.isLive) PlayerLayer.Options else PlayerLayer.Tracks
                    }
                    PlayerKeyAction.CenterRelease -> {
                        session.centerArmed = false
                        session.centerLongPressFired = false
                    }
                    is PlayerKeyAction.Zap -> session.zap(action.delta)
                    PlayerKeyAction.ShowBanner -> session.bannerTick++
                    PlayerKeyAction.PlayPause -> {
                        session.togglePlayPause()
                        session.poke()
                    }
                    PlayerKeyAction.LastChannel -> session.jumpTo(session.previousIndex)
                    PlayerKeyAction.ToggleGuide ->
                        // Layers are exclusive, so raising the guide inherently
                        // closes every other panel — the guide would otherwise
                        // open underneath an overlay drawn later in the Box and
                        // take focus into a grid the viewer can't see.
                        session.layer =
                            if (session.layer == PlayerLayer.Guide) PlayerLayer.None
                            else PlayerLayer.Guide
                    PlayerKeyAction.OpenChannelList -> {
                        session.centerArmed = false
                        session.layer = PlayerLayer.ChannelList
                    }
                    PlayerKeyAction.OpenOptions -> session.layer = PlayerLayer.Options
                    PlayerKeyAction.OpenTracks -> session.layer = PlayerLayer.Tracks
                    PlayerKeyAction.ShowControls -> {
                        session.centerArmed = false
                        session.poke()
                    }
                    is PlayerKeyAction.Seek -> {
                        engine.seekTo(engine.positionMs + action.deltaMs)
                        session.positionMs = engine.positionMs
                        session.durationMs = engine.durationMs
                        seekFlashTick++
                    }
                    is PlayerKeyAction.Digit ->
                        if (session.digitBuffer.length < 4) {
                            session.digitBuffer += action.digit.toString()
                        }
                    PlayerKeyAction.CommitDigits -> commitDigits()
                    null -> Unit
                }
                result.consumed
            }
    ) {
        // key() forces a fresh surface when the engine is swapped — AndroidView's
        // factory runs once per node, so without this the new engine would render
        // into a view that was already released (black screen after fallback).
        //
        // While the grid guide is open the same view shrinks into the top-left
        // slot PlayerGuideOverlay reserves — video and audio keep going, the
        // guide fills the rest. A scrim over fullscreen video was tried first
        // and read as "playback stopped"; broadcast guides embed the picture.
        val guideVideoInset = session.layer == PlayerLayer.Guide && request.isLive && !inPip
        androidx.compose.runtime.key(session.engineChoice) {
            AndroidView(
                modifier = if (guideVideoInset) {
                    Modifier
                        .padding(start = PLAYER_GUIDE_PADDING, top = PLAYER_GUIDE_TOP_PADDING)
                        .size(PLAYER_GUIDE_VIDEO_WIDTH, PLAYER_GUIDE_VIDEO_HEIGHT)
                } else {
                    Modifier.fillMaxSize()
                },
                factory = { engine.createView(it) },
            )
        }

        // Tuning shows who we're tuning to, not an anonymous spinner — fading
        // in over the last frame so a zap never black-flashes.
        AnimatedVisibility(
            visible = session.tuning && session.errorMessage == null && !inPip &&
                session.layer != PlayerLayer.Guide,
            enter = PlayerMotion.enterFade(),
            exit = PlayerMotion.exitFade(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            TuneCard(channel = channel, item = item)
        }

        // Paused with no chrome up: say so, or a dark still frame reads as a
        // hang. Covers the sleep timer's pause too.
        AnimatedVisibility(
            visible = !session.playing && !session.buffering && !session.tuning &&
                session.layer == PlayerLayer.None && session.errorMessage == null && !inPip,
            enter = PlayerMotion.enterFade(PlayerMotion.FastMs),
            exit = PlayerMotion.exitFade(PlayerMotion.FastMs),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(NuxColors.Scrim)
                    .padding(18.dp),
            ) {
                Icon(
                    Icons.Default.Pause,
                    contentDescription = "Paused",
                    tint = NuxColors.OnSurface,
                    modifier = Modifier.size(56.dp),
                )
            }
        }

        // The VOD title header — live names itself through the banner.
        AnimatedVisibility(
            visible = session.layer == PlayerLayer.Controls && !inPip && isVod &&
                !item?.title.isNullOrBlank(),
            enter = PlayerMotion.enterFromTop(),
            exit = PlayerMotion.exitToTop(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            VodTitleHeader(
                title = item?.title.orEmpty(),
                subtitle = item?.subtitle,
                resolution = session.videoSize,
                hdrFormat = session.hdrFormat,
                audioFormatLabel = session.audioFormatLabel,
            )
        }

        // Banner + transport in one bottom column over one gradient: their
        // stacking is layout, not a hardcoded lift, and the gradient never
        // doubles up when both are visible.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(PlayerTheme.BottomGradient),
        ) {
            // The banner stays up for as long as the controls do on live —
            // the controls carry no title there, so the banner is the only
            // thing naming what is playing, and its own timer would otherwise
            // retire it out from under an open control bar.
            AnimatedVisibility(
                visible = (session.bannerVisible ||
                    (session.layer == PlayerLayer.Controls && request.isLive)) &&
                    (session.layer == PlayerLayer.None || session.layer == PlayerLayer.Controls) &&
                    !inPip,
                enter = PlayerMotion.enterFromBottom(),
                exit = PlayerMotion.exitToBottom(),
            ) {
                ChannelBanner(
                    vm = vm,
                    item = item,
                    channel = channel,
                    isLive = request.isLive,
                    resolution = session.videoSize,
                    hdrFormat = session.hdrFormat,
                    audioFormatLabel = session.audioFormatLabel,
                    isRecording = activeRecording != null,
                    // Only while the controls are down: with them open the
                    // viewer has already found the thing the hint points at.
                    showKeyHints = request.isLive && hintsVersionSeen < KEY_HINTS_VERSION &&
                        session.bannerShows <= 3 && session.layer != PlayerLayer.Controls,
                )
            }

            AnimatedVisibility(
                visible = session.layer == PlayerLayer.Controls && !inPip,
                enter = PlayerMotion.enterFromBottom(),
                exit = PlayerMotion.exitToBottom(),
            ) {
                PlayerControls(
                    isLive = request.isLive,
                    playing = session.playing,
                    positionMs = session.positionMs,
                    durationMs = session.durationMs,
                    hasPlaylist = request.items.size > 1,
                    canPip = pipSupported,
                    onPlayPause = { session.togglePlayPause(); session.poke() },
                    onSeekBy = { delta -> engine.seekTo(engine.positionMs + delta); session.poke() },
                    onPrevious = { engine.previous(); session.poke() },
                    onNext = { engine.next(); session.poke() },
                    onChannels = { session.layer = PlayerLayer.ChannelList },
                    onGuide = { session.layer = PlayerLayer.Guide },
                    onOptions = {
                        session.layer =
                            if (request.isLive) PlayerLayer.Options else PlayerLayer.Tracks
                    },
                    onPip = {
                        (context as? android.app.Activity)?.let { activity ->
                            if (android.os.Build.VERSION.SDK_INT >= 26) {
                                // Only the call's own verdict flips the flag; the
                                // mode-changed callback stays the source of truth.
                                val entered = runCatching {
                                    activity.enterPictureInPictureMode(
                                        buildPipParams(session.videoSize, autoEnter = true)
                                    )
                                }.getOrDefault(false)
                                if (entered) inPip = true
                            }
                        }
                    },
                    onInteraction = { session.poke() },
                )
            }
        }

        // Bare VOD seeking: a readout, not a control surface.
        AnimatedVisibility(
            visible = seekFlashVisible && session.layer == PlayerLayer.None && !inPip,
            enter = PlayerMotion.enterFromBottom(PlayerMotion.FastMs),
            exit = PlayerMotion.exitToBottom(PlayerMotion.FastMs),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            SeekFlash(positionMs = session.positionMs, durationMs = session.durationMs)
        }

        // The error card keeps its last message so the exit animation doesn't
        // run on an empty card.
        var lastError by remember { mutableStateOf("") }
        session.errorMessage?.let { lastError = it }
        AnimatedVisibility(
            visible = session.layer == PlayerLayer.Error && !inPip,
            enter = PlayerMotion.enterScale(),
            exit = PlayerMotion.exitScale(),
        ) {
            PlaybackErrorCard(
                title = item?.title.orEmpty(),
                message = lastError,
                canSwapEngine = true,
                hasNext = request.items.size > 1,
                onRetry = { session.retryAfterError() },
                onSwapEngine = { session.swapEngineAfterError() },
                onNext = { session.zap(+1) },
                onBack = onExit,
            )
        }

        AnimatedVisibility(
            visible = session.layer == PlayerLayer.Guide && !inPip && request.isLive,
            enter = PlayerMotion.enterGuide(),
            exit = PlayerMotion.exitGuide(),
        ) {
            PlayerGuideOverlay(
                vm = vm,
                playingChannelId = channel?.id,
                // Tuning from the grid replaces the zap playlist, the same
                // contract as picking a category in the mini-guide.
                onTune = { channels, index ->
                    session.layer = PlayerLayer.None
                    session.previousIndex = -1 // the old playlist's index no longer means anything
                    session.positionMs = 0
                    vm.playChannels(channels, index)
                },
                onPlayCatchup = { catchupChannel, program, url ->
                    session.layer = PlayerLayer.None
                    session.positionMs = 0
                    vm.playCatchup(catchupChannel, program, url)
                },
                onStatus = { session.statusMessage = it },
                onDismiss = { session.closePanel() },
            )
        }

        AnimatedVisibility(
            visible = session.layer == PlayerLayer.ChannelList && !inPip,
            enter = PlayerMotion.enterFromLeft(),
            exit = PlayerMotion.exitToLeft(),
        ) {
            ChannelListPanel(
                vm = vm,
                items = request.items,
                currentIndex = session.currentIndex,
                onSelect = { index ->
                    session.layer = PlayerLayer.None
                    session.jumpTo(index)
                },
                // Picking from another category replaces the zap playlist, so
                // CH+/- then cycles that category rather than the old one.
                onSelectChannels = { channels, index ->
                    session.layer = PlayerLayer.None
                    session.previousIndex = -1 // the old playlist's index no longer means anything
                    session.positionMs = 0
                    vm.playChannels(channels, index)
                },
                onExitToHome = { session.layer = PlayerLayer.None; onExit() },
                onDismiss = { session.closePanel() },
            )
        }

        AnimatedVisibility(
            visible = session.layer == PlayerLayer.Tracks && !inPip,
            enter = PlayerMotion.enterFromRight(),
            exit = PlayerMotion.exitToRight(),
        ) {
            TracksOverlay(
                engine = engine,
                isVod = !request.isLive,
                scaleMode = session.scaleMode,
                onScaleMode = { mode -> applyAspect(mode) },
                speed = session.speed,
                onSpeed = { sp ->
                    session.speed = sp
                    engine.setSpeed(sp)
                    scope.launch { prefs.setVodSpeed(sp) }
                },
                sleepMinutes = session.sleepChoiceMinutes,
                onSleep = { minutes -> setSleepMinutes(minutes) },
                onAudioSelected = { track ->
                    scope.launch { prefs.setPreferredAudioLanguage(track.language) }
                },
                onSubtitleSelected = { track ->
                    scope.launch { prefs.setPreferredSubtitleLanguage(track?.language) }
                },
                // Auto vs. the top rung is the one video choice worth carrying
                // to the next channel; a specific rung belongs to this stream.
                onVideoQuality = { mode -> scope.launch { prefs.setVideoQuality(mode) } },
                onDismiss = { session.closePanel() },
            )
        }

        AnimatedVisibility(
            visible = session.layer == PlayerLayer.Catchup && channel != null && !inPip,
            enter = PlayerMotion.enterFromRight(),
            exit = PlayerMotion.exitToRight(),
        ) {
            channel?.let { catchupChannel ->
                CatchupOverlay(
                    vm = vm,
                    channel = catchupChannel,
                    onDismiss = { session.closePanel() },
                    onPlay = { program, url ->
                        session.layer = PlayerLayer.None
                        session.positionMs = 0
                        vm.playCatchup(catchupChannel, program, url)
                    },
                )
            }
        }

        AnimatedVisibility(
            visible = session.layer == PlayerLayer.Options && !inPip,
            enter = PlayerMotion.enterFromRight(),
            exit = PlayerMotion.exitToRight(),
        ) {
            ChannelOptionsMenu(
                channelName = channel?.displayName ?: item?.title.orEmpty(),
                isFavoritable = request.isLive && channel != null,
                isFavorite = channel != null && channel.url in favorites,
                canRecord = request.isLive && item?.recordUrl != null,
                isRecording = activeRecording != null,
                hasCatchup = request.isLive && (channel?.archiveDays ?: 0) > 0,
                aspectLabel = ASPECT_LABELS.getOrElse(session.scaleMode) { ASPECT_LABELS[0] },
                engineName = engine.name,
                sleepLabel = if (session.sleepChoiceMinutes == 0) "Off"
                else "${session.sleepChoiceMinutes}m",
                canHide = request.isLive && channel != null,
                onFavoriteToggle = { channel?.let { vm.toggleFavorite(it) } },
                onRecordToggle = { toggleRecording() },
                onCatchup = { session.layer = PlayerLayer.Catchup },
                onTracks = { session.layer = PlayerLayer.Tracks },
                onAspectCycle = { applyAspect((session.scaleMode + 1) % ASPECT_LABELS.size) },
                onEngineSwap = { session.swapEngine() },
                onSleepCycle = {
                    val index = SLEEP_CHOICES.indexOf(session.sleepChoiceMinutes)
                        .coerceAtLeast(0)
                    setSleepMinutes(SLEEP_CHOICES[(index + 1) % SLEEP_CHOICES.size])
                },
                onHide = {
                    channel?.let { vm.toggleHidden(it) }
                    session.statusMessage = "Channel hidden — manage in Settings"
                    session.layer = PlayerLayer.None
                },
                onDismiss = { session.closePanel() },
            )
        }

        // Top status chips: REC + transient messages + errors. Composed after
        // the overlays, deliberately: "Recording scheduled" and "Catch-up
        // isn't available" are fired from inside the grid guide, and drawn
        // earlier they would sit under its scrim and auto-clear unseen.
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(24.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val rec = activeRecording
            if (rec != null) {
                PlayerBadge(
                    text = "REC ${rec.channelName} • ${rec.bytesWritten / (1024 * 1024)} MB",
                    color = NuxColors.Error,
                )
            }
            session.statusMessage?.let { PlayerBadge(text = it, color = NuxColors.Secondary) }
            AnimatedVisibility(
                visible = showBufferingChip,
                enter = PlayerMotion.enterFade(PlayerMotion.FastMs),
                exit = PlayerMotion.exitFade(PlayerMotion.FastMs),
            ) {
                PlayerBadge(text = "Buffering…", color = NuxColors.OnSurfaceDim)
            }
            // Digits large, in their own pill — read from the couch mid-type.
            // The dim state is the verdict on a number that matched nothing;
            // it self-dismisses, never an error card.
            if (session.digitBuffer.isNotEmpty()) {
                DigitEntryPill(text = session.digitBuffer)
            } else {
                noChannelNumber?.let { DigitEntryPill(text = "No channel $it", dim = true) }
            }
            if (!request.isLive && session.durationMs > 0 &&
                session.durationMs - session.positionMs in 1_000..15_000 &&
                session.currentIndex < request.items.size - 1
            ) {
                PlayerBadge(
                    text = "Up next: ${request.items[session.currentIndex + 1].subtitle
                        ?: request.items[session.currentIndex + 1].title}",
                    color = NuxColors.Primary,
                )
            }
            if (session.sleepDeadlineMs > 0) {
                val minutesLeft =
                    ((session.sleepDeadlineMs - sleepNowMs + 59_999) / 60_000L).coerceAtLeast(0)
                PlayerBadge(text = "Sleep in ${minutesLeft}m", color = NuxColors.OnSurfaceDim)
            }
        }
    }
}
