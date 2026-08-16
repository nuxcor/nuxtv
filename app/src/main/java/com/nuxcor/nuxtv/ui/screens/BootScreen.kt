package com.nuxcor.nuxtv.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuxcor.nuxtv.ui.theme.NuxColors

/**
 * Animated boot screen: the gold play mark springs in over a breathing glow
 * while the AGORO wordmark letters rise in one by one.
 */
@Composable
fun BootScreen() {
    val markScale = remember { Animatable(0.3f) }
    val letters = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        markScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow),
        )
    }
    LaunchedEffect(Unit) {
        letters.animateTo(1f, animationSpec = tween(durationMillis = 1100, easing = LinearEasing))
    }

    val breathe by rememberInfiniteTransition(label = "glow").animateFloat(
        initialValue = 0.75f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "glowScale",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuxColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                // Breathing radial glow behind the mark.
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .scale(breathe)
                        .background(
                            Brush.radialGradient(
                                listOf(NuxColors.Primary.copy(alpha = 0.28f), Color.Transparent)
                            )
                        )
                )
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(
                        com.nuxcor.nuxtv.R.drawable.ic_splash
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(120.dp).scale(markScale.value),
                )
            }
            Spacer(Modifier.height(20.dp))
            Row {
                "AGORO".forEachIndexed { index, letter ->
                    val reveal = ((letters.value - index * 0.09f) * 4f).coerceIn(0f, 1f)
                    Text(
                        text = letter.toString(),
                        style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black),
                        color = NuxColors.Primary,
                        modifier = Modifier
                            .alpha(reveal)
                            .offset { IntOffset(0, ((1f - reveal) * 24).toInt()) },
                    )
                }
            }
            val taglineAlpha = ((letters.value - 0.6f) * 3f).coerceIn(0f, 1f)
            Text(
                text = "Your playlists, organized like real TV",
                style = MaterialTheme.typography.bodyMedium,
                color = NuxColors.OnSurfaceDim,
                modifier = Modifier.alpha(taglineAlpha),
            )
        }
    }
}
