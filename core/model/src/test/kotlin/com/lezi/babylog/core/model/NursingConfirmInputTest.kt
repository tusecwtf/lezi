package com.lezi.babylog.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NursingConfirmInputTest {
    @Test
    fun `all four orders round-trip without changing single-side payloads`() {
        val payloads = listOf(
            NursingPayload(leftMinutes = 7, rightMinutes = 0, order = "L"),
            NursingPayload(leftMinutes = 0, rightMinutes = 9, order = "R"),
            NursingPayload(leftMinutes = 7, rightMinutes = 9, order = "LR", amountMl = 45),
            NursingPayload(leftMinutes = 7, rightMinutes = 9, order = "RL", recordMode = "start"),
        )

        payloads.forEach { payload ->
            val input = NursingConfirmInput.fromPayload(payload)

            assertEquals(payload.leftMinutes.toString(), input.leftMinutes)
            assertEquals(payload.rightMinutes.toString(), input.rightMinutes)
            assertEquals(payload.order, input.order)
            assertEquals(payload.amountMl?.toString().orEmpty(), input.amountMl)
            assertEquals(payload, input.toPayload(recordMode = payload.recordMode))
        }
    }

    @Test
    fun `duration fields require integers within the shared minute range`() {
        val expected = NursingConfirmIssue(
            field = NursingConfirmField.Duration,
            message = "左右时长需为 0–1440 分钟的整数",
        )

        assertEquals(
            expected,
            NursingConfirmInput("oops", "0", "L").validationIssue(),
        )
        assertEquals(
            expected,
            NursingConfirmInput("1441", "0", "L").validationIssue(),
        )
    }

    @Test
    fun `facts require one side while intent-only plans allow zero durations`() {
        val input = NursingConfirmInput("0", "0", "LR")

        assertEquals(
            NursingConfirmIssue(
                field = NursingConfirmField.Duration,
                message = "请填写左侧或右侧喂养时长",
            ),
            input.validationIssue(),
        )
        assertNull(input.validationIssue(allowIntentOnly = true))
    }

    @Test
    fun `all four orders are valid and intent-only does not admit unknown orders`() {
        listOf("L", "R", "LR", "RL").forEach { order ->
            assertNull(NursingConfirmInput("1", "0", order).validationIssue())
        }
        assertEquals(
            NursingConfirmIssue(
                field = NursingConfirmField.Order,
                message = "请选择喂养顺序",
            ),
            NursingConfirmInput("0", "0", "UNKNOWN")
                .validationIssue(allowIntentOnly = true),
        )
    }

    @Test
    fun `optional amount uses one to 999 for facts and intent-only plans`() {
        assertNull(NursingConfirmInput("1", "0", "L", "").validationIssue())
        assertNull(NursingConfirmInput("1", "0", "L", "1").validationIssue())
        assertNull(NursingConfirmInput("1", "0", "L", "999").validationIssue())

        val expected = NursingConfirmIssue(
            field = NursingConfirmField.Amount,
            message = "奶量需在 1–999 ml 之间",
        )
        assertEquals(
            expected,
            NursingConfirmInput("1", "0", "L", "many").validationIssue(),
        )
        assertEquals(
            expected,
            NursingConfirmInput("0", "0", "LR", "1000")
                .validationIssue(allowIntentOnly = true),
        )
    }
}
