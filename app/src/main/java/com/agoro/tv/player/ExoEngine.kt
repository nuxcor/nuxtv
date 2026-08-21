package com.agoro.tv.player

import android.content.Context
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.agoro.tv.data.PlayableItem

/**
 * IPTV URLs are frequently extensionless (`/live/user/pass/1234`) or lie about
 * their container, and ExoPlayer's sniffing then falls back to progressive.
 * A MIME hint lets it pick the right media source up front.
 */
private fun mimeHintFor(url: String): String? {
    val path = url.substringBefore('?').lowercase()
    return when {
        path.endsWith(".m3u8") || path.contains("/hls/") -> MimeTypes.APPLICATION_M3U8
        path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
        path.endsWith(".ism") || path.contains("/manifest") -> MimeTypes.APPLICATION_SS
        url.startsWith("rtsp://", true) -> MimeTypes.APPLICATION_RTSP
        else -> null
    }
}

/**
 * @param requestAudioFocus Whether this player takes audio focus (ducking
 * music apps and pausing for other media, the way every TV player should).
 * The guide's muted preview passes false — a silent preview must never yank
 * focus from whatever is actually being listened to.
 */
@OptIn(UnstableApi::class)
class ExoEngine(context: Context, requestAudioFocus: Boolean = true) : PlayerEngine {

    override val name = "ExoPlayer"
    override var listener: PlayerEngine.Listener? = null
    override var onTransportPlay: (() -> Unit)? = null
    override var onTransportPause: (() -> Unit)? = null

    private val trackSelector = DefaultTrackSelector(context).apply {
        parameters = buildUponParameters()
            // The default caps adaptive selection to the *reported* display
            // size. TV boxes routinely under-report (1080p surface on a 4K
            // panel, or 720p before the first frame), which silently pins an
            // HLS ladder to a low rung. On a TV we always want the top rung.
            .clearViewportSizeConstraints()
            .clearVideoSizeConstraints()
            // Adaptive. Pinning the top rung regardless of what the line can
            // carry doesn't look sharper, it macroblocks and rebuffers; the
            // selector climbs to the top rung on its own when the bandwidth is
            // there. Only a viewer pinning "Highest available" in the quality
            // sheet turns this on.
            .setForceHighestSupportedBitrate(false)
            // Tunneled playback: video frames go straight from the decoder to
            // the display pipeline, which is how TV silicon keeps 4K and HDR in
            // sync with its audio instead of drifting. The selector only takes
            // this path when the decoders advertise the feature for the actual
            // format, so it costs nothing on hardware that can't do it.
            //
            // Off for the muted guide preview: tunneled decoder instances are a
            // scarce resource on TV chipsets, and a preview competing for one
            // with the stream the viewer is watching is a bad trade.
            .setTunnelingEnabled(requestAudioFocus)
            .build()
    }

    private val player: ExoPlayer = run {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            // A live feed that stops sending is dead NOW, not in fifteen
            // seconds: the recovery ladder can't run until this fires, and
            // every second here is a second of frozen picture first.
            .setReadTimeoutMs(8_000)
        val renderers = DefaultRenderersFactory(context)
            // ON, not PREFER: hardware decoders first, software only as a
            // fallback. PREFER puts software ahead of MediaCodec, which drops
            // frames on TV silicon the moment a decoder extension is present.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            // A stream whose hardware decoder refuses to initialise retries on
            // another decoder instead of failing the whole item.
            .setEnableDecoderFallback(true)
        // Wraps the HTTP factory so file:// (recordings) resolves, and picks up
        // the RTMP data source reflectively now that the module is on the
        // classpath. DefaultMediaSourceFactory does the same for the HLS, DASH,
        // SmoothStreaming and RTSP media sources.
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setTrackSelector(trackSelector)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    // IPTV feeds are bursty. A deeper buffer rides out the
                    // provider hiccups that otherwise read as "bad quality".
                    .setBufferDurationsMs(
                        // 25s of headroom, not 15: this is what a hiccup is
                        // spent from, and a line that jitters for three
                        // seconds should cost the viewer nothing rather than
                        // a visible hole. Memory is the trade, and a TV box
                        // can hold 25s of one stream.
                        /* minBufferMs = */ 25_000,
                        /* maxBufferMs = */ 60_000,
                        // Stock 2.5s to start. 1.5s made channel changes feel
                        // quicker but began playback on a thinner buffer, so a
                        // marginal connection re-stalled seconds later — a
                        // stall costs far more than the second it saved.
                        /* bufferForPlaybackMs = */ 2_500,
                        // Deeper after a stall than at start — coming back on
                        // the same thin buffer that just failed invites a
                        // rebuffer loop — but not 5s: a live panel feeds in
                        // real time, so it REFILLS in real time, and every
                        // stall then cost a visible five-second hole. 3s
                        // still clears the start threshold while roughly
                        // halving how long a hiccup stays on screen.
                        /* bufferForPlaybackAfterRebufferMs = */ 3_000,
                    )
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            // Proper audio-focus citizenship: request focus as media playback
            // and pause when headphones unplug, instead of talking over
            // whatever was already playing.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ requestAudioFocus,
            )
            .setHandleAudioBecomingNoisy(requestAudioFocus)
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
                listener?.onError(humanError(error.errorCodeName))
            }
        })
    }

    // Only the real player gets a media session: the muted guide preview
    // must stay invisible to system media surfaces, and two live sessions
    // would fight over transport keys. The session sees a forwarding wrapper
    // so transport intents route through the owning PlayerSession (which
    // knows about live stale-buffer rejoin) when one is attached.
    private val mediaSession: PlayerMediaSession? =
        if (requestAudioFocus) {
            PlayerMediaSession(
                context,
                @OptIn(UnstableApi::class)
                object : androidx.media3.common.ForwardingPlayer(player) {
                    override fun play() {
                        val handler = onTransportPlay
                        if (handler != null) handler() else super.play()
                    }

                    override fun pause() {
                        val handler = onTransportPause
                        if (handler != null) handler() else super.pause()
                    }
                },
            )
        } else null

    private var playerView: PlayerView? = null

    override fun createView(context: Context): View =
        PlayerView(context).apply {
            useController = false
            player = this@ExoEngine.player
            keepScreenOn = true
            playerView = this
        }

    override fun setScaleMode(mode: Int) {
        playerView?.resizeMode = when (mode) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    override fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
    }

    override fun prepare(
        items: List<PlayableItem>,
        startIndex: Int,
        startPositionMs: Long,
        isLive: Boolean,
    ) {
        // isLive is unused here: ExoPlayer reports live end-of-stream through
        // onPlayerError, so the session's ladder already sees it.
        player.setMediaItems(
            items.map { item ->
                MediaItem.Builder()
                    .setUri(item.url)
                    .apply { mimeHintFor(item.url)?.let { setMimeType(it) } }
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
        // prepare() is what makes this a retry. A PlaybackException leaves the
        // player in STATE_IDLE, where seek and playWhenReady are both inert —
        // so without this the session's failure ladder fired into a dead
        // player: no load was reattempted, no second onError ever arrived, the
        // retry budget never ran out and the error card was unreachable. The
        // viewer sat on "Tuning…" forever. Harmless when the player is healthy.
        player.prepare()
    }

    override fun setMuted(muted: Boolean) {
        player.volume = if (muted) 0f else 1f
    }

    override fun release() {
        listener = null
        mediaSession?.release() // must go before the player it wraps
        player.release()
    }

    override val isPlaying: Boolean get() = player.isPlaying
    override val currentIndex: Int get() = player.currentMediaItemIndex
    override val positionMs: Long get() = player.currentPosition
    override val durationMs: Long
        get() = player.duration.takeIf { it != C.TIME_UNSET && !player.isCurrentMediaItemLive } ?: 0L

    override val videoResolution: Pair<Int, Int>?
        get() = player.videoFormat?.let { f -> (f.width to f.height).takeIf { f.height > 0 } }

    override val videoFrameRate: Float?
        get() = player.videoFormat?.frameRate?.takeIf { it > 0f }

    override val hdrFormat: String?
        get() {
            val format = player.videoFormat ?: return null
            if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) return "Dolby Vision"
            return when (format.colorInfo?.colorTransfer) {
                C.COLOR_TRANSFER_ST2084 -> "HDR10"
                C.COLOR_TRANSFER_HLG -> "HLG"
                else -> null
            }
        }

    override val audioFormatLabel: String?
        get() {
            val format = player.audioFormat ?: return null
            // Codec first where it means something to a viewer: "Dolby Atmos"
            // is the badge people look for, and it outranks a channel count.
            val codec = when (format.sampleMimeType) {
                MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
                MimeTypes.AUDIO_AC4 -> "Dolby AC-4"
                MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
                MimeTypes.AUDIO_E_AC3 -> "Dolby Digital+"
                MimeTypes.AUDIO_AC3 -> "Dolby Digital"
                MimeTypes.AUDIO_DTS_X -> "DTS:X"
                MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
                MimeTypes.AUDIO_DTS -> "DTS"
                else -> null
            }
            if (codec != null) return codec
            // Otherwise only say something when it beats plain stereo.
            return when (format.channelCount) {
                6 -> "5.1"
                8 -> "7.1"
                else -> null
            }
        }

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
                        language = format.language,
                    )
                }
            }

    override fun audioTracks(): List<Track> = tracksOf(C.TRACK_TYPE_AUDIO)
    override fun textTracks(): List<Track> = tracksOf(C.TRACK_TYPE_TEXT)

    /**
     * True only while "Highest available" is the standing choice — set from
     * the quality sheet, or re-applied per stream from the viewer's quality
     * preference. The default is adaptive (false): the selector climbs to the
     * top rung on its own, and pinning it regardless of the line macroblocks.
     */
    val isForcingHighest: Boolean
        get() = trackSelector.parameters.forceHighestSupportedBitrate

    override fun videoTracks(): List<Track> {
        val pinned = trackSelector.parameters.overrides.values
            .any { it.type == C.TRACK_TYPE_VIDEO }
        val rungs = player.currentTracks.groups
            .withIndex()
            .filter { (_, group) -> group.type == C.TRACK_TYPE_VIDEO }
            .flatMap { (groupIndex, group) ->
                (0 until group.length).map { trackIndex ->
                    val format = group.getTrackFormat(trackIndex)
                    val supported = group.isTrackSupported(trackIndex)
                    val codec = format.codecs?.substringBefore('.')?.uppercase()
                    Track(
                        id = "$groupIndex:$trackIndex",
                        label = buildString {
                            append(qualityLabel(format.width, format.height, format.bitrate))
                            if (codec != null) append("  $codec")
                            if (!supported) append("  — this TV can't decode it")
                        },
                        // Only mark a rung "selected" when the user pinned
                        // one; under adaptive selection "Auto" owns the tick.
                        selected = supported && pinned && group.isTrackSelected(trackIndex),
                        supported = supported,
                    )
                }
            }
        // One playable rung and nothing else is not a choice. But a rung the
        // device can't decode is worth showing even on its own — that is the
        // whole explanation for a UHD channel looking soft.
        return if (rungs.size > 1 || rungs.any { !it.supported }) rungs else emptyList()
    }

    override fun selectVideoTrack(id: String?) {
        trackSelector.parameters = trackSelector.buildUponParameters().apply {
            // Any explicit choice — a pinned rung or Auto — hands bitrate
            // control back to the selector, so the forced-highest default
            // stops overriding it.
            setForceHighestSupportedBitrate(id == HIGHEST_QUALITY)
            if (id == null || id == HIGHEST_QUALITY) {
                clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            } else if (player.currentTracks.groups
                    .getOrNull(id.substringBefore(':').toIntOrNull() ?: -1)
                    ?.isTrackSupported(id.substringAfter(':').toIntOrNull() ?: -1) == false
            ) {
                // Pinning a rung the decoder rejects would black the video out.
                return@apply
            } else {
                val (groupIndex, trackIndex) = id.split(":").map { it.toInt() }
                val group = player.currentTracks.groups.getOrNull(groupIndex)
                    ?: return@apply
                setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            }
        }.build()
    }

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

/**
 * ExoPlayer's error constant, said in words a viewer can act on.
 *
 * The raw name went straight to the error card, so a dead stream announced
 * itself as "IO_BAD_HTTP_STATUS" — accurate, and useless to the person holding
 * the remote. Unmapped codes keep the tidied constant so a bug report still
 * carries something specific.
 */
internal fun humanError(errorCodeName: String): String = when (errorCodeName) {
    "ERROR_CODE_IO_BAD_HTTP_STATUS",
    "ERROR_CODE_IO_FILE_NOT_FOUND" -> "your provider didn't return this stream"
    "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
    "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT" -> "the connection dropped"
    "ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE",
    "ERROR_CODE_PARSING_CONTAINER_MALFORMED",
    "ERROR_CODE_PARSING_MANIFEST_MALFORMED",
    "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",
    "ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED" -> "this stream is in a format the player can't read"
    "ERROR_CODE_DECODING_FAILED",
    "ERROR_CODE_DECODER_INIT_FAILED",
    "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED",
    "ERROR_CODE_DECODER_QUERY_FAILED" -> "this device can't decode this stream"
    "ERROR_CODE_DRM_UNSPECIFIED",
    "ERROR_CODE_DRM_SCHEME_UNSUPPORTED",
    "ERROR_CODE_DRM_PROVISIONING_FAILED",
    "ERROR_CODE_DRM_CONTENT_ERROR",
    "ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED" -> "this stream is copy-protected"
    "ERROR_CODE_BEHIND_LIVE_WINDOW" -> "the live stream moved on"
    else -> errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase()
}
