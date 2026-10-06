package com.lezi.babylog.core.model

import java.util.Locale

const val UNKNOWN_RECORD_TYPE_LABEL: String = "未知记录"

/**
 * The storage-independent Chinese business summary shared by timeline,
 * search, export, and widgets. Notes are deliberately left to the caller.
 */
fun RecordType.businessLabel(): String = when (this) {
    RecordType.NURSING -> "母乳"
    RecordType.FORMULA -> "配方奶"
    RecordType.PUMPED_FEED -> "母乳瓶喂"
    RecordType.PUMP_EXPRESS -> "挤奶"
    RecordType.PEE -> "尿尿"
    RecordType.POOP -> "便便"
    RecordType.BOTH_DIAPER -> "尿+便"
    RecordType.SLEEP -> "睡眠"
    RecordType.TEMPERATURE -> "体温"
    RecordType.DIARY -> "日记"
    RecordType.BATH -> "洗澡"
    RecordType.WALK -> "散步"
    RecordType.COUGH -> "咳嗽"
    RecordType.RASH -> "发疹"
    RecordType.VOMIT -> "呕吐"
    RecordType.INJURY -> "受伤"
    RecordType.MEDICINE -> "用药"
    RecordType.HOSPITAL -> "就医"
    RecordType.HEIGHT -> "身高"
    RecordType.WEIGHT -> "体重"
    RecordType.BABY_FOOD -> "辅食"
    RecordType.SNACK -> "点心"
    RecordType.DRINK -> "饮料"
    RecordType.HEAD -> "头围"
    RecordType.CHEST -> "胸围"
    RecordType.FOOT_SIZE -> "足长"
    RecordType.VACCINE -> "疫苗"
    RecordType.CUSTOM -> "自定义"
}

/** User-visible label for a persisted/wire type key; never exposes an unknown internal key. */
fun recordTypeLabel(typeKey: String): String =
    RecordType.fromKey(typeKey)?.businessLabel() ?: UNKNOWN_RECORD_TYPE_LABEL

/**
 * User-visible project title for timeline, search, and export.
 * Concrete custom rows prefer their saved snapshot so renamed/deleted definitions
 * do not rewrite recorded facts.
 */
fun Record.displayLabel(): String = when (val value = payload.payload) {
    is CustomPayload -> value.titleSnapshot.trim().ifBlank { type.businessLabel() }
    else -> type.businessLabel()
}

/** Care plan list/calendar title; prefers custom name/icon snapshot in payload. */
fun CarePlan.displayLabel(): String {
    if (type != RecordType.CUSTOM) return type.businessLabel()
    val title = (payload.payload as? CustomPayload)?.titleSnapshot?.trim().orEmpty()
    return title.ifBlank { type.businessLabel() }
}

/**
 * Built-in types that start timers / open intervals on fulfill, not on schedule.
 * Plans for these types are intent-only until the caregiver confirms.
 */
val RecordType.isStatefulCarePlanType: Boolean
    get() = this == RecordType.SLEEP || this == RecordType.NURSING

/** Concrete types eligible for non-stateful local care plans. */
val RecordType.isPlanableNonStateful: Boolean
    get() = isAvailableForNewEntry && !isStatefulCarePlanType

/**
 * Concrete built-in types that may be scheduled as a CarePlan.
 * Includes intent-only nursing/sleep; bare custom is not a schedulable item.
 */
val RecordType.isPlanableCarePlanType: Boolean
    get() = isAvailableForNewEntry

/**
 * Concrete item identity recovered from a saved row when present.
 * Malformed custom payloads return null.
 */
fun Record.itemIdentity(): RecordItemIdentity? = when (type) {
    RecordType.CUSTOM -> {
        val id = (payload.payload as? CustomPayload)?.customItemId
        if (id != null) RecordItemIdentity.custom(id) else null
    }
    else -> RecordItemIdentity.BuiltIn(type)
}

fun Record.payloadSummary(): String = when (val value = payload.payload) {
    is MilkPayload -> listOf(
        "${value.amountMl}ml",
        value.preparedMl?.let { "冲调量${it}ml" },
        // Total elapsed minutes share formatRecordDuration with sleep/day totals.
        value.durationMinutes?.let { "耗时 ${formatRecordDuration(it.toLong())}" },
    ).filterNotNull().joinToString(" · ")
    is NursingPayload -> listOf(
        // Per-side minutes keep Chinese 「分」 so side-split copy stays searchable
        // ("左10分") and readable next to L/R labels; day/total minutes use
        // formatRecordDuration instead (see logDaySummaryDuration).
        "左${value.leftMinutes}分",
        "右${value.rightMinutes}分",
        when (value.order) {
            "L" -> "仅左侧"
            "R" -> "仅右侧"
            "LR" -> "先左后右"
            "RL" -> "先右后左"
            else -> null
        },
        value.amountMl?.let { "${it}ml" },
    ).filterNotNull().joinToString(" · ")
    is PeePayload -> "尿量${peeAmountLabel(value.amount)}"
    is StoolPayload -> stoolSummary(value.amount, value.consistency, value.color)
    is BothDiaperPayload -> {
        "尿量${peeAmountLabel(value.peeAmount)} · " +
            stoolSummary(value.stoolAmount, value.stoolConsistency, value.stoolColor)
    }
    is SleepPayload -> endTimestamp?.takeIf { it >= timestamp }?.let {
        listOf(
            if (value.isNap) "午睡" else null,
            "时长 ${formatRecordDuration((it - timestamp) / 60_000L)}",
        ).filterNotNull().joinToString(" · ")
    } ?: if (value.isNap) "午睡 · 进行中" else "进行中"
    is TemperaturePayload -> "${value.celsius.normalizedText()}℃"
    is TextPayload -> value.body
    is EmptyPayload -> ""
    is SymptomPayload -> listOf(
        when (value.severity) {
            1 -> "轻微"
            2 -> "一般"
            3 -> "明显"
            else -> ""
        },
        value.description.orEmpty(),
    ).filter(String::isNotBlank).joinToString(" · ")
    is MedicinePayload -> listOf(value.name, value.dose.orEmpty())
        .filter(String::isNotBlank).joinToString(" · ")
    is HospitalPayload -> listOf(value.reason, value.advice.orEmpty())
        .filter(String::isNotBlank).joinToString(" · ")
    is MeasurementPayload -> if (value.type == RecordType.WEIGHT) {
        val kilograms = if (value.unit == "g") value.value / 1_000.0 else value.value
        "${kilograms.normalizedText()}kg"
    } else {
        "${value.value.normalizedText()}${value.unit}"
    }
    is FoodPayload -> listOf(value.content, value.amount.orEmpty())
        .filter(String::isNotBlank).joinToString(" · ")
    is VaccinePayload -> listOf(value.name, value.batch.orEmpty())
        .filter(String::isNotBlank).joinToString(" · ")
    is CustomPayload -> listOf(value.titleSnapshot, value.detail.orEmpty())
        .filter(String::isNotBlank).joinToString(" · ")
    is UnknownPayload -> "未知格式（原始数据已保留）"
}

fun Record.visibleBusinessText(): String = listOf(
    payloadSummary(),
    note.orEmpty().trim(),
).filter(String::isNotBlank).joinToString(" · ")

/** Shared pee amount copy for summaries, composer chips, and presentation. */
fun peeAmountLabel(level: Int): String = when (level.coerceIn(1, 3)) {
    1 -> "小"
    2 -> "中"
    else -> "大"
}

/** Shared stool amount copy (1–4). */
fun stoolAmountLabel(level: Int): String = when (level.coerceIn(1, 4)) {
    1 -> "一点"
    2 -> "偏少"
    3 -> "正常"
    else -> "偏多"
}

/** Shared stool consistency copy (1–4). */
fun stoolConsistencyLabel(level: Int): String = when (level.coerceIn(1, 4)) {
    1 -> "稀"
    2 -> "偏软"
    3 -> "正常"
    else -> "偏硬"
}

/** Shared stool color copy (0–7). */
fun stoolColorLabel(index: Int): String = when (index.coerceIn(0, 7)) {
    0 -> "未选"
    1 -> "白"
    2 -> "黄"
    3 -> "橙"
    4 -> "褐"
    5 -> "绿"
    6 -> "红"
    else -> "黑"
}

private fun stoolSummary(amount: Int, consistency: Int, color: Int): String =
    "便量${stoolAmountLabel(amount)} · ${stoolConsistencyLabel(consistency)} · ${stoolColorLabel(color)}"

/**
 * Compact duration copy shared by saved-record summaries, draft previews, and day chips.
 * Single source for "0m" / "Nm" / "Nh" / "NhNm".
 */
fun formatRecordDuration(minutes: Long): String {
    if (minutes <= 0) return "0m"
    val hours = minutes / 60
    val remaining = minutes % 60
    return when {
        hours == 0L -> "${remaining}m"
        remaining == 0L -> "${hours}h"
        else -> "${hours}h${remaining}m"
    }
}

private fun Double.normalizedText(): String =
    if (this % 1.0 == 0.0) toLong().toString()
    else "%.2f".format(Locale.US, this).trimEnd('0').trimEnd('.')
