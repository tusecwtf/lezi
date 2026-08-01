package com.lezi.babylog.feature.log.composer
import com.lezi.babylog.core.model.TimerHandoffAcceptResult
import com.lezi.babylog.core.model.TimerHandoffBuildResult
import com.lezi.babylog.core.model.TimerHandoffSeed
import com.lezi.babylog.core.model.UntransferableTimerField
import com.lezi.babylog.core.model.buildTimerHandoffSeed
import com.lezi.babylog.core.model.untransferableTimerFields
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

/**
 * Internal Composer→Timer handoff seams (Ticket 09).
 *
 * Transferable fields and photos leave Composer only after Timer accepts the
 * seed; this is ownership handoff, not draft abandon.
 */

/**
 * One-shot session handed to the app shell at navigate time.
 *
 * Accept/reject callbacks are captured when handoff starts so release does not
 * depend on recomposition-time registration.
 */
data class TimerHandoffSession(
    val seed: TimerHandoffSeed,
    val onAccepted: (TimerHandoffSeed) -> Unit,
    val onRejected: () -> Unit,
)

/** True when Timer owns the seed and Composer may release transferred owned paths. */
internal fun shouldReleaseComposerAfterHandoff(result: TimerHandoffAcceptResult): Boolean =
    when (result) {
        is TimerHandoffAcceptResult.Accepted,
        TimerHandoffAcceptResult.AlreadyAccepted,
        -> true
        TimerHandoffAcceptResult.RejectedConflict -> false
    }

/**
 * Durable accept token may arrive after process death when shell [TimerHandoffSession]
 * is gone. Release only when Composer still holds a matching pending seed.
 */
internal fun shouldReleasePendingTimerHandoff(
    pendingSeed: TimerHandoffSeed?,
    acceptedSeed: TimerHandoffSeed,
): Boolean {
    val pending = pendingSeed ?: return false
    return pending.handoffId == acceptedSeed.handoffId
}

internal fun untransferableFieldsForTimerHandoff(
    draft: QuickRecordDraft,
    baseline: QuickRecordDraft,
): Set<UntransferableTimerField> = untransferableTimerFields(
    leftMinutes = draft.leftMin,
    rightMinutes = draft.rightMin,
    order = draft.order,
    timestamp = draft.timestamp,
    baselineOrder = baseline.order,
    baselineTimestamp = baseline.timestamp,
)

/**
 * Build an explicit seed from the live Composer draft.
 *
 * [livePlanPhotoPaths] (Ticket 08 current plan media) is used only for
 * pre-leave overflow detection when a care plan is linked.
 */
internal fun prepareTimerHandoffSeed(
    handoffId: String,
    babyId: Long,
    draft: QuickRecordDraft,
    livePlanPhotoPaths: List<String> = emptyList(),
): TimerHandoffBuildResult {
    val carePlanId = draft.carePlanId?.takeUnless { draft.editCarePlan }
    return buildTimerHandoffSeed(
        handoffId = handoffId,
        babyId = babyId,
        carePlanId = carePlanId,
        note = draft.note,
        amountMl = draft.nursingAmountMl,
        orderedPhotoPaths = draft.photos,
        borrowedPaths = draft.borrowedPhotos,
        ownedPaths = draft.ownedDraftPhotos,
        livePlanPhotoPaths = if (carePlanId != null) livePlanPhotoPaths else emptyList(),
    )
}

internal fun timerHandoffPhotoOverflowMessage(distinctCount: Int, maxAllowed: Int): String =
    "计时完成后最多保留 $maxAllowed 张照片（当前合并后 $distinctCount 张）。请先删减草稿中的照片再开始计时。"

internal fun timerHandoffUntransferableConfirmMessage(
    fields: Set<UntransferableTimerField>,
): String {
    val parts = buildList {
        if (UntransferableTimerField.ManualDuration in fields) add("时长")
        if (UntransferableTimerField.ManualOrder in fields) add("顺序")
        if (UntransferableTimerField.ManualTime in fields) add("时间")
    }
    val labeled = when (parts.size) {
        0 -> "已填写内容"
        1 -> "已填写的${parts[0]}"
        2 -> "已填写的${parts[0]}和${parts[1]}"
        else -> "已填写的${parts[0]}、${parts[1]}和${parts[2]}"
    }
    return "开始计时后，${labeled}不会带入计时器，将由计时重新记录。确定开始计时吗？"
}

