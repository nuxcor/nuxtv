package com.agoro.tv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxMotion
import com.agoro.tv.ui.theme.Space

/**
 * Grows the layer past its parent's bounds so ambient art can reach the screen
 * edge from inside a padded pane.
 *
 * Every browse and detail screen is composed inside the TV-safe gutter, so a
 * backdrop laid out normally stopped 58dp short on the right and 32dp short at
 * the bottom — and with nothing feathering those edges it read as a
 * translucent rectangle pasted onto the page, seams and all. Nothing here
 * clips, so drawing outside the bounds is safe; the layer still *reports* the
 * parent's size, so it changes no layout.
 */
private fun Modifier.bleed(horizontal: Dp, vertical: Dp) = layout { measurable, constraints ->
    val h = horizontal.roundToPx()
    val v = vertical.roundToPx()
    val placeable = measurable.measure(
        Constraints.fixed(
            width = (constraints.maxWidth + h * 2).coerceAtLeast(0),
            height = (constraints.maxHeight + v * 2).coerceAtLeast(0),
        )
    )
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(-h, -v) }
}

/**
 * Allocated once, like the theme's own gradients.
 *
 * Modifier.background caches its compiled Shader per Brush INSTANCE, so a
 * fresh instance per recomposition threw that cache away and rebuilt a
 * full-screen shader every time the hero changed — twice, on the largest
 * surface in the app. Theme.kt hoists PageGradient and HeroGlow for exactly
 * this reason; these two were the ones that got away.
 *
 * Both span the IMAGE BOX now, not the screen. The old horizontal scrim ran
 * across the whole width, so the 30% leading strip — where there is no image
 * at all — was painted Background-over-Background at ~98% every frame, and
 * the vertical one did the same across the full width. These stops reproduce
 * what the full-width gradient evaluated to across the image's own span
 * (~0.97 at its leading edge, 0.96 a third of the way in, 0.72 at the trailing
 * edge), so the art reads the same and the fill is confined to where it does
 * something.
 */
private val HorizontalScrim = Brush.horizontalGradient(
    0f to NuxColors.Background.copy(alpha = 0.975f),
    0.29f to NuxColors.Background.copy(alpha = 0.96f),
    1f to NuxColors.Background.copy(alpha = 0.72f),
)

private val VerticalScrim = Brush.verticalGradient(
    0f to NuxColors.Background.copy(alpha = 0.10f),
    0.45f to NuxColors.Background.copy(alpha = 0.45f),
    1f to NuxColors.Background,
)

/**
 * TMDB's `original` is the full 3840×2160 JPEG. Behind two scrims that hide
 * most of it, at 70% of a 1080p (or 4K) canvas, that was an 8–33 MB decode
 * per hero change — every 180ms while a row was travelled — and each one
 * evicted a handful of posters from the memory cache on its way through.
 * w1280 is indistinguishable here and a quarter of the work. The URLs are
 * persisted in prefs, so they are rewritten at use rather than migrated.
 */
private fun ambientSize(url: String): String =
    if (url.contains("image.tmdb.org/t/p/original/")) {
        url.replace("/t/p/original/", "/t/p/w1280/")
    } else url

/**
 * Ambient artwork behind a content pane: the image is cropped to the trailing
 * side and faded into [NuxColors.Background] so text on the leading side stays
 * fully legible. Used by the detail screens and the browse heroes.
 *
 * Draw it as the first child of a full-size Box, under the content. [bleedX]
 * and [bleedY] default to the TV-safe gutter, which is what a pane composed
 * inside [Space] margins needs to reach the screen edge;
 * pass zero when the caller already sits outside that padding.
 *
 * ONE image node, and the cross-fade is drawn by hand. This used to be a
 * Crossfade over a fresh AsyncImage per URL: two full-screen alpha layers for
 * the fade's duration, each holding an image plus two full-screen gradient
 * boxes, with Coil running a third fade inside — seven screen-sized fill
 * passes and two offscreen buffers per frame at the exact moment the viewer
 * stops on a card and looks. And because the hero retargets every 180ms
 * while a row is travelled but the fade took 240ms, the Crossfade never
 * settled: every hero seen during the travel stayed composed, bitmap and all,
 * until the viewer paused. Here the previous bitmap is kept as a plain
 * Painter and the incoming one is drawn over it with a per-draw alpha — no
 * layer, nothing retained beyond the two bitmaps, and a URL that 404s clears
 * both (the same stale-pixels rule Artwork documents).
 */
@Composable
fun BoxScope.BackdropLayer(
    imageUrl: String?,
    widthFraction: Float = 0.7f,
    bleedX: Dp = Space.gutter,
    bleedY: Dp = Space.gutterVertical,
) {
    val url = imageUrl?.takeIf { it.isNotBlank() }?.let(::ambientSize)
    val context = LocalContext.current
    // Remembered per URL: this is the largest image in the app and the hero
    // recomposes on every focus rest.
    val request = remember(url, context) {
        url?.let { ImageRequest.Builder(context).data(it).build() }
    }
    val painter = rememberAsyncImagePainter(model = request, contentScale = ContentScale.Crop)
    val state by painter.state.collectAsState()

    // The bitmap on screen, and the one fading in over it.
    var settled by remember { mutableStateOf<Painter?>(null) }
    var incoming by remember { mutableStateOf<Painter?>(null) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(state, url) {
        when (val s = state) {
            is AsyncImagePainter.State.Success -> {
                if (s.painter === settled) return@LaunchedEffect
                incoming = s.painter
                fade.snapTo(0f)
                fade.animateTo(
                    1f,
                    tween(NuxMotion.ImageCrossfadeMs, easing = NuxMotion.StandardEasing),
                )
                settled = s.painter
                incoming = null
            }
            is AsyncImagePainter.State.Error -> {
                // Nothing may stand in for a missing image: the previous
                // item's art behind this item's title is a mislabel.
                incoming = null
                settled = null
            }
            else -> if (url == null) {
                incoming = null
                settled = null
            } else {
                // Loading keeps the previous art up until the new one lands.
                // A fade the previous run was cut off in the middle of
                // finishes as settled, so nothing sits half-faded meanwhile.
                incoming?.let { settled = it; incoming = null }
            }
        }
    }

    Box(
        modifier = Modifier
            .matchParentSize()
            .bleed(bleedX, bleedY)
            // The ground under a hero is flat Background, edge to edge. The
            // old full-width scrims tinted the theme's page gradient to
            // within 2% of Background everywhere, so confining them to the
            // image box left the lane's leading third visibly lighter than
            // the rest — a seam down the screen at the image's edge. One
            // opaque fill is the cheapest pass a GPU can make (no blend, no
            // shader) and it restores the same flat ground. Only while there
            // is art to stand on: with no hero the page keeps its gradient,
            // as it always did.
            .drawBehind { if (url != null) drawRect(NuxColors.Background) },
    ) {
        // No art, nothing at all — the scrims are there to tame an image,
        // and painted over the bare page they cut the same seam the fill
        // above exists to prevent.
        if (url != null) Box(
            modifier = Modifier
                .fillMaxWidth(widthFraction)
                .fillMaxHeight()
                .align(Alignment.TopEnd)
                .drawBehind {
                    // Coil sizes the decode from the first draw, so the
                    // painter has to be drawn even before it has pixels.
                    val live = painter
                    val base = settled
                    val next = incoming
                    if (base != null) drawCropped(base, 1f)
                    if (next != null) drawCropped(next, fade.value)
                    if (base == null && next == null && url != null) {
                        with(live) { draw(size, alpha = 0f) }
                    }
                }
                // Two scrims, because one was never enough.
                //
                // Horizontal alone left the art running at ~65% at the
                // trailing edge: the backdrop's own lettering read plainly
                // through the poster grid and, on the detail screens,
                // collided with the review copy. It also did nothing about
                // the bottom, where the image was at full strength directly
                // behind the densest content on the screen. The vertical one
                // is ambient at the top where the hero text sits, opaque by
                // the content band below it.
                .background(HorizontalScrim)
                .background(VerticalScrim),
        )
    }
}

/**
 * [ContentScale.Crop], centred, by hand — the painter is drawn straight into
 * this scope so the fade can be a paint alpha rather than a layer.
 */
private fun DrawScope.drawCropped(painter: Painter, alpha: Float) {
    val src = painter.intrinsicSize
    if (src.isUnspecified || src.width <= 0f || src.height <= 0f) {
        with(painter) { draw(size, alpha = alpha) }
        return
    }
    val scale = ContentScale.Crop.computeScaleFactor(src, size)
    val w = src.width * scale.scaleX
    val h = src.height * scale.scaleY
    val dx = (size.width - w) / 2f
    val dy = (size.height - h) / 2f
    clipRect {
        translate(dx, dy) {
            with(painter) { draw(Size(w, h), alpha = alpha) }
        }
    }
}
