package com.lezi.babylog.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NursingOrdersTest {
    @Test
    fun orderChoicesCoverAllPayloadOrders() {
        val choiceKeys = NURSING_ORDER_CHOICES.map { it.first }.toSet()
        assertEquals(NURSING_ORDERS, choiceKeys)
        assertTrue(NURSING_ORDERS.containsAll(setOf("L", "R", "LR", "RL")))
    }

    @Test
    fun payloadValidationAcceptsEveryChipOrder() {
        for (order in NURSING_ORDERS) {
            val errors = RecordPayloadCodec.validate(
                NursingPayload(leftMinutes = 5, rightMinutes = 0, order = order),
            )
            assertTrue("order=$order should be valid, got $errors", errors.isEmpty())
        }
    }
}
