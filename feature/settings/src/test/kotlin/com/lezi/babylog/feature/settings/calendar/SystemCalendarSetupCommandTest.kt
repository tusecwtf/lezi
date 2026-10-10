package com.lezi.babylog.feature.settings.calendar

import com.lezi.babylog.domain.calendar.SystemCalendarConfigurationCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemCalendarSetupCommandTest {
    @Test fun failedSaveRetainsItsSelectionAndAllowsExplicitRetry() = runTest {
        var fail = true
        val gate = CompletableDeferred<Unit>()
        val coordinator = object : SystemCalendarConfigurationCoordinator {
            override suspend fun confirm(calendarId: String, disclosureLevel: Int) {
                gate.await()
                if (fail) error("storage unavailable")
            }
            override suspend fun disable() = error("unexpected disable")
        }
        val command = SystemCalendarSetupCommand(coordinator)
        val selection = SystemCalendarSetupSelection("history-calendar", 1)
        val job = launch { command.confirm(selection) }
        runCurrent()
        assertTrue(command.state.value.busy)
        assertFalse(command.confirm(selection))
        gate.complete(Unit)
        job.join()
        assertFalse(command.state.value.completed)
        assertEquals(selection, command.state.value.selection)
        assertNotNull(command.state.value.error)
        fail = false
        assertTrue(command.confirm(selection))
        assertTrue(command.state.value.completed)
    }
}
