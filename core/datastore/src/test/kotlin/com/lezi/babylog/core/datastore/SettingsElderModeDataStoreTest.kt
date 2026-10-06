package com.lezi.babylog.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SettingsElderModeDataStoreTest {
    @Test
    fun missingKeyReadsAsOff() = runBlocking {
        val source = SettingsDataSource(RecordingPreferencesDataStore())

        assertEquals("off", source.settings.first().elderMode)
    }

    @Test
    fun fourValuesRoundTripAndSurviveRestart() = runBlocking {
        val store = RecordingPreferencesDataStore()
        val firstProcess = SettingsDataSource(store)

        listOf("off", "l1", "l2", "l3").forEach { mode ->
            firstProcess.setElderMode(mode)
            assertEquals(mode, firstProcess.settings.first().elderMode)
        }

        val restarted = SettingsDataSource(store)
        assertEquals("l3", restarted.settings.first().elderMode)
    }

    @Test
    fun unknownStoredValueFallsBackToOff() = runBlocking {
        val key = stringPreferencesKey("elder_mode")
        val store = RecordingPreferencesDataStore(mutablePreferencesOf(key to "huge"))
        val source = SettingsDataSource(store)

        assertEquals("off", source.settings.first().elderMode)
    }

    @Test
    fun illegalWriteIsRejectedAndLeavesStoredValueUnchanged() = runBlocking {
        val store = RecordingPreferencesDataStore()
        val source = SettingsDataSource(store)
        source.setElderMode("l2")

        assertSuspendFails<IllegalArgumentException> {
            source.setElderMode("huge")
        }

        assertEquals("l2", source.settings.first().elderMode)
        assertEquals(1, store.updateCount)
    }

    @Test
    fun elderModeDoesNotRewriteVisualStyleOrDarkMode() = runBlocking {
        val store = RecordingPreferencesDataStore()
        val source = SettingsDataSource(store)
        source.setVisualStyle("journal")
        source.setDarkMode("dark")
        source.setElderMode("l1")

        val afterElder = source.settings.first()
        assertEquals("l1", afterElder.elderMode)
        assertEquals("journal", afterElder.visualStyle)
        assertEquals("dark", afterElder.darkMode)

        source.setVisualStyle("warm")
        source.setDarkMode("light")
        val afterOthers = source.settings.first()
        assertEquals("l1", afterOthers.elderMode)
        assertEquals("warm", afterOthers.visualStyle)
        assertEquals("light", afterOthers.darkMode)
    }

    private class RecordingPreferencesDataStore(
        initial: Preferences = mutablePreferencesOf(),
    ) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        private val mutex = Mutex()
        var updateCount: Int = 0

        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = mutex.withLock {
            updateCount += 1
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
