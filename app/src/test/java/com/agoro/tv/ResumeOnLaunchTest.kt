package com.agoro.tv

import com.agoro.tv.data.ContentBundle
import com.agoro.tv.data.ContentState
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.data.indexAnswering
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A television comes back on the channel it went off on. The two things that
 * decide whether it can are how far the catalogue got, and whether the channel
 * is still in it — and those two misses are not the same miss.
 */
class ResumeOnLaunchTest {

    private fun channel(id: String, url: String, fallbacks: List<String> = emptyList()) =
        LiveChannel(
            id = id,
            name = id,
            logo = null,
            url = url,
            categoryId = null,
            fallbackUrls = fallbacks,
        )

    private val ready = ContentState.Ready(ContentBundle())

    @Test
    fun `a channel the catalogue still carries is resumed`() {
        assertEquals(ResumeOutcome.OpenPlayer, resumeOutcome(ready, index = 0))
        assertEquals(ResumeOutcome.OpenPlayer, resumeOutcome(ready, index = 42))
    }

    @Test
    fun `a channel the catalogue answered for and does not have is forgotten`() {
        // Kept, this waits for a catalogue on every later launch only to land
        // on Home anyway. The catalogue has spoken: the url is dead.
        assertEquals(ResumeOutcome.ForgetAndOpenHome, resumeOutcome(ready, index = -1))
    }

    @Test
    fun `a catalogue that never arrived is not taken as proof the channel is gone`() {
        // The distinction that matters. A playlist that failed to load, timed
        // out, or came back empty says nothing about the channel, so the url
        // survives to be tried again next launch.
        assertEquals(ResumeOutcome.OpenHome, resumeOutcome(null, index = -1))
        assertEquals(ResumeOutcome.OpenHome, resumeOutcome(ContentState.Empty, index = -1))
        assertEquals(ResumeOutcome.OpenHome, resumeOutcome(ContentState.Error("no"), index = -1))
        assertEquals(ResumeOutcome.OpenHome, resumeOutcome(ContentState.Loading(), index = -1))
    }

    @Test
    fun `a settled but unready catalogue never resumes even if an index is offered`() {
        assertEquals(ResumeOutcome.OpenHome, resumeOutcome(ContentState.Empty, index = 3))
    }

    @Test
    fun `a visible list that never filled is not read as the channel being gone`() {
        // null index means there was no list to look in — a filter still
        // settling. Treating that as -1 would wipe a perfectly good url on a
        // timing race, and the viewer would never get their channel back.
        assertEquals(ResumeOutcome.OpenHome, resumeOutcome(ready, index = null))
    }

    @Test
    fun `the remembered url is matched through fallbacks, not by equality`() {
        // The feed tuned last night is frequently the one this morning's merge
        // folded away; comparing urls would silently send the viewer to Home.
        val channels = listOf(
            channel("one", "http://a/1"),
            channel("two", "http://a/2", fallbacks = listOf("http://a/2-sd")),
        )
        assertEquals(1, channels.indexAnswering("http://a/2-sd"))
        assertEquals(0, channels.indexAnswering("http://a/1"))
        assertEquals(-1, channels.indexAnswering("http://a/gone"))
    }
}
