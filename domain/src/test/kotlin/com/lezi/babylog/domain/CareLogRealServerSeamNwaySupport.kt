package com.lezi.babylog.domain

import com.lezi.babylog.domain.carelog.ConflictResolveOutcome
import com.lezi.babylog.domain.carelog.ConflictResolverLoad
import com.lezi.babylog.sync.backend.ConflictResolutionChoice
import com.lezi.babylog.sync.backend.ConflictResolveRequest
import com.lezi.babylog.sync.conflict.ConflictOutcome
import com.lezi.babylog.sync.conflict.ConflictSnapshot
import com.lezi.babylog.sync.conflict.ConflictSnapshotCodec
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive


internal suspend fun SeamClient.recordByClientUuid(clientUuid: String) =
    fakes.records.getByClientUuid(clientUuid)

internal suspend fun SeamClient.babyByClientUuid(clientUuid: String) =
    fakes.babies.listAllIncludingDeleted().firstOrNull { it.clientUuid == clientUuid }

internal suspend fun SeamClient.openConflictIdForRecord(clientUuid: String): String? =
    recordByClientUuid(clientUuid)?.openConflictId
        ?: fakes.conflictSummaries.listForRoot("record", clientUuid)
            .firstOrNull { it.status == "open" }
            ?.conflictId

internal suspend fun SeamClient.openConflictIdForBaby(clientUuid: String): String? =
    babyByClientUuid(clientUuid)?.openConflictId
        ?: fakes.conflictSummaries.listForRoot("baby", clientUuid)
            .firstOrNull { it.status == "open" }
            ?.conflictId

internal suspend fun SeamClient.loadConflict(
    conflictId: String,
    forceRefresh: Boolean = true,
): ConflictResolverLoad =
    requireNotNull(careLog.loadConflictDetail(conflictId, forceRefresh = forceRefresh)) {
        "$label missing conflict detail for $conflictId"
    }

/**
 * Choice-only resolution through the public CareLog seam.
 * When [preferValueByPath] is set, pick the candidate whose set-value matches;
 * otherwise take the first candidate on each conflicting path.
 */
internal suspend fun SeamClient.resolveOpenConflict(
    conflictId: String,
    resolutionMutationId: String = UUID.randomUUID().toString(),
    preferValueByPath: Map<String, JsonElement> = emptyMap(),
): Pair<ConflictSnapshot, ConflictResolveOutcome> {
    val loaded = loadConflict(conflictId, forceRefresh = true)
    check(loaded.fetchedOnline) { "$label conflict detail must be online for resolution" }
    val snapshot = loaded.snapshot
    // Ensure coordinator.resolve can re-read the same token from the local projection.
    careLog.loadConflictDetail(conflictId, forceRefresh = false)
    val choices = snapshot.conflicting.map { path ->
        val preferred = preferValueByPath[path.path]
        val candidate = if (preferred != null) {
            path.candidates.firstOrNull { candidate ->
                val outcome = candidate.outcome
                outcome is ConflictOutcome.Set && outcome.value == preferred
            } ?: path.candidates.first()
        } else {
            path.candidates.first()
        }
        ConflictResolutionChoice(path = path.path, choiceId = candidate.choiceId)
    }
    val outcome = careLog.resolveConflict(
        conflictId = conflictId,
        request = ConflictResolveRequest(
            snapshotToken = snapshot.snapshotToken,
            resolutionMutationId = resolutionMutationId,
            choices = choices,
        ),
    )
    return snapshot to outcome
}

/**
 * Semantic digest of a ConflictSnapshot: strip server-generated version/branch IDs,
 * receipt/token, receive-time instance fields; keep outcome/path/deleted/media,
 * provenance correspondence (mutation/actor/device), and causal structure.
 *
 * On the live seam the first accepted concurrent head becomes stable and later
 * same-base heads become branches. Classification (conflicting/auto_merged) is still
 * N-way over the open head set; this digest folds stable+branches into one ordered
 * head multiset so arrival order cannot invent a silent winner in comparisons.
 */
internal fun semanticConflictDigest(snapshot: ConflictSnapshot): String {
    val encoded = ConflictSnapshotCodec.encode(snapshot)
    val root = Json.parseToJsonElement(encoded).jsonObject.toMutableMap()
    listOf(
        "conflict_id",
        "client_uuid",
        "snapshot_token",
        "expires_at",
        "page_index",
        "continuation",
        "complete",
    ).forEach(root::remove)

    fun stripVersion(version: JsonObject): JsonObject {
        val map = version.toMutableMap()
        // Instance IDs: server versions, client mutation UUID, receive time.
        map.remove("version_id")
        map.remove("base_version")
        map.remove("mutation_id")
        map.remove("received_at")
        val rootObj = map["root"]?.jsonObject?.toMutableMap() ?: linkedMapOf()
        // Instance / order-sensitive root fields (keep business leaves).
        rootObj.remove("updated_at")
        rootObj.remove("timestamp")
        rootObj.remove("baby_client_uuid")
        rootObj.remove("created_by_membership_id")
        map["root"] = JsonObject(rootObj)
        return JsonObject(map)
    }

    // Fold stable tip + open branches into one head multiset. Prefer device_id then
    // actor_id then root payload so arrival-promoted stable tip cannot diverge digests.
    val heads = buildList {
        add(stripVersion(root.getValue("stable").jsonObject))
        root.getValue("branches").jsonArray.forEach { add(stripVersion(it.jsonObject)) }
    }.sortedWith(
        compareBy(
            { it["device_id"]?.jsonPrimitive?.content.orEmpty() },
            { it["actor_id"]?.jsonPrimitive?.content.orEmpty() },
            { it["root"]?.toString().orEmpty() },
            { it["deleted"]?.toString().orEmpty() },
        ),
    )
    root.remove("stable")
    root.remove("branches")
    root["heads"] = JsonArray(heads)

    fun stripSources(sources: JsonArray): JsonArray {
        val cleaned = sources.map { source ->
            val map = source.jsonObject.toMutableMap()
            map.remove("version_id")
            map.remove("mutation_id")
            map.remove("received_at")
            JsonObject(map)
        }.sortedWith(
            compareBy(
                { it["device_id"]?.jsonPrimitive?.content.orEmpty() },
                { it["actor_id"]?.jsonPrimitive?.content.orEmpty() },
            ),
        )
        return JsonArray(cleaned)
    }

    val conflicting = root.getValue("conflicting").jsonArray.map { item ->
        val map = item.jsonObject.toMutableMap()
        val candidates = item.jsonObject.getValue("candidates").jsonArray.map { candidate ->
            val cand = candidate.jsonObject.toMutableMap()
            cand.remove("choice_id")
            cand["sources"] = stripSources(cand.getValue("sources").jsonArray)
            JsonObject(cand)
        }.sortedBy { it.getValue("outcome").toString() }
        map["candidates"] = JsonArray(candidates)
        JsonObject(map)
    }.sortedBy { it.getValue("path").jsonPrimitive.content }
    root["conflicting"] = JsonArray(conflicting)

    val autoMerged = root.getValue("auto_merged").jsonArray.map { item ->
        val map = item.jsonObject.toMutableMap()
        map["sources"] = stripSources(map.getValue("sources").jsonArray)
        JsonObject(map)
    }.sortedBy { it.getValue("path").jsonPrimitive.content }
    root["auto_merged"] = JsonArray(autoMerged)

    return JsonObject(root).toString()
}

internal fun assertAutoConflictDisjoint(snapshot: ConflictSnapshot) {
    val conflictPaths = snapshot.conflicting.map { it.path }.toSet()
    val autoPaths = snapshot.autoMerged.map { it.path }.toSet()
    check(conflictPaths.intersect(autoPaths).isEmpty()) {
        "conflicting/auto_merged overlap: ${conflictPaths.intersect(autoPaths)}"
    }
    snapshot.conflicting.forEach { path ->
        check(path.candidates.isNotEmpty()) { "empty candidates on ${path.path}" }
        path.candidates.forEach { candidate ->
            check(candidate.choiceId.isNotBlank()) { "blank choice_id on ${path.path}" }
            check(candidate.sources.isNotEmpty()) { "empty provenance on ${path.path}" }
        }
    }
    snapshot.autoMerged.forEach { path ->
        check(path.sources.isNotEmpty()) { "empty auto_merged provenance on ${path.path}" }
    }
}

internal data class StableRecordFact(
    val note: String?,
    val payloadJson: String,
    val baseVersion: String?,
    val openConflictId: String?,
    val syncDirty: Boolean,
    val deletedAt: Long?,
)

internal suspend fun SeamClient.stableRecordFact(clientUuid: String): StableRecordFact {
    val row = requireNotNull(recordByClientUuid(clientUuid)) {
        "$label missing record $clientUuid"
    }
    return StableRecordFact(
        note = row.note,
        payloadJson = row.payloadJson,
        baseVersion = row.baseVersion,
        openConflictId = row.openConflictId,
        syncDirty = row.syncDirty,
        deletedAt = row.deletedAt,
    )
}

internal data class StableBabyFact(
    val nickname: String,
    val sex: String?,
    val birthdayEpochDay: Long?,
    val baseVersion: String?,
    val openConflictId: String?,
    val syncDirty: Boolean,
)

internal suspend fun SeamClient.stableBabyFact(clientUuid: String): StableBabyFact {
    val row = requireNotNull(babyByClientUuid(clientUuid)) {
        "$label missing baby $clientUuid"
    }
    return StableBabyFact(
        nickname = row.nickname,
        sex = row.sex,
        birthdayEpochDay = row.birthdayEpochDay,
        baseVersion = row.baseVersion,
        openConflictId = row.openConflictId,
        syncDirty = row.syncDirty,
    )
}

