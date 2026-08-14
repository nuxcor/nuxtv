package com.nuxcor.nuxtv.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object NuxColors {
    val Background = Color(0xFF0A0D14)
    val Surface = Color(0xFF141926)
    val SurfaceVariant = Color(0xFF1D2433)
    val Primary = Color(0xFF8B7CFF)
    val PrimaryDim = Color(0xFF5D4FD6)
    val Secondary = Color(0xFF22D3EE)
    val OnSurface = Color(0xFFE8EBF5)
    val OnSurfaceDim = Color(0xFF97A0B5)
    val FocusBorder = Color(0xFFB4A9FF)
    val Error = Color(0xFFFF6B6B)
    val Scrim = Color(0xCC0A0D14)
}

@Composable
fun NuxTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NuxColors.Primary,
            onPrimary = Color(0xFF14102E),
            primaryContainer = NuxColors.PrimaryDim,
            onPrimaryContainer = NuxColors.OnSurface,
            secondary = NuxColors.Secondary,
            onSecondary = Color(0xFF06252B),
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
