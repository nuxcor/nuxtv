package com.agoro.tv

import com.agoro.tv.data.SportsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Every name here is copied from the provider's own PPV slots. The four packs
 * agree on nothing, so the parser is only worth as much as these cases are.
 */
class SportsParserTest {

    private val leagues = mapOf(
        "NFL" to listOf("Raiders", "Texans", "Jets", "Steelers"),
        "NBA" to listOf("Knicks", "Timberwolves"),
        "MLS" to listOf("Philadelphia Union", "Inter Miami"),
        "Premier League" to listOf("Brighton", "Arsenal"),
    )

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: String): Long =
        Calendar.getInstance(TimeZone.getTimeZone(zone)).apply {
            clear(); set(y, mo - 1, d, h, mi)
        }.timeInMillis

    @Test
    fun `reads the NFL pack`() {
        val now = ms(2026, 8, 20, 22, 0, "America/New_York")
        val e = SportsParser.parse(1, "NFL  | 01 - 8/20 8pm Raiders at Texans", now, leagues)!!
        assertEquals("NFL", e.league)
        assertEquals("Raiders", e.home)
        assertEquals("Texans", e.away)
        assertEquals("kick-off is 8pm Eastern", ms(2026, 8, 20, 20, 0, "America/New_York"), e.startMs)
        assertTrue("started two hours ago", e.live)
    }

    @Test
    fun `reads the NBA pack, which gives a real date`() {
        val now = ms(2026, 1, 18, 1, 0, "UTC")
        val e = SportsParser.parse(
            2, "NBA 02: Knicks (NYK) x Timberwolves (MIN) start:2026-01-18 00:20:00 stop:x",
            now, leagues,
        )!!
        assertEquals("NBA", e.league)
        assertEquals("Knicks", e.home)
        assertEquals("Timberwolves", e.away)
        assertEquals(ms(2026, 1, 18, 0, 20, "UTC"), e.startMs)
    }

    @Test
    fun `reads the MLS pack`() {
        val now = ms(2026, 8, 19, 23, 45, "America/New_York")
        val e = SportsParser.parse(
            3, "Philadelphia Union vs Inter Miami CF @ Aug 19 7:30 PM :MLS  02", now, leagues,
        )!!
        assertEquals("MLS", e.league)
        assertEquals("Philadelphia Union", e.home)
    }

    @Test
    fun `reads the piped soccer pack`() {
        val now = ms(2026, 8, 20, 15, 30, "UTC")
        val e = SportsParser.parse(
            4, "Next | Brighton vs. Arsenal | all | 20-08-2026 | 15:00 (GMT) | 8K EXCLUSIVE",
            now, leagues,
        )!!
        assertEquals("Premier League", e.league)
        assertEquals(ms(2026, 8, 20, 15, 0, "UTC"), e.startMs)
    }

    /**
     * The listings pack, copied exactly. A fifth time format — a bare
     * timestamp in brackets — and the competition billed right after the
     * fixture, both of which have to come off before the clubs are readable.
     */
    @Test
    fun `reads the listings pack`() {
        val now = ms(2026, 8, 22, 4, 30, "Australia/Sydney")
        val roster = mapOf("Premier League" to listOf("Arsenal", "Coventry City"))
        val e = SportsParser.parse(
            1,
            "AU (STAN 13) |  \u2022 Arsenal v Coventry City  Premier League Matchweek 1 " +
                "2026/2027 (2026-08-22 04:50:29)",
            now, roster,
        )!!
        assertEquals("Premier League", e.league)
        assertEquals("Arsenal", e.home)
        assertEquals("Coventry City", e.away)
        // 04:50 is Sydney's clock, not UTC — 18:50 UTC, an evening kick-off in
        // England. Read as UTC it would land ten hours late.
        assertEquals(ms(2026, 8, 22, 4, 50, "Australia/Sydney"), e.startMs)
        assertTrue("still ahead of now, so it is cued", e.startMs!! > now)
        assertTrue(
            "and it shows",
            SportsParser.upcoming(listOf(e), now, cueMinutes = 60).isNotEmpty(),
        )
    }

    /**
     * Arsenal v Coventry arrived on four slots at the same tier: studio
     * coverage, a player camera, a multi camera and the match. The studio show
     * took the row and brought its own earlier start with it.
     */
    @Test
    fun `the match beats its own studio and camera feeds`() {
        val now = ms(2026, 8, 22, 4, 30, "Australia/Sydney")
        val roster = mapOf("Premier League" to listOf("Arsenal", "Coventry City"))
        fun slot(id: Int, label: String, minute: String) = SportsParser.parse(
            id,
            "AU (STAN $id) | $label Arsenal v Coventry City  Premier League Matchweek 1 " +
                "2026/2027 (2026-08-22 04:$minute:29)",
            now, roster,
        )!!
        val studio = slot(12, "Studio Coverage:", "00")
        val match = slot(13, "", "50")
        val player = slot(14, "Player Camera", "50")
        val rows = SportsParser.upcoming(listOf(studio, match, player), now, cueMinutes = 60)
        assertEquals("one row", 1, rows.size)
        assertEquals("the match itself leads", 13, rows[0].streamId)
        assertEquals(
            "and it carries the real kick-off, not the studio's start",
            ms(2026, 8, 22, 4, 50, "Australia/Sydney"), rows[0].startMs,
        )
    }

    /**
     * parseAll is the only path the app uses, and it runs a cheap sieve in
     * front of the parser that parse() does not. Nothing here was covered
     * until the sieve had a test: a slot it wrongly rejected would have cost
     * a fixture on screen while the suite stayed green.
     */
    @Test
    fun `the sieve keeps everything the parser would have kept`() {
        val now = ms(2026, 8, 20, 15, 0, "UTC")
        val roster = mapOf(
            "Premier League" to listOf("Brighton", "Arsenal"),
            "NBA" to listOf("Knicks", "Timberwolves"),
        )
        val kept = listOf(
            "Live | Brighton vs. Arsenal | all | 20-08-2026 | 15:00 (GMT)",
            "Next | Brighton v Arsenal | all | 20-08-2026 | 15:30 (GMT)",
            // The separator only survives noise removal here — a space-bounded
            // sieve would have dropped it before the parser saw it.
            "NBA 02: Knicks (NYK)x Timberwolves (MIN) start:2026-08-20 15:10:00",
        )
        val slots = kept.mapIndexed { i, n -> i to n }
        val viaAll = SportsParser.parseAll(slots, now, roster).map { it.title }.toSet()
        val viaParse = slots.mapNotNull { (id, n) -> SportsParser.parse(id, n, now, roster) }
            .map { it.title }.toSet()
        assertEquals("the sieve must not reject what the parser accepts", viaParse, viaAll)
        // Two of the three are the same fixture at different kick-offs, so the
        // set of titles holds two, not three.
        assertTrue(
            "the separator that only noise removal reveals survived the sieve",
            "Knicks v Timberwolves" in viaAll,
        )
        assertTrue("and the plain ones did too", "Brighton v Arsenal" in viaAll)
    }

    /** ...while still throwing out the nine in ten that are not fixtures. */
    @Test
    fun `the sieve rejects slots with no fixture and no club`() {
        val now = ms(2026, 8, 20, 15, 0, "UTC")
        val roster = mapOf("Premier League" to listOf("Brighton", "Arsenal"))
        val junk = listOf(
            "- NO EVENT STREAMING - | 8K EXCLUSIVE",
            "US: 24/7 I LOVE LUCY",
            "####### TNT SPORTS EVENT #######",
            // a fixture, but between clubs we do not carry
            "Live | Hartlepool vs Barnet | all | 20-08-2026 | 15:00 (GMT)",
        )
        val out = SportsParser.parseAll(junk.mapIndexed { i, n -> i to n }, now, roster)
        assertEquals(0, out.size)
    }

    /** 2,001 slots say this. None of them is a match. */
    @Test
    fun `an empty slot is not a fixture`() {
        val now = ms(2026, 8, 20, 15, 0, "UTC")
        assertNull(SportsParser.parse(5, "- NO EVENT STREAMING - | 8K EXCLUSIVE", now, leagues))
        assertNull(SportsParser.parse(6, "NBA 06 :", now, leagues))
    }

    @Test
    fun `a finished match is never shown`() {
        val now = ms(2026, 8, 20, 15, 0, "UTC")
        assertNull(
            SportsParser.parse(
                7, "End | Brighton vs. Arsenal | all | 20-08-2026 | 12:00 (GMT)", now, leagues,
            )
        )
    }

    /**
     * The reason a competition string cannot be trusted: this is cricket, and
     * "Premier League" is right there in the name.
     */
    @Test
    fun `a competition name that only looks major is rejected`() {
        val now = ms(2026, 8, 20, 15, 0, "UTC")
        assertNull(
            SportsParser.parse(
                8,
                "Next | Antigua Falcons vs. Barbados Royals | Caribbean Premier League " +
                    "| 20-08-2026 | 15:00 (GMT)",
                now, leagues,
            )
        )
    }

    /** One slot really does still carry last season's game. */
    @Test
    fun `a stale slot from last season is dropped`() {
        val now = ms(2026, 8, 20, 15, 0, "UTC")
        assertNull(
            SportsParser.parse(
                9, "NBA 02: Knicks (NYK) x Timberwolves (MIN) start:2025-01-18 00:20:00",
                now, leagues,
            )
        )
    }

    /** No clock and no LIVE from the pack: showing it would be a guess. */
    @Test
    fun `a fixture with no readable time is only shown when the pack says LIVE`() {
        val now = ms(2026, 8, 20, 15, 0, "UTC")
        assertNull(SportsParser.parse(10, "Brighton vs. Arsenal | all | 8K", now, leagues))
        assertTrue(
            SportsParser.parse(11, "Live | Brighton vs. Arsenal | all | 8K", now, leagues)!!.live
        )
    }

    @Test
    fun `the cue window opens an hour early and closes when the match is over`() {
        val now = ms(2026, 8, 20, 15, 0, "UTC")
        fun at(hour: Int) = SportsParser.parse(
            12, "Next | Brighton vs. Arsenal | all | 20-08-2026 | $hour:00 (GMT)", now, leagues,
        )
        val soon = at(16)!!      // kicks off in an hour
        val later = at(18)!!     // three hours away
        val done = at(11)!!      // four hours ago
        val shown = SportsParser.upcoming(listOf(soon, later, done), now, cueMinutes = 60)
            .map { it.startMs }
        assertTrue("an hour ahead is cued", soon.startMs in shown)
        assertTrue("three hours ahead is not", later.startMs !in shown)
        assertTrue("long finished is not", done.startMs !in shown)
    }

    /**
     * Every one of these matched a league on the first attempt, against the
     * provider's own slots. They are the reason matching is on word boundaries
     * and other leagues are rejected outright.
     */
    @Test
    fun `nicknames buried inside other words are not matches`() {
        val now = ms(2026, 8, 20, 15, 0, "UTC")
        val roster = mapOf(
            "NBA" to listOf("Suns", "Kings", "Pelicans"),
            "Premier League" to listOf("Wolves"),
            "Ligue 1" to listOf("Angers"),
        )
        // "Suns" inside "Sundowns"
        assertNull(
            SportsParser.parse(
                1, "NEXT | Mamelodi Sundowns vs. Nsingizini Hotspurs | 20-08-2026 | 15:00 (GMT)",
                now, roster,
            )
        )
        // "Wolves" inside "SeaWolves"
        assertNull(
            SportsParser.parse(
                2, "New Hampshire Fisher Cats vs Erie SeaWolves | 20-08-2026 | 15:00 (GMT)",
                now, roster,
            )
        )
        // "Angers" inside "Rangers"
        assertNull(
            SportsParser.parse(
                3, "Dundee United vs Rangers | 20-08-2026 | 15:00 (GMT)", now, roster,
            )
        )
    }

    /** Baseball fields a Giants, a Cardinals and a Rangers of its own. */
    @Test
    fun `another league flying the same nickname is rejected`() {
        val now = ms(2026, 8, 20, 15, 0, "UTC")
        val roster = mapOf("NFL" to listOf("Giants", "Cardinals"))
        assertNull(
            SportsParser.parse(
                4, "US (MLB 012) | San Francisco Giants vs St. Louis Cardinals " +
                    "| 20-08-2026 | 15:00 (GMT)",
                now, roster,
            )
        )
        // The NFL fixture of the same name still reads.
        assertEquals(
            "NFL",
            SportsParser.parse(
                5, "Next | Giants vs. Cardinals | all | 20-08-2026 | 15:00 (GMT)", now, roster,
            )!!.league,
        )
    }

    /**
     * A cup tie is the normal case for one recognised side: a Pokal or FA Cup
     * draw pairs a top-flight club with one three divisions down that no roster
     * will ever carry.
     */
    @Test
    fun `a cup tie against a club we do not carry still counts`() {
        val now = ms(2026, 8, 20, 15, 0, "UTC")
        val roster = mapOf("Bundesliga" to listOf("Bayern Munich", "Union Berlin"))
        val e = SportsParser.parse(
            1, "Next | SV Waldhof Mannheim vs. Bayern Munich | all | 20-08-2026 | 15:00 (GMT)",
            now, roster, ambiguous = setOf("Giants", "Cardinals"),
        )!!
        assertEquals("Bundesliga", e.league)
        assertEquals("SV Waldhof Mannheim", e.home)
    }

    /**
     * Both were real rows on screen before the full-name rule: Welsh football
     * under the NFL, and a Michigan high-school game in the Premier League.
     */
    @Test
    fun `a single-word club cannot carry a fixture alone`() {
        val now = ms(2026, 8, 20, 15, 0, "UTC")
        val roster = mapOf(
            "NFL" to listOf("Saints"),
            "Premier League" to listOf("Chelsea"),
            "Bundesliga" to listOf("Bayern Munich"),
        )
        assertNull(
            SportsParser.parse(
                1, "Live | The new saints v Sabah 6 | all | 20-08-2026 | 15:00 (GMT)", now, roster,
            )
        )
        assertNull(
            SportsParser.parse(
                2, "LIVE | CHELSEA v E. GRAND RAPIDS | Thu 20 Aug 11:00 EDT (US)", now, roster,
            )
        )
        // A full club name still carries its cup tie.
        assertEquals(
            "Bundesliga",
            SportsParser.parse(
                3, "Next | SV Waldhof Mannheim vs. Bayern Munich | all | 20-08-2026 | 15:00 (GMT)",
                now, roster,
            )!!.league,
        )
    }

    /** ...but a shared nickname standing alone still is not a fixture. */
    @Test
    fun `an ambiguous nickname alone is still rejected`() {
        val now = ms(2026, 8, 20, 15, 0, "UTC")
        val roster = mapOf("NFL" to listOf("Cardinals", "Raiders"))
        assertNull(
            SportsParser.parse(
                2, "Next | Cardinals at Reds | all | 20-08-2026 | 15:00 (GMT)",
                now, roster, ambiguous = setOf("Cardinals"),
            )
        )
    }

    /** Motherwell v Freiburg was on four slots at once. */
    @Test
    fun `one row per match, on the best slot, the rest as fallbacks`() {
        val now = ms(2026, 8, 20, 15, 0, "UTC")
        val roster = mapOf("Bundesliga" to listOf("Union Berlin", "Bayern Munich"))
        fun slot(id: Int, tier: String) = SportsParser.parse(
            id, "Live | Union Berlin vs Bayern Munich | all | 20-08-2026 | 15:00 (GMT) | $tier",
            now, roster,
        )!!
        val hd = slot(1, "HD")
        val eightK = slot(2, "8K EXCLUSIVE")
        val fourK = slot(3, "4K")
        val rows = SportsParser.upcoming(listOf(hd, eightK, fourK), now, 60)
        assertEquals("one row, not three", 1, rows.size)
        assertEquals("the 8K slot leads", 2, rows[0].streamId)
        assertEquals("the others fall behind it, best first", listOf(3, 1), rows[0].alternates)
    }

    @Test
    fun `live fixtures outrank ones about to start`() {
        val now = ms(2026, 8, 20, 15, 0, "UTC")
        val running = SportsParser.parse(
            13, "Brighton vs. Arsenal | all | 20-08-2026 | 14:00 (GMT)", now, leagues,
        )!!
        val soon = SportsParser.parse(
            14, "Next | Jets vs. Steelers | all | 20-08-2026 | 15:30 (GMT)", now, leagues,
        )!!
        val order = SportsParser.upcoming(listOf(soon, running), now, 60).map { it.league }
        assertEquals(listOf("Premier League", "NFL"), order)
    }

    /**
     * The slot text is the provider's — shouting, with the matchday glued
     * on — and the roster already knows who these clubs are.
     */
    @Test
    fun `a recognised club is named the way the roster spells it`() {
        val now = ms(2026, 8, 22, 15, 0, "UTC")
        val roster = mapOf("La Liga" to listOf("Real Betis", "Real Sociedad"))
        val e = SportsParser.parse(
            1, "Live | REAL BETIS vs REAL SOCIEDAD (MATCHDAY 2) | all | 22-08-2026 | 15:00 (GMT)",
            now, roster,
        )!!
        assertEquals("Real Betis", e.home)
        assertEquals("Real Sociedad", e.away)
    }

    @Test
    fun `a club the roster does not carry is tidied out of capitals`() {
        assertEquals("SV Waldhof Mannheim", SportsParser.tidyCase("SV WALDHOF MANNHEIM"))
        assertEquals("Inter Miami CF", SportsParser.tidyCase("Inter Miami CF"))
    }

    /** Two slots for one match, one of them the Spanish call: one row. */
    @Test
    fun `another language's feed folds into the match instead of beside it`() {
        val now = ms(2026, 8, 22, 15, 0, "UTC")
        val roster = mapOf("La Liga" to listOf("Real Betis", "Real Sociedad"))
        val english = SportsParser.parse(
            1, "Live | REAL BETIS vs REAL SOCIEDAD | all | 22-08-2026 | 15:00 (GMT) | HD",
            now, roster,
        )!!
        val spanish = SportsParser.parse(
            2, "Live | EN ESPAÑOL-REAL BETIS vs REAL SOCIEDAD | all | 22-08-2026 | 15:00 (GMT) | 4K",
            now, roster,
        )!!
        assertEquals("Real Betis", spanish.home)
        assertTrue(spanish.languageFeed)
        val rows = SportsParser.upcoming(listOf(spanish, english), now, 60)
        assertEquals(1, rows.size)
        assertEquals("the plain call leads even at a lower tier", 1, rows[0].streamId)
        assertEquals(listOf(2), rows[0].alternates)
    }

    /** A roster carrying both spellings of a club must not make two matches. */
    @Test
    fun `a short roster alias folds into the club's full name`() {
        val now = ms(2026, 8, 22, 15, 0, "UTC")
        val roster = mapOf("Championship" to listOf("Ipswich", "Ipswich Town", "Sunderland"))
        val short = SportsParser.parse(
            1, "Live | Ipswich vs Sunderland | all | 22-08-2026 | 15:00 (GMT)", now, roster,
        )!!
        val long = SportsParser.parse(
            2, "Live | Ipswich Town vs Sunderland | all | 22-08-2026 | 15:00 (GMT) | 4K", now, roster,
        )!!
        assertEquals("Ipswich Town", short.home)
        assertEquals(1, SportsParser.upcoming(listOf(short, long), now, 60).size)
    }
}
