package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.FulfillmentCandidateEntity
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.fulfillment.FulfillmentAuthoritySettlement
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.sync.SyncPlan
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.CausalCommitBatchResult
import com.lezi.babylog.sync.backend.CausalCommitStatus
import com.lezi.babylog.sync.backend.CausalCommitRejectedException
import com.lezi.babylog.sync.backend.CausalCommitUnitResult
import com.lezi.babylog.sync.backend.LiveCensus
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.core.common.failure.FailureKind
import com.lezi.babylog.core.common.failure.failureExplanation
import com.lezi.babylog.sync.session.ReplicaSyncNotConvergedException
import com.lezi.babylog.sync.session.familyFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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
                ).also { it.backend.enableCausal = true }
                val clientUuid = seedDirtyProvider(rig, entityType, deleted, updatedAt = 100)
                rig.backend.nextPull = PullResult(
                    entities = listOf(remoteReplicaRecord("must-not-pull-$entityType-$deleted")),
                    cursor = session.pullCursor,
                    generation = session.pullGeneration,
                    hasMore = false,
                )

                rig.engine.synchronize(session, SyncTrigger.LocalWrite)

                assertThat(rig.backend.pullCount).isEqualTo(0)
                assertThat(rig.preferences.current().pullCursor).isEqualTo(41)
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
                    rig.backend.nextCausalCommit = CausalCommitBatchResult(
                        generation = session.pullGeneration,
                        results = listOf(
                            CausalCommitUnitResult(
                                status = case.status,
                                mutationId = unit.mutationId,
                                requestHash = causalMutationContentHash(unit),
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
                rig.backend.nextCausalCommit = CausalCommitBatchResult(
                    generation = session.pullGeneration,
                    results = listOf(
                        CausalCommitUnitResult(
                            status = CausalCommitStatus.ACCEPTED,
                            mutationId = replay.mutationId,
                            requestHash = causalMutationContentHash(replay),
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
                rig.backend.nextCausalCommitFailure = CausalCommitRejectedException(
                    mutationId = unit.mutationId,
                    code = "content_drift",
                )
            }

            val failure = runCatching {
                rig.engine.synchronize(session, SyncTrigger.LocalWrite)
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            assertThat(failure).hasMessageThat().contains("content_drift")
            assertProviderPendingMutation(rig, entityType, uuid, requireNotNull(rejected).mutationId)
            assertThat(rig.conflictDetails.getFrozenMutation(entityType, uuid)).isNotNull()
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
    fun foregroundCycleWithUnpublishableDirtyDoesNotLookSynchronized() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 45)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
                clientUuid = "00000000-0000-4000-8000-000000000311",
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        rig.carePlans.seed(
            localReplicaCarePlan("plan-still-pending", "membership-a", updatedAt = 140).copy(
                babyId = babyId,
                status = "completed",
                fulfilledRecordClientUuid = "00000000-0000-4000-8000-000000000312",
                fulfilledAt = 130,
                syncDirty = true,
                baseVersion = "v-plan",
            ),
        )
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 45,
            generation = "generation-a",
            hasMore = false,
        )

        val failure = runCatching {
            rig.engine.synchronize(session, SyncTrigger.PullToRefresh)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ReplicaSyncNotConvergedException::class.java)
        assertThat(familyFailureKind(failure!!)).isEqualTo(FailureKind.HouseholdStateChanged)
        assertThat(failureExplanation(FailureKind.HouseholdStateChanged).likelyCause)
            .doesNotContain("称呼")
        with(requireNotNull(rig.carePlans.getByClientUuid("plan-still-pending"))) {
            assertThat(syncDirty).isTrue()
        }
        assertThat(rig.backend.causalCommittedUnits).isEmpty()
    }

    @Test
    fun carePlanAttachmentWaitsForFulfilledRecordBeforePrepareOrCommit() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 45)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
                clientUuid = "00000000-0000-4000-8000-000000000216",
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        val planUuid = "plan-media-missing-record"
        val planId = rig.carePlans.seed(
            localReplicaCarePlan(planUuid, "membership-a", updatedAt = 140).copy(
                babyId = babyId,
                status = "completed",
                fulfilledRecordClientUuid = "00000000-0000-4000-8000-000000000217",
                fulfilledAt = 130,
                syncDirty = true,
                baseVersion = "v-plan",
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = "00000000-0000-4000-8000-000000000218",
                kind = "log",
                carePlanId = planId,
                localUri = "plans/missing-record.jpg",
                mime = "image/jpeg",
                createdAt = 140,
                updatedAt = 140,
                syncDirty = true,
            ),
        )

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(rig.backend.causalMediaPreimageBytes).isEmpty()
        assertThat(rig.backend.causalCommittedUnits).isEmpty()
        assertThat(rig.conflictDetails.listFrozenMediaSpoolManifests()).isEmpty()
        with(requireNotNull(rig.carePlans.getByClientUuid(planUuid))) {
            assertThat(syncDirty).isTrue()
            assertThat(mutationId).isNull()
        }
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
        val planId = rig.carePlans.seed(
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
        val attachmentUuid = "00000000-0000-4000-8000-000000000223"
        val attachmentUri = "/private/plan-with-dependencies.jpg"
        val attachmentBytes = byteArrayOf(2, 2, 3, 5)
        rig.mediaFiles.preparedUploadBytes[attachmentUri] = attachmentBytes
        rig.media.seed(
            MediaAssetEntity(
                carePlanId = planId,
                clientUuid = attachmentUuid,
                kind = "log",
                localUri = attachmentUri,
                createdAt = 130,
                updatedAt = 130,
                syncDirty = true,
            ),
        )

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(rig.backend.causalCommittedUnits.single().map { it.entityType })
            .containsExactly("baby", "custom_item", "record", "care_plan")
            .inOrder()
        assertThat(rig.backend.causalMediaPreimageBytes.map { it.first })
            .containsExactly(attachmentUuid)
        assertThat(rig.backend.causalMediaPreimageBytes.single().second)
            .isEqualTo(attachmentBytes)
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
        assertThat(rig.media.getByClientUuid(attachmentUuid)?.syncDirty).isFalse()
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
            rig.backend.nextCausalCommit = CausalCommitBatchResult(
                generation = session.pullGeneration,
                results = listOf(
                    CausalCommitUnitResult(
                        status = CausalCommitStatus.ACCEPTED,
                        mutationId = replay.mutationId,
                        requestHash = causalMutationContentHash(replay),
                        stableVersionId = "v-plan-epoch-1",
                        stableRootJson = serverStampedStableRootJson(
                            replay.entityType,
                            replay.rootJson,
                            session.membershipId,
                        ),
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
            rig.backend.nextCausalCommit = CausalCommitBatchResult(
                generation = session.pullGeneration,
                results = listOf(
                    CausalCommitUnitResult(
                        status = CausalCommitStatus.BRANCHED,
                        mutationId = replay.mutationId,
                        requestHash = causalMutationContentHash(replay),
                        stableVersionId = "v-plan-stable",
                        stableRootJson = serverStampedStableRootJson(
                            replay.entityType,
                            replay.rootJson,
                            session.membershipId,
                        ),
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
    fun carePlanContentDriftRetainsFrozenEnvelope() = runTest {
        val (session, rig, uuid) = seedDirtyCarePlan(
            pullCursor = 50,
            clientUuid = "plan-content-drift",
        )
        rig.backend.onCausalCommit = { units ->
            val unit = units.single()
            rig.backend.nextCausalCommitFailure = CausalCommitRejectedException(
                mutationId = unit.mutationId,
                code = "content_drift",
            )
        }
        val failure = runCatching {
            rig.engine.synchronize(session, SyncTrigger.LocalWrite)
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().contains("content_drift")
        assertThat(rig.conflictDetails.getFrozenMutation("care_plan", uuid)).isNotNull()
        assertThat(requireNotNull(rig.carePlans.getByClientUuid(uuid)).syncDirty).isTrue()
    }

    @Test
    fun carePlanAttachmentPreparesReceiptThenCommitsWithoutReconcile() = runTest {
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
        val committed = mediaRig.backend.causalCommittedUnits.single().single()
        assertThat(committed.entityType).isEqualTo("care_plan")
        assertThat(committed.media.map { it.mediaUuid })
            .containsExactly("00000000-0000-4000-8000-000000000251")
        assertThat(committed.media.single().role).isEqualTo("plan")
        assertThat(mediaRig.backend.syncOrder.filter { it.startsWith("causal_") })
            .containsExactly(
                "causal_media_preimage_started:00000000-0000-4000-8000-000000000251",
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
            val sourceUuid = "00000000-0000-4000-8000-0000000003%02d".format(index)
            rig.carePlans.update(
                seeded.copy(
                    sourceRecordClientUuid = sourceUuid,
                    systemCalendarProjectionEnabled = true,
                    systemCalendarEventId = "calendar-event-${case.name}",
                    systemCalendarReminderReady = true,
                    systemCalendarProjectionPending = false,
                ),
            )
            rig.backend.onCausalCommit = { units ->
                val unit = units.single()
                val stableRoot = serverStampedStableRootJson(
                    unit.entityType,
                    if (case.deleted) {
                        unit.rootJson
                    } else {
                        val root = Json.parseToJsonElement(unit.rootJson).jsonObject
                        buildJsonObject {
                            root.forEach { (key, value) ->
                                if (key == "note") {
                                    put(key, "stable-${case.name}")
                                } else {
                                    put(key, value)
                                }
                            }
                        }.toString()
                    },
                    session.membershipId,
                )
                rig.backend.nextCausalCommit = CausalCommitBatchResult(
                    generation = session.pullGeneration,
                    results = listOf(
                        CausalCommitUnitResult(
                            status = case.status,
                            mutationId = unit.mutationId,
                            requestHash = causalMutationContentHash(unit),
                            stableVersionId = "v-calendar-${case.name}",
                            stableRootJson = stableRoot,
                            stableMedia = emptyList(),
                            stableDeleted = case.deleted,
                            stableDeletedAt = if (case.deleted) seeded.deletedAt else null,
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
                assertThat(sourceRecordClientUuid).isEqualTo(sourceUuid)
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
                rig.backend.nextCausalCommit = CausalCommitBatchResult(
                    generation = session.pullGeneration,
                    results = listOf(
                        CausalCommitUnitResult(
                            status = CausalCommitStatus.ACCEPTED,
                            mutationId = unit.mutationId,
                            requestHash = causalMutationContentHash(unit),
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
            assertThat(rig.backend.pullCount).isEqualTo(0)
            assertThat(rig.preferences.current().pullCursor).isEqualTo(52L + index)
        }
    }

    @Test
    fun ownerBabyWithActiveAvatarPreparesReceiptThenCommitsWithoutReconcile() = runTest {
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
        val avatarBytes = byteArrayOf(7, 5, 3, 1)
        rig.mediaFiles.preparedUploadBytes["avatars/provider.jpg"] = avatarBytes
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
        assertThat(rig.backend.causalCommittedUnits.single().single().entityType)
            .isEqualTo("baby")
        assertThat(rig.backend.causalCommittedUnits.single().single().media).hasSize(1)
        assertThat(rig.backend.causalMediaPreimageBytes.single().second).isEqualTo(avatarBytes)
        assertThat(rig.backend.syncOrder.filter { it.startsWith("causal_") })
            .containsExactly(
                "causal_media_preimage_started:00000000-0000-4000-8000-000000000245",
                "causal_media_preimage:00000000-0000-4000-8000-000000000245",
                "causal_commit:1",
            )
            .inOrder()
    }

    @Test
    fun dirtyAvatarElevatesItsCleanBabyIntoOneSyntheticCommitFirstRoot() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 46)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val avatarUuid = "00000000-0000-4000-8000-000000000248"
        val avatarUri = "avatars/synthetic-root.jpg"
        val avatarBytes = byteArrayOf(1, 4, 9, 16)
        rig.mediaFiles.preparedUploadBytes[avatarUri] = avatarBytes
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
                clientUuid = "baby-synthetic-avatar-root",
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby-synthetic",
                updatedAt = 140,
                avatarMediaUuid = avatarUuid,
                avatarPath = avatarUri,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = avatarUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = avatarUri,
                mime = "image/jpeg",
                createdAt = 140,
                updatedAt = 150,
                syncDirty = true,
            ),
        )

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        val committed = rig.backend.causalCommittedUnits.single().single()
        assertThat(committed.entityType).isEqualTo("baby")
        assertThat(committed.clientUuid).isEqualTo("baby-synthetic-avatar-root")
        assertThat(committed.media.map { it.mediaUuid }).containsExactly(avatarUuid)
        assertThat(rig.backend.causalMediaPreimageBytes.single().second).isEqualTo(avatarBytes)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(46)
        assertThat(rig.babies.getByClientUuid("baby-synthetic-avatar-root")?.syncDirty)
            .isFalse()
        assertThat(rig.media.getByClientUuid(avatarUuid)?.syncDirty).isFalse()
    }

    @Test
    fun deletedBabyWithTombstonedOrOrphanAvatarCommitsFrozenRootWithoutReachableMedia() = runTest {
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
            rig.backend.onCausalCommit = { units ->
                assertThat(units.map { it.entityType }).containsExactly("baby")
                assertThat(units.single().media).isEmpty()
                assertThat(units.single().rootJson).contains("\"avatar_media_uuid\":null")
                assertThat(rig.conflictDetails.getFrozenMutation("baby", babyUuid)).isNotNull()
            }

            rig.engine.synchronize(session, SyncTrigger.LocalWrite)

            assertThat(rig.backend.pullCount).isEqualTo(0)
            assertThat(rig.preferences.current().pullCursor).isEqualTo(47)
            assertThat(rig.backend.syncOrder.filter { it.startsWith("causal_") })
                .containsExactly("causal_commit:1")
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
            cursor = session.pullCursor,
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
        assertThat(rig.backend.causalCommittedUnits).hasSize(1)
    }

    @Test
    fun localWriteWithLogPhotoPreparesReceiptThenCommitsWithoutReconcile() = runTest {
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
        val localUri = "photos/localwrite-photo.jpg"
        rig.mediaFiles.preparedUploadBytes[localUri] = byteArrayOf(7, 8, 9)
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = mediaUuid,
                kind = "log",
                recordId = recordId,
                localUri = localUri,
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
                "causal_media_preimage_started:$mediaUuid",
                "causal_media_preimage:$mediaUuid",
                "causal_commit:1",
            )
            .inOrder()
        assertThat(rig.mediaFiles.prepareUploadCounts[localUri]).isEqualTo(1)
        val settled = requireNotNull(rig.records.getByClientUuid("record-with-photo"))
        assertThat(settled.syncDirty).isFalse()
        assertThat(rig.media.getByClientUuid(mediaUuid)?.syncDirty).isFalse()
    }

    @Test
    fun lostMediaCommitResponseReusesDurableReceiptAfterSourceChanges() = runTest {
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
                clientUuid = "record-spool-retry",
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
        val mediaUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee18"
        val localUri = "content://mutable-photo"
        val firstBytes = byteArrayOf(1, 3, 5, 7)
        rig.mediaFiles.preparedUploadBytes[localUri] = firstBytes
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = mediaUuid,
                kind = "log",
                recordId = recordId,
                localUri = localUri,
                mime = "image/jpeg",
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        var commitAttempts = 0
        rig.backend.onCausalCommit = {
            commitAttempts += 1
            if (commitAttempts == 1) throw java.io.IOException("commit response lost")
        }

        assertThat(
            runCatching { rig.engine.synchronize(session, SyncTrigger.LocalWrite) }
                .exceptionOrNull(),
        ).isInstanceOf(java.io.IOException::class.java)
        val firstMutation = rig.backend.causalCommittedUnits.single().single()
        val durableJournal = requireNotNull(
            rig.conflictDetails.getFrozenMediaSpoolManifest(firstMutation.mutationId),
        )
        assertThat(decodeCausalMediaSettlementOrNull(durableJournal.payloadJson)?.phase)
            .isEqualTo(CausalMediaSettlementPhase.CommitUnknown)
        assertThat(rig.records.getByClientUuid("record-spool-retry")?.mutationId)
            .isEqualTo(firstMutation.mutationId)
        rig.mediaFiles.preparedUploadBytes[localUri] = byteArrayOf(9, 9, 9)
        rig.mediaFiles.prepareUploadFailures += localUri

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(rig.backend.causalCommittedUnits.last().single()).isEqualTo(firstMutation)
        assertThat(rig.mediaFiles.prepareUploadCounts[localUri]).isEqualTo(1)
        assertThat(rig.backend.causalMediaPreimageBytes).hasSize(1)
        assertThat(rig.backend.causalMediaPreimageBytes.single().second).isEqualTo(firstBytes)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(4)
    }

    @Test
    fun coldStartRebindsCompletePendingSidecarsBeforeRoomManifestAndPublish() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        val mutationId = "00000000-0000-4000-8000-000000000018"
        val recordUuid = "record-sidecar-rebind"
        val recordId = rig.records.seed(
            RecordEntity(
                clientUuid = recordUuid,
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = """{"amount_ml":90}""",
                schemaVersion = 2,
                updatedAt = 100,
                syncDirty = true,
                baseVersion = "v-r0",
                mutationId = mutationId,
            ),
        )
        val mediaUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee19"
        val localUri = "content://sidecar-only"
        rig.mediaFiles.preparedUploadBytes[localUri] = byteArrayOf(4, 3, 2, 1)
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = mediaUuid,
                kind = "log",
                recordId = recordId,
                localUri = localUri,
                mime = "image/jpeg",
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        rig.immutableMediaSpool.freezeGroup(
            mutationId,
            listOf(
                com.lezi.babylog.sync.media.ImmutableMediaSpoolSource(
                    mediaUuid = mediaUuid,
                    role = com.lezi.babylog.sync.media.CausalMediaRole.Log,
                    localUri = localUri,
                ),
            ),
        )
        assertThat(rig.conflictDetails.getFrozenMediaSpoolManifest(mutationId)).isNull()

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(rig.mediaFiles.prepareUploadCounts[localUri]).isEqualTo(1)
        assertThat(rig.conflictDetails.getFrozenMediaSpoolManifest(mutationId)).isNull()
        assertThat(rig.immutableMediaSpool.discardedMutationIds).containsExactly(mutationId)
        assertThat(rig.backend.causalMediaPreimageBytes.single().second)
            .isEqualTo(byteArrayOf(4, 3, 2, 1))
        assertThat(rig.backend.causalCommittedUnits.single().single().clientUuid)
            .isEqualTo(recordUuid)
    }

    @Test
    fun coldStartRejectsRoomSpoolKeyThatDoesNotBindPayloadMutation() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val group = com.lezi.babylog.sync.media.ImmutableMediaSpoolGroup(
            mutationId = "00000000-0000-4000-8000-000000000018",
            items = listOf(
                com.lezi.babylog.sync.media.ImmutableMediaSpoolItem(
                    mediaUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee18",
                    slot = 0,
                    role = com.lezi.babylog.sync.media.CausalMediaRole.Log,
                    sha256 = "0".repeat(64),
                    byteSize = 1,
                    mime = "image/jpeg",
                    width = 1,
                    height = 1,
                ),
            ),
        )
        rig.conflictDetails.putTransportJournal(
            journalKey = "frozen-media-spool:00000000-0000-4000-8000-000000000019",
            payloadJson = com.lezi.babylog.sync.media.encodeImmutableMediaSpoolGroup(group),
            contentEpoch = 100,
        )

        val failure = runCatching {
            rig.engine.synchronize(session, SyncTrigger.LocalWrite)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().contains("key does not bind")
    }

    @Test
    fun removedRoomSlotLeavesPartialOrCompleteJournalAndPerformsNoNetworkWrite() = runTest {
        listOf(false, true).forEach { completeJournal ->
            val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
            val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
            val babyId = rig.babies.seed(
                localReplicaBaby().copy(
                    syncDirty = false,
                    familyAuthority = true,
                    baseVersion = "v-baby",
                ),
            )
            val mutationId = if (completeJournal) {
                "00000000-0000-4000-8000-000000000028"
            } else {
                "00000000-0000-4000-8000-000000000018"
            }
            val recordId = rig.records.seed(
                RecordEntity(
                    clientUuid = if (completeJournal) "record-complete-slot-drift" else "record-partial-slot-drift",
                    babyId = babyId,
                    type = "formula",
                    timestamp = 100,
                    payloadJson = """{"amount_ml":90}""",
                    schemaVersion = 2,
                    updatedAt = 100,
                    syncDirty = true,
                    baseVersion = "v-r0",
                    mutationId = mutationId,
                ),
            )
            val firstMediaUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee18"
            val secondMediaUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee19"
            val firstUri = "content://first-$completeJournal"
            val secondUri = "content://second-$completeJournal"
            rig.mediaFiles.preparedUploadBytes[firstUri] = byteArrayOf(1, 2)
            rig.mediaFiles.preparedUploadBytes[secondUri] = byteArrayOf(3, 4)
            rig.media.seed(
                MediaAssetEntity(
                    clientUuid = firstMediaUuid,
                    kind = "log",
                    recordId = recordId,
                    localUri = firstUri,
                    mime = "image/jpeg",
                    createdAt = 100,
                    updatedAt = 100,
                    syncDirty = false,
                ),
            )
            rig.media.seed(
                MediaAssetEntity(
                    clientUuid = secondMediaUuid,
                    kind = "log",
                    recordId = recordId,
                    localUri = secondUri,
                    mime = "image/jpeg",
                    createdAt = 100,
                    updatedAt = 100,
                    syncDirty = false,
                ),
            )
            val sources = listOf(
                com.lezi.babylog.sync.media.ImmutableMediaSpoolSource(
                    firstMediaUuid,
                    com.lezi.babylog.sync.media.CausalMediaRole.Log,
                    firstUri,
                ),
                com.lezi.babylog.sync.media.ImmutableMediaSpoolSource(
                    secondMediaUuid,
                    com.lezi.babylog.sync.media.CausalMediaRole.Log,
                    secondUri,
                ),
            )
            if (!completeJournal) rig.mediaFiles.prepareUploadFailures += secondUri
            val freeze = runCatching { rig.immutableMediaSpool.freezeGroup(mutationId, sources) }
            assertThat(freeze.isSuccess).isEqualTo(completeJournal)
            rig.mediaFiles.prepareUploadFailures.clear()
            val removed = requireNotNull(rig.media.getByClientUuid(firstMediaUuid))
            rig.media.update(removed.copy(deletedAt = 200, updatedAt = 200, syncDirty = false))

            rig.engine.synchronize(session, SyncTrigger.LocalWrite)

            assertThat(rig.backend.causalMediaPreimageBytes).isEmpty()
            assertThat(rig.backend.causalCommittedUnits).isEmpty()
            assertThat(rig.conflictDetails.getFrozenMediaSpoolManifest(mutationId)).isNull()
            val evidence = requireNotNull(rig.immutableMediaSpool.recoverGroup(mutationId))
            assertThat(evidence.group.items.map { it.mediaUuid }).contains(firstMediaUuid)
            if (completeJournal) {
                assertThat(evidence)
                    .isInstanceOf(com.lezi.babylog.sync.media.ImmutableMediaSpoolRecovery.Complete::class.java)
            } else {
                assertThat(evidence)
                    .isInstanceOf(com.lezi.babylog.sync.media.ImmutableMediaSpoolRecovery.Partial::class.java)
            }
        }
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
                rig.backend.nextCausalCommit = CausalCommitBatchResult(
                    generation = session.pullGeneration,
                    results = listOf(
                        CausalCommitUnitResult(
                            status = case.commitStatus,
                            mutationId = unit.mutationId,
                            requestHash = causalMutationContentHash(unit),
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
            rig.backend.nextCausalCommit = CausalCommitBatchResult(
                generation = session.pullGeneration,
                results = listOf(
                    CausalCommitUnitResult(
                        status = CausalCommitStatus.ACCEPTED,
                        mutationId = replay.mutationId,
                        requestHash = causalMutationContentHash(replay),
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
            rig.backend.nextCausalCommitFailure = CausalCommitRejectedException(
                mutationId = unit.mutationId,
                code = "content_drift",
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
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(13)
    }

    @Test
    fun localWriteCoBatchesSleepAndWakeInOneDirectCommit() = runTest {
        // H13 preserves the Sleep source UUID while moving the dependent Wake
        // onto the same commit-first batch. LocalWrite must not resurrect the
        // removed source-authority path or advance the pull cursor.
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

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(6)
        assertThat(rig.backend.syncOrder.filter { it.startsWith("causal_") })
            .containsExactly("causal_commit:2")
            .inOrder()
        assertThat(rig.backend.causalCommittedUnits).hasSize(1)
        assertThat(rig.backend.causalCommittedUnits.single().map { it.entityType })
            .containsExactly("record", "wake_observation")
            .inOrder()
        val wakeEnvelope = rig.backend.causalCommittedUnits.single().last()
        assertThat(wakeEnvelope.rootJson)
            .contains("\"sleep_record_client_uuid\":\"$sleepUuid\"")
        assertThat(wakeEnvelope.rootJson).doesNotContain("observer_membership_id")
        val sleep = requireNotNull(rig.records.getByClientUuid(sleepUuid))
        val wake = requireNotNull(rig.wakeObservations.getByClientUuid(wakeUuid))
        assertThat(sleep.syncDirty).isFalse()
        assertThat(sleep.baseVersion).isNotNull()
        assertThat(wake.syncDirty).isFalse()
        assertThat(wake.baseVersion).isNotNull()
        assertThat(wake.openConflictId).isNull()
    }

    @Test
    fun acceptedWakeProjectsOnlyTheServerObserverStamp() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 7)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        val sleepUuid = "sleep-stamped-wake"
        rig.records.seed(
            RecordEntity(
                clientUuid = sleepUuid,
                babyId = babyId,
                type = "sleep",
                timestamp = 100,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                schemaVersion = 2,
                updatedAt = 100,
                syncDirty = false,
                baseVersion = "v-sleep",
            ),
        )
        val wakeUuid = "wake-server-stamp"
        rig.wakeObservations.seed(
            com.lezi.babylog.core.database.causal.WakeObservationEntity(
                clientUuid = wakeUuid,
                sleepRecordClientUuid = sleepUuid,
                wakeTimestamp = 200,
                observerMembershipId = "",
                note = "observed",
                withdrawn = false,
                updatedAt = 200,
                syncDirty = true,
                baseVersion = null,
            ),
        )
        rig.backend.onCausalCommit = { units ->
            val unit = units.single()
            assertThat(unit.rootJson).doesNotContain("observer_membership_id")
            val stampedRoot = unit.rootJson.dropLast(1) +
                ",\"observer_membership_id\":\"server-observer\"}"
            rig.backend.nextCausalCommit = CausalCommitBatchResult(
                generation = session.pullGeneration,
                results = listOf(
                    CausalCommitUnitResult(
                        status = CausalCommitStatus.ACCEPTED,
                        mutationId = unit.mutationId,
                        requestHash = causalMutationContentHash(unit),
                        stableVersionId = "v-wake-stamped",
                        stableRootJson = stampedRoot,
                        stableMedia = emptyList(),
                    ),
                ),
            )
        }

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        with(requireNotNull(rig.wakeObservations.getByClientUuid(wakeUuid))) {
            assertThat(observerMembershipId).isEqualTo("server-observer")
            assertThat(baseVersion).isEqualTo("v-wake-stamped")
            assertThat(syncDirty).isFalse()
        }
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(7)
    }

    @Test
    fun invalidWakeTerminalProofsWriteNoProductOrConflictState() = runTest {
        data class Case(
            val name: String,
            val corrupt: (MutableMap<String, JsonElement>) -> Unit,
        )
        val cases = listOf(
            Case("missing-observer") { it.remove("observer_membership_id") },
            Case("wrong-wake-timestamp-type") {
                it["wake_timestamp"] = JsonPrimitive("200")
            },
            Case("wrong-withdrawn-type") { it["withdrawn"] = JsonPrimitive("false") },
            Case("wrong-observer-type") { it["observer_membership_id"] = JsonPrimitive(42) },
            Case("source-uuid-drift") {
                it["sleep_record_client_uuid"] = JsonPrimitive("other-sleep")
            },
        )
        for (case in cases) {
            val (session, rig, wakeUuid) = seedDirtyWake(
                pullCursor = 71,
                clientUuid = "wake-invalid-${case.name}",
            )
            val source = requireNotNull(
                rig.records.getByClientUuid("sleep-$wakeUuid"),
            )
            rig.records.seed(
                source.copy(
                    id = 0,
                    clientUuid = "other-sleep",
                    baseVersion = "v-other-sleep",
                ),
            )
            var beforeTerminal: com.lezi.babylog.core.database.causal.WakeObservationEntity? = null
            rig.backend.onCausalCommit = { units ->
                val unit = units.single()
                beforeTerminal = rig.wakeObservations.getByClientUuid(wakeUuid)
                val stable = Json.parseToJsonElement(unit.rootJson).jsonObject.toMutableMap()
                stable["observer_membership_id"] = JsonPrimitive("server-observer")
                case.corrupt(stable)
                rig.backend.nextCausalCommit = CausalCommitBatchResult(
                    generation = session.pullGeneration,
                    results = listOf(
                        CausalCommitUnitResult(
                            status = CausalCommitStatus.ACCEPTED,
                            mutationId = unit.mutationId,
                            requestHash = causalMutationContentHash(unit),
                            stableVersionId = "v-invalid-${case.name}",
                            stableRootJson = kotlinx.serialization.json.JsonObject(stable).toString(),
                            stableMedia = emptyList(),
                        ),
                    ),
                )
            }

            val failure = runCatching {
                rig.engine.synchronize(session, SyncTrigger.LocalWrite)
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            assertThat(failure).hasMessageThat().contains("frozen commit proof invalid")
            assertThat(rig.wakeObservations.getByClientUuid(wakeUuid))
                .isEqualTo(requireNotNull(beforeTerminal))
            assertThat(rig.conflictSummaries.listForRoot("wake_observation", wakeUuid)).isEmpty()
            assertThat(rig.conflictDetails.getFrozenMutation("wake_observation", wakeUuid))
                .isNotNull()
        }
    }

    @Test
    fun wakeLiveAndTombstoneCommitDirectlyButDanglingOrMediaRootsWait() = runTest {
        for (deleted in listOf(false, true)) {
            val (session, rig, wakeUuid) = seedDirtyWake(
                pullCursor = 8,
                clientUuid = "wake-direct-$deleted",
                deleted = deleted,
            )

            rig.engine.synchronize(session, SyncTrigger.LocalWrite)

            val committed = rig.backend.causalCommittedUnits.single().single()
            assertThat(committed.entityType).isEqualTo("wake_observation")
            assertThat(committed.clientUuid).isEqualTo(wakeUuid)
            assertThat(committed.deleted).isEqualTo(deleted)
            assertThat(committed.media).isEmpty()
            assertThat(committed.rootJson).doesNotContain("observer_membership_id")
            assertThat(rig.backend.pullCount).isEqualTo(0)
            assertThat(rig.preferences.current().pullCursor).isEqualTo(8)
        }

        val missingSession = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 9)
        val missingRig = ReplicaEngineRig(missingSession).also { it.backend.enableCausal = true }
        missingRig.wakeObservations.seed(
            com.lezi.babylog.core.database.causal.WakeObservationEntity(
                clientUuid = "wake-missing-sleep",
                sleepRecordClientUuid = "missing-sleep",
                wakeTimestamp = 200,
                updatedAt = 200,
                syncDirty = true,
            ),
        )

        missingRig.engine.synchronize(missingSession, SyncTrigger.LocalWrite)

        assertThat(missingRig.backend.causalCommittedUnits).isEmpty()
        assertThat(
            requireNotNull(
                missingRig.wakeObservations.getByClientUuid("wake-missing-sleep"),
            ).syncDirty,
        ).isTrue()
        assertThat(
            missingRig.conflictDetails.getFrozenMutation(
                "wake_observation",
                "wake-missing-sleep",
            ),
        ).isNull()

        val (mediaSession, mediaRig, mediaWakeUuid) = seedDirtyWake(
            pullCursor = 10,
            clientUuid = "wake-media-commit",
        )
        val wake = requireNotNull(mediaRig.wakeObservations.getByClientUuid(mediaWakeUuid))
        val wakePhotoUuid = "00000000-0000-4000-8000-000000001300"
        mediaRig.media.seed(
            MediaAssetEntity(
                wakeObservationId = wake.id,
                clientUuid = wakePhotoUuid,
                kind = "wake",
                localUri = "/private/wake.jpg",
                mime = "image/jpeg",
                createdAt = 100,
                updatedAt = 200,
                syncDirty = true,
            ),
        )
        assertThat(mediaRig.media.listActiveForWakeObservation(wake.id)).hasSize(1)

        mediaRig.engine.synchronize(mediaSession, SyncTrigger.LocalWrite)

        val mediaCommit = mediaRig.backend.causalCommittedUnits.single().single()
        assertThat(mediaCommit.entityType).isEqualTo("wake_observation")
        assertThat(mediaCommit.media.single().role).isEqualTo("wake")
        assertThat(mediaCommit.media.single().mediaUuid).isEqualTo(wakePhotoUuid)
        assertThat(mediaRig.backend.pullCount).isEqualTo(0)
        assertThat(mediaRig.preferences.current().pullCursor).isEqualTo(10)
        assertThat(requireNotNull(mediaRig.wakeObservations.getByClientUuid(mediaWakeUuid)).syncDirty)
            .isFalse()
        assertThat(requireNotNull(mediaRig.media.getByClientUuid(wakePhotoUuid)).syncDirty)
            .isFalse()
    }

    @Test
    fun lostWakeResponseReplaysExactEnvelopeAndBranchedCasPreservesNewerObservation() = runTest {
        val (session, rig, wakeUuid) = seedDirtyWake(
            pullCursor = 11,
            clientUuid = "wake-lost-branch",
        )
        var frozen: com.lezi.babylog.sync.backend.CausalMutationUnit? = null
        rig.backend.onCausalCommit = { units ->
            frozen = units.single()
            throw java.io.IOException("wake response lost")
        }
        assertThat(
            runCatching { rig.engine.synchronize(session, SyncTrigger.LocalWrite) }.exceptionOrNull(),
        ).isInstanceOf(java.io.IOException::class.java)
        val firstEnvelope = requireNotNull(frozen)
        val firstRow = requireNotNull(rig.wakeObservations.getByClientUuid(wakeUuid))
        rig.wakeObservations.update(
            firstRow.copy(
                wakeTimestamp = 240,
                note = "newer observation",
                withdrawn = true,
                updatedAt = 300,
                syncDirty = true,
                mutationId = null,
            ),
        )

        rig.backend.onCausalCommit = { units ->
            val replay = units.single()
            assertThat(replay).isEqualTo(firstEnvelope)
            rig.backend.nextCausalCommit = CausalCommitBatchResult(
                generation = session.pullGeneration,
                results = listOf(
                    CausalCommitUnitResult(
                        status = CausalCommitStatus.BRANCHED,
                        mutationId = replay.mutationId,
                        requestHash = causalMutationContentHash(replay),
                        stableVersionId = "v-wake-stable",
                        stableRootJson = replay.rootJson.dropLast(1) +
                            ",\"observer_membership_id\":\"server-observer\"}",
                        stableMedia = emptyList(),
                        branchVersionId = "branch-wake",
                        conflictId = "conflict-wake",
                    ),
                ),
            )
        }
        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        with(requireNotNull(rig.wakeObservations.getByClientUuid(wakeUuid))) {
            assertThat(wakeTimestamp).isEqualTo(240)
            assertThat(note).isEqualTo("newer observation")
            assertThat(withdrawn).isTrue()
            assertThat(updatedAt).isEqualTo(300)
            assertThat(syncDirty).isTrue()
            assertThat(mutationId).isNull()
            assertThat(baseVersion).isEqualTo("v-wake-stable")
            assertThat(openConflictId).isEqualTo("conflict-wake")
            assertThat(localBranchVersionId).isEqualTo("branch-wake")
        }
        assertThat(rig.conflictDetails.getFrozenMutation("wake_observation", wakeUuid)).isNull()

        rig.backend.onCausalCommit = null
        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        val replanned = rig.backend.causalCommittedUnits.last().single()
        assertThat(replanned.mutationId).isNotEqualTo(firstEnvelope.mutationId)
        assertThat(replanned.baseVersion).isEqualTo("v-wake-stable")
        assertThat(replanned.rootJson).contains("\"wake_timestamp\":240")
        assertThat(replanned.rootJson).contains("\"withdrawn\":true")
        assertThat(replanned.rootJson).contains("newer observation")
        assertThat(replanned.rootJson)
            .contains("\"sleep_record_client_uuid\":\"sleep-$wakeUuid\"")
        assertThat(replanned.rootJson).doesNotContain("observer_membership_id")
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(11)
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
        val commitStarted = CompletableDeferred<Unit>()
        rig.backend.onCausalCommit = {
            commitStarted.complete(Unit)
            gate.await()
        }
        val job = async {
            rig.engine.synchronize(session, SyncTrigger.LocalWrite)
        }
        // Deterministic mid-commit cancellation: wait until the commit hook is
        // actually running (scheduler-hop-count independent), then cancel.
        commitStarted.await()
        job.cancel()
        val thrown = runCatching { job.await() }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        gate.cancel()

        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(8)
        assertThat(requireNotNull(rig.records.getByClientUuid(uuid)).syncDirty).isTrue()
        assertThat(rig.backend.causalCommittedUnits).hasSize(1)
    }

    @Test
    fun noteEditAfterFulfillmentRemountPublishesImmutablePairNotDisplayWinner() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 77)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val memberRecordUuid = "00000000-0000-4000-8000-000000000261"
        val ownerRecordUuid = "00000000-0000-4000-8000-000000000262"
        val planUuid = "00000000-0000-4000-8000-000000000270"
        val sourceUuid = "00000000-0000-4000-8000-000000000280"
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
                clientUuid = "00000000-0000-4000-8000-000000000241",
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = memberRecordUuid,
                babyId = babyId,
                type = "formula",
                timestamp = 20,
                payloadJson = """{"amount_ml":120}""",
                schemaVersion = 2,
                updatedAt = 20,
                syncDirty = false,
                baseVersion = "v-rec-member",
            ),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = ownerRecordUuid,
                babyId = babyId,
                type = "formula",
                timestamp = 30,
                payloadJson = """{"amount_ml":120}""",
                schemaVersion = 2,
                updatedAt = 30,
                syncDirty = false,
                baseVersion = "v-rec-owner",
            ),
        )
        rig.carePlans.seed(
            localReplicaCarePlan(planUuid, "membership-a", updatedAt = 40).copy(
                babyId = babyId,
                status = CarePlanStatus.COMPLETED.storageKey,
                note = "first-complete",
                fulfilledRecordClientUuid = memberRecordUuid,
                fulfilledAt = 20L,
                sourceRecordClientUuid = sourceUuid,
                syncDirty = false,
                baseVersion = "v-plan-complete",
            ),
        )
        rig.fulfillmentCandidates.upsert(
            FulfillmentCandidateEntity(
                clientUuid = "00000000-0000-4000-8000-000000000271",
                carePlanClientUuid = planUuid,
                recordClientUuid = memberRecordUuid,
                confirmedAt = 20L,
                submitterRole = "member",
                updatedAt = 20L,
                syncDirty = false,
            ),
        )
        rig.fulfillmentCandidates.upsert(
            FulfillmentCandidateEntity(
                clientUuid = "00000000-0000-4000-8000-000000000272",
                carePlanClientUuid = planUuid,
                recordClientUuid = ownerRecordUuid,
                confirmedAt = 30L,
                submitterRole = "owner",
                updatedAt = 30L,
                syncDirty = false,
            ),
        )

        FulfillmentAuthoritySettlement(
            carePlanDao = rig.carePlans,
            fulfillmentCandidateDao = rig.fulfillmentCandidates,
            transactionRunner = rig.transactions,
        ).settle(planUuid)

        val remounted = requireNotNull(rig.carePlans.getByClientUuid(planUuid))
        assertThat(remounted.fulfilledRecordClientUuid).isEqualTo(ownerRecordUuid)
        assertThat(remounted.fulfilledAt).isEqualTo(30L)
        assertThat(remounted.updatedAt).isEqualTo(40L)
        assertThat(remounted.syncDirty).isFalse()

        rig.carePlans.update(
            remounted.copy(
                note = "edited-after-remount",
                updatedAt = 50L,
                syncDirty = true,
            ),
        )
        var frozenRoot: String? = null
        rig.backend.onCausalCommit = { units ->
            frozenRoot = units.single().rootJson
        }

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        val root = Json.parseToJsonElement(requireNotNull(frozenRoot)).jsonObject
        assertThat(root["source_record_client_uuid"]?.toString())
            .isEqualTo("\"$sourceUuid\"")
        assertThat(root["fulfilled_record_client_uuid"]?.toString())
            .isEqualTo("\"$memberRecordUuid\"")
        assertThat(root["fulfilled_at"]?.toString()).isEqualTo("20")
        assertThat(root["note"]?.toString()).isEqualTo("\"edited-after-remount\"")
        assertThat(root.keys).containsNoneOf(
            "system_calendar_event_id",
            "sync_dirty",
        )
        val after = requireNotNull(rig.carePlans.getByClientUuid(planUuid))
        assertThat(after.fulfilledRecordClientUuid).isEqualTo(ownerRecordUuid)
        assertThat(after.fulfilledAt).isEqualTo(30L)
        assertThat(after.syncDirty).isFalse()
    }

    @Test
    fun publishedFulfillmentCandidateClearsMemoizedPendingUnits() = runTest {
        val session = joinedReplicaSession().copy(pullCursor = 11)
        val rig = ReplicaEngineRig(session)
        val candidateUuid = "00000000-0000-4000-8000-000000000901"
        rig.fulfillmentCandidates.upsert(
            FulfillmentCandidateEntity(
                clientUuid = candidateUuid,
                carePlanClientUuid = "00000000-0000-4000-8000-000000000902",
                recordClientUuid = "00000000-0000-4000-8000-000000000903",
                confirmedAt = 40L,
                updatedAt = 40L,
                syncDirty = true,
            ),
        )
        assertThat(rig.engine.hasQuietForegroundRoundBlockers(session)).isTrue()
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = session.pullCursor,
            generation = session.pullGeneration,
            hasMore = false,
            liveCensus = LiveCensus(emptyMap()),
        )

        rig.engine.synchronize(session, SyncTrigger.PullToRefresh)

        assertThat(rig.backend.causalCommittedUnits).isEmpty()
        assertThat(rig.backend.committedBundles).hasSize(1)
        assertThat(rig.fulfillmentCandidates.getByClientUuid(candidateUuid)?.syncDirty).isFalse()
        assertThat(rig.engine.hasQuietForegroundRoundBlockers(session)).isFalse()
    }

    @Test
    fun cleanCompleteOrphanLogMediaIsHardDeletedWithoutInspectingOwnedRow() = runTest {
        val session = joinedReplicaSession().copy(pullCursor = 12)
        val rig = ReplicaEngineRig(session)
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        val recordId = rig.records.seed(
            RecordEntity(
                clientUuid = "record-owned-media",
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = """{"amount_ml":90}""",
                schemaVersion = 2,
                updatedAt = 100,
                syncDirty = false,
                baseVersion = "v-record",
            ),
        )
        val ownedUri = "records/owned-complete.jpg"
        val orphanUri = "records/orphan-complete.jpg"
        // byteSize matches the fake file's statLength so the clean owned row
        // is skipped without inspecting; only the parentless orphan enters repair.
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = "owned-complete-media",
                kind = "log",
                recordId = recordId,
                localUri = ownedUri,
                mime = "image/jpeg",
                width = 20,
                height = 30,
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = "orphan-complete-media",
                kind = "log",
                recordId = 9_999L,
                localUri = orphanUri,
                mime = "image/jpeg",
                width = 20,
                height = 30,
                byteSize = 400,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = session.pullCursor,
            generation = session.pullGeneration,
            hasMore = false,
        )

        rig.engine.synchronize(session, SyncTrigger.PullToRefresh)

        assertThat(rig.media.getByClientUuid("orphan-complete-media")).isNull()
        assertThat(rig.mediaFiles.deleted).contains(orphanUri)
        val owned = requireNotNull(rig.media.getByClientUuid("owned-complete-media"))
        assertThat(owned.syncDirty).isFalse()
        assertThat(owned.width).isEqualTo(20)
        assertThat(owned.byteSize).isEqualTo(12)
        assertThat(rig.mediaFiles.inspected).contains(orphanUri)
        assertThat(rig.mediaFiles.inspected).doesNotContain(ownedUri)
        assertThat(rig.mediaFiles.deleted).doesNotContain(ownedUri)
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

    private fun seedDirtyWake(
        pullCursor: Long,
        clientUuid: String,
        deleted: Boolean = false,
    ): Triple<com.lezi.babylog.sync.session.SyncSession, ReplicaEngineRig, String> {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = pullCursor)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        val sleepUuid = "sleep-$clientUuid"
        rig.records.seed(
            RecordEntity(
                clientUuid = sleepUuid,
                babyId = babyId,
                type = "sleep",
                timestamp = 100,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                schemaVersion = 2,
                updatedAt = 100,
                syncDirty = false,
                baseVersion = "v-sleep",
            ),
        )
        rig.wakeObservations.seed(
            com.lezi.babylog.core.database.causal.WakeObservationEntity(
                clientUuid = clientUuid,
                sleepRecordClientUuid = sleepUuid,
                wakeTimestamp = 200,
                observerMembershipId = "membership-a",
                note = "epoch-1",
                withdrawn = false,
                updatedAt = 200,
                deletedAt = if (deleted) 199 else null,
                syncDirty = true,
                baseVersion = "v-wake-base",
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
