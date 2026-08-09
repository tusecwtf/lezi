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
import com.lezi.babylog.sync.MemoryMediaReferenceDao
import com.lezi.babylog.sync.media.ReferenceAwareMediaFileCleanup
import com.lezi.babylog.sync.session.CreatorAcknowledgementRef
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.PolicyClock
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.receiptFor
import com.lezi.babylog.sync.MemoryBabyDao
import com.lezi.babylog.sync.MemoryCarePlanDao
import com.lezi.babylog.sync.MemoryConflictDetailCacheDao
import com.lezi.babylog.sync.MemoryConflictSummaryDao
import com.lezi.babylog.sync.MemoryCustomItemDao
import com.lezi.babylog.sync.MemoryFamilyDao
import com.lezi.babylog.sync.MemoryFulfillmentCandidateDao
import com.lezi.babylog.sync.MemoryMediaDao
import com.lezi.babylog.sync.MemoryRecordDao
import com.lezi.babylog.sync.MemorySyncPreferences
import com.lezi.babylog.sync.MemorySourceRelationDao
import com.lezi.babylog.sync.MemoryWakeObservationDao
import com.lezi.babylog.sync.RecordingSyncBackend
import com.lezi.babylog.sync.RecordingTransactionRunner
import com.lezi.babylog.sync.TestMediaFileStore

internal class ReplicaEngineRig(
    session: SyncSession,
    allowHistoricalMutableRootEvidence: Boolean = true,
) {
    val backend = RecordingSyncBackend()
    val preferences = MemorySyncPreferences(session)
    val records = MemoryRecordDao()
    val carePlans = MemoryCarePlanDao()
    val fulfillmentCandidates = MemoryFulfillmentCandidateDao()
    val babies = MemoryBabyDao()
    val media = MemoryMediaDao()
    val customItems = MemoryCustomItemDao()
    val mediaFiles = TestMediaFileStore()
    val transactions = RecordingTransactionRunner()
    val fulfillmentAuthoritySettlement =
        com.lezi.babylog.core.database.fulfillment.FulfillmentAuthoritySettlement(
            carePlanDao = carePlans,
            fulfillmentCandidateDao = fulfillmentCandidates,
            transactionRunner = transactions,
        )
    val mediaFileCleanup = ReferenceAwareMediaFileCleanup(
        mediaDao = media,
        mediaReferenceDao = MemoryMediaReferenceDao(),
        mediaFiles = mediaFiles,
        transactionRunner = transactions,
        pathGate = MediaLocalPathGate(),
    )
    val families = MemoryFamilyDao().apply {
        seed(FamilyEntity(id = 1, ownerUserId = 1, createdAt = 0))
    }
    val wakeObservations = MemoryWakeObservationDao()
    val conflictSummaries = MemoryConflictSummaryDao()
    val conflictDetails = MemoryConflictDetailCacheDao()
    val sourceRelations = MemorySourceRelationDao()
    var familyBabyAppliedCalls = 0
    var authorityVisibleAtCallback = false
    val carePlanAppliedBatches = mutableListOf<List<String>>()
    val engine = ReplicaSyncEngine(
        backend = backend,
        preferences = preferences,
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
        carePlanAppliedListener = CarePlanFamilyAppliedListener { planClientUuids ->
            carePlanAppliedBatches += planClientUuids
        },
        familyBabyAppliedListener = FamilyBabyAuthorityAppliedListener {
            familyBabyAppliedCalls++
            authorityVisibleAtCallback = babies.listFamilyAuthority().isNotEmpty()
        },
        fulfillmentCandidateDao = fulfillmentCandidates,
        fulfillmentAuthoritySettlement = fulfillmentAuthoritySettlement,
        requireRemoteAllowed = {},
        wakeObservationDao = wakeObservations,
        conflictSummaryDao = conflictSummaries,
        conflictDetailCacheDao = conflictDetails,
        sourceRelationDao = sourceRelations,
        allowHistoricalMutableRootEvidence = allowHistoricalMutableRootEvidence,
    )
}

internal fun joinedReplicaSession() = SyncSession(
    familyId = "family-a",
    accessToken = "token-a",
    deviceId = "device-a",
    role = FamilyRole.Owner,
    pullGeneration = "generation-a",
    membershipId = "membership-a",
    serverHost = "192.168.50.4",
    serverPort = 8765,
)

internal fun remoteReplicaBaby() = SyncEntity(
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

internal fun localReplicaBaby() = BabyEntity(
    familyId = 1,
    nickname = "本地宝宝",
    birthdayEpochDay = 20_000,
    themeColorArgb = 0,
    clientUuid = "baby-local",
    updatedAt = 100,
)

internal fun localReplicaCarePlan(
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

internal fun localReplicaCustomItem(
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

internal fun remoteReplicaRecord(clientUuid: String) = SyncEntity(
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

internal fun remoteReplicaMedia(
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
