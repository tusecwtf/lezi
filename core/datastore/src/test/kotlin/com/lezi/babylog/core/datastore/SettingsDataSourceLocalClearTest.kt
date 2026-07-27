package com.lezi.babylog.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsDataSourceLocalClearTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun finalizerRemovesCapturedValuesAndPreservesNewerSettingsEpoch() = runBlocking {
        val settings = newSettings("newer-epoch.preferences_pb")
        settings.setCurrentBabyId(7L)
        settings.setNextFeedAt(8L)
        settings.setSystemCalendarEventMapJson("""{"plan-old":"evt-old"}""")
        val captured = settings.captureLocalClearSettings()

        settings.setCurrentBabyId(17L)
        settings.setNextFeedAt(18L)
        settings.setSystemCalendarEventMapJson(
            """{"plan-old":"evt-old","plan-new":"evt-new"}""",
        )
        val finish = settings.finishLocalClearSettings(captured, clearCurrentBabyId = true)

        assertEquals(false, finish.cancelNextFeedAlarm)
        assertEquals(17L, settings.currentBabyId.first())
        assertEquals(18L, settings.settings.first().nextFeedAt)
        assertEquals(
            mapOf("plan-new" to "evt-new"),
            decodeSystemCalendarEventMap(settings.settings.first().systemCalendarEventMapJson),
        )
    }

    @Test
    fun finalizerClearsUnchangedCapturedValues() = runBlocking {
        val settings = newSettings("captured-epoch.preferences_pb")
        settings.setCurrentBabyId(7L)
        settings.setNextFeedAt(8L)
        settings.setSystemCalendarEventMapJson("""{"plan-old":"evt-old"}""")
        val captured = settings.captureLocalClearSettings()

        val finish = settings.finishLocalClearSettings(captured, clearCurrentBabyId = true)

        assertEquals(true, finish.cancelNextFeedAlarm)
        assertNull(settings.currentBabyId.first())
        assertNull(settings.settings.first().nextFeedAt)
        assertEquals(
            emptyMap<String, String>(),
            decodeSystemCalendarEventMap(settings.settings.first().systemCalendarEventMapJson),
        )
    }

    @Test
    fun finalizerPreservesSameTimeWrittenByANewerFeedEpoch() = runBlocking {
        val settings = newSettings("same-time-newer-epoch.preferences_pb")
        settings.setNextFeedAt(8L)
        val captured = settings.captureLocalClearSettings()

        settings.setNextFeedAt(8L)
        val finish = settings.finishLocalClearSettings(captured, clearCurrentBabyId = false)

        assertEquals(false, finish.cancelNextFeedAlarm)
        assertEquals(8L, settings.settings.first().nextFeedAt)
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
    fun staleAlarmDeliveryCannotConsumeANewerFeedEpoch() = runBlocking {
        val settings = newSettings("stale-alarm-epoch.preferences_pb")
        val staleEpoch = settings.setNextFeedAt(8L)
        val currentEpoch = settings.setNextFeedAt(18L)

        assertFalse(settings.clearNextFeedAtIfEpoch(staleEpoch))
        assertEquals(18L, settings.settings.first().nextFeedAt)
        assertEquals(currentEpoch, settings.settings.first().nextFeedEpoch)

        assertTrue(settings.clearNextFeedAtIfEpoch(currentEpoch))
        assertNull(settings.settings.first().nextFeedAt)
    }

    private fun newSettings(fileName: String): SettingsDataSource {
        val file = File(temporaryFolder.root, fileName)
        return SettingsDataSource(
            PreferenceDataStoreFactory.create(produceFile = { file }),
        )
    }
}
