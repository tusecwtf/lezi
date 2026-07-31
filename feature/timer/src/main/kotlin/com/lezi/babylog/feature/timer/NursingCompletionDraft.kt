package com.lezi.babylog.feature.timer

import com.lezi.babylog.core.model.NURSING_ORDERS
import com.lezi.babylog.core.model.NursingConfirmInput
import com.lezi.babylog.core.model.RecordTime

/**
 * A snapshot taken when the user taps "完成并记录".
 *
 * Running timers may continue behind the confirmation sheet. Durations and the
 * default end time deliberately stay frozen so a delayed confirmation cannot
 * silently add time the user did not review.
 *
 * When [carePlanId] is set, actual times reuse fulfill skew
 * ([RecordTime.FULFILLMENT_ACTUAL_TIME_SKEW_MILLIS]); otherwise create-path zero skew.
 */
internal data class NursingCompletionDraft(
    val leftMinutes: String,
    val rightMinutes: String,
    val order: String,
    val amountMl: String = "",
    val note: String = "",
    val startedAt: Long,
    val endedAt: Long,
    val capturedAt: Long,
    val carePlanId: Long? = null,
) {
    fun actualTimeMaxFutureSkewMillis(): Long =
        if (carePlanId != null) {
            RecordTime.FULFILLMENT_ACTUAL_TIME_SKEW_MILLIS
        } else {
            0L
        }

    fun validationError(nowMillis: Long): String? {
        confirmInput().validationIssue()?.let { return it.message }
        if (note.length > 200) return "备注最多 200 字"
        if (endedAt < startedAt) return "结束时刻不能早于开始时刻"
        val skew = actualTimeMaxFutureSkewMillis()
        // Shared fact/fulfill copy with domain [RecordTime.pointError].
        RecordTime.pointError(startedAt, nowMillis, skew)?.let { return it }
        RecordTime.pointError(endedAt, nowMillis, skew)?.let { return it }
        return null
    }

    fun toCommand(): NursingCompletionCommand {
        val input = confirmInput()
        require(input.validationIssue() == null)
        val payload = input.toPayload()
        return NursingCompletionCommand(
            leftMin = payload.leftMinutes,
            rightMin = payload.rightMinutes,
            order = payload.order,
            amountMl = payload.amountMl,
            note = note.trim().ifBlank { null },
            startedAt = startedAt,
            endedAt = endedAt,
        )
    }

    fun confirmInput(): NursingConfirmInput = NursingConfirmInput(
        leftMinutes = leftMinutes,
        rightMinutes = rightMinutes,
        order = order,
        amountMl = amountMl,
    )

    fun withConfirmInput(input: NursingConfirmInput): NursingCompletionDraft = copy(
        leftMinutes = input.leftMinutes,
        rightMinutes = input.rightMinutes,
        order = input.order,
        amountMl = input.amountMl,
    )
}

internal data class NursingCompletionCommand(
    val leftMin: Int,
    val rightMin: Int,
    val order: String,
    val amountMl: Int?,
    val note: String?,
    val startedAt: Long,
    val endedAt: Long,
)

internal fun freezeNursingCompletion(
    state: TimerState,
    nowElapsed: Long,
    clickedAt: Long,
    initialNote: String = "",
    initialAmountMl: String = "",
): NursingCompletionDraft {
    val leftMin = roundedNursingMinutes(state.leftMs(nowElapsed))
    val rightMin = roundedNursingMinutes(state.rightMs(nowElapsed))
    return NursingCompletionDraft(
        leftMinutes = leftMin.toString(),
        rightMinutes = rightMin.toString(),
        order = nursingOrder(state, leftMin, rightMin),
        amountMl = initialAmountMl.filter(Char::isDigit).take(3),
        note = initialNote.take(200),
        startedAt = state.sessionStartedAt ?: clickedAt,
        endedAt = clickedAt,
        capturedAt = clickedAt,
        carePlanId = state.carePlanId,
    )
}

internal fun roundedNursingMinutes(durationMs: Long): Int =
    ((durationMs.coerceIn(0L, 1_440L * 60_000L) + 30_000L) / 60_000L)
        .coerceAtMost(1_440L)
        .toInt()

private fun nursingOrder(state: TimerState, leftMin: Int, rightMin: Int): String = when {
    state.order in NURSING_ORDERS -> state.order
    leftMin > 0 && rightMin > 0 -> if (state.lastSide == "R") "LR" else "RL"
    leftMin > 0 -> "L"
    rightMin > 0 -> "R"
    state.lastSide == "R" -> "R"
    else -> "L"
}
