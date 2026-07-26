package com.lezi.babylog.feature.settings

import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.isPlanableCarePlanType
import com.lezi.babylog.domain.CustomRecordItem
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarPlanableCatalogTest {
    @Test
    fun includesEnabledBuiltInsAndCustomsExcludesHiddenAndRetired() {
        val customs = listOf(
            CustomRecordItem(id = 3, name = "抚触", iconSlot = 0, sortOrder = 0),
            CustomRecordItem(id = 4, name = "隐藏的", iconSlot = 1, sortOrder = 1),
        )
        val items = calendarPlanableItems(
            hiddenItems = setOf("pee", "custom:4"),
            customItems = customs,
        )
        val keys = items.map { it.catalogKey }
        assertFalse(keys.contains("pee"))
        // Intent-only stateful types are planable (tickets 16/17).
        assertTrue(keys.contains("sleep"))
        assertTrue(keys.contains("nursing"))
        assertFalse(keys.contains("memo"))
        assertFalse(keys.contains("other"))
        assertFalse(keys.contains("custom:4"))
        assertTrue(keys.contains("formula") || keys.contains("diary") || keys.contains("poop"))
        assertTrue(keys.contains("custom:3"))
        assertEquals(
            RecordType.CUSTOM,
            items.single { it.catalogKey == "custom:3" }.type,
        )
        assertEquals(3L, items.single { it.catalogKey == "custom:3" }.customItemId)
    }

    @Test
    fun emptyCustomStillReturnsOnlyPlanableBuiltInsIncludingNursingSleep() {
        val items = calendarPlanableItems(hiddenItems = emptySet(), customItems = emptyList())
        assertTrue(items.none { it.type == RecordType.CUSTOM })
        assertTrue(items.any { it.type == RecordType.SLEEP })
        assertTrue(items.any { it.type == RecordType.NURSING })
        assertTrue(items.all { it.type.isPlanableCarePlanType || it.type == RecordType.CUSTOM })
    }

    @Test
    fun carePlanCalendarMetaShowsLocalStatusAndOriginalZoneWhenDifferent() {
        val plan = CarePlan(
            id = 1L,
            clientUuid = "p1",
            babyId = 1L,
            type = RecordType.FORMULA,
            scheduledAt = 1_700_000_000_000L,
            scheduledZoneId = "America/New_York",
            status = CarePlanStatus.PENDING,
            updatedAt = 1L,
        )
        val deviceZone = ZoneId.of("Asia/Shanghai")
        val line = carePlanCalendarMetaLine(
            plan = plan,
            effective = CarePlanStatus.PENDING,
            deviceZone = deviceZone,
        )
        assertTrue(line.contains("待执行"))
        assertTrue(line.contains("原计划"))
        assertTrue(line.contains("America/New_York"))
        // Same zone → no dual-clock hint.
        val sameZone = carePlanCalendarMetaLine(
            plan = plan.copy(scheduledZoneId = "Asia/Shanghai"),
            effective = CarePlanStatus.MISSED,
            deviceZone = deviceZone,
        )
        assertTrue(sameZone.contains("已错过"))
        assertFalse(sameZone.contains("原计划"))
    }
}
