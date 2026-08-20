package com.agoro.tv.ui

import androidx.compose.animation.Crossfade
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
    val screen = when {
        sources == null -> RootScreen.Boot
        sources!!.isEmpty() -> RootScreen.Onboarding
        else -> RootScreen.Main
    }

    Crossfade(targetState = screen, animationSpec = tween(260), label = "root") { target ->
        when (target) {
            RootScreen.Boot -> Box(
                modifier = Modifier.fillMaxSize().background(NuxColors.Background)
            )
            RootScreen.Onboarding -> TvSafe(background = HeroGlow) {
                OnboardingScreen(vm = vm, cancellable = false, onDone = {}, onCancel = {})
            }
            RootScreen.Main -> NuxNavHost(vm)
        }
    }
}

@Composable
private fun NuxNavHost(vm: MainViewModel) {
    val nav: NavHostController = rememberNavController()
    NavHost(navController = nav, startDestination = "home") {
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
            PlayerScreen(vm = vm, onExit = { nav.popBackStack() })
        }
    }
}
