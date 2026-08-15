package com.nuxcor.nuxtv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.nuxcor.nuxtv.ui.AppRoot
import com.nuxcor.nuxtv.ui.theme.NuxTvTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            NuxTvTheme {
                AppRoot()
            }
        }
    }
}
