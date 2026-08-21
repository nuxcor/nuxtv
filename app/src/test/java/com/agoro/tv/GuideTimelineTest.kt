package com.agoro.tv

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agoro.tv.ui.screens.GUIDE_BROWSE_FIXED_COSTS
import com.agoro.tv.ui.screens.guideDpPerMinute
import com.agoro.tv.ui.theme.Space
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide used to scale at a flat 4dp per minute, which was five columns on
 * nobody's TV in particular: 4.8 on a 960dp panel, 7.4 on a 1280dp one. These
 * pin the arithmetic that replaced it, since a wrong scale is invisible in
 * review and only shows up as a guide that pages too soon on a set you don't own.
 */
class GuideTimelineTest {

    /**
     * Everything the timeline doesn't get: gutters and the channel column.
     * The production value itself — a rebuilt copy silently diverged twice,
     * first when the channel column widened, then when the rail became a
     * summoned drawer and stopped reserving anything.
     */
    private val fixedCosts = GUIDE_BROWSE_FIXED_COSTS

    private fun columnsVisible(screenWidth: Dp): Float {
        val lane = screenWidth - fixedCosts
        return lane / (guideDpPerMinute(screenWidth) * 30)
    }

    @Test
    fun `five columns on a 1080p panel`() {
        assertEquals(5.0f, columnsVisible(960.dp), 0.05f)
    }

    @Test
    fun `five columns on a wider panel too`() {
        // Looser than the 1080p case on purpose: now that the guide spends
        // most of the TV-safe gutter, a 1280dp panel's lane is wide enough to
        // hit MAX_DP_PER_MINUTE, and a clamped scale lands a shade past five
        // rather than exactly on it. Five columns is the contract; landing on
        // 5.00 was an accident of the old lane width.
        assertEquals(5.0f, columnsVisible(1280.dp), 0.1f)
    }

    @Test
    fun `a narrow panel gets the floor rather than unreadable slivers`() {
        // 640dp can't fit five readable columns, so it shows fewer at the
        // minimum legible width instead of shrinking cells past a title.
        assertEquals(2.6.dp.value, guideDpPerMinute(640.dp).value, 0.01f)
        assertTrue(columnsVisible(640.dp) < 5f)
    }

    @Test
    fun `an unusually wide panel is capped`() {
        assertEquals(6.dp.value, guideDpPerMinute(2400.dp).value, 0.01f)
    }
}
