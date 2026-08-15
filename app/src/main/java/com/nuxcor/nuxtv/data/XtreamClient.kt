package com.nuxcor.nuxtv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Client for the Xtream Codes "player_api.php" protocol.
 *
 * Xtream servers are wildly inconsistent about JSON types (numbers as strings,
 * missing fields, objects vs arrays), so everything is parsed defensively from
 * raw JsonElements instead of strict DTOs.
 */
class XtreamClient(
    private val http: OkHttpClient,
    serverUrl: String,
    private val username: String,
    private val password: String,
) {
    val baseUrl: String = normalize(serverUrl)

    // Credentials appear both in query strings and in URL path segments;
    // encode appropriately so passwords with &, +, %, #, spaces etc. work.
    private val userQ = java.net.URLEncoder.encode(username, "UTF-8")
    private val passQ = java.net.URLEncoder.encode(password, "UTF-8")
    // Path-segment encoding: like query encoding but spaces become %20.
    private val userP = java.net.URLEncoder.encode(username, "UTF-8").replace("+", "%20")
    private val passP = java.net.URLEncoder.encode(password, "UTF-8").replace("+", "%20")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        fun normalize(url: String): String {
            var u = url.trim().removeSuffix("/")
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            // Users often paste the get.php or player_api.php URL directly.
            u = u.substringBefore("/player_api.php").substringBefore("/get.php")
            return u
        }
    }

    private fun buildRequest(action: String?, extra: Map<String, String>): Request {
        val params = buildString {
            append("username=$userQ&password=$passQ")
            if (action != null) append("&action=$action")
            extra.forEach { (k, v) -> append("&$k=$v") }
        }
        return Request.Builder()
            .url("$baseUrl/player_api.php?$params")
            .header("User-Agent", "Dzidzi/2.1")
            .build()
    }

    /** Small/object-shaped responses: parse the stream into a tree. */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun call(action: String?, extra: Map<String, String> = emptyMap()): JsonElement =
        withContext(Dispatchers.IO) {
            http.newCall(buildRequest(action, extra)).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Server returned HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("Empty response from server")
                json.decodeFromStream<JsonElement>(body.byteStream())
            }
        }

    /**
     * Huge array endpoints (live/vod/series catalogs) are decoded one element
     * at a time so a 100k-entry provider never needs the whole response in
     * memory — TV boxes have tiny heaps.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun <T : Any> callList(
        action: String,
        map: (JsonObject) -> T?,
    ): List<T> = withContext(Dispatchers.IO) {
        http.newCall(buildRequest(action, emptyMap())).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Server returned HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty response from server")
            val out = ArrayList<T>()
            runCatching {
                json.decodeToSequence<JsonElement>(
                    body.byteStream(),
                    DecodeSequenceMode.ARRAY_WRAPPED,
                ).forEach { el -> (el as? JsonObject)?.let(map)?.let(out::add) }
            }
            out
        }
    }

    /** Validates credentials; throws with a readable message when login fails. */
    suspend fun authenticate() {
        val root = call(null).jsonObject
        val userInfo = root["user_info"]?.jsonObject
            ?: throw IOException("Unexpected response — is this an Xtream server?")
        val auth = userInfo.str("auth") ?: "0"
        if (auth != "1" && auth != "true") throw IOException("Login failed — check username and password")
        val status = userInfo.str("status")
        if (status != null && !status.equals("Active", ignoreCase = true)) {
            throw IOException("Account status: $status")
        }
    }

    suspend fun liveCategories(): List<Category> = categories("get_live_categories")
    suspend fun vodCategories(): List<Category> = categories("get_vod_categories")
    suspend fun seriesCategories(): List<Category> = categories("get_series_categories")

    private suspend fun categories(action: String): List<Category> =
        callList(action) { obj ->
            obj.str("category_id")?.let { id ->
                Category(id = id, name = obj.str("category_name") ?: "Unnamed")
            }
        }

    suspend fun liveStreams(): List<LiveChannel> =
        callList("get_live_streams") { obj ->
            val id = obj.int("stream_id") ?: return@callList null
            val hasArchive = obj.int("tv_archive") == 1
            LiveChannel(
                id = "live:$id",
                name = obj.str("name") ?: "Channel $id",
                logo = obj.str("stream_icon")?.takeIf { it.isNotBlank() },
                url = "$baseUrl/live/$userP/$passP/$id.m3u8",
                categoryId = obj.str("category_id"),
                number = obj.int("num"),
                epgId = obj.str("epg_channel_id"),
                archiveDays = if (hasArchive) (obj.int("tv_archive_duration") ?: 1) else 0,
                xtreamId = id,
                recordUrl = "$baseUrl/live/$userP/$passP/$id.ts",
                quality = obj.str("name")?.let { QualityTag.of(it) },
            )
        }

    /** Full EPG listing for one channel; titles/descriptions arrive base64-encoded. */
    suspend fun epg(streamId: Int): List<EpgProgram> {
        val root = call("get_simple_data_table", mapOf("stream_id" to streamId.toString()))
        val listings = (root as? JsonObject)?.get("epg_listings") as? JsonArray ?: return emptyList()
        return listings.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val start = obj.str("start_timestamp")?.toLongOrNull() ?: return@mapNotNull null
            val stop = obj.str("stop_timestamp")?.toLongOrNull() ?: return@mapNotNull null
            EpgProgram(
                id = obj.str("id") ?: "$streamId:$start",
                title = obj.str("title")?.fromBase64() ?: "Untitled",
                description = obj.str("description")?.fromBase64()?.takeIf { it.isNotBlank() },
                startMs = start * 1000,
                endMs = stop * 1000,
                hasArchive = obj.int("has_archive") == 1,
            )
        }.sortedBy { it.startMs }
    }

    /** Full XMLTV guide for every channel on the server. */
    val xmltvUrl: String
        get() = "$baseUrl/xmltv.php?username=$userQ&password=$passQ"

    /** Timeshift/catch-up stream URL for an archived programme. */
    fun catchupUrl(streamId: Int, startMs: Long, durationMinutes: Long): String {
        // Xtream panels almost universally run their archive clocks in UTC.
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val start = fmt.format(java.util.Date(startMs))
        return "$baseUrl/streaming/timeshift.php" +
            "?username=$userQ&password=$passQ&stream=$streamId&start=$start&duration=$durationMinutes"
    }

    suspend fun vodStreams(): List<Movie> =
        callList("get_vod_streams") { obj ->
            val id = obj.int("stream_id") ?: return@callList null
            val ext = obj.str("container_extension")?.takeIf { it.isNotBlank() } ?: "mp4"
            Movie(
                id = "movie:$id",
                name = obj.str("name") ?: "Movie $id",
                poster = obj.str("stream_icon")?.takeIf { it.isNotBlank() },
                url = "$baseUrl/movie/$userP/$passP/$id.$ext",
                categoryId = obj.str("category_id"),
                year = obj.int("year") ?: yearFrom(obj.str("name")),
                rating = obj.dbl("rating_5based")?.times(2) ?: obj.dbl("rating"),
                xtreamId = id,
                quality = obj.str("name")?.let { QualityTag.of(it) },
            )
        }

    suspend fun series(): List<Series> =
        callList("get_series") { obj ->
            val id = obj.int("series_id") ?: return@callList null
            Series(
                id = "series:$id",
                name = obj.str("name") ?: "Series $id",
                poster = obj.str("cover")?.takeIf { it.isNotBlank() },
                categoryId = obj.str("category_id"),
                year = obj.int("year") ?: yearFrom(obj.str("releaseDate") ?: obj.str("release_date")),
                rating = obj.dbl("rating_5based")?.times(2) ?: obj.dbl("rating"),
                plot = obj.str("plot")?.takeIf { it.isNotBlank() },
                genre = obj.str("genre")?.takeIf { it.isNotBlank() },
                xtreamId = id,
            )
        }

    /** Loads episodes for a series; tolerates both map-of-seasons and array forms. */
    suspend fun seriesEpisodes(seriesId: Int): List<Episode> {
        val root = call("get_series_info", mapOf("series_id" to seriesId.toString())).jsonObject
        val episodesEl = root["episodes"] ?: return emptyList()
        val seasonArrays: List<JsonArray> = when (episodesEl) {
            is JsonObject -> episodesEl.values.mapNotNull { it as? JsonArray }
            is JsonArray -> episodesEl.mapNotNull { it as? JsonArray }
            else -> emptyList()
        }
        return seasonArrays.flatten().mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id") ?: obj.int("id")?.toString() ?: return@mapNotNull null
            val ext = obj.str("container_extension")?.takeIf { it.isNotBlank() } ?: "mp4"
            val info = obj["info"] as? JsonObject
            Episode(
                id = "ep:$id",
                title = obj.str("title") ?: "Episode",
                season = obj.int("season") ?: 1,
                episodeNum = obj.int("episode_num") ?: 0,
                url = "$baseUrl/series/$userP/$passP/$id.$ext",
                poster = info?.str("movie_image")?.takeIf { it.isNotBlank() },
                durationText = info?.str("duration")?.takeIf { it.isNotBlank() },
            )
        }.sortedWith(compareBy({ it.season }, { it.episodeNum }))
    }

    /** Enriches a movie with plot/genre/duration from get_vod_info. */
    suspend fun movieDetails(movie: Movie): Movie {
        val id = movie.xtreamId ?: return movie
        val root = runCatching {
            call("get_vod_info", mapOf("vod_id" to id.toString())).jsonObject
        }.getOrNull() ?: return movie
        val info = root["info"] as? JsonObject ?: return movie
        return movie.copy(
            plot = info.str("plot")?.takeIf { it.isNotBlank() } ?: movie.plot,
            genre = info.str("genre")?.takeIf { it.isNotBlank() } ?: movie.genre,
            durationText = info.str("duration")?.takeIf { it.isNotBlank() } ?: movie.durationText,
            poster = info.str("movie_image")?.takeIf { it.isNotBlank() } ?: movie.poster,
            year = movie.year ?: yearFrom(info.str("releasedate") ?: info.str("release_date")),
        )
    }

    private fun yearFrom(text: String?): Int? =
        text?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toIntOrNull() }
}

// --- defensive JSON accessors -------------------------------------------------

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? =
    str(key)?.trim()?.toDoubleOrNull()?.toInt()

private fun JsonObject.dbl(key: String): Double? =
    str(key)?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }

private fun String.fromBase64(): String =
    runCatching {
        String(android.util.Base64.decode(this, android.util.Base64.DEFAULT), Charsets.UTF_8).trim()
    }.getOrDefault(this)
