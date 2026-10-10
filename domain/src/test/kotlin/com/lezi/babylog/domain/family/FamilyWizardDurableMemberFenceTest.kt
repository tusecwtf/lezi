package com.lezi.babylog.domain.family

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.NoOpSyncPort
import com.lezi.babylog.sync.PendingMemberLogin
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.session.DataStoreSyncPreferences
import com.lezi.babylog.sync.session.FamilyEndpointConfig
import com.lezi.babylog.sync.session.InMemorySecureRefreshTokenStore
import com.lezi.babylog.sync.session.SetupFamilyState
import com.lezi.babylog.sync.session.SetupProbeResult
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FamilyWizardDurableMemberFenceTest {
    @Test
    fun failedOrCancelledSamePinQrLeavesExistingTrustAndUnknownApplicationUntouched() = runTest {
        for (cancel in listOf(false, true)) {
            val file = File.createTempFile("wizard-qr-member-fence-", ".preferences_pb").also { it.delete() }
            val prefs = DataStoreSyncPreferences(PreferenceDataStoreFactory.create(scope = backgroundScope) { file }, InMemorySecureRefreshTokenStore())
            val endpoint = TrustedEndpointProfile.tofuSpki("https://family.example.com:8765", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            prefs.saveEndpointConfig(FamilyEndpointConfig.fromBaseUrl(endpoint.origin))
            prefs.rememberEndpoint(endpoint)
            val original = PendingMemberLogin("", "妈妈", "Phone", 0, "operation-a", endpoint.origin, true)
            prefs.saveMemberLoginAttempt(original)
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            var newWrites = 0
            val port = object : SyncPort by NoOpSyncPort() {
                override fun verifiedEndpoint() = prefs.verifiedEndpoint
                override suspend fun verifyEndpoint(endpoint: TrustedEndpointProfile) = SetupProbeResult.Ready(endpoint, SetupFamilyState.Configured)
                override suspend fun rememberEndpoint(endpoint: TrustedEndpointProfile) = runCatching { prefs.rememberEndpoint(endpoint) }
                override suspend fun forgetEndpoint() = runCatching { prefs.forgetEndpoint() }
                override suspend fun saveEndpointConfig(config: FamilyEndpointConfig) = runCatching { prefs.saveEndpointConfig(config) }
                override suspend fun claimMemberLoginQr(payload: com.lezi.babylog.sync.qr.MemberLoginQrPayload, deviceName: String): Result<com.lezi.babylog.sync.MemberLoginQrResult> {
                    assertThat(prefs.verifiedEndpoint.first()).isEqualTo(endpoint)
                    entered.complete(Unit)
                    if (cancel) kotlinx.coroutines.awaitCancellation()
                    return Result.failure(com.lezi.babylog.sync.MemberLoginQrUnavailableException())
                }
                override suspend fun requestMemberLogin(displayName: String, deviceName: String): Result<PendingMemberLogin> {
                    prefs.pendingMemberLogin.first()?.let { return Result.success(it) }
                    newWrites++
                    error("Original application must remain fenced")
                }
            }
            val local = object : FamilyWizardLocalStore {
                override suspend fun ensureScaffold() = Unit
                override suspend fun cacheDisplayName(displayName: String) = Unit
            }
            val controller = FamilyWizardController(SyncFamilyWizardGateway(local, port))
            val payload = com.lezi.babylog.sync.qr.MemberLoginQrPayload(endpoint, "grant-0000000000000000000000000000000000000", "Home", "妈妈", 9_999_999_999)
            controller.verifyMemberLoginQr(FamilyWizardEntry.Account, payload)
            val claim = launch { controller.claimMemberLoginQr(payload, "Phone") }
            entered.await()
            if (!cancel) claim.join()
            controller.cancelMemberLoginQr()
            claim.join()
            // Also exercise the old non-suspending/deferred-cleanup entry.
            controller.begin(snapshot(FamilyWizardEntry.Account, FamilyWizardMode.Join))
            controller.verifyMemberLoginQr(FamilyWizardEntry.Account, payload)
            assertThat(prefs.pendingMemberLogin.first()).isEqualTo(original)
            assertThat(prefs.verifiedEndpoint.first()).isEqualTo(endpoint)
            assertThat(port.requestMemberLogin("妈妈", "Phone").getOrThrow().operationId).isEqualTo(original.operationId)
            assertThat(newWrites).isEqualTo(0)
            prefs.clearPendingMemberLogin(expectedOperationId = original.operationId)
            assertThat(prefs.pendingMemberLogin.first()).isNull()
        }
    }

    @Test
    fun readyProbeRememberCannotEraseTheOriginalUnknownWriteBeforeTheSaveGuard() = runTest {
        for (entry in listOf(FamilyWizardEntry.Account, FamilyWizardEntry.Onboarding)) {
            val file = File.createTempFile("wizard-member-fence-", ".preferences_pb").also { it.delete() }
            val prefs = DataStoreSyncPreferences(
                PreferenceDataStoreFactory.create(scope = backgroundScope) { file },
                InMemorySecureRefreshTokenStore(),
            )
            val original = TrustedEndpointProfile.systemPki("https://family.example.com:8765")
            val other = TrustedEndpointProfile.systemPki("https://other.example.com:8765")
            prefs.saveEndpointConfig(FamilyEndpointConfig.fromBaseUrl(original.origin))
            prefs.rememberEndpoint(original)
            val attempt = PendingMemberLogin("", "妈妈", "Phone", 0, "operation-a", original.origin, true)
            prefs.saveMemberLoginAttempt(attempt)
            var newWrites = 0
            val port = object : SyncPort by NoOpSyncPort() {
                override fun verifiedEndpoint() = prefs.verifiedEndpoint
                override suspend fun probeEndpoint(endpointDraft: String) =
                    SetupProbeResult.Ready(TrustedEndpointProfile.systemPki(endpointDraft), SetupFamilyState.Configured)
                override suspend fun rememberEndpoint(endpoint: TrustedEndpointProfile) = runCatching { prefs.rememberEndpoint(endpoint) }
                override suspend fun saveEndpointConfig(config: FamilyEndpointConfig) = runCatching { prefs.saveEndpointConfig(config) }
                override suspend fun requestMemberLogin(displayName: String, deviceName: String): Result<PendingMemberLogin> {
                    val retained = prefs.pendingMemberLogin.first()
                    if (retained != null) return Result.success(retained)
                    newWrites++
                    error("Must retain the original operation")
                }
            }
            val local = object : FamilyWizardLocalStore {
                override suspend fun ensureScaffold() = Unit
                override suspend fun cacheDisplayName(displayName: String) = Unit
            }
            val controller = FamilyWizardController(SyncFamilyWizardGateway(local, port))
            controller.connectEndpoint(entry, other.origin)
            assertThat(controller.state.value).isInstanceOf(FamilyWizardState.RetryableFailure::class.java)
            assertThat(prefs.pendingMemberLogin.first()).isEqualTo(attempt)
            controller.connectEndpoint(entry, original.origin)
            controller.submit(snapshot(entry, FamilyWizardMode.Join).copy(
                host = "family.example.com", portText = "8765", scheme = "https",
                joinRole = FamilyWizardJoinRole.Member, displayName = "妈妈", deviceName = "Phone",
            ))
            assertThat(prefs.pendingMemberLogin.first()?.operationId).isEqualTo(attempt.operationId)
            assertThat(newWrites).isEqualTo(0)
            prefs.forgetEndpoint()
            prefs.rememberEndpoint(other)
            assertThat(prefs.pendingMemberLogin.first()).isNull()
        }
    }
}
