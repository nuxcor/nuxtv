package com.agoro.tv.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
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

/** The display this activity's window is showing on. */
internal val Activity.currentDisplay: Display?
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display
    } else {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay
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
 * HDR outranks both. A decoded HDR frame is only half of HDR: the other half
 * is an output mode that can carry PQ or HLG, and on Android 14 a display
 * says which of its modes those are. Pinning one that can't is worse than
 * pinning nothing — the box would have negotiated HDR by itself, and a pinned
 * SDR mode takes that away, which is the grey, flat picture viewers report as
 * "washed out". So a mode that carries the stream's transfer function beats a
 * better-matched refresh that doesn't, and where no mode carries it the
 * display is left exactly where it is.
 *
 * There is deliberately no setting for this. A viewer cannot be asked whether
 * their box's output mode suits the stream — they can't see the mode, and the
 * question is answerable from the stream and the display alone.
 */
class DisplayModeSwitcher(private val activity: Activity) {

    private var requestedModeId = 0

    private val display: Display? get() = activity.currentDisplay

    /**
     * @param videoHeight decoded height, or 0 before the first frame.
     * @param frameRate decoded frame rate, or null when the stream doesn't say
     * — resolution can still be matched without it, refresh cannot.
     * @param hdr the flavour the stream decoded with, or null for SDR. A mode
     * that cannot carry it is never pinned, whatever its refresh.
     * @param allowResolutionChange false once a stream has already had its
     * resolution matched: an adaptive ladder climbing a rung mid-stream is
     * not a new stream, and raising the output for it would black the
     * picture out in the middle of whatever the viewer is watching.
     */
    fun apply(
        videoHeight: Int,
        frameRate: Float?,
        hdr: HdrType? = null,
        allowResolutionChange: Boolean = true,
    ) {
        val display = display ?: return
        val current = display.mode ?: return
        val modes = display.supportedModes ?: return
        val chosen = chooseMode(
            modes = modes.map { it.toOutputMode() },
            current = current.toOutputMode(),
            videoHeight = videoHeight,
            frameRate = frameRate,
            hdr = hdr,
            allowResolutionChange = allowResolutionChange,
            pinned = requestedModeId,
        ) ?: return

        requestedModeId = chosen
        activity.window.attributes = activity.window.attributes.apply {
            preferredDisplayModeId = chosen
        }
    }
}

/**
 * One output mode, reduced to what the choice actually turns on.
 *
 * Kept apart from [Display.Mode] so [chooseMode] is decidable without a
 * display attached — which is the only way this rule can be checked at all.
 * The hardware it exists for is a TV box on the end of an HDMI cable; the
 * emulator has one mode, no HDR and no opinion about either.
 */
internal data class OutputMode(
    val modeId: Int,
    val width: Int,
    val height: Int,
    val refreshRate: Float,
    /** Empty when the mode carries no HDR — or when the platform doesn't say. */
    val hdrTypes: List<Int> = emptyList(),
)

/** What the framework reports, in the terms the rule is written in. */
private fun Display.Mode.toOutputMode() = OutputMode(
    modeId = modeId,
    width = physicalWidth,
    height = physicalHeight,
    refreshRate = refreshRate,
    // Only Android 14 knows HDR per mode. Before that a display reports its
    // HDR support as a whole, so there is nothing to say here and every mode
    // is treated as capable.
    hdrTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        supportedHdrTypes.toList()
    } else {
        emptyList()
    },
)

/**
 * Whether this mode can carry [hdr].
 *
 * Only ever asked once some mode on the display has reported a type at all —
 * a display that answers nowhere is not saying "no HDR", it is not answering,
 * and [chooseMode] skips the question entirely there. Once it IS being asked,
 * an empty list is a real answer: this mode carries none. Reading it the other
 * way is how a 4K60 mode that spent its whole HDMI budget on resolution gets
 * pinned for an HDR stream, which is the exact picture this is here to stop.
 */
private fun OutputMode.carries(hdr: HdrType): Boolean =
    hdr.carriers.any { it in hdrTypes }

/**
 * The mode the display should be pinned to, or null to leave it exactly where
 * it is. The whole rule, with no Android in it; see [DisplayModeSwitcher] for
 * why each clause is there.
 *
 * @param pinned the mode this switcher last asked for, 0 if it has asked for
 * nothing. The difference between "the display is already here" and "we put
 * it here" is what decides whether the request is worth repeating.
 */
internal fun chooseMode(
    modes: List<OutputMode>,
    current: OutputMode,
    videoHeight: Int,
    frameRate: Float?,
    hdr: HdrType?,
    allowResolutionChange: Boolean,
    pinned: Int,
): Int? {
    if (modes.size < 2) return null

    // Resolution: only ever upward, and only to the smallest mode that
    // actually covers the stream — a 1080p feed has nothing to gain from a
    // 4K output, and each extra switch is another second of black.
    val wantsBiggerSize = allowResolutionChange && videoHeight > current.height
    val targetHeight = if (wantsBiggerSize) {
        modes.map { it.height }.distinct().sorted()
            .firstOrNull { it >= videoHeight } ?: modes.maxOf { it.height }
    } else {
        current.height
    }
    val targetWidth = if (targetHeight == current.height) {
        current.width
    } else {
        modes.filter { it.height == targetHeight }.maxOf { it.width }
    }
    val sized = modes.filter { it.width == targetWidth && it.height == targetHeight }
    if (sized.isEmpty()) return null

    // HDR first, refresh second. Where nothing at the target size can carry
    // the stream, drop back to the size the display already has and look for
    // a carrier there — HDR at 1080p beats SDR at 4K, because "washed out" is
    // what the viewer complains about and softness is not. Where nothing
    // carries it at all, change nothing: whatever the box negotiated for
    // itself is better than anything this would pin.
    val carry = hdr?.takeIf { modes.any { mode -> mode.hdrTypes.isNotEmpty() } }
    val candidates = if (carry == null) {
        sized
    } else {
        sized.filter { it.carries(carry) }.ifEmpty {
            modes.filter {
                it.width == current.width && it.height == current.height && it.carries(carry)
            }.ifEmpty { return null }
        }
    }

    val best = if (frameRate != null && frameRate > 0f) {
        // Lowest refresh that carries the content cleanly. 50Hz and 100Hz
        // both show 25fps perfectly; the panel has no reason to run at 100.
        candidates
            .filter { judder(it.refreshRate, frameRate) <= JUDDER_TOLERANCE }
            .minByOrNull { it.refreshRate }
            ?: candidates.minByOrNull { judder(it.refreshRate, frameRate) }
    } else {
        // No frame rate to go on: keep the refresh the display already chose
        // and change nothing but the resolution.
        candidates.minByOrNull { abs(it.refreshRate - current.refreshRate) }
    } ?: return null

    // Already there, whether we put it there or the display did.
    if (best.modeId == current.modeId && pinned == 0) return null
    if (best.modeId == pinned) return null

    // A mode that is no better than the one running is not worth a blackout —
    // unless the one running can't carry the stream's HDR, which is the whole
    // reason to move.
    //
    // The frame-rate clause used to require one, which meant a stream that had
    // not reported its rate skipped this test entirely and pinned whatever sat
    // nearest the current refresh. That is a mode change made on no evidence,
    // and a mode change is an HDMI renegotiation: the box re-signals, and a
    // panel that comes back on a different RGB range renders everything after
    // it slightly too bright or too dark — on live and on films alike, because
    // the pin is never cleared. Without a frame rate there is nothing here
    // worth a blackout for; only a resolution or an HDR carrier is.
    if ((carry == null || current.carries(carry)) &&
        best.height == current.height &&
        (frameRate == null || frameRate <= 0f ||
            judder(current.refreshRate, frameRate) <= JUDDER_TOLERANCE)
    ) return null

    return best.modeId
}

/**
 * 2%: wide enough that 59.94Hz counts as a clean carrier for 23.976fps and
 * for 29.97, narrow enough that 60Hz never passes as a match for 25 or 50.
 */
private const val JUDDER_TOLERANCE = 0.02f

/**
 * How far this refresh rate is from showing every frame for an equal number
 * of refreshes. 0 is a perfect multiple; anything above means some frames are
 * held longer than others, which is the stutter.
 */
private fun judder(refreshRate: Float, frameRate: Float): Float {
    val ratio = refreshRate / frameRate
    if (ratio < 0.99f) return Float.MAX_VALUE // can't even show every frame
    val nearest = ratio.roundToInt().coerceAtLeast(1)
    return abs(ratio - nearest) / nearest
}

/**
 * Declares whether the app's window carries HDR.
 *
 * The output mode is one half of getting HDR to the panel; this is the other.
 * Most boxes switch on the dataspace of the buffers the decoder produces, and
 * on those this changes nothing — but a fair number go by the colour mode the
 * foreground window declares, and there an HDR stream inside a default-mode
 * window is composited as SDR however good the decode was. That is one of the
 * two shapes of a washed-out picture, and this is the cheap half of ruling it
 * out.
 *
 * Only ever asked of a display that says it does HDR. A window asking for a
 * colour mode the panel hasn't got is a request the framework has to turn
 * down, and some turn it down by falling back to a worse composition path than
 * the one the window would have had.
 *
 * Unlike the display mode this IS cleared on the way out, because it costs
 * nothing to clear: no HDMI re-sync, just a relayout of one window. A window
 * left in HDR mode would make every SDR screen behind it — Home's shelves, the
 * guide — pay for a composition path nothing on them needs.
 */
class WindowColorMode(private val activity: Activity) {

    private var applied = ActivityInfo.COLOR_MODE_DEFAULT

    /** @param hdr whether what the window is showing right now decoded as HDR. */
    fun set(hdr: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val wanted =
            if (hdr && activity.currentDisplay?.isHdr == true) ActivityInfo.COLOR_MODE_HDR
            else ActivityInfo.COLOR_MODE_DEFAULT
        if (wanted == applied) return
        applied = wanted
        // A display that has gone away between the check and the set, or a
        // window already torn down, must not take the player with it.
        runCatching { activity.window.colorMode = wanted }
    }
}
