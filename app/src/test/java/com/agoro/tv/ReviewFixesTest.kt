package com.agoro.tv

import com.agoro.tv.data.ContentClassifier
import com.agoro.tv.data.EpgMatcher
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.data.QualityTag
import com.agoro.tv.data.TextNorm
import com.agoro.tv.ui.screens.CATEGORY_ALL
import com.agoro.tv.ui.screens.channelsInCategory
import org.junit.Assert.assertEquals
import org.junit.Test

/** Regressions caught in review of the decoration/dedup/guide-bar change. */
class ReviewFixesTest {

    private fun channel(name: String, category: String? = "c") = LiveChannel(
        id = name, name = name, logo = null, url = name, categoryId = category,
        quality = QualityTag.of(name),
    )

    @Test
    fun `brand plus survives the edge-decoration strip`() {
        // The + in these is the name, not decoration. Stripping it renamed
        // most of a typical playlist's premium channels.
        listOf("Disney+", "ESPN+", "Paramount+", "Star+", "Apple TV+", "Canal+")
            .forEach { assertEquals(it, channel(it).displayName) }
        // Mid-name was never affected; it must still agree with the edge case.
        assertEquals("Canal+ Sport", channel("Canal+ Sport").displayName)
    }

    @Test
    fun `real decoration is still stripped`() {
        assertEquals("PEACOCK SERIE ORIGINAL", channel("### PEACOCK SERIE ORIGINAL ᴿᴬᵂ ⁶⁰ᶠᵖˢ ###").displayName)
        assertEquals("Eurosport 1", channel("▎ Eurosport 1 ▎").displayName)
    }

    @Test
    fun `subscripts are not provider decoration`() {
        assertEquals("H₂O TV", TextNorm.stripDecoration("H₂O TV"))
        assertEquals("H₂O TV", channel("H₂O TV").displayName)
        // The superscript half of the block still goes.
        assertEquals("ESPN", channel("ESPN ᵁᴴᴰ ³⁸⁴⁰ᴾ").displayName)
    }

    @Test
    fun `a 2K name ranks above FHD, not below SD`() {
        assertEquals("2K", QualityTag.of("Sky Sports 2K"))
        assertEquals("2K", QualityTag.of("ESPN 1440p"))
        assert(QualityTag.rank(QualityTag.of("ESPN 1440p")) > QualityTag.rank("FHD"))
        // And it groups with its own variants rather than standing apart.
        assertEquals(EpgMatcher.normalizeKey("ESPN HD"), EpgMatcher.normalizeKey("ESPN 1440p"))
        val merged = QualityTag.mergeBestQuality(
            listOf(channel("ESPN SD"), channel("ESPN 1440p"), channel("ESPN HD")),
        )
        assertEquals(1, merged.size)
        assertEquals("2K", merged.single().quality)
    }

    @Test
    fun `All falls back to the unmerged list before the merge lands`() {
        val channels = listOf(channel("BBC One"), channel("ITV"))
        // allChannelsView's initial value, before its off-thread hop emits.
        assertEquals(
            channels,
            channelsInCategory(CATEGORY_ALL, channels, emptySet(), emptyList(), allChannels = emptyList()),
        )
        // Once it lands, the merged list wins.
        val merged = listOf(channel("BBC One"))
        assertEquals(
            merged,
            channelsInCategory(CATEGORY_ALL, channels, emptySet(), emptyList(), allChannels = merged),
        )
    }

    @Test
    fun `catalogue titles keep brand plus too`() {
        // cleanTitle runs the same decoration strip as displayName, so the
        // Disney+ regression would have hit the movie and series rows as well.
        assertEquals("Disney+ Originals", ContentClassifier.cleanTitle("Disney+ Originals"))
    }
}
