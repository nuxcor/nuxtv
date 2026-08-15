@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuxcor.nuxtv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuxcor.nuxtv.ui.theme.NuxColors
import com.nuxcor.nuxtv.ui.theme.NuxFocus
import com.nuxcor.nuxtv.ui.theme.Space

private val CardShape = RoundedCornerShape(16.dp)
private val ChipShape = RoundedCornerShape(8.dp)

@Composable
fun focusBorder(): Border = NuxFocus.ring

/**
 * Artwork with a neutral fallback. Logos use [ContentScale.Fit] on a neutral
 * chip so transparent PNGs are neither cropped nor tinted by a random gradient.
 */
@Composable
fun Artwork(
    imageUrl: String?,
    title: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    monogramStyle: TextStyle = MaterialTheme.typography.titleMedium,
) {
    val monogram = remember(title) {
        title.split(" ").take(2).mapNotNull { it.firstOrNull()?.uppercase() }.joinToString("")
    }
    Box(
        modifier = modifier.background(NuxColors.SurfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUrl.isNullOrBlank()) {
            Text(text = monogram, style = monogramStyle, color = NuxColors.OnSurfaceDim)
        } else {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUrl)
                    .crossfade(220)
                    .build(),
                contentDescription = title,
                contentScale = contentScale,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (contentScale == ContentScale.Fit) 6.dp else 0.dp),
            )
        }
    }
}

/** Poster card for movie and series rows. */
@Composable
fun PosterCard(
    title: String,
    imageUrl: String?,
    subtitle: String? = null,
    width: Dp = 150.dp,
    progress: Float? = null,
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
        scale = ClickableSurfaceDefaults.scale(focusedScale = NuxFocus.CardScale),
        border = ClickableSurfaceDefaults.border(focusedBorder = NuxFocus.ring),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = NuxFocus.cardGlow),
    ) {
        Column {
            Box {
                Artwork(
                    imageUrl = imageUrl,
                    title = title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(CardShape),
                    monogramStyle = MaterialTheme.typography.headlineSmall,
                )
                if (progress != null && progress > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.White.copy(alpha = 0.25f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .background(NuxColors.Primary)
                        )
                    }
                }
            }
            Spacer(Modifier.height(Space.s))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = NuxColors.OnSurfaceDim,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Wide row used for channels, episodes and recordings. */
@Composable
fun WideItem(
    title: String,
    subtitle: String? = null,
    imageUrl: String? = null,
    badge: String? = null,
    progress: Float? = null,
    selected: Boolean = false,
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
            // Opaque surface + stroke: cards must be visible before focus.
            containerColor = NuxColors.Surface,
            focusedContainerColor = NuxFocus.container,
            contentColor = NuxColors.OnSurface,
            focusedContentColor = NuxColors.OnSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = NuxFocus.RowScale),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, NuxColors.Stroke), shape = CardShape),
            focusedBorder = NuxFocus.ring,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.m, vertical = Space.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            // Selected marker survives focus because it's a separate element.
            if (selected) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(40.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(NuxColors.Primary)
                )
            }
            if (leading != null) {
                leading()
            } else {
                Artwork(
                    imageUrl = imageUrl,
                    title = title,
                    modifier = Modifier
                        .size(width = 76.dp, height = 44.dp)
                        .clip(ChipShape),
                    contentScale = ContentScale.Fit,
                    monogramStyle = MaterialTheme.typography.labelLarge,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = NuxColors.OnSurfaceDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (progress != null && progress > 0f) {
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.2f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .background(NuxColors.Primary)
                        )
                    }
                }
            }
            if (badge != null) MetaChip(badge, accent = true)
        }
    }
}

@Composable
fun MetaChip(text: String, accent: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(ChipShape)
            .background(if (accent) NuxColors.Primary.copy(alpha = 0.18f) else NuxColors.SurfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (accent) NuxColors.Primary else NuxColors.OnSurfaceDim,
        )
    }
}

/** Rating as a number with a single star — glyph rows render as tofu on TVs. */
@Composable
fun RatingStars(rating: Double, voteCount: Int? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Icon(
            imageVector = Icons.Default.Star,
            contentDescription = null,
            tint = NuxColors.Primary,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = "%.1f".format(rating),
            style = MaterialTheme.typography.titleMedium,
            color = NuxColors.OnSurface,
        )
        voteCount?.let {
            Text(
                text = "%,d votes".format(it),
                style = MaterialTheme.typography.labelMedium,
                color = NuxColors.OnSurfaceDim,
            )
        }
    }
}

@Composable
fun SectionTitle(text: String, count: Int? = null, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            color = NuxColors.OnSurface,
        )
        count?.let {
            Text(
                text = it.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = NuxColors.OnSurfaceDim,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
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
                CircularProgressIndicator(color = NuxColors.Primary, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(Space.l))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = NuxColors.OnSurface,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(Space.s))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuxColors.OnSurfaceDim,
                )
            }
        }
    }
}

/** Full-screen PIN prompt for parental-locked content. */
@Composable
fun PinPrompt(
    onSubmit: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuxColors.Scrim),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(NuxColors.Surface)
                .border(1.dp, NuxColors.Stroke, RoundedCornerShape(20.dp))
                .padding(Space.xl),
        ) {
            Text("Enter PIN", style = MaterialTheme.typography.titleLarge, color = NuxColors.OnSurface)
            if (error) {
                Spacer(Modifier.height(Space.xs))
                Text("Wrong PIN", style = MaterialTheme.typography.labelMedium, color = NuxColors.Error)
            }
            Spacer(Modifier.height(Space.m))
            androidx.compose.material3.OutlinedTextField(
                value = pin,
                onValueChange = { value -> pin = value.filter { ch -> ch.isDigit() }.take(8); error = false },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                textStyle = MaterialTheme.typography.titleMedium,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = NuxColors.OnSurface,
                    unfocusedTextColor = NuxColors.OnSurface,
                    focusedBorderColor = NuxColors.Primary,
                    unfocusedBorderColor = NuxColors.Stroke,
                    cursorColor = NuxColors.Primary,
                ),
                modifier = Modifier
                    .width(200.dp)
                    .focusRequester(fieldFocus),
            )
            Spacer(Modifier.height(Space.m))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                androidx.tv.material3.OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                androidx.tv.material3.Button(onClick = { if (!onSubmit(pin)) error = true }) {
                    Text("Unlock")
                }
            }
        }
    }
}
