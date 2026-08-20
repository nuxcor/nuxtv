package com.agoro.tv.player

import android.content.Context
import android.net.Uri
import android.view.View
import com.agoro.tv.data.PlayableItem
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * libVLC doesn't expose a track's language separately — it folds it into the
 * display name, usually in brackets: "Track 1 - [English]". Best-effort
 * extraction; null when the name carries no bracketed language.
 */
private fun languageFromTrackName(name: String?): String? =
    name?.let { Regex("\\[([^\\]]+)\\]").find(it)?.groupValues?.get(1)?.trim() }
        ?.takeIf { it.isNotBlank() }

/**
 * libVLC backend. VLC's demuxers/decoders handle many streams ExoPlayer
 * rejects (odd TS muxing, exotic codecs), which is why it exists here.
 * The playlist is managed manually — VLC plays one Media at a time.
 */
/**
 * @param requestAudioFocus Whether this player takes audio focus and exposes
 * a media session. The guide's muted preview passes false — a silent preview
 * must never yank focus or transport keys from actual playback.
 */
class VlcEngine(
    context: Context,
    preferHighestQuality: Boolean = true,
    requestAudioFocus: Boolean = true,
) : PlayerEngine {

    override val name = "VLC"
    override var listener: PlayerEngine.Listener? = null
    override var onTransportPlay: (() -> Unit)? = null
    override var onTransportPause: (() -> Unit)? = null

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
    private var live = false
    private var playing = false
    private var released = false
    private var pendingSeekMs: Long = 0
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // libVLC doesn't manage audio focus itself the way ExoPlayer does, so the
    // engine drives it: request on play, abandon on pause/release. Loss
    // pauses; a duck request lowers volume instead.
    private val audioFocus: AudioFocusHelper? = if (requestAudioFocus) {
        AudioFocusHelper(
            context,
            onLoss = {
                mainHandler.post { if (!released && mediaPlayer.isPlaying) mediaPlayer.pause() }
            },
            onDuck = { ducked ->
                mainHandler.post { if (!released) mediaPlayer.volume = if (ducked) 30 else 100 }
            },
        )
    } else null

    // Minimal MediaSessionCompat shim so assistant/CEC play-pause reaches VLC
    // playback too; media3's session wraps a Player interface VLC doesn't
    // implement. Posts no notification of its own.
    private val mediaSession: android.support.v4.media.session.MediaSessionCompat? =
        if (requestAudioFocus) {
            android.support.v4.media.session.MediaSessionCompat(
                context.applicationContext,
                "AgoroVlcSession",
            ).apply {
                setCallback(object : android.support.v4.media.session.MediaSessionCompat.Callback() {
                    // Route through the owning session when one is attached —
                    // it knows a long-paused live stream must rejoin the live
                    // edge, not play out a dead buffer.
                    override fun onPlay() {
                        if (released || mediaPlayer.isPlaying) return
                        onTransportPlay?.invoke() ?: playPause()
                    }

                    override fun onPause() {
                        if (released || !mediaPlayer.isPlaying) return
                        onTransportPause?.invoke() ?: playPause()
                    }

                    override fun onStop() {
                        if (released || !mediaPlayer.isPlaying) return
                        onTransportPause?.invoke() ?: playPause()
                    }
                })
                isActive = true
            }
        } else null

    private fun updateSessionState(playing: Boolean) {
        val session = mediaSession ?: return
        val state = android.support.v4.media.session.PlaybackStateCompat.Builder()
            .setActions(
                android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY or
                    android.support.v4.media.session.PlaybackStateCompat.ACTION_PAUSE or
                    android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    android.support.v4.media.session.PlaybackStateCompat.ACTION_STOP
            )
            .setState(
                if (playing) android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING
                else android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED,
                positionMs,
                1f,
            )
            .build()
        mainHandler.post { if (!released) session.setPlaybackState(state) }
    }

    init {
        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    playing = true
                    updateSessionState(playing = true)
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
                    updateSessionState(playing = false)
                    listener?.onPlayingChanged(playing = false, buffering = false)
                }

                MediaPlayer.Event.Buffering ->
                    listener?.onPlayingChanged(playing = playing, buffering = event.buffering < 100f)

                MediaPlayer.Event.EndReached ->
                    // Never mutate the MediaPlayer from its own event thread.
                    if (index < items.size - 1) {
                        mainHandler.post { playAt(index + 1) }
                    } else if (live) {
                        // A live stream has no legitimate end — libVLC emits
                        // EndReached (not EncounteredError) when the provider
                        // closes the connection. Reported as a pause, this
                        // showed a Paused icon over a dead picture and the
                        // reconnect/fallback ladder never ran.
                        listener?.onError("The stream ended unexpectedly")
                    } else {
                        listener?.onPlayingChanged(playing = false, buffering = false)
                    }

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

    override fun prepare(
        items: List<PlayableItem>,
        startIndex: Int,
        startPositionMs: Long,
        isLive: Boolean,
    ) {
        this.items = items
        this.live = isLive
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
        audioFocus?.request()
        mediaPlayer.play()
        listener?.onItemChanged(index)
    }

    // These four lacked the released guard the rest of the class has. libVLC
    // throws IllegalStateException once the native player is freed, so a D-pad
    // press still in flight while the screen is disposed — or while the engine
    // is being swapped for the ExoPlayer fallback — crashed the app.
    override fun playPause() {
        if (released) return
        if (mediaPlayer.isPlaying) {
            audioFocus?.abandon()
            mediaPlayer.pause()
        } else {
            audioFocus?.request()
            mediaPlayer.play()
        }
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        if (mediaPlayer.isSeekable) mediaPlayer.time = positionMs.coerceAtLeast(0)
    }

    override fun next() = playAt((index + 1).coerceAtMost(items.size - 1))

    override fun previous() = playAt((index - 1).coerceAtLeast(0))

    override fun setMuted(muted: Boolean) {
        // libVLC takes 0-100 and returns non-zero on failure, which happens if
        // the media isn't open yet; the preview re-applies after prepare.
        mediaPlayer.volume = if (muted) 0 else 100
    }

    override fun release() {
        released = true
        listener = null
        audioFocus?.abandon()
        mediaSession?.run {
            isActive = false
            release()
        }
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

    // libVLC's bindings expose audio tracks as bare descriptions and video
    // tracks without colour transfer, so neither HDR nor a channel layout can
    // be read back honestly. Badges stay off on the VLC engine rather than
    // guessing from a codec name — ExoPlayer, the default, reports both.
    override val hdrFormat: String? get() = null
    override val audioFormatLabel: String? get() = null

    // VLC reports the rate as a rational, and leaves the denominator at 0 on
    // streams that never declared one.
    override val videoFrameRate: Float?
        get() = if (released) null else runCatching {
            mediaPlayer.currentVideoTrack?.let { t ->
                (t.frameRateNum.toFloat() / t.frameRateDen).takeIf {
                    t.frameRateDen > 0 && t.frameRateNum > 0
                }
            }
        }.getOrNull()

    // --- track selection ------------------------------------------------------

    override fun audioTracks(): List<Track> =
        if (released) emptyList()
        else mediaPlayer.audioTracks.orEmpty()
            .filter { it.id != -1 } // -1 is VLC's "Disable" pseudo-track
            .map {
                Track(
                    id = it.id.toString(),
                    label = it.name,
                    selected = it.id == mediaPlayer.audioTrack,
                    language = languageFromTrackName(it.name),
                )
            }

    override fun textTracks(): List<Track> =
        if (released) emptyList()
        else mediaPlayer.spuTracks.orEmpty()
            .filter { it.id != -1 }
            .map {
                Track(
                    id = it.id.toString(),
                    label = it.name,
                    selected = it.id == mediaPlayer.spuTrack,
                    language = languageFromTrackName(it.name),
                )
            }

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
