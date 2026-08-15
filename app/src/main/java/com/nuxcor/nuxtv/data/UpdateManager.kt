package com.nuxcor.nuxtv.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.nuxcor.nuxtv.BuildConfig
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * In-app self-updater: checks the GitHub releases feed, downloads the APK,
 * and hands it to the system installer. Same signing key on every release,
 * so updates install over the top with all data intact.
 */
class UpdateManager(private val context: Context, private val http: OkHttpClient) {

    sealed class State {
        data object Idle : State()
        data object Checking : State()
        data object UpToDate : State()
        data class Available(val version: String, val apkUrl: String, val sizeBytes: Long) : State()
        data class Downloading(val progressPercent: Int) : State()
        data class Ready(val version: String, val file: File) : State()
        data class Error(val message: String) : State()
    }

    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        const val LATEST_URL = "https://api.github.com/repos/nuxcor/nuxtv/releases/latest"

        /** true when [remote] (e.g. "v2.4.0") is newer than [local] ("2.3.1"). */
        fun isNewer(remote: String, local: String): Boolean {
            fun parts(v: String) = v.removePrefix("v").split(".", "-")
                .mapNotNull { it.toIntOrNull() }
            val r = parts(remote)
            val l = parts(local)
            for (i in 0 until maxOf(r.size, l.size)) {
                val a = r.getOrElse(i) { 0 }
                val b = l.getOrElse(i) { 0 }
                if (a != b) return a > b
            }
            return false
        }
    }

    suspend fun check(): State = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(LATEST_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Dzidzi/${BuildConfig.VERSION_NAME}")
                .build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val root = json.parseToJsonElement(resp.body!!.string()).jsonObject
                val tag = (root["tag_name"] as? JsonPrimitive)?.contentOrNull
                    ?: throw IOException("No tag in release")
                val asset = (root["assets"] as? JsonArray)
                    ?.filterIsInstance<JsonObject>()
                    ?.firstOrNull {
                        (it["name"] as? JsonPrimitive)?.contentOrNull?.endsWith(".apk") == true
                    } ?: throw IOException("No APK in latest release")
                val url = (asset["browser_download_url"] as? JsonPrimitive)?.contentOrNull
                    ?: throw IOException("No download URL")
                val size = (asset["size"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
                if (isNewer(tag, BuildConfig.VERSION_NAME)) {
                    State.Available(version = tag, apkUrl = url, sizeBytes = size)
                } else {
                    State.UpToDate
                }
            }
        }.getOrElse { e -> State.Error(e.message ?: "Update check failed") }
    }

    suspend fun download(apkUrl: String, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "dzidzi-update.apk")
            val request = Request.Builder()
                .url(apkUrl)
                .header("User-Agent", "Dzidzi/${BuildConfig.VERSION_NAME}")
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

    fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "com.nuxcor.nuxtv.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
