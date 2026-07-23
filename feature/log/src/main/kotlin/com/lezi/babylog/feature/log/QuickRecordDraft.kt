package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
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
                SleepDraftAction.SleepDown -> null
                SleepDraftAction.WakeUp -> when {
                    endTimestamp == null -> "请选择醒来时刻"
                    endTimestamp < timestamp -> "醒来时刻不能早于睡下时刻"
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

    fun confirmLabel(): String = when (sleepAction) {
        SleepDraftAction.SleepDown -> "确认睡下"
        SleepDraftAction.WakeUp -> "确认醒来"
        SleepDraftAction.Manual, null -> "确认记录"
    }

    private fun stoolValidationError(): String? = when {
        stoolAmount !in 1..4 -> "请选择便量"
        stoolConsistency !in 1..4 -> "请选择软硬"
        stoolColor !in 0..7 -> "请选择颜色"
        else -> null
    }

    private fun payloadJson(): String = when (mode) {
        QuickRecordMode.Nursing -> jsonObject(
            "left_min" to jsonNumber(leftMin.toIntOrNull() ?: 0),
            "right_min" to jsonNumber(rightMin.toIntOrNull() ?: 0),
            "order" to jsonString(order),
            "amount_ml" to nursingAmountMl.toIntOrNull()?.let(::jsonNumber),
        )
        QuickRecordMode.Milk -> jsonObject(
            "amount_ml" to jsonNumber(amountMl),
            "prepared_ml" to preparedMl.toIntOrNull()?.let(::jsonNumber),
            "duration_min" to durationMin.toIntOrNull()?.let(::jsonNumber),
        )
        QuickRecordMode.Pee -> jsonObject(
            "pee_amount" to jsonNumber(peeAmount),
        )
        QuickRecordMode.Poop -> stoolPayload()
        QuickRecordMode.BothDiaper -> jsonObject(
            "pee_amount" to jsonNumber(peeAmount),
            "stool_amount" to jsonNumber(stoolAmount),
            "stool_consistency" to jsonNumber(stoolConsistency),
            "stool_color" to jsonNumber(stoolColor),
        )
        QuickRecordMode.Sleep -> {
            if (sleepAction == SleepDraftAction.WakeUp) {
                sourcePayloadJson.withBooleanField("is_nap", isNap)
            } else {
                jsonObject("is_nap" to isNap.toString())
            }
        }
        QuickRecordMode.Temperature -> {
            val raw = temperature.toDoubleOrNull() ?: 36.5
            val celsius = if (temperatureUnit == TemperatureUnit.Fahrenheit) {
                (raw - 32.0) * 5.0 / 9.0
            } else {
                raw
            }
            jsonObject("celsius" to jsonNumber(trimNumber(celsius)))
        }
        QuickRecordMode.Text -> jsonObject("body" to jsonString(body.trim()))
        QuickRecordMode.Simple -> "{}"
        QuickRecordMode.Interval -> jsonObject(
            "duration_min" to endTimestamp?.let {
                jsonNumber(((it - timestamp).coerceAtLeast(0L) / 60_000L).toInt())
            },
        )
        QuickRecordMode.Symptom -> jsonObject(
            "severity" to jsonNumber(severity),
            "description" to description.trim().takeIf(String::isNotBlank)?.let(::jsonString),
        )
        QuickRecordMode.Medicine -> jsonObject(
            "name" to jsonString(medicineName.trim()),
            "dose" to medicineDose.trim().takeIf(String::isNotBlank)?.let(::jsonString),
        )
        QuickRecordMode.Hospital -> jsonObject(
            "reason" to jsonString(hospitalReason.trim()),
            "advice" to hospitalAdvice.trim().takeIf(String::isNotBlank)?.let(::jsonString),
        )
        QuickRecordMode.CustomText -> jsonObject(
            "title" to jsonString(customTitle.trim()),
            "detail" to customDetail.trim().takeIf(String::isNotBlank)?.let(::jsonString),
        )
        QuickRecordMode.Measurement -> {
            val input = measurementValue.toDoubleOrNull() ?: 0.0
            if (type == RecordType.WEIGHT) {
                jsonObject(
                    "value" to jsonNumber((input * 1_000.0).roundToInt()),
                    "unit" to jsonString("g"),
                )
            } else {
                jsonObject(
                    "value" to jsonNumber(trimNumber(input)),
                    "unit" to jsonString("cm"),
                )
            }
        }
        QuickRecordMode.Food -> jsonObject(
            "content" to jsonString(foodContent.trim()),
            "amount" to foodAmount.trim().takeIf(String::isNotBlank)?.let(::jsonString),
        )
        QuickRecordMode.Vaccine -> jsonObject(
            "name" to jsonString(vaccineName.trim()),
            "batch" to vaccineBatch.trim().takeIf(String::isNotBlank)?.let(::jsonString),
        )
    }

    private fun stoolPayload(): String = jsonObject(
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
            isNap = payloadBoolean(openSleep.payloadJson, "is_nap"),
        )
    }
}

private fun payloadBoolean(json: String, key: String): Boolean =
    Regex("\"${Regex.escape(key)}\"\\s*:\\s*(true|false)")
        .find(json)
        ?.groupValues
        ?.getOrNull(1)
        ?.toBooleanStrictOrNull()
        ?: false

private fun String.withBooleanField(key: String, value: Boolean): String {
    val source = trim().takeIf { it.startsWith("{") && it.endsWith("}") } ?: "{}"
    val field = "${jsonString(key)}:$value"
    val existing = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(true|false)")
    if (existing.containsMatchIn(source)) {
        return existing.replace(source, field)
    }
    return if (source == "{}") {
        "{$field}"
    } else {
        "${source.dropLast(1)},$field}"
    }
}

private fun jsonObject(vararg fields: Pair<String, String?>): String =
    fields
        .mapNotNull { (key, value) -> value?.let { "${jsonString(key)}:$it" } }
        .joinToString(separator = ",", prefix = "{", postfix = "}")

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
