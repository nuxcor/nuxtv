package com.nuxcor.nuxtv

import com.nuxcor.nuxtv.data.EpgProgram
import com.nuxcor.nuxtv.data.XmltvData
import com.nuxcor.nuxtv.data.XmltvMerger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Merging a dozen guide packs is where the parser's invariants used to die. */
class GuideMergeTest {

    private fun prog(id: String, start: Long) =
        EpgProgram(
            id = id, title = id, description = null,
            startMs = start, endMs = start + 1000, hasArchive = false,
        )

    private fun data(
        programmes: Map<String, List<EpgProgram>> = emptyMap(),
        normalized: Map<String, String> = emptyMap(),
        names: Map<String, String> = emptyMap(),
    ) = XmltvData(
        channelNames = names,
        programmes = programmes,
        nameToId = emptyMap(),
        normalizedToId = normalized,
        altNames = emptyMap(),
    )

    private fun merge(vararg packs: XmltvData) =
        XmltvMerger().apply { packs.forEach { add(it) } }.build()

    @Test
    fun `schedules from several packs are combined, not shadowed`() {
        // Finding 10: a pack that merely declares the channel used to discard
        // a fuller schedule from a lower-ranked pack entirely.
        val thin = data(programmes = mapOf("bbc" to listOf(prog("a", 0))))
        val full = data(
            programmes = mapOf("bbc" to listOf(prog("b", 1000), prog("c", 2000))),
        )
        val out = merge(thin, full)!!
        assertEquals(listOf(0L, 1000L, 2000L), out.programmes["bbc"]!!.map { it.startMs })
    }

    @Test
    fun `the higher ranked pack wins a slot both packs claim`() {
        val first = data(programmes = mapOf("bbc" to listOf(prog("first", 0))))
        val second = data(programmes = mapOf("bbc" to listOf(prog("second", 0))))
        val out = merge(first, second)!!
        assertEquals(1, out.programmes["bbc"]!!.size)
        assertEquals("first", out.programmes["bbc"]!![0].title)
    }

    @Test
    fun `programmes come back sorted regardless of pack order`() {
        val late = data(programmes = mapOf("bbc" to listOf(prog("late", 5000))))
        val early = data(programmes = mapOf("bbc" to listOf(prog("early", 100))))
        val out = merge(late, early)!!
        assertEquals(listOf(100L, 5000L), out.programmes["bbc"]!!.map { it.startMs })
    }

    @Test
    fun `a normalized key two packs disagree on is dropped, not arbitrated`() {
        // Finding 9: first-wins here is exactly the cross-wiring the field's
        // own contract says must never happen.
        val a = data(normalized = mapOf("skysports1" to "sky1"))
        val b = data(normalized = mapOf("skysports1" to "sky1hd"))
        assertNull(merge(a, b)!!.normalizedToId["skysports1"])
    }

    @Test
    fun `a contested key stays dropped when a later pack repeats it`() {
        // The pairwise fold could reinstate a key a previous step had removed,
        // because nothing remembered that it was contested.
        val a = data(normalized = mapOf("k" to "one"))
        val b = data(normalized = mapOf("k" to "two"))
        val c = data(normalized = mapOf("k" to "one"))
        assertNull(merge(a, b, c)!!.normalizedToId["k"])
    }

    @Test
    fun `an uncontested key survives and keeps the first pack's answer`() {
        val a = data(normalized = mapOf("k" to "one"))
        val b = data(normalized = mapOf("k" to "one", "j" to "two"))
        val out = merge(a, b)!!
        assertEquals("one", out.normalizedToId["k"])
        assertEquals("two", out.normalizedToId["j"])
    }

    @Test
    fun `channel identity is first-wins across packs`() {
        val a = data(names = mapOf("bbc" to "BBC One"))
        val b = data(names = mapOf("bbc" to "BBC 1 HD", "itv" to "ITV"))
        val out = merge(a, b)!!
        assertEquals("BBC One", out.channelNames["bbc"])
        assertEquals("ITV", out.channelNames["itv"])
    }

    @Test
    fun `an empty merge yields null rather than an empty guide`() {
        assertTrue(XmltvMerger().isEmpty)
        assertNull(XmltvMerger().build())
    }
}
