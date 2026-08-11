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

class ReplicaSyncEnginePushReplanTest {
    @Test
    fun ownerReconcilesBeforeBuildingAndPublishingTheRoomPlan() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session)
        rig.babies.seed(localReplicaBaby().copy(syncDirty = true))

        val outcome = rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        // Without causal wire LocalWrite still pulls (no-pull is capability-gated).
        assertThat(rig.backend.syncOrder)
            .containsExactly("handshake", "pull:0", "reconcile:1", "stage:baby")
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

        // Full cycle still pulls first; LocalWrite no longer does.
        val outcome = rig.engine.synchronize(session, SyncTrigger.PullToRefresh)

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
}
