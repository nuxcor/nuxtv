@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuxcor.nuxtv.ui.screens

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nuxcor.nuxtv.data.EngineChoice
import com.nuxcor.nuxtv.data.LiveChannel
import com.nuxcor.nuxtv.data.PlayableItem
import com.nuxcor.nuxtv.player.ExoEngine
import com.nuxcor.nuxtv.player.PlayerEngine
import com.nuxcor.nuxtv.player.VlcEngine
import kotlinx.coroutines.delay

/**
 * How long focus has to rest on a channel before its stream is opened.
 *
 * This is the entire safety margin. A provider caps concurrent connections —
 * commonly at one or two — and every preview holds one for as long as it runs,
 * with the slot often slow to come back. Opening on arrival rather than on
 * rest would burn the whole allowance walking down a category.
 */
private const val PREVIEW_DWELL_MS = 1_500L

/**
 * A single muted player the guide points at whatever channel focus is resting
 * on. One engine for the lifetime of the guide, re-prepared as focus moves —
 * never one per channel.
 *
 * Moving along a row costs nothing: the channel doesn't change, so nothing is
 * re-prepared. Only moving between channels opens a stream, and only after the
 * dwell above.
 */
class GuidePreviewController internal constructor(
    private val newEngine: () -> PlayerEngine,
) {
    internal var engine: PlayerEngine? = null
        private set

    /** Bumped on every teardown so a stale surface is never reattached. */
    internal var generation by mutableStateOf(0)
        private set

    internal fun play(channel: LiveChannel) {
        val target = engine ?: newEngine().also { engine = it }
        target.setMuted(true)
        target.prepare(
            listOf(PlayableItem(url = channel.url, title = channel.name, channelId = channel.id)),
            startIndex = 0,
        )
        // libVLC ignores volume until media is open, so say it again after.
        target.setMuted(true)
    }

    /**
     * Stops previewing and gives the connection back.
     *
     * Call this before navigating to the player, not merely on disposal. The
     * player builds its own engine as it composes, and on a two-connection line
     * that overlaps with a preview still holding one — the stream the viewer
     * actually asked for is then the one that gets refused. Leaving it to
     * whichever DisposableEffect happens to run first is not an ordering.
     */
    fun release() {
        engine?.release()
        engine = null
        generation++
    }
}

@Composable
fun rememberGuidePreview(engineChoice: EngineChoice, highestQuality: Boolean): GuidePreviewController {
    val context = LocalContext.current
    val controller = remember(engineChoice) {
        GuidePreviewController {
            if (engineChoice == EngineChoice.VLC) VlcEngine(context, highestQuality)
            else ExoEngine(context)
        }
    }
    DisposableEffect(controller) { onDispose { controller.release() } }

    // Leaving the app must hand the connection back too — a preview left
    // running behind the launcher holds a slot the viewer cannot see or stop.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller.release()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return controller
}

/**
 * Drives [controller] from the focused channel. Split out so the guide's header
 * stays a layout concern and this stays a lifecycle one.
 */
@Composable
fun GuidePreviewEffect(
    controller: GuidePreviewController,
    enabled: Boolean,
    channel: LiveChannel?,
) {
    LaunchedEffect(enabled, channel?.url) {
        if (!enabled || channel == null) {
            controller.release()
            return@LaunchedEffect
        }
        delay(PREVIEW_DWELL_MS)
        controller.play(channel)
    }
}

/**
 * The video surface, or nothing when no preview is running. Keyed on the
 * controller's generation so a released engine's view is discarded rather than
 * reattached — surfaces outlive the engines that made them, and libVLC in
 * particular does not survive being handed a stale one.
 */
@Composable
fun GuidePreviewSurface(controller: GuidePreviewController, modifier: Modifier = Modifier) {
    val engine = controller.engine ?: return
    val generation = controller.generation
    androidx.compose.runtime.key(generation) {
        AndroidView(
            modifier = modifier,
            factory = { context -> engine.createView(context) as View },
        )
    }
}
