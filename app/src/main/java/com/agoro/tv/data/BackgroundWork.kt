package com.agoro.tv.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where the heavy, nobody-is-waiting-on-it work runs: the catalogue fetch
 * and its curation, the cache write, and the guide's download, parse, merge
 * and insert.
 *
 * Not Dispatchers.Default or IO, and the reason is the box this app ships
 * on: a quad-core A53 with Compose drawing on one of those cores. Default's
 * threads run at normal priority in the foreground cgroup, so a parse that
 * saturates three of them competes as an equal with the RenderThread — and
 * the RenderThread loses often enough that a guide refresh reads as the
 * whole UI stuttering. Two threads at [android.os.Process.THREAD_PRIORITY_BACKGROUND]
 * leave the scheduler no doubt about who goes first: the screen, always.
 *
 * Two, not one, because the catalogue refresh and a guide fold routinely
 * overlap (both fire at start-up), and one waiting behind the other would
 * put the guide minutes later than it needs to be. Small, latency-sensitive
 * work — the now-window query, the resolution the UI reads — stays where it
 * was; this is for the work whose only deadline is "eventually".
 */
object BackgroundWork {
    private val counter = AtomicInteger()

    val dispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(2) { runnable ->
        Thread {
            // Wrapped because the host may not be Android at all: the JVM
            // unit tests run against a stubbed android.jar whose Process
            // throws, and a dispatcher that cannot start is worse than one
            // at the wrong priority.
            runCatching {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            }
            runnable.run()
        }.apply {
            name = "agoro-bg-${counter.incrementAndGet()}"
            isDaemon = true
        }
    }.asCoroutineDispatcher()
}
