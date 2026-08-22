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
import androidx.compose.material.icons.filled.SportsSoccer
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
    // Beside Live TV because that is what it is — live, just organised by
    // fixture instead of by channel. "Sport" and not "Sports": the Live TV
    // strip already has a Sports shelf of channels, and two rail-level things
    // reading the same word would be two names for what a viewer would assume
    // is one place.
    Sport("Sport", Icons.Default.SportsSoccer),
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
    // No select-on-dwell: that belonged to the always-visible rail, where
    // travelling it previewed each tab live. In a modal drawer the dwell's
    // tab switch recomposed the screen UNDER the open drawer, and the
    // reshuffle bounced focus back to the first item — UP/DOWN read as dead
    // on real hardware. A drawer navigates freely; OK commits.
    val width = RAIL_WIDTH_EXPANDED

    // UP/DOWN are handled by hand, not left to the geometric search: inside
    // this overlaid focus group the search proved unreliable (it refused the
    // move outright on some devices), and a fixed vertical list needs no
    // geometry — the next item is an index, not a direction.
    val items = remember { HomeTab.entries.filterNot { it == HomeTab.Search } }
    val itemFocus = remember { items.map { FocusRequester() } }
    var focusedIndex by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(width)
            // No onEnter redirect. The old rail used one so a geometric LEFT
            // from content landed on the selected item — but onEnter fires on
            // EVERY transfer into the group, including the explicit
            // requestFocus that moves between items below, and it snapped
            // each one back to the selected tab: the whole drawer read as
            // frozen on Home. Entry is openRail()'s explicit request now;
            // there is no geometric side door left to guard.
            .focusGroup()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> {
                        itemFocus[(focusedIndex + 1).coerceAtMost(items.lastIndex)]
                            .requestFocus(); true
                    }
                    Key.DirectionUp -> {
                        itemFocus[(focusedIndex - 1).coerceAtLeast(0)]
                            .requestFocus(); true
                    }
                    else -> false
                }
            }
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
            .onFocusChanged { onRailFocusChanged(it.hasFocus) }
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
        items.forEachIndexed { index, item ->
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
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(itemFocus[index])
                    .then(if (holdsFocus) Modifier.focusRequester(railFocus) else Modifier)
                    .onFocusChanged { if (it.isFocused) focusedIndex = index },
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
    modifier: Modifier = Modifier.fillMaxWidth(),
    badge: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(NuxShape.Row),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) NuxColors.SelectedContainer else Color.Transparent,
            focusedContainerColor = NuxColors.SurfaceRaised,
            contentColor = if (selected) NuxColors.Primary else NuxColors.OnSurfaceDim,
            // Gold survives focus: the drawer opens WITH focus on the current
            // tab, and with white-on-focus the viewer couldn't tell which tab
            // they were on until they moved off it.
            focusedContentColor = if (selected) NuxColors.Primary else NuxColors.OnSurface,
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
