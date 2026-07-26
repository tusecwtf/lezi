package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.ui.presentation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class CarePlanDeleteConfirmation(
    val title: String,
    val message: String,
)

internal fun deleteConfirmationMessage(impact: String, error: String?): String =
    error?.trim()?.takeIf { it.isNotEmpty() }?.let { "$impact\n\n$it" } ?: impact

internal fun carePlanDeleteConfirmation(
    draft: QuickRecordDraft,
    zoneId: ZoneId = ZoneId.systemDefault(),
): CarePlanDeleteConfirmation {
    val planName = if (draft.type == RecordType.CUSTOM) {
        draft.customTitle.trim().ifBlank { draft.type.presentation.label }
    } else {
        draft.type.presentation.label
    }
    val scheduledTime = Instant.ofEpochMilli(draft.timestamp)
        .atZone(zoneId)
        .format(CARE_PLAN_DELETE_TIME_FORMAT)
    return CarePlanDeleteConfirmation(
        title = "删除「$planName · $scheduledTime」？",
        message = "确认后，这个计划会从当前宝宝及家庭共享的待办中移除；" +
            "本机会取消乐记提醒，如已写入本机系统日历，乐记会尝试移除对应日程。" +
            "不会生成护理记录，且无法撤销。",
    )
}

private val CARE_PLAN_DELETE_TIME_FORMAT =
    DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE)
