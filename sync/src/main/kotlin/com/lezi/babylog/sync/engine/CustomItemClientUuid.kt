package com.lezi.babylog.sync.engine
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.model.CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
import com.lezi.babylog.core.model.RecordType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Resolve a CUSTOM record's definition [clientUuid] for wire packaging.
 * Shared by publish planning and replica capture so schema rules stay single-sourced.
 */
internal suspend fun resolveRecordCustomItemClientUuid(
    record: RecordEntity,
    customItemDao: CustomItemDao,
): String? = resolveRecordCustomItemClientUuid(record) { localId ->
    customItemDao.getById(localId)
}

/** Map-backed variant for capture loops that preload definitions in one query. */
internal fun resolveRecordCustomItemClientUuid(
    record: RecordEntity,
    definitionsById: Map<Long, CustomItemEntity>,
): String? = resolveRecordCustomItemClientUuid(record) { localId ->
    definitionsById[localId]
}

private inline fun resolveRecordCustomItemClientUuid(
    record: RecordEntity,
    lookup: (Long) -> CustomItemEntity?,
): String? {
    val type = SyncWireMapper.requireCurrentRecordType(record.type, "record type")
    if (type != RecordType.CUSTOM) return null
    require(record.schemaVersion == CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION) {
        "custom record schema_version 必须是 $CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION"
    }
    val localId = requireNotNull(pendingRecordCustomItemId(record)) {
        "custom record 缺少本地 custom_item_id"
    }
    val definition = requireNotNull(lookup(localId)) {
        "custom record 引用的本地定义不存在"
    }
    return definition.clientUuid.takeIf(String::isNotBlank)
        ?: error("custom record 引用的定义缺少 client_uuid")
}

/**
 * The local definition id a CUSTOM record's payload references, extracted
 * without the loud validation so capture loops can batch-preload exactly the
 * ids the resolver will look up. Null for non-CUSTOM rows, stale schemas, or
 * unparsable payloads; the resolver still fails loudly on the latter two
 * for CUSTOM rows.
 */
internal fun pendingRecordCustomItemId(record: RecordEntity): Long? {
    if (record.type != RecordType.CUSTOM.key) return null
    if (record.schemaVersion != CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION) return null
    return runCatching {
        Json.parseToJsonElement(record.payloadJson).jsonObject["custom_item_id"]
            ?.jsonPrimitive
            ?.longOrNull
    }.getOrNull()?.takeIf { it > 0L }
}
