package com.nuxcor.nuxtv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromStream
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Fills in missing channel logos from the community tv-logo/tv-logos repo
 * (github.com/tv-logo/tv-logos). The repo's file tree is fetched once via the
 * GitHub API and cached on disk for a week; channels are matched by
 * normalized name against the logo file names.
 */
class LogoRepository(context: Context, private val http: OkHttpClient) {

    private val cacheFile = File(context.cacheDir, "tvlogos-index.txt")
    private val json = Json { ignoreUnknownKeys = true }

    /** normalized-name → raw.githubusercontent URL */
    private var index: Map<String, String>? = null

    private companion object {
        const val TREE_URL =
            "https://api.github.com/repos/tv-logo/tv-logos/git/trees/main?recursive=1"
        const val RAW_BASE = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/"
        const val CACHE_TTL_MS = 7L * 24 * 3600 * 1000

        val qualitySuffix = Regex("""(?i)\b(4k|uhd|fhd|full\s?hd|hd|sd|1080p?|720p?|hevc|h\.?26[45]|vip|\+?\d)\b""")
        val nonAlnum = Regex("""[^a-z0-9]+""")

        /** "CNN International HD" → "cnn-international" */
        fun normalize(name: String): String =
            name.lowercase()
                .replace(qualitySuffix, " ")
                .replace(nonAlnum, "-")
                .trim('-')
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private suspend fun loadIndex(): Map<String, String> {
        index?.let { return it }
        // An empty result is never cached below: one transient failure (or an
        // unauthenticated GitHub rate-limit, which is easy to hit) would
        // otherwise mean no channel gets a logo until the app is force-stopped.
        val paths: List<String> = withContext(Dispatchers.IO) {
            val cached = cacheFile.takeIf {
                it.exists() && System.currentTimeMillis() - it.lastModified() < CACHE_TTL_MS
            }?.readLines()
            val stale = cacheFile.takeIf { it.exists() }?.readLines().orEmpty()
            cached ?: runCatching {
                val request = Request.Builder()
                    .url(TREE_URL)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "Agoro/1.0")
                    .build()
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val root = json.decodeFromStream<JsonObject>(resp.body!!.byteStream())
                    val tree = root["tree"] as? JsonArray ?: return@use emptyList()
                    tree.mapNotNull { el ->
                        ((el as? JsonObject)?.get("path") as? JsonPrimitive)?.contentOrNull
                    }.filter { it.startsWith("countries/") && it.endsWith(".png") }
                }.also { list ->
                    if (list.isNotEmpty()) cacheFile.writeText(list.joinToString("\n"))
                }
            }.getOrNull()?.takeIf { it.isNotEmpty() }
                // A usable stale copy beats no logos at all.
                ?: stale
        }

        val built = HashMap<String, String>(paths.size * 2)
        for (path in paths) {
            val base = path.substringAfterLast('/').removeSuffix(".png")
            val url = RAW_BASE + path
            // File names end with a country code: "sky-sports-main-event-uk".
            built.putIfAbsent(base, url)
            val withoutCountry = base.substringBeforeLast('-', base)
            if (withoutCountry.length > 2) built.putIfAbsent(withoutCountry, url)
        }
        // Only memoise a usable index, so a failed fetch is retried next time
        // instead of pinning an empty map for the life of the process.
        if (built.isNotEmpty()) index = built
        return built
    }

    /** Best-effort logo URL for a channel name, or null when nothing matches. */
    suspend fun logoFor(channelName: String): String? {
        val idx = loadIndex()
        if (idx.isEmpty()) return null
        val key = normalize(channelName)
        if (key.isBlank()) return null
        return idx[key] ?: idx[key.replace("-", "")]
    }

    /** Fills missing logos on a bundle's channels; returns null when nothing changed. */
    /**
     * Called from viewModelScope (Main). Once the index is cached nothing here
     * suspends, so without an explicit dispatcher the whole per-channel
     * normalize-and-match loop ran on the main thread — seconds of jank at
     * startup on the six-figure playlists this app is built for.
     */
    suspend fun enrich(bundle: ContentBundle): ContentBundle? {
        val missing = bundle.channels.count { it.logo.isNullOrBlank() }
        if (missing == 0) return null
        loadIndex() // suspends on IO the first time; cheap thereafter
        return withContext(Dispatchers.Default) {
            var changed = false
            val channels = bundle.channels.map { channel ->
                if (!channel.logo.isNullOrBlank()) channel
                else logoFor(channel.name)?.let { changed = true; channel.copy(logo = it) } ?: channel
            }
            if (changed) bundle.copy(channels = channels) else null
        }
    }
}
