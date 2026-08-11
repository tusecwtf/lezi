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
