package com.agoro.tv.player

/**
 * Whether this process has caught the device's audio output refusing what it
 * advertised — and so decodes every audio track to PCM in the app from then on.
 *
 * ExoPlayer takes the platform's word on passthrough: if the HDMI EDID or
 * `getDirectProfilesForAttributes` lists AC-3, it hands the encoded stream
 * straight to an AudioTrack for the TV or receiver to decode, and the bundled
 * FFmpeg decoder is never asked. Boxes lie about this in both directions — a
 * display that advertises Dolby while its output is set to PCM, an ARC link
 * that cannot carry it, a HAL that refuses the tunnelled variant — and the
 * platform's answer is "AudioTrack init failed". ExoPlayer has no fallback
 * for it (androidx/media #3223, #9588). Live channels never show it, because
 * they carry stereo AAC; films carry 5.1 Dolby, so it is films that die.
 *
 * mpv, Kodi and the better ExoPlayer front ends (OwnTV, NuvioTV) all answer
 * the same way: stop bitstreaming and decode to PCM, automatically. This is
 * that latch. Process-wide, because the fault lives in the output path and
 * not in the stream: once one film has proved the TV cannot play what it
 * claims, the next must not be made to prove it again. In memory only, on
 * purpose — an HDMI handshake or a soundbar that woke in the wrong mode is a
 * statement about this session, and persisting it would silently downgrade a
 * capable receiver forever; mpv retries passthrough on its next start for the
 * same reason. No setting: a viewer cannot be asked which of their TV's audio
 * paths works, and the failure answers it. See [TunnelPolicy], which is the
 * same shape of decision for the video side.
 */
internal object AudioOutputPolicy {
    /** True once the output has refused a track; every player built after this decodes to PCM. */
    @Volatile
    var pcmOnly: Boolean = false
        private set

    /** What was refused, for the log; null until latched. */
    @Volatile
    var reason: String? = null
        private set

    /**
     * Latches PCM-only. True the first time — the caller's cue to rebuild the
     * player — and false thereafter: a fault that arrives with the latch
     * already set is one PCM did not fix, and rebuilding again would only
     * repeat it in front of the viewer. The ladder then falls through to its
     * ordinary retries and the error card.
     */
    @Synchronized
    fun latch(why: String): Boolean {
        if (pcmOnly) return false
        pcmOnly = true
        reason = why
        return true
    }

    /**
     * True once decoded audio has shown the sink enough spurious timestamp
     * jumps; every player built after this smooths them. See [PtsSmoother].
     */
    @Volatile
    var smoothTimestamps: Boolean = false
        private set

    private val discontinuities = ArrayDeque<Long>()

    /**
     * Notes one sink-reported discontinuity on decoded audio. True when this
     * one turned the latch — [DISCONTINUITY_LIMIT] inside
     * [DISCONTINUITY_WINDOW_MS] — which is the caller's cue to rebuild. One
     * is a splice; several a minute is the decoder's clock, not the stream's.
     */
    @Synchronized
    fun noteDiscontinuity(nowMs: Long): Boolean {
        if (smoothTimestamps) return false
        discontinuities.addLast(nowMs)
        while (discontinuities.isNotEmpty() && nowMs - discontinuities.first() > DISCONTINUITY_WINDOW_MS) {
            discontinuities.removeFirst()
        }
        if (discontinuities.size < DISCONTINUITY_LIMIT) return false
        smoothTimestamps = true
        discontinuities.clear()
        return true
    }

    const val DISCONTINUITY_LIMIT = 3
    const val DISCONTINUITY_WINDOW_MS = 60_000L

    /** Test seam. */
    @Synchronized
    internal fun reset() {
        pcmOnly = false
        reason = null
        smoothTimestamps = false
        discontinuities.clear()
    }
}
