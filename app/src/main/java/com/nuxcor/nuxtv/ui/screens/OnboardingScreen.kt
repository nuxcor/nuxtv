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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
    var step by remember { mutableStateOf(Step.Choose) }
    val addState = vm.addState

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
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(com.nuxcor.nuxtv.R.drawable.ic_splash),
                contentDescription = null,
                modifier = Modifier.size(104.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "DZIDZI",
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black),
                color = NuxColors.Primary,
            )
            Text(
                text = "Your playlists, organized like real TV",
                style = MaterialTheme.typography.bodyMedium,
                color = NuxColors.OnSurfaceDim,
            )
            Spacer(Modifier.height(32.dp))

            when (step) {
                Step.Choose -> ChooseStep(
                    cancellable = cancellable,
                    onXtream = { vm.resetAddState(); step = Step.Xtream },
                    onM3u = { vm.resetAddState(); step = Step.M3u },
                    onCancel = onCancel,
                )

                Step.Xtream -> XtreamForm(
                    addState = addState,
                    onSubmit = { name, server, user, pass ->
                        vm.addXtream(name, server, user, pass, onSuccess = onDone)
                    },
                    onBack = { vm.resetAddState(); step = Step.Choose },
                )

                Step.M3u -> M3uForm(
                    addState = addState,
                    onSubmit = { name, url, epgUrl -> vm.addM3u(name, url, epgUrl, onSuccess = onDone) },
                    onBack = { vm.resetAddState(); step = Step.Choose },
                )
            }
        }
    }
}

@Composable
private fun ChooseStep(
    cancellable: Boolean,
    onXtream: () -> Unit,
    onM3u: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        SourceOptionCard(
            title = "Xtream Codes",
            subtitle = "Sign in with server URL, username and password",
            icon = { Icon(Icons.Default.Dns, contentDescription = null, tint = NuxColors.Secondary) },
            onClick = onXtream,
        )
        SourceOptionCard(
            title = "M3U Playlist",
            subtitle = "Paste a playlist link — channels, movies and series are detected automatically",
            icon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null, tint = NuxColors.Primary) },
            onClick = onM3u,
        )
        if (cancellable) {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Cancel")
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
            focusedContainerColor = NuxColors.SurfaceVariant,
            contentColor = NuxColors.OnSurface,
            focusedContentColor = NuxColors.OnSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        border = ClickableSurfaceDefaults.border(focusedBorder = focusBorder()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) { icon() }
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuxColors.OnSurfaceDim,
                )
            }
        }
    }
}

@Composable
private fun XtreamForm(
    addState: AddState,
    onSubmit: (name: String, server: String, user: String, pass: String) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    val connectFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    FormContainer(addState = addState, onBack = onBack, submitEnabled = server.isNotBlank() && user.isNotBlank(),
        onSubmit = { onSubmit(name, server, user, pass) }, connectFocus = connectFocus) {
        NuxTextField(value = name, onValueChange = { name = it }, label = "Playlist name (optional)")
        NuxTextField(value = server, onValueChange = { server = it }, label = "Server URL  •  http://host:port")
        NuxTextField(value = user, onValueChange = { user = it }, label = "Username")
        NuxTextField(
            value = pass,
            onValueChange = { pass = it },
            label = "Password",
            password = true,
            isLast = true,
            onAdvance = { runCatching { connectFocus.requestFocus() } },
        )
    }
}

@Composable
private fun M3uForm(
    addState: AddState,
    onSubmit: (name: String, url: String, epgUrl: String) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var epgUrl by remember { mutableStateOf("") }

    val connectFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    FormContainer(addState = addState, onBack = onBack, submitEnabled = url.isNotBlank(),
        onSubmit = { onSubmit(name, url, epgUrl) }, connectFocus = connectFocus) {
        NuxTextField(value = name, onValueChange = { name = it }, label = "Playlist name (optional)")
        NuxTextField(value = url, onValueChange = { url = it }, label = "M3U URL  •  http://…/playlist.m3u")
        NuxTextField(
            value = epgUrl,
            onValueChange = { epgUrl = it },
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        fields()
        if (addState is AddState.Error) {
            Text(
                text = addState.message,
                style = MaterialTheme.typography.bodySmall,
                color = NuxColors.Error,
            )
        }
        Spacer(Modifier.height(4.dp))
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
            // swallows those keys by default.
            .onPreviewKeyEvent { event ->
                if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                    return@onPreviewKeyEvent false
                }
                when (event.key.nativeKeyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        advance()
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                        focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Up)
                        true
                    }
                    else -> false
                }
            },
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
