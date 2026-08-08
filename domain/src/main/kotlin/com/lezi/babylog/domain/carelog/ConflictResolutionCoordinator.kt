package com.lezi.babylog.domain.carelog

import com.lezi.babylog.core.common.newClientUuid
import com.lezi.babylog.core.database.causal.ConflictDetailCacheDao
import com.lezi.babylog.core.database.causal.ConflictDetailCacheEntity
import com.lezi.babylog.core.database.causal.ConflictSummaryDao
import com.lezi.babylog.core.database.causal.ConflictSummaryEntity
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.backend.CausalMediaItem
import com.lezi.babylog.sync.backend.ConflictDetail
import com.lezi.babylog.sync.backend.ConflictResolveRequest
import com.lezi.babylog.sync.backend.ConflictResolveResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
 */
internal class ConflictResolutionCoordinator(
    private val conflictSummaryDao: ConflictSummaryDao,
    private val conflictDetailCacheDao: ConflictDetailCacheDao,
    private val syncPort: SyncPort,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
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
     * falls back to offline cache paired with [ConflictSummaryEntity] for CAS
     * identities (stableVersionId + full branch set). Refuse empty CAS inputs.
     */
    suspend fun loadDetail(conflictId: String, forceRefresh: Boolean = true): ConflictResolverDetail? {
        if (forceRefresh) {
            val fetched = runCatching { syncPort.fetchConflictDetail(conflictId) }.getOrNull()
            if (fetched != null) {
                cacheDetail(fetched)
                return fetched.toResolverDetail()
            }
        }
        return loadCachedDetailWithSummary(conflictId)
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
                conflictSummaryDao.delete(conflictId)
                conflictDetailCacheDao.delete(conflictId)
                ConflictResolveOutcome.Accepted(result.stableVersionId)
            }
            is ConflictResolveResult.CasMismatch -> {
                result.detail?.let { cacheDetail(it) }
                result.summary?.let { summary ->
                    conflictSummaryDao.upsert(
                        ConflictSummaryEntity(
                            conflictId = summary.conflictId,
                            entityType = summary.entityType,
                            clientUuid = summary.clientUuid,
                            baseVersionId = summary.baseVersionId,
                            stableVersionId = summary.stableVersionId,
                            status = "open",
                            kind = summary.kind,
                            branchVersionIdsJson = encodeStringArray(summary.branchVersionIds),
                            updatedAt = summary.updatedAt,
                        ),
                    )
                }
                ConflictResolveOutcome.CasMismatch(
                    refreshed = result.detail?.toResolverDetail()
                        ?: loadCachedDetailWithSummary(conflictId),
                    summary = result.summary?.let {
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

    private suspend fun cacheDetail(detail: ConflictDetail) {
        conflictDetailCacheDao.upsert(
            ConflictDetailCacheEntity(
                conflictId = detail.conflictId,
                stableRootJson = detail.stableRootJson,
                baseRootJson = detail.baseRootJson,
                branchesJson = detail.branchesJson,
                conflictPathsJson = encodeStringArray(detail.conflictingPaths),
                cachedAt = System.currentTimeMillis(),
            ),
        )
        // Keep summary CAS identities in lockstep with cached roots/paths.
        conflictSummaryDao.upsert(
            ConflictSummaryEntity(
                conflictId = detail.conflictId,
                entityType = detail.entityType,
                clientUuid = detail.clientUuid,
                baseVersionId = detail.baseVersionId,
                stableVersionId = detail.stableVersionId,
                status = "open",
                kind = detail.kind,
                branchVersionIdsJson = encodeStringArray(detail.branchVersionIds),
                updatedAt = detail.updatedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Pair offline cache roots with open [ConflictSummaryEntity] so resolve CAS
     * still has stableVersionId + full branch set without Room schema migration.
     */
    private suspend fun loadCachedDetailWithSummary(conflictId: String): ConflictResolverDetail? {
        val cache = conflictDetailCacheDao.get(conflictId) ?: return null
        val summary = conflictSummaryDao.get(conflictId)?.takeIf { it.status == "open" }
        if (summary == null || summary.stableVersionId.isBlank()) {
            // Roots alone cannot drive wire §8.2 CAS — force a network refresh.
            return null
        }
        return ConflictResolverDetail(
            conflictId = conflictId,
            stableVersionId = summary.stableVersionId,
            stableRootJson = cache.stableRootJson,
            baseRootJson = cache.baseRootJson,
            branchesJson = cache.branchesJson,
            conflictingPaths = decodeStringArray(cache.conflictPathsJson, json),
            autoMergedJson = "{}",
            branchVersionIds = decodeStringArray(summary.branchVersionIdsJson, json),
            cachedAt = cache.cachedAt,
        )
    }
}

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

private fun ConflictDetail.toResolverDetail(): ConflictResolverDetail =
    ConflictResolverDetail(
        conflictId = conflictId,
        stableVersionId = stableVersionId,
        stableRootJson = stableRootJson,
        baseRootJson = baseRootJson,
        branchesJson = branchesJson,
        conflictingPaths = conflictingPaths,
        autoMergedJson = autoMergedJson,
        branchVersionIds = branchVersionIds,
        cachedAt = System.currentTimeMillis(),
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
