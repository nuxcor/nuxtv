package com.nuxcor.nuxtv.data

/**
 * Channel → guide resolution for playlists a middleman never curated.
 *
 * Raw provider names carry country prefixes, quality tokens and punctuation
 * that guide display names don't ("US| Sky Sports 1 FHD" vs "Sky Sports 1"),
 * and their `epg_channel_id`s frequently point at nothing. Resolution runs
 * in stages from exact to fuzzy, and a fuzzy stage only ever matches when
 * the answer is unambiguous — a wrong guide on a channel is worse than none.
 *
 * Everything here is pure and index-based: [resolve] does one pass over the
 * channels against maps prepared at XMLTV parse time, so the callers that
 * fire per channel per minute keep their O(1) lookups.
 */
object EpgMatcher {

    /**
     * Case- and diacritic-insensitive text ("Télé" == "tele").
     *
     * NFKD rather than NFD: the compatibility form is what collapses the
     * superscript decoration providers bolt onto names ("ᵁᴴᴰ" → "UHD") and
     * full-width forms, all of which NFD leaves standing as letters. See
     * [TextNorm]. Normalization runs before lowercasing because the
     * superscript capitals have no lowercase mapping of their own — folding
     * first is what lets "ᴴᴰ" and "hd" meet.
     */
    fun fold(text: String): String {
        val normalized = TextNorm.compat(text).lowercase()
        // Scanning before filtering: fold runs per channel per guide match,
        // and filterNot builds a fresh string even when nothing is dropped.
        if (normalized.none { it.code in 0x300..0x36F }) return normalized
        return normalized.filterNot { it.code in 0x300..0x36F }
    }

    /**
     * A channel's identity key: platform/country tags and quality tokens
     * stripped, diacritics folded, everything non-alphanumeric dropped —
     * "US| Sky Sports 1 FHD" → "skysports1". Bare digits survive
     * deliberately: "Sky Sports 1" and "Sky Sports 2" are different
     * channels. Never blank: a name that is all decoration falls back to
     * its folded raw form.
     */
    fun normalizeKey(name: String): String {
        val stripped = QualityTag.baseName(ContentClassifier.stripChannelTags(name))
        val key = fold(stripped).filter(Char::isLetterOrDigit)
        return key.ifBlank { fold(name).filter(Char::isLetterOrDigit) }
    }

    /** Same pipeline with word boundaries kept, for the tie-break stage. */
    fun normalizeTokens(name: String): List<String> {
        val stripped = QualityTag.baseName(ContentClassifier.stripChannelTags(name))
        val folded = fold(stripped.ifBlank { name })
        return folded.split(Regex("""[^a-z0-9]+""")).filter { it.isNotBlank() }
    }

    data class Resolution(
        /** channel.id → lowercase xmltv id (always a key of [XmltvData.programmes]). */
        val byChannelId: Map<String, String>,
        val matched: Int,
        val total: Int,
    )

    /**
     * Stages, first hit wins; a stage only hits when the resolved id has
     * programmes (a guide channel with an empty lane is no better than no
     * match, and blocks the later stages from finding a fuller one):
     *  a) the channel's own epg id;
     *  b) exact display-name match, raw name then tvg-name;
     *  c) normalized-key match against every guide display-name alternate;
     *  d) token tie-break: unique guide candidate sharing the first token
     *     whose token set differs by at most one token. Zero or several
     *     candidates → unmatched.
     */
    fun resolve(channels: List<LiveChannel>, data: XmltvData): Resolution {
        // Token index for stage d, built once: first token → candidates.
        val buckets = HashMap<String, MutableList<Pair<Set<String>, String>>>()
        data.altNames.forEach { (id, names) ->
            if (id !in data.programmes) return@forEach
            names.forEach { alt ->
                val tokens = normalizeTokens(alt)
                tokens.firstOrNull()?.let { first ->
                    buckets.getOrPut(first) { mutableListOf() }.add(tokens.toSet() to id)
                }
            }
        }

        fun exactName(name: String?): String? =
            name?.trim()?.lowercase()?.let { data.nameToId[it] }?.takeIf { it in data.programmes }

        fun normalized(name: String?): String? =
            name?.let { data.normalizedToId[normalizeKey(it)] }?.takeIf { it in data.programmes }

        fun tieBreak(name: String): String? {
            val tokens = normalizeTokens(name)
            val first = tokens.firstOrNull() ?: return null
            val set = tokens.toSet()
            val hits = buckets[first].orEmpty()
                .filter { (candidate, _) ->
                    val diff = (candidate - set).size + (set - candidate).size
                    diff <= 1
                }
                .map { it.second }
                .distinct()
            return hits.singleOrNull()
        }

        val resolved = HashMap<String, String>()
        for (channel in channels) {
            val id = channel.epgId?.lowercase()?.takeIf { it in data.programmes }
                ?: exactName(channel.name)
                ?: exactName(channel.tvgName)
                ?: normalized(channel.name)
                ?: normalized(channel.tvgName)
                ?: tieBreak(channel.name)
            if (id != null) resolved[channel.id] = id
        }
        return Resolution(byChannelId = resolved, matched = resolved.size, total = channels.size)
    }
}
