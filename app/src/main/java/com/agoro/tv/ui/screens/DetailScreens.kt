@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.agoro.tv.ui.screens

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
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.agoro.tv.MainViewModel
import com.agoro.tv.data.ContentState
import com.agoro.tv.data.Episode
import com.agoro.tv.data.EpisodeTitle
import com.agoro.tv.data.Movie
import com.agoro.tv.data.PlotText
import com.agoro.tv.data.Series
import com.agoro.tv.ui.components.Artwork
import com.agoro.tv.ui.components.BackdropLayer
import com.agoro.tv.ui.components.StatusAction
import com.agoro.tv.ui.components.StatusPane
import com.agoro.tv.ui.components.ContextMenu
import com.agoro.tv.ui.components.MenuAction
import com.agoro.tv.ui.components.MetaChip
import com.agoro.tv.ui.components.RatingStars
import com.agoro.tv.ui.components.EpisodeRow
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.Space
import com.agoro.tv.ui.theme.NuxShape
import com.agoro.tv.ui.components.requestFocusRetrying

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
                    movie.durationText?.let(::prettyDuration),
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
                // No on-screen Back: every remote has the key, BACK already
                // pops this screen, and a button for it sat between Play and
                // everything else as a focus stop that does nothing new.
            }

            // Read through PlotText here as well as at the parse, because a
            // catalogue cached before that existed still holds both
            // languages and will until the next refresh. Running it twice is
            // a no-op — it only ever picks one of the halves already there.
            val moviePlot = remember(movie.plot) { PlotText.preferred(movie.plot) }
            if (!moviePlot.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = moviePlot,
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuxColors.OnSurfaceDim,
                )
            }
            if (!movie.cast.isNullOrBlank() || !movie.director.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                movie.cast?.takeIf { it.isNotBlank() }?.let { CreditLine("Starring", it) }
                movie.director?.takeIf { it.isNotBlank() }?.let { CreditLine("Director", it) }
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
 * Provider runtimes arrive as "02:01:00" or bare minutes; a chip should read
 * "2h 1m", the way every streaming service says it. Anything unparseable
 * passes through untouched.
 */
private fun prettyDuration(raw: String): String? {
    // Null, not the raw text, for anything that isn't a length: panels send
    // "00:00:00" for "unknown", and a chip reading 00:00:00 is a chip that
    // says the app doesn't know what it is showing.
    val parts = raw.trim().split(':').map { it.toIntOrNull() ?: return null }
    val minutes = when (parts.size) {
        3 -> parts[0] * 60 + parts[1] + if (parts[2] >= 30) 1 else 0
        2 -> parts[0] + if (parts[1] >= 30) 1 else 0
        1 -> parts[0]
        else -> return null
    }
    if (minutes <= 0) return null
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

/** "Starring  A, B, C" — bright label, dim names, one line. */
@Composable
private fun CreditLine(label: String, names: String) {
    Spacer(Modifier.height(4.dp))
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = NuxColors.OnSurface)) { append("$label  ") }
            append(names)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = NuxColors.OnSurfaceDim,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
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

/**
 * A show, its episodes, and one D-pad path from the top of the page to the
 * bottom of the season.
 *
 * The page is ONE scrolling column. It used to be two regions — a header
 * pinned above a list that scrolled inside whatever was left — and on a
 * 540dp canvas whatever was left came to 135dp: a single episode row and a
 * sliver of the next. The header could not be scrolled away because nothing
 * in it was focusable below the Play button, so a viewer browsing a
 * forty-episode season did it through a window a row and a half tall while
 * two thirds of the screen held a synopsis they had already read.
 *
 * Now the hero is the list's first item. It is on screen when the page opens,
 * with Play focused, and it scrolls off as the viewer walks down into the
 * episodes — which then have the whole panel. This is the grammar every
 * streaming app on this platform uses, and it is the only one that lets a
 * page carry both a proper hero and a usable list.
 *
 * The one thing that went with the split: the list no longer opens scrolled
 * to the episode the button offers. It cannot — scrolling a single column to
 * episode 40 would carry the focused Play button off the screen and unmount
 * it mid-frame, which is a page that arrives with focus nowhere. The button
 * still NAMES that episode and still plays it, which was always the answer to
 * "find my place"; the list is for browsing, and browsing starts at the top.
 */
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
        MissingItemPane("Show", contentState, onBack)
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
    val watchedAt by vm.watchedAt.collectAsState()
    var menuEpisode by remember { mutableStateOf<Pair<Episode, Int>?>(null) }
    // The row the menu was opened on keeps a requester so focus can come back
    // to it. This was the one ContextMenu in the app without the return: left
    // to Compose, a dismissed menu reseats focus on the nearest node — here
    // the Resume button at the top of the page, which on a forty-episode
    // season is a long way from the row the viewer was standing on.
    //
    // Armed by the dismissal (a plain holder: nothing in composition reads it)
    // and run by the effect below in the frame the menu unmounts, before the
    // next key event can arrive. It cannot be asked any earlier: the dialog
    // scaffold cancels any focus exit while it stands.
    val menuOriginFocus = remember { FocusRequester() }
    // The anchor is its OWN state, and that is the whole trick: derived from
    // menuEpisode it would go null in the very composition that unmounts the
    // menu, detaching the requester a frame before the effect below asks it
    // for anything — requestFocus on a requester with no node fails every
    // retry, and focus stays where Compose put it. Which is the bug this
    // exists to fix. Search and the browse grid keep theirs the same way.
    var menuOriginId by remember { mutableStateOf<String?>(null) }
    val returnFocusPending = remember { booleanArrayOf(false) }
    LaunchedEffect(menuEpisode) {
        if (menuEpisode != null || !returnFocusPending[0]) return@LaunchedEffect
        returnFocusPending[0] = false
        menuOriginFocus.requestFocusRetrying(retries = 5, intervalMs = 60)
        // Released only once focus has landed; a detached requester is fine
        // from here, and leaving it attached would keep a live branch on a row
        // no menu is open on.
        menuOriginId = null
    }

    val eps = episodes

    /** Where the viewer is in this show; the whole rule lives in [upNext]. */
    val target = remember(eps, resumePositions, watchedAt) {
        upNext(eps.orEmpty(), resumePositions, watchedAt)
    }
    val nextUp = target?.episode
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(seriesId, eps != null) {
        if (eps.isNullOrEmpty()) return@LaunchedEffect
        playFocus.requestFocusRetrying()
    }

    val seasons = remember(eps) { eps.orEmpty().map { it.season }.distinct().sorted() }
    // Opens on the season the next episode is in — the one being resumed, or
    // the one after the last one finished. Season 1 was where a viewer four
    // seasons deep landed every time.
    var selectedSeason by remember(eps, nextUp) {
        mutableStateOf(nextUp?.season ?: seasons.firstOrNull() ?: 1)
    }
    val seasonEpisodes = remember(eps, selectedSeason) {
        eps.orEmpty().filter { it.season == selectedSeason }
    }

    fun playFrom(episode: Episode, startOver: Boolean) {
        val list = eps.orEmpty().filter { it.season == episode.season }
        vm.playEpisodes(
            series, list, list.indexOf(episode).coerceAtLeast(0),
            startOver = startOver,
        )
        onPlay()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Fixed behind the column, not scrolled with it: the art is ambient
        // rather than a hero image, and a backdrop that slid up the screen
        // with the rows would drag the eye down the page every time the
        // viewer moved one row.
        BackdropLayer(series.backdrop ?: series.poster)

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            // Coming back from an episode returns to the row it was played
            // from, as on the browse grid.
            modifier = Modifier.fillMaxSize().focusRestorer(),
        ) {
            item(key = "hero") {
                SeriesHero(
                    series = series,
                    episodeCount = eps?.size,
                    target = target,
                    playFocus = playFocus,
                    onPlay = { startOver -> target?.let { playFrom(it.episode, startOver) } },
                )
            }

            when {
                // A failed fetch and an empty series used to look identical —
                // a silent "No episodes found" with no way to try again.
                eps == null && episodesFailed -> item(key = "failed") {
                    EpisodeStatus(
                        title = "Couldn't load episodes",
                        message = "The provider didn't answer. Check the connection and try again.",
                        action = StatusAction("Retry") { loadAttempt++ },
                    )
                }
                // One calm loading state. The lazy-provider wait is still
                // happening underneath, but "the provider is preparing" read
                // as an error to viewers — the only honest extra information
                // is that a first open can take longer, said quietly.
                eps == null -> item(key = "loading") {
                    EpisodeStatus(
                        title = "Loading episodes…",
                        message = if (providerPreparing) {
                            "The first open of a series can take a minute."
                        } else null,
                        loading = true,
                    )
                }
                eps.isEmpty() -> item(key = "empty") {
                    EpisodeStatus(
                        title = "No episodes found",
                        message = "The provider returned none for this series — " +
                            "trying again later can help.",
                        action = StatusAction("Retry") {
                            episodes = null
                            loadAttempt++
                        },
                    )
                }
                else -> {
                    item(key = "seasons") {
                        SeasonBar(
                            seasons = seasons,
                            selected = selectedSeason,
                            episodeCount = seasonEpisodes.size,
                            onSelect = { selectedSeason = it },
                        )
                    }
                    itemsIndexed(seasonEpisodes, key = { _, e -> e.id }) { index, episode ->
                        val watchedTo = resumePositions[episode.url] ?: 0L
                        val seen = episode.url in watchedAt
                        EpisodeRow(
                            title = EpisodeTitle.numbered(episode.title, episode.episodeNum),
                            // The EPISODE's own still, or nothing. Falling
                            // back to the series art painted the same picture
                            // down all thirty rows, which reads as a
                            // rendering fault; the monogram at least differs.
                            imageUrl = episode.poster,
                            meta = when {
                                watchedTo > 0 -> "Resume from ${formatOffset(watchedTo)}"
                                // The season is the bar above; repeating it
                                // under every row said nothing.
                                else -> episode.durationText?.let(::prettyDuration)
                            },
                            // The series synopsis on every episode row is the
                            // same paragraph N times. Nothing, rather than
                            // that: the row is built to close up around it.
                            synopsis = remember(episode.plot) {
                                PlotText.preferred(episode.plot)
                            },
                            progress = resumeProgress[episode.url],
                            watched = seen && watchedTo == 0L,
                            modifier = if (episode.id == menuOriginId) {
                                Modifier.focusRequester(menuOriginFocus)
                            } else Modifier,
                            onClick = {
                                vm.playEpisodes(series, seasonEpisodes, index)
                                onPlay()
                            },
                            // Reachable for anything with a place to forget —
                            // part-watched or finished. Before, a finished
                            // episode had no menu because it had no position,
                            // which is now the state most worth clearing.
                            onLongClick = if (watchedTo > 0 || seen) {
                                {
                                    menuOriginId = episode.id
                                    menuEpisode = episode to index
                                }
                            } else null,
                        )
                    }
                }
            }
        }

        menuEpisode?.let { (episode, index) ->
            val partWatched = (resumePositions[episode.url] ?: 0L) > 0L
            ContextMenu(
                title = EpisodeTitle.numbered(episode.title, episode.episodeNum),
                actions = buildList {
                    if (partWatched) {
                        add(
                            MenuAction("Resume") {
                                vm.playEpisodes(series, seasonEpisodes, index)
                                onPlay()
                            }
                        )
                    }
                    add(
                        MenuAction(if (partWatched) "Start over" else "Play") {
                            vm.playEpisodes(series, seasonEpisodes, index, startOver = true)
                            onPlay()
                        }
                    )
                    // The way out of a wrong mark — an episode left running
                    // to the end in another room, a show the viewer wants
                    // back at the start. Nothing else could undo a watch mark.
                    add(
                        MenuAction("Mark as unwatched") {
                            vm.forgetResume(episode.url)
                        }
                    )
                },
                onDismiss = {
                    // Arm the return first, then unmount: the effect above
                    // runs in the frame the menu leaves and finds the flag set.
                    returnFocusPending[0] = true
                    menuEpisode = null
                },
            )
        }
    }
}

/**
 * The show above its episodes: poster, name, the facts, and the one button
 * that matters.
 *
 * Sized to be scrolled past. Everything here is worth reading once and
 * nothing is worth 70% of the panel a second time, so the synopsis stops at
 * three lines and the poster is the size it needs to be recognised rather
 * than the size it would be if it were the subject.
 */
@Composable
private fun SeriesHero(
    series: Series,
    episodeCount: Int?,
    target: UpNext?,
    playFocus: FocusRequester,
    onPlay: (startOver: Boolean) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Artwork(
            imageUrl = series.poster,
            title = series.name,
            modifier = Modifier
                .width(130.dp)
                .height(195.dp)
                .clip(NuxShape.Card),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = series.name,
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                color = NuxColors.OnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOfNotNull(
                    series.year?.toString(),
                    episodeCount?.let { "$it episodes" },
                    series.genre,
                ).forEachIndexed { i, chip -> MetaChip(chip, accent = i == 0) }
            }
            series.rating?.let { rating ->
                Spacer(Modifier.height(6.dp))
                RatingStars(rating = rating, voteCount = series.voteCount)
            }
            // See the movie page: also applied at the parse, and repeated
            // here for the catalogues cached before it was.
            val plot = remember(series.plot) { PlotText.preferred(series.plot) }
            if (!plot.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = plot,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuxColors.OnSurfaceDim,
                    // Three. It was five, for a header that could not be
                    // scrolled — whatever the ellipsis cut was unreachable,
                    // so the page had to show the lot. The page scrolls now,
                    // and a synopsis is a hook rather than the article: three
                    // lines is what a TMDB overview reads as, and the two it
                    // gives back are an episode row.
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    // Stopped short of the trailing edge. The backdrop is
                    // drawn across the last 70% of the screen and its scrim
                    // only takes it down to 72% at the far side, so a line of
                    // prose run the whole width finishes on top of the art —
                    // which is where "Starring" was sitting, over a face.
                    modifier = Modifier.widthIn(max = 620.dp),
                )
            }
            // Both, when the provider sends both. `cast ?: director` gave a
            // show with a cast list no director at all, and the page scrolls
            // now, so the line the second credit costs is no longer a line
            // taken off the episode list.
            series.cast?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                CreditLine("Starring", it)
            }
            series.director?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                CreditLine("Director", it)
            }

            // The primary action — the same shape the movie page has. Without
            // it this screen was a list of episodes and nothing else, so
            // Continue Watching handed the viewer a page and left them to
            // find their own place in it again.
            if (target != null) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { onPlay(false) },
                        modifier = Modifier.focusRequester(playFocus),
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        // Names the episode. "Play" alone was the whole
                        // defect from where the viewer sits: it read as
                        // "carry on" and started the show again.
                        Text(upNextLabel(target))
                    }
                    // Only for a part-watched episode: "start over" on one
                    // that was never started says nothing.
                    if (target.resuming) {
                        OutlinedButton(onClick = { onPlay(true) }) {
                            Text("Start episode over")
                        }
                    }
                    // See the movie screen: BACK is a hardware key.
                }
            }
        }
    }
}

/**
 * "Episodes" and the season chips, on one line.
 *
 * The count belongs to the SEASON, not the show — the show's total is a chip
 * in the hero, and a viewer standing on Season 3 is asking how long Season 3
 * is. A show with one season gets the heading and no chips: a strip offering
 * a single choice is a control that cannot be operated.
 */
@Composable
private fun SeasonBar(
    seasons: List<Int>,
    selected: Int,
    episodeCount: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Episodes",
            style = MaterialTheme.typography.titleLarge,
            color = NuxColors.OnSurface,
        )
        Text(
            text = "$episodeCount",
            style = MaterialTheme.typography.labelLarge,
            color = NuxColors.OnSurfaceDim,
        )
        if (seasons.size > 1) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                // The strip a viewer walks back up to must still be on the
                // season they were reading. Every other chip strip in the app
                // restores; this one was the exception.
                modifier = Modifier.weight(1f).focusRestorer(),
            ) {
                itemsIndexed(seasons) { _, season ->
                    CategoryItem(
                        name = "Season $season",
                        selected = season == selected,
                        onClick = { onSelect(season) },
                        modifier = Modifier,
                    )
                }
            }
        }
    }
}

/**
 * A status pane sized to sit in the scrolling column rather than own the
 * screen. [StatusPane] defaults to filling its parent, and a lazy item is
 * measured with no height to fill.
 */
@Composable
private fun EpisodeStatus(
    title: String,
    message: String? = null,
    loading: Boolean = false,
    action: StatusAction? = null,
) {
    StatusPane(
        title = title,
        message = message,
        loading = loading,
        primaryAction = action,
        modifier = Modifier.fillMaxWidth().height(200.dp),
    )
}
