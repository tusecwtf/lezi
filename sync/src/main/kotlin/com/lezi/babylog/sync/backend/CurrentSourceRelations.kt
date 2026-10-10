package com.lezi.babylog.sync.backend

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

const val MAX_CURRENT_SOURCE_RELATION_REQUEST_IDS = 64
const val MAX_CURRENT_SOURCE_RELATION_RECORDS = 4096
const val MAX_CURRENT_SOURCE_RELATION_RESPONSE_BYTES = 2 * 1024 * 1024

data class CurrentSourceRelationsRequest(
    val familyId: String,
    val generation: String,
    val recordClientUuids: List<String>,
) {
    fun validate() {
        requireId(familyId)
        requireId(generation)
        require(recordClientUuids.size in 1..MAX_CURRENT_SOURCE_RELATION_REQUEST_IDS)
        require(recordClientUuids == recordClientUuids.distinct().sorted())
        recordClientUuids.forEach(::requireId)
    }
}

data class CurrentSourceRelationRecord(
    val recordClientUuid: String,
    val recordState: String,
    val relationId: String?,
)

data class CurrentSourceRelationGroup(
    val relationId: String,
    val displayClientUuid: String,
    val sourceClientUuids: List<String>,
    val mediaRetained: Boolean = true,
    val autoAligned: Boolean = false,
) {
    val memberClientUuids: List<String> get() = listOf(displayClientUuid) + sourceClientUuids
}

data class CurrentSourceRelationsSnapshot(
    val familyId: String,
    val generation: String,
    val headRev: Long,
    val requestedRecordClientUuids: List<String>,
    val records: List<CurrentSourceRelationRecord>,
    val sourceRelations: List<CurrentSourceRelationGroup>,
    val protocolVersion: Int = 1,
) {
    /** A single complete response, never a partial merge of separately observed snapshots. */
    fun validateFor(request: CurrentSourceRelationsRequest, minimumRevision: Long = 0) {
        request.validate()
        require(protocolVersion == 1 && familyId == request.familyId && generation == request.generation)
        require(headRev >= minimumRevision && headRev >= 0)
        require(requestedRecordClientUuids == request.recordClientUuids)
        require(records.size in 1..MAX_CURRENT_SOURCE_RELATION_RECORDS)
        require(sourceRelations.size <= MAX_CURRENT_SOURCE_RELATION_REQUEST_IDS)
        val rows = records.associateBy { it.recordClientUuid }
        require(rows.size == records.size)
        require(sourceRelations.map { it.relationId }.distinct().size == sourceRelations.size)
        val membership = linkedMapOf<String, String>()
        val requested = request.recordClientUuids.toSet()
        for (relation in sourceRelations) {
            requireId(relation.relationId)
            require(relation.mediaRetained)
            require(relation.memberClientUuids.size in 2..64)
            require(relation.sourceClientUuids == relation.sourceClientUuids.distinct().sorted())
            require(relation.displayClientUuid !in relation.sourceClientUuids)
            require(relation.memberClientUuids.any { it in requested })
            for (uuid in relation.memberClientUuids) {
                requireId(uuid)
                require(membership.put(uuid, relation.relationId) == null)
            }
        }
        require(rows.keys == requested + membership.keys)
        for (row in records) {
            requireId(row.recordClientUuid)
            require(row.recordState in setOf("live", "deleted", "missing"))
            row.relationId?.let(::requireId)
            require(row.relationId == membership[row.recordClientUuid])
            require(row.recordState != "missing" || row.relationId == null)
        }
    }
}

internal fun CurrentSourceRelationsRequest.toCurrentSourceRelationsJson(): JsonObject {
    validate()
    return buildJsonObject {
        put("protocol_version", 1)
        put("family_id", familyId)
        put("generation", generation)
        put("record_client_uuids", JsonArray(recordClientUuids.map(::JsonPrimitive)))
    }.also { require(it.toString().toByteArray(Charsets.UTF_8).size <= 64 * 1024) }
}

internal fun JsonObject.toCurrentSourceRelationsSnapshot(
    request: CurrentSourceRelationsRequest,
): CurrentSourceRelationsSnapshot {
    keysExactly("protocol_version", "family_id", "generation", "head_rev",
        "requested_record_client_uuids", "records", "source_relations")
    val rows = array("records", MAX_CURRENT_SOURCE_RELATION_RECORDS).map { raw ->
        val row = raw as? JsonObject ?: error("当前来源关系记录格式无效")
        row.keysExactly("record_client_uuid", "record_state", "relation_id")
        CurrentSourceRelationRecord(row.string("record_client_uuid"), row.string("record_state"),
            if (row["relation_id"] == JsonNull) null else row.string("relation_id"))
    }
    val groups = array("source_relations", MAX_CURRENT_SOURCE_RELATION_REQUEST_IDS).map { raw ->
        val group = raw as? JsonObject ?: error("当前来源关系组格式无效")
        group.keysExactly("relation_id", "display_client_uuid", "source_client_uuids", "media_retained", "auto_aligned")
        CurrentSourceRelationGroup(group.string("relation_id"), group.string("display_client_uuid"),
            group.strings("source_client_uuids", 63), group.boolean("media_retained"), group.boolean("auto_aligned"))
    }
    return CurrentSourceRelationsSnapshot(
        familyId = string("family_id"), generation = string("generation"),
        headRev = primitive("head_rev").also { require(!it.isString) }.longOrNull
            ?: error("当前来源关系 revision 无效"),
        requestedRecordClientUuids = strings("requested_record_client_uuids", MAX_CURRENT_SOURCE_RELATION_REQUEST_IDS),
        records = rows, sourceRelations = groups,
        protocolVersion = primitive("protocol_version").also { require(!it.isString) }.intOrNull
            ?: error("当前来源关系协议版本无效"),
    ).also { it.validateFor(request) }
}

private fun requireId(value: String) = require(value.isNotBlank() && value.toByteArray(Charsets.UTF_8).size <= 128)
private fun JsonObject.keysExactly(vararg names: String) = require(keys == names.toSet())
private fun JsonObject.primitive(key: String) = get(key) as? JsonPrimitive ?: error("当前来源关系字段无效: $key")
private fun JsonObject.string(key: String): String = primitive(key).also { require(it.isString) }.content
private fun JsonObject.boolean(key: String): Boolean = primitive(key).also { require(!it.isString) }.booleanOrNull
    ?: error("当前来源关系布尔字段无效: $key")
private fun JsonObject.array(key: String, limit: Int): JsonArray = (get(key) as? JsonArray
    ?: error("当前来源关系列表无效: $key")).also { require(it.size <= limit) }
private fun JsonObject.strings(key: String, limit: Int): List<String> = array(key, limit).map {
    val value = it as? JsonPrimitive ?: error("当前来源关系列表项无效")
    require(value.isString)
    value.content
}
