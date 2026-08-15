@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuxcor.nuxtv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuxcor.nuxtv.MainViewModel
import com.nuxcor.nuxtv.data.ContentBundle
import com.nuxcor.nuxtv.data.ContentRepository
import com.nuxcor.nuxtv.data.EpgProgram
import com.nuxcor.nuxtv.data.LiveChannel
import com.nuxcor.nuxtv.ui.components.Artwork
import com.nuxcor.nuxtv.ui.components.CenteredMessage
import com.nuxcor.nuxtv.ui.theme.NuxColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 3dp per minute → an hour is 180dp wide. */
private val DP_PER_MINUTE = 4.dp
private val CHANNEL_COLUMN_WIDTH = 168.dp
private val ROW_HEIGHT = 72.dp

@Composable
fun GuideTab(vm: MainViewModel, bundle: ContentBundle, onPlay: () -> Unit) {
    val epgState by vm.epgState.collectAsState()
    val scope = rememberCoroutineScope()

    when (val state = epgState) {
        is ContentRepository.EpgState.Idle,
        is ContentRepository.EpgState.Loading ->
            CenteredMessage(title = "Loading guide…", loading = true)

        is ContentRepository.EpgState.Error -> CenteredMessage(
            title = "No guide available",
            subtitle = state.message,
        )

        is ContentRepository.EpgState.Ready -> {
            val hidden by vm.hidden.collectAsState()
            val pin by vm.parentalPin.collectAsState()
            val mergeDupes by vm.mergeDuplicates.collectAsState()
            val channels = remember(bundle, hidden, pin, vm.parentalUnlocked, mergeDupes) {
                val lockedIds = bundle.liveCategories
                    .filter { vm.isLockedCategory(it.name) }.map { it.id }.toSet()
                vm.visibleChannels(bundle.channels).filterNot { it.categoryId in lockedIds }
            }
            if (channels.isEmpty()) {
                CenteredMessage(title = "No live channels")
                return
            }

            // 30h window starting an hour before now, snapped to the half hour.
            val windowStart = remember {
                val now = System.currentTimeMillis()
                now - now % (30 * 60_000L) - 60 * 60_000L
            }
            val windowEnd = windowStart + 30 * 3600_000L
            // Ticks every minute so "Now" highlighting and click behaviour stay live.
            var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(60_000)
                    nowTick = System.currentTimeMillis()
                }
            }
            val timelineScroll = rememberScrollState()
            var statusMessage by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(statusMessage) {
                if (statusMessage != null) {
                    delay(4_000)
                    statusMessage = null
                }
            }

            // Start the timeline near "now".
            val density = LocalDensity.current
            LaunchedEffect(Unit) {
                val nowOffsetMin = ((System.currentTimeMillis() - windowStart) / 60_000L - 15)
                    .coerceAtLeast(0)
                timelineScroll.scrollTo(
                    with(density) { (DP_PER_MINUTE * nowOffsetMin.toInt()).roundToPx() }
                )
            }

            Column(modifier = Modifier.fillMaxSize()) {
                statusMessage?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = NuxColors.Secondary)
                    Spacer(Modifier.height(6.dp))
                }

                TimeRuler(windowStart, windowEnd, timelineScroll)

                Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 28.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(channels, key = { it.id }) { channel ->
                        GuideRow(
                            vm = vm,
                            channel = channel,
                            windowStart = windowStart,
                            windowEnd = windowEnd,
                            nowMs = nowTick,
                            timelineScroll = timelineScroll,
                            onPlayChannel = {
                                vm.playChannels(channels, channels.indexOf(channel))
                                onPlay()
                            },
                            onCatchup = { program ->
                                scope.launch {
                                    val url = vm.catchupUrl(channel, program)
                                    if (url != null) {
                                        vm.playCatchup(channel, program, url)
                                        onPlay()
                                    } else {
                                        statusMessage = "Catch-up isn't available for this programme"
                                    }
                                }
                            },
                            onSchedule = { program ->
                                statusMessage = if (vm.scheduleRecording(channel, program)) {
                                    "Recording scheduled: ${program.title}"
                                } else {
                                    vm.scheduleReminder(channel, program)
                                    "Reminder set: ${program.title}"
                                }
                            },
                        )
                    }
                }
                // The NOW marker — the defining element of an EPG.
                val nowOffset = DP_PER_MINUTE * ((nowTick - windowStart) / 60_000f)
                val scrolled = with(LocalDensity.current) { timelineScroll.value.toDp() }
                val markerX = CHANNEL_COLUMN_WIDTH + 8.dp + nowOffset - scrolled
                if (markerX > CHANNEL_COLUMN_WIDTH) {
                    Box(
                        modifier = Modifier
                            .padding(start = markerX)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(NuxColors.Primary)
                    )
                }
                }
            }
        }
    }
}

@Composable
private fun TimeRuler(
    windowStart: Long,
    windowEnd: Long,
    timelineScroll: androidx.compose.foundation.ScrollState,
) {
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Row(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.width(CHANNEL_COLUMN_WIDTH + 8.dp))
        Row(modifier = Modifier.horizontalScroll(timelineScroll, enabled = false)) {
            var t = windowStart
            while (t < windowEnd) {
                Text(
                    text = fmt.format(Date(t)),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuxColors.OnSurfaceDim,
                    modifier = Modifier.width(DP_PER_MINUTE * 30),
                )
                t += 30 * 60_000L
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun GuideRow(
    vm: MainViewModel,
    channel: LiveChannel,
    windowStart: Long,
    windowEnd: Long,
    nowMs: Long,
    timelineScroll: androidx.compose.foundation.ScrollState,
    onPlayChannel: () -> Unit,
    onCatchup: (EpgProgram) -> Unit,
    onSchedule: (EpgProgram) -> Unit,
) {
    val programs = remember(channel.id, vm.epgState.collectAsState().value) {
        vm.programsFor(channel).filter { it.endMs > windowStart && it.startMs < windowEnd }
    }

    Row(modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT)) {
        // Fixed channel cell.
        Surface(
            onClick = onPlayChannel,
            modifier = Modifier.width(CHANNEL_COLUMN_WIDTH),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = NuxColors.Surface,
                focusedContainerColor = NuxColors.SurfaceRaised,
                contentColor = NuxColors.OnSurface,
                focusedContentColor = NuxColors.OnSurface,
            ),
            border = ClickableSurfaceDefaults.border(
                border = androidx.tv.material3.Border(
                    androidx.compose.foundation.BorderStroke(1.dp, NuxColors.Stroke),
                    shape = RoundedCornerShape(8.dp),
                ),
                focusedBorder = com.nuxcor.nuxtv.ui.theme.NuxFocus.ring,
            ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Artwork(
                    imageUrl = channel.logo,
                    title = channel.name,
                    modifier = Modifier.size(width = 64.dp, height = 40.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    monogramStyle = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))

        // Programme lane sharing the timeline scroll.
        Row(modifier = Modifier.horizontalScroll(timelineScroll)) {
            if (programs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .width(DP_PER_MINUTE * ((windowEnd - windowStart) / 60_000L).toInt())
                        .height(ROW_HEIGHT)
                        .padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(NuxColors.Surface.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        "  No guide data",
                        style = MaterialTheme.typography.labelSmall,
                        color = NuxColors.OnSurfaceDim,
                    )
                }
            } else {
                // Exact fractional-minute widths keep every row aligned with the
                // ruler; overlapping programmes are clamped to the cursor.
                var cursor = windowStart
                programs.forEach { program ->
                    val start = program.startMs.coerceIn(cursor, windowEnd)
                    val end = program.endMs.coerceIn(start, windowEnd)
                    if (end - start < 60_000) { cursor = end; return@forEach }
                    val gapMinutes = (start - cursor) / 60_000f
                    if (gapMinutes > 0f) Spacer(Modifier.width(DP_PER_MINUTE * gapMinutes))
                    ProgramCell(
                        program = program,
                        widthMinutes = (end - start) / 60_000f,
                        nowMs = nowMs,
                        hasArchive = channel.archiveDays > 0,
                        canRecord = channel.recordUrl != null,
                        onPlayLive = onPlayChannel,
                        onCatchup = { onCatchup(program) },
                        onSchedule = { onSchedule(program) },
                    )
                    cursor = end
                }
            }
        }
    }
}

@Composable
private fun ProgramCell(
    program: EpgProgram,
    widthMinutes: Float,
    nowMs: Long,
    hasArchive: Boolean,
    canRecord: Boolean,
    onPlayLive: () -> Unit,
    onCatchup: () -> Unit,
    onSchedule: () -> Unit,
) {
    val airingNow = nowMs in program.startMs until program.endMs
    val isPast = program.endMs <= nowMs
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Surface(
        onClick = {
            when {
                airingNow -> onPlayLive()
                isPast && hasArchive -> onCatchup()
                !isPast -> onSchedule() // records when possible, else sets a reminder
                else -> Unit
            }
        },
        modifier = Modifier
            // No minimum width: widening a cell without consuming time would
            // push the rest of the row out of sync with the ruler above.
            .width(DP_PER_MINUTE * widthMinutes)
            .height(ROW_HEIGHT)
            .padding(end = 2.dp, top = 6.dp, bottom = 6.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        border = ClickableSurfaceDefaults.border(
            border = androidx.tv.material3.Border(
                androidx.compose.foundation.BorderStroke(1.dp, NuxColors.Stroke),
                shape = RoundedCornerShape(8.dp),
            ),
            focusedBorder = com.nuxcor.nuxtv.ui.theme.NuxFocus.ring,
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = when {
                airingNow -> NuxColors.SurfaceVariant
                isPast -> NuxColors.Surface
                else -> NuxColors.Surface
            },
            focusedContainerColor = NuxColors.SurfaceRaised,
            contentColor = if (isPast && !airingNow) NuxColors.OnSurfaceDim else NuxColors.OnSurface,
            focusedContentColor = NuxColors.OnSurface,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = program.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = fmt.format(Date(program.startMs)) +
                    (if (airingNow) " • Now" else if (!isPast) (if (canRecord) " • OK to record" else " • OK to remind") else ""),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
