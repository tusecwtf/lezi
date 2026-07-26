package com.lezi.babylog.feature.settings

import com.lezi.babylog.domain.CalendarEvent
import com.lezi.babylog.domain.ReminderCleanupPort
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** Android adapter for the domain reminder-cleanup seam. */
@Singleton
class AlarmReminderCleanup @Inject constructor(
    private val nextFeedScheduler: NextFeedScheduler,
    private val calendarAlarm: CalendarReminderAlarm,
) : ReminderCleanupPort {
    override suspend fun scheduleCalendar(event: CalendarEvent): Boolean =
        calendarAlarm.schedule(event)

    override suspend fun cancelCalendar(eventId: Long) {
        calendarAlarm.cancel(eventId)
    }

    override suspend fun cancelForRecordsClear(calendarEventIds: Collection<Long>) {
        nextFeedScheduler.cancel()
        cancelCalendar(calendarEventIds)
    }

    override suspend fun cancelForBabyDelete(calendarEventIds: Collection<Long>) {
        cancelCalendar(calendarEventIds)
    }

    private fun cancelCalendar(calendarEventIds: Collection<Long>) {
        calendarEventIds.distinct().forEach(calendarAlarm::cancel)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ReminderCleanupModule {
    @Binds
    @Singleton
    abstract fun bindReminderCleanup(adapter: AlarmReminderCleanup): ReminderCleanupPort
}
