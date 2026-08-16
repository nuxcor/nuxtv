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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.nuxcor.nuxtv.data.PlaylistSource
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
    /** Set to edit an existing playlist instead of adding one. */
    editing: PlaylistSource? = null,
) {
    // Editing skips the chooser: you can't turn an Xtream login into an M3U
    // link, so the only sensible screen is that playlist's own form.
    var step by rememberSaveable {
        mutableStateOf(
            when (editing) {
                is PlaylistSource.Xtream -> Step.Xtream
                is PlaylistSource.M3u -> Step.M3u
                null -> Step.Choose
            }
        )
    }
    // Hoisted + saveable: a stray BACK (or process death) must never wipe
    // credentials the user spent hundreds of remote presses typing.
    var name by rememberSaveable { mutableStateOf(editing?.name.orEmpty()) }
    var server by rememberSaveable {
        mutableStateOf((editing as? PlaylistSource.Xtream)?.serverUrl.orEmpty())
    }
    var user by rememberSaveable {
        mutableStateOf((editing as? PlaylistSource.Xtream)?.username.orEmpty())
    }
    var pass by rememberSaveable {
        mutableStateOf((editing as? PlaylistSource.Xtream)?.password.orEmpty())
    }
    var m3uUrl by rememberSaveable {
        mutableStateOf((editing as? PlaylistSource.M3u)?.url.orEmpty())
    }
    var epgUrl by rememberSaveable {
        mutableStateOf((editing as? PlaylistSource.M3u)?.epgUrl.orEmpty())
    }
    val addState = vm.addState

    // Remote BACK mirrors the on-screen Back button: form → chooser → leave.
    // With no chooser to fall back to, an edit leaves outright.
    androidx.activity.compose.BackHandler(enabled = step != Step.Choose) {
        vm.resetAddState()
        if (editing != null) onCancel() else step = Step.Choose
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
        // The lockup sits outside the scrolling part, which is the whole point
        // of the split. Everything used to share one scroll container, and the
        // chooser asks for focus on its card as soon as it composes — focusing
        // inside a scroller scrolls the target into view, so on any TV where
        // the content is taller than the screen (a larger font-size setting is
        // enough) the first frame scrolled the logo off the top and left it
        // there. Nothing below can push it away now.
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // The chooser gets the full lockup; forms keep just a small mark
            // so every field and the Connect button fit on a TV screen.
            if (step == Step.Choose) {
                // Same lockup as the launcher banner — mark a little over twice
                // the cap height, spaced by roughly a third of it — so the first
                // screen and the tile it was launched from are the same mark.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    // Derived from the wordmark rather than fixed at 64dp: the
                    // text scales with the TV's font-size setting and the mark
                    // did not, so the banner's 2.81:1 held only at font scale 1
                    // and the Row outgrew its width everywhere else. aspectRatio
                    // keeps ic_logo's own 55:76 instead of a rounded 46:64.
                    val wordSize = MaterialTheme.typography.headlineLarge.fontSize
                    val markHeight = with(androidx.compose.ui.platform.LocalDensity.current) {
                        wordSize.toDp() * 2f
                    }
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(com.nuxcor.nuxtv.R.drawable.ic_logo),
                        contentDescription = null,
                        modifier = Modifier.height(markHeight).aspectRatio(55f / 76f),
                    )
                    Text(
                        text = "AGORO",
                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Black),
                        color = NuxColors.Primary,
                    )
                }
                Spacer(Modifier.height(38.dp))
            } else {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(com.nuxcor.nuxtv.R.drawable.ic_logo),
                    contentDescription = null,
                    modifier = Modifier.height(44.dp).aspectRatio(55f / 76f),
                )
                Spacer(Modifier.height(10.dp))
            }

            // 560 or the screen, whichever is smaller: a single-column form
            // reads badly with its fields stretched across a TV, and the
            // chooser is one card now, so neither wants the full width.
            //
            // Order matters and is not obvious. fillMaxWidth measures its child
            // with a fixed width, and widthIn enforces the constraints it is
            // handed, so putting the cap second coerces 560 into [screen,
            // screen] and hands back the screen — the cap silently does
            // nothing. The cap has to sit outside the fill.
            //
            // weight(fill = false) so this takes only the height it needs and
            // scrolls when there isn't enough, instead of claiming the rest of
            // the screen and pushing the lockup up regardless.
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            when (step) {
                Step.Choose -> ChooseStep(
                    vm = vm,
                    cancellable = cancellable,
                    onXtream = { vm.resetAddState(); step = Step.Xtream },
                    onCancel = onCancel,
                )

                Step.Xtream -> XtreamForm(
                    addState = addState,
                    name = name, onName = { name = it },
                    server = server, onServer = { server = it },
                    user = user, onUser = { user = it },
                    pass = pass, onPass = { pass = it },
                    submitLabel = if (editing != null) "Save" else "Connect",
                    onSubmit = {
                        if (editing != null) {
                            vm.updateXtream(editing.id, name, server, user, pass, onSuccess = onDone)
                        } else {
                            vm.addXtream(name, server, user, pass, onSuccess = onDone)
                        }
                    },
                    onBack = {
                        vm.resetAddState()
                        if (editing != null) onCancel() else step = Step.Choose
                    },
                )

                Step.M3u -> M3uForm(
                    addState = addState,
                    name = name, onName = { name = it },
                    url = m3uUrl, onUrl = { m3uUrl = it },
                    epgUrl = epgUrl, onEpgUrl = { epgUrl = it },
                    submitLabel = if (editing != null) "Save" else "Connect",
                    onSubmit = {
                        if (editing != null) {
                            vm.updateM3u(editing.id, name, m3uUrl, epgUrl, onSuccess = onDone)
                        } else {
                            vm.addM3u(name, m3uUrl, epgUrl, onSuccess = onDone)
                        }
                    },
                    onBack = {
                        vm.resetAddState()
                        if (editing != null) onCancel() else step = Step.Choose
                    },
                )
            }
            }
        }
    }
}

@Composable
private fun ChooseStep(
    vm: MainViewModel,
    cancellable: Boolean,
    onXtream: () -> Unit,
    onCancel: () -> Unit,
) {
    // Nothing held focus when this screen opened — the forms below request it
    // but the chooser never did, so the first press of the D-pad went wherever
    // Compose decided and until then the screen looked inert.
    val firstCard = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstCard.requestFocus() } }

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Add your playlist",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            color = NuxColors.OnSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "The details your provider gave you. You can add more later.",
            style = MaterialTheme.typography.bodyMedium,
            color = NuxColors.OnSurfaceDim,
        )
        Spacer(Modifier.height(22.dp))
        // One way in. Playlists already added as M3U keep working and stay
        // editable through their own form — this is the choice of how to add a
        // new one, and a chooser with a single option is not a choice.
        SourceOptionCard(
            title = "Xtream Codes",
            subtitle = "Server URL, username and password",
            icon = Icons.Default.Dns,
            onClick = onXtream,
            modifier = Modifier.fillMaxWidth().focusRequester(firstCard),
        )
        Spacer(Modifier.height(26.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            if (cancellable) {
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
            // Updates don't need a playlist or an account — reachable here so a
            // first-run user never has to sideload again. Kept quiet: it is
            // maintenance, and it was sitting level with the only two controls
            // this screen exists to offer.
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

/**
 * What this screen asks for, as a card rather than a list row: the icon gets a
 * tinted chip in the brand gold, the title gets the top of the type scale this
 * screen uses, and the whole thing lifts on focus. Kept as a card, and kept
 * general, because it stood beside a second one until adding a playlist became
 * Xtream-only and may again.
 */
@Composable
private fun SourceOptionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuxColors.Surface,
            focusedContainerColor = NuxColors.SurfaceRaised,
            contentColor = NuxColors.OnSurface,
            focusedContentColor = NuxColors.OnSurface,
        ),
        // Focus was doing nothing but swapping a border colour. On a TV the
        // selected thing should visibly come forward.
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        border = ClickableSurfaceDefaults.border(
            border = androidx.tv.material3.Border(
                androidx.compose.foundation.BorderStroke(1.dp, NuxColors.Stroke),
                shape = RoundedCornerShape(18.dp),
            ),
            focusedBorder = focusBorder(),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 26.dp, vertical = 24.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(NuxColors.Primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = NuxColors.Primary,
                    modifier = Modifier.size(27.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = NuxColors.OnSurfaceDim,
            )
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
    submitLabel: String,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    val connectFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var revealPassword by rememberSaveable { mutableStateOf(false) }
    FormContainer(addState = addState, onBack = onBack, submitEnabled = server.isNotBlank() && user.isNotBlank(),
        onSubmit = onSubmit, submitLabel = submitLabel, connectFocus = connectFocus) {
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
    submitLabel: String,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    val connectFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    FormContainer(addState = addState, onBack = onBack, submitEnabled = url.isNotBlank(),
        onSubmit = onSubmit, submitLabel = submitLabel, connectFocus = connectFocus) {
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
    submitLabel: String = "Connect",
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
                Text(if (loading) "Connecting…" else submitLabel)
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
