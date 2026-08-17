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
            val request = Request.Builder().url(url).header("User-Agent", "Agoro/1.0").build()
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
        // Provider titles arrive as "EN - Avengers (2019) 4K HEVC" — searched
        // verbatim, TMDB finds nothing and the details pane silently stays
        // bare. Search with the cleaned title; if the year makes the search
        // too narrow (release-date mismatches are common), retry without it.
        val cleaned = searchTitle(title).ifBlank { title }
        val effectiveYear = year ?: yearIn(title)
        val q = URLEncoder.encode(cleaned, "UTF-8")
        val yearParam = effectiveYear?.let {
            if (kind == "movie") "&year=$it" else "&first_air_date_year=$it"
        } ?: ""
        var search = get(
            "https://api.themoviedb.org/3/search/$kind?api_key=$apiKey&query=$q$yearParam"
        ) ?: return null
        if ((search["results"] as? JsonArray).isNullOrEmpty() && yearParam.isNotEmpty()) {
            search = get(
                "https://api.themoviedb.org/3/search/$kind?api_key=$apiKey&query=$q"
            ) ?: return null
        }
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

    companion object {
        // Release/quality/codec noise that never belongs in a search query.
        private val junk = Regex(
            """(?i)\b(4k|uhd|fhd|full\s?hd|hd|sd|2160p|1080p|720p|480p|576p|hevc|h\.?26[45]|""" +
                """x26[45]|10\s?bit|hdr10?\+?|dolby\s?vision|dv|web[-\s]?dl|webrip|bluray|""" +
                """blu-ray|brrip|dvdrip|remux|multi|vostfr|dubbed|subbed|vod)\b"""
        )

        // "[EN]", "|FR|", "(MULTI)" style tags anywhere in the name.
        private val bracketTags = Regex("""[\[|(][^\])|]{0,20}[\])|]""")

        // "EN - ", "FR| ", "NL: " style prefixes.
        private val langPrefix = Regex("""^\s*[A-Z]{2,3}\s*[-:|•]\s*""")

        private val yearToken = Regex("""\b(19|20)\d{2}\b""")

        /** The four-digit year buried in a raw provider title, if any. */
        fun yearIn(rawTitle: String): Int? =
            yearToken.find(rawTitle)?.value?.toIntOrNull()

        /** Raw provider title reduced to something TMDB can actually match. */
        fun searchTitle(rawTitle: String): String =
            rawTitle
                // A bracket group carrying the year would erase it before
                // yearIn has run at the call site — the year is read from the
                // raw title, so stripping here is safe.
                .replace(bracketTags, " ")
                .replace(langPrefix, "")
                .replace(junk, " ")
                .replace(yearToken, " ")
                .replace(Regex("""\s[-–—:|•]+\s"""), " ")
                .replace(Regex("""\s{2,}"""), " ")
                .trim()
                .trimEnd('-', ':', '|', '•')
                .trim()
    }
}
