package com.lezi.babylog.feature.log.timeline

import com.lezi.babylog.feature.log.composer.RecordComposerRequest
import com.lezi.babylog.feature.log.composer.recordComposerOpenRequest

sealed interface TimelineRecordBodyClick {
    data object CollapseSwipe : TimelineRecordBodyClick
    data object OpenCausalDetails : TimelineRecordBodyClick
    data object OpenPublishChrome : TimelineRecordBodyClick
    data class OpenComposer(val request: RecordComposerRequest) : TimelineRecordBodyClick
}

fun decideTimelineRecordBodyClick(
    recordId: Long,
    swipeRevealed: Boolean,
    hasCausalIntercept: Boolean,
    hasPublishLabel: Boolean,
    canEdit: Boolean,
): TimelineRecordBodyClick = when {
    swipeRevealed -> TimelineRecordBodyClick.CollapseSwipe
    hasCausalIntercept -> TimelineRecordBodyClick.OpenCausalDetails
    hasPublishLabel -> TimelineRecordBodyClick.OpenPublishChrome
    else -> TimelineRecordBodyClick.OpenComposer(
        recordComposerOpenRequest(recordId, canEdit),
    )
}

fun timelineRecordRowContentDescription(
    title: String,
    publishLabel: String?,
    canEdit: Boolean,
): String = when {
    publishLabel != null -> "同步状态$title"
    canEdit -> "编辑$title"
    else -> "查看$title"
}
