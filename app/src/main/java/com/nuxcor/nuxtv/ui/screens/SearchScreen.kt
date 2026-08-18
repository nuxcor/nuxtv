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
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuxcor.nuxtv.MainViewModel
import com.nuxcor.nuxtv.data.Movie
import com.nuxcor.nuxtv.data.Series
import com.nuxcor.nuxtv.ui.components.NuxFieldDefaults
import com.nuxcor.nuxtv.ui.components.StatusPane
import com.nuxcor.nuxtv.ui.components.dpadFieldNavigation
import com.nuxcor.nuxtv.ui.components.rememberClockFormat
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
    val contentState by vm.content.collectAsState()
    val visible by vm.displayChannels.collectAsState()
    var results by remember { mutableStateOf(MainViewModel.SearchResults()) }
    // Debounced off-main-thread search so typing stays smooth on huge playlists.
    LaunchedEffect(query, contentState, visible) {
        kotlinx.coroutines.delay(250)
        results = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val base = vm.search(query)
            val allowed = visible.mapTo(HashSet()) { it.id }
            base.copy(channels = base.channels.filter { it.id in allowed })
        }
    }

    val timeFmt = rememberClockFormat()
    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { androidx.compose.material3.Text("Search channels, movies and series") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().dpadFieldNavigation(),
            colors = NuxFieldDefaults.colors(),
        )
        Spacer(Modifier.height(20.dp))

        val empty = results.channels.isEmpty() && results.movies.isEmpty() &&
            results.series.isEmpty() && results.programs.isEmpty()
        when {
            query.trim().length < 2 -> StatusPane(
                title = "Search your library",
                // The field's own label already names what is searchable.
                message = "Type at least two characters.",
                icon = androidx.compose.material.icons.Icons.Default.Search,
            )

            empty -> StatusPane(
                title = "No results for \"${query.trim()}\"",
                message = "Check the spelling, or try a shorter word.",
                icon = androidx.compose.material.icons.Icons.Default.Search,
            )

            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                if (results.movies.isNotEmpty()) {
                    item(key = "movies") {
                        Column {
                            SectionTitle("Movies", results.movies.size)
                            LazyRow(modifier = Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                itemsIndexed(results.movies, key = { _, m -> m.id }) { _, movie ->
                                    PosterCard(
                                        title = movie.name,
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
                            SectionTitle("Series", results.series.size)
                            LazyRow(modifier = Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                itemsIndexed(results.series, key = { _, s -> s.id }) { _, series ->
                                    PosterCard(
                                        title = series.name,
                                        imageUrl = series.poster,
                                        onClick = { onOpenSeries(series) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (results.channels.isNotEmpty()) {
                    item(key = "channels-title") { SectionTitle("Live channels", results.channels.size) }
                    itemsIndexed(results.channels, key = { _, c -> c.id }) { index, channel ->
                        WideItem(
                            title = channel.displayName,
                            subtitle = null,
                            badge = channel.quality,
                            imageUrl = channel.logo,
                            onClick = {
                                vm.playChannels(results.channels, index)
                                onPlay()
                            },
                        )
                    }
                }
                if (results.programs.isNotEmpty()) {
                    item(key = "programs-title") { SectionTitle("On TV", results.programs.size) }
                    itemsIndexed(
                        results.programs,
                        key = { _, hit -> "${hit.channel.id}:${hit.program.startMs}" },
                    ) { _, hit ->
                        val airing = System.currentTimeMillis() in
                            hit.program.startMs until hit.program.endMs
                        WideItem(
                            title = hit.program.title,
                            subtitle = "${hit.channel.displayName} • " +
                                timeFmt.format(java.util.Date(hit.program.startMs)),
                            badge = if (airing) "ON NOW" else null,
                            imageUrl = hit.channel.logo,
                            onClick = {
                                vm.playChannels(listOf(hit.channel), 0)
                                onPlay()
                            },
                        )
                    }
                }
            }
        }
    }
}
