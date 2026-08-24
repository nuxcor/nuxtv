package com.agoro.tv.player

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities

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
 *
 * The failure is also asked for BEFORE any film, by [probe]: the first Dolby
 * film of every launch used to pay the refusal on screen — a banner, a
 * rebuild, a few seconds of black — before the latch turned. Kodi's sink
 * settles the same question by opening the AudioTrack at start-up and
 * trusting what happens rather than what was advertised; so does this.
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

    /**
     * The bitstream encodings films carry, and the ones a PCM-only output
     * most often claims and then refuses. DTS and TrueHD are left to the
     * rung: refusing one of those says less about the output than about the
     * format, and a latch that turned on it would take AC-3 passthrough away
     * from a box that plays it perfectly.
     */
    private val probeEncodings = listOf(C.ENCODING_AC3, C.ENCODING_E_AC3)

    private const val PROBE_SAMPLE_RATE = 48_000
    private const val PROBE_BUFFER_BYTES = 64 * 1024

    /**
     * Asks the platform which of [probeEncodings] it advertises for
     * passthrough, opens an AudioTrack for each — the request the player
     * would make for a 5.1 film — and latches PCM the moment one is refused.
     * Milliseconds, off the main thread, once per process: the same "retry
     * passthrough on the next start" that mpv does, answered before the
     * first frame instead of during it. A refusal the probe cannot see — a
     * tunnelled track, a write that fails mid-film — is still the rung's.
     */
    @OptIn(UnstableApi::class)
    fun probe(context: Context) {
        if (pcmOnly) return
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        val advertised = runCatching {
            val capabilities = AudioCapabilities.getCapabilities(
                context.applicationContext, attributes, /* routedDevice = */ null, emptyList(),
            )
            probeEncodings.filter { capabilities.supportsEncoding(it) }
        }.getOrElse { emptyList() }
        val refused = passthroughVerdict(advertised) { canOpen(it) }
        android.util.Log.i(
            "Agoro",
            "Passthrough probe: advertised=${advertised.map(::encodingName)} " +
                "refused=${refused?.let(::encodingName) ?: "none"}",
        )
        if (refused != null) {
            latch("probe: the platform advertised ${encodingName(refused)} passthrough and refused the AudioTrack")
        }
    }

    /** The first advertised encoding the output will not open, or null when it opens them all. */
    internal fun passthroughVerdict(advertised: List<Int>, opens: (Int) -> Boolean): Int? =
        advertised.firstOrNull { !opens(it) }

    /**
     * Whether the platform will create — not play — a passthrough track for
     * [encoding], shaped as the player shapes one for a 5.1 film. The
     * refusals this box has shown arrive here: `UnsupportedOperationException`
     * ("Cannot create AudioTrack") from the builder, or a track that comes
     * back uninitialised.
     */
    private fun canOpen(encoding: Int): Boolean = try {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(PROBE_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
                    .build(),
            )
            .setBufferSizeInBytes(PROBE_BUFFER_BYTES)
            .build()
        val initialised = track.state == AudioTrack.STATE_INITIALIZED
        track.release()
        initialised
    } catch (e: Exception) {
        false
    }

    private fun encodingName(encoding: Int): String = when (encoding) {
        C.ENCODING_AC3 -> "AC-3"
        C.ENCODING_E_AC3 -> "E-AC-3"
        else -> "encoding $encoding"
    }

    /** Test seam. */
    @Synchronized
    internal fun reset() {
        pcmOnly = false
        reason = null
        smoothTimestamps = false
        discontinuities.clear()
    }
}
