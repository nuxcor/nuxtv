package com.agoro.tv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

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
 * Two slots, because the guide's muted preview must never share the main
 * player: the preview is built without audio focus and without tunnelling,
 * both builder-time choices, and a preview that borrowed the instance the
 * viewer is watching on would have to stop it first. Each slot holds at most
 * one idle instance; a borrow while the slot's instance is still out (an
 * engine swap composes the new engine before the old one's teardown runs)
 * builds a fresh one, and the surplus is released — off the main thread's
 * critical path as far as ExoPlayer allows — when it comes back.
 */
@OptIn(UnstableApi::class)
object PlayerPool {

    /** One borrowed player and the selector it was built with. */
    class Lease internal constructor(
        val player: ExoPlayer,
        val trackSelector: DefaultTrackSelector,
        internal val main: Boolean,
    )

    private var idleMain: Lease? = null
    private var idlePreview: Lease? = null

    /**
     * @param main True for the player the viewer watches on: takes audio
     * focus, pauses when headphones unplug, may use tunnelled decoding. False
     * for the guide's silent preview, which must do none of those.
     */
    fun borrow(context: Context, main: Boolean): Lease {
        val idle = if (main) idleMain else idlePreview
        if (idle != null) {
            if (main) idleMain = null else idlePreview = null
            return idle
        }
        return build(context.applicationContext, main)
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
        val slotFree = if (lease.main) idleMain == null else idlePreview == null
        if (slotFree) {
            if (lease.main) idleMain = lease else idlePreview = lease
        } else {
            // A second instance for the same slot only exists across an engine
            // swap's overlap. Releasing it is the blocking call this pool
            // exists to avoid, so the builder caps how long it may block.
            player.release()
        }
    }

    /**
     * Releases every idle instance. For a process that is genuinely done
     * playing — the activity finishing — not for leaving the player screen.
     */
    fun drain() {
        idleMain?.player?.release()
        idleMain = null
        idlePreview?.player?.release()
        idlePreview = null
    }

    private fun build(context: Context, main: Boolean): Lease {
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
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            // A stream whose hardware decoder refuses to initialise retries on
            // another decoder instead of failing the whole item.
            .setEnableDecoderFallback(true)
        // Wraps the HTTP factory so file:// (recordings) resolves, and picks up
        // the RTMP data source reflectively now that the module is on the
        // classpath. DefaultMediaSourceFactory does the same for the HLS, DASH,
        // SmoothStreaming and RTSP media sources.
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val player = ExoPlayer.Builder(context, renderers)
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
                /* handleAudioFocus = */ main,
            )
            .setHandleAudioBecomingNoisy(main)
            // Belt and braces for the release() calls that remain — a surplus
            // instance coming back, or draining at process end. The default
            // lets the main thread wait half a second for a decoder that is
            // slow to let go; past a hundred milliseconds the wait is worth
            // less than the frame it costs, and ExoPlayer only logs a timeout.
            .setReleaseTimeoutMs(100)
            .build()
        return Lease(player, trackSelector, main)
    }
}
