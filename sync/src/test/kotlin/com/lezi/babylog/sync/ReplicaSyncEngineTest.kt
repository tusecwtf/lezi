package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.FamilyEntity
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.OutboxEntity
import com.lezi.babylog.core.database.RecordEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class ReplicaSyncEngineTest {
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
    fun concurrentNextFeedCreateAcceptsNasWinnerAndDropsLosingOutbox() = runTest {
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
        rig.outbox.enqueue(
            OutboxEntity(
                familyId = session.familyId,
                entityType = "care_plan",
                clientUuid = planUuid,
                payloadJson = "{}",
                updatedAt = 300,
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
        assertThat(rig.outbox.all()).isEmpty()
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
        assertThat(rig.outbox.all()).isEmpty()
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
        assertThat(rig.babies.getByClientUuid("baby-local")!!.syncDirty).isTrue()
        assertThat(rig.babies.getByClientUuid("baby-remote")!!.familyAuthority).isTrue()
        assertThat(rig.familyBabyAppliedCalls).isEqualTo(1)
        assertThat(rig.authorityVisibleAtCallback).isTrue()
    }

    @Test
    fun memberWithMultipleAuthorityBabies_holdsOrphanFactsUntilExplicitMerge() = runTest {
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
                payloadJson = "{\"amount\":\"medium\"}",
                updatedAt = 500,
                syncDirty = true,
            ),
        )
        rig.outbox.enqueue(
            OutboxEntity(
                familyId = session.familyId,
                entityType = "record",
                clientUuid = "record-local-orphan",
                payloadJson = "{}",
                updatedAt = 500,
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
        assertThat(rig.records.getByClientUuid("record-local-orphan")!!.syncDirty).isTrue()
        assertThat(rig.outbox.all().none { it.clientUuid == "record-local-orphan" }).isTrue()
        assertThat(
            rig.backend.pushes.flatMap { it.entities }.none {
                it.clientUuid == "record-local-orphan"
            },
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

        val planPayload = Json.parseToJsonElement(
            rig.backend.stagedBundles.single { it.root.type == "care_plan" }.root.payloadJson,
        ).jsonObject
        val itemPayload = Json.parseToJsonElement(
            rig.backend.stagedBundles.single { it.root.type == "custom_item" }.root.payloadJson,
        ).jsonObject
        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(planPayload["created_by_membership_id"].toString()).isEqualTo("null")
        assertThat(itemPayload["created_by_membership_id"].toString()).isEqualTo("null")
        assertThat(rig.backend.pullCount).isEqualTo(1)
        assertThat(rig.carePlans.getByClientUuid("plan-recovered-blank")?.let {
            Triple(it.createdByMembershipId, it.updatedAt, it.syncDirty)
        }).isEqualTo(Triple("canonical-membership", 710L, false))
        assertThat(rig.customItems.get("item-recovered-blank")?.let {
            Triple(it.createdByMembershipId, it.updatedAt, it.syncDirty)
        }).isEqualTo(Triple("canonical-membership", 720L, false))
        assertThat(rig.outbox.all()).isEmpty()
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
    val outbox = MemoryOutboxDao()
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
    )
    val families = MemoryFamilyDao().apply {
        seed(FamilyEntity(id = 1, ownerUserId = 1, createdAt = 0))
    }
    var familyBabyAppliedCalls = 0
    var authorityVisibleAtCallback = false
    val engine = ReplicaSyncEngine(
        backend = backend,
        preferences = preferences,
        outboxDao = outbox,
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
        carePlanAppliedListener = NoOpCarePlanFamilyAppliedListener(),
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
    familyToken = "token-a",
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
