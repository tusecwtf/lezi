package com.lezi.babylog.sync.engine
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheEntity
import com.lezi.babylog.core.database.causal.ConflictSummaryEntity
import kotlinx.coroutines.test.runTest
import org.junit.Test
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.FamilySessionReplica
import com.lezi.babylog.sync.session.receiptFor

class ReplicaSyncEngineConflictTest {
    @Test
    fun recordPullWithoutConflictAtomicallyClearsSnapshotAndSummary() = runTest {
        val session = joinedReplicaSession()
        val rig = ReplicaEngineRig(session)
        rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        rig.conflictSummaries.upsert(
            ConflictSummaryEntity(
                conflictId = "conflict-stale",
                entityType = "record",
                clientUuid = "record-clear-conflict",
                stableVersionId = "v-old",
                status = "open",
                kind = "concurrent",
                updatedAt = 100,
            ),
        )
        rig.conflictDetails.upsert(
            ConflictSnapshotCacheEntity(
                conflictId = "conflict-stale",
                snapshotJson = "{}",
                cachedAt = 100,
            ),
        )
        rig.conflictDetails.putTransportJournal(
            journalKey = "conflict-page-stage:conflict-stale",
            payloadJson = "{staged}",
            contentEpoch = 101,
        )

        rig.engine.applyInitialEntities(
            session,
            listOf(
                remoteReplicaRecord("record-clear-conflict").copy(
                    versionId = "v-resolved",
                    conflictSummary = null,
                ),
            ),
        )

        assertThat(rig.conflictSummaries.get("conflict-stale")).isNull()
        assertThat(rig.conflictDetails.get("conflict-stale")).isNull()
        assertThat(rig.conflictDetails.getTransportJournal("conflict-page-stage:conflict-stale"))
            .isNull()
        assertThat(rig.records.getByClientUuid("record-clear-conflict")!!.openConflictId).isNull()
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
                """{"baby_client_uuid":"baby-local","type":"formula","custom_item_client_uuid":null,"scheduled_at":9000000002000,"scheduled_zone_id":"Asia/Shanghai","note":"[[lezi:next-feed:v1]]","status":"pending","payload_json":{"amount_ml":0},"schema_version":2,"created_by_membership_id":"member-loser","fulfilled_record_client_uuid":null,"fulfilled_at":null,"source_record_client_uuid":null}""",
            updatedAt = 300,
        )
        val earlierPeer = SyncEntity(
            type = "care_plan",
            clientUuid = earlierPeerUuid,
            payloadJson =
                """{"baby_client_uuid":"baby-local","type":"formula","custom_item_client_uuid":null,"scheduled_at":9000000001000,"scheduled_zone_id":"Asia/Shanghai","note":"[[lezi:next-feed:v1]]","status":"pending","payload_json":{"amount_ml":0},"schema_version":2,"created_by_membership_id":"member-winner","fulfilled_record_client_uuid":null,"fulfilled_at":null,"source_record_client_uuid":null}""",
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
                remoteReplicaBaby().copy(
                    clientUuid = "baby-local",
                ),
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
    fun fullResyncRebasesPreexistingDirtyRecordWithoutReplacingItsEdit() = runTest {
        val (session, rig, recordUuid) = dirtyRecordRecoveryRig(deletedAt = null)
        rig.backend.onCausalCommit = { units ->
            val pending = requireNotNull(rig.records.getByClientUuid(recordUuid))
            assertThat(pending.syncDirty).isTrue()
            assertThat(pending.note).isEqualTo("离线编辑")
            assertThat(pending.baseVersion).isEqualTo("v-remote-record")
            val mutation = units.single { it.clientUuid == recordUuid }
            assertThat(mutation.baseVersion).isEqualTo("v-remote-record")
            assertThat(mutation.rootJson).contains("离线编辑")
        }

        assertThat(rig.engine.synchronize(session, SyncTrigger.PullToRefresh))
            .isEqualTo(ReplicaSyncOutcome.Synchronized)

        assertThat(rig.backend.causalCommittedUnits.flatten().single().clientUuid)
            .isEqualTo(recordUuid)
    }

    @Test
    fun fullResyncRebasesPreexistingDirtyRecordTombstoneWithoutRevivingIt() = runTest {
        val deletedAt = 500L
        val (session, rig, recordUuid) = dirtyRecordRecoveryRig(deletedAt = deletedAt)
        rig.backend.onCausalCommit = { units ->
            val pending = requireNotNull(rig.records.getByClientUuid(recordUuid))
            assertThat(pending.syncDirty).isTrue()
            assertThat(pending.deletedAt).isEqualTo(deletedAt)
            assertThat(pending.baseVersion).isEqualTo("v-remote-record")
            val mutation = units.single { it.clientUuid == recordUuid }
            assertThat(mutation.baseVersion).isEqualTo("v-remote-record")
            assertThat(mutation.deleted).isTrue()
        }

        assertThat(rig.engine.synchronize(session, SyncTrigger.PullToRefresh))
            .isEqualTo(ReplicaSyncOutcome.Synchronized)

        assertThat(rig.backend.causalCommittedUnits.flatten().single().deleted).isTrue()
    }

    @Test
    fun fullResyncPreservesRecordEditedAfterAtomicReset() = runTest {
        val (session, rig, recordUuid) = dirtyRecordRecoveryRig(deletedAt = null)
        rig.records.update(
            requireNotNull(rig.records.getByClientUuid(recordUuid)).copy(
                note = "重置后的并发编辑",
                updatedAt = 700,
                syncDirty = false,
            ),
        )
        rig.backend.beforePullReturn = {
            rig.records.update(
                requireNotNull(rig.records.getByClientUuid(recordUuid)).copy(
                    note = "重置后的并发编辑",
                    updatedAt = 701,
                    syncDirty = true,
                ),
            )
        }
        rig.backend.onCausalCommit = { units ->
            val pending = requireNotNull(rig.records.getByClientUuid(recordUuid))
            assertThat(pending.note).isEqualTo("重置后的并发编辑")
            assertThat(pending.baseVersion).isEqualTo("v-remote-record")
            assertThat(units.single { it.clientUuid == recordUuid }.rootJson)
                .contains("重置后的并发编辑")
        }

        assertThat(rig.engine.synchronize(session, SyncTrigger.PullToRefresh))
            .isEqualTo(ReplicaSyncOutcome.Synchronized)
    }

    @Test
    fun failedResetLeavesNoCrossFamilyRecoveryMode() = runTest {
        val session = recoverySession()
        val rig = ReplicaEngineRig(session)
        rig.transactions.failBeforeNextBlock = IllegalStateException("reset failed")

        val failure = runCatching {
            rig.engine.resetLocalSyncReceipts(
                previous = session,
                invalidateCurrentReceipts = true,
                crossingFamilyBoundary = true,
                recoveryTarget = FamilySessionReplica.RecoveryTarget(
                    baseUrl = session.baseUrl,
                    familyId = session.familyId,
                    membershipId = session.membershipId,
                    deviceId = session.deviceId,
                ),
            )
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().isEqualTo("reset failed")
        val recordUuid = configureDirtyRecordRecovery(rig, deletedAt = null)
        rig.backend.onCausalCommit = { units ->
            assertThat(units.single { it.clientUuid == recordUuid }.rootJson)
                .contains("离线编辑")
        }
        assertThat(rig.engine.synchronize(
            rig.preferences.current(),
            SyncTrigger.PullToRefresh,
        )).isEqualTo(ReplicaSyncOutcome.Synchronized)
    }

    @Test
    fun completedCrossFamilyResetDoesNotChangeLaterRecoveryPolicy() = runTest {
        val session = recoverySession()
        val rig = ReplicaEngineRig(session)
        rig.engine.resetLocalSyncReceipts(
            previous = session,
            invalidateCurrentReceipts = true,
            crossingFamilyBoundary = true,
            recoveryTarget = FamilySessionReplica.RecoveryTarget(
                baseUrl = "https://192.168.1.99:8787",
                familyId = "family-other",
                membershipId = "membership-other",
                deviceId = "device-other",
            ),
        )

        val recordUuid = configureDirtyRecordRecovery(rig, deletedAt = null)
        rig.backend.onCausalCommit = { units ->
            assertThat(units.single { it.clientUuid == recordUuid }.rootJson)
                .contains("离线编辑")
        }
        assertThat(rig.engine.synchronize(
            rig.preferences.current(),
            SyncTrigger.PullToRefresh,
        )).isEqualTo(ReplicaSyncOutcome.Synchronized)
    }

    @Test
    fun crossFamilyRecoveryNeverPublishesOldFamilyIntentForSameRecordUuid() = runTest {
        val previous = recoverySession()
        val current = previous.copy(
            familyId = "family-b",
            membershipId = "membership-b",
            deviceId = "device-b",
        )
        val rig = ReplicaEngineRig(previous)
        val babyId = rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        val recordUuid = "record-shared-across-families"
        rig.records.seed(
            RecordEntity(
                clientUuid = recordUuid,
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                note = "旧家庭离线编辑",
                payloadJson = "{\"amount_ml\":120}",
                updatedAt = 500,
                syncDirty = true,
                baseVersion = "v-old-family",
                mutationId = "mutation-old-family",
            ),
        )
        rig.engine.resetLocalSyncReceipts(
            previous = previous,
            crossingFamilyBoundary = true,
            recoveryTarget = FamilySessionReplica.RecoveryTarget(
                baseUrl = current.baseUrl,
                familyId = current.familyId,
                membershipId = current.membershipId,
                deviceId = current.deviceId,
            ),
        )
        rig.preferences.saveSession(current)
        configureRemoteFullResync(rig, recordUuid, note = "新家庭事实")

        assertThat(rig.engine.synchronize(current, SyncTrigger.PullToRefresh))
            .isEqualTo(ReplicaSyncOutcome.Synchronized)

        val settled = requireNotNull(rig.records.getByClientUuid(recordUuid))
        assertThat(settled.note).isEqualTo("新家庭事实")
        assertThat(settled.syncDirty).isFalse()
        assertThat(rig.backend.causalCommittedUnits.flatten().map { it.clientUuid })
            .doesNotContain(recordUuid)
    }

    @Test
    fun crossFamilyResetInvalidatesPreviousFamilyMediaReceipt() = runTest {
        val previous = recoverySession()
        val rig = ReplicaEngineRig(previous)
        val babyId = rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        val mediaUuid = "00000000-0000-4000-8000-000000000026"
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = mediaUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "avatars/old-family.jpg",
                remoteUri = previous.receiptFor(mediaUuid),
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )

        rig.engine.resetLocalSyncReceipts(
            previous = previous,
            crossingFamilyBoundary = true,
            recoveryTarget = FamilySessionReplica.RecoveryTarget(
                baseUrl = previous.baseUrl,
                familyId = "family-b",
                membershipId = "membership-b",
                deviceId = "device-b",
            ),
        )

        val reset = requireNotNull(rig.media.getByClientUuid(mediaUuid))
        assertThat(reset.remoteUri).isNull()
        assertThat(reset.syncDirty).isTrue()
    }

    @Test
    fun processRestartAfterResetKeepsCleanRowAuthoritativeDuringRecovery() = runTest {
        val session = recoverySession()
        val rig = ReplicaEngineRig(session)
        val babyId = rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        val recordUuid = "record-clean-before-reset"
        rig.records.seed(
            RecordEntity(
                clientUuid = recordUuid,
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                note = "重置前稳定副本",
                payloadJson = "{\"amount_ml\":60}",
                updatedAt = 100,
                syncDirty = false,
                baseVersion = "v-old-stable",
            ),
        )
        rig.engine.resetLocalSyncReceipts(
            previous = session,
            invalidateCurrentReceipts = true,
        )
        configureRemoteFullResync(rig, recordUuid, note = "服务端权威事实")

        assertThat(rig.newEngine().synchronize(session, SyncTrigger.PullToRefresh))
            .isEqualTo(ReplicaSyncOutcome.Synchronized)

        val settled = requireNotNull(rig.records.getByClientUuid(recordUuid))
        assertThat(settled.note).isEqualTo("服务端权威事实")
        assertThat(settled.syncDirty).isFalse()
        assertThat(rig.backend.causalCommittedUnits.flatten().map { it.clientUuid })
            .doesNotContain(recordUuid)
    }

    private suspend fun dirtyRecordRecoveryRig(
        deletedAt: Long?,
    ): Triple<com.lezi.babylog.sync.session.SyncSession, ReplicaEngineRig, String> {
        val session = recoverySession()
        val rig = ReplicaEngineRig(session)
        return Triple(session, rig, configureDirtyRecordRecovery(rig, deletedAt))
    }

    private fun recoverySession() = joinedReplicaSession().copy(
        pullCursor = 9,
        pullGeneration = "old-generation",
    )

    private suspend fun configureDirtyRecordRecovery(
        rig: ReplicaEngineRig,
        deletedAt: Long?,
    ): String {
        val babyId = rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        val recordUuid = if (deletedAt == null) {
            "record-dirty-edit-full-resync"
        } else {
            "record-dirty-delete-full-resync"
        }
        rig.records.seed(
            RecordEntity(
                clientUuid = recordUuid,
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                note = "离线编辑",
                payloadJson = "{\"amount_ml\":120}",
                updatedAt = 500,
                deletedAt = deletedAt,
                syncDirty = true,
                baseVersion = "v-old-record",
                mutationId = "mutation-before-recovery",
            ),
        )
        rig.backend.pullFailures += SyncHttpException(
            statusCode = 409,
            responseBody =
                """{"detail":{"code":"cursor_ahead","action":"full_resync","reset_cursor":0,"server_cursor":1,"server_generation":"new-generation"}}""",
        )
        rig.backend.pullResults += PullResult(
            entities = listOf(
                remoteReplicaBaby().copy(clientUuid = "baby-local", versionId = "v-baby"),
                remoteReplicaRecord(recordUuid).copy(versionId = "v-remote-record"),
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
        return recordUuid
    }

    private fun configureRemoteFullResync(
        rig: ReplicaEngineRig,
        recordUuid: String,
        note: String,
    ) {
        rig.backend.pullFailures += SyncHttpException(
            statusCode = 409,
            responseBody =
                """{"detail":{"code":"cursor_ahead","action":"full_resync","reset_cursor":0,"server_cursor":1,"server_generation":"new-generation"}}""",
        )
        rig.backend.pullResults += PullResult(
            entities = listOf(
                remoteReplicaBaby().copy(clientUuid = "baby-local", versionId = "v-baby"),
                remoteReplicaRecord(recordUuid).copy(
                    payloadJson = remoteReplicaRecord(recordUuid).payloadJson.replace(
                        "\"note\":null",
                        "\"note\":\"$note\"",
                    ),
                    versionId = "v-remote-record",
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
    }
}
