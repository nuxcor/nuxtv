package com.agoro.tv.player

/**
 * Whether this process has caught a video decoder that runs but never
 * draws — and so builds every player after it with a decoder that is
 * re-created on each stream rather than flushed and reused.
 *
 * ExoPlayer keeps a MediaCodec across media items when the formats are
 * close enough, which is what makes a zap fast. Some Codec2 decoders come
 * out of that flush-and-reuse path decoding but not rendering: audio plays,
 * the screen stays black, and only a rebuild recovers (AerioTV's user log,
 * ExoPlayer #5119/#8329 for the surface-change variant on Sony and MediaTek
 * sets, where the same decoder goes black after the screensaver hands the
 * surface back). The stream is fine and so is the decoder's output; it is
 * the reuse that is broken, and re-initialising is the path that works on
 * every device anyone has logged.
 *
 * Latched, not assumed: a re-initialisation on every channel change costs
 * a black beat that the boxes which do not need it should never pay. The
 * same shape as [AudioOutputPolicy] and [TunnelPolicy] — in memory, once
 * per process, no setting — for the same reasons.
 */
internal object VideoOutputPolicy {
    /** True once a decoder has been caught not drawing; every player built after this re-initialises. */
    @Volatile
    var reinitOnly: Boolean = false
        private set

    /** What was seen, for the log; null until latched. */
    @Volatile
    var reason: String? = null
        private set

    /** Latches re-initialisation. True the first time — the cue to rebuild — false after. */
    @Synchronized
    fun latch(why: String): Boolean {
        if (reinitOnly) return false
        reinitOnly = true
        reason = why
        return true
    }

    /** Test seam. */
    @Synchronized
    internal fun reset() {
        reinitOnly = false
        reason = null
    }
}
