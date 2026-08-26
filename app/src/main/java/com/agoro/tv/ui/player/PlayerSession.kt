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
 * How long a reconnect waits before re-opening the same stream, per attempt.
 *
 * VOD keeps the original quick pair — a film is one deliberate connection and
 * a hiccup is usually the line, so 3s then 6s. Live is the case that a
 * one-connection provider line breaks: when a stream drops, the panel counts
 * the just-ended slot as open for many seconds, so an immediate reconnect
 * asks for a second connection the line does not allow and is refused (403).
 * The two quick retries then burn out before the slot clears, and an hour of
 * viewing ends in a freeze that would have healed itself given a moment. Live
 * waits across a typical panel-release window instead — one near-immediate
 * try for a line that is NOT connection-capped, then two that straddle the
 * slot timeout.
 */
private val VOD_RECONNECT_DELAYS_MS = longArrayOf(3_000L, 6_000L)
private val LIVE_RECONNECT_DELAYS_MS = longArrayOf(6_000L, 20_000L, 40_000L)

internal fun reconnectDelaysMs(isLive: Boolean): LongArray =
    if (isLive) LIVE_RECONNECT_DELAYS_MS else VOD_RECONNECT_DELAYS_MS

/** The backoff for [attempt] (0-based), clamped to the last step for any overrun. */
internal fun reconnectDelayMs(isLive: Boolean, attempt: Int): Long {
    val delays = reconnectDelaysMs(isLive)
    return delays[attempt.coerceIn(0, delays.size - 1)]
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
    /**
     * Waits for the provider to free a live slot before a reconnect asks for
     * one, on a one/two-connection line; returns true when it took the wait
     * (reconnect at once) and false when the line has room to spare (use the
     * fixed backoff). Null off the player screen and in tests. See
     * [reconnectDelaysMs] and MainViewModel.awaitFreeLiveSlot.
     */
    private val awaitLiveSlot: (suspend () -> Boolean)? = null,
) {
    companion object {
        /**
         * A live stream paused longer than this has a stale buffer — many
         * providers drop the connection within a minute — so "resume" means
         * re-tuning to the live edge, not playing a picture from the past.
         */
        const val LIVE_PAUSE_REJOIN_MS = 30_000L

        /**
         * Repeated stalls inside this window mean the feed can't keep up —
         * a starving stream never throws, so without this the fallback
         * ladder only ever ran for hard failures while the viewer watched
         * the stutter.
         */
        private const val STALL_WINDOW_MS = 60_000L
        private const val STALLS_BEFORE_HOP = 3

        /**
         * How long one unbroken stall lasts before the stream is declared
         * dead, regardless of how many stalls have been counted.
         *
         * [STALLS_BEFORE_HOP] only catches a feed that RECOVERS between
         * failures — three stalls in a minute means three resumptions too. A
         * stream that stops and stays stopped raises exactly one, so nothing
         * retried and nothing was said, and the viewer sat on a buffering chip
         * with a working remote and no way to make anything happen. That is
         * the "it just buffers and gets stuck" report.
         *
         * Deliberately LONGER than the engine's own read-timeout cascade. A
         * live socket that goes silent throws through media3's retry policy at
         * roughly thirty-five seconds (8s read timeout, three attempts, 0/1/2s
         * backoff — see PlayerPool), and that error is better than this one:
         * it names what actually failed, and the ladder can classify it. This
         * is the backstop for the case where no error EVER arrives, so it must
         * not pre-empt the one that would have. Firing first would burn a rung
         * of the ladder and replace "the connection dropped" with a guess.
         *
         * The cost of being wrong is one unnecessary reconnect on a stream
         * about to recover on its own; the cost of not having it is a freeze
         * with no end and no explanation.
         */
        private const val STALL_IS_DEATH_MS = 40_000L

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

        /**
         * Unbroken playing time after which the retry budget is given back.
         *
         * The ladder only ever spent. [resetLadder] runs for a new item, a new
         * request, or a retry the viewer asked for, and nothing refilled the
         * budget for a stream that dropped, reconnected, and then played
         * perfectly — so the count fell across a whole viewing session. Three
         * drops spread over three hours on one channel spent the entire
         * ladder, and the fourth, however transient, skipped every reconnect
         * and went straight to the error card. "It used to recover, now it
         * just shows Retry" is that, and it gets worse the longer you watch.
         *
         * A minute, not the first frame. Refilling on the first frame is the
         * loop trap: a stream that plays two seconds and dies would reconnect
         * for as long as the viewer left it there. A minute of unbroken
         * playing is a stream that has genuinely recovered, and it is short
         * enough that ordinary hourly hiccups each meet a full ladder.
         *
         * Only the same-URL retries come back. The format and source stages
         * stay where they are on purpose: they record what this session has
         * learned about which url of this channel actually answers, and
         * re-walking them would re-try forms already known to be dead.
         */
        private const val HEALTHY_PLAYBACK_MS = 60_000L
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
                healthTimer = null
                reconnectJob = null
                // A tune the viewer asked for is not a reconnect, and it
                // cancels the one in flight above — so the card that one was
                // holding up goes with it.
                reconnectAttempt = 0
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

    /**
     * Counts every OPEN of a stream — the ladder's own reconnects and swaps
     * included — as against [tuneSerial], which counts only the tunes the
     * viewer asked for. The tune timeout is per attempt now, and this is what
     * "an attempt" means: without it that timeout was a single wall clock
     * across a whole cascade, and it expired in the middle of the recovery.
     */
    var attemptSerial: Int by mutableIntStateOf(0)
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

    /**
     * Which reconnect the ladder is on, 1-based; 0 when none is in flight.
     *
     * The screen showed NOTHING for the whole of a reconnect. A thrown error
     * leaves the player idle, which reports playing=false and buffering=false,
     * so neither the tune card (which wants [tuning]) nor the buffering chip
     * (which wants [buffering]) was up; the status toast retires after four
     * seconds; and the "paused" glyph, which asks for exactly that state, came
     * up instead. Live backs off 6s, 20s and 40s, and on a capped line it
     * polls for a free slot for up to forty-five — so a viewer sat in front of
     * a frozen frame wearing a pause icon, three times over, before the error
     * card finally appeared. That is the whole of "it buffers, then it stops".
     *
     * TiviMate's answer, and the right one: say which attempt this is, and go
     * on saying it until the picture returns or the card takes over.
     */
    var reconnectAttempt: Int by mutableIntStateOf(0)
        private set

    /** How many attempts the ladder has, for "2 of 3". */
    val reconnectTotal: Int get() = reconnectDelaysMs(request.isLive).size

    /**
     * True only while a reconnect is waiting out its backoff or a free slot,
     * and false once it has re-opened the stream. The tune timeout reads it: a
     * ladder still working is not a hang, and the error card must not pre-empt
     * it.
     *
     * NOT snapshot state, unlike every other field here: it is derived from a
     * plain Job, so nothing recomposes when it changes. Read it imperatively —
     * inside an effect, at the moment a decision is made — and never in
     * composition, which would render one value and never hear about the next.
     * It is a Job probe on purpose: a boolean set around the wait would have to
     * be cleared from a cancelled coroutine, and that clear races the next
     * reconnect's set.
     */
    val reconnectPending: Boolean get() = reconnectJob?.isActive == true

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
    private var retriesLeft = reconnectDelaysMs(initialRequest.isLive).size
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

    /** Gives up on the stall in progress once it has lasted [STALL_IS_DEATH_MS]. */
    private var deathTimer: Job? = null
        set(value) {
            field?.cancel()
            field = value
        }

    /** Refills the retry budget once playback has held for [HEALTHY_PLAYBACK_MS]. */
    private var healthTimer: Job? = null
        set(value) {
            field?.cancel()
            field = value
        }

    /**
     * Whether the app is in front of the viewer. Set by the player scaffold's
     * lifecycle observer; read only by the death watchdog, which must not
     * reconnect — and so start audio — behind the launcher.
     */
    internal var appForeground: Boolean = true

    /**
     * The pending reconnect, waiting out its backoff before it re-opens the
     * stream. Held so a tune away from the failed stream can cancel it —
     * the live backoff runs up to forty seconds now, and a reconnect that
     * fired after the viewer had zapped elsewhere would yank the channel they
     * moved to back to the one that failed. [tuneSerial] is the belt to this
     * braces: it is captured when the reconnect is scheduled and re-checked
     * before it acts, so a reconnect can never re-tune a stream the viewer
     * has already left. A reconnect's own re-open does not bump the serial
     * (it re-announces the same index without a new tune), so the chain of
     * backoffs for one failed stream still runs.
     */
    private var reconnectJob: Job? = null
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
            // Every open counts as an attempt, that reconnect included: the
            // tune timeout restarts on this, so a cascade gets one budget per
            // try rather than one for the whole climb.
            attemptSerial++
            currentIndex = index
            clearError()
        }

        override fun onPlayingChanged(p: Boolean, b: Boolean) {
            if (p) {
                tuning = false
                pauseStartedMs = 0L
                // The picture is back, so the reconnect card comes down. Tied
                // to playing rather than to the reconnect firing, because
                // playAt is the ATTEMPT: one that fails again should never
                // have flashed the card away and back.
                reconnectAttempt = 0
                // Only unbroken playing time earns the budget back, so this is
                // armed on the way into playing and cancelled the moment
                // playback stops. A timer already running is left alone rather
                // than restarted — a stream that re-reports playing would
                // otherwise never reach the minute. See [HEALTHY_PLAYBACK_MS].
                if (healthTimer == null) {
                    healthTimer = scope.launch {
                        delay(HEALTHY_PLAYBACK_MS)
                        val full = reconnectDelaysMs(request.isLive).size
                        if (retriesLeft < full) {
                            android.util.Log.i(
                                "Agoro",
                                "Played ${HEALTHY_PLAYBACK_MS}ms clean; retry budget " +
                                    "back to $full (was $retriesLeft)",
                            )
                            retriesLeft = full
                        }
                    }
                }
            } else {
                healthTimer = null
                // An actual pause, not a stall: start the rejoin clock.
                if (!b && playing) pauseStartedMs = System.currentTimeMillis()
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

            // A separate timer, because it asks a separate question. The
            // counter above asks which recovery a stall DESERVES, and every
            // gate it carries is built for that: a grace after the tune, an
            // empty buffer, live and not catch-up. None of them belong to
            // "is this stream alive at all". A feed that dies eight seconds
            // after its first frame is inside the grace; one that freezes
            // behind a full buffer is not starved; and the reconnect that
            // itself never returns is invisible to a timer that arms on
            // `playing`, which the reconnect has already cleared. Each of
            // those is a viewer stuck on a chip forever.
            deathTimer = if (b && !tuning && (request.isLive || request.isCatchup)) {
                scope.launch {
                    delay(STALL_IS_DEATH_MS)
                    if (!buffering || tuning) return@launch
                    // Never behind the launcher: the reconnect comes back
                    // playing, and the lifecycle pause cannot catch it
                    // because a stalled engine is already not playing — so
                    // the channel would start talking under the home screen.
                    if (!appForeground) return@launch
                    android.util.Log.w(
                        "Agoro",
                        "Stalled ${STALL_IS_DEATH_MS}ms with " +
                            "${engine?.bufferedAheadMs ?: 0}ms buffered, live=${request.isLive}; " +
                            "giving up on the stream",
                    )
                    onError("the stream stopped sending", PlaybackFault.TRANSIENT)
                }
            } else null
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
                    // Backing off, and on live long enough for a one-connection
                    // panel to release the slot the just-dropped stream still
                    // holds — see reconnectDelaysMs. A film keeps the quick pair.
                    val attempt = reconnectDelaysMs(request.isLive).size - retriesLeft
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
                    // Up for the whole wait, and taken down by the picture
                    // coming back; see [reconnectAttempt].
                    reconnectAttempt = attempt + 1
                    val forSerial = tuneSerial
                    // Catch-up counts. It carries isLive = false because it
                    // seeks like a file, but the same panel serves it against
                    // the same connection cap — PlayerScreen's prepare effect
                    // already waits for a slot on that basis. Left on isLive
                    // alone, a catch-up reconnect skipped the wait entirely
                    // and raced the panel for the slot it had just dropped.
                    val capped = request.isLive || request.isCatchup
                    val live = request.isLive
                    // Read now, not inside the wait. The top of onError
                    // captured the engine's live position for exactly this,
                    // and the 2Hz poll goes on writing session.positionMs from
                    // an engine that is about to be stopped — so a film left
                    // to read it a backoff later could resume from whatever
                    // the poll had last seen rather than from where it broke.
                    val resumeAt = retryPositionMs
                    reconnectJob = scope.launch {
                        // Let go of the connection BEFORE asking for one. A
                        // stall that never threw leaves the player sitting in
                        // BUFFERING with its socket open, and the wait below
                        // polls the panel until a slot frees — so on a
                        // one-connection line the app was waiting for itself
                        // to let go, every time, for the full timeout. A
                        // thrown error has already idled the player, so this
                        // costs that path nothing.
                        //
                        // Only when nothing is coming out of it. A watchdog
                        // can raise a fault over a picture that is still
                        // rendering — a latched audio refusal falls through to
                        // this rung with the video playing fine — and stopping
                        // that would black the screen for the whole backoff
                        // when the playAt at the end of it was going to
                        // re-open anyway. It also keeps the idle report below
                        // from reading as a viewer's pause; see pauseStartedMs.
                        engine?.takeIf { !it.isPlaying }?.stop()
                        // On a capped line, wait for the panel to free the
                        // slot the dropped stream still holds, and reconnect
                        // the moment it does; otherwise the fixed backoff. See
                        // awaitLiveSlot.
                        val waited = capped && (awaitLiveSlot?.invoke() ?: false)
                        if (!waited) delay(reconnectDelayMs(live, attempt))
                        // The viewer zapped or the ladder moved on while we
                        // waited: this reconnect is for a stream they left.
                        if (tuneSerial != forSerial) return@launch
                        engine?.let { it.playAt(it.currentIndex, resumeAt) }
                    }
                }

                else -> {
                    // Just the reason: the card's title already says it
                    // couldn't play, and "Couldn't play this — Couldn't play"
                    // was the sentence on screen.
                    reconnectAttempt = 0
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
        retriesLeft = reconnectDelaysMs(request.isLive).size
        liveFormatStage = 0
        sourceStage = 0
        stallClock.clear()
        stallTimer = null
        deathTimer = null
        healthTimer = null
        reconnectJob = null
        reconnectAttempt = 0
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
        // The other two exits — the ladder running out, and resetLadder —
        // both clear this. Left set, a later re-open from inside the engine
        // (a tunnelled refusal re-opening where it was) calls clearError and
        // brings the card back captioned "Reconnecting… (2 of 3)" with no
        // reconnect anywhere in flight.
        reconnectAttempt = 0
        reconnectJob = null
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
            // Unless a reconnect had it. Read before the write below, which
            // clears it: that reconnect was the only thing going to re-open
            // this stream, the tuning setter has just cancelled it, and the
            // engine behind it is stopped — so returning here would leave an
            // idle player with nothing in flight and no watchdog able to arm.
            val wasReconnecting = reconnectAttempt > 0
            tuning = !engine.isPlaying
            if (wasReconnecting) engine.playAt(target)
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
        engine?.let { it.playAt(it.currentIndex, retryPositionMs) }
        tuning = true
    }

    /**
     * Where a retry of the current item re-opens. Live re-joins at the edge;
     * a film picks up where it was — [positionMs] is captured from the
     * engine at the top of onError, before anything recovers, for exactly
     * this. The retry used to open the item by index alone, which is the
     * engine's "from the top", so every reconnected film restarted at 0:00.
     */
    private val retryPositionMs: Long
        get() = if (request.isLive || request.isCatchup) 0L else positionMs

}
