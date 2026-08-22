package com.agoro.tv.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

/** The hosting Activity, which is what owns the window a mode is requested on. */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Asks the display for the mode that actually suits the stream.
 *
 * A TV box picks one output mode at boot — usually 1080p60 or 4K60 — and stays
 * there no matter what is playing. Nothing about that mode is right for most
 * IPTV: a 25fps European feed shown at 60Hz has to repeat uneven numbers of
 * frames, which is the judder viewers see on every camera pan, and a 4K stream
 * sent to a 1080p output is downscaled inside the box before the panel ever
 * sees it, which is the softness they read as "bad quality". ExoPlayer will
 * only nudge the frame rate when the change is seamless, and on real TV
 * hardware it almost never is, so the request has to be made explicitly here.
 *
 * The switch costs an HDMI re-sync — a second of black — so it is only made
 * when a genuinely better mode exists, and resolution is only ever raised,
 * never lowered: dropping a 4K output to 1080p because a 1080p channel is on
 * would trade a blackout for nothing.
 *
 * The caller decides WHEN, and the rule there is Netflix's: match once, when
 * a stream has settled, and never on the way out. The mode used to be
 * re-evaluated the moment any frame rate was known, so zapping between a
 * 25 fps channel and a 59.94 one re-synced HDMI on every zap — a second of
 * black per channel change — and the player reset the mode on exit, which
 * blanked the Home screen behind it for another second. The launcher does
 * not care what refresh it runs at; the mode stays wherever the last stream
 * put it until a stream wants something better.
 *
 * There is deliberately no setting for this. A viewer cannot be asked whether
 * their box's output mode suits the stream — they can't see the mode, and the
 * question is answerable from the stream and the display alone.
 */
class DisplayModeSwitcher(private val activity: Activity) {

    private var requestedModeId = 0

    private val display: Display?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        }

    /**
     * @param videoHeight decoded height, or 0 before the first frame.
     * @param frameRate decoded frame rate, or null when the stream doesn't say
     * — resolution can still be matched without it, refresh cannot.
     * @param allowResolutionChange false once a stream has already had its
     * resolution matched: an adaptive ladder climbing a rung mid-stream is
     * not a new stream, and raising the output for it would black the
     * picture out in the middle of whatever the viewer is watching.
     */
    fun apply(videoHeight: Int, frameRate: Float?, allowResolutionChange: Boolean = true) {
        val display = display ?: return
        val current = display.mode ?: return
        val modes = display.supportedModes ?: return
        if (modes.size < 2) return

        // Resolution: only ever upward, and only to the smallest mode that
        // actually covers the stream — a 1080p feed has nothing to gain from a
        // 4K output, and each extra switch is another second of black.
        val wantsBiggerSize = allowResolutionChange && videoHeight > current.physicalHeight
        val targetHeight = if (wantsBiggerSize) {
            modes.map { it.physicalHeight }.distinct().sorted()
                .firstOrNull { it >= videoHeight } ?: modes.maxOf { it.physicalHeight }
        } else {
            current.physicalHeight
        }
        val targetWidth = if (targetHeight == current.physicalHeight) {
            current.physicalWidth
        } else {
            modes.filter { it.physicalHeight == targetHeight }.maxOf { it.physicalWidth }
        }
        val candidates = modes.filter {
            it.physicalWidth == targetWidth && it.physicalHeight == targetHeight
        }
        if (candidates.isEmpty()) return

        val best = if (frameRate != null && frameRate > 0f) {
            // Lowest refresh that carries the content cleanly. 50Hz and 100Hz
            // both show 25fps perfectly; the panel has no reason to run at 100.
            candidates
                .filter { judder(it.refreshRate, frameRate) <= JUDDER_TOLERANCE }
                .minByOrNull { it.refreshRate }
                ?: candidates.minByOrNull { judder(it.refreshRate, frameRate) }
        } else {
            // No frame rate to go on: keep the refresh the display already
            // chose and change nothing but the resolution.
            candidates.minByOrNull { abs(it.refreshRate - current.refreshRate) }
        } ?: return

        // Already there, whether we put it there or the display did.
        if (best.modeId == current.modeId && requestedModeId == 0) return
        if (best.modeId == requestedModeId) return

        // A mode that is no better than the one running is not worth a blackout.
        if (best.physicalHeight == current.physicalHeight &&
            frameRate != null && frameRate > 0f &&
            judder(current.refreshRate, frameRate) <= JUDDER_TOLERANCE
        ) return

        requestedModeId = best.modeId
        activity.window.attributes = activity.window.attributes.apply {
            preferredDisplayModeId = best.modeId
        }
    }

    private companion object {
        /**
         * 2%: wide enough that 59.94Hz counts as a clean carrier for 23.976fps
         * and for 29.97, narrow enough that 60Hz never passes as a match for
         * 25 or 50.
         */
        const val JUDDER_TOLERANCE = 0.02f

        /**
         * How far this refresh rate is from showing every frame for an equal
         * number of refreshes. 0 is a perfect multiple; anything above means
         * some frames are held longer than others, which is the stutter.
         */
        fun judder(refreshRate: Float, frameRate: Float): Float {
            val ratio = refreshRate / frameRate
            if (ratio < 0.99f) return Float.MAX_VALUE // can't even show every frame
            val nearest = ratio.roundToInt().coerceAtLeast(1)
            return abs(ratio - nearest) / nearest
        }
    }
}
