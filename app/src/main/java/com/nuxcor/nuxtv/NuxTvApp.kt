package com.nuxcor.nuxtv

import android.app.Application
import com.nuxcor.nuxtv.data.ContentRepository

class NuxTvApp : Application() {
    val repository: ContentRepository by lazy { ContentRepository(this) }
}
