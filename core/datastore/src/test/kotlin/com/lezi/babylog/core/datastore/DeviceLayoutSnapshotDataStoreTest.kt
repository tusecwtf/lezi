package com.lezi.babylog.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lezi.babylog.core.model.DEVICE_LAYOUT_SNAPSHOT_VERSION
import com.lezi.babylog.core.model.DeviceLayoutSnapshot
import com.lezi.babylog.core.model.deviceLayoutSnapshot
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DeviceLayoutSnapshotDataStoreTest {
    @Test
    fun dragGuidanceStartsIncompleteAndCompletionSurvivesRestart() = runBlocking {
        val store = RecordingPreferencesDataStore()
        val firstProcess = SettingsDataSource(store)

        assertFalse(firstProcess.settings.first().layoutDragGuidanceCompleted)

        firstProcess.markLayoutDragGuidanceCompleted()
        firstProcess.markLayoutDragGuidanceCompleted()
        firstProcess.setDarkMode("dark")
        firstProcess.setDeviceLayoutSnapshot(
            DeviceLayoutSnapshot(quickRecordSlots = listOf("sleep", "", "", "")),
        )

        val restartedProcess = SettingsDataSource(store)
        assertTrue(restartedProcess.settings.first().layoutDragGuidanceCompleted)
    }

    @Test
    fun completeSnapshotUsesOneAtomicUpdateAndOneSettingsEmission() = runBlocking {
        val store = RecordingPreferencesDataStore()
        val source = SettingsDataSource(store)

        source.setDeviceLayoutSnapshot(
            DeviceLayoutSnapshot(
                quickRecordSlots = listOf("pee", "", "pee", "sleep", "formula"),
                hiddenItems = setOf("bath", " sleep "),
                itemOrderJson = "[\"sleep\",\"pee\"]",
                categoryOrderJson = "[\"routine\",\"excretion\"]",
            ),
        )

        assertEquals(1, store.updateCount)
        val saved = source.settings.first().deviceLayoutSnapshot()
        assertEquals(listOf("pee", "", "", "sleep"), saved.quickRecordSlots)
        assertEquals(setOf("bath", "sleep"), saved.hiddenItems)
        assertEquals("[\"sleep\",\"pee\"]", saved.itemOrderJson)
        assertEquals("[\"routine\",\"excretion\"]", saved.categoryOrderJson)
    }

    @Test
    fun failedOrCancelledTransactionKeepsPreviousCompleteSnapshot() = runBlocking {
        val store = RecordingPreferencesDataStore()
        val source = SettingsDataSource(store)
        val previous = DeviceLayoutSnapshot(
            quickRecordSlots = listOf("pee", "sleep", "", ""),
            hiddenItems = setOf("bath"),
            itemOrderJson = "[\"pee\",\"sleep\"]",
            categoryOrderJson = "[\"excretion\",\"routine\"]",
        )
        source.setDeviceLayoutSnapshot(previous)

        store.failNext = IllegalStateException("disk full")
        assertSuspendFails<IllegalStateException> {
            source.setDeviceLayoutSnapshot(previous.copy(hiddenItems = setOf("sleep")))
        }
        assertEquals(previous, source.settings.first().deviceLayoutSnapshot())

        store.failNext = CancellationException("process stopped")
        assertSuspendFails<CancellationException> {
            source.setDeviceLayoutSnapshot(previous.copy(quickRecordSlots = emptyList()))
        }
        assertEquals(previous, source.settings.first().deviceLayoutSnapshot())
    }

    @Test
    fun restartReadsTheLastSuccessfulCompleteSnapshot() = runBlocking {
        val store = RecordingPreferencesDataStore()
        val firstProcess = SettingsDataSource(store)
        val expected = DeviceLayoutSnapshot(
            quickRecordSlots = listOf("", "sleep", "", "pee"),
            hiddenItems = setOf("formula"),
            itemOrderJson = "[\"pee\",\"sleep\",\"formula\"]",
            categoryOrderJson = "[\"routine\",\"excretion\",\"feeding\"]",
        )
        firstProcess.setDeviceLayoutSnapshot(expected)

        val restartedProcess = SettingsDataSource(store)

        assertEquals(expected, restartedProcess.settings.first().deviceLayoutSnapshot())
    }

    @Test
    fun futureSnapshotIsReadOnlyAndNeverOverwrittenByCurrentAtomicWriter() = runBlocking {
        val key = stringPreferencesKey("device_layout_snapshot_json")
        val raw = """{"version":${DEVICE_LAYOUT_SNAPSHOT_VERSION + 1},"quickRecordSlots":["sleep","","",""],"hiddenItems":["pee"],"itemOrderJson":"[]","categoryOrderJson":"[]","futureField":"kept"}"""
        val store = RecordingPreferencesDataStore(mutablePreferencesOf(key to raw))
        val source = SettingsDataSource(store)
        val read = source.settings.first().deviceLayoutSnapshot()

        assertEquals(DEVICE_LAYOUT_SNAPSHOT_VERSION + 1, read.version)
        assertEquals(listOf("sleep", "", "", ""), read.quickRecordSlots)
        assertEquals(setOf("pee"), read.hiddenItems)
        assertSuspendFails<IllegalArgumentException> {
            source.setDeviceLayoutSnapshot(read.copy(hiddenItems = emptySet()))
        }
        assertEquals(raw, store.data.first()[key])
        assertEquals(0, store.updateCount)
    }

    @Test
    fun malformedCurrentSnapshotFallsBackToTheLegacyMirror() = runBlocking {
        val store = RecordingPreferencesDataStore(
            mutablePreferencesOf(
                stringPreferencesKey("device_layout_snapshot_json") to "{",
                stringPreferencesKey("quick_record_slots") to "sleep,,,pee",
                stringPreferencesKey("hidden_items") to "bath",
                stringPreferencesKey("item_order_json") to "[\"sleep\"]",
                stringPreferencesKey("category_order_json") to "[\"routine\"]",
            ),
        )
        val source = SettingsDataSource(store)

        val read = source.settings.first().deviceLayoutSnapshot()

        assertEquals(listOf("sleep", "", "", "pee"), read.quickRecordSlots)
        assertEquals(setOf("bath"), read.hiddenItems)
        assertEquals("[\"sleep\"]", read.itemOrderJson)
        assertEquals("[\"routine\"]", read.categoryOrderJson)
    }

    private class RecordingPreferencesDataStore(
        initial: Preferences = mutablePreferencesOf(),
    ) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        private val mutex = Mutex()
        var updateCount: Int = 0
        var failNext: Throwable? = null

        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = mutex.withLock {
            updateCount += 1
            failNext?.let { failure ->
                failNext = null
                throw failure
            }
            transform(state.value).also { state.value = it }
        }
    }

    private suspend inline fun <reified T : Throwable> assertSuspendFails(
        crossinline block: suspend () -> Unit,
    ) {
        try {
            block()
            fail("Expected ${T::class.java.simpleName}")
        } catch (failure: Throwable) {
            if (failure !is T) throw failure
        }
    }
}
