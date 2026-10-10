package com.lezi.babylog.feature.family.conflict

import com.lezi.babylog.sync.session.toPresentation
import androidx.lifecycle.SavedStateHandle
import com.lezi.babylog.domain.carelog.ConflictResolveOutcome
import com.lezi.babylog.domain.carelog.ConflictResolverLoad
import com.lezi.babylog.sync.conflict.ConflictCandidate
import com.lezi.babylog.sync.conflict.ConflictOutcome
import com.lezi.babylog.sync.conflict.ConflictRoot
import com.lezi.babylog.sync.conflict.ConflictRootType
import com.lezi.babylog.sync.conflict.ConflictSnapshot
import com.lezi.babylog.sync.conflict.ConflictSource
import com.lezi.babylog.sync.conflict.ConflictVersionSnapshot
import com.lezi.babylog.sync.conflict.ConflictingPath
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConflictResolverHostRaceTest {
    @Test
    fun selectedAttemptSurvivesSameReceiptReconnectButNotNewReceipt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val savedState = SavedStateHandle()
            val firstHost = host(
                load = { _, _ -> ConflictResolverLoad(snapshot(), fetchedOnline = true) },
                savedStateHandle = savedState,
            )
            firstHost.open(CONFLICT_A)
            advanceUntilIdle()
            firstHost.choose("/note", BRANCH_CHOICE_ID)
            val selected = requireNotNull(firstHost.state.value.draft)

            var recreatedLoads = 0
            val newSnapshot = snapshot().copy(snapshotToken = "d".repeat(43))
            val recreated = host(
                load = { _, _ ->
                    recreatedLoads += 1
                    ConflictResolverLoad(
                        snapshot = if (recreatedLoads < 3) snapshot() else newSnapshot,
                        fetchedOnline = recreatedLoads > 1,
                    )
                },
                savedStateHandle = savedState,
            )
            recreated.open(CONFLICT_A)
            advanceUntilIdle()
            assertTrue(recreated.state.value.phase is ConflictResolverPhase.Offline)

            recreated.refresh()
            advanceUntilIdle()
            assertEquals(selected.resolutionMutationId, recreated.state.value.draft?.resolutionMutationId)
            assertEquals(selected.selectedChoiceIds, recreated.state.value.draft?.selectedChoiceIds)

            recreated.refresh()
            advanceUntilIdle()
            assertEquals(newSnapshot.snapshotToken, recreated.state.value.draft?.model?.snapshotToken)
            assertEquals(STABLE_CHOICE_ID, recreated.state.value.draft?.selectedChoiceIds?.get("/note"))
            assertTrue(recreated.state.value.draft?.resolutionMutationId != selected.resolutionMutationId)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun frozenLostResponseSurvivesOfflineRecreationAndSameReceiptReconnect() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val savedState = SavedStateHandle()
            val requests = mutableListOf<com.lezi.babylog.sync.backend.ConflictResolveRequest>()
            val firstHost = host(
                load = { _, _ -> ConflictResolverLoad(snapshot(), fetchedOnline = true) },
                resolve = { _, request ->
                    requests += request
                    ConflictResolveOutcome.TransportFailure("响应丢失")
                },
                savedStateHandle = savedState,
            )
            firstHost.open(CONFLICT_A)
            advanceUntilIdle()
            firstHost.choose("/note", BRANCH_CHOICE_ID)
            firstHost.submit()
            advanceUntilIdle()

            var recreatedLoads = 0
            val recreated = host(
                load = { _, _ ->
                    recreatedLoads += 1
                    ConflictResolverLoad(snapshot(), fetchedOnline = recreatedLoads > 1)
                },
                resolve = { _, request ->
                    requests += request
                    ConflictResolveOutcome.Accepted("v-resolved")
                },
                savedStateHandle = savedState,
            )
            recreated.open(CONFLICT_A)
            advanceUntilIdle()
            assertTrue(recreated.state.value.phase is ConflictResolverPhase.Offline)

            recreated.refresh()
            advanceUntilIdle()
            assertEquals(ConflictResolverPhase.Complete, recreated.state.value.phase)
            assertTrue(recreated.state.value.draft?.submitted == true)
            assertEquals(requests.single().resolutionMutationId, recreated.state.value.draft?.resolutionMutationId)
            assertEquals(requests.single().choices, recreated.state.value.draft?.command()?.choices)

            recreated.submit()
            advanceUntilIdle()
            assertEquals(2, requests.size)
            assertEquals(requests[0], requests[1])
            assertEquals(CONFLICT_A, recreated.state.value.resolvedConflictId)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun terminalRejectionsRemainNonRetryableAcrossProcessRecreation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            listOf(
                ConflictResolveOutcome.Forbidden() to "当前账号无权解决此冲突",
                ConflictResolveOutcome.Rejected("invalid_choice", "选择无效") to
                    "本次解决请求已被拒绝，请关闭后重新打开冲突",
            ).forEach { (terminal, restoredMessage) ->
                val savedState = SavedStateHandle()
                var resolveCalls = 0
                var loads = 0
                var now = 1_000L
                val firstHost = host(
                    load = { _, _ ->
                        loads += 1
                        ConflictResolverLoad(snapshot(), fetchedOnline = true)
                    },
                    resolve = { _, _ ->
                        resolveCalls += 1
                        terminal
                    },
                    nowMillis = { now },
                    savedStateHandle = savedState,
                )
                firstHost.open(CONFLICT_A)
                advanceUntilIdle()
                firstHost.choose("/note", BRANCH_CHOICE_ID)
                firstHost.submit()
                advanceUntilIdle()
                val terminalPhase = firstHost.state.value.phase as ConflictResolverPhase.Error
                assertEquals(ConflictResolverRetry.None, terminalPhase.retry)
                assertFalse(firstHost.state.value.canSubmit)
                now = 2_000_000
                firstHost.submit()
                firstHost.refresh()
                advanceUntilIdle()
                assertEquals(ConflictResolverRetry.None, (firstHost.state.value.phase as ConflictResolverPhase.Error).retry)
                assertEquals(1, resolveCalls)
                assertEquals(1, loads)

                val recreated = host(
                    load = { _, _ ->
                        loads += 1
                        ConflictResolverLoad(snapshot(), fetchedOnline = true)
                    },
                    resolve = { _, _ ->
                        resolveCalls += 1
                        error("terminal attempt repeated")
                    },
                    nowMillis = { now },
                    savedStateHandle = savedState,
                )
                recreated.open(CONFLICT_A)
                advanceUntilIdle()
                val restoredPhase = recreated.state.value.phase as ConflictResolverPhase.Error
                assertEquals(restoredMessage, restoredPhase.message)
                assertEquals(ConflictResolverRetry.None, restoredPhase.retry)
                recreated.submit()
                recreated.refresh()
                advanceUntilIdle()

                assertEquals(1, resolveCalls)
                assertEquals(2, loads)
                assertFalse(recreated.state.value.canSubmit)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun offlineSnapshotIsReadableButCannotChooseOrSubmit() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var resolveCalls = 0
            val host = host(
                load = { _, _ -> ConflictResolverLoad(snapshot(), fetchedOnline = false) },
                resolve = { _, _ ->
                    resolveCalls += 1
                    ConflictResolveOutcome.Accepted("unexpected")
                },
            )

            host.open(CONFLICT_A)
            advanceUntilIdle()
            val evidence = requireNotNull(host.state.value.draft)
            host.choose("/note", BRANCH_CHOICE_ID)
            host.submit()
            advanceUntilIdle()

            assertTrue(host.state.value.phase is ConflictResolverPhase.Offline)
            assertEquals(evidence.model.snapshotToken, host.state.value.draft?.model?.snapshotToken)
            assertTrue(host.state.value.draft?.selectedChoiceIds?.isEmpty() == true)
            assertFalse(host.state.value.canChoose)
            assertFalse(host.state.value.canSubmit)
            assertEquals(0, resolveCalls)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun freshnessRejectionsReloadExactlyOnceAndDiscardOldSelections() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            listOf(
                "snapshot_expired",
                "snapshot_stale",
                "invalid_snapshot_token",
                "cas_mismatch",
            ).forEachIndexed { index, code ->
                var loads = 0
                val freshToken = ('d' + index).toString().repeat(43)
                val freshChoiceId = ('h' + index).toString().repeat(43)
                val fresh = snapshot().copy(
                    snapshotToken = freshToken,
                    conflicting = snapshot().conflicting.map { path ->
                        path.copy(
                            candidates = path.candidates.mapIndexed { candidateIndex, candidate ->
                                candidate.copy(
                                    choiceId = if (candidateIndex == 0) freshChoiceId else "m".repeat(43),
                                )
                            },
                        )
                    },
                )
                val host = host(
                    load = { _, _ ->
                        loads += 1
                        ConflictResolverLoad(
                            snapshot = if (loads == 1) snapshot() else fresh,
                            fetchedOnline = true,
                        )
                    },
                    resolve = { _, _ -> ConflictResolveOutcome.RefreshRequired(code, code) },
                )

                host.open(CONFLICT_A)
                advanceUntilIdle()
                host.choose("/note", BRANCH_CHOICE_ID)
                val oldMutationId = requireNotNull(host.state.value.draft).resolutionMutationId
                host.submit()
                advanceUntilIdle()

                assertEquals(code, 2, loads)
                assertEquals(code, ConflictResolverPhase.Complete, host.state.value.phase)
                assertEquals(code, freshToken, host.state.value.draft?.model?.snapshotToken)
                assertEquals(code, freshChoiceId, host.state.value.draft?.selectedChoiceIds?.get("/note"))
                assertTrue(code, host.state.value.draft?.resolutionMutationId != oldMutationId)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun staleReloadWithSameReceiptKeepsOldEvidenceAndStops() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var loads = 0
            val host = host(
                load = { _, _ ->
                    loads += 1
                    ConflictResolverLoad(snapshot(), fetchedOnline = true)
                },
                resolve = { _, _ ->
                    ConflictResolveOutcome.RefreshRequired("cas_mismatch", "出现了新的并发分支")
                },
            )

            host.open(CONFLICT_A)
            advanceUntilIdle()
            host.choose("/note", BRANCH_CHOICE_ID)
            val evidence = requireNotNull(host.state.value.draft)
            host.submit()
            advanceUntilIdle()

            assertEquals(2, loads)
            assertTrue(host.state.value.phase is ConflictResolverPhase.Stale)
            assertEquals(evidence.resolutionMutationId, host.state.value.draft?.resolutionMutationId)
            assertEquals(evidence.selectedChoiceIds, host.state.value.draft?.selectedChoiceIds)
            assertTrue(host.state.value.requiresFreshSnapshot)
            assertFalse(host.state.value.canSubmit)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun transportRetryReusesFrozenCommandUntilAccepted() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val requests = mutableListOf<com.lezi.babylog.sync.backend.ConflictResolveRequest>()
            val host = host(
                load = { _, _ -> ConflictResolverLoad(snapshot(), fetchedOnline = true) },
                resolve = { _, request ->
                    requests += request
                    if (requests.size == 1) {
                        ConflictResolveOutcome.TransportFailure("网络中断，请重试")
                    } else {
                        ConflictResolveOutcome.Accepted("v-resolved")
                    }
                },
            )

            host.open(CONFLICT_A)
            advanceUntilIdle()
            host.choose("/note", BRANCH_CHOICE_ID)
            host.submit()
            advanceUntilIdle()

            assertTrue(host.state.value.phase is ConflictResolverPhase.Error)
            assertTrue(host.state.value.canSubmit)
            val frozenMutationId = host.state.value.draft?.resolutionMutationId
            host.submit()
            advanceUntilIdle()

            assertEquals(2, requests.size)
            assertEquals(requests[0], requests[1])
            assertEquals(frozenMutationId, requests[1].resolutionMutationId)
            assertEquals(CONFLICT_A, host.state.value.resolvedConflictId)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun processRecreationCannotReauthorizeServerStaleToken() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val savedState = SavedStateHandle()
            var loads = 0
            val firstHost = host(
                load = { _, _ ->
                    loads += 1
                    ConflictResolverLoad(snapshot(), fetchedOnline = loads == 1)
                },
                resolve = { _, _ ->
                    ConflictResolveOutcome.RefreshRequired(
                        code = "snapshot_stale",
                        message = "冲突内容已变化，请联网刷新后重新选择",
                    )
                },
                savedStateHandle = savedState,
            )
            firstHost.open(CONFLICT_A)
            advanceUntilIdle()
            firstHost.choose("/note", BRANCH_CHOICE_ID)
            firstHost.submit()
            advanceUntilIdle()
            assertTrue(firstHost.state.value.phase is ConflictResolverPhase.Offline)

            val recreated = host(
                load = { _, _ -> ConflictResolverLoad(snapshot(), fetchedOnline = true) },
                savedStateHandle = savedState,
            )
            recreated.open(CONFLICT_A)
            advanceUntilIdle()

            assertTrue(recreated.state.value.phase is ConflictResolverPhase.Stale)
            assertEquals(
                mapOf("/note" to BRANCH_CHOICE_ID),
                recreated.state.value.draft?.selectedChoiceIds,
            )
            assertFalse(recreated.state.value.canSubmit)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun processRecreationRestoresStaleAttemptAsOfflineEvidence() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val savedState = SavedStateHandle()
            var loads = 0
            val firstHost = host(
                load = { _, _ ->
                    loads += 1
                    ConflictResolverLoad(snapshot(), fetchedOnline = loads == 1)
                },
                resolve = { _, _ ->
                    ConflictResolveOutcome.RefreshRequired(
                        code = "snapshot_stale",
                        message = "冲突内容已变化，请联网刷新后重新选择",
                    )
                },
                savedStateHandle = savedState,
            )
            firstHost.open(CONFLICT_A)
            advanceUntilIdle()
            firstHost.choose("/note", BRANCH_CHOICE_ID)
            firstHost.submit()
            advanceUntilIdle()
            val beforeDeath = requireNotNull(firstHost.state.value.draft)
            assertTrue(beforeDeath.submitted)
            assertTrue(firstHost.state.value.phase is ConflictResolverPhase.Offline)

            val recreated = host(
                load = { _, _ -> ConflictResolverLoad(snapshot(), fetchedOnline = false) },
                savedStateHandle = savedState,
            )
            recreated.open(CONFLICT_A)
            advanceUntilIdle()

            val restored = requireNotNull(recreated.state.value.draft)
            assertTrue(recreated.state.value.phase is ConflictResolverPhase.Offline)
            assertEquals(beforeDeath.resolutionMutationId, restored.resolutionMutationId)
            assertEquals(beforeDeath.selectedChoiceIds, restored.selectedChoiceIds)
            assertTrue(restored.submitted)
            assertFalse(recreated.state.value.canSubmit)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun staleRefreshReturningExpiredSnapshotStopsWithoutLooping() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var loads = 0
            val expiredRefresh = snapshot().copy(
                snapshotToken = "d".repeat(43),
                expiresAt = 1_000,
            )
            val host = host(
                load = { _, _ ->
                    loads += 1
                    ConflictResolverLoad(
                        snapshot = if (loads == 1) snapshot() else expiredRefresh,
                        fetchedOnline = true,
                    )
                },
                resolve = { _, _ ->
                    ConflictResolveOutcome.RefreshRequired(
                        code = "snapshot_stale",
                        message = "冲突内容已变化，请联网刷新后重新选择",
                    )
                },
            )

            host.open(CONFLICT_A)
            advanceUntilIdle()
            host.choose("/note", BRANCH_CHOICE_ID)
            host.submit()
            advanceUntilIdle()

            assertEquals(2, loads)
            assertTrue(host.state.value.phase is ConflictResolverPhase.Stale)
            assertEquals(expiredRefresh.snapshotToken, host.state.value.draft?.model?.snapshotToken)
            assertFalse(host.state.value.canSubmit)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun expiryAtChoiceRefreshesOnceAndRebuildsFromNewSnapshot() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var now = 999L
            var loads = 0
            val old = snapshot().copy(expiresAt = 1_000)
            val fresh = snapshot().copy(
                snapshotToken = "d".repeat(43),
                expiresAt = 3_000,
                conflicting = snapshot().conflicting.map { path ->
                    path.copy(
                        candidates = path.candidates.mapIndexed { index, candidate ->
                            candidate.copy(choiceId = if (index == 0) "e".repeat(43) else "f".repeat(43))
                        },
                    )
                },
            )
            val host = host(
                load = { _, forceRefresh ->
                    assertTrue(forceRefresh)
                    loads += 1
                    ConflictResolverLoad(if (loads == 1) old else fresh, fetchedOnline = true)
                },
                nowMillis = { now },
            )

            host.open(CONFLICT_A)
            advanceUntilIdle()
            val oldMutation = requireNotNull(host.state.value.draft).resolutionMutationId
            now = 1_000
            host.choose("/note", BRANCH_CHOICE_ID)
            advanceUntilIdle()

            assertEquals(2, loads)
            assertEquals(ConflictResolverPhase.Complete, host.state.value.phase)
            assertEquals(fresh.snapshotToken, host.state.value.draft?.model?.snapshotToken)
            assertEquals("e".repeat(43), host.state.value.draft?.selectedChoiceIds?.get("/note"))
            assertTrue(host.state.value.draft?.resolutionMutationId != oldMutation)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun staleRefreshFailureKeepsOldEvidenceReadOnly() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var loads = 0
            var resolveCalls = 0
            val host = host(
                load = { _, forceRefresh ->
                    assertTrue(forceRefresh)
                    loads += 1
                    ConflictResolverLoad(snapshot(), fetchedOnline = loads == 1)
                },
                resolve = { _, _ ->
                    resolveCalls += 1
                    ConflictResolveOutcome.RefreshRequired(
                        code = "snapshot_stale",
                        message = "冲突内容已变化，请联网刷新后重新选择",
                    )
                },
            )

            host.open(CONFLICT_A)
            advanceUntilIdle()
            host.choose("/note", BRANCH_CHOICE_ID)
            val reviewed = requireNotNull(host.state.value.draft)
            host.submit()
            advanceUntilIdle()

            assertEquals(2, loads)
            assertEquals(1, resolveCalls)
            assertTrue(host.state.value.phase is ConflictResolverPhase.Offline)
            assertEquals(reviewed.selectedChoiceIds, host.state.value.draft?.selectedChoiceIds)
            assertEquals(reviewed.model.snapshotToken, host.state.value.draft?.model?.snapshotToken)
            assertFalse(host.state.value.canSubmit)
            assertNull(host.state.value.resolvedConflictId)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun lateLoadFromAInstanceCannotOverwriteB() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val loadA = CompletableDeferred<ConflictResolverLoad?>()
            val loadB = CompletableDeferred<ConflictResolverLoad?>()
            val host = host(
                load = { conflictId, _ ->
                    withContext(NonCancellable) {
                        if (conflictId == CONFLICT_A) loadA.await() else loadB.await()
                    }
                },
            )

            host.open(CONFLICT_A)
            runCurrent()
            host.open(CONFLICT_B)
            runCurrent()
            loadA.complete(null)
            runCurrent()

            assertEquals(CONFLICT_B, host.state.value.conflictId)
            assertEquals(ConflictResolverPhase.Loading, host.state.value.phase)

            loadB.complete(null)
            advanceUntilIdle()
            assertEquals(CONFLICT_B, host.state.value.conflictId)
            assertTrue(host.state.value.phase is ConflictResolverPhase.Error)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun withdrawingOwnLastBranchLeavesResolverAsResolved() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val host = host(
                load = { _, _ -> ConflictResolverLoad(authorSnapshot(), fetchedOnline = true) },
                withdraw = { _, _ ->
                    ConflictResolveOutcome.Withdrawn("v-stable", stillOpen = false)
                },
                role = FamilyRole.Member,
            )
            host.open(CONFLICT_A)
            advanceUntilIdle()
            assertTrue(host.state.value.canWithdraw)

            host.submitWithdraw()
            advanceUntilIdle()

            assertEquals(CONFLICT_A, host.state.value.resolvedConflictId)
            assertNull(host.state.value.notice)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun withdrawingOwnBranchWhilePeerRemainsStaysOnPageWithoutResolvedSnack() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var loads = 0
            val remaining = authorSnapshot().copy(snapshotToken = "e".repeat(43))
            val host = host(
                load = { _, _ ->
                    loads += 1
                    ConflictResolverLoad(
                        snapshot = if (loads == 1) authorSnapshot() else remaining,
                        fetchedOnline = true,
                    )
                },
                withdraw = { _, _ ->
                    ConflictResolveOutcome.Withdrawn("v-stable", stillOpen = true)
                },
                role = FamilyRole.Member,
            )
            host.open(CONFLICT_A)
            advanceUntilIdle()
            assertTrue(host.state.value.canWithdraw)

            host.submitWithdraw()
            advanceUntilIdle()

            assertNull(host.state.value.resolvedConflictId)
            assertEquals("已撤回你的修改", host.state.value.notice)
            assertEquals(remaining.snapshotToken, host.state.value.draft?.model?.snapshotToken)
            assertTrue(loads >= 2)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun lateSubmitCannotReopenDismissedResolver() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val outcome = CompletableDeferred<ConflictResolveOutcome>()
            val host = host(
                load = { _, _ -> ConflictResolverLoad(snapshot(), fetchedOnline = true) },
                resolve = { _, _ -> withContext(NonCancellable) { outcome.await() } },
            )
            host.open(CONFLICT_A)
            advanceUntilIdle()
            host.choose("/note", BRANCH_CHOICE_ID)
            host.submit()
            runCurrent()
            assertTrue(host.state.value.submitting)

            host.dismiss()
            outcome.complete(ConflictResolveOutcome.Accepted("v-resolved"))
            advanceUntilIdle()

            assertNull(host.state.value.conflictId)
            assertNull(host.state.value.draft)
            assertFalse(host.state.value.submitting)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun host(
        load: suspend (String, Boolean) -> ConflictResolverLoad?,
        resolve: suspend (String, com.lezi.babylog.sync.backend.ConflictResolveRequest) ->
            ConflictResolveOutcome = { _, _ -> error("unexpected submit") },
        withdraw: suspend (String, com.lezi.babylog.sync.backend.ConflictWithdrawRequest) ->
            ConflictResolveOutcome = { _, _ -> error("unexpected withdraw") },
        nowMillis: () -> Long = { 1_000 },
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        role: FamilyRole = FamilyRole.Owner,
    ) = ConflictResolverHost(
        loadConflict = load,
        resolveConflict = resolve,
        withdrawConflict = withdraw,
        sessions = flowOf(
            SyncSession(
                role = role,
                membershipId = "member-author",
            ).toPresentation(),
        ),
        nowMillis = nowMillis,
        savedStateHandle = savedStateHandle,
    )
}

private fun authorSnapshot(): ConflictSnapshot {
    val base = snapshot()
    val own = base.branches.single().copy(actorId = "member-author")
    return base.copy(branches = listOf(own))
}

private fun snapshot(): ConflictSnapshot {
    val root = ConflictRoot.Record(
        babyClientUuid = "00000000-0000-0000-0000-000000000003",
        type = "formula",
        customItemClientUuid = null,
        timestamp = 100,
        endTimestamp = null,
        note = "stable",
        payload = Json.parseToJsonElement("""{"amount_ml":60}""").jsonObject,
        schemaVersion = 2,
        effectiveWakeObservationClientUuid = null,
        createdByMembershipId = "member-author",
        updatedAt = 100,
        canonical = Json.parseToJsonElement(
            """{"timestamp":100,"note":"stable","created_by_membership_id":"member-author","updated_at":100}""",
        ).jsonObject,
    )
    val stable = ConflictSource(
        "v-stable",
        "00000000-0000-0000-0000-000000000004",
        "member-author",
        "device-a",
        100,
    )
    val branch = ConflictSource(
        "v-branch",
        "00000000-0000-0000-0000-000000000005",
        "member-other",
        "device-b",
        110,
    )
    return ConflictSnapshot(
        conflictId = CONFLICT_A,
        entityType = ConflictRootType.Record,
        clientUuid = "00000000-0000-0000-0000-000000000006",
        snapshotToken = "a".repeat(43),
        expiresAt = 2_000_000,
        stable = ConflictVersionSnapshot(
            "v-stable", null, root, emptyList(), false,
            stable.mutationId, stable.actorId, stable.deviceId, stable.receivedAt,
        ),
        branches = listOf(
            ConflictVersionSnapshot(
                "v-branch", "v-stable", root.copy(note = "branch"), emptyList(), false,
                branch.mutationId, branch.actorId, branch.deviceId, branch.receivedAt,
            ),
        ),
        conflicting = listOf(
            ConflictingPath(
                "/note",
                listOf(
                    ConflictCandidate(STABLE_CHOICE_ID, ConflictOutcome.Set(JsonPrimitive("stable")), listOf(stable)),
                    ConflictCandidate(BRANCH_CHOICE_ID, ConflictOutcome.Set(JsonPrimitive("branch")), listOf(branch)),
                ),
            ),
        ),
        autoMerged = emptyList(),
        pageIndex = 0,
        continuation = null,
        complete = true,
    )
}

private const val CONFLICT_A = "00000000-0000-0000-0000-000000000001"
private const val CONFLICT_B = "00000000-0000-0000-0000-000000000002"
private val STABLE_CHOICE_ID = "b".repeat(43)
private val BRANCH_CHOICE_ID = "c".repeat(43)
