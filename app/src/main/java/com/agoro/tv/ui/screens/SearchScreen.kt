@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.agoro.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.focus.FocusRequester
import com.agoro.tv.ui.components.requestFocusRetrying
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Alignment
import com.agoro.tv.ui.theme.Space
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.agoro.tv.MainViewModel
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.data.Movie
import com.agoro.tv.data.Series
import com.agoro.tv.ui.components.ChannelShelfCard
import com.agoro.tv.ui.components.ContextMenu
import com.agoro.tv.ui.components.MenuAction
import com.agoro.tv.ui.components.NuxFieldDefaults
import com.agoro.tv.ui.components.StatusPane
import com.agoro.tv.ui.components.dpadFieldNavigation
import com.agoro.tv.ui.components.rememberClockFormat
import com.agoro.tv.ui.components.PosterCard
import com.agoro.tv.ui.components.ShelfRingRoom
import com.agoro.tv.ui.components.shelfRingRoom
import com.agoro.tv.ui.components.SectionTitle
import com.agoro.tv.ui.components.WideItem
import com.agoro.tv.ui.theme.NuxColors

@Composable
fun SearchTab(
    vm: MainViewModel,
    onOpenMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onPlay: () -> Unit,
    /** BACK: Search is entered from Home's pill, so BACK goes back to Home. */
    onBack: () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    // Search is not a rail destination: the viewer came from Home's pill, and
    // BACK should return them there, not open the drawer they did not come
    // from. (The IME's own BACK closes the keyboard first, as it should.)
    androidx.activity.compose.BackHandler(onBack = onBack)
    var statusMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(statusMessage) {
        if (statusMessage != null) {
            kotlinx.coroutines.delay(4_000)
            statusMessage = null
        }
    }
    val contentState by vm.content.collectAsState()
    val visible by vm.displayChannels.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val nowNext by vm.nowNext.collectAsState()
    var menuChannel by remember { mutableStateOf<LiveChannel?>(null) }
    // The card the menu was opened on keeps a requester after the menu
    // closes, so focus can come back to it: left to Compose, a dismissed
    // menu dropped focus onto the query field and the keyboard popped up
    // over the results.
    var menuOrigin by remember { mutableStateOf<String?>(null) }
    val menuOriginFocus = remember { FocusRequester() }
    // Focus comes back in the SAME FRAME the menu unmounts, not after a
    // wall-clock wait. This used to delay 120ms before asking, and in that
    // window Compose had already reseated focus on the nearest node — the
    // query field, keyboard and all — so a quick press after closing typed
    // into it. The request cannot be made before the menu is gone: the
    // dialog scaffold cancels any focus exit while it stands. Armed by the
    // dismissal (a plain holder: nothing in composition reads it) and run by
    // the effect keyed on the menu state once the frame that removed it has
    // applied, before the next key event can arrive. The retries are the
    // bounded fallback for a shelf still recomposing; a hidden channel
    // leaves the shelf, so a refusal there just means the card is gone.
    val returnFocusPending = remember { booleanArrayOf(false) }
    LaunchedEffect(menuChannel) {
        if (menuChannel != null || !returnFocusPending[0]) return@LaunchedEffect
        returnFocusPending[0] = false
        menuOriginFocus.requestFocusRetrying(retries = 5, intervalMs = 60)
    }
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
    val dayFmt = remember { java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()) }
    /** "9:00 AM", "Tomorrow 9:00 AM", "Mon 9:00 AM" — the day only when it is not today. */
    fun airTime(startMs: Long): String {
        val time = timeFmt.format(java.util.Date(startMs))
        val cal = java.util.Calendar.getInstance()
        val today = cal.get(java.util.Calendar.DAY_OF_YEAR)
        cal.timeInMillis = startMs
        val day = cal.get(java.util.Calendar.DAY_OF_YEAR)
        return when (day - today) {
            0 -> time
            1 -> "Tomorrow $time"
            else -> "${dayFmt.format(java.util.Date(startMs))} $time"
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        // Search is entered deliberately (Home's pill — it left the rail), so
        // the query field takes focus on arrival instead of stranding it
        // wherever the pill's departure dropped it.
        val fieldFocus = com.agoro.tv.ui.components.rememberInitialFocus(Unit)

        // Voice is the only humane way to type on a remote: the alternative is
        // walking a D-pad around a grid of letters. Handed to the system
        // recogniser rather than run in-process, so the app holds no
        // RECORD_AUDIO and the prompt is the one the viewer already knows.
        val context = androidx.compose.ui.platform.LocalContext.current
        val voiceIntent = remember {
            android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say a channel, film or show")
            }
        }
        // Offered only where something can answer it. Plenty of TV boxes ship
        // without a recogniser, and a mic that opens nothing is worse than no
        // mic: it is a control the viewer has to learn is broken.
        val canSpeak = remember {
            runCatching {
                voiceIntent.resolveActivity(context.packageManager) != null
            }.getOrDefault(false)
        }
        val speech = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val said = result.data
                ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            // A cancelled prompt returns nothing: leave what was typed alone
            // rather than clearing the field the viewer may have been editing.
            if (said.isNotEmpty()) query = said
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { androidx.compose.material3.Text("Search channels, movies and shows") },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(fieldFocus)
                    .dpadFieldNavigation(),
                colors = NuxFieldDefaults.colors(),
            )
            if (canSpeak) {
                androidx.tv.material3.OutlinedButton(
                    onClick = {
                        // Never let a missing or broken recogniser take the
                        // screen down; the field still works without it.
                        runCatching { speech.launch(voiceIntent) }
                    },
                ) {
                    androidx.compose.material3.Icon(
                        Icons.Default.Mic,
                        contentDescription = "Search by voice",
                    )
                }
            }
        }
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
                            LazyRow(
                                modifier = Modifier.focusRestorer().shelfRingRoom(),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                contentPadding = PaddingValues(horizontal = ShelfRingRoom),
                            ) {
                                itemsIndexed(results.movies, key = { _, m -> m.id }) { _, movie ->
                                    PosterCard(
                                        title = movie.name,
                                        imageUrl = borrowedArt(vm, movie.artRef(), movie.poster),
                                        year = movie.year,
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
                            SectionTitle("Shows", results.series.size)
                            LazyRow(
                                modifier = Modifier.focusRestorer().shelfRingRoom(),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                contentPadding = PaddingValues(horizontal = ShelfRingRoom),
                            ) {
                                itemsIndexed(results.series, key = { _, s -> s.id }) { _, series ->
                                    PosterCard(
                                        title = series.name,
                                        imageUrl = borrowedArt(vm, series.artRef(), series.poster),
                                        year = series.year,
                                        onClick = { onOpenSeries(series) },
                                        onFocus = { vm.prefetchEpisodes(series) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (results.channels.isNotEmpty()) {
                    // A shelf, matching the two above it and Home's rows. As a
                    // stack of full-width text rows this was the one place a
                    // channel arrived without its logo at a readable size, and
                    // without what is on it right now — the two things that
                    // tell you whether it is the channel you meant.
                    item(key = "channels") {
                        Column {
                            SectionTitle("Live channels", results.channels.size)
                            LazyRow(
                                modifier = Modifier.focusRestorer().shelfRingRoom(),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                contentPadding = PaddingValues(horizontal = ShelfRingRoom),
                            ) {
                                itemsIndexed(
                                    results.channels,
                                    key = { _, c -> c.id },
                                ) { index, channel ->
                                    ChannelShelfCard(
                                        channel = channel,
                                        now = nowNext[channel.id]?.now,
                                        modifier = if (channel.id == menuOrigin) {
                                            Modifier.focusRequester(menuOriginFocus)
                                        } else Modifier,
                                        onClick = {
                                            vm.playChannels(results.channels, index)
                                            onPlay()
                                        },
                                        onLongClick = {
                                            menuOrigin = channel.id
                                            menuChannel = channel
                                        },
                                    )
                                }
                            }
                        }
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
                            subtitle = "${hit.channel.displayName} • " + airTime(hit.program.startMs),
                            // The same vocabulary as the guide's header chip,
                            // so OK does what the row says it does.
                            badge = when {
                                airing -> "ON NOW"
                                hit.channel.recordUrl != null -> "OK to record"
                                else -> "OK to remind"
                            },
                            imageUrl = hit.channel.logo,
                            onClick = {
                                // The guide's rule: a programme on now plays;
                                // one still to come is recorded or remembered.
                                // Tuning a channel eight hours before the thing
                                // you searched for is not watching it.
                                val now = System.currentTimeMillis()
                                if (hit.program.startMs <= now) {
                                    vm.playChannels(listOf(hit.channel), 0)
                                    onPlay()
                                } else if (vm.scheduleRecording(hit.channel, hit.program)) {
                                    statusMessage = "Recording scheduled: ${hit.program.title}"
                                } else {
                                    vm.scheduleReminder(hit.channel, hit.program)
                                    statusMessage = "Reminder set: ${hit.program.title}"
                                }
                            },
                        )
                    }
                }
            }
        }
    }
    com.agoro.tv.ui.components.ToastBadge(
        message = statusMessage,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(bottom = Space.m, end = Space.m),
    )
    }
    menuChannel?.let { channel ->
        val isFav = channel.url in favorites
        ContextMenu(
            title = channel.displayName,
            actions = listOf(
                MenuAction("Play") {
                    val index = results.channels.indexOf(channel).coerceAtLeast(0)
                    vm.playChannels(results.channels, index)
                    onPlay()
                },
                MenuAction(if (isFav) "Remove from favorites" else "Add to favorites") {
                    vm.toggleFavorite(channel)
                },
                MenuAction("Hide this channel") { vm.toggleHidden(channel) },
            ),
            onDismiss = {
                // Arm the return first, then unmount: the effect above runs
                // in the frame the menu leaves and finds the flag set.
                returnFocusPending[0] = true
                menuChannel = null
            },
        )
    }
}
