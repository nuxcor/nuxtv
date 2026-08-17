@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.nuxcor.nuxtv.ui.screens

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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuxcor.nuxtv.MainViewModel
import com.nuxcor.nuxtv.data.ContentBundle
import com.nuxcor.nuxtv.data.LiveChannel
import com.nuxcor.nuxtv.ui.components.ContextMenu
import com.nuxcor.nuxtv.ui.components.MenuAction
import com.nuxcor.nuxtv.ui.components.requestFocusRetrying
import com.nuxcor.nuxtv.ui.components.StatusPane
import com.nuxcor.nuxtv.ui.theme.NuxColors
import com.nuxcor.nuxtv.ui.theme.NuxFocus
import com.nuxcor.nuxtv.ui.theme.NuxShape
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
        // Number first, position second — the same rule the row labels and the
        // player's keypad use, so typing what you see always lands on it.
        val target = channels.indexOfFirst { it.number == jump.jumpNumber }
            .takeIf { it >= 0 } ?: (jump.jumpNumber - 1)
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
internal fun LiveTab(vm: MainViewModel, bundle: ContentBundle, onPlay: () -> Unit) {
    if (bundle.channels.isEmpty()) {
        StatusPane(title = "No live channels", message = "This playlist has no live streams")
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
    val channels = remember(allVisible, activeCategory, favorites, recents) {
        channelsInCategory(activeCategory, allVisible, favorites, recents)
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onFocusEvent { state ->
                if (state.hasFocus && !guideHasFocus) entryFocusTick++
                guideHasFocus = state.hasFocus
            },
    ) {
    // One surface. The grid IS the channel list — its channel column carries
    // the logos, numbers, keypad jump and hold-OK menu the list view used to
    // own, and every channel's schedule sits beside it instead of behind a
    // toggle. Two views of the same channels meant the same press did
    // different things depending on a switch set weeks ago.
    GuideTab(
        entryFocusTick = entryFocusTick,
        vm = vm,
        bundle = bundle,
        onPlay = onPlay,
        categoryId = activeCategory,
        onCategoryId = { selectedCategory = it },
        onChannelLongPress = { menuChannel = it },
    )
    scheduleChannel?.let { channel ->
        val programs = remember(channel.id, epgState) { vm.programsFor(channel) }
        ChannelSchedule(
            channel = channel,
            programs = programs,
            nowMs = System.currentTimeMillis(),
            onWatch = {
                scheduleChannel = null
                vm.playChannels(channels, channels.indexOf(channel).coerceAtLeast(0))
                onPlay()
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
                    vm.playChannels(channels, channels.indexOf(channel).coerceAtLeast(0))
                    onPlay()
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
            onDismiss = { scheduleChannel = null },
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
            title = channel.name,
            actions = buildList {
                add(
                    MenuAction("Play") {
                        vm.playChannels(channels, channels.indexOf(channel).coerceAtLeast(0))
                        onPlay()
                    }
                )
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
            onDismiss = { menuChannel = null },
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
) {
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(NuxShape.Chip),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) NuxColors.Primary.copy(alpha = 0.16f) else Color.Transparent,
            focusedContainerColor = NuxColors.SurfaceRaised,
            // Selected stays gold even while focused.
            contentColor = if (selected) NuxColors.Primary else NuxColors.OnSurfaceDim,
            focusedContentColor = if (selected) NuxColors.Primary else NuxColors.OnSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = NuxFocus.ButtonScale,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = NuxFocus.ring8,
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
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
