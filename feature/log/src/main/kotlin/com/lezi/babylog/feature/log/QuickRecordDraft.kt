package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.BothDiaperPayload
import com.lezi.babylog.core.model.CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
import com.lezi.babylog.core.model.CustomPayload
import com.lezi.babylog.core.model.EmptyPayload
import com.lezi.babylog.core.model.FoodPayload
import com.lezi.babylog.core.model.GrowthMeasurementFacts
import com.lezi.babylog.core.model.HospitalPayload
import com.lezi.babylog.core.model.MeasurementPayload
import com.lezi.babylog.core.model.MedicinePayload
import com.lezi.babylog.core.model.MilkPayload
import com.lezi.babylog.core.model.NursingPayload
import com.lezi.babylog.core.model.OtherPayload
import com.lezi.babylog.core.model.PeePayload
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordPayload
import com.lezi.babylog.core.model.RecordPayloadCodec
import com.lezi.babylog.core.model.RecordPayloadDocument
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.core.model.SleepPayload
import com.lezi.babylog.core.model.StoolPayload
import com.lezi.babylog.core.model.SymptomPayload
import com.lezi.babylog.core.model.TemperaturePayload
import com.lezi.babylog.core.model.TextPayload
import com.lezi.babylog.core.model.UnknownPayload
import com.lezi.babylog.core.model.VaccinePayload
import com.lezi.babylog.core.ui.formatRecordDuration

internal const val FUTURE_TIME_WARNING = "不能选未来时刻"
private const val SLEEP_END_MISSING_WARNING = "请选择醒来时刻"
private const val SLEEP_END_ORDER_WARNING = "醒来须晚于睡下"

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

internal sealed interface IntervalDurationPreview {
    val text: String

    data class Duration(override val text: String) : IntervalDurationPreview

    data class Warning(override val text: String) : IntervalDurationPreview
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
        RecordType.WALK -> QuickRecordMode.Simple
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
    val schemaVersion: Int,
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
    val sourceSchemaVersion: Int = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
    val note: String = "",
    val recentNotes: List<String> = emptyList(),
    val amountMl: Int = 120,
    val recentAmountMl: List<Int> = emptyList(),
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
    val photos: List<String> = emptyList(),
    val sourcePhotos: List<String> = emptyList(),
    val severity: Int = 2,
    val description: String = "",
    val medicineName: String = "",
    val medicineDose: String = "",
    val hospitalReason: String = "",
    val hospitalAdvice: String = "",
    val customTitle: String = "",
    val customDetail: String = "",
    val customItemId: Long? = null,
    val customIconSlot: Int? = null,
    val measurementValue: String = "",
    val foodContent: String = "",
    val foodAmount: String = "",
    val vaccineName: String = "",
    val vaccineBatch: String = "",
) {
    val mode: QuickRecordMode
        get() = type.quickRecordMode

    /**
     * User-facing state rendered directly below start/end controls.
     *
     * A sleep-down draft with no end is an intentionally open interval, so it
     * has neither a duration nor a warning. Every other interval mode requires
     * a valid end before confirmation.
     */
    fun intervalDurationPreview(
        nowMillis: Long = RecordTime.currentTimeMillis(),
    ): IntervalDurationPreview? {
        if (mode != QuickRecordMode.Sleep) return null
        RecordTime.pointError(timestamp, nowMillis)?.let {
            return IntervalDurationPreview.Warning(it)
        }
        val end = endTimestamp
        if (end == null) {
            if (mode == QuickRecordMode.Sleep && sleepAction == SleepDraftAction.SleepDown) {
                return null
            }
            return IntervalDurationPreview.Warning(
                SLEEP_END_MISSING_WARNING,
            )
        }
        val decisionError = RecordTime.intervalError(timestamp, end, nowMillis)
        if (decisionError != null) {
            return IntervalDurationPreview.Warning(decisionError)
        }

        return IntervalDurationPreview.Duration(
            "时长 ${formatRecordDuration((end - timestamp) / 60_000L)}",
        )
    }

    fun visibleIntervalDurationPreview(
        nowMillis: Long = RecordTime.currentTimeMillis(),
        isDirty: Boolean,
        attemptedConfirm: Boolean,
    ): IntervalDurationPreview? {
        val preview = intervalDurationPreview(nowMillis)
        if (preview !is IntervalDurationPreview.Warning) return preview
        val isMissingRequiredEnd = preview.text in setOf(
            SLEEP_END_MISSING_WARNING,
        )
        return preview.takeIf {
            isMissingRequiredEnd || shouldShowValidation(isDirty, attemptedConfirm)
        }
    }

    fun canConfirm(nowMillis: Long = RecordTime.currentTimeMillis()): Boolean =
        validationError(nowMillis) == null

    /**
     * Validates a clock-dialog end selection without mutating this draft.
     * Sleep adds the one actionable cross-day hint only for end <= start.
     */
    fun endTimeRejectionMessage(
        candidateEndTimestamp: Long,
        nowMillis: Long = RecordTime.currentTimeMillis(),
    ): String? {
        val warning = copy(endTimestamp = candidateEndTimestamp)
            .intervalDurationPreview(nowMillis) as? IntervalDurationPreview.Warning
            ?: return null
        return if (
            mode == QuickRecordMode.Sleep &&
            warning.text == SLEEP_END_ORDER_WARNING
        ) {
            "${warning.text}。跨天请先把日期改为次日"
        } else {
            warning.text
        }
    }

    /**
     * Footer copy is deferred until the user interacts, and interval warnings
     * stay at the time controls instead of being repeated at the bottom.
     */
    fun footerValidationError(
        nowMillis: Long = RecordTime.currentTimeMillis(),
        isDirty: Boolean,
        attemptedConfirm: Boolean,
    ): String? {
        if (!shouldShowValidation(isDirty, attemptedConfirm)) return null
        val validation = validationError(nowMillis) ?: return null
        val intervalWarning = intervalDurationPreview(nowMillis)
            as? IntervalDurationPreview.Warning
        return validation.takeUnless { it == intervalWarning?.text }
    }

    fun validationError(nowMillis: Long = RecordTime.currentTimeMillis()): String? {
        RecordTime.pointError(timestamp, nowMillis)?.let { return it }
        if (note.length > 200) return "备注最多 200 字"
        if (existingRecordId != null && sourcePayloadDocument().isUnknown) {
            return "此记录格式暂不支持安全编辑，原始数据已保留"
        }
        val draftFormatError = when (mode) {
            QuickRecordMode.Nursing -> {
                val left = leftMin.toIntOrNull()
                val right = rightMin.toIntOrNull()
                "左右时长请输入非负整数".takeIf {
                    left == null || right == null || left < 0 || right < 0
                }
            }
            QuickRecordMode.Milk -> when {
                preparedMl.isNotBlank() && preparedMl.toIntOrNull() == null ->
                    "冲调量需在 0–999 ml 之间"
                durationMin.isNotBlank() && durationMin.toIntOrNull() == null ->
                    "时长需在 0–1440 分钟之间"
                else -> null
            }
            QuickRecordMode.Sleep -> intervalValidationError(nowMillis)
            QuickRecordMode.Temperature -> {
                "请输入合理的体温".takeIf { temperature.toDoubleOrNull() == null }
            }
            else -> null
        }
        if (draftFormatError != null) return draftFormatError
        val payloadError = RecordPayloadCodec.validate(payloadDocument().payload).firstOrNull()
        return payloadError?.let(::payloadValidationMessage)
    }

    fun toSaveCommand(): QuickRecordSaveCommand = QuickRecordSaveCommand(
        existingRecordId = existingRecordId,
        type = type,
        timestamp = timestamp,
        endTimestamp = endTimestamp.takeUnless { type == RecordType.WALK },
        note = note.trim().ifBlank { null },
        payloadJson = payloadDocument().let(RecordPayloadCodec::encode),
        schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
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

    private fun intervalValidationError(nowMillis: Long): String? =
        (intervalDurationPreview(nowMillis) as? IntervalDurationPreview.Warning)?.text

    private fun payloadValidationMessage(error: String): String = when (error) {
        "至少记录一侧时长" -> "请填写左侧或右侧喂养时长"
        "尿量必须是 1–3" -> "请选择尿量"
        "便量必须是 1–4" -> "请选择便量"
        "软硬必须是 1–4" -> "请选择软硬"
        "颜色必须是 0–7" -> "请选择颜色"
        "体温超出可记录范围" -> "请输入合理的体温"
        "正文不能为空" -> if (type == RecordType.DIARY) "请填写日记正文" else "请填写内容"
        "症状程度必须是 1–3" -> "请选择程度"
        "药品名称不能为空" -> "请填写药品名称"
        "就诊原因不能为空" -> "请填写就诊原因"
        "标题不能为空", "自定义标题不能为空" -> "请填写标题"
        "内容不能为空" -> "请填写内容"
        "疫苗名称不能为空" -> "请填写疫苗名称"
        else -> error
    }

    private fun shouldShowValidation(isDirty: Boolean, attemptedConfirm: Boolean): Boolean =
        isDirty || attemptedConfirm

    private fun payloadDocument(): RecordPayloadDocument {
        val source = sourcePayloadDocument()
        val payload: RecordPayload = when (mode) {
        QuickRecordMode.Nursing -> NursingPayload(
            leftMinutes = leftMin.toIntOrNull() ?: 0,
            rightMinutes = rightMin.toIntOrNull() ?: 0,
            order = order,
            amountMl = nursingAmountMl.toIntOrNull(),
            recordMode = (source.payload as? NursingPayload)?.recordMode ?: "end",
        )
        QuickRecordMode.Milk -> MilkPayload(
            type = type,
            amountMl = amountMl,
            preparedMl = preparedMl.toIntOrNull(),
            durationMinutes = durationMin.toIntOrNull(),
        )
        QuickRecordMode.Pee -> PeePayload(amount = peeAmount)
        QuickRecordMode.Poop -> StoolPayload(
            amount = stoolAmount,
            consistency = stoolConsistency,
            color = stoolColor,
        )
        QuickRecordMode.BothDiaper -> BothDiaperPayload(
            peeAmount = peeAmount,
            stoolAmount = stoolAmount,
            stoolConsistency = stoolConsistency,
            stoolColor = stoolColor,
        )
        QuickRecordMode.Sleep -> SleepPayload(
            isNap = isNap,
            anomaly = (source.payload as? SleepPayload)?.anomaly ?: false,
        )
        QuickRecordMode.Temperature -> {
            val raw = temperature.toDoubleOrNull() ?: 36.5
            val celsius = if (temperatureUnit == TemperatureUnit.Fahrenheit) {
                (raw - 32.0) * 5.0 / 9.0
            } else {
                raw
            }
            TemperaturePayload(celsius)
        }
        QuickRecordMode.Text -> TextPayload(
            type = type,
            body = body.trim(),
            photos = photos,
        )
        QuickRecordMode.Simple -> EmptyPayload(type)
        QuickRecordMode.Symptom -> SymptomPayload(
            type = type,
            severity = severity,
            description = description.trim().ifBlank { null },
        )
        QuickRecordMode.Medicine -> MedicinePayload(
            name = medicineName.trim(),
            dose = medicineDose.trim().ifBlank { null },
        )
        QuickRecordMode.Hospital -> HospitalPayload(
            reason = hospitalReason.trim(),
            advice = hospitalAdvice.trim().ifBlank { null },
        )
        QuickRecordMode.CustomText -> if (type == RecordType.CUSTOM) {
            CustomPayload(
                titleSnapshot = customTitle.trim(),
                detail = customDetail.trim().ifBlank { null },
                customItemId = customItemId,
                iconSlot = customIconSlot,
            )
        } else {
            OtherPayload(
                title = customTitle.trim(),
                detail = customDetail.trim().ifBlank { null },
            )
        }
        QuickRecordMode.Measurement -> {
            val input = measurementValue.toDoubleOrNull() ?: 0.0
            GrowthMeasurementFacts.payload(type, input) ?: MeasurementPayload(type)
        }
        QuickRecordMode.Food -> FoodPayload(
            type = type,
            content = foodContent.trim(),
            amount = foodAmount.trim().ifBlank { null },
        )
        QuickRecordMode.Vaccine -> VaccinePayload(
            name = vaccineName.trim(),
            batch = vaccineBatch.trim().ifBlank { null },
        )
        }
        return RecordPayloadDocument(
            type = type,
            payload = payload,
            schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
            extensions = source.extensions,
        )
    }

    private fun sourcePayloadDocument(): RecordPayloadDocument =
        RecordPayloadCodec.decode(type, sourcePayloadJson, sourceSchemaVersion)

    companion object {
        fun create(
            type: RecordType,
            timestamp: Long,
            lastAmountMl: Int? = null,
            recentAmountMl: List<Int> = emptyList(),
            recentNotes: List<String> = emptyList(),
            historical: Boolean = false,
        ): QuickRecordDraft = QuickRecordDraft(
            type = type,
            timestamp = timestamp,
            amountMl = lastAmountMl?.takeIf { it in 1..999 }
                ?: recentAmountMl.firstOrNull { it in 1..999 }
                ?: if (type == RecordType.PUMP_EXPRESS) 60 else 120,
            recentAmountMl = recentAmountMl.filter { it in 1..999 }.distinct().take(3),
            recentNotes = recentNotes.map(String::trim).filter(String::isNotBlank).distinct().take(5),
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
            sourceSchemaVersion = openSleep.schemaVersion,
            note = openSleep.note.orEmpty(),
            sleepAction = SleepDraftAction.WakeUp,
            isNap = (openSleep.payload.payload as? SleepPayload)?.isNap ?: false,
        )

        fun fromRecord(record: Record): QuickRecordDraft {
            val document = record.payload
            val payload = document.payload
            val measurement = payload as? MeasurementPayload
            val rawMeasurement = measurement?.value
            val measurementValue = when {
                rawMeasurement == null -> ""
                record.type == RecordType.WEIGHT &&
                    measurement.unit == "g" ->
                    trimNumber(GrowthMeasurementFacts.displayValue(measurement)).toString()
                else -> trimNumber(rawMeasurement).toString()
            }
            val milk = payload as? MilkPayload
            val nursing = payload as? NursingPayload
            val stool = payload as? StoolPayload
            val both = payload as? BothDiaperPayload
            val text = payload as? TextPayload
            val symptom = payload as? SymptomPayload
            val medicine = payload as? MedicinePayload
            val hospital = payload as? HospitalPayload
            val other = payload as? OtherPayload
            val custom = payload as? CustomPayload
            val food = payload as? FoodPayload
            val vaccine = payload as? VaccinePayload
            return QuickRecordDraft(
                type = record.type,
                timestamp = record.timestamp,
                endTimestamp = record.endTimestamp.takeUnless { record.type == RecordType.WALK },
                existingRecordId = record.id,
                sourcePayloadJson = record.payloadJson,
                sourceSchemaVersion = record.schemaVersion,
                note = record.note.orEmpty(),
                amountMl = milk?.amountMl?.takeIf { it in 1..999 }
                    ?: if (record.type == RecordType.PUMP_EXPRESS) 60 else 120,
                preparedMl = milk?.preparedMl?.toString().orEmpty(),
                durationMin = milk?.durationMinutes?.toString().orEmpty(),
                leftMin = nursing?.leftMinutes?.coerceAtLeast(0)?.toString() ?: "0",
                rightMin = nursing?.rightMinutes?.coerceAtLeast(0)?.toString() ?: "0",
                order = nursing?.order?.takeIf {
                    it in setOf("L", "R", "LR", "RL")
                } ?: "LR",
                nursingAmountMl = nursing?.amountMl?.toString().orEmpty(),
                peeAmount = when (payload) {
                    is PeePayload -> payload.amount
                    is BothDiaperPayload -> payload.peeAmount
                    else -> 2
                }.takeIf { it in 1..3 } ?: 2,
                stoolAmount = (stool?.amount ?: both?.stoolAmount)
                    ?.takeIf { it in 1..4 } ?: 3,
                stoolConsistency = (stool?.consistency ?: both?.stoolConsistency)
                    ?.takeIf { it in 1..4 } ?: 3,
                stoolColor = (stool?.color ?: both?.stoolColor ?: 0).coerceIn(0, 7),
                sleepAction = if (record.type == RecordType.SLEEP) {
                    if (record.endTimestamp == null) {
                        SleepDraftAction.SleepDown
                    } else {
                        SleepDraftAction.Manual
                    }
                } else {
                    null
                },
                isNap = (payload as? SleepPayload)?.isNap ?: false,
                temperature = (payload as? TemperaturePayload)?.celsius
                    ?.let(::trimNumber)?.toString()
                    ?: "36.5",
                body = text?.body.orEmpty(),
                photos = text?.photos.orEmpty(),
                sourcePhotos = text?.photos.orEmpty(),
                severity = symptom?.severity?.takeIf { it in 1..3 } ?: 2,
                description = symptom?.description.orEmpty(),
                medicineName = medicine?.name.orEmpty(),
                medicineDose = medicine?.dose.orEmpty(),
                hospitalReason = hospital?.reason.orEmpty(),
                hospitalAdvice = hospital?.advice.orEmpty(),
                customTitle = custom?.titleSnapshot ?: other?.title.orEmpty(),
                customDetail = custom?.detail ?: other?.detail.orEmpty(),
                customItemId = custom?.customItemId,
                customIconSlot = custom?.iconSlot,
                measurementValue = measurementValue,
                foodContent = food?.content.orEmpty(),
                foodAmount = food?.amount.orEmpty(),
                vaccineName = vaccine?.name.orEmpty(),
                vaccineBatch = vaccine?.batch.orEmpty(),
            )
        }
    }
}

private fun trimNumber(value: Double): Number =
    if (value % 1.0 == 0.0) value.toLong() else "%.2f".format(java.util.Locale.US, value).toDouble()
