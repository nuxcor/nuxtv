@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.agoro.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.drawBehind
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
import com.agoro.tv.MainViewModel
import com.agoro.tv.data.ContentState
import com.agoro.tv.data.Movie
import com.agoro.tv.data.Series
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import com.agoro.tv.ui.components.BackdropLayer
import com.agoro.tv.ui.components.animateToOrSnap
import com.agoro.tv.ui.components.StatusAction
import com.agoro.tv.ui.components.StatusPane
import com.agoro.tv.ui.components.requestFocusRetrying
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxMotion
import com.agoro.tv.ui.theme.NuxShape
import com.agoro.tv.ui.theme.Space
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
    var tab by rememberSaveable { mutableStateOf(HomeTab.Home) }
    /**
     * The tab search was opened from, which is where Back out of it returns.
     *
     * Search used to be reachable only from Home's pill, so "Back goes Home"
     * and "Back goes where you came from" were the same sentence. They are
     * not any more — the browse strips have a Search chip and the remote's
     * search key opens it from anywhere — and sending a viewer who searched
     * from Shows back to Home loses them the shelf they were standing in.
     */
    var searchOrigin by rememberSaveable { mutableStateOf(HomeTab.Home) }
    val openSearch = {
        // Guarded, so a second press while search is already up cannot make
        // Search its own origin and strand Back on this screen.
        if (tab != HomeTab.Search) searchOrigin = tab
        tab = HomeTab.Search
    }
    // Non-content tabs also work while the playlist is loading or failed.
    val contentState by vm.content.collectAsState()
    // The Home lounge's focused-card hero, hoisted so its backdrop can draw
    // full-bleed across the content lane — outside the gutter-padded Box every
    // tab composes into. Debounced by the lounge before it lands here.
    var homeHero by remember { mutableStateOf<HeroInfo?>(null) }
    var railFocused by remember { mutableStateOf(false) }
    // The rail is a drawer now: not composed at all while browsing, sliding
    // in over a scrim when summoned. LEFT past the content's edge or BACK
    // opens it; focus leaving it closes it.
    var railVisible by remember { mutableStateOf(false) }
    val railFocus = remember { FocusRequester() }
    val railScope = rememberCoroutineScope()
    var railDismiss by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // When the drawer last closed; the catcher ignores arrivals right after,
    // so a commit's own focus churn can never resurrect the drawer.
    var railClosedAtMs by remember { mutableStateOf(0L) }
    fun openRail() {
        railVisible = true
        // The drawer composes on the flip above; focus can only land after.
        railScope.launch { railFocus.requestFocusRetrying() }
    }
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
    // Whether anything in the content lane holds focus right now. The
    // parking loops below check it before every attempt: a tab that has
    // already seated focus on the card the viewer left (each tab's own
    // arrival logic) must not have it yanked to the pane's first focusable
    // by a shell retry that fires a frame later — which is exactly what
    // happened on every return from a detail page.
    var contentHasFocus by remember { mutableStateOf(false) }
    suspend fun parkInContent(retries: Int, intervalMs: Long): Boolean {
        repeat(retries) { attempt ->
            if (contentHasFocus) return true
            if (runCatching { contentFocus.requestFocus() }.getOrDefault(false)) return true
            if (attempt < retries - 1) delay(intervalMs)
        }
        return contentHasFocus
    }
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
    // WHICH key, not just when. The edge catcher below is a focus target, and
    // a focus target can be reached by any direction the geometric search
    // fancies — so "a key was pressed recently" was true of every press the
    // viewer made while travelling, and an UP or DOWN that happened to land
    // on the catcher opened the drawer.
    var lastKeyCode by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        // A return is not a launch. Every tab seats its own arrival focus on
        // the card the viewer left, and this loop's first attempt used to
        // fire in the same dispatch — landing on the first focusable (the
        // Search pill, a strip chip) for a frame or three whenever the
        // remembered card was not in the first frame, before the tab's own
        // request hopped it back. On a return the shell only backstops: if
        // the tab has not seated anything after a beat, park as before.
        if (hasLaunched) {
            delay(150)
            if (!contentHasFocus) parkInContent(retries = 25, intervalMs = 80)
            return@LaunchedEffect
        }
        // Park on the CONTENT, always — on launch that is the Live guide,
        // whose entry redirect lands focus on the current programme, so the
        // app boots one OK away from watching. Parking on the rail was both
        // worse UX and fragile: the splash screen's dismissal re-runs the
        // window's default focus placement and could leave nothing focused
        // at all. A long retry window, deliberately: this races a COLD
        // start, where content composes many frames in — not one.
        if (!parkInContent(retries = 25, intervalMs = 80)) {
            // The loading pane has nothing to take focus, so a cold start
            // slower than the first window used to fall through here. When
            // the rail was a fixed strip that fallback was invisible; as a
            // drawer it OPENED on boot. Stay patient while the library
            // lands — the drawer is the landing only when content never
            // produces anything focusable at all.
            if (!parkInContent(retries = 100, intervalMs = 120)) {
                openRail()
            }
        }
        hasLaunched = true
    }

    BackHandler(enabled = !railFocused) {
        openRail()
    }
    BackHandler(enabled = railFocused && !exitArmed) {
        exitArmed = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    lastKeyDownMs = android.os.SystemClock.uptimeMillis()
                    lastKeyCode = event.key.nativeKeyCode
                }
                // The remote's own search button. The only entry point that
                // costs no screen and no D-pad travel, and the only one the
                // guide and Live get at all — both are too dense to spend a
                // chip on, and both are where a viewer is most likely to be
                // hunting for something by name.
                //
                // Consumed on BOTH edges: taking the down and letting the up
                // through leaves a stray KeyUp for whatever chip or cell is
                // focused underneath. Nothing else in this app wants this
                // key, so previewing it here — above the rail, the tabs and
                // the guide — is the one place it cannot be swallowed first.
                // Declined while the drawer is open: it would swap the tab
                // behind a drawer the viewer still has to dismiss, and the
                // rail is its own navigation. Not gated on text entry —
                // Settings keeps its fields in dialogs, which carry their own
                // window and never see this handler, and the only inline
                // field in here belongs to search itself.
                if (event.key == Key.Search && !railFocused) {
                    if (event.type == KeyEventType.KeyDown) openSearch()
                    return@onPreviewKeyEvent true
                }
                false // everything else: observe only, never consume
            },
    ) {
    // Content gets the whole panel: the drawer overlays it when summoned
    // rather than shifting it, so nothing reflows and no strip of icons
    // sits in the corner of the eye while watching posters.
    Box(modifier = Modifier.fillMaxSize()) {
        if (tab == HomeTab.Home && contentState is ContentState.Ready) {
            // Already outside the gutter padding here, and stopping at the
            // rail is deliberate — so no bleed.
            BackdropLayer(
                borrowedArt(vm, homeHero?.art, homeHero?.backdrop, wide = true)
                    ?: homeHero?.poster,
                bleedX = 0.dp,
                bleedY = 0.dp,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = Space.gutter, end = Space.gutter, top = Space.gutterVertical, bottom = Space.gutterVertical)
                .focusRequester(contentFocus)
                .onFocusChanged { contentHasFocus = it.hasFocus }
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
            // (no Crossfade, see above) — only a short rise animates, so
            // focus targeting and the guide's registry see final layout on
            // frame one.
            //
            // A rise, and NOT a fade. Alpha on this Box put the entire
            // content lane — every shelf, poster and backdrop — through a
            // full-screen offscreen layer for fifteen frames, in the same
            // frames the new tab was composing its lists and fetching its
            // images. That is the costliest thing a TV GPU can be asked for
            // and it ran on every tab switch. A translation is free: it is
            // a transform on the display list, not a buffer.
            //
            // Nor on a return. Home leaves composition for every channel and
            // every detail page, so remember(current) was fresh on the way
            // back and the whole lounge rose into view again behind the
            // cut — the launch choreography replayed for a screen the viewer
            // had only stepped away from. A visit that has already launched
            // snaps straight to its resting place.
            val tabEntrance = remember(current) { Animatable(if (hasLaunched) 1f else 0f) }
            LaunchedEffect(current) {
                if (tabEntrance.value == 1f) return@LaunchedEffect
                try {
                    // Snap-on-timeout: an idle window can starve the frame
                    // clock, leaving animateTo suspended and the whole tab
                    // composed but painted at alpha 0 until the first key
                    // press. The wall-clock timeout doesn't need frames, and
                    // snapTo's value change is what restarts drawing.
                    tabEntrance.animateToOrSnap(
                        1f,
                        tween(NuxMotion.StandardMs, easing = NuxMotion.StandardEasing),
                        timeoutMs = NuxMotion.StandardMs + 500L,
                    )
                } finally {
                    // And whatever cancels this effect mid-flight (rapid
                    // dwell-driven tab hops), still land on visible.
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        tabEntrance.snapTo(1f)
                    }
                }
            }
            Box(
                modifier = Modifier.graphicsLayer {
                    translationY = (1f - tabEntrance.value) * (NuxMotion.EntranceRise.toPx() * 0.66f)
                }
            ) {
            // While the rail holds focus, the tab underneath must not grab it.
            // Tabs switch on dwell as focus travels the rail, so a pane that
            // focuses itself on arrival strands the viewer in the content with
            // UP/DOWN dead. Re-armed the instant focus enters the content.
            androidx.compose.runtime.CompositionLocalProvider(
                com.agoro.tv.ui.components.LocalArrivalFocusAllowed provides !railFocused
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
                        HomeTab.Home -> HomeLoungeTab(
                            vm,
                            state.bundle,
                            onOpenMovie,
                            onOpenSeries,
                            onPlay,
                            onHeroChange = { homeHero = it },
                            // Home's own pill and empty-state action come
                            // through here; routed so search seats its origin
                            // however it was reached.
                            onBrowse = { if (it == HomeTab.Search) openSearch() else tab = it },
                        )
                        HomeTab.Search -> SearchTab(
                            vm, onOpenMovie, onOpenSeries, onPlay,
                            onBack = { tab = searchOrigin },
                        )
                        HomeTab.Live -> LiveTab(vm, state.bundle, onPlay, onOpenSettings = { tab = HomeTab.Settings })
                        HomeTab.Sport -> SportTab(vm, state.bundle, onPlay, onBrowse = { tab = it })
                        HomeTab.Movies -> MoviesTab(
                            vm, state.bundle, onOpenMovie,
                            onPlay = onPlay,
                            onOpenSettings = { tab = HomeTab.Settings },
                            onOpenSearch = openSearch,
                        )
                        HomeTab.Series -> SeriesTab(
                            vm, state.bundle, onOpenSeries,
                            onOpenSettings = { tab = HomeTab.Settings },
                            onOpenSearch = openSearch,
                        )
                        HomeTab.Settings -> Unit // composed above, state-independent
                    }
                }
            }
            }
            }
        }
    }
    // Available or Ready — the two states with something for the viewer to
    // act on. Checking and Downloading are already in motion, and a dot for
    // them would nag about work the app is doing by itself.
    // Top-right, above everything: the readout has to survive the drawer and
    // any pane that opens over the content, or it stops measuring exactly
    // when the viewer is doing the thing that feels slow.
    // Debug builds only. It used to be a Settings row that asked the viewer
    // to read frame times back to me — an instrument parked where someone had
    // come to pick a channel order. The measurement still only means anything
    // on real hardware, so a debug build is how it gets taken.
    com.agoro.tv.ui.components.FrameStatsOverlay(
        enabled = com.agoro.tv.BuildConfig.DEBUG,
        modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp),
    )
    val updateState by vm.updateState.collectAsState()
    // Invisible sliver at the panel's edge: the drawer is not composed while
    // hidden, so LEFT off the content's first column needs somewhere to land.
    // Gone while the drawer is open — it sits underneath the drawer, and the
    // geometric search kept picking it over the next drawer item, whose
    // summons then bounced focus straight back: UP/DOWN inside the open
    // drawer looked dead on real hardware.
    //
    // Reachable ONLY by a LEFT press. It is full-height and focusable, so the
    // geometric search could pick it from anywhere in the content — and it
    // did, whenever the cell UP or DOWN was aiming for had not been composed
    // yet, which in a windowed grid is often. The drawer then opened in the
    // middle of travelling a row of channels.
    //
    // Refusing focus rather than bouncing back, because bouncing was its own
    // bug: the recovery hands focus to the content ROOT, which lands on
    // whatever the content focuses first, so an accidental arrival did not
    // just open the drawer — it threw away the viewer's place in the grid.
    // Refused, the search simply finds nothing that way and focus stays put,
    // which is the right answer at the edge of a grid.
    if (!railVisible) Box(
        modifier = Modifier
            .align(Alignment.CenterStart)
            .width(2.dp)
            .fillMaxHeight()
            .focusProperties {
                canFocus = lastKeyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
            }
            .onFocusChanged {
                if (!it.isFocused) return@onFocusChanged
                // The system's default focus placement lands here on boot —
                // it is the left-most focusable — and the drawer opened on
                // every cold start. Only an arrival that closely follows a
                // real key press is a summons; anything else bounces back
                // to the content.
                val now = android.os.SystemClock.uptimeMillis()
                if (lastKeyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT &&
                    now - lastKeyDownMs < 1_000 && now - railClosedAtMs > 700
                ) {
                    openRail()
                } else {
                    railScope.launch { contentFocus.requestFocusRetrying() }
                }
            }
            .focusable(),
    )
    // Dim the content while the drawer is up, so the two read as layers.
    // The fade animates the scrim's COLOUR in drawBehind, the way
    // DialogScaffold does — not a layer alpha over a full-screen Box, which
    // is an offscreen buffer the size of the screen for every frame of the
    // fade, on the one gesture the viewer makes most.
    val scrimProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (railVisible) 1f else 0f,
        animationSpec = if (railVisible) {
            tween(NuxMotion.StandardMs, easing = NuxMotion.StandardEasing)
        } else {
            tween(NuxMotion.FastMs, easing = NuxMotion.ExitEasing)
        },
        label = "drawerScrim",
    )
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val a = scrimProgress
                if (a > 0f) drawRect(NuxColors.Scrim.copy(alpha = NuxColors.Scrim.alpha * a))
            }
    )
    androidx.compose.animation.AnimatedVisibility(
        visible = railVisible,
        enter = androidx.compose.animation.slideInHorizontally(
            tween(NuxMotion.StandardMs, easing = NuxMotion.StandardEasing)
        ) { -it } + androidx.compose.animation.fadeIn(
            tween(NuxMotion.StandardMs, easing = NuxMotion.StandardEasing)
        ),
        exit = androidx.compose.animation.slideOutHorizontally(
            tween(NuxMotion.FastMs, easing = NuxMotion.ExitEasing)
        ) { -it } + androidx.compose.animation.fadeOut(
            tween(NuxMotion.FastMs, easing = NuxMotion.ExitEasing)
        ),
    ) {
        NavRail(
            // Search is railless, so the rail borrows the tab it was opened
            // from — which is also where Back out of search returns. Anchoring
            // it to Home was right only while Home's pill was the one way in;
            // opening search from Shows and then opening the rail put the
            // cursor on Home while Back went to Shows, the two controls
            // disagreeing about where the viewer had come from.
            selected = if (tab == HomeTab.Search) searchOrigin else tab,
            // OK commits: switch the tab, close the drawer, and hand focus to
            // the content — the modal form of what the old rail did on dwell.
            // Selecting under an open drawer recomposed the screen beneath it
            // and the reshuffle bounced focus back to the first item.
            // Focus moves FIRST, the drawer closes after. Closing first left
            // focus in free fall for a frame; it landed on the edge catcher,
            // which read the commit's own key press as a summons and reopened
            // the drawer it had just closed.
            onSelect = {
                tab = it
                railDismiss?.cancel()
                // The gate first: content refuses focus while the rail holds
                // it (LocalArrivalFocusAllowed), so the hand-off can only
                // land after railFocused drops. The drawer stays composed
                // until focus is safely in the content — closing first left
                // focus in free fall onto the edge catcher, which reopened
                // the drawer it had just closed.
                railFocused = false
                railScope.launch {
                    parkInContent(retries = 25, intervalMs = 80)
                    railClosedAtMs = android.os.SystemClock.uptimeMillis()
                    railVisible = false
                }
            },
            railFocus = railFocus,
            // Dismissal must survive a beat. The container reports "not
            // focused" both as it attaches AND for the instant a child-to-
            // child handoff clears focus before reassigning it — dismissing
            // on that instant closed the drawer on every UP/DOWN press, and
            // the edge catcher resurrected it on the first item: the whole
            // drawer read as frozen on Home. A loss only counts if nothing
            // reclaims focus within the debounce.
            onRailFocusChanged = { focused ->
                railDismiss?.cancel()
                if (focused) {
                    railFocused = true
                } else if (railFocused) {
                    railDismiss = railScope.launch {
                        delay(80)
                        railFocused = false
                        railClosedAtMs = android.os.SystemClock.uptimeMillis()
                        railVisible = false
                    }
                }
            },
            // Only the three states where something can actually be done.
            // Checking and Error stay out of the rail: a background check that
            // failed is not news a viewer opened a drawer for, and Settings
            // carries both in full. It goes away by itself — installing makes
            // the next check UpToDate, and the row has nothing to say.
            updateLabel = when (val u = updateState) {
                is com.agoro.tv.data.UpdateManager.State.Available ->
                    "Update to ${u.version.removePrefix("v")}"
                is com.agoro.tv.data.UpdateManager.State.Downloading ->
                    "Downloading… ${u.progressPercent}%"
                is com.agoro.tv.data.UpdateManager.State.Ready -> "Install update"
                else -> null
            },
            // The same call Settings' one button makes, so the two can never
            // disagree about what pressing means in a given state. A press
            // while downloading is already a no-op there.
            onUpdate = { vm.downloadAndInstallUpdate() },
        )
    }
    // One-time teach: the rail is invisible until summoned now, and a first
    // session with no visible navigation needs one line saying where it went.
    // Retires after a single showing, or the moment the drawer is first
    // opened — whichever comes first.
    val menuHintSeen by vm.menuHintSeen.collectAsState()
    var showMenuHint by remember { mutableStateOf(false) }
    LaunchedEffect(hasLaunched, menuHintSeen) {
        if (!hasLaunched || menuHintSeen) return@LaunchedEffect
        delay(1_500) // let the landing screen settle before speaking
        if (railVisible) { vm.markMenuHintSeen(); return@LaunchedEffect }
        showMenuHint = true
        delay(8_000)
        showMenuHint = false
        vm.markMenuHintSeen()
    }
    // Opening the drawer IS the lesson — retire the hint on the spot.
    LaunchedEffect(railVisible) {
        if (railVisible && showMenuHint) {
            showMenuHint = false
            vm.markMenuHintSeen()
        }
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = showMenuHint && !railVisible && !exitArmed,
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
                "Press BACK for the menu",
                style = MaterialTheme.typography.labelLarge,
                color = NuxColors.OnSurface,
            )
        }
    }
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
