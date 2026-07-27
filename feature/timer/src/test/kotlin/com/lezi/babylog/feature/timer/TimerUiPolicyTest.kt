package com.lezi.babylog.feature.timer

import org.junit.Assert.assertEquals
import org.junit.Test

class TimerUiPolicyTest {
    @Test
    fun landscapeAndLargeTextUseScrollableContentWhileTallPortraitKeepsSpaciousLayout() {
        assertEquals(
            TimerViewportMode.Scrollable,
            timerViewportMode(screenHeightDp = 411, fontScale = 1.25f),
        )
        assertEquals(
            TimerViewportMode.Scrollable,
            timerViewportMode(screenHeightDp = 600, fontScale = 1.25f),
        )
        assertEquals(
            TimerViewportMode.Spacious,
            timerViewportMode(screenHeightDp = 800, fontScale = 1f),
        )
    }

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
