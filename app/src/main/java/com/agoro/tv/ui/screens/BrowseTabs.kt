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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
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
import com.agoro.tv.ui.components.rememberListEntrance
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

/** Columns that fit [width] at [POSTER_TARGET_WIDTH], clamped to a TV-shaped range. */
private fun gridColumnsFor(width: Dp): Int =
    ((width + GRID_GAP) / (POSTER_TARGET_WIDTH + GRID_GAP)).toInt().coerceIn(3, 7)
internal const val VOD_CONTINUE = "__continue__"
private const val VOD_MORE = "__more__"

/** One spelling for the shortcut, so the duplicate check can't drift from it. */
private const val VOD_NEW_LABEL = "Recently added"

/**
 * Newest-first by the provider's own `added`/`last_modified`. Home carries a
 * twenty-card shelf of the same thing; this is where the rest of it lives,
 * because "what's new" is the one question a catalogue of 20,000 films cannot
 * answer from an alphabetical grid.
 */
private const val VOD_NEW = "__new__"

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
 * the same grammar as Live TV's: chips in a row, dwell selects, UP from the
 * first poster row comes back to it, and the grid keeps the full pane width.
 *
 * The hero scrolls with the grid rather than sitting above it: it is 150dp of a
 * 476dp lane, and pinning it would leave room for a single row of posters.
 */
@Composable
private fun VodBrowser(
    vm: MainViewModel,
    categories: List<Category>,
    continueWatching: List<VodEntry>,
    entriesFor: (String) -> List<VodEntry>,
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
    // Same rest-before-select rule as the nav rail and Live TV: travelling the
    // column would otherwise rebuild the whole grid on every step.
    var focusedCategory by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusedCategory) {
        val id = focusedCategory ?: return@LaunchedEffect
        kotlinx.coroutines.delay(NuxMotion.FocusDwellMs.toLong())
        selectedCategory = id
    }

    val entries = remember(activeCategory, continueWatching, entriesFor) {
        if (activeCategory == VOD_CONTINUE) continueWatching else entriesFor(activeCategory)
    }
    var hero by remember(initialHero) { mutableStateOf(initialHero) }
    // The grid is replaced wholesale on a category switch without focus moving
    // inside it, so nothing else would clear the header of the item it was
    // describing in a category that is no longer shown.
    LaunchedEffect(activeCategory) { hero = entries.firstOrNull()?.hero ?: initialHero }

    // Debounced so travelling a poster row doesn't hard-cut the hero (text and
    // backdrop together) 5x/second.
    var shownHero by remember { mutableStateOf(hero) }
    LaunchedEffect(hero) {
        kotlinx.coroutines.delay(NuxMotion.HeroDebounceMs.toLong())
        shownHero = hero
    }

    // True while focus lives in the grid — gates the row snapping so the
    // strip above never scrolls the grid out from under itself.
    var browsingGrid by remember { mutableStateOf(false) }
    val categoriesFocus = remember { FocusRequester() }
    var focusedEntryIndex by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
    // Ambient artwork for the focused entry behind the whole browse pane. The
    // poster is the stand-in, not the request: asking for the 16:9 art even
    // when a poster exists is what turns a wall of stretched portrait crops
    // into something that looks composed.
    BackdropLayer(
        borrowedArt(vm, shownHero?.art, shownHero?.backdrop, wide = true)
            ?: shownHero?.poster
    )
    Column(modifier = Modifier.fillMaxSize()) {
        androidx.compose.foundation.lazy.LazyRow(
            modifier = Modifier
                .padding(bottom = 10.dp)
                .focusRestorer(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 16.dp),
        ) {
            itemsIndexed(shown, key = { _, c -> c.id }) { index, category ->
                CategoryItem(
                    name = category.name,
                    selected = category.id == activeCategory,
                    onClick = { selectedCategory = category.id },
                    // The re-entry target must be an ITEM: focusing the
                    // strip landed on the scroll container itself — no
                    // ring, no dwell, focus stranded.
                    modifier = if (index == 0) {
                        Modifier.focusRequester(categoriesFocus)
                    } else Modifier,
                    onFocus = {
                        browsingGrid = false
                        focusedCategory = category.id
                    },
                )
            }
        }
        if (entries.isEmpty()) {
            StatusPane(
                title = "Nothing in this category",
                message = "Pick another from the row above.",
                icon = Icons.Default.Movie,
            )
            return@Column
        }
        val gridEntrance = rememberListEntrance(activeCategory)
        val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
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
        LaunchedEffect(focusedEntryIndex, browsingGrid, overhangPx) {
            if (!browsingGrid) return@LaunchedEffect
            val row = focusedEntryIndex / gridColumns
            // +1 skips the full-span hero header item. A negative offset seats
            // the row below the viewport start rather than on it.
            gridState.animateScrollToItem(1 + row * gridColumns, scrollOffset = -overhangPx)
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
            item(key = "hero", span = { GridItemSpan(maxLineSpan) }) { HeroHeader(shownHero) }
            itemsIndexed(entries, key = { _, e -> e.id }) { index, entry ->
                Box(modifier = Modifier.itemEntrance(index, gridEntrance)) {
                    PosterCard(
                        title = entry.title,
                        imageUrl = borrowedArt(vm, entry.art, entry.poster),
                        width = null,
                        year = entry.year,
                        progress = entry.progress,
                        onClick = entry.onOpen,
                        onFocus = {
                            browsingGrid = true
                            focusedEntryIndex = index
                            hero = entry.hero
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
    val resumePositions by vm.resumePositions.collectAsState()
    val resumeProgress by vm.resumeProgress.collectAsState()
    val pin by vm.parentalPin.collectAsState()
    val unlocked by vm.parentalUnlocked.collectAsState()

    val visible = remember(bundle, pin, unlocked) {
        val lockedIds = bundle.movieCategories
            .filter { vm.isLockedCategory(it.name) }.map { it.id }.toSet()
        bundle.movieCategories.filterNot { vm.isLockedCategory(it.name) } to
            bundle.movies.filterNot { it.categoryId in lockedIds }
    }
    val (categories, movies) = visible
    // Anything filed under a category the playlist never declared still has to
    // be reachable, so it gets a category of its own rather than disappearing.
    val shownCategories = remember(categories, movies) {
        val known = categories.mapTo(HashSet()) { it.id }
        buildList {
            // Ahead of the provider's own categories: it is the shortcut, and
            // a shortcut buried under three hundred genre names is not one.
            // Skipped when the catalogue already offers a shelf by that name —
            // a curated manifest names one itself, and two tabs reading
            // "Recently added" is the catalogue looking broken.
            if (movies.any { it.addedMs != null } && categories.none { it.name.equals(VOD_NEW_LABEL, true) }) {
                add(Category(id = VOD_NEW, name = VOD_NEW_LABEL))
            }
            addAll(categories)
            if (movies.any { it.categoryId == null || it.categoryId !in known }) {
                add(Category(id = VOD_MORE, name = "More"))
            }
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

    val continueWatching = remember(movies, resumePositions, resumeProgress) {
        movies.filter { it.url in resumePositions }.map { it.entry() }
    }
    VodBrowser(
        vm = vm,
        categories = shownCategories,
        continueWatching = continueWatching,
        entriesFor = { categoryId ->
            val known = categories.mapTo(HashSet()) { it.id }
            when (categoryId) {
                VOD_ALL -> movies
                VOD_NEW -> movies.filter { it.addedMs != null }.sortedByDescending { it.addedMs }
                VOD_MORE -> movies.filter { it.categoryId == null || it.categoryId !in known }
                else -> movies.filter { it.categoryId == categoryId }
            }.map { it.entry() }
        },
        initialHero = movies.firstOrNull()?.toHero(),
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
    val resumePositions by vm.resumePositions.collectAsState()
    val resumeProgress by vm.resumeProgress.collectAsState()
    val pin by vm.parentalPin.collectAsState()
    val unlocked by vm.parentalUnlocked.collectAsState()

    val visible = remember(bundle, pin, unlocked) {
        val lockedIds = bundle.seriesCategories
            .filter { vm.isLockedCategory(it.name) }.map { it.id }.toSet()
        bundle.seriesCategories.filterNot { vm.isLockedCategory(it.name) } to
            bundle.series.filterNot { it.categoryId in lockedIds }
    }
    val (categories, seriesList) = visible
    val shownCategories = remember(categories, seriesList) {
        val known = categories.mapTo(HashSet()) { it.id }
        buildList {
            // See the movies list: the manifest's own series sections lead
            // with a "Recently added" shelf, so adding a second one put the
            // same name in the column twice.
            if (seriesList.any { it.addedMs != null } &&
                categories.none { it.name.equals(VOD_NEW_LABEL, true) }
            ) {
                add(Category(id = VOD_NEW, name = VOD_NEW_LABEL))
            }
            addAll(categories)
            if (seriesList.any { it.categoryId == null || it.categoryId !in known }) {
                add(Category(id = VOD_MORE, name = "More"))
            }
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
        progress = episodes?.firstNotNullOfOrNull { resumeProgress[it.url] },
        hero = toHero(),
        onOpen = { onOpenSeries(this) },
        // Focusing a poster warms its episodes — on curated proxies
        // (IPTVEditor) the request is what starts the upstream build, so by
        // the time OK is pressed the list is often already there. Once per
        // series per session; scrolling never re-sends (the 429 trap
        // TiviMate's per-focus refetch falls into).
        onFocus = { vm.prefetchEpisodes(this) },
    )

    val continueWatching = remember(seriesList, resumePositions, resumeProgress) {
        seriesList
            .filter { series -> series.episodes?.any { it.url in resumePositions } == true }
            .map { it.entry() }
    }
    VodBrowser(
        vm = vm,
        categories = shownCategories,
        continueWatching = continueWatching,
        entriesFor = { categoryId ->
            val known = categories.mapTo(HashSet()) { it.id }
            when (categoryId) {
                VOD_ALL -> seriesList
                VOD_NEW ->
                    seriesList.filter { it.addedMs != null }.sortedByDescending { it.addedMs }
                VOD_MORE -> seriesList.filter { it.categoryId == null || it.categoryId !in known }
                else -> seriesList.filter { it.categoryId == categoryId }
            }.map { it.entry() }
        },
        initialHero = seriesList.firstOrNull()?.toHero(),
    )
}

internal fun Movie.toHero() = HeroInfo(
    title = name,
    poster = poster,
    backdrop = backdrop,
    art = artRef(),
    chips = listOfNotNull(
        "Movie",
        year?.toString(),
        rating?.let { "★ %.1f".format(it) },
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
        rating?.let { "★ %.1f".format(it) },
        episodes?.let { "${it.size} episodes" },
        genre,
    ),
    plot = plot,
)
