package com.nuxcor.nuxtv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

data class TmdbInfo(
    val rating: Double?,
    val voteCount: Int?,
    val overview: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    /** "author — excerpt" strings, at most three. */
    val reviews: List<String>,
)

/**
 * Minimal TMDB client used to enrich movies/series with ratings, overviews
 * and review excerpts. Requires a user-supplied API key (Settings).
 */
class TmdbClient(private val http: OkHttpClient, private val apiKey: String) {

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun get(url: String): JsonObject? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).header("User-Agent", "Dzidzi/1.0").build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                json.parseToJsonElement(resp.body!!.string()).jsonObject
            }
        }.getOrNull()
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.dbl(key: String) = str(key)?.toDoubleOrNull()
    private fun JsonObject.int(key: String) = str(key)?.toDoubleOrNull()?.toInt()

    /** kind: "movie" or "tv". */
    suspend fun lookup(kind: String, title: String, year: Int?): TmdbInfo? {
        val q = URLEncoder.encode(title, "UTF-8")
        val yearParam = year?.let {
            if (kind == "movie") "&year=$it" else "&first_air_date_year=$it"
        } ?: ""
        val search = get(
            "https://api.themoviedb.org/3/search/$kind?api_key=$apiKey&query=$q$yearParam"
        ) ?: return null
        val first = (search["results"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val id = first.int("id") ?: return null

        val reviews = get("https://api.themoviedb.org/3/$kind/$id/reviews?api_key=$apiKey")
            ?.let { root ->
                (root["results"] as? JsonArray).orEmpty().mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    val author = obj.str("author") ?: "Anonymous"
                    val content = obj.str("content")?.replace(Regex("""\s+"""), " ")?.trim()
                        ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    "$author — ${content.take(280)}${if (content.length > 280) "…" else ""}"
                }.take(3)
            } ?: emptyList()

        return TmdbInfo(
            rating = first.dbl("vote_average")?.takeIf { it > 0 },
            voteCount = first.int("vote_count"),
            overview = first.str("overview")?.takeIf { it.isNotBlank() },
            // w500 upscaled into a 220x330dp poster on a 4K panel is visibly
            // soft; the backdrop fills 70% of the screen, so it gets the
            // original. These are the sizes TMDB serves for TV-sized layouts.
            posterUrl = first.str("poster_path")?.let { "https://image.tmdb.org/t/p/w780$it" },
            backdropUrl = first.str("backdrop_path")?.let { "https://image.tmdb.org/t/p/original$it" },
            reviews = reviews,
        )
    }
}
