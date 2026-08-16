package com.nuxcor.nuxtv.player

import android.content.Context
import android.view.View
import com.nuxcor.nuxtv.data.PlayableItem

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

    /** The video surface this engine renders into. Created once per engine instance. */
    fun createView(context: Context): View

    fun prepare(items: List<PlayableItem>, startIndex: Int, startPositionMs: Long = 0L)
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

    /** Decoded video size, or null before the first frame. */
    val videoResolution: Pair<Int, Int>?

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

    var listener: Listener?

    interface Listener {
        fun onItemChanged(index: Int)
        fun onPlayingChanged(playing: Boolean, buffering: Boolean)
        fun onError(message: String)
    }
}
