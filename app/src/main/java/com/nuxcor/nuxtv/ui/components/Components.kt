@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuxcor.nuxtv.ui.components

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
import com.nuxcor.nuxtv.ui.theme.NuxBorders
import com.nuxcor.nuxtv.ui.theme.NuxMotion
import com.nuxcor.nuxtv.ui.theme.NuxShape
import com.nuxcor.nuxtv.ui.theme.NuxFocus
import com.nuxcor.nuxtv.ui.theme.Space

private val CardShape = NuxShape.Card
private val ChipShape = NuxShape.Chip
private val RestingBorder = NuxBorders.restingCard

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
) {
    val monogram = remember(title) {
        title.split(" ").take(2).mapNotNull { it.firstOrNull()?.uppercase() }.joinToString("")
    }
    Box(
        modifier = modifier.background(NuxColors.SurfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        // key(): reusing one AsyncImage node across URL changes lets Coil keep
        // painting the *previous* bitmap until the new request resolves — and
        // with no error painter, a logo that 404s (routine for provider logo
        // URLs) leaves the old channel's logo up for good. Zapping CNN→BBC
        // showed BBC's logo on CNN. A fresh node per URL can't inherit pixels.
        androidx.compose.runtime.key(imageUrl) {
            var failed by remember { mutableStateOf(false) }
            if (imageUrl.isNullOrBlank() || failed) {
                Text(text = monogram, style = monogramStyle, color = NuxColors.OnSurfaceDim)
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(imageUrl)
                        .crossfade(NuxMotion.ImageCrossfadeMs)
                        .build(),
                    contentDescription = title,
                    contentScale = contentScale,
                    // A stale logo is worse than no logo: it mislabels what the
                    // viewer is watching. Fall back to the monogram instead.
                    onError = { failed = true },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(if (contentScale == ContentScale.Fit) 6.dp else 0.dp),
                )
            }
        }
    }
}

/** Poster card for movie and series rows. */
@Composable
fun PosterCard(
    title: String,
    imageUrl: String?,
    subtitle: String? = null,
    /** Fixed width in a row; null fills the cell it is given, as in a grid. */
    width: Dp? = 150.dp,
    progress: Float? = null,
    onClick: () -> Unit,
    onFocus: () -> Unit = {},
) {
    Surface(
        onClick = onClick,
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
        Column(modifier = Modifier.padding(horizontal = 2.dp)) {
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

/** Timestamp marking when a list appeared; see [itemEntrance]. */
@Composable
fun rememberListEntrance(key: Any?): Long =
    remember(key) { android.os.SystemClock.uptimeMillis() }

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
    val progress = remember { androidx.compose.animation.core.Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!animate) return@LaunchedEffect
        kotlinx.coroutines.delay(
            (index.coerceAtMost(NuxMotion.StaggerCap) * NuxMotion.StaggerStepMs).toLong()
        )
        progress.animateTo(
            1f,
            androidx.compose.animation.core.tween(NuxMotion.StandardMs, easing = NuxMotion.StandardEasing),
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
                        .size(width = 64.dp, height = 38.dp)
                        .clip(ChipShape),
                    contentScale = ContentScale.Fit,
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
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }
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
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
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
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(confirmingRemove) {
        // The step swap removes the button that held focus, and Compose's
        // focus-loss cleanup runs AFTER this effect's immediate request —
        // a bare requestFocus was won and then stomped, leaving the D-pad
        // driving the settings page behind the dialog. Yield a beat so the
        // cleanup goes first, then take focus with the retrying form.
        kotlinx.coroutines.delay(50)
        (if (confirmingRemove) cancelFocus else editFocus).requestFocusRetrying()
    }
    DialogScaffold(
        onDismiss = onDismiss,
        width = 460.dp,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!confirmingRemove) {
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
        } else {
            Text(
                "Remove this playlist?",
                style = MaterialTheme.typography.titleLarge,
                color = NuxColors.OnSurface,
            )
            Spacer(Modifier.height(Space.s))
            Text(
                "Its cached channels are deleted. Recordings are kept.",
                style = MaterialTheme.typography.bodyMedium,
                color = NuxColors.OnSurfaceDim,
            )
            Spacer(Modifier.height(Space.l))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                androidx.tv.material3.OutlinedButton(
                    onClick = { confirmingRemove = false },
                    modifier = Modifier.focusRequester(cancelFocus),
                ) { Text("Cancel") }
                androidx.tv.material3.Button(onClick = { onRemove() }) { Text("Remove") }
            }
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
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
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
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }
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
