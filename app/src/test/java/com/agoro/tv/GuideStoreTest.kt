package com.agoro.tv

import com.agoro.tv.data.GuideStore
import com.agoro.tv.data.ProgrammeRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The guide store is where the app's memory problem was solved, so these
 * tests are about the properties that solve it — windowed reads, absent
 * descriptions, a replace that actually replaces — not about SQLite.
 */
@RunWith(RobolectricTestRunner::class)
class GuideStoreTest {

    private lateinit var store: GuideStore

    private val hour = 3600_000L

    @Before
    fun setUp() {
        store = GuideStore(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        store.close()
    }

    private fun row(channel: String, startHour: Long, title: String, desc: String? = "synopsis") =
        ProgrammeRow(
            channelId = channel,
            startMs = startHour * hour,
            endMs = (startHour + 1) * hour,
            title = title,
            description = desc,
        )

    private fun write(vararg rows: ProgrammeRow) {
        store.beginReplace()
        store.insertPack { sink -> rows.forEach(sink::add) }
    }

    @Test
    fun `reads back what a pack wrote`() {
        write(row("bbc", 0, "Breakfast"), row("bbc", 1, "News"))
        val out = store.programmes(listOf("bbc"), 0, 3 * hour)
        assertEquals(listOf("Breakfast", "News"), out["bbc"]!!.map { it.title })
    }

    @Test
    fun `a window returns only what overlaps it`() {
        write(row("bbc", 0, "Early"), row("bbc", 5, "Middle"), row("bbc", 20, "Late"))
        val out = store.programmes(listOf("bbc"), 5 * hour, 6 * hour)
        assertEquals(listOf("Middle"), out["bbc"]!!.map { it.title })
    }

    /** A programme straddling the window edge is on screen, so it must come back. */
    @Test
    fun `a straddling programme is in the window`() {
        write(row("bbc", 0, "Overnight").copy(endMs = 6 * hour))
        val out = store.programmes(listOf("bbc"), 3 * hour, 4 * hour)
        assertEquals(listOf("Overnight"), out["bbc"]!!.map { it.title })
    }

    /** The whole point: 70% of the text does not come back with the grid. */
    @Test
    fun `windowed reads carry no descriptions`() {
        write(row("bbc", 0, "Breakfast", desc = "A very long synopsis"))
        assertNull(store.programmes(listOf("bbc"), 0, 3 * hour)["bbc"]!![0].description)
    }

    @Test
    fun `a description is read one programme at a time`() {
        write(row("bbc", 2, "Breakfast", desc = "A very long synopsis"))
        assertEquals("A very long synopsis", store.description("bbc", 2 * hour))
        assertNull(store.description("bbc", 99 * hour))
    }

    /** The id windowed reads hand out is the one [description] is keyed by. */
    @Test
    fun `a windowed programme's id round-trips to its description`() {
        write(row("bbc", 2, "Breakfast", desc = "A very long synopsis"))
        val program = store.programmes(listOf("bbc"), 0, 5 * hour)["bbc"]!!.single()
        val channelId = program.id.substringBeforeLast(':')
        val startMs = program.id.substringAfterLast(':').toLong()
        assertEquals("A very long synopsis", store.description(channelId, startMs))
    }

    /** The schedule sheet's read is the one that DOES carry synopses. */
    @Test
    fun `a channel's schedule carries its descriptions`() {
        write(row("bbc", 0, "Breakfast", desc = "Toast"), row("itv", 0, "Other", desc = "Nope"))
        val out = store.schedule("bbc", 0, 5 * hour)
        assertEquals(listOf("Toast"), out.map { it.description })
    }

    @Test
    fun `more channels than SQLite takes parameters still all come back`() {
        val ids = (0 until 950).map { "ch$it" }
        write(*ids.map { row(it, 0, "On $it") }.toTypedArray())
        val out = store.programmes(ids, 0, 2 * hour)
        assertEquals(950, out.size)
        assertEquals("On ch949", out["ch949"]!!.single().title)
    }

    @Test
    fun `replacing a guide drops the previous one`() {
        write(row("bbc", 0, "Old"))
        write(row("itv", 0, "New"))
        assertTrue(store.programmes(listOf("bbc"), 0, 5 * hour).isEmpty())
        assertEquals(setOf("itv"), store.channelsWithProgrammes())
    }

    /** Packs land one transaction at a time and must accumulate, not replace. */
    @Test
    fun `packs after the first add to the guide`() {
        store.beginReplace()
        store.insertPack { it.add(row("bbc", 0, "One")) }
        store.insertPack { it.add(row("itv", 0, "Two")) }
        assertEquals(setOf("bbc", "itv"), store.channelsWithProgrammes())
    }

    /** An unstamped table is a half-finished download and must not be trusted. */
    @Test
    fun `the stamp is only there once written`() {
        write(row("bbc", 0, "One"))
        assertNull(store.readStamp())
        store.stamp("http://guide")
        assertEquals("http://guide", store.readStamp()!!.first)
    }

    @Test
    fun `a replace clears the previous stamp`() {
        write(row("bbc", 0, "One"))
        store.stamp("http://old")
        write(row("bbc", 0, "Two"))
        assertNull(store.readStamp())
    }

    @Test
    fun `the guide's end drives how far the grid can page`() {
        write(row("bbc", 0, "One"), row("itv", 47, "Last"))
        assertEquals(48 * hour, store.lastProgrammeEndMs())
        store.clear()
        assertEquals(0L, store.lastProgrammeEndMs())
    }

    @Test
    fun `a programme with no synopsis reads back as none, not as empty text`() {
        write(row("bbc", 0, "One", desc = null))
        assertNull(store.description("bbc", 0))
    }

    /** Two packs carrying the same airing must not draw it twice. */
    @Test
    fun `the same airing from two packs collapses to one row`() {
        store.beginReplace()
        store.insertPack { it.add(row("bbc", 0, "First take")) }
        store.insertPack { it.add(row("bbc", 0, "Second take")) }
        val out = store.programmes(listOf("bbc"), 0, 5 * hour)["bbc"]!!
        assertEquals(listOf("Second take"), out.map { it.title })
    }

    @Test
    fun `asking for no channels asks the database nothing`() {
        write(row("bbc", 0, "One"))
        assertTrue(store.programmes(emptyList(), 0, 5 * hour).isEmpty())
    }
}
