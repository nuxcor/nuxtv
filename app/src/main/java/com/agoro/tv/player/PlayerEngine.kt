package com.agoro.tv.player

import android.content.Context
import android.view.View
import com.agoro.tv.data.PlayableItem

/**
 * Minimal playback contract implemented by both the ExoPlayer and libVLC
 * backends, so the player UI is engine-agnostic and streams that one engine
 * can't decode can be retried on the other.
 */
/** Some providers gate on a known UA, so both engines send the same one. */
internal const val USER_AGENT = "Agoro/2.9"

/** Sentinel id for "always the top rung", as opposed to a specific rendition. */
const val HIGHEST_QUALITY = "highest"

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
     * "Dolby Vision", "HDR10" or "HLG" when the decoded video carries one, null
     * for ordinary SDR. Read from the decoded format, never from the stream
     * name — providers write "HDR" into titles that carry nothing of the kind.
     */
    val hdrFormat: String?

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
        fun onError(message: String)
    }
}
