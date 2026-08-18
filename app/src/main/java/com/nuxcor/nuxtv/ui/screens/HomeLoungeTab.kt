@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuxcor.nuxtv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuxcor.nuxtv.MainViewModel
import com.nuxcor.nuxtv.data.ContentBundle
import com.nuxcor.nuxtv.data.EpgProgram
import com.nuxcor.nuxtv.data.LiveChannel
import com.nuxcor.nuxtv.data.Movie
import com.nuxcor.nuxtv.data.Series
import com.nuxcor.nuxtv.ui.components.Artwork
import com.nuxcor.nuxtv.ui.components.PosterCard
import com.nuxcor.nuxtv.ui.components.SectionTitle
import com.nuxcor.nuxtv.ui.components.StatusAction
import com.nuxcor.nuxtv.ui.components.StatusPane
import com.nuxcor.nuxtv.ui.components.itemEntrance
import com.nuxcor.nuxtv.ui.components.rememberListEntrance
import com.nuxcor.nuxtv.ui.theme.NuxColors
import com.nuxcor.nuxtv.ui.theme.NuxFocus
import com.nuxcor.nuxtv.ui.theme.NuxMotion
import com.nuxcor.nuxtv.ui.theme.NuxShape
import kotlinx.coroutines.launch

/**
 * The landing screen: what you were watching, what you starred, where you
 * just were — under a hero describing whichever card is focused, with Search
 * pinned to the top-right the way every TV launcher offers it. Pure assembly;
 * the joins live in [buildContinueWatching] and [channelsInCategory].
 */
@Composable
fun HomeLoungeTab(
    vm: MainViewModel,
    bundle: ContentBundle,
    onOpenMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onPlay: () -> Unit,
    /** The debounced hero, hoisted so the shell can draw its backdrop full-bleed. */
    onHeroChange: (HeroInfo?) -> Unit,
    /** Tab switch for Search and the empty-state escape hatches. */
    onBrowse: (HomeTab) -> Unit,
) {
    val resumePositions by vm.resumePositions.collectAsState()
    val resumeProgress by vm.resumeProgress.collectAsState()
    val episodeOrigins by vm.episodeOrigins.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val recents by vm.recentChannels.collectAsState()
    val displayChannels by vm.displayChannels.collectAsState()
    val nowNext by vm.nowNext.collectAsState()

    val continueRow = remember(bundle, resumePositions, resumeProgress, episodeOrigins) {
        buildContinueWatching(
            bundle.movies, bundle.series, episodeOrigins, resumePositions, resumeProgress,
        )
    }
    val favoritesRow = remember(displayChannels, favorites) {
        channelsInCategory(CATEGORY_FAVORITES, displayChannels, favorites, recents)
    }
    val recentsRow = remember(displayChannels, recents) {
        channelsInCategory(CATEGORY_RECENT, displayChannels, favorites, recents)
    }

    // Only rows with something in them compose — an empty shelf is a dead
    // D-pad press (same rule as the browse tabs' Continue watching shortcut).
    val rowKeys = remember(continueRow, favoritesRow, recentsRow) {
        buildList {
            if (continueRow.isNotEmpty()) add("continue")
            if (favoritesRow.isNotEmpty()) add("favorites")
            if (recentsRow.isNotEmpty()) add("recents")
        }
    }

    if (rowKeys.isEmpty()) {
        LaunchedEffect(Unit) { onHeroChange(null) }
        // displayChannels folds off the main thread and lands a beat after the
        // bundle — flashing the welcome pane in that beat would tell a viewer
        // with favorites that they have none.
        if (displayChannels.isEmpty() && bundle.channels.isNotEmpty()) {
            Box(Modifier.fillMaxSize())
            return
        }
        // StatusPane focuses its primary action on arrival, which is what the
        // shell's boot-focus retry lands on.
        StatusPane(
            title = "Welcome to Agoro",
            message = "Things you watch and star will gather here.",
            icon = Icons.Default.Home,
            primaryAction = StatusAction("Browse Live TV") { onBrowse(HomeTab.Live) },
            secondaryAction = StatusAction("Browse Movies") { onBrowse(HomeTab.Movies) },
        )
        return
    }

    // At rest (nothing focused yet) the hero describes the first card, so the
    // screen never opens on an empty header. Derived, not set-once: the first
    // card's programme line refreshes with the guide's minute tick.
    var hero by remember { mutableStateOf<HeroInfo?>(null) }
    val restingHero = remember(rowKeys, continueRow, favoritesRow, recentsRow, nowNext) {
        when (rowKeys.first()) {
            "continue" -> when (val first = continueRow.first()) {
                is ContinueCard.MovieCard -> first.movie.toHero()
                is ContinueCard.SeriesCard -> first.series.toHero()
            }
            "favorites" -> channelHero(favoritesRow.first(), nowNext[favoritesRow.first().id])
            else -> channelHero(recentsRow.first(), nowNext[recentsRow.first().id])
        }
    }
    val activeHero = hero ?: restingHero
    // Debounced so travelling a row doesn't hard-cut the hero (and the
    // shell's backdrop with it) 5x/second.
    var shownHero by remember { mutableStateOf<HeroInfo?>(null) }
    LaunchedEffect(activeHero) {
        if (shownHero != null) kotlinx.coroutines.delay(NuxMotion.HeroDebounceMs.toLong())
        shownHero = activeHero
        onHeroChange(activeHero)
    }

    val columnState = rememberLazyListState()
    var focusedRow by remember { mutableStateOf(0) }
    // Bumped on every card focus, not just row changes: the LazyColumn's own
    // bring-into-view nudges the list on focus, and without a counter-snap
    // per focus event the correction only fired when the row index changed.
    var focusSignal by remember { mutableStateOf(0) }
    // Row snapping (the browse grid's rule): the focused row aligns to the
    // top of the scrolling lane so the rows above scroll fully away instead
    // of leaving clipped caption slivers. The hero is pinned above the lane,
    // so row 0 is simply the top.
    LaunchedEffect(focusSignal) {
        columnState.animateScrollToItem(focusedRow.coerceAtLeast(0))
    }
    val entrance = rememberListEntrance(Unit)
    val searchFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    @Composable
    fun ChannelTile(row: List<LiveChannel>, rowIndex: Int, index: Int, channel: LiveChannel) {
        val nn = nowNext[channel.id]
        Box(modifier = Modifier.itemEntrance(index, entrance)) {
            ChannelCard(
                channel = channel,
                now = nn?.now,
                onClick = {
                    vm.playChannels(row, index)
                    onPlay()
                },
                onFocus = {
                    focusedRow = rowIndex
                    focusSignal++
                    hero = channelHero(channel, nn)
                },
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Pinned, fixed-height hero: rows scroll beneath it, so the focused
        // card's identity never scrolls itself off the screen — captionless
        // posters lean on this title. Fixed height so a plotless hero doesn't
        // bounce the shelf positions.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp),
        ) {
            HeroHeader(shownHero)
            SearchPill(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .focusRequester(searchFocus)
                    .onPreviewKeyEvent { event ->
                        // Nothing lives above the pill; UP must not fall
                        // through to the rail.
                        event.type == KeyEventType.KeyDown &&
                            event.key == Key.DirectionUp
                    },
                onClick = { onBrowse(HomeTab.Search) },
            )
        }
        LazyColumn(
        state = columnState,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                // UP from the first row goes to the Search pill — left to the
                // geometric search it escapes to the nav rail, where the dwell
                // then switches the whole screen.
                if (event.type == KeyEventType.KeyDown &&
                    event.key == Key.DirectionUp && focusedRow == 0
                ) {
                    scope.launch {
                        runCatching { searchFocus.requestFocus() }
                    }
                    true
                } else false
            },
    ) {
        if (continueRow.isNotEmpty()) {
            item(key = "continue") {
                val rowIndex = rowKeys.indexOf("continue")
                Column {
                    SectionTitle("Continue watching")
                    LazyRow(
                        modifier = Modifier.focusRestorer(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        itemsIndexed(
                            continueRow,
                            key = { _, card ->
                                when (card) {
                                    is ContinueCard.MovieCard -> "m:${card.movie.id}"
                                    is ContinueCard.SeriesCard -> "s:${card.series.id}"
                                }
                            },
                        ) { index, card ->
                            Box(modifier = Modifier.itemEntrance(index, entrance)) {
                                when (card) {
                                    is ContinueCard.MovieCard -> PosterCard(
                                        title = card.movie.name,
                                        imageUrl = card.movie.poster,
                                        progress = card.progress,
                                        onClick = { onOpenMovie(card.movie) },
                                        onFocus = {
                                            focusedRow = rowIndex
                                            focusSignal++
                                            hero = card.movie.toHero()
                                        },
                                    )
                                    is ContinueCard.SeriesCard -> PosterCard(
                                        title = card.series.name,
                                        imageUrl = card.series.poster,
                                        progress = card.progress,
                                        // The detail screen lands on the
                                        // part-watched episode's resume action.
                                        onClick = { onOpenSeries(card.series) },
                                        onFocus = {
                                            focusedRow = rowIndex
                                            focusSignal++
                                            hero = card.series.toHero()
                                            vm.prefetchEpisodes(card.series)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (favoritesRow.isNotEmpty()) {
            item(key = "favorites") {
                val rowIndex = rowKeys.indexOf("favorites")
                Column {
                    SectionTitle("Favorites · on now")
                    LazyRow(
                        modifier = Modifier.focusRestorer(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        itemsIndexed(favoritesRow, key = { _, c -> c.id }) { index, channel ->
                            ChannelTile(favoritesRow, rowIndex, index, channel)
                        }
                    }
                }
            }
        }
        if (recentsRow.isNotEmpty()) {
            item(key = "recents") {
                val rowIndex = rowKeys.indexOf("recents")
                Column {
                    SectionTitle("Recent channels")
                    LazyRow(
                        modifier = Modifier.focusRestorer(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        itemsIndexed(recentsRow, key = { _, c -> c.id }) { index, channel ->
                            ChannelTile(recentsRow, rowIndex, index, channel)
                        }
                    }
                }
            }
        }
        }
    }
}

/** The launcher-style search entry: an outlined pill, top-right of the hero. */
@Composable
private fun SearchPill(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(NuxShape.Chip),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuxColors.Surface,
            focusedContainerColor = NuxFocus.container,
            contentColor = NuxColors.OnSurfaceDim,
            focusedContentColor = NuxColors.OnSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = NuxFocus.ButtonScale),
        border = ClickableSurfaceDefaults.border(focusedBorder = NuxFocus.ring8),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(text = "Search", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * A live channel as a 16:9 shelf card: logo on its neutral chip, the current
 * programme underneath with how far through it is. Degrades to logo + name
 * when no guide covers the channel.
 */
@Composable
private fun ChannelCard(
    channel: LiveChannel,
    now: EpgProgram?,
    onClick: () -> Unit,
    onFocus: () -> Unit,
) {
    val progress = now?.let {
        val span = it.endMs - it.startMs
        if (span <= 0) null
        else ((System.currentTimeMillis() - it.startMs).toFloat() / span).coerceIn(0f, 1f)
    }
    // Caption lives OUTSIDE the clickable surface: inside it, the focus glow
    // pools behind the text rows and reads as a stain instead of a halo.
    Column(modifier = Modifier.width(240.dp)) {
        Surface(
            onClick = onClick,
            modifier = Modifier.onFocusChanged { if (it.isFocused) onFocus() },
            shape = ClickableSurfaceDefaults.shape(NuxShape.Card),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                contentColor = NuxColors.OnSurface,
                focusedContentColor = NuxColors.OnSurface,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = NuxFocus.CardScale),
            border = ClickableSurfaceDefaults.border(focusedBorder = NuxFocus.ring16),
            glow = ClickableSurfaceDefaults.glow(focusedGlow = NuxFocus.cardGlow),
        ) {
            Box {
                Artwork(
                    imageUrl = channel.logo,
                    title = channel.displayName,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(NuxShape.Card),
                    monogramStyle = MaterialTheme.typography.headlineSmall,
                )
                channel.quality?.let { tier ->
                    Text(
                        text = tier,
                        style = MaterialTheme.typography.labelMedium,
                        color = NuxColors.OnSurfaceDim,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(NuxShape.Chip)
                            .background(NuxColors.Scrim)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                if (progress != null && progress > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.White.copy(alpha = 0.25f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress)
                                .background(NuxColors.Primary),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = channel.displayName,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Text(
            text = now?.title ?: "Live",
            style = MaterialTheme.typography.labelMedium,
            color = NuxColors.OnSurfaceDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}
