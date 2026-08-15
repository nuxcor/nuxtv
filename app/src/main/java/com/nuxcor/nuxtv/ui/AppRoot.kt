package com.nuxcor.nuxtv.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nuxcor.nuxtv.MainViewModel
import com.nuxcor.nuxtv.ui.screens.BootScreen
import com.nuxcor.nuxtv.ui.screens.HomeScreen
import com.nuxcor.nuxtv.ui.screens.MovieDetailScreen
import com.nuxcor.nuxtv.ui.screens.OnboardingScreen
import com.nuxcor.nuxtv.ui.screens.PlayerScreen
import com.nuxcor.nuxtv.ui.screens.SeriesDetailScreen
import com.nuxcor.nuxtv.ui.theme.Space
import kotlinx.coroutines.delay

private enum class RootScreen { Boot, Onboarding, Main }

/**
 * Real TVs crop the frame edges (overscan). Browsing screens keep this safe
 * inset; the player stays full-bleed. Uses the same [Space] gutters as Home so
 * content doesn't shift when you open a detail page.
 */
@Composable
private fun TvSafe(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Space.gutter, vertical = Space.gutterVertical)
    ) { content() }
}

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val sources by vm.sources.collectAsState()

    // Hold the boot animation long enough to play out even on instant starts.
    var bootElapsed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(900)
        bootElapsed = true
    }

    val screen = when {
        !bootElapsed || sources == null -> RootScreen.Boot
        sources!!.isEmpty() -> RootScreen.Onboarding
        else -> RootScreen.Main
    }

    Crossfade(targetState = screen, animationSpec = tween(260), label = "root") { target ->
        when (target) {
            RootScreen.Boot -> BootScreen()
            RootScreen.Onboarding -> TvSafe {
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
            )
        }
        composable("onboarding") {
            TvSafe {
            OnboardingScreen(
                vm = vm,
                cancellable = true,
                onDone = { nav.popBackStack() },
                onCancel = { nav.popBackStack() },
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
