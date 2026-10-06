package com.lezi.babylog.domain.carelog

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SuspectedDuplicatePresentationTest {

    @Test
    fun unresolvedGroup_defaultExpandedShowsPendingConfirmAndSources() {
        val t0 = 1_000L
        val a = formula("a", t0, "m1")
        val b = formula("b", t0 + 1_000L, "m2")
        val groups = SuspectedDuplicateGrouping.group(listOf(a, b))
        assertThat(groups).hasSize(1)
        val rows = SuspectedDuplicatePresentation.timelineRows(
            records = listOf(a, b),
            openGroups = groups,
            sourceRoleClientUuids = emptySet(),
            // null → product default: all open groups expanded
            expandedGroupIds = null,
        )
        assertThat(rows.first()).isInstanceOf(TimelineDuplicateRow.GroupContainer::class.java)
        val container = rows.first() as TimelineDuplicateRow.GroupContainer
        assertThat(container.pendingLabel).isEqualTo("待确认")
        assertThat(container.expanded).isTrue()
        assertThat(container.members.map { it.clientUuid }).containsExactly("a", "b")
        assertThat(rows.filterIsInstance<TimelineDuplicateRow.ExpandedSource>()).hasSize(2)
    }

    @Test
    fun expandedGroup_showsEachSourceWithAuthorTimeNoteValues() {
        val t0 = 1_000L
        val a = formula("a", t0, "m1").copy(note = "note-a", payloadJson = """{"amount_ml":100}""")
        val b = formula("b", t0 + 1_000L, "m2").copy(note = "note-b", payloadJson = """{"amount_ml":120}""")
        val groups = SuspectedDuplicateGrouping.group(listOf(a, b))
        val rows = SuspectedDuplicatePresentation.timelineRows(
            records = listOf(a, b),
            openGroups = groups,
            sourceRoleClientUuids = emptySet(),
            expandedGroupIds = setOf(groups.single().groupId),
        )
        assertThat(rows.first()).isInstanceOf(TimelineDuplicateRow.GroupContainer::class.java)
        val expanded = rows.filterIsInstance<TimelineDuplicateRow.ExpandedSource>()
        assertThat(expanded).hasSize(2)
        assertThat(expanded.map { it.record.clientUuid }).containsExactly("a", "b").inOrder()
        assertThat(expanded.map { it.record.note }).containsExactly("note-a", "note-b").inOrder()
    }

    @Test
    fun resolvedSourceRole_hiddenFromOrdinaryTimelineButRecordRemainsLive() {
        val display = formula("display", 1_000L, "m1")
        val source = formula("source", 2_000L, "m2")
        val rows = SuspectedDuplicatePresentation.timelineRows(
            records = listOf(display, source),
            openGroups = emptyList(),
            sourceRoleClientUuids = setOf("source"),
        )
        assertThat(rows).hasSize(1)
        assertThat((rows.single() as TimelineDuplicateRow.Ordinary).record.clientUuid)
            .isEqualTo("display")
        assertThat(source.deletedAt).isNull()
    }

    @Test
    fun authorActions_onlySelfAuthored_ownerGetsResolveOptions() {
        val a = formula("a", 1_000L, "m-self")
        val b = formula("b", 2_000L, "m-other")
        val groups = SuspectedDuplicateGrouping.group(listOf(a, b))
        val byUuid = mapOf("a" to a, "b" to b)
        val authorOnly = SuspectedDuplicatePresentation.availableActions(
            group = groups.single(),
            recordsByUuid = byUuid,
            currentMembershipId = "m-self",
            isOwner = false,
        )
        assertThat(authorOnly).containsExactly(
            DuplicateGroupAction.AuthorDeclare("a", "b"),
        )
        val owner = SuspectedDuplicatePresentation.availableActions(
            group = groups.single(),
            recordsByUuid = byUuid,
            currentMembershipId = "m-other",
            isOwner = true,
        )
        assertThat(owner.filterIsInstance<DuplicateGroupAction.AuthorDeclare>()).hasSize(1)
        assertThat(owner.filterIsInstance<DuplicateGroupAction.OwnerResolve>()).hasSize(2)
    }

    @Test
    fun boundsCopy_neverImpliesSingleWinner() {
        assertThat(SuspectedDuplicatePresentation.formatMetricBound(IntBound(100, 220), "ml"))
            .isEqualTo("100–220ml")
        assertThat(SuspectedDuplicatePresentation.formatMetricBound(IntBound(1, 1)))
            .isEqualTo("1")
    }

    @Test
    fun ordinaryDetailEditTargetsSingleSourceUuid() {
        // Unresolved group does not select a winner; detail/edit stays on explicit UUID.
        val a = formula("a", 1_000L, "m1")
        val b = formula("b", 2_000L, "m2")
        val groups = SuspectedDuplicateGrouping.group(listOf(a, b))
        val rows = SuspectedDuplicatePresentation.timelineRows(
            records = listOf(a, b),
            openGroups = groups,
            sourceRoleClientUuids = emptySet(),
            expandedGroupIds = setOf(groups.single().groupId),
        )
        val expanded = rows.filterIsInstance<TimelineDuplicateRow.ExpandedSource>()
        assertThat(expanded.map { it.record.clientUuid }.toSet()).containsExactly("a", "b")
        // Each expanded row points at one concrete record — not a group-wide edit target.
        assertThat(expanded.map { it.record.clientUuid }.distinct()).hasSize(2)
    }

    @Test
    fun projectionIndexClassifiesOpenResolvedAndOrdinaryUuids() = runTest {
        val day = LocalDate.of(2026, 8, 9)
        val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val openA = formula("open-a", start + 1_000L, "m1")
        val openB = formula("open-b", start + 2_000L, "m2")
        val source = formula("source", start + 2 * 60 * 60_000L, "m3")
        val ordinary = formula("ordinary", start + 4 * 60 * 60_000L, "m3")

        val projection = SuspectedDuplicateProjection.project(
            records = listOf(openA, openB, source, ordinary),
            startDate = day,
            dayCount = 1,
            zone = ZoneOffset.UTC,
            now = start + 24 * 60 * 60_000L,
            sourceRoleClientUuids = setOf("source"),
        )

        assertThat(projection.timelineIndex["open-a"].role)
            .isEqualTo(DuplicateRecordRole.OPEN_GROUP_MEMBER)
        assertThat(projection.timelineIndex["open-a"].group)
            .isEqualTo(projection.openGroups.single())
        assertThat(projection.timelineIndex["source"].role)
            .isEqualTo(DuplicateRecordRole.RESOLVED_SOURCE)
        assertThat(projection.timelineIndex["ordinary"].role)
            .isEqualTo(DuplicateRecordRole.ORDINARY)
    }

    private fun formula(uuid: String, ts: Long, membership: String): Record = Record(
        id = uuid.hashCode().toLong().and(0xffffL),
        clientUuid = uuid,
        babyId = 1,
        type = RecordType.FORMULA,
        timestamp = ts,
        payloadJson = """{"amount_ml":100}""",
        updatedAt = ts,
        createdByMembershipId = membership,
    )
}
