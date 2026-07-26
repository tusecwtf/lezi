package com.lezi.babylog.domain

import com.lezi.babylog.core.model.CarePlan

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

    /**
     * Schedule or replace a non-exact care-plan alarm at [CarePlan.scheduledAt].
     * Returns false when disabled, past, permission-degraded, or plan not open.
     * Never throws for permission denial — plan save must succeed regardless.
     */
    suspend fun scheduleCarePlan(plan: CarePlan): Boolean

    suspend fun cancelCarePlan(carePlanId: Long)

    suspend fun cancelCarePlanByClientUuid(clientUuid: String)

    suspend fun cancelForRecordsClear(calendarEventIds: Collection<Long>)

    suspend fun cancelForBabyDelete(calendarEventIds: Collection<Long>)
}
