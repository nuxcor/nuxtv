package com.nuxcor.nuxtv.player

import android.content.Context
import android.net.Uri
import android.view.View
import com.nuxcor.nuxtv.data.PlayableItem
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * libVLC backend. VLC's demuxers/decoders handle many streams ExoPlayer
 * rejects (odd TS muxing, exotic codecs), which is why it exists here.
 * The playlist is managed manually — VLC plays one Media at a time.
 */
class VlcEngine(context: Context) : PlayerEngine {

    override val name = "VLC"
    override var listener: PlayerEngine.Listener? = null

    private val libVlc = LibVLC(
        context.applicationContext,
        arrayListOf("--network-caching=1500", "--http-user-agent=NuxTV/1.0"),
    )
    private val mediaPlayer = MediaPlayer(libVlc)

    private var videoLayout: VLCVideoLayout? = null
    private var items: List<PlayableItem> = emptyList()
    private var index: Int = 0
    private var playing = false
    private var released = false

    init {
        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    playing = true
                    listener?.onPlayingChanged(playing = true, buffering = false)
                }

                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> {
                    playing = false
                    listener?.onPlayingChanged(playing = false, buffering = false)
                }

                MediaPlayer.Event.Buffering ->
                    listener?.onPlayingChanged(playing = playing, buffering = event.buffering < 100f)

                MediaPlayer.Event.EndReached ->
                    if (index < items.size - 1) playAt(index + 1)
                    else listener?.onPlayingChanged(playing = false, buffering = false)

                MediaPlayer.Event.EncounteredError ->
                    listener?.onError("VLC could not play this stream")
            }
        }
    }

    override fun createView(context: Context): View =
        VLCVideoLayout(context).also { layout ->
            videoLayout = layout
            layout.keepScreenOn = true
            mediaPlayer.attachViews(layout, null, false, false)
        }

    override fun prepare(items: List<PlayableItem>, startIndex: Int, startPositionMs: Long) {
        this.items = items
        playAt(startIndex)
        if (startPositionMs > 0) mediaPlayer.time = startPositionMs
    }

    override fun playAt(index: Int) {
        if (released || index !in items.indices) return
        this.index = index
        val media = Media(libVlc, Uri.parse(items[index].url))
        media.setHWDecoderEnabled(true, false)
        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()
        listener?.onItemChanged(index)
    }

    override fun playPause() {
        if (mediaPlayer.isPlaying) mediaPlayer.pause() else mediaPlayer.play()
    }

    override fun seekTo(positionMs: Long) {
        if (mediaPlayer.isSeekable) mediaPlayer.time = positionMs.coerceAtLeast(0)
    }

    override fun next() = playAt((index + 1).coerceAtMost(items.size - 1))

    override fun previous() = playAt((index - 1).coerceAtLeast(0))

    override fun release() {
        released = true
        listener = null
        mediaPlayer.setEventListener(null)
        mediaPlayer.stop()
        mediaPlayer.detachViews()
        mediaPlayer.release()
        libVlc.release()
    }

    override val isPlaying: Boolean get() = !released && mediaPlayer.isPlaying
    override val currentIndex: Int get() = index
    override val positionMs: Long get() = if (released) 0 else mediaPlayer.time.coerceAtLeast(0)
    override val durationMs: Long get() = if (released) 0 else mediaPlayer.length.coerceAtLeast(0)

    // --- track selection ------------------------------------------------------

    override fun audioTracks(): List<Track> =
        if (released) emptyList()
        else mediaPlayer.audioTracks.orEmpty()
            .filter { it.id != -1 } // -1 is VLC's "Disable" pseudo-track
            .map { Track(id = it.id.toString(), label = it.name, selected = it.id == mediaPlayer.audioTrack) }

    override fun textTracks(): List<Track> =
        if (released) emptyList()
        else mediaPlayer.spuTracks.orEmpty()
            .filter { it.id != -1 }
            .map { Track(id = it.id.toString(), label = it.name, selected = it.id == mediaPlayer.spuTrack) }

    override fun selectAudioTrack(id: String) {
        id.toIntOrNull()?.let { mediaPlayer.setAudioTrack(it) }
    }

    override fun selectTextTrack(id: String?) {
        mediaPlayer.setSpuTrack(id?.toIntOrNull() ?: -1)
    }
}
