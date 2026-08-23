package com.agoro.tv

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import com.agoro.tv.ui.AppRoot
import com.agoro.tv.ui.theme.NuxTvTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Hold the system splash until the cached playlist has been read off
        // disk, then go straight to content. Startup used to hand the splash
        // off to an in-app boot screen that drew the same mark again and sat
        // on a 900ms floor, so every launch showed the logo twice and paid for
        // the second one in latency the disk cache exists to avoid.
        //
        // Bounded by a deadline: a source flow that somehow never emits must
        // not strand the app on a splash it can never dismiss.
        val vm = ViewModelProvider(this)[MainViewModel::class.java]
        val splashDeadline = SystemClock.uptimeMillis() + 2_000
        splash.setKeepOnScreenCondition {
            vm.sources.value == null && SystemClock.uptimeMillis() < splashDeadline
        }

        // Recording/reminder notifications are invisible on 13+ without this.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        setContent {
            NuxTvTheme {
                AppRoot(vm)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // The pooled players outlive the player screen on purpose (leaving
        // a channel must not block on codec teardown); a process that is
        // genuinely done — the activity finishing, not rotating — lets them
        // go. A stopped player holds no decoders, so leaking one across a
        // configuration change would cost nothing either way.
        if (isFinishing) com.agoro.tv.player.PlayerPool.drain()
    }
}
