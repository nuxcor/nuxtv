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
import androidx.compose.ui.layout.ContentScale
import com.agoro.tv.ui.components.Artwork
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import com.agoro.tv.ui.components.ContextMenu
import com.agoro.tv.ui.components.MenuAction
import com.agoro.tv.ui.components.requestFocusRetrying
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
    // Before anything is read: the slot NAMES are the fixture schedule, and
    // the catalogue they live in refreshes twice a day. Opening Sport is the
    // moment their age matters, so it is the moment to ask for a newer one —
    // a no-op when the playlist is already young. See
    // [MainViewModel.refreshFixturesIfStale].
    LaunchedEffect(Unit) { vm.refreshFixturesIfStale() }
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
    Fixtures(
        parsed.orEmpty(), leagueOrder, cue, sport?.clubCrest.orEmpty(), onPlay, onBrowse, vm,
    )
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
    // Same skeleton the real row is: two crests at the leading edge, the two
    // clubs, the status at the trailing edge. It used to lead with the status
    // column, which is where the status was BEFORE the crests took that edge —
    // so the list arrived by shrinking its leading block 96dp to 66dp and
    // growing a status column out of nothing on the right. A skeleton the real
    // list has to jump out of is worse than no skeleton.
    Row(
        modifier = Modifier
            .padding(top = gapAbove)
            .fillMaxWidth()
            .padding(horizontal = Space.m, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.width(CrestColumnWidth),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkeletonBar(width = CrestSize, height = CrestSize, sweep = sweep)
            SkeletonBar(width = CrestSize, height = CrestSize, sweep = sweep)
        }
        Spacer(Modifier.width(Space.m))
        SkeletonBar(width = 128.dp, height = 14.dp, sweep = sweep, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(Space.m + 8.dp))
        SkeletonBar(width = 128.dp, height = 14.dp, sweep = sweep, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(Space.m))
        Box(Modifier.width(StatusColumnWidth), contentAlignment = Alignment.CenterEnd) {
            SkeletonBar(width = 52.dp, height = 12.dp, sweep = sweep)
        }
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
    /** Club name -> crest URL; not every club has one. See Sport.clubCrest. */
    crests: Map<String, String>,
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

    // How old the playlist these slots were read from is. A fixture row whose
    // only claim to being on is a slot saying "LIVE" — no kick-off anywhere in
    // its group — is worth exactly as much as that fetch is fresh, and the
    // parser drops it once it isn't. See SportsParser.upcoming.
    val fetchedAt by vm.catalogueFetchedAtMs.collectAsState()
    val fixtures = remember(parsed, now / 60_000, cue, fetchedAt) {
        SportsParser.upcoming(parsed, now, cue, fetchedAt?.let { now - it } ?: 0L)
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

    // The fixture whose kick-off has not come yet and that the viewer pressed
    // anyway; null when no confirmation is up. Hoisted out of the lazy list so
    // the sheet is not a child of a row that can scroll out from under it.
    var pending by remember { mutableStateOf<SportsEvent?>(null) }
    // The row to hand focus back to, kept by stream id and NEVER cleared: the
    // anchor has to outlive the menu it opened, or dismissing lands focus
    // nowhere and the remote is dead until BACK. Held past the dismissal for
    // the same reason — the requester must still be attached in the frame the
    // sheet leaves.
    var lastPressed by remember { mutableStateOf<Int?>(null) }
    val returnFocus = remember { FocusRequester() }
    // Armed by the dismissal and spent by the effect below, once the frame
    // that removed the sheet has applied.
    val returnPending = remember { booleanArrayOf(false) }
    // Set by the sheet's own action, so the dismissal that follows it knows
    // the player is opening and leaves focus alone.
    val launched = remember { booleanArrayOf(false) }
    LaunchedEffect(pending) {
        if (pending != null || !returnPending[0]) return@LaunchedEffect
        returnPending[0] = false
        // The row it came from, then the top of the list: a fixture list
        // re-sorts on the 30-second tick, and the row that was pressed can
        // have moved off screen while the sheet was up.
        if (!returnFocus.requestFocusRetrying()) firstRowFocus.requestFocusRetrying()
    }

    fun play(event: SportsEvent) {
        vm.playEvent(event.streamId, event.alternates, event.title)
        onPlay()
    }

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
                    // Keyed on the live-ness the lambda actually branches on,
                    // not on the status text it used to be: `now` is captured,
                    // and `fixtures` only rebuilds the event on the minute, so
                    // a match that kicked off seconds ago would have been shown
                    // the "not started" sheet on a press that should just play.
                    val live = event.isLive(now)
                    val press = remember(event, live) {
                        {
                            lastPressed = event.streamId
                            // A PPV slot is a pipe, and before kick-off there
                            // is nothing in it: pressing OK on "in 45 min"
                            // opened a black screen and sat there. Ask rather
                            // than refuse — some packs do run a countdown, and
                            // a row that will not open is its own fault report.
                            if (live) play(event) else pending = event
                        }
                    }
                    FixtureRow(
                        home = event.home,
                        away = event.away,
                        homeCrest = crests[event.home],
                        awayCrest = crests[event.away],
                        status = status,
                        // Fixtures inside a league belong together; the
                        // first one sits straight under its heading.
                        gapAbove = if (lines[index - 1] is FixtureLine.Fixture) Space.xs else 0.dp,
                        focus = when {
                            // The arrival anchor FIRST, and it has to be. The
                            // return anchor is claimed by whichever row was
                            // last pressed and is never released, so putting
                            // it first meant that pressing the top row left
                            // firstRowFocus attached to nothing at all — and
                            // the fallback below, the one thing standing
                            // between a recycled row and a dead remote, could
                            // never land. This way the pressed row keeps the
                            // return anchor unless it is also the first row,
                            // in which case the fallback lands on it anyway.
                            index == firstShown -> Modifier.focusRequester(firstRowFocus)
                            event.streamId == lastPressed ->
                                Modifier.focusRequester(returnFocus)
                            else -> Modifier
                        },
                        onClick = press,
                    )
                }
            }
        }
    }

    // Outside the list, so the sheet does not belong to a row: the fixture
    // order changes on the 30-second tick, and a menu hosted by an item that
    // moves goes with it.
    pending?.let { event ->
        ContextMenu(
            // The clock, not just the fixture — the whole point of the sheet
            // is the thing the row's status column was already saying and the
            // press ignored.
            title = "${event.title} · ${fixtureStatus(event, now, clock) ?: "not started"}",
            actions = listOf(
                MenuAction("Open anyway") { launched[0] = true; play(event) },
            ),
            onDismiss = {
                // Arm the return first, then unmount: the effect above runs in
                // the frame the sheet leaves and finds the flag set. NOT armed
                // when the sheet closed by opening the player — ContextMenu
                // dismisses after its action either way, and pulling focus
                // back to a fixture row on the way out would take it off the
                // player that is opening over this list.
                returnPending[0] = !launched[0]
                launched[0] = false
                pending = null
            },
        )
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
// 620 as before, plus every dp of chrome the crests added: the 86dp badge
// block, the 16dp gap after it, and the 16dp gap that now separates the names
// from the status column — 118. This has to move whenever CrestSize does. It
// did not the first time: the row gained 98dp of chrome against a cap raised
// by 82, so the two name columns had LESS width than before the crests
// existed and the longest pairings the 620 cap was picked for
// ("Wolverhampton Wanderers v Brighton & Hove Albion") ellipsised.
//
// Written out rather than composed from CrestColumnWidth: top-level properties
// initialise in file order and that one is declared below this, so referring
// to it here reads as zero at class-init time and the cap silently becomes 636.
private val FixtureRowWidth = 738.dp

/** The status column, fixed so the clubs line up down the page. */
private val StatusColumnWidth = 96.dp

/**
 * One crest, sized to the row rather than tucked into a corner of it.
 *
 * 30dp was the first cut and on a real panel it read as a favicon: about 3% of
 * the canvas width and visibly shorter than the row it sat in, where the point
 * of a badge is to be recognised across a room without reading anything. 40dp
 * fills the row's height without changing its rhythm.
 */
private val CrestSize = 40.dp

/**
 * The badge block, fixed so the clubs line up down the page exactly as they do
 * against the status column on the other side. Two crests and the gap between.
 */
private val CrestColumnWidth = CrestSize * 2 + 6.dp

/**
 * The two clubs' badges, home then away.
 *
 * [Artwork] and not a bare AsyncImage, because these URLs 404 routinely — the
 * crest index is matched by name against two public repositories and a club
 * that was renamed or promoted out of a covered league simply is not there —
 * and Artwork already owns that: transparent placeholder, no stale pixels
 * carried into a reused row, and a monogram when the image never arrives.
 *
 * The monogram is why a missing crest is a soft failure rather than a hole.
 * MLS has no crest source worth using, so all 32 of its clubs land here, and
 * "Atlanta United" reading as AU beside "Austin FC" as AF still lines up and
 * still tells a viewer which row is which.
 */
@Composable
private fun Crests(
    home: String,
    away: String,
    homeCrest: String?,
    awayCrest: String?,
) {
    Row(
        modifier = Modifier.width(CrestColumnWidth),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Crest(home, homeCrest)
        Crest(away, awayCrest)
    }
}

@Composable
private fun Crest(club: String, url: String?) {
    Artwork(
        imageUrl = url,
        title = club,
        // Fit, not Crop: a crest is a transparent PNG with its own margins and
        // cropping one cuts the badge. Transparent background for the same
        // reason the guide chips use one — a slab behind a round crest draws a
        // square nobody asked for.
        contentScale = ContentScale.Fit,
        background = Color.Transparent,
        monogramStyle = MaterialTheme.typography.labelSmall,
        modifier = Modifier.size(CrestSize),
    )
}

/**
 * One fixture. Takes strings, not the event and the clock: every parameter
 * here is stable and compared by value, so a row whose label did not change
 * this minute is skipped outright.
 */
@Composable
private fun FixtureRow(
    home: String,
    away: String,
    /** Crest URLs, either of which may be absent. See Sport.clubCrest. */
    homeCrest: String?,
    awayCrest: String?,
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
            // The crests lead, the way they do on a broadcaster's own
            // fixture list: two badges are recognised across a room at a
            // glance, where two names have to be read.
            Crests(home, away, homeCrest, awayCrest)
            Spacer(Modifier.width(Space.m))
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
            // Status moved from the left of the row to the right of it, where
            // a broadcaster puts the kick-off time. On the left it sat between
            // the viewer and the fixture — the thing they are actually looking
            // for — and the crests want that edge more than it does.
            Spacer(Modifier.width(Space.m))
            Box(Modifier.width(StatusColumnWidth), contentAlignment = Alignment.CenterEnd) {
                if (status == null) LiveBadge() else {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelLarge,
                        color = NuxColors.OnSurfaceDim,
                        maxLines = 1,
                    )
                }
            }
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
