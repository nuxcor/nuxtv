package com.agoro.tv.data

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * One fixture, as a body that actually keeps the schedule states it.
 *
 * The panel's slots are marketing strings: four packs write four formats,
 * disagree about the kick-off by hours, fill the competition field with the
 * word "all", and list a women's fixture under the men's club names. This is
 * the answer to all of that — the clubs, the competition and the kick-off, in
 * Zulu, from ESPN's public scoreboards.
 *
 * Built by tools/manifest/fetch_fixtures.py and published beside the manifest,
 * so a schedule that changes daily is a commit rather than a release.
 */
@Serializable
data class ScheduleFixture(
    val league: String = "",
    val home: String = "",
    val away: String = "",
    /** ISO-8601 Zulu, e.g. "2026-09-04T23:30Z". */
    val start: String = "",
) {
    val startMs: Long? by lazy { parseZulu(start) }

    companion object {
        private val formats = listOf("yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")

        fun parseZulu(text: String): Long? {
            if (text.isBlank()) return null
            for (pattern in formats) {
                runCatching {
                    val f = SimpleDateFormat(pattern, Locale.US)
                    f.timeZone = TimeZone.getTimeZone("UTC")
                    f.isLenient = false
                    return f.parse(text)?.time
                }
            }
            return null
        }
    }
}

@Serializable
data class Schedule(
    val generated: String = "",
    val fixtures: List<ScheduleFixture> = emptyList(),
) {
    companion object {
        const val ASSET = "fixtures.json"
        val DEFAULT_REMOTE =
            "https://raw.githubusercontent.com/nuxcor/agoro/main/" +
                "app/src/main/assets/fixtures.json"
    }
}

/**
 * Loads the schedule: bundled asset as the floor, a cached remote copy on top.
 *
 * Refreshed far more often than the manifest — six hours against a day —
 * because a fixture list is only worth what its freshness is: the manifest
 * describes a line-up that drifts over weeks, this describes what is on
 * tonight. The bundled copy is stale the day after a release by construction,
 * and is here only so a box with no network still has something rather than
 * falling back to reading kick-offs out of slot names.
 */
class ScheduleRepository(
    private val context: Context,
    private val http: OkHttpClient,
    private val remoteUrl: String = Schedule.DEFAULT_REMOTE,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cacheFile = File(context.cacheDir, Schedule.ASSET)
    @Volatile private var cached: Schedule? = null

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    suspend fun load(): Schedule? {
        cached?.let { return it }
        refreshIfStale()
        val fresh = runCatching {
            if (cacheFile.exists()) {
                cacheFile.inputStream().use { json.decodeFromStream<Schedule>(it) }
            } else null
        }.getOrNull() ?: runCatching {
            context.assets.open(Schedule.ASSET).use { json.decodeFromStream<Schedule>(it) }
        }.getOrNull()
        cached = fresh
        return fresh
    }

    private fun refreshIfStale() {
        val age = System.currentTimeMillis() - cacheFile.lastModified()
        if (cacheFile.exists() && age < REFRESH_MS) return
        runCatching {
            val request = Request.Builder().url(remoteUrl).header("User-Agent", "Agoro/2.1").build()
            http.newCall(request).execute().use { resp ->
                val body = resp.body ?: return
                if (!resp.isSuccessful) return
                val tmp = File(cacheFile.parentFile, "${Schedule.ASSET}.tmp")
                tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                // Decoded before it is trusted: a truncated download that
                // replaced a good cache would take the schedule out for six
                // hours, and the fixtures are what the Sport tab is FOR.
                val ok = runCatching {
                    tmp.inputStream().use { json.decodeFromStream<Schedule>(it) }
                }.getOrNull()
                if (ok != null && ok.fixtures.isNotEmpty()) tmp.renameTo(cacheFile) else tmp.delete()
            }
        }
    }

    private companion object {
        const val REFRESH_MS = 6 * 60 * 60 * 1000L
    }
}
