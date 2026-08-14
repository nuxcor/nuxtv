package com.nuxcor.nuxtv.recording

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.nuxcor.nuxtv.data.PlayerPrefs
import com.nuxcor.nuxtv.data.ScheduledRecording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Schedules future recordings with AlarmManager (exact when the user has
 * granted exact alarms, inexact otherwise) and persists them so they survive
 * process death and reboots.
 */
object RecordingScheduler {

    private const val START_PAD_MS = 60_000L // start a minute early
    private const val END_PAD_MS = 120_000L  // and run two minutes long

    fun schedule(context: Context, prefs: PlayerPrefs, item: ScheduledRecording) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            prefs.addSchedule(item)
            registerAlarm(context, item)
        }
    }

    fun cancel(context: Context, prefs: PlayerPrefs, id: String) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            prefs.removeSchedule(id)
            alarmManager(context).cancel(pendingIntent(context, id, null))
        }
    }

    suspend fun rescheduleAll(context: Context, prefs: PlayerPrefs) {
        val now = System.currentTimeMillis()
        prefs.schedules.first().forEach { item ->
            if (item.endMs > now) registerAlarm(context, item) else prefs.removeSchedule(item.id)
        }
    }

    private fun registerAlarm(context: Context, item: ScheduledRecording) {
        val triggerAt = (item.startMs - START_PAD_MS).coerceAtLeast(System.currentTimeMillis())
        val pi = pendingIntent(context, item.id, item)
        val am = alarmManager(context)
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    /** Posts a "programme starting" notification shortly before start. */
    fun scheduleReminder(context: Context, channelName: String, program: com.nuxcor.nuxtv.data.EpgProgram) {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction("com.nuxcor.nuxtv.REMINDER")
            .setData(android.net.Uri.parse("dzidzi://reminder/${program.id.hashCode()}"))
            .putExtra("title", program.title)
            .putExtra("channel", channelName)
        val pi = PendingIntent.getBroadcast(
            context,
            program.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerAt = (program.startMs - 60_000).coerceAtLeast(System.currentTimeMillis())
        val am = alarmManager(context)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun alarmManager(context: Context) =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pendingIntent(context: Context, id: String, item: ScheduledRecording?): PendingIntent {
        val intent = Intent(context, ScheduledRecordingReceiver::class.java)
            .setAction("com.nuxcor.nuxtv.SCHEDULED_RECORDING")
            .setData(android.net.Uri.parse("dzidzi://schedule/$id"))
        if (item != null) {
            intent.putExtra("id", item.id)
            intent.putExtra("url", item.recordUrl)
            intent.putExtra("name", "${item.channelName} — ${item.title}")
            intent.putExtra("durationMs", item.endMs - item.startMs + END_PAD_MS)
        }
        return PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

class ScheduledRecordingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val url = intent.getStringExtra("url") ?: return
        val name = intent.getStringExtra("name") ?: "Scheduled recording"
        val id = intent.getStringExtra("id")
        val durationMs = intent.getLongExtra("durationMs", 0L).takeIf { it > 0 }

        runCatching {
            val serviceIntent = Intent(context, RecordingService::class.java)
                .setAction(RecordingService.ACTION_START)
                .putExtra(RecordingService.EXTRA_URL, url)
                .putExtra(RecordingService.EXTRA_NAME, name)
                .apply { durationMs?.let { putExtra(RecordingService.EXTRA_DURATION_MS, it) } }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(serviceIntent)
            else context.startService(serviceIntent)
        }

        if (id != null) {
            val prefs = PlayerPrefs(context.applicationContext)
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { prefs.removeSchedule(id) }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = PlayerPrefs(context.applicationContext)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { RecordingScheduler.rescheduleAll(context, prefs) }
            pending.finish()
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
