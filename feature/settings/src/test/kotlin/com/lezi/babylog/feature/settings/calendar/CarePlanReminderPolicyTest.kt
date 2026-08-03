package com.lezi.babylog.feature.settings.calendar

import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.RecordType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    @Test
    fun requestCodesDoNotReuseTheOldSixteenBitPlanIdWindow() {
        val planId = 7L

        assertNotEquals(
            CarePlanReminderAlarm.requestCode(planId),
            CarePlanReminderAlarm.requestCode(planId + 0x0000_FFFFL),
        )
    }

    @Test
    fun pendingIntentAndDrawerNotificationUseTheEntirePlanIdAsIdentity() {
        val first = carePlanReminderPlatformIdentity(7L)
        val sameLowBits = carePlanReminderPlatformIdentity(7L + (1L shl 32))

        assertNotEquals(first.pendingIntentData, sameLowBits.pendingIntentData)
        assertNotEquals(first.notificationTag, sameLowBits.notificationTag)
        assertEquals("lezi://local-reminder/care-plan/7", first.pendingIntentData)
        assertEquals("care-plan:7", first.notificationTag)
    }

    @Test
    fun upgradeCleanupRetainsTheLegacyAlarmAndNotificationIdentity() {
        assertEquals(0x4C5A_0007, legacyCarePlanReminderRequestCode(7L))
        assertEquals(
            legacyCarePlanReminderRequestCode(7L),
            legacyCarePlanReminderRequestCode(7L + 0x0000_FFFFL),
        )
    }

    @Test
    fun enabledRemindersDurablySurfaceMissingAndroidThirteenNotificationPermission() {
        assertEquals(
            "通知权限未开启，本机不会显示到点通知。",
            carePlanNotificationPermissionWarning(
                remindersEnabled = true,
                sdkInt = 33,
                permissionGranted = false,
            ),
        )
        assertNull(
            carePlanNotificationPermissionWarning(
                remindersEnabled = false,
                sdkInt = 33,
                permissionGranted = false,
            ),
        )
        assertNull(
            carePlanNotificationPermissionWarning(
                remindersEnabled = true,
                sdkInt = 32,
                permissionGranted = false,
            ),
        )
    }
}
