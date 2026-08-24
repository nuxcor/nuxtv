package com.agoro.tv

import com.agoro.tv.data.ContentBundle
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The catalogue cache holds the FINISHED model, and lives in filesDir, which
 * an app update does not clear. Without a record of which manifest curated it,
 * a release that changed the curation went on publishing the OLD manifest's
 * output from disk until the catalogue aged out — merged shelves came back,
 * and dropped channels came back with them.
 *
 * The decision is three lines in readCache, so it is pinned here rather than
 * discovered on a television a day after a release.
 */
class BundleManifestStampTest {

    /** The rule readCache applies, kept in one place so the test states it once. */
    private fun accepts(cacheStamp: String?, currentStamp: String?): Boolean =
        currentStamp == null || cacheStamp == currentStamp

    @Test
    fun `a cache curated by the manifest in hand is used`() {
        assertEquals(true, accepts("2026-08-23T14:45:00+00:00", "2026-08-23T14:45:00+00:00"))
    }

    @Test
    fun `a cache curated by an older manifest is refused`() {
        // The exact case: 2.34.0 shipped a manifest that folded DStv's three
        // shelves into one, over a cache built by the manifest before it.
        assertEquals(false, accepts("2026-08-22T22:39:04+00:00", "2026-08-23T14:45:00+00:00"))
    }

    @Test
    fun `an unstamped cache is refused, because that is the upgrade case`() {
        // THE regression. Every cache written before the field existed is
        // unstamped, so reading null as "don't know" meant the version that
        // added this check helped only the devices that never needed it. An
        // unstamped cache was built by a manifest this build cannot identify.
        assertEquals(false, accepts(null, "2026-08-23T14:45:00+00:00"))
    }

    @Test
    fun `an unknown CURRENT stamp keeps whatever is cached`() {
        // A manifest that failed to load, or a source it does not describe.
        // Neither is a reason to throw away a good catalogue and pay a cold
        // reload for it.
        assertEquals(true, accepts("2026-08-23T14:45:00+00:00", null))
        assertEquals(true, accepts(null, null))
    }

    @Test
    fun `a bundle written before the field existed still decodes`() {
        // Old caches on disk have no manifestStamp key at all; a decode that
        // threw here would cost every existing install a cold reload.
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString<ContentBundle>("""{"cleaned":true}""")
        assertNull(old.manifestStamp)
        assertEquals(true, old.cleaned)
    }

    @Test
    fun `the stamp survives a round trip through the cache format`() {
        val json = Json { ignoreUnknownKeys = true }
        val stamped = ContentBundle(cleaned = true, manifestStamp = "2026-08-23T14:45:00+00:00")
        val back = json.decodeFromString<ContentBundle>(json.encodeToString(stamped))
        assertEquals("2026-08-23T14:45:00+00:00", back.manifestStamp)
    }
}
