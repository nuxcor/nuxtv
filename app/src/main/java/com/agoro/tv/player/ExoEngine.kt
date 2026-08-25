package com.agoro.tv.player

import android.content.Context
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
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
     * Decode no audio at all. For the guide preview, which is a muted
     * thumbnail: muting only sets volume to zero, so the AudioTrack, the audio
     * decoder and a codec stay allocated and keep running, rendering silence.
     * With the preview no longer optional it re-prepares that pipeline on
     * every channel a viewer rests on, and a box that runs out of audio
     * sessions fails the NEXT stream to ask - "AudioTrack init failed" on a
     * film, from a thumbnail three screens away. Disabling the track type
     * frees the lot; the preview has never had anything to say.
     */
    private val silent: Boolean = false,
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
        PlayerPool.borrow(
            context,
            main = requestAudioFocus,
            profile = profile,
            live = isLive,
            silent = silent,
        )
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

    /**
     * The output's refusal of an AudioTrack, held until either the track
     * opens after all or [sinkStallCheck] decides it never will.
     *
     * A refused AudioTrack is not always an error the player reports. media3
     * keeps the refusal to itself and retries for 200ms, and on 1.9 through
     * 1.11 a release-count leak (androidx/media #3338) can leave that retry
     * waiting forever: no error, no audio, the audio renderer never ready,
     * and so a player that sits in BUFFERING with a full buffer for as long
     * as the viewer will watch it. The sink does say what happened — every
     * failed attempt reaches [AnalyticsListener.onAudioSinkError] — so that
     * word is kept, and if playback has still not started a few seconds
     * later the refusal is reported as the error it was, which puts it on
     * the same rung a thrown one lands on; see [AudioOutputPolicy].
     */
    private var sinkRefusal: Exception? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val sinkStallCheck = Runnable {
        val refusal = sinkRefusal ?: return@Runnable
        if (released) return@Runnable
        // Still waiting on a renderer with playback asked for: the refusal
        // was final. Anything else — playing, paused, ended — means the
        // track opened after all, or something else is the matter.
        if (player.playbackState != Player.STATE_BUFFERING || !player.playWhenReady) {
            clearSinkRefusal()
            return@Runnable
        }
        android.util.Log.w("Agoro", "Audio output refused the track and playback never started", refusal)
        clearSinkRefusal()
        // Untunnelling re-selects the tracks and re-creates the AudioTrack on
        // the ordinary path; the player never left BUFFERING, so nothing else
        // needs restarting.
        if (refuseTunnelIfPcmRefused()) return@Runnable
        listener?.onError(humanError("ERROR_CODE_AUDIO_TRACK_INIT_FAILED"), PlaybackFault.AUDIO_OUTPUT)
    }

    // --- output watchdog ---------------------------------------------------
    //
    // The three failures a viewer meets as "picture but no sound", "sound
    // keeps cutting out" and "sound but a black screen" throw nothing:
    // the platform accepted the track or the decoder and simply does not
    // deliver. OwnTV's watchdog is the model — detection in order of
    // confidence, from the sink's own word down to "armed but never
    // advancing" — and each verdict lands on the rung that changes the
    // component at fault, once per process; a latch already set means the
    // rebuild did not fix it, and playing on beats a card over a playing
    // picture.

    /** A format reached the audio sink; sound has (not) yet left the device. */
    private var audioArmed = false
    private var audioAdvancing = false

    /** Which decoder is feeding the sink, or null when the TV is decoding (passthrough). */
    private var audioDecoderName: String? = null

    /** Which decoder is drawing the picture; the log's answer to "some titles stutter". */
    private var videoDecoderName: String? = null

    /** When playback last stopped to refill mid-stream, 0 while it is playing. */
    private var rebufferStartedAtMs = 0L

    /** A format reached the video decoder; a frame has (not) yet been drawn. */
    private var videoArmed = false
    private var firstFrameDrawn = false

    /** The surface went away (screensaver, background); the next one must draw again to count. */
    private var surfaceLost = false

    private val underruns = ArrayDeque<Long>()

    private fun resetWatchdog() {
        audioArmed = false
        audioAdvancing = false
        audioDecoderName = null
        videoDecoderName = null
        rebufferStartedAtMs = 0L
        videoArmed = false
        firstFrameDrawn = false
        surfaceLost = false
        underruns.clear()
        mainHandler.removeCallbacks(outputCheck)
    }

    /** (Re)starts the grace clock; only playing time counts, so it is armed by playing. */
    private fun scheduleOutputCheck() {
        mainHandler.removeCallbacks(outputCheck)
        if (main && !released && player.isPlaying) mainHandler.postDelayed(outputCheck, OUTPUT_GRACE_MS)
    }

    private val outputCheck = Runnable {
        if (released || !main || !player.isPlaying) return@Runnable
        when {
            audioArmed && !audioAdvancing -> {
                // The output took the format and produced silence. With the
                // PCM sink already in place there is nothing left to change,
                // and a playing picture is not traded for a card.
                if (AudioOutputPolicy.pcmOnly) {
                    android.util.Log.w("Agoro", "Audio still silent on the PCM sink; leaving playback alone")
                    return@Runnable
                }
                android.util.Log.w("Agoro", "Audio format accepted but position never advanced in ${OUTPUT_GRACE_MS}ms")
                listener?.onError("your TV played this audio format as silence", PlaybackFault.AUDIO_OUTPUT)
            }
            // Not while tunnelled: there the HAL draws, and media3 only learns
            // of the first frame through the vendor's onFrameRendered callback,
            // which some boxes never send (androidx/media #1169). A tunnelled
            // decoder that truly freezes is TunnelPolicy's case, not this one.
            // And only with sound leaving the device: the failure this is for
            // is "audio plays, screen black", and a stream that is stuck for
            // any other reason has not earned a verdict on its decoder.
            videoArmed && !firstFrameDrawn && !tunnelling && audioAdvancing -> {
                if (VideoOutputPolicy.reinitOnly) {
                    android.util.Log.w("Agoro", "Video still not drawing on a re-initialised decoder; leaving playback alone")
                    return@Runnable
                }
                android.util.Log.w("Agoro", "Video decoder running but no frame drawn in ${OUTPUT_GRACE_MS}ms")
                listener?.onError("your TV's video decoder stopped drawing", PlaybackFault.VIDEO_OUTPUT)
            }
        }
    }

    /** Whether the sink is being fed decoded PCM, as opposed to the TV decoding a bitstream. */
    private val audioDecodedInApp: Boolean
        get() {
            val name = audioDecoderName ?: return false
            return !name.startsWith("audio.raw", ignoreCase = true) &&
                !name.startsWith("audio.passthrough", ignoreCase = true)
        }

    private fun clearSinkRefusal() {
        sinkRefusal = null
        mainHandler.removeCallbacks(sinkStallCheck)
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
            when (audioSinkError) {
                is AudioSink.InitializationException, is AudioSink.WriteException -> {
                    // The output refusing to open or take the track.
                    if (sinkRefusal != null) return
                    sinkRefusal = audioSinkError
                    mainHandler.postDelayed(sinkStallCheck, SINK_STALL_GRACE_MS)
                }
                is AudioSink.UnexpectedDiscontinuityException -> {
                    // A statement about the timestamps, which the sink
                    // answers by re-anchoring its clock — a skip the viewer
                    // sees. One is a splice. Several a minute on decoded
                    // audio is the decoder's clock, and worth a sink that
                    // smooths it; see PtsSmoother. Passthrough timestamps
                    // are the stream's own and are not second-guessed.
                    val jumpMs = (audioSinkError.actualPresentationTimeUs - audioSinkError.expectedPresentationTimeUs) / 1000
                    android.util.Log.i("Agoro", "Audio timestamp jumped ${jumpMs}ms (decoder=${audioDecoderName ?: "passthrough"})")
                    // Live only: the decoder's clock is a live-TS problem, and a
                    // VOD file whose audio has real gaps must keep its gaps, or
                    // its sound would drift from its picture by their sum.
                    if (!main || !live || !audioDecodedInApp) return
                    if (AudioOutputPolicy.noteDiscontinuity(android.os.SystemClock.elapsedRealtime())) {
                        listener?.onError("the audio timing keeps jumping", PlaybackFault.AUDIO_TIMING)
                    }
                }
            }
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            audioArmed = true
            audioAdvancing = false
            scheduleOutputCheck()
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            audioDecoderName = decoderName
        }

        override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
            audioAdvancing = true
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            // A decoder that has just been (re)created refills from a full
            // buffer — a variant switch, a surface handed back, a format the
            // old instance could not take — and noteBuffering must not read
            // that refill as the HAL freezing.
            markReconfigured()
            videoDecoderName = decoderName
            // The line that tells "some titles stutter" apart: the hardware
            // decoder refused this profile and a software one took it, which
            // will not carry 1080p smoothly and 4K not at all, and nothing on
            // screen says so. Warned, not just noted, so it stands out in a
            // capture.
            val format = player.videoFormat
            if (isSoftwareDecoder(decoderName) && (format?.height ?: 0) >= 720) {
                android.util.Log.w("Agoro", "Video on SOFTWARE decoder $decoderName for ${format?.width}x${format?.height} ${format?.codecs}")
            } else {
                android.util.Log.i("Agoro", "Video decoder $decoderName for ${format?.width}x${format?.height} ${format?.codecs}")
            }
        }

        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long,
        ) {
            // One line each: healthy playback produces none, and "sound
            // keeps cutting out" was impossible to confirm from a log.
            android.util.Log.w("Agoro", "Audio underrun: buffer=${bufferSize}B/${bufferSizeMs}ms, ${elapsedSinceLastFeedMs}ms since last feed")
            if (!main) return
            // Only the ones the sink cannot blame on the line: the player had
            // seconds in hand and the track still ran dry. A starving stream
            // underruns too, and that is the stall ladder's case.
            if (player.totalBufferedDuration < UNDERRUN_BUFFERED_MS) return
            val now = android.os.SystemClock.elapsedRealtime()
            underruns.addLast(now)
            while (underruns.isNotEmpty() && now - underruns.first() > UNDERRUN_WINDOW_MS) underruns.removeFirst()
            if (underruns.size < UNDERRUN_LIMIT) return
            underruns.clear()
            if (AudioOutputPolicy.pcmOnly) {
                android.util.Log.w("Agoro", "Audio still underrunning on the PCM sink; leaving playback alone")
                return
            }
            listener?.onError("the audio output keeps dropping out", PlaybackFault.AUDIO_OUTPUT)
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            // Armed ONCE per item. media3 reports a first frame for a new
            // item and for a new surface, and never for a format change
            // inside the stream — a resolution switch on an ad break, an HLS
            // variant switch — so clearing the flag here on every change
            // called a picture that never stopped a black screen, six
            // seconds after each change, on every live channel that has
            // them. That verdict latched a decoder that re-initialises on
            // every change, which is "buffering and stop" until restart.
            if (!videoArmed) {
                videoArmed = true
                firstFrameDrawn = false
                scheduleOutputCheck()
            }
            // Whether the catalogue carries the one Dolby Vision profile
            // media3 has no base-layer fallback for (dvhe.07) is a question
            // only the box can answer; this is how it answers.
            if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) {
                android.util.Log.i("Agoro", "Dolby Vision video: codecs=${format.codecs} ${format.width}x${format.height}")
            }
        }

        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            firstFrameDrawn = true
        }

        override fun onSurfaceSizeChanged(eventTime: AnalyticsListener.EventTime, width: Int, height: Int) {
            // A surface handed back after being taken away — the screensaver,
            // the app going to the background — is where the reuse-broken
            // decoders go black, and media3 reports the first frame afresh
            // for a NEW surface, so it has to be drawn again to count. A
            // resize of the same surface reports no new first frame, and
            // must not be mistaken for one.
            if (width == 0 || height == 0) {
                surfaceLost = true
            } else if (surfaceLost) {
                surfaceLost = false
                firstFrameDrawn = false
                // The decoder re-initialises on the new surface and refills
                // behind it; see noteBuffering.
                markReconfigured()
                scheduleOutputCheck()
            }
        }

        override fun onAudioTrackInitialized(
            eventTime: AnalyticsListener.EventTime,
            audioTrackConfig: AudioSink.AudioTrackConfig,
        ) {
            // The retry took: the refusal was the transient kind the sink's
            // own retry window exists for.
            clearSinkRefusal()
        }
    }

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
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, silent)
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
        if (tunnelAllowed()) setTunnelling(true)
    }

    /**
     * Whether this player may tunnel at all. Not tied to [AudioOutputPolicy]:
     * it was, on the theory that an output refusing passthrough would refuse
     * the tunnelled track next — but a PCM track into a tunnelled AudioTrack
     * is the path every AAC channel took before the latch existed, and with
     * the probe latching PCM at launch the veto quietly took the tunnel away
     * from every 4K/HDR channel on the boxes that need it most. If the
     * tunnelled PCM track IS refused, [refuseTunnelIfPcmRefused] answers
     * that, once, for the process.
     */
    private fun tunnelAllowed(): Boolean = main && !TunnelPolicy.refusedByDevice

    /**
     * A refusal on the PCM sink while tunnelled is the tunnel's doing — there
     * is no bitstream left to blame — so the device is marked as refusing
     * the tunnel and the selector asked for the ordinary path, which
     * re-creates the track untunnelled. True when that was the case and has
     * been handled; false when the refusal is someone else's.
     */
    private fun refuseTunnelIfPcmRefused(): Boolean {
        if (!tunnelling || !AudioOutputPolicy.pcmOnly) return false
        android.util.Log.w("Agoro", "PCM track refused while tunnelled; tunnelling off for this device")
        TunnelPolicy.refuse()
        setTunnelling(false)
        return true
    }

    private companion object {
        /**
         * How long a refused AudioTrack may go without playback starting
         * before the refusal is taken as final. The sink's own retry lasts
         * 200ms; the leak that swallows it lasts forever, and OwnTV's
         * watchdog for the same fault settles on six seconds of no audio.
         */
        const val SINK_STALL_GRACE_MS = 6_000L

        /**
         * Playing time an accepted format may go without sound leaving the
         * device, or a frame reaching the screen, before the output is the
         * verdict. Playing time, not wall clock: a channel that spends eight
         * seconds buffering has not failed at anything. OwnTV's figure.
         */
        const val OUTPUT_GRACE_MS = 6_000L

        /** Underruns inside [UNDERRUN_WINDOW_MS] that mean the sink is starving, not hiccuping. */
        const val UNDERRUN_LIMIT = 4
        const val UNDERRUN_WINDOW_MS = 10_000L

        /** Buffered media below which an underrun is the line's doing, not the sink's. */
        const val UNDERRUN_BUFFERED_MS = 2_000L

        /**
         * How long after a renderer reconfiguration its own rebuffer is
         * ignored: switching the tunnel on or off, a track change, a seek, a
         * decoder (re)initialisation or a surface handed back all empty and
         * refill the decoder with a full buffer behind it, which is the very
         * shape a HAL freeze has. Each of those calls markReconfigured; a
         * BUFFERING inside this window is theirs, not the device's.
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
        android.util.Log.w("Agoro", "Tunnelled stream stalled with ${player.totalBufferedDuration}ms buffered; tunnelling off for this device")
        TunnelPolicy.refuse()
        setTunnelling(false)
    }

    // --- rebuffer log -----------------------------------------------------
    //
    // "It breaks for a few seconds" has been answered with a guess every time
    // because nothing recorded what a mid-stream stall looked like: how much
    // was buffered when it began (empty is the line, full is a renderer),
    // what was decoding and on which decoder, whether the tunnel was on, and
    // how long it lasted. One line at each end, on the viewer's player only.
    // A seek starts one too — media3 buffers on every seek — and says so.

    private fun noteRebufferStarted() {
        if (!main || !wasPlaying) return
        val now = android.os.SystemClock.elapsedRealtime()
        rebufferStartedAtMs = now
        val video = player.videoFormat
        android.util.Log.w(
            "Agoro",
            "Rebuffer at ${player.currentPosition}ms: buffered=${player.totalBufferedDuration}ms" +
                " video=${videoDecoderName ?: "?"} ${video?.width}x${video?.height}@${video?.frameRate}" +
                " ${video?.codecs} ${video?.bitrate}bps" +
                " audio=${audioDecoderName ?: "passthrough"} ${player.audioFormat?.sampleMimeType}" +
                " tunnelled=$tunnelling live=$live" +
                " afterSeekOrReconfigure=${now - reconfiguredAtMs < RECONFIGURE_GRACE_MS}",
        )
    }

    private fun noteRebufferEnded() {
        if (rebufferStartedAtMs == 0L) return
        val lasted = android.os.SystemClock.elapsedRealtime() - rebufferStartedAtMs
        rebufferStartedAtMs = 0L
        android.util.Log.w("Agoro", "Rebuffer ended after ${lasted}ms with ${player.totalBufferedDuration}ms buffered")
    }

    private val playerListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            if (videoSize.height > 0) noteDecodedFormat()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    noteRebufferStarted()
                    noteBuffering()
                }
                Player.STATE_READY -> noteRebufferEnded()
            }
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
                live -> listener?.onError("The stream ended unexpectedly", PlaybackFault.TRANSIENT)
                index < items.size - 1 -> playAt(index + 1)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            wasPlaying = isPlaying
            if (isPlaying) scheduleOutputCheck() else mainHandler.removeCallbacks(outputCheck)
            listener?.onPlayingChanged(
                playing = isPlaying,
                buffering = player.playbackState == Player.STATE_BUFFERING,
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            // A thrown error reaches the ladder by itself; the watchdog is
            // for the refusal that never throws.
            clearSinkRefusal()
            // The card gets words; the log gets the cause chain. For an
            // audio-output refusal that chain holds the one line that says
            // why — the AudioTrack Config(rate, channel mask, encoding,
            // buffer) the platform turned down — and nothing else does.
            android.util.Log.w("Agoro", "Playback error ${error.errorCodeName}", error)
            val httpStatus = httpStatusOf(error)
            val fault = faultOf(error.errorCodeName, httpStatus)
            // A thrown refusal leaves the player idle, so the untunnelled
            // retry has to re-open the item — live at the edge, VOD where it was.
            if (fault == PlaybackFault.AUDIO_OUTPUT && refuseTunnelIfPcmRefused()) {
                playAt(index, if (live) 0L else player.currentPosition.coerceAtLeast(0L))
                return
            }
            listener?.onError(humanError(error.errorCodeName, httpStatus), fault)
        }
    }

    init {
        player.addListener(playerListener)
        player.addAnalyticsListener(analyticsListener)
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
        if (released) return
        // A seek is a reconfiguration as far as noteBuffering is concerned:
        // media3 puts the player into BUFFERING on every seek from READY, and
        // a seek that lands inside the buffer keeps that buffer — which is
        // the exact shape of a HAL freeze, on a film that was merely skipped
        // forward. Read as a freeze, it took the tunnel away from every 4K
        // and HDR stream for the rest of the process.
        markReconfigured()
        player.seekTo(positionMs.coerceAtLeast(0))
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
    override fun playAt(index: Int, startPositionMs: Long) {
        if (released || index !in items.indices) return
        this.index = index
        clearSinkRefusal()
        resetWatchdog()
        // The first BUFFERING of a new item is a tune, not a stall: the
        // isPlaying=false for the old one is dispatched after it.
        wasPlaying = false
        val item = items[index]
        // Decided before the decoder opens, so a stream that deserves the
        // tunnel gets it without a re-initialisation after the first frame.
        setTunnelling(tunnelAllowed() && TunnelPolicy.wantsTunnel(item.url, deservesTunnel(item.url)))
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
        clearSinkRefusal()
        resetWatchdog()
        mediaSession?.release()
        player.removeListener(playerListener)
        player.removeAnalyticsListener(analyticsListener)
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
 * Whether this failure is the audio output's — the platform refusing to
 * create or feed the AudioTrack the player asked for.
 *
 * Distinct from a decode fault on purpose: the stream was read and the
 * decoder was fine, it is the sink that was wrong, and software decoders
 * would ask the same sink for the same track. What changes it is rebuilding
 * the player with passthrough and tunnelling off, so the FFmpeg decoder turns
 * the Dolby into PCM before the platform sees it; see [AudioOutputPolicy].
 * The write-failed pair is included because on a live HDMI route it is the
 * same refusal arriving a moment later — a passthrough track invalidated by
 * a renegotiation (androidx/media #7042), which a PCM track survives.
 */
internal fun isAudioOutputFault(errorCodeName: String): Boolean = when (errorCodeName) {
    "ERROR_CODE_AUDIO_TRACK_INIT_FAILED",
    "ERROR_CODE_AUDIO_TRACK_WRITE_FAILED",
    "ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED",
    "ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED" -> true
    else -> false
}

/** Whether this MediaCodec name is one of the platform's software decoders. */
internal fun isSoftwareDecoder(name: String): Boolean =
    name.startsWith("OMX.google.", ignoreCase = true) ||
        name.startsWith("c2.android.", ignoreCase = true) ||
        name.startsWith("OMX.ffmpeg.", ignoreCase = true) ||
        name.contains(".sw.", ignoreCase = true)

/**
 * ExoPlayer's error constant, said in words a viewer can act on.
 *
 * The raw name went straight to the error card, so a dead stream announced
 * itself as "IO_BAD_HTTP_STATUS" — accurate, and useless to the person holding
 * the remote. Unmapped codes keep the tidied constant so a bug report still
 * carries something specific.
 */
internal fun humanError(errorCodeName: String, httpStatus: Int? = null): String {
    if (errorCodeName == "ERROR_CODE_IO_BAD_HTTP_STATUS" && httpStatus != null) {
        httpReason(httpStatus)?.let { return it }
    }
    return humanErrorForCode(errorCodeName)
}

/** The provider's HTTP status from the cause chain, when the failure was its answer. */
internal fun httpStatusOf(error: Throwable): Int? {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
        cause = cause.cause
    }
    return null
}

private fun humanErrorForCode(errorCodeName: String): String = when (errorCodeName) {
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
    "ERROR_CODE_AUDIO_TRACK_INIT_FAILED",
    "ERROR_CODE_AUDIO_TRACK_WRITE_FAILED",
    "ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED",
    "ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED" -> "your TV refused this audio format"
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
