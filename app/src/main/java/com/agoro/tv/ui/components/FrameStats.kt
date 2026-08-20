package com.agoro.tv.ui.components

import android.view.Choreographer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxShape

/**
 * What the TV actually renders, measured on the TV.
 *
 * An emulator cannot answer this — its software renderer janks nearly every
 * frame on a screen with one poster row — and "feels slow" cannot be acted on.
 * This reads the frame clock the display itself runs on, so a viewer can say
 * "p95 is 210ms in the guide" and that is a fact to fix rather than a mood to
 * argue with.
 *
 * Costs one Choreographer callback per frame and no allocation, but it is
 * still off unless asked for: an overlay that reports on the frame it is drawn
 * into is measuring itself as well.
 */
private const val WINDOW = 120

class FrameStatsCollector {
    private val intervalsMs = LongArray(WINDOW)
    private var count = 0
    private var writeIndex = 0
    private var lastFrameNs = 0L

    var summary by mutableStateOf("measuring…")
        private set

    internal fun onFrame(frameTimeNs: Long) {
        if (lastFrameNs != 0L) {
            val deltaMs = (frameTimeNs - lastFrameNs) / 1_000_000
            intervalsMs[writeIndex] = deltaMs
            writeIndex = (writeIndex + 1) % WINDOW
            if (count < WINDOW) count++
            if (writeIndex % 15 == 0) recompute()
        }
        lastFrameNs = frameTimeNs
    }

    private fun recompute() {
        if (count == 0) return
        val sorted = intervalsMs.copyOf(count).sortedArray()
        val p50 = sorted[count / 2]
        val p95 = sorted[(count * 95 / 100).coerceAtMost(count - 1)]
        // A frame is late past ~20ms on a 50/60Hz panel; the percentage is
        // what "not fluid" means in a number.
        val late = sorted.count { it > 20 } * 100 / count
        summary = "p50 ${p50}ms · p95 ${p95}ms · late ${late}%"
    }
}

@Composable
fun FrameStatsOverlay(enabled: Boolean, modifier: Modifier = Modifier) {
    if (!enabled) return
    val collector = remember { FrameStatsCollector() }
    DisposableEffect(collector) {
        val choreographer = Choreographer.getInstance()
        var running = true
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                collector.onFrame(frameTimeNanos)
                if (running) choreographer.postFrameCallback(this)
            }
        }
        choreographer.postFrameCallback(callback)
        onDispose {
            running = false
            choreographer.removeFrameCallback(callback)
        }
    }
    Text(
        text = collector.summary,
        style = MaterialTheme.typography.labelSmall,
        color = NuxColors.OnSurface,
        modifier = modifier
            .background(NuxColors.Scrim, NuxShape.Row)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
