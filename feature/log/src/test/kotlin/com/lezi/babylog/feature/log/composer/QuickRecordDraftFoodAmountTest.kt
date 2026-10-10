package com.lezi.babylog.feature.log.composer

import com.lezi.babylog.core.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickRecordDraftFoodAmountTest {
    private val now = tappedAt + 60_000L

    private fun foodDraft(amount: String, type: RecordType = RecordType.BABY_FOOD) =
        QuickRecordDraft.create(type, tappedAt)
            .copy(foodContent = "南瓜米糊", foodAmount = amount)

    @Test
    fun `blank amount blocks validation with a FoodAmount field error`() {
        val draft = foodDraft("")

        val validation = draft.validationResult(nowMillis = now)

        assertNotNull(validation)
        assertEquals("请填写量（如 50g）", validation!!.message)
        assertEquals(ComposerInvalidField.FoodAmount, validation.field)
        assertFalse(draft.canConfirm(nowMillis = now))
    }

    @Test
    fun `nonblank unparseable amount uses the statistics-specific message`() {
        listOf("几口", "十几口", "半2碗", "1,000g", "1.2.3碗", "-50g").forEach { amount ->
            val validation = foodDraft(amount).validationResult(nowMillis = now)

            assertNotNull(validation)
            assertEquals(
                "量需包含数字才能统计（「几口」无法统计）；「半碗」会自动记为 0.5碗",
                validation!!.message,
            )
            assertEquals(ComposerInvalidField.FoodAmount, validation.field)
        }
    }

    @Test
    fun `supported shorthand and full-width amounts save normalized`() {
        mapOf(
            "半碗" to "\"amount\":\"0.5碗\"",
            "３。５碗" to "\"amount\":\"3.5碗\"",
            "５０g" to "\"amount\":\"50g\"",
        ).forEach { (amount, expectedJson) ->
            assertTrue(foodDraft(amount).toSaveCommand().payloadJsonForTest.contains(expectedJson))
        }
    }

    @Test
    fun `digit-prefixed amounts save unchanged`() {
        val command = foodDraft("50g").toSaveCommand()

        assertEquals(true, command.payloadJsonForTest.contains("\"amount\":\"50g\""))
    }

    @Test
    fun `snack and drink allow blank or nonnumeric amounts`() {
        listOf(RecordType.SNACK, RecordType.DRINK).forEach { type ->
            listOf("", "少量", "小半碗").forEach { amount ->
                assertNull(foodDraft(amount, type).validationResult(nowMillis = now))
            }
        }
    }

    @Test
    fun `intent-only food plan skips amount gate but fulfillment enforces fact rules`() {
        val plan = foodDraft("").copy(timestamp = now + 60_000L)
        assertEquals(ComposerWorkMode.ScheduleCare, plan.workMode(nowMillis = now))
        assertNull(plan.validationResult(nowMillis = now))

        val fulfillment = foodDraft("").copy(carePlanId = 9L)
        assertEquals(ComposerWorkMode.FulfillPlan, fulfillment.workMode(nowMillis = now))
        assertEquals(
            "请填写量（如 50g）",
            fulfillment.validationResult(nowMillis = now)?.message,
        )
    }
}
