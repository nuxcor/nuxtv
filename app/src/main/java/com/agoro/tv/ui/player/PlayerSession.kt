package com.agoro.tv.ui.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.agoro.tv.data.PlaybackRequest
import com.agoro.tv.player.AudioOutputPolicy
import com.agoro.tv.player.DecodeProfile
import com.agoro.tv.player.PlaybackFault
import com.agoro.tv.player.VideoOutputPolicy
import com.agoro.tv.player.ExoEngine
import com.agoro.tv.player.HdrType
import com.agoro.tv.player.PlayerEngine
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

        /**
         * A stall has to last this long to count. The ladder used to count
         * every BUFFERING transition, so three sub-second Wi-Fi blips in a
         * minute — routine on a dongle, and absorbed by the buffer without
         * a frame lost — bought the viewer a full re-tune, black screen
         * and all. That re-tune was the "buffering" they were seeing.
         */
        private const val STALL_COUNTS_AFTER_MS = 1_500L

        /**
         * Buffered media at the start of a stall below which the line is
         * the reason. Above it the player had plenty to play and a renderer
         * stopped taking it — a decoder problem, which another source of
         * the same picture cannot fix and the engine handles itself.
         */
        private const val STARVED_BUFFER_MS = 1_000L

        /** Settling time after a tune, during which buffering is expected. */
        private const val STALL_GRACE_MS = 12_000L
    }

    var request: PlaybackRequest by mutableStateOf(initialRequest)
        private set

    /**
     * How forgiving the engine is built; see [DecodeProfile]. Only the failure
     * ladder and the error card move it, and [onRequest] puts it back — a
     * verdict on one stream is not a verdict on the next.
     */
    var decodeProfile: DecodeProfile by mutableStateOf(DecodeProfile.FAST)
        private set

    /**
     * Bumped whenever the engine must be rebuilt from scratch. The scaffold
     * remembers the engine on this and keys the AndroidView on it too, so a
     * rebuild also gets a fresh surface — the old one belongs to a player that
     * has been handed back to the pool.
     */
    var engineGeneration: Int by mutableIntStateOf(0)
        private set

    /** The engine currently rendering; swapped whole on an [engineGeneration] bump. */
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
                stallTimer = null
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

    /**
     * How long is left of the window after a tune in which buffering is the
     * buffer filling rather than the feed failing. 0 once it has passed.
     *
     * The stall counter has ignored this window from the start — the first
     * seconds after a tune are the buffer filling, and counting them made
     * every zap look like a fault. The chip that SAYS "Buffering…" was never
     * given the same rule, so it kept announcing over a picture that was
     * simply starting: [tuning] goes false the moment the first frame lands,
     * and the refill behind it is what the viewer then read as a fault.
     *
     * One window, one meaning, read by both. A stream still buffering when it
     * closes has stopped settling and started failing, and by then the ladder
     * is already saying so in its own words.
     */
    val settleRemainingMs: Long
        get() = (STALL_GRACE_MS - (System.currentTimeMillis() - lastTuneMs)).coerceAtLeast(0L)

    var errorMessage: String? by mutableStateOf(null)
    var statusMessage: String? by mutableStateOf(null)
    var positionMs: Long by mutableLongStateOf(0L)
    var durationMs: Long by mutableLongStateOf(0L)
    var videoSize: Pair<Int, Int>? by mutableStateOf(null)

    /** Decoded frame rate, polled alongside [videoSize] for display matching. */
    var videoFrameRate: Float? by mutableStateOf(null)

    /** Decoded HDR flavour and audio format, for the stream's badges — and, for
     * the HDR one, for the display's output mode and the window's colour mode. */
    var hdrType: HdrType? by mutableStateOf(null)
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

    /** Counts the stall in progress once it has lasted [STALL_COUNTS_AFTER_MS]; cancelled if it ends first. */
    private var stallTimer: Job? = null
        set(value) {
            field?.cancel()
            field = value
        }

    internal val listener = object : PlayerEngine.Listener {
        override fun onItemChanged(index: Int) {
            // A genuinely new item restarts the failure ladder; the same index
            // arrives again on every reconnect (the engine re-announces it
            // from playAt), and resetting then would let a dead stream
            // reconnect forever.
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
            // A live feed that starves three times in a minute can't be
            // carried on this line at this tier. Catch-up is exempt: seeking
            // buffers legitimately, and so does a stream that has only just
            // started — the first seconds after a tune are the buffer
            // filling, not the feed failing, and counting them made every
            // zap look like a fault.
            //
            // Only a stall that LASTS counts, and only one that began with
            // the buffer empty: a blip the buffer rides out is what the
            // buffer is for, and a freeze with twenty seconds buffered is
            // the decoder's, not the line's — hopping source for either
            // turned a hiccup the viewer might not have noticed into a
            // re-tune they certainly did.
            if (b && playing && !tuning && request.isLive && !request.isCatchup &&
                System.currentTimeMillis() - lastTuneMs > STALL_GRACE_MS
            ) {
                val starved = (engine?.bufferedAheadMs ?: 0L) < STARVED_BUFFER_MS
                stallTimer = if (starved) scope.launch {
                    delay(STALL_COUNTS_AFTER_MS)
                    if (buffering && !tuning) countStall()
                } else null
            } else if (!b) {
                stallTimer = null
            }
            playing = p
            buffering = b
        }

        override fun onError(message: String, fault: PlaybackFault) {
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
                // The output refused the AudioTrack — a passthrough encoding
                // or a tunnelled track the TV advertised and then turned
                // down. That is the device, not the stream: another format,
                // another source and another decoder all ask the same sink
                // for the same track, so it goes first, and it goes once per
                // process — the latch says no the second time, and the rungs
                // below take over. See AudioOutputPolicy.
                fault == PlaybackFault.AUDIO_OUTPUT && AudioOutputPolicy.latch(message) ->
                    // Calm on purpose: it is automatic, it is once, and it
                    // ends in sound. The reason is on the card if it does
                    // not, and in the log either way.
                    retryRebuilt("Adjusting audio for your TV…")

                // Decoded audio whose timestamps keep jumping: the engine
                // raises this once, when its latch turns, so the rebuild is
                // unconditional. See PtsSmoother.
                fault == PlaybackFault.AUDIO_TIMING ->
                    retryRebuilt("Smoothing the audio timing…")

                // A video decoder that runs but never draws: rebuild on one
                // that re-initialises instead of reusing. Same shape, same
                // once-per-process latch. See VideoOutputPolicy.
                fault == PlaybackFault.VIDEO_OUTPUT && VideoOutputPolicy.latch(message) ->
                    retryRebuilt("Restarting the video decoder…")

                // Wrong container format fails instantly and identically on
                // every retry — step through the other Xtream live formats
                // before spending slow same-URL retries.
                request.isLive && swapLiveFormat() -> Unit

                // Formats exhausted: this source is dead, not mis-addressed.
                // The catalogue folded several streams of this channel into
                // one tile, so try the next one before spending slow retries
                // on a stream that is not coming back.
                request.isLive && swapSource() -> Unit

                // The mux or the codec is what's wrong, and that fails the
                // same way every time — so re-open on the forgiving demuxer
                // and the software decoders BEFORE spending the slow same-URL
                // retries, exactly as a wrong container format does above.
                // This is where "try the other engine" used to point, and it
                // reaches further than that swap did: only a fault the profile
                // can actually address gets here, so a 404 or a dead line is
                // never offered software decoding as false hope. The ladder
                // resets on the way through, so the retries below still run
                // afterwards — on the tolerant engine.
                fault == PlaybackFault.DECODE && canRetryTolerant -> retryTolerant()

                // Reconnect on the same player. The old ladder hopped to VLC
                // for anything a flaky provider hiccuped on, which silently
                // landed people on a player with no track selection and its
                // own quality profile — and two players rendering differently
                // read as random quality changes.
                // VOD included: the engine swap used to be VOD's only recovery
                // path, so dropping the swap without this left films dying on
                // the first hiccup.
                // Not for a provider that has said no in words: a rejected
                // login or a stream it no longer carries fails identically
                // on every reconnect, and the wait is the whole cost.
                fault != PlaybackFault.PERMANENT && retriesLeft > 0 -> {
                    // Backing off: the first reconnect is quick, the second
                    // gives a struggling provider room to breathe.
                    val attempt = RETRIES_PER_ITEM - retriesLeft
                    retriesLeft--
                    // The REASON, not just the fact. humanError has already
                    // turned the code into something a viewer can act on -
                    // "your provider didn't return this stream" is a different
                    // problem from "the connection dropped" and a different
                    // one again from "audio track init failed" - and this line
                    // was throwing all of that away for a word that says only
                    // that something went wrong. The specific message survived
                    // to the error card, which is reached only after the
                    // retries are spent: exactly the cases that recover are
                    // the ones that never said why they had to.
                    statusMessage = "$message — reconnecting…"
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
     * Builds the engine for the current [decodeProfile]. Called from a
     * `remember(engineGeneration)` block so a rebuild creates a fresh engine —
     * and the scaffold keys the AndroidView on the same value so the new
     * engine also gets a fresh surface.
     */
    internal fun createEngine(deservesTunnel: (String) -> Boolean = { false }): PlayerEngine {
        // isLive decides how the player buffers, and a film wants a different
        // shape from a channel — see loadControlFor. A profile swap rebuilds
        // the engine, so this is re-read then too.
        val built = ExoEngine(
            context,
            deservesTunnel = deservesTunnel,
            profile = decodeProfile,
            isLive = request.isLive,
        )
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
     * catch-up). The decode profile resets to the fast one: a fallback was a
     * verdict on one stream, not on the app.
     */
    internal fun onRequest(request: PlaybackRequest) {
        if (this.request === request) return
        this.request = request
        currentIndex = request.startIndex.coerceIn(0, (request.items.size - 1).coerceAtLeast(0))
        pendingTuneIndex = null
        digitBuffer = ""
        tuning = true
        resetLadder(currentIndex)
        clearError()
        rebuildOn(DecodeProfile.FAST)
    }

    private fun resetLadder(index: Int) {
        ladderItemIndex = index
        retriesLeft = RETRIES_PER_ITEM
        liveFormatStage = 0
        sourceStage = 0
        stallClock.clear()
        stallTimer = null
    }

    /** A stall has lasted long enough to count; three in a minute move the ladder. */
    private fun countStall() {
        val now = System.currentTimeMillis()
        stallClock += now
        while (stallClock.isNotEmpty() && now - stallClock.first() > STALL_WINDOW_MS) {
            stallClock.removeFirst()
        }
        if (stallClock.size < STALLS_BEFORE_HOP) return
        stallClock.clear()
        // ANOTHER SOURCE FIRST, the HLS re-wrap only as a last resort. Both
        // recover, but they cost different things: another source is the
        // same channel at another measured tier, while .m3u8 is this
        // provider re-muxing — which is exactly what capped picture quality
        // and is why live URLs were moved to raw .ts in the first place.
        // Reaching for it first traded a stutter for a permanently softer
        // picture, and did it silently.
        when {
            swapSource() ->
                statusMessage = "Stream can't keep up — trying another source…"
            swapLiveFormat() ->
                statusMessage = "Stream keeps breaking — trying a steadier feed…"
            // Both ladders spent. Without this the when did nothing at all:
            // the stall counter went on firing into a branch that could no
            // longer act, so the picture froze and the app said nothing —
            // "it buffers, then it stops". Say so, and let the retries below
            // keep working the same source; a line that recovers on its own
            // then plays again instead of sitting dead behind a full buffer.
            else -> statusMessage = "This feed keeps stalling — no other source to try."
        }
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
     * (Retry / software decoding) rather than leaving the tune spinner up.
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
        hdrType = null
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
        hdrType = null
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

    /**
     * Rebuilds the engine on [profile], carrying the playhead across. A no-op
     * when it is already there, so a second press of the error card's button
     * doesn't restart a stream that is already trying its best.
     */
    private fun rebuildOn(profile: DecodeProfile) {
        if (profile == decodeProfile) return
        decodeProfile = profile
        rebuildEngine()
    }

    /** Swaps the engine whole, carrying the playhead across. */
    private fun rebuildEngine() {
        engine?.let { live ->
            // The 500ms position poll only runs while the chrome is up, so the
            // session's copy can be minutes stale; ask the engine.
            if (!request.isLive && !request.isCatchup && live.positionMs > 0) {
                positionMs = live.positionMs
            }
        }
        engineGeneration++
    }

    /**
     * Re-opens the current stream on a freshly built player. The output
     * latch that asked for this — [AudioOutputPolicy], [VideoOutputPolicy] —
     * has already turned by the time it runs, so the pool builds the new
     * engine on the changed sink or renderer. Same profile, same playhead,
     * same ladder from the top: the retries still run afterwards, on the
     * rebuilt player.
     */
    private fun retryRebuilt(status: String) {
        clearError()
        resetLadder(currentIndex)
        tuning = true
        statusMessage = status
        rebuildEngine()
    }

    /**
     * Re-opens the current stream with the demuxer and decoders that forgive
     * most; see [DecodeProfile]. This is what the error card offers and what
     * the ladder reaches for on a decode failure — the replacement for the old
     * swap to libVLC, and unlike that swap it keeps track selection, the HDR
     * badge, tunnelling and the media session.
     */
    fun retryTolerant() {
        if (!canRetryTolerant) return
        clearError()
        resetLadder(currentIndex)
        tuning = true
        statusMessage = "Trying software decoding…"
        rebuildOn(DecodeProfile.TOLERANT)
    }

    /** Whether [retryTolerant] has anywhere left to go. */
    val canRetryTolerant: Boolean get() = decodeProfile == DecodeProfile.FAST

    /** Retry the current item after an error, with a fresh ladder. */
    fun retryAfterError() {
        clearError()
        resetLadder(currentIndex)
        engine?.let { it.playAt(it.currentIndex) }
        tuning = true
    }

}
