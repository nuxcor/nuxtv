package com.agoro.tv.data

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

        // Compiled once: this ran per movie, and a catalogue has 29,000.
        private val YEAR = Regex("(19|20)\\d{2}")
    }

    private fun buildRequest(action: String?, extra: Map<String, String>): Request {
        val params = buildString {
            append("username=$userQ&password=$passQ")
            if (action != null) append("&action=$action")
            extra.forEach { (k, v) -> append("&$k=$v") }
        }
        return Request.Builder()
            .url("$baseUrl/player_api.php?$params")
            .header("User-Agent", "Agoro/2.1")
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
     *
     * On [BackgroundWork], not IO: decoding 39,000 JSON objects is minutes
     * of CPU, and an IO thread runs at the same priority as the thread
     * drawing the screen. The catalogue is the slowest thing the app does
     * and nothing on screen waits on it, so it is the first thing that
     * should yield. See [BackgroundWork].
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun <T : Any> callList(
        action: String,
        /**
         * Optional sections (VOD, series) degrade to an empty list when the
         * panel answers with something that isn't an array at all — trial and
         * live-only accounts routinely have those packages disabled, and the
         * panel replies with an error object or an auth blob instead of [].
         * Failing the whole playlist over a disabled add-on locked those
         * accounts out entirely.
         */
        required: Boolean = true,
        map: (JsonObject) -> T?,
    ): List<T> = withContext(BackgroundWork.dispatcher) {
        http.newCall(buildRequest(action, emptyMap())).execute().use { resp ->
            if (!resp.isSuccessful) {
                if (!required) return@use emptyList()
                throw IOException("Server returned HTTP ${resp.code}")
            }
            val body = resp.body ?: throw IOException("Empty response from server")
            val out = ArrayList<T>()
            try {
                json.decodeToSequence<JsonElement>(
                    body.byteStream(),
                    DecodeSequenceMode.ARRAY_WRAPPED,
                ).forEach { el -> (el as? JsonObject)?.let(map)?.let(out::add) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Switching source mid-download cancels this scope. Wrapping it
                // as an IOException below would report a normal failure and
                // swallow the cancellation the coroutine machinery needs.
                throw e
            } catch (e: Exception) {
                // A response that was never an array (nothing decoded yet) is a
                // disabled section for optional endpoints. A stream that broke
                // MID-array still fails even there — a silently partial catalog
                // would overwrite the user's cache with a shrunken library.
                if (!required && out.isEmpty()) return@use emptyList()
                throw IOException("Catalog download failed for $action: ${e.message}")
            }
            out
        }
    }

    data class AccountInfo(
        val status: String?,
        val expiresAtMs: Long?,
        val activeConnections: Int?,
        val maxConnections: Int?,
    )

    /** Account status from player_api; null when the server doesn't report it. */
    suspend fun accountInfo(): AccountInfo? = runCatching {
        val userInfo = (call(null) as? JsonObject)?.get("user_info") as? JsonObject ?: return null
        AccountInfo(
            status = userInfo.str("status"),
            expiresAtMs = userInfo.str("exp_date")?.toLongOrNull()?.times(1000),
            activeConnections = userInfo.int("active_cons"),
            maxConnections = userInfo.int("max_connections"),
        )
    }.getOrNull()

    /** Validates credentials; throws with a readable message when login fails. */
    suspend fun authenticate() {
        val root = call(null) as? JsonObject
            ?: throw IOException("Unexpected response — is this an Xtream server?")
        val userInfo = root["user_info"] as? JsonObject
            ?: throw IOException("Unexpected response — is this an Xtream server?")
        val auth = userInfo.str("auth") ?: "0"
        if (auth != "1" && auth != "true") throw IOException("Login failed — check username and password")
        // Blocklist, not an Active allowlist: panels label working test lines
        // "Trial" (and paid lines all sorts of things). auth=1 is the gate;
        // only statuses that mean the line is dead get to refuse it.
        val status = userInfo.str("status")?.lowercase()
        if (status in setOf("banned", "disabled", "expired")) {
            throw IOException("Account status: ${userInfo.str("status")}")
        }
    }

    suspend fun liveCategories(): List<Category> = categories("get_live_categories", required = false)
    suspend fun vodCategories(): List<Category> = categories("get_vod_categories", required = false)
    suspend fun seriesCategories(): List<Category> = categories("get_series_categories", required = false)

    private suspend fun categories(action: String, required: Boolean = true): List<Category> =
        callList(action, required = required) { obj ->
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
                // The raw MPEG-TS mux, not the panel's .m3u8 endpoint: many
                // panels serve HLS as a re-segmented (often transcoded and
                // bitrate-capped) copy of the source, so playing .m3u8 caps
                // picture quality no matter what the player asks for.
                url = "$baseUrl/live/$userP/$passP/$id.ts",
                categoryId = obj.str("category_id"),
                // No number here: panels' `num` fields carry gaps and repeats,
                // and curation reorders anyway — [renumberChannels] owns it.
                // Blank, not null, was how panels say "no id" — and a stored
                // "" used to defeat the null-skip in EPG matching.
                epgId = obj.str("epg_channel_id")?.takeIf { it.isNotBlank() },
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
        callList("get_vod_streams", required = false) { obj ->
            val id = obj.int("stream_id") ?: return@callList null
            val ext = obj.str("container_extension")?.takeIf { it.isNotBlank() } ?: "mp4"
            Movie(
                id = "movie:$id",
                // Cleaned for display, same as the M3U path: the year and
                // quality live in their own fields, so "EN - Title (2019) 4K"
                // in a poster grid is pure noise. Year is read from the raw
                // name first, before the clean erases it.
                name = obj.str("name")
                    ?.let { ContentClassifier.cleanTitle(it).ifBlank { it } }
                    ?: "Movie $id",
                poster = ArtworkUrl.poster(obj.str("stream_icon")),
                url = "$baseUrl/movie/$userP/$passP/$id.$ext",
                categoryId = obj.str("category_id"),
                year = obj.int("year") ?: yearFrom(obj.str("name")),
                rating = obj.dbl("rating_5based")?.times(2) ?: obj.dbl("rating"),
                xtreamId = id,
                quality = obj.str("name")?.let { QualityTag.of(it) },
                // Seconds since the epoch, and panels emit it as a string.
                // Clamped to sane bounds: a few write 0 or a millisecond value
                // there, and either would sort the whole library wrong.
                addedMs = obj.str("added")?.trim()?.toLongOrNull()
                    ?.takeIf { it in 946_684_800..4_102_444_800 }?.times(1000),
            )
        }

    suspend fun series(): List<Series> =
        callList("get_series", required = false) { obj ->
            val id = obj.int("series_id") ?: return@callList null
            Series(
                id = "series:$id",
                name = obj.str("name")
                    ?.let { ContentClassifier.cleanTitle(it).ifBlank { it } }
                    ?: "Series $id",
                poster = ArtworkUrl.poster(obj.str("cover")),
                // The wide still, for the hero. The panel carries one for 70%
                // of the catalogue and nothing was reading it.
                backdrop = ArtworkUrl.backdrop(obj.str("backdrop_path") ?: obj.arr0("backdrop_path")),
                categoryId = obj.str("category_id"),
                year = obj.int("year")
                    ?: yearFrom(obj.str("releaseDate") ?: obj.str("release_date"))
                    ?: yearFrom(obj.str("name")),
                rating = obj.dbl("rating_5based")?.times(2) ?: obj.dbl("rating"),
                plot = PlotText.preferred(obj.str("plot")?.takeIf { it.isNotBlank() }),
                genre = obj.str("genre")?.takeIf { it.isNotBlank() },
                // Panels ship the actor list under either key.
                cast = (obj.str("cast") ?: obj.str("actors"))?.takeIf { it.isNotBlank() },
                director = obj.str("director")?.takeIf { it.isNotBlank() },
                xtreamId = id,
                // Read from the RAW name, before cleanTitle strips the token:
                // that strip is what makes "Show 4K" and "Show HD" reduce to
                // one title, and without this the fold that follows would
                // have no way to tell which of them is the 4K one.
                quality = obj.str("name")?.let { QualityTag.of(it) },
                // Series have no `added`; `last_modified` is the panel's
                // equivalent — it moves when a new episode lands, which is
                // exactly what "recently added" should surface for a box set.
                addedMs = obj.str("last_modified")?.trim()?.toLongOrNull()
                    ?.takeIf { it in 946_684_800..4_102_444_800 }?.times(1000),
            )
        }

    /** Loads episodes for a series; tolerates every container shape panels emit. */
    suspend fun seriesEpisodes(seriesId: Int): List<Episode> =
        parseEpisodes(call("get_series_info", mapOf("series_id" to seriesId.toString())))

    internal fun parseEpisodes(rootEl: JsonElement): List<Episode> {
        // A real series answer is an object ({seasons, info, episodes}). Some
        // portals answer an id they don't serve with 200 and a bare array —
        // that is a broken portal, not a series with zero episodes, and it
        // must surface as a retryable failure, not "No episodes found".
        val root = rootEl as? JsonObject
            ?: throw IOException("Unexpected series response")
        var leaves = root["episodes"]?.let { episodeLeaves(it, seasonKey = null) }.orEmpty()
        if (leaves.isEmpty()) {
            // Stalker-derived backends (IPTVEditor and kin) put the episode
            // arrays INSIDE each season object instead of the top-level
            // container — `{"seasons":[{...,"episodes":[…]}]}` with `episodes`
            // absent or empty. Read as "a valid series with none", those
            // playlists showed every series as empty while other players
            // handled the variant.
            leaves = (root["seasons"] as? JsonArray).orEmpty()
                .filterIsInstance<JsonObject>()
                .flatMap { season ->
                    val number = season.int("season_number") ?: season.int("season")
                    season["episodes"]?.let { episodeLeaves(it, number) }.orEmpty()
                }
        }
        return leaves.mapNotNull { (seasonKey, obj) ->
            val id = obj.str("id") ?: obj.str("episode_id") ?: obj.str("stream_id")
                ?: return@mapNotNull null
            val ext = obj.str("container_extension")?.takeIf { it.isNotBlank() } ?: "mp4"
            val info = obj["info"] as? JsonObject
            Episode(
                id = "ep:$id",
                title = obj.str("title") ?: "Episode",
                season = obj.int("season") ?: seasonKey ?: 1,
                episodeNum = obj.int("episode_num") ?: 0,
                url = "$baseUrl/series/$userP/$passP/$id.$ext",
                // A STILL, not a poster: 16:9, and asking for the 2:3 rung
                // handed the row the middle third of the frame. See ArtworkUrl.
                poster = ArtworkUrl.still(info?.str("movie_image")),
                durationText = info?.str("duration")?.takeIf { it.isNotBlank() },
                plot = PlotText.preferred(info?.str("plot")?.takeIf { it.isNotBlank() }),
            )
        }.sortedWith(compareBy({ it.season }, { it.episodeNum }))
    }

    /**
     * Panels emit the `episodes` container in at least four shapes:
     * `{"1":[{…}]}` (map of season → array), `[[{…}]]` (array of arrays),
     * `[{…}]` (flat array of episode objects), and `{"1":{"1":{…}}}`
     * (map of season → map of episode-number → object). Walk any of them
     * and collect (seasonKey, episodeObject) pairs, where seasonKey is the
     * outermost numeric container key on the path (inner keys are episode
     * numbers) — the only place some panels record the season at all.
     */
    private fun episodeLeaves(el: JsonElement, seasonKey: Int?): List<Pair<Int?, JsonObject>> =
        when (el) {
            is JsonObject ->
                // An episode object is recognised by fields only an episode
                // has; anything else (keyed by season/episode numbers or
                // names) is a container to walk into. The list is deliberately
                // broad — a leaf that goes unrecognised is walked into and
                // silently contributes nothing, which shows as "No episodes
                // found" on panels that work fine in other players.
                if ("id" in el || "episode_id" in el || "stream_id" in el ||
                    "episode_num" in el || "container_extension" in el
                ) {
                    listOf(seasonKey to el)
                } else {
                    el.entries.flatMap { (k, v) -> episodeLeaves(v, seasonKey ?: k.toIntOrNull()) }
                }
            is JsonArray -> el.flatMap { episodeLeaves(it, seasonKey) }
            else -> emptyList()
        }

    /** Enriches a movie with plot/genre/duration from get_vod_info. */
    suspend fun movieDetails(movie: Movie): Movie {
        val id = movie.xtreamId ?: return movie
        val root = runCatching {
            call("get_vod_info", mapOf("vod_id" to id.toString())).jsonObject
        }.getOrNull() ?: return movie
        val info = root["info"] as? JsonObject ?: return movie
        return movie.copy(
            plot = PlotText.preferred(info.str("plot")?.takeIf { it.isNotBlank() }) ?: movie.plot,
            genre = info.str("genre")?.takeIf { it.isNotBlank() } ?: movie.genre,
            // Panels ship the actor list under either key.
            cast = (info.str("cast") ?: info.str("actors"))?.takeIf { it.isNotBlank() }
                ?: movie.cast,
            director = info.str("director")?.takeIf { it.isNotBlank() } ?: movie.director,
            durationText = info.str("duration")?.takeIf { it.isNotBlank() } ?: movie.durationText,
            // Through ArtworkUrl like every other artwork field. The detail
            // page reads the same provider metadata the rails do, so without
            // this a film repaired in its row reverted to the dead host the
            // moment it was opened.
            poster = ArtworkUrl.poster(info.str("movie_image")) ?: movie.poster,
            year = movie.year ?: yearFrom(info.str("releasedate") ?: info.str("release_date")),
        )
    }

    private fun yearFrom(text: String?): Int? =
        text?.let { YEAR.find(it)?.value?.toIntOrNull() }
}

// --- defensive JSON accessors -------------------------------------------------

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? =
    str(key)?.trim()?.toDoubleOrNull()?.toInt()

/**
 * The first element of an array-valued field.
 *
 * Panels ship `backdrop_path` as a LIST — this one does, on 8,159 of 8,598
 * series — and [str] answers null for it, so a field that is nearly always
 * present read as nearly always absent.
 */
private fun JsonObject.arr0(key: String): String? =
    (this[key] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
        ?.takeIf { it.isNotBlank() }

private fun JsonObject.dbl(key: String): Double? =
    str(key)?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }

private fun String.fromBase64(): String =
    runCatching {
        String(android.util.Base64.decode(this, android.util.Base64.DEFAULT), Charsets.UTF_8).trim()
    }.getOrDefault(this)
