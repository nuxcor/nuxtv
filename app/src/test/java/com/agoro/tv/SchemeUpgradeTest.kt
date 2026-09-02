package com.agoro.tv

import com.agoro.tv.data.XtreamClient
import com.agoro.tv.data.httpFallback
import com.agoro.tv.data.httpsCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The string half of moving this provider onto TLS.
 *
 * Xtream carries the credentials in the URL — the query string for the API
 * and the PATH for every stream — so these two functions decide whether a
 * password goes out in clear. Worth pinning even though they are four lines.
 */
class SchemeUpgradeTest {

    @Test
    fun `an http url offers an https candidate`() {
        assertEquals("https://panel.example", httpsCandidate("http://panel.example"))
        assertEquals(
            "https://panel.example:8080/live/u/p/1.ts",
            httpsCandidate("http://panel.example:8080/live/u/p/1.ts"),
        )
    }

    @Test
    fun `an https url has nothing to upgrade`() {
        assertNull(httpsCandidate("https://panel.example"))
    }

    @Test
    fun `the port survives the swap`() {
        // The panel is routinely on a non-standard port, and dropping it
        // would send the probe somewhere that is not the panel — which
        // answers nothing, and would be read as "no TLS here" forever.
        assertEquals("https://panel.example:2095", httpsCandidate("http://panel.example:2095"))
        assertEquals("http://panel.example:2095", httpFallback("https://panel.example:2095"))
    }

    @Test
    fun `the downgrade is the exact inverse`() {
        val plain = "http://panel.example:8080/live/user/pass/42.ts"
        assertEquals(plain, httpFallback(httpsCandidate(plain)!!))
    }

    @Test
    fun `a plain url has nothing to downgrade`() {
        assertNull(httpFallback("http://panel.example"))
    }

    @Test
    fun `neither function touches a url that is neither`() {
        // rtsp and rtmp both appear in this catalogue; a blind prefix swap
        // would have mangled them into something unplayable.
        assertNull(httpsCandidate("rtsp://panel.example/x"))
        assertNull(httpFallback("rtmp://panel.example/x"))
    }

    @Test
    fun `a bare host still normalises to http, and the probe is what upgrades it`() {
        // normalize stays as it was on purpose: it is pure and offline, and a
        // bare host cannot be assumed to speak TLS. The probe decides, and
        // this is the input it decides on.
        assertEquals("http://panel.example", XtreamClient.normalize("panel.example"))
        assertEquals("https://panel.example", httpsCandidate(XtreamClient.normalize("panel.example")))
    }

    @Test
    fun `an https server the viewer typed is left alone`() {
        assertEquals("https://panel.example", XtreamClient.normalize("https://panel.example"))
        assertNull(httpsCandidate(XtreamClient.normalize("https://panel.example")))
    }
}
