@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.agoro.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.agoro.tv.data.EpgProgram
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.ui.components.Artwork
import com.agoro.tv.ui.components.DialogScaffold
import com.agoro.tv.ui.components.MetaChip
import com.agoro.tv.ui.components.rememberClockFormat
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxShape
import com.agoro.tv.ui.theme.NuxFocus
import com.agoro.tv.ui.theme.Space
import com.agoro.tv.ui.components.requestFocusRetrying
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What is on a channel, and what is on next.
 *
 * Opening a channel used to go straight to playback, which answers "watch this"
 * and nothing else — deciding whether to watch it meant starting the stream and
 * reading the banner. This is the schedule instead: now, and everything after
 * it, from one press on the channel.
 *
 * Watch is the first thing focused, so the old behaviour is still OK twice
 * rather than a hunt, and BACK returns to the list without playing anything.
 */
@Composable
fun ChannelSchedule(
    channel: LiveChannel,
    /** Null while the guide table is still being read; the list draws nothing. */
    programs: List<EpgProgram>?,
    nowMs: Long,
    onWatch: () -> Unit,
    /**
     * Acts on a programme and returns what to tell the viewer, or null when the
     * action speaks for itself by leaving. Scheduling a recording succeeds
     * silently otherwise, which is indistinguishable from the press not
     * registering.
     */
    onSelectProgram: (EpgProgram) -> String?,
    onDismiss: () -> Unit,
) {
    var statusMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(statusMessage) {
        if (statusMessage != null) {
            kotlinx.coroutines.delay(4_000)
            statusMessage = null
        }
    }
    val watchFocus = remember { FocusRequester() }
    // Retried, like the nav rail: the Button composes a frame after this runs,
    // and a single attempt that lands too early leaves the sheet with nothing
    // focused — which on a TV is a dialog that ignores the remote.
    LaunchedEffect(channel.id) {
        watchFocus.requestFocusRetrying()
    }
    val timeFmt = rememberClockFormat()
    val dayFmt = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }

    // The caller's nowMs is a snapshot; this sheet can sit open across a
    // programme boundary, and a schedule that goes on calling a finished
    // programme ON NOW is worse than one that shows nothing.
    var tick by remember { mutableStateOf(nowMs) }
    LaunchedEffect(channel.id) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            tick = System.currentTimeMillis()
        }
    }

    // Everything still to come, plus whatever is on now — a schedule that opens
    // on programmes that already finished is a history, not a plan.
    val upcoming = remember(programs, tick) { programs?.filter { it.endMs > tick } }
    val listState = rememberLazyListState()

    DialogScaffold(
        onDismiss = onDismiss,
        width = 680.dp,
        padding = Space.l,
    ) {
        Column(
            modifier = Modifier.heightIn(max = 460.dp),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Artwork(
                    imageUrl = channel.logo,
                    title = channel.displayName,
                    modifier = Modifier.size(width = 86.dp, height = 54.dp)
                        .clip(NuxShape.Chip),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = channel.displayName,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = NuxColors.OnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = listOfNotNull(
                        channel.quality,
                        channel.archiveDays.takeIf { it > 0 }?.let { "$it-day catch-up" },
                    )
                    if (meta.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = meta.joinToString("  •  "),
                            style = MaterialTheme.typography.labelMedium,
                            color = NuxColors.OnSurfaceDim,
                        )
                    }
                }
                Button(onClick = onWatch, modifier = Modifier.focusRequester(watchFocus)) {
                    Text("Watch")
                }
            }

            statusMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelLarge,
                    color = NuxColors.Secondary,
                )
            }
            Spacer(Modifier.height(2.dp))

            if (upcoming == null) {
                // Still loading: the slot keeps its height so the sheet
                // doesn't resize when the rows land.
                Spacer(Modifier.height(Space.xxl))
            } else if (upcoming.isEmpty()) {
                Text(
                    text = "No guide data for this channel.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuxColors.OnSurfaceDim,
                    modifier = Modifier.padding(vertical = Space.m),
                )
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = Space.s),
                ) {
                    // Keyed by position, not by the programme: XMLTV ids and
                    // start times both repeat in real feeds, and a duplicate
                    // key takes a LazyColumn down. Nothing here holds per-item
                    // state that a stable key would protect.
                    itemsIndexed(upcoming) { index, program ->
                        val onNow = tick in program.startMs until program.endMs
                        // The date only where it changes, so an evening of
                        // programmes doesn't repeat today's date twenty times.
                        val showDay = index > 0 &&
                            dayFmt.format(Date(program.startMs)) !=
                            dayFmt.format(Date(upcoming[index - 1].startMs))
                        if (showDay) {
                            Text(
                                text = dayFmt.format(Date(program.startMs)),
                                style = MaterialTheme.typography.labelMedium,
                                color = NuxColors.OnSurfaceDim,
                                modifier = Modifier.padding(top = Space.s, bottom = 2.dp),
                            )
                        }
                        ScheduleRow(
                            program = program,
                            onNow = onNow,
                            timeLabel = timeFmt.format(Date(program.startMs)),
                            onClick = { statusMessage = onSelectProgram(program) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleRow(
    program: EpgProgram,
    onNow: Boolean,
    timeLabel: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(NuxShape.Row),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (onNow) NuxColors.SurfaceVariant else NuxColors.Surface,
            focusedContainerColor = NuxColors.SurfaceRaised,
            contentColor = NuxColors.OnSurface,
            focusedContentColor = NuxColors.OnSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = NuxFocus.RowScale),
        border = ClickableSurfaceDefaults.border(focusedBorder = NuxFocus.ring12),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = timeLabel,
                style = MaterialTheme.typography.labelLarge,
                color = if (onNow) NuxColors.Primary else NuxColors.OnSurfaceDim,
                modifier = Modifier.width(72.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = program.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = NuxColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!program.description.isNullOrBlank()) {
                    Text(
                        text = program.description.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = NuxColors.OnSurfaceDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onNow) MetaChip("ON NOW", accent = true)
        }
    }
}
