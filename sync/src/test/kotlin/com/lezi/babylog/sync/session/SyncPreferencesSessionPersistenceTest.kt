package com.lezi.babylog.sync.session
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import com.lezi.babylog.sync.PendingMemberLogin
import com.lezi.babylog.sync.FamilyDevice
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.backend.MemberLoginReceipt

@OptIn(ExperimentalCoroutinesApi::class)
class SyncPreferencesSessionPersistenceTest {
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
    fun refreshTokenMirrorReadsEncryptedStoreOnColdStartAndNotOnLaterMaps() = runTest {
        val file = File.createTempFile("lezi-refresh-mirror-", ".preferences_pb")
            .also { it.delete() }
        val tokens = CountingRefreshTokenStore()
        val firstScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val first = preferences(
            PreferenceDataStoreFactory.create(scope = firstScope) { file },
            tokens,
        )
        first.saveSession(
            SyncSession(
                serverHost = "nas",
                familyId = "family-uuid",
                accessToken = "access",
                refreshToken = "refresh-secret",
                deviceId = "device-uuid",
                role = FamilyRole.Owner,
                membershipId = "membership",
            ),
        )
        val readsAfterSave = tokens.getCount
        assertThat(first.session.first().refreshToken).isEqualTo("refresh-secret")
        assertThat(first.session.first().refreshToken).isEqualTo("refresh-secret")
        assertThat(tokens.getCount).isEqualTo(readsAfterSave)
        firstScope.cancel()
        advanceUntilIdle()

        val secondScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val restarted = preferences(
            PreferenceDataStoreFactory.create(scope = secondScope) { file },
            tokens,
        )
        assertThat(restarted.session.first().refreshToken).isEqualTo("refresh-secret")
        assertThat(tokens.getCount).isEqualTo(readsAfterSave + 1)
        assertThat(restarted.session.first().refreshToken).isEqualTo("refresh-secret")
        assertThat(tokens.getCount).isEqualTo(readsAfterSave + 1)

        restarted.markPendingDeviceRemovalClear()
        val readsBeforeGate = tokens.getCount
        val gated = restarted.session.first()
        assertThat(gated.refreshToken).isEmpty()
        assertThat(gated.accessToken).isEmpty()
        assertThat(gated.reauthRequired).isTrue()
        assertThat(tokens.getToken()).isEqualTo("refresh-secret")
        assertThat(tokens.getCount).isEqualTo(readsBeforeGate + 1)
        secondScope.cancel()
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
}

private class CountingRefreshTokenStore : SecureRefreshTokenStore {
    private val delegate = InMemorySecureRefreshTokenStore()
    var getCount = 0

    override fun getToken(): String {
        getCount += 1
        return delegate.getToken()
    }

    override fun setToken(token: String) = delegate.setToken(token)

    override fun clearToken() = delegate.clearToken()
}
