package com.lezi.babylog.domain

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType

internal const val NO_MATCHING_RECORD_TYPE = "__no_matching_record_type__"

internal fun String.toSqlLikePattern(): String = buildString {
    append('%')
    this@toSqlLikePattern.forEach { char ->
        if (char == '\\' || char == '%' || char == '_') append('\\')
        append(char)
    }
    append('%')
}

internal fun String.payloadSearchNeedle(): String {
    val withoutUnit = listOf("毫升", "分钟", "ml", "cm", "kg", "分", "℃")
        .firstNotNullOfOrNull { suffix ->
            takeIf { it.endsWith(suffix) }
                ?.removeSuffix(suffix)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
    return withoutUnit ?: this
}

private fun RecordType.searchTerms(): List<String> = when (this) {
    RecordType.NURSING -> listOf("母乳", "哺乳", "亲喂", "nursing")
    RecordType.FORMULA -> listOf("配方奶", "奶粉", "奶量", "formula")
    RecordType.PUMPED_FEED -> listOf("喂挤出乳", "瓶喂母乳", "pumped feed")
    RecordType.PUMP_EXPRESS -> listOf("挤奶", "吸奶", "pump express")
    RecordType.PEE -> listOf("尿尿", "小便", "换尿布", "pee")
    RecordType.POOP -> listOf("便便", "大便", "换尿布", "poop")
    RecordType.BOTH_DIAPER -> listOf("尿+便", "尿便", "换尿布", "both diaper")
    RecordType.SLEEP -> listOf("睡眠", "睡觉", "午睡", "sleep")
    RecordType.TEMPERATURE -> listOf("体温", "温度", "temperature")
    RecordType.MEMO -> listOf("备注", "备忘", "memo")
    RecordType.DIARY -> listOf("日记", "正文", "diary")
    RecordType.BATH -> listOf("洗澡", "沐浴", "bath")
    RecordType.WALK -> listOf("散步", "外出", "walk")
    RecordType.COUGH -> listOf("咳嗽", "cough")
    RecordType.RASH -> listOf("发疹", "皮疹", "rash")
    RecordType.VOMIT -> listOf("呕吐", "吐奶", "vomit")
    RecordType.INJURY -> listOf("受伤", "外伤", "injury")
    RecordType.MEDICINE -> listOf("用药", "药物", "medicine")
    RecordType.HOSPITAL -> listOf("就医", "医院", "看医生", "hospital")
    RecordType.OTHER -> listOf("其他", "自由文本", "other")
    RecordType.HEIGHT -> listOf("身高", "height")
    RecordType.WEIGHT -> listOf("体重", "weight")
    RecordType.BABY_FOOD -> listOf("辅食", "baby food")
    RecordType.SNACK -> listOf("点心", "零食", "snack")
    RecordType.DRINK -> listOf("饮料", "喝水", "drink")
    RecordType.HEAD -> listOf("头围", "head")
    RecordType.CHEST -> listOf("胸围", "chest")
    RecordType.FOOT_SIZE -> listOf("足长", "脚长", "foot size")
    RecordType.VACCINE -> listOf("疫苗", "接种", "vaccine")
    RecordType.CUSTOM -> listOf("自定义", "custom")
}

internal fun RecordType.candidateSearchTerms(): List<String> = searchTerms() + when (this) {
    RecordType.NURSING -> listOf(
        "ml", "毫升", "分", "分钟", "仅左侧", "仅右侧", "先左后右", "先右后左",
    )
    RecordType.FORMULA, RecordType.PUMPED_FEED, RecordType.PUMP_EXPRESS ->
        listOf("ml", "毫升")
    RecordType.PEE -> listOf("小", "中", "大")
    RecordType.POOP -> listOf(
        "一点", "偏少", "正常", "偏多", "稀", "偏软", "偏硬",
        "白", "黄", "橙", "褐", "绿", "红", "黑",
    )
    RecordType.BOTH_DIAPER -> listOf(
        "小", "中", "大", "一点", "偏少", "正常", "偏多", "稀", "偏软", "偏硬",
        "白", "黄", "橙", "褐", "绿", "红", "黑",
    )
    RecordType.SLEEP -> listOf("进行中", "不足1分", "分", "分钟")
    RecordType.TEMPERATURE -> listOf("℃")
    RecordType.COUGH, RecordType.RASH, RecordType.VOMIT, RecordType.INJURY ->
        listOf("轻微", "一般", "明显")
    RecordType.HEIGHT, RecordType.HEAD, RecordType.CHEST, RecordType.FOOT_SIZE ->
        listOf("cm", "厘米")
    RecordType.WEIGHT -> listOf("g", "kg", "克", "公斤", "千克")
    RecordType.WALK -> listOf("分", "分钟")
    RecordType.MEMO,
    RecordType.DIARY,
    RecordType.BATH,
    RecordType.MEDICINE,
    RecordType.HOSPITAL,
    RecordType.OTHER,
    RecordType.BABY_FOOD,
    RecordType.SNACK,
    RecordType.DRINK,
    RecordType.VACCINE,
    RecordType.CUSTOM,
    -> emptyList()
}

internal fun Record.matchesVisibleSearchText(query: String): Boolean =
    buildList {
        addAll(type.searchTerms())
        note?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
        addAll(visiblePayloadSearchTerms())
    }.any { it.lowercase().contains(query) }

private fun Record.visiblePayloadSearchTerms(): List<String> = buildList {
    fun addString(key: String) {
        payloadStringValue(key)?.takeIf { it.isNotBlank() }?.let(::add)
    }

    fun addNumber(key: String, suffix: String = "") {
        payloadNumberValue(key)?.let { value ->
            add(value)
            if (suffix.isNotEmpty()) add("$value$suffix")
        }
    }

    fun addMappedLevel(key: String, labels: List<String>) {
        val level = payloadNumberValue(key)?.toIntOrNull() ?: return
        val index = level - 1
        if (index in labels.indices) {
            add(level.toString())
            add(labels[index])
        }
    }

    fun addStoolSearchTerms() {
        addMappedLevel("stool_amount", listOf("一点", "偏少", "正常", "偏多"))
        addMappedLevel("stool_consistency", listOf("稀", "偏软", "正常", "偏硬"))
        addMappedLevel(
            "stool_color",
            listOf("白", "黄", "橙", "褐", "绿", "红", "黑"),
        )
    }

    when (type) {
        RecordType.FORMULA, RecordType.PUMPED_FEED, RecordType.PUMP_EXPRESS ->
            addNumber("amount_ml", "ml")
        RecordType.NURSING -> {
            addNumber("left_min", "分")
            addNumber("right_min", "分")
            addNumber("amount_ml", "ml")
            when (payloadStringValue("order")) {
                "L" -> add("仅左侧")
                "R" -> add("仅右侧")
                "LR" -> add("先左后右")
                "RL" -> add("先右后左")
            }
        }
        RecordType.PEE -> addMappedLevel("pee_amount", listOf("小", "中", "大"))
        RecordType.POOP -> addStoolSearchTerms()
        RecordType.BOTH_DIAPER -> {
            addMappedLevel("pee_amount", listOf("小", "中", "大"))
            addStoolSearchTerms()
        }
        RecordType.SLEEP -> {
            if (payloadBooleanValue("is_nap")) add("午睡")
            val sleepEnd = endTimestamp
            if (sleepEnd == null) {
                add("进行中")
            } else if (sleepEnd > timestamp) {
                val minutes = (sleepEnd - timestamp) / 60_000L
                add(if (minutes == 0L) "不足1分" else "${minutes}分")
            }
        }
        RecordType.TEMPERATURE -> {
            payloadNumberValue("celsius")?.let {
                add(it)
                add("${it}℃")
            } ?: addNumber("value", "℃")
        }
        RecordType.MEMO, RecordType.DIARY -> addString("body")
        RecordType.COUGH, RecordType.RASH, RecordType.VOMIT, RecordType.INJURY -> {
            when (payloadNumberValue("severity")?.toIntOrNull()) {
                1 -> add("轻微")
                2 -> add("一般")
                3 -> add("明显")
            }
            addString("description")
        }
        RecordType.MEDICINE -> {
            addString("name")
            addString("dose")
        }
        RecordType.HOSPITAL -> {
            addString("reason")
            addString("advice")
        }
        RecordType.OTHER, RecordType.CUSTOM -> {
            addString("title")
            addString("detail")
        }
        RecordType.HEIGHT, RecordType.HEAD, RecordType.CHEST, RecordType.FOOT_SIZE ->
            addNumber("value", "cm")
        RecordType.WEIGHT -> {
            addNumber("value")
            addString("unit")
        }
        RecordType.BABY_FOOD, RecordType.SNACK, RecordType.DRINK -> {
            addString("content")
            addString("amount")
        }
        RecordType.VACCINE -> {
            addString("name")
            addString("batch")
        }
        RecordType.WALK -> addNumber("duration_min", "分")
        RecordType.BATH -> Unit
    }
}

private fun Record.payloadStringValue(key: String): String? {
    val pattern = Regex(
        "\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
    )
    val encoded = pattern.find(payloadJson)?.groupValues?.getOrNull(1) ?: return null
    return decodeJsonString(encoded)
}

private fun Record.payloadNumberValue(key: String): String? =
    Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
        .find(payloadJson)
        ?.groupValues
        ?.getOrNull(1)

private fun Record.payloadBooleanValue(key: String): Boolean =
    Regex("\"${Regex.escape(key)}\"\\s*:\\s*(true|false)")
        .find(payloadJson)
        ?.groupValues
        ?.getOrNull(1)
        ?.toBooleanStrictOrNull()
        ?: false

private fun decodeJsonString(encoded: String): String = buildString {
    var index = 0
    while (index < encoded.length) {
        val char = encoded[index++]
        if (char != '\\' || index >= encoded.length) {
            append(char)
            continue
        }
        when (val escaped = encoded[index++]) {
            '"' -> append('"')
            '\\' -> append('\\')
            '/' -> append('/')
            'b' -> append('\b')
            'f' -> append('\u000C')
            'n' -> append('\n')
            'r' -> append('\r')
            't' -> append('\t')
            'u' -> {
                val end = (index + 4).coerceAtMost(encoded.length)
                val hex = encoded.substring(index, end)
                val decoded = hex.takeIf { it.length == 4 }?.toIntOrNull(16)
                if (decoded == null) {
                    append("\\u")
                    append(hex)
                } else {
                    append(decoded.toChar())
                }
                index = end
            }
            else -> append(escaped)
        }
    }
}
