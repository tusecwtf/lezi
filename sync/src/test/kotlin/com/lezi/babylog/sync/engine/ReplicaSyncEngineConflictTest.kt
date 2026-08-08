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

class ReplicaSyncEngineConflictTest {
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
    fun memberNextFeedFullCyclePullsNasWinnerAgainAfterConcurrentNoOpPush() = runTest {
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

        // NAS winner convergence after concurrent create is a full-cycle (pull) concern.
        val outcome = rig.engine.synchronize(session, SyncTrigger.Foreground)

        val winner = rig.carePlans.getByClientUuid(planUuid)!!
        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized())
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

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized())
        val record = rig.records.getByClientUuid(recordUuid)!!
        assertThat(record.updatedAt).isEqualTo(300)
        assertThat(record.deletedAt).isEqualTo(300)
        assertThat(record.syncDirty).isFalse()
        assertThat(rig.backend.stagedBundles.map { it.root.clientUuid })
            .doesNotContain(recordUuid)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(1)
        assertThat(rig.preferences.current().pullGeneration).isEqualTo("new-generation")
    }
}
