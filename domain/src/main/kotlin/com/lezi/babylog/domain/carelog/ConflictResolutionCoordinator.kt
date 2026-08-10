package com.lezi.babylog.domain.carelog

import com.lezi.babylog.core.common.newClientUuid
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheDao
import com.lezi.babylog.core.database.causal.ConflictSummaryDao
import com.lezi.babylog.core.database.causal.ConflictSummaryEntity
import com.lezi.babylog.core.database.causal.WakeObservationDao
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.CausalMediaItem
import com.lezi.babylog.sync.backend.ConflictResolveRequest
import com.lezi.babylog.sync.backend.ConflictResolveResult
import com.lezi.babylog.sync.backend.ConflictResolveSummary
import com.lezi.babylog.sync.conflict.ConflictOutcome
import com.lezi.babylog.sync.conflict.ConflictSnapshot
import com.lezi.babylog.sync.conflict.ConflictSnapshotProjection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Offline-capable conflict summary for timeline badge/list.
 * Not a second stable fact — only a presentation handle into detail/resolve.
 */
data class OpenConflictSummary(
    val conflictId: String,
    val entityType: String,
    val clientUuid: String,
    val stableVersionId: String,
    val baseVersionId: String? = null,
    val kind: String,
    val branchVersionIds: List<String> = emptyList(),
    val updatedAt: Long,
)

/**
 * On-demand conflict detail for the resolver UI.
 * [conflictingPaths] are the only paths the user may choose; auto-merged are frozen.
 */
data class ConflictResolverDetail(
    val conflictId: String,
    val stableVersionId: String,
    val stableRootJson: String,
    val stableMedia: List<CausalMediaItem> = emptyList(),
    val baseRootJson: String? = null,
    val branchesJson: String,
    val conflictingPaths: List<String>,
    val autoMergedJson: String = "{}",
    val branchVersionIds: List<String> = emptyList(),
    val cachedAt: Long,
) {
    /** Paths shown in the resolver — excludes equal/auto-merged paths by construction. */
    val selectablePaths: List<String> get() = conflictingPaths
}

sealed class ConflictResolveOutcome {
    data class Accepted(val stableVersionId: String) : ConflictResolveOutcome()

    /**
     * CAS race: server retained prior state and returned the latest summary/detail.
     * Caller must keep the user's draft choices and refresh the presented diff.
     */
    data class CasMismatch(
        val refreshed: ConflictResolverDetail?,
        val summary: OpenConflictSummary?,
        val message: String = "冲突内容已更新，请根据最新差异重新确认",
    ) : ConflictResolveOutcome()

    data class Rejected(val code: String, val message: String) : ConflictResolveOutcome()
}

/**
 * CareLog seam for open conflict badge, detail load, and CAS resolution.
 *
 * Local save never waits on this path. Detail is on-demand; resolution uses
 * expected stable + full branch set (wire §8.2).
 *
 * On Accepted: apply server stable root + clear openConflictId in one Room
 * transaction, drop conflict rows, then request Foreground so peers converge
 * (LocalWrite never advances the pull cursor).
 */
internal class ConflictResolutionCoordinator(
    private val conflictSummaryDao: ConflictSummaryDao,
    private val conflictSnapshotCacheDao: ConflictSnapshotCacheDao,
    private val syncPort: SyncPort,
    private val recordDao: RecordDao,
    private val wakeObservationDao: WakeObservationDao,
    private val babyDao: BabyDao,
    private val carePlanDao: CarePlanDao,
    private val customItemDao: CustomItemDao,
    private val transactionRunner: DatabaseTransactionRunner,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val snapshotProjection = ConflictSnapshotProjection(
        summaries = conflictSummaryDao,
        snapshots = conflictSnapshotCacheDao,
        transactions = transactionRunner,
    )

    fun observeOpenSummaries(): Flow<List<OpenConflictSummary>> =
        conflictSummaryDao.observeOpen().map { rows -> rows.map { it.toSummary(json) } }

    suspend fun listOpenSummaries(): List<OpenConflictSummary> =
        conflictSummaryDao.listOpen().map { it.toSummary(json) }

    suspend fun summaryForRoot(entityType: String, clientUuid: String): OpenConflictSummary? =
        conflictSummaryDao.listForRoot(entityType, clientUuid)
            .firstOrNull { it.status == "open" }
            ?.toSummary(json)

    /**
     * Load detail for resolver. Prefers fresh network detail when available;
     * falls back to the same typed, complete snapshot persisted below the UI seam.
     */
    suspend fun loadDetail(conflictId: String, forceRefresh: Boolean = true): ConflictResolverDetail? {
        if (forceRefresh) {
            val fetched = runCatching { syncPort.fetchConflictSnapshot(conflictId) }.getOrNull()
            if (fetched?.conflictId == conflictId) {
                snapshotProjection.replaceComplete(fetched)
                return fetched.toResolverDetail()
            }
        }
        return snapshotProjection.read(conflictId)?.toResolverDetail()
    }

    /**
     * Submit resolution with expected stable + complete branch set.
     * On CAS mismatch, refreshes detail/summary and returns [ConflictResolveOutcome.CasMismatch]
     * so UI keeps draft choices.
     *
     * Fails closed when [expectedStableVersion] is blank — offline callers must
     * obtain CAS inputs from [loadDetail] (network or summary-paired cache).
     */
    suspend fun resolve(
        conflictId: String,
        expectedStableVersion: String,
        expectedBranchVersions: List<String>,
        resolvedRootJson: String,
        resolvedMedia: List<CausalMediaItem>,
        conflictChoices: Map<String, JsonElement>,
        resolutionMutationId: String = newClientUuid(),
    ): ConflictResolveOutcome {
        if (expectedStableVersion.isBlank()) {
            return ConflictResolveOutcome.Rejected(
                code = "missing_cas_identity",
                message = "缺少冲突稳定版本，请刷新后再解决",
            )
        }
        val request = ConflictResolveRequest(
            expectedStableVersion = expectedStableVersion,
            expectedBranchVersions = expectedBranchVersions.sorted(),
            resolvedRootJson = resolvedRootJson,
            resolvedMedia = resolvedMedia,
            resolutionMutationId = resolutionMutationId,
            conflictChoices = conflictChoices,
        )
        val result = runCatching {
            syncPort.resolveConflict(conflictId, request)
        }.getOrElse { error ->
            return ConflictResolveOutcome.Rejected(
                code = "transport",
                message = error.message ?: "提交失败，请稍后重试",
            )
        }
        return when (result) {
            is ConflictResolveResult.Accepted -> {
                val summary = conflictSummaryDao.get(conflictId)
                val applied = transactionRunner.run {
                    val cleared = if (summary != null) {
                        applyAcceptedStable(
                            entityType = summary.entityType,
                            clientUuid = summary.clientUuid,
                            conflictId = conflictId,
                            stableVersionId = result.stableVersionId,
                            stableRootJson = result.stableRootJson,
                        )
                    } else {
                        // No local summary: nothing to project; still drop cache rows.
                        true
                    }
                    // Server closed this conflict_id — drop local open rows either way.
                    conflictSummaryDao.delete(conflictId)
                    conflictSnapshotCacheDao.delete(conflictId)
                    cleared
                }
                // Peers and residual projection need a full cycle (LocalWrite skips pull).
                syncPort.requestSync(SyncTrigger.Foreground)
                if (!applied) {
                    return ConflictResolveOutcome.Rejected(
                        code = "local_apply_failed",
                        message = "冲突已在服务器解决，但本机投影未能对齐，请下拉同步",
                    )
                }
                ConflictResolveOutcome.Accepted(result.stableVersionId)
            }
            is ConflictResolveResult.CasMismatch -> {
                val detail = result.detail
                val summary = result.summary
                if (detail?.conflictId?.let { it != conflictId } == true ||
                    summary?.conflictId?.let { it != conflictId } == true ||
                    (detail != null && summary != null && !detail.matches(summary))
                ) {
                    return ConflictResolveOutcome.Rejected(
                        code = "transport_mismatch",
                        message = "服务器冲突详情与摘要不一致，请稍后重试",
                    )
                }
                if (detail != null) {
                    // The projection owns both the snapshot and its derived summary atomically.
                    snapshotProjection.replaceComplete(detail)
                } else {
                    summary?.let {
                        conflictSummaryDao.upsert(
                            ConflictSummaryEntity(
                                conflictId = it.conflictId,
                                entityType = it.entityType,
                                clientUuid = it.clientUuid,
                                baseVersionId = it.baseVersionId,
                                stableVersionId = it.stableVersionId,
                                status = "open",
                                kind = it.kind,
                                branchVersionIdsJson = encodeStringArray(it.branchVersionIds),
                                updatedAt = it.updatedAt,
                            ),
                        )
                    }
                }
                ConflictResolveOutcome.CasMismatch(
                    refreshed = detail?.toResolverDetail()
                        ?: snapshotProjection.read(conflictId)?.toResolverDetail(),
                    summary = summary?.let {
                        OpenConflictSummary(
                            conflictId = it.conflictId,
                            entityType = it.entityType,
                            clientUuid = it.clientUuid,
                            stableVersionId = it.stableVersionId,
                            baseVersionId = it.baseVersionId,
                            kind = it.kind,
                            branchVersionIds = it.branchVersionIds,
                            updatedAt = it.updatedAt,
                        )
                    },
                )
            }
            is ConflictResolveResult.Rejected ->
                ConflictResolveOutcome.Rejected(result.code, result.message)
        }
    }

    /**
     * Clear openConflictId for the root when it still points at [conflictId], advance
     * baseVersion to the resolved stable, and project server stable root business fields.
     * Fail closed (return false) when the local root is missing or linked to another conflict.
     */
    private suspend fun applyAcceptedStable(
        entityType: String,
        clientUuid: String,
        conflictId: String,
        stableVersionId: String,
        stableRootJson: String,
    ): Boolean {
        if (entityType.isBlank() || clientUuid.isBlank() || stableVersionId.isBlank()) {
            return false
        }
        val root = runCatching {
            json.parseToJsonElement(stableRootJson).jsonObject
        }.getOrNull()
        return when (entityType) {
            "record" -> {
                val existing = recordDao.getByClientUuid(clientUuid) ?: return false
                if (existing.openConflictId != null && existing.openConflictId != conflictId) {
                    return false
                }
                val note = if (root?.containsKey("note") == true) {
                    root.stringOrNull("note")
                } else {
                    existing.note
                }
                val timestamp = root?.get("timestamp")?.jsonPrimitive?.longOrNull
                    ?: existing.timestamp
                val endTimestamp = if (root?.containsKey("end_timestamp") == true) {
                    root["end_timestamp"]?.let {
                        if (it is JsonNull) null else it.jsonPrimitive.longOrNull
                    }
                } else {
                    existing.endTimestamp
                }
                val updatedAt = root?.get("updated_at")?.jsonPrimitive?.longOrNull
                    ?: existing.updatedAt
                val payload = root?.get("payload_json") as? JsonObject
                recordDao.update(
                    existing.copy(
                        note = note,
                        timestamp = timestamp,
                        endTimestamp = endTimestamp,
                        payloadJson = payload?.toString() ?: existing.payloadJson,
                        updatedAt = updatedAt,
                        baseVersion = stableVersionId,
                        mutationId = if (existing.syncDirty) existing.mutationId else null,
                        syncDirty = existing.syncDirty,
                        openConflictId = null,
                        localBranchVersionId = null,
                        effectiveWakeObservationClientUuid = if (
                            root?.containsKey("effective_wake_observation_client_uuid") == true
                        ) {
                            root.stringOrNull("effective_wake_observation_client_uuid")
                        } else {
                            existing.effectiveWakeObservationClientUuid
                        },
                    ),
                )
                true
            }
            "wake_observation" -> {
                val existing = wakeObservationDao.getByClientUuid(clientUuid) ?: return false
                if (existing.openConflictId != null && existing.openConflictId != conflictId) {
                    return false
                }
                val wakeTs = root?.get("wake_timestamp")?.jsonPrimitive?.longOrNull
                    ?: existing.wakeTimestamp
                val note = if (root?.containsKey("note") == true) {
                    root.stringOrNull("note")
                } else {
                    existing.note
                }
                val withdrawn = root?.get("withdrawn")?.jsonPrimitive?.contentOrNull
                    ?.toBooleanStrictOrNull()
                    ?: existing.withdrawn
                val updatedAt = root?.get("updated_at")?.jsonPrimitive?.longOrNull
                    ?: existing.updatedAt
                wakeObservationDao.update(
                    existing.copy(
                        wakeTimestamp = wakeTs,
                        note = note,
                        withdrawn = withdrawn,
                        updatedAt = updatedAt,
                        baseVersion = stableVersionId,
                        mutationId = if (existing.syncDirty) existing.mutationId else null,
                        syncDirty = existing.syncDirty,
                        openConflictId = null,
                        localBranchVersionId = null,
                    ),
                )
                true
            }
            "baby" -> {
                val existing = babyDao.getByClientUuid(clientUuid) ?: return false
                if (existing.openConflictId != null && existing.openConflictId != conflictId) {
                    return false
                }
                val nickname = root?.stringOrNull("nickname") ?: existing.nickname
                val sex = if (root?.containsKey("sex") == true) {
                    root.stringOrNull("sex")
                } else {
                    existing.sex
                }
                val avatar = if (root?.containsKey("avatar_media_uuid") == true) {
                    root.stringOrNull("avatar_media_uuid")
                } else {
                    existing.avatarMediaUuid
                }
                val updatedAt = root?.get("updated_at")?.jsonPrimitive?.longOrNull
                    ?: existing.updatedAt
                babyDao.update(
                    existing.copy(
                        nickname = nickname,
                        sex = sex,
                        avatarMediaUuid = avatar,
                        updatedAt = updatedAt,
                        baseVersion = stableVersionId,
                        mutationId = if (existing.syncDirty) existing.mutationId else null,
                        syncDirty = existing.syncDirty,
                        openConflictId = null,
                        localBranchVersionId = null,
                    ),
                )
                true
            }
            "care_plan" -> {
                val existing = carePlanDao.getByClientUuid(clientUuid) ?: return false
                if (existing.openConflictId != null && existing.openConflictId != conflictId) {
                    return false
                }
                val note = if (root?.containsKey("note") == true) {
                    root.stringOrNull("note")
                } else {
                    existing.note
                }
                val status = root?.stringOrNull("status") ?: existing.status
                val updatedAt = root?.get("updated_at")?.jsonPrimitive?.longOrNull
                    ?: existing.updatedAt
                val payload = root?.get("payload_json") as? JsonObject
                carePlanDao.update(
                    existing.copy(
                        note = note,
                        status = status,
                        payloadJson = payload?.toString() ?: existing.payloadJson,
                        updatedAt = updatedAt,
                        baseVersion = stableVersionId,
                        mutationId = if (existing.syncDirty) existing.mutationId else null,
                        syncDirty = existing.syncDirty,
                        openConflictId = null,
                        localBranchVersionId = null,
                    ),
                )
                true
            }
            "custom_item" -> {
                val existing = customItemDao.getByClientUuid(clientUuid) ?: return false
                if (existing.openConflictId != null && existing.openConflictId != conflictId) {
                    return false
                }
                val name = root?.stringOrNull("name") ?: existing.name
                val iconSlot = root?.get("icon_slot")?.jsonPrimitive?.longOrNull?.toInt()
                    ?: existing.iconSlot
                val updatedAt = root?.get("updated_at")?.jsonPrimitive?.longOrNull
                    ?: existing.updatedAt
                customItemDao.update(
                    existing.copy(
                        name = name,
                        iconSlot = iconSlot,
                        updatedAt = updatedAt,
                        baseVersion = stableVersionId,
                        mutationId = if (existing.syncDirty) existing.mutationId else null,
                        syncDirty = existing.syncDirty,
                        openConflictId = null,
                        localBranchVersionId = null,
                    ),
                )
                true
            }
            else -> false
        }
    }

}

private fun ConflictSnapshot.matches(summary: ConflictResolveSummary): Boolean =
    conflictId == summary.conflictId &&
        entityType.wireName == summary.entityType &&
        clientUuid == summary.clientUuid &&
        stable.versionId == summary.stableVersionId &&
        stable.baseVersion == summary.baseVersionId &&
        (if (branches.isEmpty()) "tombstone_restore" else "concurrent") == summary.kind &&
        branchVersionIds == summary.branchVersionIds &&
        maxOf(
            stable.receivedAt,
            branches.maxOfOrNull { it.receivedAt } ?: Long.MIN_VALUE,
        ) == summary.updatedAt

private fun ConflictSummaryEntity.toSummary(json: Json): OpenConflictSummary =
    OpenConflictSummary(
        conflictId = conflictId,
        entityType = entityType,
        clientUuid = clientUuid,
        stableVersionId = stableVersionId,
        baseVersionId = baseVersionId,
        kind = kind,
        branchVersionIds = decodeStringArray(branchVersionIdsJson, json),
        updatedAt = updatedAt,
    )

private fun ConflictSnapshot.toResolverDetail(): ConflictResolverDetail =
    ConflictResolverDetail(
        conflictId = conflictId,
        stableVersionId = stable.versionId,
        stableRootJson = stable.root.canonical.toString(),
        stableMedia = stable.media,
        baseRootJson = null,
        branchesJson = buildJsonArray {
            branches.forEach { branch ->
                add(
                    buildJsonObject {
                        put("branch_version_id", branch.versionId)
                        put("root", branch.root.canonical)
                        put(
                            "media",
                            buildJsonArray {
                                branch.media.forEach { add(it.toJsonElement()) }
                            },
                        )
                        put("deleted", branch.deleted)
                        put("mutation_id", branch.mutationId)
                        put("actor_id", branch.actorId)
                        put("device_id", branch.deviceId)
                        put("received_at", branch.receivedAt)
                    },
                )
            }
        }.toString(),
        conflictingPaths = conflictingPaths,
        autoMergedJson = buildJsonObject {
            autoMerged.forEach { merged ->
                put(
                    merged.path,
                    when (val outcome = merged.outcome) {
                        is ConflictOutcome.Set -> outcome.value
                        ConflictOutcome.Remove -> JsonNull
                    },
                )
            }
        }.toString(),
        branchVersionIds = branchVersionIds,
        cachedAt = maxOf(
            stable.receivedAt,
            branches.maxOfOrNull { it.receivedAt } ?: Long.MIN_VALUE,
        ),
    )

private fun encodeStringArray(values: List<String>): String =
    buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }.toString()

private fun decodeStringArray(raw: String, json: Json): List<String> {
    if (raw.isBlank()) return emptyList()
    return runCatching {
        json.parseToJsonElement(raw).jsonArray.mapNotNull { el ->
            el.jsonPrimitive.contentOrNull
        }
    }.getOrDefault(emptyList())
}

private fun JsonObject.stringOrNull(key: String): String? =
    when (val value = get(key)) {
        null, JsonNull -> null
        is JsonPrimitive -> value.contentOrNull
        else -> null
    }

/**
 * Pure presentation: only real conflicting paths; hide equal/auto-merged fields.
 * Media conflict paths stay when either side differs.
 */
fun conflictResolverSelectablePaths(
    conflictingPaths: List<String>,
    autoMergedPaths: Collection<String> = emptyList(),
): List<String> {
    val auto = autoMergedPaths.toSet()
    return conflictingPaths
        .filter { it !in auto }
        .distinct()
        .sorted()
}

/** True when a path is a media manifest member (`/media/{uuid}`). */
fun isMediaConflictPath(path: String): Boolean =
    path.startsWith("/media/")
