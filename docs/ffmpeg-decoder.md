# Rebuilding the FFmpeg audio decoder

`app/libs/media3-decoder-ffmpeg-1.8.0.aar` is built from source and committed,
because Google does not publish this module to Maven
([ExoPlayer issue 2781](https://github.com/google/ExoPlayer/issues/2781)).

## Why it ships

IPTV providers send AC-3, E-AC-3, DTS and TrueHD audio. Plenty of Android TV
boxes have no hardware decoder for those, and without this module those channels
play video with silence — the failure that the libVLC fallback existed to work
around. `DefaultRenderersFactory` loads `FfmpegAudioRenderer` reflectively when
the class is on the classpath, so `ExoEngine` needs no code to use it; it only
needs `EXTENSION_RENDERER_MODE_ON`, which it already sets.

The AAR is 1.6 MB and carries `libffmpegJNI.so` for `arm64-v8a` and
`armeabi-v7a` — the two ABIs this app ships.

## Rebuilding

Needs CMake 3.21+ and **the NDK media3 itself asks for at the target version**,
which is not necessarily the one this app pins. 1.8.0 built with
`27.1.12297006`; 1.11.0 wants `28.2.13676358` and its Gradle build downloads it
unprompted. Check before starting, and check free disk with it: a fresh NDK is
several GB, the two clones and the FFmpeg build are another ~1.5 GB, and a run
that fills the disk fails deep inside Gradle with a cascade of lock errors that
say nothing about space.

Everything else below is version-drifted too, so read it against the tag you
are building:

* **1.11.0 moved the module to the Kotlin DSL.** The `abiFilters` snippet goes
  in `build.gradle.kts`, not `build.gradle`, and reads
  `abiFilters += listOf("armeabi-v7a", "arm64-v8a")`.
* **1.11.0 bumped media3's own wrapper to Gradle 9.1**, which wants JDK 17+.

```bash
# 1. Sources, at the media3 version in gradle/libs.versions.toml.
git clone --depth 1 --branch 1.8.0 https://github.com/androidx/media.git
git clone --depth 1 --branch release/6.0 https://github.com/FFmpeg/FFmpeg.git ffmpeg

cd media/libraries/decoder_ffmpeg/src/main/jni
ln -sfn "$(cd ../../../../../../ffmpeg && pwd)" ffmpeg

# 2. Trim the stock script to the ABIs we ship (it also builds x86/x86_64).
head -n 104 build_ffmpeg.sh > build_ffmpeg_arm.sh && chmod +x build_ffmpeg_arm.sh

# 3. Build. The first argument is FFMPEG_MODULE_PATH, and the script appends
#    `/jni/ffmpeg` to it — so it is `src/main`, one level up from here, not
#    `src`. Passing `../..` sends it looking in `src/jni/ffmpeg`, which does
#    not exist, and the build dies on a cd before compiling anything.
#    ANDROID_ABI must be 21 — media3's own minSdk, NOT this app's 23.
#    Building at 23 links against a libc that the module's 21 target does not
#    export, and the JNI link fails on an undefined `stderr`.
./build_ffmpeg_arm.sh \
  "$(cd .. && pwd)" \
  "$HOME/Library/Android/sdk/ndk/27.1.12297006" \
  darwin-x86_64 \
  21 \
  ac3 eac3 dca truehd mlp alac opus vorbis flac mp3 aac aac_latm
```

Then restrict the module's own native build to the same two ABIs, or it fails
looking for x86 libraries it was never asked to produce — add to
`libraries/decoder_ffmpeg/build.gradle` inside `android { }`:

```gradle
defaultConfig { ndk { abiFilters 'armeabi-v7a', 'arm64-v8a' } }
```

Finally:

```bash
cd <media checkout>
./gradlew :lib-decoder-ffmpeg:assembleRelease
cp libraries/decoder_ffmpeg/buildout/outputs/aar/lib-decoder-ffmpeg-release.aar \
   <nuxtv>/app/libs/media3-decoder-ffmpeg-1.8.0.aar
```

## When to redo it

Whenever `media3` in `gradle/libs.versions.toml` moves. The AAR is compiled
against `lib-decoder` and `lib-exoplayer` from its own source tree, and the app
supplies those from Maven — the two versions must match, or you get a decoder
linked against a different ABI of the library that hosts it. Rename the file to
the new version so the mismatch is visible in the diff rather than silent.
