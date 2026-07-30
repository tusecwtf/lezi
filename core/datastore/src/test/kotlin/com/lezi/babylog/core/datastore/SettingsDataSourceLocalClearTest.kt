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

    private fun newSettings(fileName: String): SettingsDataSource {
        val file = File(temporaryFolder.root, fileName)
        return SettingsDataSource(
            PreferenceDataStoreFactory.create(produceFile = { file }),
        )
    }
}
