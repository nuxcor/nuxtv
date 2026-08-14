@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuxcor.nuxtv.ui.screens

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
import com.nuxcor.nuxtv.ui.components.WideItem
import com.nuxcor.nuxtv.ui.components.focusBorder
import com.nuxcor.nuxtv.ui.theme.NuxColors

enum class HomeTab(val label: String, val icon: ImageVector) {
    Search("Search", Icons.Default.Search),
    Live("Live TV", Icons.Default.LiveTv),
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

    Row(modifier = Modifier.fillMaxSize()) {
        NavRail(selected = tab, onSelect = { tab = it })
        Box(modifier = Modifier.fillMaxSize()) {
            when (val state = contentState) {
                is ContentState.Loading -> CenteredMessage(title = state.message, loading = true)
                is ContentState.Error -> ErrorPane(message = state.message, onRetry = { vm.refresh() })
                is ContentState.Empty -> CenteredMessage(
                    title = "No playlist loaded",
                    subtitle = "Add a playlist in Settings",
                )
                is ContentState.Ready -> when (tab) {
                    HomeTab.Search -> SearchTab(vm, onOpenMovie, onOpenSeries, onPlay)
                    HomeTab.Live -> LiveTab(vm, state.bundle, onPlay)
                    HomeTab.Movies -> MoviesTab(state.bundle, onOpenMovie)
                    HomeTab.Series -> SeriesTab(state.bundle, onOpenSeries)
                    HomeTab.Recordings -> RecordingsTab(vm, onPlay)
                    HomeTab.Settings -> SettingsTab(vm, state.bundle, onAddPlaylist)
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
private fun NavRail(selected: HomeTab, onSelect: (HomeTab) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(targetValue = if (expanded) 190.dp else 64.dp, label = "railWidth")

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(width)
            .background(NuxColors.Surface.copy(alpha = 0.4f))
            .onFocusChanged { expanded = it.hasFocus }
            .padding(horizontal = 8.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = if (expanded) "NUXTV" else "N",
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
            )
        }
    }
}

@Composable
private fun RailItem(item: HomeTab, selected: Boolean, expanded: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) NuxColors.Primary.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent,
            focusedContainerColor = NuxColors.Primary,
            contentColor = if (selected) NuxColors.FocusBorder else NuxColors.OnSurfaceDim,
            focusedContentColor = androidx.compose.ui.graphics.Color(0xFF14102E),
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
    val categories = remember(bundle) {
        listOf(Category(id = "__all__", name = "All channels")) + bundle.liveCategories
    }
    var selectedCategory by rememberSaveable(bundle.channels.size) { mutableStateOf("__all__") }
    val channels = remember(bundle, selectedCategory) {
        if (selectedCategory == "__all__") bundle.channels
        else bundle.channels.filter { it.categoryId == selectedCategory }
    }

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
            items(categories, key = { it.id }) { category ->
                CategoryItem(
                    name = category.name,
                    selected = category.id == selectedCategory,
                    onClick = { selectedCategory = category.id },
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
                WideItem(
                    title = channel.name,
                    subtitle = listOfNotNull(
                        channel.number?.let { "Channel $it" },
                        channel.archiveDays.takeIf { it > 0 }?.let { "$it-day catch-up" },
                    ).joinToString("  •  ").ifBlank { null },
                    imageUrl = channel.logo,
                    onClick = {
                        vm.playChannels(channels, index)
                        onPlay()
                    },
                )
            }
        }
    }
}

@Composable
fun CategoryItem(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
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

@Composable
private fun SettingsTab(vm: MainViewModel, bundle: ContentBundle?, onAddPlaylist: () -> Unit) {
    val sources by vm.sources.collectAsState()
    val active by vm.activeSource.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        Text(
            "Playlists",
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
        Spacer(Modifier.height(20.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f, fill = false),
        ) {
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
        }

        Spacer(Modifier.height(16.dp))
        val engine by vm.engine.collectAsState()
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

        Spacer(Modifier.height(20.dp))
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
        }
    }
}
