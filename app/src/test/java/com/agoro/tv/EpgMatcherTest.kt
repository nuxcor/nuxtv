package com.agoro.tv

import com.agoro.tv.data.EpgMatcher
import com.agoro.tv.data.EpgProgram
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.data.XmltvData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The channel→guide resolver must find matches through provider naming mess
 * and must NEVER guess when the answer is ambiguous.
 */
class EpgMatcherTest {

    private fun ch(id: String, name: String, epgId: String? = null, tvgName: String? = null) =
        LiveChannel(
            id = id, name = name, logo = null, url = "http://x/$id",
            categoryId = null, epgId = epgId, tvgName = tvgName,
        )

    private fun programmes(vararg ids: String): Map<String, List<EpgProgram>> =
        ids.associateWith {
            listOf(EpgProgram(it, "P", null, 0L, 1L, hasArchive = false))
        }

    private fun guide(
        alts: Map<String, List<String>>,
        withProgrammes: Set<String> = alts.keys,
    ): XmltvData {
        val nameToId = HashMap<String, String>()
        val normalizedToId = HashMap<String, String>()
        val contested = HashSet<String>()
        alts.forEach { (id, names) ->
            names.forEach { name ->
                nameToId.putIfAbsent(name.trim().lowercase(), id)
                val key = EpgMatcher.normalizeKey(name)
                val holder = normalizedToId.putIfAbsent(key, id)
                if (holder != null && holder != id) contested += key
            }
        }
        contested.forEach { normalizedToId.remove(it) }
        return XmltvData(
            channelNames = alts.mapValues { it.value.first() },
            programmes = programmes(*withProgrammes.toTypedArray()),
            nameToId = nameToId,
            normalizedToId = normalizedToId,
            altNames = alts,
        )
    }

    // --- normalizeKey ------------------------------------------------------

    @Test
    fun `platform and quality decorations strip to a bare identity`() {
        assertEquals("skysports1", EpgMatcher.normalizeKey("US| Sky Sports 1 FHD"))
        assertEquals("bbcone", EpgMatcher.normalizeKey("(UK) BBC One HD"))
    }

    @Test
    fun `diacritics fold`() {
        assertEquals("telemontecarlo", EpgMatcher.normalizeKey("Télé Monte-Carlo"))
    }

    @Test
    fun `bare digits are identity`() {
        val one = EpgMatcher.normalizeKey("Sky Sports 1")
        val two = EpgMatcher.normalizeKey("Sky Sports 2")
        org.junit.Assert.assertNotEquals(one, two)
    }

    @Test
    fun `an all-decoration name never normalizes to blank`() {
        org.junit.Assert.assertTrue(EpgMatcher.normalizeKey("HD").isNotBlank())
    }

    // --- resolution stages -------------------------------------------------

    @Test
    fun `epgId wins over names`() {
        val data = guide(mapOf("cnn.us" to listOf("CNN"), "fox.us" to listOf("Fox")))
        val res = EpgMatcher.resolve(listOf(ch("c1", "Fox", epgId = "cnn.us")), data)
        assertEquals("cnn.us", res.byChannelId["c1"])
    }

    @Test
    fun `an epgId with no programmes falls through to the name`() {
        val data = guide(
            mapOf("dead.id" to listOf("Dead"), "cnn.us" to listOf("CNN")),
            withProgrammes = setOf("cnn.us"),
        )
        val res = EpgMatcher.resolve(listOf(ch("c1", "CNN", epgId = "dead.id")), data)
        assertEquals("cnn.us", res.byChannelId["c1"])
    }

    @Test
    fun `messy provider names match clean guide names via normalization`() {
        val data = guide(mapOf("espn.us" to listOf("ESPN"), "tele5.de" to listOf("Tele 5")))
        val res = EpgMatcher.resolve(
            listOf(ch("c1", "US| ESPN FHD"), ch("c2", "Télé 5 HD")),
            data,
        )
        assertEquals("espn.us", res.byChannelId["c1"])
        assertEquals("tele5.de", res.byChannelId["c2"])
        assertEquals(2, res.matched)
        assertEquals(2, res.total)
    }

    @Test
    fun `tvgName rescues a channel whose title matches nothing`() {
        val data = guide(mapOf("bbc1.uk" to listOf("BBC One")))
        val res = EpgMatcher.resolve(
            listOf(ch("c1", "Channel 4001", tvgName = "BBC One")),
            data,
        )
        assertEquals("bbc1.uk", res.byChannelId["c1"])
    }

    @Test
    fun `display-name alternates match too`() {
        val data = guide(mapOf("espn.us" to listOf("ESPN", "ESPN HD")))
        val res = EpgMatcher.resolve(listOf(ch("c1", "ESPN HD")), data)
        assertEquals("espn.us", res.byChannelId["c1"])
    }

    // --- no false positives ------------------------------------------------

    @Test
    fun `ambiguous candidates stay unmatched`() {
        // "Fox" could be Fox News or Fox Sports — guessing is worse than
        // showing no guide for the row.
        val data = guide(
            mapOf("foxnews.us" to listOf("Fox News"), "foxsports.us" to listOf("Fox Sports")),
        )
        val res = EpgMatcher.resolve(listOf(ch("c1", "Fox")), data)
        assertNull(res.byChannelId["c1"])
        assertEquals(0, res.matched)
    }

    @Test
    fun `token tie-break accepts a unique near-match`() {
        val data = guide(mapOf("skysp1.uk" to listOf("Sky Sports 1")))
        // One extra token of difference, single candidate → match.
        val res = EpgMatcher.resolve(listOf(ch("c1", "Sky Sports 1 Football")), data)
        assertEquals("skysp1.uk", res.byChannelId["c1"])
    }

    @Test
    fun `contested normalized keys are removed rather than arbitrated`() {
        // Two guide channels normalize to the same key — the index must not
        // pick a side.
        val data = guide(
            mapOf("a.uk" to listOf("Sky Sports 1"), "b.uk" to listOf("Sky Sports 1 HD")),
        )
        val res = EpgMatcher.resolve(listOf(ch("c1", "UK| Sky Sports 1 FHD")), data)
        assertNull(res.byChannelId["c1"])
    }
}
