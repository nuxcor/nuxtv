package com.nuxcor.nuxtv

import com.nuxcor.nuxtv.data.XtreamClient
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Xtream panels emit `get_series_info`'s `episodes` container in several
 * shapes; every one of them must parse, because a shape mismatch used to
 * come out as a silent "No episodes found".
 */
class XtreamEpisodesTest {

    private val client = XtreamClient(OkHttpClient(), "http://example.com", "u", "p")

    private fun parse(json: String) = client.parseEpisodes(Json.parseToJsonElement(json))

    @Test
    fun `map of season to array parses`() {
        val eps = parse(
            """{"episodes":{"1":[{"id":"11","title":"E1","episode_num":"1"}],
                "2":[{"id":"21","title":"E2","episode_num":"1"}]}}"""
        )
        assertEquals(2, eps.size)
        assertEquals(listOf(1, 2), eps.map { it.season })
    }

    @Test
    fun `array of arrays parses`() {
        val eps = parse(
            """{"episodes":[[{"id":"11","season":"1","episode_num":"1"}],
                [{"id":"21","season":"2","episode_num":"1"}]]}"""
        )
        assertEquals(2, eps.size)
    }

    @Test
    fun `flat array of episode objects parses`() {
        val eps = parse(
            """{"episodes":[{"id":"11","season":"1","episode_num":"1"},
                {"id":"12","season":"1","episode_num":"2"}]}"""
        )
        assertEquals(2, eps.size)
        assertEquals(listOf(1, 2), eps.map { it.episodeNum })
    }

    @Test
    fun `map of season to map of episode objects parses`() {
        val eps = parse(
            """{"episodes":{"3":{"1":{"id":"31","episode_num":"1"},
                "2":{"id":"32","episode_num":"2"}}}}"""
        )
        assertEquals(2, eps.size)
        // Season only present as the container key — it must be carried over.
        assertEquals(listOf(3, 3), eps.map { it.season })
    }

    @Test
    fun `episode_id and stream_id are accepted as the id field`() {
        val eps = parse(
            """{"episodes":[{"episode_id":"7","episode_num":"1"},
                {"stream_id":"8","episode_num":"2"}]}"""
        )
        assertEquals(2, eps.size)
        assertEquals(listOf("ep:7", "ep:8"), eps.map { it.id })
    }

    @Test
    fun `numeric ids build the stream url`() {
        val eps = parse("""{"episodes":[{"id":42,"episode_num":1,"container_extension":"mkv"}]}""")
        assertEquals("http://example.com/series/u/p/42.mkv", eps.single().url)
    }

    @Test
    fun `error and empty responses come back empty instead of throwing`() {
        assertTrue(parse("""[]""").isEmpty())
        assertTrue(parse(""""error"""").isEmpty())
        assertTrue(parse("""{"info":{}}""").isEmpty())
        assertTrue(parse("""{"episodes":null}""").isEmpty())
    }

    @Test
    fun `episodes are sorted by season then number`() {
        val eps = parse(
            """{"episodes":[{"id":"1","season":"2","episode_num":"1"},
                {"id":"2","season":"1","episode_num":"2"},
                {"id":"3","season":"1","episode_num":"1"}]}"""
        )
        assertEquals(listOf("ep:3", "ep:2", "ep:1"), eps.map { it.id })
    }
}
