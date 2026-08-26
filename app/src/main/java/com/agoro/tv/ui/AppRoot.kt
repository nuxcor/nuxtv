package com.agoro.tv.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.agoro.tv.MainViewModel
import com.agoro.tv.StartTarget
import com.agoro.tv.ui.screens.HomeScreen
import com.agoro.tv.ui.screens.MovieDetailScreen
import com.agoro.tv.ui.screens.OnboardingScreen
import com.agoro.tv.ui.player.PlayerScreen
import com.agoro.tv.ui.screens.SeriesDetailScreen
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.HeroGlow
import com.agoro.tv.ui.theme.Space

private enum class RootScreen { Boot, Onboarding, Main }

/**
 * Real TVs crop the frame edges (overscan). Browsing screens keep this safe
 * inset; the player stays full-bleed. Uses the same [Space] gutters as Home so
 * content doesn't shift when you open a detail page.
 */
@Composable
private fun TvSafe(
    /**
     * Painted full-bleed, behind the overscan inset. A screen that wants its
     * own background has to hand it over rather than paint it itself: painted
     * inside the padding it leaves the theme's page gradient showing in the
     * margin, which reads as a lighter frame around the whole screen.
     */
    background: androidx.compose.ui.graphics.Brush? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (background != null) Modifier.background(background) else Modifier)
            .padding(horizontal = Space.gutter, vertical = Space.gutterVertical)
    ) { content() }
}

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val sources by vm.sources.collectAsState()

    // Coming back to the app is the natural moment for a catalog catch-up:
    // the in-app hourly cycle only helps while the app stays open, and TV
    // apps live most of their lives suspended behind the launcher. Gated on
    // cache age inside, so a same-evening resume costs nothing.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.refreshIfStale()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The system splash stays up until sources resolve (see MainActivity), so
    // Boot is only ever reached if that hold hit its deadline. It is a bare
    // background rather than a second animated logo: the splash already showed
    // the mark, and showing it again is the whole reason startup felt slow.
    val startTarget by vm.startTarget.collectAsState()

    val screen = when {
        sources == null -> RootScreen.Boot
        sources!!.isEmpty() -> RootScreen.Onboarding
        // Still working out whether this launch opens on a channel. Holding
        // the boot background through that is what keeps a resume from
        // flashing Home on its way to the player — and it costs nothing for
        // an install with nothing to resume, which settles it on the first
        // read. See MainViewModel.startTarget.
        startTarget == StartTarget.Pending -> RootScreen.Boot
        else -> RootScreen.Main
    }

    // A cut, not a dissolve, when what is appearing is the player. The video
    // surface ignores Compose alpha, so a fade lays a dissolving black scrim
    // over a picture already at full brightness — the very artefact NuxNavHost
    // strips its own transitions to avoid, on the one launch this feature
    // exists to make seamless.
    val rootFadeMs = if (startTarget == StartTarget.Player) 0 else 260

    Crossfade(targetState = screen, animationSpec = tween(rootFadeMs), label = "root") { target ->
        when (target) {
            RootScreen.Boot -> Box(
                modifier = Modifier.fillMaxSize().background(NuxColors.Background)
            )
            RootScreen.Onboarding -> TvSafe(background = HeroGlow) {
                OnboardingScreen(vm = vm, cancellable = false, onDone = {}, onCancel = {})
            }
            RootScreen.Main -> NuxNavHost(vm, startOnPlayer = startTarget == StartTarget.Player)
        }
    }
}

@Composable
private fun NuxNavHost(vm: MainViewModel, startOnPlayer: Boolean) {
    val nav: NavHostController = rememberNavController()
    // Cuts, not dissolves. NavHost's stock transition is a 700ms cross-fade
    // in every direction, and for all 42 of those frames BOTH screens are
    // composed and drawn through their own full-screen alpha layer — Home's
    // shelves, posters and backdrop underneath a player that is building its
    // engine, or a detail page loading its own art. On TV silicon a
    // full-screen alpha layer is most of a frame budget on its own, and the
    // video SurfaceView ignores Compose alpha anyway, so the picture popped
    // in at full strength under a Home that was still fading. On the way
    // back the player kept decoding under Home for the whole fade and then
    // released its codecs on the main thread the moment Home looked done.
    // A broadcast receiver cuts to the channel; so does this.
    NavHost(
        navController = nav,
        // A launch that is resuming a channel starts ON the player, rather
        // than starting at Home and navigating: Home composes its shelves,
        // backdrop and artwork on the way past, which on this hardware is a
        // visible stumble in front of a picture that should simply be there.
        startDestination = if (startOnPlayer) "player" else "home",
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable("home") {
            HomeScreen(
                vm = vm,
                onOpenMovie = { nav.navigate("movie/${it.id}") },
                onOpenSeries = { nav.navigate("series/${it.id}") },
                onPlay = { nav.navigate("player") },
                onAddPlaylist = { nav.navigate("onboarding") },
                onEditPlaylist = { id -> nav.navigate("onboarding?edit=$id") },
            )
        }
        // One route for both: "onboarding" adds, "onboarding?edit=<id>" opens
        // that playlist's own form with its details filled in.
        composable(
            route = "onboarding?edit={edit}",
            arguments = listOf(
                navArgument("edit") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            ),
        ) { entry ->
            TvSafe(background = HeroGlow) {
            OnboardingScreen(
                vm = vm,
                cancellable = true,
                onDone = { nav.popBackStack() },
                onCancel = { nav.popBackStack() },
                editing = vm.sourceById(entry.arguments?.getString("edit")),
            )
            }
        }
        composable("movie/{id}") { entry ->
            TvSafe {
            MovieDetailScreen(
                vm = vm,
                movieId = entry.arguments?.getString("id").orEmpty(),
                onPlay = { nav.navigate("player") },
                onBack = { nav.popBackStack() },
            )
            }
        }
        composable("series/{id}") { entry ->
            TvSafe {
            SeriesDetailScreen(
                vm = vm,
                seriesId = entry.arguments?.getString("id").orEmpty(),
                onPlay = { nav.navigate("player") },
                onBack = { nav.popBackStack() },
            )
            }
        }
        composable("player") {
            PlayerScreen(
                vm = vm,
                onExit = {
                    // Leaving the player is the viewer saying they are done
                    // with this channel, so the next cold start opens Home.
                    // Not when there was nothing to play: the player exits
                    // itself on a null request, and that is the shell
                    // recovering rather than a decision to forget a channel.
                    if (vm.playback != null) vm.rememberLiveResume(null)
                    // A resumed launch has the player as its start
                    // destination, so there is nothing behind it to pop to.
                    // Asked BEFORE popping: popBackStack() returns false only
                    // once it has already emptied the stack, so the popUpTo
                    // in the fallback would have nothing left to match.
                    if (nav.previousBackStackEntry == null) {
                        nav.navigate("home") { popUpTo("player") { inclusive = true } }
                    } else {
                        nav.popBackStack()
                    }
                },
            )
        }
    }
}
