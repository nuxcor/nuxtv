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

    /**
     * One recognised nickname is not a fixture. "Cardinals at Reds" is
     * baseball, and it read as NFL off its first word until both sides had to
     * be teams we carry.
     */
    @Test
    fun `one recognised side is not enough`() {
        val now = ms(2026, 8, 20, 15, 0, "UTC")
        val roster = mapOf("NFL" to listOf("Cardinals", "Raiders", "Texans"))
        assertNull(
            SportsParser.parse(
                1, "Next | Cardinals at Reds | all | 20-08-2026 | 15:00 (GMT)", now, roster,
            )
        )
        assertEquals(
            "NFL",
            SportsParser.parse(
                2, "Next | Raiders at Texans | all | 20-08-2026 | 15:00 (GMT)", now, roster,
            )!!.league,
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
}
