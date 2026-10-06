package com.lezi.babylog.feature.family.conflict

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictResolverAttemptGateTest {
    @Test
    fun lateLoadFromAIsRejectedAfterBStarts() {
        val gate = ConflictResolverAttemptGate()
        val attemptA = gate.begin("conflict-a")
        val attemptB = gate.begin("conflict-b")

        assertFalse(gate.accepts(attemptA))
        assertTrue(gate.accepts(attemptB))
        assertNull(gate.current("conflict-a"))
        assertNotNull(gate.current("conflict-b"))
    }

    @Test
    fun lateSubmitIsRejectedAfterDismiss() {
        val gate = ConflictResolverAttemptGate()
        val submitted = gate.begin("conflict-a")

        gate.invalidate()

        assertFalse(gate.accepts(submitted))
        assertNull(gate.current("conflict-a"))
    }
}
