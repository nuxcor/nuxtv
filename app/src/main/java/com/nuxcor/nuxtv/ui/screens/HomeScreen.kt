@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.nuxcor.nuxtv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuxcor.nuxtv.MainViewModel
import com.nuxcor.nuxtv.data.ContentState
import com.nuxcor.nuxtv.data.Movie
import com.nuxcor.nuxtv.data.Series
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import com.nuxcor.nuxtv.ui.components.StatusAction
import com.nuxcor.nuxtv.ui.components.StatusPane
import com.nuxcor.nuxtv.ui.components.requestFocusRetrying
import com.nuxcor.nuxtv.ui.theme.NuxColors
import com.nuxcor.nuxtv.ui.theme.NuxMotion
import com.nuxcor.nuxtv.ui.theme.NuxShape
import com.nuxcor.nuxtv.ui.theme.Space
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onOpenMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onPlay: () -> Unit,
    onAddPlaylist: () -> Unit,
    onEditPlaylist: (String) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.Live) }
    // Non-content tabs also work while the playlist is loading or failed.
    val contentState by vm.content.collectAsState()
    var railFocused by remember { mutableStateOf(false) }
    var railExpanded by remember { mutableStateOf(false) }
    val railFocus = remember { FocusRequester() }
    // Hoisted above the Ready branch so a refresh cycle doesn't wipe tab state.
    val tabStateHolder = rememberSaveableStateHolder()

    // BACK from inside the content pane jumps focus to the rail first; on the
    // rail, BACK asks for confirmation instead of instantly quitting the app.
    var exitArmed by remember { mutableStateOf(false) }
    LaunchedEffect(exitArmed) {
        if (exitArmed) {
            delay(2_500)
            exitArmed = false
        }
    }
    val contentFocus = remember { FocusRequester() }
    // Survives this screen leaving composition, which is what going to the
    // player does: coming back is a return, not a launch.
    var hasLaunched by rememberSaveable { mutableStateOf(false) }

    // Without this the first D-pad press lands wherever Compose's focus search
    // happens to go. Park it somewhere predictable — and retry, since the
    // target composes a frame later.
    //
    // Where depends on why we are here. On launch that is the rail. Coming back
    // from the player it is the content, restored to the row that was focused
    // when the stream started: BACK out of a channel used to land on the rail,
    // several presses from the list it had just left, which is not going back.
    // Uptime of the last real key press; the rail's dwell-select only acts
    // on focus changes that closely follow one, so system-driven focus moves
    // (the splash dismissal's default placement) can never switch tabs.
    var lastKeyDownMs by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        val target = if (hasLaunched) contentFocus else railFocus
        // A long window, deliberately: this races a COLD start, where the
        // rail composes many frames in — not one.
        if (!target.requestFocusRetrying(retries = 25, intervalMs = 80)) {
            // Whatever we aimed at never composed; the rail always exists.
            runCatching { railFocus.requestFocus() }
        }
        hasLaunched = true
    }

    BackHandler(enabled = !railFocused) {
        runCatching { railFocus.requestFocus() }
    }
    BackHandler(enabled = railFocused && !exitArmed) {
        exitArmed = true
    }

    // The content lane tracks the rail's width instead of being covered by it.
    // It used to reserve a fixed 64dp and let the expanded 190dp rail draw on
    // top "so nothing reflows" — but the rail is expanded exactly when you are
    // reading the rail *and* the content, and 68dp of every line was sliced
    // off. Shifting with the animation costs nothing and is what TV launchers
    // do; the reflow the old comment avoided was never the greater evil.
    val railWidth by animateDpAsState(
        targetValue = if (railExpanded) RAIL_WIDTH_EXPANDED else RAIL_WIDTH_COLLAPSED,
        label = "railLane",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    lastKeyDownMs = android.os.SystemClock.uptimeMillis()
                }
                false // observe only; never consume
            },
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = railWidth)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = Space.gutter, end = Space.gutter, top = Space.gutterVertical, bottom = Space.gutterVertical)
                .focusRequester(contentFocus)
                .focusRestorer()
        ) {
            // No Crossfade: it keeps both tab trees composed and drawn into
            // offscreen layers — a visible hitch on TV hardware.
            //
            // Settings is composed from its saveable slot whatever the content
            // state is, rather than living inside the Ready branch with a second
            // copy stacked on top for the other states. Refresh sets Loading, so
            // the old shape tore Settings down mid-press and rebuilt a different
            // instance outside the state holder: scroll jumped to the top, D-pad
            // focus was lost, the counts and "Manage channels" vanished — then
            // all of it again in reverse when the load landed.
            val current = tab
            // One-shot entrance on tab switch: the tree still swaps instantly
            // (no Crossfade, see above) — only alpha/translation animate, so
            // focus targeting and the guide's registry see final layout on
            // frame one.
            val tabEntrance = remember(current) { Animatable(0f) }
            LaunchedEffect(current) {
                tabEntrance.animateTo(
                    1f,
                    tween(NuxMotion.StandardMs, easing = NuxMotion.StandardEasing),
                )
            }
            Box(
                modifier = Modifier.graphicsLayer {
                    alpha = tabEntrance.value
                    translationY = (1f - tabEntrance.value) * (NuxMotion.EntranceRise.toPx() * 0.66f)
                }
            ) {
            tabStateHolder.SaveableStateProvider(current.name) {
                if (current == HomeTab.Settings) {
                    SettingsTab(
                        vm = vm,
                        bundle = (contentState as? ContentState.Ready)?.bundle,
                        onAddPlaylist = onAddPlaylist,
                        onEditPlaylist = onEditPlaylist,
                    )
                } else when (val state = contentState) {
                    is ContentState.Loading -> StatusPane(title = state.message, loading = true)
                    is ContentState.Error -> StatusPane(
                        title = "Couldn't load your playlist",
                        message = state.message,
                        primaryAction = StatusAction("Retry") { vm.refresh() },
                    )
                    is ContentState.Empty -> StatusPane(
                        title = "No playlist loaded",
                        message = "Connect your provider to start watching.",
                        primaryAction = StatusAction("Add playlist", onAddPlaylist),
                    )
                    is ContentState.Ready -> when (current) {
                        HomeTab.Search -> SearchTab(vm, onOpenMovie, onOpenSeries, onPlay)
                        HomeTab.Live -> LiveTab(vm, state.bundle, onPlay)
                        HomeTab.Movies -> MoviesTab(vm, state.bundle, onOpenMovie)
                        HomeTab.Series -> SeriesTab(vm, state.bundle, onOpenSeries)
                        HomeTab.Recordings -> RecordingsTab(
                            vm,
                            onPlay,
                            onGoToGuide = { tab = HomeTab.Live },
                        )
                        HomeTab.Settings -> Unit // composed above, state-independent
                    }
                }
            }
            }
        }
    }
    NavRail(
        selected = tab,
        onSelect = { tab = it },
        railFocus = railFocus,
        onRailFocusChanged = { railFocused = it; railExpanded = it },
        lastUserKeyMs = { lastKeyDownMs },
    )
    androidx.compose.animation.AnimatedVisibility(
        visible = exitArmed,
        enter = androidx.compose.animation.fadeIn(
            tween(NuxMotion.StandardMs, easing = NuxMotion.StandardEasing)
        ) + androidx.compose.animation.slideInVertically(
            tween(NuxMotion.StandardMs, easing = NuxMotion.StandardEasing)
        ) { it / 2 },
        exit = androidx.compose.animation.fadeOut(
            tween(NuxMotion.FastMs, easing = NuxMotion.ExitEasing)
        ),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = 24.dp)
                .background(NuxColors.Scrim, NuxShape.Row)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Text(
                "Press BACK again to exit",
                style = MaterialTheme.typography.labelLarge,
                color = NuxColors.OnSurface,
            )
        }
    }
    }
}
