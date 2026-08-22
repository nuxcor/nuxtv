@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.agoro.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import com.agoro.tv.ui.components.ShelfRingRoom
import com.agoro.tv.ui.components.shelfRingRoom
import com.agoro.tv.ui.components.StatusAction
import com.agoro.tv.ui.components.StatusPane
import com.agoro.tv.ui.components.itemEntrance
import com.agoro.tv.ui.components.LocalArrivalFocusAllowed
import com.agoro.tv.ui.components.requestFocusRetrying
import androidx.compose.runtime.saveable.rememberSaveable
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

    /** A catalogue poster on Home — Movies, Shows, Recently added. */
    data class CatalogMovie(val movie: Movie) : HomeMenu
    data class CatalogSeries(val series: Series) : HomeMenu
}

/**
 * Home's shelves, in the order they appear.
 *
 * An enum rather than string keys so both `when`s over it are exhaustive: a
 * shelf added here without a branch is a compile error instead of a blank row
 * that still occupies a scroll position.
 */
/**
 * How a title is named in the hidden-from-Home set.
 *
 * The catalogue id, not the stream url: a url carries the provider's stream id
 * and those get re-issued, so a title that came back under a new id would
 * quietly reappear on Home after being dismissed.
 */
private fun movieHomeKey(movie: Movie) = "m:${movie.id}"
private fun seriesHomeKey(series: Series) = "s:${series.id}"

private enum class HomeRow {
    Continue, Favorites, Recents, StarterChannels, StarterMovies, StarterSeries, New
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

    // Titles pushed off Home with "Not interested". Applied here, to Home's
    // catalogue rows only — the film is still in Movies, in Shows and in
    // search, because "not on my home screen" is not "delete this".
    val hiddenTitles by vm.hiddenTitles.collectAsState()

    // Not a day-one row: what a provider just added is the reason to open the
    // app on any day, so this one stays whatever else Home is showing.
    val recentlyAdded = remember(openCatalog, hiddenTitles) {
        buildRecentlyAdded(openMovies, openSeries)
            .filterNot { card ->
                when (card) {
                    is CatalogCard.MovieCard -> movieHomeKey(card.movie) in hiddenTitles
                    is CatalogCard.SeriesCard -> seriesHomeKey(card.series) in hiddenTitles
                }
            }
    }

    // Day one has no history, and a launcher that greets a 20,000-item
    // playlist with an empty screen is the app's worst first impression. When
    // nothing personal exists yet, Home opens on the catalogue instead — and
    // each starter row retires only when the viewer has history OF ITS OWN
    // KIND.
    //
    // One flag for all three was wrong in a way that hid the app's main
    // event: resuming a single film filled continueRow, which retired the
    // starter CHANNELS row as well, so a viewer with no channel history at
    // all lost Live TV from Home entirely and was left looking at Recently
    // added — mostly series. Live earned its place back by not being governed
    // by what someone watched on demand.
    val watchedCatalogue = continueRow.isNotEmpty()
    val watchedChannels = favoritesRow.isNotEmpty() || recentsRow.isNotEmpty()
    // Filtered before take(), or hiding a title would leave a gap in the row
    // rather than pulling the next one up into it.
    val starterMovies = remember(openCatalog, watchedCatalogue, hiddenTitles) {
        if (watchedCatalogue) emptyList()
        else openMovies.filterNot { movieHomeKey(it) in hiddenTitles }.take(STARTER_ROW_LENGTH)
    }
    val starterSeries = remember(openCatalog, watchedCatalogue, hiddenTitles) {
        if (watchedCatalogue) emptyList()
        else openSeries.filterNot { seriesHomeKey(it) in hiddenTitles }.take(STARTER_ROW_LENGTH)
    }
    val starterChannels = remember(displayChannels, watchedChannels) {
        if (watchedChannels) emptyList() else displayChannels.take(STARTER_ROW_LENGTH)
    }

    // Only rows with something in them compose — an empty shelf is a dead
    // D-pad press (same rule as the browse tabs' Continue watching shortcut).
    val rowKeys = remember(
        continueRow, favoritesRow, recentsRow, recentlyAdded,
        starterChannels, starterMovies, starterSeries,
    ) {
        // Live first, then films, then shows.
        //
        // Continue watching keeps the top: it is the one row that answers
        // "what was I doing", and it is empty for anyone who has not started
        // something. Everything after it runs live -> movies -> shows, so the
        // shelf order matches the rail order and the thing this app is
        // primarily for is the thing on screen when Home opens.
        buildList {
            if (continueRow.isNotEmpty()) add(HomeRow.Continue)
            if (favoritesRow.isNotEmpty()) add(HomeRow.Favorites)
            if (recentsRow.isNotEmpty()) add(HomeRow.Recents)
            if (starterChannels.isNotEmpty()) add(HomeRow.StarterChannels)
            if (starterMovies.isNotEmpty()) add(HomeRow.StarterMovies)
            if (starterSeries.isNotEmpty()) add(HomeRow.StarterSeries)
            // Recently added is a mixed catalogue row, so it trails the
            // typed ones rather than splitting them.
            if (recentlyAdded.isNotEmpty()) add(HomeRow.New)
        }
    }

    // displayChannels folds off the main thread and lands a beat after the
    // bundle. Nothing is drawn in that beat: the welcome pane would tell a
    // viewer with favorites that they have none, and the catalogue shelves
    // would take focus a frame before the live shelf was prepended above
    // them — so Home opened anchored on Movies with its first row scrolled
    // off the top, and focus on a film instead of on television.
    if (displayChannels.isEmpty() && bundle.channels.isNotEmpty()) {
        Box(Modifier.fillMaxSize())
        return
    }

    if (rowKeys.isEmpty()) {
        LaunchedEffect(Unit) { onHeroChange(null) }
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

    // Saveable, all three: Home leaves composition for every detail page and
    // every channel, and coming back is a return, not a launch. The row and
    // the card within it are what "where I was" means here; the signal is
    // saved too so the first-arrival scroll-to-top below stays spent.
    var focusedRow by rememberSaveable { mutableStateOf(0) }
    var focusedIndex by rememberSaveable { mutableStateOf(0) }
    // The focused row by NAME as well as by index: a shelf appearing above
    // it (the first favorite starred, a first resume) shifts every index
    // below, and the viewer's row must follow itself rather than hand its
    // slot to the newcomer.
    var focusedRowKey by rememberSaveable { mutableStateOf(HomeRow.Continue.name) }
    // Bumped on every card focus, not just row changes: the LazyColumn's own
    // bring-into-view nudges the list on focus, and without a counter-snap
    // per focus event the correction only fired when the row index changed.
    var focusSignal by rememberSaveable { mutableStateOf(0) }

    // The hero describes the focused card — the remembered one on a return,
    // the first one on a fresh visit — so the screen never opens on an empty
    // header. DERIVED from the focus position, never snapshotted by the focus
    // callback: a snapshot taken before the guide had loaded described the
    // channel as "Live" with no programme and stayed that way until focus
    // moved, while the card under it had long since filled in.
    val activeHero = remember(
        rowKeys, continueRow, favoritesRow, recentsRow, recentlyAdded,
        starterChannels, starterMovies, starterSeries, nowNext, focusedRow, focusedIndex,
    ) {
        fun <T> List<T>.at() = getOrElse(focusedIndex) { first() }
        when (rowKeys.getOrElse(focusedRow) { rowKeys.first() }) {
            HomeRow.Continue -> when (val card = continueRow.at()) {
                is ContinueCard.MovieCard -> card.movie.toHero()
                is ContinueCard.SeriesCard -> card.series.toHero()
            }
            HomeRow.New -> when (val card = recentlyAdded.at()) {
                is CatalogCard.MovieCard -> card.movie.toHero()
                is CatalogCard.SeriesCard -> card.series.toHero()
            }
            HomeRow.Favorites -> favoritesRow.at().let { channelHero(it, nowNext[it.id]) }
            HomeRow.Recents -> recentsRow.at().let { channelHero(it, nowNext[it.id]) }
            HomeRow.StarterChannels -> starterChannels.at().let { channelHero(it, nowNext[it.id]) }
            HomeRow.StarterMovies -> starterMovies.at().toHero()
            HomeRow.StarterSeries -> starterSeries.at().toHero()
        }
    }
    // Debounced so travelling a row doesn't hard-cut the hero (and the
    // shell's backdrop with it) 5x/second.
    var shownHero by remember { mutableStateOf<HeroInfo?>(null) }
    LaunchedEffect(activeHero) {
        if (shownHero != null) kotlinx.coroutines.delay(NuxMotion.HeroDebounceMs.toLong())
        shownHero = activeHero
        onHeroChange(activeHero)
        // One hero is on screen at a time, so this is the one guide synopsis
        // worth reading — after the debounce, so travelling a row of channels
        // costs no queries at all.
        val key = activeHero?.plotKey ?: return@LaunchedEffect
        if (!activeHero.plot.isNullOrBlank()) return@LaunchedEffect
        val plot = vm.descriptionFor(key)?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val filled = activeHero.copy(plot = plot)
        shownHero = filled
        onHeroChange(filled)
    }

    val columnState = rememberLazyListState()
    // Row snapping (the browse grid's rule): the focused row aligns to the
    // top of the scrolling lane so the rows above scroll fully away instead
    // of leaving clipped caption slivers. The hero is pinned above the lane,
    // so row 0 is simply the top.
    LaunchedEffect(focusSignal) {
        columnState.animateScrollToItem(focusedRow.coerceAtLeast(0))
    }
    // Shelves arrive in waves: the catalogue lands first, and displayChannels
    // folds a beat later to prepend "Live channels". The lane keeps its offset
    // when a row is inserted above, so Home opened anchored on Movies with the
    // live row hidden off the top — while the hero, which reads rowKeys.first(),
    // was already describing a channel the viewer could not see.
    //
    // Only before the first focus. After that the position is the viewer's, and
    // a late-arriving shelf must not move it under them.
    LaunchedEffect(rowKeys) {
        if (focusSignal == 0) {
            columnState.scrollToItem(0)
            return@LaunchedEffect
        }
        val index = rowKeys.indexOfFirst { it.name == focusedRowKey }
        if (index >= 0 && index != focusedRow) {
            focusedRow = index
            columnState.scrollToItem(index)
        }
    }
    // Focus has to come back after the menu closes. A destructive action —
    // "Not interested", "Remove from Continue watching" — deletes the very card
    // that opened the menu, so the focused node is gone by the time the menu
    // dismisses and focus falls to nothing: the d-pad goes dead until the
    // viewer leaves the screen. This follows the focused row so the menu can
    // hand focus back to it, and focusRestorer picks the card within.
    val rowFocus = remember { FocusRequester() }
    val entrance = rememberListEntrance(Unit)
    val searchFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    // Where focus lands on arrival: the card the viewer left, or the first
    // card of the first shelf on a fresh visit. Left to the shell's geometric
    // parking, focus went to whichever node existed first — the Search pill,
    // because the hero composes a frame before the lazy rows do — so the app
    // opened (and every return from a detail page landed) on Search rather
    // than on anything to watch. Rides on the card itself; the shelf's
    // restorer is the fallback when that card has scrolled out of composition.
    val cardFocus = remember { FocusRequester() }
    // Once per visit (the rememberInitialFocus rule): the allowed flag flips
    // on every drawer round trip, and firing on that edge would drag focus
    // off wherever the viewer had since moved to.
    val arrivalAllowed = LocalArrivalFocusAllowed.current
    val arrivalPending = remember { booleanArrayOf(true) }
    LaunchedEffect(arrivalAllowed) {
        if (!arrivalAllowed || !arrivalPending[0]) return@LaunchedEffect
        arrivalPending[0] = false
        // The rows compose a frame after the column does; give the card a
        // moment before settling for its shelf.
        if (!cardFocus.requestFocusRetrying(retries = 6, intervalMs = 50)) {
            rowFocus.requestFocusRetrying()
        }
    }

    var menu by remember { mutableStateOf<HomeMenu?>(null) }

    /** The arrival requester rides on exactly one card: the remembered one. */
    fun cardFocusModifier(rowIndex: Int, index: Int): Modifier =
        if (rowIndex == focusedRow && index == focusedIndex) {
            Modifier.focusRequester(cardFocus)
        } else Modifier

    @Composable
    fun ChannelTile(row: List<LiveChannel>, rowIndex: Int, index: Int, channel: LiveChannel) {
        val nn = nowNext[channel.id]
        Box(modifier = Modifier.itemEntrance(index, entrance)) {
            ChannelShelfCard(
                channel = channel,
                now = nn?.now,
                modifier = cardFocusModifier(rowIndex, index),
                onClick = {
                    vm.playChannels(row, index)
                    onPlay()
                },
                onLongClick = { menu = HomeMenu.Channel(channel, row, index) },
                onFocus = {
                    focusedRow = rowIndex
                    focusedIndex = index
                    focusedRowKey = rowKeys[rowIndex].name
                    focusSignal++
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
                modifier = cardFocusModifier(rowIndex, index),
                onClick = { onOpenMovie(movie) },
                onLongClick = onLongClick,
                onFocus = {
                    focusedRow = rowIndex
                    focusedIndex = index
                    focusedRowKey = rowKeys[rowIndex].name
                    focusSignal++
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
                modifier = cardFocusModifier(rowIndex, index),
                // The detail screen lands on the part-watched episode's
                // resume action.
                onClick = { onOpenSeries(series) },
                onLongClick = onLongClick,
                onFocus = {
                    focusedRow = rowIndex
                    focusedIndex = index
                    focusedRowKey = rowKeys[rowIndex].name
                    focusSignal++
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
                    scope.launch { searchFocus.requestFocusRetrying() }
                    true
                } else false
            },
    ) {
        // Emitted straight from rowKeys, so a shelf's position in this list IS
        // its index in rowKeys.
        //
        // The two used to be written out separately, and they disagreed:
        // rowKeys ran live -> movies -> shows with "Recently added" trailing,
        // while the items below put "Recently added" third and "Recent
        // channels" last. A card reported its row as rowKeys.indexOf(key) and
        // the snap then scrolled to the item AT that position — so focusing
        // Recently added (third on screen, seventh in rowKeys) scrolled the
        // list to the last row and left focus somewhere above the viewport,
        // which read as the page refusing to scroll past that shelf. One list
        // cannot disagree with itself.
        itemsIndexed(rowKeys, key = { _, row -> row.name }) { rowIndex, row ->
            val shelf = Modifier
                .focusGroup()
                .then(if (rowIndex == focusedRow) Modifier.focusRequester(rowFocus) else Modifier)
            when (row) {
                HomeRow.Continue -> Column {
                    SectionTitle("Continue watching")
                    LazyRow(
                        modifier = shelf.focusRestorer().shelfRingRoom(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(horizontal = ShelfRingRoom),
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
                HomeRow.Favorites -> Column {
                    SectionTitle("Favorites · on now")
                    LazyRow(
                        modifier = shelf.focusRestorer().shelfRingRoom(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(horizontal = ShelfRingRoom),
                    ) {
                        itemsIndexed(favoritesRow, key = { _, c -> c.id }) { index, channel ->
                            ChannelTile(favoritesRow, rowIndex, index, channel)
                        }
                    }
                }
                HomeRow.Recents -> Column {
                    SectionTitle("Recent channels")
                    LazyRow(
                        modifier = shelf.focusRestorer().shelfRingRoom(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(horizontal = ShelfRingRoom),
                    ) {
                        itemsIndexed(recentsRow, key = { _, c -> c.id }) { index, channel ->
                            ChannelTile(recentsRow, rowIndex, index, channel)
                        }
                    }
                }
                HomeRow.StarterChannels -> Column {
                    SectionTitle("Live channels")
                    LazyRow(
                        modifier = shelf.focusRestorer().shelfRingRoom(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(horizontal = ShelfRingRoom),
                    ) {
                        itemsIndexed(starterChannels, key = { _, c -> c.id }) { index, channel ->
                            ChannelTile(starterChannels, rowIndex, index, channel)
                        }
                    }
                }
                HomeRow.StarterMovies -> Column {
                    SectionTitle("Movies")
                    LazyRow(
                        modifier = shelf.focusRestorer().shelfRingRoom(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(horizontal = ShelfRingRoom),
                    ) {
                        itemsIndexed(starterMovies, key = { _, m -> m.id }) { index, movie ->
                            MoviePoster(
                                movie, rowIndex, index,
                                onLongClick = { menu = HomeMenu.CatalogMovie(movie) },
                            )
                        }
                    }
                }
                HomeRow.StarterSeries -> Column {
                    SectionTitle("Shows")
                    LazyRow(
                        modifier = shelf.focusRestorer().shelfRingRoom(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(horizontal = ShelfRingRoom),
                    ) {
                        itemsIndexed(starterSeries, key = { _, x -> x.id }) { index, series ->
                            SeriesPoster(
                                series, rowIndex, index,
                                onLongClick = { menu = HomeMenu.CatalogSeries(series) },
                            )
                        }
                    }
                }
                HomeRow.New -> Column {
                    // What the provider added, not what the world released —
                    // `added` is an import date, and a shelf headed "New
                    // releases" over a 2011 film is the kind of small lie that
                    // costs an app its credibility.
                    SectionTitle("Recently added")
                    LazyRow(
                        modifier = shelf.focusRestorer().shelfRingRoom(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(horizontal = ShelfRingRoom),
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
                                is CatalogCard.MovieCard -> MoviePoster(
                                    card.movie, rowIndex, index,
                                    onLongClick = { menu = HomeMenu.CatalogMovie(card.movie) },
                                )
                                is CatalogCard.SeriesCard -> SeriesPoster(
                                    card.series, rowIndex, index,
                                    onLongClick = { menu = HomeMenu.CatalogSeries(card.series) },
                                )
                            }
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
            onOpenMovie = onOpenMovie,
            onOpenSeries = onOpenSeries,
            onDismiss = {
                menu = null
                // requestFocus REFUSES by returning false rather than throwing,
                // and it refuses while the row is still recomposing around the
                // card that just went away — so this retries rather than
                // trusting the first attempt.
                scope.launch {
                    // After the delay, not before it. Removing the focused card
                    // leaves Compose to reassign focus itself, and it picks the
                    // nearest focusable — the Search pill, clear across the
                    // screen. Requesting first simply loses to that reassignment;
                    // this waits for it to happen and then takes focus back.
                    //
                    // requestFocus REFUSES by returning false rather than
                    // throwing, and refuses while the row is still recomposing,
                    // so it retries on the Boolean instead of trusting one call.
                    kotlinx.coroutines.delay(120)
                    repeat(10) {
                        if (runCatching { rowFocus.requestFocus() }.getOrDefault(false)) {
                            return@launch
                        }
                        kotlinx.coroutines.delay(60)
                    }
                }
            },
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
    onOpenMovie: (Movie) -> Unit,
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

        is HomeMenu.CatalogMovie -> ContextMenu(
            title = menu.movie.name,
            actions = listOf(
                MenuAction("Play") { vm.playMovie(menu.movie); onPlay() },
                MenuAction("More info") { onOpenMovie(menu.movie) },
                // Home only. The film keeps its place in Movies and in search,
                // and Settings offers these back in one go.
                MenuAction("Not interested", destructive = true) {
                    vm.hideFromHome("m:${menu.movie.id}")
                },
            ),
            onDismiss = onDismiss,
        )

        is HomeMenu.CatalogSeries -> ContextMenu(
            title = menu.series.name,
            actions = listOf(
                // No "Play": which episode that means lives on the series
                // screen, the same reason Continue watching does not offer it.
                MenuAction("Open series") { onOpenSeries(menu.series) },
                MenuAction("Not interested", destructive = true) {
                    vm.hideFromHome("s:${menu.series.id}")
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
