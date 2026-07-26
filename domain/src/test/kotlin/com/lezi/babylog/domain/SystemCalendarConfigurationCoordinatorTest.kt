package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SystemCalendarConfigurationCoordinatorTest {
    @Test
    fun confirmPersistsTargetAndDisclosureBeforeOneReprojection() = runTest {
        val events = mutableListOf<String>()
        val coordinator = DefaultSystemCalendarConfigurationCoordinator(
            configure = { calendarId, disclosureLevel ->
                events += "configure:$calendarId:$disclosureLevel"
            },
            reprojectOpenFuture = { events += "reproject" },
        )

        coordinator.confirm(calendarId = " calendar-7 ", disclosureLevel = 3)

        assertThat(events).containsExactly(
            "configure:calendar-7:3",
            "reproject",
        ).inOrder()
    }

    @Test
    fun invalidConfirmationHasNoPersistenceOrProjectionSideEffects() = runTest {
        val events = mutableListOf<String>()
        val coordinator = DefaultSystemCalendarConfigurationCoordinator(
            configure = { _, _ -> events += "configure" },
            reprojectOpenFuture = { events += "reproject" },
        )

        val error = runCatching {
            coordinator.confirm(calendarId = " ", disclosureLevel = 2)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(events).isEmpty()
    }

    @Test
    fun disableDoesNotReprojectOrChangeTheLastDisclosureChoice() = runTest {
        val events = mutableListOf<String>()
        val coordinator = DefaultSystemCalendarConfigurationCoordinator(
            configure = { calendarId, disclosureLevel ->
                events += "configure:$calendarId:$disclosureLevel"
            },
            reprojectOpenFuture = { events += "reproject" },
        )

        coordinator.disable()

        assertThat(events).containsExactly("configure:null:null")
    }
}
