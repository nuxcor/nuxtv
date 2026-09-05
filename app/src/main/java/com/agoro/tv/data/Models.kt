package com.agoro.tv.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A configured playlist source (persisted). */
@Serializable
sealed class PlaylistSource {
    abstract val id: String
    abstract val name: String

    @Serializable
    @SerialName("xtream")
    data class Xtream(
        override val id: String,
        override val name: String,
        val serverUrl: String,
        val username: String,
        val password: String,
    ) : PlaylistSource()

    @Serializable
    @SerialName("m3u")
    data class M3u(
        override val id: String,
        override val name: String,
        val url: String,
        /** Optional XMLTV guide URL; falls back to the playlist's url-tvg header. */
        val epgUrl: String? = null,
    ) : PlaylistSource()
}

@Serializable
data class Category(
    val id: String,
    val name: String,
)

@Serializable
data class LiveChannel(
    val id: String,
    val name: String,
    val logo: String?,
    val url: String,
    val categoryId: String?,
    val number: Int? = null,
    val epgId: String? = null,
    /** M3U tvg-name attribute — very often the guide's exact display name. */
    val tvgName: String? = null,
    /** Days of catch-up archive the provider keeps for this channel (0 = none). */
    val archiveDays: Int = 0,
    /** Xtream stream id, used for EPG and catch-up lookups. */
    val xtreamId: Int? = null,
    /** Raw TS URL suitable for recording (null when the stream can't be recorded). */
    val recordUrl: String? = null,
    /** Advertised quality parsed from the raw name (4K/FHD/HD/SD). */
    val quality: String? = null,
    /**
     * Other streams carrying this same channel, best quality first, for when
     * the primary is dead. Populated by [ManifestCuration] from the collapse
     * tiles it folded away; empty for sources with no manifest.
     */
    val fallbackUrls: List<String> = emptyList(),
) {
    /**
     * Name with quality tokens and provider tags ("US|", "UK|", "(TR)")
     * stripped, for every place a viewer reads it — the [quality] badge
     * carries the tier, and the region prefix is provider bookkeeping, not
     * the channel's name. The raw [name] remains the identity for grouping
     * and EPG matching, so regional feeds stay distinct entries even when
     * their display names now read the same.
     */
    // Computed once per channel rather than per read: this is touched from
    // ~25 call sites, most of them inside list-item composables that rerun on
    // every scroll frame, and each read was six regex passes. Delegated
    // properties are skipped by kotlinx.serialization, so the cached bundle
    // is unaffected.
    val displayName: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ContentClassifier.stripChannelTags(QualityTag.baseName(name)).ifBlank { name }
    }
}

/** One EPG programme, used for the catch-up picker. */
@Serializable
data class EpgProgram(
    val id: String,
    val title: String,
    val description: String?,
    val startMs: Long,
    val endMs: Long,
    val hasArchive: Boolean,
)

/**
 * Whether this tile is the one that answers for [url].
 *
 * A tile stands for every feed it collapsed, so its own url is only one of the
 * addresses it responds to. Anything holding a url the viewer chose earlier —
 * a favourite, a recent, the channel last tuned — must ask this rather than
 * compare [LiveChannel.url], because the feed they chose is frequently the one
 * that LOST a merge and now lives in [fallbackUrls]. Comparing urls directly
 * made those references silently stop resolving the moment the catalogue
 * learned a sibling was the better feed: favourites vanished off their shelf,
 * and the guide stopped opening on the channel you were just watching and fell
 * back to the top of the list instead.
 */
fun LiveChannel.answersTo(url: String): Boolean =
    this.url == url || url in fallbackUrls

/**
 * The index of the tile that answers for [url], or -1.
 *
 * The rule in [answersTo] is easy to reach for and easy to forget, and every
 * place holding a url the viewer chose earlier needs it. Kept here so the
 * reasoning lives with the rule rather than being restated at each call site.
 */
fun List<LiveChannel>.indexAnswering(url: String): Int = indexOfFirst { it.answersTo(url) }

/** Whether any feed this tile answers for has been starred. */
fun LiveChannel.isFavorite(favorites: Set<String>): Boolean =
    url in favorites || fallbackUrls.any { it in favorites }

@Serializable
data class Movie(
    val id: String,
    val name: String,
    val poster: String?,
    val url: String,
    val categoryId: String?,
    val year: Int? = null,
    val rating: Double? = null,
    val plot: String? = null,
    val genre: String? = null,
    val durationText: String? = null,
    /** Xtream stream id, used for lazy detail lookups. */
    val xtreamId: Int? = null,
    /** Advertised quality parsed from the raw name (4K/FHD/HD/SD). */
    val quality: String? = null,
    /** Review excerpts ("author — text"), populated from TMDB when a key is set. */
    val reviews: List<String> = emptyList(),
    val voteCount: Int? = null,
    /** 16:9 art for hero and detail backdrops. */
    val backdrop: String? = null,
    /**
     * When the provider added this to its library (Xtream `added`), not when
     * the film was released — so anything built on it says "recently added",
     * never "new". Null for M3U, which carries no such field.
     */
    val addedMs: Long? = null,
    /** Top-billed actors, comma-separated. Provider value, else TMDB credits. */
    val cast: String? = null,
    val director: String? = null,
)

@Serializable
data class Episode(
    val id: String,
    val title: String,
    val season: Int,
    val episodeNum: Int,
    val url: String,
    val poster: String? = null,
    val durationText: String? = null,
    /** Synopsis, when the provider ships one. Defaulted, so old caches load. */
    val plot: String? = null,
)

/**
 * The show's running order: season, then episode number.
 *
 * Never the provider's order. A panel that lists a season's episodes out of
 * order, or interleaves specials, would otherwise decide what "the next one"
 * means — and since the player's playlist IS this list, that would be the
 * order a binge plays in.
 */
fun List<Episode>.inSeriesOrder(): List<Episode> =
    sortedWith(compareBy({ it.season }, { it.episodeNum }))

@Serializable
data class Series(
    val id: String,
    val name: String,
    val poster: String?,
    val categoryId: String?,
    val year: Int? = null,
    val rating: Double? = null,
    val plot: String? = null,
    val genre: String? = null,
    /** Present for M3U sources; null for Xtream (fetched lazily). */
    val episodes: List<Episode>? = null,
    val xtreamId: Int? = null,
    /**
     * Advertised quality parsed from the raw name (4K/FHD/HD/SD), the same
     * field [Movie] carries and for the same reason: providers list one show
     * several times at different rungs, [name] is the cleaned title so the
     * rungs all reduce to it, and something has to say which of them is the
     * one worth keeping. See `foldVariants`.
     */
    val quality: String? = null,
    /** Review excerpts ("author — text"), populated from TMDB when a key is set. */
    val reviews: List<String> = emptyList(),
    val voteCount: Int? = null,
    /** 16:9 art for hero and detail backdrops. */
    val backdrop: String? = null,
    /** When the provider last touched this series (Xtream `last_modified`). */
    val addedMs: Long? = null,
    /** Top-billed actors, comma-separated. Provider value, else TMDB credits. */
    val cast: String? = null,
    val director: String? = null,
)

@Serializable
data class ContentBundle(
    val liveCategories: List<Category> = emptyList(),
    val channels: List<LiveChannel> = emptyList(),
    val movieCategories: List<Category> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val seriesCategories: List<Category> = emptyList(),
    val series: List<Series> = emptyList(),
    /**
     * PPV event slots, kept apart from [channels] on purpose.
     *
     * They are pipes rather than channels — the same stream id carries a
     * different match tomorrow — and there are over six thousand of them, so
     * putting them in [channels] would bury search and All channels under slots
     * that are mostly empty. The Sport destination reads them; nothing else
     * does.
     */
    val events: List<LiveChannel> = emptyList(),
    /**
     * Set once [CategoryCleaner] (and any manifest curation) has run, so a
     * cache read does not run them a second time.
     *
     * Cleaning is idempotent over its own output but NOT over curation's: the
     * display-name pass strips a curated "Sports · United States" back to
     * "Sports United States" because `·` is not a character a brand name
     * carries, and the movie/series stop-words take the axis word off "All
     * Movies". A warm start therefore labelled shelves differently from the
     * network load that had just written them. Defaults false so caches
     * written before this field still get cleaned on read, which is what it
     * was doing there for.
     */
    val cleaned: Boolean = false,

    /**
     * The [CatalogueManifest.generated] stamp of the manifest that curated
     * this bundle, or null for one built without a manifest or cached before
     * this field existed.
     *
     * The cache holds the FINISHED model — shelves already resolved, channels
     * already assigned to them, names already cleaned — and it lives in
     * filesDir, which an app update does not clear. So a release that changes
     * the curation shipped a new manifest into an app that went on publishing
     * the old manifest's OUTPUT from disk, for as long as the catalogue cache
     * stayed young. Shelves that had been merged came back; channels that had
     * been dropped came back with them.
     *
     * [cleaned] is the same idea one layer down and was the precedent: a
     * bundle has to say what produced it, or a warm start and a network load
     * disagree about what the same catalogue is called.
     */
    val manifestStamp: String? = null,
) {
    val isEmpty: Boolean
        get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()
}

sealed class ContentState {
    data object Empty : ContentState()
    data class Loading(val message: String = "Loading your playlist…") : ContentState()
    data class Ready(val bundle: ContentBundle) : ContentState()
    data class Error(val message: String) : ContentState()
}

/** One playable entry handed to the player. */
data class PlayableItem(
    val url: String,
    val title: String,
    val subtitle: String? = null,
    /**
     * The episode's own name, without its season and number.
     *
     * [subtitle] carries "S1 E2 • The Cellar" because that is what a player's
     * title bar wants on one line. The up-next card wants the two apart — the
     * name is the headline, because it is the thing the viewer does not
     * already know, and the address is meta under it. Splitting the subtitle
     * back up at the point of drawing would make a format string into an API.
     *
     * Null for anything that is not an episode, and for an episode the
     * provider never named.
     */
    val episodeName: String? = null,
    val artwork: String? = null,
    /** Channel this item came from — enables catch-up and recording from the player. */
    val channelId: String? = null,
    /** Raw TS URL suitable for recording (null when the stream can't be recorded). */
    val recordUrl: String? = null,
    /** Alternate sources for this same channel, best first; see [LiveChannel.fallbackUrls]. */
    val fallbackUrls: List<String> = emptyList(),
    /**
     * What to call each of [fallbackUrls] once the ladder is playing it, index
     * for index. Empty — the usual case — means the title stands whichever
     * source is up, which is right for a channel: its alternates are the same
     * channel at another quality.
     *
     * A fixture's are not. Its alternates are different PPV slots carrying the
     * same match — another pack's feed, the Spanish call, the pre-match studio
     * show — so the ladder stepping down changes what is on screen, and a
     * title that does not follow it is simply wrong. Populated by
     * MainViewModel.playEvent; read by PlayerSession.swapSource.
     */
    val fallbackTitles: List<String> = emptyList(),
)

data class PlaybackRequest(
    val items: List<PlayableItem>,
    val startIndex: Int,
    val isLive: Boolean,
    /** Seekable non-live stream that isn't in the VOD library (catch-up). */
    val isCatchup: Boolean = false,
    /** Set by "Start over" so a saved resume position is ignored this once. */
    val ignoreResume: Boolean = false,
)
