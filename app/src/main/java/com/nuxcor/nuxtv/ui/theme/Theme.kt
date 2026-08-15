package com.nuxcor.nuxtv.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
 * Dzidzi palette — cinematic charcoal with a warm gold accent.
 *
 * The surface ramp is spaced for a 10-foot dark room: each step is a real
 * lightness increment (ΔL* ≈ 5), so resting cards are visible without focus.
 */
object NuxColors {
    val Background = Color(0xFF08090C)      // page
    val Surface = Color(0xFF15171D)         // resting card
    val SurfaceVariant = Color(0xFF1F232B)  // grouped container / hover
    val SurfaceRaised = Color(0xFF2A2F39)   // focused / raised
    val Stroke = Color(0xFF32373F)          // 1dp card outline
    val StrokeSoft = Color(0x1AFFFFFF)      // dividers

    val Primary = Color(0xFFE6B450)         // brand gold — never a full focus fill
    val PrimaryDim = Color(0xFFB98A2E)
    val Secondary = Color(0xFF4FD1C5)
    val OnAccent = Color(0xFF1E1503)

    val OnSurface = Color(0xFFECEDEF)
    val OnSurfaceDim = Color(0xFFA7ACB8)
    val FocusBorder = Color(0xE6FFFFFF)     // focus is white; gold means brand
    val Error = Color(0xFFFF6B6B)
    val Scrim = Color(0xCC08090C)
}

/** 4dp-based spacing scale. Screens use [Space.gutter] for their leading edge. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 32.dp
    val xxl = 48.dp

    /** 5% TV-safe horizontal margin on a 960dp canvas. */
    val gutter = 48.dp
    val gutterVertical = 27.dp
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

    // Allocated once: these are immutable and were previously rebuilt for
    // every focusable item on every recomposition.
    val ring: Border = Border(
        border = BorderStroke(3.dp, NuxColors.FocusBorder),
        shape = RoundedCornerShape(16.dp),
    )

    val cardGlow: Glow = Glow(
        elevationColor = NuxColors.Primary.copy(alpha = 0.30f),
        elevation = 24.dp,
    )

    /** Focused containers lift the surface; they never fill with brand gold. */
    val container = NuxColors.SurfaceRaised
}

private val PageGradient = Brush.verticalGradient(
    listOf(Color(0xFF11141A), NuxColors.Background)
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
