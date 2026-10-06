package com.lezi.babylog.core.model

enum class NursingConfirmField {
    Duration,
    Order,
    Amount,
}

data class NursingConfirmIssue(
    val field: NursingConfirmField,
    val message: String,
)

/**
 * Editable nursing text fields shared by Composer and timer completion.
 *
 * [NursingPayload] remains the persisted domain value; this type only preserves in-progress text
 * input until a confirmation path validates and adapts it back to that payload.
 */
data class NursingConfirmInput(
    val leftMinutes: String,
    val rightMinutes: String,
    val order: String,
    val amountMl: String = "",
) {
    fun validationIssue(allowIntentOnly: Boolean = false): NursingConfirmIssue? {
        val left = leftMinutes.toIntOrNull()
        val right = rightMinutes.toIntOrNull()
        if (left == null || right == null || left !in 0..1_440 || right !in 0..1_440) {
            return NursingConfirmIssue(
                field = NursingConfirmField.Duration,
                message = "左右时长需为 0–1440 分钟的整数",
            )
        }
        if (left + right <= 0 && !allowIntentOnly) {
            return NursingConfirmIssue(
                field = NursingConfirmField.Duration,
                message = "请填写左侧或右侧喂养时长",
            )
        }
        if (order !in NURSING_ORDERS) {
            return NursingConfirmIssue(
                field = NursingConfirmField.Order,
                message = "请选择喂养顺序",
            )
        }
        val amount = amountMl.toIntOrNull()
        if (amountMl.isNotBlank() && amount !in 1..999) {
            return NursingConfirmIssue(
                field = NursingConfirmField.Amount,
                message = "奶量需在 1–999 ml 之间",
            )
        }
        return null
    }

    fun toPayload(recordMode: String = "end"): NursingPayload = NursingPayload(
        leftMinutes = leftMinutes.toIntOrNull() ?: 0,
        rightMinutes = rightMinutes.toIntOrNull() ?: 0,
        order = order,
        amountMl = amountMl.takeIf(String::isNotBlank)?.toIntOrNull(),
        recordMode = recordMode,
    )

    companion object {
        fun fromPayload(payload: NursingPayload): NursingConfirmInput = NursingConfirmInput(
            leftMinutes = payload.leftMinutes.toString(),
            rightMinutes = payload.rightMinutes.toString(),
            order = payload.order,
            amountMl = payload.amountMl?.toString().orEmpty(),
        )
    }
}
