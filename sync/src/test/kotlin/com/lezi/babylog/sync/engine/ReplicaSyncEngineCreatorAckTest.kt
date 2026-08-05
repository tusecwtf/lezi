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

class ReplicaSyncEngineCreatorAckTest {
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
}
