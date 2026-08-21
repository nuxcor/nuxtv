package com.agoro.tv.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The guide's programmes, on disk instead of on the heap.
 *
 * They were held as one object graph and persisted as one JSON file. Measured
 * on a real playlist that came to 115,779 programmes across 1,534 channels —
 * 32 MB of JSON, near 60 MB of objects — against a 192 MB heap that also has
 * to hold a 12,600-item catalogue. That is what killed the app: not a leak, a
 * working set that was never bounded by anything the viewer could see.
 *
 * Two things make this bounded instead:
 *
 *  - **Descriptions stay in the table.** They are 70% of the text and only
 *    ever rendered for the one programme under the cursor, so they are read a
 *    row at a time by [description] rather than carried for all 115,779.
 *  - **Reads are windowed.** [programmes] answers for the channels on screen
 *    and the hours on screen, so memory tracks the screen rather than the
 *    playlist.
 *
 * It also replaces the gzipped JSON cache outright: the table IS the cache, so
 * a warm start does no decoding at all — which is what "loading the guide"
 * was waiting for even when nothing needed downloading.
 */
class GuideStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    VERSION,
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE programme (
                channel_id TEXT NOT NULL,
                start_ms   INTEGER NOT NULL,
                end_ms     INTEGER NOT NULL,
                title      TEXT NOT NULL,
                description TEXT,
                PRIMARY KEY (channel_id, start_ms)
            )
            """.trimIndent()
        )
        // Every read is "this channel, this window", so the index carries both
        // and the query never scans.
        db.execSQL("CREATE INDEX idx_programme_window ON programme(channel_id, start_ms, end_ms)")
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // The guide is a cache of something re-downloadable, never a source of
        // truth — so a schema change drops it rather than carrying migration
        // code for data that is stale within hours anyway.
        db.execSQL("DROP TABLE IF EXISTS programme")
        db.execSQL("DROP TABLE IF EXISTS meta")
        onCreate(db)
    }

    /**
     * Empties the table, ready for a fresh guide. Its own transaction: the
     * packs that follow commit one at a time.
     */
    fun beginReplace() {
        runCatching {
            writableDatabase.delete("programme", null, null)
            writableDatabase.delete("meta", null, null)
        }
    }

    /**
     * Writes one pack's programmes in a single transaction, with one reused
     * compiled statement. 115,779 row-at-a-time inserts would each be their
     * own transaction and take minutes on the flash in a TV box.
     *
     * A pack at a time, rather than one transaction around the whole guide:
     * an open write transaction holds the connection, and readers share it,
     * so a single wrapping transaction would freeze the grid for the length
     * of a thirteen-pack download — the exact freeze this class exists to
     * end.
     */
    fun insertPack(body: (Sink) -> Unit): Int {
        val db = writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            val statement = db.compileStatement(
                "INSERT OR REPLACE INTO programme " +
                    "(channel_id, start_ms, end_ms, title, description) VALUES (?, ?, ?, ?, ?)"
            )
            body(
                Sink { row ->
                    statement.clearBindings()
                    statement.bindString(1, row.channelId)
                    statement.bindLong(2, row.startMs)
                    statement.bindLong(3, row.endMs)
                    statement.bindString(4, row.title)
                    row.description?.let { statement.bindString(5, it) } ?: statement.bindNull(5)
                    statement.executeInsert()
                    count++
                }
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return count
    }

    /**
     * Marks the table as holding [sourceUrl]'s guide, as of now.
     *
     * Written last and only on a complete ingest, so a download that dies
     * halfway leaves an unstamped table that the next start re-fetches
     * rather than trusting — the rows are still readable in the meantime.
     */
    fun stamp(sourceUrl: String) {
        runCatching {
            val db = writableDatabase
            db.replace("meta", null, ContentValues().apply {
                put("key", KEY_SOURCE)
                put("value", sourceUrl)
            })
            db.replace("meta", null, ContentValues().apply {
                put("key", KEY_INGESTED_AT)
                put("value", System.currentTimeMillis().toString())
            })
        }
    }

    /** Receives rows inside [insertPack]'s transaction. */
    fun interface Sink {
        fun add(row: ProgrammeRow)
    }

    /** Which guide the table holds, and when it was written. Null when empty. */
    fun readStamp(): Pair<String, Long>? = runCatching {
        val db = readableDatabase
        val values = HashMap<String, String>(2)
        db.query("meta", arrayOf("key", "value"), null, null, null, null, null).use { c ->
            while (c.moveToNext()) values[c.getString(0)] = c.getString(1)
        }
        val source = values[KEY_SOURCE] ?: return null
        val at = values[KEY_INGESTED_AT]?.toLongOrNull() ?: return null
        source to at
    }.getOrNull()

    /** Guide ids that actually carry programmes — the matcher's "is this lane empty" test. */
    fun channelsWithProgrammes(): Set<String> = runCatching {
        val out = HashSet<String>()
        readableDatabase.rawQuery("SELECT DISTINCT channel_id FROM programme", null).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        out
    }.getOrElse { emptySet() }

    /**
     * Programmes overlapping [fromMs, toMs] for [channelIds], **without
     * descriptions** — the grid renders titles and times, and carrying 13 MB
     * of synopsis text for cells that never show it is the whole problem this
     * class exists to solve. Sorted by start so the caller can lay a row out
     * in one pass.
     */
    fun programmes(
        channelIds: Collection<String>,
        fromMs: Long,
        toMs: Long,
    ): Map<String, List<EpgProgram>> {
        if (channelIds.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableList<EpgProgram>>(channelIds.size)
        // Chunked: SQLite caps host parameters (999 on older Androids) and a
        // category can hold more channels than that.
        channelIds.chunked(400).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val args = ArrayList<String>(chunk.size + 2)
            args += chunk
            args += fromMs.toString()
            args += toMs.toString()
            runCatching {
                readableDatabase.rawQuery(
                    "SELECT channel_id, start_ms, end_ms, title FROM programme " +
                        "WHERE channel_id IN ($placeholders) AND end_ms > ? AND start_ms < ? " +
                        "ORDER BY channel_id, start_ms",
                    args.toTypedArray(),
                ).use { c ->
                    while (c.moveToNext()) {
                        val channelId = c.getString(0)
                        out.getOrPut(channelId) { ArrayList() } += EpgProgram(
                            id = "$channelId:${c.getLong(1)}",
                            title = c.getString(3),
                            // Read on demand; see [description].
                            description = null,
                            startMs = c.getLong(1),
                            endMs = c.getLong(2),
                            hasArchive = false,
                        )
                    }
                }
            }
        }
        return out
    }

    /**
     * One channel's whole schedule, synopses included.
     *
     * The schedule sheet shows a single channel and puts a line of synopsis
     * under every title, so it reads that channel's descriptions in one
     * query rather than one per visible row. Forty rows of text is nothing;
     * it was carrying all 1,534 channels' worth that was the problem.
     */
    fun schedule(channelId: String, fromMs: Long, toMs: Long): List<EpgProgram> = runCatching {
        val out = ArrayList<EpgProgram>()
        readableDatabase.rawQuery(
            "SELECT start_ms, end_ms, title, description FROM programme " +
                "WHERE channel_id = ? AND end_ms > ? AND start_ms < ? ORDER BY start_ms",
            arrayOf(channelId, fromMs.toString(), toMs.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out += EpgProgram(
                    id = "$channelId:${c.getLong(0)}",
                    title = c.getString(2),
                    description = c.getString(3),
                    startMs = c.getLong(0),
                    endMs = c.getLong(1),
                    hasArchive = false,
                )
            }
        }
        out
    }.getOrElse { emptyList() }

    /** The synopsis for one programme — the only place a lone description is read. */
    fun description(channelId: String, startMs: Long): String? = runCatching {
        readableDatabase.query(
            "programme",
            arrayOf("description"),
            "channel_id = ? AND start_ms = ?",
            arrayOf(channelId, startMs.toString()),
            null, null, null, "1",
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /**
     * When the guide runs out — the last programme the table holds.
     *
     * Drives how far the grid will page forward, so it has to answer for the
     * whole guide rather than the window in memory. One indexed aggregate.
     */
    fun lastProgrammeEndMs(): Long = runCatching {
        readableDatabase.rawQuery("SELECT MAX(end_ms) FROM programme", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        }
    }.getOrDefault(0L)

    fun clear() {
        runCatching {
            writableDatabase.execSQL("DELETE FROM programme")
            writableDatabase.execSQL("DELETE FROM meta")
        }
    }

    companion object {
        private const val DATABASE_NAME = "guide.db"
        private const val VERSION = 1
        private const val KEY_SOURCE = "source_url"
        private const val KEY_INGESTED_AT = "ingested_at"
    }
}

/** One programme on its way into [GuideStore]. */
data class ProgrammeRow(
    val channelId: String,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val description: String?,
)
