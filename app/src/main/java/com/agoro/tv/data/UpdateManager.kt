package com.agoro.tv.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.agoro.tv.BuildConfig
import com.agoro.tv.data.StorageUsage.UPDATES_DIR
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * In-app self-updater: checks the GitHub releases feed, downloads the APK,
 * and hands it to the system installer. Same signing key on every release,
 * so updates install over the top with all data intact.
 */
/**
 * true when [remote] (e.g. "v2.4.0") is newer than [local] ("2.3.1").
 *
 * The whole update feature turns on this: it decides whether the rail shows a
 * row and whether Settings offers a download. Top-level and internal so it can
 * be, because every part of it is a silent failure — the leading "v" the
 * release tag carries, a missing third component, and the pre-release suffix
 * that must never outrank the release it precedes.
 */
internal fun isNewer(remote: String, local: String): Boolean {
    // Only the numeric x.y.z triple counts; suffixes like -rc.1 are
    // ignored so an RC never outranks the final release.
    fun parts(v: String) = v.removePrefix("v").substringBefore("-").split(".")
        .mapNotNull { it.toIntOrNull() }.take(3)
    val r = parts(remote)
    val l = parts(local)
    for (i in 0 until maxOf(r.size, l.size)) {
        val a = r.getOrElse(i) { 0 }
        val b = l.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return false
}

    /**
 * Why this file is not an installable APK, or null when it is.
 *
 * Two checks, because they fail differently. The length catches a
 * truncated download, which is the common one. Opening it as a zip and
 * asking for AndroidManifest.xml catches an archive that arrived complete
 * but corrupt, and costs a central-directory read rather than a scan of
 * seven megabytes.
 *
 * Pure and internal so it can be tested without a network or a device.
 */
internal fun verifyApk(file: File, expectedBytes: Long): String? {
    if (!file.exists()) return "The download did not finish."
    val got = file.length()
    if (got == 0L) return "The download was empty."
    if (expectedBytes > 0 && got != expectedBytes) {
        return "The download was cut short (${got / 1024} of ${expectedBytes / 1024} KB)."
    }
    val readable = runCatching {
        java.util.zip.ZipFile(file).use { it.getEntry("AndroidManifest.xml") != null }
    }.getOrDefault(false)
    if (!readable) return "The download arrived damaged."
    return null
}

class UpdateManager(private val context: Context, private val http: OkHttpClient) {

    sealed class State {
        data object Idle : State()
        data object Checking : State()
        data object UpToDate : State()
        data class Available(val version: String, val apkUrl: String, val sizeBytes: Long) : State()
        data class Downloading(val progressPercent: Int) : State()
        /**
         * Downloaded. [note] carries why the last install attempt did not
         * start (the unknown-sources permission, usually) — the file is kept
         * so fixing that costs a press, not a second download.
         */
        data class Ready(val version: String, val file: File, val note: String? = null) : State()
        data class Error(val message: String) : State()
    }

    private companion object {
        /**
         * The website, not api.github.com: the API allows 60 unauthenticated
         * requests per hour PER IP, shared by everyone behind it — on carrier
         * NAT the check answered 403 routinely. The web URL redirects to
         * /releases/tag/vX.Y.Z, which carries everything the check needs, and
         * is not rate-limited like the API.
         */
        const val LATEST_URL = "https://github.com/nuxcor/agoro/releases/latest"
        const val DOWNLOAD_BASE = "https://github.com/nuxcor/agoro/releases/download"

    }

    suspend fun check(): State = withContext(Dispatchers.IO) {
        runCatching {
            // Redirects handled by hand: the Location header IS the answer.
            val probe = http.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val request = Request.Builder()
                .url(LATEST_URL)
                .head()
                .header("User-Agent", "Agoro/${BuildConfig.VERSION_NAME}")
                .build()
            val tag = probe.newCall(request).execute().use { resp ->
                val location = resp.header("Location")
                if (resp.code !in 300..399 || location == null) {
                    throw IOException("HTTP ${resp.code}")
                }
                location.substringAfter("/releases/tag/", "")
                    .substringBefore('?')
                    .takeIf { it.isNotBlank() }
                    ?: throw IOException("No release tag")
            }
            if (!isNewer(tag, BuildConfig.VERSION_NAME)) return@runCatching State.UpToDate
            // Pinned to the tag, so a release published mid-download can't
            // swap the file under us. The workflow guarantees a plain
            // agoro.apk asset on every release.
            val apkUrl = "$DOWNLOAD_BASE/$tag/agoro.apk"
            // Size is cosmetic ("(88 MB)" in Settings); a failed HEAD is not
            // a failed check.
            val size = runCatching {
                http.newCall(
                    Request.Builder().url(apkUrl).head()
                        .header("User-Agent", "Agoro/${BuildConfig.VERSION_NAME}")
                        .build()
                ).execute().use { it.header("Content-Length")?.toLongOrNull() }
            }.getOrNull() ?: 0L
            State.Available(version = tag, apkUrl = apkUrl, sizeBytes = size)
        }.getOrElse { e -> State.Error("Update check failed — ${e.userMessage("try again later")}") }
    }

    suspend fun download(apkUrl: String, expectedBytes: Long = 0L, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            // Ask the system for the room BEFORE anything else — before the
            // directory exists, and this order matters. allocateBytes frees
            // space by clearing cached data, OURS INCLUDED, so allocating
            // after mkdirs can delete the very directory the download is about
            // to open; the write then fails outright on exactly the low-space
            // box this call exists to rescue. Allocating against the data
            // volume rather than a path inside cacheDir avoids asking about a
            // directory that may not survive the asking.
            //
            // allocateBytes is the sanctioned way to do this and the app was
            // not using it: the download simply started writing and found out
            // how it went. On a box with 688MB of this app's own caches and
            // little free space, that is how a download ends short — and a
            // short APK is what Android calls "a problem parsing the package".
            //
            // The call clears OTHER apps' caches, and ours, to make the space;
            // it is the OS's job to decide whose, not ours. Best effort: an
            // older device without the API, or one that cannot free enough,
            // just proceeds as before and verifyApk catches the fallout.
            if (android.os.Build.VERSION.SDK_INT >= 26 && expectedBytes > 0) {
                runCatching {
                    val sm = context.getSystemService(android.os.storage.StorageManager::class.java)
                    // Twice the archive: the installer stages a copy of its
                    // own, so room for one is room to download and then fail.
                    sm.allocateBytes(sm.getUuidForPath(context.dataDir), expectedBytes * 2)
                }
            }
            val dir = File(context.cacheDir, UPDATES_DIR).apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "agoro-update.apk")
            val request = Request.Builder()
                .url(apkUrl)
                .header("User-Agent", "Agoro/${BuildConfig.VERSION_NAME}")
                .build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("Empty download")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    out.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (total > 0) onProgress((done * 100 / total).toInt())
                        }
                    }
                }
                // Every byte, or none. A stream that ends early ends the loop
                // above perfectly normally — read() returns -1 whether the
                // body finished or the socket died — so a dropped connection
                // on a Wi-Fi-only box produced a SHORT FILE that looked like a
                // successful download. Android's answer to a truncated archive
                // is "There was a problem parsing the package", which names
                // the symptom and hides the cause, and install()'s only guard
                // was length() == 0, which a truncated file walks straight
                // past.
                // Content-Length first, the release feed's figure second.
                // Threading expectedBytes only into the allocation left the
                // truncation guard with nothing to check whenever the response
                // was chunked or a proxy stripped the header — which is the
                // case this whole guard exists for.
                val expected = if (total > 0) total else expectedBytes
                verifyApk(out, expected)?.let { why ->
                    out.delete()          // never leave a bad archive to be retried into
                    throw IOException(why)
                }
            }
            out
        }


    /** Launches the system installer; false when it can't be started. */
    fun install(file: File): Boolean {
        // Re-checked here, not just after the download: the file sits in
        // cacheDir between the two, and cacheDir is the first thing Android
        // empties when the box runs short of space.
        if (verifyApk(file, expectedBytes = -1L) != null) return false
        val uri = if (android.os.Build.VERSION.SDK_INT >= 24) {
            FileProvider.getUriForFile(context, "com.agoro.tv.fileprovider", file)
        } else {
            // Pre-N installers can't open content:// package archives.
            android.net.Uri.fromFile(file)
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
