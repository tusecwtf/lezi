package com.lezi.babylog.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
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
        val pendingCreatorAcknowledgements = setOf(
            CreatorAcknowledgementRef("care_plan", "plan-awaiting-author"),
            CreatorAcknowledgementRef("custom_item", "item-awaiting-author"),
        )
        first.updateCreatorAcknowledgements(add = pendingCreatorAcknowledgements)
        first.saveSession(
            first.session.first().copy(membershipId = "membership-after-members-call"),
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
        assertThat(restored.session.first().membershipId)
            .isEqualTo("membership-after-members-call")
        assertThat(restored.session.first().pendingCreatorAcknowledgements)
            .containsExactlyElementsIn(pendingCreatorAcknowledgements)
        assertThat(tokens.getToken()).isEqualTo("secret-token")

        restored.updateCreatorAcknowledgements(
            remove = setOf(CreatorAcknowledgementRef("care_plan", "plan-awaiting-author")),
        )
        assertThat(restored.session.first().pendingCreatorAcknowledgements).containsExactly(
            CreatorAcknowledgementRef("custom_item", "item-awaiting-author"),
        )
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
        preferences.updateCreatorAcknowledgements(
            add = setOf(CreatorAcknowledgementRef("care_plan", "plan-before-clear")),
        )
        preferences.clearAllLocalSyncConfig()
        assertThat(preferences.session.first().familyName).isNull()
        assertThat(preferences.session.first().familyId).isEmpty()
        assertThat(preferences.session.first().pendingCreatorAcknowledgements).isEmpty()
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
            preferences.updateCreatorAcknowledgements(
                add = setOf(CreatorAcknowledgementRef("custom_item", "item-concurrent")),
            )

            preferences.updatePullCheckpoint(
                cursor = 5,
                generation = "g1",
                familyName = "  NAS 新名字  ",
            )

            assertThat(preferences.session.first().pullCursor).isEqualTo(5)
            assertThat(preferences.session.first().pullGeneration).isEqualTo("g1")
            assertThat(preferences.session.first().familyName).isEqualTo("NAS 新名字")
            assertThat(preferences.session.first().membershipId)
                .isEqualTo("membership-concurrent")
            assertThat(preferences.session.first().allowedSsids)
                .containsExactly("Home", "Backup")
                .inOrder()
            assertThat(preferences.session.first().pendingCreatorAcknowledgements).containsExactly(
                CreatorAcknowledgementRef("custom_item", "item-concurrent"),
            )

            preferences.updatePullCheckpoint(
                cursor = 7,
                generation = "g2",
                familyName = null,
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
    fun staleSameFamilySaveCannotEraseCreatorAcknowledgementsButFamilyChangeDoes() = runTest {
        val file = File.createTempFile("lezi-sync-creator-ack-", ".preferences_pb")
            .also { it.delete() }
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val preferences = preferences(store)
        preferences.saveSession(
            SyncSession(
                serverHost = "nas",
                familyId = "family-a",
                familyToken = "token-a",
                deviceId = "device",
                role = FamilyRole.Member,
            ),
        )
        val stale = preferences.session.first()
        val pending = CreatorAcknowledgementRef("care_plan", "plan-stale-save")
        preferences.updateCreatorAcknowledgements(add = setOf(pending))

        preferences.saveSession(stale.copy(familyName = "同一家庭的新名字"))

        assertThat(preferences.session.first().pendingCreatorAcknowledgements)
            .containsExactly(pending)

        preferences.saveSession(
            stale.copy(
                familyId = "family-b",
                familyToken = "token-b",
                familyName = "另一个家庭",
            ),
        )

        assertThat(preferences.session.first().pendingCreatorAcknowledgements).isEmpty()
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
        assertThat(raw[stringPreferencesKey("sync_server_host")]).isEqualTo("nas")
        assertThat(tokens.getToken()).isEqualTo("secret-token")
        assertThat(preferences.session.first().familyToken).isEqualTo("secret-token")
        file.delete()
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
    fun interruptedEndpointCredentialClearIsSuppressedAndRecoveredAfterRestart() = runTest {
        val file = File.createTempFile("lezi-sync-endpoint-", ".preferences_pb")
            .also { it.delete() }
        val tokens = FailOnceClearTokenStore()
        val firstScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val firstStore = PreferenceDataStoreFactory.create(scope = firstScope) { file }
        val first = preferences(firstStore, tokens)
        first.saveSession(
            SyncSession(
                serverHost = "old-nas",
                familyId = "old-family",
                familyToken = "old-token",
                deviceId = "stable-device",
                role = FamilyRole.Owner,
                pullCursor = 99,
                pullGeneration = "old-generation",
            ),
        )
        tokens.failNextClear = true

        val failure = runCatching {
            first.saveServer("http://new-nas:8765")
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("secure clear interrupted")
        assertThat(tokens.getToken()).isEqualTo("old-token")
        assertThat(first.session.first()).isEqualTo(
            SyncSession(
                serverHost = "new-nas",
                deviceId = "stable-device",
            ),
        )
        assertThat(
            firstStore.data.first()[booleanPreferencesKey("sync_pending_family_credential_clear")],
        ).isTrue()
        firstScope.cancel()
        advanceUntilIdle()

        val secondScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val secondStore = PreferenceDataStoreFactory.create(scope = secondScope) { file }
        val restored = preferences(secondStore, tokens)
        restored.recoverPendingCredentialClear()

        assertThat(tokens.getToken()).isEmpty()
        assertThat(
            secondStore.data.first()[booleanPreferencesKey("sync_pending_family_credential_clear")],
        ).isNull()
        assertThat(restored.session.first()).isEqualTo(
            SyncSession(
                serverHost = "new-nas",
                deviceId = "stable-device",
            ),
        )
        secondScope.cancel()
        file.delete()
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

private class FailOnceClearTokenStore : SecureFamilyTokenStore {
    private val delegate = InMemorySecureFamilyTokenStore()
    var failNextClear = false

    override fun getToken(): String = delegate.getToken()

    override fun setToken(token: String) = delegate.setToken(token)

    override fun clearToken() {
        if (failNextClear) {
            failNextClear = false
            throw IllegalStateException("secure clear interrupted")
        }
        delegate.clearToken()
    }
}
