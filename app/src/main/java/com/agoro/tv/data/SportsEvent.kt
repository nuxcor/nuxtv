package com.agoro.tv.data

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * A fixture the Sport destination can show, read out of a PPV slot's name.
 *
 * These slots are pipes, not channels: the same stream id carries a different
 * match tomorrow, and the provider rewrites the name each time. So the name is
 * the schedule — and it is a better one than the API offers, because
 * `get_short_epg` is a call per slot and there are nearly eight thousand of
 * them.
 *
 * Four packs, four formats, none of them agreeing:
 *
 *     NFL  | 01 - 8/20 8pm Raiders at Texans
 *     NBA 02: Knicks (NYK) x Timberwolves (MIN) start:2026-01-18 00:20:00
 *     Philadelphia Union vs Inter Miami CF @ Aug 19 7:30 PM :MLS  02
 *     Next | Sheffield Wednesday vs. Bradford City | all | 20-08-2026 | 15:00 (GMT)
 */
data class SportsEvent(
    val streamId: Int,
    val league: String,
    val home: String,
    val away: String,
    /** Kick-off, or null when the name carried no time we could trust. */
    val startMs: Long?,
    val live: Boolean,
    /** What the slot advertises: 8K, 4K, UHD, FHD, HD. Lower sorts better. */
    val tierRank: Int = TIER_UNKNOWN,
    /** A studio or tactical-camera companion feed rather than the match itself. */
    val sideFeed: Boolean = false,
    /** The same match on other slots, best first, for the player to fall back to. */
    val alternates: List<Int> = emptyList(),
) {
    val title: String get() = "$home v $away"

    companion object {
        const val TIER_UNKNOWN = 9
    }
}

object SportsParser {

    /**
     * The pack says so itself, where it bothers to. "END"/"ENDED" is the only
     * reliable negative — a slot with no status word is not necessarily live,
     * which is why [parse] leans on the clock rather than this.
     */
    private val ended = Regex("""(?i)^\s*(END|ENDED|FINISHED)\b""")
    private val liveWord = Regex("""(?i)^\s*LIVE\b""")

    /** "Team A vs. Team B", "Team A v Team B", "Raiders at Texans", "X x Y". */
    private val fixture = Regex("""(?i)(.{2,60}?)\s+(?:vs?\.?|at|x)\s+(.{2,60}?)\s*$""")

    // --- the four time formats -------------------------------------------------

    /** NBA: "start:2026-01-18 00:20:00" — the only pack that gives a real date. */
    private val isoStart = Regex("""start:(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})""")

    /** Pipe soccer: "20-08-2026 | 15:00 (GMT)". */
    private val dmyGmt = Regex("""(\d{2})-(\d{2})-(\d{4})\s*\|\s*(\d{1,2}):(\d{2})""")

    /** MLS: "@ Aug 19 7:30 PM". */
    private val monthDay =
        Regex("""(?i)@?\s*([A-Z][a-z]{2})\s+(\d{1,2})\s+(\d{1,2})(?::(\d{2}))?\s*(AM|PM)""")

    /**
     * The listings packs: "(2026-08-22 04:50:29)" — a bare timestamp in
     * brackets with no zone on it, in the zone of whoever compiled the pack.
     * See [zoneOf].
     */
    private val bracketIso =
        Regex("""\((\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::\d{2})?\)""")

    /** NFL: "8/20 8pm" — no year, no zone. */
    private val slashDay = Regex("""(?i)\b(\d{1,2})/(\d{1,2})\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)""")

    private val months = listOf(
        "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec",
    )

    /**
     * Slots reuse ids across seasons and the pack does not always clear the old
     * name — one NBA slot still reads `start:2025-01-18`, a game from last
     * season. Anything outside a day either side of now is stale, not upcoming.
     */
    private const val SANE_WINDOW_MS = 24L * 60 * 60 * 1000

    /**
     * The zone the American packs mean but never write. Getting this wrong puts
     * every NFL fixture an hour out, so it is named here rather than left to
     * the device: the packs are US feeds quoting US kick-off times.
     */
    private val americanZone: TimeZone = TimeZone.getTimeZone("America/New_York")

    fun parse(
        streamId: Int,
        rawName: String,
        nowMs: Long,
        leagues: Map<String, List<String>>,
        ambiguous: Set<String> = emptySet(),
    ): SportsEvent? {
        val name = rawName.trim()
        if (name.isEmpty() || name.contains("NO EVENT", ignoreCase = true)) return null
        if (ended.containsMatchIn(name)) return null
        if (otherLeague.containsMatchIn(name)) return null

        val (home, away) = readFixture(name) ?: return null
        val league = leagueOf(home, away, leagues, ambiguous.map { norm(it) }.toSet()) ?: return null
        val start = readStart(name, nowMs)

        // A fixture with no readable kick-off is only shown when the pack has
        // said LIVE itself. Guessing that something might be on is how a row
        // fills with matches that finished hours ago.
        if (start == null) {
            return if (liveWord.containsMatchIn(name)) {
                SportsEvent(
                    streamId, league, home, away, null, true, tierOf(name), isSideFeed(name),
                )
            } else {
                null
            }
        }
        if (kotlin.math.abs(start - nowMs) > SANE_WINDOW_MS) return null
        return SportsEvent(
            streamId, league, home, away, start,
            live = start <= nowMs, tierRank = tierOf(name), sideFeed = isSideFeed(name),
        )
    }

    /** The teams, taken from the busiest-looking field the name offers. */
    internal fun readFixture(name: String): Pair<String, String>? {
        // Pipe formats put the fixture in its own field; the rest bury it in a
        // line that also carries the slot number and the time.
        val fields = name.split('|', ':').map { it.trim() }.filter { it.isNotEmpty() }
        for (field in fields.sortedByDescending { it.length }) {
            val m = fixture.find(stripNoise(field)) ?: continue
            val home = clean(m.groupValues[1])
            val away = clean(m.groupValues[2])
            if (home.length >= 2 && away.length >= 2) return home to away
        }
        return null
    }

    /** Slot numbers, dates and tier shouting, none of which is a team name. */
    private fun stripNoise(field: String): String = field
        .replace(Regex("""(?i)\b\d{1,2}/\d{1,2}\s+\d{1,2}(?::\d{2})?\s*(am|pm)"""), " ")
        .replace(Regex("""(?i)@?\s*[A-Z][a-z]{2}\s+\d{1,2}\s+\d{1,2}(?::\d{2})?\s*(AM|PM)"""), " ")
        // Split on ':' orphans these from their values, so match the bare
        // word too — no team is called "start".
        .replace(Regex("""(?i)\b(start|stop)\b:?\S*"""), " ")
        .replace(Regex("""(?i)\b\d{2}-\d{2}-\d{4}\b|\(\w{3}\)"""), " ")
        // "(2026-08-22 04:50:29)" and the season trailing it, neither of which
        // is part of a club's name.
        // The closing bracket is optional: readFixture splits on ':' too, which
        // cuts "(2026-08-22 04:50:29)" in half and leaves the opening half
        // glued to the away side.
        .replace(Regex("""\(\d{4}-\d{2}-\d{2}[^)]*\)?|\b\d{4}/\d{4}\b"""), " ")
        // The competition, which these packs bill right after the fixture.
        .replace(Regex("""(?i)\bMatchweek\s*\d+|\bPremier League\b|\bLaLiga\b"""), " ")
        // "Studio Coverage: Arsenal v ..." — the label is the feed's, not the
        // club's, and left on it the studio slot keys as a different fixture.
        .replace(sideFeedWords, " ")
        .replace(Regex("""(?i)\b(8K|4K|UHD|HD|SD|EXCLUSIVE|ᴴᴰ|ᴿᴬᵂ)\b"""), " ")
        .replace(Regex("""^\s*\d{1,3}\s*[-–]\s*"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun clean(side: String): String = side
        .replace(Regex("""^\s*\d{1,3}\s*[-–]\s*"""), "")
        .replace(Regex("""\((\w{2,4})\)"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
        // The listings packs prefix a camera angle with a bullet.
        .trim('-', '–', ':', '.', ',', '\u2022', '*')
        .trim()

    /**
     * Which league claims either side. Null means neither is one we carry.
     *
     * On WORD BOUNDARIES, never a bare substring. Squashing the name to letters
     * and asking "does it contain SUNS" says yes to Mamelodi Sundowns, yes to
     * Wolves inside Erie SeaWolves, and yes to Angers inside Dundee v Rangers.
     * Every one of those was a real match against the provider's own slots.
     */
    internal fun leagueOf(
        home: String,
        away: String,
        leagues: Map<String, List<String>>,
        ambiguous: Set<String> = emptySet(),
    ): String? {
        val h = matchFor(norm(home), leagues)
        val a = matchFor(norm(away), leagues)
        val hit = h ?: a ?: return null
        // Both sides recognised: settled, and the home side names the league.
        if (h != null && a != null) return h.league

        // Only one side, which is the normal shape of a cup tie: a Pokal or FA
        // Cup draw pairs a top-flight club with one three divisions down that
        // no roster will ever carry. Allowed, but only on a FULL club name —
        // two words or more.
        //
        // A single word is not enough to carry a fixture by itself. "The New
        // Saints v Sabah" is Welsh football and landed under the NFL on
        // "Saints"; "Chelsea v E. Grand Rapids" is a Michigan high-school game
        // and landed in the Premier League on "Chelsea", which is also a town
        // there. Both were real rows on screen. "Bayern Munich" and "Union
        // Berlin" say who they are; "Saints" does not.
        if (norm(hit.team) in ambiguous) return null
        return if (hit.team.trim().contains(' ')) hit.league else null
    }

    private data class Match(val league: String, val team: String)

    private fun matchFor(side: String, leagues: Map<String, List<String>>): Match? {
        for ((league, teams) in leagues) {
            for (team in teams) {
                val t = norm(team)
                if (t.length >= 3 && hasWord(side, t)) return Match(league, team)
            }
        }
        return null
    }

    /** [needle] present in [text] as whole words, not buried inside a longer one. */
    private fun hasWord(text: String, needle: String): Boolean {
        var from = 0
        while (true) {
            val i = text.indexOf(needle, from)
            if (i < 0) return false
            val before = if (i == 0) ' ' else text[i - 1]
            val afterIndex = i + needle.length
            val after = if (afterIndex >= text.length) ' ' else text[afterIndex]
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
            from = i + 1
        }
    }

    /**
     * Leagues that share their nicknames with the ones we carry. Baseball alone
     * fields a Giants, a Cardinals and a Rangers, and the provider files
     * "US: MLB SAN FRANCISCO GIANTS" in the same pack — so a name flying
     * another league's flag is rejected before the rosters are consulted.
     */
    private val otherLeague = Regex(
        """(?i)\b(MLB|MiLB|NHL|WNBA|NCAA|NCAAF|NCAAB|CPL|BBL|KBO|NPB|AFL|NRL|SPFL|LOI""" +
            """|FLSP|flolive|Summer League|Ladies|Women)\b"""
    )

    /** Letters and digits only, accents folded, spacing kept as a separator. */
    private fun norm(s: String) = s.uppercase(Locale.ROOT)
        .replace("Ö", "O").replace("Ü", "U").replace("Ä", "A")
        .replace("É", "E").replace("È", "E").replace("Á", "A").replace("Í", "I")
        .replace(Regex("""[^A-Z0-9]+"""), " ")
        .trim()

    internal fun readStart(name: String, nowMs: Long): Long? {
        isoStart.find(name)?.let { m ->
            val (y, mo, d, h, mi) = m.destructured
            return at(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), utc())
        }
        bracketIso.find(name)?.let { m ->
            val (y, mo, d, h, mi) = m.destructured
            return at(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), zoneOf(name))
        }
        dmyGmt.find(name)?.let { m ->
            val (d, mo, y, h, mi) = m.destructured
            return at(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), utc())
        }
        monthDay.find(name)?.let { m ->
            val mo = months.indexOf(m.groupValues[1].lowercase(Locale.ROOT))
            if (mo >= 0) {
                val hour = hour24(m.groupValues[3].toInt(), m.groupValues[5])
                val min = m.groupValues[4].toIntOrNull() ?: 0
                return nearestYear(mo, m.groupValues[2].toInt(), hour, min, nowMs)
            }
        }
        slashDay.find(name)?.let { m ->
            val hour = hour24(m.groupValues[3].toInt(), m.groupValues[5])
            val min = m.groupValues[4].toIntOrNull() ?: 0
            return nearestYear(m.groupValues[1].toInt() - 1, m.groupValues[2].toInt(), hour, min, nowMs)
        }
        return null
    }

    private fun hour24(h: Int, meridiem: String): Int {
        val pm = meridiem.equals("pm", ignoreCase = true)
        return when {
            pm && h < 12 -> h + 12
            !pm && h == 12 -> 0
            else -> h
        }
    }

    private fun utc(): TimeZone = TimeZone.getTimeZone("UTC")

    /**
     * Whose clock a zoneless timestamp is on: the pack's own.
     *
     * "AU (STAN 13) | Arsenal v Coventry City ... (2026-08-22 04:50:29)" is a
     * Stan Sport listing, and 04:50 is Sydney's — 18:50 UTC, an evening
     * kick-off in England. Read as UTC it lands ten hours late, which for a
     * cue that exists to fire an hour before kick-off is the whole feature
     * missed. The prefix is the only zone these packs give, so it is the one
     * to believe; anything unprefixed stays UTC.
     */
    private fun zoneOf(name: String): TimeZone = when {
        Regex("""^\s*AU\b""").containsMatchIn(name) -> TimeZone.getTimeZone("Australia/Sydney")
        Regex("""^\s*UK\b""").containsMatchIn(name) -> TimeZone.getTimeZone("Europe/London")
        Regex("""^\s*(US|CA)\b""").containsMatchIn(name) -> americanZone
        else -> utc()
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: TimeZone): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month, day, hour, minute)
        }.timeInMillis

    /**
     * A date with no year. Whichever year puts it closest to now wins, so a
     * fixture on 1 January read on 31 December lands next year rather than
     * eleven months ago.
     */
    private fun nearestYear(month: Int, day: Int, hour: Int, minute: Int, nowMs: Long): Long {
        val thisYear = Calendar.getInstance(americanZone).apply { timeInMillis = nowMs }
            .get(Calendar.YEAR)
        return (thisYear - 1..thisYear + 1)
            .map { at(it, month, day, hour, minute, americanZone) }
            .minByOrNull { kotlin.math.abs(it - nowMs) }!!
    }

    /** Studio coverage and tactical cameras — a companion feed, not the match. */
    private val sideFeedWords = Regex(
        """(?i)\b(Studio Coverage|Player Camera|Multi ?Camera|Match Centre|Tactical|Fan ?Zone)\b"""
    )

    internal fun isSideFeed(name: String) = sideFeedWords.containsMatchIn(name)

    /**
     * What the slot claims its picture is. The advertised token is all there
     * is here — these slots are never probed, because a pipe's measurement
     * belongs to whatever match happened to be running at the time.
     */
    internal fun tierOf(name: String): Int = when {
        Regex("""(?i)\b8K\b""").containsMatchIn(name) -> 0
        Regex("""(?i)\b(4K|UHD)\b""").containsMatchIn(name) -> 1
        Regex("""(?i)\bFHD\b""").containsMatchIn(name) -> 2
        Regex("""(?i)\bHD\b""").containsMatchIn(name) -> 3
        else -> SportsEvent.TIER_UNKNOWN
    }

    /**
     * One row per match, on the best slot carrying it.
     *
     * The same fixture is routinely on several slots at once — Motherwell v
     * Freiburg was on four — and listing a match four times is worse than
     * useless when three of them are the same picture at a lower tier. The
     * losers become the winner's fallbacks, so a slot that fails to open still
     * has somewhere to go.
     */
    internal fun bestPerFixture(events: List<SportsEvent>): List<SportsEvent> =
        events.groupBy { norm(it.home) + "|" + norm(it.away) }
            .map { (_, sameMatch) ->
                // Tier first, then the match over a side camera. Arsenal v
                // Coventry arrived on four slots at the same tier — studio
                // coverage, a player camera, a multi camera and the match —
                // so the tie broke on whichever landed first, and the
                // pre-match studio show took the row, bringing its own
                // earlier start time along as the kick-off.
                val ranked = sameMatch.sortedWith(
                    compareBy({ it.tierRank }, { if (it.sideFeed) 1 else 0 })
                )
                ranked.first().copy(alternates = ranked.drop(1).map { it.streamId })
            }

    /**
     * The fixtures worth putting on screen: on now, or starting within the cue.
     * Live first, then soonest — a match already running outranks one that has
     * not started however close its kick-off.
     */
    fun upcoming(events: List<SportsEvent>, nowMs: Long, cueMinutes: Int): List<SportsEvent> {
        val cue = cueMinutes * 60_000L
        // Window first, then one row per fixture. Collapsing first would pick a
        // slot before knowing whether it is the one still relevant — three
        // slots for the same match, one finished and one yet to start, and the
        // fold could hand back the finished one and drop the match entirely.
        return bestPerFixture(
            events.filter { e ->
                val s = e.startMs ?: return@filter e.live
                s <= nowMs + cue && nowMs <= s + FIXTURE_LENGTH_MS
            }
        ).sortedWith(compareByDescending<SportsEvent> { it.live }.thenBy { it.startMs ?: 0L })
    }

    /**
     * How long a fixture is assumed to run when nothing says otherwise. Three
     * hours covers a football match with stoppages and an NFL game; past that a
     * slot still named for it has almost certainly moved on.
     */
    private const val FIXTURE_LENGTH_MS = 3L * 60 * 60 * 1000
}
