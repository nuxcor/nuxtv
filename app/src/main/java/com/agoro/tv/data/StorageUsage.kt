package com.agoro.tv.data

import android.content.Context
import com.agoro.tv.recording.RecordingManager
import java.io.File

/**
 * What the app is holding on disk, and what of it is safe to throw away.
 *
 * Written after a box reported 688MB against this app and then could not
 * install a 7MB update — Android needs room for the download AND the install
 * staging, and nothing in the app had ever told the viewer where the space
 * went or offered to give any of it back.
 *
 * The split that matters is not by folder, it is by whether losing it costs
 * anything. Caches re-fetch on demand; recordings are the only thing here a
 * viewer asked the box to keep, so they are counted and reported and never
 * touched by [clearCaches].
 */
object StorageUsage {

    /** A cache directory and what it holds, in bytes. */
    data class Report(
        val imagesBytes: Long,
        val guideBytes: Long,
        val updatesBytes: Long,
        val otherCacheBytes: Long,
        val recordingsBytes: Long,
        val recordingsCount: Int,
    ) {
        /** Everything [clearCaches] would remove. */
        val reclaimableBytes: Long
            get() = imagesBytes + guideBytes + updatesBytes + otherCacheBytes
    }

    private fun File.sizeOf(): Long = when {
        !exists() -> 0L
        isFile -> length()
        // walkBottomUp rather than recursion: the image cache is thousands of
        // small files and a deep recursive call per directory is the kind of
        // thing that only shows up on the slowest box.
        else -> walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    fun report(context: Context): Report {
        val cache = context.cacheDir
        val images = File(cache, IMAGE_CACHE_DIR).sizeOf()
        val updates = File(cache, "updates").sizeOf()
        val guide = (cache.listFiles { f -> f.name.startsWith("epg-pack") } ?: emptyArray())
            .sumOf { it.sizeOf() } +
            File(context.filesDir, "epg-index.json.gz").sizeOf() +
            File(context.filesDir, "epg-cache.json.gz").sizeOf()
        // Whatever else has accumulated in cacheDir, so the total a viewer is
        // shown adds up to what Android's own storage screen says rather than
        // to a subset this file happened to think of.
        val counted = setOf(IMAGE_CACHE_DIR, "updates")
        val other = (cache.listFiles() ?: emptyArray())
            .filter { it.name !in counted && !it.name.startsWith("epg-pack") }
            .sumOf { it.sizeOf() }
        val recordings = RecordingManager.directory(context)
        val files = (recordings.listFiles() ?: emptyArray()).filter { it.isFile }
        return Report(
            imagesBytes = images,
            guideBytes = guide,
            updatesBytes = updates,
            otherCacheBytes = other,
            recordingsBytes = files.sumOf { it.length() },
            recordingsCount = files.size,
        )
    }

    /**
     * Throws away everything that re-fetches, and returns what it freed.
     *
     * Recordings are not in here and must not be. They are the one thing on
     * disk the viewer asked for, and a button that quietly deletes them is a
     * worse fault than the one it is solving.
     *
     * The guide index and cache live in filesDir rather than cacheDir, so
     * Android's own "clear cache" leaves them; they are the second largest
     * thing here and they rebuild from the next EPG fetch, so they belong in
     * a cleanup the app offers itself.
     */
    fun clearCaches(context: Context): Long {
        val before = report(context)
        val cache = context.cacheDir
        (cache.listFiles() ?: emptyArray()).forEach { it.deleteRecursively() }
        File(context.filesDir, "epg-index.json.gz").delete()
        File(context.filesDir, "epg-cache.json.gz").delete()
        return before.reclaimableBytes
    }

    /** Coil's disk cache lives here; see NuxTvApp. */
    const val IMAGE_CACHE_DIR = "image_cache"

    /**
     * The ceiling on cached artwork.
     *
     * Coil's default is a share of free space capped at 250MB, which is a
     * quarter of a gigabyte of posters on a box that ships with a handful to
     * spare. 64MB still holds the shelves a viewer actually walks; what falls
     * out is the tail they scrolled past once, and it costs a re-fetch.
     */
    const val IMAGE_CACHE_MAX_BYTES = 64L * 1024 * 1024
}
