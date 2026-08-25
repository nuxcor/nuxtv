package com.agoro.tv

import com.agoro.tv.player.isSoftwareDecoder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rebuffer log warns when a title is being drawn by a software decoder,
 * because that is the one cause of "some titles stutter" nothing on screen
 * reveals. The names come from real boxes: Android's own components are the
 * software ones; the vendor prefixes are hardware.
 */
class SoftwareDecoderTest {

    @Test
    fun `the platform's own decoders are software`() {
        assertTrue(isSoftwareDecoder("OMX.google.h264.decoder"))
        assertTrue(isSoftwareDecoder("c2.android.hevc.decoder"))
        assertTrue(isSoftwareDecoder("c2.android.av1.decoder"))
    }

    @Test
    fun `vendor decoders are hardware`() {
        assertFalse(isSoftwareDecoder("OMX.amlogic.hevc.decoder.awesome"))
        assertFalse(isSoftwareDecoder("c2.mtk.hevc.decoder"))
        assertFalse(isSoftwareDecoder("OMX.rk.video_decoder.hevc"))
        assertFalse(isSoftwareDecoder("c2.qti.hevc.decoder"))
    }
}
