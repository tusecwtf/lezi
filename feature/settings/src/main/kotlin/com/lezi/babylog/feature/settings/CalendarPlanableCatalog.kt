package com.lezi.babylog.feature.settings

import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.businessLabel
import com.lezi.babylog.core.model.isPlanableCarePlanType
import com.lezi.babylog.domain.CustomRecordItem

/**
 * One concrete item that can be scheduled as a care plan from the lezi calendar
 * ＋ / convert pickers. Built-ins respect [hiddenItems]; customs use
 * `custom:<id>` keys and remain scheduleable when not locally hidden.
 */
data class CalendarPlanableItem(
    val type: RecordType,
    val customItemId: Long? = null,
    val label: String,
) {
    val catalogKey: String
        get() = if (type == RecordType.CUSTOM && customItemId != null) {
            RecordItemIdentity.customCatalogKey(customItemId)
        } else {
            type.key
        }
}

/**
 * Enabled planable catalog for calendar schedule/convert entry points.
 * - Built-in: [RecordType.isPlanableCarePlanType] (includes intent-only nursing/sleep)
 *   and not in [hiddenItems]
 * - Custom: concrete definitions not in [hiddenItems]
 * - Never includes memo/other/bare custom
 */
fun calendarPlanableItems(
    hiddenItems: Set<String>,
    customItems: List<CustomRecordItem>,
): List<CalendarPlanableItem> {
    val builtIns = RecordType.entries
        .filter { it.isPlanableCarePlanType && it.key !in hiddenItems }
        .map { CalendarPlanableItem(type = it, label = it.businessLabel()) }
    val customs = customItems
        .filter { RecordItemIdentity.customCatalogKey(it.id) !in hiddenItems }
        .map {
            CalendarPlanableItem(
                type = RecordType.CUSTOM,
                customItemId = it.id,
                label = it.name.ifBlank { "自定义" },
            )
        }
    return builtIns + customs
}
