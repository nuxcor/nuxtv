@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuxcor.nuxtv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.nuxcor.nuxtv.MainViewModel
import com.nuxcor.nuxtv.ui.components.CenteredMessage
import com.nuxcor.nuxtv.ui.components.WideItem
import com.nuxcor.nuxtv.ui.theme.NuxColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordingsTab(vm: MainViewModel, onPlay: () -> Unit) {
    val recordings by vm.recordings.collectAsState()
    val active by vm.activeRecording.collectAsState()
    val schedules by vm.schedules.collectAsState()

    LaunchedEffect(active) { vm.refreshRecordings() }

    val dateFmt = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        Text(
            "Recordings",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            color = NuxColors.OnSurface,
        )
        Spacer(Modifier.height(16.dp))

        val rec = active
        if (rec != null) {
            WideItem(
                title = "Recording now: ${rec.channelName}",
                subtitle = "${rec.bytesWritten / (1024 * 1024)} MB written — select to stop",
                leading = {
                    Icon(Icons.Default.FiberManualRecord, contentDescription = null, tint = NuxColors.Error)
                },
                onClick = { vm.stopRecording() },
            )
            Spacer(Modifier.height(14.dp))
        }

        if (schedules.isNotEmpty()) {
            Text(
                "Scheduled",
                style = MaterialTheme.typography.titleSmall,
                color = NuxColors.OnSurfaceDim,
            )
            Spacer(Modifier.height(8.dp))
            schedules.sortedBy { it.startMs }.forEach { schedule ->
                WideItem(
                    title = "${schedule.title} — ${schedule.channelName}",
                    subtitle = dateFmt.format(Date(schedule.startMs)) +
                        " – ${dateFmt.format(Date(schedule.endMs))}  •  select to cancel",
                    leading = {
                        Icon(
                            Icons.Default.FiberManualRecord,
                            contentDescription = null,
                            tint = NuxColors.OnSurfaceDim,
                        )
                    },
                    onClick = { vm.cancelSchedule(schedule.id) },
                )
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(10.dp))
        }

        if (recordings.isEmpty() && rec == null && schedules.isEmpty()) {
            CenteredMessage(
                title = "No recordings yet",
                subtitle = "Record from the player, or schedule from the Guide",
            )
            return@Column
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            items(recordings, key = { it.file.absolutePath }) { recording ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        WideItem(
                            title = recording.name,
                            subtitle = "${dateFmt.format(Date(recording.recordedAtMs))} • " +
                                "%.1f MB".format(recording.sizeBytes / 1024f / 1024f),
                            leading = {
                                Icon(
                                    Icons.Default.Videocam,
                                    contentDescription = null,
                                    tint = NuxColors.Secondary,
                                )
                            },
                            onClick = {
                                vm.playRecording(recording)
                                onPlay()
                            },
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(onClick = { vm.deleteRecording(recording) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
