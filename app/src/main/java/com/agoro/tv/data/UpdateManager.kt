package com.agoro.tv.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.agoro.tv.BuildConfig
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
        const val LATEST_URL = "https://github.com/nuxcor/nuxtv/releases/latest"
        const val DOWNLOAD_BASE = "https://github.com/nuxcor/nuxtv/releases/download"

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

    suspend fun download(apkUrl: String, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
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
            }
            out
        }

    /** Launches the system installer; false when it can't be started. */
    fun install(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
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
