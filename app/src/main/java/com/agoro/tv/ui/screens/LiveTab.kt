@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.agoro.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.agoro.tv.MainViewModel
import kotlinx.coroutines.launch
import com.agoro.tv.data.ContentBundle
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.ui.components.ContextMenu
import com.agoro.tv.ui.components.MenuAction
import com.agoro.tv.ui.components.requestFocusRetrying
import com.agoro.tv.ui.components.StatusAction
import com.agoro.tv.ui.components.StatusPane
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxFocus
import com.agoro.tv.ui.theme.NuxShape
import kotlinx.coroutines.delay

// --- channel-number jump ------------------------------------------------------

/**
 * Channel-number entry for a channel list: collects digits, then jumps.
 *
 * Parks focus on the row it scrolls to, which is the part that was missing.
 * Scrolling alone left the focused row where it was — off-screen — so the next
 * D-pad press moved from *there* and the list snapped straight back to where it
 * started. The jump appeared to work and then undid itself on the following
 * press, which reads as the feature being broken rather than as focus being in
 * the wrong place.
 */
@Stable
internal class ChannelJump(val listState: LazyListState) {
    val focusRequester = FocusRequester()
    var digits by mutableStateOf("")
    var targetIndex by mutableIntStateOf(-1)

    /** Hand-off from the digit collector to the executor. A state counter, so
     *  the executor effect restarts per jump; the number itself is a plain
     *  field because nothing needs to observe it. */
    var jumpTick by mutableIntStateOf(0)
    var jumpNumber: Int = -1
}

@Composable
internal fun rememberChannelJump(
    channels: List<LiveChannel>,
): ChannelJump {
    val listState = rememberLazyListState()
    val jump = remember(listState) { ChannelJump(listState) }
    // Two effects, not one: this collector clears digits, and an effect keyed
    // on digits cancels itself the moment it does that — the scroll and the
    // focus retries below were dying at their first suspension point, leaving
    // exactly the scrolled-but-not-focused snap-back this class exists to fix.
    LaunchedEffect(jump.digits) {
        if (jump.digits.isEmpty()) return@LaunchedEffect
        delay(1_200)
        val typed = jump.digits.toIntOrNull()
        // No suspension after this write: cancellation is cooperative, so the
        // hand-off still runs.
        jump.digits = ""
        if (typed != null) {
            jump.jumpNumber = typed
            jump.jumpTick++
        }
    }
    LaunchedEffect(jump.jumpTick, channels) {
        if (jump.jumpTick == 0) return@LaunchedEffect
        // By number only — numbers are positions over the whole list and
        // this list may be a category's slice of it, where "the fifth row"
        // is not channel 5.
        val target = channels.indexOfFirst { it.number == jump.jumpNumber }
        if (target !in channels.indices) return@LaunchedEffect
        jump.targetIndex = target
        jump.listState.scrollToItem(target)
        // The row composes a frame after the scroll; retry briefly.
        jump.focusRequester.requestFocusRetrying()
    }
    return jump
}

/** Collects digit presses into [jump]. Goes on the list that scrolls. */
internal fun Modifier.channelJumpKeys(jump: ChannelJump): Modifier =
    this.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) {
            return@onPreviewKeyEvent false
        }
        val code = event.key.nativeKeyCode
        if (code in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9) {
            jump.digits += (code - android.view.KeyEvent.KEYCODE_0).toString()
            true
        } else false
    }

/** The "Channel 205" readout while digits are still being collected. */
@Composable
internal fun ChannelJumpBadge(digits: String, modifier: Modifier = Modifier) {
    if (digits.isEmpty()) return
    Box(
        modifier = modifier
            .background(NuxColors.Scrim, NuxShape.Row)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            "Channel $digits",
            style = MaterialTheme.typography.titleMedium,
            color = NuxColors.Primary,
        )
    }
}

// --- Live TV -----------------------------------------------------------------

@Composable
internal fun LiveTab(
    vm: MainViewModel,
    bundle: ContentBundle,
    onPlay: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    if (bundle.channels.isEmpty()) {
        StatusPane(
            title = "No live channels",
            message = "This playlist doesn't carry live TV.",
            icon = Icons.Default.LiveTv,
            primaryAction = StatusAction("Switch playlist", onOpenSettings),
        )
        return
    }
    val favorites by vm.favorites.collectAsState()
    var menuChannel by remember { mutableStateOf<LiveChannel?>(null) }
    var scheduleChannel by remember { mutableStateOf<LiveChannel?>(null) }
    // Filtering/merging happens off the main thread in the ViewModel.
    val allVisible by vm.displayChannels.collectAsState()
    val recents by vm.recentChannels.collectAsState()
    val categories = remember(bundle, favorites, recents, allVisible) {
        liveCategoryList(bundle, allVisible, favorites, recents)
    }
    var selectedCategory by rememberSaveable(bundle.channels.size) { mutableStateOf(CATEGORY_ALL) }
    // Recent and Favorites come and go as the viewer watches and stars things,
    // so the selection can outlive the category it names.
    val activeCategory = resolveCategoryId(selectedCategory, categories)
    // Ordering is applied in the ViewModel from the Settings preference.
    // Needed here (not just inside the guide) because the schedule sheet and
    // the context menu play from this list.
    val allView by vm.allChannelsView.collectAsState()
    // Grouped once off the main thread, so a category switch is a lookup
    // and not a filter over every channel — see LiveCategoryIndex.
    val byCategory by vm.channelsByCategory.collectAsState()
    val channels = remember(allVisible, activeCategory, favorites, recents, allView, byCategory) {
        channelsInCategory(
            activeCategory, allVisible, favorites, recents,
            allChannels = allView, byCategory = byCategory,
        )
    }
    val epgState by vm.epgState.collectAsState()

    // Focus discipline for the guide, both directions:
    // - Entering from the rail redirects to a programme cell (via the tick),
    //   instead of Compose's geometric landing — which picked the day pager
    //   or a clipped sliver cell with no visible ring.
    // - Leaving is LEFT-only (to the rail) — a DOWN from the day pager used
    //   to land geometrically on the rail, where the dwell then switched the
    //   whole screen to another tab.
    var entryFocusTick by remember { mutableStateOf(0) }
    // Focus-entry detection via the subtree's focus state, not
    // focusProperties.onEnter: Compose's directional (2D) search treats
    // group boundaries as transparent and never calls onEnter, so that hook
    // silently missed every D-pad entry. hasFocus on the wrapper flips when
    // any descendant takes focus — that edge IS the entry, whatever caused
    // it; the tick then redirects to a programme cell (one frame late at
    // worst).
    var guideHasFocus by remember { mutableStateOf(false) }
    val focusScope = rememberCoroutineScope()
    var guideLossJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onFocusEvent { state ->
                // Sustained-loss discipline: moving focus BETWEEN two children
                // of this subtree reports a one-frame hasFocus=false blip, and
                // treating that as a fresh entry made the tick yank focus back
                // to the grid — the category chips could receive focus for one
                // frame and never keep it. Only a real exit (>120ms outside)
                // re-arms the entry redirect.
                if (state.hasFocus) {
                    guideLossJob?.cancel()
                    guideLossJob = null
                    if (!guideHasFocus) {
                        guideHasFocus = true
                        entryFocusTick++
                    }
                } else if (guideHasFocus) {
                    guideLossJob = focusScope.launch {
                        kotlinx.coroutines.delay(120)
                        guideHasFocus = false
                    }
                }
            },
    ) {
    // One surface. The grid IS the channel list — its channel column carries
    // the logos, numbers, keypad jump and hold-OK menu the list view used to
    // own, and every channel's schedule sits beside it instead of behind a
    // toggle. Two views of the same channels meant the same press did
    // different things depending on a switch set weeks ago.
    val gridHandle = remember { GuideGridHandle() }
    // Overlays here are in-layout: when one closes, the row that held focus
    // is simply gone and Compose reseats focus on the nearest thing it can
    // find — the first category chip, whose dwell then switched the whole
    // guide. Hand focus back to the row the menu was opened on instead.
    // After a beat, because the request loses to that same reseating if it
    // fires in the frame the overlay unmounts.
    fun refocusGrid() {
        focusScope.launch {
            kotlinx.coroutines.delay(120)
            gridHandle.focusAnchor()
        }
    }
    fun playFromHost(channel: LiveChannel) {
        gridHandle.beforePlay()
        vm.playChannels(channels, channels.indexOf(channel).coerceAtLeast(0))
        onPlay()
    }
    GuideTab(
        entryFocusTick = entryFocusTick,
        vm = vm,
        bundle = bundle,
        onPlay = onPlay,
        categoryId = activeCategory,
        onCategoryId = { selectedCategory = it },
        onChannelLongPress = { menuChannel = it },
        onOpenSettings = onOpenSettings,
        gridHandle = gridHandle,
    )
    scheduleChannel?.let { channel ->
        // Read from the guide table rather than the resident window: this
        // sheet puts a line of synopsis under every title, and the window
        // deliberately carries none. One channel's worth, one query.
        // Null until the query lands: an empty initial value showed "No guide
        // data for this channel" for a frame or two on every open, on a sheet
        // that is only offered when there is guide data.
        val programs by androidx.compose.runtime.produceState<List<com.agoro.tv.data.EpgProgram>?>(
            initialValue = null,
            channel.id,
            epgState,
        ) {
            val from = System.currentTimeMillis() - 3600_000L
            value = vm.scheduleFor(channel, from, from + 8L * 24 * 3600_000)
        }
        ChannelSchedule(
            channel = channel,
            programs = programs,
            nowMs = System.currentTimeMillis(),
            onWatch = {
                scheduleChannel = null
                playFromHost(channel)
            },
            onSelectProgram = { program ->
                // Same rules as the guide: what a programme offers depends on
                // whether it is on now or still to come.
                //
                // "Started already" rather than "on now", because the list is
                // filtered against a clock that ticks every 30 seconds while
                // this reads the real one. In the seconds after a programme
                // ends the row still says ON NOW, and treating that press as a
                // future programme sent it to scheduleRecording — which
                // succeeds for any recordable channel and clamps its alarm to
                // now, so a press meant to watch instead began recording
                // something already over. Both cases play the channel.
                val now = System.currentTimeMillis()
                if (program.startMs <= now) {
                    scheduleChannel = null
                    playFromHost(channel)
                    null
                } else if (vm.scheduleRecording(channel, program)) {
                    "Recording scheduled: ${program.title}"
                } else {
                    // Same fallback the guide uses: a channel the provider
                    // won't let us record can still be remembered. Sending the
                    // viewer to the guide to do what this screen could have
                    // done is not an answer.
                    vm.scheduleReminder(channel, program)
                    "Reminder set: ${program.title}"
                }
            },
            onDismiss = {
                scheduleChannel = null
                refocusGrid()
            },
        )
    }
    menuChannel?.let { channel ->
        val isFav = channel.url in favorites
        // Counted the way the schedule sheet counts, which is not the same as
        // "has any programmes at all": the parsed window keeps 30 hours of
        // finished ones, while the sheet lists only what has yet to end.
        val hasSchedule = remember(channel.id, epgState) {
            val now = System.currentTimeMillis()
            vm.programsFor(channel).any { it.endMs > now }
        }
        ContextMenu(
            title = channel.displayName,
            actions = buildList {
                add(MenuAction("Play") { playFromHost(channel) })
                // Offered only when there is something to show — otherwise this
                // is a menu row that opens an empty sheet.
                if (hasSchedule) {
                    add(MenuAction("What's on") { scheduleChannel = channel })
                }
                add(
                    MenuAction(if (isFav) "Remove from favorites" else "Add to favorites") {
                        vm.toggleFavorite(channel)
                    }
                )
                add(MenuAction("Hide this channel") { vm.toggleHidden(channel) })
            },
            onDismiss = {
                menuChannel = null
                refocusGrid()
            },
        )
    }
    }
}

@Composable
fun CategoryItem(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    locked: Boolean = false,
    onFocus: () -> Unit = {},
    /**
     * Focus left the chip. Dwell-select owners cancel their pending select
     * here: a chip focus passes over on its way somewhere else — the shell's
     * parking on a return from the player, a grid's UP redirect — must not
     * go on to switch the category a quarter-second after focus has gone.
     */
    onBlur: () -> Unit = {},
) {
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocus() else onBlur() },
        // 14dp: at 8dp these read as rectangles with the corners knocked
        // off, and at a full capsule they read as lozenges — more shape than
        // the word inside needs on something this wide and short.
        shape = ClickableSurfaceDefaults.shape(NuxShape.FilterChip),
        // Focus is a FILL, not an outline. A 2dp ring is a desktop idiom read
        // from 60cm; across a room the eye finds a solid shape long before it
        // finds a hairline, and it is what every chip strip on this platform
        // does. It also ends the argument about the ring's corners — there is
        // no ring.
        //
        // Selection keeps gold and focus takes white, so the two never have to
        // be told apart by brightness alone: a chip can be selected, focused,
        // both, or neither, and all four read differently.
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) NuxColors.SelectedContainer else Color.Transparent,
            focusedContainerColor = NuxColors.FocusBorder,
            contentColor = if (selected) NuxColors.Primary else NuxColors.OnSurfaceDim,
            // Dark ON the fill: white text on a white chip is a blank pill.
            focusedContentColor = NuxColors.Background,
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = NuxFocus.ButtonScale,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border.None,
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            // Wider than it is tall, which is what makes a pill read as one.
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                // Two lines: provider category names ("DREAMWORKS ANIMATION",
                // "PARAMOUNT PICTURES") truncated to gibberish on one.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // No weight: in the guide's horizontal chip row the incoming
                // width is unbounded, and a weighted child in an unbounded Row
                // measures at zero — every chip collapsed to an empty blob.
            )
            if (locked) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = "Locked category",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
