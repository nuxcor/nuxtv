@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.nuxcor.nuxtv.ui.screens

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
import com.nuxcor.nuxtv.R
import com.nuxcor.nuxtv.ui.theme.NuxColors
import com.nuxcor.nuxtv.ui.theme.NuxFocus
import com.nuxcor.nuxtv.ui.theme.NuxMotion
import com.nuxcor.nuxtv.ui.theme.NuxShape
import com.nuxcor.nuxtv.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Shared by the rail and the content lane so the two can never disagree. */
// Not private: the guide sizes its timeline against the width it will actually
// have, and the rail is part of that budget.
internal val RAIL_WIDTH_COLLAPSED = 64.dp
internal val RAIL_WIDTH_EXPANDED = 190.dp

enum class HomeTab(val label: String, val icon: ImageVector) {
    Search("Search", Icons.Default.Search),
    Live("Live TV", Icons.Default.LiveTv),
    Movies("Movies", Icons.Default.Movie),
    Series("Series", Icons.Default.VideoLibrary),
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
) {
    // Mirrors the caller's copy, which drives the content lane's width.
    var expanded by remember { mutableStateOf(false) }
    // Focus travel selects a tab only after the focus rests briefly, so
    // moving down the rail doesn't compose every tab it passes through.
    var focusedItem by remember { mutableStateOf<HomeTab?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(focusedItem) {
        val item = focusedItem ?: return@LaunchedEffect
        delay(NuxMotion.TabDwellMs.toLong())
        onSelect(item)
    }
    val width by animateDpAsState(
        targetValue = if (expanded) RAIL_WIDTH_EXPANDED else RAIL_WIDTH_COLLAPSED,
        label = "railWidth",
    )

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
                expanded = it.hasFocus
                onRailFocusChanged(it.hasFocus)
                if (!it.hasFocus) focusedItem = null // cancel pending select-on-focus
            }
            .padding(horizontal = Space.s, vertical = Space.gutterVertical),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        // The brand mark itself, not a letter standing in for it. Same lockup
        // as onboarding: mark alone when collapsed, mark plus wordmark when
        // there is room for it.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .padding(start = 10.dp, bottom = 20.dp)
                .animateContentSize(),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_logo),
                contentDescription = "Agoro",
                // ic_logo, not ic_splash: the splash copy is padded into a
                // square and scaled for its circular mask, so drawing it here
                // gave about 59% of the size asked for.
                //
                // 48dp against titleLarge's 17.1dp cap height is the banner's
                // 2.81:1. The old 32dp was inherited from the square drawable
                // rather than derived from anything, and came out at 1.88:1 —
                // the mark reading as an afterthought beside its own wordmark.
                // 35dp wide clears the 54dp the collapsed rail leaves.
                modifier = Modifier.height(48.dp).width(35.dp),
            )
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(160, delayMillis = 120)),
                exit = fadeOut(tween(80)),
            ) {
                Text(
                    text = "AGORO",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                    color = NuxColors.Primary,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
        HomeTab.entries.forEach { item ->
            RailItem(
                item = item,
                selected = item == selected,
                expanded = expanded,
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
                modifier = if (item == selected) {
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
            Icon(item.icon, contentDescription = item.label, modifier = Modifier.size(22.dp))
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
