package com.agoro.tv

import com.agoro.tv.player.AudioOutputPolicy
import com.agoro.tv.player.humanError
import com.agoro.tv.player.isAudioOutputFault
import com.agoro.tv.player.isDecodeFault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioOutputPolicyTest {

    @Before
    fun fresh() = AudioOutputPolicy.reset()

    @Test
    fun `players decode passthrough formats until the output refuses one`() {
        assertFalse(AudioOutputPolicy.pcmOnly)
        assertNull(AudioOutputPolicy.reason)
    }

    @Test
    fun `the first refusal latches PCM for the process and asks for a rebuild`() {
        assertTrue(AudioOutputPolicy.latch("audio track init failed"))
        assertTrue(AudioOutputPolicy.pcmOnly)
        assertEquals("audio track init failed", AudioOutputPolicy.reason)
    }

    @Test
    fun `the probe latches on the first advertised encoding the output will not open`() {
        val ac3 = androidx.media3.common.C.ENCODING_AC3
        val eac3 = androidx.media3.common.C.ENCODING_E_AC3
        // Nothing advertised: nothing to refuse, nothing to latch.
        assertNull(AudioOutputPolicy.passthroughVerdict(emptyList()) { true })
        // Everything advertised opens: the platform told the truth.
        assertNull(AudioOutputPolicy.passthroughVerdict(listOf(ac3, eac3)) { true })
        // The first refusal is the verdict; the rest are not asked.
        val asked = mutableListOf<Int>()
        assertEquals(eac3, AudioOutputPolicy.passthroughVerdict(listOf(ac3, eac3)) { asked += it; it != eac3 })
        assertEquals(listOf(ac3, eac3), asked)
        assertEquals(ac3, AudioOutputPolicy.passthroughVerdict(listOf(ac3, eac3)) { false })
    }

    @Test
    fun `passthrough is allowed everywhere until a tunnelled bitstream track is refused`() {
        assertTrue(AudioOutputPolicy.allowsPassthrough(tunnelled = false))
        assertTrue(AudioOutputPolicy.allowsPassthrough(tunnelled = true))
        assertTrue(AudioOutputPolicy.refuseTunnelledPassthrough("HW_AV_SYNC E-AC-3 refused"))
        assertFalse(AudioOutputPolicy.allowsPassthrough(tunnelled = true))
        // The untunnelled path is untouched: the output plays the bitstream
        // there, and this is not the PCM latch.
        assertTrue(AudioOutputPolicy.allowsPassthrough(tunnelled = false))
        assertFalse(AudioOutputPolicy.pcmOnly)
        assertEquals("HW_AV_SYNC E-AC-3 refused", AudioOutputPolicy.tunnelledReason)
        // Once: a second refusal is not this policy's to answer.
        assertFalse(AudioOutputPolicy.refuseTunnelledPassthrough("again"))
    }

    @Test
    fun `a refusal on the PCM player is not answered with another rebuild`() {
        // PCM did not fix it, so the ladder must fall through to its ordinary
        // retries and the error card rather than loop on rebuilds.
        assertTrue(AudioOutputPolicy.latch("first"))
        assertFalse(AudioOutputPolicy.latch("second"))
        assertTrue(AudioOutputPolicy.pcmOnly)
        assertEquals("first", AudioOutputPolicy.reason)
    }
}

/**
 * Which failures are the audio output's doing — the gate on the PCM rung.
 *
 * Too wide and a decoder fault is answered by rebuilding the sink, which
 * changes nothing; too narrow and a film with Dolby audio dies on the card
 * while the FFmpeg decoder that could have played it sits idle.
 */
class AudioOutputFaultTest {

    @Test
    fun `an AudioTrack the platform refused to create or feed is the output's fault`() {
        for (code in listOf(
            "ERROR_CODE_AUDIO_TRACK_INIT_FAILED",
            "ERROR_CODE_AUDIO_TRACK_WRITE_FAILED",
            "ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED",
            "ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED",
        )) {
            assertTrue(code, isAudioOutputFault(code))
            // The two rungs must not claim the same fault: a sink refusal is
            // not a decoder's, and software decoders would not change it.
            assertFalse(code, isDecodeFault(code))
        }
    }

    @Test
    fun `a decoder, provider or network failure is not`() {
        for (code in listOf(
            "ERROR_CODE_DECODER_INIT_FAILED",
            "ERROR_CODE_DECODING_FAILED",
            "ERROR_CODE_IO_BAD_HTTP_STATUS",
            "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
            "ERROR_CODE_BEHIND_LIVE_WINDOW",
            "ERROR_CODE_SOMETHING_NEW",
        )) {
            assertFalse(code, isAudioOutputFault(code))
        }
    }

    @Test
    fun `the card says whose fault it was`() {
        assertEquals("your TV refused this audio format", humanError("ERROR_CODE_AUDIO_TRACK_INIT_FAILED"))
        assertEquals("your TV refused this audio format", humanError("ERROR_CODE_AUDIO_TRACK_WRITE_FAILED"))
    }
}
