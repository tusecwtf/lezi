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
        UploaderMemberRef(
            "device-a",
            "妈妈",
            FamilyRole.Owner,
            isSelf = true,
            membershipId = "membership-a",
        ),
        UploaderMemberRef(
            "device-b",
            "爸爸",
            FamilyRole.Member,
            isSelf = false,
            membershipId = "membership-b",
        ),
        UploaderMemberRef("device-c", null, FamilyRole.Member, isSelf = false),
    )

    @Test
    fun membershipAuthorDrivesTimelineEvenWhenLegacyDeviceConflicts() {
        val records = listOf(
            record(
                id = 1,
                deviceId = "device-b",
                membershipId = "membership-a",
            ),
            record(
                id = 2,
                deviceId = "device-a",
                membershipId = "membership-b",
            ),
        )

        val labels = buildUploaderLabels(
            records = records,
            selfDeviceId = "device-a",
            isFamilyJoined = true,
            members = members,
            selfMembershipId = "membership-a",
        )

        assertFalse(labels.containsKey(1L))
        assertEquals("爸爸", labels[2L])
    }

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

    @Test
    fun timelineSummaryAppendsMutationPublishChromeWhenPresent() {
        assertEquals(
            "120ml · 仅本机 · 等待更新同步",
            timelineRecordSummary("120ml", null, "仅本机 · 等待更新同步"),
        )
        assertEquals(
            "120ml · 爸爸 · 仅本机 · 更新同步失败",
            timelineRecordSummary("120ml", "爸爸", "仅本机 · 更新同步失败"),
        )
        assertEquals(
            "仅本机 · 等待照片同步",
            timelineRecordSummary("", null, "仅本机 · 等待照片同步"),
        )
        // Blank publish chrome does not pad the summary.
        assertEquals("120ml · 爸爸", timelineRecordSummary("120ml", "爸爸", "  "))
    }

    private fun record(
        id: Long,
        deviceId: String?,
        membershipId: String = "",
    ) = Record(
        id = id,
        clientUuid = "uuid-$id",
        babyId = 1,
        type = RecordType.FORMULA,
        timestamp = id * 1_000,
        createdByUserId = 1,
        createdByMembershipId = membershipId,
        createdByDeviceId = deviceId,
        payloadJson = """{"amount_ml":120}""",
        updatedAt = id * 1_000,
    )
}
