package com.lezi.babylog.feature.log.timeline

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.carelog.SuspectedDuplicateGrouping
import com.lezi.babylog.domain.carelog.SuspectedDuplicatePresentation
import com.lezi.babylog.domain.carelog.TimelineDuplicateRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Timeline presentation for non-destructive suspected-duplicate groups (ticket 07).
 * Pure projection seam — does not require Compose.
 */
class TimelineDuplicateGroupTest {

    @Test
    fun openGroup_defaultExpandedShowsPendingConfirmAndSources() {
        val a = record("a", 1_000L, "m1")
        val b = record("b", 2_000L, "m2")
        val groups = SuspectedDuplicateGrouping.group(listOf(a, b))
        val rows = SuspectedDuplicatePresentation.timelineRows(
            records = listOf(a, b),
            openGroups = groups,
            sourceRoleClientUuids = emptySet(),
        )
        val container = rows.first() as TimelineDuplicateRow.GroupContainer
        assertEquals(SuspectedDuplicatePresentation.PENDING_CONFIRM_LABEL, container.pendingLabel)
        assertTrue(container.expanded)
        assertTrue(rows.any { it is TimelineDuplicateRow.ExpandedSource })
    }

    @Test
    fun expandCollapse_togglesExpandedSources() {
        val a = record("a", 1_000L, "m1")
        val b = record("b", 2_000L, "m2")
        val groups = SuspectedDuplicateGrouping.group(listOf(a, b))
        val groupId = groups.single().groupId
        val collapsed = SuspectedDuplicatePresentation.timelineRows(
            records = listOf(a, b),
            openGroups = groups,
            sourceRoleClientUuids = emptySet(),
            expandedGroupIds = emptySet(),
        )
        assertEquals(1, collapsed.size)
        assertFalse((collapsed.single() as TimelineDuplicateRow.GroupContainer).expanded)
        val expanded = SuspectedDuplicatePresentation.timelineRows(
            records = listOf(a, b),
            openGroups = groups,
            sourceRoleClientUuids = emptySet(),
            expandedGroupIds = setOf(groupId),
        )
        assertTrue(expanded.any { it is TimelineDuplicateRow.ExpandedSource })
        assertEquals(3, expanded.size) // container + 2 sources
    }

    @Test
    fun afterResolution_singleDisplayOnTimeline_sourceAccessibleViaRelationNotTombstone() {
        val display = record("display", 1_000L, "m1")
        val source = record("source", 2_000L, "m2")
        val rows = SuspectedDuplicatePresentation.timelineRows(
            records = listOf(display, source),
            openGroups = emptyList(),
            sourceRoleClientUuids = setOf("source"),
        )
        assertEquals(1, rows.size)
        assertEquals(
            "display",
            (rows.single() as TimelineDuplicateRow.Ordinary).record.clientUuid,
        )
        // Source remains a live record for source-detail navigation.
        assertEquals(null, source.deletedAt)
    }

    private fun record(uuid: String, ts: Long, membership: String): Record = Record(
        id = 1,
        clientUuid = uuid,
        babyId = 1,
        type = RecordType.FORMULA,
        timestamp = ts,
        payloadJson = """{"amount_ml":100}""",
        updatedAt = ts,
        createdByMembershipId = membership,
    )
}
