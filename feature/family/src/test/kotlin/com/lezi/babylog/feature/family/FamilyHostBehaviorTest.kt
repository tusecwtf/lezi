package com.lezi.babylog.feature.family

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.domain.family.FamilyWizardController
import com.lezi.babylog.domain.family.FamilyWizardEntry
import com.lezi.babylog.domain.family.FamilyWizardGateway
import com.lezi.babylog.domain.family.FamilyWizardState
import com.lezi.babylog.feature.family.members.FamilyMembersState
import com.lezi.babylog.feature.family.members.MembersDevicesActions
import com.lezi.babylog.sync.CreateFamilyResult
import com.lezi.babylog.sync.session.CertificateTrustCandidate
import com.lezi.babylog.sync.session.FamilyEndpointConfig
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import com.lezi.babylog.sync.MemberLoginQrResult
import com.lezi.babylog.sync.NoOpSyncPort
import com.lezi.babylog.sync.OwnerLoginResult
import com.lezi.babylog.sync.backend.PendingMemberLoginRequest
import com.lezi.babylog.sync.backend.PendingMemberRenameRequest
import com.lezi.babylog.sync.session.SetupFamilyState
import com.lezi.babylog.sync.session.SetupProbeResult
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Behavior contracts for ticket-24 hosts (not reflection-only surface lists).
 * Uses [SyncPort] fakes for roster refresh / approval and wizard QR thin-delegate path.
 */
class FamilyHostBehaviorTest {
    @Test
    fun membersRosterRefreshLoadsMembersAndPendingForOwner() = runTest {
        val member = FamilyMember("管理员", FamilyRole.Owner, true, "m-owner")
        val pending = PendingMemberLoginRequest(
            requestId = "req-1",
            displayName = "爷爷",
            deviceName = "新手机",
            createdAtEpochSeconds = 1_000,
            expiresAtEpochSeconds = 2_000,
        )
        val sync = object : SyncPort by NoOpSyncPort() {
            private val sessionState = MutableStateFlow(
                SyncSession(
                    familyId = "fam-1",
                    role = FamilyRole.Owner,
                    serverHost = "192.168.50.4",
                    membershipId = "m-owner",
                    accessToken = "tok",
                    refreshToken = "ref",
                ),
            )
            override fun session(): Flow<SyncSession> = sessionState
            override suspend fun listFamilyMembers() = Result.success(listOf(member))
            override suspend fun listPendingMemberLogins() = Result.success(listOf(pending))
            override suspend fun listPendingMemberRenameRequests():
                Result<List<PendingMemberRenameRequest>> = Result.success(emptyList())
        }
        val actions = MembersDevicesActions(sync)
        val state = actions.refreshMembersNow(FamilyMembersState(), showErrors = true)
        assertThat(state.loaded).isTrue()
        assertThat(state.loading).isFalse()
        assertThat(state.members).containsExactly(member)
        assertThat(state.pendingRequests).containsExactly(pending)
        assertThat(state.error).isNull()
    }

    @Test
    fun membersApprovalPathDelegatesToSyncPort() = runTest {
        var approved: String? = null
        val sync = object : SyncPort by NoOpSyncPort() {
            override suspend fun approveNewMemberLogin(requestId: String): Result<Unit> {
                approved = requestId
                return Result.success(Unit)
            }
        }
        val actions = MembersDevicesActions(sync)
        val result = actions.approveNewMemberLogin("req-approve")
        assertThat(result.isSuccess).isTrue()
        assertThat(approved).isEqualTo("req-approve")
    }

    @Test
    fun wizardQrVerifyThinDelegatesOntoFamilyWizardController() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://192.168.50.4:8765")
        val gateway = object : FamilyWizardGateway {
            var verifyCalls = 0
            override suspend fun probeEndpoint(endpointDraft: String) =
                SetupProbeResult.Failed.Unreachable
            override suspend fun trustCertificate(candidate: CertificateTrustCandidate) =
                SetupProbeResult.Failed.Unreachable
            override suspend fun rememberEndpoint(endpoint: TrustedEndpointProfile) =
                Result.success(Unit)
            override suspend fun forgetEndpoint() = Result.success(Unit)
            override suspend fun currentVerifiedEndpoint() = endpoint
            override suspend fun saveEndpointConfig(config: FamilyEndpointConfig) =
                Result.success(Unit)
            override suspend fun createFamily(
                config: FamilyEndpointConfig,
                displayName: String,
                deviceName: String,
                bootstrapSecret: String,
                familyName: String?,
            ) = Result.failure<CreateFamilyResult>(IllegalStateException("unused"))
            override suspend fun ownerLogin(
                config: FamilyEndpointConfig,
                deviceName: String,
                rootPassword: String,
                takeover: Boolean,
            ) = Result.failure<OwnerLoginResult>(IllegalStateException("unused"))
            override suspend fun verifyMemberLoginEndpoint(
                endpoint: TrustedEndpointProfile,
            ): SetupProbeResult {
                verifyCalls += 1
                return SetupProbeResult.Ready(endpoint, SetupFamilyState.Configured)
            }
            override suspend fun claimMemberLoginQr(
                payload: MemberLoginQrPayload,
                deviceName: String,
            ) = Result.failure<MemberLoginQrResult>(IllegalStateException("unused"))
            override suspend fun retryReclaimedDataRecovery() = Result.success(Unit)
        }
        val controller = FamilyWizardController(gateway)
        val payload = MemberLoginQrPayload(
            endpoint = endpoint,
            grant = "grant-0000000000000000000000000000000000000",
            familyName = "乐乐一家",
            memberDisplayName = "妈妈",
            expiresAtEpochSeconds = System.currentTimeMillis() / 1_000 + 3_600,
        )
        // AccountFamilyWizardHost.verifyMemberLoginQr is a thin launch of this controller API.
        controller.verifyMemberLoginQr(FamilyWizardEntry.Account, payload)
        advanceUntilIdle()
        assertThat(gateway.verifyCalls).isEqualTo(1)
        assertThat(controller.state.value).isNotInstanceOf(FamilyWizardState.Editing::class.java)
    }
}
