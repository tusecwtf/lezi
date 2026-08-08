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
import kotlinx.serialization.json.long
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

class ReplicaSyncEngineAuthoritySettleTest {
    @Test
    fun historicalLiveMediaMetadataIsRepairedBeforeAuthorityReconcile() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session)
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
        )
        val recordId = rig.records.seed(
            RecordEntity(
                clientUuid = "record-with-historical-photo",
                babyId = babyId,
                type = "sleep",
                timestamp = 100,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                schemaVersion = 2,
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        val mediaUuid = "01234567-89ab-4cde-8fab-0123456789ab"
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = mediaUuid,
                kind = "log",
                recordId = recordId,
                localUri = "record-media/from-v12.png",
                mime = null,
                width = null,
                height = null,
                byteSize = 0,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.onReconcile = { units ->
            val payload = Json.parseToJsonElement(
                units.single().media.single().payloadJson,
            ).jsonObject
            require(payload.getValue("byte_size").jsonPrimitive.long > 0) {
                "byte_size is out of range"
            }
        }

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        val reconciledPayload = Json.parseToJsonElement(
            rig.backend.reconciledUnits.single().single().media.single().payloadJson,
        ).jsonObject
        assertThat(reconciledPayload.getValue("byte_size").jsonPrimitive.long).isEqualTo(12)
        assertThat(reconciledPayload.getValue("mime").jsonPrimitive.content)
            .isEqualTo("image/jpeg")
        assertThat(rig.records.getByClientUuid("record-with-historical-photo")).isNotNull()
        assertThat(rig.media.getByClientUuid(mediaUuid)).isNotNull()
    }

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
            // Without causal wire LocalWrite still pulls; recovery full-resync adds two more.
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
            // Without causal wire LocalWrite still pulls; recovery full-resync adds two more.
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
}
