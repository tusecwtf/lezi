package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.RecordType
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarePlanDeleteConfirmationTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val scheduledAt = ZonedDateTime.of(2026, 7, 28, 9, 5, 0, 0, shanghai)
        .toInstant()
        .toEpochMilli()

    @Test
    fun builtInPlanNamesTheTargetAndScheduledTime() {
        val draft = QuickRecordDraft.create(RecordType.BATH, scheduledAt).copy(
            carePlanId = 42L,
            editCarePlan = true,
        )

        val confirmation = carePlanDeleteConfirmation(draft, shanghai)

        assertEquals("删除「洗澡 · 7月28日 09:05」？", confirmation.title)
    }

    @Test
    fun customPlanUsesItsVisibleName() {
        val draft = QuickRecordDraft.create(RecordType.CUSTOM, scheduledAt).copy(
            carePlanId = 43L,
            editCarePlan = true,
            customTitle = "补充维生素 D",
        )

        val confirmation = carePlanDeleteConfirmation(draft, shanghai)

        assertEquals("删除「补充维生素 D · 7月28日 09:05」？", confirmation.title)
    }

    @Test
    fun impactCopyExplainsSharedTodoReminderCalendarAndIrreversibility() {
        val draft = QuickRecordDraft.create(RecordType.BATH, scheduledAt).copy(
            carePlanId = 44L,
            editCarePlan = true,
        )

        val copy = carePlanDeleteConfirmation(draft, shanghai).message

        assertTrue(copy.contains("家庭共享"))
        assertTrue(copy.contains("待办"))
        assertTrue(copy.contains("取消乐记提醒"))
        assertTrue(copy.contains("尝试移除对应日程"))
        assertTrue(copy.contains("不会生成护理记录"))
        assertTrue(copy.contains("无法撤销"))
        assertFalse(copy.contains("一定会从系统日历删除"))
    }
}
