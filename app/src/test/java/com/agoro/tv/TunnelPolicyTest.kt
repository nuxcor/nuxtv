package com.agoro.tv

import com.agoro.tv.player.TunnelPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TunnelPolicyTest {

    @Before
    fun fresh() = TunnelPolicy.reset()

    @Test
    fun `an ordinary stream does not tunnel`() {
        assertFalse(TunnelPolicy.wantsTunnel("http://p/live/1.ts", knownDeserving = false))
    }

    @Test
    fun `a stream the app knows to be 4K or HDR tunnels from the first frame`() {
        assertTrue(TunnelPolicy.wantsTunnel("http://p/live/uhd.ts", knownDeserving = true))
    }

    @Test
    fun `a stream that decoded as UHD this process tunnels on its next visit`() {
        TunnelPolicy.remember("http://p/live/uhd.ts")
        assertTrue(TunnelPolicy.wantsTunnel("http://p/live/uhd.ts", knownDeserving = false))
        assertFalse(TunnelPolicy.wantsTunnel("http://p/live/other.ts", knownDeserving = false))
    }

    @Test
    fun `a device that froze while tunnelled is never asked again`() {
        TunnelPolicy.remember("http://p/live/uhd.ts")
        TunnelPolicy.refuse()
        assertFalse(TunnelPolicy.wantsTunnel("http://p/live/uhd.ts", knownDeserving = true))
        assertFalse(TunnelPolicy.wantsTunnel("http://p/live/uhd.ts", knownDeserving = false))
        assertTrue(TunnelPolicy.refusedByDevice)
    }
}

/**
 * Which failures are worth re-opening on the tolerant profile.
 *
 * This is the gate on the rung that replaced the swap to libVLC, and getting
 * it wrong is user-visible in both directions: too narrow and a stream that
 * software decoding would have played dies on the error card, too wide and a
 * viewer whose provider returned a 404 is made to sit through a second doomed
 * attempt. That attempt used to announce itself as "Trying software
 * decoding…"; the ladder no longer narrates its rungs, so a wrong gate now
 * costs the wait without even saying what it is waiting for.
 */
class DecodeFaultTest {

    @org.junit.Test
    fun `a mux or decoder the device cannot handle is worth another profile`() {
        for (code in listOf(
            "ERROR_CODE_PARSING_CONTAINER_MALFORMED",
            "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",
            "ERROR_CODE_DECODING_FAILED",
            "ERROR_CODE_DECODER_INIT_FAILED",
            "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED",
            "ERROR_CODE_DECODER_QUERY_FAILED",
        )) {
            assertTrue(code, com.agoro.tv.player.isDecodeFault(code))
        }
    }

    @org.junit.Test
    fun `a provider or network failure is not`() {
        // These fail identically however the player is built, so offering
        // software decoding for them is a promise it cannot keep.
        for (code in listOf(
            "ERROR_CODE_IO_BAD_HTTP_STATUS",
            "ERROR_CODE_IO_FILE_NOT_FOUND",
            "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
            "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT",
            "ERROR_CODE_DRM_SCHEME_UNSUPPORTED",
            "ERROR_CODE_BEHIND_LIVE_WINDOW",
        )) {
            assertFalse(code, com.agoro.tv.player.isDecodeFault(code))
        }
    }

    @org.junit.Test
    fun `an unknown code is not guessed at`() {
        assertFalse(com.agoro.tv.player.isDecodeFault("ERROR_CODE_SOMETHING_NEW"))
    }
}
