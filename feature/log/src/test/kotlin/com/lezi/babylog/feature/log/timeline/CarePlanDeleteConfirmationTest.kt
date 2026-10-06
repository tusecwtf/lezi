package com.lezi.babylog.feature.log.timeline
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.RecordType
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

class CarePlanDeleteConfirmationTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun carePlanCopyMentionsReminderCalendarAndNoRecord() {
        val scheduled = LocalDateTime.of(2026, 7, 29, 19, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val confirmation = carePlanDeleteConfirmation(
            planName = "用药",
            scheduledAtMillis = scheduled,
            zoneId = zone,
        )
        assertEquals("删除「用药 · 7月29日 19:00」？", confirmation.title)
        assertTrue(confirmation.message.contains("乐记提醒"))
        assertTrue(confirmation.message.contains("系统日历"))
        assertTrue(confirmation.message.contains("不会生成护理记录"))
        assertTrue(confirmation.message.contains("无法撤销"))
    }

    @Test
    fun carePlanFromEntityUsesDisplayLabel() {
        val scheduled = LocalDateTime.of(2026, 7, 22, 8, 30)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val plan = CarePlan(
            id = 1,
            clientUuid = "plan-1",
            babyId = 1,
            type = RecordType.TEMPERATURE,
            scheduledAt = scheduled,
            scheduledZoneId = zone.id,
            status = CarePlanStatus.PENDING,
            updatedAt = scheduled,
        )
        val confirmation = carePlanDeleteConfirmation(plan, zone)
        assertEquals("删除「体温 · 7月22日 08:30」？", confirmation.title)
    }

    @Test
    fun recordDeleteCopyMatchesComposer() {
        assertEquals("删除这条记录？", RECORD_DELETE_TITLE)
        assertEquals("删除后会从时间轴和汇总中移除，无法撤销。", RECORD_DELETE_IMPACT)
    }

    @Test
    fun deleteConfirmationMessageAppendsError() {
        assertEquals(
            RECORD_DELETE_IMPACT,
            deleteConfirmationMessage(RECORD_DELETE_IMPACT, error = null),
        )
        assertEquals(
            "$RECORD_DELETE_IMPACT\n\n删除失败，请重试",
            deleteConfirmationMessage(RECORD_DELETE_IMPACT, error = "删除失败，请重试"),
        )
        assertFalse(
            deleteConfirmationMessage(RECORD_DELETE_IMPACT, error = "   ").contains("\n\n"),
        )
    }
}
