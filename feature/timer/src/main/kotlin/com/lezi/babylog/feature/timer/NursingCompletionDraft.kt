package com.lezi.babylog.feature.timer

import com.lezi.babylog.core.model.NURSING_ORDERS
import com.lezi.babylog.core.model.NursingConfirmInput

/**
 * A snapshot taken when the user taps "完成并记录".
 *
 * Running timers may continue behind the confirmation sheet. Durations and the
 * default end time deliberately stay frozen so a delayed confirmation cannot
 * silently add time the user did not review.
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
) {
    fun validationError(nowMillis: Long): String? {
        confirmInput().validationIssue()?.let { return it.message }
        return when {
            note.length > 200 -> "备注最多 200 字"
            endedAt < startedAt -> "结束时刻不能早于开始时刻"
            endedAt > nowMillis -> "结束时刻不能晚于现在"
            else -> null
        }
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
