package com.lezi.babylog.sync

import com.lezi.babylog.sync.session.ShallowSyncLine
import com.lezi.babylog.sync.session.ShallowSyncState
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.FamilyDao
import com.lezi.babylog.core.database.FamilyEntity
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.FulfillmentCandidateEntity
import com.lezi.babylog.core.database.LocalDataClearScope
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.MediaLocalPathGate
import com.lezi.babylog.core.database.PendingPublishDao
import com.lezi.babylog.core.database.matchesPublishedRevision
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.core.model.RootPublicationState
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Test
import com.lezi.babylog.sync.appupdate.APP_UPDATE_METADATA_PACKAGE_MISMATCH_MESSAGE
import com.lezi.babylog.sync.availability.AvailabilityProbeReason
import com.lezi.babylog.sync.availability.FamilyServerAvailability
import com.lezi.babylog.sync.availability.FamilyServerUnavailableReason
import com.lezi.babylog.sync.appupdate.APP_UPDATE_PACKAGE_INVALID_MESSAGE
import com.lezi.babylog.sync.appupdate.AppUpdateApkIdentityReader
import com.lezi.babylog.sync.appupdate.AppUpdateInstaller
import com.lezi.babylog.sync.appupdate.AppUpdateUiOutcome
import com.lezi.babylog.sync.appupdate.StagedApkIdentity
import com.lezi.babylog.sync.appupdate.appUpdateStagingApk
import com.lezi.babylog.sync.appupdate.appUpdateStagingDir
import com.lezi.babylog.sync.appupdate.appUpdateUiOutcome
import com.lezi.babylog.sync.appupdate.forceShellNeedsSessionRecovery
import com.lezi.babylog.sync.appupdate.lanInviteApkDownloadUrl
import com.lezi.babylog.sync.appupdate.sha256Hex
import com.lezi.babylog.sync.backend.AtomicBundleDraft
import com.lezi.babylog.sync.backend.AuthorityDisposition
import com.lezi.babylog.sync.backend.AuthorityResult
import com.lezi.babylog.sync.backend.AnonymousHealth
import com.lezi.babylog.sync.backend.AnonymousReadiness
import com.lezi.babylog.sync.backend.BundleCommitResult
import com.lezi.babylog.sync.backend.BundleStageStatus
import com.lezi.babylog.sync.backend.CanonicalRecordAuthor
import com.lezi.babylog.sync.backend.ClientUpdateRequiredException
import com.lezi.babylog.sync.backend.DisplayNameUpdateResult
import com.lezi.babylog.sync.backend.DisasterRestoreBatch
import com.lezi.babylog.sync.backend.DisasterRestoreMediaSpec
import com.lezi.babylog.sync.backend.DisasterRestoreStatus
import com.lezi.babylog.sync.backend.MemberLoginGrant
import com.lezi.babylog.sync.backend.MemberLoginReceipt
import com.lezi.babylog.sync.backend.MemberLoginStatus
import com.lezi.babylog.sync.backend.PendingMemberLoginRequest
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.ReconcileResult
import com.lezi.babylog.sync.backend.ReconcileUnitDraft
import com.lezi.babylog.sync.backend.RemoteDeviceRemovedException
import com.lezi.babylog.sync.backend.RemoteFamilyDeletedException
import com.lezi.babylog.sync.backend.RemoteMembershipDeletedException
import com.lezi.babylog.sync.backend.SessionBootstrapResult
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.appupdate.NoOpAppUpdateInstaller
import com.lezi.babylog.sync.clear.LocalClearCommittedException
import com.lezi.babylog.sync.engine.AtomicBundleId
import com.lezi.babylog.sync.engine.CarePlanFamilyAppliedListener
import com.lezi.babylog.sync.engine.ForegroundSyncBlockedException
import com.lezi.babylog.sync.engine.ForegroundSyncGate
import com.lezi.babylog.sync.media.LocalMediaInfo
import com.lezi.babylog.sync.media.ReferenceAwareMediaFileCleanup
import com.lezi.babylog.sync.media.SyncMediaFileStore
import com.lezi.babylog.sync.media.SyncMediaUploadSource
import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import com.lezi.babylog.sync.session.CertificateTrustCandidate
import com.lezi.babylog.sync.session.CreatorAcknowledgementRef
import com.lezi.babylog.sync.session.DisasterRestoreCheckpoint
import com.lezi.babylog.sync.session.DisasterRestoreRequestIds
import com.lezi.babylog.sync.session.FamilyEndpointConfig
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.ForegroundState
import com.lezi.babylog.sync.session.PolicyClock
import com.lezi.babylog.sync.session.SetupFamilyState
import com.lezi.babylog.sync.session.SetupProbe
import com.lezi.babylog.sync.session.SetupProbeResult
import com.lezi.babylog.sync.session.SyncPreferences
import com.lezi.babylog.sync.session.normalizeFamilyNameForWire
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import com.lezi.babylog.sync.session.familySyncError
import com.lezi.babylog.sync.backend.FakeSyncBackend
import com.lezi.babylog.sync.backend.LegacyPushResult
import com.lezi.babylog.sync.backend.testPreparedMedia

// Split from RealSyncPortTest kitchen sink by contract cluster (ticket 05).
class RealSyncPortAtomicMediaTest {
    @Test
    fun freshFamilyPushesBabyAndZeroPhotoRecordAsOrderedAtomicRoots() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.backend.enforceBundleReferences = true
        val babyId = rig.babies.seed(localBaby())
        rig.records.seed(localRecord(babyId))

        val result = rig.port.sync(SyncTrigger.LocalWrite)

        assertThat(result.exceptionOrNull()).isNull()
        assertThat(rig.backend.operationOrder)
            .containsExactly("stage:baby", "stage:record")
            .inOrder()
    }

    @Test
    fun freshFamilyUploadsBabyAvatarWithZeroPhotoRecordAtomicPackage() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.backend.enforceBundleReferences = true
        val avatarUuid = "11111111-1111-4111-8111-111111111111"
        val babyId = rig.babies.seed(
            localBaby().copy(
                clientUuid = "22222222-2222-4222-8222-222222222222",
                avatarMediaUuid = avatarUuid,
                avatarPath = "avatars/baby.jpg",
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                babyId = babyId,
                clientUuid = avatarUuid,
                kind = "avatar",
                localUri = "avatars/baby.jpg",
                mime = "image/jpeg",
                byteSize = 1,
                createdAt = 100,
                updatedAt = 110,
            ),
        )
        rig.records.seed(localRecord(babyId))

        val result = rig.port.sync(SyncTrigger.LocalWrite)

        assertThat(result.exceptionOrNull()).isNull()
        assertThat(rig.backend.operationOrder)
            .containsExactly(
                "stage:baby",
                "put_bundle_media:$avatarUuid",
                "stage:record",
            )
            .inOrder()
        val babyDraft = rig.backend.stagedBundles.first { it.root.type == "baby" }
        assertThat(babyDraft.root.updatedAt).isEqualTo(110)
        assertThat(babyDraft.bundleId).isEqualTo(
            AtomicBundleId.forBaby("22222222-2222-4222-8222-222222222222", 110),
        )
    }

    @Test
    fun atomicRecordAlwaysPublishesCurrentMembershipAuthor() = runTest {
        val modernRig = SyncRig(
            session = joinedSession("family-a").copy(membershipId = "membership-a"),
        )
        val modernBabyId = modernRig.babies.seed(localBaby())
        modernRig.records.seed(
            localRecord(modernBabyId).copy(createdByMembershipId = "membership-a"),
        )

        assertThat(modernRig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val modernPayload = Json.parseToJsonElement(
            modernRig.backend.stagedBundles
                .single { it.root.type == "record" }
                .root
                .payloadJson,
        ).jsonObject
        assertThat(modernPayload["created_by_membership_id"]?.jsonPrimitive?.content)
            .isEqualTo("membership-a")

    }
    @Test
    fun atomicRecordCommitAckHydratesPreJoinAuthorWithoutChangingTheRecordRevision() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(membershipId = "membership-a"),
        )
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.backend.remember("baby", "baby-local")
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "pre-join-record",
                createdByMembershipId = "",
                payloadJson = """{"amount_ml":120}""",
                updatedAt = 120,
                syncDirty = true,
            ),
        )
        rig.backend.nextCommitRecordAuthors = listOf(
            CanonicalRecordAuthor(
                clientUuid = "pre-join-record",
                createdByMembershipId = "membership-a",
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val hydrated = requireNotNull(rig.records.getByClientUuid("pre-join-record"))
        assertThat(hydrated.createdByMembershipId).isEqualTo("membership-a")
        assertThat(hydrated.updatedAt).isEqualTo(120)
        assertThat(hydrated.payloadJson).isEqualTo("""{"amount_ml":120}""")
        assertThat(hydrated.syncDirty).isFalse()
    }

    @Test
    fun atomicRecordCommitRejectsMalformedCanonicalAuthorAcknowledgements() = runTest {
        val malformedAcknowledgements = listOf(
            emptyList(),
            listOf(
                CanonicalRecordAuthor("pre-join-record", "membership-a"),
                CanonicalRecordAuthor("pre-join-record", "membership-a"),
            ),
            listOf(
                CanonicalRecordAuthor("pre-join-record", "membership-a"),
                CanonicalRecordAuthor("unexpected-record", "membership-a"),
            ),
        )

        malformedAcknowledgements.forEach { acknowledgements ->
            val rig = SyncRig(
                session = joinedSession("family-a").copy(membershipId = "membership-a"),
            )
            val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
            rig.backend.remember("baby", "baby-local")
            rig.records.seed(
                localRecord(babyId).copy(
                    clientUuid = "pre-join-record",
                    createdByMembershipId = "",
                    updatedAt = 120,
                    syncDirty = true,
                ),
            )
            rig.backend.nextCommitRecordAuthors = acknowledgements

            val result = rig.port.sync(SyncTrigger.LocalWrite)

            assertThat(result.exceptionOrNull()).hasMessageThat().contains("record_authors")
            val retained = requireNotNull(rig.records.getByClientUuid("pre-join-record"))
            assertThat(retained.createdByMembershipId).isEmpty()
            assertThat(retained.syncDirty).isTrue()
        }
    }


    @Test
    fun avatarDependencyJoinsBabyBatchPastTheNormalLimit() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val avatarUuid = "33333333-3333-3333-3333-333333333333"
        repeat(201) { index ->
            val babyId = rig.babies.seed(
                localBaby().copy(
                    nickname = "宝宝-$index",
                    clientUuid = "baby-$index",
                    avatarPath = if (index == 0) "avatars/first.jpg" else null,
                    updatedAt = index.toLong() + 1,
                ),
            )
            if (index == 0) {
                rig.media.seed(
                    MediaAssetEntity(
                        clientUuid = avatarUuid,
                        kind = "avatar",
                        babyId = babyId,
                        localUri = "avatars/first.jpg",
                        remoteUri = rig.preferences.current().expectedMediaReceipt(avatarUuid),
                        mime = "image/jpeg",
                        byteSize = 12,
                        createdAt = 1,
                        updatedAt = 1,
                    ),
                )
            }
        }

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val babyBundle = rig.backend.stagedBundles.first { it.root.clientUuid == "baby-0" }
        assertThat(babyBundle.media.map(SyncEntity::clientUuid)).contains(avatarUuid)
    }

    @Test
    fun deletedBabyPackagePublishesMediaTombstonesWithoutLiveAvatarPointer() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        // Keeper profile so product "keep at least one" is irrelevant to capture.
        rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-keeper",
                nickname = "保留",
                syncDirty = false,
                updatedAt = 50,
            ),
        )
        val avatarUuid = "44444444-4444-4444-4444-444444444444"
        val legacyUuid = "55555555-5555-5555-5555-555555555555"
        val deletedAt = 300L
        val babyId = rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-deleted",
                nickname = "已删",
                avatarMediaUuid = null,
                avatarPath = null,
                updatedAt = deletedAt,
                deletedAt = deletedAt,
                syncDirty = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = avatarUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "baby_avatars/deleted.jpg",
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = deletedAt,
                deletedAt = deletedAt,
                syncDirty = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = legacyUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "baby_avatars/legacy.jpg",
                mime = "image/jpeg",
                byteSize = 8,
                createdAt = 80,
                updatedAt = deletedAt,
                deletedAt = deletedAt,
                syncDirty = true,
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val babyBundle = rig.backend.stagedBundles.single { it.root.clientUuid == "baby-deleted" }
        assertThat(babyBundle.root.deletedAt).isEqualTo(deletedAt)
        val rootPayload = Json.parseToJsonElement(babyBundle.root.payloadJson).jsonObject
        assertThat(rootPayload["avatar_media_uuid"]).isEqualTo(JsonNull)
        assertThat(babyBundle.media.map(SyncEntity::clientUuid))
            .containsExactly(avatarUuid, legacyUuid)
        assertThat(babyBundle.media.map(SyncEntity::deletedAt)).containsExactly(deletedAt, deletedAt)
        assertThat(rig.backend.mediaUploads).isEmpty()
    }

    @Test
    fun deletedBabyPackageRepairsLiveOrphanAvatarAndForcesNullPointer() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-keeper-orphan",
                nickname = "保留",
                syncDirty = false,
                updatedAt = 50,
            ),
        )
        val liveOrphanUuid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val tombstoneUuid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val deletedAt = 400L
        // Pre-fix orphan shape: deleted root still points at a live avatar row.
        val babyId = rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-deleted-orphan",
                nickname = "已删孤儿",
                avatarMediaUuid = liveOrphanUuid,
                avatarPath = "baby_avatars/orphan-live.jpg",
                updatedAt = deletedAt,
                deletedAt = deletedAt,
                syncDirty = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = liveOrphanUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "baby_avatars/orphan-live.jpg",
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 200,
                deletedAt = null,
                syncDirty = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = tombstoneUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "baby_avatars/orphan-tomb.jpg",
                mime = "image/jpeg",
                byteSize = 8,
                createdAt = 80,
                updatedAt = deletedAt,
                deletedAt = deletedAt,
                syncDirty = true,
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val babyBundle = rig.backend.stagedBundles.single { it.root.clientUuid == "baby-deleted-orphan" }
        assertThat(babyBundle.root.deletedAt).isEqualTo(deletedAt)
        val rootPayload = Json.parseToJsonElement(babyBundle.root.payloadJson).jsonObject
        assertThat(rootPayload["avatar_media_uuid"]).isEqualTo(JsonNull)
        assertThat(babyBundle.media.map(SyncEntity::clientUuid))
            .containsExactly(liveOrphanUuid, tombstoneUuid)
        assertThat(babyBundle.media.single { it.clientUuid == liveOrphanUuid }.deletedAt)
            .isEqualTo(200)
        // The invalid live avatar is deterministically repaired as a technical
        // tombstone; the deleted Baby never regains a live pointer.
        assertThat(rig.media.getByClientUuid(liveOrphanUuid)?.deletedAt).isEqualTo(200)
        assertThat(rig.media.getByClientUuid(liveOrphanUuid)?.syncDirty).isFalse()
    }

    @Test
    fun deletedBabyAvatarPushFailThenRetryKeepsTombstonesAndNullPointer() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-keeper-retry",
                nickname = "保留",
                syncDirty = false,
                updatedAt = 50,
            ),
        )
        val avatarUuid = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val deletedAt = 500L
        val babyId = rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-deleted-retry",
                nickname = "已删重试",
                avatarMediaUuid = null,
                avatarPath = null,
                updatedAt = deletedAt,
                deletedAt = deletedAt,
                syncDirty = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = avatarUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "baby_avatars/retry-tomb.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = deletedAt,
                deletedAt = deletedAt,
                syncDirty = true,
            ),
        )
        val expectedBundleId = AtomicBundleId.forBaby("baby-deleted-retry", deletedAt)
        rig.backend.afterCommit = {
            throw IllegalStateException("crash after remote commit before local ack")
        }

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isFailure).isTrue()
        assertThat(rig.backend.committedBundles).containsExactly(expectedBundleId)
        val afterFailBaby = requireNotNull(rig.babies.getIncludingDeleted(babyId))
        assertThat(afterFailBaby.deletedAt).isEqualTo(deletedAt)
        assertThat(afterFailBaby.avatarMediaUuid).isNull()
        assertThat(afterFailBaby.syncDirty).isTrue()
        val afterFailAvatar = requireNotNull(rig.media.getByClientUuid(avatarUuid))
        assertThat(afterFailAvatar.deletedAt).isEqualTo(deletedAt)
        assertThat(afterFailAvatar.syncDirty).isTrue()
        val failedBundle = rig.backend.stagedBundles.single { it.bundleId == expectedBundleId }
        assertThat(
            Json.parseToJsonElement(failedBundle.root.payloadJson).jsonObject["avatar_media_uuid"],
        ).isEqualTo(JsonNull)
        assertThat(failedBundle.media.map(SyncEntity::clientUuid)).containsExactly(avatarUuid)
        assertThat(failedBundle.media.single().deletedAt).isEqualTo(deletedAt)

        rig.backend.afterCommit = null
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        assertThat(rig.backend.committedBundles.count { it == expectedBundleId }).isEqualTo(2)
        val publishedBaby = requireNotNull(rig.babies.getIncludingDeleted(babyId))
        assertThat(publishedBaby.deletedAt).isEqualTo(deletedAt)
        assertThat(publishedBaby.avatarMediaUuid).isNull()
        assertThat(publishedBaby.syncDirty).isFalse()
        val publishedAvatar = requireNotNull(rig.media.getByClientUuid(avatarUuid))
        assertThat(publishedAvatar.deletedAt).isEqualTo(deletedAt)
        assertThat(publishedAvatar.syncDirty).isFalse()
        val retryBundles = rig.backend.stagedBundles.filter { it.bundleId == expectedBundleId }
        assertThat(retryBundles).hasSize(2)
        retryBundles.forEach { bundle ->
            assertThat(
                Json.parseToJsonElement(bundle.root.payloadJson).jsonObject["avatar_media_uuid"],
            ).isEqualTo(JsonNull)
            assertThat(bundle.media.map(SyncEntity::deletedAt)).containsExactly(deletedAt)
            assertThat(bundle.media.none { it.deletedAt == null }).isTrue()
        }
    }

    @Test
    fun pullDeletedBabyWithAvatarTombstoneDoesNotRevivePointer() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val avatarUuid = "66666666-6666-6666-6666-666666666666"
        val babyId = rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-local",
                avatarMediaUuid = avatarUuid,
                avatarPath = "baby_avatars/live.jpg",
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = avatarUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "baby_avatars/live.jpg",
                remoteUri = rig.preferences.current().expectedMediaReceipt(avatarUuid),
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        val remoteDeletedAt = 400L
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteBaby().copy(
                    clientUuid = "baby-local",
                    payloadJson = """
                        {
                          "nickname":"服务器宝宝",
                          "sex":null,
                          "birthday":"2024-01-01",
                          "birth_weight_grams":null,
                          "avatar_media_uuid":null
                        }
                    """.trimIndent(),
                    updatedAt = remoteDeletedAt,
                    deletedAt = remoteDeletedAt,
                ),
                SyncEntity(
                    type = "media",
                    clientUuid = avatarUuid,
                    payloadJson = """
                        {
                          "kind":"avatar",
                          "record_client_uuid":null,
                          "care_plan_client_uuid":null,
                          "baby_client_uuid":"baby-local",
                          "mime":"image/jpeg",
                          "width":null,
                          "height":null,
                          "byte_size":0
                        }
                    """.trimIndent(),
                    updatedAt = remoteDeletedAt,
                    deletedAt = remoteDeletedAt,
                ),
            ),
            cursor = 1,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        val baby = requireNotNull(rig.babies.getIncludingDeleted(babyId))
        assertThat(baby.deletedAt).isEqualTo(remoteDeletedAt)
        assertThat(baby.avatarMediaUuid).isNull()
        val avatar = requireNotNull(rig.media.getByClientUuid(avatarUuid))
        assertThat(avatar.deletedAt).isEqualTo(remoteDeletedAt)
        assertThat(avatar.syncDirty).isFalse()
    }

    @Test
    fun avatarMaterializationNeverOverwritesAProfileChangedAfterSnapshot() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(
            localBaby().copy(avatarPath = "baby_avatars/local.jpg"),
        )
        rig.mediaFiles.afterInspect = {
            val current = requireNotNull(rig.babies.getIncludingDeleted(babyId))
            rig.babies.update(
                current.copy(
                    nickname = "并发改名",
                    updatedAt = current.updatedAt + 1,
                    syncDirty = true,
                ),
            )
        }

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val baby = requireNotNull(rig.babies.getIncludingDeleted(babyId))
        assertThat(baby.nickname).isEqualTo("并发改名")
        assertThat(baby.updatedAt).isEqualTo(101)
        // Materialize skipped the avatar after the concurrent profile edit; wire
        // still published the re-read concurrent baby root (no avatar pointer).
        assertThat(baby.avatarMediaUuid).isNull()
        // Synthetic root ack uses the content epoch at push time (101), so the
        // concurrent rename is confirmed clean rather than left spuriously dirty.
        assertThat(baby.syncDirty).isFalse()
    }

    @Test
    fun recordMediaSnapshotUsesMediaAssetRowsOnly() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                payloadJson = """{"amount_ml":120}""",
            ),
        )
        val mediaUuid = "32323232-3232-3232-3232-323232323232"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "photos/user-new.jpg",
                remoteUri = rig.preferences.current().expectedMediaReceipt(mediaUuid),
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.remember("baby", "baby-local")
        rig.backend.remember("record", "record-local")
        rig.backend.remember("media", mediaUuid)
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val record = requireNotNull(rig.records.getIncludingDeleted(recordId))
        assertThat(record.payloadJson).isEqualTo("""{"amount_ml":120}""")
        assertThat(record.note).isNull()
        assertThat(record.updatedAt).isEqualTo(120)
        assertThat(record.syncDirty).isFalse()
        assertThat(rig.media.getByClientUuid(mediaUuid)?.deletedAt).isNull()
        assertThat(rig.media.listAllIncludingDeleted().map(MediaAssetEntity::localUri))
            .containsExactly("photos/user-new.jpg")
    }

    @Test
    fun downloadedPhotoRefreshPreservesAConcurrentRecordEdit() = runTest {
        val session = joinedSession("family-a")
        val rig = SyncRig(session = session)
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                payloadJson = """{"amount_ml":120}""",
                syncDirty = false,
            ),
        )
        val mediaUuid = "33333333-3333-3333-3333-333333333333"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "",
                remoteUri = rig.preferences.current().expectedMediaReceipt(mediaUuid),
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.remember("baby", "baby-local")
        rig.backend.remember("record", "record-local")
        rig.backend.remember("media", mediaUuid)
        rig.backend.beforeGetMediaReturn = {
            val current = requireNotNull(rig.records.getIncludingDeleted(recordId))
            rig.records.update(
                current.copy(
                    note = "并发补充说明",
                    updatedAt = current.updatedAt + 1,
                    syncDirty = true,
                ),
            )
        }
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 1,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        val record = requireNotNull(rig.records.getIncludingDeleted(recordId))
        assertThat(record.note).isEqualTo("并发补充说明")
        assertThat(record.updatedAt).isEqualTo(121)
        assertThat(record.syncDirty).isFalse()
        assertThat(rig.backend.stagedBundles.map { it.root.clientUuid })
            .contains("record-local")
        assertThat(record.payloadJson).isEqualTo("""{"amount_ml":120}""")
        assertThat(rig.media.getByClientUuid(mediaUuid)?.localUri)
            .isEqualTo("downloaded/$mediaUuid")
    }

    @Test
    fun pullWindowPhotoEditStaysAuthoritativeWhenDownloadStartsLater() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(syncDirty = false),
        )
        val mediaUuid = "34343434-3434-3434-3434-343434343434"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "",
                remoteUri = rig.preferences.current().expectedMediaReceipt(mediaUuid),
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.beforePullReturn = {
            val current = requireNotNull(rig.records.getIncludingDeleted(recordId))
            rig.records.update(
                current.copy(
                    note = "拉取期间编辑",
                    updatedAt = current.updatedAt + 1,
                    syncDirty = true,
                ),
            )
        }
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 1,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        val record = requireNotNull(rig.records.getIncludingDeleted(recordId))
        assertThat(record.payloadJson).isEqualTo("""{"amount_ml":120}""")
        assertThat(record.note).isEqualTo("拉取期间编辑")
        assertThat(rig.media.getByClientUuid(mediaUuid)?.localUri)
            .isEqualTo("downloaded/$mediaUuid")
    }

    @Test
    fun photoEditBetweenTwoDownloadsPreventsTheSecondDerivedRefresh() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(syncDirty = false),
        )
        val mediaUuids = listOf(
            "35353535-3535-3535-3535-353535353535",
            "36363636-3636-3636-3636-363636363636",
        )
        mediaUuids.forEach { mediaUuid ->
            rig.media.seed(
                MediaAssetEntity(
                    recordId = recordId,
                    clientUuid = mediaUuid,
                    kind = "log",
                    localUri = "",
                    remoteUri = rig.preferences.current().expectedMediaReceipt(mediaUuid),
                    mime = "image/jpeg",
                    byteSize = 12,
                    createdAt = 100,
                    updatedAt = 100,
                    syncDirty = false,
                ),
            )
        }
        rig.mediaFiles.afterSaveDownloaded = {
            val current = requireNotNull(rig.media.getByClientUuid(mediaUuids.last()))
            rig.media.update(
                current.copy(
                    localUri = "photos/between-downloads.jpg",
                    updatedAt = current.updatedAt + 1,
                    syncDirty = true,
                ),
            )
        }
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 1,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        assertThat(rig.records.getIncludingDeleted(recordId)?.payloadJson)
            .isEqualTo("""{"amount_ml":120}""")
        assertThat(rig.media.getByClientUuid(mediaUuids.first())?.localUri)
            .isEqualTo("downloaded/${mediaUuids.first()}")
        assertThat(rig.media.getByClientUuid(mediaUuids.last())?.localUri)
            .isEqualTo("photos/between-downloads.jpg")
    }

    @Test
    fun downloadedAvatarRefreshPreservesAProfileEditDuringFileSave() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val mediaUuid = "44444444-4444-4444-4444-444444444444"
        val babyId = rig.babies.seed(
            localBaby().copy(
                avatarMediaUuid = mediaUuid,
                avatarPath = null,
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = mediaUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "",
                remoteUri = rig.preferences.current().expectedMediaReceipt(mediaUuid),
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.remember("baby", "baby-local")
        rig.backend.remember("media", mediaUuid)
        rig.mediaFiles.afterSaveDownloaded = {
            val current = requireNotNull(rig.babies.getIncludingDeleted(babyId))
            rig.babies.update(
                current.copy(
                    avatarPath = "baby_avatars/user-new.jpg",
                    updatedAt = current.updatedAt + 1,
                    syncDirty = true,
                ),
            )
        }
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 1,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        val baby = requireNotNull(rig.babies.getIncludingDeleted(babyId))
        assertThat(baby.avatarPath).isEqualTo("baby_avatars/user-new.jpg")
        assertThat(baby.updatedAt).isEqualTo(101)
        assertThat(baby.syncDirty).isFalse()
        assertThat(rig.backend.stagedBundles.map { it.root.clientUuid })
            .contains("baby-local")
        assertThat(rig.media.getByClientUuid(mediaUuid)?.localUri).isEmpty()
        assertThat(rig.mediaFiles.deleted).contains("downloaded/$mediaUuid")
    }

    @Test
    fun noteOnlyEditAcceptsDownloadedPhotoAndNeverTombstonesItNextSync() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(syncDirty = false),
        )
        val mediaUuid = "45454545-4545-4545-4545-454545454545"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "",
                remoteUri = rig.preferences.current().expectedMediaReceipt(mediaUuid),
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.remember("baby", "baby-local")
        rig.backend.remember("record", "record-local")
        rig.backend.remember("media", mediaUuid)
        rig.backend.beforeGetMediaReturn = {
            val current = requireNotNull(rig.records.getIncludingDeleted(recordId))
            rig.records.update(
                current.copy(
                    note = "只改备注",
                    updatedAt = current.updatedAt + 1,
                    syncDirty = true,
                ),
            )
        }
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 1,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val record = requireNotNull(rig.records.getIncludingDeleted(recordId))
        assertThat(record.note).isEqualTo("只改备注")
        assertThat(record.payloadJson).isEqualTo("""{"amount_ml":120}""")
        val localMedia = requireNotNull(rig.media.getByClientUuid(mediaUuid))
        assertThat(localMedia.deletedAt).isNull()
        assertThat(localMedia.localUri).isEqualTo("downloaded/$mediaUuid")
        // Any republished photo stays inside its atomic root package and must remain live.
        val packageMedia = rig.backend.stagedBundles
            .flatMap { it.media }
            .filter { it.clientUuid == mediaUuid }
        assertThat(packageMedia.all { it.deletedAt == null }).isTrue()
    }

    @Test
    fun nicknameOnlyEditAcceptsDownloadedAvatarAndNeverTombstonesItNextSync() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val mediaUuid = "46464646-4646-4646-4646-464646464646"
        val babyId = rig.babies.seed(
            localBaby().copy(
                avatarMediaUuid = mediaUuid,
                avatarPath = null,
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = mediaUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "",
                remoteUri = rig.preferences.current().expectedMediaReceipt(mediaUuid),
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.remember("baby", "baby-local")
        rig.backend.remember("media", mediaUuid)
        rig.mediaFiles.afterSaveDownloaded = {
            val current = requireNotNull(rig.babies.getIncludingDeleted(babyId))
            rig.babies.update(
                current.copy(
                    nickname = "只改昵称",
                    updatedAt = current.updatedAt + 1,
                    syncDirty = true,
                ),
            )
        }
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 1,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val baby = requireNotNull(rig.babies.getIncludingDeleted(babyId))
        assertThat(baby.nickname).isEqualTo("只改昵称")
        assertThat(baby.avatarPath).isEqualTo("downloaded/$mediaUuid")
        assertThat(rig.media.getByClientUuid(mediaUuid)?.deletedAt).isNull()
        val babyDraft = rig.backend.stagedBundles.last { it.root.type == "baby" }
        val pushedMedia = rig.backend.stagedBundles
            .flatMap { it.media }
            .filter { it.clientUuid == mediaUuid }
        assertThat(babyDraft.root.payloadJson).contains(mediaUuid)
        assertThat(pushedMedia.all { it.deletedAt == null }).isTrue()
    }

    @Test
    fun babyAvatarPointerWinsOverANewerUnreferencedAvatarRow() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val selectedUuid = "55555555-5555-5555-5555-555555555555"
        val newerUuid = "66666666-6666-6666-6666-666666666666"
        val babyId = rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-remote",
                avatarMediaUuid = newerUuid,
                avatarPath = "avatars/newer.jpg",
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = selectedUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "avatars/selected.jpg",
                remoteUri = rig.preferences.current().expectedMediaReceipt(selectedUuid),
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = newerUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "avatars/newer.jpg",
                remoteUri = rig.preferences.current().expectedMediaReceipt(newerUuid),
                createdAt = 200,
                updatedAt = 200,
                syncDirty = false,
            ),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteBaby().copy(
                    payloadJson = """
                        {
                          "nickname":"远端宝宝",
                          "sex":null,
                          "birthday":"2024-01-01",
                          "birth_weight_grams":null,
                          "avatar_media_uuid":"$selectedUuid"
                        }
                    """.trimIndent(),
                    updatedAt = 300,
                ),
            ),
            cursor = 1,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        val baby = rig.babies.getByClientUuid("baby-remote")
        assertThat(baby?.avatarMediaUuid).isEqualTo(selectedUuid)
        assertThat(baby?.avatarPath).isEqualTo("avatars/selected.jpg")
    }

    @Test
    fun memberNeverPushesLocalAvatarBytesAndSettlesRejectedMetadata() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(role = FamilyRole.Member),
        )
        val babyId = rig.babies.seed(
            localBaby().copy(
                avatarPath = "baby_avatars/member-local.jpg",
                familyAuthority = true,
            ),
        )
        val avatarUuid = "11111111-1111-1111-1111-111111111111"
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = avatarUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "baby_avatars/member-local.jpg",
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
            ),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        assertThat(rig.backend.pushes).isEmpty()
        assertThat(rig.backend.mediaUploads).isEmpty()
        assertThat(rig.media.getByClientUuid(avatarUuid)?.syncDirty).isFalse()
    }

    @Test
    fun memberRejoiningSameFamilyPullsCanonicalAvatarWithoutRepublishingBaby() = runTest {
        val session = joinedSession("family-a").copy(role = FamilyRole.Member)
        val rig = SyncRig(session = session)
        val avatarUuid = "11111111-1111-1111-1111-111111111111"
        val babyId = rig.babies.seed(
            localBaby().copy(
                avatarMediaUuid = avatarUuid,
                avatarPath = "baby_avatars/remote.jpg",
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = avatarUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "baby_avatars/remote.jpg",
                remoteUri = rig.preferences.current().expectedMediaReceipt(avatarUuid),
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.remember("media", avatarUuid)

        assertThat(rig.port.leave().isSuccess).isTrue()
        rig.preferences.saveSession(session.copy(accessToken = "replacement-token"))
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteBaby().copy(
                    clientUuid = "baby-local",
                    payloadJson = """
                        {
                          "nickname":"服务器宝宝",
                          "sex":null,
                          "birthday":"2024-01-01",
                          "birth_weight_grams":null,
                          "avatar_media_uuid":"$avatarUuid"
                        }
                    """.trimIndent(),
                    updatedAt = 200,
                ),
            ),
            cursor = 1,
            generation = "current-generation",
            hasMore = false,
        )

        val result = rig.port.sync(SyncTrigger.PullToRefresh)
        assertThat(result.exceptionOrNull()).isNull()

        assertThat(rig.backend.pushes).isEmpty()
        assertThat(rig.backend.mediaUploads).isEmpty()
        assertThat(rig.babies.getByClientUuid("baby-local")?.avatarMediaUuid).isEqualTo(avatarUuid)
        assertThat(rig.babies.getByClientUuid("baby-local")?.avatarPath)
            .isEqualTo("baby_avatars/remote.jpg")
        assertThat(rig.babies.getByClientUuid("baby-local")?.familyAuthority).isTrue()
        assertThat(rig.media.getByClientUuid(avatarUuid)?.remoteUri)
            .isEqualTo(rig.preferences.current().expectedMediaReceipt(avatarUuid))
    }

    @Test
    fun logMediaUsesRecordAsSingleBabyAssociationAfterProfileMerge() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val sourceBabyId = rig.babies.seed(
            localBaby().copy(clientUuid = "baby-source", nickname = "来源宝宝"),
        )
        val targetBabyId = rig.babies.seed(
            localBaby().copy(clientUuid = "baby-target", nickname = "目标宝宝"),
        )
        val recordId = rig.records.seed(
            localRecord(targetBabyId).copy(
                payloadJson = """{"amount_ml":120}""",
            ),
        )
        val mediaUuid = "22222222-2222-2222-2222-222222222222"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                babyId = null,
                localUri = "photos/merged.jpg",
                remoteUri = rig.preferences.current().expectedMediaReceipt(mediaUuid),
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        // Record packages publish through the only supported atomic bundle path.
        val mediaPayload = rig.backend.stagedBundles
            .flatMap { it.media }
            .single { it.clientUuid == mediaUuid }
            .payloadJson
        assertThat(mediaPayload).contains("\"record_client_uuid\":\"record-local\"")
        assertThat(mediaPayload).contains("\"baby_client_uuid\":null")
        assertThat(mediaPayload).doesNotContain("baby-source")
        assertThat(rig.media.getByClientUuid(mediaUuid)?.babyId).isNull()
        assertThat(rig.backend.committedBundles).isNotEmpty()
    }

    @Test
    fun recordCreateAlwaysUsesAtomicBundleIncludingZeroPhotos() = runTest {
        suspend fun runCase(photoCount: Int) {
            val rig = SyncRig(session = joinedSession("family-a"))
            // Warm the current-server health contract before staging the local bundle.
            assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
            val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
            rig.backend.remember("baby", "baby-local")
            val photos = (0 until photoCount).map { "photos/p$it.jpg" }
            val recordId = rig.records.seed(
                localRecord(babyId).copy(
                    clientUuid = "record-photos-$photoCount",
                    payloadJson = """{"amount_ml":120}""",
                    syncDirty = true,
                ),
            )
            photos.forEachIndexed { index, path ->
                rig.media.seed(
                    MediaAssetEntity(
                        recordId = recordId,
                        clientUuid = testMediaUuid("media-$photoCount-$index"),
                        kind = "log",
                        localUri = path,
                        mime = "image/jpeg",
                        byteSize = 8,
                        createdAt = 100,
                        updatedAt = 100,
                        syncDirty = true,
                    ),
                )
            }
            // Snapshot dirty entities into an ephemeral plan via a sync cycle.
            val result = rig.port.sync(SyncTrigger.LocalWrite)
            assertThat(result.exceptionOrNull()).isNull()
            val draft = rig.backend.stagedBundles.last()
            assertThat(draft.root.type).isEqualTo("record")
            assertThat(draft.root.clientUuid).isEqualTo("record-photos-$photoCount")
            assertThat(draft.media.filter { it.deletedAt == null }).hasSize(photoCount)
            assertThat(rig.backend.bundleMediaUploads).hasSize(photoCount)
            val committedBundleId = rig.backend.committedBundles.last()
            assertThat(committedBundleId).isEqualTo(draft.bundleId)
            assertThat(UUID.fromString(committedBundleId).toString()).isEqualTo(committedBundleId)
            assertThat(rig.records.getByClientUuid("record-photos-$photoCount")?.syncDirty)
                .isFalse()
        }
        runCase(0)
        runCase(1)
        runCase(3)
    }

    @Test
    fun atomicUploadFailureLeavesRecordLocalOnlyAndInvisibleOnPullCursor() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-fail-upload",
                payloadJson = """{"amount_ml":90}""",
                syncDirty = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = testMediaUuid("media-fail"),
                kind = "log",
                localUri = "photos/fail.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        rig.backend.putBundleMediaFailure = IllegalStateException("upload aborted")
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isFalse()
        assertThat(rig.records.getByClientUuid("record-fail-upload")?.syncDirty).isTrue()
        assertThat(rig.backend.committedBundles).isEmpty()
        // Local creator still sees the complete record + photo path.
        assertThat(rig.records.getByClientUuid("record-fail-upload")).isNotNull()
        assertThat(rig.media.listForRecord(recordId).single().localUri)
            .isEqualTo("photos/fail.jpg")
    }

    @Test
    fun midUploadMediaEditMissesReceiptThenNextCycleReplansCurrentRoomRevision() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val mediaUuid = testMediaUuid("media-recapture")
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-recapture",
                payloadJson = """{"amount_ml":80}""",
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "photos/old.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        var putCount = 0
        rig.backend.onPutBundleMedia = recapture@{ clientUuid ->
            if (clientUuid != mediaUuid) return@recapture
            putCount += 1
            if (putCount == 1) {
                val current = requireNotNull(rig.media.getByClientUuid(mediaUuid))
                // Domain edit mid-upload: higher revision and a new local file path.
                rig.media.update(
                    current.copy(
                        updatedAt = 200,
                        localUri = "photos/new.jpg",
                        remoteUri = null,
                        syncDirty = true,
                    ),
                )
            }
        }

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val retained = requireNotNull(rig.media.getByClientUuid(mediaUuid))
        assertThat(retained.updatedAt).isEqualTo(200)
        assertThat(retained.localUri).isEqualTo("photos/new.jpg")
        assertThat(retained.remoteUri).isNull()
        assertThat(retained.syncDirty).isTrue()
        assertThat(putCount).isEqualTo(1)

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val republished = requireNotNull(rig.media.getByClientUuid(mediaUuid))
        assertThat(republished.updatedAt).isEqualTo(200)
        assertThat(republished.localUri).isEqualTo("photos/new.jpg")
        assertThat(republished.syncDirty).isFalse()
        assertThat(putCount).isEqualTo(2)
    }

    @Test
    fun localRecordPublishLabelUsesRootReceiptAndTruthfulZeroPhotoCopy() {
        assertThat(
            localRecordPublishLabel(
                syncDirty = true,
                familyJoined = true,
                lastSyncFailed = false,
                publicationState = RootPublicationState.NEVER_PUBLISHED,
            ),
        ).isEqualTo("仅本机 · 等待家庭同步")
        assertThat(
            localRecordPublishLabel(
                syncDirty = true,
                familyJoined = true,
                lastSyncFailed = true,
                publicationState = RootPublicationState.NEVER_PUBLISHED,
            ),
        ).isEqualTo("仅本机 · 同步失败")
        assertThat(
            localRecordPublishLabel(
                syncDirty = true,
                familyJoined = true,
                lastSyncFailed = false,
                publicationState = RootPublicationState.PREVIOUS_VERSION_PUBLISHED,
            ),
        ).isEqualTo("仅本机 · 等待更新同步")
        assertThat(
            localRecordPublishLabel(
                syncDirty = true,
                familyJoined = true,
                lastSyncFailed = true,
                publicationState = RootPublicationState.PREVIOUS_VERSION_PUBLISHED,
            ),
        ).isEqualTo("仅本机 · 更新同步失败")
        assertThat(
            localRecordPublishDetail(
                lastSyncFailed = true,
                publicationState = RootPublicationState.PREVIOUS_VERSION_PUBLISHED,
            ),
        ).contains("上一完整版本")
        assertThat(
            localRecordPublishDetail(
                lastSyncFailed = false,
                publicationState = RootPublicationState.NEVER_PUBLISHED,
            ),
        ).isEqualTo("其他成员暂不可见，记录发布成功后才会出现。")
        assertThat(
            localRecordPublishLabel(
                syncDirty = false,
                familyJoined = true,
                lastSyncFailed = false,
                publicationState = RootPublicationState.NEVER_PUBLISHED,
            ),
        ).isNull()
        assertThat(
            localRecordPublishLabel(
                syncDirty = true,
                familyJoined = false,
                lastSyncFailed = false,
                publicationState = RootPublicationState.NEVER_PUBLISHED,
            ),
        ).isNull()
        assertThat(
            localRecordPublishLabel(
                syncDirty = true,
                familyJoined = true,
                lastSyncFailed = false,
                publicationState = RootPublicationState.CURRENT_VERSION_PUBLISHED,
            ),
        ).isNull()
    }

    @Test
    fun atomicRecordMutationAddRemoveReplaceAndTextOnlyUsesStableBundleId() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordUuid = "record-mutate"
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = recordUuid,
                updatedAt = 100,
                payloadJson = """{"amount_ml":100}""",
                syncDirty = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = testMediaUuid("media-a"),
                kind = "log",
                localUri = "photos/a.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val createBundle = rig.backend.stagedBundles.last {
            it.root.clientUuid == recordUuid && it.root.updatedAt == 100L
        }.bundleId
        assertThat(rig.backend.committedBundles).contains(createBundle)
        assertThat(UUID.fromString(createBundle).toString()).isEqualTo(createBundle)
        assertThat(rig.records.getByClientUuid(recordUuid)?.syncDirty).isFalse()

        // Text-only edit → new package id, no media uploads required.
        val textRow = rig.records.getByClientUuid(recordUuid)!!
        rig.records.update(
            textRow.copy(
                note = "只改文字",
                updatedAt = 200,
                syncDirty = true,
            ),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val textBundle = rig.backend.stagedBundles.last {
            it.root.clientUuid == recordUuid && it.root.updatedAt == 200L
        }.bundleId
        assertThat(rig.backend.committedBundles).contains(textBundle)
        assertThat(textBundle).isNotEqualTo(createBundle)

        // Replace photo: tombstone old, add new, same package.
        val afterText = rig.records.getByClientUuid(recordUuid)!!
        rig.records.update(
            afterText.copy(
                payloadJson = """{"amount_ml":100}""",
                updatedAt = 300,
                syncDirty = true,
            ),
        )
        // Ensure prior photo still exists as a tombstonable row (re-seed if drained).
        val existingOld = rig.media.listForRecord(recordId)
            .firstOrNull { it.clientUuid == testMediaUuid("media-a") }
        if (existingOld != null) {
            rig.media.update(
                existingOld.copy(deletedAt = 300, updatedAt = 300, syncDirty = true),
            )
        } else {
            rig.media.seed(
                MediaAssetEntity(
                    recordId = recordId,
                    clientUuid = testMediaUuid("media-a"),
                    kind = "log",
                    localUri = "photos/a.jpg",
                    mime = "image/jpeg",
                    byteSize = 4,
                    createdAt = 100,
                    updatedAt = 300,
                    deletedAt = 300,
                    syncDirty = true,
                ),
            )
        }
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = testMediaUuid("media-b"),
                kind = "log",
                localUri = "photos/b.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 300,
                updatedAt = 300,
                syncDirty = true,
            ),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val replaceDraft = rig.backend.stagedBundles.last {
            it.root.clientUuid == recordUuid && it.root.updatedAt == 300L
        }
        // Package includes live new photo + tombstone(s) for removed photo paths.
        assertThat(replaceDraft.media).isNotEmpty()
        assertThat(replaceDraft.media.any { it.deletedAt != null }).isTrue()
        assertThat(replaceDraft.media.any { it.deletedAt == null }).isTrue()
        assertThat(rig.backend.committedBundles).contains(replaceDraft.bundleId)
        assertThat(replaceDraft.bundleId).isNotEqualTo(textBundle)

        // Soft-delete whole record + media tombstones.
        val live = rig.records.getByClientUuid(recordUuid)!!
        rig.records.update(
            live.copy(
                deletedAt = 400,
                updatedAt = 400,
                payloadJson = """{"amount_ml":100}""",
                syncDirty = true,
            ),
        )
        rig.media.listActiveForRecord(recordId).forEach { media ->
            rig.media.update(
                media.copy(
                    updatedAt = 400,
                    deletedAt = 400,
                    syncDirty = true,
                ),
            )
        }
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val deleteDraft = rig.backend.stagedBundles.last { it.root.updatedAt == 400L }
        assertThat(rig.backend.committedBundles).contains(deleteDraft.bundleId)
        assertThat(deleteDraft.bundleId).isNotEqualTo(replaceDraft.bundleId)
        assertThat(deleteDraft.root.deletedAt).isEqualTo(400)
        assertThat(deleteDraft.media.all { it.deletedAt != null }).isTrue()
    }

    @Test
    fun atomicMutationIncompletePackageKeepsPriorVersionAndCursor() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                pullCursor = 50,
                pullGeneration = "g0",
            ),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        rig.preferences.saveSession(
            rig.preferences.current().copy(pullCursor = 50, pullGeneration = "g0"),
        )
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        // Prior complete version already on device.
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-prior",
                updatedAt = 100,
                payloadJson = """{"amount_ml":80}""",
                note = "旧完整",
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = testMediaUuid("media-old"),
                kind = "log",
                localUri = "old.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
                remoteUri = rig.preferences.current()
                    .expectedMediaReceipt(testMediaUuid("media-old")),
            ),
        )
        // Incomplete mutation package: record meta + missing media download.
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "record",
                    clientUuid = "record-prior",
                    payloadJson =
                        """{"baby_client_uuid":"baby-local","created_by_membership_id":"member-b","type":"formula","custom_item_client_uuid":null,"timestamp":1000,"end_timestamp":null,"note":"新版本","payload_json":{"amount_ml":90},"schema_version":2}""",
                    updatedAt = 200,
                ),
                SyncEntity(
                    type = "media",
                    clientUuid = testMediaUuid("media-new"),
                    payloadJson =
                        """{"kind":"log","record_client_uuid":"record-prior","care_plan_client_uuid":null,"baby_client_uuid":null,"mime":"image/jpeg","width":null,"height":null,"byte_size":4}""",
                    updatedAt = 200,
                ),
            ),
            cursor = 60,
            generation = "g1",
            hasMore = false,
        )
        rig.backend.getMediaFailure = IllegalStateException("download aborted")
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isFalse()
        // Prior complete version retained.
        val kept = rig.records.getByClientUuid("record-prior")!!
        assertThat(kept.note).isEqualTo("旧完整")
        assertThat(kept.updatedAt).isEqualTo(100)
        assertThat(rig.media.listActiveForRecord(recordId).map { it.clientUuid })
            .containsExactly(testMediaUuid("media-old"))
        assertThat(rig.preferences.current().pullCursor).isEqualTo(50)
    }

    @Test
    fun atomicDownloadFailureKeepsNewRecordInvisibleAndCursorUnmoved() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                pullCursor = 10,
                pullGeneration = "g0",
            ),
        )
        // Warm capability probe (empty pull) then restore the durable cursor/generation.
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        rig.preferences.saveSession(
            rig.preferences.current().copy(pullCursor = 10, pullGeneration = "g0"),
        )
        val mediaUuid = testMediaUuid("media-dl-fail")
        val recordUuid = "record-dl-fail"
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "record",
                    clientUuid = recordUuid,
                    payloadJson =
                        """{"baby_client_uuid":"baby-local","created_by_membership_id":"member-b","type":"formula","custom_item_client_uuid":null,"timestamp":1000,"end_timestamp":null,"note":null,"payload_json":{"amount_ml":80},"schema_version":2}""",
                    updatedAt = 200,
                ),
                SyncEntity(
                    type = "media",
                    clientUuid = mediaUuid,
                    payloadJson =
                        """{"kind":"log","record_client_uuid":"$recordUuid","care_plan_client_uuid":null,"baby_client_uuid":null,"mime":"image/jpeg","width":null,"height":null,"byte_size":4}""",
                    updatedAt = 200,
                ),
            ),
            cursor = 20,
            generation = "current-generation",
            hasMore = false,
        )
        // Baby must exist for record apply dependency chain when download succeeds;
        // failure happens before apply, so seed baby for a realistic package.
        rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.backend.getMediaFailure = IllegalStateException("download aborted")

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isFalse()
        assertThat(rig.records.getByClientUuid(recordUuid)).isNull()
        assertThat(rig.media.getByClientUuid(mediaUuid)).isNull()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(10)
    }

    @Test
    fun atomicApplyStageFailureDoesNotExposePartialRecord() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                pullCursor = 5,
                pullGeneration = "g0",
            ),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        rig.preferences.saveSession(
            rig.preferences.current().copy(pullCursor = 5, pullGeneration = "g0"),
        )
        val mediaUuid = testMediaUuid("media-apply-fail")
        val recordUuid = "record-apply-fail"
        // No baby on device → record apply fails after media bytes are staged.
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "record",
                    clientUuid = recordUuid,
                    payloadJson =
                        """{"baby_client_uuid":"missing-baby","created_by_membership_id":"member-b","type":"formula","custom_item_client_uuid":null,"timestamp":1000,"end_timestamp":null,"note":null,"payload_json":{"amount_ml":80},"schema_version":2}""",
                    updatedAt = 200,
                ),
                SyncEntity(
                    type = "media",
                    clientUuid = mediaUuid,
                    payloadJson =
                        """{"kind":"log","record_client_uuid":"$recordUuid","care_plan_client_uuid":null,"baby_client_uuid":null,"mime":"image/jpeg","width":null,"height":null,"byte_size":4}""",
                    updatedAt = 200,
                ),
            ),
            cursor = 15,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isFalse()
        assertThat(rig.records.getByClientUuid(recordUuid)).isNull()
        assertThat(rig.media.getByClientUuid(mediaUuid)).isNull()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(5)
    }

    @Test
    fun zeroPhotoRecordReceiptWritesOnlyAfterCommitAndSurvivesRetryAndRestart() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-root-receipt",
                updatedAt = 777,
                syncDirty = true,
            ),
        )
        rig.backend.commitBundleFailure = IllegalStateException("commit offline")

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isFailure).isTrue()
        assertThat(rig.records.getByClientUuid("record-root-receipt")?.familyPublishedUpdatedAt)
            .isNull()

        rig.backend.commitBundleFailure = null
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val published = requireNotNull(rig.records.getByClientUuid("record-root-receipt"))
        assertThat(published.familyPublishedUpdatedAt).isEqualTo(777)
        assertThat(published.syncDirty).isFalse()

        val reopened = MemoryRecordDao().apply { seed(published) }
        assertThat(reopened.getByClientUuid("record-root-receipt")?.familyPublishedUpdatedAt)
            .isEqualTo(777)
    }

    @Test
    fun uploadedPhotoCannotCreatePartialReceiptBeforeFailedRootCommit() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-photo-root-fail",
                updatedAt = 800,
                syncDirty = true,
            ),
        )
        val mediaUuid = testMediaUuid("media-photo-root-fail")
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "photos/root-fail.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        rig.backend.commitBundleFailure = IllegalStateException("commit timeout")

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isFailure).isTrue()

        assertThat(rig.media.getByClientUuid(mediaUuid)?.remoteUri).isNull()
        assertThat(rig.records.getByClientUuid("record-photo-root-fail")?.familyPublishedUpdatedAt)
            .isNull()

        rig.backend.commitBundleFailure = null
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        assertThat(rig.media.getByClientUuid(mediaUuid)?.remoteUri).isNotNull()
        assertThat(rig.records.getByClientUuid("record-photo-root-fail")?.familyPublishedUpdatedAt)
            .isEqualTo(800)
    }

    @Test
    fun cancelledOrTimedOutCommitCannotWriteRootReceipt() = runTest {
        listOf(
            CancellationException("commit cancelled"),
            java.net.SocketTimeoutException("commit timed out"),
        ).forEachIndexed { index, failure ->
            val rig = SyncRig(session = joinedSession("family-a"))
            assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
            val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
            val recordUuid = "record-commit-interrupted-$index"
            rig.records.seed(
                localRecord(babyId).copy(
                    clientUuid = recordUuid,
                    updatedAt = 850L + index,
                    syncDirty = true,
                ),
            )
            rig.backend.commitBundleFailure = failure

            val syncFailure = runCatching {
                rig.port.sync(SyncTrigger.LocalWrite).getOrThrow()
            }.exceptionOrNull()

            assertThat(syncFailure).isSameInstanceAs(failure)
            if (failure is CancellationException) {
                assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
            }
            assertThat(rig.records.getByClientUuid(recordUuid)?.familyPublishedUpdatedAt)
                .isNull()
            assertThat(rig.records.getByClientUuid(recordUuid)?.syncDirty).isTrue()
        }
    }

    @Test
    fun staleRecordReceiptPreservesNewerDirtyRevisionAndRejectsFutureReceipt() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-stale-root-receipt",
                updatedAt = 900,
                syncDirty = true,
            ),
        )
        rig.backend.afterCommit = {
            val current = requireNotNull(
                rig.records.getByClientUuid("record-stale-root-receipt"),
            )
            rig.records.update(current.copy(updatedAt = 901, syncDirty = true))
        }

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val edited = requireNotNull(rig.records.getByClientUuid("record-stale-root-receipt"))
        assertThat(edited.updatedAt).isEqualTo(901)
        assertThat(edited.familyPublishedUpdatedAt).isEqualTo(900)
        assertThat(edited.syncDirty).isTrue()

        assertThat(
            rig.records.acknowledgeFamilyPublishedVersion(
                clientUuid = "record-stale-root-receipt",
                publishedUpdatedAt = 902,
            ),
        ).isFalse()
        assertThat(
            rig.records.getByClientUuid("record-stale-root-receipt")?.familyPublishedUpdatedAt,
        ).isEqualTo(900)
    }

    @Test
    fun standaloneLogMediaRecordsExactElevatedRootReceiptAndAdvancesLocalRevision() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-standalone-log",
                updatedAt = 500,
                familyPublishedUpdatedAt = 500,
                syncDirty = false,
            ),
        )
        val mediaUuid = testMediaUuid("media-standalone-log")
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "photos/standalone-log.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 200,
                syncDirty = true,
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val published = requireNotNull(rig.records.getByClientUuid("record-standalone-log"))
        // max(nextPackageVersion(500), media 200) = 501
        assertThat(published.updatedAt).isEqualTo(501)
        assertThat(published.familyPublishedUpdatedAt).isEqualTo(501)
        assertThat(published.syncDirty).isFalse()
        assertThat(rig.media.getByClientUuid(mediaUuid)?.syncDirty).isFalse()
        assertThat(rig.media.getByClientUuid(mediaUuid)?.remoteUri).isNotNull()
        val draft = rig.backend.stagedBundles.single { it.root.clientUuid == "record-standalone-log" }
        assertThat(draft.root.updatedAt).isEqualTo(501)
        assertThat(draft.bundleId)
            .isEqualTo(AtomicBundleId.forRecord("record-standalone-log", 501))
    }

    @Test
    fun avatarOnlyBabyAdvancesLocalRevisionToPublishedRootUpdatedAt() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val avatarUuid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val babyId = rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-avatar-only",
                updatedAt = 300,
                avatarMediaUuid = avatarUuid,
                avatarPath = "avatars/only.jpg",
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                babyId = babyId,
                clientUuid = avatarUuid,
                kind = "avatar",
                localUri = "avatars/only.jpg",
                mime = "image/jpeg",
                byteSize = 2,
                createdAt = 100,
                updatedAt = 250,
                syncDirty = true,
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val baby = requireNotNull(rig.babies.getByClientUuid("baby-avatar-only"))
        // max(nextPackageVersion(300), avatar 250) = 301
        assertThat(baby.updatedAt).isEqualTo(301)
        assertThat(baby.syncDirty).isFalse()
        assertThat(rig.media.getByClientUuid(avatarUuid)?.syncDirty).isFalse()
        val draft = rig.backend.stagedBundles.single { it.root.clientUuid == "baby-avatar-only" }
        assertThat(draft.root.updatedAt).isEqualTo(301)
        assertThat(draft.bundleId)
            .isEqualTo(AtomicBundleId.forBaby("baby-avatar-only", 301))
    }

    @Test
    fun standaloneLogConcurrentRootEditKeepsContentDirtyAndMonotonicReceipt() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-standalone-concurrent",
                updatedAt = 400,
                familyPublishedUpdatedAt = 400,
                note = "original",
                syncDirty = false,
            ),
        )
        val mediaUuid = testMediaUuid("media-standalone-concurrent")
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "photos/concurrent.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        rig.backend.afterCommit = {
            // One-shot concurrent content edit during the synthetic package commit.
            rig.backend.afterCommit = null
            val current = requireNotNull(
                rig.records.getByClientUuid("record-standalone-concurrent"),
            )
            rig.records.update(
                current.copy(
                    updatedAt = 900,
                    note = "edited-during-upload",
                    syncDirty = true,
                ),
            )
        }

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val concurrent = requireNotNull(
            rig.records.getByClientUuid("record-standalone-concurrent"),
        )
        assertThat(concurrent.updatedAt).isEqualTo(900)
        assertThat(concurrent.note).isEqualTo("edited-during-upload")
        assertThat(concurrent.syncDirty).isTrue()
        // Synthetic package used rootUpdatedAt = 401; receipt advances without clearing dirty.
        assertThat(concurrent.familyPublishedUpdatedAt).isEqualTo(401)
        assertThat(rig.media.getByClientUuid(mediaUuid)?.syncDirty).isFalse()
        // The newer root is rebuilt into the next ephemeral plan (dirty retained).
        assertThat(rig.records.listPendingSync().map { it.clientUuid })
            .contains("record-standalone-concurrent")
    }

    @Test
    fun standaloneLogConcurrentEditToExactlyPublishedKeepsDirtyAndBody() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-standalone-pub-clock",
                updatedAt = 400,
                familyPublishedUpdatedAt = 400,
                note = "original",
                syncDirty = false,
            ),
        )
        val mediaUuid = testMediaUuid("media-standalone-pub-clock")
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "photos/pub-clock.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        // Package elevates to 401; concurrent edit lands on that same LWW clock.
        rig.backend.afterCommit = {
            rig.backend.afterCommit = null
            val current = requireNotNull(
                rig.records.getByClientUuid("record-standalone-pub-clock"),
            )
            rig.records.update(
                current.copy(
                    updatedAt = 401,
                    note = "edited-to-published-clock",
                    syncDirty = true,
                ),
            )
        }

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val concurrent = requireNotNull(
            rig.records.getByClientUuid("record-standalone-pub-clock"),
        )
        assertThat(concurrent.updatedAt).isEqualTo(401)
        assertThat(concurrent.note).isEqualTo("edited-to-published-clock")
        assertThat(concurrent.syncDirty).isTrue()
        assertThat(concurrent.familyPublishedUpdatedAt).isEqualTo(401)
        assertThat(rig.media.getByClientUuid(mediaUuid)?.syncDirty).isFalse()
        assertThat(rig.records.listPendingSync().map { it.clientUuid })
            .contains("record-standalone-pub-clock")
    }

    @Test
    fun standaloneLogCrashAfterRemoteCommitRetriesSameBundleIdAndConverges() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-standalone-crash",
                updatedAt = 600,
                familyPublishedUpdatedAt = 600,
                syncDirty = false,
            ),
        )
        val mediaUuid = testMediaUuid("media-standalone-crash")
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "photos/crash.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 120,
                syncDirty = true,
            ),
        )
        val expectedBundleId = AtomicBundleId.forRecord("record-standalone-crash", 601)
        rig.backend.afterCommit = {
            throw IllegalStateException("crash after remote commit before local ack")
        }

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isFailure).isTrue()
        assertThat(rig.backend.committedBundles).containsExactly(expectedBundleId)
        assertThat(rig.records.getByClientUuid("record-standalone-crash")?.updatedAt)
            .isEqualTo(600)
        assertThat(rig.records.getByClientUuid("record-standalone-crash")?.familyPublishedUpdatedAt)
            .isEqualTo(600)
        assertThat(rig.media.getByClientUuid(mediaUuid)?.syncDirty).isTrue()

        rig.backend.afterCommit = null
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        assertThat(rig.backend.committedBundles.count { it == expectedBundleId }).isEqualTo(2)
        val published = requireNotNull(rig.records.getByClientUuid("record-standalone-crash"))
        assertThat(published.updatedAt).isEqualTo(601)
        assertThat(published.familyPublishedUpdatedAt).isEqualTo(601)
        assertThat(published.syncDirty).isFalse()
        assertThat(rig.media.getByClientUuid(mediaUuid)?.syncDirty).isFalse()
        assertThat(rig.media.getByClientUuid(mediaUuid)?.remoteUri).isNotNull()
    }

    @Test
    fun avatarOnlyBabyConcurrentRootEditKeepsContentDirty() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val avatarUuid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val babyId = rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-avatar-concurrent",
                updatedAt = 300,
                nickname = "原昵称",
                avatarMediaUuid = avatarUuid,
                avatarPath = "avatars/concurrent.jpg",
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                babyId = babyId,
                clientUuid = avatarUuid,
                kind = "avatar",
                localUri = "avatars/concurrent.jpg",
                mime = "image/jpeg",
                byteSize = 2,
                createdAt = 100,
                updatedAt = 250,
                syncDirty = true,
            ),
        )
        rig.backend.afterCommit = {
            rig.backend.afterCommit = null
            val current = requireNotNull(
                rig.babies.getByClientUuid("baby-avatar-concurrent"),
            )
            rig.babies.update(
                current.copy(
                    updatedAt = 900,
                    nickname = "并发昵称",
                    syncDirty = true,
                ),
            )
        }

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val concurrent = requireNotNull(rig.babies.getByClientUuid("baby-avatar-concurrent"))
        assertThat(concurrent.updatedAt).isEqualTo(900)
        assertThat(concurrent.nickname).isEqualTo("并发昵称")
        assertThat(concurrent.syncDirty).isTrue()
        assertThat(rig.media.getByClientUuid(avatarUuid)?.syncDirty).isFalse()
        assertThat(rig.babies.listPendingSync().map { it.clientUuid })
            .contains("baby-avatar-concurrent")
    }

    @Test
    fun avatarOnlyBabyCrashAfterRemoteCommitRetriesSameBundleIdAndConverges() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val avatarUuid = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val babyId = rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-avatar-crash",
                updatedAt = 300,
                avatarMediaUuid = avatarUuid,
                avatarPath = "avatars/crash.jpg",
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                babyId = babyId,
                clientUuid = avatarUuid,
                kind = "avatar",
                localUri = "avatars/crash.jpg",
                mime = "image/jpeg",
                byteSize = 2,
                createdAt = 100,
                updatedAt = 250,
                syncDirty = true,
            ),
        )
        val expectedBundleId = AtomicBundleId.forBaby("baby-avatar-crash", 301)
        rig.backend.afterCommit = {
            throw IllegalStateException("crash after remote commit before local ack")
        }

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isFailure).isTrue()
        assertThat(rig.backend.committedBundles).containsExactly(expectedBundleId)
        assertThat(rig.babies.getByClientUuid("baby-avatar-crash")?.updatedAt).isEqualTo(300)
        assertThat(rig.media.getByClientUuid(avatarUuid)?.syncDirty).isTrue()

        rig.backend.afterCommit = null
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        assertThat(rig.backend.committedBundles.count { it == expectedBundleId }).isEqualTo(2)
        val baby = requireNotNull(rig.babies.getByClientUuid("baby-avatar-crash"))
        assertThat(baby.updatedAt).isEqualTo(301)
        assertThat(baby.syncDirty).isFalse()
        assertThat(rig.media.getByClientUuid(avatarUuid)?.syncDirty).isFalse()
        assertThat(rig.media.getByClientUuid(avatarUuid)?.remoteUri).isNotNull()
    }

    @Test
    fun syntheticRootReceiptIsMonotonicAcrossMultipleStandaloneMediaGroups() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-mono-receipt",
                updatedAt = 1_000,
                familyPublishedUpdatedAt = 1_000,
                syncDirty = false,
            ),
        )

        // Newer receipt first, then a stale older synthetic ack must not regress.
        assertThat(
            rig.records.acknowledgeSyntheticRootPublication(
                clientUuid = "record-mono-receipt",
                expectedLocalUpdatedAt = 1_000,
                publishedUpdatedAt = 1_002,
            ),
        ).isTrue()
        assertThat(rig.records.getByClientUuid("record-mono-receipt")?.familyPublishedUpdatedAt)
            .isEqualTo(1_002)
        assertThat(rig.records.getByClientUuid("record-mono-receipt")?.updatedAt)
            .isEqualTo(1_002)

        // Concurrent-path older receipt (expected epoch already left behind).
        assertThat(
            rig.records.acknowledgeSyntheticRootPublication(
                clientUuid = "record-mono-receipt",
                expectedLocalUpdatedAt = 1_000,
                publishedUpdatedAt = 1_001,
            ),
        ).isFalse()
        assertThat(rig.records.getByClientUuid("record-mono-receipt")?.familyPublishedUpdatedAt)
            .isEqualTo(1_002)
        assertThat(rig.records.getByClientUuid("record-mono-receipt")?.updatedAt)
            .isEqualTo(1_002)
    }

    @Test
    fun atomicRetryUsesSameBundleIdAndDoesNotDuplicateCommit() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-retry",
                updatedAt = 777,
                payloadJson = """{"amount_ml":50}""",
                syncDirty = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = testMediaUuid("media-retry"),
                kind = "log",
                localUri = "photos/r.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        rig.backend.putBundleMediaFailure = IllegalStateException("first upload fail")
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isFalse()
        assertThat(rig.backend.committedBundles).isEmpty()
        val retryBundleId = rig.backend.stagedBundles.single {
            it.root.clientUuid == "record-retry"
        }.bundleId
        assertThat(UUID.fromString(retryBundleId).toString()).isEqualTo(retryBundleId)

        rig.backend.putBundleMediaFailure = null
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        assertThat(rig.backend.committedBundles).containsExactly(retryBundleId)
        assertThat(rig.records.getByClientUuid("record-retry")?.syncDirty).isFalse()

        // Already clean — another foreground sync must not mint a second commit id.
        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()
        assertThat(rig.backend.committedBundles.count { it == retryBundleId })
            .isEqualTo(1)
    }

    @Test
    fun committedRecordBundleRetrySkipsMediaUploadAndStillAcknowledgesCommit() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(membershipId = "membership-a"),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val mediaUuid = "10000000-0000-4000-8000-000000000001"
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-committed-retry",
                createdByMembershipId = "",
                payloadJson = """{"amount_ml":50}""",
                syncDirty = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "photos/committed-record.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        rig.backend.stageBundleStatus = "committed"
        rig.backend.stageBundleMissingMedia = listOf(mediaUuid)
        rig.backend.putBundleMediaFailure = IllegalStateException("BundleMediaUploadClosed")
        rig.backend.nextCommitRecordAuthors = listOf(
            CanonicalRecordAuthor(
                clientUuid = "record-committed-retry",
                createdByMembershipId = "membership-a",
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        assertThat(rig.backend.bundleMediaUploads).isEmpty()
        assertThat(rig.backend.committedBundles)
            .containsExactly(rig.backend.stagedBundles.single().bundleId)
        val record = requireNotNull(
            rig.records.getByClientUuid("record-committed-retry"),
        )
        assertThat(record.createdByMembershipId).isEqualTo("membership-a")
        assertThat(record.syncDirty).isFalse()
        val media = rig.media.listForRecord(recordId).single()
        assertThat(media.syncDirty).isFalse()
        assertThat(media.remoteUri)
            .isEqualTo(rig.preferences.current().expectedMediaReceipt(mediaUuid))
    }

    @Test
    fun atomicRecordCommitMissingCanonicalAuthorAckRemainsRetryable() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(membershipId = "membership-a"),
        )
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.backend.remember("baby", "baby-local")
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "pre-join-photo-record",
                createdByMembershipId = "",
                updatedAt = 120,
                syncDirty = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = "10000000-0000-4000-8000-000000000099",
                kind = "log",
                localUri = "photos/pre-join.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        rig.backend.nextCommitRecordAuthors = emptyList()

        val result = rig.port.sync(SyncTrigger.LocalWrite)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageThat().contains("record_authors")
        val retained = requireNotNull(rig.records.getByClientUuid("pre-join-photo-record"))
        assertThat(retained.createdByMembershipId).isEmpty()
        assertThat(retained.syncDirty).isTrue()
        assertThat(rig.media.listForRecord(recordId).single().syncDirty).isTrue()
    }

    @Test
    fun stagingRecordBundleRetryUsesCurrentMissingAndStagedProgress() = runTest {
        suspend fun runCase(
            suffix: String,
            missingIndexes: List<Int>,
            stagedIndexes: List<Int>,
            expectedUploadIndexes: List<Int>,
        ) {
            val rig = SyncRig(session = joinedSession("family-a"))
            assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
            val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
            val paths = (0 until 3).map { "photos/$suffix-$it.jpg" }
            val mediaUuids = (0 until 3).map { index ->
                "20000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
            }
            val recordId = rig.records.seed(
                localRecord(babyId).copy(
                    clientUuid = "record-$suffix-retry",
                    payloadJson = """{"amount_ml":50}""",
                    syncDirty = true,
                ),
            )
            paths.forEachIndexed { index, path ->
                rig.media.seed(
                    MediaAssetEntity(
                        recordId = recordId,
                        clientUuid = mediaUuids[index],
                        kind = "log",
                        localUri = path,
                        mime = "image/jpeg",
                        byteSize = 4,
                        createdAt = 100,
                        updatedAt = 100L + index,
                        syncDirty = true,
                    ),
                )
            }
            rig.backend.stageBundleStatus = "staging"
            rig.backend.stageBundleMissingMedia = missingIndexes.map(mediaUuids::get)
            rig.backend.stageBundleStagedMedia = stagedIndexes.map(mediaUuids::get)

            assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

            assertThat(rig.backend.bundleMediaUploads.map { it.second })
                .containsExactlyElementsIn(expectedUploadIndexes.map(mediaUuids::get))
            assertThat(rig.backend.committedBundles).hasSize(1)
            assertThat(rig.records.getByClientUuid("record-$suffix-retry")?.syncDirty)
                .isFalse()
            val media = rig.media.listForRecord(recordId)
            assertThat(media.map { it.syncDirty })
                .containsExactly(false, false, false)
            val session = rig.preferences.current()
            assertThat(media.map { it.remoteUri })
                .containsExactlyElementsIn(mediaUuids.map(session::expectedMediaReceipt))
        }

        runCase(
            suffix = "missing",
            missingIndexes = listOf(1),
            stagedIndexes = listOf(0, 2),
            expectedUploadIndexes = listOf(1),
        )
    }

    @Test
    fun clientUuidAloneIsNotAcceptedAsMediaReceipt() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(localRecord(babyId).copy(syncDirty = false))
        val mediaUuid = "55555555-5555-5555-5555-555555555555"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "",
                remoteUri = mediaUuid,
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.getMediaFailure = SyncHttpException(401, "must not download")
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 42,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        assertThat(rig.preferences.current().pullCursor).isEqualTo(42)
        assertThat(rig.preferences.current().pullGeneration)
            .isEqualTo("current-generation")
        assertThat(rig.media.getByClientUuid(mediaUuid)?.localUri).isEmpty()
        assertThat(rig.media.listMissingLocalBytes().map(MediaAssetEntity::clientUuid))
            .containsExactly(mediaUuid)
    }

    @Test
    fun nonUuidLocalMediaFailsBeforeBundleNetworkIo() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(localRecord(babyId).copy(syncDirty = true))
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = "not-a-uuid",
                kind = "log",
                localUri = "photos/not-portable.jpg",
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )

        val result = rig.port.sync(SyncTrigger.LocalWrite)

        assertThat(result.isFailure).isTrue()
        assertThat(rig.backend.stagedBundles).isEmpty()
        assertThat(rig.media.getByClientUuid("not-a-uuid")).isNotNull()
    }

    @Test
    fun mediaGetAuthFailureFailsSyncWithoutAdvancingCursorOrMarkingSuccess() = runTest {
        listOf(401, 403).forEach { statusCode ->
            val rig = SyncRig(session = joinedSession("family-a"))
            val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
            val recordId = rig.records.seed(localRecord(babyId).copy(syncDirty = false))
            val mediaUuid = testMediaUuid("auth-media-$statusCode")
            rig.media.seed(
                MediaAssetEntity(
                    recordId = recordId,
                    clientUuid = mediaUuid,
                    kind = "log",
                    localUri = "",
                    remoteUri = rig.preferences.current().expectedMediaReceipt(mediaUuid),
                    mime = "image/jpeg",
                    byteSize = 12,
                    createdAt = 100,
                    updatedAt = 100,
                    syncDirty = false,
                ),
            )
            rig.backend.getMediaFailure = SyncHttpException(statusCode, "auth failed")
            rig.backend.nextPull = PullResult(
                emptyList(),
                cursor = 42,
                generation = "current-generation",
                hasMore = false,
            )

            val result = rig.port.sync(SyncTrigger.PullToRefresh)

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(SyncHttpException::class.java)
            assertThat((result.exceptionOrNull() as SyncHttpException).statusCode)
                .isEqualTo(statusCode)
            assertThat(rig.preferences.current().pullCursor).isEqualTo(0)
            assertThat(rig.preferences.current().pullGeneration)
                .isEqualTo("current-generation")
            assertThat(rig.preferences.current().lastSuccessAt).isNull()
        }
    }

    @Test
    fun invalidMediaBytesDoNotBlockPullCursorAdvance() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(localRecord(babyId).copy(syncDirty = false))
        val mediaUuid = "56565656-5656-5656-5656-565656565656"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "",
                remoteUri = rig.preferences.current().expectedMediaReceipt(mediaUuid),
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.mediaBytes = byteArrayOf()
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 43,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        assertThat(rig.preferences.current().pullCursor).isEqualTo(43)
        assertThat(rig.preferences.current().pullGeneration)
            .isEqualTo("current-generation")
        assertThat(rig.media.getByClientUuid(mediaUuid)?.localUri).isEmpty()
        assertThat(rig.media.listMissingLocalBytes().map(MediaAssetEntity::clientUuid))
            .containsExactly(mediaUuid)
    }

}
