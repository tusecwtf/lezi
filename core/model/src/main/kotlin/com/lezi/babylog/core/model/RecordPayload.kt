package com.lezi.babylog.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

const val CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION = 2

sealed interface RecordPayload {
    val type: RecordType
}

data class NursingPayload(
    val leftMinutes: Int = 0,
    val rightMinutes: Int = 0,
    val order: String = "LR",
    val amountMl: Int? = null,
    val recordMode: String = "end",
) : RecordPayload {
    override val type: RecordType = RecordType.NURSING
}

data class MilkPayload(
    override val type: RecordType,
    val amountMl: Int = 0,
    val preparedMl: Int? = null,
    val durationMinutes: Int? = null,
) : RecordPayload {
    init {
        require(type in MILK_TYPES) { "$type does not accept MilkPayload" }
    }
}

data class PeePayload(val amount: Int = 2) : RecordPayload {
    override val type: RecordType = RecordType.PEE
}

data class StoolPayload(
    val amount: Int = 3,
    val consistency: Int = 3,
    val color: Int = 0,
) : RecordPayload {
    override val type: RecordType = RecordType.POOP
}

data class BothDiaperPayload(
    val peeAmount: Int = 2,
    val stoolAmount: Int = 3,
    val stoolConsistency: Int = 3,
    val stoolColor: Int = 0,
) : RecordPayload {
    override val type: RecordType = RecordType.BOTH_DIAPER
}

data class SleepPayload(
    val isNap: Boolean = false,
    val anomaly: Boolean = false,
) : RecordPayload {
    override val type: RecordType = RecordType.SLEEP
}

data class TemperaturePayload(val celsius: Double = 36.5) : RecordPayload {
    override val type: RecordType = RecordType.TEMPERATURE
}

data class TextPayload(
    override val type: RecordType,
    val body: String = "",
    val photos: List<String> = emptyList(),
) : RecordPayload {
    init {
        require(type == RecordType.MEMO || type == RecordType.DIARY) {
            "$type does not accept TextPayload"
        }
    }
}

data class EmptyPayload(override val type: RecordType) : RecordPayload {
    init {
        require(type == RecordType.BATH || type == RecordType.WALK) {
            "$type does not accept EmptyPayload"
        }
    }
}

data class SymptomPayload(
    override val type: RecordType,
    val severity: Int = 2,
    val description: String? = null,
) : RecordPayload {
    init {
        require(type in SYMPTOM_TYPES) { "$type does not accept SymptomPayload" }
    }
}

data class MedicinePayload(
    val name: String = "",
    val dose: String? = null,
) : RecordPayload {
    override val type: RecordType = RecordType.MEDICINE
}

data class HospitalPayload(
    val reason: String = "",
    val advice: String? = null,
) : RecordPayload {
    override val type: RecordType = RecordType.HOSPITAL
}

data class OtherPayload(
    val title: String = "",
    val detail: String? = null,
) : RecordPayload {
    override val type: RecordType = RecordType.OTHER
}

data class MeasurementPayload(
    override val type: RecordType,
    val value: Double = 0.0,
    val unit: String = if (type == RecordType.WEIGHT) "g" else "cm",
) : RecordPayload {
    init {
        require(type in MEASUREMENT_TYPES) { "$type does not accept MeasurementPayload" }
    }
}

data class FoodPayload(
    override val type: RecordType,
    val content: String = "",
    val amount: String? = null,
) : RecordPayload {
    init {
        require(type in FOOD_TYPES) { "$type does not accept FoodPayload" }
    }
}

data class VaccinePayload(
    val name: String = "",
    val batch: String? = null,
) : RecordPayload {
    override val type: RecordType = RecordType.VACCINE
}

data class CustomPayload(
    val titleSnapshot: String = "",
    val detail: String? = null,
    val customItemId: Long? = null,
    val iconSlot: Int? = null,
) : RecordPayload {
    override val type: RecordType = RecordType.CUSTOM
}

data class UnknownPayload(
    override val type: RecordType,
    val rawJson: String,
    val sourceSchemaVersion: Int,
    val reason: String,
) : RecordPayload

/**
 * Decoded storage document. [extensions] contains every field not understood
 * by the matching payload type and is merged back unchanged on a version-2 write.
 */
data class RecordPayloadDocument(
    val type: RecordType,
    val payload: RecordPayload,
    val schemaVersion: Int,
    val extensions: Map<String, JsonElement> = emptyMap(),
    val rawJson: String? = null,
) {
    init {
        require(payload.type == type) { "$type cannot store ${payload::class.simpleName}" }
    }

    val isUnknown: Boolean get() = payload is UnknownPayload
}

/**
 * Typed edit draft used by Composer adapters. The payload shape is explicit;
 * storage-only extensions never leak into feature-specific fields.
 */
data class RecordPayloadDraft(
    val payload: RecordPayload,
    val extensions: Map<String, JsonElement> = emptyMap(),
    val sourceSchemaVersion: Int = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
    val rawJson: String? = null,
) {
    fun toDocument(): RecordPayloadDocument = RecordPayloadDocument(
        type = payload.type,
        payload = payload,
        schemaVersion = if (payload is UnknownPayload) {
            sourceSchemaVersion
        } else {
            CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
        },
        extensions = extensions,
        rawJson = rawJson,
    )

    companion object {
        fun from(document: RecordPayloadDocument): RecordPayloadDraft = RecordPayloadDraft(
            payload = document.payload,
            extensions = document.extensions,
            sourceSchemaVersion = document.schemaVersion,
            rawJson = document.rawJson,
        )
    }
}

/**
 * The only module allowed to translate between storage JSON and typed payloads.
 *
 * Schema v1 and v2 are read compatibly. Future and malformed documents remain
 * [UnknownPayload] and encode back to their original JSON text.
 */
object RecordPayloadCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun decode(
        type: RecordType,
        payloadJson: String,
        schemaVersion: Int,
    ): RecordPayloadDocument {
        if (schemaVersion > CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION) {
            return unknown(type, payloadJson, schemaVersion, "future schema")
        }
        val objectValue = runCatching {
            json.parseToJsonElement(payloadJson).jsonObject
        }.getOrElse {
            return unknown(type, payloadJson, schemaVersion, "malformed JSON")
        }
        val knownKeys = knownKeys(type)
        val extensions = objectValue.filterKeys { it !in knownKeys }
        val payload = runCatching { decodeKnown(type, objectValue) }.getOrElse {
            return unknown(type, payloadJson, schemaVersion, "invalid typed payload")
        }
        return RecordPayloadDocument(
            type = type,
            payload = payload,
            schemaVersion = schemaVersion,
            extensions = extensions,
            rawJson = payloadJson,
        )
    }

    fun encode(document: RecordPayloadDocument): String {
        val unknown = document.payload as? UnknownPayload
        if (unknown != null) return document.rawJson ?: unknown.rawJson
        require(document.payload.type == document.type)
        val known = encodeKnown(document.payload)
        return JsonObject(
            LinkedHashMap<String, JsonElement>().apply {
                putAll(document.extensions.filterKeys { it !in knownKeys(document.type) })
                putAll(known)
            },
        ).toString()
    }

    fun validate(payload: RecordPayload): List<String> = buildList {
        when (payload) {
            is NursingPayload -> {
                if (payload.leftMinutes < 0 || payload.rightMinutes < 0) add("喂养时长不能为负数")
                if (payload.leftMinutes + payload.rightMinutes <= 0) add("至少记录一侧时长")
                if (payload.order !in setOf("L", "R", "LR", "RL")) add("不支持的喂养顺序")
                if (payload.recordMode !in setOf("start", "end")) add("不支持的记录时刻模式")
            }
            is MilkPayload -> {
                if (payload.amountMl !in 1..999) add("奶量需在 1–999 ml 之间")
                if (payload.preparedMl != null && payload.preparedMl !in 0..999) {
                    add("冲调量需在 0–999 ml 之间")
                }
                if (payload.durationMinutes != null && payload.durationMinutes !in 0..1_440) {
                    add("时长需在 0–1440 分钟之间")
                }
            }
            is PeePayload -> if (payload.amount !in 1..3) add("尿量必须是 1–3")
            is StoolPayload -> validateStool(payload.amount, payload.consistency, payload.color)
            is BothDiaperPayload -> {
                if (payload.peeAmount !in 1..3) add("尿量必须是 1–3")
                validateStool(payload.stoolAmount, payload.stoolConsistency, payload.stoolColor)
            }
            is TemperaturePayload -> if (payload.celsius !in 34.0..43.0) add("体温超出可记录范围")
            is MeasurementPayload -> {
                GrowthMeasurementFacts.validationError(
                    payload.type,
                    GrowthMeasurementFacts.displayValue(payload),
                )?.let(::add)
            }
            is TextPayload -> if (payload.body.isBlank()) add("正文不能为空")
            is SymptomPayload -> if (payload.severity !in 1..3) add("症状程度必须是 1–3")
            is MedicinePayload -> if (payload.name.isBlank()) add("药品名称不能为空")
            is HospitalPayload -> if (payload.reason.isBlank()) add("就诊原因不能为空")
            is FoodPayload -> if (payload.content.isBlank()) add("内容不能为空")
            is VaccinePayload -> if (payload.name.isBlank()) add("疫苗名称不能为空")
            is CustomPayload -> if (payload.titleSnapshot.isBlank()) add("自定义标题不能为空")
            is OtherPayload -> if (payload.title.isBlank()) add("标题不能为空")
            is EmptyPayload, is SleepPayload, is UnknownPayload -> Unit
        }
    }

    private fun MutableList<String>.validateStool(amount: Int, consistency: Int, color: Int) {
        if (amount !in 1..4) add("便量必须是 1–4")
        if (consistency !in 1..4) add("软硬必须是 1–4")
        if (color !in 0..7) add("颜色必须是 0–7")
    }

    private fun decodeKnown(type: RecordType, value: JsonObject): RecordPayload = when (type) {
        RecordType.NURSING -> NursingPayload(
            leftMinutes = value.int("left_min"),
            rightMinutes = value.int("right_min"),
            order = value.string("order").ifBlank { "LR" },
            amountMl = value.optionalInt("amount_ml"),
            recordMode = value.string("record_mode").ifBlank { "end" },
        )
        in MILK_TYPES -> MilkPayload(
            type = type,
            amountMl = value.int("amount_ml"),
            preparedMl = value.optionalInt("prepared_ml"),
            durationMinutes = value.optionalInt("duration_min"),
        )
        RecordType.PEE -> PeePayload(value.int("pee_amount", 2))
        RecordType.POOP -> StoolPayload(
            amount = value.int("stool_amount", 3),
            consistency = value.int("stool_consistency", 3),
            color = value.int("stool_color"),
        )
        RecordType.BOTH_DIAPER -> BothDiaperPayload(
            peeAmount = value.int("pee_amount", 2),
            stoolAmount = value.int("stool_amount", 3),
            stoolConsistency = value.int("stool_consistency", 3),
            stoolColor = value.int("stool_color"),
        )
        RecordType.SLEEP -> SleepPayload(
            isNap = value.boolean("is_nap"),
            anomaly = value.boolean("anomaly_flag"),
        )
        RecordType.TEMPERATURE -> TemperaturePayload(
            celsius = value.double("celsius", value.double("value", 36.5)),
        )
        RecordType.MEMO, RecordType.DIARY -> TextPayload(
            type = type,
            body = value.string("body"),
            photos = value.stringList("photos"),
        )
        RecordType.BATH, RecordType.WALK -> EmptyPayload(type)
        in SYMPTOM_TYPES -> SymptomPayload(
            type = type,
            severity = value.int("severity", 2),
            description = value.optionalString("description"),
        )
        RecordType.MEDICINE -> MedicinePayload(
            name = value.string("name"),
            dose = value.optionalString("dose"),
        )
        RecordType.HOSPITAL -> HospitalPayload(
            reason = value.string("reason"),
            advice = value.optionalString("advice"),
        )
        RecordType.OTHER -> OtherPayload(
            title = value.string("title"),
            detail = value.optionalString("detail"),
        )
        in MEASUREMENT_TYPES -> MeasurementPayload(
            type = type,
            value = value.double("value"),
            unit = value.string("unit").ifBlank { if (type == RecordType.WEIGHT) "g" else "cm" },
        )
        in FOOD_TYPES -> FoodPayload(
            type = type,
            content = value.string("content"),
            amount = value.optionalString("amount"),
        )
        RecordType.VACCINE -> VaccinePayload(
            name = value.string("name"),
            batch = value.optionalString("batch"),
        )
        RecordType.CUSTOM -> CustomPayload(
            titleSnapshot = value.string("title"),
            detail = value.optionalString("detail"),
            customItemId = value.optionalLong("custom_item_id"),
            iconSlot = value.optionalInt("icon_slot"),
        )
        else -> error("No payload decoder registered for $type")
    }

    private fun encodeKnown(payload: RecordPayload): JsonObject = buildJsonObject {
        when (payload) {
            is NursingPayload -> {
                put("left_min", payload.leftMinutes)
                put("right_min", payload.rightMinutes)
                put("order", payload.order)
                payload.amountMl?.let { put("amount_ml", it) }
                put("record_mode", payload.recordMode)
            }
            is MilkPayload -> {
                put("amount_ml", payload.amountMl)
                payload.preparedMl?.let { put("prepared_ml", it) }
                payload.durationMinutes?.let { put("duration_min", it) }
            }
            is PeePayload -> put("pee_amount", payload.amount)
            is StoolPayload -> putStool(payload.amount, payload.consistency, payload.color)
            is BothDiaperPayload -> {
                put("pee_amount", payload.peeAmount)
                putStool(payload.stoolAmount, payload.stoolConsistency, payload.stoolColor)
            }
            is SleepPayload -> {
                put("is_nap", payload.isNap)
                if (payload.anomaly) put("anomaly_flag", true)
            }
            is TemperaturePayload -> put("celsius", payload.celsius)
            is TextPayload -> {
                put("body", payload.body)
                if (payload.photos.isNotEmpty()) {
                    put("photos", JsonArray(payload.photos.map(::JsonPrimitive)))
                }
            }
            is SymptomPayload -> {
                put("severity", payload.severity)
                payload.description?.let { put("description", it) }
            }
            is MedicinePayload -> {
                put("name", payload.name)
                payload.dose?.let { put("dose", it) }
            }
            is HospitalPayload -> {
                put("reason", payload.reason)
                payload.advice?.let { put("advice", it) }
            }
            is OtherPayload -> {
                put("title", payload.title)
                payload.detail?.let { put("detail", it) }
            }
            is MeasurementPayload -> {
                put("value", payload.value.normalizedNumber())
                put("unit", payload.unit)
            }
            is FoodPayload -> {
                put("content", payload.content)
                payload.amount?.let { put("amount", it) }
            }
            is VaccinePayload -> {
                put("name", payload.name)
                payload.batch?.let { put("batch", it) }
            }
            is CustomPayload -> {
                put("title", payload.titleSnapshot)
                payload.detail?.let { put("detail", it) }
                payload.customItemId?.let { put("custom_item_id", it) }
                payload.iconSlot?.let { put("icon_slot", it) }
            }
            is EmptyPayload -> Unit
            is UnknownPayload -> error("UnknownPayload must preserve its raw document")
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putStool(
        amount: Int,
        consistency: Int,
        color: Int,
    ) {
        put("stool_amount", amount)
        put("stool_consistency", consistency)
        put("stool_color", color)
    }

    private fun knownKeys(type: RecordType): Set<String> = when (type) {
        RecordType.NURSING -> setOf("left_min", "right_min", "order", "amount_ml", "record_mode")
        in MILK_TYPES -> setOf("amount_ml", "prepared_ml", "duration_min")
        RecordType.PEE -> setOf("pee_amount")
        RecordType.POOP -> STOOL_KEYS
        RecordType.BOTH_DIAPER -> STOOL_KEYS + "pee_amount"
        RecordType.SLEEP -> setOf("is_nap", "anomaly_flag")
        RecordType.TEMPERATURE -> setOf("celsius", "value")
        RecordType.MEMO, RecordType.DIARY -> setOf("body", "photos")
        RecordType.BATH, RecordType.WALK -> emptySet()
        in SYMPTOM_TYPES -> setOf("severity", "description")
        RecordType.MEDICINE -> setOf("name", "dose")
        RecordType.HOSPITAL -> setOf("reason", "advice")
        RecordType.OTHER -> setOf("title", "detail")
        in MEASUREMENT_TYPES -> setOf("value", "unit")
        in FOOD_TYPES -> setOf("content", "amount")
        RecordType.VACCINE -> setOf("name", "batch")
        RecordType.CUSTOM -> setOf("title", "detail", "custom_item_id", "icon_slot")
        else -> error("No payload schema registered for $type")
    }

    private fun unknown(
        type: RecordType,
        rawJson: String,
        schemaVersion: Int,
        reason: String,
    ) = RecordPayloadDocument(
        type = type,
        payload = UnknownPayload(type, rawJson, schemaVersion, reason),
        schemaVersion = schemaVersion,
        rawJson = rawJson,
    )
}

private fun JsonObject.int(key: String, default: Int = 0): Int =
    get(key)?.jsonPrimitive?.intOrNull ?: default

private fun JsonObject.optionalInt(key: String): Int? =
    get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.intOrNull

private fun JsonObject.optionalLong(key: String): Long? =
    get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull?.toLongOrNull()

private fun JsonObject.double(key: String, default: Double = 0.0): Double =
    get(key)?.jsonPrimitive?.doubleOrNull ?: default

private fun JsonObject.boolean(key: String, default: Boolean = false): Boolean =
    get(key)?.jsonPrimitive?.booleanOrNull ?: default

private fun JsonObject.string(key: String): String =
    get(key)?.jsonPrimitive?.contentOrNull.orEmpty()

private fun JsonObject.optionalString(key: String): String? =
    get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull

private fun JsonObject.stringList(key: String): List<String> =
    runCatching {
        get(key)?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
    }.getOrDefault(emptyList())

private fun Double.normalizedNumber(): JsonPrimitive =
    if (this % 1.0 == 0.0) JsonPrimitive(toLong()) else JsonPrimitive(this)

private val MILK_TYPES = setOf(
    RecordType.FORMULA,
    RecordType.PUMPED_FEED,
    RecordType.PUMP_EXPRESS,
)
private val SYMPTOM_TYPES = setOf(
    RecordType.COUGH,
    RecordType.RASH,
    RecordType.VOMIT,
    RecordType.INJURY,
)
private val MEASUREMENT_TYPES = setOf(
    RecordType.HEIGHT,
    RecordType.WEIGHT,
    RecordType.HEAD,
    RecordType.CHEST,
    RecordType.FOOT_SIZE,
)
private val FOOD_TYPES = setOf(
    RecordType.BABY_FOOD,
    RecordType.SNACK,
    RecordType.DRINK,
)
private val STOOL_KEYS = setOf("stool_amount", "stool_consistency", "stool_color")
