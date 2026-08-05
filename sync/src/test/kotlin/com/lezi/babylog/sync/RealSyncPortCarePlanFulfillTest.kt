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
class RealSyncPortCarePlanFulfillTest {
    @Test
    fun freshFamilyPushesCarePlanReferencesBeforeStagingBundle() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.backend.enforceBundleReferences = true
        val babyId = rig.babies.seed(localBaby())
        val customItemId = rig.customItems.seed(
            CustomItemEntity(
                clientUuid = "custom-local",
                familyId = 1,
                name = "抚触",
                iconSlot = 2,
                updatedAt = 100,
            ),
        )
        rig.carePlans.seed(
            localCarePlan(babyId).copy(
                type = "custom",
                customItemId = customItemId,
                payloadJson =
                    """{"title":"抚触","custom_item_id":$customItemId}""",
            ),
        )

        val result = rig.port.sync(SyncTrigger.LocalWrite)

        assertThat(result.exceptionOrNull()).isNull()
        assertThat(rig.backend.operationOrder)
            .containsExactly("stage:baby", "stage:custom_item", "stage:care_plan")
            .inOrder()
    }

    @Test
    fun tombstonedCustomPlanFulfillmentDrainsZeroAndTwoPhotoAtomicSets() = runTest {
        suspend fun runCase(photoCount: Int) {
            val rig = SyncRig(session = joinedSession("family-a"))
            rig.backend.enforceBundleReferences = true
            val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
            rig.backend.remember("baby", "baby-local")
            val customItemId = rig.customItems.seed(
                CustomItemEntity(
                    clientUuid = "custom-plan-$photoCount",
                    familyId = 1,
                    name = "抚触",
                    iconSlot = 2,
                    updatedAt = 400,
                    deletedAt = 400,
                    syncDirty = false,
                ),
            )
            rig.backend.remember("custom_item", "custom-plan-$photoCount")
            val recordUuid = "custom-fact-$photoCount"
            val planUuid = "custom-plan-root-$photoCount"
            val candidateUuid = "custom-candidate-$photoCount"
            val payload =
                """{"title":"抚触","detail":"历史快照","custom_item_id":$customItemId,"icon_slot":2}"""
            val recordId = rig.records.seed(
                localRecord(babyId).copy(
                    clientUuid = recordUuid,
                    type = "custom",
                    payloadJson = payload,
                    updatedAt = 500,
                    syncDirty = true,
                ),
            )
            repeat(photoCount) { index ->
                rig.media.seed(
                    MediaAssetEntity(
                        recordId = recordId,
                        clientUuid = testMediaUuid("custom-history-$photoCount-$index"),
                        kind = "log",
                        localUri = "photos/custom-$photoCount-$index.jpg",
                        mime = "image/jpeg",
                        byteSize = 4,
                        createdAt = 500,
                        updatedAt = 500,
                        syncDirty = true,
                    ),
                )
            }
            rig.carePlans.seed(
                localCarePlan(babyId).copy(
                    clientUuid = planUuid,
                    type = "custom",
                    customItemId = customItemId,
                    payloadJson = payload,
                    status = "completed",
                    fulfilledRecordClientUuid = recordUuid,
                    fulfilledAt = 500,
                    updatedAt = 501,
                    syncDirty = true,
                ),
            )
            rig.fulfillmentCandidates.seed(
                FulfillmentCandidateEntity(
                    clientUuid = candidateUuid,
                    carePlanClientUuid = planUuid,
                    recordClientUuid = recordUuid,
                    confirmedAt = 500,
                    updatedAt = 502,
                    syncDirty = true,
                ),
            )

            assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

            val recordDraft = rig.backend.stagedBundles.first { it.root.clientUuid == recordUuid }
            val planDraft = rig.backend.stagedBundles.first { it.root.clientUuid == planUuid }
            val candidateDraft = rig.backend.stagedBundles.first {
                it.root.clientUuid == candidateUuid
            }
            assertThat(recordDraft.media.filter { it.deletedAt == null }).hasSize(photoCount)
            assertThat(recordDraft.root.payloadJson).contains("历史快照")
            assertThat(planDraft.root.payloadJson).contains("custom-plan-$photoCount")
            assertThat(rig.backend.committedBundles)
                .containsAtLeast(recordDraft.bundleId, planDraft.bundleId, candidateDraft.bundleId)
            assertThat(rig.backend.committedBundles.indexOf(planDraft.bundleId))
                .isLessThan(rig.backend.committedBundles.indexOf(recordDraft.bundleId))
            assertThat(rig.customItems.get("custom-plan-$photoCount")?.deletedAt).isEqualTo(400)
        }

        runCase(0)
        runCase(2)
    }

    @Test
    fun liveOrphanAvatarIsTombstonedWithoutAbortingLaterPublishCandidates() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val liveOrphanUuid = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        val deletedAt = 450L
        val babyId = rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-deleted-preseed-orphan",
                nickname = "已删预种",
                avatarMediaUuid = liveOrphanUuid,
                avatarPath = "baby_avatars/preseed-orphan.jpg",
                updatedAt = deletedAt,
                deletedAt = deletedAt,
                // Baby is already acknowledged; only invalid local media remains dirty.
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = liveOrphanUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "baby_avatars/preseed-orphan.jpg",
                mime = "image/jpeg",
                byteSize = 10,
                createdAt = 100,
                updatedAt = 200,
                deletedAt = null,
                syncDirty = true,
            ),
        )
        rig.customItems.seed(
            CustomItemEntity(
                clientUuid = "custom-after-orphan",
                familyId = 1,
                name = "后续定义",
                iconSlot = 1,
                updatedAt = 500,
                syncDirty = true,
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        // A deleted-Baby live avatar is technical residue: publish its
        // tombstone atomically, then clear it from pending.
        assertThat(rig.media.getByClientUuid(liveOrphanUuid)?.deletedAt).isEqualTo(200)
        assertThat(rig.media.getByClientUuid(liveOrphanUuid)?.syncDirty).isFalse()
        assertThat(rig.backend.stagedBundles.flatMap { it.media }.map(SyncEntity::clientUuid))
            .contains(liveOrphanUuid)
        // Later residual (custom_item) still pushes; poison row did not abort the batch.
        assertThat(rig.backend.stagedBundles.map { it.root.clientUuid })
            .contains("custom-after-orphan")
        assertThat(rig.customItems.getByClientUuid("custom-after-orphan")?.syncDirty).isFalse()
    }

    @Test
    fun zeroPhotoCarePlanEditKeepsPreviousReceiptUntilRetryCommitsCurrent() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = "plan-root-receipt",
                updatedAt = 1_000,
                syncDirty = true,
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val published = requireNotNull(rig.carePlans.getByClientUuid("plan-root-receipt"))
        assertThat(published.familyPublishedUpdatedAt).isEqualTo(1_000)

        rig.carePlans.update(published.copy(updatedAt = 1_001, syncDirty = true))
        rig.backend.commitBundleFailure = IllegalStateException("offline edit")
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isFailure).isTrue()
        val pendingEdit = requireNotNull(rig.carePlans.getByClientUuid("plan-root-receipt"))
        assertThat(pendingEdit.familyPublishedUpdatedAt).isEqualTo(1_000)
        assertThat(pendingEdit.syncDirty).isTrue()

        rig.backend.commitBundleFailure = null
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val current = requireNotNull(rig.carePlans.getByClientUuid("plan-root-receipt"))
        assertThat(current.familyPublishedUpdatedAt).isEqualTo(1_001)
        assertThat(current.syncDirty).isFalse()
    }

    @Test
    fun standaloneCarePlanPhotoRecordsExactElevatedRootReceipt() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val planId = rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = "plan-standalone-log",
                updatedAt = 800,
                familyPublishedUpdatedAt = 800,
                syncDirty = false,
            ),
        )
        val mediaUuid = testMediaUuid("media-plan-standalone")
        rig.media.seed(
            MediaAssetEntity(
                carePlanId = planId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "photos/plan-standalone.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 150,
                syncDirty = true,
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val published = requireNotNull(rig.carePlans.getByClientUuid("plan-standalone-log"))
        assertThat(published.updatedAt).isEqualTo(801)
        assertThat(published.familyPublishedUpdatedAt).isEqualTo(801)
        assertThat(published.syncDirty).isFalse()
        assertThat(rig.media.getByClientUuid(mediaUuid)?.syncDirty).isFalse()
    }

    @Test
    fun standaloneCarePlanConcurrentRootEditKeepsContentDirtyAndMonotonicReceipt() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val planId = rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = "plan-standalone-concurrent",
                updatedAt = 800,
                familyPublishedUpdatedAt = 800,
                payloadJson = """{"amount_ml":1}""",
                syncDirty = false,
            ),
        )
        val mediaUuid = testMediaUuid("media-plan-standalone-concurrent")
        rig.media.seed(
            MediaAssetEntity(
                carePlanId = planId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "photos/plan-concurrent.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 150,
                syncDirty = true,
            ),
        )
        rig.backend.afterCommit = {
            rig.backend.afterCommit = null
            val current = requireNotNull(
                rig.carePlans.getByClientUuid("plan-standalone-concurrent"),
            )
            rig.carePlans.update(
                current.copy(
                    updatedAt = 950,
                    payloadJson = """{"amount_ml":99}""",
                    syncDirty = true,
                ),
            )
        }

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val concurrent = requireNotNull(
            rig.carePlans.getByClientUuid("plan-standalone-concurrent"),
        )
        assertThat(concurrent.updatedAt).isEqualTo(950)
        assertThat(concurrent.payloadJson).isEqualTo("""{"amount_ml":99}""")
        assertThat(concurrent.syncDirty).isTrue()
        // max(nextPackageVersion(800), media 150) = 801
        assertThat(concurrent.familyPublishedUpdatedAt).isEqualTo(801)
        assertThat(rig.media.getByClientUuid(mediaUuid)?.syncDirty).isFalse()
        assertThat(rig.carePlans.listPendingSync().map { it.clientUuid })
            .contains("plan-standalone-concurrent")
    }

    @Test
    fun standaloneCarePlanCrashAfterRemoteCommitRetriesSameBundleIdAndConverges() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val planId = rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = "plan-standalone-crash",
                updatedAt = 700,
                familyPublishedUpdatedAt = 700,
                syncDirty = false,
            ),
        )
        val mediaUuid = testMediaUuid("media-plan-standalone-crash")
        rig.media.seed(
            MediaAssetEntity(
                carePlanId = planId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "photos/plan-crash.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 120,
                syncDirty = true,
            ),
        )
        val expectedBundleId = AtomicBundleId.forCarePlan("plan-standalone-crash", 701)
        rig.backend.afterCommit = {
            throw IllegalStateException("crash after remote commit before local ack")
        }

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isFailure).isTrue()
        assertThat(rig.backend.committedBundles).containsExactly(expectedBundleId)
        assertThat(rig.carePlans.getByClientUuid("plan-standalone-crash")?.updatedAt)
            .isEqualTo(700)
        assertThat(rig.carePlans.getByClientUuid("plan-standalone-crash")?.familyPublishedUpdatedAt)
            .isEqualTo(700)
        assertThat(rig.media.getByClientUuid(mediaUuid)?.syncDirty).isTrue()

        rig.backend.afterCommit = null
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        assertThat(rig.backend.committedBundles.count { it == expectedBundleId }).isEqualTo(2)
        val published = requireNotNull(rig.carePlans.getByClientUuid("plan-standalone-crash"))
        assertThat(published.updatedAt).isEqualTo(701)
        assertThat(published.familyPublishedUpdatedAt).isEqualTo(701)
        assertThat(published.syncDirty).isFalse()
        assertThat(rig.media.getByClientUuid(mediaUuid)?.syncDirty).isFalse()
        assertThat(rig.media.getByClientUuid(mediaUuid)?.remoteUri).isNotNull()
    }

    @Test
    fun atomicCarePlanCreateStagesZeroOneAndThreePhotosThenCommits() = runTest {
        suspend fun runCase(photoCount: Int) {
            val rig = SyncRig(session = joinedSession("family-a"))
            assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
            val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
            val photos = (0 until photoCount).map { "photos/plan$it.jpg" }
            val planId = rig.carePlans.seed(
                localCarePlan(babyId).copy(
                    clientUuid = "plan-photos-$photoCount",
                    payloadJson = """{"amount_ml":120}""",
                    syncDirty = true,
                ),
            )
            photos.forEachIndexed { index, path ->
                rig.media.seed(
                    MediaAssetEntity(
                        carePlanId = planId,
                        recordId = null,
                        clientUuid = testMediaUuid("plan-media-$photoCount-$index"),
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
            assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
            val draft = rig.backend.stagedBundles.last()
            assertThat(draft.root.type).isEqualTo("care_plan")
            assertThat(draft.root.clientUuid).isEqualTo("plan-photos-$photoCount")
            assertThat(draft.media.filter { it.deletedAt == null }).hasSize(photoCount)
            draft.media.forEach { media ->
                val payload = Json.parseToJsonElement(media.payloadJson).jsonObject
                assertThat(payload["care_plan_client_uuid"]?.jsonPrimitive?.contentOrNull)
                    .isEqualTo("plan-photos-$photoCount")
                assertThat(payload["record_client_uuid"]?.jsonPrimitive?.contentOrNull)
                    .isNull()
            }
            val committedBundleId = rig.backend.committedBundles.last()
            assertThat(committedBundleId).isEqualTo(draft.bundleId)
            assertThat(UUID.fromString(committedBundleId).toString()).isEqualTo(committedBundleId)
            assertThat(rig.carePlans.getByClientUuid("plan-photos-$photoCount")?.syncDirty)
                .isFalse()
        }
        runCase(0)
        runCase(1)
        runCase(3)
    }

    @Test
    fun recordAndCarePlanPublishApplyTheSamePreparedMediaMetadataContract() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.backend.remember("baby", "baby-local")
        val recordId = rig.records.seed(
            localRecord(babyId).copy(clientUuid = "record-shared-media-publisher"),
        )
        val planId = rig.carePlans.seed(
            localCarePlan(babyId).copy(clientUuid = "plan-shared-media-publisher"),
        )
        val recordMediaUuid = testMediaUuid("record-shared-media-publisher")
        val planMediaUuid = testMediaUuid("plan-shared-media-publisher")
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = recordMediaUuid,
                kind = "log",
                localUri = "photos/record-shared.jpg",
                mime = "image/png",
                byteSize = 99,
                createdAt = 100,
                updatedAt = 120,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                carePlanId = planId,
                clientUuid = planMediaUuid,
                kind = "log",
                localUri = "photos/plan-shared.jpg",
                mime = "image/png",
                byteSize = 99,
                createdAt = 100,
                updatedAt = 100,
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val drafts = rig.backend.stagedBundles.filter {
            it.root.clientUuid in setOf(
                "record-shared-media-publisher",
                "plan-shared-media-publisher",
            )
        }
        assertThat(drafts.map { it.root.type }).containsExactly("record", "care_plan")
        drafts.forEach { draft ->
            val payload = Json.parseToJsonElement(draft.media.single().payloadJson).jsonObject
            assertThat(payload["mime"]?.jsonPrimitive?.contentOrNull)
                .isEqualTo("image/jpeg")
            assertThat(payload["byte_size"]?.jsonPrimitive?.longOrNull).isEqualTo(1)
            assertThat(rig.backend.committedBundles).contains(draft.bundleId)
        }
        assertThat(rig.backend.bundleMediaUploads.map { it.second })
            .containsExactly(recordMediaUuid, planMediaUuid)
        val currentSession = rig.preferences.current()
        assertThat(rig.media.getByClientUuid(recordMediaUuid)?.remoteUri)
            .isEqualTo(currentSession.expectedMediaReceipt(recordMediaUuid))
        assertThat(rig.media.getByClientUuid(planMediaUuid)?.remoteUri)
            .isEqualTo(currentSession.expectedMediaReceipt(planMediaUuid))
        assertThat(rig.records.getByClientUuid("record-shared-media-publisher")?.syncDirty)
            .isFalse()
        assertThat(rig.carePlans.getByClientUuid("plan-shared-media-publisher")?.syncDirty)
            .isFalse()
    }

    @Test
    fun committedCarePlanBundleRetrySkipsMediaUploadAndStillAcknowledgesCommit() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val mediaUuid = "30000000-0000-4000-8000-000000000001"
        val planId = rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = "plan-committed-retry",
                payloadJson = """{"amount_ml":120}""",
                syncDirty = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                carePlanId = planId,
                recordId = null,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "photos/committed-plan.jpg",
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

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        assertThat(rig.backend.bundleMediaUploads).isEmpty()
        assertThat(rig.backend.committedBundles)
            .containsExactly(rig.backend.stagedBundles.single().bundleId)
        assertThat(rig.carePlans.getByClientUuid("plan-committed-retry")?.syncDirty)
            .isFalse()
        val media = rig.media.listForCarePlan(planId).single()
        assertThat(media.syncDirty).isFalse()
        assertThat(media.remoteUri)
            .isEqualTo(rig.preferences.current().expectedMediaReceipt(mediaUuid))
    }

    @Test
    fun atomicCarePlanUploadFailureLeavesPlanLocalOnly() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val planId = rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = "plan-fail-upload",
                payloadJson = """{"amount_ml":120}""",
                syncDirty = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                carePlanId = planId,
                recordId = null,
                clientUuid = testMediaUuid("plan-media-fail"),
                kind = "log",
                localUri = "photos/fail-plan.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        rig.backend.putBundleMediaFailure = IllegalStateException("upload aborted")
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isFalse()
        assertThat(rig.carePlans.getByClientUuid("plan-fail-upload")?.syncDirty).isTrue()
        assertThat(rig.backend.committedBundles).isEmpty()
        assertThat(rig.media.listForCarePlan(planId).single().localUri)
            .isEqualTo("photos/fail-plan.jpg")
    }

    @Test
    fun atomicCarePlanPullAppliesPlanAndPhotosThenInvokesProjectionHook() = runTest {
        val applied = mutableListOf<String>()
        val rig = SyncRig(
            session = joinedSession("family-a"),
            carePlanApplied = { uuids -> applied += uuids },
        )
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val babyUuid = rig.babies.getIncludingDeleted(babyId)!!.clientUuid
        // Warm policy.
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "care_plan",
                    clientUuid = "remote-plan-1",
                    payloadJson =
                        """{"baby_client_uuid":"$babyUuid","type":"formula","custom_item_client_uuid":null,"scheduled_at":9000000000000,"scheduled_zone_id":"Asia/Shanghai","note":null,"status":"pending","payload_json":{"amount_ml":120},"schema_version":2,"created_by_membership_id":"member-a","fulfilled_record_client_uuid":null,"fulfilled_at":null}""",
                    updatedAt = 500,
                    deletedAt = null,
                ),
                SyncEntity(
                    type = "media",
                    clientUuid = testMediaUuid("remote-plan-media-1"),
                    payloadJson =
                        """{"kind":"log","record_client_uuid":null,"care_plan_client_uuid":"remote-plan-1","baby_client_uuid":"$babyUuid","mime":"image/jpeg","width":null,"height":null,"byte_size":3}""",
                    updatedAt = 500,
                    deletedAt = null,
                ),
            ),
            cursor = 20,
            generation = "current-generation",
            hasMore = false,
        )
        rig.backend.mediaBytes = byteArrayOf(1, 2, 3)
        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()
        val plan = rig.carePlans.getByClientUuid("remote-plan-1")
        assertThat(plan).isNotNull()
        assertThat(plan!!.syncDirty).isFalse()
        assertThat(plan.status).isEqualTo("pending")
        val media = rig.media.listForCarePlan(plan.id).single()
        assertThat(media.localUri)
            .isEqualTo("downloaded/${testMediaUuid("remote-plan-media-1")}")
        assertThat(media.recordId).isNull()
        assertThat(media.babyId).isNull()
        assertThat(applied).containsExactly("remote-plan-1")
    }

    @Test
    fun remoteCarePlanProjectionRevisionInvalidatesCalendarReadiness() = runTest {
        val applied = mutableListOf<String>()
        val rig = SyncRig(
            session = joinedSession("family-a"),
            carePlanApplied = { uuids -> applied += uuids },
        )
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val babyUuid = rig.babies.getIncludingDeleted(babyId)!!.clientUuid
        rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = "remote-plan-revision",
                type = "formula",
                scheduledAt = 1_000,
                scheduledZoneId = "UTC",
                note = "旧备注",
                updatedAt = 100,
                syncDirty = false,
                systemCalendarEventId = "provider-event-1",
                systemCalendarReminderReady = true,
                systemCalendarProjectionPending = false,
            ),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "care_plan",
                    clientUuid = "remote-plan-revision",
                    payloadJson =
                        """{"baby_client_uuid":"$babyUuid","type":"sleep","custom_item_client_uuid":null,"scheduled_at":2000,"scheduled_zone_id":"Asia/Shanghai","note":"新备注","status":"pending","payload_json":{"is_nap":false,"anomaly_flag":false},"schema_version":2,"created_by_membership_id":"member-a","fulfilled_record_client_uuid":null,"fulfilled_at":null}""",
                    updatedAt = 200,
                    deletedAt = null,
                ),
            ),
            cursor = 20,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()

        val plan = rig.carePlans.getByClientUuid("remote-plan-revision")!!
        assertThat(plan.type).isEqualTo("sleep")
        assertThat(plan.scheduledAt).isEqualTo(2_000)
        assertThat(plan.scheduledZoneId).isEqualTo("Asia/Shanghai")
        assertThat(plan.note).isEqualTo("新备注")
        assertThat(plan.systemCalendarEventId).isEqualTo("provider-event-1")
        assertThat(plan.systemCalendarReminderReady).isFalse()
        assertThat(plan.systemCalendarProjectionPending).isTrue()
        assertThat(applied).containsExactly("remote-plan-revision")
    }

    @Test
    fun remoteCarePlanRevisionWithoutProjectionEvidenceDoesNotClaimCleanupPending() = runTest {
        val applied = mutableListOf<String>()
        val rig = SyncRig(
            session = joinedSession("family-a"),
            carePlanApplied = { uuids -> applied += uuids },
        )
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val babyUuid = rig.babies.getIncludingDeleted(babyId)!!.clientUuid
        rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = "remote-plan-never-projected",
                scheduledAt = 1_000,
                updatedAt = 100,
                syncDirty = false,
                systemCalendarEventId = null,
                systemCalendarReminderReady = false,
                systemCalendarProjectionPending = false,
            ),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "care_plan",
                    clientUuid = "remote-plan-never-projected",
                    payloadJson =
                        """{"baby_client_uuid":"$babyUuid","type":"formula","custom_item_client_uuid":null,"scheduled_at":2000,"scheduled_zone_id":"Asia/Shanghai","note":null,"status":"pending","payload_json":{"amount_ml":120},"schema_version":2,"created_by_membership_id":"member-a","fulfilled_record_client_uuid":null,"fulfilled_at":null}""",
                    updatedAt = 200,
                    deletedAt = null,
                ),
            ),
            cursor = 20,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()

        val plan = rig.carePlans.getByClientUuid("remote-plan-never-projected")!!
        assertThat(plan.scheduledAt).isEqualTo(2_000)
        assertThat(plan.systemCalendarReminderReady).isFalse()
        assertThat(plan.systemCalendarProjectionPending).isFalse()
        assertThat(applied).containsExactly("remote-plan-never-projected")
    }

    @Test
    fun remoteCarePlanTerminalRevisionMarksCalendarCleanupPending() = runTest {
        val applied = mutableListOf<String>()
        val rig = SyncRig(
            session = joinedSession("family-a"),
            carePlanApplied = { uuids -> applied += uuids },
        )
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val babyUuid = rig.babies.getIncludingDeleted(babyId)!!.clientUuid
        rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = "remote-plan-terminal",
                updatedAt = 100,
                syncDirty = false,
                systemCalendarEventId = "provider-event-terminal",
                systemCalendarReminderReady = true,
                systemCalendarProjectionPending = false,
            ),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "care_plan",
                    clientUuid = "remote-plan-terminal",
                    payloadJson =
                        """{"baby_client_uuid":"$babyUuid","type":"formula","custom_item_client_uuid":null,"scheduled_at":9000000000000,"scheduled_zone_id":"Asia/Shanghai","note":null,"status":"skipped","payload_json":{"amount_ml":120},"schema_version":2,"created_by_membership_id":"member-a","fulfilled_record_client_uuid":null,"fulfilled_at":null}""",
                    updatedAt = 200,
                    deletedAt = null,
                ),
            ),
            cursor = 20,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()

        val plan = rig.carePlans.getByClientUuid("remote-plan-terminal")!!
        assertThat(plan.status).isEqualTo("skipped")
        assertThat(plan.systemCalendarEventId).isEqualTo("provider-event-terminal")
        assertThat(plan.systemCalendarReminderReady).isFalse()
        assertThat(plan.systemCalendarProjectionPending).isTrue()
        assertThat(applied).containsExactly("remote-plan-terminal")
    }

    @Test
    fun atomicCarePlanDownloadFailureKeepsPlanInvisibleAndCursorUnmoved() = runTest {
        val applied = mutableListOf<String>()
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                pullCursor = 10,
                pullGeneration = "g0",
            ),
            carePlanApplied = { uuids -> applied += uuids },
        )
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val babyUuid = rig.babies.getIncludingDeleted(babyId)!!.clientUuid
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        rig.preferences.saveSession(
            rig.preferences.current().copy(pullCursor = 10, pullGeneration = "g0"),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "care_plan",
                    clientUuid = "remote-plan-dl-fail",
                    payloadJson =
                        """{"baby_client_uuid":"$babyUuid","type":"pee","custom_item_client_uuid":null,"scheduled_at":9000000000000,"scheduled_zone_id":"UTC","note":null,"status":"pending","payload_json":{"pee_amount":2},"schema_version":2,"created_by_membership_id":"member-a","fulfilled_record_client_uuid":null,"fulfilled_at":null}""",
                    updatedAt = 600,
                    deletedAt = null,
                ),
                SyncEntity(
                    type = "media",
                    clientUuid = testMediaUuid("remote-plan-media-fail"),
                    payloadJson =
                        """{"kind":"log","record_client_uuid":null,"care_plan_client_uuid":"remote-plan-dl-fail","baby_client_uuid":null,"mime":"image/jpeg","width":null,"height":null,"byte_size":4}""",
                    updatedAt = 600,
                    deletedAt = null,
                ),
            ),
            cursor = 20,
            generation = "current-generation",
            hasMore = false,
        )
        rig.backend.getMediaFailure = IllegalStateException("download aborted")
        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isFalse()
        assertThat(rig.carePlans.getByClientUuid("remote-plan-dl-fail")).isNull()
        assertThat(applied).isEmpty()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(10)
    }

    @Test
    fun atomicCarePlanAcceptsTombstonedHistoricalDefinitionButNotMissingDefinition() = runTest {
        val applied = mutableListOf<String>()
        val rig = SyncRig(
            session = joinedSession("family-a"),
            carePlanApplied = { uuids -> applied += uuids },
        )
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val babyUuid = rig.babies.getIncludingDeleted(babyId)!!.clientUuid
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        // Plan arrives without its custom item definition on the page → apply fails.
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "care_plan",
                    clientUuid = "remote-custom-plan",
                    payloadJson =
                        """{"baby_client_uuid":"$babyUuid","type":"custom","custom_item_client_uuid":"custom-def-1","scheduled_at":9000000000000,"scheduled_zone_id":"UTC","note":null,"status":"pending","payload_json":{"title":"抚触"},"schema_version":2,"created_by_membership_id":"member-a","fulfilled_record_client_uuid":null,"fulfilled_at":null}""",
                    updatedAt = 700,
                    deletedAt = null,
                ),
            ),
            cursor = 20,
            generation = "current-generation",
            hasMore = false,
        )
        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isFalse()
        assertThat(rig.carePlans.getByClientUuid("remote-custom-plan")).isNull()
        assertThat(applied).isEmpty()

        // Same page with a tombstoned historical definition first → plan becomes visible once,
        // while the definition remains absent from all live creation selectors.
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "custom_item",
                    clientUuid = "custom-def-1",
                    payloadJson =
                        """{"name":"抚触","icon_slot":2,"created_by_membership_id":"m-a"}""",
                    updatedAt = 690,
                    deletedAt = 690,
                ),
                SyncEntity(
                    type = "care_plan",
                    clientUuid = "remote-custom-plan",
                    payloadJson =
                        """{"baby_client_uuid":"$babyUuid","type":"custom","custom_item_client_uuid":"custom-def-1","scheduled_at":9000000000000,"scheduled_zone_id":"UTC","note":null,"status":"pending","payload_json":{"title":"抚触"},"schema_version":2,"created_by_membership_id":"member-a","fulfilled_record_client_uuid":null,"fulfilled_at":null}""",
                    updatedAt = 700,
                    deletedAt = null,
                ),
            ),
            cursor = 30,
            generation = "current-generation",
            hasMore = false,
        )
        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()
        val plan = rig.carePlans.getByClientUuid("remote-custom-plan")
        assertThat(plan).isNotNull()
        assertThat(plan!!.customItemId).isNotNull()
        assertThat(rig.customItems.get("custom-def-1")?.deletedAt).isEqualTo(690)
        assertThat(rig.customItems.listAll()).isEmpty()
        assertThat(applied).containsExactly("remote-custom-plan")
    }

    @Test
    fun atomicCarePlanTombstoneAndSkipPackagesCommitWithoutMediaBytes() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = "plan-skip",
                status = "skipped",
                updatedAt = 111,
                syncDirty = true,
            ),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val skipDraft = rig.backend.stagedBundles.last { it.root.clientUuid == "plan-skip" }
        assertThat(rig.backend.committedBundles).contains(skipDraft.bundleId)
        assertThat(UUID.fromString(skipDraft.bundleId).toString()).isEqualTo(skipDraft.bundleId)
        assertThat(skipDraft.root.deletedAt).isNull()
        assertThat(Json.parseToJsonElement(skipDraft.root.payloadJson).jsonObject["status"]
            ?.jsonPrimitive?.contentOrNull).isEqualTo("skipped")

        rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = "plan-tomb",
                updatedAt = 222,
                deletedAt = 222,
                syncDirty = true,
            ),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val tombDraft = rig.backend.stagedBundles.last { it.root.clientUuid == "plan-tomb" }
        assertThat(rig.backend.committedBundles).contains(tombDraft.bundleId)
        assertThat(tombDraft.bundleId).isNotEqualTo(skipDraft.bundleId)
        assertThat(tombDraft.root.deletedAt).isEqualTo(222)
    }

    @Test
    fun localCarePlanPublishLabelUsesRootReceiptAndTruthfulZeroPhotoCopy() {
        assertThat(
            localCarePlanPublishLabel(
                syncDirty = true,
                familyJoined = true,
                lastSyncFailed = false,
                publicationState = RootPublicationState.NEVER_PUBLISHED,
            ),
        ).isEqualTo("仅本机 · 等待家庭同步")
        assertThat(
            localCarePlanPublishDetail(
                lastSyncFailed = false,
                publicationState = RootPublicationState.NEVER_PUBLISHED,
            ),
        ).isEqualTo("其他成员暂不可见、不会提醒，护理计划发布成功后才会出现。")
        assertThat(
            localCarePlanPublishDetail(
                lastSyncFailed = false,
                publicationState = RootPublicationState.PREVIOUS_VERSION_PUBLISHED,
            ),
        ).contains("上一完整版本")
        assertThat(
            localCarePlanPublishLabel(
                syncDirty = false,
                familyJoined = true,
                lastSyncFailed = false,
                publicationState = RootPublicationState.NEVER_PUBLISHED,
            ),
        ).isNull()
    }

    @Test
    fun fulfillUnitPushesCompletedPlanThenRecordThenCandidateWithAndWithoutPhotos() = runTest {
        suspend fun runCase(photoCount: Int) {
            val rig = SyncRig(session = joinedSession("family-a"))
            assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
            val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
            rig.backend.remember("baby", "baby-local")
            val photos = (0 until photoCount).map { "photos/fulfill$it.jpg" }
            val recordUuid = "fulfill-record-$photoCount"
            val planUuid = "fulfill-plan-$photoCount"
            val candUuid = "fulfill-cand-$photoCount"
            val recordId = rig.records.seed(
                localRecord(babyId).copy(
                    clientUuid = recordUuid,
                    payloadJson = """{"amount_ml":90}""",
                    updatedAt = 500,
                    syncDirty = true,
                ),
            )
            photos.forEachIndexed { index, path ->
                rig.media.seed(
                    MediaAssetEntity(
                        recordId = recordId,
                        carePlanId = null,
                        clientUuid = testMediaUuid("fulfill-media-$photoCount-$index"),
                        kind = "log",
                        localUri = path,
                        mime = "image/jpeg",
                        byteSize = 4,
                        createdAt = 500,
                        updatedAt = 500,
                        syncDirty = true,
                    ),
                )
            }
            rig.carePlans.seed(
                localCarePlan(babyId).copy(
                    clientUuid = planUuid,
                    status = "completed",
                    fulfilledRecordClientUuid = recordUuid,
                    fulfilledAt = 500,
                    updatedAt = 501,
                    syncDirty = true,
                ),
            )
            rig.fulfillmentCandidates.seed(
                FulfillmentCandidateEntity(
                    clientUuid = candUuid,
                    carePlanClientUuid = planUuid,
                    recordClientUuid = recordUuid,
                    actualTimestamp = 120,
                    confirmedAt = 500,
                    updatedAt = 502,
                    syncDirty = true,
                ),
            )
            assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

            // The completed plan publishes before its fact so the NAS can prove that a
            // tombstoned custom definition is being used by an explicit fulfillment.
            val planDraft = rig.backend.stagedBundles.first {
                it.root.type == "care_plan" && it.root.clientUuid == planUuid
            }
            val planCommitIdx = rig.backend.committedBundles.indexOf(planDraft.bundleId)
            assertThat(planCommitIdx).isAtLeast(0)
            val recordDraft = rig.backend.stagedBundles.first {
                it.root.type == "record" && it.root.clientUuid == recordUuid
            }
            val recordCommitIdx = rig.backend.committedBundles.indexOf(recordDraft.bundleId)
            assertThat(recordCommitIdx).isAtLeast(0)
            assertThat(planCommitIdx).isLessThan(recordCommitIdx)
            assertThat(recordDraft.media.filter { it.deletedAt == null })
                .hasSize(photoCount)

            val candidatePush = rig.backend.stagedBundles
                .map(AtomicBundleDraft::root)
                .first { it.type == "fulfillment_candidate" && it.clientUuid == candUuid }
            val candPayload = Json.parseToJsonElement(candidatePush.payloadJson).jsonObject
            assertThat(candPayload["care_plan_client_uuid"]?.jsonPrimitive?.contentOrNull)
                .isEqualTo(planUuid)
            assertThat(candPayload["record_client_uuid"]?.jsonPrimitive?.contentOrNull)
                .isEqualTo(recordUuid)
            assertThat(candPayload["confirmed_at"]?.jsonPrimitive?.contentOrNull).isEqualTo("500")
            assertThat(rig.fulfillmentCandidates.getByClientUuid(candUuid)?.syncDirty).isFalse()
            assertThat(rig.records.getByClientUuid(recordUuid)?.syncDirty).isFalse()
            assertThat(rig.carePlans.getByClientUuid(planUuid)?.syncDirty).isFalse()
        }
        runCase(0)
        runCase(2)
    }

    @Test
    fun fulfillUnitRetryUsesStableCandidateAndLostCommitDoesNotDuplicate() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordUuid = "retry-fulfill-record"
        val planUuid = "retry-fulfill-plan"
        val candUuid = "retry-fulfill-cand"
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = recordUuid,
                updatedAt = 700,
                syncDirty = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = testMediaUuid("retry-fulfill-media"),
                kind = "log",
                localUri = "photos/retry-fulfill.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 700,
                updatedAt = 700,
                syncDirty = true,
            ),
        )
        rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = planUuid,
                status = "completed",
                fulfilledRecordClientUuid = recordUuid,
                fulfilledAt = 700,
                updatedAt = 701,
                syncDirty = true,
            ),
        )
        rig.fulfillmentCandidates.seed(
            FulfillmentCandidateEntity(
                clientUuid = candUuid,
                carePlanClientUuid = planUuid,
                recordClientUuid = recordUuid,
                actualTimestamp = 120,
                confirmedAt = 700,
                updatedAt = 702,
                syncDirty = true,
            ),
        )
        // Record and plan commit first; fail the candidate's atomic commit once.
        rig.backend.failCommitRootTypeOnce =
            "fulfillment_candidate" to IllegalStateException("candidate commit lost")
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isFalse()
        // Record/plan packages may have committed before the candidate package failed.
        val recordBundleId = rig.backend.stagedBundles.first {
            it.root.type == "record" && it.root.clientUuid == recordUuid
        }.bundleId
        assertThat(rig.backend.committedBundles).contains(recordBundleId)
        assertThat(rig.fulfillmentCandidates.getByClientUuid(candUuid)?.syncDirty).isTrue()

        // Re-dirty only candidate if records already marked synced; re-seed dirty candidate.
        val cand = rig.fulfillmentCandidates.getByClientUuid(candUuid)!!
        rig.fulfillmentCandidates.seed(cand.copy(syncDirty = true))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val candidateDrafts = rig.backend.stagedBundles
            .filter { it.root.type == "fulfillment_candidate" && it.root.clientUuid == candUuid }
        val candPushes = candidateDrafts
            .map(AtomicBundleDraft::root)
            .filter { it.type == "fulfillment_candidate" && it.clientUuid == candUuid }
        assertThat(candPushes).isNotEmpty()
        assertThat(candPushes.map { it.clientUuid }.distinct()).containsExactly(candUuid)
        assertThat(candidateDrafts.map(AtomicBundleDraft::bundleId).distinct()).hasSize(1)
        assertThat(rig.fulfillmentCandidates.getByClientUuid(candUuid)?.syncDirty).isFalse()
    }

    @Test
    fun fulfillReceiveFullSetAppliesAndProjectsCompletedPlanCancellation() = runTest {
        val applied = mutableListOf<String>()
        val rig = SyncRig(
            session = joinedSession("family-a"),
            carePlanApplied = { applied += it },
        )
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val babyUuid = rig.babies.getIncludingDeleted(babyId)!!.clientUuid
        // Pending plan already local (open) so completed package replaces it.
        rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = "remote-fulfill-plan",
                status = "pending",
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "record",
                    clientUuid = "remote-fulfill-record",
                    payloadJson =
                        """{"baby_client_uuid":"$babyUuid","created_by_membership_id":"member-b","type":"formula","custom_item_client_uuid":null,"timestamp":200,"end_timestamp":null,"note":null,"payload_json":{"amount_ml":80},"schema_version":2}""",
                    updatedAt = 800,
                    deletedAt = null,
                ),
                SyncEntity(
                    type = "care_plan",
                    clientUuid = "remote-fulfill-plan",
                    payloadJson =
                        """{"baby_client_uuid":"$babyUuid","type":"formula","custom_item_client_uuid":null,"scheduled_at":9000000000000,"scheduled_zone_id":"Asia/Shanghai","note":null,"status":"completed","payload_json":{"amount_ml":120},"schema_version":2,"created_by_membership_id":"member-a","fulfilled_record_client_uuid":"remote-fulfill-record","fulfilled_at":800}""",
                    updatedAt = 801,
                    deletedAt = null,
                ),
                SyncEntity(
                    type = "fulfillment_candidate",
                    clientUuid = "remote-fulfill-cand",
                    payloadJson =
                        """{"care_plan_client_uuid":"remote-fulfill-plan","record_client_uuid":"remote-fulfill-record","actual_timestamp":200,"submitter_membership_id":"member-b","submitter_role":"member","confirmed_at":800}""",
                    updatedAt = 802,
                    deletedAt = null,
                ),
            ),
            cursor = 99,
            generation = "current-generation",
            hasMore = false,
        )
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()
        assertThat(
            rig.records.getByClientUuid("remote-fulfill-record")?.familyPublishedUpdatedAt,
        ).isEqualTo(800)
        assertThat(rig.carePlans.getByClientUuid("remote-fulfill-plan")?.status)
            .isEqualTo("completed")
        assertThat(
            rig.carePlans.getByClientUuid("remote-fulfill-plan")?.familyPublishedUpdatedAt,
        ).isEqualTo(801)
        assertThat(rig.carePlans.getByClientUuid("remote-fulfill-plan")?.fulfilledRecordClientUuid)
            .isEqualTo("remote-fulfill-record")
        val cand = rig.fulfillmentCandidates.getByClientUuid("remote-fulfill-cand")!!
        assertThat(cand.submitterMembershipId).isEqualTo("member-b")
        assertThat(cand.submitterRole).isEqualTo("member")
        assertThat(cand.confirmedAt).isEqualTo(800)
        assertThat(cand.syncDirty).isFalse()
        assertThat(applied).contains("remote-fulfill-plan")
        assertThat(rig.preferences.current().pullCursor).isEqualTo(99)
    }

    @Test
    fun multiCandidateReceiveConvergesIndependentOfArrivalOrderAndPlanLww() = runTest {
        suspend fun runOrder(order: List<String>) {
            val rig = SyncRig(session = joinedSession("family-conflict"))
            val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
            val babyUuid = rig.babies.getIncludingDeleted(babyId)!!.clientUuid
            val planUuid = "conflict-plan"
            // Local member already fulfilled; plan LWW wrongly points at local record.
            rig.records.seed(
                localRecord(babyId).copy(
                    clientUuid = "rec-member",
                    updatedAt = 500,
                    syncDirty = false,
                ),
            )
            rig.carePlans.seed(
                localCarePlan(babyId).copy(
                    clientUuid = planUuid,
                    status = "completed",
                    fulfilledRecordClientUuid = "rec-member",
                    fulfilledAt = 500,
                    updatedAt = 9_000,
                    syncDirty = false,
                ),
            )
            rig.fulfillmentCandidates.seed(
                FulfillmentCandidateEntity(
                    clientUuid = "cand-member",
                    carePlanClientUuid = planUuid,
                    recordClientUuid = "rec-member",
                    confirmedAt = 500,
                    submitterMembershipId = "m-member",
                    submitterRole = "member",
                    adoptionStatus = com.lezi.babylog.core.model.FulfillmentAdoptionStatus.ADOPTED,
                    updatedAt = 500,
                    syncDirty = false,
                ),
            )
            assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

            val ownerRecord = SyncEntity(
                type = "record",
                clientUuid = "rec-owner",
                payloadJson =
                    """{"baby_client_uuid":"$babyUuid","created_by_membership_id":"m-owner","type":"formula","custom_item_client_uuid":null,"timestamp":200,"end_timestamp":null,"note":null,"payload_json":{"amount_ml":80},"schema_version":2}""",
                updatedAt = 800,
                deletedAt = null,
            )
            val ownerCandidate = SyncEntity(
                type = "fulfillment_candidate",
                clientUuid = "cand-owner",
                payloadJson =
                    """{"care_plan_client_uuid":"$planUuid","record_client_uuid":"rec-owner","actual_timestamp":200,"submitter_membership_id":"m-owner","submitter_role":"owner","confirmed_at":900}""",
                updatedAt = 900,
                deletedAt = null,
            )
            // Stale plan LWW with higher updatedAt still pointing at member record —
            // resolution must re-link after candidates are complete.
            val stalePlan = SyncEntity(
                type = "care_plan",
                clientUuid = planUuid,
                payloadJson =
                    """{"baby_client_uuid":"$babyUuid","type":"formula","custom_item_client_uuid":null,"scheduled_at":9000000000000,"scheduled_zone_id":"Asia/Shanghai","note":null,"status":"completed","payload_json":{"amount_ml":120},"schema_version":2,"created_by_membership_id":"member-a","fulfilled_record_client_uuid":"rec-member","fulfilled_at":500}""",
                updatedAt = 10_000,
                deletedAt = null,
            )
            val byKey = mapOf(
                "record" to ownerRecord,
                "candidate" to ownerCandidate,
                "plan" to stalePlan,
            )
            rig.backend.nextPull = PullResult(
                entities = order.map { byKey.getValue(it) },
                cursor = 120,
                generation = "current-generation",
                hasMore = false,
            )
            assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()
            assertThat(rig.carePlans.getByClientUuid(planUuid)?.fulfilledRecordClientUuid)
                .isEqualTo("rec-owner")
            assertThat(rig.fulfillmentCandidates.getByClientUuid("cand-owner")?.adoptionStatus)
                .isEqualTo(com.lezi.babylog.core.model.FulfillmentAdoptionStatus.ADOPTED)
            assertThat(rig.fulfillmentCandidates.getByClientUuid("cand-member")?.adoptionStatus)
                .isEqualTo(
                    com.lezi.babylog.core.model.FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED,
                )
            // Loser record retained (not soft-deleted).
            assertThat(rig.records.getByClientUuid("rec-member")?.deletedAt).isNull()
            assertThat(rig.records.getByClientUuid("rec-owner")).isNotNull()
        }
        runOrder(listOf("record", "plan", "candidate"))
        runOrder(listOf("record", "candidate", "plan"))
        runOrder(listOf("plan", "record", "candidate"))
    }

    @Test
    fun multiCandidateUuidTieBreakAndIdempotentReplay() = runTest {
        val rig = SyncRig(session = joinedSession("family-uuid"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val babyUuid = rig.babies.getIncludingDeleted(babyId)!!.clientUuid
        val planUuid = "uuid-plan"
        rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = planUuid,
                status = "pending",
                updatedAt = 10,
                syncDirty = false,
            ),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val entities = listOf(
            SyncEntity(
                type = "record",
                clientUuid = "rec-z",
                payloadJson =
                    """{"baby_client_uuid":"$babyUuid","created_by_membership_id":"m-z","type":"formula","custom_item_client_uuid":null,"timestamp":1,"end_timestamp":null,"note":null,"payload_json":{"amount_ml":80},"schema_version":2}""",
                updatedAt = 20,
            ),
            SyncEntity(
                type = "record",
                clientUuid = "rec-a",
                payloadJson =
                    """{"baby_client_uuid":"$babyUuid","created_by_membership_id":"m-a","type":"formula","custom_item_client_uuid":null,"timestamp":2,"end_timestamp":null,"note":null,"payload_json":{"amount_ml":80},"schema_version":2}""",
                updatedAt = 21,
            ),
            SyncEntity(
                type = "care_plan",
                clientUuid = planUuid,
                payloadJson =
                    """{"baby_client_uuid":"$babyUuid","type":"formula","custom_item_client_uuid":null,"scheduled_at":9000000000000,"scheduled_zone_id":"Asia/Shanghai","note":null,"status":"completed","payload_json":{"amount_ml":120},"schema_version":2,"created_by_membership_id":"m","fulfilled_record_client_uuid":"rec-z","fulfilled_at":50}""",
                updatedAt = 30,
            ),
            SyncEntity(
                type = "fulfillment_candidate",
                clientUuid = "uuid-zzz",
                payloadJson =
                    """{"care_plan_client_uuid":"$planUuid","record_client_uuid":"rec-z","actual_timestamp":1,"submitter_membership_id":"m-z","submitter_role":"member","confirmed_at":50}""",
                updatedAt = 40,
            ),
            SyncEntity(
                type = "fulfillment_candidate",
                clientUuid = "uuid-aaa",
                payloadJson =
                    """{"care_plan_client_uuid":"$planUuid","record_client_uuid":"rec-a","actual_timestamp":2,"submitter_membership_id":"m-a","submitter_role":"member","confirmed_at":50}""",
                updatedAt = 41,
            ),
        )
        rig.backend.nextPull = PullResult(
            entities = entities,
            cursor = 200,
            generation = "current-generation",
            hasMore = false,
        )
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()
        assertThat(rig.carePlans.getByClientUuid(planUuid)?.fulfilledRecordClientUuid)
            .isEqualTo("rec-a")
        // Idempotent full-page replay with same entities (cursor advance already done).
        rig.backend.nextPull = PullResult(
            entities = entities,
            cursor = 200,
            generation = "current-generation",
            hasMore = false,
        )
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()
        assertThat(rig.carePlans.getByClientUuid(planUuid)?.fulfilledRecordClientUuid)
            .isEqualTo("rec-a")
        assertThat(rig.fulfillmentCandidates.getByClientUuid("uuid-aaa")?.adoptionStatus)
            .isEqualTo(com.lezi.babylog.core.model.FulfillmentAdoptionStatus.ADOPTED)
        assertThat(rig.fulfillmentCandidates.getByClientUuid("uuid-zzz")?.adoptionStatus)
            .isEqualTo(com.lezi.babylog.core.model.FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED)
    }

    @Test
    fun fulfillReceiveCompletedPlanWithoutRecordKeepsInvisibleAndCursorUnmoved() = runTest {
        val applied = mutableListOf<String>()
        val rig = SyncRig(
            session = joinedSession("family-a"),
            carePlanApplied = { applied += it },
        )
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val babyUuid = rig.babies.getIncludingDeleted(babyId)!!.clientUuid
        // Existing open plan revision stays visible until full set arrives.
        rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = "gated-plan",
                status = "pending",
                updatedAt = 50,
                syncDirty = false,
            ),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val cursorBefore = rig.preferences.current().pullCursor
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "care_plan",
                    clientUuid = "gated-plan",
                    payloadJson =
                        """{"baby_client_uuid":"$babyUuid","type":"formula","custom_item_client_uuid":null,"scheduled_at":9000000000000,"scheduled_zone_id":"Asia/Shanghai","note":null,"status":"completed","payload_json":{"amount_ml":120},"schema_version":2,"created_by_membership_id":"member-a","fulfilled_record_client_uuid":"missing-record","fulfilled_at":900}""",
                    updatedAt = 900,
                    deletedAt = null,
                ),
                SyncEntity(
                    type = "fulfillment_candidate",
                    clientUuid = "gated-cand",
                    payloadJson =
                        """{"care_plan_client_uuid":"gated-plan","record_client_uuid":"missing-record","actual_timestamp":null,"submitter_membership_id":"member-a","submitter_role":"member","confirmed_at":900}""",
                    updatedAt = 901,
                    deletedAt = null,
                ),
            ),
            cursor = 55,
            generation = "current-generation",
            hasMore = false,
        )
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isFalse()
        assertThat(rig.carePlans.getByClientUuid("gated-plan")?.status).isEqualTo("pending")
        assertThat(rig.carePlans.getByClientUuid("gated-plan")?.updatedAt).isEqualTo(50)
        assertThat(rig.fulfillmentCandidates.getByClientUuid("gated-cand")).isNull()
        assertThat(applied).isEmpty()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(cursorBefore)
    }

}
