package com.agoro.tv.player

import android.content.Context
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
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
 * What HDR flavour a decoded format carries, if any.
 *
 * Dolby Vision is read off the sample MIME rather than the transfer, because
 * a DV bitstream declares its own; the two HDR transfers are read off the
 * colour info, which is where a stream that merely carries PQ or HLG says so.
 */
@OptIn(UnstableApi::class)
private fun hdrTypeOf(format: androidx.media3.common.Format?): HdrType? {
    if (format == null) return null
    if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) return HdrType.DOLBY_VISION
    return when (format.colorInfo?.colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> HdrType.HDR10
        C.COLOR_TRANSFER_HLG -> HdrType.HLG
        else -> null
    }
}

/**
 * ExoPlayer backend over a player borrowed from [PlayerPool] for as long as
 * the engine lives — built once per process, never released on the way out.
 *
 * It hands the player ONE item at a time and keeps the playlist itself. It
 * used to give ExoPlayer the whole category as a playlist
 * — thousands of MediaItems for "All" — and every failure-ladder step rebuilt
 * and re-set all of them; worse, the media session's legacy bridge answers
 * every timeline change by walking every window into a queue and shipping it
 * over Binder, and a live HLS stream changes its timeline on every playlist
 * refresh. A one-window timeline makes both a non-event.
 *
 * @param requestAudioFocus Whether this player takes audio focus (ducking
 * music apps and pausing for other media, the way every TV player should).
 * The guide's muted preview passes false — a silent preview must never yank
 * focus from whatever is actually being listened to.
 */
@OptIn(UnstableApi::class)
class ExoEngine(
    context: Context,
    requestAudioFocus: Boolean = true,
    /**
     * Whether the app already knows this stream decodes at 4K or in HDR, from
     * a previous visit. Lets such a channel tunnel from its first frame
     * instead of re-initialising the decoder after it — which is a black beat
     * mid-picture, and on an HDR stream a spell of it going out to the panel
     * untunnelled; see [TunnelPolicy].
     */
    private val deservesTunnel: (String) -> Boolean = { false },
    /**
     * How forgiving to build the underlying player. Streams open on
     * [DecodeProfile.FAST]; the failure ladder re-opens a stream that would
     * not decode on [DecodeProfile.TOLERANT], which is what replaced the old
     * "try the other engine" swap to libVLC.
     */
    private val profile: DecodeProfile = DecodeProfile.FAST,
    /**
     * Whether this engine will be handed live streams. Known here rather than
     * at [prepare] because it decides how the underlying player buffers, and
     * that is fixed when the player is built; see loadControlFor. Defaults to
     * live, which is what the guide's preview is.
     */
    isLive: Boolean = true,
) : PlayerEngine {

    override val name = if (profile == DecodeProfile.TOLERANT) "ExoPlayer (software)" else "ExoPlayer"
    override var listener: PlayerEngine.Listener? = null
    override var onTransportPlay: (() -> Unit)? = null
    override var onTransportPause: (() -> Unit)? = null

    private val lease =
        PlayerPool.borrow(context, main = requestAudioFocus, profile = profile, live = isLive)
    private val player: ExoPlayer get() = lease.player
    private val trackSelector: DefaultTrackSelector get() = lease.trackSelector

    private var items: List<PlayableItem> = emptyList()
    private var index: Int = 0
    private var live = false
    private var released = false

    /** The real player, as opposed to the guide's muted preview. */
    private val main = requestAudioFocus

    /** What the selector is currently asked for; see [TunnelPolicy]. */
    private var tunnelling = false

    /** When the renderers were last asked to reconfigure — they rebuffer briefly while they do. */
    private var reconfiguredAtMs = 0L

    /** True between a READY-and-playing report and the next state change. */
    private var wasPlaying = false

    init {
        // From the DEFAULTS, not buildUponParameters(): the selector came back
        // from the pool carrying the last visit's overrides — a pinned rung,
        // subtitles switched off — and those meant something on that stream,
        // not on this one.
        trackSelector.parameters = DefaultTrackSelector.Parameters.getDefaults(context).buildUpon()
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
            // Tunnelling is decided per stream in playAt, not here: off
            // unless the stream is 4K/HDR, and off for good on a device whose
            // tunnelled decoder has frozen. See TunnelPolicy.
            .setTunnelingEnabled(false)
            .build()
    }

    /**
     * Asks the selector for tunnelled or ordinary rendering. A change while
     * a stream is up re-initialises the video decoder — a beat of black —
     * so it is only ever made when [TunnelPolicy] says the stream deserves
     * it or the device has refused it, never as a matter of routine.
     */
    private fun setTunnelling(on: Boolean) {
        if (on == tunnelling) return
        tunnelling = on
        markReconfigured()
        trackSelector.parameters = trackSelector.buildUponParameters()
            .setTunnelingEnabled(on)
            .build()
    }

    /** Any track change re-buffers with a full buffer; [noteBuffering] must not read that as a freeze. */
    private fun markReconfigured() {
        reconfiguredAtMs = android.os.SystemClock.elapsedRealtime()
    }

    /** The first decoded format tells whether this stream is one worth tunnelling. */
    private fun noteDecodedFormat() {
        val format = player.videoFormat ?: return
        val url = items.getOrNull(index)?.url ?: return
        if (format.height < 2000 && hdrTypeOf(format) == null) return
        TunnelPolicy.remember(url)
        if (main && !TunnelPolicy.refusedByDevice) setTunnelling(true)
    }

    private companion object {
        /**
         * How long after a renderer reconfiguration its own rebuffer is
         * ignored: switching the tunnel on or off, or a track change, empties
         * and refills the decoder with a full buffer behind it, which is the
         * very shape a HAL freeze has.
         */
        const val RECONFIGURE_GRACE_MS = 5_000L
    }

    /**
     * A stall with a full buffer is not the network: nothing ExoPlayer can
     * load is missing, a renderer simply is not ready. While tunnelled that
     * is the vendor HAL refusing to advance — the failure mode the whole
     * policy exists for — so the device is marked and the stream re-opens
     * on the ordinary path, which is also what un-sticks it.
     */
    private fun noteBuffering() {
        if (!main || !tunnelling || !wasPlaying) return
        if (android.os.SystemClock.elapsedRealtime() - reconfiguredAtMs < RECONFIGURE_GRACE_MS) return
        if (player.totalBufferedDuration < TunnelPolicy.FULL_BUFFER_MS) return
        TunnelPolicy.refuse()
        setTunnelling(false)
    }

    private val playerListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            if (videoSize.height > 0) noteDecodedFormat()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_BUFFERING) noteBuffering()
            listener?.onPlayingChanged(
                playing = player.isPlaying,
                buffering = playbackState == Player.STATE_BUFFERING,
            )
            if (playbackState != Player.STATE_ENDED) return
            // The playlist is ours now, so so is what happens at the end of
            // an item: the next episode in a box set, or — on live, where
            // nothing legitimately ends — the failure ladder. A raw TS feed
            // whose provider closed the connection reads to ExoPlayer as a
            // clean end of stream, and with the category as its playlist it
            // used to answer that by quietly advancing to the next channel.
            when {
                live -> listener?.onError("The stream ended unexpectedly", decodeFault = false)
                index < items.size - 1 -> playAt(index + 1)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            wasPlaying = isPlaying
            listener?.onPlayingChanged(
                playing = isPlaying,
                buffering = player.playbackState == Player.STATE_BUFFERING,
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            listener?.onError(
                humanError(error.errorCodeName),
                decodeFault = isDecodeFault(error.errorCodeName),
            )
        }
    }

    init {
        player.addListener(playerListener)
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

                    // No timeline for the session: this is media3's documented
                    // opt-out of legacy queue publishing. With it, the bridge
                    // stops rebuilding and re-sending a MediaSessionCompat
                    // queue on every timeline change — which for a live HLS
                    // stream is every playlist refresh, on the main thread,
                    // over Binder. Nothing on a TV reads that queue.
                    override fun getAvailableCommands(): Player.Commands =
                        super.getAvailableCommands().buildUpon()
                            .remove(Player.COMMAND_GET_TIMELINE)
                            .build()

                    override fun isCommandAvailable(command: Int): Boolean =
                        command != Player.COMMAND_GET_TIMELINE && super.isCommandAvailable(command)
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
        if (!released) player.setPlaybackSpeed(speed)
    }

    override fun prepare(
        items: List<PlayableItem>,
        startIndex: Int,
        startPositionMs: Long,
        isLive: Boolean,
    ) {
        this.items = items
        this.live = isLive
        playAt(startIndex, startPositionMs)
    }

    override fun playPause() {
        if (released) return
        if (player.isPlaying) player.pause() else player.play()
    }

    override fun seekTo(positionMs: Long) {
        if (!released) player.seekTo(positionMs.coerceAtLeast(0))
    }

    override fun next() = playAt((index + 1).coerceAtMost(items.size - 1))

    override fun previous() = playAt((index - 1).coerceAtLeast(0))

    override fun playAt(index: Int) = playAt(index, 0L)

    /**
     * Opens `items[index]` as the player's only item. Setting the item and
     * preparing is what makes a repeat of the same index a retry: a
     * PlaybackException leaves the player in STATE_IDLE, where seek and
     * playWhenReady are both inert — so without the prepare the session's
     * failure ladder fired into a dead player: no load was reattempted, no
     * second onError ever arrived, the retry budget never ran out and the
     * error card was unreachable. The viewer sat on "Tuning…" forever.
     */
    private fun playAt(index: Int, startPositionMs: Long) {
        if (released || index !in items.indices) return
        this.index = index
        val item = items[index]
        // Decided before the decoder opens, so a stream that deserves the
        // tunnel gets it without a re-initialisation after the first frame.
        setTunnelling(main && TunnelPolicy.wantsTunnel(item.url, deservesTunnel(item.url)))
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(item.url)
                .apply { mimeHintFor(item.url)?.let { setMimeType(it) } }
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(item.title).setArtist(item.subtitle).build()
                )
                .build(),
            if (startPositionMs > 0) startPositionMs else C.TIME_UNSET,
        )
        player.playWhenReady = true
        player.prepare()
        // Announced from here rather than from onMediaItemTransition: the session treats a repeat of the same
        // index as a reconnect and a new one as a new ladder, and it wants
        // that verdict before the banner draws, not a frame after.
        listener?.onItemChanged(index)
    }

    override fun setMuted(muted: Boolean) {
        if (!released) player.volume = if (muted) 0f else 1f
    }

    /**
     * Gives the player back to the pool rather than releasing it — release
     * is the call that blocked the main thread on the decoders. Everything
     * this engine hung on the player comes off first, so the next borrower
     * inherits nothing: the session (which must go before the player it
     * wraps), our listener, and the view's surface.
     */
    override fun release() {
        if (released) return
        released = true
        listener = null
        mediaSession?.release()
        player.removeListener(playerListener)
        playerView?.player = null
        playerView = null
        PlayerPool.giveBack(lease)
    }

    // Guarded on released throughout: the pooled player may already belong
    // to the next engine, and a poll still in flight from this one must not
    // read that engine's stream as its own.
    override val isPlaying: Boolean get() = !released && player.isPlaying
    override val currentIndex: Int get() = index
    override val positionMs: Long get() = if (released) 0L else player.currentPosition
    override val durationMs: Long
        get() = if (released) 0L
        else player.duration.takeIf { it != C.TIME_UNSET && !player.isCurrentMediaItemLive } ?: 0L

    override val bufferedAheadMs: Long?
        get() = if (released) null else player.totalBufferedDuration

    override val videoResolution: Pair<Int, Int>?
        get() = if (released) null
        else player.videoFormat?.let { f -> (f.width to f.height).takeIf { f.height > 0 } }

    override val videoFrameRate: Float?
        get() = if (released) null else player.videoFormat?.frameRate?.takeIf { it > 0f }

    override val hdrType: HdrType?
        get() = if (released) null else hdrTypeOf(player.videoFormat)

    override val audioFormatLabel: String?
        get() {
            if (released) return null
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
        if (released) emptyList()
        else player.currentTracks.groups
            .withIndex()
            .filter { (_, group) -> group.type == trackType }
            .flatMap { (groupIndex, group) ->
                (0 until group.length).map { trackIndex ->
                    val format = group.getTrackFormat(trackIndex)
                    // A name a viewer recognises, not the codec string: "EN •
                    // mp4a.40.2" is a MIME parameter, and on the screen it
                    // was the only thing in the app that looked like one.
                    val label = listOfNotNull(
                        format.label ?: format.language?.let { languageName(it) },
                        friendlyCodec(format),
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

    override val isForcingHighest: Boolean
        get() = trackSelector.parameters.forceHighestSupportedBitrate

    override fun videoTracks(): List<Track> {
        if (released) return emptyList()
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
        if (released) return
        markReconfigured()
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
        if (released) return
        markReconfigured()
        val (groupIndex, trackIndex) = id.split(":").map { it.toInt() }
        val group = player.currentTracks.groups.getOrNull(groupIndex) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(trackType, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
    }

    override fun selectAudioTrack(id: String) = applyOverride(C.TRACK_TYPE_AUDIO, id)

    override fun selectTextTrack(id: String?) {
        if (released) return
        markReconfigured()
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
 * Whether this failure is one a more forgiving demuxer or a software decoder
 * could plausibly get past.
 *
 * The parsing codes are the demuxer giving up on a mux, the decoding ones are
 * the vendor's hardware refusing a profile — both are what
 * [DecodeProfile.TOLERANT] exists for. Everything else (HTTP status, network,
 * DRM, a live window that moved on) fails identically no matter how the player
 * is built, and must not be offered software decoding as a false hope.
 */
internal fun isDecodeFault(errorCodeName: String): Boolean = when (errorCodeName) {
    "ERROR_CODE_PARSING_CONTAINER_MALFORMED",
    "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",
    "ERROR_CODE_DECODING_FAILED",
    "ERROR_CODE_DECODER_INIT_FAILED",
    "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED",
    "ERROR_CODE_DECODER_QUERY_FAILED" -> true
    else -> false
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

/** "en" → "English"; an unknown or odd tag stays as its upper-cased code. */
private fun languageName(tag: String): String {
    val locale = java.util.Locale.forLanguageTag(tag)
    val name = locale.getDisplayLanguage(java.util.Locale.getDefault())
    return if (name.isBlank() || name.equals(tag, ignoreCase = true)) tag.uppercase() else name
}

/**
 * What the codec is called on a box, where it is worth saying at all. Plain
 * AAC stereo and plain H.264 say nothing a viewer acts on, so they say
 * nothing; surround formats and the newer video codecs do.
 */
private fun friendlyCodec(format: androidx.media3.common.Format): String? {
    val mime = format.sampleMimeType ?: return null
    val base = when (mime) {
        MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
        MimeTypes.AUDIO_AC4 -> "Dolby AC-4"
        MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
        MimeTypes.AUDIO_E_AC3 -> "Dolby Digital+"
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_DTS_X -> "DTS:X"
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_AAC -> null
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.VIDEO_H265 -> "HEVC"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
        else -> null
    }
    val channels = when (format.channelCount) {
        6 -> "5.1"
        8 -> "7.1"
        else -> null
    }
    return listOfNotNull(base, channels).joinToString(" ").ifBlank { null }
}
