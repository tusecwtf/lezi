package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.sync.FamilyRole
import com.lezi.babylog.sync.UploaderMemberRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineUploaderLabelTest {
    private val members = listOf(
        UploaderMemberRef("device-a", "妈妈", FamilyRole.Owner, isSelf = true),
        UploaderMemberRef("device-b", "爸爸", FamilyRole.Member, isSelf = false),
        UploaderMemberRef("device-c", null, FamilyRole.Member, isSelf = false),
    )

    @Test
    fun hidesUploaderWhenNotJoinedOrSelf() {
        val records = listOf(
            record(1, "device-a"),
            record(2, "device-b"),
        )
        assertTrue(
            buildUploaderLabels(records, "device-a", isFamilyJoined = false, members).isEmpty(),
        )
        val joinedSelf = buildUploaderLabels(records, "device-a", isFamilyJoined = true, members)
        assertFalse(joinedSelf.containsKey(1L))
        assertEquals("爸爸", joinedSelf[2L])
    }

    @Test
    fun mapsNonSelfToCurrentMembershipNameWithFallback() {
        val records = listOf(
            record(1, "device-b"),
            record(2, "device-c"),
            record(3, "unknown"),
            record(4, null),
        )
        val labels = buildUploaderLabels(records, "device-a", isFamilyJoined = true, members)
        assertEquals("爸爸", labels[1L])
        assertEquals("家庭成员", labels[2L])
        assertEquals("家人", labels[3L])
        assertEquals("家人", labels[4L])
        labels.values.forEach { label ->
            assertFalse(label.contains("device"))
            assertFalse(label == "我（本机）")
        }
    }

    @Test
    fun timelineSummaryAppendsUploaderOnlyWhenPresent() {
        assertEquals("120ml", timelineRecordSummary("120ml", null))
        assertEquals("120ml · 爸爸", timelineRecordSummary("120ml", "爸爸"))
        assertEquals("爸爸", timelineRecordSummary("", "爸爸"))
        assertEquals("", timelineRecordSummary("", null))
    }

    private fun record(id: Long, deviceId: String?) = Record(
        id = id,
        clientUuid = "uuid-$id",
        babyId = 1,
        type = RecordType.FORMULA,
        timestamp = id * 1_000,
        createdByUserId = 1,
        createdByDeviceId = deviceId,
        payloadJson = """{"amount_ml":120}""",
        updatedAt = id * 1_000,
    )
}
