package com.lezi.babylog.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
    private fun preferences(
        store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
        tokens: SecureFamilyTokenStore = InMemorySecureFamilyTokenStore(),
    ) = DataStoreSyncPreferences(store, tokens)

    @Test
    fun sessionAndCursorSurviveStoreRecreation() = runTest {
        val file = File.createTempFile("lezi-sync-", ".preferences_pb").also { it.delete() }
        val tokens = InMemorySecureFamilyTokenStore()
        val firstScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val firstStore = PreferenceDataStoreFactory.create(scope = firstScope) { file }
        val first = preferences(firstStore, tokens)
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
        // Same secure store process-local mock stands in for Keystore-wrapped prefs
        // that would also survive process recreation on device.
        val restored = preferences(secondStore, tokens)

        assertThat(restored.session.first().pullCursor).isEqualTo(41)
        assertThat(restored.session.first().pullGeneration).isEqualTo("server-generation")
        assertThat(restored.session.first().familyToken).isEqualTo("secret-token")
        assertThat(tokens.getToken()).isEqualTo("secret-token")
        secondScope.cancel()
        file.delete()
    }

    @Test
    fun familyTokenIsNotWrittenToPlaintextDataStore() = runTest {
        val file = File.createTempFile("lezi-sync-", ".preferences_pb").also { it.delete() }
        val tokens = InMemorySecureFamilyTokenStore()
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val preferences = preferences(store, tokens)
        preferences.saveSession(
            SyncSession(
                baseUrl = "http://nas:8765",
                familyId = "family",
                familyToken = "secret-token",
                deviceId = "device",
                role = FamilyRole.Owner,
            ),
        )

        val raw = store.data.first()
        assertThat(raw[stringPreferencesKey("sync_family_token")]).isNull()
        assertThat(tokens.getToken()).isEqualTo("secret-token")
        assertThat(preferences.session.first().familyToken).isEqualTo("secret-token")
        file.delete()
    }

    @Test
    fun migratesLegacyPlaintextFamilyTokenIntoSecureStore() = runTest {
        val file = File.createTempFile("lezi-sync-", ".preferences_pb").also { it.delete() }
        val tokens = InMemorySecureFamilyTokenStore()
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        store.edit { prefs ->
            prefs[stringPreferencesKey("sync_base_url")] = "http://nas:8765"
            prefs[stringPreferencesKey("sync_family_id")] = "family"
            prefs[stringPreferencesKey("sync_family_token")] = "legacy-plaintext-token"
            prefs[stringPreferencesKey("sync_device_id")] = "device"
            prefs[stringPreferencesKey("sync_family_role")] = FamilyRole.Owner.name
        }

        val preferences = preferences(store, tokens)
        // Readable immediately from legacy key before migration runs.
        assertThat(preferences.session.first().familyToken).isEqualTo("legacy-plaintext-token")

        preferences.migratePlaintextTokenIfPresent()

        assertThat(tokens.getToken()).isEqualTo("legacy-plaintext-token")
        assertThat(store.data.first()[stringPreferencesKey("sync_family_token")]).isNull()
        assertThat(preferences.session.first().familyToken).isEqualTo("legacy-plaintext-token")
        file.delete()
    }

    @Test
    fun clearingFamilyKeepsConfiguredServer() = runTest {
        val file = File.createTempFile("lezi-sync-", ".preferences_pb").also { it.delete() }
        val tokens = InMemorySecureFamilyTokenStore()
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val preferences = preferences(store, tokens)
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
        assertThat(tokens.getToken()).isEmpty()
    }

    @Test
    fun changingJoinedServerPreservesCredentialsAndResetsOnlyPullReceipt() = runTest {
        val file = File.createTempFile("lezi-sync-", ".preferences_pb").also { it.delete() }
        val tokens = InMemorySecureFamilyTokenStore()
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val preferences = preferences(store, tokens)
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
                familyId = "old-family",
                familyToken = "old-token",
                deviceId = "stable-device",
                role = FamilyRole.Owner,
                pullCursor = 0,
                pullGeneration = "",
                lastSuccessAt = 123,
                serverHost = "new-nas",
            ),
        )
        assertThat(tokens.getToken()).isEqualTo("old-token")
    }

    @Test
    fun replacingSessionDoesNotLeakPreviousFamiliesSuccessTime() = runTest {
        val file = File.createTempFile("lezi-sync-", ".preferences_pb").also { it.delete() }
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val preferences = preferences(store)
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
        assertThat(preferences.session.first().familyToken).isEqualTo("new-token")
    }

    @Test
    fun httpsSchemeSurvivesSessionPersistence() = runTest {
        val file = File.createTempFile("lezi-sync-", ".preferences_pb").also { it.delete() }
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val preferences = preferences(store)

        preferences.saveSession(
            SyncSession(
                baseUrl = "https://lezi.home:443",
                familyId = "family",
                familyToken = "token",
                deviceId = "device",
                role = FamilyRole.Owner,
            ),
        )

        val restored = preferences.session.first()
        assertThat(restored.baseUrl).isEqualTo("https://lezi.home:443")
        assertThat(restored.homeLanConfig.baseUrl).isEqualTo("https://lezi.home:443")
    }

    @Test
    fun createRequestIdSurvivesRetryUntilOwnerSessionIsPersisted() = runTest {
        val file = File.createTempFile("lezi-sync-", ".preferences_pb").also { it.delete() }
        val firstScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val firstStore = PreferenceDataStoreFactory.create(scope = firstScope) { file }
        val first = preferences(firstStore)
        val requestId = first.ensureCreateRequestId()
        assertThat(first.ensureCreateRequestId()).isEqualTo(requestId)
        firstScope.cancel()
        advanceUntilIdle()

        val secondScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val secondStore = PreferenceDataStoreFactory.create(scope = secondScope) { file }
        val restored = preferences(secondStore)
        assertThat(restored.ensureCreateRequestId()).isEqualTo(requestId)

        restored.clearCreateRequestId()
        assertThat(restored.ensureCreateRequestId()).isNotEqualTo(requestId)
        secondScope.cancel()
        file.delete()
    }
}
