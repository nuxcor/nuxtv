@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuxcor.nuxtv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuxcor.nuxtv.MainViewModel
import com.nuxcor.nuxtv.data.Category
import com.nuxcor.nuxtv.data.ContentBundle
import com.nuxcor.nuxtv.data.ContentState
import com.nuxcor.nuxtv.data.EngineChoice
import com.nuxcor.nuxtv.data.Movie
import com.nuxcor.nuxtv.data.PlaylistSource
import com.nuxcor.nuxtv.data.Series
import com.nuxcor.nuxtv.ui.components.CenteredMessage
import com.nuxcor.nuxtv.ui.components.ConfirmDialog
import com.nuxcor.nuxtv.ui.components.ContextMenu
import com.nuxcor.nuxtv.ui.components.MenuAction
import com.nuxcor.nuxtv.ui.components.SegmentedControl
import com.nuxcor.nuxtv.ui.components.itemEntrance
import com.nuxcor.nuxtv.ui.components.rememberListEntrance
import com.nuxcor.nuxtv.ui.components.PinPrompt
import com.nuxcor.nuxtv.ui.components.WideItem
import com.nuxcor.nuxtv.ui.components.dpadFieldNavigation
import com.nuxcor.nuxtv.ui.components.focusBorder
import com.nuxcor.nuxtv.ui.theme.NuxColors
import com.nuxcor.nuxtv.ui.theme.Space
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class HomeTab(val label: String, val icon: ImageVector) {
    Search("Search", Icons.Default.Search),
    Live("Live TV", Icons.Default.LiveTv),
    Guide("Guide", Icons.Default.CalendarViewWeek),
    Movies("Movies", Icons.Default.Movie),
    Series("Series", Icons.Default.VideoLibrary),
    Recordings("Recordings", Icons.Default.Videocam),
    Settings("Settings", Icons.Default.Settings),
}

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onOpenMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onPlay: () -> Unit,
    onAddPlaylist: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.Live) }
    // Non-content tabs also work while the playlist is loading or failed.
    val contentState by vm.content.collectAsState()
    var railFocused by remember { mutableStateOf(false) }
    val railFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // Hoisted above the Ready branch so a refresh cycle doesn't wipe tab state.
    val tabStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()

    // BACK from inside the content pane jumps focus to the rail first; on the
    // rail, BACK asks for confirmation instead of instantly quitting the app.
    var exitArmed by remember { mutableStateOf(false) }
    LaunchedEffect(exitArmed) {
        if (exitArmed) {
            kotlinx.coroutines.delay(2_500)
            exitArmed = false
        }
    }
    // Without this the first D-pad press lands wherever Compose's focus search
    // happens to go. Park it on the rail so the app always starts somewhere
    // predictable — and retry, since the rail composes a frame later.
    LaunchedEffect(Unit) {
        repeat(5) {
            if (runCatching { railFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(60)
        }
    }

    BackHandler(enabled = !railFocused) {
        runCatching { railFocus.requestFocus() }
    }
    BackHandler(enabled = railFocused && !exitArmed) {
        exitArmed = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
    // Content keeps a fixed 64dp lane for the collapsed rail; the expanded
    // rail draws over it so nothing reflows when focus enters the rail.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 64.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = Space.gutter, end = Space.gutter, top = Space.gutterVertical, bottom = Space.gutterVertical)
        ) {
            when (val state = contentState) {
                is ContentState.Loading -> CenteredMessage(title = state.message, loading = true)
                is ContentState.Error -> ErrorPane(message = state.message, onRetry = { vm.refresh() })
                is ContentState.Empty -> CenteredMessage(
                    title = "No playlist loaded",
                    subtitle = "Add a playlist in Settings",
                )
                is ContentState.Ready -> {
                    // No Crossfade: it keeps both tab trees composed and drawn
                    // into offscreen layers — a visible hitch on TV hardware.
                    run {
                        val current = tab
                        tabStateHolder.SaveableStateProvider(current.name) {
                            when (current) {
                                HomeTab.Search -> SearchTab(vm, onOpenMovie, onOpenSeries, onPlay)
                                HomeTab.Live -> LiveTab(vm, state.bundle, onPlay)
                                HomeTab.Guide -> GuideTab(vm, state.bundle, onPlay)
                                HomeTab.Movies -> MoviesTab(vm, state.bundle, onOpenMovie)
                                HomeTab.Series -> SeriesTab(vm, state.bundle, onOpenSeries)
                                HomeTab.Recordings -> RecordingsTab(vm, onPlay)
                                HomeTab.Settings -> SettingsTab(vm, state.bundle, onAddPlaylist)
                            }
                        }
                    }
                }
            }
            // Settings must stay reachable even while loading or on error.
            if (contentState !is ContentState.Ready && tab == HomeTab.Settings) {
                Box(modifier = Modifier.fillMaxSize().background(NuxColors.Background)) {
                    SettingsTab(vm, null, onAddPlaylist)
                }
            }
        }
    }
    NavRail(
        selected = tab,
        onSelect = { tab = it },
        railFocus = railFocus,
        onRailFocusChanged = { railFocused = it },
    )
    if (exitArmed) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .background(NuxColors.Scrim, RoundedCornerShape(10.dp))
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Text(
                "Press BACK again to exit",
                style = MaterialTheme.typography.labelLarge,
                color = NuxColors.OnSurface,
            )
        }
    }
    }
}

@Composable
private fun ErrorPane(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Couldn't load your playlist",
                style = MaterialTheme.typography.titleMedium,
                color = NuxColors.OnSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = NuxColors.OnSurfaceDim)
            Spacer(Modifier.height(18.dp))
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

// --- navigation rail ---------------------------------------------------------

@Composable
private fun NavRail(
    selected: HomeTab,
    onSelect: (HomeTab) -> Unit,
    railFocus: androidx.compose.ui.focus.FocusRequester,
    onRailFocusChanged: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // Focus travel selects a tab only after the focus rests briefly, so
    // moving down the rail doesn't compose every tab it passes through.
    var focusedItem by remember { mutableStateOf<HomeTab?>(null) }
    androidx.compose.runtime.LaunchedEffect(focusedItem) {
        val item = focusedItem ?: return@LaunchedEffect
        kotlinx.coroutines.delay(250)
        onSelect(item)
    }
    val width by animateDpAsState(targetValue = if (expanded) 190.dp else 64.dp, label = "railWidth")

    if (expanded) {
        // Soft edge between the rail and the content it covers.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(start = width)
                .width(28.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(NuxColors.Background, androidx.compose.ui.graphics.Color.Transparent)
                    )
                )
        )
    }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(width)
            .focusRestorer()
            // Opaque so overlaid content never shows through the rail.
            .background(NuxColors.Background)
            .onFocusChanged {
                expanded = it.hasFocus
                onRailFocusChanged(it.hasFocus)
                if (!it.hasFocus) focusedItem = null // cancel pending select-on-focus
            }
            .padding(horizontal = Space.s, vertical = Space.gutterVertical),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(
            text = if (expanded) "DZIDZI" else "D",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
            color = NuxColors.Primary,
            maxLines = 1,
            modifier = Modifier
                .padding(start = 12.dp, bottom = 20.dp)
                .animateContentSize(),
        )
        HomeTab.entries.forEach { item ->
            RailItem(
                item = item,
                selected = item == selected,
                expanded = expanded,
                onClick = { onSelect(item) },
                onItemFocused = { focusedItem = item },
                modifier = if (item == selected) {
                    Modifier.fillMaxWidth().focusRequester(railFocus)
                } else {
                    Modifier.fillMaxWidth()
                },
            )
        }
    }
}

@Composable
private fun RailItem(
    item: HomeTab,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    onItemFocused: () -> Unit = onClick,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    Surface(
        onClick = onClick,
        // Tabs switch as focus travels the rail — no OK press needed.
        modifier = modifier.onFocusChanged { if (it.isFocused) onItemFocused() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) NuxColors.Primary.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent,
            focusedContainerColor = NuxColors.SurfaceRaised,
            contentColor = if (selected) NuxColors.Primary else NuxColors.OnSurfaceDim,
            focusedContentColor = NuxColors.OnSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        border = ClickableSurfaceDefaults.border(focusedBorder = com.nuxcor.nuxtv.ui.theme.NuxFocus.ring),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(item.icon, contentDescription = item.label, modifier = Modifier.size(22.dp))
            androidx.compose.animation.AnimatedVisibility(
                visible = expanded,
                enter = androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core.tween(160, delayMillis = 120)
                ),
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(80)),
            ) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

// --- Live TV -----------------------------------------------------------------

@Composable
private fun LiveTab(vm: MainViewModel, bundle: ContentBundle, onPlay: () -> Unit) {
    if (bundle.channels.isEmpty()) {
        CenteredMessage(title = "No live channels", subtitle = "This playlist has no live streams")
        return
    }
    val favorites by vm.favorites.collectAsState()
    var pinPromptOpen by remember { mutableStateOf(false) }
    var menuChannel by remember { mutableStateOf<com.nuxcor.nuxtv.data.LiveChannel?>(null) }
    val pin by vm.parentalPin.collectAsState()
    val lockedIds = remember(bundle, pin, vm.parentalUnlocked) {
        bundle.liveCategories.filter { vm.isLockedCategory(it.name) }.map { it.id }.toSet()
    }
    // Filtering/merging happens off the main thread in the ViewModel.
    val allVisible by vm.displayChannels.collectAsState()
    val nowNextMap by vm.nowNext.collectAsState()
    val categories = remember(bundle, favorites) {
        buildList {
            add(Category(id = "__all__", name = "All channels"))
            if (allVisible.any { it.url in favorites }) {
                add(Category(id = "__fav__", name = "★ Favorites"))
            }
            addAll(bundle.liveCategories)
        }
    }
    var selectedCategory by rememberSaveable(bundle.channels.size) { mutableStateOf("__all__") }
    // Same rest-before-select rule as the nav rail: travelling the category
    // list would otherwise re-filter the entire channel set on every step.
    var focusedCategory by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusedCategory) {
        val id = focusedCategory ?: return@LaunchedEffect
        kotlinx.coroutines.delay(250)
        selectedCategory = id
    }
    // Ordering is applied in the ViewModel from the Settings preference.
    val channels = remember(allVisible, selectedCategory, favorites) {
        when (selectedCategory) {
            "__all__" -> allVisible
            "__fav__" -> allVisible.filter { it.url in favorites }
            else -> allVisible.filter { it.categoryId == selectedCategory }
        }
    }

    // Restarts the stagger when the visible set changes (category switch), so
    // the new list animates in but scrolling within it never does.
    val listEntrance = rememberListEntrance(channels)

    // Number entry: the only practical way to cross a few thousand channels
    // with a remote. Mirrors the player's keypad jump, including the
    // number-first / position-fallback rule.
    val channelListState = androidx.compose.foundation.lazy.rememberLazyListState()
    var digitBuffer by remember { mutableStateOf("") }
    LaunchedEffect(digitBuffer) {
        if (digitBuffer.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(1_200)
        val typed = digitBuffer.toIntOrNull()
        digitBuffer = ""
        if (typed != null) {
            val target = channels.indexOfFirst { it.number == typed }
                .takeIf { it >= 0 } ?: (typed - 1)
            if (target in channels.indices) channelListState.scrollToItem(target)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        LazyColumn(
            modifier = Modifier
                .width(190.dp)
                .fillMaxHeight()
                .focusRestorer(),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
            contentPadding = PaddingValues(bottom = Space.l),
        ) {
            items(categories, key = { it.id }) { category ->
                val locked = category.id in lockedIds
                CategoryItem(
                    name = category.name,
                    locked = locked,
                    selected = category.id == selectedCategory,
                    onClick = {
                        if (locked) pinPromptOpen = true else selectedCategory = category.id
                    },
                    // Browsing the category list switches content once focus rests.
                    onFocus = { if (!locked) focusedCategory = category.id },
                )
            }
        }
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
        LazyColumn(
            state = channelListState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .focusRestorer()
                .onPreviewKeyEvent { event ->
                    if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                        return@onPreviewKeyEvent false
                    }
                    val code = event.key.nativeKeyCode
                    if (code in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9) {
                        digitBuffer += (code - android.view.KeyEvent.KEYCODE_0).toString()
                        true
                    } else false
                },
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = Space.l),
        ) {
            itemsIndexed(channels, key = { _, c -> c.id }) { index, channel ->
                val nowProgram = nowNextMap[channel.id]?.now
                val nowMs = System.currentTimeMillis()
                Box(modifier = Modifier.itemEntrance(index, listEntrance)) {
                WideItem(
                    title = channel.name,
                    subtitle = nowProgram?.let { "Now: ${it.title}" } ?: listOfNotNull(
                        // Falls back to position so the number shown here is
                        // always the one the remote's keypad will jump to.
                        "Channel ${channel.number ?: (index + 1)}",
                        channel.archiveDays.takeIf { it > 0 }?.let { "$it-day catch-up" },
                    ).joinToString("  •  ").ifBlank { null },
                    badge = channel.quality,
                    imageUrl = channel.logo,
                    progress = nowProgram?.let { p ->
                        ((nowMs - p.startMs).toFloat() / (p.endMs - p.startMs).coerceAtLeast(1))
                            .coerceIn(0f, 1f)
                    },
                    selected = channel.url in favorites,
                    onClick = {
                        vm.playChannels(channels, index)
                        onPlay()
                    },
                    onLongClick = { menuChannel = channel },
                )
                }
            }
        }
        }
    }
    if (digitBuffer.isNotEmpty()) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .background(NuxColors.Scrim, RoundedCornerShape(10.dp))
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Text(
                "Channel $digitBuffer",
                style = MaterialTheme.typography.titleMedium,
                color = NuxColors.Primary,
            )
        }
    }
    menuChannel?.let { channel ->
        val isFav = channel.url in favorites
        ContextMenu(
            title = channel.name,
            actions = listOf(
                MenuAction("Play") {
                    vm.playChannels(channels, channels.indexOf(channel).coerceAtLeast(0))
                    onPlay()
                },
                MenuAction(if (isFav) "Remove from favorites" else "Add to favorites") {
                    vm.toggleFavorite(channel)
                },
                MenuAction("Hide this channel") { vm.toggleHidden(channel) },
            ),
            onDismiss = { menuChannel = null },
        )
    }
    if (pinPromptOpen) {
        PinPrompt(
            onSubmit = { entered -> vm.tryUnlock(entered).also { ok -> if (ok) pinPromptOpen = false } },
            onDismiss = { pinPromptOpen = false },
        )
    }
    }
}

@Composable
fun CategoryItem(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    locked: Boolean = false,
    onFocus: () -> Unit = {},
) {
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) NuxColors.Primary.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent,
            focusedContainerColor = NuxColors.SurfaceRaised,
            // Selected stays gold even while focused.
            contentColor = if (selected) NuxColors.Primary else NuxColors.OnSurfaceDim,
            focusedContentColor = if (selected) NuxColors.Primary else NuxColors.OnSurface,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = focusBorder()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (locked) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = "Locked category",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

// --- Settings ----------------------------------------------------------------

private val EPGSHARE_PACKS = listOf("US", "UK", "CA", "DE", "FR", "IN", "ZA")

private fun epgshareUrl(cc: String) =
    "https://epgshare01.online/epgshare01/epg_ripper_${cc}1.xml.gz"

@Composable
private fun SettingsTab(vm: MainViewModel, bundle: ContentBundle?, onAddPlaylist: () -> Unit) {
    val sources by vm.sources.collectAsState()
    val active by vm.activeSource.collectAsState()
    val engine by vm.engine.collectAsState()
    val epgOverride by vm.epgOverrideUrl.collectAsState()
    val tmdbKey by vm.tmdbKey.collectAsState()

    val parentalPin by vm.parentalPin.collectAsState()
    var manageOpen by remember { mutableStateOf(false) }
    var epgField by remember(epgOverride) { mutableStateOf(epgOverride.orEmpty()) }
    var tmdbField by remember(tmdbKey) { mutableStateOf(tmdbKey.orEmpty()) }
    var pinField by remember(parentalPin) { mutableStateOf(parentalPin.orEmpty()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var confirmRemoveSource by remember { mutableStateOf<String?>(null) }
    var confirmImport by remember { mutableStateOf(false) }

    if (manageOpen && bundle != null) {
        ChannelManager(vm = vm, bundle = bundle, onClose = { manageOpen = false })
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Space.m),
        contentPadding = PaddingValues(bottom = Space.xxl),
    ) {
        item(key = "header") {
            Column {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = NuxColors.OnSurface,
                )
                if (bundle != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${bundle.channels.size} channels • ${bundle.movies.size} movies • ${bundle.series.size} series",
                        style = MaterialTheme.typography.bodySmall,
                        color = NuxColors.OnSurfaceDim,
                    )
                }
                statusMessage?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.labelMedium, color = NuxColors.Secondary)
                }
            }
        }

        items(sources.orEmpty(), key = { it.id }) { source ->
            val isActive = source.id == active?.id
            WideItem(
                title = source.name,
                selected = isActive,
                subtitle = when (source) {
                    is PlaylistSource.Xtream -> "Xtream • ${source.serverUrl}"
                    is PlaylistSource.M3u -> "M3U • ${source.url}"
                },
                leading = {
                    Icon(
                        if (source is PlaylistSource.Xtream) Icons.Default.LiveTv else Icons.Default.Movie,
                        contentDescription = null,
                        tint = if (isActive) NuxColors.Primary else NuxColors.OnSurfaceDim,
                    )
                },
                onClick = { if (!isActive) vm.selectSource(source.id) },
            )
        }

        item(key = "account") {
            val account by vm.accountInfo.collectAsState()
            account?.let { info ->
                val dayMs = 24 * 3600_000L
                val daysLeft = info.expiresAtMs?.let { (it - System.currentTimeMillis()) / dayMs }
                val expiringSoon = daysLeft != null && daysLeft in 0..7
                val inactive = info.status != null && !info.status.equals("Active", ignoreCase = true)
                val expired = daysLeft != null && daysLeft < 0
                val fmt = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }
                Column {
                    Text(
                        "Account",
                        style = MaterialTheme.typography.titleSmall,
                        color = NuxColors.OnSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = buildList {
                            info.status?.let { add(it) }
                            info.expiresAtMs?.let {
                                add(
                                    when {
                                        expired -> "Expired ${fmt.format(Date(it))}"
                                        else -> "Expires ${fmt.format(Date(it))}"
                                    }
                                )
                            }
                            if (info.maxConnections != null) {
                                add("${info.activeConnections ?: 0} of ${info.maxConnections} connections in use")
                            }
                        }.joinToString("   •   "),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (expired || inactive || expiringSoon) NuxColors.Error
                        else NuxColors.OnSurfaceDim,
                    )
                    if (expiringSoon || expired || inactive) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = when {
                                expired -> "Your subscription has ended — streams will fail until it is renewed."
                                inactive -> "Your provider reports this account as inactive."
                                else -> "Your subscription renews soon."
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = NuxColors.OnSurfaceDim,
                        )
                    }
                }
            }
        }

        item(key = "playlist-buttons") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onAddPlaylist) { Text("Add playlist") }
                OutlinedButton(onClick = { vm.refresh() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh playlist", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Refresh")
                }
                val activeId = active?.id
                if (activeId != null && (sources?.size ?: 0) > 0) {
                    OutlinedButton(onClick = { confirmRemoveSource = activeId }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove playlist", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Remove current")
                    }
                }
                if (bundle != null) {
                    OutlinedButton(onClick = { manageOpen = true }) { Text("Manage channels") }
                }
            }
        }

        item(key = "duplicates") {
            Column {
                Text(
                    "Duplicate channels",
                    style = MaterialTheme.typography.titleSmall,
                    color = NuxColors.OnSurface,
                )
                Text(
                    "Merge SD/HD/FHD variants of the same channel and keep the best quality.",
                    style = MaterialTheme.typography.labelSmall,
                    color = NuxColors.OnSurfaceDim,
                )
                Spacer(Modifier.height(8.dp))
                val mergeDupes by vm.mergeDuplicates.collectAsState()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CategoryItem(
                        name = "Show all",
                        selected = !mergeDupes,
                        onClick = { vm.setMergeDuplicates(false) },
                        modifier = Modifier,
                    )
                    CategoryItem(
                        name = "Best quality only",
                        selected = mergeDupes,
                        onClick = { vm.setMergeDuplicates(true) },
                        modifier = Modifier,
                    )
                }
            }
        }

        item(key = "order") {
            Column {
                Text(
                    "Channel order",
                    style = MaterialTheme.typography.titleSmall,
                    color = NuxColors.OnSurface,
                )
                Text(
                    "How Live TV lists channels within a category.",
                    style = MaterialTheme.typography.labelMedium,
                    color = NuxColors.OnSurfaceDim,
                )
                Spacer(Modifier.height(8.dp))
                val order by vm.channelOrder.collectAsState()
                SegmentedControl(
                    options = listOf("Provider order", "A–Z", "Best quality first"),
                    selectedIndex = order,
                    onSelect = { vm.setChannelOrder(it) },
                )
            }
        }

        item(key = "engine") {
            Column {
                Text(
                    "Default player engine",
                    style = MaterialTheme.typography.titleSmall,
                    color = NuxColors.OnSurface,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EngineChoice.entries.forEach { choice ->
                        CategoryItem(
                            name = if (choice == EngineChoice.EXO) "ExoPlayer" else "VLC",
                            selected = engine == choice,
                            onClick = { vm.setEngine(choice) },
                            modifier = Modifier,
                        )
                    }
                }
            }
        }

        item(key = "epg") {
            Column {
                Spacer(Modifier.height(6.dp))
                Text(
                    "EPG source",
                    style = MaterialTheme.typography.titleSmall,
                    color = NuxColors.OnSurface,
                )
                Text(
                    "Auto uses your playlist's guide; pick an epgshare01 pack or paste any XMLTV URL. Guides refresh every 6 hours.",
                    style = MaterialTheme.typography.labelSmall,
                    color = NuxColors.OnSurfaceDim,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CategoryItem(
                        name = "Auto",
                        selected = epgOverride.isNullOrBlank(),
                        onClick = { vm.setEpgOverrideUrl(null); statusMessage = "EPG source: playlist default" },
                        modifier = Modifier,
                    )
                    EPGSHARE_PACKS.forEach { cc ->
                        CategoryItem(
                            name = cc,
                            selected = epgOverride == epgshareUrl(cc),
                            onClick = {
                                vm.setEpgOverrideUrl(epgshareUrl(cc))
                                statusMessage = "EPG source: epgshare01 $cc pack"
                            },
                            modifier = Modifier,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.OutlinedTextField(
                        value = epgField,
                        onValueChange = { epgField = it },
                        label = { androidx.compose.material3.Text("Custom XMLTV URL") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).dpadFieldNavigation(),
                        colors = settingsFieldColors(),
                    )
                    OutlinedButton(onClick = {
                        vm.setEpgOverrideUrl(epgField)
                        statusMessage = if (epgField.isBlank()) "EPG source: playlist default"
                        else "EPG source updated"
                    }) { Text("Apply") }
                }
            }
        }

        item(key = "tmdb") {
            Column {
                Spacer(Modifier.height(6.dp))
                Text(
                    "TMDB ratings & reviews",
                    style = MaterialTheme.typography.titleSmall,
                    color = NuxColors.OnSurface,
                )
                Text(
                    "Add a free themoviedb.org API key to enrich movies and series with ratings, posters and review excerpts.",
                    style = MaterialTheme.typography.labelSmall,
                    color = NuxColors.OnSurfaceDim,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.OutlinedTextField(
                        value = tmdbField,
                        onValueChange = { tmdbField = it },
                        label = { androidx.compose.material3.Text("TMDB API key") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).dpadFieldNavigation(),
                        colors = settingsFieldColors(),
                    )
                    OutlinedButton(onClick = {
                        vm.setTmdbKey(tmdbField)
                        statusMessage = if (tmdbField.isBlank()) "TMDB disabled" else "TMDB key saved"
                    }) { Text("Save") }
                }
            }
        }

        item(key = "parental") {
            Column {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Parental control",
                    style = MaterialTheme.typography.titleSmall,
                    color = NuxColors.OnSurface,
                )
                Text(
                    "With a PIN set, restricted categories are hidden everywhere until you unlock them.",
                    style = MaterialTheme.typography.labelSmall,
                    color = NuxColors.OnSurfaceDim,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.OutlinedTextField(
                        value = pinField,
                        onValueChange = { value -> pinField = value.filter { ch -> ch.isDigit() }.take(8) },
                        label = { androidx.compose.material3.Text("PIN (blank to disable)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).dpadFieldNavigation(),
                        colors = settingsFieldColors(),
                    )
                    OutlinedButton(onClick = {
                        vm.setParentalPin(pinField)
                        statusMessage = if (pinField.isBlank()) "Parental lock disabled" else "Parental PIN saved"
                    }) { Text("Save") }
                }
            }
        }

        item(key = "updates") {
            Column {
                Spacer(Modifier.height(6.dp))
                Text(
                    "App updates",
                    style = MaterialTheme.typography.titleSmall,
                    color = NuxColors.OnSurface,
                )
                val update by vm.updateState.collectAsState()
                Text(
                    text = when (val u = update) {
                        is com.nuxcor.nuxtv.data.UpdateManager.State.Available ->
                            "Version ${com.nuxcor.nuxtv.BuildConfig.VERSION_NAME} — ${u.version} is available" +
                                (u.sizeBytes.takeIf { it > 0 }?.let { " (${it / 1048576} MB)" } ?: "")
                        is com.nuxcor.nuxtv.data.UpdateManager.State.Downloading ->
                            "Downloading update… ${u.progressPercent}%"
                        is com.nuxcor.nuxtv.data.UpdateManager.State.Ready ->
                            "Update downloaded — install when prompted"
                        is com.nuxcor.nuxtv.data.UpdateManager.State.UpToDate ->
                            "Version ${com.nuxcor.nuxtv.BuildConfig.VERSION_NAME} — up to date"
                        is com.nuxcor.nuxtv.data.UpdateManager.State.Error ->
                            "Update check failed: ${u.message}"
                        is com.nuxcor.nuxtv.data.UpdateManager.State.Checking -> "Checking…"
                        else -> "Version ${com.nuxcor.nuxtv.BuildConfig.VERSION_NAME}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when (update) {
                        is com.nuxcor.nuxtv.data.UpdateManager.State.Available -> NuxColors.Secondary
                        is com.nuxcor.nuxtv.data.UpdateManager.State.Error -> NuxColors.Error
                        else -> NuxColors.OnSurfaceDim
                    },
                )
                Spacer(Modifier.height(8.dp))
                // One stable button — swapping composables per state would drop
                // D-pad focus mid-download.
                Button(onClick = {
                    when (update) {
                        is com.nuxcor.nuxtv.data.UpdateManager.State.Available,
                        is com.nuxcor.nuxtv.data.UpdateManager.State.Ready ->
                            vm.downloadAndInstallUpdate()
                        is com.nuxcor.nuxtv.data.UpdateManager.State.Downloading,
                        is com.nuxcor.nuxtv.data.UpdateManager.State.Checking -> Unit
                        else -> vm.checkForUpdates()
                    }
                }) {
                    Text(
                        when (val u = update) {
                            is com.nuxcor.nuxtv.data.UpdateManager.State.Available -> "Update now"
                            is com.nuxcor.nuxtv.data.UpdateManager.State.Ready -> "Install"
                            is com.nuxcor.nuxtv.data.UpdateManager.State.Downloading ->
                                "Downloading… ${u.progressPercent}%"
                            is com.nuxcor.nuxtv.data.UpdateManager.State.Checking -> "Checking…"
                            else -> "Check for updates"
                        }
                    )
                }
            }
        }

        item(key = "confirmations") {
            SettingsConfirmations(
                vm = vm,
                removeSourceId = confirmRemoveSource,
                onRemoveHandled = { confirmRemoveSource = null },
                importPending = confirmImport,
                onImportHandled = { confirmImport = false },
                onStatus = { statusMessage = it },
            )
        }

        item(key = "backup") {
            Column {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Backup & restore",
                    style = MaterialTheme.typography.titleSmall,
                    color = NuxColors.OnSurface,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = {
                        vm.exportBackup { path ->
                            statusMessage = path?.let { "Backup saved to $it" } ?: "Backup failed"
                        }
                    }) { Text("Export backup") }
                    OutlinedButton(onClick = { confirmImport = true }) { Text("Import backup") }
                }
            }
        }
    }
}

@Composable
private fun SettingsConfirmations(
    vm: MainViewModel,
    removeSourceId: String?,
    onRemoveHandled: () -> Unit,
    importPending: Boolean,
    onImportHandled: () -> Unit,
    onStatus: (String) -> Unit,
) {
    if (removeSourceId != null) {
        ConfirmDialog(
            title = "Remove this playlist?",
            message = "Its cached channels are deleted. Recordings are kept.",
            confirmLabel = "Remove",
            onConfirm = { vm.removeSource(removeSourceId) },
            onDismiss = onRemoveHandled,
        )
    }
    if (importPending) {
        ConfirmDialog(
            title = "Restore from backup?",
            message = "This replaces your current playlists and settings.",
            confirmLabel = "Restore",
            onConfirm = {
                vm.importBackup { ok -> onStatus(if (ok) "Backup restored" else "No backup found") }
            },
            onDismiss = onImportHandled,
        )
    }
}

@Composable
private fun settingsFieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedTextColor = NuxColors.OnSurface,
    unfocusedTextColor = NuxColors.OnSurface,
    focusedContainerColor = NuxColors.Surface,
    unfocusedContainerColor = NuxColors.Surface.copy(alpha = 0.6f),
    focusedBorderColor = NuxColors.Primary,
    unfocusedBorderColor = NuxColors.SurfaceVariant,
    focusedLabelColor = NuxColors.Primary,
    unfocusedLabelColor = NuxColors.OnSurfaceDim,
    cursorColor = NuxColors.Primary,
)

@Composable
private fun ChannelManager(vm: MainViewModel, bundle: ContentBundle, onClose: () -> Unit) {
    val hidden by vm.hidden.collectAsState()
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Manage channels",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = NuxColors.OnSurface,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onClose) { Text("Done") }
        }
        Text(
            "Select a channel to hide or unhide it everywhere.",
            style = MaterialTheme.typography.labelSmall,
            color = NuxColors.OnSurfaceDim,
        )
        Spacer(Modifier.height(14.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            items(bundle.channels, key = { it.id }) { channel ->
                val isHidden = channel.url in hidden
                WideItem(
                    title = channel.name,
                    subtitle = if (isHidden) "Hidden — select to unhide" else "Visible",
                    badge = channel.quality,
                    imageUrl = channel.logo,
                    onClick = { vm.toggleHidden(channel) },
                )
            }
        }
    }
}
