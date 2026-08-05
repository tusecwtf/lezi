package com.lezi.gf.app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.lezi.gf.care.CarePlan
import com.lezi.gf.care.PlanStatus
import com.lezi.gf.care.ReminderPolicy

/**
 * Ticket 11: inexact local reminders + optional system calendar projection.
 * OS calendar mutations never write back into Lezi (no inbound listener).
 */
class LocalReminderScheduler(
    private val context: Context,
    private val policy: ReminderPolicy = ReminderPolicy(),
) {
    fun setLocalRemindersEnabled(enabled: Boolean) {
        policy.localRemindersEnabled = enabled
    }

    fun setSystemCalendarProjectionEnabled(enabled: Boolean) {
        policy.systemCalendarProjectionEnabled = enabled
    }

    fun policy(): ReminderPolicy = policy

    /**
     * Schedule an inexact alarm for a pending plan (no SCHEDULE_EXACT_ALARM).
     * Returns true when AlarmManager accepted the schedule.
     */
    fun scheduleInexactReminder(plan: CarePlan): Boolean {
        if (!policy.shouldFireLocalReminder(plan)) return false
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ACTION_PLAN_REMINDER
            putExtra(EXTRA_PLAN_UUID, plan.clientUuid)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            plan.clientUuid.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Inexact RTC alarm — product forbids SCHEDULE_EXACT_ALARM
        am.set(
            AlarmManager.RTC_WAKEUP,
            plan.scheduledAtMs,
            pi,
        )
        return true
    }

    fun cancelReminder(planUuid: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ACTION_PLAN_REMINDER
            putExtra(EXTRA_PLAN_UUID, planUuid)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            planUuid.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.cancel(pi)
    }

    /**
     * One-way projection to system calendar via insert Intent extras builder.
     * Returns content values for CalendarContract — caller/content resolver writes.
     * Does not read OS edits back.
     */
    fun buildSystemCalendarEventValues(plan: CarePlan, calendarId: Long): android.content.ContentValues? {
        if (!policy.shouldProjectToSystemCalendar(plan)) return null
        if (plan.status != PlanStatus.PENDING && plan.status != PlanStatus.MISSED) return null
        return android.content.ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, "乐记计划")
            put(CalendarContract.Events.DESCRIPTION, plan.note.ifBlank { plan.typeKey })
            put(CalendarContract.Events.DTSTART, plan.scheduledAtMs)
            put(CalendarContract.Events.DTEND, plan.scheduledAtMs + 30 * 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, "Asia/Shanghai")
            // No reverse path: we never listen for OS EVENT updates
        }
    }

    /**
     * Attempt insert via ContentResolver when permission available.
     * Returns event URI string or null if projection disabled / insert fails.
     */
    fun projectToSystemCalendar(plan: CarePlan, calendarId: Long = 1L): String? {
        val values = buildSystemCalendarEventValues(plan, calendarId) ?: return null
        return try {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            uri?.toString()
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val ACTION_PLAN_REMINDER = "com.lezi.gf.app.ACTION_PLAN_REMINDER"
        const val EXTRA_PLAN_UUID = "plan_uuid"
    }
}
