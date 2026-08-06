package com.lezi.babylog.feature.log.composer
import com.lezi.babylog.core.model.BothDiaperPayload
import com.lezi.babylog.core.model.CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.CustomPayload
import com.lezi.babylog.core.model.EmptyPayload
import com.lezi.babylog.core.model.FoodPayload
import com.lezi.babylog.core.model.GrowthMeasurementFacts
import com.lezi.babylog.core.model.HospitalPayload
import com.lezi.babylog.core.model.MeasurementPayload
import com.lezi.babylog.core.model.MedicinePayload
import com.lezi.babylog.core.model.MilkPayload
import com.lezi.babylog.core.model.NursingConfirmField
import com.lezi.babylog.core.model.NursingConfirmInput
import com.lezi.babylog.core.model.NursingPayload
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
import com.lezi.babylog.core.model.isPlanableCarePlanType
import com.lezi.babylog.core.ui.formatRecordDuration
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

internal const val FUTURE_TIME_WARNING = "不能选未来时刻"
internal const val CARE_PLAN_TIME_NOT_FUTURE_WARNING = "护理计划时间必须晚于当前时间"
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
        RecordType.DIARY -> QuickRecordMode.Text
        RecordType.BATH -> QuickRecordMode.Simple
        RecordType.WALK -> QuickRecordMode.Simple
        RecordType.COUGH, RecordType.RASH, RecordType.VOMIT, RecordType.INJURY ->
            QuickRecordMode.Symptom
        RecordType.MEDICINE -> QuickRecordMode.Medicine
        RecordType.HOSPITAL -> QuickRecordMode.Hospital
        RecordType.CUSTOM -> QuickRecordMode.CustomText
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
) : java.io.Serializable

/**
 * One draft model shared by every quick-record sheet.
 *
 * [timestamp] is captured when the user taps a record button. It is never
 * recomputed at confirmation time, which keeps delayed confirmations honest.
 */
/** Visible Composer mode driven by request + draft time. */
internal enum class ComposerWorkMode {
    /** Current/past time → save a fact [Record]. */
    RecordFact,
    /** Future time on create → save a local [CarePlan]. */
    ScheduleCare,
    /** Edit an existing open plan (may become missed if time is past). */
    EditPlan,
    /** Fulfill an existing plan → confirm actual time and write linked Record. */
    FulfillPlan,
}

/** Stable creation intent carried from the entry point into the restorable draft. */
enum class ComposerCreateIntent {
    /** Preserve the shared Composer rule: future creates a plan, current/past creates a fact. */
    DeriveFromTimestamp,

    /** The caller explicitly opened “安排护理”; this intent must never degrade into a fact. */
    ScheduleCare,
}

internal data class QuickRecordDraft(
    val type: RecordType,
    val timestamp: Long,
    val endTimestamp: Long? = null,
    val existingRecordId: Long? = null,
    /**
     * When set with [editCarePlan]=false, Composer is fulfilling this plan.
     * When set with [editCarePlan]=true, Composer is editing the plan (no Record).
     */
    val carePlanId: Long? = null,
    val editCarePlan: Boolean = false,
    val createIntent: ComposerCreateIntent = ComposerCreateIntent.DeriveFromTimestamp,
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
    /**
     * Device-local B1 family-wake correction: open-start fields (sleep-down time /
     * is_nap) are locked; end, note, and photos remain editable. Domain save also
     * force-preserves open-start fields.
     */
    val restrictedSleepOpenFields: Boolean = false,
    val temperature: String = "36.5",
    val temperatureUnit: TemperatureUnit = TemperatureUnit.Celsius,
    val body: String = "",
    /** Ordered paths currently visible in Composer and submitted on confirm. */
    val photos: List<String> = emptyList(),
    /** Ordered persisted paths owned by the entity currently being edited. */
    val sourcePhotos: List<String> = emptyList(),
    /** Ordered plan paths borrowed by a fulfillment draft; Composer never deletes their bytes. */
    val borrowedPhotos: List<String> = emptyList(),
    /** Ordered private-file imports created by this draft, including ones later removed. */
    val ownedDraftPhotos: List<String> = emptyList(),
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
    /**
     * Per-plan “同步到系统日历” default on (ticket 21). Unconfigured setup does not
     * block save; CareLog falls back to Lezi reminders.
     */
    val projectToSystemCalendar: Boolean = true,
    val measurementValue: String = "",
    val foodContent: String = "",
    val foodAmount: String = "",
    val vaccineName: String = "",
    val vaccineBatch: String = "",
) : java.io.Serializable {
    val mode: QuickRecordMode
        get() = type.quickRecordMode

    /**
     * Derived work mode. Future timestamps on a brand-new draft schedule a plan;
     * fulfill allows actual times up to now + [RecordTime.FULFILLMENT_ACTUAL_TIME_SKEW_MILLIS]
     * and rejects beyond that via [validationResult].
     * Editing an existing fact into the future stays [RecordFact] until the user
     * explicitly confirms convert ([needsConvertToCarePlan]).
     */
    fun workMode(nowMillis: Long = RecordTime.currentTimeMillis()): ComposerWorkMode = when {
        isEditingCarePlan -> ComposerWorkMode.EditPlan
        carePlanId != null -> ComposerWorkMode.FulfillPlan
        existingRecordId != null -> ComposerWorkMode.RecordFact
        createIntent == ComposerCreateIntent.ScheduleCare -> ComposerWorkMode.ScheduleCare
        timestamp > nowMillis -> ComposerWorkMode.ScheduleCare
        else -> ComposerWorkMode.RecordFact
    }

    /** Zero for create/update facts; five minutes for plan fulfillment actual times. */
    fun actualTimeMaxFutureSkewMillis(
        nowMillis: Long = RecordTime.currentTimeMillis(),
    ): Long = if (workMode(nowMillis) == ComposerWorkMode.FulfillPlan) {
        RecordTime.FULFILLMENT_ACTUAL_TIME_SKEW_MILLIS
    } else {
        0L
    }

    /**
     * True when an existing fact record's main time is in the future and the type
     * can become a care plan. Ordinary [updateRecord] is blocked; save requires an
     * explicit 「转为护理计划」confirm that calls convert.
     */
    fun needsConvertToCarePlan(nowMillis: Long = RecordTime.currentTimeMillis()): Boolean {
        if (existingRecordId == null || carePlanId != null) return false
        if (timestamp <= nowMillis) return false
        return when {
            type == RecordType.CUSTOM -> customItemId != null && customItemId > 0L
            else -> type.isPlanableCarePlanType
        }
    }

    /** Validate a proposed start time against the write mode it would create. */
    fun startTimeRejectionMessage(
        candidateStartTimestamp: Long,
        candidateEndTimestamp: Long?,
        nowMillis: Long = RecordTime.currentTimeMillis(),
    ): String? {
        val candidate = copy(
            timestamp = candidateStartTimestamp,
            endTimestamp = candidateEndTimestamp,
        )
        val work = candidate.workMode(nowMillis)
        val planIntent =
            work == ComposerWorkMode.ScheduleCare ||
                work == ComposerWorkMode.EditPlan ||
                candidate.needsConvertToCarePlan(nowMillis)
        val skew = candidate.actualTimeMaxFutureSkewMillis(nowMillis)
        return when {
            work == ComposerWorkMode.ScheduleCare && candidateStartTimestamp <= nowMillis ->
                CARE_PLAN_TIME_NOT_FUTURE_WARNING
            !planIntent &&
                RecordTime.pointError(candidateStartTimestamp, nowMillis, skew) != null ->
                FUTURE_TIME_WARNING
            !planIntent &&
                candidateEndTimestamp != null &&
                RecordTime.pointError(candidateEndTimestamp, nowMillis, skew) != null ->
                FUTURE_TIME_WARNING
            else -> null
        }
    }

    fun workModeTitle(nowMillis: Long = RecordTime.currentTimeMillis()): String = when {
        needsConvertToCarePlan(nowMillis) -> "转为护理计划"
        else -> when (workMode(nowMillis)) {
            ComposerWorkMode.RecordFact -> "记录事实"
            ComposerWorkMode.ScheduleCare -> "安排护理"
            ComposerWorkMode.EditPlan -> "编辑护理计划"
            ComposerWorkMode.FulfillPlan -> "完成护理计划"
        }
    }

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
        val work = workMode(nowMillis)
        // Schedule/edit/convert plan is intent-only: no open/closed interval chrome.
        if (
            work == ComposerWorkMode.ScheduleCare ||
            work == ComposerWorkMode.EditPlan ||
            needsConvertToCarePlan(nowMillis)
        ) {
            return null
        }
        val skew = actualTimeMaxFutureSkewMillis(nowMillis)
        RecordTime.pointError(timestamp, nowMillis, skew)?.let {
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
        val decisionError = RecordTime.intervalError(timestamp, end, nowMillis, skew)
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
        validationResult(nowMillis) == null

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
     * Prefer the reason card ([ComposerConfirmChromeState]) for confirm-time explanation.
     */
    fun footerValidationError(
        nowMillis: Long = RecordTime.currentTimeMillis(),
        isDirty: Boolean,
        attemptedConfirm: Boolean,
    ): String? {
        if (!shouldShowValidation(isDirty, attemptedConfirm)) return null
        val validation = validationResult(nowMillis) ?: return null
        val intervalWarning = intervalDurationPreview(nowMillis)
            as? IntervalDurationPreview.Warning
        return validation.message.takeUnless { it == intervalWarning?.text }
    }

    fun validationError(nowMillis: Long = RecordTime.currentTimeMillis()): String? =
        validationResult(nowMillis)?.message

    /**
     * First blocking validation failure with a concrete message and target field.
     * Priority must stay stable so focus/highlight matches the reason card text.
     */
    fun validationResult(
        nowMillis: Long = RecordTime.currentTimeMillis(),
    ): ComposerValidationResult? {
        val work = workMode(nowMillis)
        val converting = needsConvertToCarePlan(nowMillis)
        if (work == ComposerWorkMode.ScheduleCare && timestamp <= nowMillis) {
            return ComposerValidationResult(
                CARE_PLAN_TIME_NOT_FUTURE_WARNING,
                ComposerInvalidField.StartTime,
            )
        }
        // Schedule/edit plan (and explicit record→plan convert) allow future plan time.
        // Fact writes: 0 skew. Fulfill: +5 minutes. Convert path skips this gate.
        if (
            work != ComposerWorkMode.ScheduleCare &&
            work != ComposerWorkMode.EditPlan &&
            !converting
        ) {
            val skew = actualTimeMaxFutureSkewMillis(nowMillis)
            RecordTime.pointError(timestamp, nowMillis, skew)?.let {
                return ComposerValidationResult(it, ComposerInvalidField.StartTime)
            }
        }
        if (note.length > 200) {
            return ComposerValidationResult("备注最多 200 字", ComposerInvalidField.Note)
        }
        if (existingRecordId != null && sourcePayloadDocument().isUnknown) {
            return ComposerValidationResult(
                "此记录格式暂不支持安全编辑，原始数据已保留",
                ComposerInvalidField.Unsupported,
            )
        }
        if (type == RecordType.CUSTOM && customItemId?.takeIf { it > 0L } == null) {
            return ComposerValidationResult(
                "具体自定义项目无效",
                ComposerInvalidField.Unsupported,
            )
        }
        // Convert uses schedule intent semantics (no timer / open-interval requirements).
        val scheduleOrEditPlan =
            work == ComposerWorkMode.ScheduleCare ||
                work == ComposerWorkMode.EditPlan ||
                converting
        when (mode) {
            QuickRecordMode.Nursing -> {
                nursingConfirmInput().validationIssue(
                    allowIntentOnly = scheduleOrEditPlan,
                )?.let { issue ->
                    val field = when (issue.field) {
                        NursingConfirmField.Duration -> ComposerInvalidField.NursingDuration
                        NursingConfirmField.Order -> ComposerInvalidField.NursingOrder
                        NursingConfirmField.Amount -> ComposerInvalidField.NursingAmount
                    }
                    return ComposerValidationResult(issue.message, field)
                }
            }
            QuickRecordMode.Milk -> when {
                preparedMl.isNotBlank() && preparedMl.toIntOrNull() == null ->
                    return ComposerValidationResult(
                        "冲调量需在 0–999 ml 之间",
                        ComposerInvalidField.MilkPrepared,
                    )
                durationMin.isNotBlank() && durationMin.toIntOrNull() == null ->
                    return ComposerValidationResult(
                        "时长需在 0–1440 分钟之间",
                        ComposerInvalidField.MilkDuration,
                    )
            }
            QuickRecordMode.Sleep -> {
                // Schedule/edit/convert plan is intent-only — no open interval and no end required.
                if (!scheduleOrEditPlan) {
                    intervalValidationResult(nowMillis)?.let { return it }
                }
            }
            QuickRecordMode.Temperature -> {
                if (temperature.toDoubleOrNull() == null) {
                    return ComposerValidationResult(
                        "请输入合理的体温",
                        ComposerInvalidField.Temperature,
                    )
                }
            }
            else -> Unit
        }
        val payloadError = RecordPayloadCodec.validate(
            payloadDocument().payload,
            allowIntentOnlyFeed = scheduleOrEditPlan,
        ).firstOrNull()
            ?: return null
        return payloadValidationResult(payloadError)
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

    fun confirmLabel(nowMillis: Long = RecordTime.currentTimeMillis()): String = when {
        isEditingCarePlan -> "保存计划"
        carePlanId != null -> "确认完成"
        needsConvertToCarePlan(nowMillis) -> "转为护理计划"
        workMode(nowMillis) == ComposerWorkMode.ScheduleCare -> "确认安排"
        sleepAction == SleepDraftAction.WakeUp -> "确认醒来"
        existingRecordId != null -> "保存修改"
        sleepAction == SleepDraftAction.SleepDown && endTimestamp != null -> "确认记录"
        sleepAction == SleepDraftAction.SleepDown -> "确认睡下"
        else -> "确认记录"
    }

    val isEditing: Boolean
        get() = existingRecordId != null && sleepAction != SleepDraftAction.WakeUp

    val isEditingCarePlan: Boolean
        get() = carePlanId != null && editCarePlan

    private fun intervalValidationError(nowMillis: Long): String? =
        intervalValidationResult(nowMillis)?.message

    private fun intervalValidationResult(nowMillis: Long): ComposerValidationResult? {
        val warning = intervalDurationPreview(nowMillis) as? IntervalDurationPreview.Warning
            ?: return null
        val field = when (warning.text) {
            FUTURE_TIME_WARNING -> {
                val end = endTimestamp
                val skew = actualTimeMaxFutureSkewMillis(nowMillis)
                when {
                    RecordTime.pointError(timestamp, nowMillis, skew) != null ->
                        ComposerInvalidField.StartTime
                    end != null &&
                        RecordTime.pointError(end, nowMillis, skew) != null ->
                        ComposerInvalidField.EndTime
                    else -> ComposerInvalidField.StartTime
                }
            }
            SLEEP_END_MISSING_WARNING, SLEEP_END_ORDER_WARNING -> ComposerInvalidField.EndTime
            else -> ComposerInvalidField.EndTime
        }
        return ComposerValidationResult(warning.text, field)
    }

    private fun payloadValidationResult(error: String): ComposerValidationResult {
        val message = when (error) {
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
        val field = when (error) {
            "至少记录一侧时长", "喂养时长不能为负数" -> ComposerInvalidField.NursingDuration
            "奶量需在 1–999 ml 之间" -> ComposerInvalidField.MilkAmount
            "冲调量需在 0–999 ml 之间" -> ComposerInvalidField.MilkPrepared
            "时长需在 0–1440 分钟之间" -> ComposerInvalidField.MilkDuration
            "尿量必须是 1–3" -> ComposerInvalidField.PeeAmount
            "便量必须是 1–4" -> ComposerInvalidField.StoolAmount
            "软硬必须是 1–4" -> ComposerInvalidField.StoolConsistency
            "颜色必须是 0–7" -> ComposerInvalidField.StoolColor
            "体温超出可记录范围" -> ComposerInvalidField.Temperature
            "正文不能为空" -> ComposerInvalidField.Body
            "症状程度必须是 1–3" -> ComposerInvalidField.Severity
            "药品名称不能为空" -> ComposerInvalidField.MedicineName
            "就诊原因不能为空" -> ComposerInvalidField.HospitalReason
            "标题不能为空", "自定义标题不能为空" -> ComposerInvalidField.CustomTitle
            "内容不能为空" -> ComposerInvalidField.FoodContent
            "疫苗名称不能为空" -> ComposerInvalidField.VaccineName
            "请填写有效数值", "体重需在 0–100 kg 之间", "测量值需在 0–250 cm 之间" ->
                ComposerInvalidField.MeasurementValue
            else -> when {
                error.contains("数值") || error.contains("测量") || error.contains("体重") ->
                    ComposerInvalidField.MeasurementValue
                else -> ComposerInvalidField.Unsupported
            }
        }
        return ComposerValidationResult(message, field)
    }

    private fun shouldShowValidation(isDirty: Boolean, attemptedConfirm: Boolean): Boolean =
        isDirty || attemptedConfirm

    private fun payloadDocument(): RecordPayloadDocument {
        val source = sourcePayloadDocument()
        val payload: RecordPayload = when (mode) {
        QuickRecordMode.Nursing -> nursingConfirmInput().toPayload(
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
        QuickRecordMode.CustomText -> CustomPayload(
            titleSnapshot = customTitle.trim(),
            detail = customDetail.trim().ifBlank { null },
            customItemId = requireNotNull(customItemId?.takeIf { it > 0L }) {
                "CUSTOM requires positive customItemId"
            },
            iconSlot = customIconSlot,
        )
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
        )
    }

    private fun nursingConfirmInput(): NursingConfirmInput = NursingConfirmInput(
        leftMinutes = leftMin,
        rightMinutes = rightMin,
        order = order,
        amountMl = nursingAmountMl,
    )

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
            customItemId: Long? = null,
            customTitle: String = "",
            customIconSlot: Int? = null,
            createIntent: ComposerCreateIntent = ComposerCreateIntent.DeriveFromTimestamp,
        ): QuickRecordDraft {
            require(type != RecordType.CUSTOM || customItemId?.takeIf { it > 0L } != null) {
                "CUSTOM requires positive customItemId"
            }
            return QuickRecordDraft(
                type = type,
                timestamp = timestamp,
                createIntent = createIntent,
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
                customTitle = customTitle,
                customItemId = customItemId,
                customIconSlot = customIconSlot,
            )
        }

        fun wakeSleep(openSleep: Record, clickedAt: Long): QuickRecordDraft {
            return QuickRecordDraft(
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
        }

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
            val nursingInput = nursing?.let(NursingConfirmInput::fromPayload)
            val stool = payload as? StoolPayload
            val both = payload as? BothDiaperPayload
            val text = payload as? TextPayload
            val symptom = payload as? SymptomPayload
            val medicine = payload as? MedicinePayload
            val hospital = payload as? HospitalPayload
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
                leftMin = nursingInput?.leftMinutes ?: "0",
                rightMin = nursingInput?.rightMinutes ?: "0",
                order = nursingInput?.order?.takeIf {
                    it in com.lezi.babylog.core.model.NURSING_ORDERS
                } ?: "LR",
                nursingAmountMl = nursingInput?.amountMl.orEmpty(),
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
                severity = symptom?.severity?.takeIf { it in 1..3 } ?: 2,
                description = symptom?.description.orEmpty(),
                medicineName = medicine?.name.orEmpty(),
                medicineDose = medicine?.dose.orEmpty(),
                hospitalReason = hospital?.reason.orEmpty(),
                hospitalAdvice = hospital?.advice.orEmpty(),
                customTitle = custom?.titleSnapshot.orEmpty(),
                customDetail = custom?.detail.orEmpty(),
                customItemId = custom?.customItemId,
                customIconSlot = custom?.iconSlot,
                measurementValue = measurementValue,
                foodContent = food?.content.orEmpty(),
                foodAmount = food?.amount.orEmpty(),
                vaccineName = vaccine?.name.orEmpty(),
                vaccineBatch = vaccine?.batch.orEmpty(),
            )
        }

        /**
         * Prefill fulfill Composer from the plan field snapshot; actual time defaults to now.
         * Plan photos land in a later ticket — field payload + note are the tracer surface.
         */
        fun fromCarePlan(
            plan: CarePlan,
            actualTimestamp: Long = RecordTime.currentTimeMillis(),
        ): QuickRecordDraft {
            val synthetic = Record(
                id = 0L,
                clientUuid = plan.clientUuid,
                babyId = plan.babyId,
                type = plan.type,
                timestamp = plan.scheduledAt,
                note = plan.note,
                payloadJson = plan.payloadJson,
                schemaVersion = plan.schemaVersion,
                updatedAt = plan.updatedAt,
            )
            val base = fromRecord(synthetic)
            return base.copy(
                existingRecordId = null,
                carePlanId = plan.id,
                editCarePlan = false,
                timestamp = actualTimestamp,
                endTimestamp = null,
                // Fulfill sleep defaults to “确认睡下” (open interval); user may set end.
                sleepAction = if (plan.type == RecordType.SLEEP) {
                    SleepDraftAction.SleepDown
                } else {
                    null
                },
                // Intent-only nursing plan may carry zero durations; fulfill fills them.
                leftMin = if (plan.type == RecordType.NURSING) {
                    base.leftMin.ifBlank { "0" }
                } else {
                    base.leftMin
                },
                rightMin = if (plan.type == RecordType.NURSING) {
                    base.rightMin.ifBlank { "0" }
                } else {
                    base.rightMin
                },
                customItemId = plan.customItemId ?: base.customItemId,
                projectToSystemCalendar = plan.systemCalendarProjectionEnabled,
            )
        }

        /** Prefill edit-plan Composer; timestamp stays on the plan's scheduledAt. */
        fun fromCarePlanForEdit(plan: CarePlan): QuickRecordDraft {
            val base = fromCarePlan(plan, actualTimestamp = plan.scheduledAt)
            val intentMilkAmount = (
                RecordPayloadCodec.decode(plan.type, plan.payloadJson, plan.schemaVersion).payload
                    as? MilkPayload
                )?.amountMl
            return base.copy(
                editCarePlan = true,
                // Editing intent must round-trip zero instead of inventing a fact amount.
                amountMl = intentMilkAmount ?: base.amountMl,
                // Edit plan is pure intent — no sleep-down action chrome.
                sleepAction = null,
            )
        }
    }
}

private fun trimNumber(value: Double): Number =
    if (value % 1.0 == 0.0) value.toLong() else "%.2f".format(java.util.Locale.US, value).toDouble()
