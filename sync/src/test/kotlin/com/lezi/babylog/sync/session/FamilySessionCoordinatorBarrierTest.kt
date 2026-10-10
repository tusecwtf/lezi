package com.lezi.babylog.sync.session

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.MemberLoginCheckResult
import com.lezi.babylog.sync.MemorySyncPreferences
import com.lezi.babylog.sync.RecordingSyncBackend
import com.lezi.babylog.sync.backend.MemberLoginStatus
import com.lezi.babylog.sync.backend.deadline.FamilyHttpException
import com.lezi.babylog.sync.backend.deadline.FamilyHttpFailureKind
import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FamilySessionCoordinatorBarrierTest {
    @Test
    fun cancellationAfterChildAcquiresButBeforeCallerResumesDoesNotLeakBarrier() = runTest {
        val backend = RecordingSyncBackend()
        val barrier = Mutex(locked = true)
        var holdFirstEnteredOperation = true
        val coordinator = coordinator(
            preferences = MemorySyncPreferences(joinedFamilySession(FamilyRole.Owner)),
            backend = backend,
            barrier = barrier,
            requireRemoteAllowed = {
                if (holdFirstEnteredOperation) {
                    holdFirstEnteredOperation = false
                    kotlinx.coroutines.awaitCancellation()
                }
            },
        )
        val dispatcher = StepDispatcher()
        val waiting = async(dispatcher) {
            coordinator.execute(FamilySessionCommand.ApproveNewMemberLogin("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
        }
        dispatcher.drain() // all runnable work reaches the held barrier
        barrier.unlock()
        dispatcher.runNext() // handoff implementation queues caller; direct owner parks at its first gate
        assertThat(barrier.isLocked).isTrue()
        assertThat(backend.approvedMemberLoginRequestIds).isEmpty()

        waiting.cancel()
        dispatcher.drain()
        holdFirstEnteredOperation = false // The probe applies only to the cancelled command.

        val next = coordinator.execute(
            FamilySessionCommand.ApproveNewMemberLogin("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
        )
        assertThat(next.exceptionOrNull()).isNull()
        assertThat(backend.approvedMemberLoginRequestIds)
            .containsExactly("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    }

    @Test
    fun failedPreOperationRecoveryReleasesBarrierForNextCommand() = runTest {
        val backend = RecordingSyncBackend()
        var failRecovery = true
        val coordinator = coordinator(
            preferences = MemorySyncPreferences(joinedFamilySession(FamilyRole.Owner)),
            backend = backend,
            beforeOperation = {
                if (failRecovery) {
                    failRecovery = false
                    error("local recovery unavailable")
                }
            },
        )
        assertThat(coordinator.execute(FamilySessionCommand.ApproveNewMemberLogin(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        )).isFailure).isTrue()
        coordinator.execute(FamilySessionCommand.ApproveNewMemberLogin(
            "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
        )).getOrThrow()
        assertThat(backend.approvedMemberLoginRequestIds)
            .containsExactly("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    }

    /** Controls only dispatch at the public command boundary, never coordinator internals. */
    private class StepDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            pending.addLast(block)
        }
        fun runNext() { check(pending.isNotEmpty()); pending.removeFirst().run() }
        fun drain() { while (pending.isNotEmpty()) runNext() }
    }

    @Test
    fun checkMemberLoginFailsFastWhenReplicaBarrierIsHeld() = runTest {
        val preferences = pendingMemberPreferences()
        val backend = RecordingSyncBackend().apply {
            memberLoginStatuses += MemberLoginStatus.Pending
        }
        val barrier = Mutex()
        barrier.lock()
        val coordinator = coordinator(
            preferences = preferences,
            backend = backend,
            barrier = barrier,
        )

        val check = async { coordinator.execute(FamilySessionCommand.CheckMemberLogin) }
        advanceUntilIdle()

        val error = check.await().exceptionOrNull()
        assertThat(error).isInstanceOf(FamilyHttpException::class.java)
        assertThat((error as FamilyHttpException).kind)
            .isEqualTo(FamilyHttpFailureKind.HouseholdSyncing)
        assertThat(familyFailureKind(error)).isEqualTo(
            com.lezi.babylog.core.common.failure.FailureKind.HouseholdSyncing,
        )
        assertThat(
            com.lezi.babylog.core.common.failure.failureExplanation(
                com.lezi.babylog.core.common.failure.FailureKind.HouseholdSyncing,
            ).title,
        ).isEqualTo("家里正在同步")
        assertThat(backend.memberLoginStatuses).containsExactly(MemberLoginStatus.Pending)
        assertThat(testScheduler.currentTime).isAtMost(2_000L)
        assertThat(testScheduler.currentTime).isLessThan(20_000L)
    }

    @Test
    fun approveMemberLoginFailsFastWhenReplicaBarrierIsHeld() = runTest {
        val backend = RecordingSyncBackend()
        val barrier = Mutex()
        barrier.lock()
        val coordinator = coordinator(
            preferences = MemorySyncPreferences(joinedFamilySession(FamilyRole.Owner)),
            backend = backend,
            barrier = barrier,
        )

        val approve = async {
            coordinator.execute(
                FamilySessionCommand.ApproveNewMemberLogin(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                ),
            )
        }
        advanceUntilIdle()

        val error = approve.await().exceptionOrNull() as FamilyHttpException
        assertThat(error.kind).isEqualTo(FamilyHttpFailureKind.HouseholdSyncing)
        assertThat(familyFailureKind(error)).isEqualTo(
            com.lezi.babylog.core.common.failure.FailureKind.HouseholdSyncing,
        )
        assertThat(backend.approvedMemberLoginRequestIds).isEmpty()
        assertThat(testScheduler.currentTime).isAtMost(2_000L)
    }

    @Test
    fun ownerLoginFailsFastWhenReplicaBarrierIsHeld() = runTest {
        val backend = RecordingSyncBackend()
        val barrier = Mutex()
        barrier.lock()
        val coordinator = coordinator(
            preferences = MemorySyncPreferences(
                SyncSession(
                    serverHost = "family.home",
                    serverPort = 8765,
                    serverScheme = "https",
                ),
            ),
            backend = backend,
            barrier = barrier,
        )

        val login = async {
            coordinator.execute(
                FamilySessionCommand.OwnerLogin(
                    deviceName = "Pixel 9",
                    rootPassword = "root-password",
                    takeover = false,
                ),
            )
        }
        advanceUntilIdle()

        val error = login.await().exceptionOrNull() as FamilyHttpException
        assertThat(error.kind).isEqualTo(FamilyHttpFailureKind.HouseholdSyncing)
        assertThat(familyFailureKind(error)).isEqualTo(
            com.lezi.babylog.core.common.failure.FailureKind.HouseholdSyncing,
        )
        assertThat(backend.ownerLoginDeviceNames).isEmpty()
        assertThat(testScheduler.currentTime).isAtMost(2_000L)
    }

    @Test
    fun claimMemberLoginGrantFailsFastWhenReplicaBarrierIsHeld() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki(
            "https://family.example.com",
        )
        val preferences = MemorySyncPreferences(SyncSession()).apply {
            rememberEndpoint(endpoint)
        }
        val backend = RecordingSyncBackend()
        val barrier = Mutex()
        barrier.lock()
        val coordinator = coordinator(
            preferences = preferences,
            backend = backend,
            barrier = barrier,
        )

        val claim = async {
            coordinator.execute(
                FamilySessionCommand.ClaimMemberLoginGrant(
                    payload = MemberLoginQrPayload(
                        endpoint = endpoint,
                        grant = "grant-0000000000000000000000000000000000000",
                        familyName = "乐乐一家",
                        memberDisplayName = "妈妈",
                        expiresAtEpochSeconds = 1_753_419_000,
                    ),
                    deviceName = "Pixel Tablet",
                ),
            )
        }
        advanceUntilIdle()

        val error = claim.await().exceptionOrNull() as FamilyHttpException
        assertThat(error.kind).isEqualTo(FamilyHttpFailureKind.HouseholdSyncing)
        assertThat(familyFailureKind(error)).isEqualTo(
            com.lezi.babylog.core.common.failure.FailureKind.HouseholdSyncing,
        )
        assertThat(backend.memberLoginGrantClaims).isEmpty()
        assertThat(testScheduler.currentTime).isAtMost(2_000L)
    }

    @Test
    fun cancelledBarrierWaitDoesNotLeakTheLockForTheNextApprove() = runTest {
        val backend = RecordingSyncBackend()
        val barrier = Mutex()
        barrier.lock()
        val coordinator = coordinator(
            preferences = MemorySyncPreferences(joinedFamilySession(FamilyRole.Owner)),
            backend = backend,
            barrier = barrier,
        )

        val waiting = async {
            coordinator.execute(
                FamilySessionCommand.ApproveNewMemberLogin(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                ),
            )
        }
        advanceTimeBy(500L)
        runCurrent()
        assertThat(waiting.isCompleted).isFalse()

        waiting.cancel()
        advanceUntilIdle()
        barrier.unlock()

        val next = coordinator.execute(
            FamilySessionCommand.ApproveNewMemberLogin(
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            ),
        )
        assertThat(next.isSuccess).isTrue()
        assertThat(backend.approvedMemberLoginRequestIds)
            .containsExactly("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    }

    @Test
    fun abandonClearsLocalPendingSlotWhileReplicaBarrierIsHeld() = runTest {
        val preferences = pendingMemberPreferences()
        val backend = RecordingSyncBackend()
        val barrier = Mutex()
        barrier.lock()
        val coordinator = coordinator(
            preferences = preferences,
            backend = backend,
            barrier = barrier,
            launchBestEffort = { work -> backgroundScope.async { work() } },
        )

        val abandon = async {
            coordinator.execute(FamilySessionCommand.CancelMemberLogin)
        }
        runCurrent()

        assertThat(abandon.isCompleted).isTrue()
        assertThat(abandon.await().isSuccess).isTrue()
        assertThat(preferences.pendingMemberLogin.first()).isNull()
        assertThat(preferences.pendingMemberSecret()).isEmpty()
        assertThat(testScheduler.currentTime).isLessThan(2_000L)
    }

    @Test
    fun abandonDuringApprovedCheckDoesNotPersistAJoinedSession() = runTest {
        val preferences = pendingMemberPreferences()
        val statusStarted = CompletableDeferred<Unit>()
        val releaseStatus = CompletableDeferred<Unit>()
        val backend = RecordingSyncBackend().apply {
            memberLoginStatuses += MemberLoginStatus.Approved
            beforeMemberLoginStatusReturn = {
                statusStarted.complete(Unit)
                releaseStatus.await()
            }
        }
        val coordinator = coordinator(
            preferences = preferences,
            backend = backend,
            launchBestEffort = { work -> backgroundScope.async { work() } },
        )

        val check = async { coordinator.execute(FamilySessionCommand.CheckMemberLogin) }
        statusStarted.await()

        val abandoned = coordinator.execute(FamilySessionCommand.CancelMemberLogin)
        assertThat(abandoned.isSuccess).isTrue()
        assertThat(preferences.pendingMemberLogin.first()).isNull()

        releaseStatus.complete(Unit)
        assertThat(check.await().exceptionOrNull())
            .isInstanceOf(com.lezi.babylog.sync.MemberLoginAttemptRetiredException::class.java)
        assertThat(preferences.current().isJoined).isFalse()
        assertThat(backend.memberLoginClaimCalls).isEqualTo(0)
    }
}

private suspend fun pendingMemberPreferences(): MemorySyncPreferences {
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
    return preferences
}
