@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuxcor.nuxtv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuxcor.nuxtv.ui.theme.NuxColors

private val CardShape = RoundedCornerShape(10.dp)

@Composable
fun focusBorder(): Border = Border(
    border = BorderStroke(2.5.dp, NuxColors.FocusBorder),
    shape = CardShape,
)

/** Deterministic accent gradient used as artwork fallback, seeded by the title. */
private fun fallbackBrush(seed: String): Brush {
    val palette = listOf(
        Color(0xFF3B2F80) to Color(0xFF171B2E),
        Color(0xFF14536B) to Color(0xFF141A2C),
        Color(0xFF5A2E6E) to Color(0xFF191627),
        Color(0xFF2E5540) to Color(0xFF13202A),
        Color(0xFF6E4A2E) to Color(0xFF201A26),
    )
    val (start, end) = palette[(seed.hashCode().mod(palette.size))]
    return Brush.linearGradient(listOf(start, end))
}

@Composable
fun Artwork(
    imageUrl: String?,
    title: String,
    modifier: Modifier = Modifier,
    monogramSize: Int = 34,
) {
    Box(modifier = modifier.background(fallbackBrush(title)), contentAlignment = Alignment.Center) {
        Text(
            text = title.split(" ").take(2).mapNotNull { it.firstOrNull()?.uppercase() }.joinToString(""),
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = Color.White.copy(alpha = 0.35f),
        )
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Poster-style card for movies and series rows. */
@Composable
fun PosterCard(
    title: String,
    imageUrl: String?,
    subtitle: String? = null,
    width: Dp = 128.dp,
    onClick: () -> Unit,
    onFocus: () -> Unit = {},
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(width)
            .onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(CardShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            contentColor = NuxColors.OnSurface,
            focusedContentColor = NuxColors.OnSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(focusedBorder = focusBorder()),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = NuxColors.Primary.copy(alpha = 0.5f), elevation = 16.dp)
        ),
    ) {
        Column {
            Artwork(
                imageUrl = imageUrl,
                title = title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(CardShape),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = NuxColors.OnSurfaceDim,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 2.dp, end = 2.dp),
                )
            }
        }
    }
}

/** Wide row item used for channels and episodes. */
@Composable
fun WideItem(
    title: String,
    subtitle: String? = null,
    imageUrl: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
    onFocus: () -> Unit = {},
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(CardShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuxColors.Surface.copy(alpha = 0.55f),
            focusedContainerColor = NuxColors.SurfaceVariant,
            contentColor = NuxColors.OnSurface,
            focusedContentColor = NuxColors.OnSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        border = ClickableSurfaceDefaults.border(focusedBorder = focusBorder()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (leading != null) {
                leading()
            } else {
                Artwork(
                    imageUrl = imageUrl,
                    title = title,
                    modifier = Modifier
                        .size(width = 72.dp, height = 44.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    monogramSize = 16,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = NuxColors.OnSurfaceDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun MetaChip(text: String, accent: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (accent) NuxColors.Primary.copy(alpha = 0.22f) else NuxColors.SurfaceVariant)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (accent) NuxColors.FocusBorder else NuxColors.OnSurfaceDim,
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = NuxColors.OnSurface,
        modifier = modifier.padding(bottom = 10.dp),
    )
}

@Composable
fun CenteredMessage(
    title: String,
    subtitle: String? = null,
    loading: Boolean = false,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (loading) {
                CircularProgressIndicator(color = NuxColors.Primary)
                Spacer(Modifier.height(18.dp))
            }
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = NuxColors.OnSurface)
            if (subtitle != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuxColors.OnSurfaceDim,
                )
            }
        }
    }
}
