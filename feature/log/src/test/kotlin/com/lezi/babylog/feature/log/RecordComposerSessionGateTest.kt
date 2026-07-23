package com.lezi.babylog.feature.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordComposerSessionGateTest {
    @Test
    fun switchingRequestRejectsOldSuccessButDeliversCurrentSuccess() {
        val gate = RecordComposerSessionGate()
        val oldSession = gate.open()
        val currentSession = gate.open()
        val delivered = mutableListOf<String>()

        val oldDelivered = gate.deliver(oldSession) {
            delivered += "old sheet closed"
        }
        val currentDelivered = gate.deliver(currentSession) {
            delivered += "current sheet closed"
        }

        assertFalse(oldDelivered)
        assertTrue(currentDelivered)
        assertEquals(listOf("current sheet closed"), delivered)
        assertTrue(oldSession != currentSession)
    }

    @Test
    fun closingRequestRejectsLateFailureState() {
        val gate = RecordComposerSessionGate()
        val session = gate.open()
        var error: String? = null

        gate.close()
        val delivered = gate.deliver(session) {
            error = "late delete failure"
        }

        assertFalse(delivered)
        assertNull(error)
        assertNull(gate.current())
    }
}
