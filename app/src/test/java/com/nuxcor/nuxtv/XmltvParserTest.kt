package com.nuxcor.nuxtv

import com.nuxcor.nuxtv.data.XmltvParser
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XmltvParserTest {

    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="one.tv"><display-name>Channel One</display-name></channel>
          <channel id="two.tv"><display-name>Channel Two</display-name></channel>
          <programme start="20260814100000 +0000" stop="20260814110000 +0000" channel="one.tv">
            <title>Morning Show</title><desc>Some description.</desc>
          </programme>
          <programme start="20260814110000 +0000" stop="20260814120000 +0000" channel="one.tv">
            <title>Midday News</title>
          </programme>
          <programme start="20260820100000 +0000" stop="20260820110000 +0000" channel="two.tv">
            <title>Far Future Show</title>
          </programme>
        </tv>
    """.trimIndent()

    @Test
    fun `parses channels, programmes and name index`() {
        val data = XmltvParser.parse(xml.byteInputStream())
        assertEquals("Channel One", data.channelNames["one.tv"])
        assertEquals(2, data.programmes["one.tv"]!!.size)
        assertEquals("Morning Show", data.programmes["one.tv"]!![0].title)
        assertEquals("one.tv", data.nameToId["channel one"])
    }

    @Test
    fun `window pruning drops out-of-range programmes`() {
        // Window covering only Aug 14 → the Aug 20 programme is dropped.
        val start = 1786492800000L // 2026-08-12 00:00 UTC
        val end = start + 4 * 24 * 3600_000L
        val data = XmltvParser.parse(xml.byteInputStream(), start, end)
        assertEquals(2, data.programmes["one.tv"]!!.size)
        assertNull(data.programmes["two.tv"])
    }

    @Test
    fun `gzip payloads are sniffed and decompressed`() {
        val gz = ByteArrayOutputStream().also { bos ->
            GZIPOutputStream(bos).use { it.write(xml.toByteArray()) }
        }.toByteArray()
        val data = XmltvParser.parse(gz.inputStream())
        assertTrue(data.programmes.isNotEmpty())
    }
}
