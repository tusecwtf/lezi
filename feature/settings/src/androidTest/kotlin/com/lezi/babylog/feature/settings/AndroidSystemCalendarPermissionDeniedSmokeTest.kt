package com.lezi.babylog.feature.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.domain.SystemCalendarOwnedEventLookup
import com.lezi.babylog.domain.SystemCalendarUpsert
import com.lezi.babylog.domain.SystemCalendarUpsertOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Run in a fresh instrumentation process after both calendar permissions are revoked. */
@RunWith(AndroidJUnit4::class)
class AndroidSystemCalendarPermissionDeniedSmokeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val port = AndroidSystemCalendarPort(context)

    @Test
    fun deniedPermissionReturnsContainedOutcomesWithoutProviderMutation() = runBlocking<Unit> {
        assertThat(port.hasCalendarPermission()).isFalse()
        assertThat(port.listWritableCalendars()).isEmpty()
        assertThat(port.findOwnedEvent("denied-plan"))
            .isEqualTo(SystemCalendarOwnedEventLookup.Unavailable)
        assertThat(
            port.upsertEvent(
                SystemCalendarUpsert(
                    calendarId = "1",
                    carePlanClientUuid = "denied-plan",
                    beginAtMillis = System.currentTimeMillis() + 60_000L,
                    title = "乐记 · 护理计划",
                ),
            ).outcome,
        ).isEqualTo(SystemCalendarUpsertOutcome.ReleasedOrAbsent)
        assertThat(port.deleteEvent("1", "denied-plan")).isFalse()
    }
}
