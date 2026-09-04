package com.agoro.tv

import com.agoro.tv.data.ScheduleFixture
import com.agoro.tv.data.SportsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // As the manifest bills them, which is how the packs' four spellings
        // ("New York City Football Club", "NEW YORK CITY", "New_York_City")
        // all reach the same fixture.
        "MLS" to listOf("Philadelphia Union", "Inter Miami",
            "New York City", "Nashville SC"),
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
    fun `one club spelled two ways is still one fixture`() {
        // The reported duplicate. Only ONE side has to match the roster for a
        // fixture to parse, and the side that does not keeps whatever the
        // provider typed — so a club the roster does not carry arrives spelled
        // two ways and split the match across two rows.
        // UTC: the slot's bracketed stamp carries no pack hint, so readStart
        // reads it as UTC and the window has to be asked in the same clock.
        val now = ms(2026, 8, 22, 4, 30, "UTC")
        // Only the home side is on the roster, which is all a fixture needs to
        // parse — so the away side reaches the row as the provider typed it.
        val roster = mapOf("MLS" to listOf("Inter Miami"))
        fun slot(id: Int, away: String) = SportsParser.parse(
            id,
            "MLS | $id Inter Miami v $away (2026-08-22 04:50:00)",
            now, roster,
        )!!
        val rows = SportsParser.upcoming(
            listOf(slot(20, "CF Montreal"), slot(21, "Montreal")),
            now, cueMinutes = 60,
        )
        assertEquals("one row", 1, rows.size)
        assertEquals("and the loser is reachable as a fallback", listOf(21), rows[0].alternates)
    }

    @Test
    fun `the affix strip never merges two different clubs`() {
        // The cost of being tolerant is folding two matches into one, which
        // takes a game OFF the screen — worse than a duplicate. Milan and
        // Inter Milan must stay apart.
        assertEquals("MILAN", SportsParser.sideKey("AC Milan"))
        assertEquals("INTER MILAN", SportsParser.sideKey("Inter Milan"))
        assertEquals("BARCELONA", SportsParser.sideKey("FC Barcelona"))
        assertEquals("BARCELONA", SportsParser.sideKey("Barcelona"))
        // Nothing survives the strip, so the whole name stands rather than an
        // empty key two different clubs could share.
        assertEquals("FC", SportsParser.sideKey("FC"))
    }

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
        // A bare "8K", not "8K EXCLUSIVE": the badge is what every slot in a
        // pack wears and carries no tier at all. See the pack-badge test.
        val eightK = slot(2, "8K")
        val fourK = slot(3, "4K")
        val rows = SportsParser.upcoming(listOf(hd, eightK, fourK), now, 60)
        assertEquals("one row, not three", 1, rows.size)
        assertEquals("the 8K slot leads", 2, rows[0].streamId)
        assertEquals("the others fall behind it, best first", listOf(3, 1), rows[0].alternates)
    }

    /**
     * The bracketed packs advertise no tier at all, so before this the
     * comparator ran out of keys and the winner was playlist order — which is
     * how a fixture carried by two packs opened on the thin one.
     */
    @Test
    fun `when neither slot advertises a tier, the thin pack goes last`() {
        val now = ms(2026, 8, 20, 15, 0, "UTC")
        val roster = mapOf("Bundesliga" to listOf("Union Berlin", "Bayern Munich"))
        fun slot(id: Int, pack: String) = SportsParser.parse(
            id, "$pack | Union Berlin vs Bayern Munich | all | 20-08-2026 | 15:00 (GMT)",
            now, roster,
        )!!
        // ESPN+ listed FIRST, which is exactly the order that used to win it.
        val espn = slot(1, "US (ESPN+ 100)")
        val other = slot(2, "US (STAN 04)")
        val rows = SportsParser.upcoming(listOf(espn, other), now, 60)
        assertEquals("one row, not two", 1, rows.size)
        assertEquals("the other pack leads", 2, rows[0].streamId)
        assertEquals("ESPN+ stays reachable as a fallback", listOf(1), rows[0].alternates)
    }

    @Test
    fun `an advertised tier still outranks the source`() {
        val now = ms(2026, 8, 20, 15, 0, "UTC")
        val roster = mapOf("Bundesliga" to listOf("Union Berlin", "Bayern Munich"))
        fun slot(id: Int, pack: String) = SportsParser.parse(
            id, "$pack | Union Berlin vs Bayern Munich | all | 20-08-2026 | 15:00 (GMT)",
            now, roster,
        )!!
        // The demotion is a tie-break and nothing more: a thin pack that says
        // 4K still beats a neutral one that says nothing, because a measured
        // picture is worth more than a verdict on the pipe carrying it.
        val espn4k = slot(1, "US (ESPN+ 100) | 4K")
        val other = slot(2, "US (STAN 04)")
        val rows = SportsParser.upcoming(listOf(other, espn4k), now, 60)
        assertEquals(1, rows.size)
        assertEquals("the 4K slot leads however thin its pack", 1, rows[0].streamId)
    }

    /**
     * The real shape of the packs, and the case the demotion was written for.
     * Both slots wear "8K EXCLUSIVE" because every slot in both packs does;
     * neither has said anything about its own picture.
     */
    @Test
    fun `the pack badge is not a tier, so the thin pack still goes last`() {
        val now = ms(2026, 8, 20, 15, 0, "UTC")
        val roster = mapOf("La Liga" to listOf("Rayo Vallecano", "Alaves"))
        fun slot(id: Int, pack: String) = SportsParser.parse(
            id,
            "Next | Rayo Vallecano vs. Alaves | all | 20-08-2026 | 15:00 (GMT) | " +
                "8K EXCLUSIVE | $pack",
            now, roster,
        )!!
        // ESPN+ first, which is the order the playlist ships them in.
        val espn = slot(1, "US: ESPN+ PPV 19")
        val other = slot(2, "CA: SOCCER PPV 5")
        val rows = SportsParser.upcoming(listOf(espn, other), now, 60)
        assertEquals("one row, not two", 1, rows.size)
        assertEquals("the neutral pack leads", 2, rows[0].streamId)
        assertEquals("ESPN+ stays reachable as a fallback", listOf(1), rows[0].alternates)
    }

    @Test
    fun `a real 8K claim is still read as one`() {
        val now = ms(2026, 8, 20, 15, 0, "UTC")
        val roster = mapOf("La Liga" to listOf("Rayo Vallecano", "Alaves"))
        fun tier(badge: String) = SportsParser.parse(
            9, "Rayo Vallecano vs Alaves $badge | all | 20-08-2026 | 15:00 (GMT)", now, roster,
        )!!.tierRank
        // The badge is two words together. "8K" alone is a slot saying
        // something about itself, and it keeps its tier.
        assertEquals("a bare 8K still means 8K", 0, tier("| 8K"))
        assertEquals("and 4K still means 4K", 1, tier("| 4K"))
        assertEquals(
            "the badge alone leaves the slot with no claim",
            com.agoro.tv.data.SportsEvent.TIER_UNKNOWN,
            tier("| 8K EXCLUSIVE | US: ESPN+ PPV 19"),
        )
        assertEquals(
            "a badged slot that ALSO says 4K keeps the 4K",
            1,
            tier("| 4K | 8K EXCLUSIVE | CA: SOCCER PPV 5"),
        )
    }

    /**
     * The real pair, copied from the panel on 2026-08-27. Same fixture on two
     * shelves, seven hours apart, and the soccer shelf was winning — so a
     * 6pm kick-off was reported live at 11am.
     */
    @Test
    fun `a pack shelved under the wrong sport does not set the kick-off`() {
        val now = ms(2026, 8, 27, 16, 15, "UTC")          // 11:15 in Dallas
        val roster = mapOf("NFL" to listOf("Steelers", "Bills"))
        val soccerShelf = SportsParser.parse(
            1,
            "Next | Preseason: Steelers vs. Bills | all | 27-08-2026 | 16:00 (GMT) | " +
                "8K EXCLUSIVE | US: SOCCER PPV 14",
            now, roster,
        )!!
        val nflShelf = SportsParser.parse(2, "NFL  | 01 - 8/27 7pm Steelers at Bills", now, roster)!!
        assertTrue("the soccer shelf is flagged", soccerShelf.wrongSport)
        assertTrue("the NFL shelf is not", !nflShelf.wrongSport)
        // Soccer shelf FIRST, which is the order that used to win it.
        val slots = listOf(soccerShelf, nflShelf)
        // At 11:15 in Dallas the game is nearly seven hours off, so the right
        // answer is no row at all. It used to show, and to say LIVE.
        assertEquals(
            "nothing on screen seven hours early",
            0, SportsParser.upcoming(slots, now, 60).size,
        )
        // And it appears on the NFL shelf's clock, not the soccer shelf's:
        // half an hour before a 7pm Eastern kick-off.
        val later = ms(2026, 8, 27, 18, 30, "America/New_York")
        val rows = SportsParser.upcoming(slots, later, 60)
        assertEquals("one row, not two", 1, rows.size)
        assertEquals("the NFL shelf leads", 2, rows[0].streamId)
        assertEquals(
            "and the row carries ITS kick-off — 7pm Eastern, not 16:00 GMT",
            ms(2026, 8, 27, 19, 0, "America/New_York"), rows[0].startMs,
        )
        assertTrue("not yet under way", !rows[0].isLive(later))
        assertEquals("the soccer shelf stays reachable", listOf(1), rows[0].alternates)
    }

    /** Copied from the panel: the whip-around names two clubs like a fixture. */
    @Test
    fun `the goal round-up does not take the match's row`() {
        val now = ms(2026, 8, 30, 12, 30, "UTC")
        val roster = mapOf("Premier League" to listOf("Chelsea", "Brighton"))
        val rush = SportsParser.parse(
            1,
            "AU (STAN 59) | Goal Rush: Chelsea v Brighton & Hove Albion • 30 August  " +
                "Premier League 2026/27 (2026-08-30 22:00:34)",
            now, roster,
        )!!
        val match = SportsParser.parse(
            2,
            "AU (STAN 62) | Chelsea v Brighton & Hove Albion  Premier League Matchweek 2 " +
                "2026/2027 (2026-08-30 22:50:29)",
            now, roster,
        )!!
        assertTrue("the round-up is a side feed", rush.sideFeed)
        val rows = SportsParser.upcoming(listOf(rush, match), now, 60)
        assertEquals(1, rows.size)
        assertEquals("the match leads", 2, rows[0].streamId)
    }

    @Test
    fun `a slot naming no sport, or naming the right one, is untouched`() {
        val now = ms(2026, 8, 20, 15, 0, "UTC")
        val roster = mapOf("Premier League" to listOf("Brighton", "Arsenal"))
        // SOCCER on a soccer fixture is not a contradiction.
        val soccer = SportsParser.parse(
            1, "Next | Brighton vs. Arsenal | all | 20-08-2026 | 15:00 (GMT) | US: SOCCER PPV 3",
            now, roster,
        )!!
        assertTrue("soccer shelf, soccer league", !soccer.wrongSport)
        // And a slot that names no sport at all says nothing either way.
        val bare = SportsParser.parse(
            2, "Next | Brighton vs. Arsenal | all | 20-08-2026 | 15:00 (GMT)", now, roster,
        )!!
        assertTrue(!bare.wrongSport)
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

    // --- the listings packs' clock, and what a row does without one ---------

    /**
     * ESPN+, FIFA+, NFHS, MAX and Sportsnet all write the kick-off this way,
     * and 320 slots on this panel do. Every one of them used to reach the row
     * with no clock at all — see the blank-screen note on [SportsParser].
     */
    @Test
    fun `reads the listings packs' weekday clock`() {
        val now = ms(2026, 8, 27, 14, 0, "America/New_York")
        val roster = mapOf("La Liga" to listOf("Celta Vigo", "Osasuna", "Barcelona"))
        val e = SportsParser.parse(
            1,
            "LIVE | CELTA VIGO VS. OSASUNA (MATCHDAY #1) | Thu 27 Aug 14:20 EDT (US) | " +
                "8K EXCLUSIVE | US: ESPN+ PPV 33",
            now, roster,
        )!!
        assertEquals(ms(2026, 8, 27, 14, 20, "America/New_York"), e.startMs)
        assertTrue("kick-off is twenty minutes away", !e.isLive(now))
    }

    /** The zone token is believed over the pack prefix that would default it. */
    @Test
    fun `the zone abbreviation decides the kick-off`() {
        val now = ms(2026, 8, 27, 14, 0, "UTC")
        val roster = mapOf("Premier League" to listOf("Arsenal", "Chelsea"))
        val eastern = SportsParser.parse(
            1, "LIVE | ARSENAL VS. CHELSEA | Thu 27 Aug 15:00 EDT (US) | US: ESPN+ PPV 1",
            now, roster,
        )!!
        val british = SportsParser.parse(
            2, "LIVE | ARSENAL VS. CHELSEA | Thu 27 Aug 15:00 BST (UK) | UK: SKY PPV 1",
            now, roster,
        )!!
        assertEquals(ms(2026, 8, 27, 15, 0, "America/New_York"), eastern.startMs)
        assertEquals(ms(2026, 8, 27, 15, 0, "Europe/London"), british.startMs)
    }

    /** "GMT" means GMT in August, not Europe/London, which is BST by then. */
    @Test
    fun `GMT is not read as London in summer`() {
        val now = ms(2026, 8, 27, 14, 0, "UTC")
        val roster = mapOf("Premier League" to listOf("Arsenal", "Chelsea"))
        val e = SportsParser.parse(
            1, "LIVE | ARSENAL VS. CHELSEA | Thu 27 Aug 15:00 GMT | UK: SKY PPV 1", now, roster,
        )!!
        assertEquals(ms(2026, 8, 27, 15, 0, "UTC"), e.startMs)
    }

    /**
     * The best FEED need not be the slot that knows when the match is. This is
     * Barcelona v Athletic Club as the panel carried it: the winner said only
     * "Live", a sibling said 14:30, and the row wore a LIVE badge half an hour
     * before kick-off on a pipe that was not carrying yet.
     */
    @Test
    fun `a row with no clock borrows one from a slot that has it`() {
        val now = ms(2026, 8, 27, 14, 0, "America/New_York")
        val roster = mapOf("La Liga" to listOf("Barcelona", "Athletic Club"))
        val timeless = SportsParser.parse(
            1, "Live | Barcelona vs. Athletic Club | all | 8K EXCLUSIVE | CA: SOCCER PPV 3",
            now, roster,
        )!!
        val timed = SportsParser.parse(
            2, "LaLiga: Barcelona vs. Athletic Club @ Aug 27 14:30 :TSN+  47", now, roster,
        )!!
        assertNull("the winner brought no clock of its own", timeless.startMs)
        val rows = SportsParser.upcoming(listOf(timeless, timed), now, 60)
        assertEquals(1, rows.size)
        assertEquals("the better feed still opens", 1, rows[0].streamId)
        assertEquals(ms(2026, 8, 27, 14, 30, "America/New_York"), rows[0].startMs)
        assertTrue("half an hour out is not live", !rows[0].isLive(now))
        assertEquals(listOf(2), rows[0].alternates)
    }

    /**
     * A bare "LIVE" is only as true as the fetch that read it. These slots are
     * pipes and the provider renames them for the next event, so an old
     * snapshot's word that something is on is worth nothing.
     */
    @Test
    fun `a clockless row is dropped once its snapshot is stale`() {
        val now = ms(2026, 8, 27, 14, 0, "America/New_York")
        val roster = mapOf("La Liga" to listOf("Barcelona", "Athletic Club"))
        val timeless = SportsParser.parse(
            1, "Live | Barcelona vs. Athletic Club | all | 8K EXCLUSIVE | CA: SOCCER PPV 3",
            now, roster,
        )!!
        val fresh = SportsParser.upcoming(listOf(timeless), now, 60, 10 * 60_000L)
        assertEquals("a fresh snapshot is believed", 1, fresh.size)
        val stale = SportsParser.upcoming(listOf(timeless), now, 60, 6L * 60 * 60 * 1000)
        assertTrue("a six-hour-old claim is not", stale.isEmpty())
    }

    /** A row that HAS a kick-off ages on its own clock, whatever the snapshot. */
    @Test
    fun `a stale snapshot does not drop a fixture that knows its kick-off`() {
        val now = ms(2026, 8, 27, 14, 0, "America/New_York")
        val roster = mapOf("La Liga" to listOf("Barcelona", "Athletic Club"))
        val timed = SportsParser.parse(
            2, "LaLiga: Barcelona vs. Athletic Club @ Aug 27 14:30 :TSN+  47", now, roster,
        )!!
        assertEquals(
            1, SportsParser.upcoming(listOf(timed), now, 60, 6L * 60 * 60 * 1000).size,
        )
    }

    /**
     * What the player puts up when the ladder steps onto an alternate. The
     * alternates of a fixture are different slots, so the title has to move
     * with them; see PlayerSession.swapSource.
     */
    @Test
    fun `an alternate feed says what it is`() {
        assertEquals(
            "Spanish commentary",
            SportsParser.feedNote("LIVE | EN ESPAÑOL-FC BARCELONA VS. ATHLETIC CLUB (JORNADA 1)"),
        )
        assertEquals(
            "studio feed",
            SportsParser.feedNote("Studio Coverage: Chelsea v Brighton"),
        )
        assertNull(
            "another pack's feed of the same match needs no note",
            SportsParser.feedNote("LaLiga: Barcelona vs. Athletic Club @ Aug 27 14:30 :TSN+  47"),
        )
    }

    /**
     * The borrow has to reach a sibling the WINDOW would have dropped, which
     * is the case that matters most: the silent slot admits itself on its bare
     * "LIVE" while the slot that knows the kick-off is six hours out and never
     * reaches the fold at all.
     */
    @Test
    fun `a clockless slot borrows from a sibling outside the window`() {
        val now = ms(2026, 8, 27, 14, 0, "America/New_York")
        val roster = mapOf("La Liga" to listOf("Barcelona", "Athletic Club"))
        val timeless = SportsParser.parse(
            1, "Live | Barcelona vs. Athletic Club | all | 8K EXCLUSIVE | CA: SOCCER PPV 3",
            now, roster,
        )!!
        val tonight = SportsParser.parse(
            2, "LaLiga: Barcelona vs. Athletic Club @ Aug 27 20:30 :TSN+  47", now, roster,
        )!!
        assertNull(timeless.startMs)
        val rows = SportsParser.upcoming(listOf(timeless, tonight), now, 60)
        assertTrue("six and a half hours early is not on now", rows.isEmpty())
    }

    /** And it still appears once the cue comes round. */
    @Test
    fun `the borrowed clock lets the fixture in at the cue`() {
        val now = ms(2026, 8, 27, 20, 0, "America/New_York")
        val roster = mapOf("La Liga" to listOf("Barcelona", "Athletic Club"))
        val timeless = SportsParser.parse(
            1, "Live | Barcelona vs. Athletic Club | all | 8K EXCLUSIVE | CA: SOCCER PPV 3",
            now, roster,
        )!!
        val tonight = SportsParser.parse(
            2, "LaLiga: Barcelona vs. Athletic Club @ Aug 27 20:30 :TSN+  47", now, roster,
        )!!
        val rows = SportsParser.upcoming(listOf(timeless, tonight), now, 60)
        assertEquals(1, rows.size)
        assertEquals("the better feed still opens", 1, rows[0].streamId)
        assertEquals(ms(2026, 8, 27, 20, 30, "America/New_York"), rows[0].startMs)
    }

    /**
     * Some of these names carry a 12-hour clock, and the meridiem has to be
     * read before the zone token or "pm" is swallowed as an unknown zone and
     * the kick-off lands twelve hours early.
     */
    @Test
    fun `the weekday clock reads a meridiem, not just 24-hour`() {
        val now = ms(2026, 2, 3, 12, 0, "UTC")
        val roster = mapOf("Premier League" to listOf("Arsenal", "Chelsea"))
        val e = SportsParser.parse(
            1, "Arsenal vs Chelsea // UK Sat 3 Feb 3:30pm", now, roster,
        )!!
        assertEquals(ms(2026, 2, 3, 15, 30, "UTC"), e.startMs)
    }

    /** Newfoundland is the half-hour offset no fallback would ever guess. */
    @Test
    fun `the Canadian packs' half-hour zone is read`() {
        val now = ms(2026, 8, 27, 16, 0, "UTC")
        val roster = mapOf("Premier League" to listOf("Arsenal", "Chelsea"))
        val e = SportsParser.parse(
            1, "NEXT | ARSENAL VS. CHELSEA | Thu 27 Aug 16:20 NDT (CA) | CA: SPORTSNET PPV 4",
            now, roster,
        )!!
        assertEquals(ms(2026, 8, 27, 16, 20, "America/St_Johns"), e.startMs)
    }

    // ---------------------------------------------------------------- the
    // wrong badge. Every name below is a real slot off the provider's panel.

    /**
     * The rosters as the manifest carries them: US nicknames, English clubs
     * under both the name a pack writes and the one a viewer reads.
     */
    private val realRoster = mapOf(
        "NFL" to listOf("Falcons", "Patriots", "Browns", "Colts"),
        "NBA" to listOf("Spurs", "Lakers", "Warriors"),
        "MLS" to listOf("Los Angeles FC", "Sporting Kansas City"),
        "Premier League" to listOf(
            "Brighton", "Hove Albion", "Aston Villa", "Newcastle", "Newcastle United",
            "Spurs", "Tottenham", "Tottenham Hotspur",
        ),
    )

    private val realAliases = mapOf(
        "Premier League|Spurs" to "Tottenham Hotspur",
        "Premier League|Tottenham" to "Tottenham Hotspur",
        "Premier League|Hove Albion" to "Brighton",
        "Premier League|Newcastle" to "Newcastle United",
    )

    /**
     * The report this whole section exists for: a CRICKET match shown as an
     * NFL fixture, wearing the Atlanta Falcons and New England Patriots
     * badges, because both Caribbean sides end on a word the NFL uses.
     */
    @Test
    fun `a Caribbean Premier League cricket match is not an NFL fixture`() {
        val now = ms(2026, 8, 20, 16, 30, "UTC")
        assertNull(
            SportsParser.parse(
                1,
                "Next | Caribbean Premier League 2026 - 12th Match - Antigua And Barbuda " +
                    "Falcons vs St Kitts And Nevis Patriots | all | 20-08-2026 | 16:00 (GMT) " +
                    "| 8K EXCLUSIVE | US: SOCCER PPV 57",
                now, realRoster, aliases = realAliases,
            )
        )
    }

    /**
     * The same fault with no competition named and BOTH sides landing in the
     * same roster, so nothing but the padding itself can reject it.
     */
    @Test
    fun `a nickname padded past recognition claims nothing`() {
        val now = ms(2026, 8, 20, 16, 30, "UTC")
        assertNull(
            SportsParser.parse(
                1, "St Kitts And Nevis Patriots vs Antigua And Barbuda Falcons @ Aug 20 4:00 PM",
                now, realRoster, aliases = realAliases,
            )
        )
    }

    /**
     * And a competition we do not carry whose sides are billed as bare
     * nicknames, so the padding rule has nothing to object to and only the
     * competition itself can reject it. The listings packs write the sport
     * this plainly all the time.
     */
    @Test
    fun `a competition we do not carry is rejected on its own name`() {
        val now = ms(2026, 8, 20, 16, 30, "UTC")
        assertNull(
            SportsParser.parse(
                1, "US (ESPN+ 100) | Cricket: Falcons vs. Patriots (2026-08-20 16:00:00)",
                now, realRoster, aliases = realAliases,
            )
        )
    }

    /** A club's own city is not surplus: the NFL packs write both forms. */
    @Test
    fun `a nickname with its city still claims the club`() {
        val now = ms(2026, 8, 20, 20, 30, "UTC")
        val e = SportsParser.parse(
            1, "New England Patriots vs Cleveland Browns @ Aug 20 4:00 PM", now, realRoster,
            aliases = realAliases,
        )!!
        assertEquals("NFL", e.league)
        assertEquals("Patriots", e.home)
        assertEquals("Browns", e.away)
    }

    /**
     * "Spurs" is San Antonio in the NBA roster and Tottenham in the Premier
     * League one, and the index is longest-name-first with no opinion about
     * which sport a row is — so an English league match was filed under the
     * NBA. The competition that fields BOTH clubs is the one being played.
     */
    @Test
    fun `the league that carries both sides wins`() {
        val now = ms(2026, 8, 29, 13, 0, "UTC")
        val e = SportsParser.parse(
            1, "US (Peacock 013) |  Spurs v. Newcastle (2026-08-29 12:30:00)",
            now, realRoster, aliases = realAliases,
        )!!
        assertEquals("Premier League", e.league)
        assertEquals("Tottenham Hotspur", e.home)
        assertEquals("Newcastle United", e.away)
    }

    /** Two sides from two sports is a collision, not a fixture. */
    @Test
    fun `a football club does not play a basketball team`() {
        val now = ms(2026, 8, 29, 13, 0, "UTC")
        assertNull(
            SportsParser.parse(
                1, "US (Peacock 013) |  Lakers v. Aston Villa (2026-08-29 12:30:00)",
                now, realRoster, aliases = realAliases,
            )
        )
    }

    /**
     * The away side arrives with a halved timestamp on it, and in this pack the
     * word before the stray digit is a MONTH. Requiring a letter before the
     * marker was not enough — "Aug" is letters — so every 2 o'clock fixture in
     * this format was dropped as a reserve team.
     */
    @Test
    fun `a two o'clock kick-off is not a reserve side`() {
        val now = ms(2026, 8, 23, 14, 0, "UTC")
        val e = SportsParser.parse(
            1, "Brighton v Aston Villa // UK Sun 23 Aug 2:55pm // ET Sun 23 Aug 9:55am",
            now, realRoster, aliases = realAliases,
        )
        assertEquals("Brighton", e?.home)
    }

    /** And the shape the letter rule already handled stays handled. */
    @Test
    fun `a halved timestamp after a day number is not a reserve side either`() {
        assertFalse(SportsParser.isReserveSide("Luton Town @ Aug 27 2"))
        assertFalse(SportsParser.isReserveSide("Club B // UK Sun 23 Aug 2"))
        assertTrue(SportsParser.isReserveSide("Los Angeles FC 2"))
        assertTrue(SportsParser.isReserveSide("Sporting Kansas City II"))
    }

    /** MLS Next Pro. Both sides matched their senior club. */
    @Test
    fun `a reserve fixture is not the first team's`() {
        val now = ms(2026, 8, 20, 19, 30, "UTC")
        assertNull(
            SportsParser.parse(
                1, "Next | Los Angeles FC 2 vs. Sporting Kansas City II | all | " +
                    "20-08-2026 | 19:00 (GMT) | 8K EXCLUSIVE | US: SOCCER PPV 74",
                now, realRoster, aliases = realAliases,
            )
        )
    }

    /**
     * The roster derived "Hove Albion" from listings that write "Brighton &
     * Hove Albion" — the pattern that read them stopped at the ampersand — and
     * that half-name reached the screen as the home side. It also split one
     * match across two rows, because the pack that writes "Brighton" keyed
     * differently from the pack that writes the full name.
     */
    @Test
    fun `a club is shown by its own name, not the tail of it`() {
        val now = ms(2026, 8, 23, 23, 0, "UTC")
        val e = SportsParser.parse(
            1, "AU (STAN 55) | Brighton & Hove Albion v Aston Villa  Premier League " +
                "Matchweek 1 2026/2027 (2026-08-23 22:50:34)",
            now, realRoster, aliases = realAliases,
        )!!
        assertEquals("Brighton", e.home)
    }

    /** An alias is a club's, not a nickname's: San Antonio keeps its name. */
    @Test
    fun `an alias does not reach across leagues`() {
        val now = ms(2026, 8, 29, 13, 0, "UTC")
        val e = SportsParser.parse(
            1, "US (Peacock 013) |  Spurs v. Lakers (2026-08-29 12:30:00)",
            now, realRoster, aliases = realAliases,
        )!!
        assertEquals("NBA", e.league)
        assertEquals("Spurs", e.home)
    }

    // --------------------------------------------------------------- crests

    private val klunn = "https://raw.githubusercontent.com/klunn91/team-logos/master"

    private val crests = mapOf(
        "Patriots" to "$klunn/NFL/patriots.png",
        // What the index actually held before crest_match.py was scoped: the US
        // pool was built from klunn91's whole tree, MLB sorts before NFL, and
        // the NFL roster's "Giants" resolved to the San Francisco baseball club.
        "Giants" to "$klunn/MLB/giants.png",
        "Spurs" to "https://raw.githubusercontent.com/luukhopman/football-logos/master/" +
            "history/2021-22/England%20-%20Premier%20League/Spurs.png",
    )

    /**
     * The index is keyed by club name and nothing else, so a badge could
     * always cross sports. The crest's own source is the check: klunn91 files
     * under NFL/ and NBA/, luukhopman is football.
     */
    @Test
    fun `a football row cannot wear an NFL badge`() {
        assertNull(SportsParser.crestFor(crests, "Premier League", "Patriots"))
        assertNull(SportsParser.crestFor(crests, "NBA", "Spurs"))
        assertEquals(crests["Patriots"], SportsParser.crestFor(crests, "NFL", "Patriots"))
        assertEquals(crests["Spurs"], SportsParser.crestFor(crests, "Premier League", "Spurs"))
    }

    /** A rebuilt manifest scopes the key by sport; that wins outright. */
    @Test
    fun `a sport-scoped crest key is preferred`() {
        val scoped = crests + mapOf("basketball|Spurs" to "https://example.test/nba-spurs.png")
        assertEquals("https://example.test/nba-spurs.png", SportsParser.crestFor(scoped, "NBA", "Spurs"))
    }

    /**
     * The check on the crest's own source has to be CLOSED. klunn91 names the
     * sport in the folder, and treating an unrecognised folder as "says
     * nothing, so trust it" is how an NFL fixture wore a baseball badge — the
     * exact fault the guard was added to stop, surviving it.
     */
    @Test
    fun `a folder this app does not carry is refused, not trusted`() {
        assertNull(SportsParser.crestFor(crests, "NFL", "Giants"))
        assertNull(SportsParser.crestFor(crests, "Premier League", "Giants"))
    }

    /** A competition with no sport asks for no scoped key. */
    @Test
    fun `an unknown competition does not invent a key`() {
        val trap = crests + mapOf("null|Patriots" to "https://example.test/wrong.png")
        assertEquals(crests["Patriots"], SportsParser.crestFor(trap, "Kabaddi League", "Patriots"))
    }

    /** A cup row is football too, or the guard above has nothing to compare. */
    @Test
    fun `the cup competitions are football`() {
        assertNull(SportsParser.crestFor(crests, "Carabao Cup", "Patriots"))
        assertNull(SportsParser.crestFor(crests, "UEFA", "Patriots"))
    }

    /**
     * Four real slots for one match, three clocks between them. Reported from
     * the sofa: "nycfc vs nashville says starts in 7 minutes but the game
     * itself is 6:30 central".
     */
    @Test
    fun `the packs that agree set the kick-off`() {
        val now = ms(2026, 9, 4, 11, 20, "America/Chicago")   // when it was reported
        val kickOff = ms(2026, 9, 4, 18, 30, "America/Chicago")
        val slots = listOf(
            601 to "Next | New York City Football Club vs. Nashville SC | all | " +
                "04-09-2026 | 16:30 (GMT) | 8K EXCLUSIVE | US: SOCCER PPV 68",
            602 to "NEXT | NEW YORK CITY VS. NASHVILLE | Fri 04 Sep 19:30 EDT (US) | " +
                "8K EXCLUSIVE | US: MLS PPV 1",
            603 to "New York City Football Club vs Nashville SC @ Sep 4 7:30 PM :MLS  01",
            604 to "(Apple) (MLS) 001 |  New_York_City vs. Nashville  (2026-09-04 19:25:00)",
        )
        val parsed = SportsParser.parseAll(slots, now, leagues)
        assertEquals("all four slots read as the same fixture", 4, parsed.size)

        // Nothing on screen at 11:20: the soccer shelf's 16:30 (GMT) is seven
        // hours early and no longer speaks for the match on its own.
        assertTrue(
            "no row seven hours early",
            SportsParser.upcoming(parsed, now, cueMinutes = 60).isEmpty(),
        )

        // And it IS there half an hour before the real kick-off.
        val atCue = kickOff - 30 * 60_000L
        val rows = SportsParser.upcoming(
            SportsParser.parseAll(slots, atCue, leagues), atCue, cueMinutes = 60,
        )
        assertEquals("one row, not four", 1, rows.size)
        assertEquals("on the clock the two agreeing packs gave", kickOff, rows.first().startMs)
    }


    /**
     * Reported as "there is a bundesliga hoffenheim vs bayern but it's the
     * women". Four real slots: two bill the competition and two write "all",
     * and the two that tell the truth were the ones being thrown away — DAZN
     * dates ISO where the soccer shelf dates day-first, and only day-first
     * was read.
     */
    @Test
    fun `a womens fixture does not reach the screen as the mens`() {
        val now = ms(2026, 9, 4, 12, 0, "UTC")
        val slots = listOf(
            801 to "Next | Hoffenheim vs. Leverkusen | all | 04-09-2026 | 09:19 (GMT) | " +
                "8K EXCLUSIVE | US: SOCCER PPV 12",
            802 to "Next | Hoffenheim vs. Leverkusen | Frauen Bundesliga | 2026-09-04 | " +
                "16:30 (GMT) | 8K EXCLUSIVE | US: DAZN PPV 3",
            803 to "Next | Hoffenheim vs. Leverkusen | all | 04-09-2026 | 18:19 (GMT) | " +
                "8K EXCLUSIVE | CA: SOCCER PPV 12",
            804 to "Next | Hoffenheim vs. Leverkusen | Frauen Bundesliga | 2026-09-04 | " +
                "16:30 (GMT) | 8K EXCLUSIVE | UK: DAZN PPV 3",
        )
        val parsed = SportsParser.parseAll(slots, now, mapOf(
            "Bundesliga" to listOf("Hoffenheim", "Leverkusen"),
        ))
        assertTrue("the men's fixture is not ours to show", parsed.isEmpty())
    }

    /** The ISO half of the pipe pack is read now, and reads as one kick-off. */
    @Test
    fun `the pipe pack dates both ways round`() {
        val now = ms(2026, 9, 4, 12, 0, "UTC")
        val iso = SportsParser.parse(
            1, "Next | Brighton vs. Arsenal | Premier League | 2026-09-04 | 16:30 (GMT) | 8K",
            now, leagues,
        )!!
        val dmy = SportsParser.parse(
            2, "Next | Brighton vs. Arsenal | Premier League | 04-09-2026 | 16:30 (GMT) | 8K",
            now, leagues,
        )!!
        assertEquals(ms(2026, 9, 4, 16, 30, "UTC"), iso.startMs)
        assertEquals("both spellings, one kick-off", dmy.startMs, iso.startMs)
    }


    /**
     * The schedule settles what four packs could not. Fixture and slot text
     * are both real: ESPN had "Nashville SC at New York City FC" at
     * 2026-09-04T23:30Z while the soccer shelf billed 16:30 (GMT).
     */
    @Test
    fun `the schedule overrules the slot's clock`() {
        val now = ms(2026, 9, 4, 12, 20, "America/Chicago")
        val slot = SportsParser.parse(
            1, "Next | New York City Football Club vs. Nashville SC | all | " +
                "04-09-2026 | 16:30 (GMT) | 8K EXCLUSIVE | US: SOCCER PPV 68",
            now, mapOf("MLS" to listOf("New York City", "Nashville SC")),
        )!!
        assertEquals("the slot's own clock", ms(2026, 9, 4, 16, 30, "UTC"), slot.startMs)

        val fixtures = listOf(ScheduleFixture(
            league = "MLS", home = "New York City FC", away = "Nashville SC",
            start = "2026-09-04T23:30Z",
        ))
        val fixed = SportsParser.applySchedule(listOf(slot), fixtures, now).single()
        assertEquals(ms(2026, 9, 4, 23, 30, "UTC"), fixed.startMs)
        assertEquals("New York City FC", fixed.home)
        assertFalse("not live seven hours early", fixed.live)
    }

    /** Two spellings of one club become one row, which is the point of it. */
    @Test
    fun `the schedule gives both spellings one identity`() {
        val now = ms(2026, 9, 4, 12, 0, "UTC")
        val leagues = mapOf("Bundesliga" to listOf("Bielefeld", "St. Pauli"))
        val slots = listOf(
            1 to "Next | Bielefeld vs. St. Pauli | all | 04-09-2026 | 16:30 (GMT) | US: SOCCER PPV 1",
            2 to "Next | DSC Arminia Bielefeld vs. St. Pauli | all | 04-09-2026 | 18:20 (GMT) | CA: SOCCER PPV 1",
        )
        val parsed = SportsParser.parseAll(slots, now, leagues)
        assertEquals("two slots, two fixtures as far as the names go", 2, parsed.size)

        val fixtures = listOf(ScheduleFixture(
            league = "Bundesliga", home = "Arminia Bielefeld", away = "St. Pauli",
            start = "2026-09-04T18:30Z",
        ))
        val fixed = SportsParser.applySchedule(parsed, fixtures, now)
        assertEquals(1, fixed.map { it.home to it.away }.toSet().size)
        assertEquals(1, SportsParser.upcoming(fixed, ms(2026, 9, 4, 18, 0, "UTC"), 60).size)
    }

    /** A club the schedule does not carry is left exactly as it came. */
    @Test
    fun `an unmatched fixture is untouched`() {
        val now = ms(2026, 9, 4, 12, 0, "UTC")
        val slot = SportsParser.parse(
            1, "NFL  | 01 - 9/4 8pm Raiders at Texans", now, leagues,
        )!!
        val fixtures = listOf(ScheduleFixture(
            league = "MLS", home = "New York City FC", away = "Nashville SC",
            start = "2026-09-04T23:30Z",
        ))
        assertEquals(slot, SportsParser.applySchedule(listOf(slot), fixtures, now).single())
    }

}
