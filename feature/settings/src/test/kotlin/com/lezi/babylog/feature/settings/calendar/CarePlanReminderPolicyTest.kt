package com.lezi.babylog.feature.settings.calendar

import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.RecordType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarePlanReminderPolicyTest {
    private fun plan(
        status: CarePlanStatus = CarePlanStatus.PENDING,
        scheduledAt: Long = 2_000L,
        deletedAt: Long? = null,
    ) = CarePlan(
        id = 1L,
        clientUuid = "plan-1",
        babyId = 1L,
        type = RecordType.FORMULA,
        scheduledAt = scheduledAt,
        scheduledZoneId = "Asia/Shanghai",
        status = status,
        updatedAt = 1L,
        deletedAt = deletedAt,
    )

    @Test
    fun onlyFuturePendingOpenPlansAreEligibleWhenToggleOn() {
        assertTrue(
            carePlanReminderEligible(
                plan = plan(scheduledAt = 5_000L),
                localRemindersEnabled = true,
                nowMillis = 1_000L,
            ),
        )
        assertFalse(
            carePlanReminderEligible(
                plan = plan(scheduledAt = 5_000L),
                localRemindersEnabled = false,
                nowMillis = 1_000L,
            ),
        )
        assertFalse(
            carePlanReminderEligible(
                plan = plan(status = CarePlanStatus.SKIPPED, scheduledAt = 5_000L),
                localRemindersEnabled = true,
                nowMillis = 1_000L,
            ),
        )
        assertFalse(
            carePlanReminderEligible(
                plan = plan(scheduledAt = 500L),
                localRemindersEnabled = true,
                nowMillis = 1_000L,
            ),
        )
        assertFalse(
            carePlanReminderEligible(
                plan = plan(scheduledAt = 5_000L, deletedAt = 2_000L),
                localRemindersEnabled = true,
                nowMillis = 1_000L,
            ),
        )
    }
}
