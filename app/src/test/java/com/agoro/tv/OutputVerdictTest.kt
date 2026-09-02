package com.agoro.tv

import com.agoro.tv.player.NO_PICTURE_WINDOWS
import com.agoro.tv.player.OutputVerdict
import com.agoro.tv.player.outputVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The output watchdog's rule, handed its booleans directly.
 *
 * Two watchdog false positives have shipped from this area, so the cases that
 * must NOT fire are tested at least as hard as the one that must.
 */
class OutputVerdictTest {

    /** Healthy: sound advancing, a decoder armed, a frame on screen. */
    private fun healthy(
        audioArmed: Boolean = true,
        audioAdvancing: Boolean = true,
        videoArmed: Boolean = true,
        firstFrameDrawn: Boolean = true,
        tunnelling: Boolean = false,
        blankWindows: Int = 0,
    ) = outputVerdict(
        audioArmed = audioArmed,
        audioAdvancing = audioAdvancing,
        videoArmed = videoArmed,
        firstFrameDrawn = firstFrameDrawn,
        tunnelling = tunnelling,
        blankWindows = blankWindows,
    )

    @Test
    fun `a stream that is playing normally is never judged`() {
        assertNull(healthy())
    }

    @Test
    fun `audio accepted but never advancing is silence`() {
        assertEquals(OutputVerdict.SILENT_AUDIO, healthy(audioAdvancing = false))
    }

    @Test
    fun `silence outranks a missing picture`() {
        // No sound and no picture: the audio branch answers, as it always
        // has. StallMonitor owns the case where neither ever arrives.
        assertEquals(
            OutputVerdict.SILENT_AUDIO,
            healthy(audioAdvancing = false, firstFrameDrawn = false, videoArmed = false),
        )
    }

    @Test
    fun `a decoder that took a format and drew nothing is the black picture`() {
        assertEquals(OutputVerdict.BLACK_PICTURE, healthy(firstFrameDrawn = false))
    }

    @Test
    fun `sound with no video decoder at all waits a window, then calls the feed dead`() {
        // The live sport case: the pipe serves audio and never a picture, so
        // no video decoder is ever armed and the old rule's `videoArmed &&`
        // could not reach it.
        val shape = { windows: Int ->
            healthy(videoArmed = false, firstFrameDrawn = false, blankWindows = windows)
        }
        assertEquals(OutputVerdict.NO_PICTURE_YET, shape(0))
        assertEquals(OutputVerdict.NO_PICTURE_YET, shape(NO_PICTURE_WINDOWS - 1))
        assertEquals(OutputVerdict.NO_PICTURE, shape(NO_PICTURE_WINDOWS))
    }

    @Test
    fun `a picture that arrives late is never called dead`() {
        // The whole reason NO_PICTURE waits: within the windows the frame
        // lands, and a drawn frame ends the question at any window count.
        assertNull(healthy(videoArmed = false, blankWindows = NO_PICTURE_WINDOWS + 5))
    }

    @Test
    fun `nothing is read off a tunnelled decoder`() {
        // The vendor draws and some boxes never report the frame; that is
        // TunnelPolicy's case, not this one.
        assertNull(healthy(firstFrameDrawn = false, tunnelling = true))
        assertNull(
            healthy(
                videoArmed = false, firstFrameDrawn = false, tunnelling = true,
                blankWindows = NO_PICTURE_WINDOWS,
            ),
        )
    }

    @Test
    fun `a stream with no clock running is not judged on its picture`() {
        // Nothing armed and nothing advancing — a tune still opening. The
        // audio branch needs audioArmed, so this must fall through to null
        // rather than being read as a dead feed.
        assertNull(
            healthy(
                audioArmed = false, audioAdvancing = false,
                videoArmed = false, firstFrameDrawn = false,
                blankWindows = NO_PICTURE_WINDOWS,
            ),
        )
    }

    @Test
    fun `a silent film with a picture is left alone`() {
        // Video drawing, audio never armed: no sound is the stream's own
        // business and there is a picture, so there is nothing to report.
        assertNull(healthy(audioArmed = false, audioAdvancing = false))
    }
}
