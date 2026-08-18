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
import com.nuxcor.nuxtv.data.ContentState
import com.nuxcor.nuxtv.data.Episode
import com.nuxcor.nuxtv.data.Movie
import com.nuxcor.nuxtv.data.Series
import com.nuxcor.nuxtv.ui.components.Artwork
import com.nuxcor.nuxtv.ui.components.BackdropLayer
import com.nuxcor.nuxtv.ui.components.StatusAction
import com.nuxcor.nuxtv.ui.components.StatusPane
import com.nuxcor.nuxtv.ui.components.ContextMenu
import com.nuxcor.nuxtv.ui.components.MenuAction
import com.nuxcor.nuxtv.ui.components.MetaChip
import com.nuxcor.nuxtv.ui.components.RatingStars
import com.nuxcor.nuxtv.ui.components.WideItem
import com.nuxcor.nuxtv.ui.theme.NuxColors
import com.nuxcor.nuxtv.ui.theme.Space
import com.nuxcor.nuxtv.ui.theme.NuxShape
import com.nuxcor.nuxtv.ui.components.requestFocusRetrying

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
        MissingItemPane("Movie", contentState, onBack)
        return
    }
    var movie by remember(movieId) { mutableStateOf(base) }
    LaunchedEffect(movieId) { movie = vm.movieDetails(base) }

    val resumePositions by vm.resumePositions.collectAsState()
    val resumeMs = resumePositions[movie.url] ?: 0L

    // Focus the primary action on arrival so the page is one press from playing.
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(movieId) {
        playFocus.requestFocusRetrying()
    }

    Box(modifier = Modifier.fillMaxSize()) {
    BackdropLayer(movie.backdrop)
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Space.s, vertical = Space.s),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Artwork(
            imageUrl = movie.poster,
            title = movie.name,
            modifier = Modifier
                .width(220.dp)
                .height(330.dp)
                .clip(NuxShape.Card),
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

/**
 * An id that didn't resolve — which is two states, not one.
 *
 * These used to share a single `CenteredMessage("… not found", loading = true)`:
 * a spinner underneath a message announcing the search was over, with no control
 * on screen to leave by. While the library is still loading the id simply isn't
 * resolvable yet; once it is loaded, the item is genuinely gone.
 */
@Composable
private fun MissingItemPane(kind: String, contentState: ContentState, onBack: () -> Unit) {
    if (contentState !is ContentState.Ready) {
        StatusPane(title = "Loading…", loading = true)
        return
    }
    StatusPane(
        title = "$kind not found",
        message = "It may have been removed from this playlist.",
        primaryAction = StatusAction("Back", onBack),
    )
}

/** "1h 12m" — a resume offset a viewer can recognise at a glance. */
private fun formatOffset(ms: Long): String {
    val totalMinutes = (ms / 60_000).coerceAtLeast(0)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        // Under a minute rounded down to "0m", which read as a bug.
        minutes == 0L -> "${(ms / 1_000).coerceAtLeast(1)}s"
        else -> "${minutes}m"
    }
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
        MissingItemPane("Series", contentState, onBack)
        return
    }
    var series by remember(seriesId) { mutableStateOf(base) }
    LaunchedEffect(seriesId) { series = vm.seriesDetails(base) }

    var episodes by remember(seriesId) { mutableStateOf<List<Episode>?>(base.episodes) }
    var episodesFailed by remember(seriesId) { mutableStateOf(false) }
    var providerPreparing by remember(seriesId) { mutableStateOf(false) }
    var loadAttempt by remember(seriesId) { mutableStateOf(0) }
    LaunchedEffect(seriesId, loadAttempt) {
        if (episodes != null) return@LaunchedEffect
        episodesFailed = false
        // Curated-playlist proxies (IPTVEditor and kin) build a series'
        // episode list lazily: the FIRST get_series_info triggers the fetch
        // from the origin provider and answers empty; the real list lands
        // seconds to minutes later. One request and a shrug showed those
        // series as permanently empty, so an empty answer is polled a few
        // times with growing patience before it is believed.
        val waits = listOf(0L, 8_000L, 20_000L, 40_000L)
        for (wait in waits) {
            if (wait > 0) {
                providerPreparing = true
                kotlinx.coroutines.delay(wait)
            }
            val result = vm.episodesFor(base)
            when {
                result == null -> {
                    episodesFailed = true
                    providerPreparing = false
                    return@LaunchedEffect
                }
                result.isNotEmpty() -> {
                    episodes = result
                    providerPreparing = false
                    return@LaunchedEffect
                }
            }
        }
        providerPreparing = false
        episodes = emptyList()
    }

    val resumePositions by vm.resumePositions.collectAsState()
    val resumeProgress by vm.resumeProgress.collectAsState()
    var menuEpisode by remember { mutableStateOf<Pair<Episode, Int>?>(null) }

    val eps = episodes

    /**
     * Where the viewer got to: the furthest episode carrying a resume position.
     * Continue Watching promises resumption, and following it used to land here
     * with no primary action, focus wherever Compose put it, and the season list
     * parked on Season 1 while the part-watched episode sat in Season 3.
     */
    val resumeTarget = remember(eps, resumePositions) {
        eps?.filter { (resumePositions[it.url] ?: 0L) > 0L }
            ?.maxWithOrNull(compareBy({ it.season }, { it.episodeNum }))
    }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(seriesId, eps != null) {
        if (eps.isNullOrEmpty()) return@LaunchedEffect
        playFocus.requestFocusRetrying()
    }
    Box(modifier = Modifier.fillMaxSize()) {
    // Same grammar as the movie page: ambient backdrop, poster, display title.
    BackdropLayer(series.backdrop ?: series.poster)
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Artwork(
                imageUrl = series.poster,
                title = series.name,
                modifier = Modifier
                    .width(150.dp)
                    .height(225.dp)
                    .clip(NuxShape.Card),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = series.name,
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
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
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuxColors.OnSurfaceDim,
                        maxLines = 3,
                    )
                }

                // The primary action, focused on arrival — the same shape the
                // movie page has. Without it this screen was a list of episodes
                // and nothing else, so Continue Watching handed the viewer a
                // page and left them to find their own place in it again.
                if (!eps.isNullOrEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    val target = resumeTarget
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                val season = target?.season ?: eps.first().season
                                val list = eps.filter { it.season == season }
                                val index = target
                                    ?.let { list.indexOf(it).coerceAtLeast(0) } ?: 0
                                vm.playEpisodes(series, list, index)
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
                            Text(
                                if (target != null) {
                                    "Resume S${target.season}E${target.episodeNum}"
                                } else "Play"
                            )
                        }
                        if (target != null) {
                            OutlinedButton(onClick = {
                                val list = eps.filter { it.season == target.season }
                                vm.playEpisodes(
                                    series,
                                    list,
                                    list.indexOf(target).coerceAtLeast(0),
                                    startOver = true,
                                )
                                onPlay()
                            }) { Text("Start over") }
                        }
                        OutlinedButton(onClick = onBack) { Text("Back") }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))

        when {
            // A failed fetch and an empty series used to look identical — a
            // silent "No episodes found" with no way to try again.
            eps == null && episodesFailed -> StatusPane(
                title = "Couldn't load episodes",
                message = "The provider didn't answer. Check the connection and try again.",
                primaryAction = com.nuxcor.nuxtv.ui.components.StatusAction("Retry") {
                    loadAttempt++
                },
            )
            // One calm loading state. The lazy-provider wait is still
            // happening underneath, but "the provider is preparing" read as
            // an error to viewers — the only honest extra information is
            // that a first open can take longer, said quietly.
            eps == null -> StatusPane(
                title = "Loading episodes…",
                message = if (providerPreparing) {
                    "The first open of a series can take a minute."
                } else null,
                loading = true,
            )
            eps.isEmpty() -> StatusPane(
                title = "No episodes found",
                message = "The provider returned none for this series — " +
                    "trying again later can help.",
                primaryAction = com.nuxcor.nuxtv.ui.components.StatusAction("Retry") {
                    episodes = null
                    loadAttempt++
                },
            )
            else -> {
                val seasons = remember(eps) { eps.map { it.season }.distinct().sorted() }
                // Opens on the season the viewer is part-way through, not on
                // Season 1 — otherwise resuming a late season means finding it
                // again by hand every time.
                var selectedSeason by remember(eps, resumeTarget) {
                    mutableStateOf(resumeTarget?.season ?: seasons.first())
                }
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
}
