package com.lezi.babylog.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Internal CarePlan `note` prefix for family-shared next-feed intent (v1).
 * Not a user-facing note format. Hardcoded at runtime; the versioned build/test
 * contract is `config/next-feed-plan-marker.v1.json` (Kotlin + Rust).
 *
 * Recognition, strip, and encode live beside this constant so domain and sync
 * share one startsWith-based parse surface; the fixture locks samples for CI.
 */
const val NEXT_FEED_PLAN_MARKER = "[[lezi:next-feed:v1]]"

/** True when [note] carries the next-feed protocol marker as a strict prefix. */
fun isNextFeedPlanNote(note: String?): Boolean =
    note?.startsWith(NEXT_FEED_PLAN_MARKER) == true

/**
 * Plans may express an ordinary nursing intent before any actual duration exists.
 * Zero milk amounts remain exclusive to marked next-feed plans. Facts never use this policy.
 */
fun carePlanAllowsIntentOnlyFeed(type: RecordType, note: String?): Boolean =
    type == RecordType.NURSING || isNextFeedPlanNote(note)

/**
 * Visible remainder after stripping the next-feed marker.
 * Only meaningful when [isNextFeedPlanNote] is true (non-marker notes are not stripped).
 */
fun visibleNextFeedPlanNote(note: String?): String? = note
    ?.removePrefix(NEXT_FEED_PLAN_MARKER)
    ?.trimStart()
    ?.takeIf(String::isNotBlank)

/**
 * Compose a CarePlan note that carries next-feed intent.
 * Blank/null visible text → marker only; otherwise marker + single ASCII space + trimmed visible.
 * Encode is a client compose rule (domain uses this helper); recognition/strip are the shared wire contract.
 */
fun encodeNextFeedPlanNote(visibleNote: String?): String = buildString {
    append(NEXT_FEED_PLAN_MARKER)
    visibleNote?.trim()?.takeIf(String::isNotBlank)?.let { append(' ').append(it) }
}

const val CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION = 2

/** Maximum photos attachable to one care record (or care plan) via the shared note area. */
const val MAX_RECORD_PHOTOS = 3

/** Supported nursing side-order wire values (payload + composer + timer). */
val NURSING_ORDERS: Set<String> = setOf("L", "R", "LR", "RL")

/**
 * User-facing order chips shared by Composer and timer completion.
 * Labels must stay aligned so edit-after-timer never shows a missing selection.
 */
val NURSING_ORDER_CHOICES: List<Pair<String, String>> = listOf(
    "L" to "仅左",
    "LR" to "先左后右",
    "RL" to "先右后左",
    "R" to "仅右",
)

sealed interface RecordPayload : java.io.Serializable {
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
) : RecordPayload {
    init {
        require(type == RecordType.DIARY) {
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

    init {
        require(customItemId == null || customItemId > 0L) { "customItemId must be positive" }
    }
}

/** Closed typed-payload key table. One source for codec + dataflow-03 corpus consumers. */
data class PayloadKeySchema(
    val required: Set<String> = emptySet(),
    val optional: Set<String> = emptySet(),
    val localOnly: Set<String> = emptySet(),
) {
    val allowed: Set<String> get() = required + optional + localOnly
    val portable: Set<String> get() = required + optional
}

data class UnknownPayload(
    override val type: RecordType,
    val rawJson: String,
    val sourceSchemaVersion: Int,
    val reason: String,
) : RecordPayload

/** Decoded current storage document or an opaque fail-closed document. */
data class RecordPayloadDocument(
    val type: RecordType,
    val payload: RecordPayload,
    val schemaVersion: Int,
    val rawJson: String? = null,
) {
    init {
        require(payload.type == type) { "$type cannot store ${payload::class.simpleName}" }
    }

    val isUnknown: Boolean get() = payload is UnknownPayload
}

/**
 * Typed edit draft used by Composer adapters.
 */
data class RecordPayloadDraft(
    val payload: RecordPayload,
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
        rawJson = rawJson,
    )

    companion object {
        fun from(document: RecordPayloadDocument): RecordPayloadDraft = RecordPayloadDraft(
            payload = document.payload,
            sourceSchemaVersion = document.schemaVersion,
            rawJson = document.rawJson,
        )
    }
}

/**
 * The only module allowed to translate between storage JSON and typed payloads.
 *
 * Only the current schema is decoded into a typed payload. Unsupported and malformed
 * documents remain [UnknownPayload] and encode back to their original JSON text.
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
        if (schemaVersion != CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION) {
            val reason = if (schemaVersion > CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION) {
                "future schema"
            } else {
                "unsupported schema"
            }
            return unknown(type, payloadJson, schemaVersion, reason)
        }
        val objectValue = runCatching {
            json.parseToJsonElement(payloadJson).jsonObject
        }.getOrElse {
            return unknown(type, payloadJson, schemaVersion, "malformed JSON")
        }
        val schema = payloadKeySchema(type)
        val knownKeys = schema.allowed
        if (objectValue.keys.any { it !in knownKeys }) {
            return unknown(type, payloadJson, schemaVersion, "unknown fields")
        }
        if (schema.required.any { it !in objectValue }) {
            return unknown(type, payloadJson, schemaVersion, "missing required keys")
        }
        val payload = runCatching { decodeKnown(type, objectValue) }.getOrElse {
            return unknown(type, payloadJson, schemaVersion, "invalid typed payload")
        }
        return RecordPayloadDocument(
            type = type,
            payload = payload,
            schemaVersion = schemaVersion,
            rawJson = payloadJson,
        )
    }

    fun encode(document: RecordPayloadDocument): String {
        val unknown = document.payload as? UnknownPayload
        if (unknown != null) return document.rawJson ?: unknown.rawJson
        require(document.payload.type == document.type)
        return encodeKnown(document.payload).toString()
    }

    fun validate(
        payload: RecordPayload,
        allowIntentOnlyFeed: Boolean = false,
    ): List<String> = buildList {
        when (payload) {
            is NursingPayload -> {
                if (payload.leftMinutes < 0 || payload.rightMinutes < 0) add("喂养时长不能为负数")
                if (
                    payload.leftMinutes + payload.rightMinutes <= 0 &&
                    !allowIntentOnlyFeed
                ) add("至少记录一侧时长")
                if (payload.order !in NURSING_ORDERS) add("不支持的喂养顺序")
                if (payload.recordMode !in setOf("start", "end")) add("不支持的记录时刻模式")
            }
            is MilkPayload -> {
                val intentOnlyAmount = allowIntentOnlyFeed &&
                    payload.type in setOf(RecordType.FORMULA, RecordType.PUMPED_FEED) &&
                    payload.amountMl == 0
                if (payload.amountMl !in 1..999 && !intentOnlyAmount) {
                    add("奶量需在 1–999 ml 之间")
                }
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
            leftMinutes = value.requiredInt("left_min"),
            rightMinutes = value.requiredInt("right_min"),
            order = value.requiredString("order").also {
                require(it in setOf("L", "R", "LR", "RL"))
            },
            amountMl = value.optionalInt("amount_ml"),
            recordMode = value.requiredString("record_mode").also {
                require(it in setOf("start", "end"))
            },
        )
        in MILK_TYPES -> MilkPayload(
            type = type,
            amountMl = value.requiredInt("amount_ml"),
            preparedMl = if (type == RecordType.FORMULA) {
                value.optionalInt("prepared_ml")
            } else {
                null
            },
            durationMinutes = if (type == RecordType.FORMULA) {
                value.optionalInt("duration_min")
            } else {
                null
            },
        )
        RecordType.PEE -> PeePayload(value.optionalInt("pee_amount") ?: 2)
        RecordType.POOP -> StoolPayload(
            amount = value.optionalInt("stool_amount") ?: 3,
            consistency = value.optionalInt("stool_consistency") ?: 3,
            color = value.optionalInt("stool_color") ?: 0,
        )
        RecordType.BOTH_DIAPER -> BothDiaperPayload(
            peeAmount = value.optionalInt("pee_amount") ?: 2,
            stoolAmount = value.optionalInt("stool_amount") ?: 3,
            stoolConsistency = value.optionalInt("stool_consistency") ?: 3,
            stoolColor = value.optionalInt("stool_color") ?: 0,
        )
        RecordType.SLEEP -> SleepPayload(
            isNap = value.optionalBoolean("is_nap") ?: false,
            anomaly = value.requiredBoolean("anomaly_flag"),
        )
        RecordType.TEMPERATURE -> TemperaturePayload(
            celsius = value.requiredDouble("celsius"),
        )
        RecordType.DIARY -> TextPayload(
            type = type,
            body = value.requiredString("body"),
        )
        RecordType.BATH, RecordType.WALK -> EmptyPayload(type)
        in SYMPTOM_TYPES -> SymptomPayload(
            type = type,
            severity = value.requiredInt("severity"),
            description = value.optionalString("description"),
        )
        RecordType.MEDICINE -> MedicinePayload(
            name = value.requiredString("name"),
            dose = value.optionalString("dose"),
        )
        RecordType.HOSPITAL -> HospitalPayload(
            reason = value.requiredString("reason"),
            advice = value.optionalString("advice"),
        )
        in MEASUREMENT_TYPES -> MeasurementPayload(
            type = type,
            value = value.requiredDouble("value"),
            unit = value.requiredString("unit"),
        )
        in FOOD_TYPES -> FoodPayload(
            type = type,
            content = value.requiredString("content"),
            amount = value.optionalString("amount"),
        )
        RecordType.VACCINE -> VaccinePayload(
            name = value.requiredString("name"),
            batch = value.optionalString("batch"),
        )
        RecordType.CUSTOM -> CustomPayload(
            titleSnapshot = value.requiredString("title"),
            detail = value.optionalString("detail"),
            customItemId = value.optionalLong("custom_item_id")
                ?.also { require(it > 0L) { "CUSTOM custom_item_id must be positive" } },
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
                put("anomaly_flag", payload.anomaly)
            }
            is TemperaturePayload -> put("celsius", payload.celsius)
            is TextPayload -> put("body", payload.body)
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

    /**
     * Typed payload key table consumed by decode/encode and the dataflow-03 corpus.
     * [PayloadKeySchema.localOnly] keys may appear in Room documents but never on the portable wire.
     */
    fun payloadKeySchema(type: RecordType): PayloadKeySchema = when (type) {
        RecordType.NURSING -> PayloadKeySchema(
            required = setOf("left_min", "right_min", "order", "record_mode"),
            optional = setOf("amount_ml"),
        )
        RecordType.FORMULA -> PayloadKeySchema(
            required = setOf("amount_ml"),
            optional = setOf("prepared_ml", "duration_min"),
        )
        RecordType.PUMPED_FEED, RecordType.PUMP_EXPRESS -> PayloadKeySchema(
            required = setOf("amount_ml"),
        )
        RecordType.PEE -> PayloadKeySchema(optional = setOf("pee_amount"))
        RecordType.POOP -> PayloadKeySchema(optional = STOOL_KEYS)
        RecordType.BOTH_DIAPER -> PayloadKeySchema(optional = STOOL_KEYS + "pee_amount")
        RecordType.SLEEP -> PayloadKeySchema(
            required = setOf("anomaly_flag"),
            optional = setOf("is_nap"),
        )
        RecordType.TEMPERATURE -> PayloadKeySchema(required = setOf("celsius"))
        RecordType.DIARY -> PayloadKeySchema(required = setOf("body"))
        RecordType.BATH, RecordType.WALK -> PayloadKeySchema()
        in SYMPTOM_TYPES -> PayloadKeySchema(
            required = setOf("severity"),
            optional = setOf("description"),
        )
        RecordType.MEDICINE -> PayloadKeySchema(
            required = setOf("name"),
            optional = setOf("dose"),
        )
        RecordType.HOSPITAL -> PayloadKeySchema(
            required = setOf("reason"),
            optional = setOf("advice"),
        )
        in MEASUREMENT_TYPES -> PayloadKeySchema(required = setOf("value", "unit"))
        in FOOD_TYPES -> PayloadKeySchema(
            required = setOf("content"),
            optional = setOf("amount"),
        )
        RecordType.VACCINE -> PayloadKeySchema(
            required = setOf("name"),
            optional = setOf("batch"),
        )
        RecordType.CUSTOM -> PayloadKeySchema(
            required = setOf("title"),
            optional = setOf("detail", "icon_slot"),
            localOnly = setOf("custom_item_id"),
        )
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

private fun JsonObject.requiredInt(key: String): Int =
    get(key)?.jsonPrimitive?.intOrNull ?: error("$key must be an integer")

private fun JsonObject.optionalInt(key: String): Int? = if (key !in this) {
    null
} else {
    getValue(key).jsonPrimitive.intOrNull ?: error("$key must be an integer")
}

private fun JsonObject.optionalLong(key: String): Long? = if (key !in this) {
    null
} else {
    getValue(key).jsonPrimitive.contentOrNull?.toLongOrNull()
        ?: error("$key must be an integer")
}

private fun JsonObject.requiredDouble(key: String): Double =
    get(key)?.jsonPrimitive?.doubleOrNull?.takeIf(Double::isFinite)
        ?: error("$key must be a finite number")

private fun JsonObject.requiredBoolean(key: String): Boolean =
    get(key)?.jsonPrimitive?.booleanOrNull ?: error("$key must be a boolean")

private fun JsonObject.optionalBoolean(key: String): Boolean? = if (key !in this) {
    null
} else {
    getValue(key).jsonPrimitive.booleanOrNull ?: error("$key must be a boolean")
}

private fun JsonObject.requiredString(key: String): String {
    val primitive = get(key)?.jsonPrimitive ?: error("$key must be a string")
    require(primitive.isString) { "$key must be a string" }
    return primitive.content
}

private fun JsonObject.optionalString(key: String): String? = if (key !in this) {
    null
} else {
    val primitive = getValue(key).jsonPrimitive
    require(primitive.isString) { "$key must be a string" }
    primitive.content
}

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
