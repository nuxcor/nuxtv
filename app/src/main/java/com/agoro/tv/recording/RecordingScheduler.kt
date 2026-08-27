package com.agoro.tv.recording

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Programme reminders: a one-shot alarm a minute before something starts.
 *
 * All that is left of what was the recording package. Recording itself was
 * removed on 2026-08-27 — it wrote unbounded files to a box with little room,
 * and it started on a single unconfirmed press from the player's options menu,
 * so it was as easy to begin by accident as on purpose.
 *
 * Reminders survive because they are a different thing that happened to live
 * here: they cost nothing on disk, they were already the fallback wherever a
 * recording could not be made, and they are now what the guide's OK does in
 * every case rather than only some.
 */
object RecordingScheduler {

    fun scheduleReminder(context: Context, channelName: String, program: com.agoro.tv.data.EpgProgram) {
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra("title", program.title)
            .putExtra("channel", channelName)
        val pending = PendingIntent.getBroadcast(
            context,
            program.title.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val at = program.startMs - 60_000L
        if (at <= System.currentTimeMillis()) return
        val am = context.getSystemService(AlarmManager::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, pending)
            }
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: return
        val channel = intent.getStringExtra("channel") ?: ""
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                android.app.NotificationChannel(
                    "reminders",
                    "Programme reminders",
                    android.app.NotificationManager.IMPORTANCE_HIGH,
                )
            )
        }
        val notification = androidx.core.app.NotificationCompat.Builder(context, "reminders")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Starting soon: $title")
            .setContentText("On $channel in about a minute")
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(title.hashCode(), notification) }
    }
}
