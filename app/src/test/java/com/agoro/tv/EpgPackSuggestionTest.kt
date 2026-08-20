package com.agoro.tv

import com.agoro.tv.ui.screens.suggestedEpgPacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgPackSuggestionTest {

    /** Category names taken from a real multi-region playlist. */
    private val realWorld = listOf(
        "US| NEWS", "US| LOCALS", "US| SPORT", "US| ENTERTAINMENT",
        "UK| SPORT", "UK| NEWS", "UK| ENTERTAINMENT",
        "DSTV", "SUPER SPORTS",
    )

    @Test
    fun `picks every region present in the category names`() {
        assertEquals(listOf("US", "UK", "ZA"), suggestedEpgPacks(realWorld))
    }

    @Test
    fun `country codes only match on word boundaries`() {
        // "in" inside "entertainment" must not suggest India, and "us" inside
        // "plus" must not suggest the United States — the whole reason the
        // match is anchored rather than a plain contains().
        assertTrue(suggestedEpgPacks(listOf("ENTERTAINMENT", "PLUS HD", "BONUS")).isEmpty())
    }

    @Test
    fun `matches full country names as well as codes`() {
        assertEquals(listOf("FR"), suggestedEpgPacks(listOf("Cinema France")))
        assertEquals(listOf("DE"), suggestedEpgPacks(listOf("German Sport")))
        assertEquals(listOf("ZA"), suggestedEpgPacks(listOf("South Africa | General")))
    }

    @Test
    fun `channel-name country tags rescue generic category names`() {
        val suggested = suggestedEpgPacks(
            categoryNames = listOf("Sports", "Movies", "Kids"),
            channelNames = listOf("US| CNN", "UK: BBC One", "Discovery"),
        )
        assertEquals(listOf("US", "UK"), suggested)
    }

    @Test
    fun `no country hints yields nothing so the caller can fall back`() {
        assertTrue(suggestedEpgPacks(listOf("Sports", "Movies", "Kids")).isEmpty())
        assertTrue(suggestedEpgPacks(emptyList()).isEmpty())
    }

    @Test
    fun `separators around the code do not matter`() {
        listOf("US| NEWS", "US - NEWS", "US:NEWS", "NEWS (US)", "US").forEach { name ->
            assertEquals("failed for '$name'", listOf("US"), suggestedEpgPacks(listOf(name)))
        }
    }
}
