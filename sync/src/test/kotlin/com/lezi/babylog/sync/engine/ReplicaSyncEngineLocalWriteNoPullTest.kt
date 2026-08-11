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
 * no-pull plan under causal wire — freeze eligible dirty roots → causal commit,
 * never pull, never advance the incremental pull cursor; full cycles still pull.
 * Without causal capability LocalWrite retains pull (no unsafe LWW no-pull).
 */
class ReplicaSyncEngineLocalWriteNoPullTest {

    @Test
    fun eligibleProviderLiveAndTombstoneCommitWithoutReconcilePullOrCursorAdvance() = runTest {
        for (entityType in listOf("baby", "custom_item")) {
            for (deleted in listOf(false, true)) {
                val session = joinedReplicaSession().copy(
                    role = FamilyRole.Owner,
                    pullCursor = 41,
                )
                val rig = ReplicaEngineRig(
                    session = session,
                    allowHistoricalMutableRootEvidence = false,
                ).also { it.backend.enableCausal = true }
                val clientUuid = seedDirtyProvider(rig, entityType, deleted, updatedAt = 100)
                rig.backend.nextPull = PullResult(
                    entities = listOf(remoteReplicaRecord("must-not-pull-$entityType-$deleted")),
                    cursor = 99,
                    generation = session.pullGeneration,
                    hasMore = false,
                )

                rig.engine.synchronize(session, SyncTrigger.LocalWrite)

                assertThat(rig.backend.pullCount).isEqualTo(0)
                assertThat(rig.preferences.current().pullCursor).isEqualTo(41)
                assertThat(rig.backend.causalReconciledUnits).isEmpty()
                val committed = rig.backend.causalCommittedUnits.single().single()
                assertThat(committed.entityType).isEqualTo(entityType)
                assertThat(committed.clientUuid).isEqualTo(clientUuid)
                assertThat(committed.deleted).isEqualTo(deleted)
                assertThat(committed.media).isEmpty()
                assertProviderSettled(rig, entityType, clientUuid)
            }
        }
    }

    @Test
    fun providerAcceptedMergedAndBranchedUseOneCommitFirstSettlement() = runTest {
        data class Case(val status: String, val conflict: Boolean)
        val cases = listOf(
            Case(CausalCommitStatus.ACCEPTED, conflict = false),
            Case(CausalCommitStatus.MERGED, conflict = false),
            Case(CausalCommitStatus.BRANCHED, conflict = true),
        )
        for (entityType in listOf("baby", "custom_item")) {
            for (case in cases) {
                val session = joinedReplicaSession().copy(
                    role = FamilyRole.Owner,
                    pullCursor = 42,
                )
                val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
                val uuid = seedDirtyProvider(rig, entityType, deleted = false, updatedAt = 110)
                rig.backend.onCausalCommit = { units ->
                    val unit = units.single()
                    rig.backend.nextCausalCommit = CausalBatchResult(
                        generation = session.pullGeneration,
                        cursor = 999,
                        results = listOf(
                            CausalUnitResult(
                                status = case.status,
                                mutationId = unit.mutationId,
                                requestHash = causalMutationContentHash(unit),
                                generation = session.pullGeneration,
                                stableVersionId = "v-${case.status}-$entityType",
                                stableRootJson = unit.rootJson,
                                stableMedia = emptyList(),
                                branchVersionId = if (case.conflict) "branch-$entityType" else null,
                                conflictId = if (case.conflict) "conflict-$entityType" else null,
                            ),
                        ),
                    )
                }

                rig.engine.synchronize(session, SyncTrigger.LocalWrite)

                assertThat(rig.backend.pullCount).isEqualTo(0)
                assertThat(rig.preferences.current().pullCursor).isEqualTo(42)
                assertThat(rig.backend.causalReconciledUnits).isEmpty()
                when (entityType) {
                    "baby" -> with(requireNotNull(rig.babies.getByClientUuid(uuid))) {
                        assertThat(syncDirty).isFalse()
                        assertThat(openConflictId).isEqualTo(
                            if (case.conflict) "conflict-$entityType" else null,
                        )
                    }
                    "custom_item" -> with(requireNotNull(rig.customItems.get(uuid))) {
                        assertThat(syncDirty).isFalse()
                        assertThat(openConflictId).isEqualTo(
                            if (case.conflict) "conflict-$entityType" else null,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun lostProviderResponseReplaysFrozenEpochBeforeReplanningLaterEdit() = runTest {
        for (entityType in listOf("baby", "custom_item")) {
            val session = joinedReplicaSession().copy(
                role = FamilyRole.Owner,
                pullCursor = 43,
            )
            val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
            val uuid = seedDirtyProvider(rig, entityType, deleted = false, updatedAt = 120)
            var lostEnvelope: com.lezi.babylog.sync.backend.CausalMutationUnit? = null
            rig.backend.onCausalCommit = { units ->
                lostEnvelope = units.single()
                throw java.io.IOException("provider response lost")
            }

            assertThat(
                runCatching {
                    rig.engine.synchronize(session, SyncTrigger.LocalWrite)
                }.exceptionOrNull(),
            ).isInstanceOf(java.io.IOException::class.java)
            val frozen = requireNotNull(lostEnvelope)
            assertThat(rig.conflictDetails.getFrozenMutation(entityType, uuid)).isNotNull()
            applyLaterProviderEdit(rig, entityType, uuid, updatedAt = 220)

            rig.backend.onCausalCommit = { units ->
                val replay = units.single()
                assertThat(replay).isEqualTo(frozen)
                rig.backend.nextCausalCommit = CausalBatchResult(
                    generation = session.pullGeneration,
                    cursor = 999,
                    results = listOf(
                        CausalUnitResult(
                            status = CausalCommitStatus.ACCEPTED,
                            mutationId = replay.mutationId,
                            requestHash = causalMutationContentHash(replay),
                            generation = session.pullGeneration,
                            stableVersionId = "v-provider-epoch-1",
                            stableRootJson = replay.rootJson,
                            stableMedia = emptyList(),
                        ),
                    ),
                )
            }
            rig.engine.synchronize(session, SyncTrigger.LocalWrite)

            assertProviderLaterEditPending(rig, entityType, uuid, expectedBase = "v-provider-epoch-1")
            assertThat(rig.conflictDetails.getFrozenMutation(entityType, uuid)).isNull()

            rig.backend.onCausalCommit = null
            rig.engine.synchronize(session, SyncTrigger.LocalWrite)

            val replanned = rig.backend.causalCommittedUnits.last().single()
            assertThat(replanned.mutationId).isNotEqualTo(frozen.mutationId)
            assertThat(replanned.baseVersion).isEqualTo("v-provider-epoch-1")
            assertThat(replanned.rootJson).contains("epoch-2")
            assertProviderSettled(rig, entityType, uuid)
            assertThat(rig.backend.causalReconciledUnits).isEmpty()
            assertThat(rig.backend.pullCount).isEqualTo(0)
            assertThat(rig.preferences.current().pullCursor).isEqualTo(43)
        }
    }

    @Test
    fun providerContentDriftRetainsExactEnvelopeForRetry() = runTest {
        for (entityType in listOf("baby", "custom_item")) {
            val session = joinedReplicaSession().copy(
                role = FamilyRole.Owner,
                pullCursor = 44,
            )
            val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
            val uuid = seedDirtyProvider(rig, entityType, deleted = false, updatedAt = 130)
            var rejected: com.lezi.babylog.sync.backend.CausalMutationUnit? = null
            rig.backend.onCausalCommit = { units ->
                val unit = units.single()
                rejected = unit
                rig.backend.nextCausalCommit = CausalBatchResult(
                    generation = session.pullGeneration,
                    cursor = session.pullCursor,
                    results = listOf(
                        CausalUnitResult(
                            status = CausalCommitStatus.REJECTED,
                            mutationId = unit.mutationId,
                            requestHash = causalMutationContentHash(unit),
                            generation = session.pullGeneration,
                            code = "content_drift",
                        ),
                    ),
                )
            }

            val failure = runCatching {
                rig.engine.synchronize(session, SyncTrigger.LocalWrite)
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            assertThat(failure).hasMessageThat().contains("content_drift")
            assertProviderPendingMutation(rig, entityType, uuid, requireNotNull(rejected).mutationId)
            assertThat(rig.conflictDetails.getFrozenMutation(entityType, uuid)).isNotNull()
            assertThat(rig.backend.causalReconciledUnits).isEmpty()
            assertThat(rig.backend.pullCount).isEqualTo(0)
            assertThat(rig.preferences.current().pullCursor).isEqualTo(44)
        }
    }

    @Test
    fun completedCarePlanDefersUntilItsFulfilledRecordCanBeFrozen() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 45)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
                clientUuid = "00000000-0000-4000-8000-000000000211",
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        rig.carePlans.seed(
            localReplicaCarePlan("plan-missing-record", "membership-a", updatedAt = 140).copy(
                babyId = babyId,
                status = "completed",
                fulfilledRecordClientUuid = "00000000-0000-4000-8000-000000000212",
                fulfilledAt = 130,
                syncDirty = true,
                baseVersion = "v-plan",
            ),
        )

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(rig.backend.causalCommittedUnits).isEmpty()
        assertThat(rig.backend.causalReconciledUnits).isEmpty()
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(45)
        with(requireNotNull(rig.carePlans.getByClientUuid("plan-missing-record"))) {
            assertThat(syncDirty).isTrue()
            assertThat(mutationId).isNull()
        }
        assertThat(rig.conflictDetails.getFrozenMutation("care_plan", "plan-missing-record"))
            .isNull()
    }

    @Test
    fun completedCarePlanDefersCrossBabyFulfilledRecordWithoutDurableSnapshot() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 45)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val planBabyId = rig.babies.seed(
            localReplicaBaby().copy(
                clientUuid = "00000000-0000-4000-8000-000000000213",
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-plan-baby",
            ),
        )
        val otherBabyId = rig.babies.seed(
            localReplicaBaby().copy(
                clientUuid = "00000000-0000-4000-8000-000000000214",
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-other-baby",
            ),
        )
        val recordUuid = "00000000-0000-4000-8000-000000000215"
        rig.records.seed(
            RecordEntity(
                clientUuid = recordUuid,
                babyId = otherBabyId,
                type = "formula",
                timestamp = 120,
                payloadJson = """{"amount_ml":90}""",
                schemaVersion = 2,
                updatedAt = 120,
                syncDirty = false,
                baseVersion = "v-other-record",
            ),
        )
        rig.carePlans.seed(
            localReplicaCarePlan("plan-cross-baby", "membership-a", 140).copy(
                babyId = planBabyId,
                status = "completed",
                fulfilledRecordClientUuid = recordUuid,
                fulfilledAt = 130,
                syncDirty = true,
                baseVersion = "v-plan",
            ),
        )

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(rig.backend.causalCommittedUnits).isEmpty()
        assertThat(rig.backend.causalReconciledUnits).isEmpty()
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(45)
        with(requireNotNull(rig.carePlans.getByClientUuid("plan-cross-baby"))) {
            assertThat(syncDirty).isTrue()
            assertThat(mutationId).isNull()
        }
        assertThat(rig.conflictDetails.getFrozenMutation("care_plan", "plan-cross-baby"))
            .isNull()
    }

    @Test
    fun eligibleCarePlanLiveAndTombstoneCommitWithoutReconcilePullOrCursorAdvance() = runTest {
        for (deleted in listOf(false, true)) {
            val (session, rig, uuid) = seedDirtyCarePlan(
                pullCursor = 46,
                clientUuid = "plan-direct-$deleted",
                deleted = deleted,
            )

            rig.engine.synchronize(session, SyncTrigger.LocalWrite)

            assertThat(rig.backend.pullCount).isEqualTo(0)
            assertThat(rig.preferences.current().pullCursor).isEqualTo(46)
            assertThat(rig.backend.causalReconciledUnits).isEmpty()
            val committed = rig.backend.causalCommittedUnits.single().single()
            assertThat(committed.entityType).isEqualTo("care_plan")
            assertThat(committed.clientUuid).isEqualTo(uuid)
            assertThat(committed.deleted).isEqualTo(deleted)
            assertThat(committed.media).isEmpty()
            with(requireNotNull(rig.carePlans.getByClientUuid(uuid))) {
                assertThat(syncDirty).isFalse()
                assertThat(mutationId).isNull()
                assertThat(baseVersion).isNotNull()
            }
        }
    }

    @Test
    fun dirtyCarePlanDependenciesFreezeAndCommitInProviderFactPlanOrder() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 47)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
                clientUuid = "00000000-0000-4000-8000-000000000220",
                syncDirty = true,
                familyAuthority = true,
                updatedAt = 100,
            ),
        )
        val customId = rig.customItems.seed(
            localReplicaCustomItem(
                "00000000-0000-4000-8000-000000000221",
                "membership-a",
                110,
            ).copy(syncDirty = true),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = "00000000-0000-4000-8000-000000000222",
                babyId = babyId,
                type = "formula",
                timestamp = 120,
                payloadJson = """{"amount_ml":90}""",
                schemaVersion = 2,
                updatedAt = 120,
                syncDirty = true,
            ),
        )
        rig.carePlans.seed(
            localReplicaCarePlan("plan-with-dependencies", "membership-a", 130).copy(
                babyId = babyId,
                type = "custom",
                customItemId = customId,
                payloadJson =
                    """{"title":"体操","detail":"十分钟","custom_item_id":$customId,"icon_slot":2}""",
                status = "completed",
                fulfilledRecordClientUuid = "00000000-0000-4000-8000-000000000222",
                fulfilledAt = 125,
                syncDirty = true,
            ),
        )

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(rig.backend.causalCommittedUnits.single().map { it.entityType })
            .containsExactly("baby", "custom_item", "record", "care_plan")
            .inOrder()
        assertThat(rig.backend.causalReconciledUnits).isEmpty()
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(47)
        assertThat(
            requireNotNull(
                rig.babies.getByClientUuid("00000000-0000-4000-8000-000000000220"),
            ).syncDirty,
        ).isFalse()
        assertThat(
            requireNotNull(
                rig.customItems.get("00000000-0000-4000-8000-000000000221"),
            ).syncDirty,
        ).isFalse()
        assertThat(
            requireNotNull(
                rig.records.getByClientUuid("00000000-0000-4000-8000-000000000222"),
            ).syncDirty,
        ).isFalse()
        with(requireNotNull(rig.carePlans.getByClientUuid("plan-with-dependencies"))) {
            assertThat(syncDirty).isFalse()
            assertThat(status).isEqualTo("completed")
            assertThat(fulfilledRecordClientUuid)
                .isEqualTo("00000000-0000-4000-8000-000000000222")
        }
    }

    @Test
    fun lostCarePlanResponseReplaysFrozenEpochBeforeReplanningLaterEdit() = runTest {
        val (session, rig, uuid) = seedDirtyCarePlan(
            pullCursor = 48,
            clientUuid = "plan-response-lost",
        )
        var frozen: com.lezi.babylog.sync.backend.CausalMutationUnit? = null
        rig.backend.onCausalCommit = { units ->
            frozen = units.single()
            throw java.io.IOException("care plan response lost")
        }
        assertThat(
            runCatching { rig.engine.synchronize(session, SyncTrigger.LocalWrite) }.exceptionOrNull(),
        ).isInstanceOf(java.io.IOException::class.java)
        val firstEnvelope = requireNotNull(frozen)
        val firstRow = requireNotNull(rig.carePlans.getByClientUuid(uuid))
        rig.carePlans.update(
            firstRow.copy(note = "epoch-2", updatedAt = 200, syncDirty = true, mutationId = null),
        )

        rig.backend.onCausalCommit = { units ->
            val replay = units.single()
            assertThat(replay).isEqualTo(firstEnvelope)
            rig.backend.nextCausalCommit = CausalBatchResult(
                generation = session.pullGeneration,
                cursor = session.pullCursor,
                results = listOf(
                    CausalUnitResult(
                        status = CausalCommitStatus.ACCEPTED,
                        mutationId = replay.mutationId,
                        requestHash = causalMutationContentHash(replay),
                        generation = session.pullGeneration,
                        stableVersionId = "v-plan-epoch-1",
                        stableRootJson = replay.rootJson,
                        stableMedia = emptyList(),
                    ),
                ),
            )
        }
        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        with(requireNotNull(rig.carePlans.getByClientUuid(uuid))) {
            assertThat(note).isEqualTo("epoch-2")
            assertThat(updatedAt).isEqualTo(200)
            assertThat(syncDirty).isTrue()
            assertThat(mutationId).isNull()
            assertThat(baseVersion).isEqualTo("v-plan-epoch-1")
        }
        assertThat(rig.conflictDetails.getFrozenMutation("care_plan", uuid)).isNull()

        rig.backend.onCausalCommit = null
        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        val replanned = rig.backend.causalCommittedUnits.last().single()
        assertThat(replanned.mutationId).isNotEqualTo(firstEnvelope.mutationId)
        assertThat(replanned.baseVersion).isEqualTo("v-plan-epoch-1")
        assertThat(replanned.rootJson).contains("epoch-2")
        assertThat(requireNotNull(rig.carePlans.getByClientUuid(uuid)).syncDirty).isFalse()
        assertThat(rig.backend.causalReconciledUnits).isEmpty()
    }

    @Test
    fun branchedFrozenCarePlanPreservesNewerEditAndDeviceLocalRelations() = runTest {
        val (session, rig, uuid) = seedDirtyCarePlan(
            pullCursor = 49,
            clientUuid = "plan-branch-newer",
        )
        var firstEnvelope: com.lezi.babylog.sync.backend.CausalMutationUnit? = null
        rig.backend.onCausalCommit = { units ->
            firstEnvelope = units.single()
            throw java.io.IOException("care plan branch response lost")
        }
        runCatching { rig.engine.synchronize(session, SyncTrigger.LocalWrite) }
        val frozen = requireNotNull(firstEnvelope)
        val firstRow = requireNotNull(rig.carePlans.getByClientUuid(uuid))
        rig.carePlans.update(
            firstRow.copy(
                note = "newer-local-plan",
                sourceRecordClientUuid = "device-local-source",
                systemCalendarEventId = "device-local-calendar",
                updatedAt = 210,
                syncDirty = true,
                mutationId = null,
            ),
        )
        rig.backend.onCausalCommit = { units ->
            val replay = units.single()
            assertThat(replay).isEqualTo(frozen)
            rig.backend.nextCausalCommit = CausalBatchResult(
                generation = session.pullGeneration,
                cursor = session.pullCursor,
                results = listOf(
                    CausalUnitResult(
                        status = CausalCommitStatus.BRANCHED,
                        mutationId = replay.mutationId,
                        requestHash = causalMutationContentHash(replay),
                        generation = session.pullGeneration,
                        stableVersionId = "v-plan-stable",
                        stableRootJson = replay.rootJson,
                        stableMedia = emptyList(),
                        branchVersionId = "v-plan-branch",
                        conflictId = "conflict-plan",
                    ),
                ),
            )
        }

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        with(requireNotNull(rig.carePlans.getByClientUuid(uuid))) {
            assertThat(note).isEqualTo("newer-local-plan")
            assertThat(sourceRecordClientUuid).isEqualTo("device-local-source")
            assertThat(systemCalendarEventId).isEqualTo("device-local-calendar")
            assertThat(updatedAt).isEqualTo(210)
            assertThat(syncDirty).isTrue()
            assertThat(mutationId).isNull()
            assertThat(baseVersion).isEqualTo("v-plan-stable")
            assertThat(openConflictId).isEqualTo("conflict-plan")
            assertThat(localBranchVersionId).isEqualTo("v-plan-branch")
        }
        assertThat(rig.conflictDetails.getFrozenMutation("care_plan", uuid)).isNull()
    }

    @Test
    fun carePlanContentDriftRetainsFrozenEnvelopeAndAttachmentRetainsSourcePath() = runTest {
        val (session, rig, uuid) = seedDirtyCarePlan(
            pullCursor = 50,
            clientUuid = "plan-content-drift",
        )
        rig.backend.onCausalCommit = { units ->
            val unit = units.single()
            rig.backend.nextCausalCommit = CausalBatchResult(
                generation = session.pullGeneration,
                cursor = session.pullCursor,
                results = listOf(
                    CausalUnitResult(
                        status = CausalCommitStatus.REJECTED,
                        mutationId = unit.mutationId,
                        requestHash = causalMutationContentHash(unit),
                        generation = session.pullGeneration,
                        code = "content_drift",
                    ),
                ),
            )
        }
        val failure = runCatching {
            rig.engine.synchronize(session, SyncTrigger.LocalWrite)
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().contains("content_drift")
        assertThat(rig.conflictDetails.getFrozenMutation("care_plan", uuid)).isNotNull()
        assertThat(requireNotNull(rig.carePlans.getByClientUuid(uuid)).syncDirty).isTrue()
        assertThat(rig.backend.causalReconciledUnits).isEmpty()

        val mediaSession = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 51)
        val mediaRig = ReplicaEngineRig(mediaSession).also { it.backend.enableCausal = true }
        val babyId = mediaRig.babies.seed(
            localReplicaBaby().copy(
                clientUuid = "00000000-0000-4000-8000-000000000250",
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        val planId = mediaRig.carePlans.seed(
            localReplicaCarePlan("plan-with-attachment", "membership-a", 160).copy(
                babyId = babyId,
                syncDirty = true,
            ),
        )
        mediaRig.media.seed(
            MediaAssetEntity(
                clientUuid = "00000000-0000-4000-8000-000000000251",
                kind = "log",
                carePlanId = planId,
                localUri = "plans/attachment.jpg",
                mime = "image/jpeg",
                createdAt = 160,
                updatedAt = 160,
                syncDirty = true,
            ),
        )

        mediaRig.engine.synchronize(mediaSession, SyncTrigger.LocalWrite)

        assertThat(mediaRig.backend.pullCount).isEqualTo(0)
        assertThat(mediaRig.preferences.current().pullCursor).isEqualTo(51)
        val reconciled = mediaRig.backend.causalReconciledUnits.single().single()
        assertThat(reconciled.entityType).isEqualTo("care_plan")
        assertThat(reconciled.media.map { it.mediaUuid })
            .containsExactly("00000000-0000-4000-8000-000000000251")
        assertThat(reconciled.media.single().role).isEqualTo("plan")
        val committed = mediaRig.backend.causalCommittedUnits.single().single()
        assertThat(committed.entityType).isEqualTo("care_plan")
        assertThat(committed.media).isEqualTo(reconciled.media)
        assertThat(mediaRig.backend.syncOrder.filter { it.startsWith("causal_") })
            .containsExactly(
                "causal_reconcile:1",
                "causal_media_preimage:00000000-0000-4000-8000-000000000251",
                "causal_commit:1",
            )
            .inOrder()
        assertThat(
            requireNotNull(mediaRig.carePlans.getByClientUuid("plan-with-attachment")).syncDirty,
        ).isFalse()
        assertThat(
            requireNotNull(
                mediaRig.media.getByClientUuid("00000000-0000-4000-8000-000000000251"),
            ).syncDirty,
        ).isFalse()
        assertThat(mediaRig.conflictDetails.getFrozenMutation("care_plan", "plan-with-attachment"))
            .isNull()
    }

    @Test
    fun currentEpochCarePlanTerminalProofInvalidatesStaleCalendarProjection() = runTest {
        data class Case(
            val name: String,
            val status: String,
            val deleted: Boolean,
            val branched: Boolean,
        )
        val cases = listOf(
            Case("merged-visible-revision", CausalCommitStatus.MERGED, false, false),
            Case("branched-visible-revision", CausalCommitStatus.BRANCHED, false, true),
            Case("accepted-tombstone", CausalCommitStatus.ACCEPTED, true, false),
        )
        for ((index, case) in cases.withIndex()) {
            val (session, rig, uuid) = seedDirtyCarePlan(
                pullCursor = 60L + index,
                clientUuid = "plan-calendar-${case.name}",
                deleted = case.deleted,
            )
            val seeded = requireNotNull(rig.carePlans.getByClientUuid(uuid))
            rig.carePlans.update(
                seeded.copy(
                    sourceRecordClientUuid = "device-local-source-${case.name}",
                    systemCalendarProjectionEnabled = true,
                    systemCalendarEventId = "calendar-event-${case.name}",
                    systemCalendarReminderReady = true,
                    systemCalendarProjectionPending = false,
                ),
            )
            rig.backend.onCausalCommit = { units ->
                val unit = units.single()
                val stableRoot = if (case.deleted) {
                    unit.rootJson
                } else {
                    unit.rootJson.replace("\"note\":\"epoch-1\"", "\"note\":\"stable-${case.name}\"")
                }
                rig.backend.nextCausalCommit = CausalBatchResult(
                    generation = session.pullGeneration,
                    cursor = session.pullCursor,
                    results = listOf(
                        CausalUnitResult(
                            status = case.status,
                            mutationId = unit.mutationId,
                            requestHash = causalMutationContentHash(unit),
                            generation = session.pullGeneration,
                            stableVersionId = "v-calendar-${case.name}",
                            stableRootJson = stableRoot,
                            stableMedia = emptyList(),
                            branchVersionId = if (case.branched) {
                                "branch-calendar-${case.name}"
                            } else {
                                null
                            },
                            conflictId = if (case.branched) {
                                "conflict-calendar-${case.name}"
                            } else {
                                null
                            },
                        ),
                    ),
                )
            }

            rig.engine.synchronize(session, SyncTrigger.LocalWrite)

            with(requireNotNull(rig.carePlans.getByClientUuid(uuid))) {
                assertThat(note).isEqualTo(
                    if (case.deleted) "epoch-1" else "stable-${case.name}",
                )
                assertThat(sourceRecordClientUuid)
                    .isEqualTo("device-local-source-${case.name}")
                assertThat(systemCalendarProjectionEnabled).isTrue()
                assertThat(systemCalendarEventId).isEqualTo("calendar-event-${case.name}")
                assertThat(systemCalendarReminderReady).isFalse()
                assertThat(systemCalendarProjectionPending).isTrue()
                assertThat(syncDirty).isFalse()
                if (case.branched) {
                    assertThat(mutationId).isNotNull()
                    assertThat(localBranchVersionId).isEqualTo("branch-calendar-${case.name}")
                } else {
                    assertThat(mutationId).isNull()
                    assertThat(localBranchVersionId).isNull()
                }
                assertThat(baseVersion).isEqualTo("v-calendar-${case.name}")
                assertThat(openConflictId).isEqualTo(
                    if (case.branched) "conflict-calendar-${case.name}" else null,
                )
                assertThat(deletedAt != null).isEqualTo(case.deleted)
            }
            assertThat(rig.conflictDetails.getFrozenMutation("care_plan", uuid)).isNull()
            assertThat(rig.backend.causalReconciledUnits).isEmpty()
            assertThat(rig.backend.pullCount).isEqualTo(0)
            assertThat(rig.preferences.current().pullCursor).isEqualTo(60L + index)
        }
    }

    @Test
    fun invalidCarePlanTerminalReferencesFailBeforeSettlementAndRetainFrozenProof() = runTest {
        data class Case(
            val name: String,
            val corrupt: (String) -> String,
        )
        val cases = listOf(
            Case("noncanonical-baby") { root ->
                root.replace(
                    "00000000-0000-4000-8000-000000000241",
                    "baby-not-canonical",
                )
            },
            Case("wrong-type-reference") { root ->
                root.replace("\"type\":\"formula\"", "\"type\":\"custom\"")
            },
            Case("partial-fulfillment") { root ->
                root.replace("\"status\":\"pending\"", "\"status\":\"completed\"")
                    .replace(
                        "\"fulfilled_record_client_uuid\":null",
                        "\"fulfilled_record_client_uuid\":\"00000000-0000-4000-8000-000000000252\"",
                    )
            },
        )
        for ((index, case) in cases.withIndex()) {
            val (session, rig, uuid) = seedDirtyCarePlan(
                pullCursor = 52L + index,
                clientUuid = "plan-invalid-${case.name}",
            )
            rig.backend.onCausalCommit = { units ->
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
                            stableVersionId = "v-invalid-${case.name}",
                            stableRootJson = case.corrupt(unit.rootJson),
                            stableMedia = emptyList(),
                        ),
                    ),
                )
            }

            val failure = runCatching {
                rig.engine.synchronize(session, SyncTrigger.LocalWrite)
            }.exceptionOrNull()

            assertThat(failure).isNotNull()
            with(requireNotNull(rig.carePlans.getByClientUuid(uuid))) {
                assertThat(note).isEqualTo("epoch-1")
                assertThat(syncDirty).isTrue()
                assertThat(mutationId).isNotNull()
                assertThat(baseVersion).isEqualTo("v-plan-base")
                assertThat(openConflictId).isNull()
            }
            assertThat(rig.conflictDetails.getFrozenMutation("care_plan", uuid)).isNotNull()
            assertThat(rig.backend.causalReconciledUnits).isEmpty()
            assertThat(rig.backend.pullCount).isEqualTo(0)
            assertThat(rig.preferences.current().pullCursor).isEqualTo(52L + index)
        }
    }

    @Test
    fun babyWithActiveAvatarRetainsSourceReconcileUntilMediaMigration() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 45)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
                clientUuid = "baby-avatar-provider",
                syncDirty = true,
                familyAuthority = true,
                baseVersion = "v-baby-avatar",
                updatedAt = 140,
                avatarMediaUuid = "00000000-0000-4000-8000-000000000245",
                avatarPath = "avatars/provider.jpg",
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = "00000000-0000-4000-8000-000000000245",
                kind = "avatar",
                babyId = babyId,
                localUri = "avatars/provider.jpg",
                mime = "image/jpeg",
                createdAt = 140,
                updatedAt = 140,
                syncDirty = true,
            ),
        )

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(45)
        assertThat(rig.backend.causalReconciledUnits.single().single().entityType)
            .isEqualTo("baby")
        assertThat(rig.backend.causalReconciledUnits.single().single().media).hasSize(1)
        assertThat(rig.backend.syncOrder.filter { it.startsWith("causal_") })
            .containsExactly(
                "causal_reconcile:1",
                "causal_media_preimage:00000000-0000-4000-8000-000000000245",
                "causal_commit:1",
            )
            .inOrder()
    }

    @Test
    fun deletedBabyWithTombstonedOrOrphanAvatarUsesSourceRepairWithoutEnvelope() = runTest {
        data class Case(
            val name: String,
            val mediaUuid: String,
            val deletedAt: Long?,
            val initiallyDirty: Boolean,
        )
        val cases = listOf(
            Case(
                name = "dirty-tombstone",
                mediaUuid = "00000000-0000-4000-8000-000000000246",
                deletedAt = 139,
                initiallyDirty = true,
            ),
            Case(
                name = "pre-fix-live-orphan",
                mediaUuid = "00000000-0000-4000-8000-000000000247",
                deletedAt = null,
                initiallyDirty = false,
            ),
        )
        for (case in cases) {
            val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 47)
            val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
            val babyUuid = "baby-deleted-${case.name}"
            val babyId = rig.babies.seed(
                localReplicaBaby().copy(
                    clientUuid = babyUuid,
                    syncDirty = true,
                    familyAuthority = true,
                    baseVersion = "v-baby-deleted",
                    updatedAt = 140,
                    deletedAt = 140,
                    avatarMediaUuid = if (case.deletedAt == null) case.mediaUuid else null,
                    avatarPath = null,
                ),
            )
            rig.media.seed(
                MediaAssetEntity(
                    clientUuid = case.mediaUuid,
                    kind = "avatar",
                    babyId = babyId,
                    localUri = "avatars/${case.name}.jpg",
                    mime = "image/jpeg",
                    createdAt = 100,
                    updatedAt = 139,
                    deletedAt = case.deletedAt,
                    syncDirty = case.initiallyDirty,
                ),
            )
            rig.backend.onCausalReconcile = { units ->
                assertThat(units.map { it.entityType }).containsExactly("baby")
                assertThat(units.single().media).isEmpty()
                assertThat(rig.conflictDetails.getFrozenMutation("baby", babyUuid)).isNull()
            }

            rig.engine.synchronize(session, SyncTrigger.LocalWrite)

            assertThat(rig.backend.pullCount).isEqualTo(0)
            assertThat(rig.preferences.current().pullCursor).isEqualTo(47)
            assertThat(rig.backend.causalReconciledUnits).hasSize(1)
            assertThat(rig.backend.syncOrder.filter { it.startsWith("causal_") })
                .containsExactly("causal_reconcile:1", "causal_commit:1")
                .inOrder()
            assertThat(rig.conflictDetails.getFrozenMutation("baby", babyUuid)).isNull()
            with(requireNotNull(rig.media.getByClientUuid(case.mediaUuid))) {
                assertThat(deletedAt).isNotNull()
                assertThat(syncDirty).isFalse()
            }
            with(requireNotNull(rig.babies.getByClientUuid(babyUuid))) {
                assertThat(deletedAt).isEqualTo(140)
                assertThat(syncDirty).isFalse()
            }
        }
    }

    @Test
    fun localWritePlanDeclaresPushWithoutPull() {
        assertThat(SyncPlan.forTrigger(SyncTrigger.LocalWrite))
            .isEqualTo(SyncPlan(push = true, pull = false))
        assertThat(SyncPlan.forTrigger(SyncTrigger.Foreground).pull).isTrue()
        assertThat(SyncPlan.forTrigger(SyncTrigger.PullToRefresh).pull).isTrue()
    }

    @Test
    fun localWriteNoMediaRecordCommitsWithoutReconcilePullOrCursorAdvance() = runTest {
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
            .containsExactly("causal_commit:1")
        assertThat(rig.preferences.current().pullCursor).isEqualTo(7)
        assertThat(rig.records.getByClientUuid("record-peer-unrelated")).isNull()
        val settled = requireNotNull(rig.records.getByClientUuid(recordUuid))
        assertThat(settled.syncDirty).isFalse()
        assertThat(settled.mutationId).isNull()
        assertThat(rig.backend.causalReconciledUnits).isEmpty()
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
    fun lostCommitResponseReplaysFrozenEnvelopeBeforeFreezingNewerRecordEpoch() = runTest {
        val (session, rig, uuid) = seedDirtyRecord(
            pullCursor = 12,
            clientUuid = "record-durable-replay",
            note = "epoch-1",
        )
        var firstEnvelope: com.lezi.babylog.sync.backend.CausalMutationUnit? = null
        rig.backend.onCausalCommit = { units ->
            firstEnvelope = units.single()
            throw java.io.IOException("response lost after durable commit")
        }

        assertThat(
            runCatching {
                rig.engine.synchronize(session, SyncTrigger.LocalWrite)
            }.exceptionOrNull(),
        ).isInstanceOf(java.io.IOException::class.java)
        val frozen = requireNotNull(firstEnvelope)
        assertThat(
            rig.conflictDetails.getFrozenMutation("record", uuid),
        ).isNotNull()

        val beforeEdit = requireNotNull(rig.records.getByClientUuid(uuid))
        rig.records.update(
            beforeEdit.copy(
                note = "epoch-2",
                updatedAt = 200,
                syncDirty = true,
            ),
        )
        rig.backend.onCausalCommit = { units ->
            val replay = units.single()
            assertThat(replay).isEqualTo(frozen)
            rig.backend.nextCausalCommit = CausalBatchResult(
                generation = session.pullGeneration,
                cursor = 999,
                results = listOf(
                    CausalUnitResult(
                        status = CausalCommitStatus.ACCEPTED,
                        mutationId = replay.mutationId,
                        requestHash = causalMutationContentHash(replay),
                        generation = session.pullGeneration,
                        stableVersionId = "v-epoch-1",
                        stableRootJson = replay.rootJson,
                        stableMedia = replay.media,
                    ),
                ),
            )
        }

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        val afterReplay = requireNotNull(rig.records.getByClientUuid(uuid))
        assertThat(afterReplay.note).isEqualTo("epoch-2")
        assertThat(afterReplay.updatedAt).isEqualTo(200)
        assertThat(afterReplay.syncDirty).isTrue()
        assertThat(afterReplay.mutationId).isNull()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(12)
        assertThat(rig.conflictDetails.getFrozenMutation("record", uuid)).isNull()

        rig.backend.onCausalCommit = null
        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        val nextEnvelope = rig.backend.causalCommittedUnits.last().single()
        assertThat(nextEnvelope.mutationId).isNotEqualTo(frozen.mutationId)
        assertThat(nextEnvelope.baseVersion).isEqualTo("v-epoch-1")
        assertThat(nextEnvelope.rootJson).contains("\"note\":\"epoch-2\"")
        val settled = requireNotNull(rig.records.getByClientUuid(uuid))
        assertThat(settled.syncDirty).isFalse()
        assertThat(settled.note).isEqualTo("epoch-2")
        assertThat(rig.preferences.current().pullCursor).isEqualTo(12)
    }

    @Test
    fun contentDriftRejectRetainsFrozenRecordEnvelopeForExactRetry() = runTest {
        val (session, rig, uuid) = seedDirtyRecord(
            pullCursor = 13,
            clientUuid = "record-content-drift",
        )
        var rejected: com.lezi.babylog.sync.backend.CausalMutationUnit? = null
        rig.backend.onCausalCommit = { units ->
            val unit = units.single()
            rejected = unit
            rig.backend.nextCausalCommit = CausalBatchResult(
                generation = session.pullGeneration,
                cursor = session.pullCursor,
                results = listOf(
                    CausalUnitResult(
                        status = CausalCommitStatus.REJECTED,
                        mutationId = unit.mutationId,
                        requestHash = causalMutationContentHash(unit),
                        generation = session.pullGeneration,
                        code = "content_drift",
                        reason = "mutation id reused with different canonical content",
                    ),
                ),
            )
        }
        val failure = runCatching {
            rig.engine.synchronize(session, SyncTrigger.LocalWrite)
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().contains("content_drift")

        val row = requireNotNull(rig.records.getByClientUuid(uuid))
        assertThat(row.syncDirty).isTrue()
        assertThat(row.mutationId).isEqualTo(requireNotNull(rejected).mutationId)
        assertThat(rig.conflictDetails.getFrozenMutation("record", uuid)).isNotNull()
        assertThat(rig.backend.causalReconciledUnits).isEmpty()
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(13)
    }

    @Test
    fun localWriteCoBatchedSleepCommitsBeforeWakeSourceReconcile() = runTest {
        // H10 owns only the no-media Record. The dependent Wake stays on the
        // source reconcile path, but sees the accepted sleep in the same cycle.
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

        rig.backend.onCausalReconcile = { units ->
            assertThat(units.map { it.entityType }).containsExactly("wake_observation")
            val results = units.map { unit ->
                CausalUnitResult(
                    status = CausalReconcileStatus.PUBLISH,
                    mutationId = unit.mutationId,
                    requestHash = causalMutationContentHash(unit),
                    generation = session.pullGeneration,
                )
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
        assertThat(rig.backend.syncOrder.filter { it.startsWith("causal_") })
            .containsExactly("causal_commit:1", "causal_reconcile:1", "causal_commit:1")
            .inOrder()
        assertThat(rig.backend.causalReconciledUnits).hasSize(1)
        assertThat(rig.backend.causalCommittedUnits).hasSize(2)
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
        rig.backend.onCausalCommit = {
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
        assertThat(rig.backend.causalCommittedUnits).hasSize(1)
    }

    @Test
    fun localWriteCancellationRetainsDirtyWithoutCursorAdvance() = runTest {
        val (session, rig, uuid) = seedDirtyRecord(
            pullCursor = 8,
            clientUuid = "record-cancel",
        )
        val gate = CompletableDeferred<Unit>()
        rig.backend.onCausalCommit = {
            gate.await()
        }
        val job = async {
            rig.engine.synchronize(session, SyncTrigger.LocalWrite)
        }
        // Let commit start then cancel.
        kotlinx.coroutines.yield()
        job.cancel()
        val thrown = runCatching { job.await() }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        gate.cancel()

        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(8)
        assertThat(requireNotNull(rig.records.getByClientUuid(uuid)).syncDirty).isTrue()
        assertThat(rig.backend.causalCommittedUnits).hasSize(1)
    }

    private fun seedDirtyProvider(
        rig: ReplicaEngineRig,
        entityType: String,
        deleted: Boolean,
        updatedAt: Long,
    ): String = when (entityType) {
        "baby" -> {
            val uuid = "baby-provider-$updatedAt-$deleted"
            rig.babies.seed(
                localReplicaBaby().copy(
                    clientUuid = uuid,
                    nickname = "epoch-1-baby",
                    sortOrder = 8,
                    syncDirty = true,
                    familyAuthority = true,
                    updatedAt = updatedAt,
                    deletedAt = if (deleted) updatedAt - 1 else null,
                    baseVersion = "v-provider-base",
                ),
            )
            uuid
        }
        "custom_item" -> {
            val uuid = "custom-provider-$updatedAt-$deleted"
            rig.customItems.seed(
                localReplicaCustomItem(uuid, "membership-a", updatedAt).copy(
                    name = "epoch-1-custom",
                    sortOrder = 9,
                    syncDirty = true,
                    deletedAt = if (deleted) updatedAt - 1 else null,
                    baseVersion = "v-provider-base",
                ),
            )
            uuid
        }
        else -> error(entityType)
    }

    private fun seedDirtyCarePlan(
        pullCursor: Long,
        clientUuid: String,
        deleted: Boolean = false,
    ): Triple<com.lezi.babylog.sync.session.SyncSession, ReplicaEngineRig, String> {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = pullCursor)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
                clientUuid = "00000000-0000-4000-8000-000000000241",
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        rig.carePlans.seed(
            localReplicaCarePlan(clientUuid, "membership-a", updatedAt = 150).copy(
                babyId = babyId,
                note = "epoch-1",
                syncDirty = true,
                deletedAt = if (deleted) 149 else null,
                baseVersion = "v-plan-base",
            ),
        )
        return Triple(session, rig, clientUuid)
    }

    private suspend fun applyLaterProviderEdit(
        rig: ReplicaEngineRig,
        entityType: String,
        clientUuid: String,
        updatedAt: Long,
    ) {
        when (entityType) {
            "baby" -> {
                val current = requireNotNull(rig.babies.getByClientUuid(clientUuid))
                rig.babies.update(
                    current.copy(
                        nickname = "epoch-2-baby",
                        sortOrder = 18,
                        updatedAt = updatedAt,
                        syncDirty = true,
                    ),
                )
            }
            "custom_item" -> {
                val current = requireNotNull(rig.customItems.get(clientUuid))
                rig.customItems.update(
                    current.copy(
                        name = "epoch-2-custom",
                        sortOrder = 19,
                        updatedAt = updatedAt,
                        syncDirty = true,
                    ),
                )
            }
            else -> error(entityType)
        }
    }

    private suspend fun assertProviderLaterEditPending(
        rig: ReplicaEngineRig,
        entityType: String,
        clientUuid: String,
        expectedBase: String,
    ) {
        when (entityType) {
            "baby" -> with(requireNotNull(rig.babies.getByClientUuid(clientUuid))) {
                assertThat(nickname).isEqualTo("epoch-2-baby")
                assertThat(sortOrder).isEqualTo(18)
                assertThat(updatedAt).isEqualTo(220)
                assertThat(syncDirty).isTrue()
                assertThat(mutationId).isNull()
                assertThat(baseVersion).isEqualTo(expectedBase)
            }
            "custom_item" -> with(requireNotNull(rig.customItems.get(clientUuid))) {
                assertThat(name).isEqualTo("epoch-2-custom")
                assertThat(sortOrder).isEqualTo(19)
                assertThat(updatedAt).isEqualTo(220)
                assertThat(syncDirty).isTrue()
                assertThat(mutationId).isNull()
                assertThat(baseVersion).isEqualTo(expectedBase)
            }
            else -> error(entityType)
        }
    }

    private suspend fun assertProviderPendingMutation(
        rig: ReplicaEngineRig,
        entityType: String,
        clientUuid: String,
        expectedMutationId: String,
    ) {
        when (entityType) {
            "baby" -> with(requireNotNull(rig.babies.getByClientUuid(clientUuid))) {
                assertThat(syncDirty).isTrue()
                assertThat(mutationId).isEqualTo(expectedMutationId)
            }
            "custom_item" -> with(requireNotNull(rig.customItems.get(clientUuid))) {
                assertThat(syncDirty).isTrue()
                assertThat(mutationId).isEqualTo(expectedMutationId)
            }
            else -> error(entityType)
        }
    }

    private suspend fun assertProviderSettled(
        rig: ReplicaEngineRig,
        entityType: String,
        clientUuid: String,
    ) {
        when (entityType) {
            "baby" -> with(requireNotNull(rig.babies.getByClientUuid(clientUuid))) {
                assertThat(syncDirty).isFalse()
                assertThat(mutationId).isNull()
                assertThat(sortOrder).isAnyOf(8, 18)
            }
            "custom_item" -> with(requireNotNull(rig.customItems.get(clientUuid))) {
                assertThat(syncDirty).isFalse()
                assertThat(mutationId).isNull()
                assertThat(sortOrder).isAnyOf(9, 19)
            }
            else -> error(entityType)
        }
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
