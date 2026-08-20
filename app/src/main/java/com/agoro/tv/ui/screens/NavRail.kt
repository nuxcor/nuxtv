@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.agoro.tv.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.agoro.tv.R
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxFocus
import com.agoro.tv.ui.theme.NuxMotion
import com.agoro.tv.ui.theme.NuxShape
import com.agoro.tv.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Shared by the rail and the content lane so the two can never disagree. */
// Not private: the guide sizes its timeline against the width it will actually
// have, and the rail is part of that budget.
internal val RAIL_WIDTH_COLLAPSED = 64.dp
internal val RAIL_WIDTH_EXPANDED = 190.dp

enum class HomeTab(val label: String, val icon: ImageVector) {
    // Enum order is rail order; Home leads because it is the landing tab.
    Home("Home", Icons.Default.Home),
    Search("Search", Icons.Default.Search),
    Live("Live TV", Icons.Default.LiveTv),
    Movies("Movies", Icons.Default.Movie),
    Series("Shows", Icons.Default.VideoLibrary),
    Recordings("Recordings", Icons.Default.Videocam),
    Settings("Settings", Icons.Default.Settings),
}

@Composable
internal fun NavRail(
    selected: HomeTab,
    onSelect: (HomeTab) -> Unit,
    railFocus: FocusRequester,
    onRailFocusChanged: (Boolean) -> Unit,
    /**
     * Uptime of the last real key press anywhere on the screen. The dwell
     * only acts on focus changes that closely follow one: the system also
     * moves focus by itself (splash dismissal re-runs default placement,
     * restorers fire), and those moves are indistinguishable from user
     * travel by timing alone — selecting a tab from them changed the screen
     * before the viewer pressed anything.
     */
    lastUserKeyMs: () -> Long,
    /**
     * Marks the Settings item with a small dot — an update is waiting there.
     * The app never interrupts playback or browsing over an update, so this
     * dot is the entire nudge; Settings itself carries the version and the
     * install button.
     */
    settingsBadge: Boolean = false,
) {
    // Always the labeled form: the rail only exists while summoned now, and
    // a drawer that arrives icons-first and then widens reads as two
    // animations stacked on one entrance.
    val expanded = true
    // Focus travel selects a tab only after the focus rests briefly, so
    // moving down the rail doesn't compose every tab it passes through.
    var focusedItem by remember { mutableStateOf<HomeTab?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(focusedItem) {
        val item = focusedItem ?: return@LaunchedEffect
        delay(NuxMotion.TabDwellMs.toLong())
        onSelect(item)
    }
    val width = RAIL_WIDTH_EXPANDED

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(width)
            // Every entry into the rail lands on the SELECTED tab's item.
            // Left to the geometric search, a LEFT from the content lane
            // landed on whichever rail item was vertically adjacent (the
            // logo lockup offsets the items ~70dp below the category list,
            // so the top categories beam onto Search and Live), and the
            // dwell then switched the whole screen to that tab. A
            // focusRestorer could not fix this: without a focus group the
            // restorer's onEnter never fires, and the key-less forEach
            // below gives all six items one compositeKeyHash, so a restore
            // always resolved to the first item — Search.
            .focusProperties { onEnter = { railFocus.requestFocus() } }
            .focusGroup()
            // Opaque so overlaid content never shows through the rail.
            .background(NuxColors.Background)
            // A hairline on the trailing edge so the rail reads as a plane, not
            // a gap, against lifted-black panels.
            .drawBehind {
                drawLine(
                    color = NuxColors.StrokeSoft,
                    start = Offset(size.width - 0.5f, 0f),
                    end = Offset(size.width - 0.5f, size.height),
                    strokeWidth = 1f,
                )
            }
            .onFocusChanged {
                onRailFocusChanged(it.hasFocus)
                if (!it.hasFocus) focusedItem = null // cancel pending select-on-focus
            }
            .padding(horizontal = Space.s, vertical = Space.gutterVertical),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        // The brand mark alone — the wordmark came off: the mark is
        // distinctive enough to carry identity, and a drawer that opens with
        // its own name in caps read as the app introducing itself on every
        // visit.
        Image(
            painter = painterResource(R.drawable.ic_logo),
            contentDescription = "Agoro",
            // ic_logo, not ic_splash: the splash copy is padded into a
            // square and scaled for its circular mask, so drawing it here
            // gave about 59% of the size asked for.
            modifier = Modifier
                .padding(start = 10.dp, bottom = 20.dp)
                .height(48.dp)
                .width(35.dp),
        )
        // Search is reached from Home's top-right pill, not the rail — a
        // launcher lists destinations, and search is an action. It is offered
        // in Home's empty state too, so the one control that makes a
        // 20,000-item playlist usable is never out of reach on a fresh
        // install, which is what put it here in the first place.
        HomeTab.entries.filterNot { it == HomeTab.Search }.forEach { item ->
            // railFocus must be attached somewhere even while the Search tab
            // (railless) is selected, or entering the rail has no target.
            val holdsFocus = item == selected ||
                (selected == HomeTab.Search && item == HomeTab.Home)
            RailItem(
                item = item,
                selected = item == selected,
                expanded = expanded,
                badge = settingsBadge && item == HomeTab.Settings,
                onClick = { onSelect(item) },
                onItemFocused = {
                    val userDriven =
                        android.os.SystemClock.uptimeMillis() - lastUserKeyMs() < 1_200
                    if (userDriven) {
                        focusedItem = item
                    } else if (item != selected) {
                        // A system-driven move (no key behind it): never select
                        // from it, and put the ring back on the selected tab so
                        // the resting state stays honest.
                        scope.launch { runCatching { railFocus.requestFocus() } }
                    }
                },
                modifier = if (holdsFocus) {
                    Modifier.fillMaxWidth().focusRequester(railFocus)
                } else {
                    Modifier.fillMaxWidth()
                },
            )
        }
    }
}

@Composable
private fun RailItem(
    item: HomeTab,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    onItemFocused: () -> Unit = onClick,
    modifier: Modifier = Modifier.fillMaxWidth(),
    badge: Boolean = false,
) {
    Surface(
        onClick = onClick,
        // Tabs switch as focus travels the rail — no OK press needed.
        modifier = modifier.onFocusChanged { if (it.isFocused) onItemFocused() },
        shape = ClickableSurfaceDefaults.shape(NuxShape.Row),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) NuxColors.Primary.copy(alpha = 0.18f) else Color.Transparent,
            focusedContainerColor = NuxColors.SurfaceRaised,
            contentColor = if (selected) NuxColors.Primary else NuxColors.OnSurfaceDim,
            focusedContentColor = NuxColors.OnSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = NuxFocus.RowScale,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = NuxFocus.ring12),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The tint alone is hard to read at icon size from 10 feet when
                // the rail is collapsed; the same gold bar WideItem uses marks
                // the selected tab unambiguously. Drawn, not laid out, so the
                // icon never shifts.
                .drawBehind {
                    if (selected && !expanded) {
                        val barHeight = 18.dp.toPx()
                        drawRoundRect(
                            color = NuxColors.Primary,
                            topLeft = Offset(0f, (size.height - barHeight) / 2f),
                            size = androidx.compose.ui.geometry.Size(3.dp.toPx(), barHeight),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                        )
                    }
                }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                item.icon,
                contentDescription = item.label,
                modifier = Modifier
                    .size(22.dp)
                    // Drawn, not laid out, like the selection bar — the icon
                    // never shifts. Secondary, not gold: teal already means
                    // "an update is available" in Settings' own copy, and
                    // gold here would read as a second selected tab. Sits
                    // just off the icon's corner so the gear stays whole.
                    .drawBehind {
                        if (badge) {
                            drawCircle(
                                color = NuxColors.Secondary,
                                radius = 3.dp.toPx(),
                                center = Offset(size.width + 1.dp.toPx(), (-1).dp.toPx()),
                            )
                        }
                    },
            )
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(160, delayMillis = 120)),
                exit = fadeOut(tween(80)),
            ) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}
