package com.lezi.babylog.sync.disasterrecovery

import com.lezi.babylog.core.database.causal.SourceRelationDao
import com.lezi.babylog.core.database.causal.SourceRelationEntity
import com.lezi.babylog.core.database.causal.SourceRelationMemberEntity
import com.lezi.babylog.core.database.causal.SourceRelationReason
import com.lezi.babylog.core.database.causal.SourceRelationRole
import com.lezi.babylog.sync.backend.DisasterRestoreSourceRelation
import com.lezi.babylog.sync.engine.pendingRecordCustomItemId
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class CapturedRestoreSourceRelations(
    val sourceRelations: List<DisasterRestoreSourceRelation>,
    val localEvidence: String,
)

/** Call inside the same Room transaction that captures the fact rows. */
internal suspend fun captureRestoreSourceRelations(dao: SourceRelationDao): CapturedRestoreSourceRelations {
    val rows = readSourceRelationRows(dao)
    return CapturedRestoreSourceRelations(rows.canonical(), rows.evidence())
}

/** A changed or incomplete current relation is unequal evidence, not permission to replace it. */
internal suspend fun readRestoreSourceRelationEvidence(dao: SourceRelationDao): String =
    readSourceRelationRows(dao).evidence()

private suspend fun readSourceRelationRows(dao: SourceRelationDao) = SourceRelationRows(
    dao.listAll(), dao.listAllMembers(), dao.listAutoAlignedDisplayClientUuids(),
)

private data class SourceRelationRows(
    val headers: List<SourceRelationEntity>,
    val members: List<SourceRelationMemberEntity>,
    val autoDisplays: List<String>,
) {
    // Retain every original header and member, including obsolete headers. No wire projection or
    // reconstructed relation can serve as equality evidence for the captured local state.
    fun evidence(): String = buildJsonObject {
        put("headers", JsonArray(headers.sortedBy { it.relationId }.map { header -> buildJsonObject {
            put("relation_id", header.relationId)
            put("display_client_uuid", header.displayClientUuid)
            put("media_retained", header.mediaRetained)
            put("reason", header.reason)
            put("mutation_id", header.mutationId)
            put("created_by_membership_id", header.createdByMembershipId)
            put("created_at", header.createdAt)
        } }))
        put("members", JsonArray(members.sortedWith(compareBy<SourceRelationMemberEntity> { it.relationId }
            .thenBy { it.recordClientUuid }.thenBy { it.role }).map { member -> buildJsonObject {
                put("relation_id", member.relationId)
                put("record_client_uuid", member.recordClientUuid)
                put("role", member.role)
            } }))
        put("auto_displays", JsonArray(autoDisplays.sorted().map(::JsonPrimitive)))
    }.toString()

    fun canonical(): List<DisasterRestoreSourceRelation> {
        require(headers.map { it.relationId }.distinct().size == headers.size) { INCOMPLETE_RELATIONS }
        val headerIds = headers.mapTo(mutableSetOf()) { it.relationId }
        require(members.all { it.relationId in headerIds }) { INCOMPLETE_RELATIONS }
        require(members.map { it.recordClientUuid }.distinct().size == members.size) { INCOMPLETE_RELATIONS }
        val byRelation = members.groupBy { it.relationId }
        val automatic = autoDisplays.toSet()
        return headers.sortedBy { it.relationId }.mapNotNull { header ->
            val component = byRelation[header.relationId].orEmpty()
            // A newer canonical component moves memberships, leaving the old header behind.
            if (component.isEmpty()) return@mapNotNull null
            require(header.relationId.isNotBlank() && header.displayClientUuid.isNotBlank() &&
                header.mediaRetained && component.size in 2..64) { INCOMPLETE_RELATIONS }
            require(header.reason in setOf(SourceRelationReason.PULL_SUMMARY,
                SourceRelationReason.AUTHOR_DECLARE, SourceRelationReason.OWNER_GROUP_RESOLVE)) { INCOMPLETE_RELATIONS }
            require(component.all { it.recordClientUuid.isNotBlank() &&
                it.role in setOf(SourceRelationRole.DISPLAY, SourceRelationRole.SOURCE) }) { INCOMPLETE_RELATIONS }
            require(component.singleOrNull { it.role == SourceRelationRole.DISPLAY }?.recordClientUuid ==
                header.displayClientUuid) { INCOMPLETE_RELATIONS }
            if (header.reason == SourceRelationReason.PULL_SUMMARY) {
                val legacyMutation = "pull-${header.relationId}"
                val fingerprint = sourceRelationFingerprint(component.map { it.recordClientUuid })
                // A display role alone does not prove an interrupted legacy pull delivered all
                // peers. Only the persisted closed-set fingerprint can authorize this capture.
                require(header.mutationId == "$legacyMutation:$fingerprint") { INCOMPLETE_RELATIONS }
            }
            DisasterRestoreSourceRelation(header.relationId, header.displayClientUuid,
                component.filter { it.role == SourceRelationRole.SOURCE }.map { it.recordClientUuid }.sorted(),
                header.displayClientUuid in automatic)
        }
    }
}

private fun sourceRelationFingerprint(memberIds: List<String>): String {
    val canonical = memberIds.sorted().joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

internal data class RestoreRelationDependencies(
    val records: Set<String>,
    val babies: Set<Long>,
    val customItems: Set<Long>,
    val wakes: Set<String>,
)

/** Only real fact-backed edges reachable from canonical relation members admit tombstones. */
internal fun restoreRelationDependencies(
    rows: CapturedRestoreRows,
    relations: List<DisasterRestoreSourceRelation>,
): RestoreRelationDependencies {
    val recordsByUuid = rows.records.uniqueDependencyIndex { it.clientUuid }
    rows.records.uniqueDependencyIndex { it.id }
    val babiesById = rows.babies.uniqueDependencyIndex { it.id }
    rows.babies.uniqueDependencyIndex { it.clientUuid }
    val customItemsById = rows.customItems.uniqueDependencyIndex { it.id }
    rows.customItems.uniqueDependencyIndex { it.clientUuid }
    val wakesByUuid = rows.wakes.uniqueDependencyIndex { it.clientUuid }
    rows.wakes.uniqueDependencyIndex { it.id }
    val records = linkedSetOf<String>()
    val babies = linkedSetOf<Long>()
    val customItems = linkedSetOf<Long>()
    val wakes = linkedSetOf<String>()
    val pending = ArrayDeque<String>()
    relations.forEach { relation ->
        pending.addLast(relation.displayClientUuid)
        relation.sourceClientUuids.forEach(pending::addLast)
    }
    while (pending.isNotEmpty()) {
        val uuid = pending.removeFirst()
        if (!records.add(uuid)) continue
        val record = requireNotNull(recordsByUuid[uuid]) { INCOMPLETE_RELATIONS }
        requireNotNull(babiesById[record.babyId]) { INCOMPLETE_RELATIONS }
        babies += record.babyId
        pendingRecordCustomItemId(record)?.let { customId ->
            requireNotNull(customItemsById[customId]) { INCOMPLETE_RELATIONS }
            customItems += customId
        }
        if (record.type == "sleep") record.effectiveWakeObservationClientUuid?.let { wakeUuid ->
            val wake = requireNotNull(wakesByUuid[wakeUuid]) { INCOMPLETE_RELATIONS }
            require(wake.sleepRecordClientUuid == uuid) { INCOMPLETE_RELATIONS }
            wakes += wakeUuid
            pending.addLast(wake.sleepRecordClientUuid)
        }
    }
    return RestoreRelationDependencies(records, babies, customItems, wakes)
}

private inline fun <T, K> List<T>.uniqueDependencyIndex(key: (T) -> K): Map<K, T> = buildMap {
    for (row in this@uniqueDependencyIndex) {
        val identity = key(row)
        require(!containsKey(identity)) { INCOMPLETE_RELATIONS }
        put(identity, row)
    }
}

private const val INCOMPLETE_RELATIONS = "本机来源关系尚未完整同步，无法安全恢复"
