package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.payloadBool
import com.lezi.babylog.domain.payloadDouble
import com.lezi.babylog.domain.payloadInt
import kotlin.math.roundToInt

internal enum class QuickRecordMode {
    Nursing,
    Milk,
    Pee,
    Poop,
    BothDiaper,
    Sleep,
    Temperature,
    Text,
    Simple,
    Interval,
    Symptom,
    Medicine,
    Hospital,
    CustomText,
    Measurement,
    Food,
    Vaccine,
}

internal enum class SleepDraftAction {
    SleepDown,
    WakeUp,
    Manual,
}

internal enum class TemperatureUnit {
    Celsius,
    Fahrenheit,
}

/**
 * Exhaustive form routing for every visible record type.
 *
 * Keeping this mapping separate from the Composable makes it impossible for a
 * newly-added record button to silently fall back to an immediate write.
 */
internal val RecordType.quickRecordMode: QuickRecordMode
    get() = when (this) {
        RecordType.NURSING -> QuickRecordMode.Nursing
        RecordType.FORMULA, RecordType.PUMPED_FEED, RecordType.PUMP_EXPRESS ->
            QuickRecordMode.Milk
        RecordType.PEE -> QuickRecordMode.Pee
        RecordType.POOP -> QuickRecordMode.Poop
        RecordType.BOTH_DIAPER -> QuickRecordMode.BothDiaper
        RecordType.SLEEP -> QuickRecordMode.Sleep
        RecordType.TEMPERATURE -> QuickRecordMode.Temperature
        RecordType.MEMO, RecordType.DIARY -> QuickRecordMode.Text
        RecordType.BATH -> QuickRecordMode.Simple
        RecordType.WALK -> QuickRecordMode.Interval
        RecordType.COUGH, RecordType.RASH, RecordType.VOMIT, RecordType.INJURY ->
            QuickRecordMode.Symptom
        RecordType.MEDICINE -> QuickRecordMode.Medicine
        RecordType.HOSPITAL -> QuickRecordMode.Hospital
        RecordType.OTHER, RecordType.CUSTOM -> QuickRecordMode.CustomText
        RecordType.HEIGHT, RecordType.WEIGHT, RecordType.HEAD, RecordType.CHEST,
        RecordType.FOOT_SIZE,
        -> QuickRecordMode.Measurement
        RecordType.BABY_FOOD, RecordType.SNACK, RecordType.DRINK -> QuickRecordMode.Food
        RecordType.VACCINE -> QuickRecordMode.Vaccine
    }

internal data class QuickRecordSaveCommand(
    val existingRecordId: Long?,
    val type: RecordType,
    val timestamp: Long,
    val endTimestamp: Long?,
    val note: String?,
    val payloadJson: String,
)

/**
 * One draft model shared by every quick-record sheet.
 *
 * [timestamp] is captured when the user taps a record button. It is never
 * recomputed at confirmation time, which keeps delayed confirmations honest.
 */
internal data class QuickRecordDraft(
    val type: RecordType,
    val timestamp: Long,
    val endTimestamp: Long? = null,
    val existingRecordId: Long? = null,
    val sourcePayloadJson: String = "{}",
    val note: String = "",
    val amountMl: Int = 120,
    val preparedMl: String = "",
    val durationMin: String = "",
    val leftMin: String = "0",
    val rightMin: String = "0",
    val order: String = "LR",
    val nursingAmountMl: String = "",
    val peeAmount: Int = 2,
    val stoolAmount: Int = 3,
    val stoolConsistency: Int = 3,
    val stoolColor: Int = 0,
    val sleepAction: SleepDraftAction? = null,
    val isNap: Boolean = false,
    val temperature: String = "36.5",
    val temperatureUnit: TemperatureUnit = TemperatureUnit.Celsius,
    val body: String = "",
    val severity: Int = 2,
    val description: String = "",
    val medicineName: String = "",
    val medicineDose: String = "",
    val hospitalReason: String = "",
    val hospitalAdvice: String = "",
    val customTitle: String = "",
    val customDetail: String = "",
    val measurementValue: String = "",
    val foodContent: String = "",
    val foodAmount: String = "",
    val vaccineName: String = "",
    val vaccineBatch: String = "",
) {
    val mode: QuickRecordMode
        get() = type.quickRecordMode

    fun validationError(nowMillis: Long = System.currentTimeMillis()): String? {
        if (timestamp > nowMillis) return "记录时刻不能晚于现在"
        if (note.length > 200) return "备注最多 200 字"
        return when (mode) {
            QuickRecordMode.Nursing -> {
                val left = leftMin.toIntOrNull() ?: -1
                val right = rightMin.toIntOrNull() ?: -1
                when {
                    left < 0 || right < 0 -> "左右时长请输入非负整数"
                    left + right <= 0 -> "请填写左侧或右侧喂养时长"
                    else -> null
                }
            }
            QuickRecordMode.Milk -> when {
                amountMl !in 1..999 -> "奶量需在 1–999 ml 之间"
                preparedMl.isNotBlank() && preparedMl.toIntOrNull() !in 0..999 ->
                    "冲调量需在 0–999 ml 之间"
                durationMin.isNotBlank() && durationMin.toIntOrNull() !in 0..1_440 ->
                    "时长需在 0–1440 分钟之间"
                else -> null
            }
            QuickRecordMode.Pee -> {
                if (peeAmount in 1..3) null else "请选择尿量"
            }
            QuickRecordMode.Poop -> stoolValidationError()
            QuickRecordMode.BothDiaper -> {
                if (peeAmount !in 1..3) "请选择尿量" else stoolValidationError()
            }
            QuickRecordMode.Sleep -> when (sleepAction) {
                // 准备休息：结束可空（进睡眠中）；若填写则一次记完完整区间。
                SleepDraftAction.SleepDown -> when {
                    endTimestamp == null -> null
                    endTimestamp <= timestamp -> "醒来时刻必须晚于睡下时刻"
                    endTimestamp > nowMillis -> "醒来时刻不能晚于现在"
                    else -> null
                }
                SleepDraftAction.WakeUp -> when {
                    endTimestamp == null -> "请选择醒来时刻"
                    endTimestamp <= timestamp -> "醒来时刻必须晚于睡下时刻"
                    endTimestamp > nowMillis -> "醒来时刻不能晚于现在"
                    else -> null
                }
                SleepDraftAction.Manual, null -> when {
                    endTimestamp == null -> "请选择醒来时刻"
                    endTimestamp <= timestamp -> "醒来时刻必须晚于睡下时刻"
                    endTimestamp > nowMillis -> "醒来时刻不能晚于现在"
                    else -> null
                }
            }
            QuickRecordMode.Temperature -> {
                val raw = temperature.toDoubleOrNull()
                val celsius = raw?.let {
                    if (temperatureUnit == TemperatureUnit.Fahrenheit) {
                        (it - 32.0) * 5.0 / 9.0
                    } else {
                        it
                    }
                }
                if (celsius == null || celsius !in 34.0..43.0) {
                    "请输入合理的体温"
                } else {
                    null
                }
            }
            QuickRecordMode.Text -> {
                if (body.isBlank()) {
                    if (type == RecordType.DIARY) "请填写日记正文" else "请填写内容"
                } else {
                    null
                }
            }
            QuickRecordMode.Simple -> null
            QuickRecordMode.Interval -> when {
                endTimestamp == null -> "请选择结束时刻"
                endTimestamp <= timestamp -> "结束时刻必须晚于开始时刻"
                endTimestamp > nowMillis -> "结束时刻不能晚于现在"
                else -> null
            }
            QuickRecordMode.Symptom -> {
                if (severity in 1..3) null else "请选择程度"
            }
            QuickRecordMode.Medicine -> {
                if (medicineName.isBlank()) "请填写药品名称" else null
            }
            QuickRecordMode.Hospital -> {
                if (hospitalReason.isBlank()) "请填写就诊原因" else null
            }
            QuickRecordMode.CustomText -> {
                if (customTitle.isBlank()) "请填写标题" else null
            }
            QuickRecordMode.Measurement -> {
                val value = measurementValue.toDoubleOrNull()
                when {
                    value == null || value <= 0.0 -> "请填写有效数值"
                    type == RecordType.WEIGHT && value > 100.0 -> "体重需在 0–100 kg 之间"
                    type != RecordType.WEIGHT && value > 250.0 -> "测量值需在 0–250 cm 之间"
                    else -> null
                }
            }
            QuickRecordMode.Food -> {
                if (foodContent.isBlank()) "请填写内容" else null
            }
            QuickRecordMode.Vaccine -> {
                if (vaccineName.isBlank()) "请填写疫苗名称" else null
            }
        }
    }

    fun toSaveCommand(): QuickRecordSaveCommand = QuickRecordSaveCommand(
        existingRecordId = existingRecordId,
        type = type,
        timestamp = timestamp,
        endTimestamp = endTimestamp,
        note = note.trim().ifBlank { null },
        payloadJson = payloadJson(),
    )

    fun confirmLabel(): String = when {
        sleepAction == SleepDraftAction.WakeUp -> "确认醒来"
        existingRecordId != null -> "保存修改"
        sleepAction == SleepDraftAction.SleepDown && endTimestamp != null -> "确认记录"
        sleepAction == SleepDraftAction.SleepDown -> "确认睡下"
        else -> "确认记录"
    }

    val isEditing: Boolean
        get() = existingRecordId != null && sleepAction != SleepDraftAction.WakeUp

    private fun stoolValidationError(): String? = when {
        stoolAmount !in 1..4 -> "请选择便量"
        stoolConsistency !in 1..4 -> "请选择软硬"
        stoolColor !in 0..7 -> "请选择颜色"
        else -> null
    }

    private fun payloadJson(): String = when (mode) {
        QuickRecordMode.Nursing -> sourcePayloadJson.patchJson(
            "left_min" to jsonNumber(leftMin.toIntOrNull() ?: 0),
            "right_min" to jsonNumber(rightMin.toIntOrNull() ?: 0),
            "order" to jsonString(order),
            "amount_ml" to nursingAmountMl.toIntOrNull()?.let(::jsonNumber),
        )
        QuickRecordMode.Milk -> sourcePayloadJson.patchJson(
            "amount_ml" to jsonNumber(amountMl),
            "prepared_ml" to preparedMl.toIntOrNull()?.let(::jsonNumber),
            "duration_min" to durationMin.toIntOrNull()?.let(::jsonNumber),
        )
        QuickRecordMode.Pee -> sourcePayloadJson.patchJson(
            "pee_amount" to jsonNumber(peeAmount),
        )
        QuickRecordMode.Poop -> stoolPayload()
        QuickRecordMode.BothDiaper -> sourcePayloadJson.patchJson(
            "pee_amount" to jsonNumber(peeAmount),
            "stool_amount" to jsonNumber(stoolAmount),
            "stool_consistency" to jsonNumber(stoolConsistency),
            "stool_color" to jsonNumber(stoolColor),
        )
        QuickRecordMode.Sleep -> sourcePayloadJson.patchJson("is_nap" to isNap.toString())
        QuickRecordMode.Temperature -> {
            val raw = temperature.toDoubleOrNull() ?: 36.5
            val celsius = if (temperatureUnit == TemperatureUnit.Fahrenheit) {
                (raw - 32.0) * 5.0 / 9.0
            } else {
                raw
            }
            sourcePayloadJson.patchJson("celsius" to jsonNumber(trimNumber(celsius)))
        }
        QuickRecordMode.Text -> sourcePayloadJson.patchJson("body" to jsonString(body.trim()))
        QuickRecordMode.Simple -> sourcePayloadJson.normalizedJsonObject()
        QuickRecordMode.Interval -> sourcePayloadJson.patchJson(
            "duration_min" to endTimestamp?.let {
                jsonNumber(((it - timestamp).coerceAtLeast(0L) / 60_000L).toInt())
            },
        )
        QuickRecordMode.Symptom -> sourcePayloadJson.patchJson(
            "severity" to jsonNumber(severity),
            "description" to description.trim().takeIf(String::isNotBlank)?.let(::jsonString),
        )
        QuickRecordMode.Medicine -> sourcePayloadJson.patchJson(
            "name" to jsonString(medicineName.trim()),
            "dose" to medicineDose.trim().takeIf(String::isNotBlank)?.let(::jsonString),
        )
        QuickRecordMode.Hospital -> sourcePayloadJson.patchJson(
            "reason" to jsonString(hospitalReason.trim()),
            "advice" to hospitalAdvice.trim().takeIf(String::isNotBlank)?.let(::jsonString),
        )
        QuickRecordMode.CustomText -> sourcePayloadJson.patchJson(
            "title" to jsonString(customTitle.trim()),
            "detail" to customDetail.trim().takeIf(String::isNotBlank)?.let(::jsonString),
        )
        QuickRecordMode.Measurement -> {
            val input = measurementValue.toDoubleOrNull() ?: 0.0
            if (type == RecordType.WEIGHT) {
                sourcePayloadJson.patchJson(
                    "value" to jsonNumber((input * 1_000.0).roundToInt()),
                    "unit" to jsonString("g"),
                )
            } else {
                sourcePayloadJson.patchJson(
                    "value" to jsonNumber(trimNumber(input)),
                    "unit" to jsonString("cm"),
                )
            }
        }
        QuickRecordMode.Food -> sourcePayloadJson.patchJson(
            "content" to jsonString(foodContent.trim()),
            "amount" to foodAmount.trim().takeIf(String::isNotBlank)?.let(::jsonString),
        )
        QuickRecordMode.Vaccine -> sourcePayloadJson.patchJson(
            "name" to jsonString(vaccineName.trim()),
            "batch" to vaccineBatch.trim().takeIf(String::isNotBlank)?.let(::jsonString),
        )
    }

    private fun stoolPayload(): String = sourcePayloadJson.patchJson(
        "stool_amount" to jsonNumber(stoolAmount),
        "stool_consistency" to jsonNumber(stoolConsistency),
        "stool_color" to jsonNumber(stoolColor),
    )

    companion object {
        fun create(
            type: RecordType,
            timestamp: Long,
            lastAmountMl: Int? = null,
            historical: Boolean = false,
        ): QuickRecordDraft = QuickRecordDraft(
            type = type,
            timestamp = timestamp,
            amountMl = lastAmountMl?.takeIf { it in 1..999 }
                ?: if (type == RecordType.PUMP_EXPRESS) 60 else 120,
            sleepAction = if (type == RecordType.SLEEP) {
                if (historical) SleepDraftAction.Manual else SleepDraftAction.SleepDown
            } else {
                null
            },
            customTitle = if (type == RecordType.CUSTOM) "自定义项目" else "",
        )

        fun wakeSleep(openSleep: Record, clickedAt: Long): QuickRecordDraft = QuickRecordDraft(
            type = RecordType.SLEEP,
            timestamp = openSleep.timestamp,
            endTimestamp = clickedAt,
            existingRecordId = openSleep.id,
            sourcePayloadJson = openSleep.payloadJson,
            note = openSleep.note.orEmpty(),
            sleepAction = SleepDraftAction.WakeUp,
            isNap = payloadBool(openSleep.payloadJson, "is_nap"),
        )

        fun fromRecord(record: Record): QuickRecordDraft {
            val payload = record.payloadJson
            val rawMeasurement = payloadDouble(payload, "value")
            val measurementValue = when {
                rawMeasurement == null -> ""
                record.type == RecordType.WEIGHT &&
                    payload.jsonStringField("unit") == "g" ->
                    trimNumber(rawMeasurement / 1_000.0).toString()
                else -> trimNumber(rawMeasurement).toString()
            }
            return QuickRecordDraft(
                type = record.type,
                timestamp = record.timestamp,
                endTimestamp = record.endTimestamp,
                existingRecordId = record.id,
                sourcePayloadJson = payload,
                note = record.note.orEmpty(),
                amountMl = payloadInt(payload, "amount_ml").takeIf { it in 1..999 }
                    ?: if (record.type == RecordType.PUMP_EXPRESS) 60 else 120,
                preparedMl = payload.optionalIntText("prepared_ml"),
                durationMin = payload.optionalIntText("duration_min"),
                leftMin = payloadInt(payload, "left_min").coerceAtLeast(0).toString(),
                rightMin = payloadInt(payload, "right_min").coerceAtLeast(0).toString(),
                order = payload.jsonStringField("order").takeIf {
                    it in setOf("L", "R", "LR", "RL")
                } ?: "LR",
                nursingAmountMl = payload.optionalIntText("amount_ml"),
                peeAmount = payloadInt(payload, "pee_amount").takeIf { it in 1..3 } ?: 2,
                stoolAmount = payloadInt(payload, "stool_amount").takeIf { it in 1..4 } ?: 3,
                stoolConsistency = payloadInt(payload, "stool_consistency")
                    .takeIf { it in 1..4 } ?: 3,
                stoolColor = payloadInt(payload, "stool_color").coerceIn(0, 7),
                sleepAction = if (record.type == RecordType.SLEEP) {
                    if (record.endTimestamp == null) {
                        SleepDraftAction.SleepDown
                    } else {
                        SleepDraftAction.Manual
                    }
                } else {
                    null
                },
                isNap = payloadBool(payload, "is_nap"),
                temperature = payloadDouble(payload, "celsius")?.let(::trimNumber)?.toString()
                    ?: "36.5",
                body = payload.jsonStringField("body"),
                severity = payloadInt(payload, "severity").takeIf { it in 1..3 } ?: 2,
                description = payload.jsonStringField("description"),
                medicineName = payload.jsonStringField("name"),
                medicineDose = payload.jsonStringField("dose"),
                hospitalReason = payload.jsonStringField("reason"),
                hospitalAdvice = payload.jsonStringField("advice"),
                customTitle = payload.jsonStringField("title"),
                customDetail = payload.jsonStringField("detail"),
                measurementValue = measurementValue,
                foodContent = payload.jsonStringField("content"),
                foodAmount = payload.jsonStringField("amount"),
                vaccineName = payload.jsonStringField("name"),
                vaccineBatch = payload.jsonStringField("batch"),
            )
        }
    }
}

private fun String.patchJson(vararg updates: Pair<String, String?>): String {
    val fields = parseTopLevelJsonObject(this)
    updates.forEach { (key, value) ->
        if (value == null) {
            fields.remove(key)
        } else {
            fields[key] = value
        }
    }
    return fields.entries.joinToString(separator = ",", prefix = "{", postfix = "}") {
        "${jsonString(it.key)}:${it.value}"
    }
}

private fun String.normalizedJsonObject(): String =
    patchJson()

private fun parseTopLevelJsonObject(source: String): LinkedHashMap<String, String> {
    val result = linkedMapOf<String, String>()
    val text = source.trim()
    if (!text.startsWith("{") || !text.endsWith("}")) return result
    var index = 1

    fun skipWhitespaceAndCommas() {
        while (index < text.lastIndex && (text[index].isWhitespace() || text[index] == ',')) {
            index += 1
        }
    }

    while (index < text.lastIndex) {
        skipWhitespaceAndCommas()
        if (index >= text.lastIndex || text[index] != '"') break
        val keyStart = ++index
        var escaped = false
        while (index < text.lastIndex) {
            val char = text[index]
            if (!escaped && char == '"') break
            escaped = !escaped && char == '\\'
            if (char != '\\') escaped = false
            index += 1
        }
        if (index >= text.lastIndex) break
        val key = text.substring(keyStart, index).decodeJsonStringBody()
        index += 1
        while (index < text.lastIndex && text[index].isWhitespace()) index += 1
        if (index >= text.lastIndex || text[index] != ':') break
        index += 1
        while (index < text.lastIndex && text[index].isWhitespace()) index += 1
        val valueStart = index
        var inString = false
        var valueEscaped = false
        var objectDepth = 0
        var arrayDepth = 0
        while (index < text.lastIndex) {
            val char = text[index]
            if (inString) {
                if (!valueEscaped && char == '"') inString = false
                valueEscaped = !valueEscaped && char == '\\'
                if (char != '\\') valueEscaped = false
            } else {
                when (char) {
                    '"' -> inString = true
                    '{' -> objectDepth += 1
                    '}' -> if (objectDepth > 0) objectDepth -= 1 else break
                    '[' -> arrayDepth += 1
                    ']' -> if (arrayDepth > 0) arrayDepth -= 1
                    ',' -> if (objectDepth == 0 && arrayDepth == 0) break
                }
            }
            index += 1
        }
        val rawValue = text.substring(valueStart, index).trim()
        if (rawValue.isNotEmpty()) result[key] = rawValue
        if (index < text.lastIndex && text[index] == ',') index += 1
    }
    return result
}

private fun String.optionalIntText(key: String): String =
    Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+)")
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        .orEmpty()

private fun String.jsonStringField(key: String): String =
    Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"")
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        ?.decodeJsonStringBody()
        .orEmpty()

private fun String.decodeJsonStringBody(): String = buildString {
    var index = 0
    while (index < this@decodeJsonStringBody.length) {
        val char = this@decodeJsonStringBody[index]
        if (char != '\\' || index == this@decodeJsonStringBody.lastIndex) {
            append(char)
            index += 1
            continue
        }
        val escaped = this@decodeJsonStringBody[index + 1]
        append(
            when (escaped) {
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                '\\' -> '\\'
                '"' -> '"'
                else -> escaped
            },
        )
        index += 2
    }
}

private fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(char)
        }
    }
    append('"')
}

private fun jsonNumber(value: Number): String = value.toString()

private fun trimNumber(value: Double): Number =
    if (value % 1.0 == 0.0) value.toLong() else "%.2f".format(java.util.Locale.US, value).toDouble()
