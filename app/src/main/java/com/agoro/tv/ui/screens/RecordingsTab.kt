@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.agoro.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.agoro.tv.MainViewModel
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import com.agoro.tv.ui.components.ScreenTitle
import com.agoro.tv.ui.components.SectionTitle
import com.agoro.tv.ui.components.StatusAction
import com.agoro.tv.ui.components.StatusPane
import com.agoro.tv.ui.components.WideItem
import com.agoro.tv.ui.theme.NuxColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordingsTab(vm: MainViewModel, onPlay: () -> Unit, onGoToGuide: (() -> Unit)? = null) {
    val recordings by vm.recordings.collectAsState()
    val active by vm.activeRecording.collectAsState()
    val schedules by vm.schedules.collectAsState()

    LaunchedEffect(active) { vm.refreshRecordings() }

    val dateFmt = remember { SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()) }
    var confirmDelete by remember { mutableStateOf<com.agoro.tv.recording.Recording?>(null) }
    var confirmCancel by remember { mutableStateOf<com.agoro.tv.data.ScheduledRecording?>(null) }
    var menuRecording by remember { mutableStateOf<com.agoro.tv.recording.Recording?>(null) }

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        ScreenTitle("Recordings")
        Spacer(Modifier.height(16.dp))

        val rec = active
        if (rec != null) {
            WideItem(
                title = "Recording now: ${rec.channelName}",
                subtitle = "${rec.bytesWritten / (1024 * 1024)} MB written — select to stop",
                leading = {
                    // Alpha-only pulse: reads as "live" without animating layout.
                    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "recPulse")
                    val alpha by pulse.animateFloat(
                        initialValue = 1f,
                        targetValue = 0.35f,
                        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                            animation = androidx.compose.animation.core.tween(900),
                            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                        ),
                        label = "recPulseAlpha",
                    )
                    Icon(
                        Icons.Default.FiberManualRecord,
                        contentDescription = "Recording in progress",
                        tint = NuxColors.Error,
                        modifier = Modifier.graphicsLayer { this.alpha = alpha },
                    )
                },
                onClick = { vm.stopRecording() },
            )
            Spacer(Modifier.height(14.dp))
        }

        if (recordings.isEmpty() && rec == null && schedules.isEmpty()) {
            StatusPane(
                title = "No recordings yet",
                message = "Record from the player, or schedule from the Guide.",
                icon = Icons.Default.Videocam,
                primaryAction = onGoToGuide?.let { StatusAction("Open the guide", it) },
            )
            return@Column
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            if (schedules.isNotEmpty()) {
                item(key = "sched-header") {
                    SectionTitle("Scheduled", schedules.size)
                }
                items(schedules.sortedBy { it.startMs }, key = { it.id }) { schedule ->
                    WideItem(
                        title = "${schedule.title} — ${schedule.channelName}",
                        subtitle = dateFmt.format(Date(schedule.startMs)) +
                            " – ${dateFmt.format(Date(schedule.endMs))}  •  select to cancel",
                        leading = {
                            Icon(
                                Icons.Default.FiberManualRecord,
                                contentDescription = "Scheduled recording",
                                tint = NuxColors.OnSurfaceDim,
                            )
                        },
                        onClick = { confirmCancel = schedule },
                    )
                }
                item(key = "rec-header") {
                    SectionTitle(
                        "Recorded",
                        recordings.size,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            items(recordings, key = { it.file.absolutePath }) { recording ->
                // Delete lives behind the same long-press menu the rest of the
                // app uses, rather than a bare icon button one press away from
                // every row.
                WideItem(
                    title = recording.name,
                    subtitle = "${dateFmt.format(Date(recording.recordedAtMs))} • " +
                        "%.1f MB".format(recording.sizeBytes / 1024f / 1024f),
                    leading = {
                        Icon(
                            Icons.Default.Videocam,
                            contentDescription = "Recording",
                            tint = NuxColors.Secondary,
                        )
                    },
                    onClick = {
                        vm.playRecording(recording)
                        onPlay()
                    },
                    onLongClick = { menuRecording = recording },
                )
            }
        }
    }

    // After the list, never before it: siblings draw in composition order,
    // and a dialog composed first sits under the page — the list rendered
    // straight through its panel.
    menuRecording?.let { recording ->
        com.agoro.tv.ui.components.ContextMenu(
            title = recording.name,
            actions = listOf(
                com.agoro.tv.ui.components.MenuAction("Play") {
                    vm.playRecording(recording)
                    onPlay()
                },
                com.agoro.tv.ui.components.MenuAction("Delete", destructive = true) {
                    confirmDelete = recording
                },
            ),
            onDismiss = { menuRecording = null },
        )
    }
    confirmDelete?.let { rec ->
        com.agoro.tv.ui.components.ConfirmDialog(
            title = "Delete this recording?",
            message = rec.name,
            onConfirm = { vm.deleteRecording(rec) },
            onDismiss = { confirmDelete = null },
        )
    }
    confirmCancel?.let { schedule ->
        com.agoro.tv.ui.components.ConfirmDialog(
            title = "Cancel this scheduled recording?",
            message = schedule.title,
            confirmLabel = "Cancel recording",
            onConfirm = { vm.cancelSchedule(schedule.id) },
            onDismiss = { confirmCancel = null },
        )
    }
}
