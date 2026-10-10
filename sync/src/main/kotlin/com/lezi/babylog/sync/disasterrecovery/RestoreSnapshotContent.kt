package com.lezi.babylog.sync.disasterrecovery

import com.lezi.babylog.sync.DisasterRecoverySummary
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.backend.DisasterRestoreSourceRelation
import com.lezi.babylog.sync.session.DisasterRestoreEntityVersion
import kotlinx.serialization.json.*

/** Immutable file content; mutable phase/target never rewrite this payload. */
internal fun encodeRestoreSnapshotContent(snapshot: DisasterRecoverySnapshot, familyId: String): String = buildJsonObject {
    require(snapshot.evidenceVersion in 1..2) { "unsupported restore evidence version" }
    put("format", 2)
    put("evidence_version", snapshot.evidenceVersion)
    put("source_family", familyId)
    snapshot.sourceRelations?.let { relations ->
        put("source_relations", JsonArray(relations.map { relation -> buildJsonObject {
            put("relation_id", relation.relationId)
            put("display_client_uuid", relation.displayClientUuid)
            put("source_client_uuids", JsonArray(relation.sourceClientUuids.map(::JsonPrimitive)))
            put("auto_aligned", relation.autoAligned)
        } }))
    }
    snapshot.sourceRelationEvidence?.let { put("source_relation_evidence", it) }
    put("entities", JsonArray(snapshot.entities.map { entity -> buildJsonObject {
        put("type", entity.type); put("uuid", entity.clientUuid)
        put("payload", Json.parseToJsonElement(entity.payloadJson)); put("updated", entity.updatedAt)
        put("deleted", entity.deletedAt?.let(::JsonPrimitive) ?: JsonNull)
    } }))
    put("versions", JsonArray(snapshot.retirementVersions.map { version -> buildJsonObject {
        put("type", version.type); put("uuid", version.clientUuid); put("updated", version.updatedAt)
        put("restored", version.restored); put("evidence", version.localEvidence?.let(::JsonPrimitive) ?: JsonNull)
    } }))
}.toString()

internal fun decodeRestoreSnapshotContent(
    content: JsonObject,
    media: List<PreparedRestoreMedia>,
    fileSnapshot: RestoreFileSnapshot? = null,
): DisasterRecoverySnapshot {
    // Only an absent marker is legacy v1. Unknown or malformed evidence must never authorize CAS.
    val evidenceVersion = content["evidence_version"]?.let { raw ->
        require(raw is JsonPrimitive && !raw.isString) { "invalid restore evidence version" }
        raw.int.also { require(it in 1..2) { "unsupported restore evidence version" } }
    } ?: 1
    val entities = content.getValue("entities").jsonArray.map { raw -> raw.jsonObject.let {
        SyncEntity(it.getValue("type").jsonPrimitive.content, it.getValue("uuid").jsonPrimitive.content,
            it.getValue("payload").toString(), it.getValue("updated").jsonPrimitive.long,
            it["deleted"]?.jsonPrimitive?.longOrNull)
    } }
    requireSchema13RestoreEntities(entities)
    val versions = content.getValue("versions").jsonArray.map { raw -> raw.jsonObject.let {
        DisasterRestoreEntityVersion(it.getValue("type").jsonPrimitive.content,
            it.getValue("uuid").jsonPrimitive.content, it.getValue("updated").jsonPrimitive.long,
            it.getValue("restored").jsonPrimitive.boolean, it["evidence"]?.jsonPrimitive?.contentOrNull)
    } }
    // Absence is legacy unknown evidence; only a captured JSON [] means no source relations.
    val relations = content["source_relations"]?.takeUnless { it == JsonNull }?.jsonArray?.map { raw ->
        raw.jsonObject.let {
            DisasterRestoreSourceRelation(it.getValue("relation_id").jsonPrimitive.content,
                it.getValue("display_client_uuid").jsonPrimitive.content,
                it.getValue("source_client_uuids").jsonArray.map { member -> member.jsonPrimitive.content },
                it.getValue("auto_aligned").jsonPrimitive.boolean)
        }
    }
    val counts = entities.filter { it.deletedAt == null }.groupingBy { it.type }.eachCount()
    fun count(type: String) = counts[type] ?: 0
    return DisasterRecoverySnapshot(entities, media, DisasterRecoverySummary(count("baby"), count("record"),
        count("care_plan"), count("fulfillment_candidate"), count("custom_item"), media.size,
        media.sumOf { it.spec.byteSize }), versions, fileSnapshot, relations,
        content["source_relation_evidence"]?.jsonPrimitive?.contentOrNull, evidenceVersion)
}
