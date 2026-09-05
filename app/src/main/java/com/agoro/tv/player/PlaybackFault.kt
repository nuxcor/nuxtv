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
     * A LIVE stream that reached a clean end of stream.
     *
     * Nothing live legitimately ends, so this is treated as transient and
     * climbs the same ladder — a provider that closes a socket mid-programme
     * looks exactly like this, and reconnecting is usually right. What it
     * carries is the other possibility, for the card at the top of the
     * ladder: the thing being watched was a match or a show, and it is over.
     * "Can't play Sky Sports" is the wrong sentence for that.
     */
    ENDED,

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
    403 -> "your provider refused the connection — another stream may still be open"
    400, 404, 410 -> "your provider no longer offers this stream"
    429 -> "your provider says too many streams are open"
    in 500..599 -> "your provider is having trouble"
    else -> null
}
