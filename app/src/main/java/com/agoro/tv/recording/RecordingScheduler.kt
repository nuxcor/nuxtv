package com.agoro.tv.recording

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.agoro.tv.data.PlayerPrefs
import com.agoro.tv.data.ScheduledRecording
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
            // The one path the user asked for, so the one path that may prompt.
            registerAlarm(context, item, promptForExactAlarms = true)
        }
    }

    fun cancel(context: Context, prefs: PlayerPrefs, id: String) {
        // Cancel the alarm synchronously (cheap) so it can't fire while the
        // persistence write is still in flight.
        alarmManager(context).cancel(pendingIntent(context, id, null))
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            prefs.removeSchedule(id)
        }
    }

    suspend fun rescheduleAll(context: Context, prefs: PlayerPrefs) {
        val now = System.currentTimeMillis()
        prefs.schedules.first().forEach { item ->
            if (item.endMs > now) registerAlarm(context, item) else prefs.removeSchedule(item.id)
        }
    }

    /**
     * [promptForExactAlarms] only on a user-initiated schedule. rescheduleAll
     * runs on every app start and after boot, so prompting from there threw one
     * Settings activity per pending schedule over the UI at every launch —
     * SCHEDULE_EXACT_ALARM is denied by default from targetSdk 31.
     */
    private fun registerAlarm(
        context: Context,
        item: ScheduledRecording,
        promptForExactAlarms: Boolean = false,
    ) {
        val triggerAt = (item.startMs - START_PAD_MS).coerceAtLeast(System.currentTimeMillis())
        val pi = pendingIntent(context, item.id, item)
        val am = alarmManager(context)
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            // Ask the user to grant exact alarms so recordings start on time.
            if (promptForExactAlarms) runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .setData(android.net.Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /** Posts a "programme starting" notification shortly before start. */
    fun scheduleReminder(context: Context, channelName: String, program: com.agoro.tv.data.EpgProgram) {
        val intent = Intent(context, ReminderReceiver::class.java)
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
            .setAction("com.agoro.tv.SCHEDULED_RECORDING")
            .setData(android.net.Uri.parse("dzidzi://schedule/$id"))
        if (item != null) {
            intent.putExtra("id", item.id)
            intent.putExtra("url", item.recordUrl)
            intent.putExtra("name", "${item.channelName} — ${item.title}")
            // Measured from the alarm, which fires START_PAD_MS early — without
            // that term the head start comes out of the tail pad and a
            // programme that overruns loses its ending.
            intent.putExtra("durationMs", (item.endMs + END_PAD_MS) - (item.startMs - START_PAD_MS))
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
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching { prefs.removeSchedule(id) }
                pending.finish()
            }
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
