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
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
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
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxFocus
import com.agoro.tv.ui.theme.NuxMotion
import com.agoro.tv.ui.theme.NuxShape
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.agoro.tv.data.isFavorite

/**
 * The landing screen: what you were watching, what you starred, where you
 * just were — under a hero describing whichever card is focused, with Search
 * pinned to the top-right the way every TV launcher offers it. Pure assembly;
 * the joins live in [Catalog] (built off the main thread by
 * [MainViewModel.catalog]) and [channelsInCategory].
 */
/**
 * How many items a day-one catalogue row carries. Long enough to browse, short
 * enough that Home never becomes a second, worse Movies tab.
 */
internal const val STARTER_ROW_LENGTH = 20

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

    data class ResumedMovie(val movie: Movie) : HomeMenu
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
private enum class HomeRow {
    Continue, Favorites, Recents, StarterChannels, StarterMovies, StarterSeries, New
}

/**
 * Everything the hero can describe, in one holder so the hero effect reads
 * one state for the rows and re-derives when any of them change — the guide
 * filling in, a favorite starred — without a composition read in between.
 */
private class HomeShelves(
    val rowKeys: List<HomeRow>,
    val continueRow: List<ContinueCard>,
    val favoritesRow: List<LiveChannel>,
    val recentsRow: List<LiveChannel>,
    val recentlyAdded: List<CatalogCard>,
    val starterChannels: List<LiveChannel>,
    val starterMovies: List<Movie>,
    val starterSeries: List<Series>,
    val nowNext: Map<String, MainViewModel.NowNext>,
) {
    fun sizeOf(row: HomeRow): Int = when (row) {
        HomeRow.Continue -> continueRow.size
        HomeRow.Favorites -> favoritesRow.size
        HomeRow.Recents -> recentsRow.size
        HomeRow.StarterChannels -> starterChannels.size
        HomeRow.StarterMovies -> starterMovies.size
        HomeRow.StarterSeries -> starterSeries.size
        HomeRow.New -> recentlyAdded.size
    }

    /** The hero for the card at [index] in row [rowIndex], clamped to what exists. */
    fun heroAt(rowIndex: Int, index: Int): HeroInfo? {
        val row = rowKeys.getOrNull(rowIndex) ?: rowKeys.firstOrNull() ?: return null
        fun <T> List<T>.at(): T? = getOrNull(index) ?: firstOrNull()
        return when (row) {
            HomeRow.Continue -> when (val card = continueRow.at()) {
                is ContinueCard.MovieCard -> card.movie.toHero()
                is ContinueCard.SeriesCard -> card.series.toHero()
                null -> null
            }
            HomeRow.New -> when (val card = recentlyAdded.at()) {
                is CatalogCard.MovieCard -> card.movie.toHero()
                is CatalogCard.SeriesCard -> card.series.toHero()
                null -> null
            }
            HomeRow.Favorites -> favoritesRow.at()?.let { channelHero(it, nowNext[it.id]) }
            HomeRow.Recents -> recentsRow.at()?.let { channelHero(it, nowNext[it.id]) }
            HomeRow.StarterChannels -> starterChannels.at()?.let { channelHero(it, nowNext[it.id]) }
            HomeRow.StarterMovies -> starterMovies.at()?.toHero()
            HomeRow.StarterSeries -> starterSeries.at()?.toHero()
        }
    }
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
    val favorites by vm.favorites.collectAsState()
    val recents by vm.recentChannels.collectAsState()
    val displayChannels by vm.displayChannels.collectAsState()
    val nowNext by vm.nowNext.collectAsState()
    // The catalogue rows, joined once off the main thread — Continue
    // watching, Recently added, the day-one shelves — see [Catalog]. They
    // never draw straight from the bundle: the index filters the parental
    // lock first, and Home is the screen that greets whoever switches the TV
    // on, so it is the last place that may put a locked category's artwork
    // on screen. "Not interested" is applied there too, to Home's rows only —
    // the film is still in Movies, in Shows and in search, because "not on
    // my home screen" is not "delete this".
    val catalog by vm.catalog.collectAsState()

    val favoritesRow = remember(displayChannels, favorites) {
        channelsInCategory(CATEGORY_FAVORITES, displayChannels, favorites, recents)
    }
    val recentsRow = remember(displayChannels, recents) {
        channelsInCategory(CATEGORY_RECENT, displayChannels, favorites, recents)
    }

    // displayChannels folds off the main thread and lands a beat after the
    // bundle, and the catalogue index a beat after that. Nothing is drawn in
    // those beats: the welcome pane would tell a viewer with favorites that
    // they have none, and the catalogue shelves would take focus a frame
    // before the live shelf was prepended above them — so Home opened
    // anchored on Movies with its first row scrolled off the top, and focus
    // on a film instead of on television.
    if (displayChannels.isEmpty() && bundle.channels.isNotEmpty()) {
        Box(Modifier.fillMaxSize())
        return
    }
    val shelves = catalog?.takeIf { it.index.bundle === bundle }
    if (shelves == null && (bundle.movies.isNotEmpty() || bundle.series.isNotEmpty())) {
        Box(Modifier.fillMaxSize())
        return
    }
    val continueRow = shelves?.continueWatching.orEmpty()
    // Not a day-one row: what a provider just added is the reason to open the
    // app on any day, so this one stays whatever else Home is showing.
    val recentlyAdded = shelves?.recentlyAdded.orEmpty()

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
    val starterMovies = shelves?.starterMovies.orEmpty()
    val starterSeries = shelves?.starterSeries.orEmpty()
    val watchedChannels = favoritesRow.isNotEmpty() || recentsRow.isNotEmpty()
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
            title = "Welcome to Agorɔ",
            message = "Things you watch and star will gather here.",
            icon = Icons.Default.Home,
            primaryAction = StatusAction("Browse Live TV") { onBrowse(HomeTab.Live) },
            secondaryAction = StatusAction("Search") { onBrowse(HomeTab.Search) },
        )
        return
    }

    // Saveable, all of them: Home leaves composition for every detail page
    // and every channel, and coming back is a return, not a launch. The row
    // and the card within it are what "where I was" means here; whether a
    // card has ever taken focus is saved too so the first-arrival
    // scroll-to-top below stays spent.
    //
    // NOTHING in this scope reads them. A D-pad press writes the three and
    // that is the whole of its cost in composition: the hero and the row
    // snap are driven off them by snapshotFlow in effects, the arrival
    // requester rides on a card chosen once, and each shelf carries a
    // requester of its own. The first cut derived the hero from them in this
    // body and compared them in every card's modifier, so every press
    // re-executed the tab — seven flow reads, a dozen remembers, a new
    // LazyColumn content lambda — and invalidated every composed card to
    // find out that one of them had gained a requester.
    var focusedRow by rememberSaveable { mutableStateOf(0) }
    var focusedIndex by rememberSaveable { mutableStateOf(0) }
    // The focused row by NAME as well as by index: a shelf appearing above
    // it (the first favorite starred, a first resume) shifts every index
    // below, and the viewer's row must follow itself rather than hand its
    // slot to the newcomer.
    var focusedRowKey by rememberSaveable { mutableStateOf(HomeRow.Continue.name) }
    var hasFocused by rememberSaveable { mutableStateOf(false) }
    val rowKeysState = rememberUpdatedState(rowKeys)
    /** The viewer's row, by name first, else by index — read in effects only. */
    fun currentRow(): HomeRow {
        val keys = rowKeysState.value
        return keys.firstOrNull { it.name == focusedRowKey }
            ?: keys.getOrNull(focusedRow)
            ?: keys.first()
    }
    // Set by the context menu, consumed by the effects below in the frame
    // the menu leaves composition. Plain holders: nothing in composition
    // reads them, and arming one must not invalidate anything.
    val returnFocusPending = remember { booleanArrayOf(false) }
    // The action taken removes the card the menu was opened on.
    val removalPending = remember { booleanArrayOf(false) }
    // ...and that card was the last in its row, so the row goes with it.
    val rowGonePending = remember { booleanArrayOf(false) }
    // One requester per shelf, attached unconditionally, so no shelf has to
    // watch which row is focused. Focus has to come back after the menu
    // closes: a destructive action — "Not interested", "Remove from Continue
    // watching" — deletes the very card that opened the menu, so the focused
    // node is gone by the time the menu dismisses and focus falls to
    // nothing: the d-pad goes dead until the viewer leaves the screen. The
    // menu hands focus back to the viewer's row, and focusRestorer picks the
    // card within.
    val rowRequesters = remember { HomeRow.entries.associateWith { FocusRequester() } }

    // The hero describes the focused card — the remembered one on a return,
    // the first one on a fresh visit — so the screen never opens on an empty
    // header. DERIVED from the focus position and the rows' current data,
    // never snapshotted by the focus callback: a snapshot taken before the
    // guide had loaded described the channel as "Live" with no programme and
    // stayed that way until focus moved, while the card under it had long
    // since filled in. The rows ride in one updated state so the flow
    // re-derives when they change, and the first value is read here exactly
    // once — without observation, or this scope would follow every press —
    // so a return opens on its hero rather than on a blank header.
    val shelvesState = rememberUpdatedState(
        remember(
            rowKeys, continueRow, favoritesRow, recentsRow, recentlyAdded,
            starterChannels, starterMovies, starterSeries, nowNext,
        ) {
            HomeShelves(
                rowKeys, continueRow, favoritesRow, recentsRow, recentlyAdded,
                starterChannels, starterMovies, starterSeries, nowNext,
            )
        },
    )
    val shownHero = remember {
        mutableStateOf(Snapshot.withoutReadObservation { shelvesState.value.heroAt(focusedRow, focusedIndex) })
    }
    val heroSink = rememberUpdatedState(onHeroChange)
    LaunchedEffect(Unit) {
        snapshotFlow { shelvesState.value.heroAt(focusedRow, focusedIndex) }
            .distinctUntilChanged()
            .collectLatest { hero ->
                // Debounced so travelling a row doesn't hard-cut the hero
                // (and the shell's backdrop with it) 5x/second. The first
                // value is the one composed above, so it passes straight
                // through to the shell.
                if (hero != shownHero.value) {
                    kotlinx.coroutines.delay(NuxMotion.HeroDebounceMs.toLong())
                    shownHero.value = hero
                }
                heroSink.value(hero)
                // One hero is on screen at a time, so this is the one guide
                // synopsis worth reading — after the debounce, so travelling
                // a row of channels costs no queries at all.
                val key = hero?.plotKey ?: return@collectLatest
                if (!hero.plot.isNullOrBlank()) return@collectLatest
                val plot = vm.descriptionFor(key)?.takeIf { it.isNotBlank() } ?: return@collectLatest
                val filled = hero.copy(plot = plot)
                shownHero.value = filled
                heroSink.value(filled)
            }
    }

    val columnState = rememberLazyListState()
    // Row snapping (the browse grid's rule): the focused row aligns to the
    // top of the scrolling lane so the rows above scroll fully away instead
    // of leaving clipped caption slivers. The hero is pinned above the lane,
    // so row 0 is simply the top.
    //
    // Keyed on the ROW. This used to re-run on every card focus as a
    // "counter-snap" against the LazyColumn's own bring-into-view, which
    // meant a LEFT or RIGHT started a vertical animation to the row the
    // viewer was already on, and the two fought for the scroll mutex on
    // every horizontal move. A row snapped to the top leaves bring-into-view
    // nothing to do — it checks visibility before it scrolls — and the one
    // case where it does interrupt, a sideways press mid-snap, is what
    // snapRetrying is for.
    LaunchedEffect(Unit) {
        snapshotFlow { focusedRow }
            .distinctUntilChanged()
            .collectLatest { row ->
                snapRetrying { columnState.animateScrollToItem(row.coerceAtLeast(0)) }
            }
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
        if (!hasFocused) {
            columnState.scrollToItem(0)
            return@LaunchedEffect
        }
        val index = rowKeys.indexOfFirst { it.name == focusedRowKey }
        if (index >= 0 && index != focusedRow) {
            focusedRow = index
            columnState.scrollToItem(index)
        }
        // A menu action just removed the last card of the viewer's row, and
        // the row with it: focus fell to nothing when it went. Seat it on
        // whatever row now holds that slot — see the menu effect below.
        if (rowGonePending[0] && index < 0) {
            rowGonePending[0] = false
            rowRequesters.getValue(currentRow()).requestFocusRetrying()
        }
    }
    // Saveable, so a return from a channel or a film does not stagger every
    // card in again: Home leaves composition for both, and the stamp was
    // re-taken on each return. Only a fresh launch animates.
    val entrance = rememberSaveable { android.os.SystemClock.uptimeMillis() }
    val searchFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    // Where focus lands on arrival: the card the viewer left, or the first
    // card of the first shelf on a fresh visit. Left to the shell's geometric
    // parking, focus went to whichever node existed first — the Search pill,
    // because the hero composes a frame before the lazy rows do — so the app
    // opened (and every return from a detail page landed) on Search rather
    // than on anything to watch. Rides on the card itself; the shelf's
    // restorer is the fallback when that card has scrolled out of composition.
    //
    // The target is read ONCE, at composition, and without observation: the
    // requester rides on exactly one card for the whole visit, and a read
    // the composition tracked would bring every press back into this scope.
    // By row NAME, so a shelf that arrives above it in the meantime cannot
    // move the requester onto the wrong row's card.
    val cardFocus = remember { FocusRequester() }
    val arrival = remember {
        Snapshot.withoutReadObservation {
            val row = rowKeys.firstOrNull { it.name == focusedRowKey }
                ?: rowKeys.getOrNull(focusedRow)
                ?: rowKeys.first()
            row to focusedIndex
        }
    }
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
            rowRequesters.getValue(currentRow()).requestFocusRetrying()
        }
    }

    var menu by remember { mutableStateOf<HomeMenu?>(null) }
    val focusManager = LocalFocusManager.current
    // Focus comes back in the SAME FRAME the menu unmounts, not after a wall-
    // clock wait. This used to delay 120ms and then retry for up to 600ms
    // more, and in that window Compose had already reseated focus on the
    // nearest node — the Search pill, clear across the screen — so a quick
    // press after closing acted on it. The request cannot be made before the
    // menu is gone: the dialog scaffold cancels any focus exit while it
    // stands, so a request made inside onDismiss is refused. Keyed on the
    // menu state, this runs once the frame that removed it has applied,
    // before the next key event can arrive.
    LaunchedEffect(menu) {
        if (menu != null || !returnFocusPending[0]) return@LaunchedEffect
        returnFocusPending[0] = false
        val row = currentRow()
        val doomedIndex = focusedIndex
        val sizeBefore = shelvesState.value.sizeOf(row)
        // First attempt immediately; the rest are the bounded fallback for a
        // row still recomposing — requestFocus REFUSES by returning false
        // rather than throwing.
        val landed = rowRequesters.getValue(row).requestFocusRetrying(retries = 5, intervalMs = 60)
        if (!landed || !removalPending[0]) return@LaunchedEffect
        removalPending[0] = false
        // The destructive case lands late: the write reaches the prefs, the
        // catalogue re-joins off the main thread, and only then does the row
        // drop the card — and focus with it, to nothing. The restorer has
        // just put focus back on that very card, so step off it NOW, to the
        // neighbour that survives, rather than wait for the loss and guess
        // whether the viewer had meanwhile moved somewhere on purpose. Only
        // when the card is still there: if the removal beat this effect the
        // restorer fell to the first card, which is already the answer.
        val stillThere = focusedIndex == doomedIndex && shelvesState.value.sizeOf(row) == sizeBefore
        if (!stillThere) return@LaunchedEffect
        if (!focusManager.moveFocus(FocusDirection.Right) &&
            !focusManager.moveFocus(FocusDirection.Left)
        ) {
            // The only card in its row: the row disappears with it, and the
            // rowKeys effect seats focus on whatever takes its place.
            rowGonePending[0] = true
        }
    }

    /** The arrival requester rides on exactly one card: the remembered one. */
    fun cardFocusModifier(row: HomeRow, index: Int): Modifier =
        if (row == arrival.first && index == arrival.second) {
            Modifier.focusRequester(cardFocus)
        } else Modifier

    fun noteFocus(row: HomeRow, rowIndex: Int, index: Int) {
        focusedRow = rowIndex
        focusedIndex = index
        focusedRowKey = row.name
        hasFocused = true
    }

    @Composable
    fun ChannelTile(
        channels: List<LiveChannel>,
        row: HomeRow,
        rowIndex: Int,
        index: Int,
        channel: LiveChannel,
    ) {
        val nn = nowNext[channel.id]
        Box(modifier = Modifier.itemEntrance(index, entrance)) {
            ChannelShelfCard(
                channel = channel,
                now = nn?.now,
                modifier = cardFocusModifier(row, index),
                onClick = {
                    vm.playChannels(channels, index)
                    onPlay()
                },
                onLongClick = { menu = HomeMenu.Channel(channel, channels, index) },
                onFocus = { noteFocus(row, rowIndex, index) },
            )
        }
    }

    /** A catalogue poster, with TMDB's art when the provider shipped none. */
    @Composable
    fun MoviePoster(
        movie: Movie,
        row: HomeRow,
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
                modifier = cardFocusModifier(row, index),
                onClick = { onOpenMovie(movie) },
                onLongClick = onLongClick,
                onFocus = { noteFocus(row, rowIndex, index) },
            )
        }
    }

    @Composable
    fun SeriesPoster(
        series: Series,
        row: HomeRow,
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
                modifier = cardFocusModifier(row, index),
                // The detail screen lands on the part-watched episode's
                // resume action.
                onClick = { onOpenSeries(series) },
                onLongClick = onLongClick,
                onFocus = {
                    noteFocus(row, rowIndex, index)
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
            HomeHeroSlot(shownHero)
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
                .focusRequester(rowRequesters.getValue(row))
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
                                    card.movie, row, rowIndex, index, card.progress,
                                    onLongClick = {
                                        menu = HomeMenu.ResumedMovie(card.movie)
                                    },
                                )
                                is ContinueCard.SeriesCard -> SeriesPoster(
                                    card.series, row, rowIndex, index, card.progress,
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
                            ChannelTile(favoritesRow, row, rowIndex, index, channel)
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
                            ChannelTile(recentsRow, row, rowIndex, index, channel)
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
                            ChannelTile(starterChannels, row, rowIndex, index, channel)
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
                                movie, row, rowIndex, index,
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
                                series, row, rowIndex, index,
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
                                    card.movie, row, rowIndex, index,
                                    onLongClick = { menu = HomeMenu.CatalogMovie(card.movie) },
                                )
                                is CatalogCard.SeriesCard -> SeriesPoster(
                                    card.series, row, rowIndex, index,
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
            favoritesRow = favoritesRow,
            onRemove = { removalPending[0] = true },
            onDismiss = {
                // Arm the return first, then unmount: the effect above runs
                // in the frame the menu leaves and finds the flag set.
                returnFocusPending[0] = true
                menu = null
            },
        )
    }
}

/**
 * The header reads the hero in a scope of its own, so a debounced hero swap
 * recomposes the header and nothing else — not the tab, its rows or the
 * cards in them.
 */
@Composable
private fun HomeHeroSlot(hero: State<HeroInfo?>) {
    HeroHeader(hero.value)
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
    /** The Favorites shelf's list, so a channel menu can tell whether un-starring empties its card's slot. */
    favoritesRow: List<LiveChannel>,
    /**
     * The action chosen takes the card the menu was opened on off its shelf.
     * Called before [onDismiss], so the return of focus can step off the
     * card before the shelf drops it.
     */
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (menu) {
        is HomeMenu.Channel -> {
            val isFav = menu.channel.isFavorite(favorites)
            ContextMenu(
                title = menu.channel.displayName,
                actions = listOf(
                    MenuAction("Play") {
                        vm.playChannels(menu.row, menu.index)
                        onPlay()
                    },
                    MenuAction(if (isFav) "Remove from favorites" else "Add to favorites") {
                        // Un-starring removes the card only from the
                        // Favorites shelf; on Recents or Live channels it
                        // stays where it is.
                        if (isFav && menu.row === favoritesRow) onRemove()
                        vm.toggleFavorite(menu.channel)
                    },
                    MenuAction("Hide this channel") {
                        onRemove()
                        vm.toggleHidden(menu.channel)
                    },
                ),
                onDismiss = onDismiss,
            )
        }

        // The two Continue watching menus read alike, because the shelf shows
        // one kind of thing: something part-watched. Details for what it is,
        // Clear progress to take it off the shelf. Resume and Start over used
        // to sit here for films and could not for shows — which episode a
        // series card means is a question only the detail screen can answer —
        // so the same hold on two neighbouring cards offered different
        // actions. Details reaches Resume/Start over in one more press, and
        // OK on the card goes straight there.
        is HomeMenu.ResumedMovie -> ContextMenu(
            title = menu.movie.name,
            actions = listOf(
                MenuAction("Details") { onOpenMovie(menu.movie) },
                // Destructive only in the sense that it cannot be undone from
                // here; nothing is deleted but the bookmark.
                MenuAction("Clear progress", destructive = true) {
                    onRemove()
                    vm.forgetResume(menu.movie.url)
                },
            ),
            onDismiss = onDismiss,
        )

        is HomeMenu.CatalogMovie -> ContextMenu(
            title = menu.movie.name,
            actions = listOf(
                MenuAction("Play") { vm.playMovie(menu.movie); onPlay() },
                MenuAction("Details") { onOpenMovie(menu.movie) },
                // Home only. The film keeps its place in Movies and in search,
                // and Settings offers these back in one go.
                MenuAction("Not interested", destructive = true) {
                    onRemove()
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
                MenuAction("Details") { onOpenSeries(menu.series) },
                MenuAction("Not interested", destructive = true) {
                    onRemove()
                    vm.hideFromHome("s:${menu.series.id}")
                },
            ),
            onDismiss = onDismiss,
        )

        is HomeMenu.ResumedSeries -> ContextMenu(
            title = menu.series.name,
            actions = listOf(
                MenuAction("Details") { onOpenSeries(menu.series) },
                MenuAction("Clear progress", destructive = true) {
                    onRemove()
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
