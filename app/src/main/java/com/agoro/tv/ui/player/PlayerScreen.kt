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
import androidx.compose.runtime.snapshotFlow
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
import com.agoro.tv.player.HdrType
import com.agoro.tv.player.WindowColorMode
import com.agoro.tv.player.findActivity
import com.agoro.tv.ui.components.requestFocusRetrying
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.agoro.tv.data.isFavorite

/** Bump when the key map changes so the banner hints re-teach once. */
private const val KEY_HINTS_VERSION = 3

/**
 * How long one ATTEMPT at a stream may stand before it is declared dead.
 *
 * Per attempt, not per tune. This was one wall clock across the whole cascade,
 * on the reasoning that it had to "clear the ladder's own 3s + 6s backoff" —
 * the VOD pair, and true when it was written. Live's ladder is 6s, 20s and 40s
 * now, and any of those can be a forty-five-second poll for a free slot
 * instead, so the clock expired in the middle of a recovery that was still
 * running: the error card appeared, the reconnect landed a beat later and
 * cleared it, and the screen went dark again. Restarting it on every open —
 * and holding it while a reconnect is pending — gives each try its own budget
 * and lets the ladder finish.
 *
 * What that gives up, stated plainly, because the old comment promised the
 * opposite: there is no longer one wall clock over a whole cascade. The bound
 * is now the ladder's own shape — every rung either fails fast and moves on,
 * or hangs and meets this within forty-five seconds — which on a channel that
 * fails fast at every rung is the reconnect backoffs, so about four minutes at
 * the very worst. That is deliberate. It is what the viewer asked for by
 * complaining that the card came up while the app was still recovering, it is
 * what TiviMate does, and it is not a hang: the card names the attempt
 * throughout, BACK leaves, and OK still opens the channel list.
 */
private const val TUNE_TIMEOUT_MS = 45_000L

/**
 * How long a stream's decoded size and frame rate must hold before the
 * display is asked to match them. Long enough that a zap-through never
 * switches — the next channel's first frame cancels the wait — and that an
 * adaptive ladder has climbed off its opening rung before the verdict.
 */
private const val DISPLAY_MODE_SETTLE_MS = 3_000L

/**
 * How long a stream must have been decoding before its tier is recorded.
 * The first reported height is the ladder's opening rung, not the channel's
 * quality; five seconds in, the selector has climbed to what the line can
 * actually carry.
 */
private const val QUALITY_LEARN_SETTLE_MS = 5_000L

/**
 * How long the next episode waits before it takes itself.
 *
 * Long enough to read the title and press BACK, short enough that a viewer
 * who wants it does not sit through a countdown they never asked for. Ten is
 * what the streaming services settled on and it is about right on a remote,
 * where declining costs one press and finding the remote costs the rest.
 */
private const val UP_NEXT_SECONDS = 10

/**
 * How long the end card sits there before the player closes itself.
 *
 * The offer's count, deliberately: from the viewer's side these are the same
 * moment — something has ended and the screen is about to do something about
 * it — and two different waits for one gesture is a difference nobody asked
 * for. BACK stops it, for the viewer who wants the credits.
 */
private const val FINISHED_SECONDS = 10

/**
 * How long before the end of an item the next one announces itself.
 *
 * A FRACTION of the runtime, not a fixed countdown. The card fired at the end
 * of the FILE once, and the report was that it arrived roughly five minutes
 * after the credits had started rolling — which is what the end of the file
 * means on this catalogue. These are rips of a broadcast slot: the credits, a
 * trailer for the next episode and a stretch of black or a station card all
 * sit inside the runtime, and the point the picture stops being the episode is
 * minutes before the point the file stops.
 *
 * Nothing here can see where that is — no chapter or credit markers, no way to
 * derive one — so the window is a guess, and the first guess was too generous.
 * An eighth of the runtime, up to six minutes, put the card on screen while an
 * hour-long episode was plainly still running: not a run-out, an interruption.
 * Halved, and capped in minutes rather than in a quarter of an hour — two and
 * a half at the top, which on these runtimes is about the last 5%, the same
 * place PlayerPrefs draws its own line between part-watched and finished.
 *
 * Early no longer costs only a corner card, either. The peek OWNS OK while it
 * is up ([PlayerKeyAction.PlayUpNext]), so a window that opens too soon takes
 * the controls key with it for the whole of it. That is what caps this tight,
 * and it is why BACK hides the card — see [PlayerSession.dismissUpNextPeek].
 *
 * The numbers are still a guess at ONE catalogue's shape and they are still
 * meant to be moved. If the card is late again, raise the cap; if it sits
 * through the last scene, lower it.
 */
private const val UP_NEXT_PEEK_FRACTION = 0.05
private const val UP_NEXT_PEEK_MIN_MS = 60_000L
private const val UP_NEXT_PEEK_MAX_MS = 150_000L

/** The window for an item of [durationMs]; see [UP_NEXT_PEEK_FRACTION]. */
private fun upNextPeekMs(durationMs: Long): Long =
    (durationMs * UP_NEXT_PEEK_FRACTION).toLong()
        .coerceIn(UP_NEXT_PEEK_MIN_MS, UP_NEXT_PEEK_MAX_MS)

/**
 * Below this, an item is too short to have a run-out worth announcing: the
 * card would be on screen for most of its length, and would hold OK there.
 * Ten minutes — the floor of the window above is already a tenth of an item
 * this short, and half of a four-minute clip.
 */
private const val UP_NEXT_MIN_ITEM_MS = 600_000L

/**
 * The item the run-out peek is offering, or null when the peek is not up.
 *
 * ONE definition, because two things now have to agree about it: the overlay
 * draws the card from this, and the key map binds OK from it. While the peek
 * took no keys they could not disagree; the moment it took one, a card on
 * screen that OK does not answer — or an OK that skips an episode with no card
 * up — is a bug rather than a drift.
 *
 * Deliberately a plain function and not a composable or a remembered state:
 * it is read from onPreviewKeyEvent, where a snapshot read subscribes to
 * nothing, and from the overlay's own scope, where it invalidates only that
 * scope. Hoisted to the top of PlayerScreen it would put [PlayerSession
 * .positionMs] — which ticks once a second — in the whole screen's recompose
 * scope, on the box with 2GB of RAM.
 */
private fun upNextPeekIndex(session: PlayerSession, inPip: Boolean): Int? {
    val request = session.request
    val next = session.currentIndex + 1
    val peeking = session.upNextIndex == null &&
        !session.upNextPeekDismissed &&
        !request.isLive && !request.isCatchup &&
        session.layer == PlayerLayer.None &&
        !inPip &&
        next < request.items.size &&
        session.durationMs >= UP_NEXT_MIN_ITEM_MS &&
        session.positionMs > 0 &&
        session.durationMs - session.positionMs in
            1_000..upNextPeekMs(session.durationMs)
    return next.takeIf { peeking }
}

/**
 * How long a channel has to stay tuned before it becomes the one a cold start
 * reopens on.
 *
 * currentIndex moves on every keypress of a zap chain, and this write is a
 * DataStore file rewrite that re-emits the whole Preferences object to every
 * collector in the app — fifteen of them for a walk down fifteen channels, on
 * the hot tuning path, on the weakest hardware. A channel left within a second
 * of arriving is not the one you were watching, and losing it costs a resume
 * onto the previous channel rather than anything the viewer would notice.
 */
private const val RESUME_MARK_DWELL_MS = 1_500L

/** How long a channel has to stay tuned to earn a place on the Recent shelf. */
private const val RECENT_SHELF_DWELL_MS = 8_000L

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
    val incoming = vm.playback
    if (incoming == null) {
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

    val qualityPref by vm.videoQuality.collectAsState()
    val favorites by vm.favorites.collectAsState()

    val session = remember {
        PlayerSession(
            context = context,
            scope = scope,
            initialRequest = incoming,
            onSaveResume = vm::saveResumePosition,
            awaitLiveSlot = vm::awaitFreeLiveSlot,
        )
    }
    // A replacement playlist re-primes the session the way the old screen's
    // remember(request) resets did — and returns the decode profile to the
    // fast one.
    remember(incoming) {
        session.onRequest(incoming)
        true
    }

    // The SESSION's request, not the ViewModel's, and this is not cosmetic.
    //
    // Two rungs of the failure ladder work by rewriting the url of the item
    // being played — swapLiveFormat steps through the Xtream forms, swapSource
    // moves to the next stream the manifest folded into this channel — and
    // they do it by patching session.request. Read from vm.playback, none of
    // that patching reached anything: the engine is prepared from whatever
    // this line points at, so both rungs set a status message and returned
    // true, the `when` stopped there, and nothing re-opened the stream. On a
    // live url of the shape those rungs match, that was the FIRST branch any
    // error hit — so the reconnect below it was never reached either, and the
    // picture simply stopped with a toast and no way forward. The ViewModel
    // stays the way a new playlist arrives (above); the session owns it after
    // that, and a patch re-keys the prepare effect the way a new request does.
    val request = session.request

    val item = request.items.getOrNull(session.currentIndex)
    val channel: LiveChannel? = item?.channelId?.let { vm.channelById(it) }
    val isVod = !request.isLive

    // Engine lives until something asks for a rebuild; see engineGeneration.
    val engine = remember(session.engineGeneration) {
        // A channel the app has seen decode at 4K OR in HDR tunnels from its
        // first frame; anything else starts on the ordinary path. HDR has to
        // count separately from the tier: an HLG feed at 1080p is ordinary in
        // IPTV, and a resolution tier can never record it — so without this
        // every HDR stream spent its opening seconds untunnelled and then
        // re-initialised the decoder in front of the viewer. See TunnelPolicy.
        session.createEngine(
            deservesTunnel = { url ->
                vm.knownTierOf(url) == "4K" || vm.knownHdrOf(url) != null
            },
        )
    }
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
        // A guide preview may have handed its connection back a second ago,
        // and a capped panel goes on counting that slot after the client has
        // dropped it. Opening now would be asking for a second connection the
        // line may not allow, and the stream refused would be the one the
        // viewer just chose. The connecting screen is already up (tuning went
        // true above), so the wait costs nothing the viewer can see. No-op
        // where no preview preceded this tune, and on any line with room.
        //
        // Catch-up counts: it carries isLive = false because it seeks like a
        // file, but it is served by the same panel and counted against the
        // same cap — and the guide plays it from the same preview.
        if (request.isLive || request.isCatchup) vm.awaitLiveSlotAfterHandover()
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
    //
    // The BEST height this tune reaches, not the first one it settles on.
    // A provider .ts stream has one quality and the two are the same answer,
    // which is why sampling once was right until the app started playing
    // adaptive HLS ladders (the broadcaster news feeds). A ladder opens on a
    // low rung and climbs as the bandwidth estimate fills in, so the single
    // sample recorded the OPENING rung and the badge kept it: ABC News Live
    // decodes 1080p and announced itself as SD, permanently, because five
    // seconds in it was still on the 540p rung.
    //
    // Upwards only, and still not on every decoded size. Keyed on videoSize
    // this ran on every adaptive rung change, and each run was a DataStore
    // decode, encode and rewrite that re-emitted the known-quality map,
    // re-sorted every channel list and recomposed this screen's channel
    // collector — a prefs write per bandwidth wobble, on the box that was
    // wobbling. Recording only an improvement bounds that at one write per
    // tier, four in the worst case, and a dip no longer demotes a channel:
    // the badge says what the stream can deliver, not what one bad minute
    // did. Tiers already recorded this visit, and tiers that merely confirm
    // what the name says, skip the write outright.
    val learnedTiers = remember { mutableMapOf<String, String>() }
    LaunchedEffect(session.tuneSerial) {
        if (!request.isLive) return@LaunchedEffect
        val url = request.items.getOrNull(session.currentIndex)?.url ?: return@LaunchedEffect
        snapshotFlow { session.videoSize?.second ?: 0 }.first { it > 0 }
        // The settle stays: it is what keeps a zap that lands for two seconds
        // from teaching the list anything, and a first record here means a
        // quick visit still learns something rather than nothing.
        delay(QUALITY_LEARN_SETTLE_MS)
        var best = 0
        snapshotFlow { session.videoSize?.second ?: 0 }.collect { height ->
            if (height <= best) return@collect
            best = height
            val tier = com.agoro.tv.data.QualityTag.tierOf(height) ?: return@collect
            if (learnedTiers[url] == tier || channel?.quality == tier) return@collect
            learnedTiers[url] = tier
            vm.recordDecodedQuality(url, height)
        }
    }

    // And which streams decode HDR, so the next visit opens straight onto the
    // tunnelled decoder. Learned separately from the tier above, which is
    // live-only because it feeds the channel lists — HDR matters just as much
    // on a film, and is keyed on a height that cannot express it. Recorded
    // once settled, SDR included, so a channel the provider has moved off HDR
    // stops claiming the tunnel.
    val learnedHdr = remember { mutableMapOf<String, HdrType?>() }
    LaunchedEffect(session.tuneSerial, engine) {
        val url = request.items.getOrNull(session.currentIndex)?.url ?: return@LaunchedEffect
        snapshotFlow { session.videoSize?.second ?: 0 }.first { it > 0 }
        delay(QUALITY_LEARN_SETTLE_MS)
        val hdr = engine.hdrType
        if (learnedHdr.containsKey(url) && learnedHdr[url] == hdr) return@LaunchedEffect
        learnedHdr[url] = hdr
        vm.recordDecodedHdr(url, hdr)
    }

    // Ask the TV for a mode that suits the stream — the panel's own refresh
    // and, where the output is smaller than the picture, its resolution.
    // Skipped in PiP: the window is a thumbnail there, and a mode change to
    // suit it would blank the app the viewer is actually looking at.
    //
    // Only once the stream has held the same size and rate for a few
    // seconds: any change restarts the wait, so a zap-through never switches
    // and a flapping report never switches twice. Resolution is matched once
    // per tune — the first settled report — because a ladder climbing a rung
    // later is the same stream, and a re-sync then would black out the
    // picture mid-programme. And nothing on exit: the mode stays where the
    // stream left it. The reset that used to run here blanked Home for a
    // second every time the player closed, for the benefit of nobody.
    val displayModes = remember(context) {
        context.findActivity()?.let { DisplayModeSwitcher(it) }
    }
    var resolutionMatchedForTune by remember { mutableIntStateOf(-1) }
    LaunchedEffect(
        displayModes, session.tuneSerial, session.videoSize?.second, session.videoFrameRate,
        session.hdrType, inPip,
    ) {
        val switcher = displayModes ?: return@LaunchedEffect
        if (inPip) return@LaunchedEffect
        val height = session.videoSize?.second ?: 0
        val frameRate = session.videoFrameRate
        // Nothing has decoded yet: switching on a guess would blank the screen
        // over the tune, and be wrong as often as not.
        if (height <= 0 && frameRate == null) return@LaunchedEffect
        delay(DISPLAY_MODE_SETTLE_MS)
        val tune = session.tuneSerial
        runCatching {
            // Read from the engine, not from the polled copy: the poll drops
            // to a 5s cadence with the chrome down, and a mode chosen from a
            // stale "this is SDR" is exactly the pin that costs the viewer HDR.
            switcher.apply(
                height,
                frameRate,
                hdr = engine.hdrType,
                allowResolutionChange = resolutionMatchedForTune != tune,
            )
        }
        resolutionMatchedForTune = tune
    }

    // The other half of the same job: tell the window it is carrying HDR, for
    // the boxes that switch their output on the foreground window's declared
    // colour mode rather than on the decoder's buffers. Cleared on the way out
    // — unlike the display mode, this costs nothing to put back, and leaving
    // it set would make every SDR screen behind the player pay for it.
    val windowColor = remember(context) {
        context.findActivity()?.let { WindowColorMode(it) }
    }
    DisposableEffect(windowColor) {
        onDispose { windowColor?.set(false) }
    }
    LaunchedEffect(windowColor, session.hdrType, inPip) {
        windowColor?.set(session.hdrType != null && !inPip)
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
    //
    // Not while a zap chain is running — this and the language lookup below
    // are DataStore reads (the aspect one decodes a JSON map), and keyed on
    // the index alone they ran once per channel skimmed. They wait for the
    // run to rest, which is the only channel whose settings matter.
    LaunchedEffect(engine, session.currentIndex, request, session.pendingTuneIndex) {
        if (session.pendingTuneIndex != null) return@LaunchedEffect
        val url = request.items.getOrNull(session.currentIndex)?.url ?: return@LaunchedEffect
        val mode = prefs.aspectModeFor(url)
        if (mode != session.scaleMode) {
            session.scaleMode = mode
            engine.setScaleMode(mode)
        }
    }

    // Preferred audio/subtitle language, applied once per item as soon as the
    // stream announces its tracks (they appear a beat after it opens).
    LaunchedEffect(engine, session.currentIndex, session.pendingTuneIndex) {
        if (session.pendingTuneIndex != null) return@LaunchedEffect
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
    // Up for the whole scrub, and for a moment after it lands so the viewer
    // sees WHERE it landed rather than the bar vanishing on commit.
    var seekFlashVisible by remember { mutableStateOf(false) }
    LaunchedEffect(session.seekTargetMs) {
        if (session.seekTargetMs != null) {
            seekFlashVisible = true
            return@LaunchedEffect
        }
        if (!seekFlashVisible) return@LaunchedEffect
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
            // Not while a reconnect has the engine stopped. A stopped player
            // reports no video format, so this wrote null over the decoded
            // size, frame rate and HDR flavour of the stream that is coming
            // back — and the HDR one drives the window's colour mode and the
            // display's output mode, so every reconnect became two extra mode
            // changes and the screen blanks they cost. A re-tune that really
            // does change stream clears these itself; see zap and jumpTo.
            if (session.reconnectAttempt == 0) {
                session.videoSize = engine.videoResolution
                session.videoFrameRate = engine.videoFrameRate
                session.hdrType = engine.hdrType
                session.audioFormatLabel = engine.audioFormatLabel
            }
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
        if (!request.isLive) {
            // And a film, a catch-up programme or a recording forgets whatever
            // channel was remembered. Playing one is the viewer choosing to sit
            // down for it; starting it again unbidden because the box woke up
            // would be interrupting rather than resuming. Only live comes back
            // on by itself — see PlayerPrefs.resumeLiveChannel.
            vm.rememberLiveResume(null)
            return@LaunchedEffect
        }
        val url = request.items.getOrNull(session.currentIndex)?.url ?: return@LaunchedEffect
        // Immediately, for the guide's return landing; the dwell below is
        // only for the Recent shelf.
        vm.noteTuned(url)
        // Both of the below are dwells on the same effect, which collectLatest
        // -style cancels on the next index change: a zap chain writes nothing
        // until it settles. The resume mark comes first and much sooner — it
        // asks only what is on, where the shelf asks what earns a place in a
        // list.
        delay(RESUME_MARK_DWELL_MS)
        vm.rememberLiveResume(url)
        delay(RECENT_SHELF_DWELL_MS - RESUME_MARK_DWELL_MS)
        vm.recordChannelVisit(url)
    }

    // Pause when the app leaves the foreground, unless we're in PiP —
    // otherwise audio keeps playing invisibly behind the launcher.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    // And come back playing. Live TV paused by the launcher has nothing to
    // resume "from" — the app re-joins the live edge (togglePlayPause's
    // 30-second rule) the way a television does when it is switched back
    // to, rather than showing a frozen frame and a Paused glyph with no
    // play control on a live remote. VOD stays paused: a film picks up
    // where the viewer chooses.
    DisposableEffect(lifecycleOwner, engine) {
        // Whether the app has actually been away, as opposed to this observer
        // being attached — addObserver replays ON_START for an owner that is
        // already started, and the resume below must not fire on a stream
        // that has simply not begun yet.
        //
        // It replaces a `pausedByLifecycle` flag that was only set when WE
        // paused a PLAYING engine, which is the narrower question and the
        // wrong one: a live stream that was stalled or buffering when the app
        // went away failed that test, so nothing resumed it on return — and
        // the death timer, which stands down while the app is in the
        // background (appForeground, below), had already been silenced. The
        // viewer came back to a black, silent screen with no card on it,
        // because the surface is cleared on reset (keepContentOnPlayerReset
        // is false) and nothing had put either the tune card or an error over
        // it. Leaving the player and coming back in was the only way out —
        // which is exactly what it re-does: it re-opens the stream.
        var wasBackgrounded = false
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    val pip = android.os.Build.VERSION.SDK_INT >= 24 &&
                        (context as? android.app.Activity)?.isInPictureInPictureMode == true
                    // A stalled engine is already not playing, so the pause
                    // below cannot speak for it — the death watchdog reads
                    // this instead rather than reconnecting into the launcher.
                    session.appForeground = pip
                    if (!pip) {
                        wasBackgrounded = true
                        if (engine.isPlaying) engine.playPause()
                    }
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    session.appForeground = true
                    // Live comes back playing however it stopped — the way a
                    // television does when the input is switched back to it.
                    // A re-open, not a resume: the stream may have been dead
                    // for the whole time the app was away, and playWhenReady
                    // on a closed socket puts nothing on screen. VOD is left
                    // alone; a film picks up where the viewer chooses.
                    if (wasBackgrounded && request.isLive && !engine.isPlaying) {
                        session.rejoinLive()
                    }
                    wasBackgrounded = false
                }
                else -> Unit
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

    // The zap dwell, for CHAINS only — a single press has already tuned by
    // the time this sees it. Identity updates were instant; the stream opens
    // only once the run rests, so skimming doesn't open a connection per press.
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
    // One budget per ATTEMPT, and never spent against the ladder. Keyed on the
    // channel alone this was a single clock across a whole cascade, which was
    // right when the ladder was two quick backoffs and wrong now that a live
    // one can legitimately spend a minute and a half: it fired mid-recovery,
    // put the error card up, and the reconnect that landed a beat later
    // cleared the card from under the viewer. attemptSerial restarts it on
    // every open and reconnectPending holds it while a backoff is in flight,
    // so what is left for it to catch is the case it was built for — an
    // attempt that is genuinely doing nothing. Nobody is stranded meanwhile:
    // the reconnect card says which try this is, and BACK works throughout.
    // A RECONNECT counts as an attempt here too, and has to: mid-stream, the
    // session is not tuning, and the death watchdog that would otherwise cover
    // it arms only for live and catch-up. A film that dropped and re-opened
    // into a stream that buffers for ever therefore had nothing watching it at
    // all — and now that a reconnect puts a card on screen promising progress,
    // that card would have stood there for as long as the viewer let it.
    LaunchedEffect(
        session.tuning,
        session.currentIndex,
        session.attemptSerial,
        session.reconnectAttempt,
    ) {
        if (!session.tuning && session.reconnectAttempt == 0) return@LaunchedEffect
        delay(TUNE_TIMEOUT_MS)
        // A ladder still working is not a hang, and the next open bumps
        // attemptSerial and re-arms this with a fresh budget — so holding off
        // here leaves nothing unwatched.
        if ((session.tuning || session.reconnectAttempt > 0) &&
            session.errorMessage == null && !session.reconnectPending
        ) {
            session.failTuning(
                if (session.tuning) "The stream didn't start." else "The stream didn't come back."
            )
        }
    }

    // A mid-stream stall earns a corner chip, and only after a grace period:
    // tuning has its own card, and sub-second hiccups deserve nothing.
    //
    // Films too, and later than live. The chip was live-only for a while, on
    // the reasoning that a film refills often and naming each refill made an
    // ordinary pause look like a failure. Those refills are the complaint
    // now, and hiding them made it worse: a film that stops with nothing on
    // screen reads as a broken stream, and a viewer cannot tell a refill from
    // a crash. Netflix shows the wait on a film as plainly as on anything
    // else. The grace is longer on a film — a refill that clears in under a
    // second and a half is the buffer doing its job and is still shown
    // nothing — and the log records what each one was, so the next report
    // names a cause rather than a symptom.
    //
    // No "Buffering…" chip. It sat in the top corner naming a condition the
    // viewer could already see, and it was the only thing on screen that
    // spoke in the app's own vocabulary rather than about their television.
    // A short refill now shows nothing, which is what a short refill is
    // worth; a stall that turns into a fault still gets the tune card and
    // then the error card, in words about the channel.

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
        // A scrub in flight is the smallest open thing, so it is the first
        // BACK undoes — and the only way out of an overshoot that does not
        // involve steering all the way back. Nothing has moved yet, so this
        // costs the viewer nothing but the pressing they just did.
        if (session.cancelSeek()) return@BackHandler
        when (session.layer) {
            PlayerLayer.Guide, PlayerLayer.ChannelList, PlayerLayer.Tracks,
            PlayerLayer.Catchup, PlayerLayer.Options, PlayerLayer.Controls ->
                // closePanel, not None: with an error pending, dropping to bare
                // video would strand a black screen with no chrome.
                session.closePanel()
            // BACK on the offer declines it and stays on the frame the
            // episode ended on, rather than leaving the player. Leaving is
            // still one more BACK away, which is the same two presses it
            // would have been; declining first is the one nobody can undo.
            PlayerLayer.UpNext -> session.dismissUpNext()
            // BACK on the end card stops the count and stays on the last
            // frame — for the credits, the end song, the after-scene. Leaving
            // is the next press, which is where the count was going anyway.
            PlayerLayer.Finished -> session.dismissFinished()
            // The peek takes OK while it is up, so it has to answer BACK too:
            // a card that binds the select key and cannot be got rid of is one
            // the viewer is stuck under for the rest of the episode. Hiding it
            // hands both keys back — OK opens the controls again, the next
            // BACK leaves. This declines the heads-up, not the episode: the
            // offer's own count still runs when the file ends.
            PlayerLayer.Error, PlayerLayer.None ->
                if (upNextPeekIndex(session, inPip) != null) session.dismissUpNextPeek()
                else onExit()
        }
    }

    // When the chrome hides, its focused button leaves the composition and
    // focus would be lost — park it on the root so D-pad events keep arriving.
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(session.layer) {
        // The two cards count as bare: neither draws a focusable control, and
        // both can REPLACE the transport bar — an item ends while its chrome
        // is up and the focused button leaves the composition under a card
        // whose whole purpose is to answer OK. So they park focus too.
        if (session.layer == PlayerLayer.None || session.layer == PlayerLayer.UpNext ||
            session.layer == PlayerLayer.Finished
        ) {
            // Retried on the Boolean: this runs exactly as a closing overlay's
            // focused control leaves composition, and a refusal here — the
            // one single shot left in the player — was "remote dead on bare
            // video".
            rootFocus.requestFocusRetrying()
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
                    playing = session.playing,
                    upNextPeeking = upNextPeekIndex(session, inPip) != null,
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
                    PlayerKeyAction.PlayUpNext -> {
                        // The press that took the episode may have armed
                        // first: the episode can END between a KeyDown and its
                        // KeyUp, and the offer takes the release either way.
                        // Left set, that arm makes the NEXT press of OK look
                        // like the release of this one.
                        session.centerArmed = false
                        session.centerLongPressFired = false
                        session.playUpNext()
                    }
                    PlayerKeyAction.LeaveFinished -> {
                        // The same arm to clear as the offer above: the item
                        // can end between a KeyDown and its KeyUp.
                        session.centerArmed = false
                        session.centerLongPressFired = false
                        onExit()
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
                    // The sign is the instruction; the SIZE comes from the
                    // ramp in SeekRamp, which grows as a scrub goes on. The
                    // seek itself waits for the viewer to stop pressing.
                    is PlayerKeyAction.Seek ->
                        session.nudgeSeek(if (action.deltaMs < 0) -1 else +1)
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
        // key() forces a fresh surface when the engine is rebuilt — AndroidView's
        // factory runs once per node, so without this the new engine would render
        // into a view that was already released (black screen after fallback).
        //
        // While the grid guide is open the same view shrinks into the top-left
        // slot PlayerGuideOverlay reserves — video and audio keep going, the
        // guide fills the rest. A scrim over fullscreen video was tried first
        // and read as "playback stopped"; broadcast guides embed the picture.
        val guideVideoInset = session.layer == PlayerLayer.Guide && request.isLive && !inPip
        androidx.compose.runtime.key(session.engineGeneration) {
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

        // A reconnect is the ladder trying again, and from the viewer's side
        // that is a tune: the same card, with a line saying which try this is.
        // Before this the screen showed nothing at all for the whole backoff —
        // see PlayerSession.reconnectAttempt.
        val reconnecting = session.reconnectAttempt > 0
        // One rule, two readers: the backdrop and the card. They were two
        // hand-kept copies of the same four terms, and the backdrop without
        // the card — or the other way round — is a visible defect.
        val showTuneUi = (session.tuning || reconnecting) &&
            session.errorMessage == null && !inPip && session.layer != PlayerLayer.Guide

        // The connecting screen every tune opens on — a soft glow over the
        // dark canvas — so opening a stream is never a flat black void while
        // the engine builds. Every re-tune clears the surface to a black
        // shutter (keepContentOnPlayerReset is false), so there is never a
        // last frame to preserve: a fresh open from a poster or a fixture, a
        // pick from the channel-list panel, and a zap all land here. Present
        // from the first frame (no enter fade) so there is no black beat
        // before it, and held across a zap chain because tuning stays true
        // throughout; it fades out as the picture lands.
        AnimatedVisibility(
            visible = showTuneUi,
            enter = androidx.compose.animation.EnterTransition.None,
            exit = PlayerMotion.exitFade(),
            modifier = Modifier.fillMaxSize(),
        ) {
            TuningBackdrop()
        }

        // Tuning shows who we're tuning to, not an anonymous spinner — fading
        // in over the last frame so a zap never black-flashes.
        AnimatedVisibility(
            visible = showTuneUi,
            enter = PlayerMotion.enterFade(),
            exit = PlayerMotion.exitFade(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            TuneCard(
                channel = channel,
                item = item,
                // The same card, because it is the same thing from where the
                // viewer sits — the channel they asked for, coming up — and a
                // second card would only be another way of waiting.
                note = if (reconnecting) {
                    "Reconnecting… (${session.reconnectAttempt} of ${session.reconnectTotal})"
                } else null,
            )
        }

        // The next episode: announced in the corner while this one runs out,
        // and the same card counting down once it has ended. Bottom end,
        // where every streaming app puts it and where it covers the least —
        // the top right is the status corner and the centre is the picture.
        //
        // The peek is suppressed while the transport chrome is up (it is part
        // of [upNextPeekIndex]). A viewer who has opened the controls in the
        // last minutes is doing something with this episode, and the card
        // would sit on the bar they opened — and would take the OK they are
        // using to work it. The countdown is never suppressed: by then the
        // episode is over and it is the only thing on screen.
        run {
            val counting = session.upNextIndex
            val index = counting ?: upNextPeekIndex(session, inPip)
            val next = index?.let { request.items.getOrNull(it) }
            if (next != null) {
                val secondsLeft = if (counting != null) {
                    var left by remember(counting) { mutableIntStateOf(UP_NEXT_SECONDS) }
                    LaunchedEffect(counting) {
                        // Ticks on its own clock and plays at zero. Keyed on
                        // the index so a viewer who declines and is later
                        // offered a different episode gets a fresh count, not
                        // the remains of the last one.
                        while (left > 0) {
                            delay(1_000)
                            left--
                        }
                        session.playUpNext()
                    }
                    left
                } else null
                UpNextCard(
                    // The episode's name leads; the series and the address
                    // are the line under it. A queue item that is not an
                    // episode has neither, so it leads on its own title.
                    heading = next.episodeName ?: next.subtitle ?: next.title,
                    meta = if (next.episodeName != null) {
                        listOfNotNull(
                            next.subtitle?.substringBefore(" • "),
                            next.title,
                        ).joinToString("  ·  ")
                    } else next.title,
                    artwork = next.artwork,
                    secondsLeft = secondsLeft,
                    countdownFraction =
                        (secondsLeft ?: 0).toFloat() / UP_NEXT_SECONDS,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        // The TV-safe margin, not a number that looked
                        // right. The player is the one screen composed
                        // OUTSIDE TvSafe — it is full-bleed by design,
                        // because the picture is — so anything laid into a
                        // corner of it has to hold its own overscan inset.
                        // At the 36dp this had, the card's trailing edge sat
                        // inside the nominal 5% crop, and a set that crops
                        // would have taken the seconds off the end of it.
                        .padding(
                            horizontal = Space.gutter,
                            vertical = Space.gutterVertical,
                        ),
                )
            }
        }

        // And the end of the list, in the same corner: nothing is queued and
        // nothing is playing. The card names what finished and counts down to
        // closing the player, which is the thing that never happened — the
        // engine ended, the session did nothing, and the viewer was left on
        // the last frame of the credits under a pause glyph.
        //
        // Not in PiP: the window is a thumbnail with no room for a card and
        // no keys to answer it, and closing the player out from under someone
        // who is doing something else is not a thing to do while they cannot
        // see it. The layer keeps, so the card is waiting when they come back.
        run {
            val done = item.takeIf { session.layer == PlayerLayer.Finished && !inPip }
            if (done != null) {
                var left by remember(done.url) { mutableIntStateOf(FINISHED_SECONDS) }
                LaunchedEffect(done.url) {
                    while (left > 0) {
                        delay(1_000)
                        left--
                    }
                    onExit()
                }
                FinishedCard(
                    // The episode's own name leads, exactly as it does on the
                    // offer; a film and a catch-up recording have only their
                    // title, and lead on that.
                    heading = done.episodeName ?: done.title,
                    meta = if (done.episodeName != null) {
                        listOfNotNull(
                            done.subtitle?.substringBefore(" • "),
                            done.title,
                        ).joinToString("  ·  ")
                    } else done.subtitle ?: done.title,
                    artwork = done.artwork,
                    // Where OK actually lands: the series page for an
                    // episode, and for anything else the page it was played
                    // from, which is not worth naming wrongly.
                    action = if (done.episodeName != null) "Back to the show" else "Back",
                    secondsLeft = left,
                    countdownFraction = left.toFloat() / FINISHED_SECONDS,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            horizontal = Space.gutter,
                            vertical = Space.gutterVertical,
                        ),
                )
            }
        }

        // Paused with no chrome up: say so, or a dark still frame reads as a
        // hang. Covers the sleep timer's pause too.
        AnimatedVisibility(
            // Not during a reconnect. An idled player reports exactly the
            // state this asks for — not playing, not buffering, not tuning —
            // so the pause glyph was what the viewer got for the whole of a
            // dropped stream's recovery, which is a lie about who stopped it.
            // And never for something that has ENDED. That state is
            // identical to a pause from here — this is the glyph that sat on
            // the last frame of every finale, film and catch-up recording,
            // saying the viewer had stopped it. See [PlayerSession.ended]; it
            // stays set after the end card is dismissed, so the frame the
            // viewer chose to sit on stays clean.
            visible = !session.playing && !session.buffering && !session.tuning &&
                !reconnecting && !session.ended &&
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
                    // Only while the controls are down: with them open the
                    // viewer has already found the thing the hint points at.
                    showKeyHints = request.isLive && hintsVersionSeen < KEY_HINTS_VERSION &&
                        session.bannerShows <= 3 && session.layer != PlayerLayer.Controls,
                    logoDeferred = session.pendingTuneIndex != null,
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
                    // What the decoder made of the stream, at the end of the
                    // button row — the one place it is drawn now.
                    resolution = session.videoSize,
                    hdrFormat = session.hdrType?.label,
                    audioFormatLabel = session.audioFormatLabel,
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
            // The TARGET while a scrub is in flight, the landed position once
            // it commits. Showing the live position during a scrub would sit
            // still while the viewer pressed, which reads as the remote not
            // working — the whole reason the number moves is to say it heard.
            SeekFlash(
                positionMs = session.seekTargetMs ?: session.positionMs,
                durationMs = session.durationMs,
                deltaMs = session.seekTargetMs?.let { it - session.seekAnchorMs },
            )
        }

        // The error card keeps its last message so the exit animation doesn't
        // run on an empty card. Its scrim fades on its own, under the card:
        // inside the scale-and-fade it made the animated layer screen-sized.
        var lastError by remember { mutableStateOf("") }
        // The shape of the card is held with its words: both are cleared when
        // the error goes, and the card is still fading out then.
        var lastEnded by remember { mutableStateOf(false) }
        session.errorMessage?.let { lastError = it; lastEnded = session.errorEnded }
        val errorUp = session.layer == PlayerLayer.Error && !inPip
        FadingScrim(visible = errorUp)
        AnimatedVisibility(
            visible = errorUp,
            enter = PlayerMotion.enterScale(),
            exit = PlayerMotion.exitScale(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            PlaybackErrorCard(
                title = item?.title.orEmpty(),
                message = lastError,
                canRetryTolerant = session.canRetryTolerant,
                hasNext = request.items.size > 1,
                isLive = request.isLive,
                ended = lastEnded,
                onRetry = { session.retryAfterError() },
                onRetryTolerant = { session.retryTolerant() },
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
                isFavorite = channel != null && channel.isFavorite(favorites),
                hasCatchup = request.isLive && (channel?.archiveDays ?: 0) > 0,
                aspectLabel = ASPECT_LABELS.getOrElse(session.scaleMode) { ASPECT_LABELS[0] },
                sleepLabel = if (session.sleepChoiceMinutes == 0) "Off"
                else "${session.sleepChoiceMinutes}m",
                canHide = request.isLive && channel != null,
                onFavoriteToggle = { channel?.let { vm.toggleFavorite(it) } },
                onCatchup = { session.layer = PlayerLayer.Catchup },
                onTracks = { session.layer = PlayerLayer.Tracks },
                onAspectCycle = { applyAspect((session.scaleMode + 1) % ASPECT_LABELS.size) },
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
            // The sleep countdown steps aside while a panel owns the
            // top-right: it sat on the options menu's channel name and the
            // guide's clock for as long as those were open. Transient toasts
            // still show everywhere. (The REC chip stood here too, until
            // recording was removed.)
            val panelOwnsCorner = session.layer == PlayerLayer.Options ||
                session.layer == PlayerLayer.Guide || session.layer == PlayerLayer.Tracks
            session.statusMessage?.let { PlayerBadge(text = it, color = NuxColors.Secondary) }
            // Digits large, in their own pill — read from the couch mid-type.
            // The dim state is the verdict on a number that matched nothing;
            // it self-dismisses, never an error card.
            if (session.digitBuffer.isNotEmpty()) {
                DigitEntryPill(text = session.digitBuffer)
            } else {
                noChannelNumber?.let { DigitEntryPill(text = "No channel $it", dim = true) }
            }
            // Only while the chrome is up, or in the last two minutes: a
            // static "Sleep in 74m" over the picture for an hour and a
            // quarter is the kind of thing a TV burns in.
            if (session.sleepDeadlineMs > 0 && !panelOwnsCorner) {
                val minutesLeft =
                    ((session.sleepDeadlineMs - sleepNowMs + 59_999) / 60_000L).coerceAtLeast(0)
                if (session.layer != PlayerLayer.None || minutesLeft <= 2) {
                    PlayerBadge(text = "Sleep in ${minutesLeft}m", color = NuxColors.OnSurfaceDim)
                }
            }
        }
    }
}
