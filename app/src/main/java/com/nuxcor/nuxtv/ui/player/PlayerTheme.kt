package com.nuxcor.nuxtv.ui.player

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nuxcor.nuxtv.ui.theme.NuxColors
import com.nuxcor.nuxtv.ui.theme.NuxShape

/**
 * The player's design tokens — the only place in ui/player allowed to spell
 * out a colour, an alpha or a corner radius. Everything derives from the app
 * palette: warm charcoal scrims, never pure black, so the player's chrome
 * belongs to the same room as the rest of the app.
 */
internal object PlayerTheme {
    /** Full-screen overlay scrim (tracks, catch-up, error). */
    val ScrimStrong = NuxColors.Background.copy(alpha = 0.96f)

    /** Panel scrim that still lets the video glow through (banner, list edge). */
    val ScrimMedium = NuxColors.Background.copy(alpha = 0.90f)

    /**
     * The canvas behind the video. The one deliberate pure black in the
     * player: letterbox bars must disappear into the panel's own black.
     */
    val VideoCanvas = Color.Black

    /** Bottom-of-screen gradient behind the banner + transport stack. */
    val BottomGradient = Brush.verticalGradient(
        listOf(Color.Transparent, NuxColors.Background.copy(alpha = 0.88f))
    )

    /** Top-of-screen gradient behind the VOD title header. */
    val TopGradient = Brush.verticalGradient(
        listOf(NuxColors.Background.copy(alpha = 0.88f), Color.Transparent)
    )

    /** Left-edge panel gradient for the channel list. */
    val PanelGradient = Brush.horizontalGradient(listOf(ScrimStrong, ScrimMedium))

    // Shapes — reuse the app vocabulary so rings keep pairing 1:1.
    val PanelShape = NuxShape.Row
    val CardShape = NuxShape.Card
    val ChipShape = NuxShape.Chip

    /** Labelled control pills; pairs with NuxFocus.ring22. */
    val PillShape = RoundedCornerShape(22.dp)

    // Fills derived from the palette (alphas live only in this file).
    /** Resting fill for overlay rows over a scrim. */
    val RowFill = NuxColors.Surface.copy(alpha = 0.6f)

    /** Gold selection tint — selection is gold, focus is the white ring. */
    val SelectionTint = NuxColors.Primary.copy(alpha = 0.18f)

    /** The prominent (play/pause) control's resting fill. */
    val ProminentFill = NuxColors.OnSurface.copy(alpha = 0.14f)

    /** Unfilled portion of progress tracks over video. */
    val TrackBackground = NuxColors.OnSurface.copy(alpha = 0.22f)

    // Panel geometry.
    val ChannelListWidth = 430.dp
    val CategoryWidth = 300.dp
}

/**
 * Player motion: slightly slower than the app's [com.nuxcor.nuxtv.ui.theme.NuxMotion]
 * because everything here moves over full-bleed video — a chip popping at
 * browse tempo reads as flicker against a moving picture. Transform/alpha
 * only; exits run at ~0.8× so dismissal always feels quicker than arrival.
 */
internal object PlayerMotion {
    const val FastMs = 150
    const val StandardMs = 250
    const val PanelMs = 300
    const val GuideMs = 350

    /** Decelerating arrival. */
    val EnterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Accelerating departure. */
    val ExitEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Mid-stream stalls shorter than this show nothing. */
    const val BufferGraceMs = 500L

    /** How long a zap rests on a channel before its stream is opened. */
    const val ZapDwellMs = 400L

    /** How long the VOD seek chrome lingers after the last seek key. */
    const val SeekFlashMs = 1_500L

    private fun exitMs(ms: Int) = ms * 4 / 5

    /** Fade + rise from the bottom edge — banner, transport, seek chrome. */
    fun enterFromBottom(ms: Int = StandardMs): EnterTransition =
        slideInVertically(tween(ms, easing = EnterEasing)) { it / 2 } +
            fadeIn(tween(ms, easing = EnterEasing))

    fun exitToBottom(ms: Int = StandardMs): ExitTransition =
        slideOutVertically(tween(exitMs(ms), easing = ExitEasing)) { it / 2 } +
            fadeOut(tween(exitMs(ms), easing = ExitEasing))

    /** Fade + drop from the top edge — the VOD title header. */
    fun enterFromTop(ms: Int = StandardMs): EnterTransition =
        slideInVertically(tween(ms, easing = EnterEasing)) { -it / 2 } +
            fadeIn(tween(ms, easing = EnterEasing))

    fun exitToTop(ms: Int = StandardMs): ExitTransition =
        slideOutVertically(tween(exitMs(ms), easing = ExitEasing)) { -it / 2 } +
            fadeOut(tween(exitMs(ms), easing = ExitEasing))

    /** Slide in from the left edge — the channel list and its category column. */
    fun enterFromLeft(ms: Int = PanelMs): EnterTransition =
        slideInHorizontally(tween(ms, easing = EnterEasing)) { -it } +
            fadeIn(tween(ms, easing = EnterEasing))

    fun exitToLeft(ms: Int = PanelMs): ExitTransition =
        slideOutHorizontally(tween(exitMs(ms), easing = ExitEasing)) { -it } +
            fadeOut(tween(exitMs(ms), easing = ExitEasing))

    /** Slide in from the right edge — tracks, catch-up, channel options. */
    fun enterFromRight(ms: Int = PanelMs): EnterTransition =
        slideInHorizontally(tween(ms, easing = EnterEasing)) { it } +
            fadeIn(tween(ms, easing = EnterEasing))

    fun exitToRight(ms: Int = PanelMs): ExitTransition =
        slideOutHorizontally(tween(exitMs(ms), easing = ExitEasing)) { it } +
            fadeOut(tween(exitMs(ms), easing = ExitEasing))

    /** Fade + gentle scale — the error card. */
    fun enterScale(ms: Int = StandardMs): EnterTransition =
        scaleIn(tween(ms, easing = EnterEasing), initialScale = 0.96f) +
            fadeIn(tween(ms, easing = EnterEasing))

    fun exitScale(ms: Int = StandardMs): ExitTransition =
        scaleOut(tween(exitMs(ms), easing = ExitEasing), targetScale = 0.96f) +
            fadeOut(tween(exitMs(ms), easing = ExitEasing))

    /** Fade with a slight vertical settle — the grid guide. */
    fun enterGuide(ms: Int = GuideMs): EnterTransition =
        slideInVertically(tween(ms, easing = EnterEasing)) { it / 24 } +
            fadeIn(tween(ms, easing = EnterEasing))

    fun exitGuide(ms: Int = GuideMs): ExitTransition =
        slideOutVertically(tween(exitMs(ms), easing = ExitEasing)) { it / 24 } +
            fadeOut(tween(exitMs(ms), easing = ExitEasing))

    /** Plain fade — TuneCard over the last frame, the buffering chip. */
    fun enterFade(ms: Int = StandardMs): EnterTransition =
        fadeIn(tween(ms, easing = EnterEasing))

    fun exitFade(ms: Int = StandardMs): ExitTransition =
        fadeOut(tween(exitMs(ms), easing = ExitEasing))
}

/** Overscan-safe insets for the full-bleed player; the player route skips the
 *  TvSafe wrapper, so the guide pays its own margins. PlayerScreen positions
 *  the shrunken video with these same values, which is what keeps the video
 *  exactly inside the slot the guide layout reserves for it. */
internal val PLAYER_GUIDE_PADDING = 40.dp
internal val PLAYER_GUIDE_TOP_PADDING = 28.dp

/** The video corner while the guide is open — 16:9, sized so the details
 *  beside it get a readable column on a 960dp canvas and the grid below keeps
 *  five channel rows. */
internal val PLAYER_GUIDE_VIDEO_WIDTH = 332.dp
internal val PLAYER_GUIDE_VIDEO_HEIGHT = 187.dp
