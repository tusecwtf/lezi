package com.lezi.babylog.sync.engine

import com.lezi.babylog.sync.backend.CausalMutationUnit
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The one immutable empty-media transport truth for a pending causal mutation. */
internal data class FrozenCommitEnvelope(
    val mutation: CausalMutationUnit,
    val contentEpoch: Long,
    val requestHash: String,
)

internal fun encodeFrozenCommitEnvelope(
    mutation: CausalMutationUnit,
    contentEpoch: Long,
    requestHash: String,
): String {
    require(mutation.media.isEmpty()) { "frozen empty-media envelope cannot contain media" }
    return buildJsonObject {
        put("contract", "frozen_commit_first_v1")
        put("content_epoch", contentEpoch)
        put("request_hash", requestHash)
        put(
            "mutation",
            buildJsonObject {
                put("mutation_id", mutation.mutationId)
                if (mutation.baseVersion == null) put("base_version", JsonNull)
                else put("base_version", mutation.baseVersion)
                put("entity_type", mutation.entityType)
                put("client_uuid", mutation.clientUuid)
                put("root", Json.parseToJsonElement(mutation.rootJson))
                put("media", JsonArray(emptyList()))
                put("deleted", mutation.deleted)
            },
        )
    }.toString()
}

internal fun decodeFrozenCommitEnvelope(raw: String): FrozenCommitEnvelope {
    val envelope = Json.parseToJsonElement(raw).jsonObject
    require(envelope.keys == FROZEN_COMMIT_ENVELOPE_KEYS) {
        "frozen commit envelope has unknown or missing fields"
    }
    val contract = envelope["contract"]?.jsonPrimitive?.contentOrNull
    require(contract in FROZEN_COMMIT_CONTRACTS) {
        "unsupported frozen commit envelope contract"
    }
    val contentEpoch = envelope["content_epoch"]?.jsonPrimitive?.longOrNull
        ?: error("frozen commit envelope content_epoch is invalid")
    val requestHash = envelope["request_hash"]?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.matches(FROZEN_LOWERCASE_SHA256) }
        ?: error("frozen commit envelope request_hash is invalid")
    val mutationJson = envelope["mutation"] as? JsonObject
        ?: error("frozen commit envelope mutation is invalid")
    require(mutationJson.keys == FROZEN_COMMIT_MUTATION_KEYS) {
        "frozen commit mutation has unknown or missing fields"
    }
    val mutationId = mutationJson["mutation_id"]?.jsonPrimitive?.contentOrNull
        ?.takeIf { rawId ->
            runCatching { UUID.fromString(rawId).toString() == rawId }.getOrDefault(false)
        }
        ?: error("frozen commit mutation_id is invalid")
    val baseVersion = when (val base = mutationJson["base_version"]) {
        JsonNull -> null
        is JsonPrimitive ->
            base.contentOrNull?.takeIf(String::isNotBlank)
                ?: error("frozen commit base_version is invalid")
        else -> error("frozen commit base_version is invalid")
    }
    val entityType = mutationJson["entity_type"]?.jsonPrimitive?.contentOrNull
        ?.takeIf { it in CAUSAL_ROOT_TYPES }
        ?: error("frozen commit entity_type is invalid")
    val clientUuid = mutationJson["client_uuid"]?.jsonPrimitive?.contentOrNull
        ?.takeIf(String::isNotBlank)
        ?: error("frozen commit client_uuid is invalid")
    val root = mutationJson["root"] as? JsonObject
        ?: error("frozen commit root is invalid")
    val media = mutationJson["media"] as? JsonArray
        ?: error("frozen commit media is invalid")
    require(media.isEmpty()) { "frozen empty-media envelope must have an empty manifest" }
    val deleted = mutationJson["deleted"]?.jsonPrimitive?.booleanOrNull
        ?: error("frozen commit deleted is invalid")
    val mutation = CausalMutationUnit(
        mutationId = mutationId,
        baseVersion = baseVersion,
        entityType = entityType,
        clientUuid = clientUuid,
        rootJson = root.toString(),
        media = emptyList(),
        deleted = deleted,
    )
    require(contract != "record_commit_first_v1" || entityType == "record") {
        "legacy frozen Record envelope cannot own another root type"
    }
    require(causalMutationContentHash(mutation) == requestHash) {
        "frozen commit request_hash does not match its canonical mutation"
    }
    return FrozenCommitEnvelope(mutation, contentEpoch, requestHash)
}

private val FROZEN_COMMIT_ENVELOPE_KEYS = setOf(
    "contract",
    "content_epoch",
    "request_hash",
    "mutation",
)
private val FROZEN_COMMIT_MUTATION_KEYS = setOf(
    "mutation_id",
    "base_version",
    "entity_type",
    "client_uuid",
    "root",
    "media",
    "deleted",
)
private val FROZEN_LOWERCASE_SHA256 = Regex("^[0-9a-f]{64}$")
private val FROZEN_COMMIT_CONTRACTS = setOf(
    "frozen_commit_first_v1",
    // H10 persisted this opaque contract before the envelope became root-generic.
    "record_commit_first_v1",
)
