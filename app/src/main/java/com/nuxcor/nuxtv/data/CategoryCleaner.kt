package com.nuxcor.nuxtv.data

/**
 * Category cleanup for playlists a middleman never curated. Raw providers
 * ship hundreds of near-duplicate shelves — "US | SPORTS", "US| SPORT HD",
 * "#### SPORTS ####" — plus decorative separators with no content identity.
 * Categories merge on a region-PRESERVING key (the opposite discipline of
 * [EpgMatcher.normalizeKey]: "US| SPORTS" and "UK| SPORTS" are different
 * shelves and must stay that way), junk prunes, and every item's categoryId
 * is remapped so caches, EPG matching, merging and the UI all see one clean
 * model.
 */
object CategoryCleaner {

    // Tokens length >= 4 lose a trailing 's' so SPORTS == SPORT and
    // MOVIES == MOVIE — except words that ARE their plural.
    private val pluralExceptions = setOf("news", "plus")

    private val nonAlnumRuns = Regex("""[^a-z0-9]+""")

    // Leading/trailing decoration: any run of symbols with no letter or digit
    // ("#### SPORTS ####", "== KIDS ==", "•• MOVIES ••").
    private val edgeDecoration = Regex("""^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$""")

    /**
     * Region-preserving identity key: case/diacritics folded, quality tokens
     * stripped, punctuation collapsed, plurals folded.
     * "US | SPORTS HD" → "us sport"; "UK| SPORTS" → "uk sport" (distinct).
     */
    fun categoryKey(name: String): String {
        val folded = EpgMatcher.fold(QualityTag.baseName(name))
        val tokens = folded.split(nonAlnumRuns)
            .filter { it.isNotBlank() }
            .map { token ->
                if (token.length >= 4 && token.endsWith("s") && token !in pluralExceptions) {
                    token.dropLast(1)
                } else token
            }
        return tokens.joinToString(" ").ifBlank { name.trim().lowercase() }
    }

    /** The kept category's label: edge decoration unwrapped, casing kept. */
    fun displayName(name: String): String =
        name.replace(edgeDecoration, "").trim().ifBlank { name.trim() }

    /** True for purely decorative names — no letter or digit anywhere. */
    private fun isJunk(name: String): Boolean = name.none { it.isLetterOrDigit() }

    fun clean(bundle: ContentBundle): ContentBundle {
        val (liveCats, liveRemap) = mergeCategories(bundle.liveCategories)
        val (movieCats, movieRemap) = mergeCategories(bundle.movieCategories)
        val (seriesCats, seriesRemap) = mergeCategories(bundle.seriesCategories)

        val channels = bundle.channels.map { it.copy(categoryId = liveRemap[it.categoryId]) }
        val movies = bundle.movies.map { it.copy(categoryId = movieRemap[it.categoryId]) }
        val series = bundle.series.map { it.copy(categoryId = seriesRemap[it.categoryId]) }

        return bundle.copy(
            liveCategories = liveCats.filter { cat -> channels.any { it.categoryId == cat.id } },
            movieCategories = movieCats.filter { cat -> movies.any { it.categoryId == cat.id } },
            seriesCategories = seriesCats.filter { cat -> series.any { it.categoryId == cat.id } },
            channels = channels,
            movies = movies,
            series = series,
        )
    }

    /**
     * Merges categories by [categoryKey], first seen wins (playlist order and
     * label). Junk categories map to null — their items surface under "All",
     * which already lists null-category items.
     */
    private fun mergeCategories(
        categories: List<Category>,
    ): Pair<List<Category>, Map<String?, String?>> {
        val kept = LinkedHashMap<String, Category>()
        val remap = HashMap<String?, String?>()
        remap[null] = null
        for (category in categories) {
            if (isJunk(category.name)) {
                remap[category.id] = null
                continue
            }
            val key = categoryKey(category.name)
            val holder = kept.getOrPut(key) {
                Category(id = category.id, name = displayName(category.name))
            }
            remap[category.id] = holder.id
        }
        return kept.values.toList() to remap
    }
}
