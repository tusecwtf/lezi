package com.lezi.babylog.feature.widget

import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.businessLabel
import com.lezi.babylog.core.model.formatRecordDuration
import com.lezi.babylog.core.model.recordTypeLabel

const val MAX_WIDGET_QUICK_TYPES: Int = 4

val DEFAULT_WIDGET_QUICK_TYPES: List<RecordType> = listOf(
    RecordType.NURSING,
    RecordType.FORMULA,
    RecordType.PEE,
)

/** Built-in types selectable as widget quick actions (no memo/other/bare custom). */
val CONFIGURABLE_WIDGET_QUICK_TYPES: List<RecordType> = listOf(
    RecordType.NURSING,
    RecordType.FORMULA,
    RecordType.PUMPED_FEED,
    RecordType.PUMP_EXPRESS,
    RecordType.PEE,
    RecordType.POOP,
    RecordType.BOTH_DIAPER,
    RecordType.SLEEP,
    RecordType.TEMPERATURE,
    RecordType.DIARY,
    RecordType.BATH,
    RecordType.WALK,
)

data class WidgetConfiguration(
    val widgetId: Int,
    val babyId: Long,
    val quickTypes: List<RecordType>,
) {
    init {
        require(widgetId > 0) { "widgetId must be positive" }
        require(babyId > 0) { "babyId must be positive" }
        require(quickTypes.isNotEmpty()) { "At least one quick type is required" }
        require(quickTypes.size <= MAX_WIDGET_QUICK_TYPES) {
            "At most $MAX_WIDGET_QUICK_TYPES quick types are supported"
        }
        require(quickTypes.distinct().size == quickTypes.size) {
            "Quick types must be unique"
        }
    }
}

data class WidgetSummaryData(
    val babyName: String,
    val feedMl: Int,
    val sleepMinutes: Long,
    val peeCount: Int,
    val poopCount: Int,
    val lastLabel: String?,
)

data class WidgetSummarySnapshot(
    val widgetId: Int,
    val babyId: Long,
    val babyName: String,
    val feedMl: Int,
    val sleepMinutes: Long,
    val peeCount: Int,
    val poopCount: Int,
    val lastLabel: String?,
    val updatedAtEpochMillis: Long,
    val lastLabelIsCanonical: Boolean = false,
)

data class WidgetQuickAction(
    val type: RecordType,
    val label: String,
)

data class WidgetDisplayModel(
    val widgetId: Int,
    val babyId: Long?,
    val title: String,
    val primarySummary: String,
    val secondarySummary: String,
    val quickActions: List<WidgetQuickAction>,
    val isConfigured: Boolean,
    val isStale: Boolean,
)

internal fun configuredWidgetDisplayModel(
    configuration: WidgetConfiguration,
    snapshot: WidgetSummarySnapshot?,
    isStale: Boolean = false,
): WidgetDisplayModel {
    val matchingSnapshot = snapshot?.takeIf {
        it.widgetId == configuration.widgetId && it.babyId == configuration.babyId
    }
    val sleep = matchingSnapshot?.sleepMinutes?.coerceAtLeast(0) ?: 0L
    val sleepText = formatRecordDuration(sleep)
    val feedMl = matchingSnapshot?.feedMl?.coerceAtLeast(0) ?: 0
    val pee = matchingSnapshot?.peeCount?.coerceAtLeast(0) ?: 0
    val poop = matchingSnapshot?.poopCount?.coerceAtLeast(0) ?: 0
    val latest = matchingSnapshot?.lastLabel
        ?.takeIf(String::isNotBlank)
        ?.let { label ->
            if (matchingSnapshot.lastLabelIsCanonical) label else localizeLegacyLastLabel(label)
        }
        ?: "暂无记录"
    return WidgetDisplayModel(
        widgetId = configuration.widgetId,
        babyId = configuration.babyId,
        title = matchingSnapshot?.babyName?.takeIf(String::isNotBlank) ?: "乐记",
        primarySummary = "喂养 ${feedMl}ml · 睡眠 $sleepText",
        secondarySummary = "排泄 尿$pee/便$poop · 最近 $latest",
        quickActions = configuration.quickTypes.map { type ->
            WidgetQuickAction(type, type.businessLabel())
        },
        isConfigured = true,
        isStale = isStale || matchingSnapshot == null,
    )
}

internal fun unconfiguredWidgetDisplayModel(widgetId: Int): WidgetDisplayModel =
    WidgetDisplayModel(
        widgetId = widgetId,
        babyId = null,
        title = "乐记",
        primarySummary = "请先选择宝宝和快捷记录",
        secondarySummary = "轻触打开乐记",
        quickActions = emptyList(),
        isConfigured = false,
        isStale = false,
    )

private fun localizeLegacyLastLabel(raw: String): String {
    val separator = " · "
    val typeKey = raw.substringBefore(separator)
    val suffix = raw.substringAfter(separator, missingDelimiterValue = "")
    val label = when (val type = RecordType.fromKey(typeKey)) {
        null -> if (typeKey.isLikelyInternalRecordTypeKey()) {
            recordTypeLabel(typeKey)
        } else {
            typeKey.ifBlank { recordTypeLabel(typeKey) }
        }
        else -> type.businessLabel()
    }
    return if (suffix.isBlank()) label else "$label$separator$suffix"
}

private fun String.isLikelyInternalRecordTypeKey(): Boolean =
    isNotEmpty() && first() in 'a'..'z' && all { it in 'a'..'z' || it in '0'..'9' || it == '_' }
