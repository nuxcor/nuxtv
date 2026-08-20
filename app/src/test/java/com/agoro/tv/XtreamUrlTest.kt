package com.agoro.tv

import com.agoro.tv.data.M3uParser
import com.agoro.tv.data.XtreamClient
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamUrlTest {

    @Test
    fun `credentials with special characters are encoded`() {
        val client = XtreamClient(OkHttpClient(), "example.com:8080", "us er", "p&ss+w#rd")
        val url = client.catchupUrl(7, 1786536000000L, 60)
        assertTrue(url.contains("username=us+er"))
        assertTrue(url.contains("password=p%26ss%2Bw%23rd"))
        assertFalse(url.contains("p&ss"))
    }

    @Test
    fun `catchup timestamps are formatted in UTC`() {
        val client = XtreamClient(OkHttpClient(), "http://example.com", "u", "p")
        // 2026-08-12 12:00:00 UTC
        val url = client.catchupUrl(1, 1786536000000L, 30)
        assertTrue(url.contains("start=2026-08-12:"))
    }

    @Test
    fun `server url normalization strips api paths and adds scheme`() {
        assertEquals("http://host:8080", XtreamClient.normalize("host:8080/player_api.php"))
        assertEquals("https://host", XtreamClient.normalize("https://host/get.php"))
    }

    @Test
    fun `url-tvg header is extracted from playlists`() {
        val text = "#EXTM3U url-tvg=\"http://epg.example.com/guide.xml.gz\"\n#EXTINF:-1,Ch\nhttp://x/1.ts"
        assertEquals("http://epg.example.com/guide.xml.gz", M3uParser.tvgUrl(text))
    }
}
