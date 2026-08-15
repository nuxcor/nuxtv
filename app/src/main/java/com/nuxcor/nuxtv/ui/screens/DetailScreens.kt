@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuxcor.nuxtv.ui.screens

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.nuxcor.nuxtv.MainViewModel
import com.nuxcor.nuxtv.data.Episode
import com.nuxcor.nuxtv.data.Movie
import com.nuxcor.nuxtv.data.Series
import com.nuxcor.nuxtv.ui.components.Artwork
import com.nuxcor.nuxtv.ui.components.CenteredMessage
import com.nuxcor.nuxtv.ui.components.ContextMenu
import com.nuxcor.nuxtv.ui.components.MenuAction
import com.nuxcor.nuxtv.ui.components.MetaChip
import com.nuxcor.nuxtv.ui.components.RatingStars
import com.nuxcor.nuxtv.ui.components.WideItem
import com.nuxcor.nuxtv.ui.theme.NuxColors

@Composable
fun MovieDetailScreen(
    vm: MainViewModel,
    movieId: String,
    onPlay: () -> Unit,
    onBack: () -> Unit,
) {
    val contentState by vm.content.collectAsState()
    val base = remember(movieId, contentState) { vm.movieById(movieId) }
    if (base == null) {
        CenteredMessage(title = "Movie not found", loading = true)
        return
    }
    var movie by remember(movieId) { mutableStateOf(base) }
    LaunchedEffect(movieId) { movie = vm.movieDetails(base) }

    val resumePositions by vm.resumePositions.collectAsState()
    val resumeMs = resumePositions[movie.url] ?: 0L

    // Focus the primary action on arrival so the page is one press from playing.
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(movieId) {
        repeat(5) {
            if (runCatching { playFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(60)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    movie.backdrop?.let { backdrop ->
        coil3.compose.AsyncImage(
            model = backdrop,
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .fillMaxHeight()
                .align(Alignment.TopEnd),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(
                            NuxColors.Background,
                            NuxColors.Background.copy(alpha = 0.94f),
                            NuxColors.Background.copy(alpha = 0.35f),
                        )
                    )
                )
        )
    }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Artwork(
            imageUrl = movie.poster,
            title = movie.name,
            modifier = Modifier
                .width(220.dp)
                .height(330.dp)
                .clip(RoundedCornerShape(16.dp)),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = movie.name,
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                color = NuxColors.OnSurface,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOfNotNull(
                    movie.year?.toString(),
                    movie.quality,
                    movie.durationText,
                    movie.genre,
                ).forEachIndexed { i, chip -> MetaChip(chip, accent = i == 0) }
            }
            movie.rating?.let { rating ->
                Spacer(Modifier.height(10.dp))
                RatingStars(rating = rating, voteCount = movie.voteCount)
            }

            // Actions sit above the synopsis: they are why the page exists, and
            // below the fold the first D-pad press would scroll the title away.
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        vm.playMovie(movie)
                        onPlay()
                    },
                    modifier = Modifier.focusRequester(playFocus),
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (resumeMs > 0) "Resume from ${formatOffset(resumeMs)}" else "Play")
                }
                if (resumeMs > 0) {
                    OutlinedButton(onClick = {
                        vm.playMovie(movie, startOver = true)
                        onPlay()
                    }) { Text("Start over") }
                }
                OutlinedButton(onClick = onBack) { Text("Back") }
            }

            if (!movie.plot.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = movie.plot.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuxColors.OnSurfaceDim,
                )
            }
            if (movie.reviews.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text(
                    "Reviews",
                    style = MaterialTheme.typography.titleSmall,
                    color = NuxColors.OnSurface,
                )
                movie.reviews.forEach { review ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "“$review”",
                        style = MaterialTheme.typography.bodySmall,
                        color = NuxColors.OnSurfaceDim,
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
    }
}

/** "1h 12m" — a resume offset a viewer can recognise at a glance. */
private fun formatOffset(ms: Long): String {
    val totalMinutes = (ms / 60_000).coerceAtLeast(0)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

@Composable
fun SeriesDetailScreen(
    vm: MainViewModel,
    seriesId: String,
    onPlay: () -> Unit,
    onBack: () -> Unit,
) {
    val contentState by vm.content.collectAsState()
    val base: Series? = remember(seriesId, contentState) { vm.seriesById(seriesId) }
    if (base == null) {
        CenteredMessage(title = "Series not found", loading = true)
        return
    }
    var series by remember(seriesId) { mutableStateOf(base) }
    LaunchedEffect(seriesId) { series = vm.seriesDetails(base) }

    var episodes by remember(seriesId) { mutableStateOf<List<Episode>?>(base.episodes) }
    LaunchedEffect(seriesId) {
        if (episodes == null) episodes = vm.episodesFor(base)
    }

    val resumePositions by vm.resumePositions.collectAsState()
    val resumeProgress by vm.resumeProgress.collectAsState()
    var menuEpisode by remember { mutableStateOf<Pair<Episode, Int>?>(null) }

    val eps = episodes
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Artwork(
                imageUrl = series.poster,
                title = series.name,
                modifier = Modifier
                    .width(110.dp)
                    .height(160.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = series.name,
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                    color = NuxColors.OnSurface,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOfNotNull(
                        series.year?.toString(),
                        eps?.let { "${it.size} episodes" },
                        series.genre,
                    ).forEachIndexed { i, chip -> MetaChip(chip, accent = i == 0) }
                }
                series.rating?.let { rating ->
                    Spacer(Modifier.height(6.dp))
                    RatingStars(rating = rating, voteCount = series.voteCount)
                }
                if (!series.plot.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = series.plot.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuxColors.OnSurfaceDim,
                        maxLines = 3,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))

        when {
            eps == null -> CenteredMessage(title = "Loading episodes…", loading = true)
            eps.isEmpty() -> CenteredMessage(title = "No episodes found")
            else -> {
                val seasons = remember(eps) { eps.map { it.season }.distinct().sorted() }
                var selectedSeason by remember(eps) { mutableStateOf(seasons.first()) }
                val seasonEpisodes = remember(eps, selectedSeason) {
                    eps.filter { it.season == selectedSeason }
                }

                if (seasons.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(seasons) { _, season ->
                            CategoryItem(
                                name = "Season $season",
                                selected = season == selectedSeason,
                                onClick = { selectedSeason = season },
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 36.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    itemsIndexed(seasonEpisodes, key = { _, e -> e.id }) { index, episode ->
                        val watchedTo = resumePositions[episode.url] ?: 0L
                        WideItem(
                            title = "${episode.episodeNum}. ${episode.title}",
                            subtitle = if (watchedTo > 0) {
                                "Resume from ${formatOffset(watchedTo)}"
                            } else {
                                episode.durationText ?: "Season ${episode.season}"
                            },
                            imageUrl = episode.poster ?: series.poster,
                            progress = resumeProgress[episode.url],
                            onClick = {
                                vm.playEpisodes(series, seasonEpisodes, index)
                                onPlay()
                            },
                            onLongClick = if (watchedTo > 0) {
                                { menuEpisode = episode to index }
                            } else null,
                        )
                    }
                }

                menuEpisode?.let { (episode, index) ->
                    ContextMenu(
                        title = "${episode.episodeNum}. ${episode.title}",
                        actions = listOf(
                            MenuAction("Resume") {
                                vm.playEpisodes(series, seasonEpisodes, index)
                                onPlay()
                            },
                            MenuAction("Start over") {
                                vm.playEpisodes(series, seasonEpisodes, index, startOver = true)
                                onPlay()
                            },
                        ),
                        onDismiss = { menuEpisode = null },
                    )
                }
            }
        }
    }
}
