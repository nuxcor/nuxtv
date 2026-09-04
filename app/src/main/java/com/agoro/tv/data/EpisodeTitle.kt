package com.agoro.tv.data

/**
 * The name an episode row actually shows.
 *
 * Panels ship the whole address of an episode in its `title`, because that is
 * how the file was named on disk: this one sends
 * `"Lady in the Lake - S01E01 - Did you know Seahorses are fish?"`. Drawn in a
 * list under a page already headed **Lady in the Lake**, in a row already
 * numbered `1.`, that says the same thing three times and pushes the part
 * nobody knows — the episode's own name — off the end of the line.
 *
 * What survives here is the last part. The show's name is the page title, the
 * season is the strip above the list, the number is the row's own; all three
 * come off, and anything the provider never supplied falls back to
 * "Episode N" rather than to a fragment of punctuation.
 *
 * Deliberately not clever about it. Every rule below is anchored — a leading
 * series name, a season/episode code as a whole token — so a legitimate title
 * that merely CONTAINS a number ("Episode 9 of a 12-part series", "1x1
 * Football") keeps it. The cost of stripping too much is an episode with no
 * name at all, which is worse than the repetition it was meant to fix.
 */
object EpisodeTitle {

    /**
     * A season/episode address as a whole token: `S01E01`, `S1 E1`,
     * `S01.E01`, `1x01`, `Season 1 Episode 1`.
     *
     * Bounded on both sides so it cannot bite into a word — without the
     * trailing bound `S01E01` would also match the opening of a title like
     * `S01E01x`, and without the leading one the `1x01` branch would fire on
     * the tail of a resolution (`1920x1080`).
     */
    private val addressCode = Regex(
        """(?<![A-Za-z0-9])(?:""" +
            """s\s?\d{1,2}\s?[\-. ]?\s?e\s?\d{1,3}""" +
            """|season\s?\d{1,2}\s?[\-–:,]?\s?episode\s?\d{1,3}""" +
            """|\d{1,2}x\d{2,3}""" +
            """)(?![A-Za-z0-9])""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * What is left when the provider named nothing: an empty string, a bare
     * number, or the word "episode" with or without one. All of these reach
     * the screen as "Episode N" instead, which at least numbers itself the
     * same way its neighbours do.
     */
    private val unnamed = Regex(
        """^(?:episode|ep|e|part|chapter)?\s*[.\-]?\s*\d{0,4}\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** Separators a stripped-out part leaves stranded at either end. */
    private const val EDGES = " \t-–—:|,._/"

    private val spaces = Regex("""\s{2,}""")

    /**
     * A trailing `(2024)` / `(US)` on the SHOW's name, which the episode
     * titles do not carry — left on, the prefix match below would never fire.
     */
    private val nameTail = Regex("""\s*\((?:\d{4}|[A-Za-z]{2})\)\s*$""")

    /**
     * [title] with the show's name taken off the front, or null when it does
     * not start with it.
     *
     * Matched on LETTERS AND DIGITS ALONE, because the punctuation is the one
     * thing that reliably differs between a catalogue entry and the episode
     * files under it: the same show is "Marvel's Agents of S.H.I.E.L.D." on
     * one and "Marvels Agents of SHIELD" on the other, and no pattern written
     * over the separators matches both. Comparing the letters and then
     * cutting the RAW string at the index they ran out at sidesteps the
     * question entirely.
     *
     * Two guards. The name must not end mid-word — "Fargo" does not strip
     * from "Fargolandia" — and a name of one or two characters is not matched
     * at all, since "24" would eat the opening of "24 Hours in A&E" and a
     * show named that briefly is rare enough not to be worth it.
     */
    private fun stripLeadingName(title: String, seriesName: String): String? {
        val want = seriesName.replace(nameTail, "")
            .filter { it.isLetterOrDigit() }
            .lowercase()
        if (want.length < 3) return null

        val seen = StringBuilder(want.length)
        var i = 0
        while (i < title.length && seen.length < want.length) {
            val c = title[i]
            if (c.isLetterOrDigit()) seen.append(c.lowercaseChar())
            i++
        }
        if (seen.length < want.length || seen.toString() != want) return null
        if (i < title.length && title[i].isLetterOrDigit()) return null
        return title.substring(i)
    }

    /**
     * The episode's own name, or null when the provider supplied none.
     *
     * [seriesName] is optional: an M3U source that never names the show still
     * gets the address codes taken off.
     */
    fun clean(raw: String, seriesName: String?): String? {
        var t = raw.trim()
        if (t.isEmpty()) return null

        // The show's name, and only at the head. A title that ENDS on the
        // show's name is naming something else — an episode called "Better
        // Call Saul" inside Breaking Bad is the joke, not a repetition.
        seriesName?.let { name ->
            stripLeadingName(t, name)?.let { stripped ->
                // Never to nothing: an episode whose whole title IS the show's
                // name (a pilot, usually) keeps it rather than going nameless.
                if (stripped.trim(*EDGES.toCharArray()).isNotEmpty()) t = stripped
            }
        }

        t = t.replace(addressCode, " ")
            .replace(spaces, " ")
            .trim(*EDGES.toCharArray())

        return t.takeUnless { it.isEmpty() || unnamed.matches(it) }
    }

    /**
     * The episode's name for a line that carries its number elsewhere — a
     * row already prefixed "1.", or a player subtitle already reading "S1 E1".
     */
    fun display(title: String, episodeNum: Int): String = when {
        title.isNotBlank() -> title
        episodeNum > 0 -> "Episode $episodeNum"
        else -> "Episode"
    }

    /** The whole label for an episode row: "1. Did you know Seahorses are fish?" */
    fun numbered(title: String, episodeNum: Int): String = when {
        title.isBlank() -> display(title, episodeNum)
        episodeNum > 0 -> "$episodeNum. $title"
        else -> title
    }
}
