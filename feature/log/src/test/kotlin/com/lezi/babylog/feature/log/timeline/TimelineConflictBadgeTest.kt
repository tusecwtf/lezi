package com.lezi.babylog.feature.log.timeline

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.RootPublicationState
import com.lezi.babylog.domain.carelog.SleepPresentation
import com.lezi.babylog.domain.carelog.conflictResolverSelectablePaths
import com.lezi.babylog.feature.log.timelineRecordPublishLabel
import com.lezi.babylog.feature.log.timelineRecordSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineConflictBadgeTest {
    @Test
    fun openConflict_showsConflictPendingNotSynced() {
        val record = Record(
            id = 1,
            clientUuid = "r1",
            babyId = 1,
            type = RecordType.FORMULA,
            timestamp = 1_000L,
            payloadJson = """{"amount_ml":120}""",
            updatedAt = 1_000L,
            syncDirty = false,
            familyPublishedUpdatedAt = 1_000L,
            openConflictId = "conflict-1",
        )
        assertEquals(
            SleepPresentation.CONFLICT_PENDING_LABEL,
            timelineRecordPublishLabel(
                record = record,
                metadata = null,
                familyJoined = true,
                lastSyncFailed = false,
            ),
        )
        assertEquals(
            "120ml · 冲突待解决",
            timelineRecordSummary("120ml", null, timelineRecordPublishLabel(record, null, true, false)),
        )
    }

    @Test
    fun noConflict_fallsBackToOrdinaryPublishChrome() {
        val record = Record(
            id = 1,
            clientUuid = "r1",
            babyId = 1,
            type = RecordType.FORMULA,
            timestamp = 1_000L,
            payloadJson = """{"amount_ml":120}""",
            updatedAt = 1_000L,
            syncDirty = true,
            openConflictId = null,
        )
        // Offline / not joined: no publish chrome from dirty alone without family.
        assertNull(
            timelineRecordPublishLabel(
                record = record,
                metadata = null,
                familyJoined = false,
                lastSyncFailed = false,
            ),
        )
    }

    @Test
    fun resolver_onlyShowsRealConflictPaths() {
        assertEquals(
            listOf("/media/m1", "/note"),
            conflictResolverSelectablePaths(
                conflictingPaths = listOf("/note", "/timestamp", "/media/m1"),
                autoMergedPaths = listOf("/timestamp"),
            ),
        )
    }
}
