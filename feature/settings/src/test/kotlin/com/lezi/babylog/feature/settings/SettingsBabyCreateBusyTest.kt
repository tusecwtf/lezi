package com.lezi.babylog.feature.settings

import com.lezi.babylog.core.common.SingleFlightAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBabyCreateBusyTest {
    @Test
    fun rapidDoubleSubmitRunsOneAddAndKeepsDialogBusy() = runTest {
        val gate = SingleFlightAction()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var addCalls = 0
        val first = async {
            gate.run {
                addCalls += 1
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val busy = settingsAddBabyPrimaryPresentation(gate.busy.value)
        val secondAccepted = gate.run { addCalls += 1 }

        assertFalse(busy.enabled)
        assertFalse(busy.dismissible)
        assertEquals("添加中…", busy.label)
        assertFalse(secondAccepted)
        assertEquals(1, addCalls)

        release.complete(Unit)
        assertTrue(first.await())
        assertFalse(gate.busy.value)
        assertEquals("添加", settingsAddBabyPrimaryPresentation(false).label)
    }
}
