package com.nuxcor.nuxtv.player

import android.content.Context
import android.view.View
import com.nuxcor.nuxtv.data.PlayableItem

/**
 * Minimal playback contract implemented by both the ExoPlayer and libVLC
 * backends, so the player UI is engine-agnostic and streams that one engine
 * can't decode can be retried on the other.
 */
data class Track(val id: String, val label: String, val selected: Boolean)

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

    fun audioTracks(): List<Track>
    fun textTracks(): List<Track>
    fun selectAudioTrack(id: String)

    /** null disables subtitles. */
    fun selectTextTrack(id: String?)

    var listener: Listener?

    interface Listener {
        fun onItemChanged(index: Int)
        fun onPlayingChanged(playing: Boolean, buffering: Boolean)
        fun onError(message: String)
    }
}
