package com.lezi.babylog.domain.carelog
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.businessLabel
import com.lezi.babylog.core.model.visibleBusinessText

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

private fun RecordType.searchTerms(): List<String> = listOf(businessLabel()) + when (this) {
    RecordType.NURSING -> listOf("哺乳", "亲喂", "nursing")
    RecordType.FORMULA -> listOf("奶粉", "奶量", "formula")
    RecordType.PUMPED_FEED -> listOf("喂挤出乳", "瓶喂母乳", "pumped feed")
    RecordType.PUMP_EXPRESS -> listOf("吸奶", "pump express")
    RecordType.PEE -> listOf("小便", "换尿布", "pee")
    RecordType.POOP -> listOf("大便", "换尿布", "poop")
    RecordType.BOTH_DIAPER -> listOf("尿便", "换尿布", "both diaper")
    RecordType.SLEEP -> listOf("睡觉", "午睡", "sleep")
    RecordType.TEMPERATURE -> listOf("温度", "temperature")
    RecordType.DIARY -> listOf("正文", "diary")
    RecordType.BATH -> listOf("沐浴", "bath")
    RecordType.WALK -> listOf("外出", "walk")
    RecordType.COUGH -> listOf("cough")
    RecordType.RASH -> listOf("皮疹", "rash")
    RecordType.VOMIT -> listOf("吐奶", "vomit")
    RecordType.INJURY -> listOf("外伤", "injury")
    RecordType.MEDICINE -> listOf("药物", "medicine")
    RecordType.HOSPITAL -> listOf("医院", "看医生", "hospital")
    RecordType.HEIGHT -> listOf("height")
    RecordType.WEIGHT -> listOf("weight")
    RecordType.BABY_FOOD -> listOf("baby food")
    RecordType.SNACK -> listOf("零食", "snack")
    RecordType.DRINK -> listOf("喝水", "drink")
    RecordType.HEAD -> listOf("head")
    RecordType.CHEST -> listOf("chest")
    RecordType.FOOT_SIZE -> listOf("脚长", "foot size")
    RecordType.VACCINE -> listOf("接种", "vaccine")
    RecordType.CUSTOM -> listOf("custom")
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
    RecordType.DIARY,
    RecordType.BATH,
    RecordType.MEDICINE,
    RecordType.HOSPITAL,
    RecordType.BABY_FOOD,
    RecordType.SNACK,
    RecordType.DRINK,
    RecordType.VACCINE,
    RecordType.CUSTOM,
    -> emptyList()
}

/**
 * Type-alias matching rules for search.
 *
 * Latin-only aliases (`pee`, `sleep`, `formula`, …) match by prefix / whole
 * term, multi-word token, or when the **query is longer than the term** and
 * contains it (so `"120ml"`/`"6.35kg"` still hit unit tokens). Short queries
 * never mid-hit longer aliases (`"e"` ⊄ `"pee"`). Chinese / mixed labels keep
 * substring match so partials like `"尿布"` still hit `"换尿布"`.
 */
internal fun typeTermMatchesQuery(term: String, query: String): Boolean {
    val t = term.lowercase()
    val q = query.lowercase()
    if (q.isEmpty() || t.isEmpty()) return false
    val termIsLatinAlias = t.all { it.isLatinAliasChar() }
    if (termIsLatinAlias) {
        if (t == q || t.startsWith(q) || q.startsWith(t)) return true
        // Multi-word English: "pumped feed", "both diaper", "baby food", "foot size"
        if (
            t.split(' ').any { word ->
                word.isNotEmpty() && (word == q || word.startsWith(q) || q.startsWith(word))
            }
        ) {
            return true
        }
        // Unit / token as substring of a longer query only ("120ml"⊃"ml").
        // Blocks short mid-alias hits: "e" inside "pee", "a" inside "bath".
        return q.length > t.length && q.contains(t)
    }
    return t.contains(q) || q.contains(t)
}

private fun Char.isLatinAliasChar(): Boolean =
    this == ' ' || this in 'a'..'z' || this in '0'..'9'

internal fun Record.matchesVisibleSearchText(query: String): Boolean {
    if (type.searchTerms().any { typeTermMatchesQuery(it, query) }) return true
    val visible = visibleBusinessText()
    return visible.isNotBlank() && visible.lowercase().contains(query)
}
