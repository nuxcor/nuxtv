package com.agoro.tv

import com.agoro.tv.player.AudioOutputPolicy
import com.agoro.tv.player.PlaybackFault
import com.agoro.tv.player.PtsSmoother
import com.agoro.tv.player.VideoOutputPolicy
import com.agoro.tv.player.faultOf
import com.agoro.tv.player.httpReason
import com.agoro.tv.player.humanError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Whose fault a failure is decides which rung gets it, so a wrong answer is
 * a wasted wait at best and a false promise on the card at worst.
 */
class PlaybackFaultTest {

    @Test
    fun `a mux or decoder failure is the decoder's`() {
        assertEquals(PlaybackFault.DECODE, faultOf("ERROR_CODE_DECODER_INIT_FAILED"))
        assertEquals(PlaybackFault.DECODE, faultOf("ERROR_CODE_PARSING_CONTAINER_MALFORMED"))
    }

    @Test
    fun `a refused AudioTrack is the output's`() {
        assertEquals(PlaybackFault.AUDIO_OUTPUT, faultOf("ERROR_CODE_AUDIO_TRACK_INIT_FAILED"))
        assertEquals(PlaybackFault.AUDIO_OUTPUT, faultOf("ERROR_CODE_AUDIO_TRACK_WRITE_FAILED"))
    }

    @Test
    fun `a provider that said no in words is not retried`() {
        for (status in listOf(400, 401, 404, 410)) {
            assertEquals("HTTP $status", PlaybackFault.PERMANENT, faultOf("ERROR_CODE_IO_BAD_HTTP_STATUS", status))
        }
    }

    @Test
    fun `a provider that may say yes next time is`() {
        // 403 on an Xtream line is usually the previous stream still
        // lingering on the panel; 429 and 5xx say so outright.
        for (status in listOf(403, 429, 500, 502, 503)) {
            assertEquals("HTTP $status", PlaybackFault.TRANSIENT, faultOf("ERROR_CODE_IO_BAD_HTTP_STATUS", status))
        }
        assertEquals(PlaybackFault.TRANSIENT, faultOf("ERROR_CODE_IO_BAD_HTTP_STATUS", null))
        assertEquals(PlaybackFault.TRANSIENT, faultOf("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"))
        assertEquals(PlaybackFault.TRANSIENT, faultOf("ERROR_CODE_SOMETHING_NEW"))
    }

    @Test
    fun `the status is said in words when it means something`() {
        assertEquals("your provider no longer offers this stream", humanError("ERROR_CODE_IO_BAD_HTTP_STATUS", 404))
        assertEquals("your provider says too many streams are open", humanError("ERROR_CODE_IO_BAD_HTTP_STATUS", 429))
        assertEquals("your provider is having trouble", humanError("ERROR_CODE_IO_BAD_HTTP_STATUS", 503))
        // A status with nothing to add keeps the generic line.
        assertEquals("your provider didn't return this stream", humanError("ERROR_CODE_IO_BAD_HTTP_STATUS", 418))
        assertEquals("your provider didn't return this stream", humanError("ERROR_CODE_IO_BAD_HTTP_STATUS"))
        assertNull(httpReason(200))
    }
}

class VideoOutputPolicyTest {

    @Before
    fun fresh() = VideoOutputPolicy.reset()

    @Test
    fun `decoders are reused until one is caught not drawing`() {
        assertFalse(VideoOutputPolicy.reinitOnly)
        assertTrue(VideoOutputPolicy.latch("black screen"))
        assertTrue(VideoOutputPolicy.reinitOnly)
        assertEquals("black screen", VideoOutputPolicy.reason)
    }

    @Test
    fun `a black screen on the re-initialising decoder is not answered with another rebuild`() {
        assertTrue(VideoOutputPolicy.latch("first"))
        assertFalse(VideoOutputPolicy.latch("second"))
    }
}

class TimestampSmoothingLatchTest {

    @Before
    fun fresh() = AudioOutputPolicy.reset()

    @Test
    fun `one discontinuity is a splice`() {
        assertFalse(AudioOutputPolicy.noteDiscontinuity(1_000L))
        assertFalse(AudioOutputPolicy.smoothTimestamps)
    }

    @Test
    fun `three inside a minute is the decoder's clock`() {
        assertFalse(AudioOutputPolicy.noteDiscontinuity(1_000L))
        assertFalse(AudioOutputPolicy.noteDiscontinuity(20_000L))
        assertTrue(AudioOutputPolicy.noteDiscontinuity(40_000L))
        assertTrue(AudioOutputPolicy.smoothTimestamps)
        // Turned once; later jumps are the smoother's problem now.
        assertFalse(AudioOutputPolicy.noteDiscontinuity(41_000L))
    }

    @Test
    fun `three spread over more than a minute are not`() {
        assertFalse(AudioOutputPolicy.noteDiscontinuity(0L))
        assertFalse(AudioOutputPolicy.noteDiscontinuity(30_000L))
        assertFalse(AudioOutputPolicy.noteDiscontinuity(90_000L))
        assertFalse(AudioOutputPolicy.smoothTimestamps)
    }
}

/**
 * The rewrite must be a no-op on a continuous timeline and on anything that
 * looks like a seek, and only ever remove the jumps it was built for.
 */
class PtsSmootherTest {

    private val cadence = 32_000L // one AC-3 frame at 48kHz

    private fun feed(smoother: PtsSmoother, vararg inputs: Long): List<Long> = inputs.map(smoother::rewrite)

    @Test
    fun `a continuous timeline passes through untouched`() {
        val s = PtsSmoother()
        val ins = (0L until 10L).map { it * cadence }.toLongArray()
        assertEquals(ins.toList(), feed(s, *ins))
    }

    @Test
    fun `a spurious forward jump keeps the cadence and carries the offset`() {
        val s = PtsSmoother()
        feed(s, 0L, cadence, 2 * cadence)
        // A one-second jump where a frame's worth was due.
        assertEquals(3 * cadence, s.rewrite(2 * cadence + 1_000_000L))
        // And the buffers after it stay continuous, still a second behind.
        assertEquals(4 * cadence, s.rewrite(2 * cadence + 1_000_000L + cadence))
    }

    @Test
    fun `a jump with no cadence known yet passes through`() {
        val s = PtsSmoother()
        assertEquals(0L, s.rewrite(0L))
        assertEquals(1_000_000L, s.rewrite(1_000_000L))
    }

    @Test
    fun `a real gap or a backward jump follows the stream and drops the offset`() {
        val s = PtsSmoother()
        feed(s, 0L, cadence, 2 * cadence, 2 * cadence + 1_000_000L) // offset now ~1s
        assertEquals(10_000_000L, s.rewrite(10_000_000L)) // a seek: passed through
        assertEquals(5_000_000L, s.rewrite(5_000_000L)) // backward: passed through
    }

    @Test
    fun `the same buffer offered again gets the same answer and keeps the cadence`() {
        val s = PtsSmoother()
        feed(s, 0L, cadence)
        assertEquals(cadence, s.rewrite(cadence))
        assertEquals(2 * cadence, s.rewrite(cadence + 1_000_000L))
    }

    @Test
    fun `reset forgets the offset`() {
        val s = PtsSmoother()
        feed(s, 0L, cadence, cadence + 1_000_000L)
        s.reset()
        assertEquals(7_000_000L, s.rewrite(7_000_000L))
    }

    @Test
    fun `an Xtream account that is disabled says so, not that the panel is unwell`() {
        // 512 and 513 used to fall into the 5xx bucket and come out as "your
        // provider is having trouble", which sends a viewer to wait for a
        // panel that is working fine and has simply said no to them.
        assertEquals(
            "your provider has disabled or expired this account",
            httpReason(512),
        )
        assertEquals(
            "your provider rejected these credentials, or too many attempts too quickly",
            httpReason(513),
        )
    }

    @Test
    fun `a real server fault still reads as one`() {
        assertEquals("your provider is having trouble", httpReason(500))
        assertEquals("your provider is having trouble", httpReason(502))
    }
}
