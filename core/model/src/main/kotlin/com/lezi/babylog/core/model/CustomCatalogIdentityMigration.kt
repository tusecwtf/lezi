package com.lezi.babylog.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/**
 * Rewrite pre-0.3.5 `custom:<localId>` layout references to the family UUID.
 * Unknown local ids are removed so a later Room row reusing the number can
 * never inherit a user-owned slot or hidden state by accident.
 */
fun stabilizeCustomLayoutSnapshot(
    snapshot: DeviceLayoutSnapshot,
    clientUuidByLocalId: Map<Long, String>,
): DeviceLayoutSnapshot {
    fun stableKey(key: String): String? {
        val trimmed = key.trim()
        val localId = legacyLocalCustomId(trimmed) ?: return trimmed
        val clientUuid = clientUuidByLocalId[localId] ?: return null
        return runCatching { RecordItemIdentity.customFamilyCatalogKey(clientUuid) }.getOrNull()
    }

    val itemOrder = (Json.parseToJsonElement(snapshot.itemOrderJson) as? JsonArray)
        ?.map { element ->
            val primitive = element as? JsonPrimitive
                ?: throw IllegalArgumentException("itemOrderJson values must be strings")
            require(primitive.isString) { "itemOrderJson values must be strings" }
            primitive.content
        }
        ?: throw IllegalArgumentException("itemOrderJson must be an array")
    val migratedOrder = itemOrder.mapNotNull(::stableKey).distinct()

    return normalizeDeviceLayoutSnapshot(
        snapshot.copy(
            quickRecordSlots = snapshot.quickRecordSlots.map { stableKey(it).orEmpty() },
            hiddenItems = snapshot.hiddenItems.mapNotNull(::stableKey).toSet(),
            itemOrderJson = buildJsonArray {
                migratedOrder.forEach { add(JsonPrimitive(it)) }
            }.toString(),
        ),
    )
}

fun hasLegacyLocalCustomCatalogKeys(snapshot: DeviceLayoutSnapshot): Boolean {
    val order = runCatching {
        (Json.parseToJsonElement(snapshot.itemOrderJson) as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            .orEmpty()
    }.getOrDefault(emptyList())
    return (snapshot.quickRecordSlots + snapshot.hiddenItems + order)
        .any { legacyLocalCustomId(it.trim()) != null }
}

private fun legacyLocalCustomId(key: String): Long? {
    if (!key.startsWith("custom:")) return null
    return key.removePrefix("custom:").toLongOrNull()?.takeIf { it > 0L }
}
