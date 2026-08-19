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

    // "1 - ", "10.", "3) " — providers number their shelves for ordering;
    // the index is not identity and clutters every label.
    private val indexPrefix = Regex("""^\s*\d{1,4}\s*[-.):|]\s*""")

    // AV decoration beyond what QualityTag covers: "NETFLIX 4K 3840P DOLBY
    // VISION" and "NETFLIX" are the same catalog at different qualities.
    private val avNoise = Regex(
        """(?i)\b(8k|3840p|2160p|1440p|dolby|atmos|vision|audio|bluray|blu|ray|remux|""" +
            """hevc|h\.?26[45]|x26[45]|hdr10?\+?|10\s?bit|sdr|multi|subs?|multisubs?|vod)\b"""
    )

    // Anything that isn't a word character, space, or the few symbols brand
    // names legitimately carry (+ & ' .) — kills ⭐⚽ and friends.
    private val symbolJunk = Regex("""[^\p{L}\p{N}\s+&'.]""")

    private val separators = Regex("""[|:•/\\_\-\s]+""")

    /**
     * Region-preserving identity key: index prefix and an optional shared
     * namespace word dropped, case/diacritics folded, quality tokens
     * stripped, punctuation collapsed, plurals folded.
     * "4 - Billing - USA ULTIMATE" (dropWord "billing") → "usa ultimate";
     * "US | SPORTS HD" → "us sport"; "UK| SPORTS" → "uk sport" (distinct).
     */
    fun categoryKey(
        name: String,
        dropWord: String? = null,
        stopWords: Set<String> = emptySet(),
    ): String {
        val undecorated = name.replace(indexPrefix, "")
        val folded = EpgMatcher.fold(QualityTag.baseName(undecorated).replace(avNoise, " "))
        val tokens = folded.split(nonAlnumRuns)
            .filter { it.isNotBlank() }
            .map { token ->
                if (token.length >= 4 && token.endsWith("s") && token !in pluralExceptions) {
                    token.dropLast(1)
                } else token
            }
            .filterIndexed { index, token -> !(index == 0 && token == dropWord) }
            .filterNot { it in stopWords }
        return tokens.joinToString(" ").ifBlank { name.trim().lowercase() }
    }

    /**
     * The kept category's label, prettified: index prefix, shared namespace
     * word, decoration, emoji and separators out; SHOUTING title-cased with
     * short all-caps codes kept and mixed-case brand spellings left alone.
     * Quality tokens drop only when real words remain — "US | SPORTS HD" →
     * "US Sports", but a shelf NAMED by its tier ("4K UHD 3840P") keeps
     * its name rather than collapsing to residue.
     */
    // Four-letter all-caps that are brands/acronyms, not shouting. Words
    // like NEWS and FULL title-case; these keep their caps.
    private val capsBrands = setOf(
        "UEFA", "FIFA", "BEIN", "ESPN", "TNT", "CNN", "BBC", "ITV", "HBO",
        "AMC", "MTV", "TSN", "RAI", "ZDF", "ARD", "NBA", "NFL", "MLB",
        "NHL", "UFC", "WWE", "PPV", "DSTV", "TRT", "RTL",
    )

    fun displayName(
        name: String,
        dropWord: String? = null,
        stopWords: Set<String> = emptySet(),
    ): String {
        // Decoration first: symbolJunk spares anything Unicode calls a letter
        // or number, which is exactly what ᴿᴬᵂ and ⁶⁰ᶠᵖˢ are. Shelves merged
        // on a clean key already — only the label they merged UNDER still
        // showed the junk.
        val undecorated = TextNorm.stripDecoration(name)
            .replace(indexPrefix, "")
            .replace(symbolJunk, " ")
            .replace(edgeDecoration, "")

        fun folded(word: String): String {
            val f = EpgMatcher.fold(word)
            return if (f.length >= 4 && f.endsWith("s") && f !in pluralExceptions) {
                f.dropLast(1)
            } else f
        }
        fun wordsOf(text: String) = text.split(separators)
            .filter { it.isNotBlank() }
            .filterIndexed { index, word ->
                !(index == 0 && dropWord != null && EpgMatcher.fold(word) == dropWord)
            }
            .filterNot { folded(it) in stopWords }
        val full = wordsOf(undecorated)
        val stripped = wordsOf(QualityTag.baseName(undecorated).replace(avNoise, " "))
        // Quality tokens drop only when real words remain — a shelf NAMED by
        // its tier ("4K UHD 3840P") keeps its name instead of collapsing to
        // residue. Checked after the namespace word is gone, so "Billing"
        // can't stand in for actual content.
        val words = if (stripped.any { w -> w.count { it.isLetter() } >= 2 }) stripped else full

        return words.map { word ->
            when {
                // Region/network codes and digit-bearing tokens stay as-is.
                word.length <= 3 && word.all { it.isUpperCase() || it.isDigit() } -> word
                word.uppercase() in capsBrands && word.all { it.isUpperCase() } -> word
                // SHOUTING becomes Title case; mixed case is a brand's own
                // spelling and is left alone.
                word.all { !it.isLetter() || it.isUpperCase() } ->
                    word.lowercase().replaceFirstChar { it.uppercase() }
                else -> word
            }
        }.joinToString(" ").ifBlank { name.trim() }
    }

    /**
     * The provider's own namespace word, when there is one: the leading word
     * (after the index prefix) that at least 80% of four-plus categories
     * share — "Billing" on every live shelf, "VOD" on every movie shelf.
     * Repetition on every row carries no information.
     */
    private fun sharedLeadingWord(categories: List<Category>): String? {
        if (categories.size < 4) return null
        val firsts = categories.mapNotNull { category ->
            EpgMatcher.fold(category.name.replace(indexPrefix, ""))
                .split(nonAlnumRuns)
                .firstOrNull { it.isNotBlank() }
        }
        val (word, count) = firsts.groupingBy { it }.eachCount()
            .maxByOrNull { it.value } ?: return null
        return word.takeIf { count * 5 >= categories.size * 4 }
    }

    /** True for purely decorative names — no letter or digit anywhere. */
    private fun isJunk(name: String): Boolean = name.none { it.isLetterOrDigit() }

    fun clean(bundle: ContentBundle): ContentBundle {
        // The axis word is redundant inside its own axis: every shelf in the
        // movie list IS movies, so "NETFLIX MOVIES" and "NETFLIX" are one
        // shelf there (tokens arrive depluralized: movie / serie).
        val (liveCats, liveRemap) = mergeCategories(bundle.liveCategories)
        val (movieCats, movieRemap) =
            mergeCategories(bundle.movieCategories, setOf("movie", "film"))
        val (seriesCats, seriesRemap) =
            mergeCategories(bundle.seriesCategories, setOf("serie", "series", "show"))

        val channels = bundle.channels.map { it.copy(categoryId = liveRemap[it.categoryId]) }
        // Titles re-clean here — not only at parse — so bundles cached by
        // versions that predate a cleanup rule heal on the next read instead
        // of waiting out the refresh cycle. cleanTitle is idempotent.
        val movies = bundle.movies.map {
            it.copy(
                categoryId = movieRemap[it.categoryId],
                name = ContentClassifier.cleanTitle(it.name).ifBlank { it.name },
            )
        }
        val series = bundle.series.map {
            it.copy(
                categoryId = seriesRemap[it.categoryId],
                name = ContentClassifier.cleanTitle(it.name).ifBlank { it.name },
            )
        }

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
        stopWords: Set<String> = emptySet(),
    ): Pair<List<Category>, Map<String?, String?>> {
        val dropWord = sharedLeadingWord(categories)
        val kept = LinkedHashMap<String, Category>()
        val remap = HashMap<String?, String?>()
        remap[null] = null
        for (category in categories) {
            if (isJunk(category.name)) {
                remap[category.id] = null
                continue
            }
            val key = categoryKey(category.name, dropWord, stopWords)
            val holder = kept.getOrPut(key) {
                Category(id = category.id, name = displayName(category.name, dropWord, stopWords))
            }
            remap[category.id] = holder.id
        }
        return kept.values.toList() to remap
    }
}
