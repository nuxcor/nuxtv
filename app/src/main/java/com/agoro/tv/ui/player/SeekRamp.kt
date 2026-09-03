package com.agoro.tv.ui.player

/**
 * How far one press of LEFT or RIGHT moves the playhead, given how many
 * presses have already gone into the current scrub.
 *
 * A fixed step is the thing that makes D-pad seeking feel like work. Ten
 * seconds is right for finding the line of dialogue you missed and useless
 * for skipping a twenty-minute stretch — that is a hundred and twenty
 * presses. Every polished player ramps instead, and the ramp is what lets one
 * pair of keys serve both jobs.
 *
 * The first presses stay small because that is where precision matters and
 * where a viewer is most likely to be correcting an overshoot. It grows only
 * once the pattern says "I am travelling, not adjusting" — which a run of
 * presses in the same scrub is a decent proxy for.
 *
 * Pure, so the shape of the ramp can be argued with in a test rather than by
 * holding a remote.
 */
internal fun seekStepMs(pressIndex: Int): Long = when {
    // Correcting: the missed line, the skipped joke.
    pressIndex < 3 -> 10_000
    // Travelling: an ad break, a cold open, the recap.
    pressIndex < 8 -> 30_000
    // Crossing the film: a whole act, in about a dozen presses.
    else -> 60_000
}

/**
 * Where a scrub lands, given where it is now and which way the viewer pressed.
 *
 * Clamped at both ends, and the end is [END_GUARD_MS] SHORT of the duration
 * rather than the duration itself. Landing exactly on the end is
 * indistinguishable from the film finishing: it fires the end-of-item path
 * and offers the next episode, so a viewer trying to reach the last scene
 * would be shown the credits of the next one instead.
 */
internal fun seekTargetMs(
    currentTargetMs: Long,
    direction: Int,
    pressIndex: Int,
    durationMs: Long,
): Long {
    val step = seekStepMs(pressIndex) * if (direction < 0) -1 else 1
    val ceiling = (durationMs - END_GUARD_MS).coerceAtLeast(0L)
    return (currentTargetMs + step).coerceIn(0L, if (durationMs > 0) ceiling else Long.MAX_VALUE)
}

/** How close to the end a scrub may land; see [seekTargetMs]. */
internal const val END_GUARD_MS = 5_000L

/**
 * How long the scrub waits, after the last press, before it actually seeks.
 *
 * This is the whole point of the accumulator. Every press used to seek for
 * real, so skipping a minute was six separate seeks: six key-frame hunts, six
 * re-buffers, six stutters — on a box fetching over IPTV, where a seek is not
 * cheap. Holding the key made it worse in exact proportion to how far you
 * wanted to go.
 *
 * Now the presses only move a NUMBER, and one seek happens when the viewer
 * stops. Long enough that ordinary repeated pressing never commits early,
 * short enough that a single deliberate press still feels immediate.
 */
internal const val SEEK_COMMIT_MS = 450L
