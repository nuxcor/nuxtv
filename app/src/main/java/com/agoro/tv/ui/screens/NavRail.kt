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
import androidx.compose.material.icons.filled.SystemUpdateAlt
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
    Settings("Settings", Icons.Default.Settings),
}

@Composable
internal fun NavRail(
    selected: HomeTab,
    onSelect: (HomeTab) -> Unit,
    railFocus: FocusRequester,
    onRailFocusChanged: (Boolean) -> Unit,
    /**
     * What the update row under Settings should say, or null when there is
     * nothing to offer and the row does not exist.
     *
     * This replaced a dot on the Settings icon. The dot was the entire nudge
     * and it pointed at a screen rather than at the thing — a viewer who saw
     * it had to know that a mark on a gear meant a new version, then go and
     * find it. A row that says "Update to 2.34.0" says both, and it costs
     * nothing when there is no update because then it is not there. Still
     * never interrupts: it waits in the drawer, it does not open one.
     */
    updateLabel: String? = null,
    onUpdate: () -> Unit = {},
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
    // One spare, always allocated. Sizing this to the rows actually shown
    // would rebuild every requester the moment a background update check came
    // back, and the rebuilt one the viewer was standing on no longer points
    // at the row holding focus.
    val itemFocus = remember { List(items.size + 1) { FocusRequester() } }
    val lastIndex = items.lastIndex + if (updateLabel != null) 1 else 0
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
                        itemFocus[(focusedIndex + 1).coerceAtMost(lastIndex)]
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
                label = item.label,
                icon = item.icon,
                selected = item == selected,
                expanded = expanded,
                onClick = { onSelect(item) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(itemFocus[index])
                    .then(if (holdsFocus) Modifier.focusRequester(railFocus) else Modifier)
                    .onFocusChanged { if (it.isFocused) focusedIndex = index },
            )
        }
        // Below Settings, and only while there is something to install. Never
        // "selected" — it is an action, not a destination, and marking it the
        // way a tab is marked would say the viewer is somewhere they are not.
        if (updateLabel != null) {
            RailItem(
                label = updateLabel,
                icon = Icons.Default.SystemUpdateAlt,
                selected = false,
                expanded = expanded,
                accent = true,
                onClick = onUpdate,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(itemFocus[items.size])
                    .onFocusChanged { if (it.isFocused) focusedIndex = items.size },
            )
        }
    }
}

@Composable
private fun RailItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    /**
     * Teal rather than the rail's usual dim grey. The same colour Settings
     * uses for "an update is available", and deliberately not gold: gold is
     * the selected tab, and a second gold row would read as two tabs open at
     * once.
     */
    accent: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(NuxShape.Row),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) NuxColors.SelectedContainer else Color.Transparent,
            focusedContainerColor = NuxColors.SurfaceRaised,
            contentColor = when {
                selected -> NuxColors.Primary
                accent -> NuxColors.Secondary
                else -> NuxColors.OnSurfaceDim
            },
            // Gold survives focus: the drawer opens WITH focus on the current
            // tab, and with white-on-focus the viewer couldn't tell which tab
            // they were on until they moved off it.
            focusedContentColor = when {
                selected -> NuxColors.Primary
                accent -> NuxColors.Secondary
                else -> NuxColors.OnSurface
            },
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
            Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(160, delayMillis = 120)),
                exit = fadeOut(tween(80)),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    // Two lines, and ELLIPSIS rather than Clip. The rail is
                    // 190dp wide with a 22dp icon in front, which does not fit
                    // "Update to 2.34.11" on one line - and clipped mid-word it
                    // read as a row labelled "Update To", the version the row
                    // exists to name being the half that got cut. The nav
                    // labels are all one short word and are unaffected; only
                    // the update row ever needs the second line.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
