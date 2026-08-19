# --- kotlinx.serialization (official R8 template) ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

-keepclasseswithmembers class **$$serializer {
    *** INSTANCE;
}

# --- libVLC uses JNI callbacks into these classes ---
-keep class org.videolan.libvlc.** { *; }

# --- Media3 FFmpeg audio decoder ---
# DefaultRenderersFactory finds this by name at runtime, so nothing in the app
# references it and R8 would otherwise drop the class. media3-exoplayer ships a
# -keepclassmembers rule for the constructor, which only holds if the class
# itself survives; these keep it.
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer { *; }
-keep class androidx.media3.decoder.ffmpeg.FfmpegLibrary { *; }
