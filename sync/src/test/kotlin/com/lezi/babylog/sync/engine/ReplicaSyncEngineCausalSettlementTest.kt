package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheEntity
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.CausalBatchResult
import com.lezi.babylog.sync.backend.CausalCommitStatus
import com.lezi.babylog.sync.backend.CausalUnitResult
import com.lezi.babylog.sync.backend.PullConflictSummary
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Public seam: [ReplicaSyncEngine.synchronize] with [RecordingSyncBackend] causal
 * commit-first/source reconcile — freeze, proof fail-closed, accepted/merged/branched CAS,
 * dirty pull protection, generation recovery.
 */
class ReplicaSyncEngineCausalSettlementTest {

    @Test
    fun recordCommitFirstDrainsWireBoundedBatchesWithoutReconcile() = runTest {
        for (rootCount in listOf(1, 64, 65, 129)) {
            val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
            val rig = ReplicaEngineRig(
                session = session,
                allowHistoricalMutableRootEvidence = false,
            ).also { it.backend.enableCausal = true }
            val babyId = rig.babies.seed(
                localReplicaBaby().copy(
                    syncDirty = false,
                    familyAuthority = true,
                    baseVersion = "v-baby",
                ),
            )
            repeat(rootCount) { index ->
                rig.records.seed(
                    RecordEntity(
                        clientUuid = "record-batch-$rootCount-$index",
                        babyId = babyId,
                        type = "formula",
                        timestamp = index.toLong(),
                        payloadJson = """{"amount_ml":60}""",
                        schemaVersion = 2,
                        updatedAt = index + 1L,
                        syncDirty = true,
                        baseVersion = "v-base-$index",
                    ),
                )
            }
            rig.backend.onCausalCommit = { units ->
                require(units.size in 1..64) { "wire batch exceeded 64 roots" }
            }

            rig.engine.synchronize(session, SyncTrigger.LocalWrite)

            assertThat(rig.backend.causalReconciledUnits).isEmpty()
            assertThat(rig.backend.causalCommittedUnits.map(List<*>::size))
                .containsExactlyElementsIn(
                    (0 until rootCount step 64).map { start ->
                        minOf(64, rootCount - start)
                    },
                )
                .inOrder()
            assertThat(rig.records.listPendingSync()).isEmpty()
        }
    }

    @Test
    fun laterCausalCommitBatchFailureRetainsOnlyUncommittedRootsPending() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(
            session = session,
            allowHistoricalMutableRootEvidence = false,
        ).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
        )
        repeat(65) { index ->
            rig.records.seed(
                RecordEntity(
                    clientUuid = "record-commit-partial-$index",
                    babyId = babyId,
                    type = "formula",
                    timestamp = index.toLong(),
                    payloadJson = """{"amount_ml":60}""",
                    schemaVersion = 2,
                    updatedAt = index + 1L,
                    syncDirty = true,
                    baseVersion = "v$index",
                ),
            )
        }
        var commitBatch = 0
        rig.backend.onCausalCommit = {
            commitBatch += 1
            if (commitBatch == 2) throw java.io.IOException("second commit batch unavailable")
        }

        assertThat(
            runCatching {
                rig.engine.synchronize(session, SyncTrigger.LocalWrite)
            }.exceptionOrNull(),
        ).isInstanceOf(java.io.IOException::class.java)

        assertThat(rig.backend.causalReconciledUnits).isEmpty()
        assertThat(rig.backend.causalCommittedUnits.map(List<*>::size))
            .containsExactly(64, 1)
            .inOrder()
        assertThat(rig.records.listPendingSync().map(RecordEntity::clientUuid))
            .containsExactly("record-commit-partial-64")
    }

    @Test
    fun mediaManifestFreezeRejectsConcurrentAttachmentAdditionOrDeletion() = runTest {
        for (mutation in listOf("add", "delete")) {
            val originalMediaUuid = if (mutation == "add") {
                "00000000-0000-4000-8000-000000000001"
            } else {
                "00000000-0000-4000-8000-000000000002"
            }
            val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
            val rig = ReplicaEngineRig(
                session = session,
                allowHistoricalMutableRootEvidence = false,
            ).also { it.backend.enableCausal = true }
            val babyId = rig.babies.seed(
                localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
            )
            val recordId = rig.records.seed(
                RecordEntity(
                    clientUuid = "record-media-race-$mutation",
                    babyId = babyId,
                    type = "formula",
                    timestamp = 100,
                    payloadJson = """{"amount_ml":60}""",
                    schemaVersion = 2,
                    updatedAt = 100,
                    syncDirty = true,
                    baseVersion = "v0",
                ),
            )
            val originalId = rig.media.seed(
                MediaAssetEntity(
                    recordId = recordId,
                    clientUuid = originalMediaUuid,
                    kind = "log",
                    localUri = "/private/original-$mutation.jpg",
                    createdAt = 100,
                    updatedAt = 100,
                    syncDirty = true,
                ),
            )
            rig.mediaFiles.afterPrepareUpload = {
                when (mutation) {
                    "add" -> rig.media.seed(
                        MediaAssetEntity(
                            recordId = recordId,
                            clientUuid = "00000000-0000-4000-8000-000000000003",
                            kind = "log",
                            localUri = "/private/concurrent-add.jpg",
                            createdAt = 101,
                            updatedAt = 101,
                            syncDirty = true,
                        ),
                    )
                    "delete" -> {
                        val original = requireNotNull(
                            rig.media.getByClientUuid(originalMediaUuid),
                        )
                        assertThat(original.id).isEqualTo(originalId)
                        rig.media.update(
                            original.copy(
                                updatedAt = 101,
                                deletedAt = 101,
                                syncDirty = true,
                            ),
                        )
                    }
                }
            }
            if (mutation == "add") {
                rig.backend.onCausalCommit = { units ->
                    val unit = units.single()
                    rig.backend.nextCausalCommit = CausalBatchResult(
                        generation = session.pullGeneration,
                        cursor = session.pullCursor,
                        results = listOf(
                            CausalUnitResult(
                                status = CausalCommitStatus.MERGED,
                                mutationId = unit.mutationId,
                                requestHash = causalMutationContentHash(unit),
                                generation = session.pullGeneration,
                                stableVersionId = "v-merged-media",
                                stableRootJson = unit.rootJson,
                                stableMedia = unit.media,
                            ),
                        ),
                    )
                }
            }

            rig.engine.synchronize(session, SyncTrigger.LocalWrite)

            if (mutation == "add") {
                val expectedMedia = setOf(
                    originalMediaUuid,
                    "00000000-0000-4000-8000-000000000003",
                )
                assertThat(rig.backend.causalReconciledUnits).isNotEmpty()
                rig.backend.causalReconciledUnits.forEach { batch ->
                    assertThat(batch.single().media.map { it.mediaUuid }.toSet())
                        .isEqualTo(expectedMedia)
                }
                assertThat(rig.records.getByClientUuid("record-media-race-add")!!.syncDirty)
                    .isFalse()
                assertThat(rig.media.listPendingSync()).isEmpty()
            } else {
                assertThat(rig.backend.causalReconciledUnits).isEmpty()
                assertThat(rig.records.getByClientUuid("record-media-race-delete")!!.syncDirty)
                    .isTrue()
                assertThat(rig.media.listPendingSync()).isNotEmpty()
            }
        }
    }

    @Test
    fun causalProofRejectsNonCanonicalRequestHashesBeforeRoomSettlement() = runTest {
        val corruptions: List<(String) -> String> = listOf(
            { "" },
            { "garbage" },
            { it.uppercase() },
            { "0".repeat(64) },
        )
        for ((index, corrupt) in corruptions.withIndex()) {
            val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
            val rig = ReplicaEngineRig(
                session = session,
                allowHistoricalMutableRootEvidence = false,
            ).also { it.backend.enableCausal = true }
            val babyId = rig.babies.seed(
                localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
            )
            val uuid = "record-hash-$index"
            rig.records.seed(
                RecordEntity(
                    clientUuid = uuid,
                    babyId = babyId,
                    type = "formula",
                    timestamp = 100,
                    payloadJson = """{"amount_ml":60}""",
                    schemaVersion = 2,
                    updatedAt = 100,
                    syncDirty = true,
                    baseVersion = "v0",
                ),
            )
            val sentinelMediaUuid = seedProofCollateralMedia(rig, babyId, "hash-$index")
            var frozenState: DirectProofDurableState? = null
            rig.backend.onCausalCommit = { units ->
                val unit = units.single()
                frozenState = directProofDurableState(rig, listOf(uuid), sentinelMediaUuid)
                rig.backend.nextCausalCommit = CausalBatchResult(
                    generation = session.pullGeneration,
                    cursor = session.pullCursor,
                    results = listOf(
                        CausalUnitResult(
                            status = CausalCommitStatus.ACCEPTED,
                            mutationId = unit.mutationId,
                            requestHash = corrupt(causalMutationContentHash(unit)),
                            generation = session.pullGeneration,
                            stableVersionId = "v0",
                            stableRootJson = unit.rootJson,
                            stableMedia = unit.media,
                        ),
                    ),
                )
            }

            val failure = runCatching {
                rig.engine.synchronize(session, SyncTrigger.LocalWrite)
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalStateException::class.java)

            assertThat(directProofDurableState(rig, listOf(uuid), sentinelMediaUuid))
                .isEqualTo(requireNotNull(frozenState))
            assertThat(rig.backend.pullCount).isEqualTo(0)
            assertThat(rig.backend.causalReconciledUnits).isEmpty()
        }
    }

    @Test
    fun causalProofRejectsMisorderedResultsBeforeRoomSettlement() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(
            session = session,
            allowHistoricalMutableRootEvidence = false,
        ).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
        )
        repeat(2) { index ->
            rig.records.seed(
                RecordEntity(
                    clientUuid = "record-order-$index",
                    babyId = babyId,
                    type = "formula",
                    timestamp = index.toLong(),
                    payloadJson = """{"amount_ml":60}""",
                    schemaVersion = 2,
                    updatedAt = index + 1L,
                    syncDirty = true,
                    baseVersion = "v$index",
                ),
            )
        }
        val recordUuids = listOf("record-order-0", "record-order-1")
        val sentinelMediaUuid = seedProofCollateralMedia(rig, babyId, "order")
        var frozenState: DirectProofDurableState? = null
        rig.backend.onCausalCommit = { units ->
            frozenState = directProofDurableState(rig, recordUuids, sentinelMediaUuid)
            rig.backend.nextCausalCommit = CausalBatchResult(
                generation = session.pullGeneration,
                cursor = session.pullCursor,
                results = units.reversed().map { unit ->
                    CausalUnitResult(
                        status = CausalCommitStatus.ACCEPTED,
                        mutationId = unit.mutationId,
                        requestHash = causalMutationContentHash(unit),
                        generation = session.pullGeneration,
                        stableVersionId = unit.baseVersion,
                        stableRootJson = unit.rootJson,
                        stableMedia = unit.media,
                    )
                },
            )
        }

        val failure = runCatching {
            rig.engine.synchronize(session, SyncTrigger.LocalWrite)
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(directProofDurableState(rig, recordUuids, sentinelMediaUuid))
            .isEqualTo(requireNotNull(frozenState))
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.backend.causalReconciledUnits).isEmpty()
    }

    @Test
    fun incompleteStableProjectionFailsBeforeMutationAck() = runTest {
        data class Corruption(
            val root: (String) -> String,
            val rootPresent: Boolean = true,
            val mediaPresent: Boolean = true,
        )
        val corruptions = listOf(
            Corruption(root = { it }, rootPresent = false),
            Corruption(root = { it }, mediaPresent = false),
            Corruption(root = { """{"note":null}""" }),
        )
        for ((index, corruption) in corruptions.withIndex()) {
            val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
            val rig = ReplicaEngineRig(
                session = session,
                allowHistoricalMutableRootEvidence = false,
            ).also { it.backend.enableCausal = true }
            val babyId = rig.babies.seed(
                localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
            )
            val uuid = "record-projection-$index"
            rig.records.seed(
                RecordEntity(
                    clientUuid = uuid,
                    babyId = babyId,
                    type = "formula",
                    timestamp = 100,
                    payloadJson = """{"amount_ml":60}""",
                    schemaVersion = 2,
                    updatedAt = 100,
                    syncDirty = true,
                    baseVersion = "v0",
                ),
            )
            val sentinelMediaUuid = seedProofCollateralMedia(rig, babyId, "projection-$index")
            var frozenState: DirectProofDurableState? = null
            rig.backend.onCausalCommit = { units ->
                val unit = units.single()
                frozenState = directProofDurableState(rig, listOf(uuid), sentinelMediaUuid)
                rig.backend.nextCausalCommit = CausalBatchResult(
                    generation = session.pullGeneration,
                    cursor = session.pullCursor,
                    results = listOf(
                        CausalUnitResult(
                            status = CausalCommitStatus.ACCEPTED,
                            mutationId = unit.mutationId,
                            requestHash = causalMutationContentHash(unit),
                            generation = session.pullGeneration,
                            stableVersionId = "v0",
                            stableRootJson = corruption.root(unit.rootJson),
                            stableMedia = unit.media,
                            stableRootPresent = corruption.rootPresent,
                            stableMediaPresent = corruption.mediaPresent,
                        ),
                    ),
                )
            }

            val failure = runCatching {
                rig.engine.synchronize(session, SyncTrigger.LocalWrite)
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            assertThat(directProofDurableState(rig, listOf(uuid), sentinelMediaUuid))
                .isEqualTo(requireNotNull(frozenState))
            assertThat(rig.backend.pullCount).isEqualTo(0)
            assertThat(rig.backend.causalReconciledUnits).isEmpty()
        }
    }

    @Test
    fun acceptedAndMergedStableProjectionApplyExplicitNullRecordFields() = runTest {
        data class Case(
            val uuid: String,
            val record: (Long) -> RecordEntity,
            val clearStableField: (String) -> String,
            val assertCleared: (RecordEntity) -> Unit,
        )
        val cases = listOf(
            Case(
                uuid = "record-null-end",
                record = { babyId ->
                    RecordEntity(
                        clientUuid = "record-null-end",
                        babyId = babyId,
                        type = "formula",
                        timestamp = 100,
                        endTimestamp = 200,
                        payloadJson = """{"amount_ml":60}""",
                        schemaVersion = 2,
                        updatedAt = 100,
                        syncDirty = true,
                        baseVersion = "v0",
                    )
                },
                clearStableField = { it.replace("\"end_timestamp\":200", "\"end_timestamp\":null") },
                assertCleared = { assertThat(it.endTimestamp).isNull() },
            ),
            Case(
                uuid = "record-null-wake",
                record = { babyId ->
                    RecordEntity(
                        clientUuid = "record-null-wake",
                        babyId = babyId,
                        type = "sleep",
                        timestamp = 100,
                        payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                        schemaVersion = 2,
                        updatedAt = 100,
                        syncDirty = true,
                        baseVersion = "v0",
                        effectiveWakeObservationClientUuid = "wake-old",
                    )
                },
                clearStableField = {
                    it.replace(
                        "\"effective_wake_observation_client_uuid\":\"wake-old\"",
                        "\"effective_wake_observation_client_uuid\":null",
                    )
                },
                assertCleared = { assertThat(it.effectiveWakeObservationClientUuid).isNull() },
            ),
        )
        for (status in listOf(CausalCommitStatus.ACCEPTED, CausalCommitStatus.MERGED)) {
            for (case in cases) {
                val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
                val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
                val babyId = rig.babies.seed(
                    localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
                )
                rig.records.seed(case.record(babyId))
                rig.backend.onCausalCommit = { units ->
                    val unit = units.single()
                    rig.backend.nextCausalCommit = CausalBatchResult(
                        generation = session.pullGeneration,
                        cursor = session.pullCursor,
                        results = listOf(
                            CausalUnitResult(
                                status = status,
                                mutationId = unit.mutationId,
                                requestHash = causalMutationContentHash(unit),
                                generation = session.pullGeneration,
                                stableVersionId = "v1",
                                stableRootJson = case.clearStableField(unit.rootJson),
                                stableMedia = unit.media,
                            ),
                        ),
                    )
                }

                rig.engine.synchronize(session, SyncTrigger.LocalWrite)

                case.assertCleared(requireNotNull(rig.records.getByClientUuid(case.uuid)))
            }
        }
    }

    @Test
    fun fullPullPreservesLegacyAbsentAndAppliesConcreteThenNullEffectiveWake() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(syncDirty = false, familyAuthority = true, baseVersion = "v-b"),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = "sleep-pull-nullable",
                babyId = babyId,
                type = "sleep",
                timestamp = 100,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                schemaVersion = 2,
                updatedAt = 100,
                syncDirty = false,
                baseVersion = "v0",
                effectiveWakeObservationClientUuid = "wake-old",
            ),
        )
        val concreteWake = "00000000-0000-4000-8000-000000000004"
        val effectiveValues = listOf(
            null,
            "\"effective_wake_observation_client_uuid\":\"$concreteWake\",",
            "\"effective_wake_observation_client_uuid\":null,",
        )
        val expected = listOf("wake-old", concreteWake, null)
        effectiveValues.forEachIndexed { index, effectiveField ->
            rig.backend.nextPull = PullResult(
                entities = listOf(
                    SyncEntity(
                        type = "record",
                        clientUuid = "sleep-pull-nullable",
                        payloadJson = """
                            {
                              "baby_client_uuid":"baby-local",
                              "created_by_membership_id":"membership-b",
                              "type":"sleep",
                              "custom_item_client_uuid":null,
                              "timestamp":100,
                              "end_timestamp":null,
                              "note":null,
                              ${effectiveField.orEmpty()}
                              "payload_json":{"is_nap":false,"anomaly_flag":false},
                              "schema_version":2
                            }
                        """.trimIndent(),
                        updatedAt = 101L + index,
                        versionId = "v${index + 1}",
                    ),
                ),
                cursor = index + 1L,
                generation = session.pullGeneration,
                hasMore = false,
            )

            rig.engine.synchronize(session, SyncTrigger.PullToRefresh)

            assertThat(
                rig.records.getByClientUuid("sleep-pull-nullable")!!
                    .effectiveWakeObservationClientUuid,
            ).isEqualTo(expected[index])
        }
    }

    @Test
    fun missingCausalCapabilityFailsClosedBeforeMutableRootLegacyReconcile() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(
            session = session,
            allowHistoricalMutableRootEvidence = false,
        )
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = "record-no-causal-fallback",
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = """{"amount_ml":60}""",
                schemaVersion = 2,
                updatedAt = 100,
                syncDirty = true,
            ),
        )

        val failure = runCatching {
            rig.engine.synchronize(session, SyncTrigger.LocalWrite)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().contains("因果同步协议")
        assertThat(rig.backend.reconciledUnits).isEmpty()
        assertThat(rig.records.getByClientUuid("record-no-causal-fallback")!!.syncDirty).isTrue()
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
                        stableRootJson = unit.rootJson.replace(
                            "\"note\":\"local note\"",
                            "\"note\":\"remote\"",
                        ),
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
        val recordUuids = listOf("record-proof")
        val sentinelMediaUuid = seedProofCollateralMedia(rig, babyId, "missing-key")
        var frozenState: DirectProofDurableState? = null
        rig.backend.onCausalCommit = {
            frozenState = directProofDurableState(rig, recordUuids, sentinelMediaUuid)
            rig.backend.nextCausalCommit = CausalBatchResult(
                generation = session.pullGeneration,
                cursor = session.pullCursor,
                results = emptyList(),
            )
        }

        val failure = runCatching {
            rig.engine.synchronize(session, SyncTrigger.LocalWrite)
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)

        assertThat(directProofDurableState(rig, recordUuids, sentinelMediaUuid))
            .isEqualTo(requireNotNull(frozenState))
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.backend.causalReconciledUnits).isEmpty()
        assertThat(rig.backend.causalCommittedUnits).hasSize(1)
    }

    @Test
    fun concurrentLocalEditKeepsNewFactAndUsesOldTerminalAsNextBase() = runTest {
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
        var frozenMutationId: String? = null
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
            frozenMutationId = unit.mutationId
            rig.backend.nextCausalCommit = CausalBatchResult(
                generation = session.pullGeneration,
                cursor = session.pullCursor,
                results = listOf(
                    CausalUnitResult(
                        status = CausalCommitStatus.ACCEPTED,
                        mutationId = unit.mutationId,
                        requestHash = causalMutationContentHash(unit),
                        generation = session.pullGeneration,
                        stableVersionId = "v-epoch-1",
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
        assertThat(row.mutationId).isNull()
        assertThat(row.baseVersion).isEqualTo("v-epoch-1")

        rig.backend.onCausalCommit = null
        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        val nextEnvelope = rig.backend.causalCommittedUnits.last().single()
        assertThat(nextEnvelope.mutationId).isNotEqualTo(requireNotNull(frozenMutationId))
        assertThat(nextEnvelope.baseVersion).isEqualTo("v-epoch-1")
        assertThat(nextEnvelope.rootJson).contains("\"note\":\"epoch-2\"")
        val settled = requireNotNull(rig.records.getByClientUuid("record-cas-race"))
        assertThat(settled.note).isEqualTo("epoch-2")
        assertThat(settled.syncDirty).isFalse()
    }

    @Test
    fun pullDoesNotOverwriteDirtyUnfinishedMutationByUpdatedAt() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(
            session = session,
            allowHistoricalMutableRootEvidence = false,
        ).also { it.backend.enableCausal = true }
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
    fun firstCausalPullEstablishesBaselineWithoutOverwritingMigratedDirtyRoot() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(
            session = session,
            allowHistoricalMutableRootEvidence = false,
        ).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(syncDirty = false, familyAuthority = true, baseVersion = "v-b"),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = "record-upgraded-dirty",
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                note = "离线保留",
                payloadJson = """{"amount_ml":60}""",
                schemaVersion = 2,
                updatedAt = 100,
                syncDirty = true,
                baseVersion = null,
                mutationId = null,
            ),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteReplicaRecord("record-upgraded-dirty").copy(
                    updatedAt = 999_999,
                    versionId = "v-upgrade-baseline",
                    payloadJson = """
                        {
                          "baby_client_uuid":"baby-local",
                          "created_by_membership_id":"membership-b",
                          "type":"formula",
                          "custom_item_client_uuid":null,
                          "timestamp":210,
                          "end_timestamp":null,
                          "note":"远端稳定投影",
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
        rig.backend.onCausalCommit = { units ->
            val mutation = units.single()
            assertThat(mutation.baseVersion).isEqualTo("v-upgrade-baseline")
            assertThat(mutation.rootJson).contains("离线保留")
        }

        rig.engine.synchronize(session, SyncTrigger.PullToRefresh)

        val settled = requireNotNull(rig.records.getByClientUuid("record-upgraded-dirty"))
        assertThat(settled.note).isEqualTo("离线保留")
        assertThat(settled.syncDirty).isFalse()
        assertThat(settled.baseVersion).isNotEqualTo("v-upgrade-baseline")
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
    fun generationDriftOnRecordCommitTriggersFullResyncPath() = runTest {
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
        rig.backend.onCausalCommit = { units ->
            if (!driftedOnce) {
                driftedOnce = true
                val unit = units.single()
                rig.backend.nextCausalCommit = CausalBatchResult(
                    generation = "other-generation",
                    cursor = session.pullCursor,
                    results = listOf(
                        CausalUnitResult(
                            status = CausalCommitStatus.ACCEPTED,
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
        // Full recovery dirties the provider Baby, which intentionally remains on
        // the source reconcile path until H11. The no-media Record still retries
        // directly from its durable envelope and settles its terminal.
        assertThat(rig.backend.pullCount).isAtLeast(1)
        assertThat(rig.backend.causalReconciledUnits.flatten().map { it.entityType })
            .containsExactly("baby")
        val recordCommits = rig.backend.causalCommittedUnits.flatten()
            .filter { it.entityType == "record" }
        assertThat(recordCommits).hasSize(2)
        assertThat(recordCommits.all { it.media.isEmpty() }).isTrue()
        assertThat(row.syncDirty).isFalse()
        assertThat(row.mutationId).isNull()
        assertThat(row.baseVersion).isNotNull()
        assertThat(rig.conflictDetails.getFrozenMutation("record", "record-gen")).isNull()
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
            val stableRoot = unit.rootJson
                .replace("\"note\":\"local\"", "\"note\":\"accepted-stable\"")
                .replace("\"updated_at\":100", "\"updated_at\":999")
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
                        stableRootJson = unit.rootJson
                            .replace("\"note\":\"local note\"", "\"note\":\"remote\"")
                            .replace("\"updated_at\":200", "\"updated_at\":50"),
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
        rig.backend.onCausalCommit = { units ->
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

    private data class DirectProofDurableState(
        val session: SyncSession,
        val babies: List<BabyEntity>,
        val records: List<RecordEntity>,
        val collateralMedia: MediaAssetEntity?,
        val envelopes: List<ConflictSnapshotCacheEntity?>,
    )

    private suspend fun seedProofCollateralMedia(
        rig: ReplicaEngineRig,
        babyId: Long,
        suffix: String,
    ): String {
        val uuid = "proof-collateral-$suffix"
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = uuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "proof/$suffix.jpg",
                mime = "image/jpeg",
                createdAt = 1,
                updatedAt = 1,
                syncDirty = false,
            ),
        )
        return uuid
    }

    private suspend fun directProofDurableState(
        rig: ReplicaEngineRig,
        recordUuids: List<String>,
        collateralMediaUuid: String,
    ): DirectProofDurableState = DirectProofDurableState(
        session = rig.preferences.current(),
        babies = rig.babies.listAllIncludingDeleted(),
        records = recordUuids.map { uuid ->
            requireNotNull(rig.records.getByClientUuid(uuid))
        },
        collateralMedia = rig.media.getByClientUuid(collateralMediaUuid),
        envelopes = recordUuids.map { uuid ->
            rig.conflictDetails.getFrozenMutation("record", uuid)
        },
    )
}

@RunWith(Parameterized::class)
class ReplicaSyncEngineCausalRootTypesTest(
    private val entityType: String,
) {
    @Test
    fun migratedDirtyRootLearnsBaselineWithoutLosingLocalIntent() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val settlement = CausalSettlement(
            backend = rig.backend,
            recordDao = rig.records,
            carePlanDao = rig.carePlans,
            babyDao = rig.babies,
            mediaDao = rig.media,
            customItemDao = rig.customItems,
            wakeObservationDao = rig.wakeObservations,
            conflictSummaryDao = rig.conflictSummaries,
            conflictSnapshotCacheDao = rig.conflictDetails,
            mediaFiles = rig.mediaFiles,
            transactionRunner = rig.transactions,
            requireRemoteAllowed = {},
            protectDirtyCausalRoots = true,
        )
        when (entityType) {
            "baby" -> rig.babies.seed(
                localReplicaBaby().copy(
                    nickname = "本机宝宝",
                    syncDirty = true,
                    familyAuthority = true,
                    updatedAt = 50,
                    baseVersion = null,
                    mutationId = "old-null-base-envelope",
                ),
            )
            "record" -> {
                val babyId = rig.babies.seed(
                    localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
                )
                val recordId = rig.records.seed(
                    RecordEntity(
                        clientUuid = "root-record",
                        babyId = babyId,
                        type = "formula",
                        timestamp = 1,
                        note = "本机修改",
                        payloadJson = """{"amount_ml":88}""",
                        schemaVersion = 2,
                        updatedAt = 50,
                        syncDirty = true,
                        mutationId = "old-null-base-envelope",
                    ),
                )
                rig.media.seed(
                    com.lezi.babylog.core.database.MediaAssetEntity(
                        recordId = recordId,
                        clientUuid = "root-record-photo",
                        localUri = "/private/local-photo.jpg",
                        createdAt = 40,
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
                        note = "本机计划",
                        syncDirty = true,
                        mutationId = "old-null-base-envelope",
                    ),
                )
            }
            "custom_item" -> rig.customItems.seed(
                localReplicaCustomItem("root-custom", "membership-a", 50).copy(
                    name = "本机自定义",
                    syncDirty = true,
                    deletedAt = 49,
                    mutationId = "old-null-base-envelope",
                ),
            )
            "wake_observation" -> rig.wakeObservations.seed(
                com.lezi.babylog.core.database.causal.WakeObservationEntity(
                    clientUuid = "root-wake",
                    sleepRecordClientUuid = "sleep-local",
                    wakeTimestamp = 2,
                    observerMembershipId = "membership-a",
                    note = "本机醒来",
                    withdrawn = true,
                    updatedAt = 50,
                    syncDirty = true,
                    mutationId = "old-null-base-envelope",
                ),
            )
        }

        val clientUuid = when (entityType) {
            "baby" -> "baby-local"
            "record" -> "root-record"
            "care_plan" -> "root-plan"
            "custom_item" -> "root-custom"
            "wake_observation" -> "root-wake"
            else -> error(entityType)
        }
        assertThat(
            settlement.shouldApplyStablePull(
                entityType = entityType,
                clientUuid = clientUuid,
                remoteVersionId = "remote-baseline-$entityType",
                forceAuthority = false,
            ),
        ).isFalse()

        when (entityType) {
            "baby" -> with(requireNotNull(rig.babies.getByClientUuid(clientUuid))) {
                assertThat(nickname).isEqualTo("本机宝宝")
                assertThat(baseVersion).isEqualTo("remote-baseline-baby")
                assertThat(mutationId).isNull()
            }
            "record" -> with(requireNotNull(rig.records.getByClientUuid(clientUuid))) {
                assertThat(note).isEqualTo("本机修改")
                assertThat(baseVersion).isEqualTo("remote-baseline-record")
                assertThat(mutationId).isNull()
                assertThat(rig.media.getByClientUuid("root-record-photo")!!.localUri)
                    .isEqualTo("/private/local-photo.jpg")
            }
            "care_plan" -> with(requireNotNull(rig.carePlans.getByClientUuid(clientUuid))) {
                assertThat(note).isEqualTo("本机计划")
                assertThat(baseVersion).isEqualTo("remote-baseline-care_plan")
                assertThat(mutationId).isNull()
            }
            "custom_item" -> with(requireNotNull(rig.customItems.getByClientUuid(clientUuid))) {
                assertThat(name).isEqualTo("本机自定义")
                assertThat(deletedAt).isEqualTo(49L)
                assertThat(baseVersion).isEqualTo("remote-baseline-custom_item")
                assertThat(mutationId).isNull()
            }
            "wake_observation" -> with(
                requireNotNull(rig.wakeObservations.getByClientUuid(clientUuid)),
            ) {
                assertThat(note).isEqualTo("本机醒来")
                assertThat(withdrawn).isTrue()
                assertThat(baseVersion).isEqualTo("remote-baseline-wake_observation")
                assertThat(mutationId).isNull()
            }
        }
    }

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

        if (entityType == "record") {
            assertThat(rig.backend.causalReconciledUnits).isEmpty()
        } else {
            assertThat(rig.backend.causalReconciledUnits).isNotEmpty()
        }
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
