@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.agoro.tv.ui.screens

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import com.agoro.tv.ui.components.LocalArrivalFocusAllowed
import com.agoro.tv.ui.theme.Space
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.runtime.collectAsState
import com.agoro.tv.MainViewModel
import com.agoro.tv.data.Category
import com.agoro.tv.data.ContentBundle
import com.agoro.tv.data.Movie
import com.agoro.tv.data.Series
import com.agoro.tv.ui.components.Artwork
import com.agoro.tv.ui.components.BackdropLayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.VideoLibrary
import com.agoro.tv.ui.components.StatusAction
import com.agoro.tv.ui.components.StatusPane
import com.agoro.tv.ui.components.MetaChip
import com.agoro.tv.ui.components.shelfRingRoom
import com.agoro.tv.ui.components.PosterCard
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import com.agoro.tv.ui.components.itemEntrance
import com.agoro.tv.ui.components.SectionTitle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.agoro.tv.ui.components.requestFocusRetrying
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxMotion
import kotlinx.coroutines.launch

/**
 * The category vocabulary of Movies and Series, mirroring [liveCategoryList].
 *
 * Both tabs used to render one row per category inside one scrolling column,
 * with no filter, no jump and no index — so on a playlist carrying a few hundred
 * VOD categories, reaching the last one was a D-pad press per row. Live TV had
 * already solved this with a category column; these are the same pseudo-category
 * ids, so the two halves of the app browse the same way.
 */
internal const val VOD_ALL = "__all__"

/**
 * Target poster width. The grid derives its column count from this instead of
 * fixing the columns, so a poster is always the same size.
 *
 * Five fixed columns divided whatever width was left after two collapsible
 * panels — the rail (64↔190dp) and the 190dp category column — so walking
 * rail → categories → grid re-laid the pane out twice and took cells from
 * ~76dp to ~145dp. The posters visibly inflated as you moved toward them.
 */
private val POSTER_TARGET_WIDTH = 168.dp
private val GRID_GAP = 16.dp

/**
 * Room above the first grid row for a focused card to grow into.
 *
 * [NuxFocus.CardScale] scales a focused poster about its CENTRE, so half the
 * growth goes upward — and the grid clips to its own bounds. With the focused
 * row snapped flush to the top of the pane (see the snap below) that upward
 * half had nowhere to go, and the top of the poster you were actually looking
 * at was sliced off against the top edge.
 *
 * Half the growth of the tallest cell this grid produces: a 3-column pane
 * gives roughly a 250dp-wide poster, 375dp tall at 2:3, and 375 × 0.06 / 2 ≈
 * 11dp. Rounded up so the widest layouts keep a hairline of clearance.
 */
private val FOCUS_OVERHANG = 12.dp

/**
 * Columns that fit [width] at [POSTER_TARGET_WIDTH], clamped to a TV-shaped
 * range. Rounded, not floored: on the 880dp lane flooring gave four columns
 * of 208dp — a quarter wider than the target and a third wider than Home's
 * shelf posters, one row to a screen. Five columns of 163dp is the nearest
 * fit to the size the constant asks for.
 */
private fun gridColumnsFor(width: Dp): Int =
    ((width + GRID_GAP) / (POSTER_TARGET_WIDTH + GRID_GAP)).let { kotlin.math.round(it).toInt() }
        .coerceIn(3, 7)
internal const val VOD_CONTINUE = "__continue__"

/** Titles filed under a category the playlist never declared — see [CatalogIndex]. */
internal const val VOD_MORE = "__more__"

/** One spelling for the shortcut, so the duplicate check can't drift from it. */
private const val VOD_NEW_LABEL = "Recently added"

/**
 * Newest-first by the provider's own `added`/`last_modified`, held to titles
 * released this year or last — see `isRecentRelease` for why a panel's import
 * date alone is not enough. Home carries a twenty-card shelf of the same
 * thing; this is where the rest of it lives, because "what's new" is the one
 * question a catalogue of 20,000 films cannot answer from an alphabetical grid.
 */
private const val VOD_NEW = "__new__"

/**
 * A genre chip's id: this prefix and then the genre's own name.
 *
 * Genres sit at the END of the strip, after the provider's own collections.
 * Those collections are how the panel is organised — ALL MOVIES, TOP RATED,
 * NETFLIX SERIES — and they are what a viewer arriving with something in mind
 * reaches for; a genre is what they reach for when they don't. Ahead of them,
 * the shortcuts would be the thing buried.
 *
 * No divider chip between the two groups. The strip is a focus row with a
 * restorer on it, and threading a non-focusable item through that is the kind
 * of D-pad change this project does not ship without a device check.
 */
private const val VOD_GENRE = "__genre__:"

/** The genre chips, or none at all when the catalogue carries no genres. */
private fun genreCategories(genres: List<String>): List<Category> =
    genres.map { Category(id = VOD_GENRE + it, name = it) }

/**
 * A category's worth of posters, built one cell at a time.
 *
 * The browser used to receive a fully materialised List<VodEntry>: entering
 * Movies mapped all ~29,000 films into VodEntry + ArtRef + HeroInfo + two
 * lambdas — and HeroInfo formats a rating, so ~29,000 Formatters — on the
 * composition thread, for the twenty cells that fit on screen. It re-ran on
 * every category the focus rested on. The grid only ever composes what is
 * visible, so this hands it a size and a builder and lets it pay per cell.
 */
internal class VodPage(
    val size: Int,
    val keyAt: (Int) -> String,
    val entryAt: (Int) -> VodEntry,
) {
    companion object {
        val empty = VodPage(0, { "" }, { error("empty page") })

        /** For lists small enough that materialising them costs nothing. */
        fun of(entries: List<VodEntry>) =
            VodPage(entries.size, { entries[it].id }, { entries[it] })
    }

    /** The hero for the cell at [index], clamped; null on an empty page. */
    fun heroAt(index: Int): HeroInfo? =
        if (size > 0) entryAt(index.coerceIn(0, size - 1)).hero else null
}

/**
 * A snap scroll that survives the list's own bring-into-view.
 *
 * Focusing a card asks its lazy list to bring it into view, and that request
 * takes the list's scroll mutex at the same priority as an animateScrollToItem
 * in flight — so a RIGHT pressed while a row was still sliding to the top
 * cancelled the slide and left the row wherever the card became visible.
 * Home used to paper over this by re-snapping on EVERY focus event, which is
 * the "two animations fight per horizontal move" it was meant to cure. The
 * interruption arrives as the mutex's CancellationException with this
 * coroutine still alive; a real cancellation — a newer snap, the screen
 * leaving — is rethrown by ensureActive.
 */
internal suspend fun snapRetrying(attempts: Int = 3, scroll: suspend () -> Unit) {
    repeat(attempts) {
        try {
            scroll()
            return
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
        }
    }
}

/** One poster, flattened out of Movie or Series so the browser can be shared. */
internal data class VodEntry(
    val id: String,
    val title: String,
    val subtitle: String?,
    val poster: String?,
    /** Looked up when [poster] is null — see [borrowedArt]. */
    val art: ArtRef?,
    /** Shown on the poster: key art carries the title but never the year. */
    val year: Int?,
    val progress: Float?,
    val hero: HeroInfo,
    val onOpen: () -> Unit,
    /** Fired once when the tile gains focus — series use it to warm episodes. */
    val onFocus: () -> Unit = {},
)

data class HeroInfo(
    val title: String,
    val poster: String?,
    val backdrop: String?,
    val chips: List<String>,
    val plot: String?,
    /**
     * What to ask TMDB for when [backdrop] is null, which it is for every
     * catalogue entry until its detail screen has been opened. Null for
     * channels — a live channel has no backdrop to borrow.
     */
    val art: ArtRef? = null,
    /**
     * The programme whose synopsis belongs in [plot], when the guide has one
     * and it has not been read yet. Guide synopses live in a table and are
     * read one at a time, so a live hero names the programme it wants and
     * the screen fills the text once focus settles.
     */
    val plotKey: String? = null,
)

@Composable
fun HeroHeader(hero: HeroInfo?) {
    if (hero == null) return
    androidx.compose.animation.AnimatedContent(
        targetState = hero,
        transitionSpec = {
            androidx.compose.animation.fadeIn(
                androidx.compose.animation.core.tween(NuxMotion.EmphasizedMs, easing = NuxMotion.StandardEasing)
            ) togetherWith androidx.compose.animation.fadeOut(
                androidx.compose.animation.core.tween(NuxMotion.FastMs, easing = NuxMotion.ExitEasing)
            )
        },
        label = "hero",
    ) { current ->
        Column(modifier = Modifier.padding(bottom = 8.dp)) {
            Text(
                text = current.title,
                style = MaterialTheme.typography.displaySmall,
                color = NuxColors.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                current.chips.take(4).forEachIndexed { i, chip -> MetaChip(chip, accent = i == 0) }
            }
            if (!current.plot.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = current.plot,
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuxColors.OnSurfaceDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 620.dp),
                )
            }
        }
    }
}

/**
 * Poster browsing with a category strip along the top — shared by Movies and
 * Shows, which differ only in what a card says and where it goes. The strip is
 * the same grammar as Live TV's: chips in a row, UP from the first poster row
 * comes back to it, and the grid keeps the full pane width. OK selects a
 * chip; there is no dwell here — see the strip below for why.
 *
 * The hero is pinned above the grid, one line tall — see [BrowseHero].
 *
 * Nothing in this body reads the focus position. A D-pad press writes
 * [focusedEntryIndex] and that is all it does in composition: the hero and
 * the row snap are driven off it by snapshotFlow in effects, the arrival
 * requester sits on a cell chosen once, and each cell remembers its own
 * entry. The first cut read the index in every cell's modifier and in this
 * scope's snap, so every composed poster — and the browser around them —
 * recomposed on every press, rebuilding a VodEntry, a HeroInfo and two
 * lambdas per cell to find out that nothing about the cell had changed.
 */
@Composable
private fun VodBrowser(
    vm: MainViewModel,
    categories: List<Category>,
    continueWatching: List<VodEntry>,
    entriesFor: (String) -> VodPage,
    initialHero: HeroInfo?,
) {
    var selectedCategory by rememberSaveable { mutableStateOf(VOD_ALL) }
    val shown = remember(categories, continueWatching.isNotEmpty()) {
        buildList {
            add(Category(id = VOD_ALL, name = "All"))
            // Only once there is something in it: an empty shortcut is a dead
            // end that still costs a D-pad press to walk past.
            if (continueWatching.isNotEmpty()) {
                add(Category(id = VOD_CONTINUE, name = "Continue watching"))
            }
            addAll(categories)
        }
    }
    val activeCategory = remember(selectedCategory, shown) {
        if (shown.any { it.id == selectedCategory }) selectedCategory else VOD_ALL
    }

    val page = remember(activeCategory, continueWatching, entriesFor) {
        if (activeCategory == VOD_CONTINUE) VodPage.of(continueWatching)
        else entriesFor(activeCategory)
    }
    // Saveable: the tab leaves composition for every detail page and every
    // film played, and coming back must be a return. The grid's scroll state
    // already survives; this is the poster within it, and whether focus was
    // in the grid at all (else it belongs on the strip).
    var focusedEntryIndex by rememberSaveable { mutableStateOf(0) }
    var browsingGrid by rememberSaveable { mutableStateOf(false) }
    // The category this composition has already laid out. A change after
    // that is a real switch (scroll to the top, describe the first poster);
    // the first composition after a return is not, and treating it as one
    // threw away the viewer's place in the grid.
    var laidOutCategory by rememberSaveable { mutableStateOf(activeCategory) }
    // The grid is replaced wholesale on a category switch without focus moving
    // inside it; resetting the index is what moves the hero to the first
    // poster of the new category, since the hero follows the index.
    LaunchedEffect(activeCategory) {
        if (activeCategory == laidOutCategory) return@LaunchedEffect
        laidOutCategory = activeCategory
        focusedEntryIndex = 0
    }

    // The hero follows the focused poster — the remembered one on a return,
    // the first one on a fresh visit — and is DERIVED from the index rather
    // than snapshotted by the focus callback, so it is read here exactly once
    // (without observation: a read the composition tracked would bring every
    // press back into this scope) and from then on only inside the effect.
    // Debounced there, so travelling a poster row doesn't hard-cut the hero
    // (text and backdrop together) 5x/second.
    val pageState = rememberUpdatedState(page)
    val initialHeroState = rememberUpdatedState(initialHero)
    val shownHero = remember {
        mutableStateOf(Snapshot.withoutReadObservation { page.heroAt(focusedEntryIndex) ?: initialHero })
    }
    LaunchedEffect(Unit) {
        snapshotFlow { pageState.value.heroAt(focusedEntryIndex) ?: initialHeroState.value }
            .distinctUntilChanged()
            .collectLatest { hero ->
                if (hero == shownHero.value) return@collectLatest
                kotlinx.coroutines.delay(NuxMotion.HeroDebounceMs.toLong())
                shownHero.value = hero
            }
    }

    // On the strip itself, with a restorer: UP from the grid lands on the chip
    // focus last left, the guide's pattern. A requester on chip 0 broke two
    // ways — the chip could be disposed (the strip scrolls) so UP did nothing
    // at all, and when it existed the landing on "All" dwell-selected it and
    // wiped the category the viewer had chosen.
    val categoriesFocus = remember { FocusRequester() }
    val posterFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    // Arrival: the poster the viewer left, if focus was in the grid; else the
    // strip. Once per visit — see rememberInitialFocus for why not on every
    // flip of the allowed flag.
    //
    // The target is read ONCE, at composition: the shell's own parking lands
    // on the first thing it finds — a chip, since the strip composes before
    // the grid — and that chip's focus callback rewrites browsingGrid before
    // this effect gets to run. Read late, the return always "remembered"
    // being on the strip. Read without observation, or this scope would
    // recompose on every press for a value it only ever wanted once.
    val returnIndex = remember {
        Snapshot.withoutReadObservation { if (browsingGrid) focusedEntryIndex else -1 }
    }
    // True until the arrival has settled. A chip focused in passing during
    // that window must not count as the viewer leaving the grid: the shell's
    // parking put focus on whichever chip was nearest, and the return then
    // "remembered" being on the strip. A plain holder — nothing in
    // composition reads it, so flipping it must not invalidate anything.
    val arriving = remember { booleanArrayOf(true) }
    val arrivalAllowed = LocalArrivalFocusAllowed.current
    val arrivalPending = remember { booleanArrayOf(true) }
    LaunchedEffect(arrivalAllowed) {
        if (!arrivalAllowed || !arrivalPending[0]) return@LaunchedEffect
        arrivalPending[0] = false
        try {
            if (returnIndex >= 0) {
                focusedEntryIndex = returnIndex
                browsingGrid = true
                // The grid composes a few frames after the strip; wait it out.
                if (posterFocus.requestFocusRetrying(retries = 16, intervalMs = 50)) {
                    return@LaunchedEffect
                }
                browsingGrid = false
            }
            categoriesFocus.requestFocusRetrying()
        } finally {
            arriving[0] = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    // Ambient artwork for the focused entry behind the whole browse pane. The
    // poster is the stand-in, not the request: asking for the 16:9 art even
    // when a poster exists is what turns a wall of stretched portrait crops
    // into something that looks composed.
    BrowseBackdrop(vm, shownHero)
    Column(modifier = Modifier.fillMaxSize()) {
        // OK selects, nothing else does. Live TV's chips dwell-select because
        // the guide beneath them is the whole screen and a rest on a chip is
        // a real choice; here the grid beneath the strip is rebuilt from
        // scratch on every switch — scrolled to the top, re-staggered, the
        // hero reset — and a dwell turned travelling the strip into a series
        // of those. A viewer moving along the chips to reach "Thriller" does
        // not want "Action", "Comedy" and "Drama" laid out on the way.
        androidx.compose.foundation.lazy.LazyRow(
            modifier = Modifier
                .padding(bottom = 10.dp)
                .focusRequester(categoriesFocus)
                .focusRestorer(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 16.dp),
        ) {
            itemsIndexed(shown, key = { _, c -> c.id }) { index, category ->
                CategoryItem(
                    name = category.name,
                    selected = category.id == activeCategory,
                    onClick = { selectedCategory = category.id },
                    onFocus = {
                        if (arriving[0]) return@CategoryItem
                        browsingGrid = false
                    },
                )
            }
        }
        // Pinned above the grid, like Home's: posters are captionless, so
        // the focused one's name has to live somewhere that does not scroll
        // away with the row above it — which is what the hero did as the
        // grid's first item, leaving every poster past row one nameless.
        // One line, so it costs the grid less than a third of a row.
        Box(modifier = Modifier.fillMaxWidth().height(BROWSE_HERO_HEIGHT)) {
            BrowseHeroSlot(shownHero)
        }
        if (page.size == 0) {
            StatusPane(
                title = "Nothing in this category",
                message = "Pick another from the row above.",
                icon = Icons.Default.Movie,
            )
            return@Column
        }
        // Saveable, so a return from a film does not stagger the grid in
        // again. Only a category switch — a real change of content — or a
        // fresh launch animates.
        val gridEntrance = rememberSaveable(activeCategory) { android.os.SystemClock.uptimeMillis() }
        val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
        // A category switch starts at the top. The state is shared across
        // categories, and without this "Comedy" opened wherever "All" had
        // been scrolled to — row 60 of a list that may have 40. Only a real
        // switch: the first composition after a return keeps the restored
        // offset.
        var gridCategory by remember { mutableStateOf(activeCategory) }
        LaunchedEffect(activeCategory) {
            if (gridCategory == activeCategory) return@LaunchedEffect
            gridCategory = activeCategory
            gridState.scrollToItem(0)
        }
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
        val gridColumns = gridColumnsFor(maxWidth)
        // Row snapping: the focused row aligns to the top of the pane, so the
        // rows above scroll fully away instead of leaving an orphaned caption
        // sliver ("2014" floating under nothing) clipped at the top edge.
        //
        // The snap stops FOCUS_OVERHANG short of the edge. Landing the row
        // flush against it is what cost the focused poster its top: the card
        // scales about its centre and the grid clips, so the row that snapping
        // exists to show you was the one row guaranteed to be cut.
        val overhangPx = with(LocalDensity.current) { FOCUS_OVERHANG.roundToPx() }
        // Keyed on the ROW, via snapshotFlow rather than a read in this
        // scope: focusedEntryIndex changes on LEFT/RIGHT too, and a read here
        // rebuilt the grid's whole modifier chain — re-measuring it through
        // shelfRingRoom — once per keypress, while the row it derives stayed
        // the same. distinctUntilChanged is what makes sideways travel free.
        LaunchedEffect(gridColumns, overhangPx) {
            snapshotFlow { if (browsingGrid) focusedEntryIndex / gridColumns else -1 }
                .distinctUntilChanged()
                .collectLatest { row ->
                    if (row < 0) return@collectLatest
                    // A negative offset seats the row below the viewport
                    // start rather than on it.
                    snapRetrying {
                        gridState.animateScrollToItem(row * gridColumns, scrollOffset = -overhangPx)
                    }
                }
        }
        LazyVerticalGrid(
            state = gridState,
            // Fixed with a COMPUTED count, not Adaptive: Adaptive keeps
            // cells at least minSize but shares the remainder out, so the
            // poster still changes size with the pane. See POSTER_TARGET_WIDTH.
            columns = GridCells.Fixed(gridColumns),
            modifier = Modifier
                .fillMaxSize()
                // A vertical grid clips its cross axis at bounds too, so the
                // FIRST COLUMN's focus ring lost its left edge exactly the
                // way the shelves' first cards did. Same cure: widen, pad
                // back, resting cells stay put.
                .shelfRingRoom()
                .focusRestorer()
                .onPreviewKeyEvent { event ->
                    // UP from the first poster row returns to the strip.
                    // Intercepted, not left to geometry: the hero header
                    // sits between them, takes no focus, and the search
                    // could sail past a scrolled strip to the clock.
                    if (event.type == KeyEventType.KeyDown &&
                        event.key == Key.DirectionUp &&
                        focusedEntryIndex < gridColumns
                    ) {
                        browsingGrid = false
                        scope.launch { categoriesFocus.requestFocusRetrying() }
                        true
                    } else false
                },
            horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(
                // The ring room plus the original inset, so cell widths and
                // resting positions are exactly what they were.
                start = com.agoro.tv.ui.components.ShelfRingRoom + 4.dp,
                end = com.agoro.tv.ui.components.ShelfRingRoom + 8.dp,
                // Covers the unscrolled case the snap never runs for.
                top = FOCUS_OVERHANG,
                bottom = 36.dp,
            ),
        ) {
            items(count = page.size, key = { page.keyAt(it) }) { index ->
                // Built here, for this cell only — see [VodPage] — and
                // remembered, so the cell's lambdas keep their identity and
                // the poster beneath can skip.
                val entry = remember(page, index) { page.entryAt(index) }
                Box(modifier = Modifier.itemEntrance(index, gridEntrance)) {
                    PosterCard(
                        title = entry.title,
                        imageUrl = borrowedArt(vm, entry.art, entry.poster),
                        width = null,
                        year = entry.year,
                        progress = entry.progress,
                        // The arrival requester rides on exactly one cell —
                        // the one chosen at composition — and is never asked
                        // for again, so no cell needs to watch the index.
                        modifier = if (index == returnIndex) {
                            Modifier.focusRequester(posterFocus)
                        } else Modifier,
                        onClick = entry.onOpen,
                        onFocus = {
                            browsingGrid = true
                            focusedEntryIndex = index
                            entry.onFocus()
                        },
                    )
                }
            }
        }
        }
    }
    }
}

/**
 * The backdrop and the header each read the hero in a scope of their own, so
 * a debounced hero swap recomposes the two of them and not the browser — the
 * strip, the grid and every composed cell — around them.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.BrowseBackdrop(
    vm: MainViewModel,
    hero: State<HeroInfo?>,
) {
    val current = hero.value
    BackdropLayer(
        borrowedArt(vm, current?.art, current?.backdrop, wide = true)
            ?: current?.poster
    )
}

@Composable
private fun BrowseHeroSlot(hero: State<HeroInfo?>) {
    BrowseHero(hero.value)
}

/** The pinned hero's slot — one line of title and chips. */
private val BROWSE_HERO_HEIGHT = 52.dp

/**
 * Title and chips on one line, for the browse grids. Home's two-deck
 * [HeroHeader] with its synopsis is the right size above three shelves; above
 * a grid it is a third of the lane, and the synopsis is one OK press away on
 * the detail page anyway.
 */
@Composable
private fun BrowseHero(hero: HeroInfo?) {
    if (hero == null) return
    androidx.compose.animation.AnimatedContent(
        targetState = hero,
        transitionSpec = {
            androidx.compose.animation.fadeIn(
                androidx.compose.animation.core.tween(NuxMotion.EmphasizedMs, easing = NuxMotion.StandardEasing)
            ) togetherWith androidx.compose.animation.fadeOut(
                androidx.compose.animation.core.tween(NuxMotion.FastMs, easing = NuxMotion.ExitEasing)
            )
        },
        label = "browseHero",
    ) { current ->
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.m),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            Text(
                text = current.title,
                style = MaterialTheme.typography.headlineSmall,
                color = NuxColors.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            current.chips.take(4).forEachIndexed { i, chip -> MetaChip(chip, accent = i == 0) }
        }
    }
}

@Composable
fun MoviesTab(
    vm: MainViewModel,
    bundle: ContentBundle,
    onOpenMovie: (Movie) -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    if (bundle.movies.isEmpty()) {
        // Every empty state carries the same three things: a mark, one line
        // that says something the title didn't, and a way forward. The bare
        // title-plus-restatement these used to show was a dead end with no
        // exit — and read as a screen someone hadn't finished.
        StatusPane(
            title = "No movies",
            message = "This playlist doesn't carry a film library.",
            icon = Icons.Default.Movie,
            primaryAction = StatusAction("Switch playlist", onOpenSettings),
        )
        return
    }
    // The catalogue, indexed once off the main thread — see [CatalogIndex].
    // Nothing is drawn until the index for THIS bundle has landed: it arrives
    // a beat after the bundle, and the one before it describes a playlist
    // that is no longer on screen.
    val catalog by vm.catalog.collectAsState()
    val current = catalog?.takeIf { it.index.bundle === bundle }
    if (current == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    val index = current.index
    val resumeProgress by vm.resumeProgress.collectAsState()

    // Anything filed under a category the playlist never declared still has to
    // be reachable, so it gets a category of its own rather than disappearing.
    val shownCategories = remember(index) {
        buildList {
            // Ahead of the provider's own categories: it is the shortcut, and
            // a shortcut buried under three hundred genre names is not one.
            // Skipped when the catalogue already offers a shelf by that name —
            // a curated manifest names one itself, and two tabs reading
            // "Recently added" is the catalogue looking broken.
            if (index.newMovies.isNotEmpty() &&
                index.movieCategories.none { it.name.equals(VOD_NEW_LABEL, true) }
            ) {
                add(Category(id = VOD_NEW, name = VOD_NEW_LABEL))
            }
            addAll(index.movieCategories)
            if (!index.moviesByCategory[VOD_MORE].isNullOrEmpty()) {
                add(Category(id = VOD_MORE, name = "More"))
            }
            addAll(genreCategories(index.movieGenres))
        }
    }

    fun Movie.entry() = VodEntry(
        id = id,
        title = name,
        subtitle = year?.toString(),
        poster = poster,
        art = artRef(),
        year = year,
        progress = resumeProgress[url],
        hero = toHero(),
        onOpen = { onOpenMovie(this) },
    )

    // Newest first, like Home's shelf — the index already orders it so.
    val continueWatching = remember(current, resumeProgress) {
        current.resumedMovies.map { it.entry() }
    }
    VodBrowser(
        vm = vm,
        categories = shownCategories,
        continueWatching = continueWatching,
        // Every category is a lookup; the filters and the sort that used to
        // run here, on the main thread, per chip, ran once in the index.
        entriesFor = { categoryId ->
            val list = when {
                categoryId == VOD_ALL -> index.movies
                categoryId == VOD_NEW -> index.newMovies
                // A map lookup, off the same single index pass the categories
                // come from — never a filter over 29,000 films on the main
                // thread, which is what the strip cost before it was indexed.
                categoryId.startsWith(VOD_GENRE) ->
                    index.moviesByGenre[genreKey(categoryId.removePrefix(VOD_GENRE))].orEmpty()
                else -> index.moviesByCategory[categoryId].orEmpty()
            }
            VodPage(list.size, { list[it].id }, { list[it].entry() })
        },
        initialHero = remember(index) { index.movies.firstOrNull()?.toHero() },
    )
}

@Composable
fun SeriesTab(
    vm: MainViewModel,
    bundle: ContentBundle,
    onOpenSeries: (Series) -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    if (bundle.series.isEmpty()) {
        StatusPane(
            title = "No shows",
            message = "This playlist doesn't carry a box-set library.",
            icon = Icons.Default.VideoLibrary,
            primaryAction = StatusAction("Switch playlist", onOpenSettings),
        )
        return
    }
    // See MoviesTab: the index for this bundle, or nothing yet.
    val catalog by vm.catalog.collectAsState()
    val current = catalog?.takeIf { it.index.bundle === bundle }
    if (current == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    val index = current.index
    val resumeProgress by vm.resumeProgress.collectAsState()
    // Which series each watched episode belongs to, folded by the index into
    // seriesId -> progress of the most recently watched episode.
    // Series.episodes is null for Xtream until the detail page fetched them,
    // so anything keyed off it — the Continue watching shelf, the poster
    // progress bar — simply never appeared on the setup this app is built
    // for, while Home's Continue watching row (built from the origins map)
    // did.
    val seriesProgress = current.seriesProgress

    val shownCategories = remember(index) {
        buildList {
            // See the movies list: the manifest's own series sections lead
            // with a "Recently added" shelf, so adding a second one put the
            // same name in the column twice.
            if (index.newSeries.isNotEmpty() &&
                index.seriesCategories.none { it.name.equals(VOD_NEW_LABEL, true) }
            ) {
                add(Category(id = VOD_NEW, name = VOD_NEW_LABEL))
            }
            addAll(index.seriesCategories)
            if (!index.seriesByCategory[VOD_MORE].isNullOrEmpty()) {
                add(Category(id = VOD_MORE, name = "More"))
            }
            addAll(genreCategories(index.seriesGenres))
        }
    }

    fun Series.entry() = VodEntry(
        id = id,
        title = name,
        subtitle = episodes?.let { "${it.size} episodes" } ?: year?.toString(),
        poster = poster,
        art = artRef(),
        year = year,
        // The episode the viewer is actually part-way through.
        progress = seriesProgress[id] ?: episodes?.firstNotNullOfOrNull { resumeProgress[it.url] },
        hero = toHero(),
        onOpen = { onOpenSeries(this) },
        // Focusing a poster warms its episodes — on curated proxies
        // (IPTVEditor) the request is what starts the upstream build, so by
        // the time OK is pressed the list is often already there. Once per
        // series per session; scrolling never re-sends (the 429 trap
        // TiviMate's per-focus refetch falls into).
        onFocus = { vm.prefetchEpisodes(this) },
    )

    // Newest first, the order Home uses for the same shelf.
    val continueWatching = remember(current, resumeProgress) {
        current.resumedSeries.map { it.entry() }
    }
    VodBrowser(
        vm = vm,
        categories = shownCategories,
        continueWatching = continueWatching,
        entriesFor = { categoryId ->
            val list = when {
                categoryId == VOD_ALL -> index.series
                categoryId == VOD_NEW -> index.newSeries
                categoryId.startsWith(VOD_GENRE) ->
                    index.seriesByGenre[genreKey(categoryId.removePrefix(VOD_GENRE))].orEmpty()
                else -> index.seriesByCategory[categoryId].orEmpty()
            }
            VodPage(list.size, { list[it].id }, { list[it].entry() })
        },
        initialHero = remember(index) { index.series.firstOrNull()?.toHero() },
    )
}

/**
 * "★ 7.5", by arithmetic. `"%.1f".format(it)` builds a java.util.Formatter
 * per call, and this runs once per poster composed into a grid — a screenful
 * on every category switch and every scroll, on the main thread. A rounded
 * tenth needs no Formatter.
 */
internal fun ratingChip(rating: Double): String {
    val tenths = Math.round(rating * 10)
    return "★ ${tenths / 10}.${kotlin.math.abs(tenths % 10)}"
}

internal fun Movie.toHero() = HeroInfo(
    title = name,
    poster = poster,
    backdrop = backdrop,
    art = artRef(),
    chips = listOfNotNull(
        "Movie",
        year?.toString(),
        rating?.let { ratingChip(it) },
        genre,
    ),
    plot = plot,
)

internal fun Series.toHero() = HeroInfo(
    title = name,
    poster = poster,
    backdrop = backdrop,
    art = artRef(),
    chips = listOfNotNull(
        "Show",
        year?.toString(),
        rating?.let { ratingChip(it) },
        episodes?.let { "${it.size} episodes" },
        genre,
    ),
    plot = plot,
)
