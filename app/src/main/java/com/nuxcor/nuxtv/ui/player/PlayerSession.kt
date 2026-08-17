package com.nuxcor.nuxtv.ui.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nuxcor.nuxtv.data.EngineChoice
import com.nuxcor.nuxtv.data.PlaybackRequest
import com.nuxcor.nuxtv.player.ExoEngine
import com.nuxcor.nuxtv.player.PlayerEngine
import com.nuxcor.nuxtv.player.VlcEngine
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun formatPlayerTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Best-effort language equality between what an engine reports for a track
 * ("en", "eng", "English"…) and the viewer's saved preference. Everything is
 * normalised to an ISO-639-2 code where possible; unknowns never match.
 */
internal fun languageMatches(trackLanguage: String?, preferred: String?): Boolean {
    val a = normalizeLanguage(trackLanguage) ?: return false
    val b = normalizeLanguage(preferred) ?: return false
    return a == b
}

private fun normalizeLanguage(raw: String?): String? {
    val text = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
    if (text.length in 2..3 && text.all { it.isLetter() }) {
        return runCatching { Locale(text).isO3Language }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: text
    }
    // A spelled-out name ("English", "Français") — find a locale that calls
    // itself that in English or in its own tongue.
    return Locale.getAvailableLocales().firstOrNull {
        it.getDisplayLanguage(Locale.ENGLISH).equals(text, ignoreCase = true) ||
            it.getDisplayLanguage(it).equals(text, ignoreCase = true)
    }?.let { runCatching { it.isO3Language }.getOrNull() }?.takeIf { it.isNotBlank() }
}

/**
 * Player state and engine lifecycle, held outside the composition the way
 * GuidePreviewController holds the guide preview's: Compose-observable fields,
 * explicit teardown, and the composable reduced to layout plus effects.
 *
 * The scaffold owns the *timers* (banner retirement, digit commit, dwell,
 * sleep, polling) as LaunchedEffects keyed on this state; the session owns the
 * *transitions* so they stay consistent whichever surface triggers them.
 */
class PlayerSession internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    initialRequest: PlaybackRequest,
    initialEngine: EngineChoice,
    private val onSaveResume: (url: String, positionMs: Long, durationMs: Long) -> Unit,
) {
    companion object {
        /**
         * A live stream paused longer than this has a stale buffer — many
         * providers drop the connection within a minute — so "resume" means
         * re-tuning to the live edge, not playing a picture from the past.
         */
        const val LIVE_PAUSE_REJOIN_MS = 30_000L

        private const val RETRIES_PER_ITEM = 2
    }

    var request: PlaybackRequest by mutableStateOf(initialRequest)
        private set

    var engineChoice: EngineChoice by mutableStateOf(initialEngine)
        private set

    /** The engine currently rendering; swapped whole on [engineChoice] change. */
    var engine: PlayerEngine? by mutableStateOf(null)
        private set

    var layer: PlayerLayer by mutableStateOf(PlayerLayer.None)

    var currentIndex: Int by mutableIntStateOf(initialRequest.startIndex)
    var previousIndex: Int by mutableIntStateOf(-1)

    var playing: Boolean by mutableStateOf(true)
        private set
    var buffering: Boolean by mutableStateOf(true)
        private set

    /**
     * True from "a tune was requested" until the new stream actually plays —
     * the window the TuneCard covers. Distinct from [buffering], which also
     * fires for mid-stream stalls that only deserve a corner chip.
     */
    var tuning: Boolean by mutableStateOf(true)

    var errorMessage: String? by mutableStateOf(null)
    var statusMessage: String? by mutableStateOf(null)
    var positionMs: Long by mutableLongStateOf(0L)
    var durationMs: Long by mutableLongStateOf(0L)
    var videoSize: Pair<Int, Int>? by mutableStateOf(null)

    var bannerTick: Int by mutableIntStateOf(0)
    var bannerVisible: Boolean by mutableStateOf(false)
    var bannerShows: Int by mutableIntStateOf(0)

    // OK's press/hold state between KeyDown and KeyUp: armed on the press,
    // longFired once the hold has acted, both cleared on release — so a short
    // press acts on the release and a long press's release is swallowed
    // rather than activating whatever the hold just brought into focus.
    var centerArmed: Boolean by mutableStateOf(false)
    var centerLongPressFired: Boolean by mutableStateOf(false)

    var interactionTick: Int by mutableIntStateOf(0)
    var digitBuffer: String by mutableStateOf("")

    /** Wall-clock deadline for the sleep timer; 0 means off. */
    var sleepDeadlineMs: Long by mutableLongStateOf(0L)

    /** The minutes option the viewer picked, for the chips' selection state. */
    var sleepChoiceMinutes: Int by mutableIntStateOf(0)

    var scaleMode: Int by mutableIntStateOf(0)
    var speed: Float by mutableStateOf(1f)

    /** Zap target waiting out the dwell; null when nothing is pending. */
    var pendingTuneIndex: Int? by mutableStateOf(null)
        private set

    /** Guards the one-time prefs loads (VOD speed) across engine swaps. */
    internal var vodSpeedLoaded: Boolean = false

    // --- failure ladder, reset per item ------------------------------------
    private var retriesLeft = RETRIES_PER_ITEM
    private var ladderItemIndex = initialRequest.startIndex

    /** When live playback was paused, for the stale-buffer rejoin decision. */
    private var pauseStartedMs = 0L

    internal val listener = object : PlayerEngine.Listener {
        override fun onItemChanged(index: Int) {
            // A genuinely new item restarts the failure ladder; the same index
            // arrives again on every reconnect (VLC re-announces it from
            // playAt), and resetting then would let a dead stream swap
            // engines forever.
            if (index != ladderItemIndex) resetLadder(index)
            currentIndex = index
            clearError()
        }

        override fun onPlayingChanged(p: Boolean, b: Boolean) {
            if (p) {
                tuning = false
                pauseStartedMs = 0L
            } else if (!b && playing) {
                // An actual pause, not a stall: start the rejoin clock.
                pauseStartedMs = System.currentTimeMillis()
            }
            playing = p
            buffering = b
        }

        override fun onError(message: String) {
            // The 500ms position poll only runs while chrome is visible, so
            // session.positionMs can be minutes stale here. Capture the live
            // position before any recovery path recreates the engine, or a
            // VOD engine swap resumes from wherever chrome last was open.
            engine?.let { live ->
                if (!request.isLive && !request.isCatchup && live.positionMs > 0) {
                    positionMs = live.positionMs
                }
            }
            when {
                // No engine hopping: an error retries on the SAME engine the
                // viewer chose. The old "try the other player once" ladder
                // silently landed people on VLC — which has no track
                // selection and its own quality profile — for everything a
                // flaky provider hiccuped on, and the two players rendering
                // differently read as random quality changes.
                // VOD included: the engine swap used to be VOD's only recovery
                // path, so dropping the swap without this left films dying on
                // the first hiccup.
                retriesLeft > 0 -> {
                    // Backing off: the first reconnect is quick, the second
                    // gives a struggling provider room to breathe.
                    val attempt = RETRIES_PER_ITEM - retriesLeft
                    retriesLeft--
                    statusMessage = "Stream error — reconnecting…"
                    scope.launch {
                        delay(3_000L shl attempt)
                        engine?.let { it.playAt(it.currentIndex) }
                    }
                }

                else -> {
                    errorMessage = "Playback failed — $message"
                    layer = PlayerLayer.Error
                }
            }
        }
    }

    /**
     * Builds the engine for the current [engineChoice]. Called from a
     * `remember(engineChoice)` block so a choice swap creates a fresh engine —
     * and the scaffold keys the AndroidView on the same value so the new
     * engine also gets a fresh surface.
     */
    internal fun createEngine(highestQuality: Boolean): PlayerEngine {
        // Not keyed on the quality preference: ExoPlayer applies it live
        // through the track selector, and rebuilding VLC mid-stream to change
        // a construction flag would interrupt playback for a setting change.
        // VLC picks it up next time the player opens.
        val built =
            if (engineChoice == EngineChoice.VLC) VlcEngine(context, highestQuality)
            else ExoEngine(context)
        engine = built
        return built
    }

    /** Per-engine teardown; runs on every swap and once more on exit. */
    internal fun teardownEngine(target: PlayerEngine) {
        // Persist resume position for single-item VOD before teardown.
        val url = request.items.getOrNull(target.currentIndex)?.url
        if (!request.isLive && !request.isCatchup && url != null && target.durationMs > 0) {
            onSaveResume(url, target.positionMs, target.durationMs)
        }
        target.release()
        if (engine === target) engine = null
    }

    /**
     * A replacement playlist (mini-guide category pick, grid-guide tune,
     * catch-up). The engine preference resets to the user's default: an
     * automatic fallback was a verdict on one stream, not on the app.
     */
    internal fun onRequest(request: PlaybackRequest, defaultEngine: EngineChoice) {
        if (this.request === request) return
        this.request = request
        currentIndex = request.startIndex.coerceIn(0, (request.items.size - 1).coerceAtLeast(0))
        pendingTuneIndex = null
        digitBuffer = ""
        tuning = true
        resetLadder(currentIndex)
        clearError()
        engineChoice = defaultEngine
    }

    private fun resetLadder(index: Int) {
        ladderItemIndex = index
        retriesLeft = RETRIES_PER_ITEM
    }

    fun clearError() {
        errorMessage = null
        if (layer == PlayerLayer.Error) layer = PlayerLayer.None
    }

    /**
     * Dismissing a panel returns to the error card when an error is pending —
     * dropping to bare video would leave a black screen with no chrome and no
     * hint that keys still work.
     */
    fun closePanel() {
        layer = if (errorMessage != null) PlayerLayer.Error else PlayerLayer.None
    }

    /** Transport "play" from a media session / CEC / assistant. */
    fun transportPlay() {
        val engine = engine ?: return
        if (!engine.isPlaying) togglePlayPause() // carries the live-rejoin rule
    }

    /** Transport "pause" from a media session / CEC / assistant. */
    fun transportPause() {
        val engine = engine ?: return
        if (engine.isPlaying) engine.playPause()
    }

    fun poke() {
        layer = PlayerLayer.Controls
        interactionTick++
    }

    // Both set currentIndex up front rather than waiting for the engine's
    // transition callback. We already know the target, and the callback lands a
    // frame or two later — long enough for the banner to appear captioned with
    // the channel you just left. The callback then confirms the same value.
    fun zap(delta: Int) {
        val engine = engine ?: return
        val count = request.items.size
        if (count <= 1) return
        clearError()
        previousIndex = engine.currentIndex
        // Chain from what's on screen: during the dwell the engine still holds
        // the channel the chain started from.
        val base = pendingTuneIndex ?: engine.currentIndex
        val target = ((base + delta) % count + count) % count
        currentIndex = target
        pendingTuneIndex = target // the scaffold commits it after ZAP_DWELL_MS
        tuning = true
        videoSize = null // the old stream's resolution isn't this channel's
        bannerTick++
    }

    /** Opens the stream a zap chain settled on; a no-op when nothing is pending. */
    fun commitPendingTune() {
        val target = pendingTuneIndex ?: return
        pendingTuneIndex = null
        engine?.playAt(target)
    }

    fun jumpTo(index: Int) {
        val engine = engine ?: return
        if (index !in request.items.indices) return
        clearError()
        pendingTuneIndex = null
        previousIndex = engine.currentIndex
        currentIndex = index
        tuning = true
        engine.playAt(index)
        videoSize = null
        bannerTick++
    }

    /**
     * Play/pause with the live-rejoin rule: resuming a live stream that sat
     * paused past [LIVE_PAUSE_REJOIN_MS] re-tunes to the live edge instead of
     * playing out a stale buffer the provider has likely dropped anyway.
     */
    fun togglePlayPause() {
        val engine = engine ?: return
        if (!engine.isPlaying && request.isLive && pauseStartedMs > 0 &&
            System.currentTimeMillis() - pauseStartedMs > LIVE_PAUSE_REJOIN_MS
        ) {
            pauseStartedMs = 0L
            tuning = true
            engine.playAt(engine.currentIndex)
        } else {
            engine.playPause()
        }
    }

    /** The manual engine toggle from the controls. */
    fun swapEngine() {
        val engine = engine ?: return
        positionMs = engine.positionMs // survive the swap
        engineChoice =
            if (engineChoice == EngineChoice.EXO) EngineChoice.VLC else EngineChoice.EXO
    }

    /** Retry the current item after an error, with a fresh ladder. */
    fun retryAfterError() {
        clearError()
        resetLadder(currentIndex)
        engine?.let { it.playAt(it.currentIndex) }
        tuning = true
    }

    fun swapEngineAfterError() {
        engine?.let { live ->
            if (!request.isLive && !request.isCatchup && live.positionMs > 0) {
                positionMs = live.positionMs
            }
        }
        clearError()
        engineChoice =
            if (engineChoice == EngineChoice.EXO) EngineChoice.VLC else EngineChoice.EXO
    }
}
