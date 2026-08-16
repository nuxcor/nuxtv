@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuxcor.nuxtv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import com.nuxcor.nuxtv.ui.components.CenteredMessage
import com.nuxcor.nuxtv.ui.components.MetaChip
import com.nuxcor.nuxtv.ui.components.PosterCard
import com.nuxcor.nuxtv.ui.components.SectionTitle
import com.nuxcor.nuxtv.ui.theme.NuxColors

/** Groups items into category rows; items with no category land in "More". */
private fun <T> rowsOf(
    categories: List<Category>,
    items: List<T>,
    categoryId: (T) -> String?,
): List<Pair<String, List<T>>> {
    val byCategory = items.groupBy(categoryId)
    val rows = categories.mapNotNull { cat ->
        byCategory[cat.id]?.takeIf { it.isNotEmpty() }?.let { cat.name to it }
    }
    val uncategorized = byCategory.filterKeys { key -> key == null || categories.none { it.id == key } }
        .values.flatten()
    return if (uncategorized.isEmpty()) rows else rows + ("More" to uncategorized)
}

data class HeroInfo(
    val title: String,
    val poster: String?,
    val chips: List<String>,
    val plot: String?,
)

@Composable
fun HeroHeader(hero: HeroInfo?) {
    if (hero == null) return
    // Debounced so travelling a poster row doesn't hard-cut the text 5x/second.
    var shown by remember { mutableStateOf(hero) }
    LaunchedEffect(hero) {
        kotlinx.coroutines.delay(180)
        shown = hero
    }
    androidx.compose.animation.AnimatedContent(
        targetState = shown,
        transitionSpec = {
            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(280)) togetherWith
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))
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

@Composable
fun MoviesTab(vm: MainViewModel, bundle: ContentBundle, onOpenMovie: (Movie) -> Unit) {
    if (bundle.movies.isEmpty()) {
        CenteredMessage(title = "No movies", subtitle = "This playlist has no movie content")
        return
    }
    val resumePositions by vm.resumePositions.collectAsState()
    val resumeProgress by vm.resumeProgress.collectAsState()
    val pin by vm.parentalPin.collectAsState()
    val unlocked by vm.parentalUnlocked.collectAsState()
    val rows = remember(bundle, pin, unlocked) {
        val visibleCategories = bundle.movieCategories.filterNot { vm.isLockedCategory(it.name) }
        val lockedIds = bundle.movieCategories.filter { vm.isLockedCategory(it.name) }.map { it.id }.toSet()
        rowsOf(visibleCategories, bundle.movies.filterNot { it.categoryId in lockedIds }) { it.categoryId }
    }
    val continueWatching = remember(bundle, resumePositions) {
        bundle.movies.filter { it.url in resumePositions }
    }
    var hero by remember(bundle) { mutableStateOf(bundle.movies.firstOrNull()?.toHero()) }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(32.dp),
            contentPadding = PaddingValues(bottom = 36.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "hero") { HeroHeader(hero) }
            if (continueWatching.isNotEmpty()) {
                item(key = "movies:continue") {
                    Column {
                        SectionTitle("Continue watching", continueWatching.size)
                        LazyRow(
                            modifier = Modifier.focusRestorer(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(start = 4.dp, end = 24.dp),
                        ) {
                            items(continueWatching.size, key = { continueWatching[it].id }) { i ->
                                val movie = continueWatching[i]
                                PosterCard(
                                    title = movie.name,
                                    subtitle = movie.year?.toString(),
                                    imageUrl = movie.poster,
                                    progress = resumeProgress[movie.url],
                                    onClick = { onOpenMovie(movie) },
                                    onFocus = { hero = movie.toHero() },
                                )
                            }
                        }
                    }
                }
            }
            rows.forEach { (categoryName, movies) ->
                item(key = "movies:$categoryName") {
                    Column {
                        SectionTitle(categoryName, movies.size)
                        LazyRow(
                            modifier = Modifier.focusRestorer(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(start = 4.dp, end = 24.dp),
                        ) {
                            items(movies.size, key = { movies[it].id }) { i ->
                                val movie = movies[i]
                                PosterCard(
                                    title = movie.name,
                                    subtitle = movie.year?.toString(),
                                    imageUrl = movie.poster,
                                    onClick = { onOpenMovie(movie) },
                                    onFocus = { hero = movie.toHero() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SeriesTab(vm: MainViewModel, bundle: ContentBundle, onOpenSeries: (Series) -> Unit) {
    if (bundle.series.isEmpty()) {
        CenteredMessage(title = "No series", subtitle = "This playlist has no series content")
        return
    }
    val resumePositions by vm.resumePositions.collectAsState()
    val resumeProgress by vm.resumeProgress.collectAsState()
    val pin by vm.parentalPin.collectAsState()
    val unlocked by vm.parentalUnlocked.collectAsState()
    val rows = remember(bundle, pin, unlocked) {
        val visibleCategories = bundle.seriesCategories.filterNot { vm.isLockedCategory(it.name) }
        val lockedIds = bundle.seriesCategories.filter { vm.isLockedCategory(it.name) }.map { it.id }.toSet()
        rowsOf(visibleCategories, bundle.series.filterNot { it.categoryId in lockedIds }) { it.categoryId }
    }
    val continueWatching = remember(bundle, resumePositions) {
        bundle.series.filter { series -> series.episodes?.any { it.url in resumePositions } == true }
    }
    var hero by remember(bundle) { mutableStateOf(bundle.series.firstOrNull()?.toHero()) }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(32.dp),
            contentPadding = PaddingValues(bottom = 36.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "hero") { HeroHeader(hero) }
            if (continueWatching.isNotEmpty()) {
                item(key = "series:continue") {
                    Column {
                        SectionTitle("Continue watching", continueWatching.size)
                        LazyRow(
                            modifier = Modifier.focusRestorer(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(start = 4.dp, end = 24.dp),
                        ) {
                            items(continueWatching.size, key = { continueWatching[it].id }) { i ->
                                val series = continueWatching[i]
                                PosterCard(
                                    title = series.name,
                                    subtitle = series.year?.toString(),
                                    imageUrl = series.poster,
                                    // The episode the viewer is actually part-way through.
                                    progress = series.episodes
                                        ?.firstNotNullOfOrNull { resumeProgress[it.url] },
                                    onClick = { onOpenSeries(series) },
                                    onFocus = { hero = series.toHero() },
                                )
                            }
                        }
                    }
                }
            }
            rows.forEach { (categoryName, seriesList) ->
                item(key = "series:$categoryName") {
                    Column {
                        SectionTitle(categoryName, seriesList.size)
                        LazyRow(
                            modifier = Modifier.focusRestorer(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(start = 4.dp, end = 24.dp),
                        ) {
                            items(seriesList.size, key = { seriesList[it].id }) { i ->
                                val series = seriesList[i]
                                PosterCard(
                                    title = series.name,
                                    subtitle = series.episodes?.let { "${it.size} episodes" }
                                        ?: series.year?.toString(),
                                    imageUrl = series.poster,
                                    onClick = { onOpenSeries(series) },
                                    onFocus = { hero = series.toHero() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Movie.toHero() = HeroInfo(
    title = name,
    poster = poster,
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
    chips = listOfNotNull(
        "Series",
        year?.toString(),
        rating?.let { "★ %.1f".format(it) },
        episodes?.let { "${it.size} episodes" },
        genre,
    ),
    plot = plot,
)
