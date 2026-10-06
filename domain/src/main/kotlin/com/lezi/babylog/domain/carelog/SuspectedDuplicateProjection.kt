package com.lezi.babylog.domain.carelog

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordTime
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Deep in-process module for the unresolved suspected-duplicate read projection.
 *
 * Its interface accepts a target natural-day window. The implementation owns the
 * exact instant halo, grouping, whole-window interpretation bounds, and the O(1)
 * UUID lookup used by timeline presentation. Source relations remain live facts,
 * but source-role records are excluded from the ordinary projection.
 */
object SuspectedDuplicateProjection {
    suspend fun project(
        records: List<Record>,
        startDate: LocalDate,
        dayCount: Int,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = RecordTime.currentTimeMillis(),
        sourceRoleClientUuids: Set<String> = emptySet(),
        factEndExclusive: Long? = null,
    ): SuspectedDuplicateProjectionResult {
        require(dayCount > 0) { "dayCount must be positive" }
        val context = currentCoroutineContext()
        val targetStart = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val naturalTargetEnd = startDate.plusDays(dayCount.toLong())
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        val targetEnd = factEndExclusive ?: naturalTargetEnd
        require(targetEnd > targetStart && targetEnd <= naturalTargetEnd) {
            "factEndExclusive must be inside the target natural-day window"
        }
        val candidateStart = saturatingSubtract(
            targetStart,
            SuspectedDuplicateGrouping.WINDOW_MS,
        )
        val candidateEnd = saturatingAdd(targetEnd, SuspectedDuplicateGrouping.WINDOW_MS)
        val projected = ArrayList<Record>(records.size)
        val candidates = ArrayList<Record>()
        records.forEach { record ->
            context.ensureActive()
            if (record.clientUuid !in sourceRoleClientUuids) {
                projected += record
                if (record.timestamp >= candidateStart && record.timestamp < candidateEnd) {
                    candidates += record
                }
            }
        }
        val byUuid = HashMap<String, Record>(candidates.size)
        candidates.forEach { record ->
            context.ensureActive()
            byUuid[record.clientUuid] = record
        }
        val groups = SuspectedDuplicateGrouping.group(
            records = candidates,
            excludedClientUuids = emptySet(),
            checkActive = context::ensureActive,
        ).filter { group ->
            context.ensureActive()
            group.memberClientUuids.any { uuid ->
                context.ensureActive()
                val timestamp = byUuid[uuid]?.timestamp ?: return@any false
                timestamp >= targetStart && timestamp < targetEnd
            }
        }
        val bounds = SuspectedDuplicateBounds.range(
            records = projected.filter { record ->
                context.ensureActive()
                record.timestamp < targetEnd
            },
            openGroups = groups,
            startDate = startDate,
            dayCount = dayCount,
            zone = zone,
            now = minOf(now, targetEnd - 1L),
            checkActive = context::ensureActive,
        )
        return SuspectedDuplicateProjectionResult(
            projectedRecords = projected,
            openGroups = groups,
            bounds = bounds,
            timelineIndex = DuplicateTimelineIndex.build(
                records,
                groups,
                sourceRoleClientUuids,
                context::ensureActive,
            ),
        )
    }
}

data class SuspectedDuplicateProjectionResult(
    val projectedRecords: List<Record>,
    val openGroups: List<SuspectedDuplicateGroup>,
    val bounds: CareRangeBounds,
    val timelineIndex: DuplicateTimelineIndex,
)

enum class DuplicateRecordRole {
    ORDINARY,
    OPEN_GROUP_MEMBER,
    RESOLVED_SOURCE,
}

data class DuplicateTimelineEntry(
    val role: DuplicateRecordRole,
    val group: SuspectedDuplicateGroup? = null,
)

/** Immutable UUID -> group/role lookup; timeline rows never scan all groups. */
class DuplicateTimelineIndex private constructor(
    private val entries: Map<String, DuplicateTimelineEntry>,
) {
    operator fun get(clientUuid: String): DuplicateTimelineEntry =
        entries[clientUuid] ?: DuplicateTimelineEntry(DuplicateRecordRole.ORDINARY)

    companion object {
        internal fun build(
            records: List<Record>,
            openGroups: List<SuspectedDuplicateGroup>,
            sourceRoleClientUuids: Set<String>,
            checkActive: () -> Unit = {},
        ): DuplicateTimelineIndex {
            val entries = HashMap<String, DuplicateTimelineEntry>(records.size)
            records.forEach { record ->
                checkActive()
                entries[record.clientUuid] = DuplicateTimelineEntry(
                    role = if (record.clientUuid in sourceRoleClientUuids) {
                        DuplicateRecordRole.RESOLVED_SOURCE
                    } else {
                        DuplicateRecordRole.ORDINARY
                    },
                )
            }
            openGroups.forEach { group ->
                checkActive()
                group.memberClientUuids.forEach { uuid ->
                    checkActive()
                    entries[uuid] = DuplicateTimelineEntry(
                        role = DuplicateRecordRole.OPEN_GROUP_MEMBER,
                        group = group,
                    )
                }
            }
            return DuplicateTimelineIndex(entries)
        }
    }
}
