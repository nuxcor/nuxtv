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

    private fun ch(
        id: String,
        name: String,
        epgId: String? = null,
        tvgName: String? = null,
        /** "US|NEWS" — the matcher reads the territory off the front of it. */
        categoryId: String? = null,
    ) =
        LiveChannel(
            id = id, name = name, logo = null, url = "http://x/$id",
            categoryId = categoryId, epgId = epgId, tvgName = tvgName,
        )

    /** [counts] gives a guide id more of the day than its rivals. */
    private fun programmes(
        ids: Collection<String>,
        counts: Map<String, Int> = emptyMap(),
    ): Map<String, List<EpgProgram>> =
        ids.associateWith { id ->
            (0 until (counts[id] ?: 1)).map {
                EpgProgram("$id:$it", "P", null, it * 1000L, it * 1000L + 999, hasArchive = false)
            }
        }

    private fun guide(
        alts: Map<String, List<String>>,
        withProgrammes: Set<String> = alts.keys,
        counts: Map<String, Int> = emptyMap(),
    ): XmltvData {
        val nameToId = HashMap<String, String>()
        val normalizedToId = HashMap<String, MutableList<String>>()
        alts.forEach { (id, names) ->
            names.forEach { name ->
                nameToId.putIfAbsent(name.trim().lowercase(), id)
                val holders = normalizedToId.getOrPut(EpgMatcher.normalizeKey(name)) { mutableListOf() }
                if (id !in holders) holders += id
            }
        }
        return XmltvData(
            channelNames = alts.mapValues { it.value.first() },
            programmes = programmes(withProgrammes, counts),
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
            counts = mapOf("foxnews.us" to 130, "foxsports.us" to 145), // PROBE
        )
        val res = EpgMatcher.resolve(listOf(ch("c1", "Fox", categoryId = "US|NEWS")), data)
        println("PROBE Fox resolved to " + res.byChannelId["c1"])
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

    /**
     * The rows that read "No information" while their schedule sat in the
     * table: the provider decorates its names, no guide does, and matching
     * only the raw name meant a whole block of channels bound to nothing.
     */
    @Test
    fun `a decorated provider name still finds its guide channel`() {
        val data = guide(mapOf("hbocomedy.us" to listOf("HBO Comedy")))
        val resolution = EpgMatcher.resolve(
            listOf(ch("live:1", "PRIME: HBO COMEDY \u1d3f\u1d2c\u1d42")),
            data,
        )
        assertEquals("hbocomedy.us", resolution.byChannelId["live:1"])
    }

    /** A precise name still wins; the display name is a fallback, not an override. */
    @Test
    fun `the raw name is preferred over the display name`() {
        val data = guide(
            mapOf(
                "sky.sports.hd.uk" to listOf("Sky Sports HD"),
                "sky.sports.uk" to listOf("Sky Sports"),
            )
        )
        val resolution = EpgMatcher.resolve(listOf(ch("live:1", "Sky Sports HD")), data)
        assertEquals("sky.sports.hd.uk", resolution.byChannelId["live:1"])
    }

    // --- two feeds, one channel -------------------------------------------

    /**
     * The empty row this was written for: a network carried by two feeds
     * bound to neither, because the shared name looked like ambiguity. Both
     * candidates are the same US channel, so the fuller schedule wins.
     */
    @Test
    fun `two feeds carrying one channel resolve to the fuller schedule`() {
        val data = guide(
            alts = mapOf(
                "abcnewslive.us" to listOf("ABC News Live"),
                "abc.news.live.us2" to listOf("ABC News Live"),
            ),
            counts = mapOf("abcnewslive.us" to 127, "abc.news.live.us2" to 109),
        )
        val resolution = EpgMatcher.resolve(
            listOf(ch("live:1", "PRIME: ABC NEWS LIVE", categoryId = "US|NEWS")),
            data,
        )
        assertEquals("abcnewslive.us", resolution.byChannelId["live:1"])
    }

    /**
     * Territory outranks the schedule. This is what the old refusal was
     * really protecting: a fuller foreign feed must never win a US channel.
     */
    @Test
    fun `the channel's own territory beats a fuller foreign feed`() {
        val data = guide(
            alts = mapOf(
                "court.tv.uk" to listOf("Court TV"),
                "courttv.us" to listOf("Court TV"),
            ),
            counts = mapOf("court.tv.uk" to 500, "courttv.us" to 10),
        )
        val resolution = EpgMatcher.resolve(
            listOf(ch("live:1", "PRIME: COURT TV", categoryId = "US|ENTERTAINMENT")),
            data,
        )
        assertEquals("courttv.us", resolution.byChannelId["live:1"])
    }

    /** A dead heat has no answer in it, so it stays unmatched. */
    @Test
    fun `same territory and the same schedule stays unmatched`() {
        val data = guide(
            alts = mapOf(
                "one.us" to listOf("Court TV"),
                "two.us" to listOf("Court TV"),
            ),
            counts = mapOf("one.us" to 40, "two.us" to 40),
        )
        val resolution = EpgMatcher.resolve(
            listOf(ch("live:1", "PRIME: COURT TV", categoryId = "US|ENTERTAINMENT")),
            data,
        )
        assertNull(resolution.byChannelId["live:1"])
    }

    /** With no territory to go on, the fuller schedule is the whole answer. */
    @Test
    fun `an uncategorised channel still takes the fuller schedule`() {
        val data = guide(
            alts = mapOf(
                "court.tv.uk" to listOf("Court TV"),
                "courttv.us" to listOf("Court TV"),
            ),
            counts = mapOf("court.tv.uk" to 90, "courttv.us" to 12),
        )
        val resolution = EpgMatcher.resolve(listOf(ch("live:1", "PRIME: COURT TV")), data)
        assertEquals("court.tv.uk", resolution.byChannelId["live:1"])
    }

    /** A candidate with an empty lane is no candidate at all. */
    @Test
    fun `a rival with no schedule never wins`() {
        val data = guide(
            alts = mapOf(
                "empty.us" to listOf("Court TV"),
                "courttv.us" to listOf("Court TV"),
            ),
            withProgrammes = setOf("courttv.us"),
        )
        val resolution = EpgMatcher.resolve(
            listOf(ch("live:1", "COURT TV", categoryId = "US|ENTERTAINMENT")),
            data,
        )
        assertEquals("courttv.us", resolution.byChannelId["live:1"])
    }
}
