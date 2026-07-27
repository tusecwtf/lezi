package com.lezi.babylog.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
    fun sessionBaseUrlIsDerivedFromStructuredEndpoint() {
        val session = SyncSession(
            serverHost = "authoritative.home",
            serverPort = 9443,
            serverScheme = "https",
        )

        assertThat(session.baseUrl).isEqualTo("https://authoritative.home:9443")
    }

    @Test
    fun sessionAndCursorSurviveStoreRecreation() = runTest {
        val file = File.createTempFile("lezi-sync-", ".preferences_pb").also { it.delete() }
        val tokens = InMemorySecureFamilyTokenStore()
        val firstScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val firstStore = PreferenceDataStoreFactory.create(scope = firstScope) { file }
        val first = preferences(firstStore, tokens)
        first.saveSession(
            SyncSession(
                serverHost = "nas",
                familyId = "family-uuid",
                familyToken = "secret-token",
                deviceId = "device-uuid",
                role = FamilyRole.Owner,
                pullCursor = 41,
                pullGeneration = "server-generation",
                familyName = "乐乐一家",
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
        assertThat(restored.session.first().familyName).isEqualTo("乐乐一家")
        assertThat(tokens.getToken()).isEqualTo("secret-token")
        secondScope.cancel()
        file.delete()
    }

    @Test
    fun familyNameIsClearedWithFamilySessionAndBlankBecomesNull() = runTest {
        val file = File.createTempFile("lezi-sync-name-", ".preferences_pb").also { it.delete() }
        val tokens = InMemorySecureFamilyTokenStore()
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val preferences = preferences(store, tokens)
        preferences.saveSession(
            SyncSession(
                serverHost = "nas",
                familyId = "family",
                familyToken = "secret-token",
                deviceId = "device",
                role = FamilyRole.Owner,
                familyName = "  我家  ",
            ),
        )
        assertThat(preferences.session.first().familyName).isEqualTo("我家")

        preferences.saveSession(
            preferences.session.first().copy(familyName = "  "),
        )
        assertThat(preferences.session.first().familyName).isNull()
        assertThat(store.data.first()[stringPreferencesKey("sync_family_name")]).isNull()

        preferences.saveSession(
            preferences.session.first().copy(
                familyId = "family",
                familyToken = "secret-token",
                familyName = "恢复",
            ),
        )
        preferences.clearFamilySession()
        assertThat(preferences.session.first().familyName).isNull()
        assertThat(preferences.session.first().familyId).isEmpty()
        file.delete()
    }

    @Test
    fun pullCheckpointAppliesFamilyNamePresenceWithoutReplacingConcurrentSessionFields() =
        runTest {
            val file = File.createTempFile("lezi-sync-checkpoint-", ".preferences_pb")
                .also { it.delete() }
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
            val preferences = preferences(store)
            preferences.saveSession(
                SyncSession(
                    serverHost = "nas",
                    familyId = "family",
                    familyToken = "token",
                    deviceId = "device",
                    role = FamilyRole.Member,
                    pullCursor = 4,
                    pullGeneration = "g0",
                    familyName = "旧名字",
                    membershipId = "membership-before",
                    allowedSsids = listOf("Home"),
                ),
            )
            preferences.saveSession(
                preferences.session.first().copy(
                    membershipId = "membership-concurrent",
                    allowedSsids = listOf("Home", "Backup"),
                ),
            )

            preferences.updatePullCheckpoint(
                cursor = 5,
                generation = "g1",
                familyName = PullFamilyName.Present("  NAS 新名字  "),
            )

            assertThat(preferences.session.first().pullCursor).isEqualTo(5)
            assertThat(preferences.session.first().pullGeneration).isEqualTo("g1")
            assertThat(preferences.session.first().familyName).isEqualTo("NAS 新名字")
            assertThat(preferences.session.first().membershipId)
                .isEqualTo("membership-concurrent")
            assertThat(preferences.session.first().allowedSsids)
                .containsExactly("Home", "Backup")
                .inOrder()

            preferences.saveSession(
                preferences.session.first().copy(familyName = "旧 NAS 本地缓存"),
            )
            preferences.updatePullCheckpoint(
                cursor = 6,
                generation = "g1",
                familyName = PullFamilyName.Omitted,
            )
            assertThat(preferences.session.first().familyName).isEqualTo("旧 NAS 本地缓存")
            assertThat(preferences.session.first().pullCursor).isEqualTo(6)

            preferences.updatePullCheckpoint(
                cursor = 7,
                generation = "g2",
                familyName = PullFamilyName.Present(null),
            )
            assertThat(preferences.session.first().familyName).isNull()
            assertThat(preferences.session.first().pullCursor).isEqualTo(7)
            assertThat(preferences.session.first().pullGeneration).isEqualTo("g2")
            assertThat(preferences.session.first().membershipId)
                .isEqualTo("membership-concurrent")
            assertThat(preferences.session.first().allowedSsids)
                .containsExactly("Home", "Backup")
                .inOrder()
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
                serverHost = "nas",
                familyId = "family",
                familyToken = "secret-token",
                deviceId = "device",
                role = FamilyRole.Owner,
            ),
        )

        val raw = store.data.first()
        assertThat(raw[stringPreferencesKey("sync_base_url")]).isNull()
        assertThat(raw[stringPreferencesKey("sync_server_host")]).isEqualTo("nas")
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
    fun legacyBaseUrlMigratesToStructuredEndpointAndIsRemoved() = runTest {
        val file = File.createTempFile("lezi-sync-", ".preferences_pb").also { it.delete() }
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        store.edit { prefs ->
            prefs[stringPreferencesKey("sync_base_url")] = "https://legacy.home:9443"
            prefs[stringPreferencesKey("sync_family_id")] = "legacy-family"
            prefs[stringPreferencesKey("sync_family_token")] = "legacy-token"
            prefs[stringPreferencesKey("sync_device_id")] = "legacy-device"
            prefs[stringPreferencesKey("sync_family_role")] = FamilyRole.Member.name
        }
        val preferences = preferences(store)

        preferences.migrateSecretsIfNeeded()

        val restored = preferences.session.first()
        assertThat(restored.homeLanConfig).isEqualTo(
            HomeLanServerConfig(
                host = "legacy.home",
                port = 9443,
                scheme = "https",
            ),
        )
        assertThat(restored.baseUrl).isEqualTo("https://legacy.home:9443")
        assertThat(restored.isJoined).isTrue()
        val raw = store.data.first()
        assertThat(raw[stringPreferencesKey("sync_server_host")]).isEqualTo("legacy.home")
        assertThat(raw[intPreferencesKey("sync_server_port")]).isEqualTo(9443)
        assertThat(raw[stringPreferencesKey("sync_server_scheme")]).isEqualTo("https")
        assertThat(raw[stringPreferencesKey("sync_base_url")]).isNull()
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
                serverHost = "nas",
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
    fun changingJoinedServerClearsCredentialsAndFamilyReceipts() = runTest {
        val file = File.createTempFile("lezi-sync-", ".preferences_pb").also { it.delete() }
        val tokens = InMemorySecureFamilyTokenStore()
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val preferences = preferences(store, tokens)
        preferences.saveSession(
            SyncSession(
                serverHost = "old-nas",
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
                serverHost = "new-nas",
                deviceId = "stable-device",
            ),
        )
        assertThat(tokens.getToken()).isEmpty()
    }

    @Test
    fun replacingSessionDoesNotLeakPreviousFamiliesSuccessTime() = runTest {
        val file = File.createTempFile("lezi-sync-", ".preferences_pb").also { it.delete() }
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val preferences = preferences(store)
        preferences.saveSession(
            SyncSession(
                serverHost = "old-nas",
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
                serverHost = "new-nas",
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
                serverHost = "lezi.home",
                serverPort = 443,
                serverScheme = "https",
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

        restored.saveSession(
            SyncSession(
                serverHost = "nas",
                familyId = "family",
                familyToken = "token",
                deviceId = "device",
                role = FamilyRole.Owner,
            ),
        )
        assertThat(restored.ensureCreateRequestId()).isNotEqualTo(requestId)
        secondScope.cancel()
        file.delete()
    }
}
