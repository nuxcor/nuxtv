package com.agoro.tv.ui.screens

import com.agoro.tv.data.Episode

/**
 * The one episode a series page is about: what the primary button plays,
 * which season opens, and where the episode list is scrolled to.
 *
 * @param episode the episode itself.
 * @param resuming the viewer is part-way through it — the button offers to
 * resume, and "Start episode over" appears beside it.
 * @param allWatched every episode has been seen; this is a second run.
 * @param fresh nothing in this show has been started or finished, so the
 * button is a plain "Play" rather than naming an episode a viewer has no
 * reason to be told about.
 */
internal data class UpNext(
    val episode: Episode,
    val resuming: Boolean,
    val allWatched: Boolean,
    val fresh: Boolean,
)

/**
 * Answers "where am I in this show?" in the order every streaming service
 * asks it: part-way through something → resume it; otherwise the one after
 * the furthest episode watched to the end; otherwise the first, which covers
 * both a show never started and one seen through to its last episode.
 *
 * The middle question is the one that did not exist here. A resume position
 * is DELETED when a title runs past 95% (`PlayerPrefs.saveResumePosition`),
 * and nothing recorded that it had been seen — so a viewer who stopped at an
 * episode boundary, which is the ordinary way to stop watching a series, left
 * no trace at all. The page fell back to "Play" meaning S1E1, the season strip
 * reset to Season 1, and the show dropped out of Continue watching entirely.
 * `PlayerPrefs.watchedAt` is the record that was missing; this is the rule
 * that reads it.
 *
 * Ordered by (season, episode) rather than trusting the provider's order: a
 * panel that lists a season's episodes out of order, or interleaves specials,
 * would otherwise make "the next one" mean whatever the JSON happened to say.
 *
 * @return null only when there are no episodes at all.
 */
internal fun upNext(
    episodes: List<Episode>,
    resumePositions: Map<String, Long>,
    watchedAt: Map<String, Long>,
): UpNext? {
    if (episodes.isEmpty()) return null
    val ordered = episodes.sortedWith(compareBy({ it.season }, { it.episodeNum }))
    val lastWatched = ordered.indexOfLast { it.url in watchedAt }
    // The FURTHEST part-watched episode, not the earliest: a viewer who dipped
    // back into season one and then carried on is still on season four.
    //
    // And only when it is at or beyond where they have watched to. A position
    // is written after 30 seconds and nothing ever clears one that was never
    // finished, so ninety seconds sampled from S1E5 during a re-watch sits
    // there for good — and taken at face value it would answer "Resume S1E5"
    // to someone three seasons further on, park the season strip on Season 1
    // and scroll the list there, permanently. Positions carry no timestamp,
    // so their place in the running order is the only evidence there is.
    val resumingIndex = ordered.indexOfLast { (resumePositions[it.url] ?: 0L) > 0L }
    val resuming = if (resumingIndex >= lastWatched) ordered.getOrNull(resumingIndex) else null
    val allWatched = ordered.all { it.url in watchedAt }
    val episode = resuming
        ?: ordered.getOrNull(lastWatched + 1) // -1 + 1 = 0: never started
        ?: ordered.first() // watched to the last episode; round again
    return UpNext(
        episode = episode,
        resuming = resuming != null,
        allWatched = allWatched,
        fresh = resumingIndex < 0 && lastWatched < 0,
    )
}

/**
 * What the primary button says.
 *
 * It names the episode, because "Play" on a show four seasons in read as
 * "carry on" and started it again from the beginning — which is the whole
 * defect from the viewer's side. The one case that stays wordless is a show
 * never touched: naming S1E1 there tells a first-time viewer nothing they
 * cannot see in the list below.
 */
internal fun upNextLabel(up: UpNext): String {
    val episode = "S${up.episode.season}E${up.episode.episodeNum}"
    return when {
        up.resuming -> "Resume $episode"
        up.fresh -> "Play"
        up.allWatched -> "Watch again from $episode"
        else -> "Play $episode"
    }
}
