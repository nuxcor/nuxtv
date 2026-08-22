@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.agoro.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.agoro.tv.MainViewModel
import com.agoro.tv.data.Category
import com.agoro.tv.data.ContentBundle
import com.agoro.tv.ui.components.ScreenTitle
import com.agoro.tv.ui.components.WideItem
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxMotion
import com.agoro.tv.ui.theme.Space
import kotlinx.coroutines.delay

@Composable
internal fun ChannelManager(vm: MainViewModel, bundle: ContentBundle, onClose: () -> Unit) {
    val hidden by vm.hidden.collectAsState()
    // Without this, BACK falls through to Home's handlers and starts the
    // app-exit sequence while the manager is still open — the one place left
    // in the app where BACK didn't mean "go back".
    BackHandler(onBack = onClose)
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle("Manage channels", modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onClose) { Text("Done") }
        }
        Text(
            "OK hides or shows a channel everywhere. Type a channel number to jump.",
            style = MaterialTheme.typography.labelMedium,
            color = NuxColors.OnSurfaceDim,
        )
        Spacer(Modifier.height(14.dp))

        // This is the screen for finding one unwanted channel among the few
        // thousand the app is built to handle, and it had neither of the two
        // things Live TV uses to cross that many rows. It works on
        // bundle.channels rather than displayChannels on purpose: hidden
        // channels have to be listed here or there is no way to unhide one.
        var selectedCategory by rememberSaveable { mutableStateOf(CATEGORY_ALL) }
        val categories = remember(bundle) {
            listOf(Category(id = CATEGORY_ALL, name = "All channels")) + bundle.liveCategories
        }
        val activeCategory = resolveCategoryId(selectedCategory, categories)
        var focusedCategory by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(focusedCategory) {
            val id = focusedCategory ?: return@LaunchedEffect
            delay(NuxMotion.FocusDwellMs.toLong())
            selectedCategory = id
        }
        val channels = remember(bundle, activeCategory) {
            if (activeCategory == CATEGORY_ALL) bundle.channels
            else bundle.channels.filter { it.categoryId == activeCategory }
        }
        val jump = rememberChannelJump(channels)
        // The Settings list this replaces held focus; without an arrival
        // target the first press was spent on Compose's own guess (the
        // bottom-right row, for UP). Land on the category column, which is
        // where the work starts.
        val arrival = com.agoro.tv.ui.components.rememberInitialFocus(Unit)

        Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            LazyColumn(
                modifier = Modifier
                    .width(190.dp)
                    .fillMaxHeight()
                    .focusRestorer(),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
                contentPadding = PaddingValues(bottom = Space.l),
            ) {
                itemsIndexed(categories, key = { _, c -> c.id }) { index, category ->
                    CategoryItem(
                        name = category.name,
                        selected = category.id == activeCategory,
                        onClick = { selectedCategory = category.id },
                        onFocus = { focusedCategory = category.id },
                        onBlur = { if (focusedCategory == category.id) focusedCategory = null },
                        modifier = if (index == 0) {
                            Modifier.fillMaxWidth().focusRequester(arrival)
                        } else Modifier.fillMaxWidth(),
                    )
                }
            }
            LazyColumn(
                state = jump.listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .focusRestorer()
                    .channelJumpKeys(jump),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                itemsIndexed(channels, key = { _, c -> c.id }) { index, channel ->
                    val isHidden = channel.url in hidden
                    WideItem(
                        title = channel.displayName,
                        subtitle = if (isHidden) "Hidden — OK to show" else "Shown — OK to hide",
                        badge = channel.quality,
                        imageUrl = channel.logo,
                        modifier = if (index == jump.targetIndex) {
                            Modifier.focusRequester(jump.focusRequester)
                        } else {
                            Modifier
                        },
                        onClick = { vm.toggleHidden(channel) },
                    )
                }
            }
        }
        ChannelJumpBadge(jump.digits, Modifier.align(Alignment.TopEnd))
        }
    }
}
