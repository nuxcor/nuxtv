@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.agoro.tv.ui.screens

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
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.focusRequester
import com.agoro.tv.ui.components.requestFocusRetrying
import kotlinx.coroutines.launch
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
import com.agoro.tv.BuildConfig
import com.agoro.tv.MainViewModel
import com.agoro.tv.data.ContentBundle
import com.agoro.tv.data.ContentState
import com.agoro.tv.data.PlaylistSource
import com.agoro.tv.data.UpdateManager
import com.agoro.tv.ui.components.ConfirmDialog
import com.agoro.tv.ui.components.MetaChip
import com.agoro.tv.ui.components.PlaylistOptionsDialog
import com.agoro.tv.ui.components.ScreenTitle
import com.agoro.tv.ui.components.SettingsChoiceRow
import com.agoro.tv.ui.components.SettingsGroup
import com.agoro.tv.ui.components.TextInputDialog
import com.agoro.tv.ui.components.WideItem
import com.agoro.tv.ui.components.ScrollEdgeFade
import com.agoro.tv.ui.components.ToastBadge
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxShape
import com.agoro.tv.ui.theme.Space
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
    var pinDialogOpen by remember { mutableStateOf(false) }
    var pinGateOpen by remember { mutableStateOf(false) }
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

    // Above the manager's early return, so the list keeps its scroll across
    // a visit to it instead of coming back at the top with focus nowhere.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val manageFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var returnToManage by remember { mutableStateOf(false) }
    if (manageOpen && shownBundle != null) {
        ChannelManager(vm = vm, bundle = shownBundle, onClose = {
            manageOpen = false
            returnToManage = true
        })
        return
    }
    LaunchedEffect(returnToManage) {
        if (!returnToManage) return@LaunchedEffect
        returnToManage = false
        manageFocus.requestFocusRetrying()
    }

    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Space.m),
        // Room at both ends so a row can clear the fold instead of being
        // sliced through the middle of its text by the pane edge.
        contentPadding = PaddingValues(top = Space.s, bottom = Space.xxl),
    ) {
        item(key = "header") {
            ScreenTitle("Settings")
        }

        // A build made for one provider has an ACCOUNT, not "playlists" — the
        // source rows named a server host and the Add button invited a second
        // one, both plumbing from the app's IPTV-tool ancestry. The generic
        // build keeps them: there, managing sources is the whole point.
        val brandedBuild = com.agoro.tv.BuildConfig.PROVIDER_HOST.isNotBlank()
        if (!brandedBuild) items(sources.orEmpty(), key = { it.id }) { source ->
            val isActive = source.id == active?.id
            WideItem(
                title = source.name,
                selected = isActive,
                // Hold OK for options on any playlist; the active one has
                // nothing to switch to, so a plain OK opens them too.
                onLongClick = { sourceOptions = source.id },
                // The host is what identifies a playlist to its owner; the
                // scheme, port and path are plumbing, and putting plumbing on
                // screen is what makes an app look like a tool for its author.
                subtitle = when (source) {
                    is PlaylistSource.Xtream -> "Xtream • ${displayHost(source.serverUrl)}"
                    is PlaylistSource.M3u -> "M3U • ${displayHost(source.url)}"
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
            // Two of these buttons remove themselves when pressed ("Clear
            // recent", "Show N hidden"): the button focus is on leaves
            // composition and focus falls to nothing. Move it to the stable
            // neighbour first, then act.
            val refreshFocus = remember { androidx.compose.ui.focus.FocusRequester() }
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            fun thenRefocus(action: () -> Unit) {
                scope.launch {
                    refreshFocus.requestFocusRetrying()
                    action()
                }
            }
            // Wraps at large font scales instead of running the last button
            // off the pane while it stays focusable.
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!brandedBuild) {
                    Button(onClick = onAddPlaylist) { Text("Add playlist") }
                }
                // The label is the progress indicator: one stable button, so a
                // load in flight can't move focus out from under the press.
                OutlinedButton(
                    onClick = {
                        pendingLoadMessage = "Playlist refreshed"
                        vm.refresh()
                    },
                    enabled = !loadingNow,
                    modifier = Modifier.focusRequester(refreshFocus),
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
                    OutlinedButton(
                        onClick = { manageOpen = true },
                        modifier = Modifier.focusRequester(manageFocus),
                    ) { Text("Manage channels") }
                }
                // Only offered when there is something to clear: a button that
                // does nothing still costs a press to walk past.
                val recentChannels by vm.recentChannels.collectAsState()
                if (recentChannels.isNotEmpty()) {
                    OutlinedButton(onClick = { thenRefocus { vm.clearRecentChannels() } }) {
                        Text("Clear recent")
                    }
                }
                // The way back from "Not interested". All of them at once
                // rather than a screen listing them: dismissing a title off
                // Home is a small, frequent act, and undoing one specific
                // dismissal months later is not a thing anyone asks for —
                // where "put the suggestions back" is. Counted so the button
                // says what it will do, and absent when it would do nothing.
                val hiddenTitles by vm.hiddenTitles.collectAsState()
                if (hiddenTitles.isNotEmpty()) {
                    OutlinedButton(onClick = { thenRefocus { vm.showHiddenTitlesAgain() } }) {
                        Text("Show ${hiddenTitles.size} hidden on Home")
                    }
                }
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
                    // A set PIN guards its own switch: changing or removing it
                    // asks for it first, or the lock is decorative.
                    onClick = {
                        if (parentalPin.isNullOrBlank()) pinDialogOpen = true else pinGateOpen = true
                    },
                )
            }
        }

        item(key = "updates") {
            val update by vm.updateState.collectAsState()
            SettingsGroup(title = "App updates", divider = true) {
                Text(
                    text = when (val u = update) {
                        is UpdateManager.State.Available ->
                            "Version ${BuildConfig.VERSION_NAME} — ${u.version.removePrefix("v")} is available" +
                                (u.sizeBytes.takeIf { it > 0 }?.let { " (${it / 1048576} MB)" } ?: "")
                        // The button IS the prompt once the system dialog has
                        // been dismissed; "install when prompted" promised a
                        // prompt that was never coming back.
                        is UpdateManager.State.Ready ->
                            u.note ?: "Update downloaded — press Install"
                        is UpdateManager.State.UpToDate ->
                            "Version ${BuildConfig.VERSION_NAME} — up to date"
                        // Already a sentence: the check, the download and the
                        // installer each say which of them failed.
                        is UpdateManager.State.Error -> u.message
                        // Downloading and Checking: the button below already
                        // carries the live progress — saying it twice, 40px
                        // apart, read as a glitch.
                        else -> "Version ${BuildConfig.VERSION_NAME}"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when (val u = update) {
                        is UpdateManager.State.Available -> NuxColors.Secondary
                        is UpdateManager.State.Error -> NuxColors.Error
                        is UpdateManager.State.Ready ->
                            if (u.note != null) NuxColors.Error else NuxColors.Secondary
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
                            // No path: it is app storage a TV has no file
                            // browser for, and it didn't fit the pill anyway.
                            statusMessage = if (path != null) "Backup saved" else "Backup failed"
                        }
                    }) { Text("Export backup") }
                    OutlinedButton(onClick = { confirmImport = true }) { Text("Import backup") }
                }
            }
        }
    }
    ScrollEdgeFade(
        canScrollBackward = listState.canScrollBackward,
        canScrollForward = listState.canScrollForward,
    )
    // Pinned to the pane, not to a row that scrolls away.
    ToastBadge(
        message = statusMessage,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(bottom = Space.m, end = Space.s),
        // Failures in the failure colour: "Backup failed" in the same teal
        // as "Backup restored" read as success from across the room.
        textColor = if (statusMessage?.let { isFailure(it) } == true) NuxColors.Error
        else NuxColors.Secondary,
    )

    // Dialogs come AFTER the list inside this Box: siblings draw in
    // composition order, so composed before the page they sat underneath it —
    // the list's text rendered straight through the "open" dialog and the
    // scrim dimmed nothing. (They still must not live inside a LazyColumn
    // item: an item near the bottom only composes once scrolled to, so a
    // confirmation living there never appeared for actions triggered from
    // the top of the screen — Remove playlist silently did nothing.)
    if (pinGateOpen) {
        com.agoro.tv.ui.components.PinPrompt(
            onSubmit = { entered ->
                vm.tryUnlock(entered).also { ok ->
                    if (ok) {
                        pinGateOpen = false
                        pinDialogOpen = true
                    }
                }
            },
            onDismiss = { pinGateOpen = false },
        )
    }
    if (pinDialogOpen) {
        val hasPin = !parentalPin.isNullOrBlank()
        TextInputDialog(
            title = if (hasPin) "Change PIN" else "Set a PIN",
            message = if (hasPin) "Enter a new PIN, or remove it to turn parental control off."
            else "Locked categories stay hidden until this PIN is entered.",
            // Empty, not the current PIN: the field would otherwise hand the
            // digits to anyone who reached it.
            initialValue = "",
            label = "PIN",
            digitsOnly = true,
            clearLabel = if (hasPin) "Remove PIN" else null,
            onConfirm = { entered ->
                // Saving an empty field changes nothing — only the explicit
                // Remove button turns the lock off.
                if (entered.isNotBlank()) {
                    vm.setParentalPin(entered)
                    statusMessage = "Parental PIN saved"
                }
            },
            onClear = {
                vm.setParentalPin("")
                statusMessage = "Parental lock disabled"
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
                // The row focus was on is about to leave the list; seat it on
                // the action row rather than leaving the next press blind.
                returnToManage = true
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

/** Whether a status line reports something that did not work. */
private fun isFailure(message: String): Boolean =
    message.contains("failed", ignoreCase = true) ||
        message.startsWith("No backup") || message.startsWith("Couldn't")

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

/**
 * The human half of a source URL: its host, without scheme, port or path.
 * Falls back to the raw string if it will not parse — better an odd-looking
 * playlist than a nameless one.
 */
private fun displayHost(url: String): String = runCatching {
    java.net.URI(url.trim()).host?.removePrefix("www.")
}.getOrNull()?.takeIf { it.isNotBlank() } ?: url.trim()
