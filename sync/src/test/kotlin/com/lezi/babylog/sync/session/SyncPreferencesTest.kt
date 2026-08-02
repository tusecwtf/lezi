package com.lezi.babylog.sync.session
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import com.lezi.babylog.sync.PendingMemberLogin
import com.lezi.babylog.sync.FamilyDevice
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.backend.MemberLoginReceipt

@OptIn(ExperimentalCoroutinesApi::class)
class SyncPreferencesTest {
    private fun preferences(
        store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
        tokens: SecureRefreshTokenStore = InMemorySecureRefreshTokenStore(),
    ) = DataStoreSyncPreferences(store, tokens)

    @Test
    fun minimalMemberDirectorySurvivesRestartButIdentityClearRemovesIt() = runTest {
        val file = File.createTempFile("lezi-member-directory-", ".preferences_pb")
            .also { it.delete() }
        val tokens = InMemorySecureRefreshTokenStore()
        val firstScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val first = preferences(
            PreferenceDataStoreFactory.create(scope = firstScope) { file },
            tokens,
        )
        first.saveSession(
            SyncSession(
                serverHost = "nas",
                familyId = "family",
                accessToken = "access",
                refreshToken = "refresh",
                deviceId = "device",
                role = FamilyRole.Owner,
                membershipId = "owner",
            ),
        )
        first.saveFamilyMemberDirectory(
            listOf(
                FamilyMember(
                    displayName = "妈妈",
                    role = FamilyRole.Owner,
                    isSelf = true,
                    membershipId = "owner",
                    devices = listOf(FamilyDevice("device", "手机", 7, true)),
                ),
                FamilyMember("爸爸", FamilyRole.Member, false, "member"),
            ),
        )
        firstScope.cancel()
        advanceUntilIdle()

        val secondScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val restored = preferences(
            PreferenceDataStoreFactory.create(scope = secondScope) { file },
            tokens,
        )
        val directory = restored.familyMemberDirectory.first()

        assertThat(directory.map(FamilyMember::displayName)).containsExactly("妈妈", "爸爸").inOrder()
        assertThat(directory.all { it.devices == null }).isTrue()

        restored.clearAllLocalSyncConfig()
        assertThat(restored.familyMemberDirectory.first()).isEmpty()
        secondScope.cancel()
        file.delete()
    }

    @Test
    fun reconnectedSessionAtomicallyRetiresSameFamilyMemberDirectory() = runTest {
        val file = File.createTempFile("lezi-reconnected-directory-", ".preferences_pb")
            .also { it.delete() }
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val preferences = preferences(store)
        preferences.saveSession(
            SyncSession(
                serverHost = "old.example.com",
                familyId = "family-preserved",
                accessToken = "old-access",
                refreshToken = "old-refresh",
                deviceId = "old-device",
                role = FamilyRole.Owner,
                membershipId = "old-owner",
            ),
        )
        preferences.saveFamilyMemberDirectory(
            listOf(FamilyMember("旧管理员", FamilyRole.Owner, true, "old-owner")),
        )
        val endpoint = TrustedEndpointProfile.systemPki("https://new.example.com")
        val restored = SyncSession(
            serverHost = "new.example.com",
            familyId = "family-preserved",
            accessToken = "new-access",
            refreshToken = "new-refresh",
            deviceId = "new-device",
            role = FamilyRole.Owner,
            membershipId = "new-owner",
        )

        preferences.saveReconnectedSession(restored, endpoint)

        assertThat(preferences.session.first()).isEqualTo(restored)
        assertThat(preferences.verifiedEndpoint.first()).isEqualTo(endpoint)
        assertThat(preferences.familyMemberDirectory.first()).isEmpty()
        file.delete()
    }

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
    fun probedEndpointIsPersistedSeparatelyAndForgettingItPreservesActiveSession() = runTest {
        val file = File.createTempFile("lezi-trusted-endpoint-", ".preferences_pb")
            .also { it.delete() }
        val tokens = InMemorySecureRefreshTokenStore()
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val preferences = preferences(store, tokens)
        val active = SyncSession(
            serverHost = "current.example.com",
            serverScheme = "https",
            serverPort = 443,
            familyId = "family-current",
            accessToken = "active-token",
            refreshToken = "active-refresh",
            deviceId = "device-current",
            role = FamilyRole.Owner,
            membershipId = "membership-current",
        )
        preferences.saveSession(active)
        val probed = TrustedEndpointProfile.systemPki("https://next.example.com")

        preferences.rememberEndpoint(probed)

        assertThat(preferences.verifiedEndpoint.first()).isEqualTo(probed)
        assertThat(preferences.session.first()).isEqualTo(active)
        assertThat(tokens.getToken()).isEqualTo("active-refresh")

        preferences.forgetEndpoint()

        assertThat(preferences.verifiedEndpoint.first()).isNull()
        assertThat(preferences.session.first()).isEqualTo(active)
        assertThat(tokens.getToken()).isEqualTo("active-refresh")
    }

    @Test
    fun tofuSpkiPinSurvivesStoreRecreationWithoutPersistingAHandshakeCandidate() = runTest {
        val file = File.createTempFile("lezi-tofu-endpoint-", ".preferences_pb")
            .also { it.delete() }
        val firstScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val first = preferences(
            PreferenceDataStoreFactory.create(scope = firstScope) { file },
        )
        val endpoint = TrustedEndpointProfile.tofuSpki(
            "https://192.168.50.4:8765",
            Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
        )

        first.rememberEndpoint(endpoint)
        firstScope.cancel()
        advanceUntilIdle()

        val secondScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val second = preferences(
            PreferenceDataStoreFactory.create(scope = secondScope) { file },
        )
        assertThat(second.verifiedEndpoint.first()).isEqualTo(endpoint)
        secondScope.cancel()
    }

    @Test
    fun sessionAndCursorSurviveStoreRecreation() = runTest {
        val file = File.createTempFile("lezi-sync-", ".preferences_pb").also { it.delete() }
        val tokens = InMemorySecureRefreshTokenStore()
        val firstScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val firstStore = PreferenceDataStoreFactory.create(scope = firstScope) { file }
        val first = preferences(firstStore, tokens)
        first.saveSession(
            SyncSession(
                serverHost = "nas",
                familyId = "family-uuid",
                accessToken = "short-access-token",
                refreshToken = "secret-refresh-token",
                accessExpiresAtEpochSeconds = 1_753_419_300,
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
        assertThat(restored.session.first().accessToken).isEmpty()
        assertThat(restored.session.first().refreshToken).isEqualTo("secret-refresh-token")
        assertThat(restored.session.first().accessExpiresAtEpochSeconds).isEqualTo(0)
        assertThat(restored.session.first().familyName).isEqualTo("乐乐一家")
        assertThat(restored.session.first().membershipId)
            .isEqualTo("membership-after-members-call")
        assertThat(restored.session.first().pendingCreatorAcknowledgements)
            .containsExactlyElementsIn(pendingCreatorAcknowledgements)
        assertThat(tokens.getToken()).isEqualTo("secret-refresh-token")

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
        val tokens = InMemorySecureRefreshTokenStore()
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val preferences = preferences(store, tokens)
        preferences.saveSession(
            SyncSession(
                serverHost = "nas",
                familyId = "family",
                accessToken = "secret-token",
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
                accessToken = "secret-token",
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
                    accessToken = "token",
                    deviceId = "device",
                    role = FamilyRole.Member,
                    pullCursor = 4,
                    pullGeneration = "g0",
                    familyName = "旧名字",
                    membershipId = "membership-before",
                ),
            )
            preferences.saveSession(
                preferences.session.first().copy(
                    membershipId = "membership-concurrent",
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
                accessToken = "token-a",
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
                accessToken = "token-b",
                familyName = "另一个家庭",
            ),
        )

        assertThat(preferences.session.first().pendingCreatorAcknowledgements).isEmpty()
        file.delete()
    }

    @Test
    fun accessIsProcessOnlyAndRefreshIsNotWrittenToPlaintextDataStore() = runTest {
        val file = File.createTempFile("lezi-sync-", ".preferences_pb").also { it.delete() }
        val tokens = InMemorySecureRefreshTokenStore()
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val preferences = preferences(store, tokens)
        preferences.saveSession(
            SyncSession(
                serverHost = "nas",
                familyId = "family",
                accessToken = "secret-token",
                refreshToken = "secret-refresh-token",
                deviceId = "device",
                role = FamilyRole.Owner,
            ),
        )

        val raw = store.data.first()
        assertThat(raw[stringPreferencesKey("sync_server_host")]).isEqualTo("nas")
        assertThat(tokens.getToken()).isEqualTo("secret-refresh-token")
        assertThat(preferences.session.first().accessToken).isEqualTo("secret-token")
        file.delete()
    }

    @Test
    fun reauthClearRemovesOnlyCredentialsAndSurvivesProcessRecreation() = runTest {
        val file = File.createTempFile("lezi-reauth-", ".preferences_pb").also { it.delete() }
        val tokens = InMemorySecureRefreshTokenStore()
        val firstScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val first = preferences(
            PreferenceDataStoreFactory.create(scope = firstScope) { file },
            tokens,
        )
        val endpoint = TrustedEndpointProfile.systemPki("https://family.example.com")
        first.rememberEndpoint(endpoint)
        first.saveSession(
            SyncSession(
                serverHost = "family.example.com",
                serverPort = 443,
                serverScheme = "https",
                familyId = "family",
                accessToken = "access-secret",
                refreshToken = "refresh-secret",
                accessExpiresAtEpochSeconds = 2_000_900,
                deviceId = "device",
                role = FamilyRole.Member,
                pullCursor = 42,
                pullGeneration = "generation",
                familyName = "乐乐一家",
                membershipId = "membership",
            ),
        )

        first.clearDeviceCredentialsForReauth()

        val retained = first.session.first()
        assertThat(retained.reauthRequired).isTrue()
        assertThat(retained.accessToken).isEmpty()
        assertThat(retained.refreshToken).isEmpty()
        assertThat(retained.familyId).isEqualTo("family")
        assertThat(retained.deviceId).isEqualTo("device")
        assertThat(retained.membershipId).isEqualTo("membership")
        assertThat(retained.pullCursor).isEqualTo(42)
        assertThat(first.verifiedEndpoint.first()).isEqualTo(endpoint)
        assertThat(tokens.getToken()).isEmpty()
        firstScope.cancel()
        advanceUntilIdle()

        val secondScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val restored = preferences(
            PreferenceDataStoreFactory.create(scope = secondScope) { file },
            tokens,
        )
        assertThat(restored.session.first().reauthRequired).isTrue()
        assertThat(restored.session.first().familyId).isEqualTo("family")
        assertThat(restored.session.first().pullCursor).isEqualTo(42)
        assertThat(restored.verifiedEndpoint.first()).isEqualTo(endpoint)
        secondScope.cancel()
        file.delete()
    }

    @Test
    fun changingJoinedServerClearsCredentialsAndFamilyReceipts() = runTest {
        val file = File.createTempFile("lezi-sync-", ".preferences_pb").also { it.delete() }
        val tokens = InMemorySecureRefreshTokenStore()
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val preferences = preferences(store, tokens)
        preferences.saveSession(
            SyncSession(
                serverHost = "old-nas",
                familyId = "old-family",
                accessToken = "old-token",
                refreshToken = "old-refresh-token",
                deviceId = "stable-device",
                role = FamilyRole.Owner,
                pullCursor = 99,
                pullGeneration = "old-generation",
                lastSuccessAt = 123,
            ),
        )

        preferences.saveEndpointConfig(
            FamilyEndpointConfig.fromBaseUrl("https://new-nas:8765"),
        )

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
                accessToken = "old-token",
                refreshToken = "old-refresh-token",
                deviceId = "stable-device",
                role = FamilyRole.Owner,
                pullCursor = 99,
                pullGeneration = "old-generation",
            ),
        )
        tokens.failNextClear = true

        val failure = runCatching {
            first.saveEndpointConfig(
                FamilyEndpointConfig.fromBaseUrl("https://new-nas:8765"),
            )
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("secure clear interrupted")
        assertThat(tokens.getToken()).isEqualTo("old-refresh-token")
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
    fun pendingDeviceRemovalClearMarkerSurvivesRestartUntilExplicitCompletion() = runTest {
        val file = File.createTempFile("lezi-device-removal-", ".preferences_pb")
            .also { it.delete() }
        val firstScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val first = preferences(PreferenceDataStoreFactory.create(scope = firstScope) { file })

        first.markPendingDeviceRemovalClear()
        assertThat(first.hasPendingDeviceRemovalClear()).isTrue()
        firstScope.cancel()
        advanceUntilIdle()

        val secondScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val restored = preferences(PreferenceDataStoreFactory.create(scope = secondScope) { file })
        assertThat(restored.hasPendingDeviceRemovalClear()).isTrue()

        restored.clearPendingDeviceRemovalClear()
        assertThat(restored.hasPendingDeviceRemovalClear()).isFalse()
        secondScope.cancel()
        file.delete()
    }

    @Test
    fun terminalClearMarkerMakesCredentialsUnusableBeforeDomainClearStarts() = runTest {
        val file = File.createTempFile("lezi-terminal-gate-", ".preferences_pb")
            .also { it.delete() }
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val preferences = preferences(store)
        preferences.saveSession(
            SyncSession(
                serverHost = "family.home",
                familyId = "family-id",
                accessToken = "access-token",
                refreshToken = "refresh-token",
                deviceId = "device-id",
                membershipId = "membership-id",
                role = FamilyRole.Member,
            ),
        )

        preferences.markPendingDeviceRemovalClear()

        val gated = preferences.session.first()
        assertThat(gated.familyId).isEqualTo("family-id")
        assertThat(gated.accessToken).isEmpty()
        assertThat(gated.refreshToken).isEmpty()
        assertThat(gated.reauthRequired).isTrue()
        assertThat(gated.isJoined).isFalse()
        file.delete()
    }

    @Test
    fun pendingMembershipDeletionClearMarkerSurvivesRestartUntilExplicitCompletion() = runTest {
        val file = File.createTempFile("lezi-membership-deletion-", ".preferences_pb")
            .also { it.delete() }
        val firstScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val first = preferences(PreferenceDataStoreFactory.create(scope = firstScope) { file })

        first.markPendingMembershipDeletionClear()
        assertThat(first.hasPendingMembershipDeletionClear()).isTrue()
        firstScope.cancel()
        advanceUntilIdle()

        val secondScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val restored = preferences(PreferenceDataStoreFactory.create(scope = secondScope) { file })
        assertThat(restored.hasPendingMembershipDeletionClear()).isTrue()

        restored.clearPendingMembershipDeletionClear()
        assertThat(restored.hasPendingMembershipDeletionClear()).isFalse()
        secondScope.cancel()
        file.delete()
    }

    @Test
    fun pendingFamilyDeletionClearMarkerSurvivesRestartUntilExplicitCompletion() = runTest {
        val file = File.createTempFile("lezi-family-deletion-", ".preferences_pb")
            .also { it.delete() }
        val firstScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val first = preferences(PreferenceDataStoreFactory.create(scope = firstScope) { file })

        first.markPendingFamilyDeletionClear()
        assertThat(first.hasPendingFamilyDeletionClear()).isTrue()
        firstScope.cancel()
        advanceUntilIdle()

        val secondScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val restored = preferences(PreferenceDataStoreFactory.create(scope = secondScope) { file })
        assertThat(restored.hasPendingFamilyDeletionClear()).isTrue()

        restored.clearPendingFamilyDeletionClear()
        assertThat(restored.hasPendingFamilyDeletionClear()).isFalse()
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
                accessToken = "old-token",
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
                accessToken = "new-token",
                deviceId = "stable-device",
                role = FamilyRole.Member,
            ),
        )

        assertThat(preferences.session.first().lastSuccessAt).isNull()
        assertThat(preferences.session.first().pullCursor).isEqualTo(0)
        assertThat(preferences.session.first().pullGeneration).isEmpty()
        assertThat(preferences.session.first().accessToken).isEqualTo("new-token")
    }

    @Test
    fun interruptedSessionReplacementPersistsNewIdentityAsReauthBeforeWritingRefreshToken() =
        runTest {
            val file = File.createTempFile("lezi-session-replace-", ".preferences_pb")
                .also { it.delete() }
            val tokens = FailOnceSetTokenStore()
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
            val preferences = preferences(store, tokens)
            preferences.saveSession(
                SyncSession(
                    serverHost = "old.home",
                    familyId = "old-family",
                    accessToken = "old-access",
                    refreshToken = "old-refresh",
                    deviceId = "old-device",
                    membershipId = "old-membership",
                    role = FamilyRole.Owner,
                ),
            )
            tokens.failNextSet = true

            val failure = runCatching {
                preferences.saveSession(
                    SyncSession(
                        serverHost = "new.home",
                        familyId = "new-family",
                        accessToken = "new-access",
                        refreshToken = "new-refresh",
                        deviceId = "new-device",
                        membershipId = "new-membership",
                        role = FamilyRole.Member,
                    ),
                )
            }.exceptionOrNull()

            assertThat(failure).hasMessageThat().contains("secure set interrupted")
            assertThat(tokens.getToken()).isEqualTo("old-refresh")
            val gated = preferences.session.first()
            assertThat(gated.familyId).isEqualTo("new-family")
            assertThat(gated.deviceId).isEqualTo("new-device")
            assertThat(gated.accessToken).isEmpty()
            assertThat(gated.refreshToken).isEmpty()
            assertThat(gated.reauthRequired).isTrue()

            preferences.recoverPendingCredentialClear()
            assertThat(tokens.getToken()).isEmpty()
            assertThat(preferences.session.first().familyId).isEqualTo("new-family")
            file.delete()
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
                accessToken = "token",
                deviceId = "device",
                role = FamilyRole.Owner,
            ),
        )

        val restored = preferences.session.first()
        assertThat(restored.baseUrl).isEqualTo("https://lezi.home:443")
        assertThat(restored.endpointConfig.baseUrl).isEqualTo("https://lezi.home:443")
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
                accessToken = "token",
                deviceId = "device",
                role = FamilyRole.Owner,
            ),
        )
        assertThat(restored.ensureCreateRequestId()).isNotEqualTo(requestId)
        secondScope.cancel()
        file.delete()
    }

    @Test
    fun ownerLoginRequestIdSurvivesRetryUntilOwnerSessionIsPersisted() = runTest {
        val file = File.createTempFile("lezi-owner-login-", ".preferences_pb").also { it.delete() }
        val firstScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val firstStore = PreferenceDataStoreFactory.create(scope = firstScope) { file }
        val first = preferences(firstStore)
        val requestId = first.ensureOwnerLoginRequestId()
        assertThat(first.ensureOwnerLoginRequestId()).isEqualTo(requestId)
        firstScope.cancel()
        advanceUntilIdle()

        val secondScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val secondStore = PreferenceDataStoreFactory.create(scope = secondScope) { file }
        val restored = preferences(secondStore)
        assertThat(restored.ensureOwnerLoginRequestId()).isEqualTo(requestId)

        restored.saveSession(
            SyncSession(
                serverHost = "nas",
                familyId = "family",
                accessToken = "token",
                deviceId = "device",
                role = FamilyRole.Owner,
            ),
        )
        assertThat(restored.ensureOwnerLoginRequestId()).isNotEqualTo(requestId)
        secondScope.cancel()
        file.delete()
    }

    @Test
    fun pendingMemberCapabilitySurvivesProcessButIsEncryptedAndRetiredWithSession() = runTest {
        val file = File.createTempFile("lezi-member-pending-", ".preferences_pb")
            .also { it.delete() }
        val tokens = InMemorySecureRefreshTokenStore()
        val firstScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val firstStore = PreferenceDataStoreFactory.create(scope = firstScope) { file }
        val first = preferences(firstStore, tokens)
        val receipt = MemberLoginReceipt(
            requestId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            pendingSecret = "pending-secret-000000000000000000000001",
            expiresAtEpochSeconds = 1_753_504_800,
        )

        first.savePendingMemberLogin(receipt, "爸爸", "Pixel 9")
        assertThat(first.pendingMemberLogin.first()).isEqualTo(
            PendingMemberLogin(
                requestId = receipt.requestId,
                displayName = "爸爸",
                deviceName = "Pixel 9",
                expiresAtEpochSeconds = receipt.expiresAtEpochSeconds,
            ),
        )
        assertThat(first.pendingMemberSecret()).isEqualTo(receipt.pendingSecret)
        assertThat(file.readBytes().toString(Charsets.ISO_8859_1))
            .doesNotContain(receipt.pendingSecret)
        firstScope.cancel()
        advanceUntilIdle()

        val secondScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val restored = preferences(
            PreferenceDataStoreFactory.create(scope = secondScope) { file },
            tokens,
        )
        assertThat(restored.pendingMemberLogin.first()?.requestId).isEqualTo(receipt.requestId)
        assertThat(restored.pendingMemberSecret()).isEqualTo(receipt.pendingSecret)

        restored.saveSession(
            SyncSession(
                serverHost = "family.home",
                serverScheme = "https",
                familyId = "family",
                accessToken = "member-access",
                refreshToken = "member-refresh",
                deviceId = "member-device",
                role = FamilyRole.Member,
                membershipId = "member-membership",
            ),
        )
        assertThat(restored.pendingMemberLogin.first()).isNull()
        assertThat(tokens.getPendingMemberSecret()).isEmpty()
        secondScope.cancel()
        file.delete()
    }

    @Test
    fun disasterRestoreCheckpointSurvivesRestartWithoutWritingCredentialToDataStore() = runTest {
        val file = File.createTempFile("lezi-restore-checkpoint-", ".preferences_pb")
            .also { it.delete() }
        val tokens = InMemorySecureRefreshTokenStore()
        val firstScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val first = preferences(
            PreferenceDataStoreFactory.create(scope = firstScope) { file },
            tokens,
        )
        val endpoint = TrustedEndpointProfile.systemPki("https://family.home:8765")
        val checkpoint = DisasterRestoreCheckpoint(
            batchId = "batch-a",
            endpoint = endpoint,
            familyId = "family-a",
            startRequestId = "start-request-00000000000000000001",
            manifestRequestId = "manifest-request-0000000000000001",
            commitRequestId = "commit-request-000000000000000001",
            expiresAtEpochSeconds = 1_753_504_800,
            status = "manifest_received",
            entityVersions = listOf(
                DisasterRestoreEntityVersion("record", "record-a", 42, true),
            ),
        )
        val recoveryToken = "restore-token-000000000000000000000001"

        first.saveDisasterRestoreCheckpoint(checkpoint, recoveryToken)
        assertThat(first.disasterRestoreToken()).isEqualTo(recoveryToken)
        assertThat(file.readBytes().toString(Charsets.ISO_8859_1))
            .doesNotContain(recoveryToken)
        firstScope.cancel()
        advanceUntilIdle()

        val secondScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val restored = preferences(
            PreferenceDataStoreFactory.create(scope = secondScope) { file },
            tokens,
        )
        assertThat(restored.disasterRestoreCheckpoint.first()).isEqualTo(checkpoint)
        assertThat(restored.disasterRestoreToken()).isEqualTo(recoveryToken)

        restored.clearDisasterRestoreCheckpoint()
        assertThat(restored.disasterRestoreCheckpoint.first()).isNull()
        assertThat(restored.disasterRestoreToken()).isEmpty()
        secondScope.cancel()
        file.delete()
    }

    @Test
    fun nonAuthoritativeConfigRewriteKeepsPendingMemberCapability() = runTest {
        val file = File.createTempFile("lezi-member-config-", ".preferences_pb")
            .also { it.delete() }
        val tokens = InMemorySecureRefreshTokenStore()
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val preferences = preferences(store, tokens)
        val receipt = MemberLoginReceipt(
            requestId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            pendingSecret = "pending-secret-000000000000000000000002",
            expiresAtEpochSeconds = 1_753_504_800,
        )
        preferences.saveEndpointConfig(FamilyEndpointConfig(host = "old.home"))
        preferences.savePendingMemberLogin(receipt, "奶奶", "Pixel 10")

        preferences.saveEndpointConfig(
            FamilyEndpointConfig(host = "new.home"),
            clearSessionIfServerChanged = false,
        )

        assertThat(preferences.pendingMemberLogin.first()?.requestId).isEqualTo(receipt.requestId)
        assertThat(preferences.pendingMemberSecret()).isEqualTo(receipt.pendingSecret)
        file.delete()
    }
}

private class FailOnceClearTokenStore : SecureRefreshTokenStore {
    private val delegate = InMemorySecureRefreshTokenStore()
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

private class FailOnceSetTokenStore : SecureRefreshTokenStore {
    private val delegate = InMemorySecureRefreshTokenStore()
    var failNextSet = false

    override fun getToken(): String = delegate.getToken()

    override fun setToken(token: String) {
        if (failNextSet) {
            failNextSet = false
            throw IllegalStateException("secure set interrupted")
        }
        delegate.setToken(token)
    }

    override fun clearToken() = delegate.clearToken()
}
