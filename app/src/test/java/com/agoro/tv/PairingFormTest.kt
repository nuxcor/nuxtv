package com.agoro.tv

import com.agoro.tv.data.PairingServer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone-side sign-in page follows the same rule as the TV's own form: a
 * build made for one provider never asks for a URL the viewer has no way to
 * know, and neither build asks for a playlist name the app can pick itself.
 */
class PairingFormTest {

    private fun form(defaultServer: String?) =
        PairingServer(defaultServer = defaultServer) { _, _, _, _ -> }.formHtml()

    @Test
    fun `branded build asks only who you are`() {
        val html = form("http://provider.example:8080")
        assertFalse("server field must be hidden", html.contains("name=\"server\""))
        assertTrue(html.contains("name=\"username\""))
        assertTrue(html.contains("name=\"password\""))
    }

    @Test
    fun `generic build still asks for the server`() {
        val html = form(null)
        assertTrue(html.contains("name=\"server\""))
        assertTrue(html.contains("name=\"username\""))
        assertTrue(html.contains("name=\"password\""))
    }

    @Test
    fun `the playlist name field is gone on both`() {
        assertFalse(form(null).contains("name=\"name\""))
        assertFalse(form("http://provider.example:8080").contains("name=\"name\""))
    }
}
