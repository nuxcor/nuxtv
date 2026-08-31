package com.agoro.tv

import com.agoro.tv.ui.screens.dayLabel
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The day pager's label used to be dated from the guide's scroll anchor —
 * "now, floored to the half hour, minus an hour" — so between midnight and 1am
 * it sat on yesterday and every dated chip read a day early. Offsets 0 and 1
 * are the words "Today" and "Tomorrow", which is why only the third day
 * forward ever showed it, and why it went unnoticed for so long.
 */
class GuideDayLabelTest {

    private val zone = TimeZone.getTimeZone("Europe/London")
    private var previousZone: TimeZone? = null
    private var previousLocale: Locale? = null

    // Locale as well as zone: dayLabel formats through Locale.getDefault(), so
    // "Fri 5 Jun" is "Fr. 5 Juni" on a German JVM and these assertions would be
    // green only on English CI.
    @Before
    fun fixClockAndLocale() {
        previousZone = TimeZone.getDefault()
        previousLocale = Locale.getDefault()
        TimeZone.setDefault(zone)
        Locale.setDefault(Locale.UK)
    }

    @After
    fun restoreClockAndLocale() {
        previousZone?.let { TimeZone.setDefault(it) }
        previousLocale?.let { Locale.setDefault(it) }
    }

    /** Midnight-plus-thirty on Wednesday 3 June 2026 — inside the bad hour. */
    private fun justAfterMidnight(): Long =
        Calendar.getInstance(zone, Locale.UK).apply {
            clear()
            set(2026, Calendar.JUNE, 3, 0, 30, 0)
        }.timeInMillis

    private fun midAfternoon(): Long =
        Calendar.getInstance(zone, Locale.UK).apply {
            clear()
            set(2026, Calendar.JUNE, 3, 15, 30, 0)
        }.timeInMillis

    @Test
    fun `the first two days are words, never dates`() {
        assertEquals("Today", dayLabel(0, justAfterMidnight()))
        assertEquals("Tomorrow", dayLabel(1, justAfterMidnight()))
    }

    @Test
    fun `two days on from half past midnight is the fifth, not the fourth`() {
        // The bug: the anchor was 23:30 on the 2nd, so +2 days landed on the
        // 4th — tomorrow's date on a chip two days out.
        assertEquals("Fri 5 Jun", dayLabel(2, justAfterMidnight()))
    }

    @Test
    fun `the afternoon reads the same as the small hours`() {
        // The hour of the day must not enter into it at all.
        assertEquals(dayLabel(2, midAfternoon()), dayLabel(2, justAfterMidnight()))
        assertEquals(dayLabel(6, midAfternoon()), dayLabel(6, justAfterMidnight()))
    }

    @Test
    fun `paging across a month boundary keeps counting`() {
        assertEquals("Wed 1 Jul", dayLabel(28, justAfterMidnight()))
    }
}
