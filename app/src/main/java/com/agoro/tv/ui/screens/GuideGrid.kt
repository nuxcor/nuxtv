@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.agoro.tv.ui.screens

import androidx.tv.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.Icons
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.agoro.tv.data.EpgProgram
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.ui.components.Artwork
import com.agoro.tv.ui.components.requestFocusRetrying
import com.agoro.tv.ui.components.rememberClockFormat
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxShape
import com.agoro.tv.ui.theme.Space
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The grid itself — channel column, programme lanes, NOW marker — shared
 * between the Live TV tab and the player's guide overlay. The two hosts differ
 * in everything around the grid (header, category row, scrim, what a click
 * does) but the grid is the same surface, and it forked once before: the
 * mini-guide grew its own copy of the category vocabulary until
 * LiveCategories.kt unified it. This file is the same move for the timeline.
 */

/**
 * How many half-hour columns the timeline aims to show at once, the "on now"
 * one included. Two and a half hours of schedule is the point of a grid guide:
 * fewer and you are paging to answer "what's on after this", more and the cells
 * are too narrow to carry a title.
 */
private const val TARGET_COLUMNS = 5

/**
 * The scale is derived from the panel's own width rather than fixed, because
 * the same constant lands differently on every one of them: a 960dp
 * canvas has 572dp of lane once the TV-safe gutters, the navigation rail and
 * the channel column are paid for, while a 1280dp one has 892dp. A fixed 4dp
 * per minute showed 4.8 columns on the first and 7.4 on the second.
 *
 * Clamped at both ends: below the minimum a half-hour cell can't hold a title,
 * and above the maximum a wide panel would show two programmes and a lot of
 * empty rounding.
 */
private val MIN_DP_PER_MINUTE = 2.6.dp
private val MAX_DP_PER_MINUTE = 6.dp

// 330, not 280: the name is what a viewer reads, and at 280 it still only
// got 160dp once the number, logo, gaps and padding were paid — enough for
// "Sky Sports…" but not for "Sky Sports Darts". The parts around it are
// slimmed below as well, so the name now has ~230dp and the timeline gives
// up less than the column gains.
internal val CHANNEL_COLUMN_WIDTH = 330.dp
internal val CHANNEL_COLUMN_GAP = 8.dp
// 52, not 62: the row carried a second deck for the quality chip and no
// longer does, and every dp here is a channel the viewer can see at once.
private val ROW_HEIGHT = 52.dp

/** The narrowest cell that still shows a title and a focus ring — 61dp on a
 *  960dp panel, more on a wider one since the scale grows with it. */
internal const val MIN_CELL_MINUTES = 16f

/** What the browse guide's timeline never gets: TV-safe gutters and the
 *  channel column. The rail is a summoned drawer now and reserves nothing.
 *  The player overlay pays different costs — its own padding — so
 *  [guideDpPerMinute] takes them as a parameter. */
internal val GUIDE_BROWSE_FIXED_COSTS: Dp
    get() = Space.gutter * 2 + CHANNEL_COLUMN_WIDTH + CHANNEL_COLUMN_GAP

/**
 * Timeline scale for a panel [screenWidth] dp wide: the lane left after
 * [fixedCosts], divided into [TARGET_COLUMNS] half-hour columns.
 *
 * Internal and pure so the column arithmetic can be tested — getting it wrong
 * is invisible in code review and only shows up as a guide that pages too soon
 * on someone else's TV.
 */
internal fun guideDpPerMinute(
    screenWidth: Dp,
    fixedCosts: Dp = GUIDE_BROWSE_FIXED_COSTS,
): Dp {
    val lane = screenWidth - fixedCosts
    return (lane / (TARGET_COLUMNS * 30)).coerceIn(MIN_DP_PER_MINUTE, MAX_DP_PER_MINUTE)
}

/**
 * One rendered cell of a guide row: which programme, how wide, and any gap
 * drawn before it. Pure output of [layoutGuideRow], so the rendering pass and
 * the focus registry can never disagree about which programmes got cells.
 */
internal data class GuideCellSpec(
    val program: EpgProgram,
    /** Unaccounted gap before this cell, after repaying borrowed width. */
    val gapMinutesBefore: Float,
    val widthMinutes: Float,
    /** The programme's span clipped to the window — what the cell answers for. */
    val clampedStartMs: Long,
    val clampedEndMs: Long,
)

internal data class GuideRowLayout(
    val cells: List<GuideCellSpec>,
    /** Filler after the last cell so every row reaches the same total width —
     *  all rows share one ScrollState, and a short row would otherwise clamp
     *  the scroll for every other row. */
    val tailMinutes: Float,
)

/**
 * Widths are fractional minutes so rows line up with the ruler, but a cell
 * narrower than [MIN_CELL_MINUTES] is an unreadable sliver and a near-invisible
 * focus target. Short programmes borrow width from what follows and the debt is
 * repaid out of the next long programme or gap, so the row re-syncs with the
 * ruler within a slot or two instead of drifting.
 */
internal fun layoutGuideRow(
    programs: List<EpgProgram>,
    windowStart: Long,
    windowEnd: Long,
): GuideRowLayout {
    val cells = mutableListOf<GuideCellSpec>()
    var cursor = windowStart
    var borrowedMinutes = 0f
    for (program in programs) {
        val start = program.startMs.coerceIn(cursor, windowEnd)
        val end = program.endMs.coerceIn(start, windowEnd)
        if (end - start < 60_000) {
            cursor = end
            continue
        }

        var gapMinutes = (start - cursor) / 60_000f
        if (gapMinutes > 0f) {
            val repaid = minOf(gapMinutes, borrowedMinutes)
            gapMinutes -= repaid
            borrowedMinutes -= repaid
        } else {
            gapMinutes = 0f
        }

        val naturalMinutes = (end - start) / 60_000f
        val widthMinutes: Float
        if (naturalMinutes >= MIN_CELL_MINUTES) {
            val repaid = minOf(borrowedMinutes, naturalMinutes - MIN_CELL_MINUTES)
            widthMinutes = naturalMinutes - repaid
            borrowedMinutes -= repaid
        } else {
            widthMinutes = MIN_CELL_MINUTES
            borrowedMinutes += MIN_CELL_MINUTES - naturalMinutes
        }

        cells += GuideCellSpec(program, gapMinutes, widthMinutes, start, end)
        cursor = end
    }
    return GuideRowLayout(cells, ((windowEnd - cursor) / 60_000f) - borrowedMinutes)
}

/** A focusable cell in the registry: the span it answers for, and how to land on it. */
internal class GuideCellFocus(
    val startMs: Long,
    val endMs: Long,
    val requester: FocusRequester,
)

/**
 * Time-anchored vertical navigation. Compose's default focus search is
 * geometric: passing DOWN through a two-hour film lands you on whatever cell
 * sits under the film's centre, which drifts further from where you were with
 * every row. The anchor remembers the time you were looking at, and UP/DOWN
 * lands on the programme covering that time in the next row — the way every
 * broadcast guide behaves.
 *
 * Plain fields, not Compose state: everything here is read and written inside
 * event handlers, and making it state would recompose the grid on every focus
 * move.
 */
internal class GuideGridFocus(anchorMs: Long) {
    /** rowIndex → that row's cells, registered while the row is composed. */
    val rows = mutableMapOf<Int, List<GuideCellFocus>>()
    var focusedRow = -1

    /** Bumped on every focus arrival. requestFocus() returning without
     *  throwing is NOT proof focus moved — the entry redirect was observed
     *  "succeeding" on device while focus stayed on a category chip. Callers
     *  that must know compare this before and after. */
    var arrivals = 0

    /** False while focus is in the channel column, where default (index-wise)
     *  vertical movement is already the right thing. */
    var focusedIsCell = false
    var anchorMs = anchorMs

    /** Set when a vertical move had to fall back to the geometric focus search
     *  (target row not registered yet). The landing that follows must not
     *  overwrite the anchor: the geometric cell's time is exactly the drift
     *  the anchor exists to prevent, and preserving it lets the very next
     *  registered row snap back to the original column. */
    var verticalFallback = false

    /** The anchor moves only when the focused cell doesn't cover it — entering
     *  a cell to the right drags the anchor along, but crossing a long
     *  programme vertically doesn't reset it to the programme's start. */
    fun noteCellFocus(rowIndex: Int, startMs: Long, endMs: Long, nowMs: Long) {
        arrivals++
        focusedRow = rowIndex
        focusedIsCell = true
        val fromFallback = verticalFallback
        verticalFallback = false
        if (!fromFallback && anchorMs !in startMs until endMs) {
            anchorMs = if (nowMs in startMs until endMs) nowMs else startMs
        }
    }

    fun noteChannelFocus(rowIndex: Int) {
        arrivals++
        focusedRow = rowIndex
        focusedIsCell = false
        verticalFallback = false
    }
}

/**
 * The host's way to put focus INTO the grid. Compose's geometric search cannot
 * be trusted to find it: on device, DOWN from the day chip or a category chip
 * often finds no candidate at all, and the unconsumed event falls back to the
 * platform default — the first chip in composition order — so focus ping-pongs
 * above a grid it can never enter. Hosts intercept DOWN and call [focusAnchor]
 * instead, the exact pattern the grid itself already uses for UP.
 */
@Stable
class GuideGridHandle {
    internal var focusAnchorImpl: (suspend () -> Boolean)? = null

    /** Land focus on the anchored cell of the last-focused row (else the first
     *  row), verified — not merely requested. False when the grid is empty or
     *  never composed. */
    suspend fun focusAnchor(): Boolean = focusAnchorImpl?.invoke() ?: false
}

/** The cell covering [anchorMs], else the nearest one — a row whose data stops
 *  early should still take focus rather than bounce it. */
internal fun cellIndexFor(cells: List<GuideCellFocus>, anchorMs: Long): Int {
    val containing = cells.indexOfFirst { anchorMs in it.startMs until it.endMs }
    if (containing >= 0) return containing
    var best = 0
    var bestDistance = Long.MAX_VALUE
    cells.forEachIndexed { index, cell ->
        val distance =
            if (anchorMs < cell.startMs) cell.startMs - anchorMs else anchorMs - cell.endMs
        if (distance < bestDistance) {
            bestDistance = distance
            best = index
        }
    }
    return best
}

/**
 * The timeline grid: channel column, programme lanes sharing one horizontal
 * scroll, and the NOW marker. Hosts provide everything around it and decide
 * what playing/catch-up/scheduling mean.
 *
 * [programsKey] invalidates the per-row programme cache — pass the EPG state so
 * rows refresh when the guide reloads. [playingChannelId] tints the channel the
 * player is currently tuned to (the overlay's "you are here").
 * [initialFocusChannelId] lands focus on that channel's current programme once,
 * on entry — the overlay opens on what you're watching, not on row zero.
 */
@Composable
internal fun GuideGrid(
    channels: List<LiveChannel>,
    programsFor: (LiveChannel) -> List<EpgProgram>,
    programsKey: Any?,
    windowStart: Long,
    windowEnd: Long,
    nowMs: Long,
    timelineScroll: ScrollState,
    dpPerMinute: Dp,
    onFocus: (LiveChannel, EpgProgram?) -> Unit,
    onPlayChannel: (LiveChannel) -> Unit,
    onCatchup: (LiveChannel, EpgProgram) -> Unit,
    onSchedule: (LiveChannel, EpgProgram) -> Unit,
    modifier: Modifier = Modifier,
    playingChannelId: String? = null,
    initialFocusChannelId: String? = null,
    /**
     * Bumped by the host when focus enters the guide from outside (the nav
     * rail). Compose's geometric search otherwise lands on whatever sits
     * nearest — the day pager, or a clipped sliver cell with an invisible
     * ring; this routes entry to a real programme cell instead.
     */
    entryFocusTick: Int = 0,
    /**
     * Focus target for UP from the top row. Left to the geometric search it
     * escaped diagonally to the nav rail; a directional override routes it
     * to the host's controls row (category chips) instead.
     */
    upFromTopRow: FocusRequester? = null,
    onChannelLongPress: (LiveChannel) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    /** Host-facing focus entry — see [GuideGridHandle]. */
    handle: GuideGridHandle? = null,
    /**
     * Digit buffer owned by the host, when the host collects number keys for
     * the whole tab (so typing a channel number works from the category strip
     * too, not only with focus inside the grid). Left null, the grid owns its
     * own — the player overlay's arrangement.
     */
    digitState: MutableState<String>? = null,
) {
    val gridFocus = remember { GuideGridFocus(anchorMs = nowMs) }
    val scope = rememberCoroutineScope()

    // Number entry: the channel column is the channel list now, so the keypad
    // jump lives here. Digits collect briefly, then the grid scrolls to the
    // match and focus follows — scrolling alone leaves focus behind and the
    // next D-pad press snaps straight back.
    val digitBufferState: MutableState<String> = digitState ?: remember { mutableStateOf("") }
    var digitBuffer by digitBufferState

    // Day paging replaces the window; an anchor from yesterday would send every
    // vertical move to the nearest-edge fallback.
    LaunchedEffect(windowStart, windowEnd) {
        gridFocus.anchorMs = gridFocus.anchorMs.coerceIn(windowStart, windowEnd - 1)
    }

    fun focusRow(rowIndex: Int): Boolean {
        val cells = gridFocus.rows[rowIndex] ?: return false
        if (cells.isEmpty()) return false
        val cell = cells[cellIndexFor(cells, gridFocus.anchorMs)]
        // The Boolean is the truth — a clean return with `false` means the
        // focus system declined, and treating that as success is what made
        // entry work only sometimes. See requestFocusRetrying.
        return runCatching { cell.requester.requestFocus() }.getOrDefault(false)
    }

    /**
     * Scroll to [rowIndex] and put focus on its anchored cell, retrying until
     * the arrival is CONFIRMED by the cell's own focus callback. requestFocus()
     * returning cleanly is not enough: on device it was observed reporting
     * success while focus stayed where it was, which left the whole grid
     * unreachable and nothing in a log to say why.
     */
    suspend fun landOnRow(rowIndex: Int): Boolean {
        if (rowIndex !in channels.indices) return false
        runCatching { listState.scrollToItem(rowIndex) }
        repeat(8) {
            val before = gridFocus.arrivals
            if (focusRow(rowIndex)) {
                // The focus event lands within a frame; give it two.
                delay(32)
                if (gridFocus.arrivals > before) return true
            } else {
                // Row not composed yet — the scroll's composition runs a frame
                // behind the call.
                delay(60)
            }
        }
        return false
    }

    fun moveFocusVertically(delta: Int): Boolean {
        // In the channel column the default index-wise search is already right.
        if (!gridFocus.focusedIsCell) return false
        val target = gridFocus.focusedRow + delta
        if (target !in channels.indices) return false
        if (focusRow(target)) return true
        // Row not composed yet (fast scroll outran the LazyColumn): fall back
        // to the default search, which scrolls and lands geometrically — but
        // flag it, so the landing keeps the anchor instead of adopting the
        // geometric cell's time.
        gridFocus.verticalFallback = true
        return false
    }

    // CH+/- pages a whole screen of channels, matching the player where CH+ is
    // "next channel". Scroll first, then land focus once the row composes —
    // scrolling alone leaves focus behind and the next D-pad press snaps back.
    fun pageChannels(delta: Int): Boolean {
        if (channels.isEmpty()) return false
        val visible = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
        val current = gridFocus.focusedRow.takeIf { it >= 0 } ?: listState.firstVisibleItemIndex
        val target = (current + delta * visible).coerceIn(0, channels.lastIndex)
        if (target == current) return true
        scope.launch {
            listState.scrollToItem(target)
            repeat(5) {
                if (focusRow(target)) return@launch
                delay(60)
            }
        }
        return true
    }

    LaunchedEffect(digitBuffer) {
        if (digitBuffer.isEmpty()) return@LaunchedEffect
        delay(1_200)
        val typed = digitBuffer.toIntOrNull()
        // No suspension between this write and the launch below: clearing the
        // buffer restarts this effect, and the jump must survive that.
        digitBuffer = ""
        if (typed == null) return@LaunchedEffect
        // Number first, position second — the same rule the row labels use, so
        // typing what you see always lands on it.
        val target = channels.indexOfFirst { it.number == typed }
            .takeIf { it >= 0 } ?: (typed - 1)
        if (target in channels.indices) {
            scope.launch {
                listState.scrollToItem(target)
                repeat(5) {
                    if (focusRow(target)) return@launch
                    delay(60)
                }
            }
        }
    }

    // Focus entry from the rail: land on the playing channel's row, or the
    // first row. Same retry as every arrival focus — the row composes a
    // frame after the scroll.
    LaunchedEffect(entryFocusTick) {
        if (entryFocusTick == 0) return@LaunchedEffect
        val index = channels.indexOfFirst { it.id == playingChannelId }
            .takeIf { it >= 0 } ?: 0
        landOnRow(index)
    }

    // The host's DOWN route in. Reassigned every composition so the lambda
    // never captures a stale channel list; cleared on dispose so a dead grid
    // can't be asked to take focus.
    DisposableEffect(handle) {
        onDispose { handle?.focusAnchorImpl = null }
    }
    handle?.focusAnchorImpl = {
        landOnRow(gridFocus.focusedRow.takeIf { it in channels.indices } ?: 0)
    }

    // Land on the playing channel's current programme when the overlay opens.
    LaunchedEffect(initialFocusChannelId) {
        val id = initialFocusChannelId ?: return@LaunchedEffect
        val index = channels.indexOfFirst { it.id == id }
        if (index < 0) return@LaunchedEffect
        listState.scrollToItem(index)
        repeat(5) {
            if (focusRow(index)) return@LaunchedEffect
            delay(60)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> moveFocusVertically(+1)
                    AndroidKeyEvent.KEYCODE_DPAD_UP ->
                        // Top row exits to the controls (category chips).
                        // Deferred a frame: a requestFocus made synchronously
                        // inside key dispatch is dropped, and the cell's
                        // focusProperties.up override is not consulted for
                        // D-pad moves on this tv-material version.
                        if (gridFocus.focusedRow == 0 && upFromTopRow != null) {
                            scope.launch { upFromTopRow.requestFocusRetrying() }
                            true
                        } else {
                            moveFocusVertically(-1)
                        }
                    AndroidKeyEvent.KEYCODE_CHANNEL_UP -> pageChannels(+1)
                    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> pageChannels(-1)
                    in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> {
                        digitBuffer += (event.key.nativeKeyCode - AndroidKeyEvent.KEYCODE_0).toString()
                        true
                    }
                    else -> false
                }
            }
    ) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(channels, key = { _, channel -> channel.id }) { index, channel ->
                GuideRow(
                    channel = channel,
                    rowIndex = index,
                    upFromRow = if (index == 0) upFromTopRow else null,
                    programsFor = programsFor,
                    programsKey = programsKey,
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    nowMs = nowMs,
                    timelineScroll = timelineScroll,
                    dpPerMinute = dpPerMinute,
                    gridFocus = gridFocus,
                    playing = channel.id == playingChannelId,
                    onFocus = { program -> onFocus(channel, program) },
                    onPlayChannel = { onPlayChannel(channel) },
                    onChannelLongPress = { onChannelLongPress(channel) },
                    onCatchup = { program -> onCatchup(channel, program) },
                    onSchedule = { program -> onSchedule(channel, program) },
                )
            }
        }

        ChannelJumpBadge(digitBuffer, Modifier.align(Alignment.TopEnd).padding(8.dp))

        // The NOW marker — the defining element of an EPG. A dot caps the line
        // so it reads as a marker even where cell borders cross it.
        //
        // The scroll position is read in the LAYOUT phase, never composition:
        // as a composition read it recomposed this whole composable — the
        // LazyColumn and every visible row with it — on each pixel of every
        // horizontal scroll, which is what made travelling the timeline crawl.
        // The marker also clips itself instead of vanishing behind an `if`,
        // because that condition was a composition read of the same value.
        val density = LocalDensity.current
        val laneStartPx = with(density) { (CHANNEL_COLUMN_WIDTH + CHANNEL_COLUMN_GAP).roundToPx() }
        val nowOffsetPx = with(density) { (dpPerMinute * ((nowMs - windowStart) / 60_000f)).roundToPx() }
        val channelColumnPx = with(density) { CHANNEL_COLUMN_WIDTH.roundToPx() }
        Box(
            modifier = Modifier
                .offset { IntOffset(laneStartPx + nowOffsetPx - timelineScroll.value, 0) }
                .width(2.dp)
                .fillMaxHeight()
                .drawWithContent {
                    // Behind the channel column the marker is meaningless;
                    // drawing is skipped rather than the node removed.
                    if (laneStartPx + nowOffsetPx - timelineScroll.value > channelColumnPx) {
                        drawContent()
                    }
                }
                .background(NuxColors.Primary)
        )
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        laneStartPx + nowOffsetPx - timelineScroll.value -
                            with(density) { 3.dp.roundToPx() },
                        0,
                    )
                }
                .size(8.dp)
                .drawWithContent {
                    if (laneStartPx + nowOffsetPx - timelineScroll.value > channelColumnPx) {
                        drawContent()
                    }
                }
                .clip(CircleShape)
                .background(NuxColors.Primary)
        )
    }
}

@Composable
internal fun TimeRuler(
    windowStart: Long,
    windowEnd: Long,
    nowMs: Long,
    /** The day being viewed. windowStart sits an hour earlier and can fall on
     *  the previous date between midnight and 01:00. */
    dayMs: Long,
    timelineScroll: ScrollState,
    dpPerMinute: Dp,
    /**
     * The day control, when the guide has more than today to show. It lives
     * here rather than in the category strip because a day is a position on
     * the time axis, and the times it moves are the ones to its right — in
     * the strip it read as a second selected category, in a row that answers
     * an entirely different question.
     */
    dayLabel: String? = null,
    onDayClick: (() -> Unit)? = null,
    dayFocus: FocusRequester? = null,
    dayUp: FocusRequester? = null,
    /** DOWN from the day chip. Intercepted rather than left to the geometric
     *  search, which finds no candidate below and lets the unconsumed event
     *  fall back to the first category chip — see [GuideGridHandle]. */
    onDayDown: (() -> Unit)? = null,
) {
    val fmt = rememberClockFormat()
    val dayFmt = remember { SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()) }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // The date names the day being VIEWED — on today it duplicated the
        // header's clock corner an inch away, so it only renders when paging.
        // Day numbers, not two formatted strings: this ran two
        // SimpleDateFormat passes per composition purely to compare dates.
        val viewingToday = remember(dayMs, nowMs) {
            val zone = java.util.TimeZone.getDefault()
            fun dayOf(ms: Long) = (ms + zone.getOffset(ms)) / 86_400_000L
            dayOf(dayMs) == dayOf(nowMs)
        }
        Box(
            modifier = Modifier.width(CHANNEL_COLUMN_WIDTH + CHANNEL_COLUMN_GAP),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (onDayClick != null && dayLabel != null) {
                Surface(
                    onClick = onDayClick,
                    modifier = Modifier
                        .then(dayFocus?.let { Modifier.focusRequester(it) } ?: Modifier)
                        .focusProperties { dayUp?.let { up = it } }
                        .onPreviewKeyEvent { event ->
                            if (onDayDown != null &&
                                event.type == KeyEventType.KeyDown &&
                                event.key.nativeKeyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN
                            ) {
                                onDayDown()
                                true
                            } else false
                        },
                    shape = ClickableSurfaceDefaults.shape(NuxShape.Chip),
                    // Quiet at rest like an unselected chip: this names where
                    // the grid is, it is not a thing that is switched on.
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        focusedContainerColor = NuxColors.SurfaceRaised,
                        contentColor = NuxColors.OnSurface,
                        focusedContentColor = NuxColors.OnSurface,
                    ),
                    scale = ClickableSurfaceDefaults.scale(
                        focusedScale = com.agoro.tv.ui.theme.NuxFocus.ButtonScale,
                    ),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = com.agoro.tv.ui.theme.NuxFocus.ring8,
                    ),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            Icons.Default.CalendarMonth,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = dayLabel,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                }
            } else if (!viewingToday) {
                Text(
                    text = dayFmt.format(Date(dayMs)),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuxColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(modifier = Modifier.horizontalScroll(timelineScroll, enabled = false)) {
            // Windowed like the rows below it. The ruler spans the same 30
            // hours — sixty labels, each with its own text layout, offset
            // lambda and RenderNode — and the row windowing never reached it,
            // so it was left composing as many nodes as the entire grid to
            // show the five slots that fit. Slots outside the window are one
            // spacer at each end, so widths and the shared scroll are
            // unchanged.
            val slotMillis = 30 * 60_000L
            val slotCount = ((windowEnd - windowStart) / slotMillis).toInt()
            val density = LocalDensity.current
            val slotPx = with(density) { (dpPerMinute * 30).toPx() }
            val viewportPx = timelineScroll.viewportSize.takeIf { it > 0 }?.toFloat() ?: 0f
            val bucket by remember(slotPx, viewportPx) {
                androidx.compose.runtime.derivedStateOf {
                    if (viewportPx <= 0f) 0
                    else (timelineScroll.value / (viewportPx / 2f)).toInt()
                }
            }
            val range = remember(slotCount, slotPx, viewportPx, bucket) {
                if (viewportPx <= 0f) 0 until slotCount
                else {
                    val from = ((bucket * (viewportPx / 2f) - viewportPx) / slotPx).toInt()
                    val to = ((bucket * (viewportPx / 2f) + 2 * viewportPx) / slotPx).toInt()
                    from.coerceAtLeast(0)..to.coerceAtMost(slotCount - 1)
                }
            }
            if (range.first > 0) Spacer(Modifier.width(dpPerMinute * 30 * range.first))
            var t = windowStart + range.first * slotMillis
            val rulerEnd = windowStart + (range.last + 1) * slotMillis
            while (t < rulerEnd) {
                // The half-hour containing "now" is called out instead of
                // labelled with a time you'd have to compare against a clock.
                val isNow = nowMs >= t && nowMs < t + 30 * 60_000L
                val slotStart = t
                // Measured, not guessed. The pin limit used to reserve a fixed
                // 72dp for the label, which is both too little for "ON NOW"
                // and — at MIN_DP_PER_MINUTE, where a half-hour slot is 78dp —
                // only 6dp of travel. Past the limit the label slid under the
                // lane's clip edge and rendered as a fragment: the guide's most
                // important marker read "N NOW" for most of every hour.
                var labelWidthPx by remember { mutableIntStateOf(0) }
                fun pinLimit(density: Density): Float = with(density) {
                    // Minus a gap, so a pinned label never butts against the
                    // next slot's.
                    (30f * dpPerMinute.toPx() - labelWidthPx - 12.dp.toPx())
                        .coerceAtLeast(0f)
                }
                fun scrolledPast(density: Density): Float = with(density) {
                    val perMinPx = dpPerMinute.toPx()
                    timelineScroll.value - ((slotStart - windowStart) / 60_000f) * perMinPx
                }
                Text(
                    text = if (isNow) "ON NOW" else fmt.format(Date(t)),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isNow) NuxColors.Error else NuxColors.OnSurfaceDim,
                    // getLineWidth, not size.width: the Text is stretched to
                    // the full slot, so its layout size is the slot — which
                    // made the pin limit zero and hid every label the moment
                    // its slot began to scroll.
                    onTextLayout = { result ->
                        labelWidthPx = if (result.lineCount > 0) {
                            kotlin.math.ceil(result.getLineRight(0) - result.getLineLeft(0)).toInt()
                        } else 0
                    },
                    modifier = Modifier
                        .width(dpPerMinute * 30)
                        .offset {
                            IntOffset(
                                scrolledPast(this).coerceIn(0f, pinLimit(this)).toInt(),
                                0,
                            )
                        }
                        .graphicsLayer {
                            // Once the whole label no longer fits inside its own
                            // slot, the NEXT slot's label has already reached the
                            // lane edge and takes over. Drop this one rather than
                            // let it clip mid-glyph.
                            alpha = if (labelWidthPx > 0 &&
                                scrolledPast(this) > pinLimit(this)
                            ) 0f else 1f
                        },
                )
                t += 30 * 60_000L
            }
            val after = slotCount - 1 - range.last
            if (after > 0) Spacer(Modifier.width(dpPerMinute * 30 * after))
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun GuideRow(
    channel: LiveChannel,
    rowIndex: Int,
    upFromRow: FocusRequester?,
    programsFor: (LiveChannel) -> List<EpgProgram>,
    programsKey: Any?,
    windowStart: Long,
    windowEnd: Long,
    nowMs: Long,
    timelineScroll: ScrollState,
    dpPerMinute: Dp,
    gridFocus: GuideGridFocus,
    playing: Boolean,
    onFocus: (EpgProgram?) -> Unit,
    onPlayChannel: () -> Unit,
    onChannelLongPress: () -> Unit,
    onCatchup: (EpgProgram) -> Unit,
    onSchedule: (EpgProgram) -> Unit,
) {
    // windowStart/windowEnd must be keys, not just captures. Without them,
    // paging to tomorrow kept yesterday's list: every programme then clamped to
    // zero width in the layout loop, so the lane drew empty — and because the
    // list was non-empty the "No information" placeholder was suppressed too,
    // leaving channel names beside a blank row.
    val layout = remember(channel.id, programsKey, windowStart, windowEnd) {
        layoutGuideRow(
            programsFor(channel).filter { it.endMs > windowStart && it.startMs < windowEnd },
            windowStart,
            windowEnd,
        )
    }
    val cellRequesters = remember(layout) { List(layout.cells.size) { FocusRequester() } }
    val placeholderRequester = remember { FocusRequester() }

    // Register this row's cells for time-anchored UP/DOWN and CH paging. The
    // rendering pass and this list both come from `layout`, so they can't
    // disagree about which programmes got cells.
    DisposableEffect(rowIndex, layout, cellRequesters) {
        val entry =
            if (layout.cells.isEmpty()) {
                listOf(GuideCellFocus(windowStart, windowEnd, placeholderRequester))
            } else {
                layout.cells.mapIndexed { i, spec ->
                    GuideCellFocus(spec.clampedStartMs, spec.clampedEndMs, cellRequesters[i])
                }
            }
        gridFocus.rows[rowIndex] = entry
        // Remove only our own registration. On a category switch a removed
        // row's dispose runs after the kept rows have re-registered under new
        // indices, and an unconditional remove would delete another row's
        // fresh entry — leaving a visible row the vertical navigation and CH
        // paging can no longer land on.
        onDispose {
            if (gridFocus.rows[rowIndex] === entry) gridFocus.rows.remove(rowIndex)
        }
    }

    Row(modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT)) {
        // Fixed channel cell. Long-press carries the list's secondary actions
        // (What's on, favorite, hide) — the column is the channel list now.
        Surface(
            onClick = onPlayChannel,
            onLongClick = onChannelLongPress,
            modifier = Modifier
                .width(CHANNEL_COLUMN_WIDTH)
                .focusProperties { upFromRow?.let { up = it } }
                .onFocusChanged {
                    if (it.isFocused) {
                        gridFocus.noteChannelFocus(rowIndex)
                        onFocus(
                            layout.cells.firstOrNull { spec ->
                                nowMs in spec.program.startMs until spec.program.endMs
                            }?.program
                        )
                    }
                },
            shape = ClickableSurfaceDefaults.shape(NuxShape.Chip),
            colors = ClickableSurfaceDefaults.colors(
                // The gold tint marks the channel the player is tuned to — the
                // overlay's "you are here". Same treatment as a selected
                // CategoryItem, so it reads as state rather than focus.
                containerColor = if (playing) {
                    NuxColors.Primary.copy(alpha = 0.16f)
                } else {
                    NuxColors.Surface
                },
                focusedContainerColor = NuxColors.SurfaceRaised,
                contentColor = NuxColors.OnSurface,
                focusedContentColor = NuxColors.OnSurface,
            ),
            // Explicit, like every other row in the app. Left unset, this takes
            // tv-material3's 1.1 default, and in a grid whose cells sit 2dp
            // apart with 6dp between rows a focused cell grows over its
            // neighbours. The ring is the focus signal here; the geometry has to
            // stay put or the row it is in stops lining up with the ruler.
            scale = ClickableSurfaceDefaults.scale(
                focusedScale = com.agoro.tv.ui.theme.NuxFocus.RowScale,
            ),
            border = ClickableSurfaceDefaults.border(
                // The hoisted singleton, not a fresh Border per cell per
                // composition — Theme.kt says why, and the guide is the
                // densest focus surface in the app.
                border = com.agoro.tv.ui.theme.NuxBorders.restingChip,
                focusedBorder = com.agoro.tv.ui.theme.NuxFocus.ring8,
            ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                // The number is the address the digit keys answer to — here
                // and in the player — so the row that defines it shows it.
                // Dim and in a fixed slot: the name stays the headline, and
                // logos line up whether the number is 7 or 1042.
                Text(
                    text = channel.number?.toString().orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = NuxColors.OnSurfaceDim,
                    maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier.width(24.dp),
                )
                Artwork(
                    imageUrl = channel.logo,
                    title = channel.displayName,
                    modifier = Modifier.size(width = 44.dp, height = 36.dp).clip(NuxShape.Chip),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    monogramStyle = MaterialTheme.typography.labelMedium,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = channel.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        // One line: two lines of 24sp need 48dp and the row's
                        // content box is 46dp, so the second was always clipped.
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // No quality chip here. It cost a whole second deck per
                    // row — the reason the guide showed four channels where
                    // the app it is measured against shows eight — to repeat
                    // something the channel list, the player's banner and
                    // search all say. In a grid the NAME is what a viewer
                    // reads, and it gets the line to itself.
                }
            }
        }
        Spacer(Modifier.width(CHANNEL_COLUMN_GAP))

        // Programme lane sharing the timeline scroll.
        Row(modifier = Modifier.horizontalScroll(timelineScroll)) {
            if (layout.cells.isEmpty()) {
                // Focusable and playable, not just an annotation: a channel
                // with no guide data used to have a lane you could neither
                // land on nor act from, so vertical travel skipped it and the
                // channel cell was the only way in.
                Surface(
                    onClick = onPlayChannel,
                    modifier = Modifier
                        .focusRequester(placeholderRequester)
                        .focusProperties { upFromRow?.let { up = it } }
                        .onFocusChanged {
                            if (it.isFocused) {
                                gridFocus.noteCellFocus(rowIndex, windowStart, windowEnd, nowMs)
                                onFocus(null)
                            }
                        }
                        .width(dpPerMinute * ((windowEnd - windowStart) / 60_000L).toInt())
                        .height(ROW_HEIGHT)
                        .padding(end = 2.dp, top = 6.dp, bottom = 6.dp),
                    // 8dp like the programme cells beside it, so one ring token
                    // serves the whole lane.
                    shape = ClickableSurfaceDefaults.shape(NuxShape.Chip),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = NuxColors.Surface.copy(alpha = 0.35f),
                        focusedContainerColor = NuxColors.SurfaceRaised,
                        contentColor = NuxColors.OnSurfaceDim,
                        focusedContentColor = NuxColors.OnSurface,
                    ),
                    scale = ClickableSurfaceDefaults.scale(
                        focusedScale = com.agoro.tv.ui.theme.NuxFocus.RowScale,
                    ),
                    border = ClickableSurfaceDefaults.border(
                        border = androidx.tv.material3.Border.None,
                        focusedBorder = com.agoro.tv.ui.theme.NuxFocus.ring8,
                    ),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        // Pinned to the visible edge, not the lane's start.
                        // This lane spans the whole 30-hour window while the
                        // view sits at "now", so a label at its start was
                        // hours off screen to the left and the row read as
                        // simply blank — the channel looked broken rather
                        // than unlisted. Offsetting by the scroll keeps the
                        // one thing it has to say where it can be read.
                        Text(
                            "No information",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            modifier = Modifier
                                .offset { IntOffset(timelineScroll.value, 0) }
                                .padding(start = 16.dp),
                        )
                    }
                }
            } else {
                // Only the cells near the viewport are composed. horizontalScroll
                // does not virtualize, so a row used to build every cell across
                // the whole 30-hour window — sixty to a hundred TV Surfaces per
                // row, ten rows on screen, for the six cells a viewer can see.
                // That was the guide's real cost: frames in the hundreds of
                // milliseconds, input backing up behind them, and the main
                // thread starving the player's threads on the same core.
                //
                // Geometry is untouched: the skipped cells are replaced by one
                // Spacer of exactly their width at each end, so every row still
                // measures the full window and the shared ScrollState clamps
                // identically. The window is bucketed so scrolling recomposes
                // when it crosses a bucket, not on every pixel.
                val visible = rememberVisibleCellRange(layout, dpPerMinute, timelineScroll)
                if (visible.leadMinutes > 0f) {
                    Spacer(Modifier.width(dpPerMinute * visible.leadMinutes))
                }
                layout.cells.forEachIndexed { i, spec ->
                    if (i !in visible.range) return@forEachIndexed
                    if (spec.gapMinutesBefore > 0f && i != visible.range.first) {
                        Spacer(Modifier.width(dpPerMinute * spec.gapMinutesBefore))
                    }
                    ProgramCell(
                        program = spec.program,
                        upFocus = upFromRow,
                        widthMinutes = spec.widthMinutes,
                        startMinutesFromWindow = (spec.clampedStartMs - windowStart) / 60_000f,
                        timelineScroll = timelineScroll,
                        dpPerMinute = dpPerMinute,
                        airingNow = nowMs in spec.program.startMs until spec.program.endMs,
                        isPast = spec.program.endMs <= nowMs,
                        progress = if (nowMs in spec.program.startMs until spec.program.endMs) {
                            ((nowMs - spec.program.startMs).toFloat() /
                                (spec.program.endMs - spec.program.startMs).coerceAtLeast(1))
                                .coerceIn(0f, 1f)
                        } else 0f,
                        focusRequester = cellRequesters[i],
                        onFocus = {
                            gridFocus.noteCellFocus(
                                rowIndex, spec.clampedStartMs, spec.clampedEndMs, nowMs,
                            )
                            onFocus(spec.program)
                        },
                        hasArchive = channel.archiveDays > 0,
                        canRecord = channel.recordUrl != null,
                        onPlayLive = onPlayChannel,
                        onCatchup = { onCatchup(spec.program) },
                        onSchedule = { onSchedule(spec.program) },
                    )
                }
                if (visible.trailMinutes + layout.tailMinutes > 0f) {
                    Spacer(Modifier.width(dpPerMinute * (visible.trailMinutes + layout.tailMinutes)))
                }
            }
        }
    }
}

/** Which cells a row composes, and the width standing in for the rest. */
internal class VisibleCells(
    val range: IntRange,
    val leadMinutes: Float,
    val trailMinutes: Float,
)

/**
 * The cells whose time overlaps the visible lane, plus a screen of margin on
 * each side so ordinary travel never outruns the composed set — the margin is
 * what keeps a focus requester attached for the cell D-pad is about to reach.
 *
 * Bucketed by half a viewport: recomposing per scrolled pixel would cost more
 * than the cells it saves.
 */
/**
 * The pure half of [rememberVisibleCellRange], so the geometry can be tested:
 * getting it wrong shows up as cells that drift out of line with the ruler,
 * which is invisible in review and obvious on a TV.
 *
 * Invariant the tests hold it to: lead + composed spans + trail always equals
 * the row's full width, so every row still measures the same and the shared
 * ScrollState clamps identically.
 */
internal fun visibleCellsFor(
    layout: GuideRowLayout,
    perMinutePx: Float,
    scrollPx: Int,
    viewportPx: Float,
): VisibleCells {
    if (viewportPx <= 0f) return VisibleCells(layout.cells.indices, 0f, 0f)
    val marginPx = viewportPx
    val fromPx = scrollPx - marginPx
    val toPx = scrollPx + viewportPx + marginPx
    var cursor = 0f
    var first = -1
    var last = -1
    layout.cells.forEachIndexed { i, spec ->
        val startMinutes = cursor + spec.gapMinutesBefore
        val endMinutes = startMinutes + spec.widthMinutes
        if (endMinutes * perMinutePx >= fromPx && startMinutes * perMinutePx <= toPx) {
            if (first < 0) first = i
            last = i
        }
        cursor = endMinutes
    }
    val total = cursor
    if (first < 0) return VisibleCells(IntRange.EMPTY, 0f, total)
    var lead = 0f
    var endOfLast = 0f
    var walk = 0f
    layout.cells.forEachIndexed { i, spec ->
        val startMinutes = walk + spec.gapMinutesBefore
        val endMinutes = startMinutes + spec.widthMinutes
        // The lead spacer swallows everything before the first composed cell,
        // its own leading gap included — which is why that gap is skipped in
        // the render loop for the first cell.
        if (i == first) lead = startMinutes
        if (i == last) endOfLast = endMinutes
        walk = endMinutes
    }
    return VisibleCells(first..last, lead, (total - endOfLast).coerceAtLeast(0f))
}

/**
 * The cells whose time overlaps the visible lane, plus a screen of margin on
 * each side so ordinary travel never outruns the composed set — the margin is
 * what keeps a focus requester attached for the cell D-pad is about to reach.
 *
 * Bucketed by half a viewport: recomposing per scrolled pixel would cost more
 * than the cells it saves.
 */
@Composable
private fun rememberVisibleCellRange(
    layout: GuideRowLayout,
    dpPerMinute: Dp,
    timelineScroll: ScrollState,
): VisibleCells {
    val density = LocalDensity.current
    val perMinutePx = with(density) { dpPerMinute.toPx() }
    // viewportSize is 0 until the row has been laid out once; a full window
    // then, so the first frame is correct and the second is cheap.
    val viewportPx = timelineScroll.viewportSize.takeIf { it > 0 }?.toFloat() ?: 0f
    val bucketPx = (viewportPx / 2f).coerceAtLeast(1f)
    val bucket by remember(layout, perMinutePx, viewportPx) {
        androidx.compose.runtime.derivedStateOf { (timelineScroll.value / bucketPx).toInt() }
    }
    return remember(layout, perMinutePx, viewportPx, bucket) {
        visibleCellsFor(layout, perMinutePx, (bucket * bucketPx).toInt(), viewportPx)
    }
}

@Composable
private fun ProgramCell(
    program: EpgProgram,
    upFocus: FocusRequester?,
    widthMinutes: Float,
    startMinutesFromWindow: Float,
    timelineScroll: ScrollState,
    dpPerMinute: Dp,
    // Derived in the row, not here. The clock used to arrive as a value
    // parameter, so its 30-second tick changed a parameter of EVERY composed
    // cell and none of them could skip — sixty Surfaces rebuilt to tell one
    // of them it had started. These flip for the one cell that actually
    // crossed a boundary.
    airingNow: Boolean,
    isPast: Boolean,
    /** 0..1 through the programme, only meaningful while [airingNow]. */
    progress: Float,
    focusRequester: FocusRequester,
    onFocus: () -> Unit,
    hasArchive: Boolean,
    canRecord: Boolean,
    onPlayLive: () -> Unit,
    onCatchup: () -> Unit,
    onSchedule: () -> Unit,
) {
    val fmt = rememberClockFormat()

    Surface(
        onClick = {
            when {
                airingNow -> onPlayLive()
                isPast && hasArchive -> onCatchup()
                !isPast -> onSchedule() // records when possible, else sets a reminder
                else -> Unit
            }
        },
        modifier = Modifier
            .focusRequester(focusRequester)
            .focusProperties { upFocus?.let { up = it } }
            .onFocusChanged { if (it.isFocused) onFocus() }
            // Caller has already reconciled this against the ruler; see layoutGuideRow.
            .width(dpPerMinute * widthMinutes)
            .height(ROW_HEIGHT)
            .padding(end = 2.dp, top = 6.dp, bottom = 6.dp),
        shape = ClickableSurfaceDefaults.shape(NuxShape.Chip),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = com.agoro.tv.ui.theme.NuxFocus.RowScale,
        ),
        border = ClickableSurfaceDefaults.border(
            border = com.agoro.tv.ui.theme.NuxBorders.restingChip,
            focusedBorder = com.agoro.tv.ui.theme.NuxFocus.ring8,
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = when {
                airingNow -> NuxColors.SurfaceVariant
                // Finished programmes recede so the "on now" column reads at a
                // glance; the text is already dimmed, but on a TV's lifted
                // blacks the fill difference is what actually carries it.
                isPast -> NuxColors.Surface.copy(alpha = 0.45f)
                else -> NuxColors.Surface
            },
            focusedContainerColor = NuxColors.SurfaceRaised,
            contentColor = if (isPast && !airingNow) NuxColors.OnSurfaceDim else NuxColors.OnSurface,
            focusedContentColor = NuxColors.OnSurface,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Pin the text to the visible edge while the cell is partially
            // scrolled off, the way broadcast guides do — otherwise a long
            // programme's title leaves the screen minutes before the cell
            // does. The offset lambda reads the scroll during placement, so
            // scrolling never recomposes the cell.
            Column(
                modifier = Modifier
                    .offset {
                        val perMinPx = dpPerMinute.toPx()
                        val startPx = startMinutesFromWindow * perMinPx
                        val cellPx = widthMinutes * perMinPx
                        val maxPin = (cellPx - 120.dp.toPx()).coerceAtLeast(0f)
                        IntOffset(
                            (timelineScroll.value - startPx).coerceIn(0f, maxPin).toInt(),
                            0,
                        )
                    }
                    .graphicsLayer {
                        // Once the pin is exhausted the text slides under the
                        // lane's clip edge and renders as a mid-glyph fragment
                        // ("oom" out of "News Room"). Drop it whole instead —
                        // the same rule the ruler labels follow.
                        val perMinPx = dpPerMinute.toPx()
                        val startPx = startMinutesFromWindow * perMinPx
                        val maxPin =
                            (widthMinutes * perMinPx - 120.dp.toPx()).coerceAtLeast(0f)
                        alpha = if (timelineScroll.value - startPx > maxPin) 0f else 1f
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = program.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // Time only. "OK to record" repeated on every future
                    // cell was the same sentence dozens of times per screen;
                    // the header teaches it once, for the focused cell.
                    text = fmt.format(Date(program.startMs)) +
                        (if (airingNow) " • Now" else ""),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // How far through, without leaving the grid — the header repeats it
            // in words, but the header describes only the focused cell.
            if (airingNow) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(progress)
                        .height(3.dp)
                        .background(NuxColors.Primary.copy(alpha = 0.75f))
                )
            }
        }
    }
}
