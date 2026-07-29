package com.lezi.babylog.sync

import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.model.CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
import com.lezi.babylog.core.model.RecordType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Resolve a CUSTOM record's definition [clientUuid] for wire packaging.
 * Shared by outbox capture/push and replica capture so schema rules stay single-sourced.
 */
internal suspend fun resolveRecordCustomItemClientUuid(
    record: RecordEntity,
    customItemDao: CustomItemDao,
): String? {
    val type = SyncWireMapper.requireCurrentRecordType(record.type, "record type")
    if (type != RecordType.CUSTOM) return null
    require(record.schemaVersion == CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION) {
        "custom record schema_version 必须是 $CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION"
    }
    val localId = runCatching {
        Json.parseToJsonElement(record.payloadJson).jsonObject["custom_item_id"]
            ?.jsonPrimitive
            ?.longOrNull
    }.getOrNull()?.takeIf { it > 0L }
    require(localId != null) { "custom record 缺少本地 custom_item_id" }
    val definition = requireNotNull(customItemDao.getById(localId)) {
        "custom record 引用的本地定义不存在"
    }
    return definition.clientUuid.takeIf(String::isNotBlank)
        ?: error("custom record 引用的定义缺少 client_uuid")
}
