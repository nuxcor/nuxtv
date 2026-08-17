package com.nuxcor.nuxtv.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * Audio-focus citizenship for engines that don't manage it themselves.
 * ExoPlayer handles focus internally (`handleAudioFocus` in its audio
 * attributes); libVLC does not, so the VLC engine drives this: request on
 * play, abandon on pause/release.
 *
 * Policy: permanent or transient loss pauses playback; a duck request lowers
 * the volume rather than pausing. No auto-resume on regaining focus — on a
 * TV, playback restarting by itself is more surprising than pressing play.
 */
internal class AudioFocusHelper(
    context: Context,
    private val onLoss: () -> Unit,
    private val onDuck: (Boolean) -> Unit,
) {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var held = false

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // The system revoked focus for good: unregister the request so
                // the stale listener doesn't linger and the next request()
                // starts clean. (held=false alone left abandon() a no-op.)
                abandon()
                onDuck(false)
                onLoss()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                onDuck(false)
                onLoss()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onDuck(true)
            AudioManager.AUDIOFOCUS_GAIN -> onDuck(false)
        }
    }

    private val focusRequest =
        if (Build.VERSION.SDK_INT >= 26) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setOnAudioFocusChangeListener(listener)
                .build()
        } else null

    fun request() {
        // No held fast-path: re-requesting an already-granted focus is
        // idempotent, and skipping it left the state machine stuck when a
        // transient loss (held stays true) was followed by a user resume.
        val result = if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) {
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
        held = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun abandon() {
        if (!held) return
        held = false
        if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(listener)
        }
    }
}
