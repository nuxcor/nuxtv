package com.agoro.tv

import com.agoro.tv.data.Episode
import com.agoro.tv.ui.screens.upNext
import com.agoro.tv.ui.screens.upNextLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Where am I in this show?" — the rule the whole series page hangs off.
 *
 * Every case here was broken the same way: a resume position is deleted when a
 * title runs past 95%, and nothing recorded that it had been SEEN, so stopping
 * at an episode boundary made the app forget the viewer entirely.
 */
class EpisodeProgressTest {

    private fun ep(season: Int, number: Int) = Episode(
        id = "s${season}e$number",
        title = "Episode $number",
        season = season,
        episodeNum = number,
        url = "http://x/s${season}e$number",
    )

    private val show = listOf(
        ep(1, 1), ep(1, 2), ep(1, 3),
        ep(2, 1), ep(2, 2),
    )

    private fun watched(vararg eps: Episode) = eps.associate { it.url to 1_000L }

    @Test
    fun `a show never touched opens on its first episode, unnamed`() {
        val up = upNext(show, emptyMap(), emptyMap())!!
        assertEquals(ep(1, 1).url, up.episode.url)
        assertTrue(up.fresh)
        assertFalse(up.resuming)
        // A first-time viewer learns nothing from being told "S1E1".
        assertEquals("Play", upNextLabel(up))
    }

    @Test
    fun `a finished episode advances to the next one`() {
        // The case that was broken: S1E1 finished, no position left anywhere,
        // and the page used to answer "Play" — meaning S1E1, again.
        val up = upNext(show, emptyMap(), watched(ep(1, 1)))!!
        assertEquals(ep(1, 2).url, up.episode.url)
        assertEquals("Play S1E2", upNextLabel(up))
    }

    @Test
    fun `a finished season rolls into the next one`() {
        val up = upNext(show, emptyMap(), watched(ep(1, 1), ep(1, 2), ep(1, 3)))!!
        assertEquals(ep(2, 1).url, up.episode.url)
        assertEquals("Play S2E1", upNextLabel(up))
    }

    @Test
    fun `a part-watched episode outranks a finished one`() {
        val up = upNext(
            show,
            resumePositions = mapOf(ep(2, 1).url to 90_000L),
            watchedAt = watched(ep(1, 1), ep(1, 2)),
        )!!
        assertEquals(ep(2, 1).url, up.episode.url)
        assertTrue(up.resuming)
        assertEquals("Resume S2E1", upNextLabel(up))
    }

    @Test
    fun `the furthest part-watched episode wins, not the earliest`() {
        // Dipping back into season one does not move the viewer back to it.
        val up = upNext(
            show,
            resumePositions = mapOf(ep(1, 1).url to 30_000L, ep(2, 2).url to 30_000L),
            watchedAt = emptyMap(),
        )!!
        assertEquals(ep(2, 2).url, up.episode.url)
    }

    @Test
    fun `a show watched to the end rounds back to the start and says so`() {
        val up = upNext(show, emptyMap(), watched(*show.toTypedArray()))!!
        assertEquals(ep(1, 1).url, up.episode.url)
        assertTrue(up.allWatched)
        assertFalse(up.fresh)
        assertEquals("Watch again from S1E1", upNextLabel(up))
    }

    @Test
    fun `skipping ahead is respected, not rewound`() {
        // Watched S2E1 out of order: the next one is S2E2, not the S1E2 the
        // viewer passed over. "Furthest watched" is the question, deliberately.
        val up = upNext(show, emptyMap(), watched(ep(2, 1)))!!
        assertEquals(ep(2, 2).url, up.episode.url)
    }

    @Test
    fun `provider order does not decide what comes next`() {
        // Panels ship episodes in whatever order they like; the rule sorts.
        val shuffled = show.reversed()
        val up = upNext(shuffled, emptyMap(), watched(ep(1, 1)))!!
        assertEquals(ep(1, 2).url, up.episode.url)
    }

    @Test
    fun `a stale position behind the viewer does not rewind them`() {
        // 90 seconds sampled from S1E1 during a re-watch. Positions are never
        // cleared unless the title finishes, so left to outrank the watch
        // history it would answer "Resume S1E1" to someone two seasons on —
        // for good.
        val up = upNext(
            show,
            resumePositions = mapOf(ep(1, 1).url to 90_000L),
            watchedAt = watched(ep(1, 1), ep(1, 2), ep(1, 3), ep(2, 1)),
        )!!
        assertEquals(ep(2, 2).url, up.episode.url)
        assertFalse(up.resuming)
        assertEquals("Play S2E2", upNextLabel(up))
    }

    @Test
    fun `a position at the viewer's own place is still a resume`() {
        // The boundary: part-way through the furthest episode they have
        // reached, which is the ordinary case and must not be swallowed.
        val up = upNext(
            show,
            resumePositions = mapOf(ep(2, 1).url to 90_000L),
            watchedAt = watched(ep(1, 1), ep(1, 2), ep(1, 3)),
        )!!
        assertEquals(ep(2, 1).url, up.episode.url)
        assertTrue(up.resuming)
    }

    @Test
    fun `no episodes has no answer`() {
        assertNull(upNext(emptyList(), emptyMap(), emptyMap()))
    }

    @Test
    fun `a watch mark on an episode this show does not have is ignored`() {
        // The history is global and keyed by url; another show's marks must
        // not move this one.
        val up = upNext(show, emptyMap(), mapOf("http://x/other" to 1_000L))!!
        assertEquals(ep(1, 1).url, up.episode.url)
        assertTrue(up.fresh)
    }
}
