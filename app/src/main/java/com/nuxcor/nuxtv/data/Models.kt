package com.nuxcor.nuxtv.data

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
    /** Days of catch-up archive the provider keeps for this channel (0 = none). */
    val archiveDays: Int = 0,
    /** Xtream stream id, used for EPG and catch-up lookups. */
    val xtreamId: Int? = null,
    /** Raw TS URL suitable for recording (null when the stream can't be recorded). */
    val recordUrl: String? = null,
    /** Advertised quality parsed from the raw name (4K/FHD/HD/SD). */
    val quality: String? = null,
) {
    /**
     * Name with quality tokens stripped, for every place a viewer reads it —
     * the [quality] badge carries the tier, so "beIN Sports FHD" as text is
     * saying it twice. Country/region prefixes stay: they distinguish feeds.
     * The raw [name] remains the identity for grouping and EPG matching.
     */
    val displayName: String get() = QualityTag.baseName(name).ifBlank { name }
}

/** One EPG programme, used for the catch-up picker. */
data class EpgProgram(
    val id: String,
    val title: String,
    val description: String?,
    val startMs: Long,
    val endMs: Long,
    val hasArchive: Boolean,
)

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
)

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
    /** Review excerpts ("author — text"), populated from TMDB when a key is set. */
    val reviews: List<String> = emptyList(),
    val voteCount: Int? = null,
    /** 16:9 art for hero and detail backdrops. */
    val backdrop: String? = null,
)

@Serializable
data class ContentBundle(
    val liveCategories: List<Category> = emptyList(),
    val channels: List<LiveChannel> = emptyList(),
    val movieCategories: List<Category> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val seriesCategories: List<Category> = emptyList(),
    val series: List<Series> = emptyList(),
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
    val artwork: String? = null,
    /** Channel this item came from — enables catch-up and recording from the player. */
    val channelId: String? = null,
    /** Raw TS URL suitable for recording (null when the stream can't be recorded). */
    val recordUrl: String? = null,
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
