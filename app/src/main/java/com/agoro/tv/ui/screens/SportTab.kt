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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    // Remembered so the produceState keys below stay the same instances
    // between recompositions instead of fresh collections to compare.
    val ambiguous = remember(sport) { sport?.ambiguous.orEmpty().toSet() }
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

    // Parsed OFF the main thread, and once — not on every minute tick.
    //
    // This reads six thousand PPV slots, several regexes each, against every
    // club of every league. In composition on the main thread that froze the
    // app the moment the tab was opened on a streaming stick, and then froze
    // it again every sixty seconds. It is time-independent work, so it belongs
    // behind the catalogue, not behind the clock.
    val parsed by produceState<List<SportsEvent>?>(null, bundle.events, leagues, ambiguous) {
        // Cleared first: produceState keeps its last value across a key
        // change, so a re-parse would otherwise go on rendering the previous
        // catalogue's fixtures until the new ones landed.
        value = null
        value = withContext(Dispatchers.Default) {
            SportsParser.parseAll(
                bundle.events.mapNotNull { ch -> ch.xtreamId?.let { it to ch.name } },
                System.currentTimeMillis(), leagues, ambiguous,
            )
        }
    }

    // Null means the first parse has not landed. Saying "nothing on right now"
    // and then replacing it a second later reads as a fault, so say nothing.
    if (parsed == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    // The clock lives in here, not up there. Ticking in this composable would
    // recompose the whole of SportTab every thirty seconds — the manifest
    // collect, the produceState scope and every key it compares — so that a
    // label could say "in 5 min". Only the part that reads the clock should
    // answer to it.
    Fixtures(parsed.orEmpty(), leagueOrder, cue, onPlay, onBrowse, vm)
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
    // fixture list.
    val byLeague = remember(fixtures, leagueOrder) {
        leagueOrder.mapNotNull { league ->
            fixtures.filter { it.league == league }.takeIf { it.isNotEmpty() }?.let { league to it }
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
    val firstShown = byLeague.firstOrNull()?.second?.firstOrNull()
    val clock = rememberClockFormat()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Leagues need air between them; fixtures inside one belong together.
        verticalArrangement = Arrangement.spacedBy(Space.l),
        contentPadding = PaddingValues(bottom = Space.xl),
    ) {
        items(byLeague, key = { it.first }) { (league, list) ->
            Column {
                SectionTitle(league)
                // The cap lives on the container, not the rows. Put on the
                // row itself it was ignored — the TV Surface fills whatever it
                // is given — so the width has to be gone before it gets there.
                Column(
                    modifier = Modifier.widthIn(max = FixtureRowWidth),
                    verticalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    list.forEach { event ->
                        FixtureRow(
                            event, now, clock,
                            // The first row ON SCREEN — fixtures.first() is
                            // the earliest kick-off, which can sit in the
                            // last league and drag the list to the bottom.
                            modifier = if (event === firstShown) {
                                Modifier.focusRequester(firstRowFocus)
                            } else Modifier,
                        ) {
                            vm.playEvent(event.streamId, event.alternates)
                            onPlay()
                        }
                    }
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

@Composable
private fun FixtureRow(
    event: SportsEvent,
    nowMs: Long,
    clock: SimpleDateFormat,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
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
                // isLive(nowMs), not the flag stamped at parse time: parsing
                // happens once per catalogue and a match that kicks off after
                // it would otherwise never light up.
                if (event.isLive(nowMs)) LiveBadge() else {
                    Text(
                        text = statusOf(event, nowMs, clock),
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
                text = event.home,
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
                text = event.away,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
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
 * "LIVE" for something already running; otherwise how long until kick-off,
 * because a clock time on its own makes the viewer do the arithmetic.
 */
private fun statusOf(event: SportsEvent, nowMs: Long, clock: SimpleDateFormat): String {
    val start = event.startMs ?: return "LIVE"
    if (start <= nowMs) return "LIVE"
    val minutes = ((start - nowMs) / 60_000).toInt()
    return when {
        minutes <= 1 -> "Starts now"
        minutes < 60 -> "in $minutes min"
        // The app's clock format, so a 12-hour viewer doesn't read "20:00"
        // here beside "8:00 PM" in the guide.
        else -> clock.format(Date(start))
    }
}
