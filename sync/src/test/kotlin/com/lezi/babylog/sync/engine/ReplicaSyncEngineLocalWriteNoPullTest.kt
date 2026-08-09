package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.sync.SyncPlan
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.CausalBatchResult
import com.lezi.babylog.sync.backend.CausalCommitStatus
import com.lezi.babylog.sync.backend.CausalReconcileStatus
import com.lezi.babylog.sync.backend.CausalUnitResult
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.session.FamilyRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Public seam: [ReplicaSyncEngine.synchronize] + [SyncPlan.forTrigger] for LocalWrite
 * no-pull plan under causal wire — freeze dirty roots → causal reconcile/(media preimage)/
 * commit, never pull, never advance the incremental pull cursor; full cycles still pull.
 * Without causal capability LocalWrite retains pull (no unsafe LWW no-pull).
 */
class ReplicaSyncEngineLocalWriteNoPullTest {

    @Test
    fun localWritePlanDeclaresPushWithoutPull() {
        assertThat(SyncPlan.forTrigger(SyncTrigger.LocalWrite))
            .isEqualTo(SyncPlan(push = true, pull = false))
        assertThat(SyncPlan.forTrigger(SyncTrigger.Foreground).pull).isTrue()
        assertThat(SyncPlan.forTrigger(SyncTrigger.PullToRefresh).pull).isTrue()
    }

    @Test
    fun localWriteCausalOrderIsReconcileThenCommitWithNoPullAndUnchangedCursor() = runTest {
        val (session, rig, recordUuid) = seedDirtyRecord(
            pullCursor = 7,
            clientUuid = "record-localwrite-fast",
        )

        // Unrelated remote page that must NOT be applied or advance the cursor.
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteReplicaRecord("record-peer-unrelated").copy(
                    clientUuid = "record-peer-unrelated",
                    updatedAt = 500,
                    versionId = "v-peer",
                ),
            ),
            cursor = 99,
            generation = session.pullGeneration,
            hasMore = false,
        )

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.backend.syncOrder.filter { it.startsWith("pull:") }).isEmpty()
        assertThat(rig.backend.syncOrder.filter { it.startsWith("causal_") })
            .containsExactly("causal_reconcile:1", "causal_commit:1")
            .inOrder()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(7)
        assertThat(rig.records.getByClientUuid("record-peer-unrelated")).isNull()
        val settled = requireNotNull(rig.records.getByClientUuid(recordUuid))
        assertThat(settled.syncDirty).isFalse()
        assertThat(settled.mutationId).isNull()
        assertThat(rig.backend.causalReconciledUnits).hasSize(1)
        assertThat(rig.backend.causalCommittedUnits).hasSize(1)
    }

    @Test
    fun localWriteWithLogPhotoStagesMediaPreimageBetweenReconcileAndCommit() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 4)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        val recordId = rig.records.seed(
            RecordEntity(
                clientUuid = "record-with-photo",
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = """{"amount_ml":90}""",
                schemaVersion = 2,
                updatedAt = 100,
                syncDirty = true,
                baseVersion = "v-r0",
            ),
        )
        val mediaUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = mediaUuid,
                kind = "log",
                recordId = recordId,
                localUri = "photos/localwrite-photo.jpg",
                mime = "image/jpeg",
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(4)
        assertThat(rig.backend.syncOrder.filter { it.startsWith("causal_") })
            .containsExactly(
                "causal_reconcile:1",
                "causal_media_preimage:$mediaUuid",
                "causal_commit:1",
            )
            .inOrder()
        val settled = requireNotNull(rig.records.getByClientUuid("record-with-photo"))
        assertThat(settled.syncDirty).isFalse()
        assertThat(rig.media.getByClientUuid(mediaUuid)?.syncDirty).isFalse()
    }

    @Test
    fun localWriteAcceptedMergedBranchedDoNotPullOrAdvanceCursor() = runTest {
        data class Case(
            val name: String,
            val commitStatus: String,
            val expectConflict: Boolean,
        )
        val cases = listOf(
            Case("accepted", CausalCommitStatus.ACCEPTED, expectConflict = false),
            Case("merged", CausalCommitStatus.MERGED, expectConflict = false),
            Case("branched", CausalCommitStatus.BRANCHED, expectConflict = true),
        )
        for (case in cases) {
            val (session, rig, uuid) = seedDirtyRecord(
                pullCursor = 3,
                clientUuid = "record-${case.name}",
                note = "local-${case.name}",
            )
            if (case.expectConflict) {
                rig.backend.onCausalReconcile = { units ->
                    val unit = units.single()
                    rig.backend.nextCausalReconcile = CausalBatchResult(
                        generation = session.pullGeneration,
                        cursor = 999,
                        results = listOf(
                            CausalUnitResult(
                                status = CausalReconcileStatus.CONFLICT_PREVIEW,
                                mutationId = unit.mutationId,
                                requestHash = causalMutationContentHash(unit),
                                generation = session.pullGeneration,
                                stableVersionId = "v-base",
                                stableRootJson = unit.rootJson,
                            ),
                        ),
                    )
                }
            }
            rig.backend.onCausalCommit = { units ->
                val unit = units.single()
                rig.backend.nextCausalCommit = CausalBatchResult(
                    generation = session.pullGeneration,
                    cursor = 999,
                    results = listOf(
                        CausalUnitResult(
                            status = case.commitStatus,
                            mutationId = unit.mutationId,
                            requestHash = causalMutationContentHash(unit),
                            generation = session.pullGeneration,
                            stableVersionId = if (case.expectConflict) {
                                "v-base"
                            } else {
                                "v-${case.name}"
                            },
                            stableRootJson = if (case.expectConflict) {
                                unit.rootJson.replace(
                                    "\"note\":\"local-${case.name}\"",
                                    "\"note\":\"remote\"",
                                )
                            } else {
                                unit.rootJson
                            },
                            branchVersionId = if (case.expectConflict) {
                                "branch-${case.name}"
                            } else {
                                null
                            },
                            conflictId = if (case.expectConflict) {
                                "conflict-${case.name}"
                            } else {
                                null
                            },
                        ),
                    ),
                )
            }

            rig.engine.synchronize(session, SyncTrigger.LocalWrite)

            assertThat(rig.backend.pullCount).isEqualTo(0)
            assertThat(rig.preferences.current().pullCursor).isEqualTo(3)
            val row = requireNotNull(rig.records.getByClientUuid(uuid))
            assertThat(row.syncDirty).isFalse()
            if (case.expectConflict) {
                assertThat(row.openConflictId).isEqualTo("conflict-${case.name}")
            } else {
                assertThat(row.openConflictId).isNull()
                assertThat(row.baseVersion).isEqualTo("v-${case.name}")
            }
        }
    }

    @Test
    fun localWriteDoesNotPullUnrelatedFamilyThenFullCycleDoes() = runTest {
        val (session, rig, _) = seedDirtyRecord(
            pullCursor = 0,
            clientUuid = "record-mine",
        )

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.records.getByClientUuid("record-peer-other")).isNull()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(0)

        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteReplicaRecord("record-peer-other").copy(
                    versionId = "v-peer-1",
                ),
            ),
            cursor = 4,
            generation = session.pullGeneration,
            hasMore = false,
        )
        rig.engine.synchronize(session, SyncTrigger.PullToRefresh)

        assertThat(rig.backend.pullCount).isEqualTo(1)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(4)
        val peer = requireNotNull(rig.records.getByClientUuid("record-peer-other"))
        assertThat(peer.syncDirty).isFalse()
        assertThat(peer.baseVersion).isEqualTo("v-peer-1")
    }

    @Test
    fun concurrentLocalEditAfterLocalWriteFreezeEntersNextCycle() = runTest {
        val (session, rig, uuid) = seedDirtyRecord(
            pullCursor = 2,
            clientUuid = "record-epoch",
            note = "epoch-1",
        )
        var frozenMutationId: String? = null
        rig.backend.onCausalCommit = { units ->
            frozenMutationId = units.single().mutationId
            val current = requireNotNull(rig.records.getByClientUuid(uuid))
            rig.records.update(
                current.copy(
                    note = "epoch-2",
                    updatedAt = 200,
                    syncDirty = true,
                    mutationId = null,
                ),
            )
            val unit = units.single()
            rig.backend.nextCausalCommit = CausalBatchResult(
                generation = session.pullGeneration,
                cursor = session.pullCursor,
                results = listOf(
                    CausalUnitResult(
                        status = CausalCommitStatus.ACCEPTED,
                        mutationId = unit.mutationId,
                        requestHash = causalMutationContentHash(unit),
                        generation = session.pullGeneration,
                        stableVersionId = "v-stale",
                        stableRootJson = unit.rootJson,
                    ),
                ),
            )
        }

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(2)
        val mid = requireNotNull(rig.records.getByClientUuid(uuid))
        assertThat(mid.note).isEqualTo("epoch-2")
        assertThat(mid.syncDirty).isTrue()
        assertThat(mid.baseVersion).isNotEqualTo("v-stale")

        rig.backend.onCausalCommit = null
        rig.engine.synchronize(session, SyncTrigger.LocalWrite)
        val second = rig.backend.causalCommittedUnits.last().single()
        assertThat(second.mutationId).isNotEqualTo(frozenMutationId)
        val done = requireNotNull(rig.records.getByClientUuid(uuid))
        assertThat(done.note).isEqualTo("epoch-2")
        assertThat(done.syncDirty).isFalse()
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(2)
    }

    @Test
    fun localWriteCoBatchedSleepAndWakeResettlesWakeAfterSleepAccepted() = runTest {
        // First reconcile rejects wake (missing_sleep_reference) while sleep publishes;
        // residual replan in the same LocalWrite cycle must accept wake without a second trigger.
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 6)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        val sleepUuid = "sleep-cobatch"
        val wakeUuid = "wake-cobatch"
        rig.records.seed(
            RecordEntity(
                clientUuid = sleepUuid,
                babyId = babyId,
                type = "sleep",
                timestamp = 100,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                schemaVersion = 2,
                updatedAt = 100,
                syncDirty = true,
                baseVersion = null,
            ),
        )
        rig.wakeObservations.seed(
            com.lezi.babylog.core.database.causal.WakeObservationEntity(
                clientUuid = wakeUuid,
                sleepRecordClientUuid = sleepUuid,
                wakeTimestamp = 200,
                observerMembershipId = "membership-a",
                note = "up",
                withdrawn = false,
                updatedAt = 200,
                syncDirty = true,
                baseVersion = null,
            ),
        )

        var reconcilePass = 0
        rig.backend.onCausalReconcile = { units ->
            reconcilePass += 1
            val results = units.map { unit ->
                when {
                    unit.entityType == "wake_observation" && reconcilePass == 1 ->
                        CausalUnitResult(
                            status = CausalReconcileStatus.REJECTED,
                            mutationId = unit.mutationId,
                            requestHash = causalMutationContentHash(unit),
                            generation = session.pullGeneration,
                            code = "missing_sleep_reference",
                            reason = "sleep not yet stable",
                        )
                    else -> CausalUnitResult(
                        status = CausalReconcileStatus.PUBLISH,
                        mutationId = unit.mutationId,
                        requestHash = causalMutationContentHash(unit),
                        generation = session.pullGeneration,
                    )
                }
            }
            rig.backend.nextCausalReconcile = CausalBatchResult(
                generation = session.pullGeneration,
                cursor = session.pullCursor,
                results = results,
            )
        }

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(6)
        // Two settle passes: sleep+wake then residual wake.
        assertThat(reconcilePass).isEqualTo(2)
        assertThat(rig.backend.causalReconciledUnits).hasSize(2)
        assertThat(rig.backend.causalCommittedUnits).isNotEmpty()
        val sleep = requireNotNull(rig.records.getByClientUuid(sleepUuid))
        val wake = requireNotNull(rig.wakeObservations.getByClientUuid(wakeUuid))
        assertThat(sleep.syncDirty).isFalse()
        assertThat(sleep.baseVersion).isNotNull()
        assertThat(wake.syncDirty).isFalse()
        assertThat(wake.baseVersion).isNotNull()
        assertThat(wake.openConflictId).isNull()
    }

    @Test
    fun localWriteWithoutCausalCapabilityStillPullsBeforeLegacySettle() = runTest {
        // Spec: no-pull is forbidden until causal wire is available (no faster LWW).
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 5)
        val rig = ReplicaEngineRig(session) // enableCausal remains false
        rig.babies.seed(localReplicaBaby().copy(syncDirty = true))

        val outcome = rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.backend.pullCount).isEqualTo(1)
        assertThat(rig.backend.syncOrder)
            .containsExactly("pull:5", "reconcile:1", "stage:baby")
            .inOrder()
        // Incremental pull may advance cursor only when the server reports a new cursor;
        // empty RecordingSyncBackend pull keeps the session cursor at the requested value.
        assertThat(rig.preferences.current().pullCursor).isEqualTo(5)
    }

    @Test
    fun localWriteTransportFailureRetainsDirtyWithoutCursorAdvance() = runTest {
        val (session, rig, uuid) = seedDirtyRecord(
            pullCursor = 11,
            clientUuid = "record-unreachable",
        )
        rig.backend.onCausalReconcile = {
            throw java.io.IOException("endpoint unreachable")
        }

        val failure = runCatching {
            rig.engine.synchronize(session, SyncTrigger.LocalWrite)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(java.io.IOException::class.java)
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(11)
        val row = requireNotNull(rig.records.getByClientUuid(uuid))
        assertThat(row.syncDirty).isTrue()
        assertThat(rig.backend.causalCommittedUnits).isEmpty()
    }

    @Test
    fun localWriteCancellationRetainsDirtyWithoutCursorAdvance() = runTest {
        val (session, rig, uuid) = seedDirtyRecord(
            pullCursor = 8,
            clientUuid = "record-cancel",
        )
        val gate = CompletableDeferred<Unit>()
        rig.backend.onCausalReconcile = {
            gate.await()
        }
        val job = async {
            rig.engine.synchronize(session, SyncTrigger.LocalWrite)
        }
        // Let reconcile start then cancel.
        kotlinx.coroutines.yield()
        job.cancel()
        val thrown = runCatching { job.await() }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        gate.cancel()

        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(8)
        assertThat(requireNotNull(rig.records.getByClientUuid(uuid)).syncDirty).isTrue()
        assertThat(rig.backend.causalCommittedUnits).isEmpty()
    }

    private fun seedDirtyRecord(
        pullCursor: Long,
        clientUuid: String,
        note: String? = null,
    ): Triple<com.lezi.babylog.sync.session.SyncSession, ReplicaEngineRig, String> {
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Owner,
            pullCursor = pullCursor,
        )
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = clientUuid,
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                note = note,
                payloadJson = """{"amount_ml":90}""",
                schemaVersion = 2,
                updatedAt = 100,
                syncDirty = true,
                baseVersion = "v-r0",
            ),
        )
        return Triple(session, rig, clientUuid)
    }
}
