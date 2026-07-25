package com.lezi.babylog.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncPreferencesTest {
    @Test
    fun sessionAndCursorSurviveStoreRecreation() = runTest {
        val file = File.createTempFile("lezi-sync-", ".preferences_pb").also { it.delete() }
        val firstScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val firstStore = PreferenceDataStoreFactory.create(scope = firstScope) { file }
        val first = DataStoreSyncPreferences(firstStore)
        first.saveSession(
            SyncSession(
                baseUrl = "http://nas:8765",
                familyId = "family-uuid",
                familyToken = "secret-token",
                deviceId = "device-uuid",
                role = FamilyRole.Owner,
                pullCursor = 41,
                pullGeneration = "server-generation",
            ),
        )
        firstScope.cancel()
        advanceUntilIdle()

        val secondScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val secondStore = PreferenceDataStoreFactory.create(scope = secondScope) { file }
        val restored = DataStoreSyncPreferences(secondStore)

        assertThat(restored.session.first().pullCursor).isEqualTo(41)
        assertThat(restored.session.first().pullGeneration).isEqualTo("server-generation")
        assertThat(restored.session.first().familyToken).isEqualTo("secret-token")
        secondScope.cancel()
        file.delete()
    }

    @Test
    fun clearingFamilyKeepsConfiguredServer() = runTest {
        val file = File.createTempFile("lezi-sync-", ".preferences_pb").also { it.delete() }
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val preferences = DataStoreSyncPreferences(store)
        preferences.saveSession(
            SyncSession(
                baseUrl = "http://nas:8765",
                familyId = "family",
                familyToken = "token",
                deviceId = "device",
                role = FamilyRole.Member,
            ),
        )

        preferences.clearFamilySession()

        assertThat(preferences.session.first().baseUrl).isEqualTo("http://nas:8765")
        assertThat(preferences.session.first().familyToken).isEmpty()
    }

    @Test
    fun changingServerAtomicallyDropsCredentialsAndCursor() = runTest {
        val file = File.createTempFile("lezi-sync-", ".preferences_pb").also { it.delete() }
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val preferences = DataStoreSyncPreferences(store)
        preferences.saveSession(
            SyncSession(
                baseUrl = "http://old-nas:8765",
                familyId = "old-family",
                familyToken = "old-token",
                deviceId = "stable-device",
                role = FamilyRole.Owner,
                pullCursor = 99,
                pullGeneration = "old-generation",
                lastSuccessAt = 123,
            ),
        )

        preferences.saveServer("http://new-nas:8765")

        assertThat(preferences.session.first()).isEqualTo(
            SyncSession(
                baseUrl = "http://new-nas:8765",
                deviceId = "stable-device",
            ),
        )
    }

    @Test
    fun replacingSessionDoesNotLeakPreviousFamiliesSuccessTime() = runTest {
        val file = File.createTempFile("lezi-sync-", ".preferences_pb").also { it.delete() }
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val preferences = DataStoreSyncPreferences(store)
        preferences.saveSession(
            SyncSession(
                baseUrl = "http://old-nas:8765",
                familyId = "old-family",
                familyToken = "old-token",
                deviceId = "stable-device",
                role = FamilyRole.Owner,
                pullCursor = 99,
                pullGeneration = "old-generation",
                lastSuccessAt = 123,
            ),
        )

        preferences.saveSession(
            SyncSession(
                baseUrl = "http://new-nas:8765",
                familyId = "new-family",
                familyToken = "new-token",
                deviceId = "stable-device",
                role = FamilyRole.Member,
            ),
        )

        assertThat(preferences.session.first().lastSuccessAt).isNull()
        assertThat(preferences.session.first().pullCursor).isEqualTo(0)
        assertThat(preferences.session.first().pullGeneration).isEmpty()
    }

    @Test
    fun createRequestIdSurvivesRetryUntilOwnerSessionIsPersisted() = runTest {
        val file = File.createTempFile("lezi-sync-", ".preferences_pb").also { it.delete() }
        val firstScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val firstStore = PreferenceDataStoreFactory.create(scope = firstScope) { file }
        val first = DataStoreSyncPreferences(firstStore)
        val requestId = first.ensureCreateRequestId()
        assertThat(first.ensureCreateRequestId()).isEqualTo(requestId)
        firstScope.cancel()
        advanceUntilIdle()

        val secondScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val secondStore = PreferenceDataStoreFactory.create(scope = secondScope) { file }
        val restored = DataStoreSyncPreferences(secondStore)
        assertThat(restored.ensureCreateRequestId()).isEqualTo(requestId)

        restored.clearCreateRequestId()
        assertThat(restored.ensureCreateRequestId()).isNotEqualTo(requestId)
        secondScope.cancel()
        file.delete()
    }
}
