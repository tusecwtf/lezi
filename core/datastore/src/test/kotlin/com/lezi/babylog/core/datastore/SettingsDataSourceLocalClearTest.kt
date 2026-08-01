package com.lezi.babylog.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsDataSourceLocalClearTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun finalizerRemovesCapturedValuesAndPreservesNewerSettings() = runBlocking {
        val settings = newSettings("newer-epoch.preferences_pb")
        settings.setCurrentBabyId(7L)
        settings.setSystemCalendarEventMapJson("""{"plan-old":"evt-old"}""")
        val captured = settings.captureLocalClearSettings()

        settings.setCurrentBabyId(17L)
        settings.setSystemCalendarEventMapJson(
            """{"plan-old":"evt-old","plan-new":"evt-new"}""",
        )
        settings.finishLocalClearSettings(captured, clearCurrentBabyId = true)

        assertEquals(17L, settings.currentBabyId.first())
        assertEquals(
            mapOf("plan-new" to "evt-new"),
            decodeSystemCalendarEventMap(settings.settings.first().systemCalendarEventMapJson),
        )
    }

    @Test
    fun finalizerClearsUnchangedCapturedValues() = runBlocking {
        val settings = newSettings("captured-epoch.preferences_pb")
        settings.setCurrentBabyId(7L)
        settings.setSystemCalendarEventMapJson("""{"plan-old":"evt-old"}""")
        val captured = settings.captureLocalClearSettings()

        settings.finishLocalClearSettings(captured, clearCurrentBabyId = true)

        assertNull(settings.currentBabyId.first())
        assertEquals(
            emptyMap<String, String>(),
            decodeSystemCalendarEventMap(settings.settings.first().systemCalendarEventMapJson),
        )
    }

    @Test
    fun finalizerPreservesReusedEventIdOwnedByANewerPlanUuid() = runBlocking {
        val settings = newSettings("reused-calendar-event-id.preferences_pb")
        settings.setSystemCalendarEventMapJson("""{"plan-old":"evt-reused"}""")
        val captured = settings.captureLocalClearSettings()

        settings.setSystemCalendarEventMapJson("""{"plan-new":"evt-reused"}""")
        settings.finishLocalClearSettings(captured, clearCurrentBabyId = false)

        assertEquals(
            mapOf("plan-new" to "evt-reused"),
            decodeSystemCalendarEventMap(settings.settings.first().systemCalendarEventMapJson),
        )
    }

    @Test
    fun captureIncludesNursingTimerJsonAndSessionToken() = runBlocking {
        val settings = newSettings("timer-capture.preferences_pb")
        val timerJson =
            """{"schemaVersion":1,"completionClientUuid":"session-old","leftRunning":false}"""
        settings.setNursingTimerJson(timerJson)

        val captured = settings.captureLocalClearSettings()

        assertEquals(timerJson, captured.nursingTimerJson)
        assertEquals("session-old", captured.nursingTimerSessionToken)
    }

    @Test
    fun finalizerRemovesCapturedTimerAndPreservesNewerSession() = runBlocking {
        val settings = newSettings("timer-cas.preferences_pb")
        val oldJson =
            """{"schemaVersion":1,"completionClientUuid":"session-old","leftRunning":true}"""
        val newJson =
            """{"schemaVersion":1,"completionClientUuid":"session-new","leftRunning":true}"""
        settings.setNursingTimerJson(oldJson)
        val captured = settings.captureLocalClearSettings()

        settings.setNursingTimerJson(newJson)
        settings.finishLocalClearSettings(captured, clearCurrentBabyId = false)

        assertEquals(newJson, settings.nursingTimerJson.first())
    }

    @Test
    fun finalizerClearsUnchangedCapturedTimer() = runBlocking {
        val settings = newSettings("timer-clear.preferences_pb")
        val timerJson =
            """{"schemaVersion":1,"completionClientUuid":"session-old","leftRunning":false}"""
        settings.setNursingTimerJson(timerJson)
        val captured = settings.captureLocalClearSettings()

        settings.finishLocalClearSettings(captured, clearCurrentBabyId = false)

        assertNull(settings.nursingTimerJson.first())
    }

    private fun newSettings(fileName: String): SettingsDataSource {
        val file = File(temporaryFolder.root, fileName)
        return SettingsDataSource(
            PreferenceDataStoreFactory.create(produceFile = { file }),
        )
    }
}
