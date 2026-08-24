package com.agoro.tv

import com.agoro.tv.data.isNewer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the app offers an update at all comes down to this comparison, and
 * every way it can be wrong is silent: too eager and it offers a download of
 * what is already installed, too shy and a release nobody sees ships into a
 * void. The release tag carries a leading "v" and BuildConfig does not, which
 * is the asymmetry most likely to be lost in a refactor.
 */
class UpdateVersionTest {

    @Test
    fun `the release tag's leading v is not part of the number`() {
        assertTrue(isNewer("v2.34.1", "2.34.0"))
        assertFalse(isNewer("v2.34.0", "2.34.0"))
        assertFalse(isNewer("v2.33.0", "2.34.0"))
    }

    @Test
    fun `each component is compared as a number, not a string`() {
        // The one a lexicographic compare gets backwards.
        assertTrue(isNewer("v2.10.0", "2.9.0"))
        assertFalse(isNewer("v2.9.0", "2.10.0"))
        assertTrue(isNewer("v2.34.10", "2.34.9"))
    }

    @Test
    fun `a missing component counts as zero`() {
        assertTrue(isNewer("v2.35", "2.34.9"))
        assertFalse(isNewer("v2.34", "2.34.0"))
        assertTrue(isNewer("v3", "2.34.1"))
    }

    @Test
    fun `a pre-release never outranks the release it precedes`() {
        // -rc.1 is dropped, so v2.35.0-rc.1 reads as 2.35.0.
        assertFalse(isNewer("v2.34.0-rc.1", "2.34.0"))
        assertTrue(isNewer("v2.35.0-rc.1", "2.34.0"))
    }

    @Test
    fun `a tag that carries no number offers nothing`() {
        // Better to miss an update than to offer a download of nothing.
        assertFalse(isNewer("latest", "2.34.0"))
        assertFalse(isNewer("", "2.34.0"))
    }
}
