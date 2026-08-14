package com.nuxcor.nuxtv.ui.screens

import android.view.KeyEvent as AndroidKeyEvent
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuxcor.nuxtv.MainViewModel
import com.nuxcor.nuxtv.ui.theme.NuxColors
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(vm: MainViewModel, onExit: () -> Unit) {
    val request = vm.playback
    if (request == null) {
        LaunchedEffect(Unit) { onExit() }
        return
    }

    val context = LocalContext.current
    var currentIndex by remember { mutableIntStateOf(request.startIndex) }
    var infoVisible by remember { mutableStateOf(true) }
    var controlsVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val player = remember {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("NuxTV/1.0")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
            .apply {
                setMediaItems(
                    request.items.map { item ->
                        MediaItem.Builder()
                            .setUri(item.url)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(item.title)
                                    .setArtist(item.subtitle)
                                    .build()
                            )
                            .build()
                    },
                    request.startIndex,
                    androidx.media3.common.C.TIME_UNSET,
                )
                playWhenReady = true
                prepare()
            }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentIndex = player.currentMediaItemIndex
                errorMessage = null
            }

            override fun onPlayerError(error: PlaybackException) {
                errorMessage = "Playback failed — ${error.errorCodeName.removePrefix("ERROR_CODE_")}"
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
            vm.clearPlayback()
        }
    }

    // Show the info overlay briefly whenever the item changes.
    LaunchedEffect(currentIndex) {
        infoVisible = true
        delay(3500)
        infoVisible = false
    }

    fun zap(delta: Int) {
        val count = player.mediaItemCount
        if (count <= 1) return
        val next = ((player.currentMediaItemIndex + delta) % count + count) % count
        player.seekToDefaultPosition(next)
        player.playWhenReady = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_CHANNEL_UP -> { zap(+1); true }
                    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); true }
                    AndroidKeyEvent.KEYCODE_DPAD_UP ->
                        if (request.isLive && !controlsVisible) { zap(+1); true } else false
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN ->
                        if (request.isLive && !controlsVisible) { zap(-1); true } else false
                    else -> false
                }
            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    controllerShowTimeoutMs = 4000
                    setShowNextButton(request.items.size > 1)
                    setShowPreviousButton(request.items.size > 1)
                    keepScreenOn = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            controlsVisible = visibility == View.VISIBLE
                        }
                    )
                    requestFocus()
                }
            },
        )

        val item = request.items.getOrNull(currentIndex)
        AnimatedVisibility(
            visible = infoVisible || controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(28.dp),
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(NuxColors.Scrim)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = item?.title.orEmpty(),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                )
                if (!item?.subtitle.isNullOrBlank()) {
                    Text(
                        text = item?.subtitle.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = NuxColors.OnSurfaceDim,
                    )
                }
            }
        }

        val error = errorMessage
        if (error != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(NuxColors.Scrim)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelLarge,
                    color = NuxColors.Error,
                )
            }
        }
    }
}
