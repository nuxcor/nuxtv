package com.agoro.tv.data

/** Extracts an advertised video quality tag from a raw stream name. */
object QualityTag {

    private val fourK = Regex("""(?i)\b(4k|uhd|2160p?|3840p)\b""")

    // rank() and tierOf() both know 2K, so of() has to as well. Without it a
    // channel NAMED "Sky Sports 2K" or "ESPN 1440p" scored rank(null) == 0 —
    // below SD — which is the exact failure the 2K tier was added to fix,
    // just reached from the name instead of from a measurement.
    private val twoK = Regex("""(?i)\b(2k|1440p?)\b""")
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
            twoK.containsMatchIn(name) -> "2K"
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
        Regex(
            // Codec tokens belong here too. Without them "BBC NEWS HEVC 4K"
            // lost its 4K and kept its HEVC, so the channel called itself
            // "BBC NEWS HEVC" on screen — and grouped separately from the
            // same channel's other feeds, which is the whole reason this
            // reduction exists.
            //
            // ASCII "RAW" is deliberately absent: WWE Raw is a channel, and
            // the provider's decorative RAW arrives in superscript, which
            // stripDecoration has already removed by the time this runs.
            """(?i)\b(4k|8k|uhd|2k|fhd|full\s?hd|hd|sd|hevc|h\.?265|h\.?264|""" +
                """1080p|720p|1440p|2160p|3840p|4320p|480p|576p)\b"""
        )

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
     * variant, keeping the original order of first appearance. The variants it
     * collapses are kept on the survivor as [LiveChannel.fallbackUrls], best
     * quality first.
     *
     * Keeping them is the whole difference between a merge and a deletion.
     * [com.agoro.tv.ui.player.PlayerSession] recovers a starving stream by
     * stepping down that list — its contract is that collapsing a channel
     * "keeps the rest as fallbackUrls" — and the manifest's own collapse has
     * always done so. This one did not: it picked a winner and dropped the
     * losers on the floor. So the merge handed the viewer the HEAVIEST feed of
     * every duplicated channel and, in the same act, destroyed the lighter
     * feeds the ladder would have stepped down to when the line could not
     * carry it. A stall mid-programme then had nowhere to go.
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
        // Grouped rather than reduced: the losers are the payload now, not
        // waste. LinkedHashMap still fixes output order to each key's first
        // appearance, which is what "keeping the original order" meant.
        val groups = LinkedHashMap<String, MutableList<LiveChannel>>()
        for (channel in channels) {
            groups.getOrPut(keyOf(channel)) { mutableListOf() }.add(channel)
        }
        return groups.values.map { variants ->
            // The overwhelming majority of keys hold exactly one channel, and
            // this runs over the whole catalogue: no copy, no sort, no alloc.
            if (variants.size == 1) return@map variants[0]
            var winner = variants[0]
            for (challenger in variants) {
                val challengerRank = rank(challenger.quality)
                val holderRank = rank(winner.quality)
                val wins = challengerRank > holderRank ||
                    (
                        challengerRank == holderRank &&
                            challenger.url in measured && winner.url !in measured
                        )
                if (wins) winner = challenger
            }
            // Best first, so the ladder's first step down is the smallest one
            // that still helps. Any fallbacks the winner already carried (the
            // manifest's own alternates) keep their place at the head.
            val alternates = variants.asSequence()
                .filter { it.url != winner.url }
                .sortedByDescending { rank(it.quality) }
                .map { it.url }
            winner.copy(fallbackUrls = (winner.fallbackUrls + alternates).distinct())
        }
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
