@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.agoro.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.agoro.tv.data.Category
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.Space
import com.agoro.tv.ui.components.requestFocusRetrying

/** Wide enough for two-line provider names without crowding the grid behind it. */
private val PANEL_WIDTH = 260.dp

/**
 * The guide's category control.
 *
 * This was a LazyRow across the top of the screen, and the top of the screen is
 * the one place a category is never needed from. Changing category meant
 * travelling back to row 0 — one D-pad press per channel, because the grid
 * moves a row at a time — and a viewer at the bottom of News wanting
 * Entertainment paid the whole list to get there. The strip could only be
 * reached from row 0, so its distance from the viewer grew with every row they
 * had browsed.
 *
 * On the left, opened by LEFT from the channel column, it is one press from
 * every row. LEFT is the natural key for it: the channel column is already the
 * leftmost thing in the grid, and this simply extends that line — cells, then
 * the channel, then what the channel list IS. LEFT again from here reaches the
 * nav drawer, which is where LEFT used to land from the channel column, so
 * nothing that used to be reachable stopped being reachable.
 *
 * Vertical also fixes what the horizontal strip could only work around. The
 * region headings exist because nineteen chips in a row read as "News · United
 * Kingdom, Sports · United Kingdom…" — the territory repeated into every label
 * for want of anywhere to put it once. Down a column, a heading is just a
 * heading with its categories under it.
 */
@Composable
internal fun GuideCategoryPanel(
    entries: List<StripEntry>,
    selectedId: String,
    lockedIds: Set<String>,
    onSelect: (Category) -> Unit,
    onLocked: (Category) -> Unit,
    /** Dismiss without choosing — BACK, or RIGHT back into the grid. */
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val selectedFocus = remember { FocusRequester() }

    // Open ON the current category, not at the top. The panel's job is to move
    // you from where you are, so it has to say where that is — and a viewer
    // eleven categories down a nineteen-entry list should not have to find
    // their place before they can leave it.
    val selectedIndex = remember(entries, selectedId) {
        entries.indexOfFirst { it is StripEntry.Chip && it.category.id == selectedId }
            // A selection that names no entry would leave the requester
            // attached to nothing. The first category is a wrong guess about
            // where the viewer is; no focus at all is a panel the remote
            // cannot reach, BACK included.
            .takeIf { it >= 0 }
            ?: entries.indexOfFirst { it is StripEntry.Chip }
    }
    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) {
            // Scrolled first: the requester is attached by the item itself, so
            // it exists only once the LazyColumn has composed that item.
            listState.scrollToItem(selectedIndex)
        }
        // Close rather than sit there deaf. The key handler below only sees
        // events while focus is INSIDE the panel, so a panel that never took
        // focus is one the viewer cannot dismiss — every press goes to the
        // grid behind it, under a list covering the channel names.
        if (!selectedFocus.requestFocusRetrying()) onDismiss()
    }

    Column(
        modifier = modifier
            .width(PANEL_WIDTH)
            .fillMaxHeight()
            .background(NuxColors.Surface)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key.nativeKeyCode) {
                    // RIGHT is the way back into the grid, and BACK is the way
                    // back out of anything. Both land the viewer where they
                    // were rather than at the top of a re-entered list.
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                    android.view.KeyEvent.KEYCODE_BACK,
                    -> {
                        onDismiss()
                        true
                    }
                    else -> false
                }
            },
    ) {
        Text(
            text = "Categories",
            style = MaterialTheme.typography.titleSmall,
            color = NuxColors.OnSurfaceDim,
            modifier = Modifier.padding(start = 20.dp, top = Space.m, bottom = Space.s),
        )
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = Space.xl, start = Space.s, end = Space.s),
        ) {
            itemsIndexed(entries, key = { _, e -> e.key }) { index, entry ->
                if (entry is StripEntry.Group) {
                    Spacer(Modifier.height(Space.s))
                    RegionGroupLabel(entry.label)
                    Spacer(Modifier.height(Space.xs))
                    return@itemsIndexed
                }
                val category = (entry as StripEntry.Chip).category
                val locked = category.id in lockedIds
                CategoryItem(
                    name = entry.label,
                    selected = category.id == selectedId,
                    locked = locked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (index == selectedIndex) Modifier.focusRequester(selectedFocus)
                            else Modifier
                        ),
                    onClick = {
                        if (locked) onLocked(category) else {
                            onSelect(category)
                            onDismiss()
                        }
                    },
                )
            }
        }
    }
}
