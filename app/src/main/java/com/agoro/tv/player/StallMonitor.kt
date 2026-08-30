package com.agoro.tv.player

/**
 * Whether a player that says it is PLAYING has actually stopped.
 *
 * This is the failure a viewer meets as "it says it's live and the screen is
 * black": no picture, no sound, no error card, and only leaving the player
 * and coming back in clears it.
 *
 * Every other watchdog in [ExoEngine] asks which COMPONENT failed, and each
 * of them is allowed to stand down. The audio branch stands down once the PCM
 * sink is latched — there is nothing left for it to change — and on a box
 * whose launch probe latches PCM before anything has played, that is every
 * stream. The video branch stands down while tunnelled, because there the
 * vendor draws and some boxes never report the frame (androidx/media #1169).
 * So a stream that produces NEITHER sound NOR a frame matches the audio
 * branch, which then declines, and the video branch is never reached. Nothing
 * else covers it either: the player sits in STATE_READY saying it is playing,
 * so there is no BUFFERING for the tunnel policy or the session's death timer
 * to read. The viewer waits it out.
 *
 * This one asks nothing about components and needs no vendor callback to
 * answer. A player that claims to be playing while its own clock stands still
 * is not playing, whatever the reason — and the remedy is the one the viewer
 * was already applying by hand: close the stream and open it again.
 *
 * Kept apart from the engine, and free of Android, because it is a rule about
 * a sequence of readings rather than about a player — which is the only way
 * it can be checked at all. Two watchdog false positives have shipped from
 * this file's neighbours; this one is testable.
 */
internal class StallMonitor(
    /** How long the clock may stand still before the stream is taken as dead. */
    private val graceMs: Long,
) {

    /** The position at the previous sample; [NO_BASELINE] before the first. */
    private var lastPositionMs = NO_BASELINE

    /** When the position was last seen to move — the clock the verdict is read off. */
    private var lastAdvancedAtMs = 0L

    /**
     * Records one reading and says whether the stream is stuck.
     *
     * @param positionMs the player's own position.
     * @param nowMs a monotonic clock; only playing time is ever sampled, so
     * the gap between readings is time the stream was supposed to be moving.
     * @param settling true inside a renderer reconfiguration's grace window —
     * a track change, a decoder re-initialisation, a surface handed back. Each
     * empties the decoders and refills them, which is a real pause in the
     * clock that nothing has gone wrong for.
     *
     * True at most once per stall: the verdict resets the monitor, so a
     * caller that goes on sampling gets one error, not one per poll.
     */
    fun sample(positionMs: Long, nowMs: Long, settling: Boolean): Boolean {
        // A seek is the one thing that moves the clock BACKWARDS, and it
        // marks a reconfiguration on its way past — so "not greater than the
        // last reading" only counts as standing still outside that window.
        if (lastPositionMs == NO_BASELINE || settling || positionMs > lastPositionMs) {
            lastPositionMs = positionMs
            lastAdvancedAtMs = nowMs
            return false
        }
        lastPositionMs = positionMs
        if (nowMs - lastAdvancedAtMs < graceMs) return false
        reset()
        return true
    }

    /** Drops the baseline: the next reading starts a fresh run, never a verdict. */
    fun reset() {
        lastPositionMs = NO_BASELINE
        lastAdvancedAtMs = 0L
    }

    private companion object {
        const val NO_BASELINE = -1L
    }
}
