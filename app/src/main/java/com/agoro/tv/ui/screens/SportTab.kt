@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.agoro.tv.ui.screens

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.State
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.agoro.tv.MainViewModel
import com.agoro.tv.data.ContentBundle
import com.agoro.tv.data.SportsEvent
import com.agoro.tv.data.SportsParser
import com.agoro.tv.ui.components.SectionTitle
import com.agoro.tv.ui.components.StatusPane
import com.agoro.tv.ui.components.StatusAction
import com.agoro.tv.ui.components.rememberClockFormat
import com.agoro.tv.ui.components.rememberInitialFocus
import androidx.compose.ui.focus.focusRequester
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxFocus
import com.agoro.tv.ui.theme.NuxShape
import com.agoro.tv.ui.theme.Space
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live sport, read out of the PPV slots.
 *
 * A destination rather than a Home row, which is what makes showing the next
 * hour worthwhile: a row must hide when it is empty, and at 3am there is
 * nothing on. A destination can say what is coming instead of vanishing.
 */
@Composable
fun SportTab(
    vm: MainViewModel,
    bundle: ContentBundle,
    onPlay: () -> Unit,
    /** Where the empty states send the viewer instead of leaving them stranded. */
    onBrowse: (HomeTab) -> Unit = {},
) {
    val sport by vm.sport.collectAsState()
    val leagues = sport?.leagues.orEmpty()
    val cue = sport?.cueMinutes ?: 60
    val leagueOrder = remember(leagues) { leagues.keys.toList() }

    // Before the parse, not after it. With no leagues there is nothing to look
    // for, and walking eight thousand slots to discover that is work spent to
    // render a status pane.
    if (leagues.isEmpty()) {
        // With an action: the pane owns the whole screen now that the rail
        // is a drawer, and a pane with nothing focusable leaves the remote
        // dead until BACK.
        StatusPane(
            title = "Sport isn't set up",
            message = "This playlist carries no fixture listings.",
            icon = Icons.Default.SportsSoccer,
            primaryAction = StatusAction("Browse Live TV") { onBrowse(HomeTab.Live) },
        )
        return
    }

    // Parsed OFF the main thread, once per playlist, and KEPT — see
    // [MainViewModel.sportFixtures]. As a produceState here the parse was
    // thrown away every time the tab left composition, so every visit
    // re-read six thousand slots, several regexes each, against every club
    // of every league; and it was keyed on the events list, so a republished
    // bundle re-ran it even when the slots had not changed.
    val parsed by vm.sportFixtures.collectAsState()

    // Null means the first parse has not landed. Saying "nothing on right now"
    // and then replacing it a second later reads as a fault — but so did the
    // blank screen that replaced it: the parse walks six thousand slots, which
    // on the box is a second or two of a tab that looks broken or empty, and a
    // viewer who presses again gets nothing for their trouble.
    //
    // A skeleton of the list that is coming says the same "not yet" without
    // claiming anything about what is on. It is shaped like the fixture list
    // on purpose — a league heading, then rows at the same width and rhythm —
    // so the real list lands INTO its outline rather than replacing a spinner
    // that was somewhere else on the screen.
    if (parsed == null) {
        FixturesSkeleton()
        return
    }
    // The clock lives in here, not up there. Ticking in this composable would
    // recompose the whole of SportTab every thirty seconds — the manifest
    // collect and the fixtures collect — so that a label could say "in 5
    // min". Only the part that reads the clock should answer to it.
    Fixtures(parsed.orEmpty(), leagueOrder, cue, onPlay, onBrowse, vm)
}

/**
 * The fixture list before it exists: two leagues' worth of outline, breathing.
 *
 * Deliberately NOT a spinner. A spinner in the middle of an empty tab says
 * "something is happening somewhere"; this says "a list of fixtures is coming,
 * and it starts here" — and when the real one arrives it lands on the same
 * baseline at the same width, so nothing jumps.
 */
@Composable
private fun FixturesSkeleton() {
    val motion = rememberInfiniteTransition(label = "fixtureSkeleton")
    // Held as State and read inside the draw lambdas below, never in
    // composition. Read with `by` up here every frame of the sweep would
    // recompose this whole column — the same trap TuneCard's sweep documents.
    val sweep = motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(1_500, easing = LinearEasing),
            RepeatMode.Restart,
        ),
        label = "sweep",
    )
    Column(
        modifier = Modifier.fillMaxSize().widthIn(max = FixtureRowWidth),
    ) {
        repeat(2) { block ->
            SkeletonBar(
                width = 150.dp,
                height = 18.dp,
                sweep = sweep,
                modifier = Modifier.padding(top = if (block == 0) 0.dp else Space.l),
            )
            Spacer(Modifier.height(Space.m))
            // Four then three, because a block of equal rows reads as a
            // pattern rather than as a list waiting to happen.
            repeat(if (block == 0) 4 else 3) { row ->
                SkeletonFixtureRow(sweep, gapAbove = if (row == 0) 0.dp else Space.xs)
            }
        }
    }
}

/** One outlined fixture: the status column, then the two clubs about the "v". */
@Composable
private fun SkeletonFixtureRow(sweep: State<Float>, gapAbove: Dp) {
    Row(
        modifier = Modifier
            .padding(top = gapAbove)
            .fillMaxWidth()
            .padding(horizontal = Space.m, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(StatusColumnWidth), contentAlignment = Alignment.CenterStart) {
            SkeletonBar(width = 52.dp, height = 12.dp, sweep = sweep)
        }
        SkeletonBar(width = 128.dp, height = 14.dp, sweep = sweep, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(Space.m + 8.dp))
        SkeletonBar(width = 128.dp, height = 14.dp, sweep = sweep, modifier = Modifier.weight(1f))
    }
}

/**
 * A rounded placeholder with one band of light travelling across it.
 *
 * Drawn, not composed: the animated value is read inside [drawBehind], so a
 * sweep costs a draw per frame and nothing above it recomposes at all.
 */
@Composable
private fun SkeletonBar(
    width: Dp,
    height: Dp,
    sweep: State<Float>,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .widthIn(max = width)
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .drawBehind {
                drawRect(NuxColors.SurfaceRaised)
                // The band starts fully off the left edge and leaves fully to
                // the right, so the loop point never shows as a jump.
                val band = size.width * 0.5f
                val x = -band + (size.width + band * 2f) * sweep.value
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.5f to NuxColors.OnSurface.copy(alpha = 0.10f),
                        1f to Color.Transparent,
                        startX = x,
                        endX = x + band,
                    ),
                )
            },
    )
}

/** One line of the fixture list: a league heading or a fixture under it. */
private sealed interface FixtureLine {
    data class Header(val league: String) : FixtureLine
    data class Fixture(val league: String, val event: SportsEvent) : FixtureLine
}

@Composable
private fun Fixtures(
    parsed: List<SportsEvent>,
    leagueOrder: List<String>,
    cue: Int,
    onPlay: () -> Unit,
    onBrowse: (HomeTab) -> Unit,
    vm: MainViewModel,
) {
    // A fixture list is a clock face: a match kicks off, another ends, and the
    // screen is wrong until something recomposes it. Half a minute keeps
    // "starts in 5 min" honest and costs nothing here.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    val fixtures = remember(parsed, now / 60_000, cue) {
        SportsParser.upcoming(parsed, now, cue)
    }
    if (fixtures.isEmpty()) {
        StatusPane(
            title = "Nothing on right now",
            message = when {
                cue % 60 == 0 && cue / 60 == 1 -> "Fixtures appear here an hour before kick-off."
                cue % 60 == 0 -> "Fixtures appear here ${cue / 60} hours before kick-off."
                else -> "Fixtures appear here $cue minutes before kick-off."
            },
            icon = Icons.Default.SportsSoccer,
            primaryAction = StatusAction("Browse Live TV") { onBrowse(HomeTab.Live) },
        )
        return
    }

    // Grouped by league, in the manifest's own order, so the sports a viewer
    // follows sit where they were last time rather than moving with the
    // fixture list — and FLATTENED, one lazy item per line. Each league used
    // to be a single item holding every one of its rows in a Column, so a
    // PPV league carrying 150 fixtures composed 150 Surfaces the moment it
    // scrolled into view. A lazy list can only be lazy about what it is
    // handed one at a time.
    val lines = remember(fixtures, leagueOrder) {
        buildList {
            for (league in leagueOrder) {
                val inLeague = fixtures.filter { it.league == league }
                if (inLeague.isEmpty()) continue
                add(FixtureLine.Header(league))
                inLeague.forEach { add(FixtureLine.Fixture(league, it)) }
            }
        }
    }

    // The list lands seconds after the tab opens — the parse runs behind the
    // catalogue — which is after the shell has given up trying to park focus
    // here. Claim it ourselves when there is something to claim, or the tab
    // opened with no focus anywhere and the first press was spent finding it.
    // Keyed on nothing: this composable only exists once there are fixtures,
    // and keying on the first fixture would re-seat focus every time the
    // order changed under a 30-second tick.
    val firstRowFocus = rememberInitialFocus(Unit)
    // The first row ON SCREEN — fixtures.first() is the earliest kick-off,
    // which can sit in the last league and drag the list to the bottom.
    val firstShown = lines.indexOfFirst { it is FixtureLine.Fixture }
    val clock = rememberClockFormat()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Leagues need air between them; fixtures inside one belong together.
        // Headings carry the league gap above them, rows the tight one.
        contentPadding = PaddingValues(bottom = Space.xl),
    ) {
        itemsIndexed(
            lines,
            key = { _, line ->
                when (line) {
                    is FixtureLine.Header -> "h:${line.league}"
                    is FixtureLine.Fixture -> "f:${line.league}:${line.event.streamId}"
                }
            },
            contentType = { _, line -> line is FixtureLine.Header },
        ) { index, line ->
            when (line) {
                is FixtureLine.Header -> SectionTitle(
                    line.league,
                    modifier = Modifier.padding(top = if (index == 0) 0.dp else Space.l),
                )
                is FixtureLine.Fixture -> {
                    val event = line.event
                    // Per row, from the tick: only the rows whose label
                    // actually changed this minute recompose. Passing the
                    // clock itself to every row made every row answer to it.
                    val status = fixtureStatus(event, now, clock)
                    // Remembered on the event by VALUE: upcoming() hands back
                    // fresh copies each minute, and a lambda capturing a new
                    // instance is a new lambda, which is a changed parameter,
                    // which is a recomposed row.
                    val play = remember(event) {
                        {
                            vm.playEvent(event.streamId, event.alternates)
                            onPlay()
                        }
                    }
                    FixtureRow(
                        home = event.home,
                        away = event.away,
                        status = status,
                        // Fixtures inside a league belong together; the
                        // first one sits straight under its heading.
                        gapAbove = if (lines[index - 1] is FixtureLine.Fixture) Space.xs else 0.dp,
                        focus = if (index == firstShown) {
                            Modifier.focusRequester(firstRowFocus)
                        } else Modifier,
                        onClick = play,
                    )
                }
            }
        }
    }
}

/**
 * Wide enough for the longest club pairing and no wider.
 *
 * Full-bleed was the first cut and it read as a fault: a fixture is a short
 * line, and stretched across a 4K panel it left most of a metre of empty row
 * between the clubs and their status.
 *
 * 620dp, the same cap the browse rows use. The canvas here is about 960dp
 * wide whatever the panel's pixels are, so an earlier 900 was 94% of the
 * screen and looked like no cap at all.
 */
private val FixtureRowWidth = 620.dp

/** The status column, fixed so the clubs line up down the page. */
private val StatusColumnWidth = 96.dp

/**
 * One fixture. Takes strings, not the event and the clock: every parameter
 * here is stable and compared by value, so a row whose label did not change
 * this minute is skipped outright.
 */
@Composable
private fun FixtureRow(
    home: String,
    away: String,
    /** The label to print, or null for the LIVE badge. */
    status: String?,
    gapAbove: androidx.compose.ui.unit.Dp,
    /** Goes on the Surface — the node that takes focus — so a requester lands. */
    focus: Modifier,
    onClick: () -> Unit,
) {
    // The cap lives on a wrapper, not the Surface. Put on the Surface itself
    // it was ignored — the TV Surface fills whatever it is given — so the
    // width has to be gone before it gets there.
    Box(modifier = Modifier.padding(top = gapAbove).widthIn(max = FixtureRowWidth)) {
    Surface(
        onClick = onClick,
        modifier = focus.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(NuxShape.Row),
        colors = ClickableSurfaceDefaults.colors(
            // Unfocused rows sit flat on the background so the focused one is
            // the only lifted thing on screen — the same rule the shelves use.
            containerColor = Color.Transparent,
            focusedContainerColor = NuxColors.SurfaceRaised,
            contentColor = NuxColors.OnSurface,
            focusedContentColor = NuxColors.OnSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = NuxFocus.RowScale),
        border = ClickableSurfaceDefaults.border(focusedBorder = NuxFocus.ring12),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.m, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(StatusColumnWidth), contentAlignment = Alignment.CenterStart) {
                if (status == null) LiveBadge() else {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelLarge,
                        color = NuxColors.OnSurfaceDim,
                        maxLines = 1,
                    )
                }
            }
            // Home right, away left, the "v" between them: the clubs sit on a
            // common axis so a column of fixtures reads down rather than
            // ragged.
            Text(
                text = home,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "v",
                style = MaterialTheme.typography.labelLarge,
                color = NuxColors.OnSurfaceDim,
                modifier = Modifier.padding(horizontal = Space.m),
            )
            Text(
                text = away,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
    }
}

/** A gold dot and the word, which is all "on now" needs to say. */
@Composable
private fun LiveBadge() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(NuxColors.Primary)
        )
        Spacer(Modifier.width(Space.s))
        Text(
            text = "LIVE",
            style = MaterialTheme.typography.labelLarge,
            color = NuxColors.Primary,
        )
    }
}

/**
 * Null — the LIVE badge — for something already running; otherwise how long
 * until kick-off, because a clock time on its own makes the viewer do the
 * arithmetic. isLive(nowMs), not the flag stamped at parse time: parsing
 * happens once per catalogue and a match that kicks off after it would
 * otherwise never light up.
 */
private fun fixtureStatus(event: SportsEvent, nowMs: Long, clock: SimpleDateFormat): String? {
    if (event.isLive(nowMs)) return null
    val start = event.startMs ?: return null
    val minutes = ((start - nowMs) / 60_000).toInt()
    return when {
        minutes <= 1 -> "Starts now"
        minutes < 60 -> "in $minutes min"
        // The app's clock format, so a 12-hour viewer doesn't read "20:00"
        // here beside "8:00 PM" in the guide.
        else -> clock.format(Date(start))
    }
}
