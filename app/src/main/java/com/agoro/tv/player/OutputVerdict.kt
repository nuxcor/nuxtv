package com.agoro.tv.player

/**
 * What the output watchdog makes of one look at a stream that says it is
 * playing.
 *
 * Extracted from [ExoEngine] rather than left as a `when` inside a Runnable
 * because of what the neighbours cost: two watchdog false positives have
 * shipped from that file, both of them a branch that read correctly and fired
 * on a stream it had no business judging. A rule about five booleans is only
 * checkable if it is written somewhere five booleans can be handed to it.
 * Same reasoning as [StallMonitor], which says so itself.
 */
internal enum class OutputVerdict {
    /** The sink took the format and produced silence. */
    SILENT_AUDIO,

    /**
     * A video decoder is running and drawing nothing — sound with a black
     * screen. The DEVICE's failure: the stream handed over a picture and the
     * decoder kept it. Answered by re-initialising rather than reusing.
     */
    BLACK_PICTURE,

    /** The no-picture shape holds, but it has not held long enough to act on. */
    NO_PICTURE_YET,

    /**
     * Sound is playing and the stream never offered a picture at all — no
     * video decoder was ever armed, so there is no decoder to blame and
     * nothing for [BLACK_PICTURE]'s remedy to re-initialise.
     *
     * This is the STREAM's failure, and until now nothing named it. The audio
     * branch declines because the sound is fine; the video branch is written
     * `videoArmed && …` and a pipe that sent no video never armed one;
     * [StallMonitor] declines because the audio is advancing the clock, so
     * the player is genuinely playing. The viewer sits on black with
     * commentary over it and no error card, which is where the live sport
     * reports come from: a PPV slot the provider has already reassigned goes
     * on serving audio, or serves a slate, long after the match it was named
     * for has finished.
     */
    NO_PICTURE,
}

/**
 * How many consecutive grace windows the no-picture shape must survive.
 *
 * Two, where the other branches act on one. A stream whose video arrives a
 * beat after its audio is a real thing — a mid-event join, a slot that opens
 * on an audio-first segment — and it is indistinguishable from a dead pipe
 * inside one window. It is distinguishable across two, and the cost of
 * waiting is six more seconds on a screen that is already black; the cost of
 * being wrong is hopping a source that was about to work.
 */
internal const val NO_PICTURE_WINDOWS = 2

/**
 * The verdict, or null when nothing is wrong that this watchdog can see.
 *
 * @param audioArmed an audio format was accepted by the sink.
 * @param audioAdvancing the sink reported the audio position moving — the
 * clock is running, so the player really is playing.
 * @param videoArmed a video decoder was given a format.
 * @param firstFrameDrawn a frame has been rendered on the current surface.
 * @param tunnelling the HAL is drawing. media3 only learns of the first frame
 * through the vendor's callback there and some boxes never send it
 * (androidx/media #1169), so no picture verdict may be read off it at all —
 * a tunnelled decoder that truly freezes is [TunnelPolicy]'s case.
 * @param blankWindows how many grace windows have already closed on the
 * no-picture shape.
 */
internal fun outputVerdict(
    audioArmed: Boolean,
    audioAdvancing: Boolean,
    videoArmed: Boolean,
    firstFrameDrawn: Boolean,
    tunnelling: Boolean,
    blankWindows: Int,
): OutputVerdict? = when {
    // First, and unconditionally: silence is the failure the viewer notices
    // fastest, and a stream with no sound has not earned a verdict on its
    // picture yet either.
    audioArmed && !audioAdvancing -> OutputVerdict.SILENT_AUDIO

    // There is a picture, or there is no clock to judge one against, or the
    // vendor is drawing and will not say. Nothing to answer for.
    firstFrameDrawn || tunnelling || !audioAdvancing -> null

    // Sound with a black screen, and a decoder that took the format.
    videoArmed -> OutputVerdict.BLACK_PICTURE

    // Sound with a black screen and no video decoder at all.
    blankWindows >= NO_PICTURE_WINDOWS -> OutputVerdict.NO_PICTURE
    else -> OutputVerdict.NO_PICTURE_YET
}
