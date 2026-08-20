package com.agoro.tv

import android.app.Application
import com.agoro.tv.data.ContentRepository

class NuxTvApp : Application() {
    val repository: ContentRepository by lazy { ContentRepository(this) }
}
