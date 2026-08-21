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
) {
    val title: String get() = "$home v $away"
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

    fun parse(streamId: Int, rawName: String, nowMs: Long, leagues: Map<String, List<String>>): SportsEvent? {
        val name = rawName.trim()
        if (name.isEmpty() || name.contains("NO EVENT", ignoreCase = true)) return null
        if (ended.containsMatchIn(name)) return null
        if (otherLeague.containsMatchIn(name)) return null

        val (home, away) = readFixture(name) ?: return null
        val league = leagueOf(home, away, leagues) ?: return null
        val start = readStart(name, nowMs)

        // A fixture with no readable kick-off is only shown when the pack has
        // said LIVE itself. Guessing that something might be on is how a row
        // fills with matches that finished hours ago.
        if (start == null) {
            return if (liveWord.containsMatchIn(name)) {
                SportsEvent(streamId, league, home, away, null, live = true)
            } else {
                null
            }
        }
        if (kotlin.math.abs(start - nowMs) > SANE_WINDOW_MS) return null
        return SportsEvent(streamId, league, home, away, start, live = start <= nowMs)
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
        .replace(Regex("""(?i)\b(8K|4K|UHD|HD|SD|EXCLUSIVE|ᴴᴰ|ᴿᴬᵂ)\b"""), " ")
        .replace(Regex("""^\s*\d{1,3}\s*[-–]\s*"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun clean(side: String): String = side
        .replace(Regex("""^\s*\d{1,3}\s*[-–]\s*"""), "")
        .replace(Regex("""\((\w{2,4})\)"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .trim('-', '–', ':', '.', ',')

    /**
     * Which league claims either side. Null means neither is one we carry.
     *
     * On WORD BOUNDARIES, never a bare substring. Squashing the name to letters
     * and asking "does it contain SUNS" says yes to Mamelodi Sundowns, yes to
     * Wolves inside Erie SeaWolves, and yes to Angers inside Dundee v Rangers.
     * Every one of those was a real match against the provider's own slots.
     */
    internal fun leagueOf(home: String, away: String, leagues: Map<String, List<String>>): String? {
        // BOTH sides, or neither. One recognised nickname is not enough: the
        // NFL, the NHL and baseball all field a Cardinals, a Giants and a
        // Rangers, so "Cardinals at Reds" — a baseball game — read as NFL off
        // its first word. Requiring the opponent too settles it without a
        // league marker in the name, because Reds is nobody we carry.
        //
        // The tie goes to the home side, which is right for a cup tie pairing
        // clubs from two of our leagues.
        val hl = leagueFor(norm(home), leagues)
        val al = leagueFor(norm(away), leagues)
        return if (hl != null && al != null) hl else null
    }

    private fun leagueFor(side: String, leagues: Map<String, List<String>>): String? {
        for ((league, teams) in leagues) {
            for (team in teams) {
                val t = norm(team)
                if (t.length >= 3 && hasWord(side, t)) return league
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

    /**
     * The fixtures worth putting on screen: on now, or starting within the cue.
     * Live first, then soonest — a match already running outranks one that has
     * not started however close its kick-off.
     */
    fun upcoming(events: List<SportsEvent>, nowMs: Long, cueMinutes: Int): List<SportsEvent> {
        val cue = cueMinutes * 60_000L
        return events
            .filter { e ->
                val s = e.startMs ?: return@filter e.live
                s <= nowMs + cue && nowMs <= s + FIXTURE_LENGTH_MS
            }
            .sortedWith(compareByDescending<SportsEvent> { it.live }.thenBy { it.startMs ?: 0L })
    }

    /**
     * How long a fixture is assumed to run when nothing says otherwise. Three
     * hours covers a football match with stoppages and an NFL game; past that a
     * slot still named for it has almost certainly moved on.
     */
    private const val FIXTURE_LENGTH_MS = 3L * 60 * 60 * 1000
}
