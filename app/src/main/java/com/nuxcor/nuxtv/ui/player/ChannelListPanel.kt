@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuxcor.nuxtv.ui.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuxcor.nuxtv.MainViewModel
import com.nuxcor.nuxtv.data.LiveChannel
import com.nuxcor.nuxtv.ui.components.requestFocusRetrying
import com.nuxcor.nuxtv.ui.screens.channelsInCategory
import com.nuxcor.nuxtv.ui.screens.liveCategoryList
import com.nuxcor.nuxtv.ui.theme.NuxColors
import com.nuxcor.nuxtv.ui.theme.NuxFocus
import com.nuxcor.nuxtv.ui.theme.NuxShape
import kotlinx.coroutines.delay

/** TiviMate-style channel list overlay inside the player, with now/next. */
@Composable
internal fun ChannelListPanel(
    vm: MainViewModel,
    items: List<com.nuxcor.nuxtv.data.PlayableItem>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onSelectChannels: (List<LiveChannel>, Int) -> Unit,
    onExitToHome: () -> Unit,
    onDismiss: () -> Unit,
) {
    val nowNextMap by vm.nowNext.collectAsState()
    val contentState by vm.content.collectAsState()
    val allChannels by vm.displayChannels.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val listState = rememberLazyListState()
    val firstFocus = remember { FocusRequester() }
    val categoryFocus = remember { FocusRequester() }
    // Drives the "Nm left" countdown and the progress fill.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMs = System.currentTimeMillis()
        }
    }

    // LEFT walks outward: video → channels → categories. RIGHT walks back in.
    var categoriesOpen by remember { mutableStateOf(false) }
    // null means "whatever playlist is already playing", so opening the guide
    // never silently reshuffles what CH+/- cycles through.
    var categoryId by remember { mutableStateOf<String?>(null) }

    val bundle = (contentState as? com.nuxcor.nuxtv.data.ContentState.Ready)?.bundle
    val recents by vm.recentChannels.collectAsState()
    // Third view of the same channels, and it built its own copy of this too.
    // Shared with Live TV and the guide — see LiveCategories.kt — so Recent
    // shows up here as well without being added a third time.
    val categories = remember(bundle, allChannels, favorites, recents) {
        liveCategoryList(
            bundle ?: com.nuxcor.nuxtv.data.ContentBundle(),
            allChannels,
            favorites,
            recents,
        )
    }
    val categoryChannels = remember(categoryId, allChannels, favorites, recents) {
        if (categoryId == null) emptyList()
        else channelsInCategory(categoryId!!, allChannels, favorites, recents)
    }
    val browsingCategory = categoryId != null

    LaunchedEffect(categoryId) {
        listState.scrollToItem(if (browsingCategory) 0 else currentIndex.coerceAtLeast(0))
        // The target row composes a frame after the scroll; retry briefly.
        firstFocus.requestFocusRetrying()
    }
    LaunchedEffect(categoriesOpen) {
        if (!categoriesOpen) return@LaunchedEffect
        categoryFocus.requestFocusRetrying()
    }

    // BACK walks back in one level, the same way RIGHT does, instead of
    // collapsing the whole guide from the outermost panel. Composed deeper than
    // the player's handler, so it wins while the category column is open.
    BackHandler(enabled = categoriesOpen) {
        categoriesOpen = false
        runCatching { firstFocus.requestFocus() }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // --- category column (second level, revealed by LEFT) ---------------
        AnimatedVisibility(
            visible = categoriesOpen,
            enter = PlayerMotion.enterFromLeft(),
            exit = PlayerMotion.exitToLeft(),
        ) {
            Box(
                modifier = Modifier
                    .width(PlayerTheme.CategoryWidth)
                    .fillMaxHeight()
                    .background(PlayerTheme.ScrimStrong)
                    .focusGroup()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key.nativeKeyCode) {
                            // RIGHT walks back in towards the video.
                            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                                categoriesOpen = false
                                runCatching { firstFocus.requestFocus() }
                                true
                            }
                            // Categories is the last panel, so LEFT completes the
                            // walk outward and leaves the player for Home, where
                            // Live/Movies/Series/Settings live.
                            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { onExitToHome(); true }
                            else -> false
                        }
                    }
                    .padding(start = 22.dp, top = 22.dp, end = 14.dp)
            ) {
                Column {
                    Text(
                        text = "Categories",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = NuxColors.OnSurface,
                    )
                    Spacer(Modifier.height(10.dp))
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxHeight(),
                    ) {
                        items(categories.size, key = { categories[it].id }) { index ->
                            val category = categories[index]
                            val selected = category.id == categoryId
                            Surface(
                                onClick = {
                                    categoryId = category.id
                                    categoriesOpen = false
                                },
                                modifier = if (index == 0) {
                                    Modifier.fillMaxWidth().focusRequester(categoryFocus)
                                } else {
                                    Modifier.fillMaxWidth()
                                },
                                shape = ClickableSurfaceDefaults.shape(PlayerTheme.ChipShape),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = if (selected) {
                                        PlayerTheme.SelectionTint
                                    } else Color.Transparent,
                                    focusedContainerColor = NuxFocus.container,
                                    contentColor = if (selected) NuxColors.Primary else NuxColors.OnSurface,
                                    focusedContentColor = NuxColors.OnSurface,
                                ),
                                scale = ClickableSurfaceDefaults.scale(
                                    focusedScale = NuxFocus.RowScale,
                                ),
                                border = ClickableSurfaceDefaults.border(
                                    focusedBorder = NuxFocus.ring8,
                                ),
                            ) {
                                Text(
                                    text = category.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // --- channel column (first level) -----------------------------------
        Box(
            modifier = Modifier
                .width(PlayerTheme.ChannelListWidth)
                .fillMaxHeight()
                .background(PlayerTheme.PanelGradient)
                .focusGroup()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key.nativeKeyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { onDismiss(); true }
                        // LEFT keeps walking outward instead of dead-ending.
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { categoriesOpen = true; true }
                        else -> false
                    }
                }
                .padding(start = 22.dp, top = 22.dp, end = 14.dp)
        ) {
            Column {
                Text(
                    text = if (browsingCategory) {
                        categories.firstOrNull { it.id == categoryId }?.name ?: "Channels"
                    } else "Channels",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = NuxColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "‹ Categories",
                    style = MaterialTheme.typography.labelMedium,
                    color = NuxColors.OnSurfaceDim,
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxHeight(),
                ) {
                    val rowCount = if (browsingCategory) categoryChannels.size else items.size
                    // A category can legitimately be empty — every channel in it
                    // hidden, or Favorites before anything is starred. Without a
                    // focusable row here focus has nowhere to land, and since
                    // the guide suppresses the root's focus parking, the remote
                    // would go dead until BACK. Never leave the panel focusless.
                    if (rowCount == 0) {
                        item(key = "empty") {
                            Surface(
                                onClick = { categoriesOpen = true },
                                modifier = Modifier.fillMaxWidth().focusRequester(firstFocus),
                                shape = ClickableSurfaceDefaults.shape(PlayerTheme.ChipShape),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color.Transparent,
                                    focusedContainerColor = NuxFocus.container,
                                    contentColor = NuxColors.OnSurfaceDim,
                                    focusedContentColor = NuxColors.OnSurface,
                                ),
                                scale = ClickableSurfaceDefaults.scale(
                                    focusedScale = NuxFocus.RowScale,
                                ),
                                border = ClickableSurfaceDefaults.border(
                                    focusedBorder = NuxFocus.ring8,
                                ),
                            ) {
                                Text(
                                    text = "No channels here — press OK for categories",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                )
                            }
                        }
                    }
                    // Keys carry the channel's identity, not its position — on
                    // a playlist swap or category change a positional key made
                    // Compose reuse row N's state for a different channel.
                    items(
                        count = rowCount,
                        // The index suffix guards against a playlist (or a
                        // category — providers duplicate URLs there too) that
                        // repeats a URL; a duplicate LazyColumn key throws.
                        key = { idx ->
                            if (browsingCategory) "cat:${categoryChannels[idx].url}:$idx"
                            else "pl:${items[idx].url}:$idx"
                        },
                    ) { index ->
                        // Browsing a category shows that category's channels and
                        // selecting one makes it the new zap playlist; otherwise
                        // the rows are the playlist already playing.
                        val channel: LiveChannel? = if (browsingCategory) {
                            categoryChannels[index]
                        } else {
                            items[index].channelId?.let { vm.channelById(it) }
                        }
                        val title = if (browsingCategory) {
                            channel?.name.orEmpty()
                        } else {
                            items[index].title
                        }
                        val isCurrent = !browsingCategory && index == currentIndex
                        val nowNext = channel?.id?.let { nowNextMap[it] }
                        Surface(
                            onClick = {
                                if (browsingCategory) onSelectChannels(categoryChannels, index)
                                else onSelect(index)
                            },
                            modifier = if (index == if (browsingCategory) 0 else currentIndex) {
                                Modifier.fillMaxWidth().focusRequester(firstFocus)
                            } else {
                                Modifier.fillMaxWidth()
                            },
                            shape = ClickableSurfaceDefaults.shape(PlayerTheme.ChipShape),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (isCurrent) {
                                    PlayerTheme.SelectionTint
                                } else Color.Transparent,
                                focusedContainerColor = NuxFocus.container,
                                contentColor = NuxColors.OnSurface,
                                focusedContentColor = NuxColors.OnSurface,
                            ),
                            // Explicit: tv-material3's 1.1 default grew a
                            // full-width row about 20dp past each edge, and the
                            // panel only has 14dp of trailing padding — so the
                            // focused row spilled onto the video.
                            scale = ClickableSurfaceDefaults.scale(
                                focusedScale = NuxFocus.RowScale,
                            ),
                            border = ClickableSurfaceDefaults.border(
                                focusedBorder = NuxFocus.ring8,
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                com.nuxcor.nuxtv.ui.components.Artwork(
                                    imageUrl = channel?.logo,
                                    title = title,
                                    modifier = Modifier
                                        .size(width = 52.dp, height = 32.dp)
                                        .clip(PlayerTheme.ChipShape),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                    monogramStyle = MaterialTheme.typography.labelSmall,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            // Same number the keypad matches on,
                                            // so typing what you see lands here.
                                            text = "${channel?.number ?: (index + 1)}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = NuxColors.Primary,
                                        )
                                        Text(
                                            text = title,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    // What's on, how far through it is, and how
                                    // long is left — the three things you need to
                                    // decide whether to stop here. "Next" belongs
                                    // in the full guide, not in a zapping list.
                                    nowNext?.now?.let { now ->
                                        Text(
                                            text = now.title,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = NuxColors.OnSurfaceDim,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        val span = (now.endMs - now.startMs).coerceAtLeast(1)
                                        val progress =
                                            ((nowMs - now.startMs).toFloat() / span).coerceIn(0f, 1f)
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(3.dp)
                                                    .clip(NuxShape.Track)
                                                    .background(PlayerTheme.TrackBackground)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxHeight()
                                                        .fillMaxWidth(progress)
                                                        .background(NuxColors.Primary)
                                                )
                                            }
                                            // Rounded up: integer division said
                                            // "0m left" for the final minute.
                                            val minutesLeft =
                                                ((now.endMs - nowMs + 59_999) / 60_000L)
                                                    .coerceAtLeast(0)
                                            Text(
                                                text = "${minutesLeft}m left",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = NuxColors.OnSurfaceDim,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // Clicking the exposed video area closes the guide.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        event.key.nativeKeyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT
                    ) {
                        onDismiss()
                        true
                    } else false
                }
        )
    }
}
