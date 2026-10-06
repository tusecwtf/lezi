package com.lezi.babylog.feature.settings.calendar

import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.domain.careplan.ReminderCleanupPort
import dagger.Binds
import kotlinx.coroutines.CancellationException
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** Android adapter for the domain reminder-cleanup seam. */
@Singleton
class AlarmReminderCleanup @Inject constructor(
    private val carePlanAlarm: CarePlanReminderAlarm,
) : ReminderCleanupPort {
    override suspend fun scheduleCarePlan(plan: CarePlan): Boolean =
        try {
            carePlanAlarm.schedule(plan)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

    override suspend fun cancelCarePlan(carePlanId: Long) {
        carePlanAlarm.cancel(carePlanId)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ReminderCleanupModule {
    @Binds
    @Singleton
    abstract fun bindReminderCleanup(adapter: AlarmReminderCleanup): ReminderCleanupPort
}
