package com.lezi.babylog.sync.session
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import com.lezi.babylog.sync.BootstrapSecretRejectedException
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.InitialFamilyDataRecovery
import com.lezi.babylog.sync.MemberLoginCheckResult
import com.lezi.babylog.sync.OwnerRootPasswordRejectedException
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.DisplayNameUpdateResult
import com.lezi.babylog.sync.backend.MemberLoginReceipt
import com.lezi.babylog.sync.backend.MemberLoginStatus
import com.lezi.babylog.sync.backend.PendingMemberLoginRequest
import com.lezi.babylog.sync.backend.SessionBootstrapResult
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import com.lezi.babylog.sync.MemorySyncPreferences
import com.lezi.babylog.sync.RecordingSyncBackend

@OptIn(ExperimentalCoroutinesApi::class)
class FamilySessionCoordinatorMemberJoinQrTest {
    @Test
    fun memberRequestIsDurableAndPendingChecksNeverClaimOrPublishASession() = runTest {
        val initial = SyncSession(
            serverHost = "family.home",
            serverPort = 8765,
            serverScheme = "https",
        )
        val preferences = MemorySyncPreferences(initial)
        val backend = RecordingSyncBackend().apply {
            memberLoginStatuses += MemberLoginStatus.Pending
        }
        val coordinator = coordinator(preferences = preferences, backend = backend)

        val requested = coordinator.execute(
            FamilySessionCommand.RequestMemberLogin("  爸爸  ", "  Pixel 9  "),
        ).getOrThrow() as FamilySessionOutcome.MemberLoginRequested
        val checked = coordinator.execute(FamilySessionCommand.CheckMemberLogin)
            .getOrThrow() as FamilySessionOutcome.MemberLoginChecked

        assertThat(backend.memberLoginRequests).containsExactly(
            Triple("https://family.home:8765", "爸爸", "Pixel 9"),
        )
        assertThat(requested.request).isEqualTo(preferences.pendingMemberLogin.first())
        assertThat(preferences.pendingMemberSecret())
            .isEqualTo(backend.nextMemberLoginReceipt.pendingSecret)
        assertThat(checked.result).isEqualTo(MemberLoginCheckResult.Waiting(requested.request))
        assertThat(backend.memberLoginClaimCalls).isEqualTo(0)
        assertThat(preferences.current().isJoined).isFalse()
    }

    @Test
    fun offlineCancelAbandonsLocalPendingAndAllowsASecondMemberRequest() = runTest {
        val initial = SyncSession(
            serverHost = "family.home",
            serverPort = 8765,
            serverScheme = "https",
        )
        val preferences = MemorySyncPreferences(initial)
        val backend = RecordingSyncBackend().apply {
            cancelMemberLoginFailure = IllegalStateException("offline")
        }
        preferences.savePendingMemberLogin(
            backend.nextMemberLoginReceipt,
            displayName = "爸爸",
            deviceName = "Pixel 9",
        )
        val coordinator = coordinator(preferences = preferences, backend = backend)

        val cancelled = coordinator.execute(FamilySessionCommand.CancelMemberLogin)

        assertThat(cancelled.isSuccess).isTrue()
        assertThat(preferences.pendingMemberLogin.first()).isNull()
        assertThat(preferences.pendingMemberSecret()).isEmpty()

        backend.cancelMemberLoginFailure = null
        backend.nextMemberLoginReceipt = backend.nextMemberLoginReceipt.copy(
            requestId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            pendingSecret = "pending-secret-000000000000000000000002",
        )
        val repeated = coordinator.execute(
            FamilySessionCommand.RequestMemberLogin("爸爸", "Pixel 9"),
        )

        assertThat(repeated.isSuccess).isTrue()
        assertThat(backend.memberLoginRequests).hasSize(1)
        assertThat(preferences.pendingMemberLogin.first()?.requestId)
            .isEqualTo("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    }

    @Test
    fun abandoningAnAlreadyEmptyPendingSlotIsAnIdempotentSuccess() = runTest {
        val preferences = MemorySyncPreferences(
            SyncSession(
                serverHost = "family.home",
                serverPort = 8765,
                serverScheme = "https",
            ),
        )
        val backend = RecordingSyncBackend()

        val result = coordinator(preferences = preferences, backend = backend)
            .execute(FamilySessionCommand.CancelMemberLogin)

        assertThat(result.isSuccess).isTrue()
        assertThat(preferences.pendingMemberLogin.first()).isNull()
        assertThat(backend.cancelMemberLoginCalls).isEqualTo(0)
    }

    @Test
    fun localCancelCompletesBeforeRemoteCleanupReturns() = runTest {
        val preferences = MemorySyncPreferences(
            SyncSession(
                serverHost = "family.home",
                serverPort = 8765,
                serverScheme = "https",
            ),
        )
        val remoteStarted = CompletableDeferred<Unit>()
        val releaseRemote = CompletableDeferred<Unit>()
        val backend = RecordingSyncBackend().apply {
            beforeCancelMemberLoginReturn = {
                remoteStarted.complete(Unit)
                releaseRemote.await()
            }
        }
        preferences.savePendingMemberLogin(
            backend.nextMemberLoginReceipt,
            displayName = "爸爸",
            deviceName = "Pixel 9",
        )
        val coordinator = coordinator(
            preferences = preferences,
            backend = backend,
            launchBestEffort = { work -> backgroundScope.async { work() } },
        )

        val cancellation = async {
            coordinator.execute(FamilySessionCommand.CancelMemberLogin)
        }
        remoteStarted.await()
        runCurrent()

        try {
            assertThat(cancellation.isCompleted).isTrue()
            assertThat(cancellation.await().isSuccess).isTrue()
            assertThat(preferences.pendingMemberLogin.first()).isNull()
        } finally {
            releaseRemote.complete(Unit)
        }
    }

    @Test
    fun missingPendingSecretCanStillBeAbandonedLocally() = runTest {
        val preferences = MemorySyncPreferences(
            SyncSession(
                serverHost = "family.home",
                serverPort = 8765,
                serverScheme = "https",
            ),
        )
        val backend = RecordingSyncBackend()
        preferences.savePendingMemberLogin(
            backend.nextMemberLoginReceipt,
            displayName = "爸爸",
            deviceName = "Pixel 9",
        )
        preferences.dropPendingMemberSecretForTest()

        val result = coordinator(preferences = preferences, backend = backend)
            .execute(FamilySessionCommand.CancelMemberLogin)

        assertThat(result.isSuccess).isTrue()
        assertThat(preferences.pendingMemberLogin.first()).isNull()
        assertThat(backend.cancelMemberLoginCalls).isEqualTo(0)
    }

    @Test
    fun approvedMemberClaimGatesTheNewSessionUntilReplicaResetAndNeverClaimsTwice() = runTest {
        val initial = SyncSession(
            serverHost = "family.home",
            serverPort = 8765,
            serverScheme = "https",
        )
        val preferences = MemorySyncPreferences(initial)
        val backend = RecordingSyncBackend().apply {
            memberLoginStatuses += MemberLoginStatus.Approved
        }
        preferences.savePendingMemberLogin(
            backend.nextMemberLoginReceipt,
            displayName = "爸爸",
            deviceName = "Pixel 9",
        )
        var resetSawPushBlockedSession = false
        val syncRequests = mutableListOf<SyncTrigger>()
        val coordinator = coordinator(
            preferences = preferences,
            backend = backend,
            replica = RecordingFamilySessionReplica(
                onReset = {
                    resetSawPushBlockedSession = !preferences.current().isJoined
                },
            ),
            requestSync = syncRequests::add,
        )

        val checked = coordinator.execute(FamilySessionCommand.CheckMemberLogin)
            .getOrThrow() as FamilySessionOutcome.MemberLoginChecked
        val joined = checked.result as MemberLoginCheckResult.Joined

        assertThat(resetSawPushBlockedSession).isTrue()
        assertThat(joined.session).isEqualTo(preferences.current())
        assertThat(joined.dataRecovery).isEqualTo(InitialFamilyDataRecovery.NotRequired)
        assertThat(preferences.pendingMemberLogin.first()).isNull()
        assertThat(preferences.pendingMemberSecret()).isEmpty()
        assertThat(backend.memberLoginClaimCalls).isEqualTo(1)
        assertThat(syncRequests).containsExactly(SyncTrigger.Foreground)

        assertThat(coordinator.execute(FamilySessionCommand.CheckMemberLogin).isFailure).isTrue()
        assertThat(backend.memberLoginClaimCalls).isEqualTo(1)
    }

    @Test
    fun claimedMemberStatusReplaysClaimAndRecoversTheDurableSession() = runTest {
        val initial = SyncSession(
            serverHost = "family.home",
            serverPort = 8765,
            serverScheme = "https",
        )
        val preferences = MemorySyncPreferences(initial)
        val backend = RecordingSyncBackend().apply {
            memberLoginStatuses += MemberLoginStatus.Claimed
        }
        preferences.savePendingMemberLogin(
            backend.nextMemberLoginReceipt,
            displayName = "爸爸",
            deviceName = "Pixel 9",
        )
        val syncRequests = mutableListOf<SyncTrigger>()

        val checked = coordinator(
            preferences = preferences,
            backend = backend,
            requestSync = syncRequests::add,
        ).execute(FamilySessionCommand.CheckMemberLogin)
            .getOrThrow() as FamilySessionOutcome.MemberLoginChecked

        val joined = checked.result as MemberLoginCheckResult.Joined
        assertThat(joined.session).isEqualTo(preferences.current())
        assertThat(joined.dataRecovery).isEqualTo(InitialFamilyDataRecovery.NotRequired)
        assertThat(preferences.pendingMemberLogin.first()).isNull()
        assertThat(preferences.pendingMemberSecret()).isEmpty()
        assertThat(backend.memberLoginClaimCalls).isEqualTo(1)
        assertThat(syncRequests).containsExactly(SyncTrigger.Foreground)
    }

    @Test
    fun claimedMemberReplayClearsPendingOnlyAfterAConfirmedNonReplayableConflict() = runTest {
        val preferences = MemorySyncPreferences(
            SyncSession(
                serverHost = "family.home",
                serverPort = 8765,
                serverScheme = "https",
            ),
        )
        val backend = RecordingSyncBackend().apply {
            memberLoginStatuses += MemberLoginStatus.Claimed
            memberLoginClaimFailure = SyncHttpException(409, "claim cannot be replayed")
        }
        preferences.savePendingMemberLogin(
            backend.nextMemberLoginReceipt,
            displayName = "爸爸",
            deviceName = "Pixel 9",
        )

        val checked = coordinator(preferences, backend)
            .execute(FamilySessionCommand.CheckMemberLogin)
            .getOrThrow() as FamilySessionOutcome.MemberLoginChecked

        assertThat(checked.result)
            .isEqualTo(MemberLoginCheckResult.Terminal(MemberLoginStatus.Claimed))
        assertThat(preferences.pendingMemberLogin.first()).isNull()
        assertThat(preferences.pendingMemberSecret()).isEmpty()
        assertThat(preferences.current().isJoined).isFalse()
    }

    @Test
    fun memberReauthWithTheSameReplicaIdentitySkipsReceiptReset() = runTest {
        val previous = joinedFamilySession(FamilyRole.Member).copy(
            accessToken = "",
            refreshToken = "",
            reauthRequired = true,
            membershipId = "membership-member-approved",
        )
        val preferences = MemorySyncPreferences(previous)
        val backend = RecordingSyncBackend().apply {
            memberLoginStatuses += MemberLoginStatus.Approved
            nextMemberLoginClaim = nextMemberLoginClaim.copy(
                familyId = previous.familyId,
                membershipId = previous.membershipId,
                role = previous.role,
            )
        }
        preferences.savePendingMemberLogin(
            backend.nextMemberLoginReceipt,
            displayName = "妈妈",
            deviceName = "新手机",
        )
        val replica = RecordingFamilySessionReplica()

        val checked = coordinator(
            preferences = preferences,
            backend = backend,
            replica = replica,
        ).execute(FamilySessionCommand.CheckMemberLogin)
            .getOrThrow() as FamilySessionOutcome.MemberLoginChecked

        assertThat(checked.result).isInstanceOf(MemberLoginCheckResult.Joined::class.java)
        assertThat(replica.resetCalls).isEmpty()
        assertThat(preferences.current().isJoined).isTrue()
    }

    @Test
    fun rejectedMemberRequestClearsCapabilityWithoutCreatingIdentity() = runTest {
        val initial = SyncSession(
            serverHost = "family.home",
            serverPort = 8765,
            serverScheme = "https",
        )
        val preferences = MemorySyncPreferences(initial)
        val backend = RecordingSyncBackend().apply {
            memberLoginStatuses += MemberLoginStatus.Rejected
        }
        preferences.savePendingMemberLogin(
            backend.nextMemberLoginReceipt,
            displayName = "爸爸",
            deviceName = "Pixel 9",
        )
        val coordinator = coordinator(preferences = preferences, backend = backend)

        val outcome = coordinator.execute(FamilySessionCommand.CheckMemberLogin)
            .getOrThrow() as FamilySessionOutcome.MemberLoginChecked

        assertThat(outcome.result)
            .isEqualTo(MemberLoginCheckResult.Terminal(MemberLoginStatus.Rejected))
        assertThat(preferences.pendingMemberLogin.first()).isNull()
        assertThat(preferences.current()).isEqualTo(initial)
        assertThat(backend.memberLoginClaimCalls).isEqualTo(0)
    }

    @Test
    fun onlyOwnerCanListApproveAndRejectPendingMemberRequests() = runTest {
        val requestId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val pending = PendingMemberLoginRequest(
            requestId = requestId,
            displayName = "爸爸",
            deviceName = "Pixel 9",
            createdAtEpochSeconds = 100,
            expiresAtEpochSeconds = 200,
        )
        val ownerBackend = RecordingSyncBackend().apply {
            nextPendingMemberLogins = listOf(pending)
        }
        val owner = coordinator(
            preferences = MemorySyncPreferences(joinedFamilySession(FamilyRole.Owner)),
            backend = ownerBackend,
        )
        val memberBackend = RecordingSyncBackend()
        val member = coordinator(
            preferences = MemorySyncPreferences(joinedFamilySession(FamilyRole.Member)),
            backend = memberBackend,
        )

        val listed = owner.execute(FamilySessionCommand.ListPendingMemberLogins)
            .getOrThrow() as FamilySessionOutcome.PendingMemberLoginsListed
        assertThat(listed.requests).containsExactly(pending)
        assertThat(
            owner.execute(FamilySessionCommand.ApproveNewMemberLogin(requestId)).isSuccess,
        ).isTrue()
        assertThat(
            owner.execute(
                FamilySessionCommand.BindExistingMemberLogin(requestId, "membership-existing"),
            ).isSuccess,
        ).isTrue()
        assertThat(
            owner.execute(FamilySessionCommand.RejectMemberLogin(requestId)).isSuccess,
        ).isTrue()
        assertThat(ownerBackend.approvedMemberLoginRequestIds).containsExactly(requestId)
        assertThat(ownerBackend.boundMemberLoginRequests).containsExactly(
            requestId to "membership-existing",
        )
        assertThat(ownerBackend.rejectedMemberLoginRequestIds).containsExactly(requestId)

        assertThat(member.execute(FamilySessionCommand.ListPendingMemberLogins).isFailure).isTrue()
        assertThat(
            member.execute(FamilySessionCommand.ApproveNewMemberLogin(requestId)).isFailure,
        ).isTrue()
        assertThat(
            member.execute(
                FamilySessionCommand.BindExistingMemberLogin(requestId, "membership-existing"),
            ).isFailure,
        ).isTrue()
        assertThat(memberBackend.approvedMemberLoginRequestIds).isEmpty()
        assertThat(memberBackend.boundMemberLoginRequests).isEmpty()
    }

    @Test
    fun onlyOwnerCanCreateTargetBoundMemberLoginGrant() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://family.example.com")
        val ownerPreferences = MemorySyncPreferences(
            joinedFamilySession(FamilyRole.Owner).copy(
                serverHost = "family.example.com",
                serverPort = 443,
                serverScheme = "https",
            ),
        ).apply { rememberEndpoint(endpoint) }
        val ownerBackend = RecordingSyncBackend()
        val owner = coordinator(ownerPreferences, ownerBackend)

        val outcome = owner.execute(
            FamilySessionCommand.CreateMemberLoginGrant("membership-member"),
        ).getOrThrow()

        assertThat(outcome).isEqualTo(
            FamilySessionOutcome.MemberLoginGrantCreated(ownerBackend.nextMemberLoginGrant),
        )
        assertThat(ownerBackend.memberLoginGrantTargets)
            .containsExactly(Triple("token-a", endpoint, "membership-member"))

        val memberBackend = RecordingSyncBackend()
        val member = coordinator(
            MemorySyncPreferences(joinedFamilySession(FamilyRole.Member)),
            memberBackend,
        )
        assertThat(
            member.execute(
                FamilySessionCommand.CreateMemberLoginGrant("membership-member"),
            ).isFailure,
        ).isTrue()
        assertThat(memberBackend.memberLoginGrantTargets).isEmpty()
    }

    @Test
    fun qrGrantClaimRequiresExactPersistedTrustAndGatesSessionBeforeRecovery() = runTest {
        val endpoint = TrustedEndpointProfile.tofuSpki(
            "https://family.example.com:9443",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        )
        val payload = MemberLoginQrPayload(
            endpoint = endpoint,
            grant = "grant-0000000000000000000000000000000000000",
            familyName = "乐乐一家",
            memberDisplayName = "妈妈",
            expiresAtEpochSeconds = 1_753_419_000,
        )
        val preferences = MemorySyncPreferences(SyncSession()).apply {
            rememberEndpoint(endpoint)
        }
        val backend = RecordingSyncBackend()
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            preferences = preferences,
            backend = backend,
            requestSync = {
                assertThat(preferences.current().isJoined).isTrue()
                events += "sync-scheduled:$it"
            },
        )

        val outcome = coordinator.execute(
            FamilySessionCommand.ClaimMemberLoginGrant(payload, "  Pixel Tablet  "),
        ).getOrThrow() as FamilySessionOutcome.Joined

        assertThat(outcome.session).isEqualTo(preferences.current())
        assertThat(outcome.dataRecovery).isEqualTo(InitialFamilyDataRecovery.NotRequired)
        assertThat(events).containsExactly("sync-scheduled:Foreground")
        assertThat(backend.memberLoginGrantClaims).containsExactly(
            Triple(endpoint, payload.grant, "Pixel Tablet"),
        )
        assertThat(preferences.current().baseUrl).isEqualTo(endpoint.origin)

        val mismatchBackend = RecordingSyncBackend()
        val mismatchPreferences = MemorySyncPreferences(SyncSession()).apply {
            rememberEndpoint(TrustedEndpointProfile.systemPki("https://other.example.com"))
        }
        val mismatch = coordinator(mismatchPreferences, mismatchBackend).execute(
            FamilySessionCommand.ClaimMemberLoginGrant(payload, "Pixel Tablet"),
        )
        assertThat(mismatch.isFailure).isTrue()
        assertThat(mismatchBackend.memberLoginGrantClaims).isEmpty()
    }

    @Test
    fun secretBearingFamilyCommandsNeverRenderPlaintext() {
        val payload = MemberLoginQrPayload(
            endpoint = TrustedEndpointProfile.systemPki("https://family.example.com"),
            grant = "grant-0000000000000000000000000000000000000",
            familyName = "乐乐一家",
            memberDisplayName = "妈妈",
            expiresAtEpochSeconds = 1_753_419_000,
        )

        val rendered = listOf(
            FamilySessionCommand.CreateFamily("妈妈", "Pixel", "root-create-secret", "乐乐一家"),
            FamilySessionCommand.OwnerLogin("Pixel", "root-login-secret", false),
            FamilySessionCommand.ClaimMemberLoginGrant(payload, "Pixel"),
            MemberLoginReceipt(
                requestId = "request-id",
                pendingSecret = "pending-member-secret",
                expiresAtEpochSeconds = 1_753_419_000,
            ),
            SessionBootstrapResult(
                familyId = "family-id",
                accessToken = "bootstrap-access-token",
                refreshToken = "bootstrap-refresh-token",
                role = FamilyRole.Member,
                generation = "generation",
                membershipId = "membership-id",
            ),
        ).joinToString()

        assertThat(rendered).doesNotContain("root-create-secret")
        assertThat(rendered).doesNotContain("root-login-secret")
        assertThat(rendered).doesNotContain(payload.grant)
        assertThat(rendered).doesNotContain("pending-member-secret")
        assertThat(rendered).doesNotContain("bootstrap-access-token")
        assertThat(rendered).doesNotContain("bootstrap-refresh-token")
    }

    @Test
    fun cancellationEscapesWithoutBeingTranslatedIntoACommandFailure() = runTest {
        val cancellation = CancellationException("stop family command")
        val backend = RecordingSyncBackend().apply {
            renameFamilyFailure = cancellation
        }
        val coordinator = coordinator(
            preferences = MemorySyncPreferences(joinedFamilySession()),
            backend = backend,
        )

        val thrown = runCatching {
            coordinator.execute(FamilySessionCommand.RenameFamily("新名字"))
        }.exceptionOrNull()

        assertThat(thrown).isSameInstanceAs(cancellation)
    }
}
