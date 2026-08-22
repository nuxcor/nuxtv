@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.agoro.tv.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
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
import com.agoro.tv.AddState
import com.agoro.tv.MainViewModel
import com.agoro.tv.data.PairingServer
import com.agoro.tv.data.PlaylistSource
import com.agoro.tv.ui.components.QrCode
import com.agoro.tv.ui.components.dpadFieldNavigation
import com.agoro.tv.ui.components.requestFocusRetrying
import androidx.compose.animation.togetherWith
import com.agoro.tv.ui.components.NuxFieldDefaults
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxMotion
import com.agoro.tv.ui.theme.NuxShape
import com.agoro.tv.ui.theme.Space
import com.agoro.tv.ui.theme.NuxFocus

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
    // A build made for one provider carries its address, so the viewer signs
    // in with a username and password and is never asked for a URL they have
    // no way to know. Editing keeps whatever that playlist was actually saved
    // with — a stored source is the truth about itself, not the build.
    var server by rememberSaveable {
        mutableStateOf(
            (editing as? PlaylistSource.Xtream)?.serverUrl
                ?: com.agoro.tv.BuildConfig.PROVIDER_HOST
        )
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
    // And not mid-connect: the on-screen Back is disabled while a login runs,
    // and the remote's BACK used to do what it refused — reset the form while
    // the save carried on underneath and popped the screen by itself later.
    androidx.activity.compose.BackHandler(enabled = step != Step.Choose && addState !is AddState.Loading) {
        vm.resetAddState()
        if (editing != null) onCancel() else step = Step.Choose
    }
    if (cancellable) {
        androidx.activity.compose.BackHandler(enabled = step == Step.Choose) { onCancel() }
    }

    // No background here: the glow is [NuxTheme.HeroGlow], handed to TvSafe so
    // it paints full-bleed. Painting it on this Box put it inside the overscan
    // inset, leaving the theme's page gradient visible in the margin as a
    // lighter frame around all four edges.
    Box(
        modifier = Modifier
            .fillMaxSize()
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
            // Forms keep a small mark on top; the chooser carries the full
            // lockup inside its own left column, so nothing composes above it.
            if (step != Step.Choose) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(com.agoro.tv.R.drawable.ic_logo),
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
                    .widthIn(max = if (step == Step.Choose) 1040.dp else 560.dp)
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    // Inside the scroll, so the clip region includes it. A
                    // focused card draws 1.04x with a 3dp ring — several dp
                    // outside its own bounds — and a scroller clips its scroll
                    // axis exactly while inflating the cross axis 30dp for
                    // shadows. With the chooser's heading gone the card became
                    // the first child, flush against that clip: ring cut along
                    // the top, intact down the sides. This is the headroom.
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.compose.animation.AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        androidx.compose.animation.fadeIn(
                            androidx.compose.animation.core.tween(
                                NuxMotion.StandardMs, easing = NuxMotion.StandardEasing,
                            )
                        ) togetherWith androidx.compose.animation.fadeOut(
                            androidx.compose.animation.core.tween(
                                NuxMotion.FastMs, easing = NuxMotion.ExitEasing,
                            )
                        )
                    },
                    label = "onboardingStep",
                ) { currentStep ->
                when (currentStep) {
                    Step.Choose -> ChooseStep(
                        vm = vm,
                        cancellable = cancellable,
                        onXtream = { vm.resetAddState(); step = Step.Xtream },
                        onCancel = onCancel,
                        onDone = onDone,
                    )

                    Step.Xtream -> XtreamForm(
                        // Hidden only when the build supplies it AND this is a
                        // fresh login: adding a second playlist by hand, or
                        // correcting the address on an existing one, still
                        // needs the field.
                        askForServer = editing != null ||
                            com.agoro.tv.BuildConfig.PROVIDER_HOST.isBlank(),
                        addState = addState,
                        name = name, onName = { name = it },
                        server = server, onServer = { server = it },
                        user = user, onUser = { user = it },
                        pass = pass, onPass = { pass = it },
                        submitLabel = if (editing != null) "Save" else "Connect",
                        editing = editing != null,
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
                        editing = editing != null,
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
}

@Composable
private fun ChooseStep(
    vm: MainViewModel,
    cancellable: Boolean,
    onXtream: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    // Nothing held focus when this screen opened — the forms below request it
    // but the chooser never did, so the first press of the D-pad went wherever
    // Compose decided and until then the screen looked inert.
    val firstFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // Retried on the Boolean — a declined request must not strand the screen
    // with nothing focused; see requestFocusRetrying.
    LaunchedEffect(Unit) { firstFocus.requestFocusRetrying() }

    // Phone-assisted sign-in: the server lives exactly as long as this step —
    // its DisposableEffect stops it when a form opens or the screen leaves.
    val pairing = remember {
        PairingServer(
            // Same rule as the TV's own form: a build made for one provider
            // carries its address, so the phone page asks only who you are.
            defaultServer = com.agoro.tv.BuildConfig.PROVIDER_HOST.takeIf { it.isNotBlank() },
        ) { name, server, user, pass ->
            vm.addXtream(name, server, user, pass, onSuccess = onDone)
        }
    }
    var pairingUrl by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        pairingUrl = pairing.start()
        onDispose { pairing.stop() }
    }
    val addState = vm.addState

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(56.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // The mark alone, matching the launcher banner and the drawer —
            // the wordmark came off everywhere at once, so the tile, the
            // first screen and the nav all carry the same identity. Height
            // still derives from the headline size so it tracks the TV's
            // font-size setting; aspectRatio keeps ic_logo's own 55:76.
            run {
                val wordSize = MaterialTheme.typography.headlineLarge.fontSize
                val markHeight = with(androidx.compose.ui.platform.LocalDensity.current) {
                    wordSize.toDp() * 2f
                }
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(com.agoro.tv.R.drawable.ic_logo),
                    contentDescription = "Agoro",
                    modifier = Modifier.height(markHeight).aspectRatio(55f / 76f),
                )
            }
            Spacer(Modifier.height(26.dp))
            Text(
                text = if (pairingUrl != null) "Sign in from your phone"
                else "Connect your provider",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                color = NuxColors.OnSurface,
            )
            if (pairingUrl != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Scan the code with your phone's camera.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuxColors.OnSurfaceDim,
                )
            }
            // The phone flow reports here: this screen stays up while the
            // provider is checked, so its progress and errors must be visible
            // on the TV, not just implied on the phone.
            when (addState) {
                is AddState.Loading -> {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = addState.step,
                        style = MaterialTheme.typography.labelLarge,
                        color = NuxColors.Secondary,
                    )
                }
                is AddState.Error -> {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = addState.message,
                        style = MaterialTheme.typography.labelLarge,
                        color = NuxColors.Error,
                    )
                }
                else -> Unit
            }
            Spacer(Modifier.height(26.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onXtream,
                    modifier = Modifier.focusRequester(firstFocus),
                    // "Instead" of the phone — only when there is a phone
                    // route on screen to be instead of.
                ) { Text(if (pairingUrl != null) "Enter on TV instead" else "Enter details") }
                if (cancellable) {
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
            }
        }

        val url = pairingUrl
        if (url != null) {
            // The QR is the hero: a white tile, because a QR needs light
            // ground and dark modules to scan — the one place the charcoal
            // theme steps aside.
            Box(
                modifier = Modifier
                    .size(236.dp)
                    .clip(NuxShape.Card)
                    .background(Color.White)
                    .padding(16.dp),
            ) {
                QrCode(
                    data = url,
                    contentDescription = "Sign-in QR code",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            // No LAN address (no network yet): the manual card is the flow.
            SourceOptionCard(
                title = "Sign in with your details",
                subtitle = "Server address, username and password",
                icon = Icons.Default.Dns,
                onClick = onXtream,
                modifier = Modifier.weight(1f),
            )
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
        shape = ClickableSurfaceDefaults.shape(NuxShape.Card),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuxColors.Surface,
            focusedContainerColor = NuxColors.SurfaceRaised,
            contentColor = NuxColors.OnSurface,
            focusedContentColor = NuxColors.OnSurface,
        ),
        // Focus was doing nothing but swapping a border colour. On a TV the
        // selected thing should visibly come forward.
        scale = ClickableSurfaceDefaults.scale(focusedScale = NuxFocus.CardScale),
        border = ClickableSurfaceDefaults.border(
            border = com.agoro.tv.ui.theme.NuxBorders.restingCard,
            focusedBorder = NuxFocus.ring16,
        ),
    ) {
        // Horizontal: icon chip, then the text, then an affordance chevron.
        // The old stacked layout (icon above text) was shaped for a grid of
        // two cards; alone at 560dp it left a field of dead space beside the
        // floating icon.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(NuxColors.SelectedContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = NuxColors.Primary,
                    modifier = Modifier.size(27.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuxColors.OnSurfaceDim,
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = NuxColors.OnSurfaceDim,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun XtreamForm(
    /** False when the build already knows the address; see [OnboardingScreen]. */
    askForServer: Boolean,
    addState: AddState,
    name: String, onName: (String) -> Unit,
    server: String, onServer: (String) -> Unit,
    user: String, onUser: (String) -> Unit,
    pass: String, onPass: (String) -> Unit,
    submitLabel: String,
    editing: Boolean,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    val connectFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var revealPassword by rememberSaveable { mutableStateOf(false) }
    FormContainer(addState = addState, onBack = onBack, submitEnabled = server.isNotBlank() && user.isNotBlank(),
        onSubmit = onSubmit, submitLabel = submitLabel, editing = editing,
        connectFocus = connectFocus) { firstFieldFocus ->
        fun first() = firstFieldFocus?.let { Modifier.focusRequester(it) } ?: Modifier
        // Credentials only. The playlist name was an optional field nobody
        // filled in on a remote, and it sat between the password and Connect.
        if (askForServer) {
            NuxTextField(
                value = server, onValueChange = onServer, label = "Server URL  •  http://host:port",
                modifier = first(),
            )
        }
        NuxTextField(
            value = user, onValueChange = onUser, label = "Username",
            modifier = if (askForServer) Modifier else first(),
        )
        NuxTextField(
            value = pass,
            onValueChange = onPass,
            label = "Password",
            password = !revealPassword,
            isLast = true,
            // Done on the keyboard goes to Connect; DOWN on the remote takes
            // the next thing on screen, which is the Show password toggle —
            // jumping over it made it reachable only by UP from Connect.
            onAdvance = { runCatching { connectFocus.requestFocus() } },
            dpadDownAdvances = false,
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
    }
}

@Composable
private fun M3uForm(
    addState: AddState,
    name: String, onName: (String) -> Unit,
    url: String, onUrl: (String) -> Unit,
    epgUrl: String, onEpgUrl: (String) -> Unit,
    submitLabel: String,
    editing: Boolean,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    val connectFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    FormContainer(addState = addState, onBack = onBack, submitEnabled = url.isNotBlank(),
        onSubmit = onSubmit, submitLabel = submitLabel, editing = editing,
        connectFocus = connectFocus) { firstFieldFocus ->
        NuxTextField(
            value = url, onValueChange = onUrl, label = "Playlist URL  •  http://…/playlist.m3u",
            modifier = firstFieldFocus?.let { Modifier.focusRequester(it) } ?: Modifier,
        )
        NuxTextField(
            value = epgUrl,
            onValueChange = onEpgUrl,
            label = "TV guide URL (optional)",
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
    /** True when editing: the button reads "Cancel" and focus opens on Save. */
    editing: Boolean = false,
    connectFocus: androidx.compose.ui.focus.FocusRequester =
        androidx.compose.ui.focus.FocusRequester(),
    /** The form's fields; the requester, when non-null, goes on the first one. */
    fields: @Composable (firstFieldFocus: androidx.compose.ui.focus.FocusRequester?) -> Unit,
) {
    val loading = addState is AddState.Loading
    // The chooser's focused button is gone by the time the form fades in, so
    // the form opens with nothing focused unless it asks. Adding lands on the
    // first field; editing lands on Save, so the keyboard doesn't pop unasked
    // over details that were probably fine.
    val arrival = com.agoro.tv.ui.components.rememberInitialFocus(Unit)
    val backLabel = if (editing) "Cancel" else "Back"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        fields(if (editing) null else arrival)
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
            OutlinedButton(onClick = onBack, enabled = !loading) { Text(backLabel) }
            Button(
                onClick = onSubmit,
                enabled = submitEnabled && !loading,
                modifier = Modifier
                    .focusRequester(connectFocus)
                    .then(if (editing) Modifier.focusRequester(arrival) else Modifier),
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
    /** Whether DOWN on the remote runs [onAdvance] too, or just moves focus. */
    dpadDownAdvances: Boolean = true,
    modifier: Modifier = Modifier,
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
        modifier = modifier
            .fillMaxWidth()
            // TV remotes navigate fields with the D-pad; the m3 TextField
            // swallows those keys by default. Down advances the form.
            .dpadFieldNavigation(onDown = if (dpadDownAdvances) advance else null),
        colors = NuxFieldDefaults.colors(),
    )
}
