@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.agoro.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.agoro.tv.MainViewModel
import com.agoro.tv.data.Category
import com.agoro.tv.data.ContentBundle
import com.agoro.tv.data.ContentRepository
import com.agoro.tv.data.EpgProgram
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.ui.components.Artwork
import com.agoro.tv.ui.components.rememberClockFormat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LiveTv
import androidx.tv.material3.Icon
import com.agoro.tv.ui.components.StatusAction
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import com.agoro.tv.ui.components.MetaChip
import com.agoro.tv.ui.components.StatusPane
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.focus.focusRequester
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxMotion
import com.agoro.tv.ui.theme.NuxShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Budget on a 960x540dp TV canvas: 540 − 64 (screen gutters) − 50 (category
 * and day row) − 30 (ruler) leaves ~396dp, so a 120dp header keeps four
 * channel rows on screen. A guide showing fewer channels than that stops being
 * a guide, which is why this is a minimum height and not a target.
 */
private val HEADER_HEIGHT = 120.dp

/**
 * The grid view of Live TV. Not a destination of its own: it is one of two ways
 * to look at the same channels, so [categoryId] is owned by the caller and the
 * two views share one filter. They each kept their own before, which meant
 * picking a category in one and switching silently put you back on "All" in the
 * other.
 *
 * The grid itself lives in GuideGrid.kt, shared with the player's guide
 * overlay; this file is the browse host — header, category row, day paging and
 * the preview plumbing.
 *
 * [leading] is drawn as the first item of the control row — the view switch
 * belongs there rather than above the grid, where it would cost the height of a
 * channel row on a screen that only has four.
 */
@Composable
fun GuideTab(
    entryFocusTick: Int,
    vm: MainViewModel,
    bundle: ContentBundle,
    onPlay: () -> Unit,
    categoryId: String,
    onCategoryId: (String) -> Unit,
    leading: @Composable () -> Unit = {},
    /** Long-press on a channel cell — the host hangs its context menu here. */
    onChannelLongPress: (LiveChannel) -> Unit = {},
    /** Escape hatch offered when the playlist has no live channels at all. */
    onOpenSettings: () -> Unit = {},
) {
    val epgState by vm.epgState.collectAsState()
    val coverage by vm.guideCoverage.collectAsState()
    val scope = rememberCoroutineScope()

    // Guide trouble never takes the channels with it.
    //
    // The grid IS the channel list, so replacing it with an error pane meant a
    // playlist whose xmltv URL 404s — routine, not exotic — had no way to play
    // any channel at all: no rows, no number keys, no hold-OK menu. The prompt
    // rides above a working grid instead, and the lanes simply read "No
    // information" until a guide arrives.
    val notice: GuideNotice? = when (val state = epgState) {
        is ContentRepository.EpgState.Idle,
        is ContentRepository.EpgState.Loading -> GuideNotice.Loading

        is ContentRepository.EpgState.Error -> GuideNotice.Missing(matchFailure = false)

        is ContentRepository.EpgState.Ready ->
            // Ready means the XMLTV parsed, not that any of it is this
            // playlist's. When the ids don't line up every row reads "No
            // information" over a grid that looks like it is working, so a
            // coverage miss gets the same prompt a failed download does.
            if (coverage.matchesPlaylist) null else GuideNotice.Missing(matchFailure = true)
    }


    val allChannels by vm.displayChannels.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val recents by vm.recentChannels.collectAsState()
    // Parental locks, same vocabulary as everywhere else: locked
    // categories show a lock on their chip and ask for the PIN. Their
    // channels are already filtered out of displayChannels, so without
    // this the chip just opened an empty grid with no explanation.
    val pin by vm.parentalPin.collectAsState()
    val unlocked by vm.parentalUnlocked.collectAsState()
    var pinPromptOpen by remember { mutableStateOf(false) }
    val lockedIds = remember(bundle, pin, unlocked) {
        bundle.liveCategories.filter { vm.isLockedCategory(it.name) }
            .map { it.id }.toSet()
    }
    // Same list and same filtering as the channel view — see
    // LiveCategories.kt. The caller owns which one is selected.
    val categories = remember(bundle, favorites, recents, allChannels) {
        liveCategoryList(bundle, allChannels, favorites, recents)
    }
    val allView by vm.allChannelsView.collectAsState()
    val channels = remember(allChannels, categoryId, favorites, recents, allView) {
        channelsInCategory(categoryId, allChannels, favorites, recents, allChannels = allView)
    }
    // Same rest-before-select rule as every other category surface
    // (nav rail, Movies/Series columns): resting on a chip selects it,
    // debounced so travelling the row doesn't rebuild the grid on
    // every step. This was lost when the redesign made the guide the
    // only Live surface — chips highlighted on focus but only OK
    // filtered, which read as "the category doesn't work".
    var focusedCategory by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusedCategory) {
        val id = focusedCategory ?: return@LaunchedEffect
        kotlinx.coroutines.delay(NuxMotion.FocusDwellMs.toLong())
        onCategoryId(id)
    }
    if (allChannels.isEmpty()) {
        StatusPane(
            title = "No live channels",
            message = "This playlist has no live streams, or they are all hidden.",
            icon = Icons.Default.LiveTv,
            primaryAction = StatusAction("Open Settings", onOpenSettings),
        )
        return
    }

    var dayOffset by rememberSaveable { mutableStateOf(0) }
    val baseStart = remember {
        val now = System.currentTimeMillis()
        now - now % (30 * 60_000L) - 60 * 60_000L
    }
    // How far forward there is anything to page to. XMLTV feeds carry
    // two to seven days and the parser keeps a 48-hour window, so
    // without a ceiling `›` walked forever into identical screens of
    // "No information" with nothing saying you had left the data.
    val maxDayOffset = remember(coverage.lastProgramEndMs, baseStart) {
        // The sentinel means "not computed yet", and the ViewModel's
        // contract for it is optimism: mapping it to 0 disabled `›`
        // until the background combine finished and clamped a restored
        // dayOffset back to today. Cap generously instead and let the
        // real ceiling take over when it lands.
        if (coverage.lastProgramEndMs == Long.MAX_VALUE) 14
        else ((coverage.lastProgramEndMs - baseStart) / (24 * 3600_000L))
            .toInt().coerceIn(0, 14)
    }
    // A guide that shrank under us (a refresh with less data) must not
    // strand the viewer on a day that no longer exists.
    LaunchedEffect(maxDayOffset) {
        if (dayOffset > maxDayOffset) dayOffset = maxDayOffset
    }
    val windowStart = baseStart + dayOffset * 24 * 3600_000L
    val windowEnd = windowStart + 30 * 3600_000L
    // Ticks every 30s so the clock, "Now" highlighting and click
    // behaviour stay live.
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowTick = System.currentTimeMillis()
        }
    }
    val timelineScroll = rememberScrollState()
    var statusMessage by remember { mutableStateOf<String?>(null) }
    // What the header describes. Focus drives it, so moving across the
    // grid reads out each programme without having to select it.
    var focusedProgram by remember { mutableStateOf<EpgProgram?>(null) }
    var focusedChannel by remember { mutableStateOf<LiveChannel?>(null) }

    // Muted video for whatever channel focus rests on. Off unless the
    // viewer turned it on: it holds one of the provider's concurrent
    // connections for as long as it runs. Moving along a row costs
    // nothing — the channel is unchanged, so nothing re-prepares.
    val previewEnabled by vm.guidePreview.collectAsState()
    val engineChoice by vm.engine.collectAsState()
    val videoQuality by vm.videoQuality.collectAsState()
    val preview = rememberGuidePreview(engineChoice, highestQuality = videoQuality == 1)
    GuidePreviewEffect(
        controller = preview,
        enabled = previewEnabled,
        channel = focusedChannel,
    )
    // Changing category or day replaces the grid without moving focus
    // inside it, so nothing would clear these — the header would go on
    // describing a channel that is no longer listed, above a category
    // line that now says something else.
    LaunchedEffect(categoryId, dayOffset) {
        focusedChannel = null
        focusedProgram = null
    }

    LaunchedEffect(statusMessage) {
        if (statusMessage != null) {
            delay(4_000)
            statusMessage = null
        }
    }

    // Sized against the screen rather than the width this composable is
    // handed, deliberately: the rail animates its width on focus, and a
    // scale read from the live measurement would resize every cell in
    // the grid on each frame of that animation and leave the scroll
    // offset pointing at a different time than before.
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val dpPerMinute = remember(screenWidth) { guideDpPerMinute(screenWidth) }

    val density = LocalDensity.current
    // Where "now minus 15 minutes" sits on the timeline, in scroll px.
    fun nowScrollPx(): Int {
        val nowOffsetMin = ((System.currentTimeMillis() - baseStart) / 60_000L - 15)
            .coerceAtLeast(0)
        return with(density) { (dpPerMinute * nowOffsetMin.toInt()).roundToPx() }
    }

    fun jumpToNow() {
        dayOffset = 0
        scope.launch { timelineScroll.animateScrollTo(nowScrollPx()) }
    }

    // Start the timeline near "now".
    LaunchedEffect(Unit) { timelineScroll.scrollTo(nowScrollPx()) }

    // First BACK returns to now when the viewer has wandered — another
    // day, or a couple of hours along the timeline. A second BACK then
    // leaves the tab as usual. Getting home from deep in tomorrow's
    // schedule was otherwise a long march of ‹ presses.
    //
    // derivedStateOf, because reading timelineScroll.value directly in
    // composition would recompose the whole guide on every scrolled
    // frame; this only invalidates when the answer flips.
    val farThresholdPx = with(density) { (dpPerMinute * 120).roundToPx() }
    val awayFromNow by remember(dayOffset, nowTick, farThresholdPx) {
        val nowPx = nowScrollPx()
        derivedStateOf {
            dayOffset != 0 || abs(timelineScroll.value - nowPx) > farThresholdPx
        }
    }
    BackHandler(enabled = awayFromNow) { jumpToNow() }

    Column(modifier = Modifier.fillMaxSize()) {
        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.labelLarge, color = NuxColors.Secondary)
            Spacer(Modifier.height(6.dp))
        }
        notice?.let {
            // Resolved here, and remembered: the suggestion reads every
            // channel name in the playlist, and this used to rebuild that
            // whole list on every recomposition of the guide.
            val matchFailure = (it as? GuideNotice.Missing)?.matchFailure == true
            val pack = remember(bundle, matchFailure) {
                suggestedEpgPacks(
                    categoryNames = bundle.liveCategories.map { c -> c.name },
                    channelNames = if (matchFailure) bundle.channels.map { c -> c.name }
                    else emptyList(),
                ).firstOrNull()
            }
            GuideNoticeBar(
                notice = it,
                pack = pack,
                onPick = { cc -> vm.setEpgOverrideUrl(epgshareUrl(cc)) },
                onOpenSettings = onOpenSettings,
            )
            Spacer(Modifier.height(10.dp))
        }
        // Category filter, and the day the grid is showing. Two axes, so
        // the day reads as a day: a pair of bare chevrons at the head of a
        // row of category names said nothing about what they moved — they
        // could as easily have scrolled the categories themselves. One chip
        // that NAMES the day, behind a calendar icon that marks it as a
        // different kind of control, and it is gone entirely when the guide
        // has only today to show rather than sitting there inert.
        val chipsFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        val dayFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        // The territory is the GROUP, not a property of each chip. Spelling it
        // into every label made the strip read "News · United Kingdom, Sports ·
        // United Kingdom, Locals & Networks · United Kingdom…" — nineteen chips
        // repeating four words nineteen times, where the eye is trying to find
        // a section. Named once per run, the chips carry only what differs.
        val strip = remember(categories) { groupByRegion(categories) }
        val firstChipIndex = remember(strip) { strip.indexOfFirst { it is StripEntry.Chip } }
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 10.dp),
        ) {
            item(key = "__leading__") { leading() }
            itemsIndexed(strip, key = { _, e -> e.key }) { index, entry ->
                if (entry is StripEntry.Group) {
                    RegionGroupLabel(entry.label)
                    return@itemsIndexed
                }
                val category = (entry as StripEntry.Chip).category
                val locked = category.id in lockedIds
                CategoryItem(
                    name = entry.label,
                    selected = category.id == categoryId,
                    onClick = {
                        if (locked) pinPromptOpen = true
                        else onCategoryId(category.id)
                    },
                    // Locked categories still need the OK press (and
                    // its PIN prompt); dwell must not walk past a PIN.
                    onFocus = { if (!locked) focusedCategory = category.id },
                    // UP from the grid's top row lands on the first
                    // chip — always composed at the row's start, so
                    // the target requester is always attached.
                    // UP from the grid's top row lands on the first chip.
                    // It is the first CHIP, not the first entry: a region
                    // label leads the run and cannot take focus.
                    modifier = if (index == firstChipIndex) {
                        Modifier.focusRequester(chipsFocus)
                    } else Modifier,
                    locked = locked,
                )
            }
        }

        GuideHeader(
            // Lambdas, not values: read in this scope these would
            // invalidate the whole guide — LazyColumn and every visible
            // row — on each cell the cursor passes over.
            channel = { focusedChannel ?: channels.firstOrNull() },
            program = { focusedProgram },
            nowMs = nowTick,
            categoryName = categories.firstOrNull { it.id == categoryId }?.name,
            preview = {
                GuidePreviewSurface(preview, modifier = Modifier.fillMaxSize())
            },
        )
        Spacer(Modifier.height(10.dp))

        TimeRuler(
            windowStart, windowEnd, nowTick,
            nowTick + dayOffset * 24 * 3600_000L, timelineScroll, dpPerMinute,
            dayLabel = if (maxDayOffset > 0) dayLabel(baseStart, dayOffset) else null,
            // Cycles rather than clamping: one control, and the way back from
            // the last day is never a hunt for a second one that has quietly
            // greyed itself out.
            onDayClick = if (maxDayOffset > 0) {
                { dayOffset = if (dayOffset >= maxDayOffset) 0 else dayOffset + 1 }
            } else null,
            dayFocus = dayFocus,
            dayUp = chipsFocus,
        )

        GuideGrid(
            entryFocusTick = entryFocusTick,
            // UP from the grid meets the day control first — it sits directly
            // above — and UP again reaches the category strip.
            upFromTopRow = if (maxDayOffset > 0) dayFocus else chipsFocus,
            channels = channels,
            programsFor = { vm.programsFor(it) },
            programsKey = epgState,
            onChannelLongPress = onChannelLongPress,
            windowStart = windowStart,
            windowEnd = windowEnd,
            nowMs = nowTick,
            timelineScroll = timelineScroll,
            dpPerMinute = dpPerMinute,
            onFocus = { channel, program ->
                focusedChannel = channel
                focusedProgram = program
            },
            onPlayChannel = { channel ->
                // Hand the connection back before the player asks for
                // one. Two engines briefly alive at once is one too
                // many on a line that allows two, and the stream
                // refused is the one the viewer just asked for.
                preview.release()
                vm.playChannels(channels, channels.indexOf(channel))
                onPlay()
            },
            onCatchup = { channel, program ->
                scope.launch {
                    val url = vm.catchupUrl(channel, program)
                    if (url != null) {
                        preview.release()
                        vm.playCatchup(channel, program, url)
                        onPlay()
                    } else {
                        statusMessage = "Catch-up isn't available for this programme"
                    }
                }
            },
            onSchedule = { channel, program ->
                statusMessage = if (vm.scheduleRecording(channel, program)) {
                    "Recording scheduled: ${program.title}"
                } else {
                    vm.scheduleReminder(channel, program)
                    "Reminder set: ${program.title}"
                }
            },
        )
    }

    if (pinPromptOpen) {
        com.agoro.tv.ui.components.PinPrompt(
            onSubmit = { entered ->
                vm.tryUnlock(entered).also { ok -> if (ok) pinPromptOpen = false }
            },
            onDismiss = { pinPromptOpen = false },
        )
    }
}

/**
 * Broadcast-style header: what the cursor is sitting on, described in full,
 * above the grid. The grid can only ever show a truncated title, so without
 * this you have to select a programme to find out what it is.
 */
@Composable
private fun GuideHeader(
    channel: () -> LiveChannel?,
    program: () -> EpgProgram?,
    nowMs: Long,
    categoryName: String?,
    /** Video for the focused channel, when previewing is on and one is running. */
    preview: @Composable () -> Unit = {},
) {
    val timeFmt = rememberClockFormat()
    val dateFmt = remember { SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()) }
    val current = channel()
    val currentProgram = program()

    Row(
        // Fixed, deliberately. heightIn(min=) let the artwork's fillMaxSize
        // resolve against all remaining height — a Column measures children
        // against what's left — so the header swallowed the screen and the
        // ruler and grid were laid out at zero height. A fixed height also
        // keeps the grid from shifting vertically as focus moves between
        // programmes with and without a synopsis.
        modifier = Modifier.fillMaxWidth().height(HEADER_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Channel artwork, with the live preview drawn over it when that is
        // switched on. Off by default and gated on a dwell, because previewing
        // every channel focus passes over would open a stream per channel and
        // providers cap concurrent connections — see GuidePreview.kt.
        Box(
            modifier = Modifier
                .width(200.dp)
                .fillMaxHeight()
                .clip(NuxShape.Row)
                .background(NuxColors.Surface),
            contentAlignment = Alignment.Center,
        ) {
            Artwork(
                imageUrl = current?.logo,
                title = current?.displayName.orEmpty(),
                // No extra padding: Artwork already insets a Fit logo by a
                // fraction of the box, so a 20dp ring on top of it left the
                // logo adrift in a 200dp panel of empty grey.
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                monogramStyle = MaterialTheme.typography.headlineSmall,
                // The panel around this already IS the container. Artwork's
                // own slab drew a second, square-cornered box inside the
                // rounded one, framing the logo twice.
                background = androidx.compose.ui.graphics.Color.Transparent,
            )
            // Drawn over the logo rather than instead of it: the stream takes a
            // moment to give a first frame, and swapping to an empty black box
            // in the meantime reads worse than the logo staying put.
            preview()
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = currentProgram?.title ?: current?.displayName ?: "Guide",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = NuxColors.OnSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        // One contextual chip teaches what OK does to the
                        // focused programme — the grid's cells stay clean.
                        if (currentProgram != null) {
                            when {
                                nowMs in currentProgram.startMs until currentProgram.endMs ->
                                    MetaChip("ON NOW", accent = true)
                                currentProgram.startMs > nowMs ->
                                    MetaChip(
                                        if (current?.recordUrl != null) "OK to record"
                                        else "OK to remind"
                                    )
                                (current?.archiveDays ?: 0) > 0 ->
                                    MetaChip("OK for catch-up")
                                else -> Unit
                            }
                        }
                    }
                    if (currentProgram != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${timeFmt.format(Date(currentProgram.startMs))} – " +
                                timeFmt.format(Date(currentProgram.endMs)),
                            style = MaterialTheme.typography.labelLarge,
                            color = NuxColors.OnSurfaceDim,
                        )
                    }
                }
                // Bounded: an unweighted sibling is measured against the full
                // width first, so a long playlist name would leave the title a
                // few characters before ellipsis.
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.widthIn(max = 300.dp),
                ) {
                    Text(
                        text = "${timeFmt.format(Date(nowMs))}  •  ${dateFmt.format(Date(nowMs))}",
                        style = MaterialTheme.typography.labelMedium,
                        color = NuxColors.OnSurface,
                    )
                }
            }

            // Progress only means something for whatever is on right now.
            if (currentProgram != null && nowMs in currentProgram.startMs until currentProgram.endMs) {
                Spacer(Modifier.height(8.dp))
                val span = (currentProgram.endMs - currentProgram.startMs).coerceAtLeast(1)
                val progress = ((nowMs - currentProgram.startMs).toFloat() / span).coerceIn(0f, 1f)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(220.dp)
                            .height(5.dp)
                            .clip(NuxShape.Track)
                            .background(NuxColors.SurfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress)
                                .background(NuxColors.Primary)
                        )
                    }
                    // Rounded up: integer division reported "0 minutes left"
                    // for the last minute, beside a bar that wasn't full.
                    val minutesLeft =
                        ((currentProgram.endMs - nowMs + 59_999) / 60_000L).coerceAtLeast(0L)
                    Text(
                        text = if (minutesLeft == 1L) "1 minute left" else "$minutesLeft minutes left",
                        style = MaterialTheme.typography.labelMedium,
                        color = NuxColors.OnSurfaceDim,
                        maxLines = 1,
                    )
                }
            }

            if (!currentProgram?.description.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = currentProgram?.description.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuxColors.OnSurfaceDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** What, if anything, is wrong with the guide sitting above the grid. */
internal sealed interface GuideNotice {
    /** The download is still in flight; the grid shows what the cache has. */
    data object Loading : GuideNotice

    /**
     * No usable guide. [matchFailure] separates "the download failed" from
     * "it downloaded but matched almost nothing" — two different sentences to
     * the viewer, and better suggestions in the second case, where channel
     * names are known to be worth reading.
     *
     * The provider's own wording ("isn't publishing a guide", "server didn't
     * respond") is deliberately NOT carried here. All of it meant the same
     * thing on screen — no guide, pick another — and spending a second line of
     * a TV banner on which flavour of nothing arrived is a diagnostic, not
     * copy. It still reaches logcat from ContentRepository.
     */
    data class Missing(val matchFailure: Boolean) : GuideNotice
}

/**
 * A one-line band above the grid explaining why the lanes are empty, with the
 * free-guide shortcuts inline.
 *
 * This used to be a full-screen pane that replaced the grid — which also
 * removed every channel, since the grid is the channel list. It is a banner
 * now: it explains, it offers a fix, and it never stands between the viewer
 * and the thing they came to watch.
 */
@Composable
private fun GuideNoticeBar(
    notice: GuideNotice,
    /** Best-guess country pack for this playlist, or null when nothing hints at one. */
    pack: String?,
    onPick: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(NuxShape.Row)
            .background(NuxColors.SurfaceVariant)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (notice is GuideNotice.Loading) {
            androidx.compose.material3.CircularProgressIndicator(
                color = NuxColors.Primary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "Loading the guide — channels are ready to watch now.",
                style = MaterialTheme.typography.labelLarge,
                color = NuxColors.OnSurfaceDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            return@Row
        }

        val missing = notice as GuideNotice.Missing
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            tint = NuxColors.Primary,
            modifier = Modifier.size(18.dp),
        )
        // One sentence, and it names which of the two failures happened — a
        // guide that downloaded but doesn't fit this playlist is a different
        // problem from no guide at all, and the viewer can tell them apart
        // without being handed the HTTP reason.
        Text(
            // Both fit one line at the width the bar actually gets; the
            // match-failure sentence carried "— showing channels only" too and
            // ellipsised on a 4K panel, which is the width there is most of.
            // The grid underneath is visibly full of channels, so that clause
            // was spending the only line on something already on screen.
            text = if (missing.matchFailure) {
                "This guide doesn't match your channels"
            } else {
                "No guide — showing channels only"
            },
            style = MaterialTheme.typography.labelLarge,
            color = NuxColors.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // One button, not a rank of seven country codes. The app already knows
        // which pack fits — suggestedEpgPacks reads it off the playlist's own
        // categories and channel-name tags — so asking the viewer to choose
        // was the app declining to decide. When nothing hints at a country
        // there is no honest guess to offer, and Settings owns the full list.
        if (pack != null) {
            androidx.tv.material3.OutlinedButton(onClick = { onPick(pack) }) {
                Text("Use free $pack guide", style = MaterialTheme.typography.labelMedium)
            }
        } else {
            androidx.tv.material3.OutlinedButton(onClick = onOpenSettings) {
                Text("Choose a guide", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}


/**
 * Day-pager chevron that is only focusable while it can act. tv-material's
 * disabled buttons deliberately stay focusable but draw no focus ring, which
 * here made the first focus entry into the tab land on an invisible, inert
 * control.
 */
/** "Today", "Tomorrow", then the date itself — never a bare offset. */
private fun dayLabel(baseStartMs: Long, offset: Int): String = when (offset) {
    0 -> "Today"
    1 -> "Tomorrow"
    else -> SimpleDateFormat("EEE d MMM", Locale.getDefault())
        .format(Date(baseStartMs + offset * 24 * 3600_000L))
}

/** One entry in the category strip: a territory's name, or a shelf. */
private sealed interface StripEntry {
    val key: String
    val label: String

    data class Group(override val label: String) : StripEntry {
        override val key get() = "__group__$label"
    }

    data class Chip(val category: Category, override val label: String) : StripEntry {
        override val key get() = category.id
    }
}

/**
 * Splits "News · United Kingdom" back into the shelf and the territory, and
 * emits the territory once ahead of its run.
 *
 * The id carries the territory CODE and the name carries its LABEL, so the run
 * is detected on the id — stable — while what the viewer reads comes from the
 * name. Entries with no territory (All channels, Recent, Favorites) pass
 * through untouched and start no group.
 */
private fun groupByRegion(categories: List<Category>): List<StripEntry> = buildList {
    var lastRegion: String? = null
    categories.forEach { category ->
        val region = category.id.substringBefore('|').takeIf { category.id.contains('|') }
        val shelf = if (region != null) category.name.substringBefore(SHELF_SEPARATOR) else category.name
        if (region != null && region != lastRegion) {
            add(StripEntry.Group(category.name.substringAfter(SHELF_SEPARATOR, category.name)))
        }
        lastRegion = region
        add(StripEntry.Chip(category, shelf.trim()))
    }
}

/** Matches the separator [ManifestCuration] builds shelf labels with. */
private const val SHELF_SEPARATOR = " · "

/**
 * The territory heading inside the strip. Not focusable and not a target: it
 * names the run that follows, so travelling the row still steps shelf to shelf.
 */
@Composable
private fun RegionGroupLabel(label: String) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = NuxColors.OnSurfaceDim,
        maxLines = 1,
        modifier = Modifier.padding(start = 14.dp, end = 6.dp),
    )
}
