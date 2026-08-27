package com.agoro.tv.player

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.MediaSession

/**
 * A media3 [MediaSession] over the ExoPlayer instance, so system surfaces
 * (Now Playing, assistant "pause", HDMI-CEC transport keys routed through the
 * session framework) see and control playback.
 *
 * Deliberately an in-activity session, not a MediaSessionService: media3 only
 * posts a media notification from MediaSessionService/MediaLibraryService,
 * so a bare session adds no notification at all, which is what a TV player
 * wants. The session id is
 * per-instance because an engine swap briefly overlaps the old and new
 * engines, and duplicate session ids throw.
 */
internal class PlayerMediaSession(context: Context, player: Player) {
    private val session: MediaSession = MediaSession.Builder(context, player)
        .setId("agoro-player-${System.identityHashCode(player)}")
        .build()

    /** Must run before the wrapped player is released. */
    fun release() {
        session.release()
    }
}
