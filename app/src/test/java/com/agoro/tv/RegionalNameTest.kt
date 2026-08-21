package com.agoro.tv

import com.agoro.tv.data.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A UK network ships one feed per English region carrying the same schedule
 * apart from the local news bulletin. The pipeline folds them to one feed;
 * the row must not then announce which region that feed came from.
 */
class RegionalNameTest {

    private fun display(raw: String) = LiveChannel(
        id = "x", name = raw, logo = null, url = "http://x", categoryId = null,
    ).displayName

    @Test
    fun `a network's regional feed reads as the network`() {
        val cases = mapOf(
            "UK: BBC ONE LONDON 4K" to "BBC ONE",
            "UK: BBC ONE NORTH WEST" to "BBC ONE",
            "UK: BBC ONE EAST MIDLANDS" to "BBC ONE",
            "UK: BBC TWO ENGLAND" to "BBC TWO",
            "UK: ITV LONDON 4K" to "ITV",
            "UK: ITV GRANADA" to "ITV",
            "UK: ITV TYNE TEES" to "ITV",
            "UK: CHANNEL 4 LONDON" to "CHANNEL 4",
        )
        cases.forEach { (raw, want) -> assertEquals(raw, want, display(raw)) }
    }

    /**
     * A region word is a CHANNEL in its own right when it stands alone.
     * Stripping region words generally would rename these to nothing.
     */
    @Test
    fun `channels that are themselves regional keep their names`() {
        val cases = mapOf(
            "UK: BBC SCOTLAND HD" to "BBC SCOTLAND",
            "UK: BBC ALBA" to "BBC ALBA",
            "UK: ITV BE HD" to "ITV BE",
            "UK: ITV 2 HD" to "ITV 2",
            "UK: SKY SPORTS MAIN EVENT" to "SKY SPORTS MAIN EVENT",
            "UK: CHANNEL 5 HD" to "CHANNEL 5",
        )
        cases.forEach { (raw, want) -> assertEquals(raw, want, display(raw)) }
    }

    /** All of one network's regional feeds must reduce to a single name. */
    @Test
    fun `every regional feed of a network collapses to one name`() {
        val names = listOf(
            "UK: BBC ONE LONDON 4K", "UK: BBC ONE WALES HD",
            "UK: BBC ONE YORKSHIRE", "UK: BBC ONE SOUTH WEST",
        ).map(::display).toSet()
        assertEquals("all must read as one channel: $names", 1, names.size)
    }
}
