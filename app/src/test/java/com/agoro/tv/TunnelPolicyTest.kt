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
        assertFalse(TunnelPolicy.wantsTunnel("http://p/live/1.ts", knownUhd = false))
    }

    @Test
    fun `a stream the app knows to be 4K tunnels from the first frame`() {
        assertTrue(TunnelPolicy.wantsTunnel("http://p/live/uhd.ts", knownUhd = true))
    }

    @Test
    fun `a stream that decoded as UHD this process tunnels on its next visit`() {
        TunnelPolicy.remember("http://p/live/uhd.ts")
        assertTrue(TunnelPolicy.wantsTunnel("http://p/live/uhd.ts", knownUhd = false))
        assertFalse(TunnelPolicy.wantsTunnel("http://p/live/other.ts", knownUhd = false))
    }

    @Test
    fun `a device that froze while tunnelled is never asked again`() {
        TunnelPolicy.remember("http://p/live/uhd.ts")
        TunnelPolicy.refuse()
        assertFalse(TunnelPolicy.wantsTunnel("http://p/live/uhd.ts", knownUhd = true))
        assertFalse(TunnelPolicy.wantsTunnel("http://p/live/uhd.ts", knownUhd = false))
        assertTrue(TunnelPolicy.refusedByDevice)
    }
}
