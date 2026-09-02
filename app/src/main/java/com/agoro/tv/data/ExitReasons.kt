package com.agoro.tv.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Reads the system's record of how this app last died; see [LastExit].
 *
 * Kept apart from [LastExit] because everything here needs Android and
 * nothing there does, which is what lets the wording and the reason table be
 * tested on the JVM.
 */
object ExitReasons {

    /**
     * The most recent ABNORMAL death, or null — no record, too old an
     * Android, or the app has only ever been closed normally.
     *
     * Asks for a handful rather than one: the last exit is very often an
     * ordinary one (the viewer pressing back, an update landing), and a
     * freeze the night before is still the thing worth reporting. The
     * newest abnormal one within that window wins.
     *
     * Never throws. This runs on the way into an app that is already
     * suspected of dying badly, and a diagnostic that can take the process
     * down with it is worse than no diagnostic at all.
     */
    fun lastAbnormal(context: Context, within: Int = 8): LastExit? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return null
            am.getHistoricalProcessExitReasons(context.packageName, 0, within)
                .firstNotNullOfOrNull { info ->
                    exitLabel(info.reason)?.let { label ->
                        LastExit(
                            label = label,
                            atMs = info.timestamp,
                            pssKb = info.pss,
                            detail = info.description,
                        )
                    }
                }
        }.getOrNull()
    }
}
