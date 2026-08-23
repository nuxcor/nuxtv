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
    /**
     * The match, but in another commentary language ("EN ESPAÑOL"). Still the
     * match — it ranks below the plain feed and above a studio show, and it
     * folds into the same fixture row instead of appearing as a second one.
     */
    val languageFeed: Boolean = false,
    /** The same match on other slots, best first, for the player to fall back to. */
    val alternates: List<Int> = emptyList(),
) {
    val title: String get() = "$home v $away"

    /**
     * On now, judged against the clock rather than the flag set when this was
     * parsed. Parsing 6,000 slots is expensive enough to do once and keep, so
     * the answer has to age with the minute rather than with the parse.
     */
    fun isLive(nowMs: Long): Boolean = startMs?.let { it <= nowMs } ?: live

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

    /**
     * Every slot in one pass, with the rosters flattened once up front.
     *
     * The per-slot entry point below rebuilds that index on every call, which
     * is fine for a handful and ruinous for six thousand — this is what the
     * screen calls.
     */
    fun parseAll(
        slots: List<Pair<Int, String>>,
        nowMs: Long,
        leagues: Map<String, List<String>>,
        ambiguous: Set<String> = emptySet(),
    ): List<SportsEvent> {
        val idx = index(leagues)
        val amb = ambiguous.mapTo(HashSet()) { norm(it) }
        // Every word any club we carry uses, for the cheap test below.
        val clubWords = idx.flatMapTo(HashSet()) { it.third.split(' ') }
            .filterTo(HashSet()) { it.length >= 3 }
        return slots.mapNotNull { (id, name) ->
            if (!worthParsing(name, clubWords)) null
            else parseIndexed(id, name, nowMs, idx, amb)
        }
    }

    /**
     * A cheap sieve in front of the expensive path.
     *
     * Parsing means splitting the name, running eight noise substitutions over
     * each field and then scanning 231 clubs — call it thirty regex operations
     * a slot. Across the nearly eight thousand slots a panel carries that is
     * hundreds of thousands of them, which a streaming stick feels even on a
     * background thread, through the allocation churn if nothing else.
     *
     * Two tests, both cheap, reject nine in ten before any of that happens: a
     * fixture needs a separator between two sides, and it needs to mention a
     * club we carry. Both are strictly weaker than what [parseIndexed] would
     * conclude, so nothing is lost that would otherwise have been kept.
     */
    private fun worthParsing(name: String, clubWords: Set<String>): Boolean {
        // No emptiness check: a blank or whitespace-only name carries no
        // separator, so the next line rejects it, and parseIndexed keeps its
        // own guard for the callers that reach it directly.
        if (name.contains("NO EVENT", ignoreCase = true)) return false
        if (!fixtureSeparator.containsMatchIn(name)) return false
        return norm(name).split(' ').any { it in clubWords }
    }

    /**
     * "A vs B", "A v B", "A at B", "A x B" — the four the packs use.
     *
     * Bounded on non-alphanumerics rather than on spaces, because the sieve
     * reads the RAW name while the parser reads a noise-stripped field, and
     * stripping turns "(NYK)" into a space. "Knicks (NYK)x Timberwolves"
     * parses fine and a space-bounded test would have rejected it before the
     * parser ever saw it — the sieve is only safe while it stays strictly
     * weaker than what the parser concludes. Nothing on the panel is shaped
     * that way today; this is so nothing has to be.
     */
    private val fixtureSeparator =
        Regex("""(?i)(?<![A-Za-z0-9])(?:vs?\.?|at|x)(?![A-Za-z0-9])""")

    fun parse(
        streamId: Int,
        rawName: String,
        nowMs: Long,
        leagues: Map<String, List<String>>,
        ambiguous: Set<String> = emptySet(),
    ): SportsEvent? = parseIndexed(
        streamId, rawName, nowMs, index(leagues), ambiguous.mapTo(HashSet()) { norm(it) },
    )

    private fun parseIndexed(
        streamId: Int,
        rawName: String,
        nowMs: Long,
        idx: List<Triple<String, String, String>>,
        ambiguous: Set<String>,
    ): SportsEvent? {
        val name = rawName.trim()
        if (name.isEmpty() || name.contains("NO EVENT", ignoreCase = true)) return null
        if (ended.containsMatchIn(name)) return null
        if (otherLeague.containsMatchIn(name)) return null

        val (rawHome, rawAway) = readFixture(name) ?: return null
        val sides = resolveSides(rawHome, rawAway, idx, ambiguous) ?: return null
        val (league, home, away) = sides
        val start = readStart(name, nowMs)

        // A fixture with no readable kick-off is only shown when the pack has
        // said LIVE itself. Guessing that something might be on is how a row
        // fills with matches that finished hours ago.
        if (start == null) {
            return if (liveWord.containsMatchIn(name)) {
                SportsEvent(
                    streamId, league, home, away, null, true, tierOf(name), isSideFeed(name),
                    languageFeed = isLanguageFeed(name),
                )
            } else {
                null
            }
        }
        if (kotlin.math.abs(start - nowMs) > SANE_WINDOW_MS) return null
        return SportsEvent(
            streamId, league, home, away, start,
            live = start <= nowMs, tierRank = tierOf(name), sideFeed = isSideFeed(name),
            languageFeed = isLanguageFeed(name),
        )
    }

    /** League plus the two sides as they should be shown. */
    private data class Sides(val league: String, val home: String, val away: String)

    /**
     * [leagueIn], and while the roster is open: the club's name AS THE ROSTER
     * SPELLS IT. The provider's side is the raw slot text — "REAL SOCIEDAD
     * (MATCHDAY 2)", "EN ESPAÑOL-REAL BETIS" — and a fixture list that prints
     * it looks like the playlist, not like sport. The roster already knows
     * this club is Real Sociedad; say so. It also makes the two slots for one
     * match key as one match, so the Spanish feed folds into the row instead
     * of standing beside it. A side the roster does not carry (the cup-tie
     * case) keeps its own text, tidied out of all-caps.
     */
    private fun resolveSides(
        home: String,
        away: String,
        idx: List<Triple<String, String, String>>,
        ambiguous: Set<String>,
    ): Sides? {
        val h = norm(home)
        val a = norm(away)
        val hHit = idx.firstOrNull { hasWord(h, it.third) }
        val aHit = idx.firstOrNull { hasWord(a, it.third) }
        val hit = hHit ?: aHit ?: return null
        val league = when {
            hHit != null && aHit != null -> hHit.first
            hit.third in ambiguous -> return null
            hit.second.trim().contains(' ') -> hit.first
            else -> return null
        }
        return Sides(league, hHit?.let { fullName(it, idx) } ?: tidyCase(home),
            aHit?.let { fullName(it, idx) } ?: tidyCase(away))
    }

    /**
     * The roster's LONGEST spelling of a club, when it carries more than one:
     * a pack that lists both "Ipswich" and "Ipswich Town" had one slot match
     * each, and the same match appeared twice under two names. The index is
     * longest-first, so the first same-league entry containing the hit as
     * whole words is the full name.
     */
    private fun fullName(
        hit: Triple<String, String, String>,
        idx: List<Triple<String, String, String>>,
    ): String =
        idx.firstOrNull { it.first == hit.first && hasWord(it.third, hit.third) }?.second ?: hit.second

    /**
     * "SV WALDHOF MANNHEIM" → "SV Waldhof Mannheim". Only all-caps input is
     * touched, and only words long enough to be words — "FC", "SV", "PSG" are
     * initials and stay as they are.
     */
    internal fun tidyCase(side: String): String {
        if (side.any { it.isLowerCase() }) return side
        return side.split(' ').joinToString(" ") { word ->
            if (word.length <= 3) word
            else word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
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

    // Every pattern below is compiled once. They were written inline, which
    // reads naturally and compiles a Pattern per call — and stripNoise runs
    // per field per slot, so a parse of 6,000 slots was ~30,000 trips
    // through Pattern.compile before a single name was read.

    /** "8/20 8pm": the NFL pack's slot time. */
    private val noiseSlashTime = Regex("""(?i)\b\d{1,2}/\d{1,2}\s+\d{1,2}(?::\d{2})?\s*(am|pm)""")

    /** "@ Aug 19 7:30 PM": the MLS pack's. */
    private val noiseMonthTime =
        Regex("""(?i)@?\s*[A-Z][a-z]{2}\s+\d{1,2}\s+\d{1,2}(?::\d{2})?\s*(AM|PM)""")

    // Split on ':' orphans these from their values, so match the bare
    // word too — no team is called "start".
    private val noiseStartStop = Regex("""(?i)\b(start|stop)\b:?\S*""")

    private val noiseDateOrCode = Regex("""(?i)\b\d{2}-\d{2}-\d{4}\b|\(\w{3}\)""")

    // "(2026-08-22 04:50:29)" and the season trailing it, neither of which
    // is part of a club's name.
    // The closing bracket is optional: readFixture splits on ':' too, which
    // cuts "(2026-08-22 04:50:29)" in half and leaves the opening half
    // glued to the away side.
    private val noiseBracketStamp = Regex("""\(\d{4}-\d{2}-\d{2}[^)]*\)?|\b\d{4}/\d{4}\b""")

    // The competition, which these packs bill right after the fixture.
    private val noiseCompetition = Regex("""(?i)\bMatchweek\s*\d+|\bPremier League\b|\bLaLiga\b""")

    private val noiseTier = Regex("""(?i)\b(8K|4K|UHD|HD|SD|EXCLUSIVE|ᴴᴰ|ᴿᴬᵂ)\b""")

    /** "12 - " — the slot number the listings packs lead with. */
    private val leadingSlotNumber = Regex("""^\s*\d{1,3}\s*[-–]\s*""")

    /** "(NYK)": the abbreviation a side is tagged with. */
    private val sideAbbreviation = Regex("""\((\w{2,4})\)""")

    private val runsOfSpace = Regex("""\s+""")

    /** Slot numbers, dates and tier shouting, none of which is a team name. */
    private fun stripNoise(field: String): String = field
        .replace(noiseSlashTime, " ")
        .replace(noiseMonthTime, " ")
        .replace(noiseStartStop, " ")
        .replace(noiseDateOrCode, " ")
        .replace(noiseBracketStamp, " ")
        .replace(noiseCompetition, " ")
        // "Studio Coverage: Arsenal v ..." — the label is the feed's, not the
        // club's, and left on it the studio slot keys as a different fixture.
        .replace(sideFeedWords, " ")
        .replace(languageFeedWords, " ")
        .replace(noiseTier, " ")
        .replace(leadingSlotNumber, " ")
        .replace(runsOfSpace, " ")
        .trim()

    private fun clean(side: String): String = side
        .replace(leadingSlotNumber, "")
        .replace(sideAbbreviation, "")
        .replace(runsOfSpace, " ")
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

    /** [leagueOf] over the flattened index — the same rules, without the scan. */
    private fun leagueIn(
        home: String,
        away: String,
        idx: List<Triple<String, String, String>>,
        ambiguous: Set<String>,
    ): String? {
        val h = norm(home)
        val a = norm(away)
        val hHit = idx.firstOrNull { hasWord(h, it.third) }
        val aHit = idx.firstOrNull { hasWord(a, it.third) }
        val hit = hHit ?: aHit ?: return null
        if (hHit != null && aHit != null) return hHit.first
        if (hit.third in ambiguous) return null
        return if (hit.second.trim().contains(' ')) hit.first else null
    }

    private fun matchFor(side: String, leagues: Map<String, List<String>>): Match? {
        for ((league, teams) in leagues) {
            for (team in teams) {
                val t = norm(team)
                if (t.length >= 3 && hasWord(side, t)) return Match(league, team)
            }
        }
        return null
    }

    /**
     * The rosters, flattened once, longest name first.
     *
     * Scanning every club of every league for every side of every one of six
     * thousand slots is a quarter of a million string searches per pass, on
     * the CPU of a streaming stick. Flattening costs nothing and the longest
     * name first means "Coventry City" is found before "Coventry" would be.
     */
    fun index(leagues: Map<String, List<String>>): List<Triple<String, String, String>> =
        leagues.entries
            .flatMap { (league, teams) -> teams.map { Triple(league, it, norm(it)) } }
            .filter { it.third.length >= 3 }
            .sortedByDescending { it.third.length }

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
        .replace(nonAlphanumeric, " ")
        .trim()

    private val nonAlphanumeric = Regex("""[^A-Z0-9]+""")

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
        australianPrefix.containsMatchIn(name) -> TimeZone.getTimeZone("Australia/Sydney")
        britishPrefix.containsMatchIn(name) -> TimeZone.getTimeZone("Europe/London")
        americanPrefix.containsMatchIn(name) -> americanZone
        else -> utc()
    }

    private val australianPrefix = Regex("""^\s*AU\b""")
    private val britishPrefix = Regex("""^\s*UK\b""")
    private val americanPrefix = Regex("""^\s*(US|CA)\b""")

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

    /** "EN ESPAÑOL", "SPANISH", "(FR)": the same match, another commentary. */
    private val languageFeedWords = Regex(
        """(?i)\b(EN\s+ESPA[ÑN]OL|ESPA[ÑN]OL|SPANISH|EN\s+FRAN[ÇC]AIS|FRENCH|ARABIC|PORTUGU[EÊ]S|DEUTSCH|ITALIANO)\b"""
    )

    internal fun isLanguageFeed(name: String) = languageFeedWords.containsMatchIn(name)

    /**
     * What the slot claims its picture is. The advertised token is all there
     * is here — these slots are never probed, because a pipe's measurement
     * belongs to whatever match happened to be running at the time.
     */
    internal fun tierOf(name: String): Int = when {
        tier8k.containsMatchIn(name) -> 0
        tier4k.containsMatchIn(name) -> 1
        tierFhd.containsMatchIn(name) -> 2
        tierHd.containsMatchIn(name) -> 3
        else -> SportsEvent.TIER_UNKNOWN
    }

    private val tier8k = Regex("""(?i)\b8K\b""")
    private val tier4k = Regex("""(?i)\b(4K|UHD)\b""")
    private val tierFhd = Regex("""(?i)\bFHD\b""")
    private val tierHd = Regex("""(?i)\bHD\b""")

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
                // The match itself first — over another language's call,
                // over a side camera — and only then the better picture. A
                // studio show in 8K is still not the match. Arsenal v
                // Coventry arrived on four slots at the same tier — studio
                // coverage, a player camera, a multi camera and the match —
                // and with nothing separating them the tie broke on
                // whichever landed first: the pre-match studio show took the
                // row, bringing its own earlier start time as the kick-off.
                val ranked = sameMatch.sortedWith(
                    compareBy(
                        { if (it.sideFeed) 2 else if (it.languageFeed) 1 else 0 },
                        { it.tierRank },
                    )
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
        ).sortedWith(
            compareByDescending<SportsEvent> { it.isLive(nowMs) }.thenBy { it.startMs ?: 0L }
        )
    }

    /**
     * How long a fixture is assumed to run when nothing says otherwise. Three
     * hours covers a football match with stoppages and an NFL game; past that a
     * slot still named for it has almost certainly moved on.
     */
    private const val FIXTURE_LENGTH_MS = 3L * 60 * 60 * 1000
}
