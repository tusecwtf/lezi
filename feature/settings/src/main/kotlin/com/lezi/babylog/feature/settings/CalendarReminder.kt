package com.lezi.babylog.feature.settings

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.domain.CalendarEvent
import com.lezi.babylog.domain.CareLog
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalendarReminderAlarm @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun schedule(event: CalendarEvent): Boolean {
        val remindAt = event.remindAt ?: return false
        if (remindAt <= RecordTime.currentTimeMillis()) return false
        CalendarReminderScheduler.ensureChannel(context)
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = pendingIntent(context, event)
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, remindAt, pending)
        return true
    }

    fun cancel(eventId: Long) {
        context.getSystemService(AlarmManager::class.java)
            .cancel(pendingIntent(context, eventId, "", 0L))
    }

    private companion object {
        const val EXTRA_ID = "calendar_event_id"
        const val EXTRA_TITLE = "calendar_title"
        const val EXTRA_EVENT_AT = "calendar_event_at"

        fun pendingIntent(context: Context, event: CalendarEvent): PendingIntent =
            pendingIntent(context, event.id, event.title, event.eventAt)

        fun pendingIntent(
            context: Context,
            eventId: Long,
            title: String,
            eventAt: Long,
        ): PendingIntent = PendingIntent.getBroadcast(
            context,
            eventId.hashCode(),
            Intent(context, CalendarReminderReceiver::class.java)
                .putExtra(EXTRA_ID, eventId)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_EVENT_AT, eventAt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

@Singleton
class CalendarReminderScheduler @Inject constructor(
    private val alarm: CalendarReminderAlarm,
    private val careLog: CareLog,
) {
    fun schedule(event: CalendarEvent): Boolean = alarm.schedule(event)

    fun cancel(eventId: Long) {
        alarm.cancel(eventId)
    }

    suspend fun rescheduleAll() {
        careLog.listBabies().forEach { baby ->
            careLog.listCalendarEvents(baby.id).forEach(::schedule)
        }
    }

    companion object {
        const val CHANNEL = "calendar_reminders"

        fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        "育儿日程",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                )
        }

    }
}

@AndroidEntryPoint
class CalendarReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        CalendarReminderScheduler.ensureChannel(context)
        val title = intent?.getStringExtra("calendar_title").orEmpty().ifBlank { "育儿日程" }
        val notification = NotificationCompat.Builder(context, CalendarReminderScheduler.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("乐记 · $title")
            .setContentText("日程时间到了")
            .setAutoCancel(true)
            .build()
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            notifyGranted(context, intent?.getLongExtra("calendar_event_id", 0L) ?: 0L, notification)
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyGranted(context: Context, eventId: Long, notification: Notification) {
        runCatching {
            NotificationManagerCompat.from(context).notify(eventId.hashCode(), notification)
        }
    }
}
