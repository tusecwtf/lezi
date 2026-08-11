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

/** Immutable H10 transport envelope stored atomically beside the mutable Record fact. */
internal data class FrozenRecordEnvelope(
    val mutation: CausalMutationUnit,
    val contentEpoch: Long,
    val requestHash: String,
)

internal fun encodeFrozenRecordEnvelope(
    mutation: CausalMutationUnit,
    contentEpoch: Long,
    requestHash: String,
): String = buildJsonObject {
    put("contract", "record_commit_first_v1")
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

internal fun decodeFrozenRecordEnvelope(raw: String): FrozenRecordEnvelope {
    val envelope = Json.parseToJsonElement(raw).jsonObject
    require(envelope.keys == FROZEN_RECORD_ENVELOPE_KEYS) {
        "frozen Record envelope has unknown or missing fields"
    }
    require(envelope["contract"]?.jsonPrimitive?.contentOrNull == "record_commit_first_v1") {
        "unsupported frozen Record envelope contract"
    }
    val contentEpoch = envelope["content_epoch"]?.jsonPrimitive?.longOrNull
        ?: error("frozen Record envelope content_epoch is invalid")
    val requestHash = envelope["request_hash"]?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.matches(FROZEN_LOWERCASE_SHA256) }
        ?: error("frozen Record envelope request_hash is invalid")
    val mutationJson = envelope["mutation"] as? JsonObject
        ?: error("frozen Record envelope mutation is invalid")
    require(mutationJson.keys == FROZEN_RECORD_MUTATION_KEYS) {
        "frozen Record mutation has unknown or missing fields"
    }
    val mutationId = mutationJson["mutation_id"]?.jsonPrimitive?.contentOrNull
        ?.takeIf { rawId ->
            runCatching { UUID.fromString(rawId).toString() == rawId }.getOrDefault(false)
        }
        ?: error("frozen Record mutation_id is invalid")
    val baseVersion = when (val base = mutationJson["base_version"]) {
        JsonNull -> null
        is JsonPrimitive ->
            base.contentOrNull?.takeIf(String::isNotBlank)
                ?: error("frozen Record base_version is invalid")
        else -> error("frozen Record base_version is invalid")
    }
    val entityType = mutationJson["entity_type"]?.jsonPrimitive?.contentOrNull
        ?: error("frozen Record entity_type is invalid")
    val clientUuid = mutationJson["client_uuid"]?.jsonPrimitive?.contentOrNull
        ?: error("frozen Record client_uuid is invalid")
    val root = mutationJson["root"] as? JsonObject
        ?: error("frozen Record root is invalid")
    val media = mutationJson["media"] as? JsonArray
        ?: error("frozen Record media is invalid")
    require(media.isEmpty()) { "H10 frozen Record must have an empty media manifest" }
    val deleted = mutationJson["deleted"]?.jsonPrimitive?.booleanOrNull
        ?: error("frozen Record deleted is invalid")
    val mutation = CausalMutationUnit(
        mutationId = mutationId,
        baseVersion = baseVersion,
        entityType = entityType,
        clientUuid = clientUuid,
        rootJson = root.toString(),
        media = emptyList(),
        deleted = deleted,
    )
    require(causalMutationContentHash(mutation) == requestHash) {
        "frozen Record request_hash does not match its canonical mutation"
    }
    return FrozenRecordEnvelope(mutation, contentEpoch, requestHash)
}

private val FROZEN_RECORD_ENVELOPE_KEYS = setOf(
    "contract",
    "content_epoch",
    "request_hash",
    "mutation",
)
private val FROZEN_RECORD_MUTATION_KEYS = setOf(
    "mutation_id",
    "base_version",
    "entity_type",
    "client_uuid",
    "root",
    "media",
    "deleted",
)
private val FROZEN_LOWERCASE_SHA256 = Regex("^[0-9a-f]{64}$")
