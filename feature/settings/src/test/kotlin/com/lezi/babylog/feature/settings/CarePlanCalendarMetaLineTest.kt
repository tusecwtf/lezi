package com.lezi.babylog.feature.settings

import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.SYSTEM_CALENDAR_UNSYNCED_LABEL
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class CarePlanCalendarMetaLineTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun plan(
        zoneId: String = "Asia/Shanghai",
    ) = CarePlan(
        id = 1L,
        clientUuid = "p1",
        babyId = 1L,
        type = RecordType.FORMULA,
        scheduledAt = 1_700_000_000_000L,
        scheduledZoneId = zoneId,
        status = CarePlanStatus.PENDING,
        updatedAt = 1L,
    )

    @Test
    fun unsyncedAppendsUserFacingLabel() {
        val line = carePlanCalendarMetaLine(
            plan = plan(),
            effective = CarePlanStatus.PENDING,
            deviceZone = zone,
            systemCalendarUnsynced = true,
        )
        assertTrue(line.contains(SYSTEM_CALENDAR_UNSYNCED_LABEL))
        assertTrue(line.contains("待执行"))
    }

    @Test
    fun syncedDoesNotShowUnsyncedLabel() {
        val line = carePlanCalendarMetaLine(
            plan = plan(),
            effective = CarePlanStatus.PENDING,
            deviceZone = zone,
            systemCalendarUnsynced = false,
        )
        assertFalse(line.contains(SYSTEM_CALENDAR_UNSYNCED_LABEL))
    }
}
