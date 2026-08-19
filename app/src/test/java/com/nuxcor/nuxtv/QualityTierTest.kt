package com.nuxcor.nuxtv

import com.nuxcor.nuxtv.data.QualityTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tier vocabulary and its ordering, which nothing covered before.
 *
 * Adding the 2K tier to [QualityTag.tierOf] without adding it to
 * [QualityTag.rank] left rank("2K") at 0 — below SD — so a channel measured at
 * 1440p lost the duplicate merge to its own SD variant and sorted last under
 * "Best quality first". Every tier tierOf can produce has to rank.
 */
class QualityTierTest {

    @Test
    fun `every tier tierOf produces has a rank above unknown`() {
        val heights = listOf(2160, 1440, 1080, 720, 480)
        for (height in heights) {
            val tier = QualityTag.tierOf(height)
            assertTrue("no tier for ${height}p", tier != null)
            assertTrue("${tier} ranks at or below unknown", QualityTag.rank(tier) > 0)
        }
    }

    @Test
    fun `ranks order from SD up to 4K`() {
        val ordered = listOf("SD", "HD", "FHD", "2K", "4K")
        val ranks = ordered.map { QualityTag.rank(it) }
        assertEquals(ranks.sorted(), ranks)
        assertEquals(ranks.distinct().size, ranks.size)
        assertTrue("unknown must rank below SD", QualityTag.rank(null) < QualityTag.rank("SD"))
    }

    @Test
    fun `1440p is 2K in both the tier and the resolution label`() {
        assertEquals("2K", QualityTag.tierOf(1440))
        assertEquals("1440p 2K", QualityTag.ofResolution(2560, 1440))
    }

    @Test
    fun `tier boundaries land where the labels claim`() {
        assertEquals("4K", QualityTag.tierOf(2160))
        assertEquals("2K", QualityTag.tierOf(1400))
        assertEquals("FHD", QualityTag.tierOf(1080))
        assertEquals("HD", QualityTag.tierOf(720))
        assertEquals("SD", QualityTag.tierOf(576))
        assertEquals(null, QualityTag.tierOf(0))
    }
}
