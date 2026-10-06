package com.lezi.babylog.feature.widget

import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.businessLabel
import com.lezi.babylog.core.model.formatRecordDuration
import com.lezi.babylog.core.model.recordTypeLabel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

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

/**
 * Glance preferences payload. A live session only recomposes when this state
 * changes; rendering still uses the already-built snapshot text, not the database.
 * A missing payload means "not published yet" and the caller may fall back to
 * the persisted snapshot. An explicit unconfigured payload must not fall back.
 */
internal object WidgetGlancePayload {
    const val KEY_NAME: String = "care_widget_display_v1"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun encode(model: WidgetDisplayModel): String = buildJsonObject {
        put("version", 1)
        put("widgetId", model.widgetId)
        put("configured", model.isConfigured)
        put("stale", model.isStale)
        if (model.isConfigured) {
            put("babyId", model.babyId ?: 0L)
            put("title", model.title)
            put("primary", model.primarySummary)
            put("secondary", model.secondarySummary)
            put(
                "quickTypes",
                buildJsonArray {
                    model.quickActions.forEach { action -> add(JsonPrimitive(action.type.key)) }
                },
            )
        }
    }.toString()

    fun decode(encoded: String): WidgetDisplayModel? = runCatching {
        val document = json.parseToJsonElement(encoded).jsonObject
        val widgetId = document.int("widgetId") ?: return null
        val configured = document.boolean("configured") ?: return null
        if (!configured) return unconfiguredWidgetDisplayModel(widgetId)
        val babyId = document.long("babyId") ?: return unconfiguredWidgetDisplayModel(widgetId)
        val quickTypes = document["quickTypes"]
            ?.jsonArrayOrNull()
            .orEmpty()
            .mapNotNull { element ->
                element.jsonPrimitive.contentOrNull?.let(RecordType::fromKey)
            }
        WidgetDisplayModel(
            widgetId = widgetId,
            babyId = babyId,
            title = document.string("title") ?: "乐记",
            primarySummary = document.string("primary").orEmpty(),
            secondarySummary = document.string("secondary").orEmpty(),
            quickActions = quickTypes.map { type -> WidgetQuickAction(type, type.businessLabel()) },
            isConfigured = true,
            isStale = document.boolean("stale") ?: false,
        )
    }.getOrNull()

    fun resolve(
        encoded: String?,
        widgetId: Int,
        persistedFallback: () -> WidgetDisplayModel,
    ): WidgetDisplayModel {
        if (encoded.isNullOrBlank()) return persistedFallback()
        return decode(encoded) ?: unconfiguredWidgetDisplayModel(widgetId)
    }

    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.boolean(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun kotlinx.serialization.json.JsonElement.jsonArrayOrNull(): JsonArray? =
        runCatching { jsonArray }.getOrNull()
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
