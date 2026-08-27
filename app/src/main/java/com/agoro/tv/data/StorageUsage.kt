package com.agoro.tv.data

import android.content.Context
import coil3.SingletonImageLoader
import java.io.File

/**
 * What the app is holding on disk, and what of it is safe to throw away.
 *
 * Written after a box reported 688MB against this app and then could not
 * install a 7MB update — Android needs room for the download AND the install
 * staging, and nothing in the app had ever told the viewer where the space
 * went or offered any of it back.
 *
 * The split that matters is not by folder, it is by what losing it costs.
 * Caches re-fetch, so [clearCaches] takes them. The catalogue costs a full
 * re-download, so it is reported and never offered.
 *
 * Recordings are a viewer's own content and [clearCaches] does not touch them.
 * They get their own action, [deleteLegacyRecordings], behind its own
 * confirmation — recording was removed in 2.35.22 and took the Recordings tab
 * with it, so without a deliberate way to delete them these files would sit
 * there with nothing in the app able to reach them.
 */
object StorageUsage {

    /**
     * A measurement of everything this app has on disk.
     *
     * [reclaimableBytes] is the only part [clearCaches] will remove; the
     * catalogue and the recordings are counted so the panel adds up to what
     * Android's own storage screen says rather than to a subset, and a viewer
     * who clears everything on offer and still sees 600MB has an explanation
     * rather than a mystery.
     */
    data class Report(
        val imagesBytes: Long,
        val guideBytes: Long,
        val updatesBytes: Long,
        val otherCacheBytes: Long,
        /** The playlist bundle and its EPG id map. Re-downloads; not cleared. */
        val catalogueBytes: Long,
        /** Left over from the removed recording feature; see [legacyRecordings]. */
        val recordingsBytes: Long,
        val recordingsCount: Int,
    ) {
        val reclaimableBytes: Long
            get() = imagesBytes + guideBytes + updatesBytes + otherCacheBytes
    }

    private fun File.sizeOf(): Long = when {
        !exists() -> 0L
        isFile -> length()
        // walkBottomUp rather than recursion: the image cache is thousands of
        // small files and a deep call per directory is the kind of thing that
        // only shows up on the slowest box.
        else -> walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    /** Cache entries no cleanup may remove; see [clearCaches]. */
    private fun protectedCacheNames() = setOf("catalogue-manifest.json")

    private fun guideTemps(cache: File) =
        (cache.listFiles { f -> f.name.startsWith("epg-pack") } ?: emptyArray()).toList()

    fun report(context: Context): Report {
        val cache = context.cacheDir
        val images = File(cache, IMAGE_CACHE_DIR).sizeOf()
        val updates = File(cache, UPDATES_DIR).sizeOf()
        val guide = guideTemps(cache).sumOf { it.sizeOf() } +
            File(context.filesDir, EPG_INDEX).sizeOf()
        val named = setOf(IMAGE_CACHE_DIR, UPDATES_DIR)
        val other = (cache.listFiles() ?: emptyArray())
            .filter { it.name !in named && !it.name.startsWith("epg-pack") }
            .sumOf { it.sizeOf() }
        // The catalogue: bundle-<source>.json and tvg-<source>.txt. Usually the
        // largest thing this app owns, and it was missing from the first cut of
        // this report — so on the very box that prompted it, the panel would
        // have shown tens of megabytes against Android's six hundred.
        val catalogue = (context.filesDir.listFiles { f ->
            f.name.startsWith("bundle-") || f.name.startsWith("tvg-")
        } ?: emptyArray()).sumOf { it.sizeOf() }
        val files = (legacyRecordings(context)?.listFiles() ?: emptyArray()).toList()
        return Report(
            imagesBytes = images,
            guideBytes = guide,
            updatesBytes = updates,
            otherCacheBytes = other,
            catalogueBytes = catalogue,
            recordingsBytes = files.sumOf { it.sizeOf() },
            recordingsCount = files.size,
        )
    }

    /**
     * Throws away what re-fetches, and returns what it ACTUALLY freed.
     *
     * Deliberately not a wipe of cacheDir, which is what this was first and
     * which broke three things at once:
     *
     *  - It deleted Coil's live cache directory out from under the open
     *    ImageLoader. The journal then pointed at an unlinked inode, okio
     *    swallowed the errors, and disk caching silently stopped working for
     *    the rest of the process — every poster re-downloading on a Wi-Fi-only
     *    box while the panel cheerfully reported "Artwork 0 KB". Coil's own
     *    clear() trims through its bookkeeping and leaves the cache usable.
     *
     *  - It deleted a staged update APK, and the update flow has no way back
     *    from Ready to Available, so Install then failed forever blaming a
     *    permission. The staged file is left alone here; it is seven megabytes
     *    and it is about to be installed.
     *
     *  - It deleted catalogue-manifest.json, dropping the box back to the
     *    curation bundled in the APK until the next fetch. A few kilobytes,
     *    and this project's rule is that the manifest is authoritative.
     *
     * Recordings are not here and must not be. They are the one thing on this
     * disk the viewer asked for, and a button that quietly deletes them is a
     * worse fault than the one it is solving.
     */
    fun clearCaches(context: Context): Long {
        var freed = 0L
        // Through Coil's own API, so the cache stays usable afterwards.
        val images = File(context.cacheDir, IMAGE_CACHE_DIR).sizeOf()
        runCatching { SingletonImageLoader.get(context).diskCache?.clear() }
            .onSuccess { freed += images - File(context.cacheDir, IMAGE_CACHE_DIR).sizeOf() }
        // Guide: the leftover pack temporaries and the index. The index lives
        // in filesDir, where Android's own "clear cache" cannot reach it, and
        // it rebuilds from the next fetch.
        for (f in guideTemps(context.cacheDir)) {
            val n = f.sizeOf()
            if (f.deleteRecursively()) freed += n
        }
        File(context.filesDir, EPG_INDEX).let { if (it.isFile) { val n = it.length(); if (it.delete()) freed += n } }
        // Whatever else has collected in cacheDir, minus what must survive.
        val keep = protectedCacheNames() + setOf(IMAGE_CACHE_DIR, UPDATES_DIR)
        for (f in context.cacheDir.listFiles() ?: emptyArray()) {
            if (f.name in keep || f.name.startsWith("epg-pack")) continue
            val n = f.sizeOf()
            if (f.deleteRecursively()) freed += n
        }
        // Counted from what actually went, never from what was intended:
        // deleteRecursively returns false on a partial failure, and reporting
        // "Freed 300 MB" over a file still on disk is the panel lying about
        // the one thing it exists to be right about.
        return freed
    }

    /**
     * Where recordings were kept, before the feature was removed (2026-08-27).
     *
     * The files outlive the feature: they are a viewer's own content and this
     * app is not going to delete them on an upgrade because a menu item went
     * away. They are counted so the space is not a mystery, and Settings
     * offers to delete them, which is now the only way to get it back.
     */
    fun legacyRecordings(context: Context): File? =
        // Null when external storage is unavailable. File(null, "recordings")
        // is a RELATIVE path resolved against the process working directory,
        // so measuring or deleting through it would touch something that has
        // nothing to do with this app.
        context.getExternalFilesDir(null)?.let { File(it, "recordings") }

    /** Removes the old recordings, and returns what that freed. */
    fun deleteLegacyRecordings(context: Context): Long {
        var freed = 0L
        // deleteRecursively and sizeOf, so a subdirectory is counted and taken
        // rather than reported as zero bytes and left holding space — report()
        // filters to isFile, and the two must agree about what is there.
        for (f in legacyRecordings(context)?.listFiles() ?: emptyArray()) {
            val n = f.sizeOf()
            if (f.deleteRecursively()) freed += n
        }
        return freed
    }

    const val IMAGE_CACHE_DIR = "image_cache"
    internal const val UPDATES_DIR = "updates"
    private const val EPG_INDEX = "epg-index.json.gz"

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
