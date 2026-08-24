package com.agoro.tv

import android.app.Application
import com.agoro.tv.data.ContentRepository
import com.agoro.tv.player.AudioOutputPolicy

class NuxTvApp : Application() {
    val repository: ContentRepository by lazy { ContentRepository(this) }

    override fun onCreate() {
        super.onCreate()
        // Whether this TV really takes the Dolby it advertises, settled
        // before the first film asks; see AudioOutputPolicy.probe. Off the
        // main thread: two AudioTracks are milliseconds, but AudioFlinger is
        // a binder call away and start-up is the wrong place to wait on one.
        Thread({ AudioOutputPolicy.probe(this) }, "audio-probe").start()
    }
}
