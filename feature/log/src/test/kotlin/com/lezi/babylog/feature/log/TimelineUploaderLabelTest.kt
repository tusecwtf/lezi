package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.RootPublicationState
import com.lezi.babylog.domain.TimelineCarePlanRow
import com.lezi.babylog.domain.TimelineMediaSnapshot
import com.lezi.babylog.domain.TimelineRecordRow
import com.lezi.babylog.domain.TimelineRowCapabilities
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineUploaderLabelTest {
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
            "仅本机 · 等待家庭同步",
            timelineRecordSummary("", null, "仅本机 · 等待家庭同步"),
        )
        assertEquals("120ml · 爸爸", timelineRecordSummary("120ml", "爸爸", "  "))
    }

    @Test
    fun batchMetadataPreservesTicket16CopyForZeroAndPhotoRows() {
        val cases = listOf(
            Triple(RootPublicationState.NEVER_PUBLISHED, true, "仅本机 · 等待家庭同步"),
            Triple(RootPublicationState.PREVIOUS_VERSION_PUBLISHED, true, "仅本机 · 等待更新同步"),
            Triple(RootPublicationState.CURRENT_VERSION_PUBLISHED, false, null),
        )
        val mediaVariants = listOf(
            TimelineMediaSnapshot.empty(revision = 7),
            TimelineMediaSnapshot(
                revision = 7,
                photoPaths = listOf("photos/ready.jpg"),
                photoCount = 1,
                allLocalPhotosReady = true,
            ),
        )

        for ((state, syncDirty, expected) in cases) {
            for (media in mediaVariants) {
                val record = record(id = 1).copy(syncDirty = syncDirty)
                val plan = plan(id = 2).copy(syncDirty = syncDirty)
                assertEquals(
                    expected,
                    timelineRecordPublishLabel(
                        record = record,
                        metadata = recordRow(record, state, media),
                        familyJoined = true,
                        lastSyncFailed = false,
                    ),
                )
                assertEquals(
                    expected,
                    timelineCarePlanPublishLabel(
                        plan = plan,
                        metadata = planRow(plan, state, media),
                        familyJoined = true,
                        lastSyncFailed = false,
                    ),
                )
            }
        }
    }

    private fun record(id: Long) = Record(
        id = id,
        clientUuid = "uuid-$id",
        babyId = 1,
        type = RecordType.FORMULA,
        timestamp = id * 1_000,
        payloadJson = """{"amount_ml":120}""",
        updatedAt = id * 1_000,
    )

    private fun plan(id: Long) = CarePlan(
        id = id,
        clientUuid = "plan-$id",
        babyId = 1,
        type = RecordType.FORMULA,
        scheduledAt = id * 1_000,
        scheduledZoneId = "UTC",
        payloadJson = """{"amount_ml":120}""",
        updatedAt = id * 1_000,
    )

    private fun recordRow(
        record: Record,
        state: RootPublicationState,
        media: TimelineMediaSnapshot,
    ) = TimelineRecordRow(
        revision = 7,
        record = record,
        publicationState = state,
        media = media,
        uploaderLabel = null,
        capabilities = capabilities(),
    )

    private fun planRow(
        plan: CarePlan,
        state: RootPublicationState,
        media: TimelineMediaSnapshot,
    ) = TimelineCarePlanRow(
        revision = 7,
        carePlan = plan,
        publicationState = state,
        media = media,
        capabilities = capabilities(),
    )

    private fun capabilities() = TimelineRowCapabilities(
        revision = 7,
        canEdit = true,
        canDelete = true,
        canFulfill = true,
        canSkip = true,
    )
}
