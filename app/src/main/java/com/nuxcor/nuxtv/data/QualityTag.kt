package com.nuxcor.nuxtv.data

/** Extracts an advertised video quality tag from a raw stream name. */
object QualityTag {

    private val fourK = Regex("""(?i)\b(4k|uhd|2160p?|3840p)\b""")
    private val fhd = Regex("""(?i)\b(fhd|full\s?hd|1080p?)\b""")
    private val hd = Regex("""(?i)\b(hd|720p?)\b""")
    private val sd = Regex("""(?i)\b(sd|480p?|576p?)\b""")

    // Read through the compatibility form: "ESPN ᵁᴴᴰ ³⁸⁴⁰ᴾ" advertises 4K in
    // superscript, and the tier is worth recovering even though the name
    // itself drops the run entirely.
    fun of(rawName: String): String? {
        val name = TextNorm.compat(rawName)
        return when {
            fourK.containsMatchIn(name) -> "4K"
            fhd.containsMatchIn(name) -> "FHD"
            hd.containsMatchIn(name) -> "HD"
            sd.containsMatchIn(name) -> "SD"
            else -> null
        }
    }

    /** Higher = better; unknown quality ranks below SD. */
    fun rank(quality: String?): Int = when (quality) {
        "4K" -> 5
        // Must stay above FHD. Omitting it left rank("2K") at 0 — below SD —
        // so a channel measured at 1440p lost the duplicate merge to its own
        // SD variant and sorted last under "Best quality first".
        "2K" -> 4
        "FHD" -> 3
        "HD" -> 2
        "SD" -> 1
        else -> 0
    }

    // Bare numbers ("Sky 1080") stay part of the identity; only explicit
    // quality tokens are stripped for duplicate grouping.
    private val allTags =
        Regex("""(?i)\b(4k|uhd|fhd|full\s?hd|hd|sd|1080p|720p|2160p|3840p|480p|576p)\b""")

    // Hoisted: baseName runs per channel per merge, and a Regex compiled
    // inside the call was the only allocation on that path.
    private val multiSpace = Regex("""\s{2,}""")

    /**
     * Channel name with quality tokens removed, for duplicate grouping.
     * Superscript decoration goes first — "ESPN ᵁᴴᴰ ³⁸⁴⁰ᴾ" and "ESPN HD" have to
     * reduce to the same "ESPN" or they group as two channels.
     */
    fun baseName(name: String): String =
        TextNorm.stripDecoration(name).replace(allTags, " ").replace(multiSpace, " ").trim()

    /**
     * Collapses duplicate channels (same base name) down to the best-quality
     * variant, keeping the original order of first appearance.
     *
     * [measured] holds the URLs whose quality was learned from actual decoded
     * playback rather than the stream's name. On equal rank the measured
     * variant wins: a stream that demonstrably decodes at FHD beats one that
     * merely says FHD — names overstate, measurements don't.
     */
    fun mergeBestQuality(
        channels: List<LiveChannel>,
        measured: Set<String> = emptySet(),
        // Default scope is per-category so regional feeds with the same name
        // don't merge; the "All channels" view passes a global key so a
        // channel duplicated across five categories lists once.
        // Keyed on the full identity pipeline, not baseName alone: baseName
        // only removes quality tokens, so "US| ESPN", "USA: ESPN" and
        // "ESPN ᵁᴴᴰ" stayed three entries on the same shelf. The region tag
        // that normalizeKey drops is already implied by the category scope.
        keyOf: (LiveChannel) -> String = { channel ->
            "${channel.categoryId}|${EpgMatcher.normalizeKey(channel.name)}"
        },
    ): List<LiveChannel> {
        val best = LinkedHashMap<String, LiveChannel>()
        for (channel in channels) {
            val key = keyOf(channel)
            val current = best[key]
            val challengerRank = rank(channel.quality)
            val holderRank = current?.let { rank(it.quality) } ?: -1
            val wins = current == null || challengerRank > holderRank ||
                (
                    challengerRank == holderRank &&
                        channel.url in measured && current.url !in measured
                    )
            if (wins) best[key] = channel
        }
        return best.values.toList()
    }

    /** Tier alone for a decoded height — the language the app's badges speak. */
    fun tierOf(height: Int): String? {
        if (height <= 0) return null
        return when {
            height >= 2000 -> "4K"
            // 1440p is its own tier. Calling it FHD was the reason a channel
            // that decodes at 2560x1440 announced itself as 1080p.
            height >= 1400 -> "2K"
            height >= 1000 -> "FHD"
            height >= 700 -> "HD"
            else -> "SD"
        }
    }

    /** Label for an actual decoded resolution, e.g. 1920x1080 → "1080p FHD". */
    fun ofResolution(width: Int, height: Int): String? {
        // Derived from tierOf rather than repeating its thresholds: the two
        // had already drifted once, and a 1440p stream then called itself
        // "1440p FHD" here while tierOf called it 2K.
        val tier = tierOf(height) ?: return null
        return "${height}p $tier"
    }
}
