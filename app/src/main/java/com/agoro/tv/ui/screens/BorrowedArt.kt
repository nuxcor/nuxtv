package com.agoro.tv.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.agoro.tv.MainViewModel
import com.agoro.tv.data.ArtworkUrl
import com.agoro.tv.data.Movie
import com.agoro.tv.data.Series
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Filling in the artwork a provider didn't ship.
 *
 * Plenty of Xtream panels carry a full VOD library with no images at all, and
 * a browse grid of forty grey slabs with titles printed on them is the single
 * loudest signal that an app is a hobby project. TMDB has the posters; the
 * detail screen already borrowed them, but only for the one title you had
 * already committed to opening — which is exactly backwards, because artwork
 * is how you decide what to open.
 */

/** What a catalogue entry needs to be looked up on TMDB. */
data class ArtRef(
    val id: String,
    /** TMDB's vocabulary: "movie" or "tv". */
    val kind: String,
    val title: String,
    val year: Int?,
)

internal fun Movie.artRef() = ArtRef(id, "movie", name, year)

internal fun Series.artRef() = ArtRef(id, "tv", name, year)

/**
 * How long a card must stay on screen before it is worth a request. A viewer
 * holding RIGHT down flies past hundreds of posters; none of those were looked
 * at, and none of them should cost a lookup.
 */
private const val ART_DWELL_MS = 400L

/**
 * How long a card waits before asking again after the request queue turned it
 * away. Only the overflow of a screenful retries, so this stays short enough
 * that a poster fills in while the viewer is still looking at it.
 */
private const val ART_RETRY_MS = 400L

/**
 * [provided] when the provider shipped art, TMDB's when it didn't, null while
 * the answer isn't known yet — [com.agoro.tv.ui.components.Artwork] draws
 * its own fallback in the meantime, so there is nothing to show for a miss.
 *
 * Art the panel has painted quality badges onto counts as art it did not ship
 * ([ArtworkUrl.isDoctored]): 1,965 films and 696 series on this panel wear a
 * "4K UltraHD" banner and a gold "8K" over the poster, and TMDB has the same
 * poster without them. The badged copy stays on screen throughout — while the
 * lookup runs, and for good if TMDB turns out not to know the title — because
 * a poster with a sticker on it still tells the viewer what the title is, and
 * a monogram does not.
 *
 * Subscribes per id rather than to the whole map: a filling grid publishes a
 * new map on every arrival, and a card that reads all of it recomposes for
 * every other card's artwork as well as its own.
 */
@Composable
internal fun borrowedArt(
    vm: MainViewModel,
    ref: ArtRef?,
    provided: String?,
    /** Prefer the 16:9 art — for heroes and ambient backdrops. */
    wide: Boolean = false,
): String? {
    if (!provided.isNullOrBlank() && !ArtworkUrl.isDoctored(provided)) return provided
    if (ref == null) return provided
    val entry by remember(ref.id) {
        vm.artwork.map { it[ref.id] }.distinctUntilChanged()
    }.collectAsState(initial = vm.artwork.value[ref.id])
    LaunchedEffect(ref.id) {
        kotlinx.coroutines.delay(ART_DWELL_MS)
        // Keyed on the id, so this fires exactly once per card while it stays
        // composed — which is why a refusal has to be retried here rather than
        // dropped. A seven-column grid expires ~28 dwell timers at once against
        // a queue of 24, and the losers used to stay monograms until the
        // viewer scrolled them away and back. Cancelled with the card.
        while (!vm.requestArtwork(ref.id, ref.kind, ref.title, ref.year)) {
            kotlinx.coroutines.delay(ART_RETRY_MS)
        }
    }
    // Not known yet. [provided] is null for a title with no art at all, and
    // the badged copy for one whose art is merely painted on — which is what
    // should stay up until the clean one lands.
    val art = entry ?: return provided
    // A poster standing in for a missing backdrop is better than no ambient
    // art; a backdrop standing in for a missing poster is not — cropped to 2:3
    // it is an unrecognisable slice of a frame.
    val borrowed = if (wide) art.backdrop ?: art.poster else art.poster
    // TMDB does not know this title. The badged poster is what there is.
    return borrowed ?: provided
}
