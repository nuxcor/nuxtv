@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.agoro.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
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
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxBorders
import com.agoro.tv.ui.theme.NuxMotion
import com.agoro.tv.ui.theme.NuxShape
import com.agoro.tv.ui.theme.NuxFocus
import com.agoro.tv.ui.theme.Space

private val CardShape = NuxShape.Card

/** How much of a logo chip the logo itself occupies; see [Artwork]. */
private const val LogoFitFraction = 0.72f

private val ChipShape = NuxShape.Chip
private val RestingBorder = NuxBorders.restingCard

/**
 * Clip room for a shelf of scaling cards. A scrollable clips its main axis,
 * so the first card's focus ring — 3dp of stroke plus half the 6% scale
 * growth — lost its left edge against the row's own bound. The row measures
 * wider than its slot by this much on each side and reports its original
 * width, so resting cards keep the gutter alignment while the ring gets
 * somewhere to exist. Pair with contentPadding = PaddingValues(horizontal =
 * ShelfRingRoom) so the first card still rests on the gutter line.
 */
internal val ShelfRingRoom = 14.dp

internal fun Modifier.shelfRingRoom(room: Dp = ShelfRingRoom): Modifier =
    layout { measurable, constraints ->
        val extra = room.roundToPx() * 2
        val placeable = measurable.measure(
            constraints.copy(maxWidth = constraints.maxWidth + extra)
        )
        layout(placeable.width - extra, placeable.height) {
            placeable.placeRelative(-room.roundToPx(), 0)
        }
    }

/**
 * Clock format honouring Android's "Use 24-hour format" toggle.
 *
 * `java.text.DateFormat.getTimeInstance` is locale-driven only, so an en-US
 * viewer who switches their TV to 24-hour still saw "8:00 PM" everywhere.
 * Every clock in the app goes through this, so the guide and the player can't
 * disagree about the same programme.
 */
@Composable
fun rememberClockFormat(): java.text.SimpleDateFormat {
    val context = LocalContext.current
    return remember(context) {
        java.text.SimpleDateFormat(
            if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a",
            java.util.Locale.getDefault(),
        )
    }
}

/**
 * Material3's TextField consumes D-pad keys for cursor movement, which on a
 * remote strands focus inside the field with no way out. This routes them back
 * to focus travel instead — on TV the on-screen keyboard owns text editing, so
 * nothing is lost. Every text field in the app must carry this.
 */
@Composable
fun Modifier.dpadFieldNavigation(
    onDown: (() -> Unit)? = null,
    onUp: (() -> Unit)? = null,
): Modifier {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    return this.onPreviewKeyEvent { event ->
        if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) {
            return@onPreviewKeyEvent false
        }
        val move = { direction: androidx.compose.ui.focus.FocusDirection ->
            focusManager.moveFocus(direction)
            true
        }
        when (event.key.nativeKeyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_DOWN ->
                if (onDown != null) { onDown(); true }
                else move(androidx.compose.ui.focus.FocusDirection.Down)
            android.view.KeyEvent.KEYCODE_DPAD_UP ->
                if (onUp != null) { onUp(); true }
                else move(androidx.compose.ui.focus.FocusDirection.Up)
            android.view.KeyEvent.KEYCODE_DPAD_LEFT ->
                move(androidx.compose.ui.focus.FocusDirection.Left)
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT ->
                move(androidx.compose.ui.focus.FocusDirection.Right)
            else -> false
        }
    }
}

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
    /**
     * Show the full [title] instead of a monogram when there is no artwork.
     * For captionless cards (posters), where the artwork IS the label and a
     * monogram would leave the item nameless.
     */
    fallbackFullTitle: Boolean = false,
    /**
     * The slab painted behind the image. Pass [Color.Transparent] when the
     * caller already supplies the container — nesting this default inside
     * another panel draws a second, square-cornered box inside the first,
     * which is exactly what it looks like.
     */
    background: Color = NuxColors.SurfaceVariant,
) {
    val monogram = remember(title) {
        title.split(" ").take(2).mapNotNull { it.firstOrNull()?.uppercase() }.joinToString("")
    }
    Box(
        modifier = modifier.background(background),
        contentAlignment = Alignment.Center,
    ) {
        // key(): reusing one AsyncImage node across URL changes lets Coil keep
        // painting the *previous* bitmap until the new request resolves — and
        // with no error painter, a logo that 404s (routine for provider logo
        // URLs) leaves the old channel's logo up for good. Zapping CNN→BBC
        // showed BBC's logo on CNN. A fresh node per URL can't inherit pixels.
        androidx.compose.runtime.key(imageUrl) {
            var failed by remember { mutableStateOf(false) }
            // Until the bitmap actually paints, the cell is the SurfaceVariant
            // slab and nothing else — so a catalogue scrolled over a slow
            // provider is a wall of identical grey rectangles. Show the same
            // fallback the error path shows, underneath, and let the image
            // cover it when it arrives.
            var loaded by remember { mutableStateOf(false) }
            // Held for the crossfade's duration: Coil reports success as the
            // fade STARTS, so dropping the fallback there flashes the bare
            // slab for 220ms — the exact thing this is here to prevent.
            var covered by remember { mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(loaded) {
                if (loaded) {
                    kotlinx.coroutines.delay(NuxMotion.ImageCrossfadeMs.toLong())
                    covered = true
                }
            }
            if (imageUrl.isNullOrBlank() || failed || !covered) {
                if (fallbackFullTitle) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = NuxColors.OnSurfaceDim,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                } else {
                    Text(text = monogram, style = monogramStyle, color = NuxColors.OnSurfaceDim)
                }
            }
            if (!imageUrl.isNullOrBlank() && !failed) {
                val context = LocalContext.current
                // Remembered per URL: this was rebuilt on every composition of
                // every card in every grid, and a grid recomposes constantly
                // while it scrolls.
                val request = remember(imageUrl, context) {
                    ImageRequest.Builder(context)
                        .data(imageUrl)
                        .crossfade(NuxMotion.ImageCrossfadeMs)
                        .build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = title,
                    contentScale = contentScale,
                    // A stale logo is worse than no logo: it mislabels what the
                    // viewer is watching. Fall back to the monogram instead.
                    onError = { failed = true },
                    onSuccess = { loaded = true },
                    // Logos are inset by a FRACTION of the chip, never a
                    // fixed dp. The 6dp that breathed on a 52dp guide chip is
                    // a 2.5% hairline on the 240dp channel shelf card, where
                    // it left the logo running to all four edges looking blown
                    // up. A fraction is the same inset at every call site: it
                    // reproduces the small chips almost exactly (0.72 of a
                    // 40dp-tall chip is the 28dp that 6dp of padding gave)
                    // while pulling the big card's logo back off its edges.
                    // Crop is unaffected — posters are meant to be full-bleed.
                    modifier = if (contentScale == ContentScale.Fit) {
                        Modifier.fillMaxSize(LogoFitFraction)
                    } else {
                        Modifier.fillMaxSize()
                    },
                )
            }
        }
    }
}

/**
 * Poster card for movie and series rows. Captionless: the artwork carries the
 * title (and the hero/detail views spell it out), so a text row under every
 * poster was saying it twice. When there is no artwork the full title renders
 * inside the card instead — never an anonymous grey box.
 *
 * [year] is the exception to captionless — key art almost never carries it,
 * and it is what separates a remake from the original and tells a browsing
 * viewer whether a shelf is current. It sits in a line under the poster, not
 * over it: this provider stamps a "4K ULTRA HD" ribbon along the top edge of
 * most of its art and runs wordmarks full-bleed along the bottom, so every
 * overlay position collided with something on some poster. The line lives
 * inside the Surface so it scales and focuses with the card as one piece.
 */
@Composable
fun PosterCard(
    title: String,
    imageUrl: String?,
    /** Fixed width in a row; null fills the cell it is given, as in a grid. */
    width: Dp? = 150.dp,
    year: Int? = null,
    progress: Float? = null,
    onClick: () -> Unit,
    /** Hold OK for the actions OK itself can't offer; null means no menu here. */
    onLongClick: (() -> Unit)? = null,
    onFocus: () -> Unit = {},
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
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
            // Clipped as a stack so the progress bar follows the card's rounded
            // corners instead of squaring off its bottom edge.
            Box(modifier = Modifier.clip(CardShape)) {
                Artwork(
                    imageUrl = imageUrl,
                    title = title,
                    fallbackFullTitle = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f),
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
            // The ROW is unconditional once there is a year; only the digits
            // wait for artwork. [imageUrl] arrives asynchronously — borrowed
            // TMDB art resolves after first composition — so deciding the
            // card's height on it made cards grow ~20dp at different moments,
            // reflowing a row the viewer was already travelling. Reserving the
            // line keeps every card in a row the same height from frame one,
            // while still not printing a year over a card whose own fallback
            // text is the title (which usually carries the year already).
            if (year != null && year > 1800) {
                Text(
                    text = year.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = NuxColors.OnSurface.copy(alpha = 0.55f),
                    // Both insets clear the card's bottom-left corner, which
                    // is an arc and not a right angle: the Surface clips to
                    // CardShape's 16dp radius and the focus ring strokes that
                    // same arc, so digits parked near the corner get sliced
                    // through. Flush-left is not available here at any bottom
                    // padding — 3dp from the edge sits outside the arc for the
                    // text's whole height. Stepping in as well puts the line
                    // inside both the clip and the ring with room to spare.
                    // Hidden rather than omitted when there is no artwork:
                    // same composable, same text, same measurement, just not
                    // painted — so the reserved height tracks the font scale
                    // instead of a constant that only holds at 1.0.
                    modifier = Modifier
                        .alpha(if (imageUrl != null) 1f else 0f)
                        .padding(start = 10.dp, top = 6.dp, bottom = 8.dp),
                )
            }
        }
    }
}

/** Timestamp marking when a list appeared; see [itemEntrance]. */
@Composable
fun rememberListEntrance(key: Any?): Long =
    remember(key) { android.os.SystemClock.uptimeMillis() }

/**
 * Runs an entrance animation whose TERMINAL state must never depend on the
 * animation actually playing. Frame-clock animations suspend on the next
 * vsync, and an idle window (nothing invalidating, no input) can starve that
 * clock — an alpha-gated surface then sits fully composed but painted
 * invisible until the first key press. The timeout runs on the wall clock,
 * not the frame clock, so it always fires; snapTo changes the value without
 * needing a frame first, which is itself what restarts drawing.
 */
suspend fun androidx.compose.animation.core.Animatable<Float, *>.animateToOrSnap(
    target: Float,
    spec: androidx.compose.animation.core.AnimationSpec<Float>,
    timeoutMs: Long,
) {
    val done = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { animateTo(target, spec) }
    if (done == null) snapTo(target)
}

/**
 * Fade + rise used to stagger a list into view.
 *
 * Only items composed in the first moments of the list's life animate. A lazy
 * list composes rows as they scroll in, so animating unconditionally means the
 * row the D-pad just moved to is still transparent and sliding — the list
 * reads as laggy exactly when the viewer is moving fastest.
 */
@Composable
fun Modifier.itemEntrance(index: Int, listStartedAtMs: Long): Modifier {
    val animate = remember {
        android.os.SystemClock.uptimeMillis() - listStartedAtMs < 300
    }
    // Every cell composed after the entrance window allocated an Animatable,
    // launched a coroutine that immediately returned, and added a
    // graphicsLayer — i.e. a RenderNode — to draw itself at alpha 1. That is
    // every cell a scroll brings into view, forever.
    if (!animate) return this
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        val stagger = (index.coerceAtMost(NuxMotion.StaggerCap) * NuxMotion.StaggerStepMs).toLong()
        kotlinx.coroutines.delay(stagger)
        progress.animateToOrSnap(
            1f,
            androidx.compose.animation.core.tween(NuxMotion.StandardMs, easing = NuxMotion.StandardEasing),
            timeoutMs = NuxMotion.StandardMs + 500L,
        )
    }
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * NuxMotion.EntranceRise.toPx()
    }
}

/** Wide row used for channels, episodes and recordings. */
@Composable
fun WideItem(
    title: String,
    subtitle: String? = null,
    /**
     * Optional synopsis, on its own dimmer line under the subtitle. Episode
     * rows are the reason this exists: a title, a runtime and 1,400px of
     * nothing is a row that looks unfinished next to any streaming app.
     */
    body: String? = null,
    imageUrl: String? = null,
    badge: String? = null,
    progress: Float? = null,
    selected: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onFocus: () -> Unit = {},
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        // Callers pass a FocusRequester through here to park focus on a
        // specific row — the channel-number jump needs the row it scrolled to
        // to also be the row the next D-pad press moves from.
        modifier = modifier
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
            border = RestingBorder,
            focusedBorder = NuxFocus.ring,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Selected marker survives focus because it's a separate element.
            if (selected) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(40.dp)
                        .clip(NuxShape.Track)
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
                        // 16:9, and large enough to read as a still rather than
                        // a stamp floating in a mostly empty row.
                        .size(width = if (body != null) 112.dp else 64.dp,
                              height = if (body != null) 63.dp else 38.dp)
                        .clip(ChipShape),
                    contentScale = if (body != null) ContentScale.Crop else ContentScale.Fit,
                    monogramStyle = MaterialTheme.typography.labelLarge,
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
                        style = MaterialTheme.typography.labelMedium,
                        color = NuxColors.OnSurfaceDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (body != null) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.labelMedium,
                        color = NuxColors.OnSurfaceDim,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (progress != null && progress > 0f) {
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(NuxShape.Track)
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
            if (badge != null) {
                Box(modifier = Modifier.padding(start = 4.dp)) {
                    MetaChip(badge, accent = true)
                }
            }
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

/** Full-screen PIN prompt for parental-locked content. */
@Composable
fun PinPrompt(
    onSubmit: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { fieldFocus.requestFocusRetrying() }
    DialogScaffold(
        onDismiss = onDismiss,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        run {
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
                    .focusRequester(fieldFocus)
                    .dpadFieldNavigation(),
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

/** A focusable action in a [ContextMenu]. */
data class MenuAction(val label: String, val destructive: Boolean = false, val onSelect: () -> Unit)

/**
 * Universal secondary-action surface (long-press OK or the MENU key), so items
 * don't have to overload OK with context-dependent meanings.
 */
@Composable
fun ContextMenu(
    title: String,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    // Retried: the row composes a frame after this effect runs, and a single
    // attempt that lands too early leaves the menu open with focus still on
    // the page behind it — the remote then drives the guide THROUGH the scrim.
    LaunchedEffect(Unit) { firstFocus.requestFocusRetrying() }
    DialogScaffold(
        onDismiss = onDismiss,
        width = 420.dp,
        padding = Space.l,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = NuxColors.OnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Space.xs))
            actions.forEachIndexed { index, action ->
                Surface(
                    onClick = { action.onSelect(); onDismiss() },
                    modifier = if (index == 0) {
                        Modifier.fillMaxWidth().focusRequester(firstFocus)
                    } else {
                        Modifier.fillMaxWidth()
                    },
                    shape = ClickableSurfaceDefaults.shape(NuxShape.Row),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = NuxColors.SurfaceVariant,
                        focusedContainerColor = NuxColors.SurfaceRaised,
                        contentColor = if (action.destructive) NuxColors.Error else NuxColors.OnSurface,
                        focusedContentColor = if (action.destructive) NuxColors.Error else NuxColors.OnSurface,
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = NuxFocus.RowScale),
                    border = ClickableSurfaceDefaults.border(focusedBorder = NuxFocus.ring12),
                ) {
                    Text(
                        text = action.label,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = Space.m, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/**
 * What you can do to one playlist. Editing used to be impossible and removing
 * reached only the playlist you were already watching, which left a dead or
 * mistyped source stuck in the list for good.
 *
 * The remove confirmation is a second STEP of this dialog, not a second
 * dialog: closing one scaffold and opening another restarted the scrim and
 * dropped focus onto the page for a frame, so the settings list visibly
 * shifted behind the incoming prompt. Swapping content inside one scaffold
 * keeps the scrim and focus where they are.
 */
@Composable
fun PlaylistOptionsDialog(
    name: String,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmingRemove by remember { mutableStateOf(false) }
    val editFocus = remember { FocusRequester() }
    LaunchedEffect(confirmingRemove) {
        // Also re-takes focus when the stacked confirmation closes.
        if (!confirmingRemove) editFocus.requestFocusRetrying()
    }
    DialogScaffold(
        onDismiss = onDismiss,
        width = 460.dp,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(name, style = MaterialTheme.typography.titleLarge, color = NuxColors.OnSurface)
        Spacer(Modifier.height(Space.s))
        Text(
            "Change this playlist's details, or remove it.",
            style = MaterialTheme.typography.bodyMedium,
            color = NuxColors.OnSurfaceDim,
        )
        Spacer(Modifier.height(Space.l))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            androidx.tv.material3.Button(
                onClick = { onEdit() },
                modifier = Modifier.focusRequester(editFocus),
            ) { Text("Edit") }
            androidx.tv.material3.OutlinedButton(
                onClick = { confirmingRemove = true },
            ) { Text("Remove") }
            androidx.tv.material3.OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        }
    }
    // A real platform window, not an in-layout overlay: every in-layout
    // variant (content swap on 2.17.3, sibling overlay on 2.17.5) verified
    // fine on the emulator and still lost the D-pad to the dialog underneath
    // on real boxes — Compose focus requests around a same-frame swap are
    // timing games. A window takes input focus at the OS level; the remote
    // physically cannot keep driving what is behind it.
    if (confirmingRemove) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { confirmingRemove = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
            ),
        ) {
            ConfirmDialog(
                title = "Remove this playlist?",
                message = "Its cached channels are deleted. Recordings are kept.",
                confirmLabel = "Remove",
                onConfirm = { onRemove() },
                onDismiss = { confirmingRemove = false },
            )
        }
    }
}

/** Confirmation for anything destructive — nothing irreversible on a single OK. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String? = null,
    confirmLabel: String = "Delete",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    // Retried — see ContextMenu; one early attempt leaves the dialog deaf.
    LaunchedEffect(Unit) { cancelFocus.requestFocusRetrying() }
    DialogScaffold(
        onDismiss = onDismiss,
        width = 460.dp,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        run {
            Text(title, style = MaterialTheme.typography.titleLarge, color = NuxColors.OnSurface)
            if (message != null) {
                Spacer(Modifier.height(Space.s))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuxColors.OnSurfaceDim,
                )
            }
            Spacer(Modifier.height(Space.l))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                androidx.tv.material3.OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(cancelFocus),
                ) { Text("Cancel") }
                androidx.tv.material3.Button(onClick = { onConfirm(); onDismiss() }) {
                    Text(confirmLabel)
                }
            }
        }
    }
}

/**
 * Text entry behind a dialog rather than inline in a settings list.
 *
 * A focused TextField on Android TV opens the on-screen keyboard by itself, so
 * a field sitting in the scroll path hijacks the remote every time you D-pad
 * past it — an optional setting that behaves like a mandatory one. Putting it
 * behind an explicit OK means scrolling never summons a keyboard.
 */
@Composable
fun TextInputDialog(
    title: String,
    initialValue: String,
    label: String,
    message: String? = null,
    digitsOnly: Boolean = false,
    confirmLabel: String = "Save",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initialValue) }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { fieldFocus.requestFocusRetrying() }
    DialogScaffold(
        onDismiss = onDismiss,
        width = 620.dp,
    ) {
        run {
            Text(title, style = MaterialTheme.typography.titleLarge, color = NuxColors.OnSurface)
            if (message != null) {
                Spacer(Modifier.height(Space.s))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuxColors.OnSurfaceDim,
                )
            }
            Spacer(Modifier.height(Space.m))
            androidx.compose.material3.OutlinedTextField(
                value = value,
                onValueChange = { entered ->
                    value = if (digitsOnly) entered.filter { it.isDigit() }.take(8) else entered
                },
                label = { androidx.compose.material3.Text(label) },
                singleLine = true,
                visualTransformation = if (digitsOnly) {
                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
                colors = NuxFieldDefaults.colors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldFocus)
                    .dpadFieldNavigation(),
            )
            Spacer(Modifier.height(Space.l))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                androidx.tv.material3.Button(onClick = { onConfirm(value); onDismiss() }) {
                    Text(confirmLabel)
                }
                androidx.tv.material3.OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                if (initialValue.isNotBlank()) {
                    androidx.tv.material3.OutlinedButton(onClick = { onConfirm(""); onDismiss() }) {
                        Text("Clear")
                    }
                }
            }
        }
    }
}

/** Segmented control — replaces chips that silently cycle through states. */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Surface(
                onClick = { onSelect(index) },
                shape = ClickableSurfaceDefaults.shape(NuxShape.Row),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (selected) NuxColors.Primary.copy(alpha = 0.18f)
                    else NuxColors.Surface,
                    focusedContainerColor = NuxColors.SurfaceRaised,
                    contentColor = if (selected) NuxColors.Primary else NuxColors.OnSurfaceDim,
                    focusedContentColor = if (selected) NuxColors.Primary else NuxColors.OnSurface,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = NuxFocus.ButtonScale),
                border = ClickableSurfaceDefaults.border(focusedBorder = NuxFocus.ring12),
            ) {
                Text(
                    text = option,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = Space.m, vertical = 10.dp),
                )
            }
        }
    }
}

/**
 * Soft fades at the top and bottom of a scrolling pane, shown only on the side
 * that has content beyond it.
 *
 * A TV pane clips hard at its edge, so a long list ends mid-glyph — a half
 * height row of letters sliced off by the frame, which reads as a screen that
 * did not finish drawing. A fade says "there is more" in the language every
 * other TV app uses.
 *
 * Place as the last child of the Box holding the list, so it draws over it.
 */
@Composable
fun BoxScope.ScrollEdgeFade(
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
    height: Dp = 28.dp,
) {
    if (canScrollBackward) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(height)
                .background(
                    Brush.verticalGradient(
                        listOf(NuxColors.Background, Color.Transparent)
                    )
                )
        )
    }
    if (canScrollForward) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(height)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, NuxColors.Background)
                    )
                )
        )
    }
}
