package com.agoro.tv.ui.screens

import com.agoro.tv.data.Category
import com.agoro.tv.data.ContentBundle
import com.agoro.tv.data.LiveChannel

/**
 * The category vocabulary of Live TV, in one place because it has two views.
 *
 * The list and the guide each used to build this for themselves and hold their
 * own selection, so picking a category in one and switching put you back on
 * "All" in the other, and every pseudo-category had to be added twice. They
 * share a selected id now; sharing the list and the filtering is what stops
 * that id from meaning two different things.
 */

/**
 * Every channel, unfiltered. No longer offered as a shelf — the curated
 * territories cover the catalogue and a browse-everything tab in front of them
 * was a rung the viewer stepped over. [ChannelManager] still selects it, where
 * seeing the whole list at once is the entire job.
 */
const val CATEGORY_ALL = "__all__"
const val CATEGORY_FAVORITES = "__fav__"
const val CATEGORY_RECENT = "__recent__"

/**
 * The categories to offer, given what the playlist has and what the viewer has
 * done. Favorites and Recent appear only once they hold something: an empty
 * shortcut is a dead end that still costs a D-pad press to skip.
 */
internal fun liveCategoryList(
    bundle: ContentBundle,
    channels: List<LiveChannel>,
    favorites: Set<String>,
    recents: List<String>,
): List<Category> = buildList {
    if (channels.any { it.url in recents }) {
        add(Category(id = CATEGORY_RECENT, name = "Recent"))
    }
    if (channels.any { it.url in favorites }) {
        add(Category(id = CATEGORY_FAVORITES, name = "★ Favorites"))
    }
    addAll(bundle.liveCategories)
}

/**
 * The channels in a category. Recent keeps its own order — most recently
 * watched first — rather than the playlist's, which is the whole point of it;
 * every other category keeps the order it was given.
 */
internal fun channelsInCategory(
    categoryId: String,
    channels: List<LiveChannel>,
    favorites: Set<String>,
    recents: List<String>,
    /**
     * The All view's list: cross-category duplicates already collapsed, so a
     * channel living in five categories lists once. Per-category views stay
     * untouched — nothing vanishes from the shelf being browsed — and
     * Favorites/Recent filter the full list by url, so a deduped-away variant
     * the viewer starred still appears there.
     *
     * Passed in rather than computed here, and this is the whole point of the
     * parameter: collapsing it is a global regex pass over every channel, and
     * all four screens that call this ran it inside composition on the main
     * thread. It re-ran on every emission of displayChannels — including the
     * one that lands mid-playback each time a stream's real quality is
     * learned. [MainViewModel.allChannelsView] computes it once, off-thread.
     */
    allChannels: List<LiveChannel> = channels,
    /**
     * [channels] grouped by category, built once off the main thread by
     * [MainViewModel.channelsByCategory]. With it a provider category is a
     * map lookup; without it — or with one built from a different list, which
     * is what a cold start hands over for a frame — this filters as before.
     */
    byCategory: LiveCategoryIndex? = null,
): List<LiveChannel> = when (categoryId) {
    // ifEmpty, and not as a formality: allChannelsView is a flowOn hop
    // DOWNSTREAM of displayChannels, so on a cold start there is a window
    // where the catalogue has arrived but its merge has not. All is the
    // default selection on all four screens, and the "No live channels" pane
    // can't cover the gap because it tests displayChannels — which is full.
    // The unmerged list for one frame beats an empty grid that the entry
    // focus tick then fires against.
    CATEGORY_ALL -> allChannels.ifEmpty { channels }
    CATEGORY_FAVORITES -> channels.filter { it.url in favorites }
    CATEGORY_RECENT -> {
        // Index the channels once: recents is capped small, but the channel
        // list routinely runs to thousands and this is recomputed on every
        // change to either.
        val byUrl = channels.associateBy { it.url }
        recents.mapNotNull { byUrl[it] }
    }
    else ->
        if (byCategory != null && byCategory.channels === channels) {
            byCategory.byId[categoryId].orEmpty()
        } else {
            channels.filter { it.categoryId == categoryId }
        }
}

/**
 * A channel list grouped by provider category.
 *
 * Every category switch used to filter the whole list — thousands of
 * channels, on the main thread, under a chip the viewer had only rested on.
 * Keeps the list it was built from so a reader can tell whether the index is
 * for the channels it holds: the index is a flow hop downstream of the list,
 * so there is always a frame where the two disagree.
 */
class LiveCategoryIndex(
    val channels: List<LiveChannel>,
    val byId: Map<String, List<LiveChannel>>,
) {
    companion object {
        val empty = LiveCategoryIndex(emptyList(), emptyMap())

        fun of(channels: List<LiveChannel>) = LiveCategoryIndex(
            channels,
            channels.groupBy { it.categoryId.orEmpty() },
        )
    }
}

/**
 * A category the viewer selected can stop existing — the last favorite gets
 * un-starred, a playlist refresh drops a category, recents are cleared. Falls
 * back to the first category on offer rather than showing an empty grid under
 * a heading for something that is no longer there.
 */
internal fun resolveCategoryId(selected: String, categories: List<Category>): String =
    if (categories.any { it.id == selected }) selected
    else categories.firstOrNull()?.id.orEmpty()

/** The first category to show when nothing has been chosen yet. */
internal fun defaultCategoryId(categories: List<Category>): String =
    categories.firstOrNull()?.id.orEmpty()
