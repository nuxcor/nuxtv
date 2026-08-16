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
class VlcEngine(context: Context, preferHighestQuality: Boolean = true) : PlayerEngine {

    override val name = "VLC"
    override var listener: PlayerEngine.Listener? = null

    private val libVlc = LibVLC(
        context.applicationContext,
        arrayListOf(
            // Deeper caches than the default ride out provider hiccups, which
            // are the usual cause of "the picture keeps breaking up".
            "--network-caching=3000",
            "--live-caching=3000",
            "--file-caching=1500",
            // IPTV transport streams routinely carry broken PCR timestamps.
            // Ignoring the stream clock stops the stutter that causes.
            "--clock-jitter=0",
            "--clock-synchro=0",
            // Mirrors the Settings preference that ExoPlayer applies via its
            // track selector, so switching engines doesn't silently change
            // picture quality. Unlike ExoPlayer this is fixed at construction,
            // so a change takes effect when the player is next opened.
            if (preferHighestQuality) "--adaptive-logic=highest" else "--adaptive-logic=rate",
            // Never let the adaptive demuxer cap itself below the panel.
            "--adaptive-maxwidth=3840",
            "--adaptive-maxheight=2160",
            "--http-reconnect",
            "--http-user-agent=$USER_AGENT",
        ),
    )
    private val mediaPlayer = MediaPlayer(libVlc)

    private var videoLayout: VLCVideoLayout? = null
    private var items: List<PlayableItem> = emptyList()
    private var index: Int = 0
    private var playing = false
    private var released = false
    private var pendingSeekMs: Long = 0
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    init {
        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    playing = true
                    // VLC ignores seeks before the media is open; apply them now.
                    if (pendingSeekMs > 0) {
                        val seek = pendingSeekMs
                        pendingSeekMs = 0
                        mainHandler.post { if (!released && mediaPlayer.isSeekable) mediaPlayer.time = seek }
                    }
                    listener?.onPlayingChanged(playing = true, buffering = false)
                }

                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> {
                    playing = false
                    listener?.onPlayingChanged(playing = false, buffering = false)
                }

                MediaPlayer.Event.Buffering ->
                    listener?.onPlayingChanged(playing = playing, buffering = event.buffering < 100f)

                MediaPlayer.Event.EndReached ->
                    // Never mutate the MediaPlayer from its own event thread.
                    if (index < items.size - 1) mainHandler.post { playAt(index + 1) }
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
        pendingSeekMs = startPositionMs
        playAt(startIndex)
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

    // These four lacked the released guard the rest of the class has. libVLC
    // throws IllegalStateException once the native player is freed, so a D-pad
    // press still in flight while the screen is disposed — or while the engine
    // is being swapped for the ExoPlayer fallback — crashed the app.
    override fun playPause() {
        if (released) return
        if (mediaPlayer.isPlaying) mediaPlayer.pause() else mediaPlayer.play()
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
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

    override val videoResolution: Pair<Int, Int>?
        get() = if (released) null else runCatching {
            mediaPlayer.currentVideoTrack?.let { t -> (t.width to t.height).takeIf { t.height > 0 } }
        }.getOrNull()

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

    /**
     * VLC resolves adaptive ladders internally and exposes only the rung it is
     * playing, so there is no rendition list to offer. The single entry is
     * reported so the UI can still show what is actually being decoded.
     */
    override fun videoTracks(): List<Track> = emptyList()

    override fun selectVideoTrack(id: String?) = Unit

    override fun selectAudioTrack(id: String) {
        if (released) return
        id.toIntOrNull()?.let { mediaPlayer.setAudioTrack(it) }
    }

    override fun selectTextTrack(id: String?) {
        if (released) return
        mediaPlayer.setSpuTrack(id?.toIntOrNull() ?: -1)
    }

    override fun setScaleMode(mode: Int) {
        if (released) return
        mediaPlayer.videoScale = when (mode) {
            1 -> MediaPlayer.ScaleType.SURFACE_FILL
            2 -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
        }
    }

    override fun setSpeed(speed: Float) {
        if (!released) mediaPlayer.rate = speed
    }
}
