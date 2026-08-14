@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuxcor.nuxtv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuxcor.nuxtv.MainViewModel
import com.nuxcor.nuxtv.data.Movie
import com.nuxcor.nuxtv.data.Series
import com.nuxcor.nuxtv.ui.components.PosterCard
import com.nuxcor.nuxtv.ui.components.SectionTitle
import com.nuxcor.nuxtv.ui.components.WideItem
import com.nuxcor.nuxtv.ui.theme.NuxColors

@Composable
fun SearchTab(
    vm: MainViewModel,
    onOpenMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onPlay: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val hidden by vm.hidden.collectAsState()
    val results = remember(query, vm.content.value, hidden) {
        vm.search(query).let { it.copy(channels = vm.visibleChannels(it.channels)) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp, vertical = 28.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { androidx.compose.material3.Text("Search channels, movies and series") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = NuxColors.OnSurface,
                unfocusedTextColor = NuxColors.OnSurface,
                focusedContainerColor = NuxColors.Surface,
                unfocusedContainerColor = NuxColors.Surface.copy(alpha = 0.6f),
                focusedBorderColor = NuxColors.Primary,
                unfocusedBorderColor = NuxColors.SurfaceVariant,
                focusedLabelColor = NuxColors.Primary,
                unfocusedLabelColor = NuxColors.OnSurfaceDim,
                cursorColor = NuxColors.Primary,
            ),
        )
        Spacer(Modifier.height(20.dp))

        val empty = results.channels.isEmpty() && results.movies.isEmpty() && results.series.isEmpty()
        when {
            query.trim().length < 2 -> Text(
                "Type at least two characters to search your library.",
                style = MaterialTheme.typography.bodyMedium,
                color = NuxColors.OnSurfaceDim,
            )

            empty -> Text(
                "No results for \"${query.trim()}\".",
                style = MaterialTheme.typography.bodyMedium,
                color = NuxColors.OnSurfaceDim,
            )

            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                if (results.movies.isNotEmpty()) {
                    item(key = "movies") {
                        Column {
                            SectionTitle("Movies  ·  ${results.movies.size}")
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                itemsIndexed(results.movies, key = { _, m -> m.id }) { _, movie ->
                                    PosterCard(
                                        title = movie.name,
                                        subtitle = movie.year?.toString(),
                                        imageUrl = movie.poster,
                                        onClick = { onOpenMovie(movie) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (results.series.isNotEmpty()) {
                    item(key = "series") {
                        Column {
                            SectionTitle("Series  ·  ${results.series.size}")
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                itemsIndexed(results.series, key = { _, s -> s.id }) { _, series ->
                                    PosterCard(
                                        title = series.name,
                                        subtitle = series.episodes?.let { "${it.size} episodes" },
                                        imageUrl = series.poster,
                                        onClick = { onOpenSeries(series) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (results.channels.isNotEmpty()) {
                    item(key = "channels-title") { SectionTitle("Live channels  ·  ${results.channels.size}") }
                    itemsIndexed(results.channels, key = { _, c -> c.id }) { index, channel ->
                        WideItem(
                            title = channel.name,
                            subtitle = channel.number?.let { "Channel $it" },
                            badge = channel.quality,
                            imageUrl = channel.logo,
                            onClick = {
                                vm.playChannels(results.channels, index)
                                onPlay()
                            },
                        )
                    }
                }
            }
        }
    }
}
