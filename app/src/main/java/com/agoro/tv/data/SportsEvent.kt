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
    /**
     * What the slot's SOURCE is worth, for the case [tierRank] cannot decide.
     * Lower sorts better, and it is compared AFTER the tier, so it can never
     * displace a feed that advertises a better picture. See [SportsParser.sourceOf].
     */
    val sourceRank: Int = SOURCE_NEUTRAL,
    /**
     * The slot is shelved under a DIFFERENT sport than this fixture's league.
     *
     * Sorted on before anything else about the picture, because a pack that
     * has the sport wrong routinely has the clock wrong too, and a fixture row
     * whose time is wrong is worse than one whose picture is. See
     * [SportsParser.namedSport].
     */
    val wrongSport: Boolean = false,
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

        /** No opinion about the source, which is true of nearly every slot. */
        const val SOURCE_NEUTRAL = 5

        /** A pack known to re-stream well below the others; last among equals. */
        const val SOURCE_THIN = 8
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

    /**
     * MLS and TSN+: "@ Aug 19 7:30 PM", and the same shape on a 24-hour clock.
     *
     * The meridiem was required, which quietly cost every TSN+ fixture: that
     * pack writes "LaLiga: Celta vs. Osasuna @ Aug 27 14:18 :TSN+ 45" — same
     * format, 24-hour time — so it read as having no kick-off at all and was
     * dropped for it. Made optional, and the hour tells the two apart: a bare
     * hour above 12 can only be 24-hour, and below it the meridiem is there.
     */
    private val monthDay =
        Regex("""(?i)@?\s*([A-Z][a-z]{2})\s+(\d{1,2})\s+(\d{1,2})(?::(\d{2}))?\s*(AM|PM)?""")

    /**
     * The listings packs: "(2026-08-22 04:50:29)" — a bare timestamp in
     * brackets with no zone on it, in the zone of whoever compiled the pack.
     * See [zoneOf].
     */
    private val bracketIso =
        Regex("""\((\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::\d{2})?\)""")

    /** NFL: "8/20 8pm" — no year, no zone. */
    private val slashDay = Regex("""(?i)\b(\d{1,2})/(\d{1,2})\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)""")

    /**
     * The listings packs' own clock: "Thu 27 Aug 14:20 EDT (US)".
     *
     * Counted on this panel: 320 slots write it — ESPN+ 82, NFHS 54, FIFA+ 39,
     * MAX 18, Sportsnet 9 — and not one of the five formats above can read it.
     * [monthDay] wants the month FIRST ("Aug 27 14:20"); everything else wants
     * a numeric date. So every one of these reached [parseIndexed] with no
     * kick-off, and the LIVE-flagged ones became rows with `startMs = null`.
     *
     * That is the whole of "live sport opens on a blank screen". A fixture
     * with no clock cannot be aged: [upcoming]'s window falls through to the
     * parse-time `live` flag, [SportsEvent.isLive] does the same, so the row
     * wears a LIVE badge from the moment it is read until the playlist behind
     * it is fetched again — hours after the match finished, by which time the
     * PPV pipe it opens has moved on to something else or gone dark. It also
     * showed matches as LIVE up to half an hour BEFORE kick-off, which opens
     * the same dead pipe from the other side.
     *
     * The weekday is the anchor, and it is what makes this safe to try ahead
     * of [monthDay]: no other pack leads a time with a day name. It is tried
     * AFTER [dmyGmt] all the same, because the pipe pack fields a club called
     * Sheffield Wednesday.
     *
     * The meridiem is read BEFORE the zone and it has to be: some of these
     * names carry a 12-hour clock ("UK Sat 3 Feb 3:30pm"), and with only a
     * zone slot after the minutes the "pm" landed in it — an unknown token,
     * discarded, leaving 3:30am. Twelve hours out, on 37 slots.
     */
    private val dowDayMonth = Regex(
        """(?i)\b(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)[a-z]*\s+(\d{1,2})\s+([A-Z][a-z]{2})[a-z]*""" +
            """\s+(\d{1,2}):(\d{2})\s*(AM|PM)?\s*([A-Z]{2,5})?"""
    )

    /**
     * The zone abbreviations those packs write, as named zones.
     *
     * Named rather than fixed offsets so the daylight saving the abbreviation
     * is already announcing is applied on the FIXTURE's date rather than on
     * today's. They have to be spelled out because `TimeZone.getTimeZone("EDT")`
     * does not resolve — Java answers GMT for an id it does not know, silently,
     * and a wrong zone here is a kick-off an hour or five out, which is the
     * same broken row this was written to fix.
     *
     * GMT and UTC map to UTC exactly, never to Europe/London: London is BST
     * for half the year, and a pack that writes "15:00 GMT" in August means
     * 15:00 GMT. BST is only ever summer, so it can safely be the named zone.
     */
    private val zoneTokens = mapOf(
        "EDT" to "America/New_York", "EST" to "America/New_York",
        "CDT" to "America/Chicago", "CST" to "America/Chicago",
        "MDT" to "America/Denver", "MST" to "America/Denver",
        "PDT" to "America/Los_Angeles", "PST" to "America/Los_Angeles",
        "AKDT" to "America/Anchorage", "AKST" to "America/Anchorage",
        "HST" to "Pacific/Honolulu",
        // The bare forms, which say the zone without saying the season.
        "ET" to "America/New_York", "CT" to "America/Chicago",
        "MT" to "America/Denver", "PT" to "America/Los_Angeles",
        // Atlantic and Newfoundland: the Canadian packs write them, and
        // Newfoundland is the half-hour offset that no fallback would guess —
        // 9 Sportsnet slots read 2½ hours out without this.
        "ADT" to "America/Halifax", "AST" to "America/Halifax",
        "NDT" to "America/St_Johns", "NST" to "America/St_Johns",
        "GMT" to "UTC", "UTC" to "UTC", "BST" to "Europe/London",
        "WET" to "Europe/Lisbon", "WEST" to "Europe/Lisbon",
        "CET" to "Europe/Paris", "CEST" to "Europe/Paris",
        "EET" to "Europe/Athens", "EEST" to "Europe/Athens",
        "AEST" to "Australia/Sydney", "AEDT" to "Australia/Sydney",
        "NZST" to "Pacific/Auckland", "NZDT" to "Pacific/Auckland",
    )

    /** The zone a slot names outright, or null when it names none we know. */
    private fun zoneFromToken(token: String): TimeZone? =
        zoneTokens[token.uppercase(Locale.ROOT)]?.let { TimeZone.getTimeZone(it) }

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
        aliases: Map<String, String> = emptyMap(),
    ): List<SportsEvent> {
        val idx = index(leagues)
        val amb = ambiguous.mapTo(HashSet()) { norm(it) }
        val ali = normaliseAliases(aliases)
        // Every word any club we carry uses, for the cheap test below.
        val clubWords = idx.flatMapTo(HashSet()) { it.third.split(' ') }
            .filterTo(HashSet()) { it.length >= 3 }
        return slots.mapNotNull { (id, name) ->
            if (!worthParsing(name, clubWords)) null
            else parseIndexed(id, name, nowMs, idx, amb, ali)
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
        // A club we carry, OR a competition we carry. The club test alone is
        // what made the sieve stricter than the parser behind it: a slot
        // billed "Carabao Cup: Chelsea vs Luton Town" reaches the parser on
        // Chelsea, but "Celje vs Slovan Bratislava" on the UEFA shelf names no
        // club any roster has and was thrown away here, before the billing it
        // leads with could be read. See billedLeague.
        return norm(name).split(' ').any { it in clubWords } || anyBilledComp.containsMatchIn(name)
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
        aliases: Map<String, String> = emptyMap(),
    ): SportsEvent? = parseIndexed(
        streamId, rawName, nowMs, index(leagues), ambiguous.mapTo(HashSet()) { norm(it) },
        normaliseAliases(aliases),
    )

    /**
     * The alias table keyed the way [fullName] looks a hit up.
     *
     * "League|Club", normalised on the club half only. Per LEAGUE and not per
     * club, because the nicknames these exist to canonicalise are the ones
     * several sports share: a bare "Spurs" -> "Tottenham Hotspur" would have
     * renamed San Antonio too, which is the same wrong-club fault this file
     * already fixes on the other side.
     */
    private fun normaliseAliases(aliases: Map<String, String>): Map<String, String> =
        if (aliases.isEmpty()) emptyMap()
        else aliases.entries.associate { (k, v) ->
            val league = k.substringBefore('|', "")
            "$league|${norm(k.substringAfter('|'))}" to v
        }

    private fun parseIndexed(
        streamId: Int,
        rawName: String,
        nowMs: Long,
        idx: List<Triple<String, String, String>>,
        ambiguous: Set<String>,
        aliases: Map<String, String>,
    ): SportsEvent? {
        val name = rawName.trim()
        if (name.isEmpty() || name.contains("NO EVENT", ignoreCase = true)) return null
        if (ended.containsMatchIn(name)) return null
        if (otherLeague.containsMatchIn(name)) return null
        // A slot that names somebody else's competition is out, whatever
        // nicknames its two sides happen to share with ours. This list already
        // existed as a guard on [billedLeague] — a check that only ran once the
        // roster had failed — so "Caribbean Premier League … Antigua And
        // Barbuda Falcons vs St Kitts And Nevis Patriots" never reached it: the
        // NFL roster answered first and put a cricket match on screen as an NFL
        // fixture. The competition a slot names is the strongest thing it says
        // about itself and it belongs at the top, not in the fallback.
        if (notOurCompetition.containsMatchIn(name)) return null

        val (rawHome, rawAway) = readFixture(name) ?: return null
        // "Los Angeles FC 2 vs. Sporting Kansas City II" is MLS Next Pro, the
        // reserve league. Both sides matched their senior club, so the row
        // announced a first-team fixture that nobody was playing.
        if (isReserveSide(rawHome) || isReserveSide(rawAway)) return null
        // The roster first, then the slot's own billing.
        //
        // A roster cannot cover this on its own and it was never going to.
        // Every fixture needs BOTH clubs listed, so a cup tie pairing a
        // Premier League side with an EFL one is invisible — Chelsea v Luton
        // Town, Fulham v AFC Wimbledon — and so is European qualifying, which
        // brought Celje, Slovan Bratislava and KI Klaksvik in one evening.
        // Authoring past that is a losing race: the 653-club crest index has
        // no Slovenia, Slovakia or Faroe Islands either, and the playlist only
        // ever lists the fixtures of the day, so nothing can be derived from
        // it that lasts.
        //
        // But these slots SAY what they are — "Carabao Cup:", "UEFA", "LaLiga:"
        // — and a competition this catalogue carries, with two sides either
        //ngside a vs, is a fixture whether or not a list of clubs agrees. The
        // roster still runs first, because it knows the full spelling of a
        // club and which competition it plays in; billing only catches what
        // the roster cannot.
        // The billing wins on the COMPETITION, the roster on the names.
        //
        // Ordering the roster first put "Carabao Cup: Manchester United vs
        // Luton Town" under Premier League, because United is a roster club
        // and the roster answers with the league it plays in — so one cup
        // round split across two rows depending on whether a tie happened to
        // name a multi-word club. The slot says which competition it is; the
        // roster only ever knew which league a club belongs to.
        val billed = billedLeague(name)
        val sides = resolveSides(rawHome, rawAway, idx, ambiguous, aliases)
            ?.let { if (billed != null) Sides(billed, it.home, it.away) else it }
            ?: billed?.let { Sides(it, billedSide(rawHome), billedSide(rawAway)) }
            ?: return null
        val (league, home, away) = sides
        val start = readStart(name, nowMs)

        // A fixture with no readable kick-off is only shown when the pack has
        // said LIVE itself. Guessing that something might be on is how a row
        // fills with matches that finished hours ago.
        if (start == null) {
            return if (liveWord.containsMatchIn(name)) {
                SportsEvent(
                    streamId = streamId, league = league, home = home, away = away,
                    startMs = null, live = true,
                    tierRank = tierOf(name), sourceRank = sourceOf(name),
                    wrongSport = isWrongSport(name, league),
                    sideFeed = isSideFeed(name), languageFeed = isLanguageFeed(name),
                )
            } else {
                null
            }
        }
        if (kotlin.math.abs(start - nowMs) > SANE_WINDOW_MS) return null
        return SportsEvent(
            streamId, league, home, away, start,
            live = start <= nowMs, tierRank = tierOf(name), sourceRank = sourceOf(name),
            wrongSport = isWrongSport(name, league),
            sideFeed = isSideFeed(name), languageFeed = isLanguageFeed(name),
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
    /**
     * The competition a slot bills itself as, when this catalogue carries it.
     *
     * Only competitions worth a row of their own. Deliberately NOT a generic
     * "any two names around a vs" rule — that would take darts, cricket and
     * every press conference the PPV shelves carry.
     */
    internal fun billedLeague(name: String): String? {
        // [notOurCompetition] used to be checked here and is not any more: it
        // now runs at the top of [parseIndexed], over the whole name, before
        // the roster gets a say. Keeping a second copy would be two places to
        // hold one list in sync, and the copy here could never fire.
        return billedComps.firstOrNull { it.second.containsMatchIn(name) }?.first
    }

    /**
     * One scan for "does this name a competition at all", for the sieve.
     *
     * The sieve exists to reject nine slots in ten before any real work, and
     * calling billedLeague there ran the reject list plus a dozen competition
     * regexes over every one of the ~7,000 slots it was built to discard
     * cheaply. This answers the same question in a single pass; billedLeague
     * still does the precise work on what survives.
     */
    private val anyBilledComp = Regex(
        """(?i)\b(LaLiga|La Liga|Serie A|Bundesliga|Ligue 1|Champions League|UCL""" +
            """|Europa League|Conference League|Carabao Cup|EFL Cup|League Cup|FA Cup)\b|^\s*UEFA\b"""
    )

    /** Words that make a major-sounding competition somebody else's. */
    private val notOurCompetition = Regex(
        """(?i)\b(Caribbean|DFA|Dominica|Cricket|Rugby|Netball|Women'?s?|Ladies|Youth|U\d{2}|Reserves?)\b"""
    )

    /**
     * A side that is a club's second team. MLS Next Pro writes "2", the
     * European reserve leagues write "II" or "B", and the senior roster
     * matches all of them.
     *
     * The hard part is not the marker, it is everything that LOOKS like one.
     * readFixture splits on the colon as well as the pipe, so an away side
     * routinely arrives with a halved timestamp on it, and both shapes the
     * packs write end in a bare digit:
     *
     *     "Luton Town @ Aug 27 2"          <- 2:20 PM, cut at the colon
     *     "Club B // UK Sun 23 Aug 2"      <- 2:55 pm, same cut
     *
     * A trailing-"2" rule threw away every cup tie in the catalogue. Requiring
     * a LETTER before the marker fixed the first shape and not the second —
     * "Aug" is letters — so any 2 o'clock fixture in the 44 slots that use the
     * weekday format was still dropped, and for a reason that had nothing to do
     * with reserve teams.
     *
     * So the word BEFORE the marker decides, and it has to be a club's word:
     * not a number, and not a month or a weekday. "Los Angeles FC 2" and
     * "Sporting Kansas City II" pass; a date fragment does not.
     *
     * "U21" and "Reserves" are handled upstream — [notOurCompetition] rejects
     * the whole slot on either — so only the two markers that can reach here
     * are listed.
     */
    private val reserveMarker = Regex("""(?i)^(?:2|II)$""")

    private val dateWord = Regex(
        """(?i)^(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec""" +
            """|Mon|Tue|Wed|Thu|Fri|Sat|Sun)[a-z]*$"""
    )

    internal fun isReserveSide(raw: String): Boolean {
        val words = raw.trim().split(runsOfSpace).filter { it.isNotEmpty() }
        if (words.size < 2) return false
        if (!reserveMarker.matches(words.last())) return false
        val before = words[words.size - 2]
        return !dateWord.matches(before) && before.none { it.isDigit() }
    }

    private val billedComps: List<Pair<String, Regex>> = listOf(
        // The domestic leagues are here as well as in the roster, so a club the
        // roster spells differently still lands: "LaLiga: Celta vs. Osasuna"
        // was lost because the roster says "Celta Vigo" and the slot says
        // "Celta".
        //
        // "Premier League" is deliberately ABSENT. Every nation has one — the
        // Indian Premier League is cricket, the Ghana Premier League is
        // football but not this one — and an unanchored match filed both under
        // England. The roster carries all twenty English clubs, so billing has
        // nothing to add here and could only over-match.
        "La Liga" to Regex("""(?i)\bLaLiga\b|\bLa Liga\b"""),
        "Serie A" to Regex("""(?i)\bSerie A\b"""),
        "Bundesliga" to Regex("""(?i)\bBundesliga\b"""),
        "Ligue 1" to Regex("""(?i)\bLigue 1\b"""),
        "Champions League" to Regex("""(?i)\bChampions League\b|\bUCL\b"""),
        "Europa League" to Regex("""(?i)\bEuropa League\b"""),
        "Conference League" to Regex("""(?i)\bConference League\b"""),
        "Carabao Cup" to Regex("""(?i)\bCarabao Cup\b|\bEFL Cup\b|\bLeague Cup\b"""),
        "FA Cup" to Regex("""(?i)\bFA Cup\b"""),
        // The provider's own UEFA shelf, which bills the confederation and not
        // the competition: "UEFA  | 01 - Freiburg vs Motherwell". A row of its
        // own rather than a guess at which UEFA competition it is.
        "UEFA" to Regex("""(?i)^\s*UEFA\b"""),
    )

    /**
     * How many words stand IN FRONT of the club a side matched.
     *
     * The US rosters are NICKNAMES — "Falcons", "Patriots", "Spurs", "Kings" —
     * and a nickname is a word other sports use too. "Antigua And Barbuda
     * Falcons vs St Kitts And Nevis Patriots" is a Caribbean Premier League
     * CRICKET match, and both sides matched the NFL roster on their last word:
     * the row reached the screen as an NFL fixture wearing the Atlanta Falcons
     * and New England Patriots badges. That is the whole of "it shows a match
     * with the wrong logo".
     *
     * Leading words only, and that is the whole of making this safe. A side
     * arrives with whatever readFixture left on its tail — "Chelsea // UK Sat 3
     * Feb 3", "Cleveland Browns @ Aug 20 4" — so counting every surplus word
     * threw away ordinary fixtures by the packful. What pads a club's name is
     * in FRONT of it: a city ("New England Patriots") or somebody else's
     * country ("St Kitts And Nevis Patriots").
     *
     * Only single-word roster entries are held to this. "Manchester United" and
     * "Inter Miami" say who they are; "Falcons" does not.
     */
    private fun leadingSurplus(side: String, club: String): Int {
        val words = side.split(' ').filter { it.isNotEmpty() }
        val want = club.split(' ').filter { it.isNotEmpty() }
        if (want.isEmpty()) return 0
        for (i in words.indices) {
            if (i + want.size <= words.size && words.subList(i, i + want.size) == want) return i
        }
        return 0
    }

    /**
     * The most a bare nickname may be led by and still be that club. Two, which
     * is every city these packs write — "New England", "Kansas City" — and one
     * short of the shortest island name that collided with one.
     */
    private const val NICKNAME_LEAD = 2

    /** Every roster entry this side names, longest first. */
    private fun hitsFor(
        side: String,
        idx: List<Triple<String, String, String>>,
    ): List<Triple<String, String, String>> = idx.filter {
        hasWord(side, it.third) &&
            (it.third.contains(' ') || leadingSurplus(side, it.third) <= NICKNAME_LEAD)
    }

    private fun resolveSides(
        home: String,
        away: String,
        idx: List<Triple<String, String, String>>,
        ambiguous: Set<String>,
        aliases: Map<String, String>,
    ): Sides? {
        val h = norm(home)
        val a = norm(away)
        val hHits = hitsFor(h, idx)
        val aHits = hitsFor(a, idx)
        // The league that carries BOTH sides, before the league that carries
        // the better-spelled one. "Spurs v. Newcastle" is Tottenham against
        // Newcastle and both are in the Premier League roster — but "Spurs" is
        // an NBA club too, the index is longest-name-first with no opinion
        // about which sport a row is, and taking the home side's first hit
        // filed an English league match under the NBA. A competition that
        // fields both of these clubs is the competition they are playing in.
        val shared = hHits.firstOrNull { hit -> aHits.any { it.first == hit.first } }?.first
        val hHit = shared?.let { l -> hHits.firstOrNull { it.first == l } } ?: hHits.firstOrNull()
        val aHit = shared?.let { l -> aHits.firstOrNull { it.first == l } } ?: aHits.firstOrNull()
        val hit = hHit ?: aHit ?: return null
        val league = when {
            hHit != null && aHit != null -> {
                // Two sides from two SPORTS is not a fixture, it is a nickname
                // collision, and there is no way to tell which of the two is
                // the impostor. A cup tie across two of our football leagues is
                // ordinary and stays; a club playing a basketball team does not
                // happen.
                val hs = sportOf(hHit.first)
                val As = sportOf(aHit.first)
                if (hs != null && As != null && hs != As) return null
                hHit.first
            }
            hit.third in ambiguous -> return null
            hit.second.trim().contains(' ') -> hit.first
            else -> return null
        }
        // billedSide, not tidyCase: a side the roster did not match keeps
        // whatever readFixture left on it, and readFixture splits on the colon
        // — so "Luton Town @ Aug 27 2" reached the row as a club name, and the
        // fold key with it, which stopped the same fixture on two slots from
        // folding into one row.
        return Sides(league, hHit?.let { fullName(it, idx, aliases) } ?: billedSide(home),
            aHit?.let { fullName(it, idx, aliases) } ?: billedSide(away))
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
        aliases: Map<String, String>,
    ): String {
        // The authored table first: it is the only thing that can reach a name
        // the alias shares no word with. See Sport.clubAlias.
        aliases["${hit.first}|${hit.third}"]?.let { return it }
        return idx.firstOrNull { it.first == hit.first && hasWord(it.third, hit.third) }?.second
            ?: hit.second
    }

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

    /**
     * A club name off a billed slot, with the field's trailing junk cut off.
     *
     * readFixture hands back whatever sat around the "vs" in the longest
     * field, and that field is only as clean as the pack's punctuation:
     * "Carabao Cup: Chelsea vs Luton Town @ Aug 27 2:20 PM" splits on the
     * colon into "Chelsea vs Luton Town @ Aug 27 2", so the away side arrives
     * with a date attached. The roster path never showed this because a
     * matched club is replaced by the roster's own spelling.
     *
     * Keeps the leading run of capitalised words and stops at the first thing
     * that is not one, which is where the club name ends in every pack here.
     */
    internal fun billedSide(raw: String): String =
        tidyCase(billedName.find(raw.trim())?.value?.trim() ?: raw.trim())

    private val billedName = Regex("""^[\p{L}][\p{L}.'\-]*(?:\s+[\p{L}][\p{L}.'\-]*)*""")

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

    // leagueOf / leagueIn / matchFor lived here and are gone. They were the
    // first-hit rule — the first roster entry whose words appear in a side wins,
    // with no cap on how much of the side is somebody else's name and no check
    // that the two sides play the same sport. That is precisely what put a
    // Caribbean cricket match on screen as an NFL fixture and an English league
    // match under the NBA; [resolveSides] replaced it. Nothing called them, and
    // leaving a working copy of a rule this file has just disproved is an
    // invitation to revive it.

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
    /**
     * Club-name prefixes and suffixes that carry no identity, dropped when two
     * slots are asked whether they are the same match.
     *
     * A side the roster MATCHED is rewritten to the roster's own spelling by
     * [resolveSides], and two slots then agree. A side it did not match keeps
     * whatever the provider typed — and only one of the two sides has to match
     * for a fixture to parse at all — so "SK Slovan Bratislava" on one slot and
     * "Slovan Bratislava" on another produced two keys, two groups and two
     * rows for one match. That is the whole of "sometimes I see duplicates":
     * it happens to the clubs the roster does not cover.
     */
    private val clubAffix = setOf(
        "FC", "CF", "SC", "SK", "SV", "AC", "AS", "SS", "CD", "CA", "AFC", "RC", "RCD",
        "BK", "IF", "IK", "FK", "NK", "HK", "GKS", "KS", "VFL", "VFB", "TSG", "TSV",
        "FSV", "MSV", "BSC", "SPVGG", "CFR", "UD", "SD", "AD", "CS", "OGC",
    )

    /**
     * One side of a fixture, reduced to the words that identify the club.
     *
     * Falls back to the whole normalised name when nothing survives — a club
     * billed as nothing but an affix is unlikely, and two of them colliding on
     * an empty key would fold two different matches into one row, which is a
     * match taken OFF the screen. A duplicate is the cheaper mistake.
     */
    internal fun sideKey(side: String): String {
        val words = norm(side).split(' ').filter { it.isNotEmpty() && it !in clubAffix }
        return if (words.isEmpty()) norm(side) else words.joinToString(" ")
    }

    /** The identity two slots share when they carry the same match. */
    internal fun fixtureKey(event: SportsEvent): String =
        sideKey(event.home) + "|" + sideKey(event.away)

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
        // Before monthDay, after dmyGmt — see [dowDayMonth] for why both.
        dowDayMonth.find(name)?.let { m ->
            val mo = months.indexOf(m.groupValues[2].lowercase(Locale.ROOT))
            if (mo >= 0) {
                return nearestYear(
                    mo,
                    m.groupValues[1].toInt(),
                    // hour24, because some of these packs write a 12-hour
                    // clock. A blank meridiem leaves the hour alone, which is
                    // what the 24-hour majority need.
                    hour24(m.groupValues[3].toInt(), m.groupValues[5]),
                    m.groupValues[4].toInt(),
                    nowMs,
                    // The token if it wrote one, else the pack prefix's zone —
                    // the same rule the bracketed listings already follow.
                    zoneFromToken(m.groupValues[6]) ?: zoneOf(name),
                )
            }
        }
        monthDay.find(name)?.let { m ->
            val mo = months.indexOf(m.groupValues[1].lowercase(Locale.ROOT))
            val minutes = m.groupValues[4]
            val meridiem = m.groupValues[5]
            // One of the two has to be there, or this is not a time at all.
            // With the meridiem optional, "Sep 09 2026" otherwise matches with
            // the year read as an hour.
            if (mo >= 0 && (minutes.isNotEmpty() || meridiem.isNotEmpty())) {
                val hour = hour24(m.groupValues[3].toInt(), meridiem)
                return nearestYear(mo, m.groupValues[2].toInt(), hour, minutes.toIntOrNull() ?: 0, nowMs)
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
        // A 24-hour clock says nothing after the number, and says it about
        // every hour including twelve — so no meridiem means the hour stands.
        // Without this "12:30" with no AM/PM became midnight.
        if (meridiem.isBlank()) return h
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
     *
     * [zone] defaults to [americanZone] because the two packs that reach here
     * without one — NFL's "8/20 8pm" and MLS's "@ Aug 19 7:30 PM" — are US
     * feeds quoting US kick-offs. The listings packs name their zone and pass
     * it in.
     */
    private fun nearestYear(
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        nowMs: Long,
        zone: TimeZone = americanZone,
    ): Long {
        val thisYear = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
            .get(Calendar.YEAR)
        return (thisYear - 1..thisYear + 1)
            .map { at(it, month, day, hour, minute, zone) }
            .minByOrNull { kotlin.math.abs(it - nowMs) }!!
    }

    /**
     * Studio coverage and tactical cameras — a companion feed, not the match.
     *
     * "Goal Rush" is the whip-around show, and it earns its place here by
     * naming two clubs the way a fixture does: "Goal Rush: Chelsea v Brighton
     * & Hove Albion" groups with the real Chelsea v Brighton slot fifty
     * minutes later and, with nothing to separate them, could take the row and
     * bring its own earlier start as the kick-off.
     *
     * Deliberately not here: Countdown, Preview, PL Live. They are companion
     * programming too, but none of them names two clubs of one league on this
     * panel, so none can group with a fixture — adding them would be guarding
     * a door nothing walks through.
     */
    private val sideFeedWords = Regex(
        """(?i)\b(Studio Coverage|Player Camera|Multi ?Camera|Match Centre|Tactical|Fan ?Zone|Goal ?Rush)\b"""
    )

    internal fun isSideFeed(name: String) = sideFeedWords.containsMatchIn(name)

    /**
     * "EN ESPAÑOL", "SPANISH", "DEUTSCH": the same match, another commentary.
     *
     * Kept as one list per language rather than a single alternation so that
     * [feedNote] can say WHICH one. The ladder swaps a failed stream for the
     * next alternate and keeps the title it opened with, so a viewer dropped
     * onto the Spanish call has to be told; naming the language is the whole
     * difference between "this is not the match I chose" and "this is my
     * match, in Spanish". [isLanguageFeed] is this list, asked as a yes/no.
     */
    private val languageFeeds: List<Pair<Regex, String>> = listOf(
        Regex("""(?i)\b(EN\s+ESPA[ÑN]OL|ESPA[ÑN]OL|SPANISH)\b""") to "Spanish commentary",
        Regex("""(?i)\b(EN\s+FRAN[ÇC]AIS|FRENCH)\b""") to "French commentary",
        Regex("""(?i)\bARABIC\b""") to "Arabic commentary",
        Regex("""(?i)\bPORTUGU[EÊ]S\b""") to "Portuguese commentary",
        Regex("""(?i)\bDEUTSCH\b""") to "German commentary",
        Regex("""(?i)\bITALIANO\b""") to "Italian commentary",
    )

    /**
     * The same markers as one pass, for [stripNoise].
     *
     * Built FROM [languageFeeds] so the two cannot drift: a language this
     * knows to classify but not to strip leaves "EN ESPAÑOL-CELTA DE VIGO"
     * keying as a different club from "CELTA DE VIGO", which is two rows for
     * one match. Declared after the list it is built from — an object's
     * properties initialise in file order, and reading one above its source
     * gets null. [stripNoise] is a function, so its position does not matter.
     */
    private val languageFeedWords = Regex(
        languageFeeds.joinToString("|") { it.first.pattern.removePrefix("(?i)") },
        RegexOption.IGNORE_CASE,
    )

    internal fun isLanguageFeed(name: String) = feedLanguage(name) != null

    private fun feedLanguage(name: String): String? =
        languageFeeds.firstOrNull { it.first.containsMatchIn(name) }?.second

    /**
     * What an alternate slot IS, in the words a viewer would use, or null when
     * it is simply another feed of the same match.
     *
     * [com.agoro.tv.ui.player.PlayerSession] recovers a stream that will not
     * open by stepping onto the next alternate, and it keeps the title it
     * opened with. For a channel that is right: the alternates there are the
     * same channel at another quality. For a fixture they are different SLOTS
     * — the Spanish call, the pre-match studio show, another pack's feed — so
     * without this the screen names a match while playing the build-up. Dead
     * PPV pipes are routine, so that ladder runs often.
     */
    fun feedNote(name: String): String? =
        feedLanguage(name) ?: "studio feed".takeIf { isSideFeed(name) }

    /**
     * What the slot claims its picture is. The advertised token is all there
     * is here — these slots are never probed, because a pipe's measurement
     * belongs to whatever match happened to be running at the time.
     */
    internal fun tierOf(name: String): Int {
        val claim = packBadge.replace(name, " ")
        return when {
            tier8k.containsMatchIn(claim) -> 0
            tier4k.containsMatchIn(claim) -> 1
            tierFhd.containsMatchIn(claim) -> 2
            tierHd.containsMatchIn(claim) -> 3
            else -> SportsEvent.TIER_UNKNOWN
        }
    }

    /**
     * "8K EXCLUSIVE": a shelf badge every slot in a pack wears, not a claim
     * about this slot's picture.
     *
     * Counted on this panel: 2,411 of the 2,412 slots saying 8K are in packs
     * that stamp it on more than nine slots in ten — all 1,001 of ESPN+ PPV
     * VIP, all 200 of SOCCER PPV, every DAZN, MAX, FIFA+ and Apple TV pack.
     * It is on the slots reading "NO EVENT STREAMING NOW". A token that a
     * whole pack wears is worth exactly what a token no slot wears is worth,
     * and reading it as tier 0 did real damage: it put every badged slot at
     * the top of [tierOf], which is compared BEFORE [sourceOf], so the ESPN+
     * demotion could never reach the fixtures that most needed it.
     *
     * Only this phrase, and only where the two words sit together. A slot that
     * says plain "8K", or "4K", has said something about itself.
     */
    private val packBadge = Regex("""(?i)\b8K\s+EXCLUSIVE\b""")

    /**
     * A verdict on the SOURCE, for when the tier token cannot give one.
     *
     * Most event slots advertise a tier and [tierOf] settles it. The bracketed
     * packs do not — "US (ESPN+ 100) | Soccer: …" says nothing about its
     * picture — so every one of them ranks [SportsEvent.TIER_UNKNOWN], the
     * comparator had nothing left to separate them by, and the winner was
     * whichever the playlist happened to list first. That is how a fixture
     * carried by two packs opened on the thin one, which is the "the LaLiga
     * feeds from ESPN+ are subpar" report.
     *
     * ESPN+ is named, and only downwards: it is the pack this catalogue has
     * been shown to re-stream below the others. Nothing is promoted, because
     * demoting the field on suspicion would trade a measured feed for a guess,
     * and the tier is still compared first — a slot that advertises 4K wins
     * however thin its source is thought to be. The demoted feed stays in
     * `alternates`, so the player's ladder still reaches it if the better one
     * will not open.
     */
    internal fun sourceOf(name: String): Int =
        if (thinSource.containsMatchIn(name)) SportsEvent.SOURCE_THIN
        else SportsEvent.SOURCE_NEUTRAL

    private val thinSource = Regex("""(?i)\bESPN\s*\+""")

    /**
     * Which sport a competition is, and which sport a slot says it is on.
     *
     * The packs are shelves, and a shelf can be wrong. On 2026-08-27 the
     * NFL preseason ran on two of them at once:
     *
     *   NFL  | 01 - 8/27 7pm Steelers at Bills                        (NFL PPV)
     *   Next | Preseason: Steelers vs. Bills | 27-08-2026 | 16:00 (GMT)
     *                                              ... | US: SOCCER PPV 14
     *
     * Same fixture, so [bestPerFixture] folds them into one row — and the two
     * disagree about the kick-off by SEVEN HOURS. 16:00 GMT is 11am in Dallas;
     * the game was at 6pm. Nothing in the comparator could tell them apart, so
     * the row took whichever the playlist listed first and reported a match as
     * live while it was still seven hours away.
     *
     * A pack that has filed American football under SOCCER is not a pack to
     * take a clock from. Only unambiguous markers count: FOOTBALL is deliberately
     * absent because half the world means soccer by it and ESPN means the NFL.
     */
    internal fun sportOf(league: String): String? = when (league) {
        "NFL" -> "gridiron"
        "NBA" -> "basketball"
        "MLS", "Premier League", "La Liga", "Serie A",
        "Bundesliga", "Ligue 1", "Champions League",
        // The cup and confederation shelves are football too. They were absent
        // while this only fed [isWrongSport], where a null simply meant "no
        // opinion" — but [crestFor] reads it as well, and a competition with no
        // sport can be handed any badge in the index.
        "Europa League", "Conference League", "UEFA", "Carabao Cup", "FA Cup" -> "soccer"
        else -> null
    }

    /**
     * The crest for one side of one fixture, or null for a monogram.
     *
     * Scoped by SPORT, because the club names are not unique across them. The
     * manifest's index is keyed by the roster's spelling and nothing else, and
     * "Spurs" is San Antonio in the NBA roster and Tottenham in the Premier
     * League one — so one of the two silently overwrote the other, and whoever
     * lost wore the wrong badge. "Patriots", "Falcons", "Kings", "Giants" and
     * "Rangers" are all shared the same way.
     *
     * Two keys, in order: "<sport>|<club>", which crest_match.py writes and
     * which settles the collision outright, and the bare club name, which is
     * what a manifest built before that does — including the one already on the
     * box, since this ships in the app and the manifest arrives separately.
     *
     * The bare hit is checked against the crest's OWN SOURCE, and that check is
     * closed rather than open: a klunn91 path names its sport in the folder,
     * and a folder this app does not carry is refused rather than trusted.
     * Getting that polarity wrong is not theoretical — the index maps
     * "Cardinals" and "Giants" to klunn91's **MLB** folder, because the US pool
     * is built from the whole tree and MLB sorts before NFL, so an NFL fixture
     * was handed a baseball badge by a guard written to prevent exactly that.
     *
     * A row with no crest is a row with a monogram, which is a shape this
     * screen has always had to draw.
     */
    fun crestFor(crests: Map<String, String>, league: String, club: String): String? {
        val want = sportOf(league)
        // No "null|Chelsea": a competition with no sport has no scoped key, and
        // asking for one is a lookup that can only ever miss.
        if (want != null) crests["$want|$club"]?.let { return it }
        val url = crests[club] ?: return null
        if (want == null) return url
        return if (crestSport(url) == want) url else null
    }

    /** The folder a klunn91 crest sits in, which is the sport it belongs to. */
    private val klunnFolder = Regex("""/team-logos/[^/]+/([^/]+)/""")

    /**
     * What a crest URL says it is, or null when its source says nothing.
     *
     * Null is only for sources with no sport in them at all. Every klunn91
     * path HAS one — it is the folder — so an unrecognised folder answers with
     * the folder itself, which matches none of the sports this app carries and
     * is therefore refused. NCAA, MLB, NHL and anything the repository adds
     * later all land there without this needing to know about them.
     */
    private fun crestSport(url: String): String? {
        if (url.contains("football-logos")) return "soccer"
        val folder = klunnFolder.find(url)?.groupValues?.get(1) ?: return null
        return when (folder.uppercase(Locale.ROOT)) {
            "NFL" -> "gridiron"
            "NBA" -> "basketball"
            else -> folder.uppercase(Locale.ROOT)
        }
    }

    private val sportMarkers = listOf(
        Regex("""(?i)\bSOCCER\b""") to "soccer",
        Regex("""(?i)\bNFL\b""") to "gridiron",
        Regex("""(?i)\bNBA\b""") to "basketball",
        Regex("""(?i)\b(MLB|MiLB)\b""") to "baseball",
        Regex("""(?i)\bNHL\b""") to "hockey",
        Regex("""(?i)\bRUGBY\b""") to "rugby",
        Regex("""(?i)\bCRICKET\b""") to "cricket",
    )

    /** The sport a slot's own name claims, or null when it names none. */
    internal fun namedSport(name: String): String? =
        sportMarkers.firstOrNull { it.first.containsMatchIn(name) }?.second

    /**
     * True only when BOTH are known and they disagree. A slot naming no sport,
     * or a competition this does not map, changes nothing — the point is to
     * demote a contradiction, never to reward a slot for being explicit.
     */
    internal fun isWrongSport(name: String, league: String): Boolean {
        val want = sportOf(league) ?: return false
        val said = namedSport(name) ?: return false
        return said != want
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
        events.groupBy { fixtureKey(it) }
            .map { (_, sameMatch) ->
                val ranked = sameMatch.sortedWith(byFeed)
                ranked.first().copy(alternates = ranked.drop(1).map { it.streamId })
            }

    /**
     * Which slot speaks for a fixture, best first.
     *
     * One comparator, shared by [bestPerFixture], by [lendClocks] and by the
     * mis-shelved extras in [upcoming]. They were three copies, and three
     * copies of a rule this subtle is three chances for it to drift.
     *
     * The match itself first — over another language's call, over a side
     * camera — and only then the better picture. A studio show in 8K is still
     * not the match. Arsenal v Coventry arrived on four slots at the same tier
     * (studio coverage, a player camera, a multi camera and the match) and
     * with nothing separating them the tie broke on whichever landed first:
     * the pre-match studio show took the row, bringing its own earlier start
     * time as the kick-off.
     */
    private val byFeed = compareBy<SportsEvent>(
        { if (it.sideFeed) 2 else if (it.languageFeed) 1 else 0 },
        // Before any question of picture. A pack that has the sport wrong has
        // earned no say in the kick-off, and the row takes its time from
        // whoever wins here.
        { if (it.wrongSport) 1 else 0 },
        { it.tierRank },
        // Only reached when the two advertise the same picture, which for the
        // bracketed packs means neither said anything at all. See sourceOf.
        { it.sourceRank },
    )

    /**
     * Gives a slot that carries no clock the kick-off a sibling slot knows.
     *
     * The same match is routinely on four slots and only some of them say
     * when it is. A slot with no clock cannot be windowed, cannot expire and
     * cannot stop claiming to be live — see [dowDayMonth] — so a fixture that
     * one slot times perfectly still produced a permanently-live row off the
     * slot that stayed silent.
     *
     * **Before the window, not inside the fold.** Lending inside
     * [bestPerFixture] only reaches siblings that were already admitted, and
     * the case that matters most is the one where they were not: at 14:00,
     * SOCCER PPV says "Live | Barcelona vs. Athletic Club" with no clock while
     * TSN+ has the same match at 20:30. The TSN+ slot is six hours out, so the
     * window drops it first, the fold never sees it, and the silent slot is
     * admitted on its bare word and badged LIVE six and a half hours early.
     * Lent here, the silent slot carries 20:30 into the window and is
     * correctly held back until the cue.
     *
     * The lender is the best-ranked slot that has a clock, which is the same
     * order the fold picks a feed in. Slots that HAVE a clock keep their own —
     * each one still gets its own say on whether the fixture is on, which is
     * the rule that stopped one late clock among three taking a match off the
     * screen.
     */
    private fun lendClocks(events: List<SportsEvent>): List<SportsEvent> {
        if (events.none { it.startMs == null }) return events
        val known = HashMap<String, Long>()
        for (e in events.sortedWith(byFeed)) {
            val at = e.startMs ?: continue
            known.putIfAbsent(fixtureKey(e), at)
        }
        if (known.isEmpty()) return events
        return events.map { e ->
            if (e.startMs != null) e
            else known[fixtureKey(e)]?.let { e.copy(startMs = it) } ?: e
        }
    }

    /**
     * How far apart two packs' clocks can be and still mean one kick-off.
     *
     * Twenty minutes. The packs that agree are rarely identical — the Apple
     * pack starts its slot five minutes before the whistle, the listings packs
     * round to the quarter hour — and the disagreements this exists to catch
     * are hours, not minutes.
     */
    private const val CLOCK_AGREEMENT_MS = 20 * 60_000L

    /**
     * When the packs disagree about a kick-off, the ones that AGREE win.
     *
     * A match is routinely listed by four packs at once, and one of them
     * being wrong is not unusual — it is Friday. New York City v Nashville,
     * 4 September: the soccer shelf billed it "04-09-2026 | 16:30 (GMT)", the
     * Apple pack "(2026-09-04 19:25:00)" with no zone at all, and two others
     * "Fri 04 Sep 19:30 EDT" and "@ Sep 4 7:30 PM". The last two are right and
     * agree; the first is seven hours early and the second four.
     *
     * The window then takes the earliest clock at its word, because a clock
     * inside the cue is all it asks for. So a viewer at 11:20 in the morning
     * was told the evening's match started in seven minutes, while the two
     * slots that had it right sat outside the window saying nothing. The row
     * it opens is a pipe with no match behind it for another seven hours.
     *
     * No zone is guessed here and no pack is called a liar. Two independent
     * packs landing on the same minute is simply better evidence than one
     * landing somewhere else, and the odd one out is moved onto their clock
     * rather than dropped: the stream behind it is still the same match, and
     * it stays in the ladder as a fallback.
     *
     * Three slots minimum, and the winning cluster has to be strictly the
     * largest. Two slots that disagree are a coin toss, and this does not
     * toss coins — it leaves them exactly as they came.
     */
    private fun agreeClocks(events: List<SportsEvent>): List<SportsEvent> {
        val consensus = HashMap<String, Long>()
        for ((key, slots) in events.groupBy { fixtureKey(it) }) {
            val times = slots.mapNotNull { it.startMs }.sorted()
            if (times.size < 3) continue
            var best = emptyList<Long>()
            var tied = false
            for (from in times) {
                val cluster = times.filter { it >= from && it - from <= CLOCK_AGREEMENT_MS }
                when {
                    cluster.size > best.size -> { best = cluster; tied = false }
                    cluster.size == best.size && cluster.firstOrNull() != best.firstOrNull() ->
                        tied = true
                }
            }
            if (!tied && best.size >= 2) consensus[key] = best[best.size / 2]
        }
        if (consensus.isEmpty()) return events
        return events.map { e ->
            val agreed = consensus[fixtureKey(e)] ?: return@map e
            val own = e.startMs ?: return@map e
            if (kotlin.math.abs(own - agreed) <= CLOCK_AGREEMENT_MS) e
            else e.copy(startMs = agreed)
        }
    }

    /**
     * The fixtures worth putting on screen: on now, or starting within the cue.
     * Live first, then soonest — a match already running outranks one that has
     * not started however close its kick-off.
     */
    fun upcoming(
        events: List<SportsEvent>,
        nowMs: Long,
        cueMinutes: Int,
        /**
         * How old the playlist these slots were read from is. Zero, the
         * default, means "just fetched" and trusts everything; see
         * [TIMELESS_MAX_AGE_MS] for what it buys.
         */
        snapshotAgeMs: Long = 0L,
    ): List<SportsEvent> {
        val cue = cueMinutes * 60_000L
        // Drop the slots that cannot be trusted with a clock, THEN window,
        // then fold. All three steps, in that order, and each one is there
        // because the other arrangement broke something real.
        //
        // Folding before the window — which this did briefly — let the slot
        // that wins on feed quality decide whether the fixture is on at all.
        // Barcelona v Athletic Club was on four slots: TSN+ said 18:30, ESPN+
        // said 18:55, and the soccer shelf said 20:30. The soccer shelf won
        // the fold on feed rank, carried its clock into the row, and the match
        // vanished from a screen that said "Nothing on right now" while it was
        // being played.
        //
        // Windowing before the fold — which it did before that — let ANY slot
        // admit a fixture on its own say-so, which is how the NFL preseason
        // appeared as LIVE seven hours early off a soccer shelf.
        //
        // Excluding wrongSport first settles both. A pack that has filed
        // American football under SOCCER cannot put a fixture on screen, so
        // the seven-hours-early case is gone; and every remaining slot gets
        // its own say on whether the fixture is on, so one late clock among
        // three can no longer take the match off the screen. The fold then
        // chooses the FEED, which is all it was ever good at.
        // Clocks are lent BEFORE the window, so a slot that says only "LIVE"
        // is judged on its fixture's real kick-off rather than admitting
        // itself. Lent within each partition, never across: a pack that has
        // filed American football under SOCCER must not hand its clock to a
        // trusted slot, which is the seven-hours-early lesson above.
        val (trustedRaw, misshelvedRaw) = events.partition { !it.wrongSport }
        // Agreement AFTER lending and, like lending, never across the
        // partition: a pack that has the sport wrong does not get a vote on a
        // trusted slot's clock, which is the same rule for the same reason.
        val trusted = agreeClocks(lendClocks(trustedRaw))
        val misshelved = agreeClocks(lendClocks(misshelvedRaw))
        fun inWindow(e: SportsEvent): Boolean {
            val s = e.startMs ?: return e.live
            return s <= nowMs + cue && nowMs <= s + FIXTURE_LENGTH_MS
        }
        val rows = bestPerFixture(
            trusted.filter(::inWindow)
        )
        // The mis-shelved slots come back as FALLBACKS, having been kept out
        // of every decision above. What they cannot be trusted with is a
        // clock; the stream behind them is the same match, and the player's
        // ladder should still be able to reach it when the chosen feed will
        // not open. Excluding them outright was the first cut of this and it
        // quietly cost the row a source.
        val extras = misshelved.groupBy { fixtureKey(it) }
        // A fixture carried ONLY by mis-shelved slots still has to reach the
        // screen. Excluding them outright was right for the clock and wrong
        // for the match: a soccer shelf that has the sport wrong can still be
        // the only place a game is, and dropping it meant a match that is
        // being played showed nowhere at all — the same fault, from the other
        // side, as the one this ordering was written to fix. They cannot ADMIT
        // a fixture that a trusted slot already speaks for; they can only
        // speak for one nothing else does.
        // Spoken for by ANY trusted slot, in window or not. Keying this on the
        // rows instead was the seven-hours-early bug back again: the NFL shelf
        // had Steelers v Bills correctly at 23:00, that is outside the cue so
        // it produced no row, and the soccer shelf walked in as an "orphan" at
        // 16:00. A trusted slot that says the match is not on yet has spoken.
        val spokenFor = trusted.mapTo(HashSet()) { fixtureKey(it) }
        val orphans = bestPerFixture(
            misshelved.filter {
                inWindow(it) && fixtureKey(it) !in spokenFor
            }
        )
        return (rows + orphans).filter {
            // A row that still has no clock after the fold had a chance to
            // lend it one is a slot's bare word that something is on, and
            // that word is exactly as old as the playlist it was read from.
            // Nothing can age it: the window above falls through to the parse
            // flag, and so does [SportsEvent.isLive], so it wears a LIVE badge
            // for as long as it is kept. Believe it while the snapshot is
            // fresh, stop believing it after that.
            it.startMs != null || snapshotAgeMs < TIMELESS_MAX_AGE_MS
        }.map { row ->
            // Ranked, not appended in playlist order. A mis-shelved slot can
            // also be a studio show, and unranked it became the first thing
            // the player's ladder reached for when the match feed would not
            // open — the pre-match programme instead of the match.
            val also = extras[fixtureKey(row)]
                ?.sortedWith(byFeed)
                ?.map { it.streamId }
                ?.filterNot { it == row.streamId || it in row.alternates }
                .orEmpty()
            if (also.isEmpty()) row else row.copy(alternates = row.alternates + also)
        }.sortedWith(
            compareByDescending<SportsEvent> { it.isLive(nowMs) }.thenBy { it.startMs ?: 0L }
        )
    }

    /**
     * How long a fixture is assumed to run when nothing says otherwise. Three
     * hours covers a football match with stoppages and an NFL game; past that a
     * slot still named for it has almost certainly moved on.
     */
    private const val FIXTURE_LENGTH_MS = 3L * 60 * 60 * 1000

    /**
     * How long a bare "LIVE" with no kick-off on it is worth believing.
     *
     * Two hours, which is a football match with its build-up: past that the
     * claim is older than any event it could have been describing, and a PPV
     * pipe whose name still says so has almost certainly been re-pointed —
     * these slots are pipes, and the provider rewrites the name for the next
     * event on the same stream id.
     *
     * It only ever fires when the app cannot get a newer playlist, because
     * opening the Sport destination asks for one at this same age (see
     * MainViewModel.refreshFixturesIfStale). So the ordinary path is a fresh
     * snapshot and nothing is dropped; this is what happens when the panel is
     * unreachable, and dropping the row is the honest answer there. Leaving it
     * up is how a viewer presses OK on a match that ended at lunchtime.
     */
    private const val TIMELESS_MAX_AGE_MS = 2L * 60 * 60 * 1000
}
