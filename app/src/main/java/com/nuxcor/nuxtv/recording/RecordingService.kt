package com.nuxcor.nuxtv.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Foreground service that records a live stream by copying its raw TS bytes
 * to a file until stopped. One recording at a time.
 */
class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.nuxcor.nuxtv.recording.START"
        const val ACTION_STOP = "com.nuxcor.nuxtv.recording.STOP"
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        /** Optional: auto-stop after this long (scheduled recordings). */
        const val EXTRA_DURATION_MS = "durationMs"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 42
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var stopTimer: Job? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // endless live stream
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY.also { stopSelf() }
                val name = intent.getStringExtra(EXTRA_NAME) ?: "Recording"
                val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L).takeIf { it > 0 }
                startForeground(NOTIFICATION_ID, buildNotification(name))
                startRecording(url, name, durationMs)
            }

            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(url: String, name: String, durationMs: Long? = null) {
        runBlocking { job?.cancelAndJoin() }
        val safeName = name.replace(Regex("""[^\w\s.-]"""), "").trim().ifBlank { "Recording" }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        val file = File(RecordingManager.directory(this), "$safeName $stamp.ts")

        stopTimer?.cancel()
        stopTimer = durationMs?.let { limit ->
            scope.launch {
                kotlinx.coroutines.delay(limit)
                stopRecording()
            }
        }

        job = scope.launch {
            val startedAt = System.currentTimeMillis()
            RecordingManager.update(ActiveRecording(name, file, startedAt, 0))
            try {
                val request = Request.Builder().url(url).header("User-Agent", "NuxTV/1.0").build()
                http.newCall(request).execute().use { response ->
                    val body = response.body ?: return@use
                    body.byteStream().use { input ->
                        file.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            var lastUpdate = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                total += read
                                val now = System.currentTimeMillis()
                                if (now - lastUpdate > 2_000) {
                                    lastUpdate = now
                                    RecordingManager.update(ActiveRecording(name, file, startedAt, total))
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Cancellation or network drop — whatever was written stays on disk.
            } finally {
                if (file.length() == 0L) file.delete()
                RecordingManager.update(null)
                stopSelf()
            }
        }
    }

    private fun stopRecording() {
        stopTimer?.cancel()
        stopTimer = null
        job?.cancel()
        job = null
        RecordingManager.update(null)
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 24) stopForeground(true)
        stopSelf()
    }

    private fun buildNotification(name: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Recording $name")
            .setContentText("NuxTV is recording this channel")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .build()
    }

    override fun onDestroy() {
        job?.cancel()
        RecordingManager.update(null)
        super.onDestroy()
    }
}
