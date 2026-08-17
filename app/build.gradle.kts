import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Bundled TMDB key, so ratings and artwork work out of the box instead of
 * asking every viewer to register for one. Read from local.properties (which
 * is gitignored) or the environment for CI — never committed. Builds fine
 * without it: the key is then empty and enrichment stays opt-in via Settings.
 */
val tmdbApiKey: String = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }
        ?.inputStream()?.use { load(it) }
}.getProperty("TMDB_API_KEY") ?: System.getenv("TMDB_API_KEY") ?: ""

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.nuxcor.nuxtv"
    compileSdk = 36

    /**
     * Pinned so the build always has the NDK's `strip` on hand. Without it AGP
     * can't strip the prebuilt .so files and merely warns ("Unable to strip the
     * following libraries, packaging them as they are") — which is exactly what
     * CI did through v2.10.2, shipping 8 MB of arm64 debug symbols in
     * libc++_shared.so to every viewer. The workflow installs this same version.
     */
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "com.nuxcor.nuxtv"
        minSdk = 23
        targetSdk = 36
        versionCode = 43
        versionName = "2.15.0"

        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")

        // One APK for every real device: both ARM ABIs, no x86 (emulators only).
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("DZIDZI_KEYSTORE")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("DZIDZI_KEYSTORE_PASS")
                keyAlias = System.getenv("DZIDZI_KEY_ALIAS") ?: "dzidzi"
                keyPassword = System.getenv("DZIDZI_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (System.getenv("DZIDZI_KEYSTORE") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    // TV hardware is arm; per-ABI APKs keep downloads small (libVLC is heavy).
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.tv.material)

    // Every container an IPTV provider might hand us: progressive TS/MP4/MKV
    // from the core, plus HLS, DASH, SmoothStreaming, RTSP and RTMP.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.smoothstreaming)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.datasource.rtmp)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    // MediaSessionCompat for the VLC engine's minimal session.
    implementation(libs.androidx.media)
    implementation(libs.libvlc)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
