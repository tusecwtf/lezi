package com.lezi.babylog.feature.timer

import org.junit.Assert.assertEquals
import org.junit.Test

class TimerUiPolicyTest {
    @Test
    fun deniedNotificationPermissionKeepsSavedRecordPromptOpenWithExplicitFeedback() {
        assertEquals(
            TimerReminderPermissionDecision.ScheduleReminder,
            timerReminderPermissionDecision(granted = true),
        )

        val denied = timerReminderPermissionDecision(granted = false)
            as TimerReminderPermissionDecision.KeepPromptOpen
        assertEquals("记录已保存、提醒未设置", denied.message)
    }
}
