package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.FamilyEntity
import com.lezi.babylog.core.database.MediaAssetEntity
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
    fun fullPageWithoutContinuationMarkerFailsClosedBeforeApply() = runTest {
        val rig = ReplicaEngineRig(joinedReplicaSession())
        rig.backend.nextPull = PullResult(
            entities = List(200) { index ->
                remoteReplicaBaby().copy(clientUuid = "baby-full-page-$index")
            },
            cursor = 200,
            hasMore = null,
        )

        val failure = runCatching {
            rig.engine.synchronize(
                session = rig.preferences.current(),
                trigger = SyncTrigger.PullToRefresh,
            )
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("缺少 has_more")
        assertThat(rig.preferences.current().pullCursor).isEqualTo(0)
        assertThat(rig.babies.getByClientUuid("baby-full-page-0")).isNull()
    }

    @Test
    fun continuationCannotExceedTheBoundedPageLimit() = runTest {
        val rig = ReplicaEngineRig(joinedReplicaSession())
        repeat(500) { index ->
            rig.backend.pullResults += PullResult(
                entities = emptyList(),
                cursor = index.toLong() + 1,
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
    fun synchronizationConvergesLegacyMembershipAliasToCanonicalSelf() = runTest {
        val session = joinedReplicaSession().copy(membershipId = "legacy-membership-alias")
        val rig = ReplicaEngineRig(
            session = session,
            capabilities = setOf(
                CAPABILITY_ATOMIC_BUNDLE,
                CAPABILITY_RECORD_MEMBERSHIP_AUTHOR,
            ),
        )
        rig.backend.nextMembers = listOf(
            FamilyMember(
                displayName = "妈妈",
                role = FamilyRole.Owner,
                isSelf = true,
                membershipId = "canonical-membership",
            ),
            FamilyMember(
                displayName = "爸爸",
                role = FamilyRole.Member,
                isSelf = false,
                membershipId = "peer-membership",
            ),
        )

        val outcome = rig.engine.synchronize(
            session = session,
            trigger = SyncTrigger.PullToRefresh,
        )

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.backend.memberCalls).isEqualTo(1)
        assertThat(rig.preferences.current().membershipId)
            .isEqualTo("canonical-membership")
    }

    @Test
    fun canonicalSelfConvergenceRepairsOnlyMatchingLocalCreatorStamps() = runTest {
        val session = joinedReplicaSession().copy(membershipId = "legacy-membership-alias")
        val rig = ReplicaEngineRig(
            session = session,
            capabilities = setOf(
                CAPABILITY_ATOMIC_BUNDLE,
                CAPABILITY_RECORD_MEMBERSHIP_AUTHOR,
            ),
        )
        rig.backend.nextMembers = listOf(
            FamilyMember(
                displayName = "妈妈",
                role = FamilyRole.Owner,
                isSelf = true,
                membershipId = "canonical-membership",
            ),
            FamilyMember(
                displayName = "爸爸",
                role = FamilyRole.Member,
                isSelf = false,
                membershipId = "peer-membership",
            ),
        )
        rig.carePlans.seed(
            localReplicaCarePlan("plan-self", "legacy-membership-alias", updatedAt = 110),
        )
        rig.carePlans.seed(
            localReplicaCarePlan("plan-peer", "peer-membership", updatedAt = 120),
        )
        rig.customItems.seed(
            localReplicaCustomItem("item-self", "legacy-membership-alias", updatedAt = 130),
        )
        rig.customItems.seed(
            localReplicaCustomItem("item-peer", "peer-membership", updatedAt = 140),
        )

        val outcome = rig.engine.synchronize(
            session = session,
            trigger = SyncTrigger.PullToRefresh,
        )

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.carePlans.getByClientUuid("plan-self"))
            .isEqualTo(
                localReplicaCarePlan(
                    "plan-self",
                    "canonical-membership",
                    updatedAt = 110,
                ).copy(id = 1),
            )
        assertThat(rig.carePlans.getByClientUuid("plan-peer"))
            .isEqualTo(localReplicaCarePlan("plan-peer", "peer-membership", 120).copy(id = 2))
        assertThat(rig.customItems.get("item-self"))
            .isEqualTo(
                localReplicaCustomItem(
                    "item-self",
                    "canonical-membership",
                    updatedAt = 130,
                ).copy(id = 1),
            )
        assertThat(rig.customItems.get("item-peer"))
            .isEqualTo(localReplicaCustomItem("item-peer", "peer-membership", 140).copy(id = 2))
        assertThat(rig.backend.pushes).isEmpty()
        assertThat(rig.backend.stagedBundles).isEmpty()
    }

    @Test
    fun canonicalSelfConvergenceRepairsAlreadyPendingCreatorPayloadsAtSameRevision() = runTest {
        val session = joinedReplicaSession().copy(membershipId = "legacy-membership-alias")
        val rig = ReplicaEngineRig(
            session = session,
            capabilities = setOf(
                CAPABILITY_ATOMIC_BUNDLE,
                CAPABILITY_RECORD_MEMBERSHIP_AUTHOR,
            ),
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
            localReplicaCarePlan("plan-pending", "legacy-membership-alias", updatedAt = 210)
                .copy(syncDirty = true),
        )
        rig.customItems.seed(
            localReplicaCustomItem("item-pending", "legacy-membership-alias", updatedAt = 220)
                .copy(syncDirty = true),
        )

        val outcome = rig.engine.synchronize(
            session = session,
            trigger = SyncTrigger.PullToRefresh,
        )

        val planPayload = Json.parseToJsonElement(
            rig.backend.stagedBundles.single().root.payloadJson,
        ).jsonObject
        val itemPayload = Json.parseToJsonElement(
            rig.backend.pushes.single().entities.single { it.type == "custom_item" }.payloadJson,
        ).jsonObject
        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(planPayload.getValue("created_by_membership_id").jsonPrimitive.content)
            .isEqualTo("canonical-membership")
        assertThat(itemPayload.getValue("created_by_membership_id").jsonPrimitive.content)
            .isEqualTo("canonical-membership")
        assertThat(rig.carePlans.getByClientUuid("plan-pending")?.updatedAt).isEqualTo(210)
        assertThat(rig.customItems.get("item-pending")?.updatedAt).isEqualTo(220)
    }

    @Test
    fun canonicalSelfConvergenceDoesNotClaimAStampThatNowIdentifiesAPeer() = runTest {
        val session = joinedReplicaSession().copy(membershipId = "peer-membership")
        val rig = ReplicaEngineRig(
            session = session,
            capabilities = setOf(
                CAPABILITY_ATOMIC_BUNDLE,
                CAPABILITY_RECORD_MEMBERSHIP_AUTHOR,
            ),
        )
        rig.backend.nextMembers = listOf(
            FamilyMember(
                displayName = "妈妈",
                role = FamilyRole.Owner,
                isSelf = true,
                membershipId = "canonical-membership",
            ),
            FamilyMember(
                displayName = "爸爸",
                role = FamilyRole.Member,
                isSelf = false,
                membershipId = "peer-membership",
            ),
        )
        rig.carePlans.seed(
            localReplicaCarePlan("plan-peer", "peer-membership", updatedAt = 310),
        )
        rig.customItems.seed(
            localReplicaCustomItem("item-peer", "peer-membership", updatedAt = 320),
        )

        val outcome = rig.engine.synchronize(
            session = session,
            trigger = SyncTrigger.PullToRefresh,
        )

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.preferences.current().membershipId).isEqualTo("canonical-membership")
        assertThat(rig.carePlans.getByClientUuid("plan-peer")?.createdByMembershipId)
            .isEqualTo("peer-membership")
        assertThat(rig.customItems.get("item-peer")?.createdByMembershipId)
            .isEqualTo("peer-membership")
        assertThat(rig.backend.pushes).isEmpty()
        assertThat(rig.backend.stagedBundles).isEmpty()
    }

    @Test
    fun newerRemoteRevisionCannotRestoreARepairedSelfAlias() = runTest {
        val session = joinedReplicaSession().copy(membershipId = "legacy-membership-alias")
        val rig = ReplicaEngineRig(
            session = session,
            capabilities = setOf(
                CAPABILITY_ATOMIC_BUNDLE,
                CAPABILITY_RECORD_MEMBERSHIP_AUTHOR,
            ),
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
            localReplicaCarePlan("plan-self", "legacy-membership-alias", updatedAt = 400),
        )
        rig.customItems.seed(
            localReplicaCustomItem("item-self", "legacy-membership-alias", updatedAt = 400),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "custom_item",
                    clientUuid = "item-self",
                    payloadJson =
                        """{"name":"新名称","icon_slot":2,"created_by_membership_id":"legacy-membership-alias"}""",
                    updatedAt = 410,
                ),
                SyncEntity(
                    type = "care_plan",
                    clientUuid = "plan-self",
                    payloadJson =
                        """{"baby_client_uuid":"baby-local","type":"formula","scheduled_at":9000000001000,"scheduled_zone_id":"Asia/Shanghai","status":"pending","payload_json":{},"schema_version":1,"created_by_membership_id":"legacy-membership-alias"}""",
                    updatedAt = 420,
                ),
            ),
            cursor = 2,
            generation = "generation-a",
        )

        val outcome = rig.engine.synchronize(
            session = session,
            trigger = SyncTrigger.PullToRefresh,
        )

        assertThat(outcome).isEqualTo(ReplicaSyncOutcome.Synchronized)
        assertThat(rig.customItems.get("item-self")?.name).isEqualTo("新名称")
        assertThat(rig.customItems.get("item-self")?.createdByMembershipId)
            .isEqualTo("canonical-membership")
        assertThat(rig.carePlans.getByClientUuid("plan-self")?.scheduledAt)
            .isEqualTo(9_000_000_001_000)
        assertThat(rig.carePlans.getByClientUuid("plan-self")?.createdByMembershipId)
            .isEqualTo("canonical-membership")
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
                    "server_cursor":1
                  }
                }
            """.trimIndent(),
        )
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 2,
            generation = "new-generation",
        )

        val outcome = rig.engine.synchronize(
            session = session,
            trigger = SyncTrigger.PullToRefresh,
        )

        assertThat(rig.backend.pullCursors).containsExactly(9L, 0L, 2L).inOrder()
        assertThat(
            rig.backend.pushes
                .flatMap(PushedBatch::entities)
                .map(SyncEntity::clientUuid),
        ).contains("baby-local")
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
                createdByUserId = 1,
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
        ).let { PullResult(listOf(it), cursor = 1, generation = "generation-a") }
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
                createdByUserId = 1,
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
        ).let { PullResult(listOf(it), cursor = 1, generation = "generation-a") }

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
    capabilities: Set<String> = setOf(CAPABILITY_ATOMIC_BUNDLE),
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
    val families = MemoryFamilyDao().apply {
        seed(FamilyEntity(id = 1, ownerUserId = 1, createdAt = 0))
    }
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
        transactionRunner = transactions,
        carePlanAppliedListener = NoOpCarePlanFamilyAppliedListener(),
        fulfillmentCandidateDao = fulfillmentCandidates,
        requireRemoteAllowed = {},
        remoteCapabilities = { capabilities },
    )
}

private fun joinedReplicaSession() = SyncSession(
    familyId = "family-a",
    familyToken = "token-a",
    deviceId = "device-a",
    role = FamilyRole.Owner,
    serverHost = "192.168.50.4",
    serverPort = 8765,
    allowedSsids = listOf("Home"),
)

private fun remoteReplicaBaby() = SyncEntity(
    type = "baby",
    clientUuid = "baby-remote",
    updatedAt = 100,
    deletedAt = null,
    payloadJson = """
        {
          "nickname":"远端宝宝",
          "birthday":"2024-01-01",
          "sort_order":0
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
          "created_by_device_id":"device-b",
          "type":"formula",
          "timestamp":210,
          "payload_json":{"amount_ml":90},
          "schema_version":1
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
          "mime":"image/jpeg",
          "byte_size":4
        }
    """.trimIndent(),
    updatedAt = 210,
)
