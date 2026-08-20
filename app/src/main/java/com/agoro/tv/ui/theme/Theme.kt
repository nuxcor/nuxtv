package com.agoro.tv.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Shapes
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

/**
 * Agoro palette — cinematic charcoal with a warm gold accent.
 *
 * The surface ramp is spaced for a 10-foot dark room: each step is a real
 * lightness increment (ΔL* ≈ 5), so resting cards are visible without focus.
 */
object NuxColors {
    // Warm charcoal, not blue-grey. These were all hue ~220 at 15-21%
    // saturation — near-black on a monitor, but a TV lifts its black level and
    // runs a cool white point, which exposes that tint: the page corners read
    // navy and the resting cards, being the lightest of them, read as solid
    // blue. Same hue as the gold at 7% instead, so no amount of black-level
    // lift can turn the background into another colour.
    val Background = Color(0xFF0B0A09)      // page
    val Surface = Color(0xFF1B1917)         // resting card
    val SurfaceVariant = Color(0xFF282622)  // grouped container / hover
    val SurfaceRaised = Color(0xFF35322E)   // focused / raised
    val Stroke = Color(0xFF3C3A35)          // 1dp card outline
    val StrokeSoft = Color(0x1AFFFFFF)      // dividers

    val Primary = Color(0xFFD99A2E)         // brand gold — never a full focus fill
    val PrimaryDim = Color(0xFF9C6D1C)
    val Secondary = Color(0xFF4FD1C5)
    val OnAccent = Color(0xFF1E1503)

    val OnSurface = Color(0xFFEEEEED)
    val OnSurfaceDim = Color(0xFFB4B1AB)
    val FocusBorder = Color(0xE6FFFFFF)     // focus is white; gold means brand
    val Error = Color(0xFFFF6B6B)
    val Scrim = Color(0xCC0B0A09)

    // Gradient stops. Same warm hue as the surface ramp so black-level lift
    // can't shift them to another colour (see the ramp comment above).
    val BackgroundRaised = Color(0xFF171614) // top stop of the page gradient
    val AccentGlow = Color(0xFF2B2113)       // radial glow behind hero panes
    val OnSecondary = Color(0xFF06251F)
}

/** 4dp-based spacing scale. Screens use [Space.gutter] for their leading edge. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 32.dp
    val xxl = 48.dp

    /**
     * TV-safe margins. Nominal overscan is 5% (48dp/27dp on a 960x540dp
     * canvas) but real panels crop more — verified against a Sony Bravia
     * clipping content at 48dp.
     */
    val gutter = 58.dp
    val gutterVertical = 32.dp
}

/**
 * Semantic corner radii — the app's entire radius vocabulary.
 *
 * tv-material3 surfaces take their shape explicitly (ClickableSurfaceDefaults),
 * so the theme registry alone can't enforce consistency; these named tokens
 * can. Every shape must pair with the [NuxFocus] ring of the same radius.
 */
object NuxShape {
    val Chip = RoundedCornerShape(8.dp)     // chips, guide cells, logo tiles
    val Row = RoundedCornerShape(12.dp)     // rail items, menu rows, toasts
    val Card = RoundedCornerShape(16.dp)    // posters, wide rows, detail art
    val Dialog = RoundedCornerShape(20.dp)  // dialogs and sheets
    val Track = RoundedCornerShape(2.dp)    // progress bars, selection markers
}

val NuxShapes = Shapes(
    extraSmall = NuxShape.Chip,
    small = NuxShape.Row,
    medium = NuxShape.Card,
    large = NuxShape.Dialog,
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * One motion language for the whole app. Durations are short because a TV
 * renders at 10 feet — big elements travelling far read as slow; small fades
 * with a touch of rise read as responsive. Everything here is transform/alpha
 * only: low-end sticks cannot afford animated layout.
 */
object NuxMotion {
    /** Exits and small fades. */
    const val FastMs = 120
    /** Entrances and content swaps. */
    const val StandardMs = 240
    /** Hero text and screen entrances. */
    const val EmphasizedMs = 320

    /** Decelerate-biased standard curve — objects arrive, they don't bounce. */
    val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** Accelerating exit curve; pair with [FastMs]. */
    val ExitEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Stagger for list entrances; capped so deep rows don't trickle in. */
    const val StaggerStepMs = 28
    const val StaggerCap = 8

    /** Dwell before a focused category item selects itself. */
    const val FocusDwellMs = 250

    /**
     * Dwell before a focused rail TAB selects itself — deliberately longer
     * than [FocusDwellMs]: a tab switch replaces the whole screen, and at
     * 250ms merely pausing on Recordings while travelling the rail swapped
     * the pane out from under the viewer. Deliberate rests still switch;
     * pass-through travel no longer does.
     */
    const val TabDwellMs = 450
    /** Debounce before the browse hero swaps to the focused item. */
    const val HeroDebounceMs = 180
    /** Coil image crossfade. */
    const val ImageCrossfadeMs = 220

    /** Rise distance for one-shot entrances (fade + translate up). */
    val EntranceRise = 24.dp
}

/**
 * Type scale for a 960x540dp TV canvas at ~10 feet. Nothing carrying
 * information sits below 14sp; body copy is 16-18sp.
 */
val NuxTypography = Typography(
    displayLarge = TextStyle(fontSize = 48.sp, lineHeight = 56.sp, fontWeight = FontWeight.Bold),
    displayMedium = TextStyle(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold),
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 28.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodySmall = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    // Floor: never smaller than this, and only for de-emphasised metadata.
    labelSmall = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
)

/** One focus language for every focusable surface in the app. */
object NuxFocus {
    const val CardScale = 1.06f
    const val RowScale = 1.0f
    const val ButtonScale = 1.06f

    private val Stroke = BorderStroke(3.dp, NuxColors.FocusBorder)

    // One ring per corner radius in use, allocated once.
    //
    // tv-material3 draws the focus border with the shape carried on the Border,
    // not the surface's own shape, so a single fixed radius is only correct for
    // surfaces that happen to share it. A 16dp ring on an 8dp cell bows visibly
    // off the corner it is supposed to outline — worst in the guide, whose 62dp
    // programme cells are the densest focus targets in the app. Pick the ring
    // that matches the shape you passed to ClickableSurfaceDefaults.shape.
    val ring8: Border = Border(Stroke, shape = NuxShape.Chip)
    val ring12: Border = Border(Stroke, shape = NuxShape.Row)
    val ring16: Border = Border(Stroke, shape = NuxShape.Card)
    val ring20: Border = Border(Stroke, shape = NuxShape.Dialog)
    val ring22: Border = Border(Stroke, shape = RoundedCornerShape(22.dp)) // player pills
    val ringCircle: Border = Border(Stroke, shape = CircleShape)

    /** The card/row default — [NuxShape.Card]. */
    val ring: Border = ring16

    val cardGlow: Glow = Glow(
        elevationColor = NuxColors.Primary.copy(alpha = 0.30f),
        elevation = 24.dp,
    )

    /** Focused containers lift the surface; they never fill with brand gold. */
    val container = NuxColors.SurfaceRaised
}

/**
 * Resting (unfocused) borders, allocated once — per-item allocation during
 * recomposition is the classic scroll-stutter source on TV hardware.
 */
object NuxBorders {
    val strokeCard = BorderStroke(1.dp, NuxColors.Stroke)
    val restingCard: Border = Border(strokeCard, shape = NuxShape.Card)
    val restingChip: Border = Border(strokeCard, shape = NuxShape.Chip)
}

private val PageGradient = Brush.verticalGradient(
    listOf(NuxColors.BackgroundRaised, NuxColors.Background)
)

/**
 * Radial warmth behind a full-screen hero pane. Must be painted FULL-BLEED,
 * outside the overscan inset: drawn inside it, the page gradient underneath
 * stays visible in the margin and the screen grows a lighter frame around a
 * darker middle — two backgrounds where the design has one.
 */
val HeroGlow: Brush = Brush.radialGradient(
    colors = listOf(NuxColors.AccentGlow, NuxColors.Background),
    radius = 1600f,
)

@Composable
fun NuxTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NuxColors.Primary,
            onPrimary = NuxColors.OnAccent,
            primaryContainer = NuxColors.PrimaryDim,
            onPrimaryContainer = NuxColors.OnSurface,
            secondary = NuxColors.Secondary,
            onSecondary = NuxColors.OnSecondary,
            background = NuxColors.Background,
            onBackground = NuxColors.OnSurface,
            surface = NuxColors.Surface,
            onSurface = NuxColors.OnSurface,
            surfaceVariant = NuxColors.SurfaceVariant,
            onSurfaceVariant = NuxColors.OnSurfaceDim,
            error = NuxColors.Error,
            border = NuxColors.FocusBorder,
        ),
        typography = NuxTypography,
        shapes = NuxShapes,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // A subtle page gradient reads as depth; flat black reads as unfinished.
                .background(PageGradient)
        ) {
            content()
        }
    }
}
