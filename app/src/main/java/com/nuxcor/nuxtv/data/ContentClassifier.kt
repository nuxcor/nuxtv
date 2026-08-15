package com.nuxcor.nuxtv.data

/**
 * Turns a flat M3U playlist into a structured TV library: Live channels,
 * Movies, and Series (with episodes grouped by show, season and episode).
 *
 * Signals used, strongest first:
 *  1. Xtream-style URL paths (/live/, /movie/, /series/)
 *  2. Series numbering patterns in the title (S01E02, 1x02, "Season 1 Episode 2")
 *  3. group-title keywords (VOD, Movies, Series, …)
 *  4. File extension of the stream URL (.mkv/.mp4 → VOD, .ts/.m3u8/none → live)
 */
object ContentClassifier {

    private val seasonEpisodePatterns = listOf(
        Regex("""(?i)\bS(\d{1,2})\s*[.\-_ ]?\s*EP?(\d{1,4})\b"""),
        Regex("""(?i)\b(\d{1,2})\s*x\s*(\d{1,4})\b"""),
        Regex("""(?i)\bseason[\s.]*(\d{1,2})\b[\s.\-]*(?:episode|ep)[\s.]*(\d{1,4})\b"""),
    )
    private val episodeOnlyPattern = Regex("""(?i)\b(?:episode|ep)[\s.]*(\d{1,4})\b""")

    private val movieGroupKeywords = listOf("movie", "vod", "film", "cine", "pelicula", "película")
    private val seriesGroupKeywords = listOf("series", "serie", "show", "drama", "anime", "novela")
    private val liveGroupKeywords = listOf("live", "tv", "channel", "sport", "news", "kids", "music")

    private val vodExtensions = setOf("mp4", "mkv", "avi", "mov", "flv", "wmv", "m4v", "webm", "mpg", "mpeg")

    private val yearPattern = Regex("""[(\[]?((?:19|20)\d{2})[)\]]?""")
    private val qualityNoise = Regex(
        """(?i)[\[(]?\b(4k|uhd|fhd|hd|sd|hevc|h\.?26[45]|x26[45]|1080p?|720p?|2160p?|multi(?:sub)?|vip)\b[\])]?"""
    )

    fun classify(entries: List<M3uEntry>): ContentBundle {
        val channels = mutableListOf<LiveChannel>()
        val movies = mutableListOf<Movie>()
        // series key (normalized name) -> accumulated info
        data class SeriesAcc(
            val name: String,
            var poster: String?,
            var group: String?,
            val episodes: MutableList<Episode> = mutableListOf(),
        )

        val seriesMap = LinkedHashMap<String, SeriesAcc>()
        val liveGroups = LinkedHashMap<String, String>()
        val movieGroups = LinkedHashMap<String, String>()
        val seriesGroups = LinkedHashMap<String, String>()
        var channelNumber = 1

        fun groupId(map: LinkedHashMap<String, String>, name: String?): String? {
            val n = name?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val id = n.lowercase()
            map.putIfAbsent(id, n)
            return id
        }

        for ((index, entry) in entries.withIndex()) {
            if (isSeparator(entry.title)) continue
            val kind = detectKind(entry)
            when (kind) {
                Kind.LIVE -> channels += LiveChannel(
                    id = "live:$index",
                    name = entry.title,
                    logo = entry.logo,
                    url = entry.url,
                    categoryId = groupId(liveGroups, entry.group),
                    number = channelNumber++,
                    epgId = entry.tvgId,
                    // Raw TS/progressive streams can be recorded by dumping bytes; HLS can't.
                    recordUrl = entry.url.takeUnless {
                        it.lowercase().substringBefore('?').endsWith(".m3u8")
                    },
                    quality = QualityTag.of(entry.title),
                )

                Kind.MOVIE -> movies += Movie(
                    id = "movie:$index",
                    name = cleanTitle(entry.title),
                    poster = entry.logo,
                    url = entry.url,
                    categoryId = groupId(movieGroups, entry.group),
                    year = yearOf(entry.title),
                    quality = QualityTag.of(entry.title),
                )

                Kind.EPISODE -> {
                    val (seriesName, season, episodeNum) = parseEpisode(entry.title)
                    val key = seriesName.lowercase()
                    val acc = seriesMap.getOrPut(key) {
                        SeriesAcc(name = seriesName, poster = entry.logo, group = entry.group)
                    }
                    if (acc.poster == null) acc.poster = entry.logo
                    if (acc.group == null) acc.group = entry.group
                    acc.episodes += Episode(
                        id = "ep:$index",
                        title = entry.title,
                        season = season,
                        episodeNum = if (episodeNum > 0) episodeNum else acc.episodes.size + 1,
                        url = entry.url,
                        poster = entry.logo,
                    )
                }
            }
        }

        val seriesList = seriesMap.entries.map { (key, acc) ->
            Series(
                id = "series:$key",
                name = acc.name,
                poster = acc.poster,
                categoryId = groupId(seriesGroups, acc.group),
                year = yearOf(acc.name),
                episodes = acc.episodes.sortedWith(compareBy({ it.season }, { it.episodeNum })),
            )
        }

        return ContentBundle(
            liveCategories = liveGroups.map { (id, name) -> Category(id, name) },
            channels = channels,
            movieCategories = movieGroups.map { (id, name) -> Category(id, name) },
            movies = movies,
            seriesCategories = seriesGroups.map { (id, name) -> Category(id, name) },
            series = seriesList,
        )
    }

    private val symbolWrapped = Regex("""^[^\p{L}\p{N}]{3,}.*[^\p{L}\p{N}]{3,}$""")

    /** Providers pad playlists with separator rows like "#### SPORTS ####". */
    fun isSeparator(title: String): Boolean {
        val t = title.trim()
        return t.none { it.isLetterOrDigit() } || symbolWrapped.matches(t)
    }

    private enum class Kind { LIVE, MOVIE, EPISODE }

    private fun detectKind(entry: M3uEntry): Kind {
        val url = entry.url.lowercase()
        val path = url.substringAfter("://").substringAfter("/")

        // 1. Xtream-style URL paths are authoritative.
        when {
            path.startsWith("live/") || "/live/" in url -> {
                // Live path but a series-numbered title still means an episode list.
                if (hasEpisodeMarker(entry.title)) return Kind.EPISODE
                return Kind.LIVE
            }
            path.startsWith("series/") || "/series/" in url -> return Kind.EPISODE
            path.startsWith("movie/") || "/movie/" in url || "/movies/" in url -> return Kind.MOVIE
        }

        // 2. Series numbering in the title.
        if (hasEpisodeMarker(entry.title)) return Kind.EPISODE

        val group = entry.group?.lowercase().orEmpty()
        val ext = url.substringAfterLast('.', "").substringBefore('?')

        // 3. Group-title keywords. Explicit VOD/movie markers outrank genre-ish
        //    series words ("VOD | Drama" is a movie shelf, not a series).
        if (movieGroupKeywords.any { it in group }) {
            // "movies" group but a live-format stream → still live (e.g. 24/7 movie channels).
            return if (ext in vodExtensions || ext.isEmpty()) Kind.MOVIE else Kind.LIVE
        }
        if (seriesGroupKeywords.any { it in group }) return Kind.EPISODE
        if (liveGroupKeywords.any { it in group }) return Kind.LIVE

        // 4. Fall back to the stream container.
        return if (ext in vodExtensions) Kind.MOVIE else Kind.LIVE
    }

    private fun hasEpisodeMarker(title: String): Boolean =
        seasonEpisodePatterns.any { it.containsMatchIn(title) }

    /** Returns (series name, season, episode) extracted from an episode title. */
    fun parseEpisode(title: String): Triple<String, Int, Int> {
        for (pattern in seasonEpisodePatterns) {
            val match = pattern.find(title) ?: continue
            val season = match.groupValues[1].toIntOrNull() ?: 1
            val episode = match.groupValues[2].toIntOrNull() ?: 0
            val name = cleanTitle(title.substring(0, match.range.first))
                .ifBlank { cleanTitle(title) }
            return Triple(name, season, episode)
        }
        val epOnly = episodeOnlyPattern.find(title)
        if (epOnly != null) {
            val name = cleanTitle(title.substring(0, epOnly.range.first)).ifBlank { cleanTitle(title) }
            return Triple(name, 1, epOnly.groupValues[1].toIntOrNull() ?: 0)
        }
        return Triple(cleanTitle(title), 1, 0)
    }

    fun yearOf(title: String): Int? =
        yearPattern.findAll(title).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()

    /** Strips quality tags, provider prefixes ("EN -", "|FR|") and dangling separators. */
    fun cleanTitle(raw: String): String {
        var t = raw
        t = t.replace(qualityNoise, " ")
        t = t.replace(Regex("""^\s*[|\[(]?[A-Z]{2,3}[|\])]?\s*[-:|]\s*"""), "")
        t = t.replace(Regex("""[(\[]\s*(?:19|20)\d{2}\s*[)\]]"""), " ")
        t = t.replace(Regex("""\s{2,}"""), " ")
        t = t.trim().trimEnd('-', ':', '|', '.', ',').trim()
        return t
    }
}
