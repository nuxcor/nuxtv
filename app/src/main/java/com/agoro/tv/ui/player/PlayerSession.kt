package com.agoro.tv.ui.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.agoro.tv.data.EngineChoice
import com.agoro.tv.data.PlaybackRequest
import com.agoro.tv.player.ExoEngine
import com.agoro.tv.player.PlayerEngine
import com.agoro.tv.player.VlcEngine
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
 * [base] with one entry swapped, without copying it. The failure ladder
 * rewrites the current item's url a step at a time, and `toMutableList()` on
 * a category of thousands — which the player receives as a lazily mapped
 * view — would materialise the lot on every hop of every dead channel.
 * Patches on the same index collapse rather than stack, so a deep ladder
 * never nests more than one level.
 */
private class PatchedList<T>(
    base: List<T>,
    private val index: Int,
    private val value: T,
) : AbstractList<T>() {
    private val base: List<T> =
        if (base is PatchedList<T> && base.index == index) base.base else base
    override val size: Int get() = base.size
    override fun get(index: Int): T = if (index == this.index) value else base[index]
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

        /**
         * Repeated stalls inside this window mean the feed can't keep up —
         * a starving stream never throws, so without this the fallback
         * ladder only ever ran for hard failures while the viewer watched
         * the stutter.
         */
        private const val STALL_WINDOW_MS = 60_000L
        private const val STALLS_BEFORE_HOP = 3

        /** Settling time after a tune, during which buffering is expected. */
        private const val STALL_GRACE_MS = 12_000L
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
     *
     * Setting it resets the stall accounting, because a tune is a new stream
     * and the last one's hiccups say nothing about it. zap() and jumpTo()
     * set this without going through resetLadder, so the counter used to
     * carry across channel changes: three ordinary channel-change buffers in
     * a minute tripped the "can't keep up" hop on a perfectly healthy feed,
     * which is what a viewer saw as the app announcing a broken stream every
     * few zaps.
     */
    private var tuningState: Boolean by mutableStateOf(true)
    var tuning: Boolean
        get() = tuningState
        set(value) {
            if (value) {
                stallClock.clear()
                lastTuneMs = System.currentTimeMillis()
                tuneSerial++
            }
            tuningState = value
        }

    /**
     * Counts tunes. Effects that must act once per stream — matching the
     * display mode, learning the decoded tier — key on this rather than on
     * the index, which a retry or a rejoin repeats and a new playlist reuses.
     */
    var tuneSerial: Int by mutableIntStateOf(0)
        private set

    /** When the current stream was asked for; see [STALL_GRACE_MS]. */
    private var lastTuneMs = System.currentTimeMillis()

    var errorMessage: String? by mutableStateOf(null)
    var statusMessage: String? by mutableStateOf(null)
    var positionMs: Long by mutableLongStateOf(0L)
    var durationMs: Long by mutableLongStateOf(0L)
    var videoSize: Pair<Int, Int>? by mutableStateOf(null)

    /** Decoded frame rate, polled alongside [videoSize] for display matching. */
    var videoFrameRate: Float? by mutableStateOf(null)

    /** Decoded HDR flavour and audio format, for the stream's badges. */
    var hdrFormat: String? by mutableStateOf(null)
    var audioFormatLabel: String? by mutableStateOf(null)

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

    /**
     * Zap target waiting out the dwell; null when nothing is pending. Only
     * a CHAIN of zaps waits: the first press tunes at once, and the dwell
     * applies from the second press of a run — see [zap].
     */
    var pendingTuneIndex: Int? by mutableStateOf(null)
        private set

    /** When the last zap landed, for telling a chain from a single press. */
    private var lastZapMs = 0L

    /** Guards the one-time prefs loads (VOD speed) across engine swaps. */
    internal var vodSpeedLoaded: Boolean = false

    // --- failure ladder, reset per item ------------------------------------
    private var retriesLeft = RETRIES_PER_ITEM
    private var liveFormatStage = 0
    private var sourceStage = 0
    private var ladderItemIndex = initialRequest.startIndex

    /** When live playback was paused, for the stale-buffer rejoin decision. */
    private var pauseStartedMs = 0L

    /** Recent mid-play stall timestamps; see [STALL_WINDOW_MS]. */
    private val stallClock = ArrayDeque<Long>()

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
            // A live feed that stalls three times in a minute is starving,
            // not hiccuping. Catch-up is exempt: seeking buffers legitimately,
            // and so does a stream that has only just started — the first
            // seconds after a tune are the buffer filling, not the feed
            // failing, and counting them made every zap look like a fault.
            if (b && playing && !tuning && request.isLive && !request.isCatchup &&
                System.currentTimeMillis() - lastTuneMs > STALL_GRACE_MS
            ) {
                val now = System.currentTimeMillis()
                stallClock += now
                while (stallClock.isNotEmpty() && now - stallClock.first() > STALL_WINDOW_MS) {
                    stallClock.removeFirst()
                }
                if (stallClock.size >= STALLS_BEFORE_HOP) {
                    stallClock.clear()
                    // ANOTHER SOURCE FIRST, the HLS re-wrap only as a last
                    // resort. Both recover, but they cost different things:
                    // another source is the same channel at another measured
                    // tier, while .m3u8 is this provider re-muxing — which is
                    // exactly what capped picture quality and is why live URLs
                    // were moved to raw .ts in the first place. Reaching for
                    // it first traded a stutter for a permanently softer
                    // picture, and did it silently.
                    when {
                        swapSource() ->
                            statusMessage = "Stream can't keep up — trying another source…"
                        swapLiveFormat() ->
                            statusMessage = "Stream keeps breaking — trying a steadier feed…"
                    }
                }
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
                // Wrong container format fails instantly and identically on
                // every retry — step through the other Xtream live formats
                // before spending slow same-URL retries.
                request.isLive && swapLiveFormat() -> Unit

                // Formats exhausted: this source is dead, not mis-addressed.
                // The catalogue folded several streams of this channel into
                // one tile, so try the next one before spending slow retries
                // on a stream that is not coming back.
                request.isLive && swapSource() -> Unit

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
                    // Just the reason: the card's title already says it
                    // couldn't play, and "Couldn't play this — Couldn't play"
                    // was the sentence on screen.
                    errorMessage = message
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
        liveFormatStage = 0
        sourceStage = 0
        stallClock.clear()
    }

    /**
     * Xtream panels serve live streams in up to three formats, and playlist
     * middlemen (IPTVEditor's "Change Stream Format") decide which of them
     * actually answers: `/live/u/p/id.ts`, `/live/u/p/id.m3u8`, or `/u/p/id`
     * with no extension or `/live/` at all. The app constructs `.ts` first
     * (the full-quality mux); when a live stream errors, step through the
     * other two before falling back to slow same-URL retries — a wrong
     * format fails instantly every time, so retrying it is pure wait.
     */
    private val liveUrlForm = Regex("""^(https?://[^/]+)/live/([^/]+)/([^/]+)/(\d+)\.(ts|m3u8)$""")

    private fun swapLiveFormat(): Boolean {
        val idx = currentIndex
        val url = request.items.getOrNull(idx)?.url ?: return false
        val m = liveUrlForm.matchEntire(url) ?: return false
        val (host, user, pass, id) = m.destructured
        val next = when (liveFormatStage) {
            0 -> "$host/live/$user/$pass/$id.m3u8"
            1 -> "$host/$user/$pass/$id"
            else -> return false
        }
        liveFormatStage++
        statusMessage = "Trying a different stream format…"
        request = request.copy(items = PatchedList(request.items, idx, request.items[idx].copy(url = next)))
        return true
    }

    /**
     * Steps to the next alternate source for the current channel.
     *
     * The manifest collapses a channel's several streams into one tile and
     * keeps the rest as [PlayableItem.fallbackUrls], best quality first. Each
     * new source starts the format ladder over, because which of `.ts`,
     * `.m3u8` or the bare path a panel answers is a property of the stream,
     * not of the channel.
     */
    private fun swapSource(): Boolean {
        val idx = currentIndex
        val item = request.items.getOrNull(idx) ?: return false
        val next = item.fallbackUrls.getOrNull(sourceStage) ?: return false
        sourceStage++
        liveFormatStage = 0
        statusMessage = "Trying another source…"
        request = request.copy(items = PatchedList(request.items, idx, item.copy(url = next)))
        return true
    }

    fun clearError() {
        errorMessage = null
        if (layer == PlayerLayer.Error) layer = PlayerLayer.None
    }

    /**
     * A tune that never resolved either way. The engine reported no error, so
     * the ladder above never ran; this hands the viewer the same error card
     * (Retry / Swap engine) rather than leaving the tune spinner up.
     */
    fun failTuning(reason: String) {
        tuning = false
        errorMessage = reason
        layer = PlayerLayer.Error
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
    //
    // A single press tunes NOW. The dwell used to apply to every zap, so one
    // CH+ sat on the old picture for 400 ms before the new stream was even
    // asked for — and on a TV that wait is the whole difference between
    // "changed channel" and "is it doing anything?". The dwell is for runs:
    // once a second press lands within the dwell window the viewer is
    // skimming, and from then on the stream opens only where the run rests,
    // so skimming twenty channels opens one connection rather than twenty.
    fun zap(delta: Int) {
        val engine = engine ?: return
        val count = request.items.size
        if (count <= 1) return
        clearError()
        val now = System.currentTimeMillis()
        val chained = pendingTuneIndex != null || now - lastZapMs < PlayerMotion.ZapDwellMs
        lastZapMs = now
        // Chain from what's on screen: during the dwell the engine still holds
        // the channel the chain started from.
        val base = pendingTuneIndex ?: engine.currentIndex
        val target = ((base + delta) % count + count) % count
        currentIndex = target
        bannerTick++
        if (chained && target == engine.currentIndex) {
            // The run stepped back onto the channel the engine already has
            // open: nothing to commit, and re-opening it would only restart
            // it. The tune card shows only if that stream is still coming up.
            pendingTuneIndex = null
            tuning = !engine.isPlaying
            return
        }
        previousIndex = engine.currentIndex
        tuning = true
        videoSize = null // the old stream's resolution isn't this channel's
        videoFrameRate = null
        hdrFormat = null
        audioFormatLabel = null
        if (chained) {
            pendingTuneIndex = target // the scaffold commits it after ZAP_DWELL_MS
        } else {
            pendingTuneIndex = null
            engine.playAt(target)
        }
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
        videoFrameRate = null
        hdrFormat = null
        audioFormatLabel = null
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
