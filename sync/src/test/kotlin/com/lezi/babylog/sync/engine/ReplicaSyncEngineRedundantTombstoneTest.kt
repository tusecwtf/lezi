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

class ReplicaSyncEngineRedundantTombstoneTest {
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

            assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized())
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

            assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized())
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

            assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized())
            assertThat(rig.mediaFiles.deleted).doesNotContain(reusedPath)
            assertThat(rig.media.getByClientUuid(tombstoneUuid)?.localUri).isEmpty()
        }
}
