@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.agoro.tv.ui.screens

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
import com.agoro.tv.MainViewModel
import com.agoro.tv.data.ContentBundle
import com.agoro.tv.data.EpgProgram
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.data.Movie
import com.agoro.tv.data.Series
import com.agoro.tv.ui.components.ChannelShelfCard
import com.agoro.tv.ui.components.ContextMenu
import com.agoro.tv.ui.components.MenuAction
import com.agoro.tv.ui.components.PosterCard
import com.agoro.tv.ui.components.SectionTitle
import com.agoro.tv.ui.components.StatusAction
import com.agoro.tv.ui.components.StatusPane
import com.agoro.tv.ui.components.itemEntrance
import com.agoro.tv.ui.components.rememberListEntrance
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxFocus
import com.agoro.tv.ui.theme.NuxMotion
import com.agoro.tv.ui.theme.NuxShape
import kotlinx.coroutines.launch

/**
 * The landing screen: what you were watching, what you starred, where you
 * just were — under a hero describing whichever card is focused, with Search
 * pinned to the top-right the way every TV launcher offers it. Pure assembly;
 * the joins live in [buildContinueWatching] and [channelsInCategory].
 */
/**
 * How many items a day-one catalogue row carries. Long enough to browse, short
 * enough that Home never becomes a second, worse Movies tab.
 */
private const val STARTER_ROW_LENGTH = 20

/**
 * What long-pressing a Home card opens. Only the cards with actions OK cannot
 * reach get a menu — a channel (favorite, hide) and a Continue watching card
 * (start over, forget). A catalogue poster's every action is one OK press away
 * on its detail screen, and a menu offering only what OK already does is a
 * second way to do nothing new.
 */
private sealed interface HomeMenu {
    data class Channel(
        val channel: LiveChannel,
        val row: List<LiveChannel>,
        val index: Int,
    ) : HomeMenu

    data class ResumedMovie(val movie: Movie, val progress: Float?) : HomeMenu
    data class ResumedSeries(val series: Series) : HomeMenu
}

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

    // The catalogue rows below draw straight from the bundle, which the browse
    // tabs never do — they filter the parental lock first. Home is the screen
    // that greets whoever switches the TV on, so it is the last place that may
    // put a locked category's artwork on screen.
    val pin by vm.parentalPin.collectAsState()
    val unlocked by vm.parentalUnlocked.collectAsState()
    val openCatalog = remember(bundle, pin, unlocked) {
        val lockedMovieIds = bundle.movieCategories
            .filter { vm.isLockedCategory(it.name) }.mapTo(HashSet()) { it.id }
        val lockedSeriesIds = bundle.seriesCategories
            .filter { vm.isLockedCategory(it.name) }.mapTo(HashSet()) { it.id }
        bundle.movies.filterNot { it.categoryId in lockedMovieIds } to
            bundle.series.filterNot { it.categoryId in lockedSeriesIds }
    }
    val (openMovies, openSeries) = openCatalog

    // Not a day-one row: what a provider just added is the reason to open the
    // app on any day, so this one stays whatever else Home is showing.
    val recentlyAdded = remember(openCatalog) { buildRecentlyAdded(openMovies, openSeries) }

    // Day one has no history, and a launcher that greets a 20,000-item
    // playlist with an empty screen is the app's worst first impression. When
    // nothing personal exists yet, Home opens on the catalogue instead — the
    // rows retire the moment the viewer has watched or starred anything.
    val personal = continueRow.isNotEmpty() || favoritesRow.isNotEmpty() ||
        recentsRow.isNotEmpty()
    val starterMovies = remember(openCatalog, personal) {
        if (personal) emptyList() else openMovies.take(STARTER_ROW_LENGTH)
    }
    val starterSeries = remember(openCatalog, personal) {
        if (personal) emptyList() else openSeries.take(STARTER_ROW_LENGTH)
    }
    val starterChannels = remember(displayChannels, personal) {
        if (personal) emptyList() else displayChannels.take(STARTER_ROW_LENGTH)
    }

    // Only rows with something in them compose — an empty shelf is a dead
    // D-pad press (same rule as the browse tabs' Continue watching shortcut).
    val rowKeys = remember(
        continueRow, favoritesRow, recentsRow, recentlyAdded,
        starterChannels, starterMovies, starterSeries,
    ) {
        buildList {
            if (continueRow.isNotEmpty()) add("continue")
            if (favoritesRow.isNotEmpty()) add("favorites")
            if (recentlyAdded.isNotEmpty()) add("new")
            if (recentsRow.isNotEmpty()) add("recents")
            if (starterChannels.isNotEmpty()) add("starterChannels")
            if (starterMovies.isNotEmpty()) add("starterMovies")
            if (starterSeries.isNotEmpty()) add("starterSeries")
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
        //
        // Search is one of the two offers here because the pill that normally
        // carries it lives in the hero, and the hero is not composed on this
        // path — leaving the only way to search a playlist of thousands
        // unreachable exactly when the viewer has nothing else to go on.
        StatusPane(
            title = "Welcome to Agoro",
            message = "Things you watch and star will gather here.",
            icon = Icons.Default.Home,
            primaryAction = StatusAction("Browse Live TV") { onBrowse(HomeTab.Live) },
            secondaryAction = StatusAction("Search") { onBrowse(HomeTab.Search) },
        )
        return
    }

    // At rest (nothing focused yet) the hero describes the first card, so the
    // screen never opens on an empty header. Derived, not set-once: the first
    // card's programme line refreshes with the guide's minute tick.
    var hero by remember { mutableStateOf<HeroInfo?>(null) }
    val restingHero = remember(
        rowKeys, continueRow, favoritesRow, recentsRow, recentlyAdded,
        starterChannels, starterMovies, starterSeries, nowNext,
    ) {
        when (rowKeys.first()) {
            "continue" -> when (val first = continueRow.first()) {
                is ContinueCard.MovieCard -> first.movie.toHero()
                is ContinueCard.SeriesCard -> first.series.toHero()
            }
            "new" -> when (val first = recentlyAdded.first()) {
                is CatalogCard.MovieCard -> first.movie.toHero()
                is CatalogCard.SeriesCard -> first.series.toHero()
            }
            "favorites" -> channelHero(favoritesRow.first(), nowNext[favoritesRow.first().id])
            "recents" -> channelHero(recentsRow.first(), nowNext[recentsRow.first().id])
            "starterChannels" ->
                channelHero(starterChannels.first(), nowNext[starterChannels.first().id])
            "starterMovies" -> starterMovies.first().toHero()
            else -> starterSeries.first().toHero()
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

    var menu by remember { mutableStateOf<HomeMenu?>(null) }

    @Composable
    fun ChannelTile(row: List<LiveChannel>, rowIndex: Int, index: Int, channel: LiveChannel) {
        val nn = nowNext[channel.id]
        Box(modifier = Modifier.itemEntrance(index, entrance)) {
            ChannelShelfCard(
                channel = channel,
                now = nn?.now,
                onClick = {
                    vm.playChannels(row, index)
                    onPlay()
                },
                onLongClick = { menu = HomeMenu.Channel(channel, row, index) },
                onFocus = {
                    focusedRow = rowIndex
                    focusSignal++
                    hero = channelHero(channel, nn)
                },
            )
        }
    }

    /** A catalogue poster, with TMDB's art when the provider shipped none. */
    @Composable
    fun MoviePoster(
        movie: Movie,
        rowIndex: Int,
        index: Int,
        progress: Float? = null,
        onLongClick: (() -> Unit)? = null,
    ) {
        Box(modifier = Modifier.itemEntrance(index, entrance)) {
            PosterCard(
                title = movie.name,
                imageUrl = borrowedArt(vm, movie.artRef(), movie.poster),
                year = movie.year,
                progress = progress,
                onClick = { onOpenMovie(movie) },
                onLongClick = onLongClick,
                onFocus = {
                    focusedRow = rowIndex
                    focusSignal++
                    hero = movie.toHero()
                },
            )
        }
    }

    @Composable
    fun SeriesPoster(
        series: Series,
        rowIndex: Int,
        index: Int,
        progress: Float? = null,
        onLongClick: (() -> Unit)? = null,
    ) {
        Box(modifier = Modifier.itemEntrance(index, entrance)) {
            PosterCard(
                title = series.name,
                imageUrl = borrowedArt(vm, series.artRef(), series.poster),
                year = series.year,
                progress = progress,
                // The detail screen lands on the part-watched episode's
                // resume action.
                onClick = { onOpenSeries(series) },
                onLongClick = onLongClick,
                onFocus = {
                    focusedRow = rowIndex
                    focusSignal++
                    hero = series.toHero()
                    vm.prefetchEpisodes(series)
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
                            when (card) {
                                is ContinueCard.MovieCard -> MoviePoster(
                                    card.movie, rowIndex, index, card.progress,
                                    onLongClick = {
                                        menu = HomeMenu.ResumedMovie(card.movie, card.progress)
                                    },
                                )
                                is ContinueCard.SeriesCard -> SeriesPoster(
                                    card.series, rowIndex, index, card.progress,
                                    onLongClick = { menu = HomeMenu.ResumedSeries(card.series) },
                                )
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
        if (recentlyAdded.isNotEmpty()) {
            item(key = "new") {
                val rowIndex = rowKeys.indexOf("new")
                Column {
                    // What the provider added, not what the world released —
                    // `added` is an import date, and a shelf headed "New
                    // releases" over a 2011 film is the kind of small lie that
                    // costs an app its credibility.
                    SectionTitle("Recently added")
                    LazyRow(
                        modifier = Modifier.focusRestorer(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        itemsIndexed(
                            recentlyAdded,
                            key = { _, card ->
                                when (card) {
                                    is CatalogCard.MovieCard -> "m:${card.movie.id}"
                                    is CatalogCard.SeriesCard -> "s:${card.series.id}"
                                }
                            },
                        ) { index, card ->
                            when (card) {
                                is CatalogCard.MovieCard ->
                                    MoviePoster(card.movie, rowIndex, index)
                                is CatalogCard.SeriesCard ->
                                    SeriesPoster(card.series, rowIndex, index)
                            }
                        }
                    }
                }
            }
        }
        if (starterChannels.isNotEmpty()) {
            item(key = "starterChannels") {
                val rowIndex = rowKeys.indexOf("starterChannels")
                Column {
                    SectionTitle("Live channels")
                    LazyRow(
                        modifier = Modifier.focusRestorer(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        itemsIndexed(starterChannels, key = { _, c -> c.id }) { index, channel ->
                            ChannelTile(starterChannels, rowIndex, index, channel)
                        }
                    }
                }
            }
        }
        if (starterMovies.isNotEmpty()) {
            item(key = "starterMovies") {
                val rowIndex = rowKeys.indexOf("starterMovies")
                Column {
                    SectionTitle("Movies")
                    LazyRow(
                        modifier = Modifier.focusRestorer(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        itemsIndexed(starterMovies, key = { _, m -> m.id }) { index, movie ->
                            MoviePoster(movie, rowIndex, index)
                        }
                    }
                }
            }
        }
        if (starterSeries.isNotEmpty()) {
            item(key = "starterSeries") {
                val rowIndex = rowKeys.indexOf("starterSeries")
                Column {
                    SectionTitle("Shows")
                    LazyRow(
                        modifier = Modifier.focusRestorer(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        itemsIndexed(starterSeries, key = { _, x -> x.id }) { index, series ->
                            SeriesPoster(series, rowIndex, index)
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
    menu?.let { open ->
        HomeContextMenu(
            vm = vm,
            menu = open,
            favorites = favorites,
            onPlay = onPlay,
            onOpenSeries = onOpenSeries,
            onDismiss = { menu = null },
        )
    }
}

/**
 * The actions a Home card carries beyond OK. Channels borrow Live TV's
 * vocabulary word for word — the same channel must not offer "Add to
 * favorites" on one screen and "Star" on another.
 */
@Composable
private fun HomeContextMenu(
    vm: MainViewModel,
    menu: HomeMenu,
    favorites: Set<String>,
    onPlay: () -> Unit,
    onOpenSeries: (Series) -> Unit,
    onDismiss: () -> Unit,
) {
    when (menu) {
        is HomeMenu.Channel -> {
            val isFav = menu.channel.url in favorites
            ContextMenu(
                title = menu.channel.displayName,
                actions = listOf(
                    MenuAction("Play") {
                        vm.playChannels(menu.row, menu.index)
                        onPlay()
                    },
                    MenuAction(if (isFav) "Remove from favorites" else "Add to favorites") {
                        vm.toggleFavorite(menu.channel)
                    },
                    MenuAction("Hide this channel") { vm.toggleHidden(menu.channel) },
                ),
                onDismiss = onDismiss,
            )
        }

        is HomeMenu.ResumedMovie -> ContextMenu(
            title = menu.movie.name,
            actions = listOf(
                MenuAction("Resume") { vm.playMovie(menu.movie); onPlay() },
                MenuAction("Start over") { vm.playMovie(menu.movie, startOver = true); onPlay() },
                // Destructive only in the sense that it cannot be undone from
                // here; nothing is deleted but the bookmark.
                MenuAction("Remove from Continue watching", destructive = true) {
                    vm.forgetResume(menu.movie.url)
                },
            ),
            onDismiss = onDismiss,
        )

        is HomeMenu.ResumedSeries -> ContextMenu(
            title = menu.series.name,
            actions = listOf(
                // No "Resume" here: which episode that means lives on the
                // series screen, which is also where "Start over" would have
                // to ask. Sending the viewer there is the honest answer.
                MenuAction("Open series") { onOpenSeries(menu.series) },
                MenuAction("Remove from Continue watching", destructive = true) {
                    vm.forgetSeriesResume(menu.series)
                },
            ),
            onDismiss = onDismiss,
        )
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
