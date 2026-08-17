@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuxcor.nuxtv.ui.screens

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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.runtime.collectAsState
import com.nuxcor.nuxtv.MainViewModel
import com.nuxcor.nuxtv.data.Category
import com.nuxcor.nuxtv.data.ContentBundle
import com.nuxcor.nuxtv.data.Movie
import com.nuxcor.nuxtv.data.Series
import com.nuxcor.nuxtv.ui.components.Artwork
import com.nuxcor.nuxtv.ui.components.BackdropLayer
import com.nuxcor.nuxtv.ui.components.StatusPane
import com.nuxcor.nuxtv.ui.components.MetaChip
import com.nuxcor.nuxtv.ui.components.PosterCard
import androidx.compose.foundation.lazy.grid.itemsIndexed
import com.nuxcor.nuxtv.ui.components.itemEntrance
import com.nuxcor.nuxtv.ui.components.rememberListEntrance
import com.nuxcor.nuxtv.ui.components.SectionTitle
import com.nuxcor.nuxtv.ui.theme.NuxColors
import com.nuxcor.nuxtv.ui.theme.NuxMotion

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
internal const val VOD_CONTINUE = "__continue__"
private const val VOD_MORE = "__more__"

/** One poster, flattened out of Movie or Series so the browser can be shared. */
internal data class VodEntry(
    val id: String,
    val title: String,
    val subtitle: String?,
    val poster: String?,
    val progress: Float?,
    val hero: HeroInfo,
    val onOpen: () -> Unit,
)

data class HeroInfo(
    val title: String,
    val poster: String?,
    val backdrop: String?,
    val chips: List<String>,
    val plot: String?,
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
 * Poster browsing with a category column — shared by Movies and Series, which
 * differ only in what a card says and where it goes.
 *
 * The hero scrolls with the grid rather than sitting above it: it is 150dp of a
 * 476dp lane, and pinning it would leave room for a single row of posters.
 */
@Composable
private fun VodBrowser(
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

    Box(modifier = Modifier.fillMaxSize()) {
    // Ambient artwork for the focused entry behind the whole browse pane.
    BackdropLayer(shownHero?.backdrop ?: shownHero?.poster)
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LazyColumn(
            modifier = Modifier
                .width(190.dp)
                .fillMaxHeight()
                .focusRestorer(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(shown, key = { it.id }) { category ->
                CategoryItem(
                    name = category.name,
                    selected = category.id == activeCategory,
                    onClick = { selectedCategory = category.id },
                    onFocus = { focusedCategory = category.id },
                )
            }
        }
        if (entries.isEmpty()) {
            StatusPane(title = "Nothing in this category")
            return@Row
        }
        val gridEntrance = rememberListEntrance(activeCategory)
        LazyVerticalGrid(
            // Fixed, not Adaptive: 150dp-adaptive landed on 3 columns on
            // common TV densities, which reads as a phone layout blown up.
            // Five posters a row is the shelf density every TV catalog uses.
            columns = GridCells.Fixed(5),
            modifier = Modifier.weight(1f).fillMaxHeight().focusRestorer(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(start = 4.dp, end = 8.dp, bottom = 36.dp),
        ) {
            item(key = "hero", span = { GridItemSpan(maxLineSpan) }) { HeroHeader(shownHero) }
            itemsIndexed(entries, key = { _, e -> e.id }) { index, entry ->
                Box(modifier = Modifier.itemEntrance(index, gridEntrance)) {
                    PosterCard(
                        title = entry.title,
                        subtitle = entry.subtitle,
                        imageUrl = entry.poster,
                        width = null,
                        progress = entry.progress,
                        onClick = entry.onOpen,
                        onFocus = { hero = entry.hero },
                    )
                }
            }
        }
    }
    }
}

@Composable
fun MoviesTab(vm: MainViewModel, bundle: ContentBundle, onOpenMovie: (Movie) -> Unit) {
    if (bundle.movies.isEmpty()) {
        StatusPane(title = "No movies", message = "This playlist has no movie content")
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
        if (movies.any { it.categoryId == null || it.categoryId !in known }) {
            categories + Category(id = VOD_MORE, name = "More")
        } else categories
    }

    fun Movie.entry() = VodEntry(
        id = id,
        title = name,
        subtitle = year?.toString(),
        poster = poster,
        progress = resumeProgress[url],
        hero = toHero(),
        onOpen = { onOpenMovie(this) },
    )

    val continueWatching = remember(movies, resumePositions, resumeProgress) {
        movies.filter { it.url in resumePositions }.map { it.entry() }
    }
    VodBrowser(
        categories = shownCategories,
        continueWatching = continueWatching,
        entriesFor = { categoryId ->
            val known = categories.mapTo(HashSet()) { it.id }
            when (categoryId) {
                VOD_ALL -> movies
                VOD_MORE -> movies.filter { it.categoryId == null || it.categoryId !in known }
                else -> movies.filter { it.categoryId == categoryId }
            }.map { it.entry() }
        },
        initialHero = movies.firstOrNull()?.toHero(),
    )
}

@Composable
fun SeriesTab(vm: MainViewModel, bundle: ContentBundle, onOpenSeries: (Series) -> Unit) {
    if (bundle.series.isEmpty()) {
        StatusPane(title = "No series", message = "This playlist has no series content")
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
        if (seriesList.any { it.categoryId == null || it.categoryId !in known }) {
            categories + Category(id = VOD_MORE, name = "More")
        } else categories
    }

    fun Series.entry() = VodEntry(
        id = id,
        title = name,
        subtitle = episodes?.let { "${it.size} episodes" } ?: year?.toString(),
        poster = poster,
        // The episode the viewer is actually part-way through.
        progress = episodes?.firstNotNullOfOrNull { resumeProgress[it.url] },
        hero = toHero(),
        onOpen = { onOpenSeries(this) },
    )

    val continueWatching = remember(seriesList, resumePositions, resumeProgress) {
        seriesList
            .filter { series -> series.episodes?.any { it.url in resumePositions } == true }
            .map { it.entry() }
    }
    VodBrowser(
        categories = shownCategories,
        continueWatching = continueWatching,
        entriesFor = { categoryId ->
            val known = categories.mapTo(HashSet()) { it.id }
            when (categoryId) {
                VOD_ALL -> seriesList
                VOD_MORE -> seriesList.filter { it.categoryId == null || it.categoryId !in known }
                else -> seriesList.filter { it.categoryId == categoryId }
            }.map { it.entry() }
        },
        initialHero = seriesList.firstOrNull()?.toHero(),
    )
}

private fun Movie.toHero() = HeroInfo(
    title = name,
    poster = poster,
    backdrop = backdrop,
    chips = listOfNotNull(
        "Movie",
        year?.toString(),
        rating?.let { "★ %.1f".format(it) },
        genre,
    ),
    plot = plot,
)

private fun Series.toHero() = HeroInfo(
    title = name,
    poster = poster,
    backdrop = backdrop,
    chips = listOfNotNull(
        "Series",
        year?.toString(),
        rating?.let { "★ %.1f".format(it) },
        episodes?.let { "${it.size} episodes" },
        genre,
    ),
    plot = plot,
)
