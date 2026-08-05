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

class ReplicaSyncEnginePullCheckpointTest {
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

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized())
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
        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized())
        assertThat(rig.preferences.current().pullCursor).isEqualTo(2)
    }
}
