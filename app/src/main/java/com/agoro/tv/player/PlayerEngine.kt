package com.agoro.tv.player

import android.content.Context
import android.view.Display
import android.view.View
import com.agoro.tv.data.PlayableItem

/**
 * The playback contract the player UI is written against.
 *
 * One implementation, [ExoEngine]. The seam is kept because the UI genuinely
 * wants it — the guide's muted preview and the player build engines the same
 * way and neither should reach into ExoPlayer's internals — not because a
 * second backend is coming back. A stream that will not decode is now retried
 * on a more forgiving ExoPlayer rather than on another player entirely; see
 * [DecodeProfile].
 */
/** Some providers gate on a known UA. */
internal const val USER_AGENT = "Agoro/2.9"

/** Sentinel id for "always the top rung", as opposed to a specific rendition. */
const val HIGHEST_QUALITY = "highest"

/**
 * The transfer function a stream actually decoded with.
 *
 * Distinct from a display label: this is the machine-readable half the output
 * path needs. A decoded HDR frame is only half of HDR — the other half is a
 * display mode that can carry it, and a mode that carries HLG does not
 * necessarily carry HDR10 or Dolby Vision. PQ or HLG sent through an SDR
 * output is the grey, flat, desaturated picture viewers report as
 * "washed out", and the app cannot avoid it without knowing which of these
 * it is holding.
 */
enum class HdrType(
    /** How a viewer knows it, for the stream badges. */
    val label: String,
) {
    HDR10("HDR10"),
    HLG("HLG"),
    DOLBY_VISION("Dolby Vision");

    /**
     * [Display.HdrCapabilities] types that can carry this stream, best first.
     *
     * Not one-to-one: a panel that takes HDR10+ takes plain HDR10, and a
     * Dolby Vision stream the display can't take is played by the decoder's
     * HDR10-compatible base layer, so an HDR10 mode still beats an SDR one.
     */
    val carriers: List<Int>
        get() = when (this) {
            HDR10 -> listOf(
                Display.HdrCapabilities.HDR_TYPE_HDR10,
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS,
            )
            HLG -> listOf(Display.HdrCapabilities.HDR_TYPE_HLG)
            DOLBY_VISION -> listOf(
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION,
                Display.HdrCapabilities.HDR_TYPE_HDR10,
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS,
            )
        }

    companion object {
        /** Reads back what [name] persisted; null for an unknown or absent entry. */
        fun byName(name: String?): HdrType? = entries.firstOrNull { it.name == name }
    }
}

data class Track(
    val id: String,
    val label: String,
    val selected: Boolean,
    /**
     * False when the stream carries this rendition but the device can't decode
     * it. Shown rather than hidden: "2160p — this TV can't decode it" explains
     * a soft picture on a channel advertised as UHD; silently dropping the
     * track leaves the viewer with no picture quality and no reason.
     */
    val supported: Boolean = true,
    /**
     * The track's language as the engine reports it — a code ("en", "eng") or
     * a spelled-out name, or null when the stream doesn't say. Used to apply
     * and persist the viewer's preferred audio/subtitle language.
     */
    val language: String? = null,
)

/**
 * "1080p FHD • 5.4 Mbps" — the vocabulary viewers actually recognise, used for
 * both the video-rendition picker and the live resolution readout.
 * Width/height of 0 or less means the engine hasn't decoded a frame yet.
 */
fun qualityLabel(width: Int, height: Int, bitrate: Int = -1): String {
    val tier = when {
        height <= 0 -> null
        height >= 2000 -> "4K"
        height >= 1400 -> "2K"
        height >= 1000 -> "FHD"
        height >= 700 -> "HD"
        else -> "SD"
    }
    val resolution = if (height > 0) "${height}p" else "Unknown"
    val mbps = if (bitrate > 0) " • %.1f Mbps".format(bitrate / 1_000_000f) else ""
    return listOfNotNull(resolution, tier).joinToString(" ") + mbps
}

interface PlayerEngine {
    val name: String

    /**
     * Transport-key intents from system surfaces (media session, CEC,
     * assistant). When set, they route through the owning session — which
     * knows about live stale-buffer rejoin — instead of hitting the raw
     * engine. Null (the default, e.g. the muted guide preview) falls back to
     * the engine's own playPause.
     */
    var onTransportPlay: (() -> Unit)?
    var onTransportPause: (() -> Unit)?

    /** The video surface this engine renders into. Created once per engine instance. */
    fun createView(context: Context): View

    /**
     * @param isLive Live streams have no legitimate end: an end-of-stream
     * signal on one is a dropped connection and is reported through
     * [Listener.onError] so recovery can run, not as a quiet pause.
     */
    fun prepare(
        items: List<PlayableItem>,
        startIndex: Int,
        startPositionMs: Long = 0L,
        isLive: Boolean = false,
    )
    fun playPause()
    fun seekTo(positionMs: Long)
    fun next()
    fun previous()
    fun playAt(index: Int)
    fun release()

    val isPlaying: Boolean
    val currentIndex: Int
    val positionMs: Long

    /** <= 0 for live streams. */
    val durationMs: Long

    /**
     * Media buffered ahead of the playhead, in ms, or null when the engine
     * can't say. Read at the start of a stall to tell a starving line (next
     * to nothing buffered) from a renderer that has stopped consuming (plenty
     * buffered) — only the first is something another source can fix.
     */
    val bufferedAheadMs: Long?

    /** Decoded video size, or null before the first frame. */
    val videoResolution: Pair<Int, Int>?

    /**
     * Decoded frame rate in fps, or null when the stream doesn't declare one.
     * Drives display refresh matching: 25fps shown on a 60Hz output judders,
     * and there is no way to know that without this.
     */
    val videoFrameRate: Float?

    /**
     * The HDR flavour the decoded video carries, null for ordinary SDR. Read
     * from the decoded format, never from the stream name — providers write
     * "HDR" into titles that carry nothing of the kind.
     *
     * Drives the display's output mode and the colour mode of the window as
     * well as the badge: see [HdrType].
     */
    val hdrType: HdrType?

    /**
     * The decoded audio said the way a TV viewer recognises it: "Dolby Atmos",
     * "Dolby Digital+", "DTS-HD", "5.1". Null when it is ordinary stereo or the
     * engine can't tell.
     */
    val audioFormatLabel: String?

    fun audioTracks(): List<Track>
    fun textTracks(): List<Track>

    /**
     * Selectable video renditions. Adaptive sources (HLS/DASH) expose one per
     * bitrate ladder rung; single-rendition streams return an empty list.
     */
    fun videoTracks(): List<Track>

    fun selectAudioTrack(id: String)

    /** null disables subtitles. */
    fun selectTextTrack(id: String?)

    /** null restores adaptive selection ("Auto"). */
    fun selectVideoTrack(id: String?)

    /**
     * True only while "Highest available" is the standing choice — set from
     * the quality sheet, or re-applied per stream from the viewer's quality
     * preference. The default is adaptive (false): the selector climbs to the
     * top rung on its own, and pinning it regardless of the line macroblocks.
     */
    val isForcingHighest: Boolean

    /** 0 = fit, 1 = fill/stretch, 2 = zoom/crop. */
    fun setScaleMode(mode: Int)

    fun setSpeed(speed: Float)

    /**
     * Silences the engine without pausing it. Used by the guide preview, which
     * plays whatever channel focus rests on — audio that changed every time you
     * moved down a list would be unusable, and would fight whatever is already
     * playing behind the guide.
     */
    fun setMuted(muted: Boolean)

    var listener: Listener?

    interface Listener {
        fun onItemChanged(index: Int)
        fun onPlayingChanged(playing: Boolean, buffering: Boolean)
        /**
         * @param decodeFault true when the stream arrived but could not be
         * read or decoded — a malformed container, an unsupported profile, a
         * decoder that refused to start. Only those are worth re-opening on
         * [DecodeProfile.TOLERANT]; a 404 or a dropped line will fail exactly
         * the same way however forgiving the demuxer is, and offering software
         * decoding for one wastes the viewer's time on a promise it can't keep.
         * @param audioFault true when the platform refused the AudioTrack the
         * player asked for — the output turning down a passthrough encoding
         * or a tunnelled track it had advertised. The stream and the decoders
         * are fine; only the sink is wrong, and only rebuilding the player on
         * a PCM-only sink changes anything. See [AudioOutputPolicy].
         */
        fun onError(message: String, decodeFault: Boolean, audioFault: Boolean = false)
    }
}
