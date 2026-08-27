package com.agoro.tv

import com.agoro.tv.data.isNewer
import com.agoro.tv.data.verifyApk
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.rules.TemporaryFolder
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

    @get:Rule val tmp = TemporaryFolder()

    /**
     * "There was a problem parsing the package", 2026-08-27. A download that
     * ends early ends the read loop the same way a finished one does, so a
     * dropped connection wrote a short file that looked like a success.
     */
    @Test
    fun `a truncated download is rejected, not installed`() {
        val f = tmp.newFile("agoro-update.apk")
        f.writeBytes(ByteArray(4096))
        assertNotNull("short of the advertised length", verifyApk(f, expectedBytes = 7_296_263L))
        assertNotNull("and it is not a readable archive either", verifyApk(f, expectedBytes = -1L))
    }

    @Test
    fun `an empty or missing file is rejected`() {
        val gone = java.io.File(tmp.root, "nope.apk")
        assertNotNull(verifyApk(gone, -1L))
        assertNotNull(verifyApk(tmp.newFile("empty.apk"), -1L))
    }

    /** A real zip carrying an AndroidManifest entry is what an APK looks like. */
    @Test
    fun `a complete archive passes`() {
        val f = tmp.newFile("good.apk")
        java.util.zip.ZipOutputStream(f.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("AndroidManifest.xml"))
            zip.write(ByteArray(64))
            zip.closeEntry()
        }
        assertNull("length unknown, archive readable", verifyApk(f, expectedBytes = -1L))
        assertNull("length known and matching", verifyApk(f, expectedBytes = f.length()))
        assertNotNull("length known and NOT matching", verifyApk(f, expectedBytes = f.length() + 1))
    }
}
