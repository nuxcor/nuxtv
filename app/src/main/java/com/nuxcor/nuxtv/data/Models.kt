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
    ) : PlaylistSource()
}

data class Category(
    val id: String,
    val name: String,
)

data class LiveChannel(
    val id: String,
    val name: String,
    val logo: String?,
    val url: String,
    val categoryId: String?,
    val number: Int? = null,
    val epgId: String? = null,
)

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
)

data class Episode(
    val id: String,
    val title: String,
    val season: Int,
    val episodeNum: Int,
    val url: String,
    val poster: String? = null,
    val durationText: String? = null,
)

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
)

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
)

data class PlaybackRequest(
    val items: List<PlayableItem>,
    val startIndex: Int,
    val isLive: Boolean,
)
