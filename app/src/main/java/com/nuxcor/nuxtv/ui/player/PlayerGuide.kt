@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuxcor.nuxtv.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.clip
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
import com.nuxcor.nuxtv.ui.components.StatusPane
import com.nuxcor.nuxtv.ui.components.rememberClockFormat
import com.nuxcor.nuxtv.ui.screens.CATEGORY_ALL
import com.nuxcor.nuxtv.ui.screens.CHANNEL_COLUMN_GAP
import com.nuxcor.nuxtv.ui.screens.CHANNEL_COLUMN_WIDTH
import com.nuxcor.nuxtv.ui.screens.CategoryItem
import com.nuxcor.nuxtv.ui.screens.GuideGrid
import com.nuxcor.nuxtv.ui.screens.TimeRuler
import com.nuxcor.nuxtv.ui.screens.channelsInCategory
import com.nuxcor.nuxtv.ui.screens.guideDpPerMinute
import com.nuxcor.nuxtv.ui.screens.liveCategoryList
import com.nuxcor.nuxtv.ui.theme.NuxColors
import com.nuxcor.nuxtv.ui.theme.NuxShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The guide embedded around live playback: the video shrinks into the top-left
 * corner and keeps playing — sound and all — while the focused programme's
 * details sit beside it and the grid fills the rest of the screen. One
 * surface, the way every broadcast guide lays it out; not a curtain drawn over
 * the video.
 *
 * The shrinking itself happens in PlayerScreen (the engine's view is resized
 * to the slot PlayerTheme reserves); this overlay only draws around it, which
 * is why its root deliberately has no background — the player's black shows
 * through everywhere except the video slot.
 *
 * Tuning a channel here replaces the zap playlist, same contract as the
 * mini-guide. No GuidePreview machinery: the player's engine already holds the
 * provider connection, and a preview would open a second one.
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
            fixedCosts = PLAYER_GUIDE_PADDING * 2 + CHANNEL_COLUMN_WIDTH + CHANNEL_COLUMN_GAP,
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
    // Keyed on list-emptiness, not unkeyed: the channels flow starts empty,
    // and an overlay composed a frame before it emits would otherwise pin
    // null forever — no landing, dead remote.
    val initialFocusChannelId = remember(channels.isEmpty()) {
        playingChannelId?.takeIf { id -> channels.any { it.id == id } }
            ?: channels.firstOrNull()?.id
    }

    Column(
        // No background: the player's black canvas is the page, and the video
        // slot below must stay a hole in this layout for the resized engine
        // view to show through.
        modifier = Modifier
            .fillMaxSize()
            .focusGroup()
            .padding(
                start = PLAYER_GUIDE_PADDING,
                end = PLAYER_GUIDE_PADDING,
                top = PLAYER_GUIDE_TOP_PADDING,
                bottom = 24.dp,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(PLAYER_GUIDE_VIDEO_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // The video slot. The engine's view is positioned exactly here by
            // PlayerScreen; this box only draws the hairline that visually
            // ties it into the layout.
            Box(
                modifier = Modifier
                    .size(PLAYER_GUIDE_VIDEO_WIDTH, PLAYER_GUIDE_VIDEO_HEIGHT)
                    .border(1.dp, NuxColors.Stroke, NuxShape.Track),
            )
            GuideDetails(
                // Lambdas, not values: read in this scope they would recompose
                // the whole overlay — grid included — on every cell the cursor
                // passes over.
                channel = { focusedChannel },
                program = { focusedProgram },
                nowMs = nowTick,
                categoryName = categories.firstOrNull { it.id == categoryId }?.name,
            )
        }
        Spacer(Modifier.height(14.dp))

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
                epgState is ContentRepository.EpgState.Loading -> StatusPane(
                title = "Loading guide…",
                loading = true,
            )

            epgState is ContentRepository.EpgState.Error -> StatusPane(
                title = "Guide not available",
                message = "This playlist's guide failed to load — set one up in Settings → EPG source.",
            )

            channels.isEmpty() -> StatusPane(title = "No channels in this category")

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
 * The focused programme, described in full beside the still-playing video:
 * title, span, live progress, synopsis — plus the clock and the two keys that
 * aren't obvious. The grid can only ever show a truncated title; this is where
 * the rest of it lives.
 */
@Composable
private fun GuideDetails(
    channel: () -> LiveChannel?,
    program: () -> EpgProgram?,
    nowMs: Long,
    categoryName: String?,
) {
    val timeFmt = rememberClockFormat()
    val dateFmt = remember { SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()) }
    val current = channel()
    val currentProgram = program()

    Row(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = currentProgram?.title ?: current?.name ?: "Guide",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = NuxColors.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (currentProgram != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(timeFmt.format(Date(currentProgram.startMs)))
                        append(" – ")
                        append(timeFmt.format(Date(currentProgram.endMs)))
                        current?.name?.let { append("   •   ").append(it) }
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = NuxColors.OnSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Progress only means something for whatever is on right now.
            if (currentProgram != null &&
                nowMs in currentProgram.startMs until currentProgram.endMs
            ) {
                Spacer(Modifier.height(8.dp))
                val span = (currentProgram.endMs - currentProgram.startMs).coerceAtLeast(1)
                val progress = ((nowMs - currentProgram.startMs).toFloat() / span).coerceIn(0f, 1f)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(220.dp)
                            .height(5.dp)
                            .clip(NuxShape.Track)
                            .background(NuxColors.SurfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress)
                                .background(NuxColors.Primary)
                        )
                    }
                    // Rounded up: integer division reported "0 minutes left"
                    // for the last minute, beside a bar that wasn't full.
                    val minutesLeft =
                        ((currentProgram.endMs - nowMs + 59_999) / 60_000L).coerceAtLeast(0L)
                    Text(
                        text = if (minutesLeft == 1L) "1 minute left" else "$minutesLeft minutes left",
                        style = MaterialTheme.typography.labelMedium,
                        color = NuxColors.OnSurfaceDim,
                        maxLines = 1,
                    )
                }
            }

            if (!currentProgram?.description.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = currentProgram?.description.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuxColors.OnSurfaceDim,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.widthIn(max = 280.dp).padding(start = 16.dp),
        ) {
            Text(
                text = "${timeFmt.format(Date(nowMs))}  •  ${dateFmt.format(Date(nowMs))}",
                style = MaterialTheme.typography.labelMedium,
                color = NuxColors.OnSurface,
                maxLines = 1,
            )
            if (categoryName != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = categoryName,
                    style = MaterialTheme.typography.labelSmall,
                    color = NuxColors.OnSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "CH +/− page  •  BACK resume",
                style = MaterialTheme.typography.labelSmall,
                color = NuxColors.OnSurfaceDim,
                maxLines = 1,
            )
        }
    }
}
