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
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
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
}

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
    internal data class Slot(val main: Boolean, val profile: DecodeProfile)

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
     */
    fun borrow(
        context: Context,
        main: Boolean,
        profile: DecodeProfile = DecodeProfile.FAST,
    ): Lease {
        val slot = Slot(main, profile)
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
                        // rebuffer loop.
                        //
                        // This was 3s, on the reasoning that a live panel
                        // feeds in real time so the buffer refills in real
                        // time, making a deeper threshold a proportionally
                        // longer hole. Measured against the panel, that is
                        // simply not true: Sky Sports Main Event is an 11
                        // Mbit/s stream delivered at three to three and a half
                        // times real time.
                        //
                        // The threshold is a depth of buffered MEDIA, not a
                        // wall-clock wait: six seconds means six seconds of
                        // playback held back, twice what three did. What the
                        // 3x delivery changes is the price — reaching six
                        // seconds of media takes about two seconds of real
                        // time, so the deeper cushion costs roughly one second
                        // more than the shallower one did, not three.
                        //
                        // Which matters more than it sounds: the alternative
                        // to cushion is hopping to another source, and that
                        // costs a black screen mid-match. Riding the dip out
                        // invisibly beats recovering from it visibly.
                        /* bufferForPlaybackAfterRebufferMs = */ 6_000,
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
