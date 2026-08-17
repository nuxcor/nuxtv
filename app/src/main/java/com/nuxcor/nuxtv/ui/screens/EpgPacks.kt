package com.nuxcor.nuxtv.ui.screens

internal val EPGSHARE_PACKS = listOf("US", "UK", "CA", "DE", "FR", "IN", "ZA")

/**
 * Which country packs plausibly match a playlist, judged from its own category
 * names ("US| NEWS", "UK| SPORT", "DSTV") rather than from where the device is.
 * A viewer's location says nothing about their lineup — IPTV playlists are
 * routinely watched from another continent — whereas the categories describe
 * exactly what is in there.
 */
internal fun suggestedEpgPacks(categoryNames: List<String>): List<String> {
    val hints = mapOf(
        "US" to listOf("us", "usa", "united states", "america"),
        "UK" to listOf("uk", "gb", "britain", "british", "united kingdom"),
        "CA" to listOf("ca", "canada", "canadian"),
        "DE" to listOf("de", "german", "germany", "deutsch"),
        "FR" to listOf("fr", "france", "french"),
        "IN" to listOf("in", "india", "indian", "hindi"),
        "ZA" to listOf("za", "south africa", "dstv", "supersport"),
    )
    val haystack = categoryNames.map { it.lowercase() }
    return EPGSHARE_PACKS.filter { code ->
        val needles = hints[code].orEmpty()
        haystack.any { name ->
            needles.any { needle ->
                // Word-boundary match so "in" doesn't fire on "entertainment".
                Regex("""(^|[^a-z])${Regex.escape(needle)}([^a-z]|$)""").containsMatchIn(name)
            }
        }
    }
}

internal fun epgshareUrl(cc: String) =
    "https://epgshare01.online/epgshare01/epg_ripper_${cc}1.xml.gz"
