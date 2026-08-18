package com.nuxcor.nuxtv.ui.screens

import com.nuxcor.nuxtv.data.Category
import com.nuxcor.nuxtv.data.ContentBundle
import com.nuxcor.nuxtv.data.LiveChannel

/**
 * The category vocabulary of Live TV, in one place because it has two views.
 *
 * The list and the guide each used to build this for themselves and hold their
 * own selection, so picking a category in one and switching put you back on
 * "All" in the other, and every pseudo-category had to be added twice. They
 * share a selected id now; sharing the list and the filtering is what stops
 * that id from meaning two different things.
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
    add(Category(id = CATEGORY_ALL, name = "All channels"))
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
     * Collapse cross-category duplicates in the All view: a channel living
     * in five categories lists once, keyed globally by its cleaned identity.
     * Per-category views stay untouched — nothing vanishes from the shelf
     * being browsed — and Favorites/Recent filter the full list by url, so a
     * deduped-away variant the viewer starred still appears there.
     */
    dedupAll: Boolean = false,
): List<LiveChannel> = when (categoryId) {
    CATEGORY_ALL ->
        if (dedupAll) {
            com.nuxcor.nuxtv.data.QualityTag.mergeBestQuality(
                channels,
                keyOf = { com.nuxcor.nuxtv.data.EpgMatcher.normalizeKey(it.name) },
            )
        } else channels
    CATEGORY_FAVORITES -> channels.filter { it.url in favorites }
    CATEGORY_RECENT -> {
        // Index the channels once: recents is capped small, but the channel
        // list routinely runs to thousands and this is recomputed on every
        // change to either.
        val byUrl = channels.associateBy { it.url }
        recents.mapNotNull { byUrl[it] }
    }
    else -> channels.filter { it.categoryId == categoryId }
}

/**
 * A category the viewer selected can stop existing — the last favorite gets
 * un-starred, a playlist refresh drops a category, recents are cleared. Falls
 * back to All rather than showing an empty grid under a heading for something
 * that is no longer there.
 */
internal fun resolveCategoryId(selected: String, categories: List<Category>): String =
    if (categories.any { it.id == selected }) selected else CATEGORY_ALL
