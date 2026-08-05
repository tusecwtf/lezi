package com.lezi.gf.care

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Closed-set payload validation for builtin care types (fail-closed on write).
 * Shared by CareService confirm/edit and server-side table-driven checks.
 */
object PayloadValidation {
    private val json = Json { ignoreUnknownKeys = true }

    /** Known type keys the family wire accepts as care facts. */
    val knownTypeKeys: Set<String> =
        RecordType.entries.map { it.key }.toSet()

    /**
     * @return null if OK, Chinese error message if rejected.
     */
    fun validate(typeKey: String, payloadJson: String): String? {
        val type = RecordType.fromKey(typeKey)
            ?: return "未知记录类型: $typeKey"
        return validate(type, payloadJson)
    }

    fun validate(type: RecordType, payloadJson: String): String? {
        val o: JsonObject = try {
            json.parseToJsonElement(payloadJson.ifBlank { "{}" }).jsonObject
        } catch (_: Exception) {
            return "字段格式无效"
        }
        return when (type) {
            RecordType.PEE -> {
                val a = o["pee_amount"]?.jsonPrimitive?.intOrNull ?: 2
                if (a !in 1..3) "尿量档位须为 1–3" else null
            }
            RecordType.TEMPERATURE -> {
                val c = o["celsius"]?.jsonPrimitive?.doubleOrNull
                    ?: return "体温字段缺失或无效"
                if (c < 30.0 || c > 45.0) "体温超出合理范围" else null
            }
            RecordType.SLEEP -> {
                // anomaly does not block
                o["anomaly"]?.jsonPrimitive?.booleanOrNull
                val open = o["open"]?.jsonPrimitive?.booleanOrNull ?: false
                val end = o["end_ms"]?.jsonPrimitive?.longOrNull
                val start = o["start_ms"]?.jsonPrimitive?.longOrNull
                if (!open && end != null && start != null && end < start) {
                    "醒来时刻不能早于睡下"
                } else {
                    null
                }
            }
            RecordType.FORMULA, RecordType.PUMPED_FEED, RecordType.PUMP_EXPRESS -> {
                val ml = o["amount_ml"]?.jsonPrimitive?.intOrNull
                if (ml != null && ml < 0) "奶量不能为负" else null
            }
            RecordType.WEIGHT -> {
                val g = o["grams"]?.jsonPrimitive?.intOrNull
                if (g != null && g < 0) "体重不能为负" else null
            }
            RecordType.HEIGHT -> {
                val mm = o["mm"]?.jsonPrimitive?.intOrNull
                if (mm != null && mm < 0) "身长不能为负" else null
            }
            RecordType.POOP -> {
                val a = o["poop_amount"]?.jsonPrimitive?.intOrNull
                if (a != null && a !in 1..4) "便量档位须为 1–4" else null
            }
            else -> null
        }
    }

    fun isKnownTypeKey(typeKey: String): Boolean = typeKey in knownTypeKeys
}
