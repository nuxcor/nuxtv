package com.nuxcor.nuxtv.player

import android.content.Context
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.nuxcor.nuxtv.data.PlayableItem

@OptIn(UnstableApi::class)
class ExoEngine(context: Context) : PlayerEngine {

    override val name = "ExoPlayer"
    override var listener: PlayerEngine.Listener? = null

    private val player: ExoPlayer = run {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("NuxTV/1.0")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        // Wrap the HTTP factory so file:// URIs (recordings) also resolve.
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                listener?.onItemChanged(player.currentMediaItemIndex)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                listener?.onPlayingChanged(
                    playing = player.isPlaying,
                    buffering = playbackState == Player.STATE_BUFFERING,
                )
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                listener?.onPlayingChanged(
                    playing = isPlaying,
                    buffering = player.playbackState == Player.STATE_BUFFERING,
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                listener?.onError(error.errorCodeName.removePrefix("ERROR_CODE_"))
            }
        })
    }

    override fun createView(context: Context): View =
        PlayerView(context).apply {
            useController = false
            player = this@ExoEngine.player
            keepScreenOn = true
        }

    override fun prepare(items: List<PlayableItem>, startIndex: Int, startPositionMs: Long) {
        player.setMediaItems(
            items.map { item ->
                MediaItem.Builder()
                    .setUri(item.url)
                    .setMediaMetadata(
                        MediaMetadata.Builder().setTitle(item.title).setArtist(item.subtitle).build()
                    )
                    .build()
            },
            startIndex,
            if (startPositionMs > 0) startPositionMs else C.TIME_UNSET,
        )
        player.playWhenReady = true
        player.prepare()
    }

    override fun playPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs.coerceAtLeast(0))

    override fun next() = player.seekToNextMediaItem()

    override fun previous() = player.seekToPreviousMediaItem()

    override fun playAt(index: Int) {
        player.seekToDefaultPosition(index)
        player.playWhenReady = true
    }

    override fun release() {
        listener = null
        player.release()
    }

    override val isPlaying: Boolean get() = player.isPlaying
    override val currentIndex: Int get() = player.currentMediaItemIndex
    override val positionMs: Long get() = player.currentPosition
    override val durationMs: Long
        get() = player.duration.takeIf { it != C.TIME_UNSET && !player.isCurrentMediaItemLive } ?: 0L

    // --- track selection ------------------------------------------------------

    private fun tracksOf(trackType: Int): List<Track> =
        player.currentTracks.groups
            .withIndex()
            .filter { (_, group) -> group.type == trackType }
            .flatMap { (groupIndex, group) ->
                (0 until group.length).map { trackIndex ->
                    val format = group.getTrackFormat(trackIndex)
                    val label = listOfNotNull(
                        format.label ?: format.language?.uppercase(),
                        format.codecs,
                    ).joinToString(" • ").ifBlank { "Track ${trackIndex + 1}" }
                    Track(
                        id = "$groupIndex:$trackIndex",
                        label = label,
                        selected = group.isTrackSelected(trackIndex),
                    )
                }
            }

    override fun audioTracks(): List<Track> = tracksOf(C.TRACK_TYPE_AUDIO)
    override fun textTracks(): List<Track> = tracksOf(C.TRACK_TYPE_TEXT)

    private fun applyOverride(trackType: Int, id: String) {
        val (groupIndex, trackIndex) = id.split(":").map { it.toInt() }
        val group = player.currentTracks.groups.getOrNull(groupIndex) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(trackType, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
    }

    override fun selectAudioTrack(id: String) = applyOverride(C.TRACK_TYPE_AUDIO, id)

    override fun selectTextTrack(id: String?) {
        if (id == null) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        } else {
            applyOverride(C.TRACK_TYPE_TEXT, id)
        }
    }
}
