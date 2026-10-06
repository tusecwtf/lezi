package com.lezi.babylog.domain.carelog

import com.lezi.babylog.core.model.Record

/**
 * Timeline / summary presentation helpers for 疑似重复组 (ticket 07).
 *
 * Pure projection over open groups + source relations — no Record mutation.
 */
object SuspectedDuplicatePresentation {
    const val PENDING_CONFIRM_LABEL: String = "待确认"
    const val SUSPECTED_DUPLICATE_LABEL: String = "疑似重复"
    const val SOURCE_DETAIL_LABEL: String = "来源详情"

    /**
     * Default timeline rows: each open group is one expandable container.
     * Spec requires **default expanded** (全部原记录可见) with 待确认; pass
     * [expandedGroupIds] to collapse some groups. Resolved source-role UUIDs
     * are hidden from the ordinary list (still reachable via [SOURCE_DETAIL_LABEL]).
     *
     * @param expandedGroupIds null → all open groups expanded (product default)
     */
    fun timelineRows(
        records: List<Record>,
        openGroups: List<SuspectedDuplicateGroup>,
        sourceRoleClientUuids: Set<String>,
        expandedGroupIds: Set<String>? = null,
        timelineIndex: DuplicateTimelineIndex = DuplicateTimelineIndex.build(
            records,
            openGroups,
            sourceRoleClientUuids,
        ),
    ): List<TimelineDuplicateRow> {
        val byUuid = records.associateBy { it.clientUuid }
        val expandedIds = expandedGroupIds ?: openGroups.map { it.groupId }.toSet()
        val rows = mutableListOf<TimelineDuplicateRow>()
        val emittedGroups = mutableSetOf<String>()

        // Preserve approximate time order using earliest member timestamp per group.
        val ordered = records
            .filter { it.deletedAt == null }
            .filter { it.clientUuid !in sourceRoleClientUuids }
            .sortedByDescending { it.timestamp }

        val seenRecords = mutableSetOf<String>()
        for (record in ordered) {
            if (record.clientUuid in seenRecords) continue
            val entry = timelineIndex[record.clientUuid]
            val group = entry.group
            if (group != null) {
                if (group.groupId in emittedGroups) continue
                emittedGroups += group.groupId
                val members = group.memberClientUuids.mapNotNull { byUuid[it] }
                members.forEach { seenRecords += it.clientUuid }
                val expanded = group.groupId in expandedIds
                rows += TimelineDuplicateRow.GroupContainer(
                    group = group,
                    members = members,
                    expanded = expanded,
                    pendingLabel = PENDING_CONFIRM_LABEL,
                )
                if (expanded) {
                    for (member in members.sortedBy { it.timestamp }) {
                        rows += TimelineDuplicateRow.ExpandedSource(
                            groupId = group.groupId,
                            record = member,
                        )
                    }
                }
            } else if (entry.role == DuplicateRecordRole.ORDINARY) {
                seenRecords += record.clientUuid
                rows += TimelineDuplicateRow.Ordinary(record)
            }
        }
        return rows
    }

    /**
     * Summary copy for a metric bound: exact value or inclusive min–max range.
     * Never presents an implicit single winner for unresolved groups.
     */
    fun formatMetricBound(bound: IntBound, unitSuffix: String = ""): String {
        val core = bound.formatRange()
        return if (unitSuffix.isEmpty()) core else "$core$unitSuffix"
    }

    fun formatMetricBound(bound: LongBound, unitSuffix: String = ""): String {
        val core = bound.formatRange()
        return if (unitSuffix.isEmpty()) core else "$core$unitSuffix"
    }

    /**
     * Author may only declare their own authored source; Owner may resolve full group.
     */
    fun availableActions(
        group: SuspectedDuplicateGroup,
        recordsByUuid: Map<String, Record>,
        currentMembershipId: String,
        isOwner: Boolean,
    ): List<DuplicateGroupAction> {
        val actions = mutableListOf<DuplicateGroupAction>()
        val selfAuthored = group.memberClientUuids.filter { uuid ->
            recordsByUuid[uuid]?.createdByMembershipId == currentMembershipId
        }
        for (uuid in selfAuthored) {
            val peers = group.memberClientUuids.filter { it != uuid }
            for (peer in peers) {
                actions += DuplicateGroupAction.AuthorDeclare(
                    recordClientUuid = uuid,
                    equivalentToClientUuid = peer,
                )
            }
        }
        if (isOwner) {
            for (display in group.memberClientUuids) {
                actions += DuplicateGroupAction.OwnerResolve(
                    displayClientUuid = display,
                    memberClientUuids = group.memberClientUuids,
                )
            }
        }
        return actions
    }
}

sealed class TimelineDuplicateRow {
    data class Ordinary(val record: Record) : TimelineDuplicateRow()

    data class GroupContainer(
        val group: SuspectedDuplicateGroup,
        val members: List<Record>,
        val expanded: Boolean,
        val pendingLabel: String,
    ) : TimelineDuplicateRow()

    data class ExpandedSource(
        val groupId: String,
        val record: Record,
    ) : TimelineDuplicateRow()
}

sealed class DuplicateGroupAction {
    data class AuthorDeclare(
        val recordClientUuid: String,
        val equivalentToClientUuid: String,
    ) : DuplicateGroupAction()

    data class OwnerResolve(
        val displayClientUuid: String,
        val memberClientUuids: List<String>,
    ) : DuplicateGroupAction()
}
