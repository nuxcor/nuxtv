package com.agoro.tv

import com.agoro.tv.data.SportsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The Sport tab said "Nothing on right now" while LaLiga was being played.
 * Every slot here is copied verbatim from the panel at 18:44 UTC on
 * 2026-08-27, with Barcelona v Athletic Club under way.
 */
class LiveNowTest {

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: String): Long =
        Calendar.getInstance(TimeZone.getTimeZone(zone)).apply {
            clear(); set(y, mo - 1, d, h, mi)
        }.timeInMillis

    private val roster = mapOf(
        "La Liga" to listOf("Barcelona", "Athletic Club", "Athletic Bilbao", "Celta Vigo", "Osasuna"),
    )

    @Test
    fun `barcelona v athletic club is on screen while it is being played`() {
        val now = ms(2026, 8, 27, 18, 44, "UTC")
        val slots = listOf(
            1924693 to "LIVE | EN ESPAÑOL-FC BARCELONA VS. ATHLETIC CLUB (JORNADA 1) | " +
                "Thu 27 Aug 14:30 EDT (US) | 8K EXCLUSIVE | US: ESPN+ PPV 12",
            1924691 to "NEXT | FC BARCELONA VS. ATHLETIC CLUB (MATCHDAY #1) | " +
                "Thu 27 Aug 14:55 EDT (US) | 8K EXCLUSIVE | US: ESPN+ PPV 14",
            // The name the BOX is holding, not the one the panel serves now:
            // the catalogue caches for twelve hours and these slots are
            // rewritten through the day. This is the version that was on the
            // box while the tab said "Nothing on right now" — an hour and
            // three quarters out, on a fixture already being played.
            1939953 to "End | Barcelona vs. Athletic Club | all | 27-08-2026 | 20:30 (GMT) | " +
                "8K EXCLUSIVE | CA: SOCCER PPV 3",
            1611010 to "LaLiga: Barcelona vs. Athletic Club @ Aug 27 14:30 :TSN+  47",
        )
        val parsed = SportsParser.parseAll(slots, now, roster)
        println("parsed ${parsed.size}: " + parsed.joinToString { "${it.streamId}/${it.startMs}/live=${it.live}" })
        val rows = SportsParser.upcoming(parsed, now, 60)
        println("rows: " + rows.joinToString { "${it.streamId} ${it.title} live=${it.isLive(now)}" })
        assertTrue("at least one slot parsed", parsed.isNotEmpty())
        assertEquals("the fixture is one row on screen", 1, rows.size)
        assertTrue("and it reads as under way", rows[0].isLive(now))
        assertTrue(
            "on a slot that agrees the match has started, not the one two hours out",
            rows[0].streamId != 1939953,
        )
    }

    /**
     * The other direction, from the same panel earlier the same day: the NFL
     * preseason on a soccer shelf, seven hours before kick-off. Excluding the
     * mis-shelved slot has to keep solving this while solving the above.
     */
    @Test
    fun `a mis-shelved slot still cannot put a fixture on screen early`() {
        val now = ms(2026, 8, 27, 16, 15, "UTC")          // 11:15 in Dallas
        val nfl = mapOf("NFL" to listOf("Steelers", "Bills"))
        val slots = listOf(
            1940143 to "Next | Preseason: Steelers vs. Bills | all | 27-08-2026 | 16:00 (GMT) | " +
                "8K EXCLUSIVE | US: SOCCER PPV 14",
            606180 to "NFL  | 01 - 8/27 7pm Steelers at Bills",
        )
        val rows = SportsParser.upcoming(SportsParser.parseAll(slots, now, nfl), now, 60)
        assertEquals("nothing on screen seven hours early", 0, rows.size)
    }
}
