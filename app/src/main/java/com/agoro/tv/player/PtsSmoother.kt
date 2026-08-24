package com.agoro.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/**
 * Keeps an audio timeline continuous across the spurious forward jumps a
 * software decoder's output timestamps can take on a live transport stream.
 *
 * The sink checks every buffer's timestamp against the one it expects from
 * the frames it has already been given, and past a 200ms disagreement it
 * re-anchors its clock to the new timestamp (media3 1.11
 * `DefaultAudioSink.handleBuffer`; the disagreement is reported as an
 * `UnexpectedDiscontinuityException` but never thrown). Because that clock
 * is the player's clock, a one-second jump is a second of video skipped or
 * frozen, on every jump. The FFmpeg AC-3/E-AC-3 decoder emits such jumps on
 * live single-PMT MPEG-TS — roughly one a second on some feeds (AerioTV's
 * root-cause note against media3 1.4.1; the 200ms gate is unchanged in
 * 1.11) — while the samples themselves are continuous, so the frame count
 * is the truth and the timestamp is the lie.
 *
 * So: a forward jump between [MIN_SPURIOUS_JUMP_US] and [MAX_SPURIOUS_JUMP_US]
 * on decoded audio is replaced by the last normal inter-buffer cadence, and
 * the difference is carried as an offset so the output stays continuous.
 * Normal buffers, backward jumps and real gaps pass through untouched and
 * drop the offset — a genuine seek or splice is never mis-corrected. A
 * no-op on a stream whose timestamps are continuous, which is every VOD
 * file and most live channels; see [AudioOutputPolicy.smoothTimestamps] for
 * when it is built at all.
 */
internal class PtsSmoother {
    private var hasLast = false
    private var lastInUs = 0L

    /** Output = input − offset; grows by each spurious jump, cleared by a real one. */
    private var offsetUs = 0L

    /** The last normal inter-buffer delta, or -1 until one has been seen. */
    private var cadenceUs = -1L

    fun reset() {
        hasLast = false
        offsetUs = 0L
        cadenceUs = -1L
    }

    fun rewrite(inUs: Long): Long {
        if (!hasLast) {
            hasLast = true
            lastInUs = inUs
            return inUs - offsetUs
        }
        val delta = inUs - lastInUs
        // The same buffer offered again after the sink declined it: no
        // opinion, and the cadence must not be clobbered by a zero.
        if (delta == 0L) return inUs - offsetUs
        lastInUs = inUs
        when {
            delta < 0L || delta > MAX_SPURIOUS_JUMP_US -> {
                // Backward, or a real gap: the stream moved, follow it.
                offsetUs = 0L
                cadenceUs = -1L
            }
            delta >= MIN_SPURIOUS_JUMP_US -> {
                // A jump too small to be a seek and too large to be a
                // buffer: keep the cadence, carry the difference.
                if (cadenceUs > 0L) offsetUs += delta - cadenceUs
            }
            else -> cadenceUs = delta
        }
        return inUs - offsetUs
    }

    companion object {
        const val MIN_SPURIOUS_JUMP_US = 150_000L
        const val MAX_SPURIOUS_JUMP_US = 1_500_000L
    }
}

/**
 * The sink wrapper that applies [PtsSmoother] — to decoded (PCM) input only.
 * Passthrough buffers are the stream's own timestamps on the way to the HAL,
 * and are left alone.
 */
@OptIn(UnstableApi::class)
internal class PtsSmoothingAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    private val smoother = PtsSmoother()
    private var active = false

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        active = audioSinkConfig.format.sampleMimeType == MimeTypes.AUDIO_RAW
        smoother.reset()
        super.configure(audioSinkConfig)
    }

    override fun handleDiscontinuity() {
        smoother.reset()
        super.handleDiscontinuity()
    }

    override fun flush() {
        smoother.reset()
        super.flush()
    }

    override fun reset() {
        smoother.reset()
        super.reset()
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val pts = if (active) smoother.rewrite(presentationTimeUs) else presentationTimeUs
        return super.handleBuffer(buffer, pts, encodedAccessUnitCount)
    }
}
