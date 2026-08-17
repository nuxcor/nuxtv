@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuxcor.nuxtv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuxcor.nuxtv.MainViewModel
import com.nuxcor.nuxtv.data.LiveChannel
import com.nuxcor.nuxtv.data.PlayableItem
import com.nuxcor.nuxtv.ui.theme.NuxColors
import com.nuxcor.nuxtv.ui.theme.NuxShape
import java.util.Date
import kotlinx.coroutines.delay

/**
 * TiviMate-style channel banner on the bottom gradient: number and logo on
 * the left, name / now-programme / progress / next in the middle, clock and
 * status chips on the right. Shown on every channel change so zapping is
 * never blind. The gradient itself belongs to the scaffold's bottom column,
 * which stacks this above the transport bar — their spacing is layout, not a
 * hardcoded lift.
 */
@Composable
internal fun ChannelBanner(
    vm: MainViewModel,
    item: PlayableItem?,
    channel: LiveChannel?,
    isLive: Boolean,
    resolution: Pair<Int, Int>?,
    isRecording: Boolean,
    showKeyHints: Boolean = false,
) {
    val nowNextMap by vm.nowNext.collectAsState()
    val favorites by vm.favorites.collectAsState()
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMs = System.currentTimeMillis()
        }
    }
    val nowNext = channel?.id?.let { nowNextMap[it] }
    val timeFmt = com.nuxcor.nuxtv.ui.components.rememberClockFormat()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 40.dp, end = 40.dp, top = 24.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // --- left: number + logo card ------------------------------------
        channel?.number?.let { number ->
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = NuxColors.Primary,
            )
        }
        if (isLive && channel != null) {
            Box(
                modifier = Modifier
                    .clip(PlayerTheme.ChipShape)
                    .background(PlayerTheme.RowFill)
                    .padding(6.dp),
            ) {
                // Fit, not the Crop default: channel logos are arbitrary aspect
                // ratios and Crop fills the box by slicing the sides off — a
                // wide wordmark came out reading "CTRUM EWS". Crop is right for
                // posters, which is why it is the default, but never for logos.
                com.nuxcor.nuxtv.ui.components.Artwork(
                    imageUrl = channel.logo,
                    title = channel.name,
                    modifier = Modifier.size(width = 78.dp, height = 46.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
            }
        }

        // --- middle: name, now, progress, next ---------------------------
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = channel?.name ?: item?.title.orEmpty(),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = NuxColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (channel != null && channel.url in favorites) {
                    Text("★", style = MaterialTheme.typography.titleSmall, color = NuxColors.Primary)
                }
            }
            val current = nowNext?.now
            if (current != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${timeFmt.format(Date(current.startMs))} – " +
                        "${timeFmt.format(Date(current.endMs))}   ${current.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuxColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                val progress = ((nowMs - current.startMs).toFloat() /
                    (current.endMs - current.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(NuxShape.Track)
                        .background(PlayerTheme.TrackBackground)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress)
                            .background(NuxColors.Primary)
                    )
                }
            } else if (!item?.subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item?.subtitle.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuxColors.OnSurfaceDim,
                    maxLines = 1,
                )
            }
            nowNext?.next?.let { next ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Next  ${timeFmt.format(Date(next.startMs))}  ${next.title}",
                    style = MaterialTheme.typography.labelMedium,
                    color = NuxColors.OnSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showKeyHints) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "OK Options  ·  ◀ Channels  ·  ▲▼ Change channel  ·  INFO Info",
                    style = MaterialTheme.typography.labelSmall,
                    color = NuxColors.OnSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // --- right: clock + status chips ---------------------------------
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = timeFmt.format(Date(nowMs)),
                style = MaterialTheme.typography.labelLarge,
                color = NuxColors.OnSurface,
                maxLines = 1,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // What is actually being decoded, not what the stream name
                // advertises — the two disagree more often than not.
                resolution?.let { (w, h) ->
                    com.nuxcor.nuxtv.ui.components.MetaChip(
                        // Tier only — "HD", not "720p HD". The precise numbers
                        // live in the options sheet for whoever wants them.
                        com.nuxcor.nuxtv.data.QualityTag.tierOf(h) ?: return@let,
                        accent = true,
                    )
                }
                if (isRecording) {
                    PlayerBadge(text = "REC", color = NuxColors.Error)
                }
                // No engine-name chip here: which decoder is playing is
                // diagnostics, not viewing information — it lives in the
                // options sheet instead.
            }
        }
    }
}

/**
 * What tuning looks like: the channel's identity with a working spinner,
 * instead of a bare spinner over black that could mean anything. Shown from
 * the moment a tune is requested until the new stream renders; mid-stream
 * stalls get only a corner chip.
 */
@Composable
internal fun TuneCard(
    channel: LiveChannel?,
    item: PlayableItem?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .widthIn(max = 560.dp)
            .clip(PlayerTheme.PanelShape)
            .background(PlayerTheme.ScrimMedium)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (channel != null) {
            com.nuxcor.nuxtv.ui.components.Artwork(
                imageUrl = channel.logo,
                title = channel.name,
                modifier = Modifier
                    .size(width = 86.dp, height = 54.dp)
                    .clip(PlayerTheme.ChipShape),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
        }
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                channel?.number?.let { number ->
                    Text(
                        text = number.toString(),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = NuxColors.Primary,
                    )
                }
                Text(
                    text = channel?.name ?: item?.title.orEmpty(),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = NuxColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(
                    color = NuxColors.Primary,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Tuning…",
                    style = MaterialTheme.typography.labelLarge,
                    color = NuxColors.OnSurfaceDim,
                )
            }
        }
    }
}
