package com.lezi.babylog.sync.engine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.session.CreatorAcknowledgementRef
import com.lezi.babylog.sync.session.FamilyRole

class ReplicaSyncEngineCreatorAckTest {
    @Test
    fun canonicalSessionPullsAcknowledgementsForPendingBlankCreators() = runTest {
        val babyUuid = "00000000-0000-0000-0000-000000000201"
        val planUuid = "00000000-0000-0000-0000-000000000202"
        val itemUuid = "00000000-0000-0000-0000-000000000203"
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
        rig.backend.enableCausal = true
        rig.babies.seed(
            localReplicaBaby().copy(
                clientUuid = babyUuid,
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        rig.carePlans.seed(
            localReplicaCarePlan(planUuid, "", updatedAt = 710)
                .copy(syncDirty = true),
        )
        rig.customItems.seed(
            localReplicaCustomItem(itemUuid, "", updatedAt = 720)
                .copy(syncDirty = true),
        )
        rig.backend.pullResults += PullResult(
            entities = emptyList(),
            cursor = 0,
            generation = "generation-a",
            hasMore = false,
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "custom_item",
                    clientUuid = itemUuid,
                    payloadJson =
                        """{"name":"item-recovered-blank","icon_slot":0,"created_by_membership_id":"canonical-membership"}""",
                    updatedAt = 720,
                ),
                SyncEntity(
                    type = "care_plan",
                    clientUuid = planUuid,
                    payloadJson =
                        """{"baby_client_uuid":"$babyUuid","type":"formula","custom_item_client_uuid":null,"scheduled_at":9000000000000,"scheduled_zone_id":"Asia/Shanghai","note":null,"status":"pending","payload_json":{"amount_ml":120},"schema_version":2,"created_by_membership_id":"canonical-membership","fulfilled_record_client_uuid":null,"fulfilled_at":null}""",
                    updatedAt = 710,
                ),
            ),
            cursor = 2,
            generation = "generation-a",
            hasMore = false,
        )

        // Creator-ack recovery needs a full pull cycle; LocalWrite no longer pulls.
        val outcome = rig.engine.synchronize(
            session = session,
            trigger = SyncTrigger.Foreground,
        )

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.backend.stagedBundles).isEmpty()
        assertThat(rig.backend.pullCount).isEqualTo(2)
        assertThat(rig.backend.causalCommittedUnits.flatten().map { it.entityType })
            .containsExactly("custom_item", "care_plan")
            .inOrder()
        assertThat(rig.carePlans.getByClientUuid(planUuid)?.let {
            Triple(it.createdByMembershipId, it.updatedAt, it.syncDirty)
        }).isEqualTo(Triple("canonical-membership", 710L, false))
        assertThat(rig.customItems.get(itemUuid)?.let {
            Triple(it.createdByMembershipId, it.updatedAt, it.syncDirty)
        }).isEqualTo(Triple("canonical-membership", 720L, false))
    }

    @Test
    fun failedCreatorAcknowledgementPullRetriesOnTheNextFullCycle() = runTest {
        val babyUuid = "00000000-0000-0000-0000-000000000211"
        val planUuid = "00000000-0000-0000-0000-000000000212"
        val itemUuid = "00000000-0000-0000-0000-000000000213"
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
        rig.backend.enableCausal = true
        rig.babies.seed(
            localReplicaBaby().copy(
                clientUuid = babyUuid,
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        rig.carePlans.seed(
            localReplicaCarePlan(planUuid, "", updatedAt = 810)
                .copy(syncDirty = true),
        )
        rig.customItems.seed(
            localReplicaCustomItem(itemUuid, "", updatedAt = 820)
                .copy(syncDirty = true),
        )
        rig.backend.pullFailures += SyncHttpException(statusCode = 503)

        val firstFailure = runCatching {
            rig.engine.synchronize(
                session = rig.preferences.current(),
                trigger = SyncTrigger.Foreground,
            )
        }.exceptionOrNull()

        assertThat(firstFailure).isInstanceOf(SyncHttpException::class.java)
        assertThat(rig.backend.pullCount).isEqualTo(1)
        rig.backend.pullResults += PullResult(
            entities = emptyList(),
            cursor = 0,
            generation = "generation-a",
            hasMore = false,
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "custom_item",
                    clientUuid = itemUuid,
                    payloadJson =
                        """{"name":"item-retry-ack","icon_slot":0,"created_by_membership_id":"canonical-membership"}""",
                    updatedAt = 820,
                ),
                SyncEntity(
                    type = "care_plan",
                    clientUuid = planUuid,
                    payloadJson =
                        """{"baby_client_uuid":"$babyUuid","type":"formula","custom_item_client_uuid":null,"scheduled_at":9000000000000,"scheduled_zone_id":"Asia/Shanghai","note":null,"status":"pending","payload_json":{"amount_ml":120},"schema_version":2,"created_by_membership_id":"canonical-membership","fulfilled_record_client_uuid":null,"fulfilled_at":null}""",
                    updatedAt = 810,
                ),
            ),
            cursor = 2,
            generation = "generation-a",
            hasMore = false,
        )

        val outcome = rig.engine.synchronize(
            session = rig.preferences.current(),
            trigger = SyncTrigger.Foreground,
        )

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.backend.pullCount).isEqualTo(3)
        assertThat(rig.backend.causalCommittedUnits.flatten().map { it.entityType })
            .containsExactly("custom_item", "care_plan")
            .inOrder()
        assertThat(rig.carePlans.getByClientUuid(planUuid)?.createdByMembershipId)
            .isEqualTo("canonical-membership")
        assertThat(rig.customItems.get(itemUuid)?.createdByMembershipId)
            .isEqualTo("canonical-membership")
    }

    @Test
    fun commitFailureKeepsExactLocalCreatorProvenanceUntilAuthoritativePull() = runTest {
        val babyUuid = "00000000-0000-0000-0000-000000000221"
        val planUuid = "00000000-0000-0000-0000-000000000222"
        val itemUuid = "00000000-0000-0000-0000-000000000223"
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Member,
            membershipId = "canonical-membership",
        )
        val rig = ReplicaEngineRig(
            session = session,
        )
        rig.backend.enableCausal = true
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
                clientUuid = babyUuid,
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        rig.carePlans.seed(
            localReplicaCarePlan(planUuid, "", updatedAt = 830)
                .copy(syncDirty = true),
        )
        rig.customItems.seed(
            localReplicaCustomItem(itemUuid, "", updatedAt = 840)
                .copy(syncDirty = true),
        )
        rig.backend.onCausalCommit = {
            rig.backend.onCausalCommit = null
            error("commit interrupted")
        }

        val firstFailure = runCatching {
            rig.engine.synchronize(
                session = rig.preferences.current(),
                trigger = SyncTrigger.LocalWrite,
            )
        }.exceptionOrNull()

        assertThat(firstFailure).hasMessageThat().contains("commit interrupted")
        assertThat(rig.preferences.current().membershipId).isEqualTo("canonical-membership")
        assertThat(rig.preferences.current().pendingCreatorAcknowledgements).containsExactly(
            CreatorAcknowledgementRef("care_plan", planUuid),
            CreatorAcknowledgementRef("custom_item", itemUuid),
        )

        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "custom_item",
                    clientUuid = itemUuid,
                    payloadJson =
                        """{"name":"item-commit-retry","icon_slot":0,"created_by_membership_id":"canonical-membership"}""",
                    updatedAt = 840,
                ),
                SyncEntity(
                    type = "care_plan",
                    clientUuid = planUuid,
                    payloadJson =
                        """{"baby_client_uuid":"$babyUuid","type":"formula","custom_item_client_uuid":null,"scheduled_at":9000000000000,"scheduled_zone_id":"Asia/Shanghai","note":null,"status":"pending","payload_json":{"amount_ml":120},"schema_version":2,"created_by_membership_id":"canonical-membership","fulfilled_record_client_uuid":null,"fulfilled_at":null}""",
                    updatedAt = 830,
                ),
            ),
            cursor = 2,
            generation = "generation-a",
            hasMore = false,
        )

        // Ack recovery requires a full pull cycle after LocalWrite failed mid-commit.
        val outcome = rig.engine.synchronize(
            session = rig.preferences.current(),
            trigger = SyncTrigger.Foreground,
        )

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.preferences.current().pendingCreatorAcknowledgements).isEmpty()
        assertThat(rig.carePlans.getByClientUuid(planUuid)?.createdByMembershipId)
            .isEqualTo("canonical-membership")
        assertThat(rig.customItems.get(itemUuid)?.createdByMembershipId)
            .isEqualTo("canonical-membership")
    }

    @Test
    fun memberPostPushCreatorPullRecoversGenerationChange() = runTest {
        val babyUuid = "00000000-0000-0000-0000-000000000231"
        val itemUuid = "00000000-0000-0000-0000-000000000232"
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Member,
            membershipId = "member-local",
            pullGeneration = "old-generation",
        )
        val rig = ReplicaEngineRig(session)
        rig.backend.enableCausal = true
        rig.babies.seed(
            localReplicaBaby().copy(
                clientUuid = babyUuid,
                syncDirty = false,
                familyAuthority = true,
            ),
        )
        rig.customItems.seed(
            localReplicaCustomItem(itemUuid, "", updatedAt = 840)
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
                remoteReplicaBaby().copy(clientUuid = babyUuid),
                SyncEntity(
                    type = "custom_item",
                    clientUuid = itemUuid,
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

        // Post-push creator pull only runs on full cycles (Foreground / PullToRefresh).
        val outcome = rig.engine.synchronize(
            session = session,
            trigger = SyncTrigger.Foreground,
        )

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.backend.pullCursors).containsExactly(0L, 0L, 0L, 1L).inOrder()
        assertThat(rig.preferences.current().pullGeneration).isEqualTo("new-generation")
        assertThat(rig.preferences.current().pendingCreatorAcknowledgements).isEmpty()
        assertThat(
            rig.customItems.get(itemUuid)?.createdByMembershipId,
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
                trigger = SyncTrigger.Foreground,
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
