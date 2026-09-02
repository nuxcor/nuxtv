package com.agoro.tv.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Why the app last died, as the SYSTEM recorded it.
 *
 * Reported 2026-09-02 as "app sometimes freezes and closes". Nothing in the
 * app could answer that: there is no uncaught-exception handler, nothing
 * writes a crash file, and a process that is killed does not get to log its
 * own death anyway. So the report could not be acted on at all — a freeze
 * that ends in the launcher leaves the viewer with nothing to tell anyone,
 * and "sometimes" is exactly the shape of a bug that is gone by the time you
 * go looking.
 *
 * Android has kept the answer since API 30. `getHistoricalProcessExitReasons`
 * returns the last several deaths of this package with a reason code, a
 * timestamp and the resident memory at the time — which separates the three
 * causes that matter here and cannot otherwise be told apart from the outside:
 * the app stopped responding, the app crashed, or the box ran out of memory
 * and killed it. On a 2GB Chromecast holding an 18,800-stream catalogue the
 * third is not a remote possibility.
 *
 * Read, not sent. It goes to the log and to one line in Settings, so the
 * viewer can read it out. Nothing leaves the box.
 */
data class LastExit(
    /** What happened, in words a viewer can repeat. */
    val label: String,
    /** When it happened, epoch millis. */
    val atMs: Long,
    /** Resident memory at death in kilobytes; 0 when the system did not say. */
    val pssKb: Long,
    /** The system's own one-line description, when it gave one. */
    val detail: String?,
)

/**
 * Exit reasons worth telling the viewer about, and what to call them.
 *
 * Keyed by the framework's own `ApplicationExitInfo.REASON_*` values, written
 * as literals so this file stays free of Android and can be tested on the
 * JVM. They are frozen public API — a reason code cannot be renumbered
 * without breaking every app that has ever read one.
 *
 * Everything absent from this map is an ORDINARY death and must stay absent:
 * the viewer backing out (REASON_USER_REQUESTED, 1), the system trimming a
 * backgrounded app (REASON_USER_STOPPED, 11; REASON_OTHER, 13), an update
 * replacing the package (REASON_PACKAGE_UPDATED, 12), the app stopping
 * itself (REASON_EXIT_SELF, 0). Reporting those would put a scary line in
 * Settings after every normal evening.
 */
private val ABNORMAL_EXITS: Map<Int, String> = mapOf(
    3 to "the box ran out of memory",        // REASON_LOW_MEMORY
    4 to "the app crashed",                  // REASON_CRASH
    5 to "the player crashed",               // REASON_CRASH_NATIVE — a decoder, in practice
    6 to "the app stopped responding",       // REASON_ANR
    9 to "the app used too much memory",     // REASON_EXCESSIVE_RESOURCE_USAGE
)

/** The label for an exit reason, or null when the death was an ordinary one. */
fun exitLabel(reason: Int): String? = ABNORMAL_EXITS[reason]

/**
 * The Settings line: "Stopped responding on 2 Sep at 19:40, using 412 MB".
 *
 * Memory is included only when the system reported it AND the death was one
 * where memory is the question — an out-of-memory kill or an ANR, where a box
 * thrashing on a full heap is the usual cause. On a plain crash the number is
 * noise that invites the wrong theory.
 */
fun LastExit.sentence(zone: ZoneId = ZoneId.systemDefault()): String {
    val when0 = DateTimeFormatter.ofPattern("d MMM 'at' HH:mm")
        .withZone(zone)
        .format(Instant.ofEpochMilli(atMs))
    val memoryMatters = label.contains("memory") || label.contains("responding")
    val memory = if (pssKb > 0 && memoryMatters) ", using ${pssKb / 1024} MB" else ""
    return label.replaceFirstChar { it.uppercase() } + " on $when0$memory"
}
