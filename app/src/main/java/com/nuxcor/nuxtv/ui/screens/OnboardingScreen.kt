@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuxcor.nuxtv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuxcor.nuxtv.AddState
import com.nuxcor.nuxtv.MainViewModel
import com.nuxcor.nuxtv.ui.components.dpadFieldNavigation
import com.nuxcor.nuxtv.ui.components.focusBorder
import com.nuxcor.nuxtv.ui.theme.NuxColors

private enum class Step { Choose, Xtream, M3u }

@Composable
fun OnboardingScreen(
    vm: MainViewModel,
    cancellable: Boolean,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(Step.Choose) }
    // Hoisted + saveable: a stray BACK (or process death) must never wipe
    // credentials the user spent hundreds of remote presses typing.
    var name by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var m3uUrl by rememberSaveable { mutableStateOf("") }
    var epgUrl by rememberSaveable { mutableStateOf("") }
    val addState = vm.addState

    // Remote BACK mirrors the on-screen Back button: form → chooser → leave.
    androidx.activity.compose.BackHandler(enabled = step != Step.Choose) {
        vm.resetAddState()
        step = Step.Choose
    }
    if (cancellable) {
        androidx.activity.compose.BackHandler(enabled = step == Step.Choose) { onCancel() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF2B2413), NuxColors.Background),
                    radius = 1600f,
                )
            )
            .padding(horizontal = 64.dp, vertical = 40.dp)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .width(560.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The chooser gets the full lockup; forms keep just a small mark
            // so every field and the Connect button fit on a TV screen.
            if (step == Step.Choose) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(com.nuxcor.nuxtv.R.drawable.ic_splash),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                    )
                    Column {
                        Text(
                            text = "DZIDZI",
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                            color = NuxColors.Primary,
                        )
                        Text(
                            text = "Your playlists, organized like real TV",
                            style = MaterialTheme.typography.labelMedium,
                            color = NuxColors.OnSurfaceDim,
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
            } else {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(com.nuxcor.nuxtv.R.drawable.ic_splash),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(10.dp))
            }

            when (step) {
                Step.Choose -> ChooseStep(
                    vm = vm,
                    cancellable = cancellable,
                    onXtream = { vm.resetAddState(); step = Step.Xtream },
                    onM3u = { vm.resetAddState(); step = Step.M3u },
                    onCancel = onCancel,
                )

                Step.Xtream -> XtreamForm(
                    addState = addState,
                    name = name, onName = { name = it },
                    server = server, onServer = { server = it },
                    user = user, onUser = { user = it },
                    pass = pass, onPass = { pass = it },
                    onSubmit = { vm.addXtream(name, server, user, pass, onSuccess = onDone) },
                    onBack = { vm.resetAddState(); step = Step.Choose },
                )

                Step.M3u -> M3uForm(
                    addState = addState,
                    name = name, onName = { name = it },
                    url = m3uUrl, onUrl = { m3uUrl = it },
                    epgUrl = epgUrl, onEpgUrl = { epgUrl = it },
                    onSubmit = { vm.addM3u(name, m3uUrl, epgUrl, onSuccess = onDone) },
                    onBack = { vm.resetAddState(); step = Step.Choose },
                )
            }
        }
    }
}

@Composable
private fun ChooseStep(
    vm: MainViewModel,
    cancellable: Boolean,
    onXtream: () -> Unit,
    onM3u: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        SourceOptionCard(
            title = "Xtream Codes",
            subtitle = "Sign in with server URL, username and password",
            icon = { Icon(Icons.Default.Dns, contentDescription = "Xtream Codes", tint = NuxColors.Secondary) },
            onClick = onXtream,
        )
        SourceOptionCard(
            title = "M3U Playlist",
            subtitle = "Paste a playlist link — channels, movies and series are detected automatically",
            icon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = "M3U playlist", tint = NuxColors.Primary) },
            onClick = onM3u,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            if (cancellable) {
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
            // Updates don't need a playlist or an account — reachable here so a
            // first-run user never has to sideload again.
            val update by vm.updateState.collectAsState()
            OutlinedButton(onClick = {
                when (update) {
                    is com.nuxcor.nuxtv.data.UpdateManager.State.Available,
                    is com.nuxcor.nuxtv.data.UpdateManager.State.Ready ->
                        vm.downloadAndInstallUpdate()
                    is com.nuxcor.nuxtv.data.UpdateManager.State.Downloading,
                    is com.nuxcor.nuxtv.data.UpdateManager.State.Checking -> Unit
                    else -> vm.checkForUpdates()
                }
            }) {
                Text(
                    when (val u = update) {
                        is com.nuxcor.nuxtv.data.UpdateManager.State.Available -> "Update to ${u.version}"
                        is com.nuxcor.nuxtv.data.UpdateManager.State.Ready -> "Install update"
                        is com.nuxcor.nuxtv.data.UpdateManager.State.Downloading -> "Downloading… ${u.progressPercent}%"
                        is com.nuxcor.nuxtv.data.UpdateManager.State.Checking -> "Checking…"
                        is com.nuxcor.nuxtv.data.UpdateManager.State.UpToDate -> "Up to date"
                        else -> "Check for updates"
                    }
                )
            }
        }
    }
}

@Composable
private fun SourceOptionCard(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuxColors.Surface,
            focusedContainerColor = NuxColors.SurfaceRaised,
            contentColor = NuxColors.OnSurface,
            focusedContentColor = NuxColors.OnSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        border = ClickableSurfaceDefaults.border(
            border = androidx.tv.material3.Border(
                androidx.compose.foundation.BorderStroke(1.dp, NuxColors.Stroke),
                shape = RoundedCornerShape(14.dp),
            ),
            focusedBorder = focusBorder(),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) { icon() }
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = NuxColors.OnSurfaceDim,
                )
            }
        }
    }
}

@Composable
private fun XtreamForm(
    addState: AddState,
    name: String, onName: (String) -> Unit,
    server: String, onServer: (String) -> Unit,
    user: String, onUser: (String) -> Unit,
    pass: String, onPass: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    val connectFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var revealPassword by rememberSaveable { mutableStateOf(false) }
    FormContainer(addState = addState, onBack = onBack, submitEnabled = server.isNotBlank() && user.isNotBlank(),
        onSubmit = onSubmit, connectFocus = connectFocus) {
        // Credentials first; the optional name last.
        NuxTextField(value = server, onValueChange = onServer, label = "Server URL  •  http://host:port")
        NuxTextField(value = user, onValueChange = onUser, label = "Username")
        NuxTextField(
            value = pass,
            onValueChange = onPass,
            label = "Password",
            password = !revealPassword,
            onAdvance = { runCatching { connectFocus.requestFocus() } },
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { revealPassword = !revealPassword }) {
                Text(if (revealPassword) "Hide password" else "Show password")
            }
            Text(
                "Typing is easier with the Google TV app's remote keyboard",
                style = MaterialTheme.typography.labelSmall,
                color = NuxColors.OnSurfaceDim,
            )
        }
        NuxTextField(
            value = name,
            onValueChange = onName,
            label = "Playlist name (optional)",
            isLast = true,
            onAdvance = { runCatching { connectFocus.requestFocus() } },
        )
    }
}

@Composable
private fun M3uForm(
    addState: AddState,
    name: String, onName: (String) -> Unit,
    url: String, onUrl: (String) -> Unit,
    epgUrl: String, onEpgUrl: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    val connectFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    FormContainer(addState = addState, onBack = onBack, submitEnabled = url.isNotBlank(),
        onSubmit = onSubmit, connectFocus = connectFocus) {
        NuxTextField(value = url, onValueChange = onUrl, label = "M3U URL  •  http://…/playlist.m3u")
        NuxTextField(value = name, onValueChange = onName, label = "Playlist name (optional)")
        NuxTextField(
            value = epgUrl,
            onValueChange = onEpgUrl,
            label = "EPG URL (optional, XMLTV)  •  auto-detected from url-tvg",
            isLast = true,
            onAdvance = { runCatching { connectFocus.requestFocus() } },
        )
    }
}

@Composable
private fun FormContainer(
    addState: AddState,
    submitEnabled: Boolean,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    connectFocus: androidx.compose.ui.focus.FocusRequester =
        androidx.compose.ui.focus.FocusRequester(),
    fields: @Composable () -> Unit,
) {
    val loading = addState is AddState.Loading
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        fields()
        if (addState is AddState.Error) {
            Text(
                text = addState.message,
                style = MaterialTheme.typography.bodySmall,
                color = NuxColors.Error,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            OutlinedButton(onClick = onBack, enabled = !loading) { Text("Back") }
            Button(
                onClick = onSubmit,
                enabled = submitEnabled && !loading,
                modifier = Modifier.focusRequester(connectFocus),
            ) {
                Text(if (loading) "Connecting…" else "Connect")
            }
            if (loading) {
                CircularProgressIndicator(
                    color = NuxColors.Primary,
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp,
                )
            }
        }
    }
}

@Composable
private fun NuxTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    password: Boolean = false,
    isLast: Boolean = false,
    onAdvance: (() -> Unit)? = null,
) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val advance: () -> Unit = {
        onAdvance?.invoke()
            ?: focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { androidx.compose.material3.Text(label) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = if (isLast) androidx.compose.ui.text.input.ImeAction.Done
            else androidx.compose.ui.text.input.ImeAction.Next
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onNext = { advance() },
            // Done jumps straight to the Connect button instead of dropping focus.
            onDone = { advance() },
        ),
        modifier = Modifier
            .fillMaxWidth()
            // TV remotes navigate fields with the D-pad; the m3 TextField
            // swallows those keys by default. Down advances the form.
            .dpadFieldNavigation(onDown = advance),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = NuxColors.OnSurface,
            unfocusedTextColor = NuxColors.OnSurface,
            focusedContainerColor = NuxColors.Surface,
            unfocusedContainerColor = NuxColors.Surface.copy(alpha = 0.6f),
            focusedBorderColor = NuxColors.Primary,
            unfocusedBorderColor = NuxColors.SurfaceVariant,
            focusedLabelColor = NuxColors.Primary,
            unfocusedLabelColor = NuxColors.OnSurfaceDim,
            cursorColor = NuxColors.Primary,
        ),
    )
}
