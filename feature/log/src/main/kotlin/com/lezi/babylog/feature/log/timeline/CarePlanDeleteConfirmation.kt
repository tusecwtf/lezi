package com.lezi.babylog.feature.log.timeline
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.displayLabel
import com.lezi.babylog.core.ui.presentation
import java.time.ZoneId
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

internal data class CarePlanDeleteConfirmation(
    val title: String,
    val message: String,
)

/** List + Composer share this record-delete copy. */
internal const val RECORD_DELETE_TITLE = "删除这条记录？"
internal const val RECORD_DELETE_IMPACT = "删除后会从时间轴和汇总中移除，无法撤销。"

internal fun deleteConfirmationMessage(impact: String, error: String?): String =
    error?.trim()?.takeIf { it.isNotEmpty() }?.let { "$impact\n\n$it" } ?: impact

internal fun carePlanDeleteConfirmation(
    planName: String,
    scheduledAtMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): CarePlanDeleteConfirmation {
    val scheduledTime = com.lezi.babylog.core.model.ProductDateTime.monthDayTime(
        scheduledAtMillis,
        zoneId,
    )
    return CarePlanDeleteConfirmation(
        title = "删除「$planName · $scheduledTime」？",
        message = "确认后，这个计划会从当前宝宝及家庭共享的待办中移除；" +
            "本机会取消乐记提醒，如已写入本机系统日历，乐记会尝试移除对应日程。" +
            "不会生成护理记录，且无法撤销。",
    )
}

internal fun carePlanDeleteConfirmation(
    draft: QuickRecordDraft,
    zoneId: ZoneId = ZoneId.systemDefault(),
): CarePlanDeleteConfirmation {
    val planName = if (draft.type == RecordType.CUSTOM) {
        draft.customTitle.trim().ifBlank { draft.type.presentation.label }
    } else {
        draft.type.presentation.label
    }
    return carePlanDeleteConfirmation(
        planName = planName,
        scheduledAtMillis = draft.timestamp,
        zoneId = zoneId,
    )
}

internal fun carePlanDeleteConfirmation(
    plan: CarePlan,
    zoneId: ZoneId = ZoneId.systemDefault(),
): CarePlanDeleteConfirmation =
    carePlanDeleteConfirmation(
        planName = plan.displayLabel(),
        scheduledAtMillis = plan.scheduledAt,
        zoneId = zoneId,
    )

