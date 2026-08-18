@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuxcor.nuxtv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.nuxcor.nuxtv.BuildConfig
import com.nuxcor.nuxtv.MainViewModel
import com.nuxcor.nuxtv.data.ContentBundle
import com.nuxcor.nuxtv.data.ContentState
import com.nuxcor.nuxtv.data.EngineChoice
import com.nuxcor.nuxtv.data.PlaylistSource
import com.nuxcor.nuxtv.data.UpdateManager
import com.nuxcor.nuxtv.ui.components.ConfirmDialog
import com.nuxcor.nuxtv.ui.components.MetaChip
import com.nuxcor.nuxtv.ui.components.PlaylistOptionsDialog
import com.nuxcor.nuxtv.ui.components.ScreenTitle
import com.nuxcor.nuxtv.ui.components.SettingsChoiceRow
import com.nuxcor.nuxtv.ui.components.SettingsGroup
import com.nuxcor.nuxtv.ui.components.TextInputDialog
import com.nuxcor.nuxtv.ui.components.WideItem
import com.nuxcor.nuxtv.ui.components.ToastBadge
import com.nuxcor.nuxtv.ui.theme.NuxColors
import com.nuxcor.nuxtv.ui.theme.NuxShape
import com.nuxcor.nuxtv.ui.theme.Space
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun SettingsTab(
    vm: MainViewModel,
    bundle: ContentBundle?,
    onAddPlaylist: () -> Unit,
    onEditPlaylist: (String) -> Unit,
) {
    val sources by vm.sources.collectAsState()
    val active by vm.activeSource.collectAsState()
    val engine by vm.engine.collectAsState()
    val epgOverride by vm.epgOverrideUrl.collectAsState()
    val contentState by vm.content.collectAsState()

    // The library reads null for the duration of a load. Holding the last one
    // keeps the counts, the "Manage channels" button and an open channel manager
    // from blinking out and back every time the playlist reloads underneath.
    var lastBundle by remember { mutableStateOf(bundle) }
    LaunchedEffect(bundle) { if (bundle != null) lastBundle = bundle }
    val shownBundle = lastBundle

    val parentalPin by vm.parentalPin.collectAsState()
    var manageOpen by remember { mutableStateOf(false) }
    // Text entry happens in dialogs, not inline: a focused TextField on TV
    // opens the keyboard on its own, so a field in the scroll path grabs the
    // remote every time you D-pad past it.
    var epgDialogOpen by remember { mutableStateOf(false) }
    var pinDialogOpen by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var sourceOptions by remember { mutableStateOf<String?>(null) }
    var confirmImport by remember { mutableStateOf(false) }

    // Status used to sit in the header item of the scrolling list, six to
    // fifteen rows above the buttons that set it — so "Backup failed" and
    // "Parental PIN saved" were reported off-screen and every one of those
    // presses looked like it had done nothing. It is pinned to the pane now,
    // and it clears itself; before, it never did.
    LaunchedEffect(statusMessage) {
        if (statusMessage != null) {
            delay(4_000)
            statusMessage = null
        }
    }

    // Refresh has no completion signal of its own: the repository deliberately
    // keeps a working library rather than replacing it with a loading or error
    // state once one is loaded. Watching the transition out of Loading is the
    // only honest way to tell the viewer their press finished.
    val loadingNow = contentState is ContentState.Loading
    var sawLoading by remember { mutableStateOf(false) }
    var pendingLoadMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(loadingNow) {
        if (loadingNow) {
            sawLoading = true
        } else if (sawLoading) {
            sawLoading = false
            // The queued message is a success message, and leaving Loading is
            // not success: a refresh that lands in Error kept announcing
            // "Playlist refreshed" over the stale library it did not refresh —
            // on the very screen the button lives on, which shows no error of
            // its own.
            val landed = contentState
            statusMessage = if (landed is ContentState.Error) {
                "Refresh failed — ${landed.message}"
            } else {
                pendingLoadMessage
            }
            pendingLoadMessage = null
        }
    }

    if (manageOpen && shownBundle != null) {
        ChannelManager(vm = vm, bundle = shownBundle, onClose = { manageOpen = false })
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Space.m),
        contentPadding = PaddingValues(bottom = Space.xxl),
    ) {
        item(key = "header") {
            ScreenTitle("Settings")
        }

        items(sources.orEmpty(), key = { it.id }) { source ->
            val isActive = source.id == active?.id
            WideItem(
                title = source.name,
                selected = isActive,
                // Hold OK for options on any playlist; the active one has
                // nothing to switch to, so a plain OK opens them too.
                onLongClick = { sourceOptions = source.id },
                subtitle = when (source) {
                    is PlaylistSource.Xtream -> "Xtream • ${source.serverUrl}"
                    is PlaylistSource.M3u -> "M3U • ${source.url}"
                },
                leading = {
                    Icon(
                        if (source is PlaylistSource.Xtream) Icons.Default.LiveTv else Icons.Default.Movie,
                        contentDescription = null,
                        tint = if (isActive) NuxColors.Primary else NuxColors.OnSurfaceDim,
                    )
                },
                onClick = {
                    if (isActive) {
                        sourceOptions = source.id
                    } else {
                        pendingLoadMessage = "Switched to ${source.name}"
                        vm.selectSource(source.id)
                    }
                },
            )
        }

        item(key = "account") {
            val account by vm.accountInfo.collectAsState()
            account?.let { info ->
                val dayMs = 24 * 3600_000L
                val daysLeft = info.expiresAtMs?.let { (it - System.currentTimeMillis()) / dayMs }
                val expiringSoon = daysLeft != null && daysLeft in 0..7
                val inactive = info.status != null && !info.status.equals("Active", ignoreCase = true)
                val expired = daysLeft != null && daysLeft < 0
                val fmt = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }
                SettingsGroup(title = "Account") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.s),
                    ) {
                        // The provider's own word for the account, as a chip so
                        // it reads as state; gold while the account is healthy.
                        info.status?.let { MetaChip(it, accent = !inactive && !expired) }
                        Text(
                            text = buildList {
                                info.expiresAtMs?.let {
                                    add(
                                        when {
                                            expired -> "Expired ${fmt.format(Date(it))}"
                                            else -> "Expires ${fmt.format(Date(it))}"
                                        }
                                    )
                                }
                                if (info.maxConnections != null) {
                                    add("${info.activeConnections ?: 0} of ${info.maxConnections} connections in use")
                                }
                            }.joinToString("   •   "),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (expired || inactive || expiringSoon) NuxColors.Error
                            else NuxColors.OnSurfaceDim,
                        )
                    }
                    if (expiringSoon || expired || inactive) {
                        Spacer(Modifier.height(Space.xs))
                        Text(
                            text = when {
                                expired -> "Your subscription has ended — streams will fail until it is renewed."
                                inactive -> "Your provider reports this account as inactive."
                                else -> "Your subscription renews soon."
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = NuxColors.OnSurfaceDim,
                        )
                    }
                }
            }
        }

        item(key = "playlist-buttons") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onAddPlaylist) { Text("Add playlist") }
                // The label is the progress indicator: one stable button, so a
                // load in flight can't move focus out from under the press.
                OutlinedButton(
                    onClick = {
                        pendingLoadMessage = "Playlist refreshed"
                        vm.refresh()
                    },
                    enabled = !loadingNow,
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh playlist", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (loadingNow) "Refreshing…" else "Refresh")
                }
                // No "Remove current" here. Holding OK on any playlist row
                // already offers Edit and Remove, and works for every playlist
                // rather than only the active one — so this button was the
                // narrower of two routes to the same thing, sitting in the
                // primary row where it was the easiest control to hit by
                // accident.
                if (shownBundle != null) {
                    OutlinedButton(onClick = { manageOpen = true }) { Text("Manage channels") }
                }
                // Only offered when there is something to clear: a button that
                // does nothing still costs a press to walk past.
                val recentChannels by vm.recentChannels.collectAsState()
                if (recentChannels.isNotEmpty()) {
                    OutlinedButton(onClick = { vm.clearRecentChannels() }) { Text("Clear recent") }
                }
            }
        }

        item(key = "duplicates") {
            val mergeDupes by vm.mergeDuplicates.collectAsState()
            SettingsChoiceRow(
                title = "Duplicate channels",
                description = "Merge SD/HD/FHD variants of the same channel and keep the best quality.",
                options = listOf("Show all", "Best quality only"),
                selectedIndex = if (mergeDupes) 1 else 0,
                onSelect = { vm.setMergeDuplicates(it == 1) },
            )
        }

        // No "Guide preview" row: the corner preview decides for itself now —
        // on when the account reports a spare connection, off otherwise. The
        // toggle's whole description was instructions for making that same
        // decision by hand.

        item(key = "order") {
            val order by vm.channelOrder.collectAsState()
            SettingsChoiceRow(
                title = "Channel order",
                description = "How Live TV lists channels within a category.",
                options = listOf("Provider order", "A–Z", "Best quality first"),
                selectedIndex = order,
                onSelect = { vm.setChannelOrder(it) },
            )
        }

        item(key = "quality") {
            val quality by vm.videoQuality.collectAsState()
            SettingsChoiceRow(
                title = "Picture quality",
                description = "Highest is sharpest but can buffer on a weak line; " +
                    "Auto starts lower and climbs. Only affects streams that offer " +
                    "more than one quality.",
                options = listOf("Auto", "Highest"),
                selectedIndex = quality,
                onSelect = { vm.setVideoQuality(it) },
            )
        }

        item(key = "engine") {
            val engines = remember { EngineChoice.entries.toList() }
            SettingsChoiceRow(
                title = "Default player engine",
                options = engines.map { if (it == EngineChoice.EXO) "ExoPlayer" else "VLC" },
                selectedIndex = engines.indexOf(engine).coerceAtLeast(0),
                onSelect = { vm.setEngine(engines[it]) },
            )
        }

        item(key = "epg") {
            val epgOptions = remember { listOf("Auto") + EPGSHARE_PACKS }
            SettingsChoiceRow(
                title = "EPG source",
                description = "Auto uses your playlist's guide; pick an epgshare01 pack or paste any XMLTV URL. Guides refresh every 6 hours.",
                divider = true,
                options = epgOptions,
                selectedIndex = when {
                    epgOverride.isNullOrBlank() -> 0
                    else -> EPGSHARE_PACKS.indexOfFirst { epgshareUrl(it) == epgOverride }
                        .let { if (it >= 0) it + 1 else -1 }
                },
                onSelect = { index ->
                    if (index == 0) {
                        vm.setEpgOverrideUrl(null)
                        statusMessage = "EPG source: playlist default"
                    } else {
                        val cc = EPGSHARE_PACKS[index - 1]
                        vm.setEpgOverrideUrl(epgshareUrl(cc))
                        statusMessage = "EPG source: epgshare01 $cc pack"
                    }
                },
            ) {
                Spacer(Modifier.height(Space.s))
                val custom = epgOverride
                    ?.takeIf { url -> url.isNotBlank() && EPGSHARE_PACKS.none { epgshareUrl(it) == url } }
                WideItem(
                    title = "Custom XMLTV URL",
                    subtitle = custom ?: "Not set — the playlist's own guide is used",
                    leading = {
                        Icon(
                            Icons.Default.CalendarViewWeek,
                            contentDescription = null,
                            tint = if (custom != null) NuxColors.Primary else NuxColors.OnSurfaceDim,
                        )
                    },
                    onClick = { epgDialogOpen = true },
                )
            }
        }

        item(key = "parental") {
            // Says what it actually does. "Restricted categories" implied a
            // set the viewer had chosen; it is detected from the category
            // names the provider supplies, and the names it matches are
            // listed below so the setting can be judged before it is set —
            // not discovered later by finding something unlocked.
            SettingsGroup(
                title = "Parental control",
                description = "Optional. Categories whose names look adult are hidden everywhere " +
                    "until you enter the PIN.",
                divider = true,
            ) {
                val restricted = remember(shownBundle) { vm.restrictedCategoryNames(shownBundle) }
                if (shownBundle != null) {
                    Text(
                        text = if (restricted.isEmpty()) {
                            "Nothing in this playlist matches — a PIN would hide nothing."
                        } else {
                            "In this playlist: ${restricted.take(6).joinToString(", ")}" +
                                if (restricted.size > 6) " and ${restricted.size - 6} more" else ""
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (restricted.isEmpty()) NuxColors.OnSurfaceDim else NuxColors.Primary,
                    )
                    Spacer(Modifier.height(Space.s))
                }
                WideItem(
                    title = if (parentalPin.isNullOrBlank()) "Set a PIN" else "Change or remove PIN",
                    subtitle = if (parentalPin.isNullOrBlank()) "Off — nothing is hidden"
                    else "On — ${restricted.size} categories are locked",
                    leading = {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = if (parentalPin.isNullOrBlank()) NuxColors.OnSurfaceDim
                            else NuxColors.Primary,
                        )
                    },
                    onClick = { pinDialogOpen = true },
                )
            }
        }

        item(key = "updates") {
            val update by vm.updateState.collectAsState()
            SettingsGroup(title = "App updates", divider = true) {
                Text(
                    text = when (val u = update) {
                        is UpdateManager.State.Available ->
                            "Version ${BuildConfig.VERSION_NAME} — ${u.version} is available" +
                                (u.sizeBytes.takeIf { it > 0 }?.let { " (${it / 1048576} MB)" } ?: "")
                        is UpdateManager.State.Ready ->
                            "Update downloaded — install when prompted"
                        is UpdateManager.State.UpToDate ->
                            "Version ${BuildConfig.VERSION_NAME} — up to date"
                        is UpdateManager.State.Error ->
                            "Update check failed: ${u.message}"
                        // Downloading and Checking: the button below already
                        // carries the live progress — saying it twice, 40px
                        // apart, read as a glitch.
                        else -> "Version ${BuildConfig.VERSION_NAME}"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when (update) {
                        is UpdateManager.State.Available -> NuxColors.Secondary
                        is UpdateManager.State.Error -> NuxColors.Error
                        else -> NuxColors.OnSurfaceDim
                    },
                )
                Spacer(Modifier.height(Space.s))
                // One stable button — swapping composables per state would drop
                // D-pad focus mid-download.
                Button(onClick = {
                    when (update) {
                        is UpdateManager.State.Available,
                        is UpdateManager.State.Ready ->
                            vm.downloadAndInstallUpdate()
                        is UpdateManager.State.Downloading,
                        is UpdateManager.State.Checking -> Unit
                        else -> vm.checkForUpdates()
                    }
                }) {
                    Text(
                        when (val u = update) {
                            is UpdateManager.State.Available -> "Update now"
                            is UpdateManager.State.Ready -> "Install"
                            is UpdateManager.State.Downloading ->
                                "Downloading… ${u.progressPercent}%"
                            is UpdateManager.State.Checking -> "Checking…"
                            else -> "Check for updates"
                        }
                    )
                }
            }
        }

        item(key = "backup") {
            SettingsGroup(title = "Backup & restore", divider = true) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = {
                        vm.exportBackup { path ->
                            statusMessage = path?.let { "Backup saved to $it" } ?: "Backup failed"
                        }
                    }) { Text("Export backup") }
                    OutlinedButton(onClick = { confirmImport = true }) { Text("Import backup") }
                }
            }
        }
    }
    // Pinned to the pane, not to a row that scrolls away.
    ToastBadge(
        message = statusMessage,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(bottom = Space.m, end = Space.s),
    )

    // Dialogs come AFTER the list inside this Box: siblings draw in
    // composition order, so composed before the page they sat underneath it —
    // the list's text rendered straight through the "open" dialog and the
    // scrim dimmed nothing. (They still must not live inside a LazyColumn
    // item: an item near the bottom only composes once scrolled to, so a
    // confirmation living there never appeared for actions triggered from
    // the top of the screen — Remove playlist silently did nothing.)
    if (epgDialogOpen) {
        TextInputDialog(
            title = "Custom XMLTV URL",
            message = "Optional. Leave this unset and the guide from your playlist is used.",
            initialValue = epgOverride.orEmpty(),
            label = "XMLTV URL",
            onConfirm = { entered ->
                vm.setEpgOverrideUrl(entered)
                statusMessage = if (entered.isBlank()) "EPG source: playlist default"
                else "EPG source updated"
            },
            onDismiss = { epgDialogOpen = false },
        )
    }
    if (pinDialogOpen) {
        TextInputDialog(
            title = "Parental PIN",
            message = "Optional. Clearing the PIN turns parental control off.",
            initialValue = parentalPin.orEmpty(),
            label = "PIN",
            digitsOnly = true,
            onConfirm = { entered ->
                vm.setParentalPin(entered)
                statusMessage = if (entered.isBlank()) "Parental lock disabled"
                else "Parental PIN saved"
            },
            onDismiss = { pinDialogOpen = false },
        )
    }
    // A stale id (the playlist vanished under us) simply shows nothing.
    sources.orEmpty().firstOrNull { it.id == sourceOptions }?.let { source ->
        PlaylistOptionsDialog(
            name = source.name,
            onEdit = {
                sourceOptions = null
                onEditPlaylist(source.id)
            },
            // Confirmation is a step inside the options dialog itself; a
            // second dialog restarted the scrim and let the page shift
            // visibly behind the prompt for a frame.
            onRemove = {
                sourceOptions = null
                vm.removeSource(source.id)
            },
            onDismiss = { sourceOptions = null },
        )
    }
    SettingsConfirmations(
        vm = vm,
        importPending = confirmImport,
        onImportHandled = { confirmImport = false },
        onStatus = { statusMessage = it },
    )
    }
}

@Composable
private fun SettingsConfirmations(
    vm: MainViewModel,
    importPending: Boolean,
    onImportHandled: () -> Unit,
    onStatus: (String) -> Unit,
) {
    if (importPending) {
        ConfirmDialog(
            title = "Restore from backup?",
            message = "This replaces your current playlists and settings.",
            confirmLabel = "Restore",
            onConfirm = {
                vm.importBackup { ok -> onStatus(if (ok) "Backup restored" else "No backup found") }
            },
            onDismiss = onImportHandled,
        )
    }
}
