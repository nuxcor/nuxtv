package com.agoro.tv.player

/**
 * Whose fault a playback failure is — which is the only thing the ladder
 * needs to know, because it decides which rung can do anything about it.
 *
 * Every rung changes exactly one thing: the URL's format, the source, the
 * demuxer and decoders, the audio sink, the video decoder's reuse, or simply
 * the moment. A failure reaches the rung that changes the thing that was
 * wrong, and is never offered a rung that would ask the same component the
 * same question — a 404 is not offered software decoding, a refused
 * AudioTrack is not offered another URL.
 */
enum class PlaybackFault {
    /** Network, a dropped line, a provider hiccup: a reconnect may well succeed. */
    TRANSIENT,

    /**
     * The provider said no in words a retry cannot change: the login was
     * rejected, or the stream is gone. Live still steps through its other
     * URL forms first — a 404 on `.ts` is often just the wrong form — but
     * nothing waits on a reconnect.
     */
    PERMANENT,

    /** The mux or the codec: worth the tolerant profile; see [DecodeProfile]. */
    DECODE,

    /**
     * The audio output refused, silenced or starved the track it advertised
     * it could play: worth the PCM-only sink; see [AudioOutputPolicy].
     */
    AUDIO_OUTPUT,

    /**
     * Decoded audio arriving with timestamps that jump, which the sink
     * answers by re-anchoring its clock — a skip the viewer sees on every
     * jump. Worth a sink that keeps the timeline continuous; see
     * [PtsSmoother]. Only ever raised once per process, when the latch turns.
     */
    AUDIO_TIMING,

    /**
     * The video decoder runs but never draws — sound with a black screen —
     * worth a decoder that is re-created rather than reused; see
     * [VideoOutputPolicy].
     */
    VIDEO_OUTPUT,
}

/** HTTP statuses on which a same-URL retry is pure wait. */
private val permanentHttpStatuses = setOf(400, 401, 404, 410)

/**
 * Classifies ExoPlayer's error constant, with the HTTP status when the
 * failure was the provider's answer. Unknown codes are transient: a retry
 * is the cheapest wrong guess.
 */
internal fun faultOf(errorCodeName: String, httpStatus: Int? = null): PlaybackFault = when {
    isDecodeFault(errorCodeName) -> PlaybackFault.DECODE
    isAudioOutputFault(errorCodeName) -> PlaybackFault.AUDIO_OUTPUT
    errorCodeName == "ERROR_CODE_IO_BAD_HTTP_STATUS" && httpStatus in permanentHttpStatuses ->
        PlaybackFault.PERMANENT
    else -> PlaybackFault.TRANSIENT
}

/**
 * The provider's HTTP status, said in words a viewer can act on; null for a
 * status that says nothing more than "no".
 *
 * On an Xtream line these are the ones that mean something: 401 is the
 * subscription, 403 is almost always the line's connection limit — the
 * previous stream lingering on the panel for a few seconds after a zap —
 * 404/410 is a stream the provider stopped carrying, 429 is the limit said
 * plainly, and 5xx is the panel itself.
 */
internal fun httpReason(status: Int): String? = when (status) {
    401 -> "your provider rejected the login — the subscription may have lapsed"
    // Xtream's own two, and they were falling into the 5xx bucket below and
    // coming out as "your provider is having trouble" — which sends a viewer
    // to wait for a panel that is working fine and has simply said no to
    // THEM. 2026-09-02: a second account listed every channel and played
    // none, and this line is what should have said why.
    //
    // The catalogue keeps working through both, which is what makes them
    // confusing: get_live_streams is an API call and answers for an account
    // that is no longer allowed to open a stream.
    512 -> "your provider has disabled or expired this account"
    513 -> "your provider rejected these credentials, or too many attempts too quickly"
    403 -> "your provider refused the connection — another stream may still be open"
    400, 404, 410 -> "your provider no longer offers this stream"
    429 -> "your provider says too many streams are open"
    in 500..599 -> "your provider is having trouble"
    else -> null
}
