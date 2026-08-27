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
        // Kept verbatim from before recording was removed. Rewriting it from
        // scratch lost three things at once, all of them silent: the exact-alarm
        // permission guard, the identity that keeps two reminders apart, and the
        // clamp that lets one fire for a programme already inside the minute.
        val intent = Intent(context, ReminderReceiver::class.java)
            // Action and data are what make PendingIntents distinct — extras are
            // NOT part of the comparison — so without them every reminder is the
            // same PendingIntent and FLAG_UPDATE_CURRENT quietly replaces the
            // previous one. Keyed on the programme's id, not its title: "BBC News
            // at Ten" tonight and tomorrow are two reminders, and the same
            // programme on two channels is two more.
            .setAction("com.agoro.tv.REMINDER")
            .setData(android.net.Uri.parse("dzidzi://reminder/${program.id.hashCode()}"))
            .putExtra("title", program.title)
            .putExtra("channel", channelName)
        val pi = PendingIntent.getBroadcast(
            context,
            program.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Clamped, not skipped. A programme starting in thirty seconds still
        // gets a reminder — it just fires now — where returning early would
        // leave the caller saying "Reminder set" over nothing.
        val triggerAt = (program.startMs - 60_000).coerceAtLeast(System.currentTimeMillis())
        val am = alarmManager(context)
        // targetSdk 36, so on 31+ this app does not hold SCHEDULE_EXACT_ALARM by
        // default. Without the fallback setExactAndAllowWhileIdle throws
        // SecurityException, and a runCatching around it turns every reminder
        // into a toast and no alarm.
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun alarmManager(context: Context) =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

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
