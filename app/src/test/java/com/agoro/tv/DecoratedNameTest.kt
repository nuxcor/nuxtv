package com.agoro.tv

import com.agoro.tv.data.Category
import com.agoro.tv.data.CategoryCleaner
import com.agoro.tv.data.ContentClassifier
import com.agoro.tv.data.EpgMatcher
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.data.QualityTag
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Superscript decoration — "ESPN ᵁᴴᴰ ³⁸⁴⁰ᴾ", "YAHOO SPORTS NETWORK ᴿᴬᵂ",
 * "### PEACOCK SERIE ORIGINAL ᴿᴬᵂ ⁶⁰ᶠᵖˢ ###". Unicode calls these glyphs
 * letters and numbers, so every ASCII cleanup rule used to walk past them
 * and they reached identity keys intact: three ESPNs, three guide lookups,
 * no merge. See TextNorm.
 */
class DecoratedNameTest {

    private fun channel(name: String, category: String? = "c") = LiveChannel(
        id = name, name = name, logo = null, url = name, categoryId = category,
        quality = QualityTag.of(name),
    )

    @Test
    fun `superscript decoration leaves the visible name`() {
        assertEquals("ESPN", channel("ESPN ᵁᴴᴰ ³⁸⁴⁰ᴾ").displayName)
        assertEquals("ESPN", channel("ESPN ᴴᴰ").displayName)
        assertEquals("YAHOO SPORTS NETWORK", channel("YAHOO SPORTS NETWORK ᴿᴬᵂ").displayName)
        assertEquals("SKY SPORTS F1", channel("SKY SPORTS F1 ᴿᴬᵂ ⁶⁰ᶠᵖˢ").displayName)
        assertEquals("NBA TV", channel("US| NBA TV ᵁᴴᴰ ³⁸⁴⁰ᴾ").displayName)
    }

    @Test
    fun `edge decoration leaves the visible name`() {
        assertEquals("PEACOCK SERIE ORIGINAL", channel("### PEACOCK SERIE ORIGINAL ᴿᴬᵂ ⁶⁰ᶠᵖˢ ###").displayName)
        assertEquals("Eurosport 1", channel("▎ Eurosport 1 ▎").displayName)
    }

    @Test
    fun `decorated and plain variants share one identity`() {
        val key = EpgMatcher.normalizeKey("ESPN HD")
        assertEquals(key, EpgMatcher.normalizeKey("ESPN ᴴᴰ"))
        assertEquals(key, EpgMatcher.normalizeKey("ESPN ᵁᴴᴰ ³⁸⁴⁰ᴾ"))
        assertEquals(key, EpgMatcher.normalizeKey("US| ESPN 1080p"))
    }

    @Test
    fun `superscript still advertises its tier`() {
        assertEquals("4K", QualityTag.of("ESPN ᵁᴴᴰ ³⁸⁴⁰ᴾ"))
        assertEquals("HD", QualityTag.of("ESPN ᴴᴰ"))
        assertEquals("4K", QualityTag.of("ESPN 3840P"))
    }

    @Test
    fun `duplicates collapse to the best variant`() {
        val merged = QualityTag.mergeBestQuality(
            listOf(channel("ESPN ᴴᴰ"), channel("ESPN ᵁᴴᴰ ³⁸⁴⁰ᴾ"), channel("US| ESPN SD")),
        )
        assertEquals(1, merged.size)
        assertEquals("4K", merged.single().quality)
    }

    @Test
    fun `shelf labels lose the decoration they already merged past`() {
        assertEquals(
            "Peacock Serie Original",
            CategoryCleaner.displayName("### PEACOCK SERIE ORIGINAL ᴿᴬᵂ ⁶⁰ᶠᵖˢ ###"),
        )
        val (kept, _) = CategoryCleaner.clean(
            com.agoro.tv.data.ContentBundle(
                liveCategories = listOf(
                    Category("a", "US| SPORTS ᵁᴴᴰ"),
                    Category("b", "US | SPORT HD"),
                ),
                channels = listOf(channel("ESPN", "a"), channel("Fox", "b")),
            )
        ).let { it.liveCategories to it.channels }
        assertEquals(1, kept.size)
    }

    @Test
    fun `plain names that merely contain the tokens survive`() {
        // "RAW" and "60fps" only mean decoration in superscript; a channel
        // genuinely named WWE Raw writes it in ASCII and must keep it.
        assertEquals("WWE RAW", channel("WWE RAW").displayName)
        assertEquals("Maximum Security", ContentClassifier.cleanTitle("Maximum Security"))
    }
}
