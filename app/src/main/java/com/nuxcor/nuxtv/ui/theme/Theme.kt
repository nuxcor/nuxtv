package com.nuxcor.nuxtv.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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

val NuxShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

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
    val ring8: Border = Border(Stroke, shape = RoundedCornerShape(8.dp))
    val ring10: Border = Border(Stroke, shape = RoundedCornerShape(10.dp))
    val ring12: Border = Border(Stroke, shape = RoundedCornerShape(12.dp))
    val ring16: Border = Border(Stroke, shape = RoundedCornerShape(16.dp))
    val ring18: Border = Border(Stroke, shape = RoundedCornerShape(18.dp))
    val ring22: Border = Border(Stroke, shape = RoundedCornerShape(22.dp))
    val ringCircle: Border = Border(Stroke, shape = CircleShape)

    /** The card/row default — [NuxShapes.medium]. */
    val ring: Border = ring16

    val cardGlow: Glow = Glow(
        elevationColor = NuxColors.Primary.copy(alpha = 0.30f),
        elevation = 24.dp,
    )

    /** Focused containers lift the surface; they never fill with brand gold. */
    val container = NuxColors.SurfaceRaised
}

private val PageGradient = Brush.verticalGradient(
    listOf(Color(0xFF171614), NuxColors.Background)
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
            onSecondary = Color(0xFF06251F),
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
