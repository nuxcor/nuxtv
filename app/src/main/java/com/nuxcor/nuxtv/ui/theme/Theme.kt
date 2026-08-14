package com.nuxcor.nuxtv.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * Dzidzi palette — cinematic charcoal neutrals with a warm gold accent.
 */
object NuxColors {
    val Background = Color(0xFF0B0C0F)
    val Surface = Color(0xFF15171B)
    val SurfaceVariant = Color(0xFF21242B)
    val Primary = Color(0xFFE6B450)      // warm gold
    val PrimaryDim = Color(0xFFB98A2E)
    val Secondary = Color(0xFF4FD1C5)    // muted teal
    val OnAccent = Color(0xFF1E1503)     // text on gold
    val OnSurface = Color(0xFFECEDEF)
    val OnSurfaceDim = Color(0xFF979BA6)
    val FocusBorder = Color(0xFFF5CE7E)
    val Error = Color(0xFFFF6B6B)
    val Scrim = Color(0xCC0B0C0F)
}

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
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NuxColors.Background)
        ) {
            content()
        }
    }
}
