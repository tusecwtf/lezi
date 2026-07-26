package com.lezi.babylog.domain

/**
 * Removes device-local reminders when their owning domain data disappears.
 *
 * The Android adapter owns AlarmManager/PendingIntent details. Callers only
 * express the domain operation and the calendar identities that are leaving.
 */
interface ReminderCleanupPort {
    suspend fun cancelForRecordsClear(calendarEventIds: Collection<Long>)

    suspend fun cancelForBabyDelete(calendarEventIds: Collection<Long>)
}
