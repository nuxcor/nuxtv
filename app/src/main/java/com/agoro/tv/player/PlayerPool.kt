package com.agoro.tv.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

/**
 * How forgiving a player is asked to be, and at what cost.
 *
 * ExoPlayer is the only engine now. What used to be "try the other player"
 * when a stream wouldn't decode is this instead: the same engine opened a
 * second way, with the demuxer told to expect a mess and the decoders chosen
 * for breadth rather than speed. It is a real second chance — libVLC's value
 * here was never its UI, it was that its demuxer forgave things ExoPlayer's
 * default configuration does not — and unlike a second engine it keeps track
 * selection, HDR reporting, tunnelling and the media session intact.
 *
 * Not the default, because both halves cost something on TV silicon: access
 * unit detection is per-sample work, and a software decoder will not carry 4K
 * HEVC at all. So streams open [FAST] and only a failure moves them.
 */
enum class DecodeProfile {
    /** Hardware decoders, and a TS reader that assumes the mux is sane. */
    FAST,

    /**
     * Software decoders preferred, and a TS reader told to work it out from
     * the bitstream. The recovery rung, and the reason there is no VLC.
     */
    TOLERANT,
}

/**
 * Transport-stream reader flags, per [DecodeProfile].
 *
 * [DecodeProfile.FAST] takes only the free one. `ALLOW_NON_IDR_KEYFRAMES` lets
 * a live join start on the first recovery point rather than waiting for a true
 * IDR, which on a provider that sends them sparsely is the difference between
 * a channel that tunes in a second and one that sits black for ten.
 *
 * [DecodeProfile.TOLERANT] adds the two that cost or risk something:
 *
 *  - `DETECT_ACCESS_UNITS` makes the H.264 reader find frame boundaries in the
 *    bitstream instead of trusting the container's markers. It is what plays a
 *    mux with no access unit delimiters, and it is per-sample work the fast
 *    path should not be paying on every channel to rescue the few.
 *  - `ENABLE_HDMV_DTS_AUDIO_STREAMS` reads TS stream type 0x82 as DTS audio.
 *    That type is SCTE-35 splice info in ordinary broadcast content, so this
 *    is a genuine trade and not a free win — worth making when the stream has
 *    already failed, never before.
 */
@OptIn(UnstableApi::class)
private fun tsFlagsFor(profile: DecodeProfile): Int {
    val base = DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
    return if (profile == DecodeProfile.FAST) {
        base
    } else {
        base or
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
            DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS
    }
}

/**
 * Routes HLS to a factory that has been told about transport streams, and
 * everything else to the stock one.
 *
 * [DefaultMediaSourceFactory] builds its HLS source reflectively and exposes
 * no hook into it, so the TS payload flags set on an [DefaultExtractorsFactory]
 * reach progressive `.ts` playback and nothing else. Nearly every HLS stream an
 * IPTV provider serves is TS segments, so without this the flags miss the half
 * of the catalogue they were set for.
 */
@OptIn(UnstableApi::class)
private class IptvMediaSourceFactory(
    dataSourceFactory: DataSource.Factory,
    profile: DecodeProfile,
) : MediaSource.Factory {

    private val progressive = DefaultMediaSourceFactory(
        dataSourceFactory,
        DefaultExtractorsFactory().setTsExtractorFlags(tsFlagsFor(profile)),
    )

    private val hls = HlsMediaSource.Factory(dataSourceFactory)
        .setExtractorFactory(
            DefaultHlsExtractorFactory(
                tsFlagsFor(profile),
                // media3's own default, restated because the two-argument
                // constructor is the only way to pass the flags and leaving
                // this to guesswork would be how it silently flips one day.
                /* exposeCea608WhenMissingDeclarations = */ true,
            )
        )

    override fun getSupportedTypes(): IntArray = progressive.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val local = mediaItem.localConfiguration
        val type = Util.inferContentTypeForUriAndMimeType(
            local?.uri ?: Uri.EMPTY,
            local?.mimeType,
        )
        return if (type == C.CONTENT_TYPE_HLS) {
            hls.createMediaSource(mediaItem)
        } else {
            progressive.createMediaSource(mediaItem)
        }
    }

    override fun setDrmSessionManagerProvider(
        provider: DrmSessionManagerProvider,
    ): MediaSource.Factory {
        progressive.setDrmSessionManagerProvider(provider)
        hls.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(
        policy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory {
        progressive.setLoadErrorHandlingPolicy(policy)
        hls.setLoadErrorHandlingPolicy(policy)
        return this
    }

    // The rest of MediaSource.Factory has default implementations that return
    // the receiver and forward nothing. ExoPlayer only ever calls
    // createMediaSource, so today none of these is reached — but
    // DefaultMediaSourceFactory applies every one of them to the delegates it
    // builds, including its HLS one, and the bare factory here would silently
    // miss whatever it was told. They agree on the defaults at 1.8.0; the way
    // that stops being true is a media3 upgrade changing one of them, which is
    // the worst possible moment to discover the delegate never listened.
    override fun setSubtitleParserFactory(
        subtitleParserFactory: SubtitleParser.Factory,
    ): MediaSource.Factory {
        progressive.setSubtitleParserFactory(subtitleParserFactory)
        hls.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    override fun experimentalParseSubtitlesDuringExtraction(
        parseSubtitlesDuringExtraction: Boolean,
    ): MediaSource.Factory {
        progressive.experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction)
        hls.experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction)
        return this
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(
        codecsToParseWithinGopSampleDependencies: Int,
    ): MediaSource.Factory {
        progressive.experimentalSetCodecsToParseWithinGopSampleDependencies(
            codecsToParseWithinGopSampleDependencies
        )
        hls.experimentalSetCodecsToParseWithinGopSampleDependencies(
            codecsToParseWithinGopSampleDependencies
        )
        return this
    }

    override fun setCmcdConfigurationFactory(
        cmcdConfigurationFactory: CmcdConfiguration.Factory,
    ): MediaSource.Factory {
        progressive.setCmcdConfigurationFactory(cmcdConfigurationFactory)
        hls.setCmcdConfigurationFactory(cmcdConfigurationFactory)
        return this
    }
}

/**
 * How much to buffer, and how long to wait before resuming after a stall.
 *
 * Two shapes, because live and a film are not the same problem and one set of
 * numbers had been serving both — the live one, since that is what they were
 * measured against.
 *
 * LIVE is unchanged and deliberately deep. The panel delivers a live stream at
 * three to three and a half times real time, so six seconds of media costs
 * about two seconds of wall clock, and the cushion is what stops a dip from
 * becoming a source hop and a black screen mid-match.
 *
 * VOD is where that reasoning stops being true. A film is served at roughly
 * real time, so the SAME six seconds costs six seconds of frozen picture on
 * every hiccup — three times the price for a cushion that buys less, because a
 * film has no live edge to fall off and re-stalling is only another short
 * wait. It resumes on two seconds instead.
 *
 * The byte cap is the other half, and it matters most on the weakest boxes.
 * `prioritizeTimeOverSizeThresholds` buffers by TIME and ignores
 * DEFAULT_VIDEO_BUFFER_SIZE (125 MB) entirely — which live can afford at 11
 * Mbit/s, where 25 seconds is about 34 MB, and a 40 Mbit/s 4K film cannot: 60
 * seconds of it is roughly 300 MB held in the allocator. On a cheap TV box
 * that is not a buffer, it is garbage collection, and GC pauses read to a
 * viewer as exactly the stutter the deep buffer was meant to prevent. VOD
 * keeps the cap, so the buffer stays deep in seconds where the bitrate is
 * modest and gives way to memory where it is not.
 */
@OptIn(UnstableApi::class)
private fun loadControlFor(live: Boolean): DefaultLoadControl =
    DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ if (live) 25_000 else 20_000,
            /* maxBufferMs = */ if (live) 60_000 else 50_000,
            // 2.5s to start on live: 1.5s made channel changes feel quicker
            // but began playback on a thinner buffer, so a marginal connection
            // re-stalled seconds later. A film is opened once, deliberately,
            // and a second of start-up is cheaper there than a stall later.
            /* bufferForPlaybackMs = */ 2_500,
            // 2.5s on live too. This was 6s, on the reasoning that the panel
            // serves a channel at three to three and a half times real time,
            // so six seconds of media costs about two of wall clock. That
            // holds only while the panel is actually running ahead. When it
            // serves at or near 1x - a loaded panel, a middleman re-streaming,
            // a marginal line - six seconds of media costs six seconds of
            // frozen picture, and the player will not resume until it has
            // them. It resumes, falls behind, and waits six seconds again:
            // the stall loop trips three-in-sixty, which hops the source and
            // then runs out of ladder. That is the same trade a film was
            // losing before it got its own numbers, and live loses it worse,
            // because live is the one with a recovery ladder to exhaust.
            /* bufferForPlaybackAfterRebufferMs = */ 2_500,
        )
        .setPrioritizeTimeOverSizeThresholds(live)
        .build()

/**
 * Process-lifetime ExoPlayer instances that [ExoEngine] borrows instead of
 * building and releasing its own.
 *
 * `ExoPlayer.release()` is the one call in the player that blocks the main
 * thread: it parks on a ConditionVariable until the playback thread has torn
 * down its renderers — released the hardware decoders — and quit, which on a
 * TV SoC is anywhere from 20 ms to the 500 ms timeout. The app paid that on
 * every exit from the player, on every engine swap, and on every guide
 * preview that stopped, each time as a frozen frame or two right where the
 * viewer was expecting the next screen. Netflix never releases its player
 * between titles, and neither does this.
 *
 * `stop()` is the cheap half of the same work: with foreground mode off (the
 * default) it resets the renderers on the playback thread, so the decoders
 * are still given back the moment an engine is done — just without the
 * main thread waiting for it. The idle player that stays behind is a handler
 * thread and an empty allocator, which is nothing worth a stall to reclaim.
 *
 * A slot per (main, profile) pair, because those differ at construction and
 * cannot be reconfigured on a live player. The guide's muted preview must
 * never share the main player: it is built without audio focus, a builder-time
 * choice, and a preview that borrowed the instance the viewer is watching on
 * would have to stop it first. The tolerant player exists only after a stream
 * has failed, so most sessions never build one at all. Each slot holds at most
 * one idle instance; a borrow while the slot's instance is still out (a
 * profile change composes the new engine before the old one's teardown runs)
 * builds a fresh one, and the surplus is released — off the main thread's
 * critical path as far as ExoPlayer allows — when it comes back.
 */
@OptIn(UnstableApi::class)
object PlayerPool {

    /** What makes two players non-interchangeable; see the class comment. */
    /**
     * [silent] is part of the key, not a property of the borrower.
     *
     * A silent player has audio disabled in its track selector, and handing
     * one to a viewer is a film that plays with no sound and nothing on screen
     * to explain it. Today [main] happens to separate them — the only silent
     * borrower is the guide preview, which is also the only one that declines
     * audio focus — so this is belt and braces. It is worth the field anyway:
     * that alignment is a coincidence of the current call sites, and the next
     * silent-but-focus-taking player (or the reverse) would inherit the wrong
     * selector with no compile error and no crash, only silence.
     */
    internal data class Slot(
        val main: Boolean,
        val profile: DecodeProfile,
        val live: Boolean,
        val silent: Boolean,
    )

    /** One borrowed player and the selector it was built with. */
    class Lease internal constructor(
        val player: ExoPlayer,
        val trackSelector: DefaultTrackSelector,
        internal val slot: Slot,
    )

    private val idle = HashMap<Slot, Lease>()

    /**
     * @param main True for the player the viewer watches on: takes audio
     * focus, pauses when headphones unplug, may use tunnelled decoding. False
     * for the guide's silent preview, which must do none of those.
     * @param profile How forgiving to build it; see [DecodeProfile].
     * @param silent Built with audio decoding switched off; see [Slot].
     */
    fun borrow(
        context: Context,
        main: Boolean,
        profile: DecodeProfile = DecodeProfile.FAST,
        live: Boolean = true,
        silent: Boolean = false,
    ): Lease {
        val slot = Slot(main, profile, live, silent)
        idle.remove(slot)?.let { return it }
        return build(context.applicationContext, slot)
    }

    /**
     * Hands a player back. Stops it — which releases its decoders on the
     * playback thread — and empties it, so the next borrower starts from the
     * same blank state a freshly built player would. The caller has already
     * detached its view, its listener and its media session.
     */
    fun giveBack(lease: Lease) {
        val player = lease.player
        player.stop()
        player.clearMediaItems()
        player.playWhenReady = false
        player.volume = 1f
        player.setPlaybackSpeed(1f)
        player.clearVideoSurface()
        if (idle[lease.slot] == null) {
            idle[lease.slot] = lease
        } else {
            // A second instance for the same slot only exists across a swap's
            // overlap. Releasing it is the blocking call this pool exists to
            // avoid, so the builder caps how long it may block.
            player.release()
        }
    }

    /**
     * Releases every idle instance. For a process that is genuinely done
     * playing — the activity finishing — not for leaving the player screen.
     */
    fun drain() {
        idle.values.forEach { it.player.release() }
        idle.clear()
    }

    private fun build(context: Context, slot: Slot): Lease {
        val trackSelector = DefaultTrackSelector(context)
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
            //
            // This is also what loads the bundled FFmpeg audio decoder, and
            // with libVLC gone that module is now the app's only answer for
            // AC-3, E-AC-3, DTS and TrueHD on a box whose hardware doesn't
            // cover them. It is load-bearing; see docs/ffmpeg-decoder.md.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            // A stream whose hardware decoder refuses to initialise retries on
            // another decoder instead of failing the whole item.
            .setEnableDecoderFallback(true)
        if (slot.profile == DecodeProfile.TOLERANT) {
            // The recovery rung's other half: put the software decoders in
            // front. They play what the vendor's hardware refused — odd
            // profiles, unusual bit depths, streams whose headers lie — at a
            // frame rate that will not carry 4K, which is why nothing opens
            // here and only a failure arrives here.
            renderers.setMediaCodecSelector(MediaCodecSelector.PREFER_SOFTWARE)
        }
        // Wraps the HTTP factory so file:// (recordings) resolves, and picks up
        // the RTMP data source reflectively now that the module is on the
        // classpath. DefaultMediaSourceFactory does the same for the DASH,
        // SmoothStreaming and RTSP media sources; HLS is routed by hand so the
        // transport-stream flags reach its segments.
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val player = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(IptvMediaSourceFactory(dataSourceFactory, slot.profile))
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControlFor(slot.live))
            // Proper audio-focus citizenship: request focus as media playback
            // and pause when headphones unplug, instead of talking over
            // whatever was already playing.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ slot.main,
            )
            .setHandleAudioBecomingNoisy(slot.main)
            // Belt and braces for the release() calls that remain — a surplus
            // instance coming back, or draining at process end. The default
            // lets the main thread wait half a second for a decoder that is
            // slow to let go; past a hundred milliseconds the wait is worth
            // less than the frame it costs, and ExoPlayer only logs a timeout.
            .setReleaseTimeoutMs(100)
            .build()
        return Lease(player, trackSelector, slot)
    }
}
