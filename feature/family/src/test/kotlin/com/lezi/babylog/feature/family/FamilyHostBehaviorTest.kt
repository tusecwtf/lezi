package com.lezi.babylog.feature.family

// 192.168.77.10 is a synthetic RFC1918 LAN test endpoint, never a deployment default.

import kotlinx.coroutines.flow.map
import com.lezi.babylog.sync.session.toPresentation
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.domain.family.FamilyWizardController
import com.lezi.babylog.domain.LocalFamilyIdentity
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Behavior contracts for ticket-24 hosts (not reflection-only surface lists).
 * Uses [SyncPort] fakes for roster refresh / approval and wizard QR thin-delegate path.
 */
class FamilyHostBehaviorTest {
    @get:Rule
    val mainDispatcherRule = FamilyMainDispatcherRule()

    @Test
    fun qrFailureIsRetainedForRecreatedUiInsteadOfLostCallback() =
        runTest(mainDispatcherRule.testDispatcher) {
            val release = CompletableDeferred<Unit>()
            val sync = object : SyncPort by NoOpSyncPort() {
                override suspend fun createMemberLoginQrCode(membershipId: String): Result<com.lezi.babylog.sync.qr.MemberLoginQrCode> {
                    release.await()
                    return Result.failure(IllegalStateException("synthetic failure"))
                }
            }
            val host = com.lezi.babylog.feature.family.members.MembersDevicesHost(
                sync, LocalFamilyIdentity("device-1", "管理员", 1),
            )
            host.createMemberLoginQr("member-a")
            runCurrent()
            assertThat(host.command.value?.pending).isTrue()
            release.complete(Unit)
            advanceUntilIdle()
            assertThat(host.command.value?.pending).isFalse()
            assertThat(host.command.value?.success).isFalse()
            assertThat(host.command.value?.message).isEqualTo("二维码生成失败，请重试")
        }

    @Test
    fun memberRemovalReceiptSurvivesRecreationAndRejectsSecondTarget() =
        runTest(mainDispatcherRule.testDispatcher) {
            val release = CompletableDeferred<Unit>()
            val removed = mutableListOf<String>()
            val sync = object : SyncPort by NoOpSyncPort() {
                override suspend fun removeMember(membershipId: String): Result<Unit> {
                    release.await()
                    removed += membershipId
                    return Result.success(Unit)
                }
            }
            val host = com.lezi.babylog.feature.family.members.MembersDevicesHost(
                sync, LocalFamilyIdentity("device-1", "管理员", 1),
            )
            host.removeMember("member-a", "家人甲")
            host.removeMember("member-b", "家人乙")
            runCurrent()
            release.complete(Unit)
            advanceUntilIdle()
            assertThat(removed).containsExactly("member-a")
            assertThat(host.command.value?.target).isEqualTo("remove_member:member-a")
            assertThat(host.command.value?.success).isTrue()
        }

    @Test
    fun renameRemainsSingleFlightAndPublishesOutcomeToRecreatedUi() =
        runTest(mainDispatcherRule.testDispatcher) {
            val release = CompletableDeferred<Unit>()
            val names = mutableListOf<String>()
            val sync = object : SyncPort by NoOpSyncPort() {
                override suspend fun renameFamily(familyName: String?): Result<Unit> {
                    release.await()
                    names += familyName.orEmpty()
                    return Result.success(Unit)
                }
            }
            val host = com.lezi.babylog.feature.family.members.MembersDevicesHost(
                sync, LocalFamilyIdentity("device-1", "管理员", 1),
            )
            host.renameFamily("新的家庭")
            host.renameFamily("重复提交")
            runCurrent()
            assertThat(host.command.value?.pending).isTrue()
            release.complete(Unit)
            advanceUntilIdle()
            assertThat(names).containsExactly("新的家庭")
            val restoredOutcome = host.command.value!!
            assertThat(restoredOutcome.success).isTrue()
            assertThat(restoredOutcome.message).isEqualTo("家庭名已更新")
            host.consumeCommand(restoredOutcome.id)
            assertThat(host.command.value).isNull()
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun membersHostPublishesLoadingBeforeTheRosterRequestCompletes() =
        runTest(mainDispatcherRule.testDispatcher) {
            val requestStarted = CompletableDeferred<Unit>()
            val releaseRequest = CompletableDeferred<Unit>()
            val member = FamilyMember("管理员", FamilyRole.Owner, true, "m-owner")
            val sync = object : SyncPort by NoOpSyncPort() {
                private val sessionState = MutableStateFlow(
                    SyncSession(
                        familyId = "fam-1",
                        role = FamilyRole.Owner,
                        serverHost = "192.168.77.4",
                        membershipId = "m-owner",
                        accessToken = "tok",
                        refreshToken = "ref",
                    ),
                )
                override fun sessionPresentation() = session().map { it.toPresentation() }
                override fun session(): Flow<SyncSession> = sessionState
                override suspend fun listFamilyMembers(): Result<List<FamilyMember>> {
                    requestStarted.complete(Unit)
                    releaseRequest.await()
                    return Result.success(listOf(member))
                }
                override suspend fun listPendingMemberLogins():
                    Result<List<PendingMemberLoginRequest>> = Result.success(emptyList())
                override suspend fun listPendingMemberRenameRequests():
                    Result<List<PendingMemberRenameRequest>> = Result.success(emptyList())
            }
            val host = com.lezi.babylog.feature.family.members.MembersDevicesHost(
                sync = sync,
                localIdentity = LocalFamilyIdentity("device-1", "管理员", 1),
            )
            val collectionJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                host.ui.collect()
            }
            host.refreshMembers(showErrors = true)
            requestStarted.await()
            runCurrent()
            assertThat(host.ui.value.membersLoading).isTrue()
            assertThat(host.ui.value.membersLoaded).isFalse()

            releaseRequest.complete(Unit)
            advanceUntilIdle()
            assertThat(host.ui.value.membersLoading).isFalse()
            assertThat(host.ui.value.membersLoaded).isTrue()
            assertThat(host.ui.value.members).containsExactly(member)
            collectionJob.cancel()
        }

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
                    serverHost = "192.168.77.4",
                    membershipId = "m-owner",
                    accessToken = "tok",
                    refreshToken = "ref",
                ),
            )
            override fun sessionPresentation() = session().map { it.toPresentation() }
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
    fun cancelledRosterRefreshClearsLoadingAndRetainsThePriorRoster() = runTest {
        val priorMember = FamilyMember("管理员", FamilyRole.Owner, true, "m-owner")
        val previous = FamilyMembersState(
            familyId = "fam-1",
            members = listOf(priorMember),
            loaded = true,
        )
        val cancellation = CancellationException("host unmounted")
        val sync = object : SyncPort by NoOpSyncPort() {
            private val sessionState = MutableStateFlow(
                SyncSession(
                    familyId = "fam-1",
                    role = FamilyRole.Owner,
                    serverHost = "192.168.77.4",
                    membershipId = "m-owner",
                    accessToken = "tok",
                    refreshToken = "ref",
                ),
            )
            override fun sessionPresentation() = session().map { it.toPresentation() }
            override fun session(): Flow<SyncSession> = sessionState
            override suspend fun listFamilyMembers(): Result<List<FamilyMember>> =
                throw cancellation
        }
        val projections = mutableListOf<FamilyMembersState>()
        val actions = MembersDevicesActions(sync, projections::add)

        val thrown = runCatching {
            actions.refreshMembersNow(previous, showErrors = true)
        }.exceptionOrNull()

        assertThat(thrown).isSameInstanceAs(cancellation)
        assertThat(projections.first().loading).isTrue()
        assertThat(projections.last()).isEqualTo(previous)
        assertThat(projections.last().loading).isFalse()
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
        val endpoint = TrustedEndpointProfile.systemPki("https://192.168.77.4:8765")
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FamilyMainDispatcherRule(
    val testDispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
