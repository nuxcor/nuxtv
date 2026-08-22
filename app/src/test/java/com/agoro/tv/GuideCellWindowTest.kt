package com.agoro.tv

import com.agoro.tv.data.EpgProgram
import com.agoro.tv.ui.screens.GuideCellSpec
import com.agoro.tv.ui.screens.GuideRowLayout
import com.agoro.tv.ui.screens.visibleCellsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A guide row composes only the cells near the viewport — the whole 30-hour
 * window is sixty to a hundred of them. The spacers standing in for the rest
 * have to be exact: a row that measures short lets the shared ScrollState
 * clamp early, and every other row's timeline drifts out of line with the
 * ruler.
 */
class GuideCellWindowTest {

    private fun row(count: Int, widthMinutes: Float = 30f, gap: Float = 0f) = GuideRowLayout(
        cells = List(count) { i ->
            GuideCellSpec(
                program = EpgProgram(
                    id = "p$i",
                    title = "Programme $i",
                    description = null,
                    startMs = i * 1_800_000L,
                    endMs = (i + 1) * 1_800_000L,
                    hasArchive = false,
                ),
                gapMinutesBefore = if (i == 0) 0f else gap,
                widthMinutes = widthMinutes,
                clampedStartMs = i * 1_800_000L,
                clampedEndMs = (i + 1) * 1_800_000L,
            )
        },
        tailMinutes = 0f,
    )

    /** lead + every composed span + trail must equal the row's full width. */
    private fun assertWidthPreserved(
        layout: GuideRowLayout,
        perMinutePx: Float,
        scrollPx: Int,
        viewportPx: Float,
        seedViewportPx: Float = 0f,
    ) {
        val visible = visibleCellsFor(layout, perMinutePx, scrollPx, viewportPx, seedViewportPx)
        var composed = 0f
        layout.cells.forEachIndexed { i, spec ->
            if (i in visible.range) {
                composed += spec.widthMinutes
                if (i != visible.range.first) composed += spec.gapMinutesBefore
            }
        }
        val total = layout.cells.sumOf { (it.widthMinutes + it.gapMinutesBefore).toDouble() }.toFloat()
        assertEquals(total, visible.leadMinutes + composed + visible.trailMinutes, 0.01f)
    }

    @Test
    fun `width is preserved wherever the viewport sits`() {
        val layout = row(count = 60, gap = 2f)
        val perMinutePx = 4f
        listOf(0, 500, 2_000, 7_200, 14_000).forEach { scroll ->
            assertWidthPreserved(layout, perMinutePx, scroll, viewportPx = 900f)
        }
    }

    @Test
    fun `only a fraction of a long row is composed`() {
        val layout = row(count = 60)
        // 60 half-hour cells at 4px/min is 7200px of row for a 900px viewport.
        val visible = visibleCellsFor(layout, perMinutePx = 4f, scrollPx = 3_600, viewportPx = 900f)
        val composed = visible.range.count()
        assertTrue("composed $composed of 60", composed in 1..25)
    }

    @Test
    fun `the window covers the viewport plus a screen of margin each side`() {
        val layout = row(count = 60)
        val perMinutePx = 4f
        val viewportPx = 900f
        val scrollPx = 3_600
        val visible = visibleCellsFor(layout, perMinutePx, scrollPx, viewportPx)
        // Every cell overlapping the visible lane must be composed, or D-pad
        // would land on a cell whose focus requester was never attached.
        var cursor = 0f
        layout.cells.forEachIndexed { i, spec ->
            val start = cursor + spec.gapMinutesBefore
            val end = start + spec.widthMinutes
            val onScreen = end * perMinutePx > scrollPx && start * perMinutePx < scrollPx + viewportPx
            if (onScreen) assertTrue("cell $i is on screen but not composed", i in visible.range)
            cursor = end
        }
    }

    @Test
    fun `an unmeasured viewport composes everything rather than nothing`() {
        // Before the first layout pass the viewport is unknown; a row that
        // guessed empty would render blank on its first frame.
        val layout = row(count = 12)
        val visible = visibleCellsFor(layout, perMinutePx = 4f, scrollPx = 0, viewportPx = 0f)
        assertEquals(0..11, visible.range)
        assertEquals(0f, visible.leadMinutes, 0.01f)
        assertEquals(0f, visible.trailMinutes, 0.01f)
    }

    @Test
    fun `an unmeasured viewport with a seed composes only the seeded window`() {
        // The first frame of every fresh composition — Live entry, return
        // from the player, overlay open — has no measured viewport. The host
        // knows the lane's width from its own arithmetic and passes it as
        // the seed, so frame one composes the handful frame two keeps rather
        // than every cell of the day.
        val layout = row(count = 60)
        val perMinutePx = 4f
        val scrollPx = 3_600
        val seeded = visibleCellsFor(layout, perMinutePx, scrollPx, viewportPx = 0f, seedViewportPx = 900f)
        val measured = visibleCellsFor(layout, perMinutePx, scrollPx, viewportPx = 900f)
        assertEquals(measured.range, seeded.range)
        assertTrue("composed ${seeded.range.count()} of 60", seeded.range.count() in 1..25)
        assertWidthPreserved(layout, perMinutePx, scrollPx, viewportPx = 0f, seedViewportPx = 900f)
        // The seed only ever stands in for an UNKNOWN viewport; a measured
        // one wins however the two disagree.
        val real = visibleCellsFor(layout, perMinutePx, scrollPx, viewportPx = 400f, seedViewportPx = 900f)
        assertEquals(visibleCellsFor(layout, perMinutePx, scrollPx, viewportPx = 400f).range, real.range)
    }

    @Test
    fun `a row of one cell is unaffected`() {
        val layout = row(count = 1)
        val visible = visibleCellsFor(layout, perMinutePx = 4f, scrollPx = 0, viewportPx = 900f)
        assertEquals(0..0, visible.range)
        assertEquals(0f, visible.leadMinutes, 0.01f)
        assertEquals(0f, visible.trailMinutes, 0.01f)
    }
}
