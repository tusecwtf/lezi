package com.lezi.babylog.sync.engine
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.FamilyEntity
import com.lezi.babylog.core.database.FulfillmentCandidateEntity
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.MediaLocalPathGate
import com.lezi.babylog.core.database.RecordEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.AuthorityDisposition
import com.lezi.babylog.sync.backend.AuthorityProofException
import com.lezi.babylog.sync.backend.AuthorityResult
import com.lezi.babylog.sync.backend.ReconcileResult
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.media.ReferenceAwareMediaFileCleanup
import com.lezi.babylog.sync.session.CreatorAcknowledgementRef
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.PolicyClock
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.receiptFor
import com.lezi.babylog.sync.MemoryBabyDao
import com.lezi.babylog.sync.MemoryCarePlanDao
import com.lezi.babylog.sync.MemoryCustomItemDao
import com.lezi.babylog.sync.MemoryFamilyDao
import com.lezi.babylog.sync.MemoryFulfillmentCandidateDao
import com.lezi.babylog.sync.MemoryMediaDao
import com.lezi.babylog.sync.MemoryRecordDao
import com.lezi.babylog.sync.MemorySyncPreferences
import com.lezi.babylog.sync.RecordingSyncBackend
import com.lezi.babylog.sync.RecordingTransactionRunner
import com.lezi.babylog.sync.TestMediaFileStore

class ReplicaSyncEngineTest {
    @Test
    fun adoptRemoteAtomicallyRemovesLocalMediaMissingFromCanonicalManifest() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session)
        val babyId = rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        val recordUuid = "record-with-losing-local-photo"
        val recordId = rig.records.seed(
            RecordEntity(
                clientUuid = recordUuid,
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = """{"amount_ml":60}""",
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        val losingMediaUuid = "11111111-1111-4111-8111-111111111119"
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = losingMediaUuid,
                kind = "log",
                recordId = recordId,
                localUri = "record-media/losing-authority-photo.jpg",
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        rig.backend.onReconcile = { units ->
            val unit = units.single()
            rig.backend.nextReconcile = ReconcileResult(
                generation = session.pullGeneration,
                cursor = session.pullCursor,
                results = listOf(
                    AuthorityResult(
                        type = unit.root.type,
                        clientUuid = unit.root.clientUuid,
                        requestContentHash = unit.contentHash,
                        disposition = AuthorityDisposition.AdoptRemote,
                        reason = "server_lww_winner",
                        remoteRoot = remoteReplicaRecord(recordUuid).copy(updatedAt = 200),
                        remoteMedia = emptyList(),
                    ),
                ),
            )
        }

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(rig.records.getByClientUuid(recordUuid)?.payloadJson).contains("90")
        assertThat(rig.media.getByClientUuid(losingMediaUuid)).isNull()
        assertThat(rig.mediaFiles.deleted).contains("record-media/losing-authority-photo.jpg")
        assertThat(rig.backend.stagedBundles).isEmpty()
    }

    @Test
    fun confirmedMediaOnlyRetryAdvancesTheSyntheticRootReceipt() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session)
        val babyId = rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        val recordUuid = "record-lost-media-commit-receipt"
        val recordId = rig.records.seed(
            RecordEntity(
                clientUuid = recordUuid,
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = """{"amount_ml":80}""",
                updatedAt = 100,
                familyPublishedUpdatedAt = 100,
                syncDirty = false,
            ),
        )
        val mediaUuid = "22222222-2222-4222-8222-222222222229"
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = mediaUuid,
                kind = "log",
                recordId = recordId,
                localUri = "record-media/retry.jpg",
                createdAt = 150,
                updatedAt = 150,
                syncDirty = true,
            ),
        )
        rig.backend.onReconcile = { units ->
            val unit = units.single()
            rig.backend.nextReconcile = ReconcileResult(
                generation = session.pullGeneration,
                cursor = session.pullCursor,
                results = listOf(
                    AuthorityResult(
                        type = unit.root.type,
                        clientUuid = unit.root.clientUuid,
                        requestContentHash = unit.contentHash,
                        disposition = AuthorityDisposition.Confirmed,
                        reason = "canonical_equivalent",
                        remoteRoot = unit.root,
                        remoteMedia = unit.media,
                    ),
                ),
            )
        }

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        val record = rig.records.getByClientUuid(recordUuid)!!
        assertThat(record.updatedAt).isEqualTo(150)
        assertThat(record.familyPublishedUpdatedAt).isEqualTo(150)
        assertThat(record.syncDirty).isFalse()
        assertThat(rig.media.getByClientUuid(mediaUuid)?.syncDirty).isFalse()
        assertThat(rig.backend.stagedBundles).isEmpty()
    }

    @Test
    fun cleanHistoricalMediaTombstonesDoNotOverflowANewerRootPackage() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session)
        val babyId = rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            RecordEntity(
                clientUuid = "record-with-media-history",
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = """{"amount_ml":100}""",
                updatedAt = 300,
                syncDirty = true,
            ),
        )
        repeat(10) { index ->
            rig.media.seed(
                MediaAssetEntity(
                    clientUuid = "33333333-3333-4333-8333-${(index + 1).toString().padStart(12, '0')}",
                    kind = "log",
                    recordId = recordId,
                    localUri = "",
                    createdAt = 100L + index,
                    updatedAt = 200L + index,
                    deletedAt = 200L + index,
                    syncDirty = false,
                ),
            )
        }

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(rig.backend.reconciledUnits.single().single().media).isEmpty()
        assertThat(rig.backend.stagedBundles.single().media).isEmpty()
        assertThat(rig.records.getByClientUuid("record-with-media-history")?.syncDirty).isFalse()
    }

    @Test
    fun invalidAuthorityProofTriggersAFullSnapshotRebuild() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session)
        rig.babies.seed(localReplicaBaby().copy(syncDirty = true))
        var attempt = 0
        rig.backend.onReconcile = { units ->
            attempt++
            if (attempt == 1) {
                throw AuthorityProofException(
                    session.pullGeneration,
                    IllegalArgumentException("incomplete authority response"),
                )
            }
            rig.backend.nextReconcile = ReconcileResult(
                generation = session.pullGeneration,
                cursor = session.pullCursor,
                results = units.map { unit ->
                    AuthorityResult(
                        type = unit.root.type,
                        clientUuid = unit.root.clientUuid,
                        requestContentHash = unit.contentHash,
                        disposition = AuthorityDisposition.Confirmed,
                        reason = "canonical_equivalent",
                        remoteRoot = unit.root,
                        remoteMedia = unit.media,
                    )
                },
            )
        }

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(rig.backend.reconciledUnits).hasSize(2)
        assertThat(rig.backend.pullCursors).containsExactly(0L, 0L, 0L).inOrder()
        assertThat(rig.babies.getByClientUuid("baby-local")?.syncDirty).isFalse()
    }

    @Test
    fun oversizedAuthorityResponseCheckpointTriggersAFullSnapshotRebuild() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session)
        rig.babies.seed(localReplicaBaby().copy(syncDirty = true))
        var attempt = 0
        rig.backend.onReconcile = { units ->
            attempt++
            if (attempt == 1) {
                throw SyncHttpException(
                    statusCode = 409,
                    responseBody = """
                        {
                          "detail":{
                            "code":"authority_response_too_large",
                            "action":"full_resync",
                            "reset_cursor":0,
                            "server_cursor":0,
                            "server_generation":"generation-a"
                          }
                        }
                    """.trimIndent(),
                )
            }
            rig.backend.nextReconcile = ReconcileResult(
                generation = session.pullGeneration,
                cursor = session.pullCursor,
                results = units.map { unit ->
                    AuthorityResult(
                        type = unit.root.type,
                        clientUuid = unit.root.clientUuid,
                        requestContentHash = unit.contentHash,
                        disposition = AuthorityDisposition.Confirmed,
                        reason = "canonical_equivalent",
                        remoteRoot = unit.root,
                        remoteMedia = unit.media,
                    )
                },
            )
        }

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(rig.backend.reconciledUnits).hasSize(2)
        assertThat(rig.backend.pullCursors).containsExactly(0L, 0L, 0L).inOrder()
        assertThat(rig.babies.getByClientUuid("baby-local")?.syncDirty).isFalse()
    }

    @Test
    fun reconcileChunksMustShareOneAuthorityCursorBeforeAnySettlement() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session)
        repeat(65) { index ->
            rig.babies.seed(
                localReplicaBaby().copy(
                    clientUuid = "55555555-5555-4555-8555-${index.toString().padStart(12, '0')}",
                    nickname = "宝宝$index",
                    syncDirty = true,
                ),
            )
        }
        var call = 0
        rig.backend.onReconcile = { units ->
            call++
            rig.backend.nextReconcile = ReconcileResult(
                generation = session.pullGeneration,
                cursor = if (call == 2) 1 else 0,
                results = units.map { unit ->
                    AuthorityResult(
                        type = unit.root.type,
                        clientUuid = unit.root.clientUuid,
                        requestContentHash = unit.contentHash,
                        disposition = AuthorityDisposition.Confirmed,
                        reason = "canonical_equivalent",
                        remoteRoot = unit.root,
                        remoteMedia = unit.media,
                    )
                },
            )
        }

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(call).isEqualTo(4)
        assertThat(rig.babies.listPendingSync()).isEmpty()
        assertThat(rig.backend.stagedBundles).isEmpty()
    }

    @Test
    fun missingPhotoBytesBecomeAnAtomicTombstoneAndOwnerlessMediaIsDiscarded() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session)
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
        )
        val recordId = rig.records.seed(
            RecordEntity(
                clientUuid = "record-with-missing-photo",
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = """{"amount_ml":80}""",
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = "11111111-1111-4111-8111-111111111111",
                kind = "log",
                recordId = recordId,
                localUri = "record-media/missing.jpg",
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = "22222222-2222-4222-8222-222222222222",
                kind = "log",
                recordId = 99_999,
                localUri = "record-media/orphan.jpg",
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        rig.mediaFiles.missing += "record-media/missing.jpg"

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        val repaired = rig.media.getByClientUuid("11111111-1111-4111-8111-111111111111")!!
        assertThat(repaired.deletedAt).isEqualTo(100)
        assertThat(repaired.syncDirty).isFalse()
        assertThat(rig.records.getByClientUuid("record-with-missing-photo")?.syncDirty).isFalse()
        assertThat(rig.media.getByClientUuid("22222222-2222-4222-8222-222222222222")).isNull()
        assertThat(rig.backend.stagedBundles.single().media.single().deletedAt).isEqualTo(100)
        assertThat(rig.mediaFiles.deleted)
            .containsAtLeast("record-media/missing.jpg", "record-media/orphan.jpg")
    }

    @Test
    fun completeAuthorityCycleSettlesConfirmedAdoptedAndLocalOnlyWithoutPublishing() = runTest {
        val ownerSession = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val confirmed = ReplicaEngineRig(ownerSession)
        confirmed.babies.seed(localReplicaBaby().copy(syncDirty = true))
        confirmed.backend.onReconcile = { units ->
            confirmed.backend.nextReconcile = ReconcileResult(
                generation = ownerSession.pullGeneration,
                cursor = ownerSession.pullCursor,
                results = units.map { unit ->
                    AuthorityResult(
                        type = unit.root.type,
                        clientUuid = unit.root.clientUuid,
                        requestContentHash = unit.contentHash,
                        disposition = AuthorityDisposition.Confirmed,
                        reason = "canonical_equivalent",
                        remoteRoot = unit.root,
                        remoteMedia = unit.media,
                    )
                },
            )
        }

        confirmed.engine.synchronize(ownerSession, SyncTrigger.LocalWrite)

        assertThat(confirmed.babies.getByClientUuid("baby-local")?.syncDirty).isFalse()
        assertThat(confirmed.backend.stagedBundles).isEmpty()

        val adopted = ReplicaEngineRig(ownerSession)
        val adoptedBabyId = adopted.babies.seed(localReplicaBaby().copy(syncDirty = false))
        adopted.records.seed(
            RecordEntity(
                clientUuid = "record-equal-body-conflict",
                babyId = adoptedBabyId,
                type = "formula",
                timestamp = 100,
                payloadJson = """{"amount_ml":80}""",
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        adopted.backend.onReconcile = { units ->
            val unit = units.single()
            adopted.backend.nextReconcile = ReconcileResult(
                generation = ownerSession.pullGeneration,
                cursor = ownerSession.pullCursor,
                results = listOf(
                    AuthorityResult(
                        type = unit.root.type,
                        clientUuid = unit.root.clientUuid,
                        requestContentHash = unit.contentHash,
                        disposition = AuthorityDisposition.AdoptRemote,
                        reason = "server_lww_winner",
                        remoteRoot = remoteReplicaRecord(unit.root.clientUuid).copy(updatedAt = 100),
                    ),
                ),
            )
        }

        adopted.engine.synchronize(ownerSession, SyncTrigger.LocalWrite)

        val canonical = adopted.records.getByClientUuid("record-equal-body-conflict")!!
        assertThat(canonical.payloadJson).contains("90")
        assertThat(canonical.syncDirty).isFalse()
        assertThat(adopted.backend.stagedBundles).isEmpty()

        val memberSession = joinedReplicaSession().copy(
            role = FamilyRole.Member,
            membershipId = "member-local-only",
        )
        val localOnly = ReplicaEngineRig(memberSession)
        val localBabyId = localOnly.babies.seed(
            localReplicaBaby().copy(syncDirty = true, familyAuthority = false),
        )
        localOnly.records.seed(
            RecordEntity(
                clientUuid = "record-pre-join-local-only",
                babyId = localBabyId,
                type = "formula",
                timestamp = 100,
                payloadJson = """{"amount_ml":70}""",
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        localOnly.backend.onReconcile = { units ->
            val unit = units.single()
            localOnly.backend.nextReconcile = ReconcileResult(
                generation = memberSession.pullGeneration,
                cursor = memberSession.pullCursor,
                results = listOf(
                    AuthorityResult(
                        type = unit.root.type,
                        clientUuid = unit.root.clientUuid,
                        requestContentHash = unit.contentHash,
                        disposition = AuthorityDisposition.RemoteAbsentRejected,
                        reason = "forbidden_baby",
                    ),
                ),
            )
        }

        localOnly.engine.synchronize(memberSession, SyncTrigger.LocalWrite)

        assertThat(localOnly.babies.getByClientUuid("baby-local")?.syncDirty).isFalse()
        assertThat(localOnly.records.getByClientUuid("record-pre-join-local-only")?.syncDirty)
            .isFalse()
        assertThat(localOnly.records.getByClientUuid("record-pre-join-local-only")).isNotNull()
        assertThat(localOnly.backend.stagedBundles).isEmpty()
    }

    @Test
    fun serverProvenRedundantTombstoneIsTechnicallyDiscardedWithItsMediaBytes() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session)
        val babyId = rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        val recordUuid = "record-redundant-tombstone"
        val recordId = rig.records.seed(
            RecordEntity(
                clientUuid = recordUuid,
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = """{"amount_ml":80}""",
                updatedAt = 200,
                deletedAt = 200,
                syncDirty = true,
            ),
        )
        val mediaUuid = "44444444-4444-4444-8444-444444444444"
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = mediaUuid,
                kind = "log",
                recordId = recordId,
                localUri = "record-media/redundant.jpg",
                createdAt = 100,
                updatedAt = 200,
                deletedAt = 200,
                syncDirty = true,
            ),
        )
        rig.backend.onReconcile = { units ->
            val unit = units.single()
            rig.backend.nextReconcile = ReconcileResult(
                generation = session.pullGeneration,
                cursor = session.pullCursor,
                results = listOf(
                    AuthorityResult(
                        type = unit.root.type,
                        clientUuid = unit.root.clientUuid,
                        requestContentHash = unit.contentHash,
                        disposition = AuthorityDisposition.RemoteAbsentRejected,
                        reason = "redundant_tombstone",
                    ),
                ),
            )
        }

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(rig.records.getByClientUuid(recordUuid)).isNull()
        assertThat(rig.media.getByClientUuid(mediaUuid)).isNull()
        assertThat(rig.mediaFiles.deleted).contains("record-media/redundant.jpg")
        assertThat(rig.backend.stagedBundles).isEmpty()
    }

    @Test
    fun redundantVerdictForMediaOnlyDeltaNeverDeletesTheLiveAtomicRoot() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session)
        val babyId = rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            RecordEntity(
                clientUuid = "record-with-media-only-tombstone",
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = """{"amount_ml":80}""",
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        val mediaUuid = "55555555-5555-4555-8555-555555555555"
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = mediaUuid,
                kind = "log",
                recordId = recordId,
                localUri = "record-media/media-only-tombstone.jpg",
                createdAt = 100,
                updatedAt = 200,
                deletedAt = 200,
                syncDirty = true,
            ),
        )
        rig.backend.onReconcile = { units ->
            val unit = units.single()
            rig.backend.nextReconcile = ReconcileResult(
                generation = session.pullGeneration,
                cursor = session.pullCursor,
                results = listOf(
                    AuthorityResult(
                        type = unit.root.type,
                        clientUuid = unit.root.clientUuid,
                        requestContentHash = unit.contentHash,
                        disposition = AuthorityDisposition.RemoteAbsentRejected,
                        reason = "redundant_tombstone",
                    ),
                ),
            )
        }

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(rig.records.getByClientUuid("record-with-media-only-tombstone")).isNotNull()
        assertThat(rig.media.getByClientUuid(mediaUuid)).isNotNull()
        assertThat(rig.media.getByClientUuid(mediaUuid)?.syncDirty).isFalse()
    }

    @Test
    fun redundantBabyTombstoneWithAMeaningfulRecordKeepsTheIdentityAnchor() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session)
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
                updatedAt = 200,
                deletedAt = 200,
                syncDirty = true,
            ),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = "record-needs-local-baby-anchor",
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = """{"amount_ml":80}""",
                updatedAt = 200,
                syncDirty = true,
            ),
        )
        rig.backend.onReconcile = { units ->
            rig.backend.nextReconcile = ReconcileResult(
                generation = session.pullGeneration,
                cursor = session.pullCursor,
                results = units.map { unit ->
                    val baby = unit.root.type == "baby"
                    AuthorityResult(
                        type = unit.root.type,
                        clientUuid = unit.root.clientUuid,
                        requestContentHash = unit.contentHash,
                        disposition = if (baby) {
                            AuthorityDisposition.RemoteAbsentRejected
                        } else {
                            AuthorityDisposition.RetryAuthority
                        },
                        reason = if (baby) "redundant_tombstone" else "dependency_unresolved",
                    )
                },
            )
        }

        repeat(2) {
            assertThat(
                runCatching {
                    rig.engine.synchronize(session, SyncTrigger.LocalWrite)
                }.exceptionOrNull(),
            ).isInstanceOf(IllegalArgumentException::class.java)
        }

        assertThat(rig.babies.getByClientUuid("baby-local")).isNotNull()
        assertThat(rig.records.getByClientUuid("record-needs-local-baby-anchor")?.syncDirty)
            .isTrue()
        assertThat(rig.backend.reconciledUnits.last().map { it.root.clientUuid })
            .contains("record-needs-local-baby-anchor")
    }

    @Test
    fun redundantTombstoneEligibilityConvergesAcrossTheCompleteDependencyGraph() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session)
        val babyId = rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        val customUuid = "custom-technical-but-still-referenced"
        val customId = rig.customItems.seed(
            localReplicaCustomItem(
                clientUuid = customUuid,
                creatorMembershipId = session.membershipId,
                updatedAt = 200,
            ).copy(deletedAt = 200, syncDirty = true),
        )
        val recordUuid = "record-technical-but-still-referenced"
        rig.records.seed(
            RecordEntity(
                clientUuid = recordUuid,
                babyId = babyId,
                type = "custom",
                timestamp = 100,
                payloadJson = """{"title":"抚触","custom_item_id":$customId}""",
                updatedAt = 200,
                deletedAt = 200,
                syncDirty = true,
            ),
        )
        rig.fulfillmentCandidates.seed(
            FulfillmentCandidateEntity(
                clientUuid = "candidate-retaining-the-record",
                carePlanClientUuid = "plan-not-in-the-technical-set",
                recordClientUuid = recordUuid,
                confirmedAt = 300,
                updatedAt = 300,
                syncDirty = false,
            ),
        )
        rig.backend.onReconcile = { units ->
            rig.backend.nextReconcile = ReconcileResult(
                generation = session.pullGeneration,
                cursor = session.pullCursor,
                results = units.map { unit ->
                    AuthorityResult(
                        type = unit.root.type,
                        clientUuid = unit.root.clientUuid,
                        requestContentHash = unit.contentHash,
                        disposition = AuthorityDisposition.RemoteAbsentRejected,
                        reason = "redundant_tombstone",
                    )
                },
            )
        }

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(rig.customItems.get(customUuid)).isNotNull()
        assertThat(rig.records.getByClientUuid(recordUuid)).isNotNull()
        assertThat(
            rig.fulfillmentCandidates.getByClientUuid("candidate-retaining-the-record"),
        ).isNotNull()
        assertThat(rig.customItems.get(customUuid)?.syncDirty).isFalse()
        assertThat(rig.records.getByClientUuid(recordUuid)?.syncDirty).isFalse()
    }

    @Test
    fun redundantAtomicTombstoneCasMissFailsBeforeDeletingTheRoot() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session)
        val babyId = rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        val recordUuid = "record-atomic-cas-miss"
        val recordId = rig.records.seed(
            RecordEntity(
                clientUuid = recordUuid,
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = """{"amount_ml":80}""",
                updatedAt = 200,
                deletedAt = 200,
                syncDirty = true,
            ),
        )
        val mediaUuid = "66666666-6666-4666-8666-666666666666"
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = mediaUuid,
                kind = "log",
                recordId = recordId,
                localUri = "record-media/atomic-cas-miss.jpg",
                createdAt = 100,
                updatedAt = 200,
                deletedAt = 200,
                syncDirty = true,
            ),
        )
        rig.media.failNextTombstoneDelete = true
        rig.backend.onReconcile = { units ->
            val unit = units.single()
            rig.backend.nextReconcile = ReconcileResult(
                generation = session.pullGeneration,
                cursor = session.pullCursor,
                results = listOf(
                    AuthorityResult(
                        type = unit.root.type,
                        clientUuid = unit.root.clientUuid,
                        requestContentHash = unit.contentHash,
                        disposition = AuthorityDisposition.RemoteAbsentRejected,
                        reason = "redundant_tombstone",
                    ),
                ),
            )
        }

        val failure = runCatching {
            rig.engine.synchronize(session, SyncTrigger.LocalWrite)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(rig.records.getByClientUuid(recordUuid)).isNotNull()
        assertThat(rig.media.getByClientUuid(mediaUuid)).isNotNull()
    }

    @Test
    fun retryAuthorityAndConcurrentEditNeverClearFrozenWork() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val retry = ReplicaEngineRig(session)
        retry.babies.seed(localReplicaBaby().copy(syncDirty = true))
        retry.backend.onReconcile = { units ->
            val unit = units.single()
            retry.backend.nextReconcile = ReconcileResult(
                generation = session.pullGeneration,
                cursor = session.pullCursor,
                results = listOf(
                    AuthorityResult(
                        type = unit.root.type,
                        clientUuid = unit.root.clientUuid,
                        requestContentHash = unit.contentHash,
                        disposition = AuthorityDisposition.RetryAuthority,
                        reason = "dependency_unresolved",
                    ),
                ),
            )
        }

        val failure = runCatching {
            retry.engine.synchronize(session, SyncTrigger.LocalWrite)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(retry.babies.getByClientUuid("baby-local")?.syncDirty).isTrue()
        assertThat(retry.backend.stagedBundles).isEmpty()

        val concurrent = ReplicaEngineRig(session)
        concurrent.babies.seed(localReplicaBaby().copy(syncDirty = true))
        concurrent.backend.onReconcile = { units ->
            val unit = units.single()
            val current = concurrent.babies.getByClientUuid("baby-local")!!
            concurrent.babies.update(
                current.copy(nickname = "同步期间的新编辑", updatedAt = 200, syncDirty = true),
            )
            concurrent.backend.nextReconcile = ReconcileResult(
                generation = session.pullGeneration,
                cursor = session.pullCursor,
                results = listOf(
                    AuthorityResult(
                        type = unit.root.type,
                        clientUuid = unit.root.clientUuid,
                        requestContentHash = unit.contentHash,
                        disposition = AuthorityDisposition.Confirmed,
                        reason = "canonical_equivalent",
                        remoteRoot = unit.root,
                    ),
                ),
            )
        }

        concurrent.engine.synchronize(session, SyncTrigger.LocalWrite)

        val edited = concurrent.babies.getByClientUuid("baby-local")!!
        assertThat(edited.nickname).isEqualTo("同步期间的新编辑")
        assertThat(edited.updatedAt).isEqualTo(200)
        assertThat(edited.syncDirty).isTrue()
        assertThat(concurrent.backend.stagedBundles).isEmpty()
    }

    @Test
    fun dependencyRetryPublishesProvenRootThenRefreezesToACompleteFixedPoint() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session)
        val babyId = rig.babies.seed(localReplicaBaby().copy(syncDirty = true))
        rig.records.seed(
            RecordEntity(
                clientUuid = "record-waits-for-baby",
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = """{"amount_ml":80}""",
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        var pass = 0
        rig.backend.onReconcile = { units ->
            pass++
            rig.backend.nextReconcile = ReconcileResult(
                generation = session.pullGeneration,
                cursor = session.pullCursor,
                results = units.map { unit ->
                    AuthorityResult(
                        type = unit.root.type,
                        clientUuid = unit.root.clientUuid,
                        requestContentHash = unit.contentHash,
                        disposition = if (pass == 1 && unit.root.type == "record") {
                            AuthorityDisposition.RetryAuthority
                        } else {
                            AuthorityDisposition.Publish
                        },
                        reason = if (pass == 1 && unit.root.type == "record") {
                            "dependency_unresolved"
                        } else {
                            "authoritative_absence"
                        },
                    )
                },
            )
        }

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(rig.backend.reconciledUnits.map { it.size }).containsExactly(2, 1).inOrder()
        assertThat(rig.backend.stagedBundles.map { it.root.type })
            .containsExactly("baby", "record")
            .inOrder()
        assertThat(rig.babies.getByClientUuid("baby-local")?.syncDirty).isFalse()
        assertThat(rig.records.getByClientUuid("record-waits-for-baby")?.syncDirty).isFalse()
    }

    @Test
    fun ownerReconcilesBeforeBuildingAndPublishingTheRoomPlan() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session)
        rig.babies.seed(localReplicaBaby().copy(syncDirty = true))

        val outcome = rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.backend.syncOrder)
            .containsExactly("pull:0", "reconcile:1", "stage:baby")
            .inOrder()
    }

    @Test
    fun reconcileRemovesRemoteNewerIdentityBeforeTheEphemeralPlanIsBuilt() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session)
        val babyId = rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        val recordUuid = "record-remote-newer-before-plan"
        rig.records.seed(
            RecordEntity(
                clientUuid = recordUuid,
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = """{"amount_ml":60}""",
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(remoteReplicaRecord(recordUuid).copy(updatedAt = 200)),
            cursor = 1,
            generation = session.pullGeneration,
            hasMore = false,
        )

        val outcome = rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.records.getByClientUuid(recordUuid)?.syncDirty).isFalse()
        assertThat(rig.backend.stagedBundles.map { it.root.clientUuid })
            .doesNotContain(recordUuid)
    }

    @Test
    fun midPushRecordEditKeepsRoomDirtyAndNextCycleReplansTheNewRevision() = runTest {
        val session = joinedReplicaSession()
        val rig = ReplicaEngineRig(session)
        val babyId = rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            RecordEntity(
                clientUuid = "record-mid-push-edit",
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                note = "旧内容",
                payloadJson = """{"amount_ml":80}""",
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        val mediaUuid = "77777777-7777-4777-8777-777777777777"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "photos/mid-push.jpg",
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        rig.backend.onPutBundleMedia = { uploadedUuid ->
            if (uploadedUuid == mediaUuid) {
                val live = requireNotNull(rig.records.getByClientUuid("record-mid-push-edit"))
                rig.records.update(
                    live.copy(
                        note = "上传途中产生的新内容",
                        payloadJson = """{"amount_ml":120}""",
                        updatedAt = 200,
                        syncDirty = true,
                    ),
                )
            }
        }

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        val published = rig.backend.stagedBundles.single { it.root.type == "record" }.root
        val live = requireNotNull(rig.records.getByClientUuid("record-mid-push-edit"))
        assertThat(published.updatedAt).isEqualTo(100)
        assertThat(published.payloadJson).contains("80")
        assertThat(published.payloadJson).doesNotContain("120")
        assertThat(live.note).isEqualTo("上传途中产生的新内容")
        assertThat(live.updatedAt).isEqualTo(200)
        assertThat(live.syncDirty).isTrue()

        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.LocalWrite)

        val retried = rig.backend.stagedBundles
            .filter { it.root.type == "record" }
            .last()
            .root
        assertThat(retried.updatedAt).isEqualTo(200)
        assertThat(retried.payloadJson).contains("120")
        assertThat(rig.records.getByClientUuid("record-mid-push-edit")?.syncDirty).isFalse()
    }

    @Test
    fun divergentNextFeedPlansHealToOneOpenAndReconcileBothAlarms() = runTest {
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Member,
            membershipId = "member-loser",
        )
        val rig = ReplicaEngineRig(session)
        rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        val laterLocalUuid = "aaaaaaaa-aaaa-3aaa-8aaa-aaaaaaaaaaaa"
        val earlierPeerUuid = "bbbbbbbb-bbbb-3bbb-8bbb-bbbbbbbbbbbb"
        val laterLocal = SyncEntity(
            type = "care_plan",
            clientUuid = laterLocalUuid,
            payloadJson =
                """{"baby_client_uuid":"baby-local","type":"formula","custom_item_client_uuid":null,"scheduled_at":9000000002000,"scheduled_zone_id":"Asia/Shanghai","note":"[[lezi:next-feed:v1]]","status":"pending","payload_json":{"amount_ml":0},"schema_version":2,"created_by_membership_id":"member-loser","fulfilled_record_client_uuid":null,"fulfilled_at":null}""",
            updatedAt = 300,
        )
        val earlierPeer = SyncEntity(
            type = "care_plan",
            clientUuid = earlierPeerUuid,
            payloadJson =
                """{"baby_client_uuid":"baby-local","type":"formula","custom_item_client_uuid":null,"scheduled_at":9000000001000,"scheduled_zone_id":"Asia/Shanghai","note":"[[lezi:next-feed:v1]]","status":"pending","payload_json":{"amount_ml":0},"schema_version":2,"created_by_membership_id":"member-winner","fulfilled_record_client_uuid":null,"fulfilled_at":null}""",
            updatedAt = 200,
        )

        // Apply in the opposite order from the deterministic winner to prove
        // Room insertion ids and pull ordering cannot select the open intent.
        rig.engine.applyInitialEntities(session, listOf(laterLocal, earlierPeer))

        val open = rig.carePlans.listAllIncludingDeleted().filter {
            it.deletedAt == null &&
                it.status in setOf("pending", "missed") &&
                it.note?.startsWith("[[lezi:next-feed:v1]]") == true
        }
        assertThat(open.map(CarePlanEntity::clientUuid)).containsExactly(earlierPeerUuid)
        val loser = rig.carePlans.getByClientUuid(laterLocalUuid)!!
        assertThat(loser.deletedAt).isNotNull()
        assertThat(loser.syncDirty).isTrue()
        assertThat(rig.carePlanAppliedBatches.flatten())
            .containsExactly(laterLocalUuid, earlierPeerUuid)

        val winnerReplica = ReplicaEngineRig(
            session.copy(membershipId = "member-winner"),
        )
        winnerReplica.babies.seed(localReplicaBaby().copy(syncDirty = false))
        winnerReplica.engine.applyInitialEntities(
            session.copy(membershipId = "member-winner"),
            listOf(laterLocal, earlierPeer),
        )

        val foreignLoser = winnerReplica.carePlans.getByClientUuid(laterLocalUuid)!!
        assertThat(foreignLoser.deletedAt).isNull()
        assertThat(foreignLoser.status).isEqualTo("skipped")
        assertThat(foreignLoser.syncDirty).isFalse()
        assertThat(winnerReplica.carePlans.listAllIncludingDeleted().filter {
            it.deletedAt == null && it.status in setOf("pending", "missed")
        }.map(CarePlanEntity::clientUuid)).containsExactly(earlierPeerUuid)
        assertThat(winnerReplica.carePlanAppliedBatches.flatten())
            .containsExactly(laterLocalUuid, earlierPeerUuid)
    }

    @Test
    fun remoteWakeClosesAConcurrentOpenSleepWithDifferentUuid() = runTest {
        val session = joinedReplicaSession()
        val rig = ReplicaEngineRig(session)
        val babyId = rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        rig.records.seed(
            RecordEntity(
                clientUuid = "sleep-open-other-device",
                babyId = babyId,
                type = "sleep",
                timestamp = 1_000L,
                endTimestamp = null,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                schemaVersion = 2,
                updatedAt = 100L,
                syncDirty = false,
            ),
        )
        val remoteWake = SyncEntity(
            type = "record",
            clientUuid = "sleep-woken-first-device",
            payloadJson = """
                {
                  "baby_client_uuid":"baby-local",
                  "created_by_membership_id":"membership-b",
                  "type":"sleep",
                  "custom_item_client_uuid":null,
                  "timestamp":900,
                  "end_timestamp":1500,
                  "note":null,
                  "payload_json":{"is_nap":false,"anomaly_flag":false},
                  "schema_version":2
                }
            """.trimIndent(),
            updatedAt = 200L,
        )

        rig.engine.applyInitialEntities(session, listOf(remoteWake))

        assertThat(rig.records.listOpenSleeps(babyId)).isEmpty()
        val healed = rig.records.getByClientUuid("sleep-open-other-device")!!
        assertThat(healed.endTimestamp).isEqualTo(1_500L)
        assertThat(healed.payloadJson).contains("\"anomaly_flag\":true")
        assertThat(healed.syncDirty).isTrue()
        assertThat(healed.updatedAt).isGreaterThan(200L)
    }

    @Test
    fun dirtyRecordAdoptsStrictlyNewerRemoteTombstone() = runTest {
        val session = joinedReplicaSession()
        val rig = ReplicaEngineRig(session)
        val babyId = rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        rig.records.seed(
            RecordEntity(
                clientUuid = "dirty-record-newer-remote",
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                note = "本机旧修改",
                payloadJson = "{\"amount_ml\":90}",
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        val remote = remoteReplicaRecord("dirty-record-newer-remote").copy(
            updatedAt = 200,
            deletedAt = 200,
        )

        rig.engine.applyInitialEntities(session, listOf(remote))

        val applied = rig.records.getByClientUuid("dirty-record-newer-remote")!!
        assertThat(applied.updatedAt).isEqualTo(200)
        assertThat(applied.deletedAt).isEqualTo(200)
        assertThat(applied.syncDirty).isFalse()
    }

    @Test
    fun ownerDirtyBabyFailsClosedInsteadOfClearingConcurrentProfileEdit() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session)
        rig.babies.seed(
            localReplicaBaby().copy(
                nickname = "本机编辑中",
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        val remote = remoteReplicaBaby().copy(
            clientUuid = "baby-local",
            updatedAt = 200,
        )

        val failure = runCatching {
            rig.engine.applyInitialEntities(session, listOf(remote))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().contains("引用尚未就绪")
        val kept = rig.babies.getByClientUuid("baby-local")!!
        assertThat(kept.nickname).isEqualTo("本机编辑中")
        assertThat(kept.updatedAt).isEqualTo(100)
        assertThat(kept.syncDirty).isTrue()
    }

    @Test
    fun equalRevisionRemoteTombstonesAreAdoptedForCustomItemAndMedia() = runTest {
        val session = joinedReplicaSession()
        val rig = ReplicaEngineRig(session)
        val babyId = rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            RecordEntity(
                clientUuid = "record-for-equal-media-tombstone",
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = "{\"amount_ml\":90}",
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        val mediaUuid = "78787878-7878-4787-8787-787878787878"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "photos/equal-tombstone.jpg",
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        rig.customItems.seed(
            CustomItemEntity(
                clientUuid = "equal-custom-tombstone",
                familyId = 1,
                name = "本机定义",
                iconSlot = 1,
                createdByMembershipId = "membership-a",
                updatedAt = 100,
                syncDirty = true,
            ),
        )

        rig.engine.applyInitialEntities(
            session,
            listOf(
                SyncEntity(
                    type = "custom_item",
                    clientUuid = "equal-custom-tombstone",
                    payloadJson =
                        """{"name":"远端定义","icon_slot":2,"created_by_membership_id":"membership-a"}""",
                    updatedAt = 100,
                    deletedAt = 100,
                ),
                SyncEntity(
                    type = "media",
                    clientUuid = mediaUuid,
                    payloadJson =
                        """{"kind":"log","record_client_uuid":"record-for-equal-media-tombstone","care_plan_client_uuid":null,"baby_client_uuid":null,"mime":"image/jpeg","width":null,"height":null,"byte_size":4}""",
                    updatedAt = 100,
                    deletedAt = 100,
                ),
            ),
        )

        val custom = rig.customItems.getByClientUuid("equal-custom-tombstone")!!
        assertThat(custom.deletedAt).isEqualTo(100)
        assertThat(custom.syncDirty).isFalse()
        val media = rig.media.getByClientUuid(mediaUuid)!!
        assertThat(media.deletedAt).isEqualTo(100)
        assertThat(media.syncDirty).isFalse()
    }

    @Test
    fun reauthRequiredPreviousIdentityStillOwnsItsStableMediaReceipt() = runTest {
        val active = joinedReplicaSession()
        val previous = active.copy(
            accessToken = "",
            refreshToken = "",
            reauthRequired = true,
        )
        val rig = ReplicaEngineRig(previous)
        val babyId = rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            RecordEntity(
                clientUuid = "reauth-record",
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = "{\"amount_ml\":90}",
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        val mediaUuid = "76767676-7676-7676-7676-767676767676"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                localUri = "photos/reauth.jpg",
                remoteUri = active.receiptFor(mediaUuid),
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )

        rig.engine.resetLocalSyncReceipts(
            previous = previous,
            invalidateCurrentReceipts = false,
            crossingFamilyBoundary = false,
        )

        assertThat(rig.media.getByClientUuid(mediaUuid)?.remoteUri)
            .isEqualTo(active.receiptFor(mediaUuid))
    }
    @Test
    fun pullAcceptsServerAnonymizedRecordAndPlanAuthorsAsFamilyFallback() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session)
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = "anonymous-record",
                babyId = babyId,
                type = "formula",
                timestamp = 900,
                payloadJson = """{"amount_ml":70}""",
                createdByMembershipId = "deleted-member",
                updatedAt = 400,
                syncDirty = false,
            ),
        )
        rig.carePlans.seed(
            localReplicaCarePlan("anonymous-plan", "deleted-member", updatedAt = 401).copy(
                babyId = babyId,
            ),
        )

        rig.engine.applyInitialEntities(
            session,
            listOf(
                SyncEntity(
                    type = "record",
                    clientUuid = "anonymous-record",
                    payloadJson =
                        """{"baby_client_uuid":"baby-local","created_by_membership_id":null,"type":"formula","custom_item_client_uuid":null,"timestamp":1000,"end_timestamp":null,"note":null,"payload_json":{"amount_ml":80},"schema_version":2}""",
                    updatedAt = 500,
                ),
                SyncEntity(
                    type = "care_plan",
                    clientUuid = "anonymous-plan",
                    payloadJson =
                        """{"baby_client_uuid":"baby-local","type":"formula","custom_item_client_uuid":null,"scheduled_at":9000000001000,"scheduled_zone_id":"Asia/Shanghai","note":null,"status":"pending","payload_json":{"amount_ml":120},"schema_version":2,"created_by_membership_id":null,"fulfilled_record_client_uuid":null,"fulfilled_at":null}""",
                    updatedAt = 501,
                ),
            ),
        )

        assertThat(rig.records.getByClientUuid("anonymous-record")?.createdByMembershipId)
            .isEmpty()
        assertThat(rig.carePlans.getByClientUuid("anonymous-plan")?.createdByMembershipId)
            .isEmpty()
    }

    @Test
    fun concurrentNextFeedCreateAcceptsNasWinnerWithoutAQueuedSecondTruth() = runTest {
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Member,
            membershipId = "member-local",
        )
        val rig = ReplicaEngineRig(session)
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
        )
        val planUuid = "11111111-1111-3111-8111-111111111111"
        rig.carePlans.seed(
            localReplicaCarePlan(planUuid, "member-local", updatedAt = 300).copy(
                babyId = babyId,
                note = "[[lezi:next-feed:v1]]",
                payloadJson = """{"amount_ml":0}""",
                syncDirty = true,
            ),
        )
        val remote = SyncEntity(
            type = "care_plan",
            clientUuid = planUuid,
            payloadJson =
                """{"baby_client_uuid":"baby-local","type":"formula","custom_item_client_uuid":null,"scheduled_at":9000000001000,"scheduled_zone_id":"Asia/Shanghai","note":"[[lezi:next-feed:v1]]","status":"pending","payload_json":{"amount_ml":0},"schema_version":2,"created_by_membership_id":"member-remote","fulfilled_record_client_uuid":null,"fulfilled_at":null}""",
            updatedAt = 250,
        )

        rig.engine.applyInitialEntities(session, listOf(remote))

        val winner = rig.carePlans.getByClientUuid(planUuid)!!
        assertThat(winner.createdByMembershipId).isEqualTo("member-remote")
        assertThat(winner.scheduledAt).isEqualTo(9_000_000_001_000)
        assertThat(winner.syncDirty).isFalse()
    }

    @Test
    fun memberNextFeedLocalWritePullsNasWinnerAgainAfterConcurrentNoOpPush() = runTest {
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Member,
            membershipId = "member-local",
        )
        val rig = ReplicaEngineRig(session)
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
        )
        val planUuid = "22222222-2222-3222-8222-222222222222"
        val localPlanId = rig.carePlans.seed(
            localReplicaCarePlan(planUuid, "member-local", updatedAt = 300).copy(
                babyId = babyId,
                note = "[[lezi:next-feed:v1]]",
                payloadJson = """{"amount_ml":0}""",
                syncDirty = true,
            ),
        )
        val losingMediaUuid = "33333333-3333-3333-8333-333333333333"
        rig.media.seed(
            MediaAssetEntity(
                carePlanId = localPlanId,
                clientUuid = losingMediaUuid,
                kind = "log",
                localUri = "photos/losing-next-feed.jpg",
                createdAt = 300,
                updatedAt = 300,
                syncDirty = true,
            ),
        )
        val nasWinner = SyncEntity(
            type = "care_plan",
            clientUuid = planUuid,
            payloadJson =
                """{"baby_client_uuid":"baby-local","type":"formula","custom_item_client_uuid":null,"scheduled_at":9000000001000,"scheduled_zone_id":"Asia/Shanghai","note":"[[lezi:next-feed:v1]]","status":"pending","payload_json":{"amount_ml":0},"schema_version":2,"created_by_membership_id":"member-remote","fulfilled_record_client_uuid":null,"fulfilled_at":null}""",
            updatedAt = 250,
        )
        rig.backend.pullResults += PullResult(
            entities = emptyList(),
            cursor = 0,
            generation = "generation-a",
            hasMore = false,
        )
        rig.backend.pullResults += PullResult(
            entities = listOf(nasWinner),
            cursor = 1,
            generation = "generation-a",
            hasMore = false,
        )

        val outcome = rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        val winner = rig.carePlans.getByClientUuid(planUuid)!!
        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.backend.pullCount).isEqualTo(2)
        assertThat(rig.backend.stagedBundles.map { it.root.clientUuid })
            .containsExactly(planUuid)
        assertThat(rig.backend.stagedBundles.single().media.map(SyncEntity::clientUuid))
            .containsExactly(losingMediaUuid)
        assertThat(winner.createdByMembershipId).isEqualTo("member-remote")
        assertThat(winner.scheduledAt).isEqualTo(9_000_000_001_000)
        assertThat(winner.updatedAt).isEqualTo(250)
        assertThat(winner.syncDirty).isFalse()
        assertThat(rig.preferences.current().pendingCreatorAcknowledgements).isEmpty()
        assertThat(rig.media.getByClientUuid(losingMediaUuid)).isNull()
        assertThat(rig.mediaFiles.deleted).containsExactly("photos/losing-next-feed.jpg")
    }

    @Test
    fun memberPullAppliesAuthorityBeforeCapture_andNeverPublishesLocalBaby() = runTest {
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Member,
            membershipId = "member-a",
        )
        val rig = ReplicaEngineRig(session)
        rig.babies.seed(localReplicaBaby().copy(syncDirty = true, familyAuthority = false))
        rig.backend.nextPull = PullResult(
            entities = listOf(remoteReplicaBaby()),
            cursor = 1,
            generation = "generation-a",
            hasMore = false,
        )

        rig.engine.synchronize(session, SyncTrigger.PullToRefresh)

        assertThat(rig.backend.pushes.flatMap { it.entities }.none { it.type == "baby" }).isTrue()
        assertThat(rig.babies.getByClientUuid("baby-local")!!.syncDirty).isFalse()
        assertThat(rig.babies.getByClientUuid("baby-remote")!!.familyAuthority).isTrue()
        assertThat(rig.familyBabyAppliedCalls).isEqualTo(1)
        assertThat(rig.authorityVisibleAtCallback).isTrue()
    }

    @Test
    fun memberWithMultipleAuthorityBabies_settlesLocalOnlySubtreeWithoutPublishing() = runTest {
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Member,
            membershipId = "member-a",
        )
        val rig = ReplicaEngineRig(session)
        val orphanBabyId = rig.babies.seed(
            localReplicaBaby().copy(syncDirty = true, familyAuthority = false),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = "record-local-orphan",
                babyId = orphanBabyId,
                type = "pee",
                timestamp = 500,
                payloadJson = "{\"pee_amount\":2}",
                updatedAt = 500,
                syncDirty = true,
            ),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteReplicaBaby(),
                remoteReplicaBaby().copy(clientUuid = "baby-remote-2", updatedAt = 101),
            ),
            cursor = 2,
            generation = "generation-a",
            hasMore = false,
        )

        rig.engine.synchronize(session, SyncTrigger.PullToRefresh)

        assertThat(rig.babies.listFamilyAuthority()).hasSize(2)
        assertThat(rig.records.getByClientUuid("record-local-orphan")!!.syncDirty).isFalse()
        assertThat(
            rig.backend.stagedBundles.none { it.root.clientUuid == "record-local-orphan" },
        ).isTrue()
    }

    @Test
    fun memberInitialSnapshot_replacesPreviousAuthoritySetBeforeCallback() = runTest {
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Member,
            membershipId = "member-a",
        )
        val rig = ReplicaEngineRig(session)
        rig.babies.seed(
            localReplicaBaby().copy(
                clientUuid = "baby-from-previous-family",
                syncDirty = false,
                familyAuthority = true,
            ),
        )

        rig.engine.applyInitialEntities(session, listOf(remoteReplicaBaby()))

        assertThat(
            rig.babies.getByClientUuid("baby-from-previous-family")!!.familyAuthority,
        ).isFalse()
        assertThat(rig.babies.getByClientUuid("baby-remote")!!.familyAuthority).isTrue()
        assertThat(rig.familyBabyAppliedCalls).isEqualTo(1)
        assertThat(rig.authorityVisibleAtCallback).isTrue()
    }

    @Test
    fun pullToRefreshAppliesEveryPageAndPersistsTheCompletedCheckpoint() = runTest {
        val rig = ReplicaEngineRig(joinedReplicaSession())
        rig.backend.pullResults += PullResult(
            entities = listOf(remoteReplicaBaby()),
            cursor = 1,
            generation = "generation-a",
            hasMore = true,
        )
        rig.backend.pullResults += PullResult(
            entities = emptyList(),
            cursor = 2,
            generation = "generation-a",
            hasMore = false,
        )

        val outcome = rig.engine.synchronize(
            session = rig.preferences.current(),
            trigger = SyncTrigger.PullToRefresh,
        )

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.backend.pullCursors).containsExactly(0L, 1L).inOrder()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(2)
        assertThat(rig.babies.getByClientUuid("baby-remote")).isNotNull()
    }

    @Test
    fun unknownPullEntityFailsBeforeApplyingThePageOrAdvancingTheCheckpoint() = runTest {
        val session = joinedReplicaSession().copy(pullCursor = 4)
        val rig = ReplicaEngineRig(session)
        rig.babies.seed(
            localReplicaBaby().copy(
                syncDirty = false,
                familyAuthority = true,
            ),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteReplicaBaby(),
                SyncEntity(
                    type = "device",
                    clientUuid = "removed-wire-entity",
                    payloadJson = "{}",
                    updatedAt = 100,
                ),
            ),
            cursor = 5,
            generation = "generation-a",
            hasMore = false,
        )

        val failure = runCatching {
            rig.engine.synchronize(
                session = session,
                trigger = SyncTrigger.PullToRefresh,
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().contains("device")
        assertThat(rig.babies.getByClientUuid("baby-remote")).isNull()
        assertThat(rig.babies.getByClientUuid("baby-local")).isNotNull()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(4)
        assertThat(rig.preferences.current().pullGeneration).isEqualTo("generation-a")
    }

    @Test
    fun continuationCannotExceedTheBoundedPageLimit() = runTest {
        val rig = ReplicaEngineRig(joinedReplicaSession())
        repeat(500) { index ->
            rig.backend.pullResults += PullResult(
                entities = emptyList(),
                cursor = index.toLong() + 1,
                generation = "generation-a",
                hasMore = true,
            )
        }

        val failure = runCatching {
            rig.engine.synchronize(
                session = rig.preferences.current(),
                trigger = SyncTrigger.PullToRefresh,
            )
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("500 页上限")
        assertThat(rig.backend.pullCount).isEqualTo(500)
    }

    @Test
    fun continuationMustAdvanceTheCursor() = runTest {
        val session = joinedReplicaSession().copy(
            pullCursor = 5,
            pullGeneration = "generation-a",
        )
        val rig = ReplicaEngineRig(session)
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 5,
            generation = "generation-a",
            hasMore = true,
        )

        val failure = runCatching {
            rig.engine.synchronize(
                session = session,
                trigger = SyncTrigger.PullToRefresh,
            )
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("cursor 未推进")
        assertThat(rig.backend.pullCursors).containsExactly(5L)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(5)
    }

    @Test
    fun pullResponseRequiresTheExactCurrentGenerationBeforeApplyOrCheckpoint() = runTest {
        listOf("", "generation-b").forEach { returnedGeneration ->
            val session = joinedReplicaSession().copy(pullCursor = 4)
            val rig = ReplicaEngineRig(session)
            rig.backend.nextPull = PullResult(
                entities = listOf(remoteReplicaBaby()),
                cursor = 5,
                generation = returnedGeneration,
                hasMore = false,
            )

            val failure = runCatching {
                rig.engine.synchronize(
                    session = session,
                    trigger = SyncTrigger.PullToRefresh,
                )
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(failure).hasMessageThat().contains("代际")
            assertThat(rig.babies.getByClientUuid("baby-remote")).isNull()
            assertThat(rig.preferences.current().pullCursor).isEqualTo(4)
            assertThat(rig.preferences.current().pullGeneration).isEqualTo("generation-a")
        }
    }

    @Test
    fun mismatchedAuthenticatedSelfMembershipFailsWithoutRepairingLocalState() = runTest {
        val session = joinedReplicaSession().copy(membershipId = "session-membership")
        val rig = ReplicaEngineRig(
            session = session,
        )
        rig.backend.nextMembers = listOf(
            FamilyMember(
                displayName = "妈妈",
                role = FamilyRole.Owner,
                isSelf = true,
                membershipId = "different-membership",
            ),
            FamilyMember(
                displayName = "爸爸",
                role = FamilyRole.Member,
                isSelf = false,
                membershipId = "peer-membership",
            ),
        )

        val failure = runCatching {
            rig.engine.synchronize(
                session = session,
                trigger = SyncTrigger.PullToRefresh,
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(rig.backend.memberCalls).isEqualTo(1)
        assertThat(rig.preferences.current().membershipId)
            .isEqualTo("session-membership")
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.backend.pushes).isEmpty()
    }
    @Test
    fun canonicalSessionPullsAcknowledgementsForPendingBlankCreators() = runTest {
        val session = joinedReplicaSession().copy(membershipId = "canonical-membership")
        val rig = ReplicaEngineRig(
            session = session,
        )
        rig.backend.nextMembers = listOf(
            FamilyMember(
                displayName = "妈妈",
                role = FamilyRole.Owner,
                isSelf = true,
                membershipId = "canonical-membership",
            ),
        )
        rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        rig.carePlans.seed(
            localReplicaCarePlan("plan-recovered-blank", "", updatedAt = 710)
                .copy(syncDirty = true),
        )
        rig.customItems.seed(
            localReplicaCustomItem("item-recovered-blank", "", updatedAt = 720)
                .copy(syncDirty = true),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "custom_item",
                    clientUuid = "item-recovered-blank",
                    payloadJson =
                        """{"name":"item-recovered-blank","icon_slot":0,"created_by_membership_id":"canonical-membership"}""",
                    updatedAt = 720,
                ),
                SyncEntity(
                    type = "care_plan",
                    clientUuid = "plan-recovered-blank",
                    payloadJson =
                        """{"baby_client_uuid":"baby-local","type":"formula","custom_item_client_uuid":null,"scheduled_at":9000000000000,"scheduled_zone_id":"Asia/Shanghai","note":null,"status":"pending","payload_json":{"amount_ml":120},"schema_version":2,"created_by_membership_id":"canonical-membership","fulfilled_record_client_uuid":null,"fulfilled_at":null}""",
                    updatedAt = 710,
                ),
            ),
            cursor = 2,
            generation = "generation-a",
            hasMore = false,
        )

        val outcome = rig.engine.synchronize(
            session = session,
            trigger = SyncTrigger.LocalWrite,
        )

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.backend.stagedBundles).isEmpty()
        assertThat(rig.backend.pullCount).isEqualTo(1)
        assertThat(rig.carePlans.getByClientUuid("plan-recovered-blank")?.let {
            Triple(it.createdByMembershipId, it.updatedAt, it.syncDirty)
        }).isEqualTo(Triple("canonical-membership", 710L, false))
        assertThat(rig.customItems.get("item-recovered-blank")?.let {
            Triple(it.createdByMembershipId, it.updatedAt, it.syncDirty)
        }).isEqualTo(Triple("canonical-membership", 720L, false))
    }

    @Test
    fun failedCreatorAcknowledgementPullRetriesOnTheNextLocalWrite() = runTest {
        val session = joinedReplicaSession().copy(membershipId = "canonical-membership")
        val rig = ReplicaEngineRig(
            session = session,
        )
        rig.backend.nextMembers = listOf(
            FamilyMember(
                displayName = "妈妈",
                role = FamilyRole.Owner,
                isSelf = true,
                membershipId = "canonical-membership",
            ),
        )
        rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        rig.carePlans.seed(
            localReplicaCarePlan("plan-retry-ack", "", updatedAt = 810)
                .copy(syncDirty = true),
        )
        rig.customItems.seed(
            localReplicaCustomItem("item-retry-ack", "", updatedAt = 820)
                .copy(syncDirty = true),
        )
        rig.backend.pullFailures += SyncHttpException(statusCode = 503)

        val firstFailure = runCatching {
            rig.engine.synchronize(
                session = rig.preferences.current(),
                trigger = SyncTrigger.LocalWrite,
            )
        }.exceptionOrNull()

        assertThat(firstFailure).isInstanceOf(SyncHttpException::class.java)
        assertThat(rig.backend.pullCount).isEqualTo(1)
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "custom_item",
                    clientUuid = "item-retry-ack",
                    payloadJson =
                        """{"name":"item-retry-ack","icon_slot":0,"created_by_membership_id":"canonical-membership"}""",
                    updatedAt = 820,
                ),
                SyncEntity(
                    type = "care_plan",
                    clientUuid = "plan-retry-ack",
                    payloadJson =
                        """{"baby_client_uuid":"baby-local","type":"formula","custom_item_client_uuid":null,"scheduled_at":9000000000000,"scheduled_zone_id":"Asia/Shanghai","note":null,"status":"pending","payload_json":{"amount_ml":120},"schema_version":2,"created_by_membership_id":"canonical-membership","fulfilled_record_client_uuid":null,"fulfilled_at":null}""",
                    updatedAt = 810,
                ),
            ),
            cursor = 2,
            generation = "generation-a",
            hasMore = false,
        )

        val outcome = rig.engine.synchronize(
            session = rig.preferences.current(),
            trigger = SyncTrigger.LocalWrite,
        )

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.backend.pullCount).isEqualTo(2)
        assertThat(rig.carePlans.getByClientUuid("plan-retry-ack")?.createdByMembershipId)
            .isEqualTo("canonical-membership")
        assertThat(rig.customItems.get("item-retry-ack")?.createdByMembershipId)
            .isEqualTo("canonical-membership")
    }

    @Test
    fun commitFailureKeepsExactLocalCreatorProvenanceUntilAuthoritativePull() = runTest {
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Member,
            membershipId = "canonical-membership",
        )
        val rig = ReplicaEngineRig(
            session = session,
        )
        rig.backend.nextMembers = listOf(
            FamilyMember(
                displayName = "爸爸",
                role = FamilyRole.Member,
                isSelf = true,
                membershipId = "canonical-membership",
            ),
        )
        rig.babies.seed(
            localReplicaBaby().copy(
                syncDirty = false,
                familyAuthority = true,
            ),
        )
        rig.carePlans.seed(
            localReplicaCarePlan("plan-commit-retry", "", updatedAt = 830)
                .copy(syncDirty = true),
        )
        rig.customItems.seed(
            localReplicaCustomItem("item-commit-retry", "", updatedAt = 840)
                .copy(syncDirty = true),
        )
        rig.backend.commitBundleFailure = IllegalStateException("commit interrupted")

        val firstFailure = runCatching {
            rig.engine.synchronize(
                session = rig.preferences.current(),
                trigger = SyncTrigger.LocalWrite,
            )
        }.exceptionOrNull()

        assertThat(firstFailure).hasMessageThat().contains("commit interrupted")
        assertThat(rig.preferences.current().membershipId).isEqualTo("canonical-membership")
        assertThat(rig.preferences.current().pendingCreatorAcknowledgements).containsExactly(
            CreatorAcknowledgementRef("care_plan", "plan-commit-retry"),
            CreatorAcknowledgementRef("custom_item", "item-commit-retry"),
        )

        rig.backend.commitBundleFailure = null
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "custom_item",
                    clientUuid = "item-commit-retry",
                    payloadJson =
                        """{"name":"item-commit-retry","icon_slot":0,"created_by_membership_id":"canonical-membership"}""",
                    updatedAt = 840,
                ),
                SyncEntity(
                    type = "care_plan",
                    clientUuid = "plan-commit-retry",
                    payloadJson =
                        """{"baby_client_uuid":"baby-local","type":"formula","custom_item_client_uuid":null,"scheduled_at":9000000000000,"scheduled_zone_id":"Asia/Shanghai","note":null,"status":"pending","payload_json":{"amount_ml":120},"schema_version":2,"created_by_membership_id":"canonical-membership","fulfilled_record_client_uuid":null,"fulfilled_at":null}""",
                    updatedAt = 830,
                ),
            ),
            cursor = 2,
            generation = "generation-a",
            hasMore = false,
        )

        val outcome = rig.engine.synchronize(
            session = rig.preferences.current(),
            trigger = SyncTrigger.LocalWrite,
        )

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.preferences.current().pendingCreatorAcknowledgements).isEmpty()
        assertThat(rig.carePlans.getByClientUuid("plan-commit-retry")?.createdByMembershipId)
            .isEqualTo("canonical-membership")
        assertThat(rig.customItems.get("item-commit-retry")?.createdByMembershipId)
            .isEqualTo("canonical-membership")
    }

    @Test
    fun memberPostPushCreatorPullRecoversGenerationChange() = runTest {
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Member,
            membershipId = "member-local",
            pullGeneration = "old-generation",
        )
        val rig = ReplicaEngineRig(session)
        rig.babies.seed(
            localReplicaBaby().copy(
                syncDirty = false,
                familyAuthority = true,
            ),
        )
        rig.customItems.seed(
            localReplicaCustomItem("item-post-push-resync", "", updatedAt = 840)
                .copy(syncDirty = true),
        )
        rig.backend.pullResults += PullResult(
            entities = emptyList(),
            cursor = 0,
            generation = "old-generation",
            hasMore = false,
        )
        rig.backend.beforePullReturn = {
            rig.backend.pullFailures += SyncHttpException(
                statusCode = 409,
                responseBody = """
                    {
                      "detail":{
                        "code":"generation_changed",
                        "action":"full_resync",
                        "reset_cursor":0,
                        "server_generation":"new-generation"
                      }
                    }
                """.trimIndent(),
            )
        }
        rig.backend.pullResults += PullResult(
            entities = listOf(
                remoteReplicaBaby().copy(clientUuid = "baby-local"),
                SyncEntity(
                    type = "custom_item",
                    clientUuid = "item-post-push-resync",
                    payloadJson =
                        """{"name":"item-post-push-resync","icon_slot":0,"created_by_membership_id":"member-local"}""",
                    updatedAt = 840,
                ),
            ),
            cursor = 1,
            generation = "new-generation",
            hasMore = false,
        )
        rig.backend.pullResults += PullResult(
            entities = emptyList(),
            cursor = 1,
            generation = "new-generation",
            hasMore = false,
        )

        val outcome = rig.engine.synchronize(
            session = session,
            trigger = SyncTrigger.LocalWrite,
        )

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.backend.pullCursors).containsExactly(0L, 0L, 0L, 1L).inOrder()
        assertThat(rig.preferences.current().pullGeneration).isEqualTo("new-generation")
        assertThat(rig.preferences.current().pendingCreatorAcknowledgements).isEmpty()
        assertThat(
            rig.customItems.get("item-post-push-resync")?.createdByMembershipId,
        ).isEqualTo("member-local")
    }

    @Test
    fun rootReceiptCasMissKeepsRoomDirtyAndNextCycleReplans() = runTest {
        val rig = ReplicaEngineRig(joinedReplicaSession())
        val babyId = rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        rig.records.seed(
            RecordEntity(
                clientUuid = "record-receipt-cas-miss",
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = "{\"amount_ml\":90}",
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        rig.backend.afterCommit = {
            rig.backend.afterCommit = null
            val current = requireNotNull(
                rig.records.getByClientUuid("record-receipt-cas-miss"),
            )
            rig.records.update(
                current.copy(
                    note = "提交期间的新编辑",
                    updatedAt = 101,
                    syncDirty = true,
                ),
            )
        }

        val outcome = rig.engine.synchronize(
            session = rig.preferences.current(),
            trigger = SyncTrigger.LocalWrite,
        )

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        val current = requireNotNull(
            rig.records.getByClientUuid("record-receipt-cas-miss"),
        )
        assertThat(current.note).isEqualTo("提交期间的新编辑")
        assertThat(current.syncDirty).isTrue()
        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.LocalWrite)

        val retried = rig.backend.stagedBundles
            .filter { it.root.clientUuid == "record-receipt-cas-miss" }
            .last()
            .root
        assertThat(retried.updatedAt).isEqualTo(101)
        assertThat(retried.payloadJson).contains("提交期间的新编辑")
        assertThat(rig.records.getByClientUuid("record-receipt-cas-miss")?.syncDirty).isFalse()
    }

    @Test
    fun unappliedRemoteCreatorDoesNotClearThePendingAcknowledgement() = runTest {
        val pending = CreatorAcknowledgementRef("care_plan", "plan-unapplied-ack")
        val session = joinedReplicaSession().copy(
            membershipId = "canonical-membership",
            pendingCreatorAcknowledgements = setOf(pending),
        )
        val rig = ReplicaEngineRig(
            session = session,
        )
        rig.backend.nextMembers = listOf(
            FamilyMember(
                displayName = "妈妈",
                role = FamilyRole.Owner,
                isSelf = true,
                membershipId = "canonical-membership",
            ),
        )
        rig.carePlans.seed(
            localReplicaCarePlan("plan-unapplied-ack", "", updatedAt = 850)
                .copy(syncDirty = false),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "care_plan",
                    clientUuid = "plan-unapplied-ack",
                    payloadJson =
                        """{"baby_client_uuid":"missing-baby","type":"formula","custom_item_client_uuid":null,"scheduled_at":9000000000000,"scheduled_zone_id":"Asia/Shanghai","note":null,"status":"pending","payload_json":{"amount_ml":120},"schema_version":2,"created_by_membership_id":"canonical-membership","fulfilled_record_client_uuid":null,"fulfilled_at":null}""",
                    updatedAt = 851,
                ),
            ),
            cursor = 1,
            generation = "generation-a",
            hasMore = false,
        )

        val failure = runCatching {
            rig.engine.synchronize(
                session = rig.preferences.current(),
                trigger = SyncTrigger.LocalWrite,
            )
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("同步数据引用尚未就绪")
        assertThat(rig.carePlans.getByClientUuid("plan-unapplied-ack")?.let {
            it.updatedAt to it.createdByMembershipId
        }).isEqualTo(850L to "")
        assertThat(rig.preferences.current().pendingCreatorAcknowledgements)
            .containsExactly(pending)
    }
    @Test
    fun cursorAheadRequeuesTheCleanReplicaBeforeTheAuthoritativePull() = runTest {
        val session = joinedReplicaSession().copy(
            pullCursor = 9,
            pullGeneration = "old-generation",
        )
        val rig = ReplicaEngineRig(session)
        rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        rig.backend.pullFailures += SyncHttpException(
            statusCode = 409,
            responseBody = """
                {
                  "detail":{
                    "code":"cursor_ahead",
                    "action":"full_resync",
                    "reset_cursor":0,
                    "server_cursor":1,
                    "server_generation":"new-generation"
                  }
                }
            """.trimIndent(),
        )
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 2,
            generation = "new-generation",
            hasMore = false,
        )

        val outcome = rig.engine.synchronize(
            session = session,
            trigger = SyncTrigger.PullToRefresh,
        )

        assertThat(rig.backend.pullCursors).containsExactly(9L, 0L, 2L).inOrder()
        assertThat(rig.backend.stagedBundles.map { it.root.clientUuid }).contains("baby-local")
        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(2)
    }

    @Test
    fun fullResyncAppliesPeerNewerRecordInsteadOfRepublishingDirtyOldBody() = runTest {
        val session = joinedReplicaSession().copy(
            pullCursor = 9,
            pullGeneration = "old-generation",
        )
        val rig = ReplicaEngineRig(session)
        val babyId = rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        val recordUuid = "record-peer-newer-full-resync"
        rig.records.seed(
            RecordEntity(
                clientUuid = recordUuid,
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                note = "旧版本",
                payloadJson = "{\"amount_ml\":60}",
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.pullFailures += SyncHttpException(
            statusCode = 409,
            responseBody = """
                {
                  "detail":{
                    "code":"cursor_ahead",
                    "action":"full_resync",
                    "reset_cursor":0,
                    "server_cursor":1,
                    "server_generation":"new-generation"
                  }
                }
            """.trimIndent(),
        )
        rig.backend.pullResults += PullResult(
            entities = listOf(
                remoteReplicaRecord(recordUuid).copy(
                    updatedAt = 300,
                    deletedAt = 300,
                ),
            ),
            cursor = 1,
            generation = "new-generation",
            hasMore = false,
        )
        rig.backend.pullResults += PullResult(
            entities = emptyList(),
            cursor = 1,
            generation = "new-generation",
            hasMore = false,
        )

        val outcome = rig.engine.synchronize(session, SyncTrigger.PullToRefresh)

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        val record = rig.records.getByClientUuid(recordUuid)!!
        assertThat(record.updatedAt).isEqualTo(300)
        assertThat(record.deletedAt).isEqualTo(300)
        assertThat(record.syncDirty).isFalse()
        assertThat(rig.backend.stagedBundles.map { it.root.clientUuid })
            .doesNotContain(recordUuid)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(1)
        assertThat(rig.preferences.current().pullGeneration).isEqualTo("new-generation")
    }

    @Test
    fun failedAtomicMediaDownloadRetriesWithoutPublishingAPartialReplica() = runTest {
        val rig = ReplicaEngineRig(joinedReplicaSession())
        rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        val mediaUuid = "56565656-5656-5656-5656-565656565656"
        val recordUuid = "record-media-retry"
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteReplicaRecord(recordUuid),
                remoteReplicaMedia(mediaUuid, recordUuid),
            ),
            cursor = 1,
            generation = "generation-a",
            hasMore = false,
        )
        rig.backend.getMediaFailure = IllegalStateException("download interrupted")

        val firstFailure = runCatching {
            rig.engine.synchronize(
                session = rig.preferences.current(),
                trigger = SyncTrigger.PullToRefresh,
            )
        }.exceptionOrNull()

        assertThat(firstFailure).hasMessageThat().contains("download interrupted")
        assertThat(rig.preferences.current().pullCursor).isEqualTo(0)
        assertThat(rig.records.getByClientUuid(recordUuid)).isNull()
        assertThat(rig.media.getByClientUuid(mediaUuid)).isNull()

        rig.backend.getMediaFailure = null
        val outcome = rig.engine.synchronize(
            session = rig.preferences.current(),
            trigger = SyncTrigger.PullToRefresh,
        )

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(1)
        assertThat(rig.records.getByClientUuid(recordUuid)).isNotNull()
        assertThat(rig.media.getByClientUuid(mediaUuid)?.localUri)
            .isEqualTo("downloaded/$mediaUuid")
    }

    @Test
    fun remoteMediaTombstoneRetriesFileCleanupAfterProcessStops() = runTest {
        val rig = ReplicaEngineRig(joinedReplicaSession())
        val babyId = rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        val recordUuid = "record-with-remote-tombstone"
        val recordId = rig.records.seed(
            RecordEntity(
                clientUuid = recordUuid,
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = "{\"amount_ml\":90}",
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        val mediaUuid = "56565656-5656-5656-5656-565656565657"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                localUri = "photos/tombstoned.jpg",
                remoteUri = "sync://family-a/$mediaUuid",
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.nextPull = remoteReplicaMedia(mediaUuid, recordUuid).copy(
            updatedAt = 220,
            deletedAt = 220,
        ).let { PullResult(listOf(it), cursor = 1, generation = "generation-a", hasMore = false) }
        rig.mediaFiles.deleteFailures += IllegalStateException("process stopped")

        val firstFailure = runCatching {
            rig.engine.synchronize(
                session = rig.preferences.current(),
                trigger = SyncTrigger.PullToRefresh,
            )
        }.exceptionOrNull()

        assertThat(firstFailure).hasMessageThat().contains("process stopped")
        assertThat(rig.media.getByClientUuid(mediaUuid)?.deletedAt).isEqualTo(220)
        assertThat(rig.media.getByClientUuid(mediaUuid)?.localUri)
            .isEqualTo("photos/tombstoned.jpg")
        assertThat(rig.preferences.current().pullCursor).isEqualTo(0)

        val outcome = rig.engine.synchronize(
            session = rig.preferences.current(),
            trigger = SyncTrigger.PullToRefresh,
        )

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.mediaFiles.deleted)
            .containsExactly("photos/tombstoned.jpg", "photos/tombstoned.jpg")
            .inOrder()
        assertThat(rig.media.getByClientUuid(mediaUuid)?.localUri).isEmpty()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(1)
    }

    @Test
    fun remoteMediaTombstoneDoesNotDeletePathReusedByLiveMedia() = runTest {
        val rig = ReplicaEngineRig(joinedReplicaSession())
        val babyId = rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        val recordUuid = "record-with-reused-media-path"
        val recordId = rig.records.seed(
            RecordEntity(
                clientUuid = recordUuid,
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = "{\"amount_ml\":90}",
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        val tombstoneUuid = "56565656-5656-5656-5656-565656565658"
        val reusedPath = "photos/reused.jpg"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = tombstoneUuid,
                localUri = reusedPath,
                remoteUri = "sync://family-a/$tombstoneUuid",
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = "67676767-6767-6767-6767-676767676767",
                localUri = reusedPath,
                createdAt = 210,
                updatedAt = 210,
                syncDirty = false,
            ),
        )
        rig.backend.nextPull = remoteReplicaMedia(tombstoneUuid, recordUuid).copy(
            updatedAt = 220,
            deletedAt = 220,
        ).let { PullResult(listOf(it), cursor = 1, generation = "generation-a", hasMore = false) }

        val outcome = rig.engine.synchronize(
            session = rig.preferences.current(),
            trigger = SyncTrigger.PullToRefresh,
        )

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.mediaFiles.deleted).doesNotContain(reusedPath)
        assertThat(rig.media.getByClientUuid(tombstoneUuid)?.localUri).isEmpty()
    }

    @Test
    fun cancellationEscapesAndDoesNotAdvanceThePullCheckpoint() = runTest {
        val rig = ReplicaEngineRig(joinedReplicaSession())
        rig.backend.pullStarted = CompletableDeferred()
        rig.backend.releasePull = CompletableDeferred()
        val syncing = async {
            rig.engine.synchronize(
                session = rig.preferences.current(),
                trigger = SyncTrigger.PullToRefresh,
            )
        }
        rig.backend.pullStarted!!.await()

        syncing.cancel()
        val failure = runCatching { syncing.await() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(0)
    }
}

private class ReplicaEngineRig(
    session: SyncSession,
) {
    val backend = RecordingSyncBackend()
    val preferences = MemorySyncPreferences(session)
    val records = MemoryRecordDao()
    val carePlans = MemoryCarePlanDao()
    val fulfillmentCandidates = MemoryFulfillmentCandidateDao()
    val babies = MemoryBabyDao()
    val media = MemoryMediaDao()
    val customItems = MemoryCustomItemDao()
    val mediaFiles = TestMediaFileStore()
    val transactions = RecordingTransactionRunner()
    val mediaFileCleanup = ReferenceAwareMediaFileCleanup(
        mediaDao = media,
        mediaFiles = mediaFiles,
        transactionRunner = transactions,
        pathGate = MediaLocalPathGate(),
    )
    val families = MemoryFamilyDao().apply {
        seed(FamilyEntity(id = 1, ownerUserId = 1, createdAt = 0))
    }
    var familyBabyAppliedCalls = 0
    var authorityVisibleAtCallback = false
    val carePlanAppliedBatches = mutableListOf<List<String>>()
    val engine = ReplicaSyncEngine(
        backend = backend,
        preferences = preferences,
        recordDao = records,
        carePlanDao = carePlans,
        babyDao = babies,
        mediaDao = media,
        customItemDao = customItems,
        familyDao = families,
        clock = object : PolicyClock {
            override fun nowMillis(): Long = 1_000
        },
        mediaFiles = mediaFiles,
        mediaFileCleanup = mediaFileCleanup,
        transactionRunner = transactions,
        carePlanAppliedListener = CarePlanFamilyAppliedListener { planClientUuids ->
            carePlanAppliedBatches += planClientUuids
        },
        familyBabyAppliedListener = FamilyBabyAuthorityAppliedListener {
            familyBabyAppliedCalls++
            authorityVisibleAtCallback = babies.listFamilyAuthority().isNotEmpty()
        },
        fulfillmentCandidateDao = fulfillmentCandidates,
        requireRemoteAllowed = {},
    )
}

private fun joinedReplicaSession() = SyncSession(
    familyId = "family-a",
    accessToken = "token-a",
    deviceId = "device-a",
    role = FamilyRole.Owner,
    pullGeneration = "generation-a",
    membershipId = "membership-a",
    serverHost = "192.168.50.4",
    serverPort = 8765,
)

private fun remoteReplicaBaby() = SyncEntity(
    type = "baby",
    clientUuid = "baby-remote",
    updatedAt = 100,
    deletedAt = null,
    payloadJson = """
        {
          "nickname":"远端宝宝",
          "sex":null,
          "birthday":"2024-01-01",
          "birth_weight_grams":null,
          "avatar_media_uuid":null
        }
    """.trimIndent(),
)

private fun localReplicaBaby() = BabyEntity(
    familyId = 1,
    nickname = "本地宝宝",
    birthdayEpochDay = 20_000,
    themeColorArgb = 0,
    clientUuid = "baby-local",
    updatedAt = 100,
)

private fun localReplicaCarePlan(
    clientUuid: String,
    creatorMembershipId: String,
    updatedAt: Long,
) = CarePlanEntity(
    clientUuid = clientUuid,
    babyId = 1,
    type = "formula",
    scheduledAt = 9_000_000_000_000,
    scheduledZoneId = "Asia/Shanghai",
    payloadJson = """{"amount_ml":120}""",
    createdByMembershipId = creatorMembershipId,
    updatedAt = updatedAt,
    syncDirty = false,
)

private fun localReplicaCustomItem(
    clientUuid: String,
    creatorMembershipId: String,
    updatedAt: Long,
) = CustomItemEntity(
    clientUuid = clientUuid,
    familyId = 1,
    name = clientUuid,
    iconSlot = 0,
    createdByMembershipId = creatorMembershipId,
    updatedAt = updatedAt,
    syncDirty = false,
)

private fun remoteReplicaRecord(clientUuid: String) = SyncEntity(
    type = "record",
    clientUuid = clientUuid,
    payloadJson = """
        {
          "baby_client_uuid":"baby-local",
          "created_by_membership_id":"membership-b",
          "type":"formula",
          "custom_item_client_uuid":null,
          "timestamp":210,
          "end_timestamp":null,
          "note":null,
          "payload_json":{"amount_ml":90},
          "schema_version":2
        }
    """.trimIndent(),
    updatedAt = 210,
)

private fun remoteReplicaMedia(
    clientUuid: String,
    recordClientUuid: String,
) = SyncEntity(
    type = "media",
    clientUuid = clientUuid,
    payloadJson = """
        {
          "kind":"log",
          "record_client_uuid":"$recordClientUuid",
          "care_plan_client_uuid":null,
          "baby_client_uuid":null,
          "mime":"image/jpeg",
          "width":null,
          "height":null,
          "byte_size":4
        }
    """.trimIndent(),
    updatedAt = 210,
)
