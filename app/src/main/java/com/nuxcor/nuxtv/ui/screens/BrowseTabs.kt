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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .padding(bottom = 12.dp)
    ) {
        if (hero == null) return@Box
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hero.title,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = NuxColors.OnSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    hero.chips.forEachIndexed { i, chip -> MetaChip(chip, accent = i == 0) }
                }
                if (!hero.plot.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = hero.plot,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuxColors.OnSurfaceDim,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(520.dp),
                    )
                }
            }
            Artwork(
                imageUrl = hero.poster,
                title = hero.title,
                modifier = Modifier
                    .width(118.dp)
                    .height(172.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
        }
    }
}

@Composable
fun MoviesTab(bundle: ContentBundle, onOpenMovie: (Movie) -> Unit) {
    if (bundle.movies.isEmpty()) {
        CenteredMessage(title = "No movies", subtitle = "This playlist has no movie content")
        return
    }
    val rows = remember(bundle) { rowsOf(bundle.movieCategories, bundle.movies) { it.categoryId } }
    var hero by remember(bundle) { mutableStateOf(bundle.movies.firstOrNull()?.toHero()) }

    Column(modifier = Modifier.fillMaxSize().padding(start = 36.dp, top = 28.dp)) {
        HeroHeader(hero)
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(22.dp),
            contentPadding = PaddingValues(bottom = 36.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            rows.forEach { (categoryName, movies) ->
                item(key = "movies:$categoryName") {
                    Column {
                        SectionTitle("$categoryName  ·  ${movies.size}")
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(end = 48.dp),
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
fun SeriesTab(bundle: ContentBundle, onOpenSeries: (Series) -> Unit) {
    if (bundle.series.isEmpty()) {
        CenteredMessage(title = "No series", subtitle = "This playlist has no series content")
        return
    }
    val rows = remember(bundle) { rowsOf(bundle.seriesCategories, bundle.series) { it.categoryId } }
    var hero by remember(bundle) { mutableStateOf(bundle.series.firstOrNull()?.toHero()) }

    Column(modifier = Modifier.fillMaxSize().padding(start = 36.dp, top = 28.dp)) {
        HeroHeader(hero)
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(22.dp),
            contentPadding = PaddingValues(bottom = 36.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            rows.forEach { (categoryName, seriesList) ->
                item(key = "series:$categoryName") {
                    Column {
                        SectionTitle("$categoryName  ·  ${seriesList.size}")
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(end = 48.dp),
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
