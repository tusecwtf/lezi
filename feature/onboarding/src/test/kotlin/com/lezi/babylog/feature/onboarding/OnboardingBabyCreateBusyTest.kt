package com.lezi.babylog.feature.onboarding

import com.lezi.babylog.core.common.SingleFlightAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

class OnboardingBabyCreateBusyTest {
    @Test
    fun rapidDoubleSubmitRunsOneCreateAndProjectsBusyPrimary() = runTest {
        val gate = SingleFlightAction()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var createCalls = 0
        val first = async {
            gate.run {
                createCalls += 1
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val busy = onboardingCreateBabyPrimaryPresentation(gate.busy.value)
        val secondAccepted = gate.run { createCalls += 1 }

        assertFalse(busy.enabled)
        assertEquals("创建中…", busy.label)
        assertFalse(secondAccepted)
        assertEquals(1, createCalls)

        release.complete(Unit)
        assertTrue(first.await())
        assertFalse(gate.busy.value)
        assertEquals("开始记录", onboardingCreateBabyPrimaryPresentation(false).label)
    }

    @Test
    fun failedCreateRestoresRetryableGate() = runTest {
        val gate = SingleFlightAction()

        val failure = runCatching {
            gate.run { error("database unavailable") }
        }.exceptionOrNull()

        assertNotNull(failure)
        assertFalse(gate.busy.value)
        assertTrue(gate.run {})
    }
}
