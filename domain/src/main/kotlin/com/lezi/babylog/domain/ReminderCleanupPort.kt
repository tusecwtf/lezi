package com.lezi.babylog.domain

/**
 * Removes device-local reminders when their owning domain data disappears.
 *
 * The Android adapter owns AlarmManager/PendingIntent details. Callers only
 * express the domain operation and the calendar identities that are leaving.
 */
interface ReminderCleanupPort {
    /** Schedule or replace the alarm for [event]; false means no future reminder exists. */
    suspend fun scheduleCalendar(event: CalendarEvent): Boolean

    suspend fun cancelCalendar(eventId: Long)

    suspend fun cancelForRecordsClear(calendarEventIds: Collection<Long>)

    suspend fun cancelForBabyDelete(calendarEventIds: Collection<Long>)
}
