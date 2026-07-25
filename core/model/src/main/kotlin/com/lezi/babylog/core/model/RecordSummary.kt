package com.lezi.babylog.core.model

import java.util.Locale

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
    RecordType.MEMO -> "备注"
    RecordType.DIARY -> "日记"
    RecordType.BATH -> "洗澡"
    RecordType.WALK -> "散步"
    RecordType.COUGH -> "咳嗽"
    RecordType.RASH -> "发疹"
    RecordType.VOMIT -> "呕吐"
    RecordType.INJURY -> "受伤"
    RecordType.MEDICINE -> "用药"
    RecordType.HOSPITAL -> "就医"
    RecordType.OTHER -> "其他"
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

fun Record.payloadSummary(): String = when (val value = payload.payload) {
    is MilkPayload -> "${value.amountMl}ml"
    is NursingPayload -> listOf(
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
            "时长 ${formatDuration((it - timestamp) / 60_000L)}",
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
    is OtherPayload -> listOf(value.title, value.detail.orEmpty())
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

private fun peeAmountLabel(level: Int): String = when (level.coerceIn(1, 3)) {
    1 -> "小"
    2 -> "中"
    else -> "大"
}

private fun stoolSummary(amount: Int, consistency: Int, color: Int): String {
    val amountLabel = listOf("一点", "偏少", "正常", "偏多")
        .getOrElse(amount - 1) { "正常" }
    val consistencyLabel = listOf("稀", "偏软", "正常", "偏硬")
        .getOrElse(consistency - 1) { "正常" }
    val colorLabel = listOf("未选", "白", "黄", "橙", "褐", "绿", "红", "黑")
        .getOrElse(color) { "未选" }
    return "便量$amountLabel · $consistencyLabel · $colorLabel"
}

private fun formatDuration(minutes: Long): String {
    if (minutes <= 0) return "不足1分"
    val hours = minutes / 60
    val remaining = minutes % 60
    return when {
        hours == 0L -> "${remaining}分"
        remaining == 0L -> "${hours}小时"
        else -> "${hours}小时${remaining}分"
    }
}

private fun Double.normalizedText(): String =
    if (this % 1.0 == 0.0) toLong().toString()
    else "%.2f".format(Locale.US, this).trimEnd('0').trimEnd('.')
