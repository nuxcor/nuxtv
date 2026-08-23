package com.agoro.tv

import com.agoro.tv.data.ProgrammeRow
import com.agoro.tv.data.XmltvData
import com.agoro.tv.data.XmltvParser
import kotlinx.serialization.json.Json
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
    fun `every display-name alternate is indexed`() {
        val alts = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="ESPN.us">
                <display-name>ESPN</display-name>
                <display-name>ESPN HD</display-name>
              </channel>
              <programme start="20260814100000 +0000" stop="20260814110000 +0000" channel="ESPN.us">
                <title>Game</title>
              </programme>
            </tv>
        """.trimIndent()
        val data = XmltvParser.parse(alts.byteInputStream())
        assertEquals("espn.us", data.nameToId["espn"])
        assertEquals("espn.us", data.nameToId["espn hd"])
        assertEquals(listOf("ESPN", "ESPN HD"), data.altNames["espn.us"])
        // Both alternates normalize onto the same channel, so it is named once.
        assertEquals(
            listOf("espn.us"),
            data.normalizedToId[com.agoro.tv.data.EpgMatcher.normalizeKey("ESPN HD")],
        )
        // channelNames keeps the first alternate, as before.
        assertEquals("ESPN", data.channelNames["ESPN.us"])
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

    // --- the store-backed path ------------------------------------------------

    @Test
    fun `a sink takes the programmes instead of the returned data`() {
        val rows = ArrayList<ProgrammeRow>()
        val data = XmltvParser.parse(xml.byteInputStream(), sink = rows::add)
        // Programmes went to the sink, so the returned index carries none —
        // that is the whole point: they are on their way to the table.
        assertTrue(data.programmes.isEmpty())
        assertEquals(3, rows.size)
        assertEquals("Morning Show", rows[0].title)
        assertEquals("Some description.", rows[0].description)
        assertNull(rows[1].description)
        // The names index is still built, because matching still needs it.
        assertEquals("one.tv", data.nameToId["channel one"])
    }

    /**
     * The matcher refuses a channel whose lane would be empty. With
     * programmes in the table it cannot learn that from [XmltvData.programmes],
     * so the sink path has to report it separately — and a channel that was
     * declared but carries nothing must not be in it.
     */
    @Test
    fun `the sink path reports which channels have a schedule`() {
        val declaredOnly = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="one.tv"><display-name>Channel One</display-name></channel>
              <channel id="silent.tv"><display-name>Silent</display-name></channel>
              <programme start="20260814100000 +0000" stop="20260814110000 +0000" channel="one.tv">
                <title>Morning Show</title>
              </programme>
            </tv>
        """.trimIndent()
        val data = XmltvParser.parse(declaredOnly.byteInputStream(), sink = {})
        assertEquals(setOf("one.tv"), data.channelsWithProgrammes)
    }

    /** The in-memory path answers the same question from its own programmes. */
    @Test
    fun `without a sink the schedule set falls back to the programmes`() {
        val data = XmltvParser.parse(xml.byteInputStream())
        assertEquals(setOf("one.tv", "two.tv"), data.channelsWithProgrammes)
    }

    /**
     * The warm start reads this index back off disk and publishes it. If the
     * schedule set did not survive the round trip, the matcher would reject
     * every channel and every row would read "No information" — on a guide
     * that is sitting complete in the table.
     */
    @Test
    fun `the schedule set survives being written to the index and read back`() {
        val json = Json { ignoreUnknownKeys = true }
        val data = XmltvParser.parse(xml.byteInputStream(), sink = {})
        val restored = json.decodeFromString<XmltvData>(json.encodeToString(data))
        assertEquals(setOf("one.tv", "two.tv"), restored.channelsWithProgrammes)
        assertEquals("Channel One", restored.channelNames["one.tv"])
    }

    // --- filtering -----------------------------------------------------------

    /**
     * A programme on a channel this playlist cannot show is dropped on the
     * channel id alone, before its times are read. The national feeds carry
     * forty programmes for every one this catalogue keeps, and parsing two
     * timestamps for each of the other thirty-nine was most of what a pack
     * cost. So the discarded one here is given times no parser could read
     * and a synopsis that would be wrong to keep: if either were looked at,
     * the kept channel's schedule would not come out exactly as it does.
     */
    @Test
    fun `a programme on an unwanted channel is skipped without reading it`() {
        val mixed = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="Kept.tv"><display-name>Kept</display-name></channel>
              <channel id="Noise.tv"><display-name>Noise</display-name></channel>
              <programme start="not-a-date" stop="" channel="Noise.tv">
                <title>Should never be seen</title><desc>Nor this.</desc>
              </programme>
              <programme start="20260814100000 +0000" stop="20260814110000 +0000" channel="Kept.tv">
                <title>Kept Show</title><desc>Kept synopsis.</desc>
              </programme>
              <programme channel="Noise.tv">
                <title>No times at all</title>
              </programme>
              <programme start="20260814110000 +0000" stop="20260814120000 +0000" channel="undeclared.tv">
                <title>Never declared</title>
              </programme>
            </tv>
        """.trimIndent()
        val rows = ArrayList<ProgrammeRow>()
        // Ids are matched lowercase, as the matcher's are, against a feed
        // that writes them in mixed case.
        val data = XmltvParser.parse(
            mixed.byteInputStream(),
            wantedIds = setOf("kept.tv"),
            sink = rows::add,
        )
        assertEquals(listOf("Kept Show"), rows.map { it.title })
        assertEquals("Kept synopsis.", rows.single().description)
        assertEquals(setOf("kept.tv"), data.channelsWithProgrammes)
        // Declarations are always kept — the matcher binds by name — even
        // for channels whose programmes are not.
        assertEquals("Noise", data.channelNames["Noise.tv"])
    }

    /** The same gate on the name route: a channel wanted by display name keeps its schedule. */
    @Test
    fun `a channel wanted by name keeps its programmes`() {
        val data = XmltvParser.parse(
            xml.byteInputStream(),
            wantedNameKeys = setOf(com.agoro.tv.data.EpgMatcher.normalizeKey("Channel Two")),
        )
        assertNull(data.programmes["one.tv"])
        assertEquals(listOf("Far Future Show"), data.programmes["two.tv"]!!.map { it.title })
    }
}
