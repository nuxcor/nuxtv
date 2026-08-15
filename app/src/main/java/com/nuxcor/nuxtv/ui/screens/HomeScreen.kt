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
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.nuxcor.nuxtv.ui.components.PinPrompt
import com.nuxcor.nuxtv.ui.components.WideItem
import com.nuxcor.nuxtv.ui.components.focusBorder
import com.nuxcor.nuxtv.ui.theme.NuxColors

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

    // BACK from inside the content pane jumps focus to the rail first;
    // a second BACK (rail focused) exits as usual.
    BackHandler(enabled = !railFocused) {
        runCatching { railFocus.requestFocus() }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        NavRail(
            selected = tab,
            onSelect = { tab = it },
            railFocus = railFocus,
            onRailFocusChanged = { railFocused = it },
        )
        Box(modifier = Modifier.fillMaxSize()) {
            when (val state = contentState) {
                is ContentState.Loading -> CenteredMessage(title = state.message, loading = true)
                is ContentState.Error -> ErrorPane(message = state.message, onRetry = { vm.refresh() })
                is ContentState.Empty -> CenteredMessage(
                    title = "No playlist loaded",
                    subtitle = "Add a playlist in Settings",
                )
                is ContentState.Ready -> {
                    // SaveableStateHolder keeps each tab's scroll/selection/search
                    // state alive across tab switches.
                    val stateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                    androidx.compose.animation.Crossfade(
                        targetState = tab,
                        animationSpec = androidx.compose.animation.core.tween(220),
                        label = "tab",
                    ) { current ->
                        stateHolder.SaveableStateProvider(current.name) {
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

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(width)
            .background(NuxColors.Surface.copy(alpha = 0.4f))
            .onFocusChanged {
                expanded = it.hasFocus
                onRailFocusChanged(it.hasFocus)
            }
            .padding(horizontal = 8.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
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
            focusedContainerColor = NuxColors.Primary,
            contentColor = if (selected) NuxColors.FocusBorder else NuxColors.OnSurfaceDim,
            focusedContentColor = NuxColors.OnAccent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(item.icon, contentDescription = item.label, modifier = Modifier.size(22.dp))
            if (expanded) {
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
    val hidden by vm.hidden.collectAsState()
    var pinPromptOpen by remember { mutableStateOf(false) }
    val pin by vm.parentalPin.collectAsState()
    val lockedIds = remember(bundle, pin, vm.parentalUnlocked) {
        bundle.liveCategories.filter { vm.isLockedCategory(it.name) }.map { it.id }.toSet()
    }
    val allVisible = remember(bundle, hidden, lockedIds) {
        vm.visibleChannels(bundle.channels).filterNot { it.categoryId in lockedIds }
    }
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
    var sortAz by rememberSaveable { mutableStateOf(false) }
    val epgState by vm.epgState.collectAsState()
    val sortedAll = remember(allVisible, sortAz) {
        if (sortAz) allVisible.sortedBy { it.name.lowercase() } else allVisible
    }
    val channels = remember(sortedAll, selectedCategory, favorites) {
        when (selectedCategory) {
            "__all__" -> sortedAll
            "__fav__" -> sortedAll.filter { it.url in favorites }
            else -> sortedAll.filter { it.categoryId == selectedCategory }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 28.dp, end = 48.dp, top = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        LazyColumn(
            modifier = Modifier
                .width(230.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            item(key = "__sort__") {
                CategoryItem(
                    name = if (sortAz) "Sort: A–Z" else "Sort: Default",
                    selected = sortAz,
                    onClick = { sortAz = !sortAz },
                )
            }
            items(categories, key = { it.id }) { category ->
                val locked = category.id in lockedIds
                CategoryItem(
                    name = if (locked) "${category.name}  🔒" else category.name,
                    selected = category.id == selectedCategory,
                    onClick = {
                        if (locked) pinPromptOpen = true else selectedCategory = category.id
                    },
                    // Browsing the category list switches content on focus.
                    onFocus = { if (!locked) selectedCategory = category.id },
                )
            }
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            itemsIndexed(channels, key = { _, c -> c.id }) { index, channel ->
                val nowProgram = remember(channel.id, epgState) {
                    val now = System.currentTimeMillis()
                    vm.programsFor(channel).firstOrNull { now in it.startMs until it.endMs }
                }
                WideItem(
                    title = channel.name,
                    subtitle = nowProgram?.let { "Now: ${it.title}" } ?: listOfNotNull(
                        channel.number?.let { "Channel $it" },
                        channel.archiveDays.takeIf { it > 0 }?.let { "$it-day catch-up" },
                    ).joinToString("  •  ").ifBlank { null },
                    badge = channel.quality,
                    imageUrl = channel.logo,
                    onClick = {
                        vm.playChannels(channels, index)
                        onPlay()
                    },
                )
            }
        }
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
    onFocus: () -> Unit = {},
) {
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) NuxColors.Primary.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent,
            focusedContainerColor = NuxColors.SurfaceVariant,
            contentColor = if (selected) NuxColors.FocusBorder else NuxColors.OnSurfaceDim,
            focusedContentColor = NuxColors.OnSurface,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = focusBorder()),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
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

    if (manageOpen && bundle != null) {
        ChannelManager(vm = vm, bundle = bundle, onClose = { manageOpen = false })
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 40.dp),
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
                title = source.name + if (isActive) "   ●" else "",
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

        item(key = "playlist-buttons") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onAddPlaylist) { Text("Add playlist") }
                OutlinedButton(onClick = { vm.refresh() }) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Refresh")
                }
                val activeId = active?.id
                if (activeId != null && (sources?.size ?: 0) > 0) {
                    OutlinedButton(onClick = { vm.removeSource(activeId) }) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Remove current")
                    }
                }
                if (bundle != null) {
                    OutlinedButton(onClick = { manageOpen = true }) { Text("Manage channels") }
                }
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
                        modifier = Modifier.weight(1f),
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
                        modifier = Modifier.weight(1f),
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
                    "With a PIN set, adult-looking categories (XXX/Adult/18+) lock everywhere until unlocked.",
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
                        modifier = Modifier.weight(1f),
                        colors = settingsFieldColors(),
                    )
                    OutlinedButton(onClick = {
                        vm.setParentalPin(pinField)
                        statusMessage = if (pinField.isBlank()) "Parental lock disabled" else "Parental PIN saved"
                    }) { Text("Save") }
                }
            }
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
                    OutlinedButton(onClick = {
                        vm.importBackup { ok ->
                            statusMessage = if (ok) "Backup restored" else "No backup found"
                        }
                    }) { Text("Import backup") }
                }
            }
        }
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
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
