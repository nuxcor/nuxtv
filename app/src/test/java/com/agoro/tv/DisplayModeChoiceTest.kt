package com.agoro.tv

import android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION
import android.view.Display.HdrCapabilities.HDR_TYPE_HDR10
import android.view.Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS
import android.view.Display.HdrCapabilities.HDR_TYPE_HLG
import com.agoro.tv.player.HdrType
import com.agoro.tv.player.OutputMode
import com.agoro.tv.player.chooseMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The output-mode rule, checked where it can be: the box it exists for is on
 * the end of an HDMI cable and the emulator has one mode and no HDR, so this
 * is the only place the decision is observable at all.
 */
class DisplayModeChoiceTest {

    // A 4K box's mode list, HDR reported per mode the way Android 14 does.
    // 4K60 carries nothing: that is the real HDMI 2.0 shape, where the top
    // refresh has spent its bandwidth on resolution and has none left for the
    // extra bits HDR needs.
    private val uhdPanel = listOf(
        OutputMode(1, 1920, 1080, 60f, listOf(HDR_TYPE_HDR10, HDR_TYPE_HLG)),
        OutputMode(2, 1920, 1080, 50f, listOf(HDR_TYPE_HDR10, HDR_TYPE_HLG)),
        OutputMode(3, 3840, 2160, 60f),
        OutputMode(4, 3840, 2160, 50f, listOf(HDR_TYPE_HDR10, HDR_TYPE_HLG)),
        OutputMode(5, 3840, 2160, 24f, listOf(HDR_TYPE_HDR10, HDR_TYPE_HLG, HDR_TYPE_DOLBY_VISION)),
    )

    private fun mode(id: Int) = uhdPanel.first { it.modeId == id }

    private fun choose(
        current: Int,
        height: Int,
        frameRate: Float?,
        hdr: HdrType? = null,
        modes: List<OutputMode> = uhdPanel,
        allowResolutionChange: Boolean = true,
        pinned: Int = 0,
    ) = chooseMode(
        modes = modes,
        current = modes.first { it.modeId == current },
        videoHeight = height,
        frameRate = frameRate,
        hdr = hdr,
        allowResolutionChange = allowResolutionChange,
        pinned = pinned,
    )

    @Test
    fun `an SDR stream still gets the lowest clean refresh`() {
        // 25fps on a 1080p60 output: 50Hz shows every frame for two refreshes.
        assertEquals(2, choose(current = 1, height = 1080, frameRate = 25f))
    }

    @Test
    fun `an HDR stream is never pinned to a mode that cannot carry it`() {
        // 4K59_94 would land on 4K60 — the only mode that matches the rate,
        // and the one mode on this panel with no HDR at all.
        val chosen = choose(current = 1, height = 2160, frameRate = 59.94f, hdr = HdrType.HDR10)
        assertEquals(4, chosen)
        assert(mode(chosen!!).hdrTypes.contains(HDR_TYPE_HDR10))
    }

    @Test
    fun `HDR at the display's own size beats SDR at a bigger one`() {
        // A panel whose only 4K mode is SDR. A 4K HDR stream would rather stay
        // at 1080p and keep its transfer function than be upscaled into grey.
        val modes = listOf(
            OutputMode(1, 1920, 1080, 60f, listOf(HDR_TYPE_HDR10)),
            OutputMode(2, 1920, 1080, 50f, listOf(HDR_TYPE_HDR10)),
            OutputMode(3, 3840, 2160, 60f),
        )
        val chosen = choose(
            current = 1, height = 2160, frameRate = 50f, hdr = HdrType.HDR10, modes = modes,
        )
        assertEquals(2, chosen)
    }

    @Test
    fun `nothing is pinned when no mode can carry the stream's HDR`() {
        // Every mode SDR except in a flavour this stream isn't. Pinning any of
        // them would take away whatever the box negotiated for itself, which
        // is the regression this whole rule exists to avoid.
        val modes = listOf(
            OutputMode(1, 1920, 1080, 60f, listOf(HDR_TYPE_HLG)),
            OutputMode(2, 1920, 1080, 50f, listOf(HDR_TYPE_HLG)),
        )
        assertNull(
            choose(current = 1, height = 1080, frameRate = 25f, hdr = HdrType.HDR10, modes = modes)
        )
    }

    @Test
    fun `a display that reports no HDR on any mode is left to negotiate`() {
        // Pre-Android-14, and post-14 devices whose HAL doesn't answer per
        // mode: an empty report is not "no HDR", it is no answer, and the
        // refresh rule carries on as it did before HDR was considered at all.
        val modes = uhdPanel.map { it.copy(hdrTypes = emptyList()) }
        assertEquals(2, choose(current = 1, height = 1080, frameRate = 25f, hdr = HdrType.HDR10, modes = modes))
    }

    @Test
    fun `a current mode that already carries the HDR and the rate is left alone`() {
        assertNull(choose(current = 2, height = 1080, frameRate = 25f, hdr = HdrType.HDR10))
    }

    @Test
    fun `a current mode with a clean rate but no HDR is still moved off`() {
        // 4K60 shows 60fps perfectly, so the SDR rule would stop here. HDR is
        // the reason to spend the blackout anyway.
        val chosen = choose(current = 3, height = 2160, frameRate = 60f, hdr = HdrType.HDR10)
        assertEquals(4, chosen)
    }

    @Test
    fun `Dolby Vision settles for an HDR10 mode rather than an SDR one`() {
        // The base layer plays as HDR10 where the panel won't take DV, so an
        // HDR10 carrier still beats dropping to SDR.
        val modes = listOf(
            OutputMode(1, 3840, 2160, 60f),
            OutputMode(2, 3840, 2160, 24f, listOf(HDR_TYPE_HDR10_PLUS)),
        )
        assertEquals(
            2,
            choose(
                current = 1, height = 2160, frameRate = 24f,
                hdr = HdrType.DOLBY_VISION, modes = modes,
            ),
        )
    }

    @Test
    fun `resolution is not raised once a tune has already matched it`() {
        // An adaptive ladder climbing a rung mid-stream is the same stream;
        // raising the output for it would black out the picture mid-programme.
        val chosen = choose(
            current = 1, height = 2160, frameRate = 50f, allowResolutionChange = false,
        )
        assertEquals(2, chosen)
    }

    @Test
    fun `a mode already asked for is not asked for twice`() {
        assertNull(choose(current = 2, height = 1080, frameRate = 25f, pinned = 2))
    }

    @Test
    fun `the persisted HDR name round-trips`() {
        // What setKnownHdr writes is what deservesTunnel reads back; a rename
        // of these constants would silently stop every HDR stream tunnelling
        // from its first frame.
        for (type in HdrType.entries) {
            assertEquals(type, HdrType.byName(type.name))
        }
        assertNull(HdrType.byName(null))
        assertNull(HdrType.byName("HDR10+"))
    }
}
