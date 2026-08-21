@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.agoro.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.agoro.tv.MainViewModel
import com.agoro.tv.data.ContentBundle
import com.agoro.tv.data.SportsEvent
import com.agoro.tv.data.SportsParser
import com.agoro.tv.ui.components.SectionTitle
import com.agoro.tv.ui.components.StatusPane
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxFocus
import com.agoro.tv.ui.theme.NuxShape
import com.agoro.tv.ui.theme.Space
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live sport, read out of the PPV slots.
 *
 * A destination rather than a Home row, which is what makes showing the next
 * hour worthwhile: a row must hide when it is empty, and at 3am there is
 * nothing on. A destination can say what is coming instead of vanishing.
 */
@Composable
fun SportTab(vm: MainViewModel, bundle: ContentBundle, onPlay: () -> Unit) {
    val sport by vm.sport.collectAsState()
    val leagues = sport?.leagues.orEmpty()
    val cue = sport?.cueMinutes ?: 60
    val ambiguous = sport?.ambiguous.orEmpty().toSet()

    // A fixture list is a clock face: a match kicks off, another ends, and the
    // screen is wrong until something recomposes it. Ticking every half minute
    // costs nothing and keeps "starts in 5 min" honest.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    val fixtures = remember(bundle.events, leagues, now / 60_000) {
        val parsed = bundle.events.mapNotNull { channel ->
            val id = channel.xtreamId ?: return@mapNotNull null
            SportsParser.parse(id, channel.name, now, leagues, ambiguous)
        }
        SportsParser.upcoming(parsed, now, cue)
    }

    if (leagues.isEmpty()) {
        StatusPane(
            title = "Sport isn't set up",
            message = "This playlist's manifest carries no leagues.",
            icon = Icons.Default.SportsSoccer,
        )
        return
    }
    if (fixtures.isEmpty()) {
        StatusPane(
            title = "Nothing on right now",
            message = "Fixtures appear here an hour before kick-off.",
            icon = Icons.Default.SportsSoccer,
        )
        return
    }

    // Grouped by league, in the manifest's own order, so the sports a viewer
    // follows sit where they were last time rather than moving with the
    // fixture list.
    val byLeague = remember(fixtures, leagues) {
        leagues.keys.mapNotNull { league ->
            fixtures.filter { it.league == league }.takeIf { it.isNotEmpty() }?.let { league to it }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        items(byLeague, key = { it.first }) { (league, list) ->
            Column {
                SectionTitle(league)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.forEach { event ->
                        FixtureRow(event, now) {
                            vm.playEvent(event.streamId, event.alternates)
                            onPlay()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FixtureRow(event: SportsEvent, nowMs: Long, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(NuxShape.Row),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuxColors.SurfaceRaised,
            focusedContainerColor = NuxColors.SurfaceRaised,
            contentColor = NuxColors.OnSurface,
            focusedContentColor = NuxColors.OnSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = NuxFocus.RowScale),
        border = ClickableSurfaceDefaults.border(focusedBorder = NuxFocus.ring12),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.m, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = statusOf(event, nowMs),
                style = MaterialTheme.typography.labelLarge,
                color = if (event.live) NuxColors.Primary else NuxColors.OnSurfaceDim,
            )
        }
    }
}

/**
 * "LIVE" for something already running; otherwise how long until kick-off,
 * because a clock time on its own makes the viewer do the arithmetic.
 */
private fun statusOf(event: SportsEvent, nowMs: Long): String {
    val start = event.startMs ?: return "LIVE"
    if (start <= nowMs) return "LIVE"
    val minutes = ((start - nowMs) / 60_000).toInt()
    return when {
        minutes <= 1 -> "Starts now"
        minutes < 60 -> "in $minutes min"
        else -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(start))
    }
}
