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
class SyncPreferencesTrustEndpointTest {
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
    fun forgettingCurrentTrustedEndpointForcesReauthButKeepsFamilyReplicaIdentity() = runTest {
        val file = File.createTempFile("lezi-forget-current-trust-", ".preferences_pb")
            .also { it.delete() }
        val tokens = InMemorySecureRefreshTokenStore()
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val preferences = preferences(store, tokens)
        val endpoint = TrustedEndpointProfile.tofuSpki(
            "https://family.home:8765",
            Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
        )
        val active = SyncSession(
            serverHost = "family.home",
            serverPort = 8765,
            serverScheme = "https",
            familyId = "family-current",
            accessToken = "active-token",
            refreshToken = "active-refresh",
            accessExpiresAtEpochSeconds = 2_000_900,
            deviceId = "device-current",
            role = FamilyRole.Member,
            pullCursor = 41,
            membershipId = "membership-current",
        )
        preferences.rememberEndpoint(endpoint)
        preferences.saveSession(active)

        preferences.forgetEndpoint()

        assertThat(preferences.verifiedEndpoint.first()).isNull()
        val retained = preferences.session.first()
        assertThat(retained.reauthRequired).isTrue()
        assertThat(retained.accessToken).isEmpty()
        assertThat(retained.refreshToken).isEmpty()
        assertThat(retained.familyId).isEqualTo(active.familyId)
        assertThat(retained.membershipId).isEqualTo(active.membershipId)
        assertThat(retained.deviceId).isEqualTo(active.deviceId)
        assertThat(retained.pullCursor).isEqualTo(active.pullCursor)
        assertThat(tokens.getToken()).isEmpty()
        file.delete()
    }

    @Test
    fun replacingCurrentTofuPinForcesReauthBeforeTrustingTheNewCertificate() = runTest {
        val file = File.createTempFile("lezi-repin-current-trust-", ".preferences_pb")
            .also { it.delete() }
        val tokens = InMemorySecureRefreshTokenStore()
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val preferences = preferences(store, tokens)
        val origin = "https://family.home:8765"
        val oldEndpoint = TrustedEndpointProfile.tofuSpki(
            origin,
            Base64.getEncoder().encodeToString(ByteArray(32) { 1 }),
        )
        val newEndpoint = TrustedEndpointProfile.tofuSpki(
            origin,
            Base64.getEncoder().encodeToString(ByteArray(32) { 2 }),
        )
        preferences.rememberEndpoint(oldEndpoint)
        preferences.saveSession(
            SyncSession(
                serverHost = "family.home",
                serverPort = 8765,
                serverScheme = "https",
                familyId = "family-current",
                accessToken = "active-token",
                refreshToken = "active-refresh",
                accessExpiresAtEpochSeconds = 2_000_900,
                deviceId = "device-current",
                role = FamilyRole.Owner,
                membershipId = "membership-current",
            ),
        )

        preferences.rememberEndpoint(newEndpoint)

        assertThat(preferences.verifiedEndpoint.first()).isEqualTo(newEndpoint)
        val retained = preferences.session.first()
        assertThat(retained.reauthRequired).isTrue()
        assertThat(retained.accessToken).isEmpty()
        assertThat(retained.refreshToken).isEmpty()
        assertThat(retained.familyId).isEqualTo("family-current")
        assertThat(retained.membershipId).isEqualTo("membership-current")
        assertThat(tokens.getToken()).isEmpty()
        file.delete()
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
}
