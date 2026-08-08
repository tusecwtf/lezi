package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.CausalBatchResult
import com.lezi.babylog.sync.backend.CausalCommitStatus
import com.lezi.babylog.sync.backend.CausalReconcileStatus
import com.lezi.babylog.sync.backend.CausalUnitResult
import com.lezi.babylog.sync.backend.PullConflictSummary
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.session.FamilyRole
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Public seam: [ReplicaSyncEngine.synchronize] with [RecordingSyncBackend] causal
 * reconcile/commit — freeze, proof fail-closed, accepted/merged/branched CAS,
 * dirty pull protection, generation recovery.
 */
class ReplicaSyncEngineCausalSettlementTest {

    @Test
    fun freezeMutationIdStableAcrossRetryAndClearsOnAccepted() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(syncDirty = false, familyAuthority = true, baseVersion = "v-baby"),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = "record-causal-1",
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

        var firstMutationId: String? = null
        rig.backend.onCausalReconcile = { units ->
            firstMutationId = units.single().mutationId
            assertThat(units.single().baseVersion).isEqualTo("v-r0")
            assertThat(units.single().entityType).isEqualTo("record")
            assertThat(units.single().clientUuid).isEqualTo("record-causal-1")
            assertThat(units.single().rootJson).contains("\"amount_ml\"")
        }
        rig.backend.onCausalCommit = { units ->
            assertThat(units.single().mutationId).isEqualTo(firstMutationId)
        }

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        val settled = requireNotNull(rig.records.getByClientUuid("record-causal-1"))
        assertThat(settled.syncDirty).isFalse()
        assertThat(settled.mutationId).isNull()
        assertThat(settled.baseVersion).isEqualTo("v-${firstMutationId!!.take(8)}")
        assertThat(settled.openConflictId).isNull()
        assertThat(rig.backend.causalReconciledUnits).hasSize(1)
        assertThat(rig.backend.causalCommittedUnits).hasSize(1)

        // Retry same content epoch reuses mutation id (freeze) — no dirty work left.
        rig.engine.synchronize(session, SyncTrigger.LocalWrite)
        assertThat(rig.backend.causalReconciledUnits).hasSize(1)
    }

    @Test
    fun confirmedExactMutationClearsPendingWithoutCommit() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = "record-confirmed",
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = """{"amount_ml":60}""",
                schemaVersion = 2,
                updatedAt = 50,
                syncDirty = true,
                baseVersion = "v-stable",
                mutationId = "mut-confirmed",
            ),
        )
        rig.backend.causalReconcileConfirmed = true
        // Default confirmed status with base_version as stable_version_id.

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        val row = requireNotNull(rig.records.getByClientUuid("record-confirmed"))
        assertThat(row.syncDirty).isFalse()
        assertThat(row.mutationId).isNull()
        assertThat(row.baseVersion).isEqualTo("v-stable")
        assertThat(rig.backend.causalCommittedUnits).isEmpty()
    }

    @Test
    fun branchedReceiptBecomesUnresolvedConflictNotBlindResend() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = "record-branch",
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                note = "local note",
                payloadJson = """{"amount_ml":60}""",
                schemaVersion = 2,
                updatedAt = 200,
                syncDirty = true,
                baseVersion = "v-base",
            ),
        )
        rig.backend.onCausalReconcile = { units ->
            val unit = units.single()
            rig.backend.nextCausalReconcile = CausalBatchResult(
                generation = session.pullGeneration,
                cursor = session.pullCursor,
                results = listOf(
                    CausalUnitResult(
                        status = CausalReconcileStatus.CONFLICT_PREVIEW,
                        mutationId = unit.mutationId,
                        requestHash = "h-branch",
                        generation = session.pullGeneration,
                        stableVersionId = "v-base",
                        stableRootJson = unit.rootJson,
                    ),
                ),
            )
        }
        rig.backend.onCausalCommit = { units ->
            val unit = units.single()
            rig.backend.nextCausalCommit = CausalBatchResult(
                generation = session.pullGeneration,
                cursor = session.pullCursor,
                results = listOf(
                    CausalUnitResult(
                        status = CausalCommitStatus.BRANCHED,
                        mutationId = unit.mutationId,
                        requestHash = "h-branch",
                        generation = session.pullGeneration,
                        stableVersionId = "v-base",
                        stableRootJson = """{"note":"remote"}""",
                        branchVersionId = "branch-v9",
                        conflictId = "conflict-1",
                    ),
                ),
            )
        }

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        val branched = requireNotNull(rig.records.getByClientUuid("record-branch"))
        assertThat(branched.syncDirty).isFalse()
        assertThat(branched.openConflictId).isEqualTo("conflict-1")
        assertThat(branched.localBranchVersionId).isEqualTo("branch-v9")
        assertThat(branched.baseVersion).isEqualTo("v-base")
        assertThat(branched.mutationId).isNotNull()
        // Stable projection content from the server is the ordinary visible root.
        assertThat(branched.note).isEqualTo("remote")
        val summary = requireNotNull(
            rig.conflictSummaries.get(conflictId = "conflict-1"),
        )
        assertThat(summary.stableVersionId).isEqualTo("v-base")
        assertThat(summary.status).isEqualTo("open")
        // Second cycle must not blind-resend the same branched mutation.
        val commitsBefore = rig.backend.causalCommittedUnits.size
        rig.engine.synchronize(session, SyncTrigger.LocalWrite)
        assertThat(rig.backend.causalCommittedUnits).hasSize(commitsBefore)
    }

    @Test
    fun proofMissingKeyFailsClosedWithoutClearingPending() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = "record-proof",
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = """{"amount_ml":60}""",
                schemaVersion = 2,
                updatedAt = 10,
                syncDirty = true,
                baseVersion = "v0",
            ),
        )
        rig.backend.onCausalReconcile = {
            rig.backend.nextCausalReconcile = CausalBatchResult(
                generation = session.pullGeneration,
                cursor = session.pullCursor,
                results = emptyList(),
            )
        }

        try {
            rig.engine.synchronize(session, SyncTrigger.LocalWrite)
            error("expected fail closed")
        } catch (_: Exception) {
            // AuthorityProofException or require failure — both retain dirty.
        }

        val row = requireNotNull(rig.records.getByClientUuid("record-proof"))
        assertThat(row.syncDirty).isTrue()
        assertThat(rig.backend.causalCommittedUnits).isEmpty()
    }

    @Test
    fun concurrentLocalEditAfterFreezeDoesNotApplyStaleAcceptedCas() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = "record-cas-race",
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                note = "epoch-1",
                payloadJson = """{"amount_ml":60}""",
                schemaVersion = 2,
                updatedAt = 100,
                syncDirty = true,
                baseVersion = "v0",
            ),
        )
        rig.backend.onCausalCommit = { units ->
            // User edits while commit is in flight.
            val current = requireNotNull(rig.records.getByClientUuid("record-cas-race"))
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
                        requestHash = "h",
                        generation = session.pullGeneration,
                        stableVersionId = "v-stale",
                        stableRootJson = unit.rootJson,
                    ),
                ),
            )
        }

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        val row = requireNotNull(rig.records.getByClientUuid("record-cas-race"))
        assertThat(row.note).isEqualTo("epoch-2")
        assertThat(row.updatedAt).isEqualTo(200)
        assertThat(row.syncDirty).isTrue()
        assertThat(row.baseVersion).isNotEqualTo("v-stale")
    }

    @Test
    fun pullDoesNotOverwriteDirtyUnfinishedMutationByUpdatedAt() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(syncDirty = false, familyAuthority = true, baseVersion = "v-b"),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = "record-dirty-pull",
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                note = "local-pending",
                payloadJson = """{"amount_ml":60}""",
                schemaVersion = 2,
                updatedAt = 100,
                syncDirty = true,
                baseVersion = "v-r0",
                mutationId = "mut-pending",
            ),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteReplicaRecord("record-dirty-pull").copy(
                    updatedAt = 999_999,
                    versionId = "v-remote-high",
                    payloadJson = """
                        {
                          "baby_client_uuid":"baby-local",
                          "created_by_membership_id":"membership-b",
                          "type":"formula",
                          "custom_item_client_uuid":null,
                          "timestamp":210,
                          "end_timestamp":null,
                          "note":"remote-should-not-win",
                          "payload_json":{"amount_ml":90},
                          "schema_version":2
                        }
                    """.trimIndent(),
                ),
            ),
            cursor = 1,
            generation = session.pullGeneration,
            hasMore = false,
        )
        // Reject settlement so pending stays; pull must still not clobber content.
        rig.backend.onCausalReconcile = { units ->
            val unit = units.single()
            rig.backend.nextCausalReconcile = CausalBatchResult(
                generation = session.pullGeneration,
                cursor = session.pullCursor,
                results = listOf(
                    CausalUnitResult(
                        status = CausalReconcileStatus.REJECTED,
                        mutationId = unit.mutationId,
                        requestHash = "h",
                        generation = session.pullGeneration,
                        code = "temporary",
                        reason = "retry_later",
                    ),
                ),
            )
        }

        rig.engine.synchronize(session, SyncTrigger.PullToRefresh)

        val row = requireNotNull(rig.records.getByClientUuid("record-dirty-pull"))
        assertThat(row.note).isEqualTo("local-pending")
        assertThat(row.syncDirty).isTrue()
        assertThat(row.updatedAt).isEqualTo(100)
        assertThat(row.baseVersion).isEqualTo("v-r0")
    }

    @Test
    fun pullAppliesStableVersionAndConflictSummaryOnCleanRoot() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(syncDirty = false, familyAuthority = true, baseVersion = "v-b"),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = "record-clean-pull",
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                note = "old",
                payloadJson = """{"amount_ml":60}""",
                schemaVersion = 2,
                updatedAt = 100,
                syncDirty = false,
                baseVersion = "v-r0",
            ),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteReplicaRecord("record-clean-pull").copy(
                    updatedAt = 300,
                    versionId = "v-r1",
                    conflictSummary = PullConflictSummary(
                        conflictId = "c-peer",
                        entityType = "record",
                        clientUuid = "record-clean-pull",
                        stableVersionId = "v-r1",
                        branchVersionIds = listOf("branch-peer"),
                    ),
                    payloadJson = """
                        {
                          "baby_client_uuid":"baby-local",
                          "created_by_membership_id":"membership-b",
                          "type":"formula",
                          "custom_item_client_uuid":null,
                          "timestamp":210,
                          "end_timestamp":null,
                          "note":"stable-peer",
                          "payload_json":{"amount_ml":90},
                          "schema_version":2
                        }
                    """.trimIndent(),
                ),
            ),
            cursor = 2,
            generation = session.pullGeneration,
            hasMore = false,
        )

        rig.engine.synchronize(session, SyncTrigger.PullToRefresh)

        val row = requireNotNull(rig.records.getByClientUuid("record-clean-pull"))
        assertThat(row.note).isEqualTo("stable-peer")
        assertThat(row.syncDirty).isFalse()
        assertThat(row.baseVersion).isEqualTo("v-r1")
        assertThat(row.openConflictId).isEqualTo("c-peer")
    }

    @Test
    fun generationDriftOnCausalReconcileTriggersFullResyncPath() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = "record-gen",
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = """{"amount_ml":60}""",
                schemaVersion = 2,
                updatedAt = 10,
                syncDirty = true,
                baseVersion = null,
            ),
        )
        var driftedOnce = false
        rig.backend.onCausalReconcile = { units ->
            if (!driftedOnce) {
                driftedOnce = true
                val unit = units.single()
                rig.backend.nextCausalReconcile = CausalBatchResult(
                    generation = "other-generation",
                    cursor = session.pullCursor,
                    results = listOf(
                        CausalUnitResult(
                            status = CausalReconcileStatus.PUBLISH,
                            mutationId = unit.mutationId,
                            requestHash = "h",
                            generation = "other-generation",
                            stableVersionId = null,
                            stableRootJson = unit.rootJson,
                        ),
                    ),
                )
            }
        }
        // recoverFullResync updates the session generation then pulls with defaults
        // (RecordingSyncBackend returns session.pullGeneration when queue is empty).

        // AuthorityProofException is caught in synchronize and triggers recoverFullResync.
        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        // Local root survives generation recovery; session advances to server generation.
        val row = requireNotNull(rig.records.getByClientUuid("record-gen"))
        assertThat(row.clientUuid).isEqualTo("record-gen")
        assertThat(rig.preferences.current().pullGeneration).isEqualTo("other-generation")
        // After recovery the engine re-settles; accepted commit may clear dirty.
        assertThat(rig.backend.causalReconciledUnits.size).isAtLeast(2)
    }

    @Test
    fun acceptedWithStableRootUpdatedAtDifferentFromFrozenEpochClearsMutation() = runTest {
        // correctness-01: projection must not rewrite contentEpoch before CAS ack.
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(syncDirty = false, familyAuthority = true, baseVersion = "v-b"),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = "record-epoch-cas",
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                note = "local",
                payloadJson = """{"amount_ml":60}""",
                schemaVersion = 2,
                updatedAt = 100,
                syncDirty = true,
                baseVersion = "v-r0",
            ),
        )
        rig.backend.onCausalCommit = { units ->
            val unit = units.single()
            // Server max(updated_at) often differs from frozen local epoch.
            val stableRoot = """
                {
                  "baby_client_uuid":"baby-local",
                  "type":"formula",
                  "custom_item_client_uuid":null,
                  "timestamp":100,
                  "note":"accepted-stable",
                  "payload_json":{"amount_ml":60},
                  "schema_version":2,
                  "updated_at":999
                }
            """.trimIndent()
            rig.backend.nextCausalCommit = CausalBatchResult(
                generation = session.pullGeneration,
                cursor = session.pullCursor,
                results = listOf(
                    CausalUnitResult(
                        status = CausalCommitStatus.ACCEPTED,
                        mutationId = unit.mutationId,
                        requestHash = causalMutationContentHash(unit),
                        generation = session.pullGeneration,
                        stableVersionId = "v-r1",
                        stableRootJson = stableRoot,
                    ),
                ),
            )
        }

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        val row = requireNotNull(rig.records.getByClientUuid("record-epoch-cas"))
        assertThat(row.syncDirty).isFalse()
        assertThat(row.mutationId).isNull()
        assertThat(row.baseVersion).isEqualTo("v-r1")
        assertThat(row.note).isEqualTo("accepted-stable")
        assertThat(row.updatedAt).isEqualTo(999)
        assertThat(row.openConflictId).isNull()
    }

    @Test
    fun branchedWithStableRootUpdatedAtDifferentSetsOpenConflict() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = "record-branch-epoch",
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                note = "local note",
                payloadJson = """{"amount_ml":60}""",
                schemaVersion = 2,
                updatedAt = 200,
                syncDirty = true,
                baseVersion = "v-base",
            ),
        )
        rig.backend.onCausalReconcile = { units ->
            val unit = units.single()
            rig.backend.nextCausalReconcile = CausalBatchResult(
                generation = session.pullGeneration,
                cursor = session.pullCursor,
                results = listOf(
                    CausalUnitResult(
                        status = CausalReconcileStatus.CONFLICT_PREVIEW,
                        mutationId = unit.mutationId,
                        requestHash = causalMutationContentHash(unit),
                        generation = session.pullGeneration,
                        stableVersionId = "v-base",
                        stableRootJson = """{"note":"remote","updated_at":50}""",
                    ),
                ),
            )
        }
        rig.backend.onCausalCommit = { units ->
            val unit = units.single()
            rig.backend.nextCausalCommit = CausalBatchResult(
                generation = session.pullGeneration,
                cursor = session.pullCursor,
                results = listOf(
                    CausalUnitResult(
                        status = CausalCommitStatus.BRANCHED,
                        mutationId = unit.mutationId,
                        requestHash = causalMutationContentHash(unit),
                        generation = session.pullGeneration,
                        stableVersionId = "v-base",
                        stableRootJson = """{"note":"remote","updated_at":50}""",
                        branchVersionId = "branch-v9",
                        conflictId = "conflict-epoch",
                    ),
                ),
            )
        }

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        val branched = requireNotNull(rig.records.getByClientUuid("record-branch-epoch"))
        assertThat(branched.syncDirty).isFalse()
        assertThat(branched.openConflictId).isEqualTo("conflict-epoch")
        assertThat(branched.localBranchVersionId).isEqualTo("branch-v9")
        assertThat(branched.baseVersion).isEqualTo("v-base")
        assertThat(branched.note).isEqualTo("remote")
        // No infinite dirty resend.
        val commitsBefore = rig.backend.causalCommittedUnits.size
        rig.engine.synchronize(session, SyncTrigger.LocalWrite)
        assertThat(rig.backend.causalCommittedUnits).hasSize(commitsBefore)
    }

    @Test
    fun sleepCausalRootOmitsEndTimestampAndIncludesEffectiveWake() = runTest {
        // correctness-03: closed sleep key set for causal freeze.
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = "sleep-causal",
                babyId = babyId,
                type = "sleep",
                timestamp = 100,
                endTimestamp = 200, // local legacy column must not appear on causal wire
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                schemaVersion = 2,
                updatedAt = 50,
                syncDirty = true,
                effectiveWakeObservationClientUuid = null,
            ),
        )
        var frozenRoot: String? = null
        rig.backend.onCausalReconcile = { units ->
            frozenRoot = units.single().rootJson
        }

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        val root = requireNotNull(frozenRoot)
        assertThat(root).doesNotContain("end_timestamp")
        assertThat(root).contains("effective_wake_observation_client_uuid")
        val settled = requireNotNull(rig.records.getByClientUuid("sleep-causal"))
        assertThat(settled.syncDirty).isFalse()
    }

    @Test
    fun pullAppliesVersionIdAdvanceEvenWhenUpdatedAtEqual() = runTest {
        // correctness-05: residual LWW must not drop peer content on equal updated_at.
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(syncDirty = false, familyAuthority = true, baseVersion = "v-b"),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = "record-vid-pull",
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                note = "local-v1",
                payloadJson = """{"amount_ml":60}""",
                schemaVersion = 2,
                updatedAt = 100,
                syncDirty = false,
                baseVersion = "v1",
            ),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteReplicaRecord("record-vid-pull").copy(
                    updatedAt = 100, // same stamp; version advanced
                    versionId = "v2",
                    conflictSummary = PullConflictSummary(
                        conflictId = "c-eq",
                        entityType = "record",
                        clientUuid = "record-vid-pull",
                        stableVersionId = "v2",
                        branchVersionIds = listOf("b-eq"),
                    ),
                    payloadJson = """
                        {
                          "baby_client_uuid":"baby-local",
                          "created_by_membership_id":"membership-b",
                          "type":"formula",
                          "custom_item_client_uuid":null,
                          "timestamp":210,
                          "end_timestamp":null,
                          "note":"peer-v2",
                          "payload_json":{"amount_ml":90},
                          "schema_version":2
                        }
                    """.trimIndent(),
                ),
            ),
            cursor = 3,
            generation = session.pullGeneration,
            hasMore = false,
        )

        rig.engine.synchronize(session, SyncTrigger.PullToRefresh)

        val row = requireNotNull(rig.records.getByClientUuid("record-vid-pull"))
        assertThat(row.note).isEqualTo("peer-v2")
        assertThat(row.baseVersion).isEqualTo("v2")
        assertThat(row.openConflictId).isEqualTo("c-eq")
    }

    @Test
    fun causalMutationContentHashMatchesServerCanonicalGolden() {
        // correctness-02: parity with Rust mutation_content_hash golden.
        val unit = com.lezi.babylog.sync.backend.CausalMutationUnit(
            mutationId = "mut-unused-in-hash",
            baseVersion = "v-r0",
            entityType = "record",
            clientUuid = "11111111-1111-1111-1111-111111111111",
            rootJson = """
                {
                  "baby_client_uuid":"22222222-2222-2222-2222-222222222222",
                  "custom_item_client_uuid":null,
                  "note":null,
                  "payload_json":{"amount_ml":90},
                  "schema_version":2,
                  "timestamp":100,
                  "type":"formula",
                  "updated_at":100
                }
            """.trimIndent(),
            media = emptyList(),
            deleted = false,
        )
        assertThat(causalMutationContentHash(unit))
            .isEqualTo("ff2cec4612265f208e3c3a06029fdd1e24c0d75a2081c89e8468812720e9a83a")
    }
}

@RunWith(Parameterized::class)
class ReplicaSyncEngineCausalRootTypesTest(
    private val entityType: String,
) {
    @Test
    fun acceptedSettlesEachRootType() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        when (entityType) {
            "baby" -> rig.babies.seed(
                localReplicaBaby().copy(
                    syncDirty = true,
                    familyAuthority = true,
                    baseVersion = null,
                    updatedAt = 50,
                ),
            )
            "record" -> {
                val babyId = rig.babies.seed(
                    localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
                )
                rig.records.seed(
                    RecordEntity(
                        clientUuid = "root-record",
                        babyId = babyId,
                        type = "formula",
                        timestamp = 1,
                        payloadJson = """{"amount_ml":1}""",
                        schemaVersion = 2,
                        updatedAt = 50,
                        syncDirty = true,
                    ),
                )
            }
            "care_plan" -> {
                val babyId = rig.babies.seed(
                    localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
                )
                rig.carePlans.seed(
                    localReplicaCarePlan("root-plan", "membership-a", 50).copy(
                        babyId = babyId,
                        syncDirty = true,
                        status = "pending",
                        schemaVersion = 2,
                    ),
                )
            }
            "custom_item" -> rig.customItems.seed(
                localReplicaCustomItem("root-custom", "membership-a", 50).copy(syncDirty = true),
            )
            "wake_observation" -> {
                val babyId = rig.babies.seed(
                    localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
                )
                rig.records.seed(
                    RecordEntity(
                        clientUuid = "sleep-for-wake",
                        babyId = babyId,
                        type = "sleep",
                        timestamp = 1,
                        payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                        schemaVersion = 2,
                        updatedAt = 1,
                        syncDirty = false,
                    ),
                )
                rig.wakeObservations.seed(
                    com.lezi.babylog.core.database.causal.WakeObservationEntity(
                        clientUuid = "root-wake",
                        sleepRecordClientUuid = "sleep-for-wake",
                        wakeTimestamp = 2,
                        observerMembershipId = "membership-a",
                        note = "woke",
                        withdrawn = false,
                        updatedAt = 50,
                        syncDirty = true,
                    ),
                )
            }
            else -> error(entityType)
        }

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(rig.backend.causalReconciledUnits).isNotEmpty()
        assertThat(rig.backend.causalCommittedUnits).isNotEmpty()
        when (entityType) {
            "baby" -> {
                val baby = requireNotNull(rig.babies.getByClientUuid("baby-local"))
                assertThat(baby.syncDirty).isFalse()
                assertThat(baby.baseVersion).isNotNull()
            }
            "record" -> {
                val row = requireNotNull(rig.records.getByClientUuid("root-record"))
                assertThat(row.syncDirty).isFalse()
                assertThat(row.baseVersion).isNotNull()
            }
            "care_plan" -> {
                val plan = requireNotNull(rig.carePlans.getByClientUuid("root-plan"))
                assertThat(plan.syncDirty).isFalse()
                assertThat(plan.baseVersion).isNotNull()
            }
            "custom_item" -> {
                val item = requireNotNull(rig.customItems.getByClientUuid("root-custom"))
                assertThat(item.syncDirty).isFalse()
                assertThat(item.baseVersion).isNotNull()
            }
            "wake_observation" -> {
                val wake = requireNotNull(rig.wakeObservations.getByClientUuid("root-wake"))
                assertThat(wake.syncDirty).isFalse()
                assertThat(wake.baseVersion).isNotNull()
            }
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun rootTypes(): List<Array<String>> = listOf(
            arrayOf("baby"),
            arrayOf("record"),
            arrayOf("care_plan"),
            arrayOf("custom_item"),
            arrayOf("wake_observation"),
        )
    }
}
