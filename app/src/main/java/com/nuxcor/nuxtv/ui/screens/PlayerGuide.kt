@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuxcor.nuxtv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuxcor.nuxtv.MainViewModel
import com.nuxcor.nuxtv.data.ContentBundle
import com.nuxcor.nuxtv.data.ContentRepository
import com.nuxcor.nuxtv.data.ContentState
import com.nuxcor.nuxtv.data.EpgProgram
import com.nuxcor.nuxtv.data.LiveChannel
import com.nuxcor.nuxtv.ui.components.CenteredMessage
import com.nuxcor.nuxtv.ui.components.rememberClockFormat
import com.nuxcor.nuxtv.ui.theme.NuxColors
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Overscan-safe inset for a full-bleed overlay; the player route skips the
 *  TvSafe wrapper, so the overlay pays its own margins. Matches the banner's. */
private val OVERLAY_PADDING = 40.dp

/**
 * The grid guide over live playback: a TiviMate-style scrim overlay — video
 * and audio keep running behind it — so browsing the schedule no longer means
 * leaving the player for the Live TV tab and finding your way back.
 *
 * The mini-guide stays for what it is good at (fast zapping down a list); this
 * is the planning surface. Tuning a channel here replaces the zap playlist,
 * same as picking a category in the mini-guide.
 *
 * Deliberately does not use the GuidePreview machinery: the player's own
 * engine is already holding a provider connection behind the scrim, and a
 * preview would open a second one — the thing GuidePreview.kt exists to avoid.
 */
@Composable
internal fun PlayerGuideOverlay(
    vm: MainViewModel,
    playingChannelId: String?,
    onTune: (List<LiveChannel>, Int) -> Unit,
    onPlayCatchup: (LiveChannel, EpgProgram, String) -> Unit,
    onStatus: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    BackHandler { onDismiss() }

    val epgState by vm.epgState.collectAsState()
    val allChannels by vm.displayChannels.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val recents by vm.recentChannels.collectAsState()
    val contentState by vm.content.collectAsState()
    val bundle = (contentState as? ContentState.Ready)?.bundle ?: ContentBundle()

    // Same category vocabulary as Live TV and the mini-guide — LiveCategories.kt.
    val categories = remember(bundle, allChannels, favorites, recents) {
        liveCategoryList(bundle, allChannels, favorites, recents)
    }
    // All channels rather than the current zap playlist: the playlist may be a
    // single category, and the guide is where you look beyond it. Focus still
    // lands on what is playing, so the wider list costs no orientation.
    var categoryId by remember { mutableStateOf(CATEGORY_ALL) }
    val channels = remember(allChannels, categoryId, favorites, recents) {
        channelsInCategory(categoryId, allChannels, favorites, recents)
    }

    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowTick = System.currentTimeMillis()
        }
    }
    // Today only. Day paging is a planning task and belongs to the browse
    // guide; from the sofa mid-programme the questions are "what is this,
    // what's next, what's on elsewhere".
    val windowStart = remember {
        val now = System.currentTimeMillis()
        now - now % (30 * 60_000L) - 60 * 60_000L
    }
    val windowEnd = windowStart + 30 * 3600_000L

    val timelineScroll = rememberScrollState()
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    // No rail and no channel-view gutters in the player; the overlay's own
    // padding is the only fixed cost besides the channel column.
    val dpPerMinute = remember(screenWidth) {
        guideDpPerMinute(
            screenWidth,
            fixedCosts = OVERLAY_PADDING * 2 + CHANNEL_COLUMN_WIDTH + CHANNEL_COLUMN_GAP,
        )
    }
    val density = LocalDensity.current
    LaunchedEffect(Unit) {
        val nowOffsetMin = ((System.currentTimeMillis() - windowStart) / 60_000L - 15)
            .coerceAtLeast(0)
        timelineScroll.scrollTo(with(density) { (dpPerMinute * nowOffsetMin.toInt()).roundToPx() })
    }

    var focusedChannel by remember { mutableStateOf<LiveChannel?>(null) }
    var focusedProgram by remember { mutableStateOf<EpgProgram?>(null) }
    LaunchedEffect(categoryId) {
        focusedChannel = null
        focusedProgram = null
    }

    // Resolved once, on entry. Recomputed per category this fell back to each
    // new list's first channel, so GuideGrid's initial-focus effect re-fired on
    // a category switch and yanked focus out of the category row mid-browse.
    // Falls back to the first row when what's playing can't be resolved to a
    // channel (a raw-URL stream): without a landing the overlay opens with
    // focus parked nowhere and the remote goes dead until BACK.
    val initialFocusChannelId = remember {
        playingChannelId?.takeIf { id -> channels.any { it.id == id } }
            ?: channels.firstOrNull()?.id
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NuxColors.Scrim)
            .focusGroup()
            .padding(horizontal = OVERLAY_PADDING, vertical = 28.dp),
    ) {
        OverlayHeader(
            channel = { focusedChannel },
            program = { focusedProgram },
            nowMs = nowTick,
        )
        Spacer(Modifier.height(12.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 10.dp),
        ) {
            items(categories, key = { it.id }) { category ->
                CategoryItem(
                    name = category.name,
                    selected = category.id == categoryId,
                    onClick = { categoryId = category.id },
                    modifier = Modifier,
                )
            }
        }

        when {
            // Loading is not failure: telling the viewer to go reconfigure a
            // guide that will appear in a few seconds sends them to Settings
            // for nothing. Only a guide that actually failed gets the CTA.
            epgState is ContentRepository.EpgState.Idle ||
                epgState is ContentRepository.EpgState.Loading -> CenteredMessage(
                title = "Loading guide…",
                loading = true,
            )

            epgState is ContentRepository.EpgState.Error -> CenteredMessage(
                title = "Guide not available",
                subtitle = "This playlist's guide failed to load — set one up in Settings → EPG source.",
            )

            channels.isEmpty() -> CenteredMessage(title = "No channels in this category")

            else -> {
                TimeRuler(windowStart, windowEnd, nowTick, nowTick, timelineScroll, dpPerMinute)
                GuideGrid(
                    channels = channels,
                    programsFor = { vm.programsFor(it) },
                    programsKey = epgState,
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    nowMs = nowTick,
                    timelineScroll = timelineScroll,
                    dpPerMinute = dpPerMinute,
                    playingChannelId = playingChannelId,
                    initialFocusChannelId = initialFocusChannelId,
                    onFocus = { channel, program ->
                        focusedChannel = channel
                        focusedProgram = program
                    },
                    onPlayChannel = { channel ->
                        if (channel.id == playingChannelId) {
                            // Already watching it — the click means "back to it".
                            onDismiss()
                        } else {
                            val index = channels.indexOfFirst { it.id == channel.id }
                            if (index >= 0) onTune(channels, index)
                        }
                    },
                    onCatchup = { channel, program ->
                        scope.launch {
                            val url = vm.catchupUrl(channel, program)
                            if (url != null) {
                                onPlayCatchup(channel, program, url)
                            } else {
                                onStatus("Catch-up isn't available for this programme")
                            }
                        }
                    },
                    onSchedule = { channel, program ->
                        onStatus(
                            if (vm.scheduleRecording(channel, program)) {
                                "Recording scheduled: ${program.title}"
                            } else {
                                vm.scheduleReminder(channel, program)
                                "Reminder set: ${program.title}"
                            }
                        )
                    },
                )
            }
        }
    }
}

/**
 * One line of orientation over the scrim: the focused programme spelled out,
 * the clock, and the two keys that aren't obvious. Compact on purpose — the
 * grid is the point, and the browse guide's tall header would cost two rows.
 */
@Composable
private fun OverlayHeader(
    channel: () -> LiveChannel?,
    program: () -> EpgProgram?,
    nowMs: Long,
) {
    val timeFmt = rememberClockFormat()
    val current = channel()
    val currentProgram = program()

    Row(
        modifier = Modifier.fillMaxWidth().height(52.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = currentProgram?.title ?: current?.name ?: "Guide",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = NuxColors.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = buildString {
                if (currentProgram != null) {
                    append(timeFmt.format(Date(currentProgram.startMs)))
                    append(" – ")
                    append(timeFmt.format(Date(currentProgram.endMs)))
                    current?.name?.let { append("  •  ").append(it) }
                    currentProgram.description?.takeIf { it.isNotBlank() }
                        ?.let { append("  •  ").append(it) }
                } else {
                    current?.name?.let { append(it) }
                }
            }
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelMedium,
                    color = NuxColors.OnSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = timeFmt.format(Date(nowMs)),
                style = MaterialTheme.typography.labelMedium,
                color = NuxColors.OnSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "CH +/− page  •  BACK resume",
                style = MaterialTheme.typography.labelSmall,
                color = NuxColors.OnSurfaceDim,
                maxLines = 1,
            )
        }
    }
}
