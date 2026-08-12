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
import com.lezi.babylog.sync.backend.CausalCommitRejectedException
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
import com.lezi.babylog.sync.backend.RemoteDeviceRemovedException
import com.lezi.babylog.sync.backend.RemoteFamilyDeletedException
import com.lezi.babylog.sync.backend.RemoteMembershipDeletedException
import com.lezi.babylog.sync.backend.SessionBootstrapResult
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.appupdate.NoOpAppUpdateInstaller
import com.lezi.babylog.sync.clear.LocalClearCommittedException
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
import com.lezi.babylog.sync.backend.testPreparedMedia

// Split from RealSyncPortTest kitchen sink by contract cluster (ticket 05).
class RealSyncPortCustomItemTest {
    @Test
    fun customItemDirtySnapshotPushesAndPullPreservesLocalSortOrder() = runTest {
        val customUuid = "11111111-1111-3111-8111-111111111111"
        val rig = SyncRig(
            session = joinedSession("family-a").copy(membershipId = "m-owner"),
        )
        rig.customItems.seed(
            CustomItemEntity(
                clientUuid = customUuid,
                familyId = 1,
                name = "抚触",
                iconSlot = 2,
                sortOrder = 7,
                updatedAt = 50,
                createdByMembershipId = "m-owner",
                syncDirty = true,
            ),
        )

        val pushResult = rig.port.sync(SyncTrigger.LocalWrite)
        assertThat(pushResult.exceptionOrNull()).isNull()
        val pushed = rig.backend.causalCommittedUnits.flatten()
            .filter { it.entityType == "custom_item" }
        assertThat(pushed).hasSize(1)
        assertThat(pushed.single().rootJson).contains("\"name\":\"抚触\"")
        assertThat(pushed.single().rootJson).doesNotContain("sort_order")
        assertThat(pushed.single().rootJson).doesNotContain("sortOrder")
        assertThat(pushed.single().rootJson).doesNotContain("hidden")
        assertThat(pushed.single().rootJson).doesNotContain("quick")
        assertThat(rig.customItems.get(customUuid)!!.syncDirty).isFalse()
        assertThat(rig.customItems.get(customUuid)!!.sortOrder).isEqualTo(7)

        // Remote rename from peer should update name but keep local sortOrder.
        val pullRig = SyncRig(
            session = joinedSession("family-a").copy(membershipId = "m-owner"),
        )
        pullRig.backend.enableCausal = false
        pullRig.customItems.seed(rig.customItems.get(customUuid)!!.copy(baseVersion = null))
        pullRig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "custom_item",
                    clientUuid = customUuid,
                    payloadJson =
                        """{"name":"新抚触","icon_slot":3,"created_by_membership_id":"m-owner"}""",
                    updatedAt = 100,
                    deletedAt = null,
                ),
            ),
            cursor = 3,
            generation = "current-generation",
            hasMore = false,
        )
        val pullResult = pullRig.port.sync(SyncTrigger.PullToRefresh)
        assertThat(pullResult.exceptionOrNull()).isNull()
        val applied = pullRig.customItems.get(customUuid)!!
        assertThat(applied.name).isEqualTo("新抚触")
        assertThat(applied.iconSlot).isEqualTo(3)
        assertThat(applied.sortOrder).isEqualTo(7)
        assertThat(applied.syncDirty).isFalse()
    }

    @Test
    fun customItemTombstonePullAppliesWithoutResurrectingOnOlderLive() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(membershipId = "m-owner"),
        )
        rig.customItems.seed(
            CustomItemEntity(
                clientUuid = "custom-tomb",
                familyId = 1,
                name = "药",
                iconSlot = 1,
                sortOrder = 3,
                updatedAt = 10,
                createdByMembershipId = "m-peer",
                syncDirty = false,
            ),
        )
        // Peer admin tombstone arrives later.
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "custom_item",
                    clientUuid = "custom-tomb",
                    payloadJson =
                        """{"name":"药","icon_slot":1,"created_by_membership_id":"m-peer"}""",
                    updatedAt = 20,
                    deletedAt = 20,
                ),
            ),
            cursor = 4,
            generation = "current-generation",
            hasMore = false,
        )
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()
        val tombstoned = rig.customItems.get("custom-tomb")!!
        assertThat(tombstoned.deletedAt).isEqualTo(20)
        assertThat(tombstoned.sortOrder).isEqualTo(3)

        // Older live payload must not resurrect after tombstone.
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "custom_item",
                    clientUuid = "custom-tomb",
                    payloadJson =
                        """{"name":"复活","icon_slot":0,"created_by_membership_id":"m-peer"}""",
                    updatedAt = 15,
                    deletedAt = null,
                ),
            ),
            cursor = 5,
            generation = "current-generation",
            hasMore = false,
        )
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()
        val stillDead = rig.customItems.get("custom-tomb")!!
        assertThat(stillDead.deletedAt).isEqualTo(20)
        assertThat(stillDead.name).isEqualTo("药")
    }

    @Test
    fun tombstonedCustomDefinitionAllowsHistoricalRecordEditAndDeleteToPublish() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyUuid = "22222222-2222-3222-8222-222222222222"
        val customUuid = "33333333-3333-3333-8333-333333333333"
        val recordUuid = "44444444-4444-3444-8444-444444444444"
        val babyId = rig.babies.seed(localBaby().copy(clientUuid = babyUuid, syncDirty = false))
        val customItemId = rig.customItems.seed(
            CustomItemEntity(
                clientUuid = customUuid,
                familyId = 1,
                name = "抚触",
                iconSlot = 2,
                updatedAt = 150,
                deletedAt = 150,
                syncDirty = false,
            ),
        )
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = recordUuid,
                type = "custom",
                note = "编辑后",
                createdByMembershipId = "membership-a",
                payloadJson =
                    """{"title":"抚触","detail":"睡前十分钟","custom_item_id":$customItemId,"icon_slot":2}""",
                updatedAt = 200,
                syncDirty = true,
            ),
        )

        rig.backend.onCausalCommit = { throw SyncHttpException(503, "temporary") }
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isFailure).isTrue()
        assertThat(rig.records.getByClientUuid(recordUuid)?.syncDirty).isTrue()
        rig.backend.onCausalCommit = null

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).exceptionOrNull()).isNull()

        val editedMutation = rig.backend.causalCommittedUnits.flatten().last {
            it.clientUuid == recordUuid
        }
        assertThat(editedMutation.rootJson)
            .contains("\"custom_item_client_uuid\":\"$customUuid\"")
        assertThat(editedMutation.rootJson).contains("睡前十分钟")
        assertThat(rig.backend.causalCommittedUnits.flatten().map { it.entityType })
            .doesNotContain("custom_item")
        assertThat(rig.records.getByClientUuid(recordUuid)?.syncDirty).isFalse()

        rig.records.softDelete(recordId, deletedAt = 300)
        val deleteResult = rig.port.sync(SyncTrigger.LocalWrite)
        assertThat(deleteResult.exceptionOrNull()).isNull()

        val deletedMutation = rig.backend.causalCommittedUnits.flatten().last {
            it.clientUuid == recordUuid
        }
        assertThat(deletedMutation.deleted).isTrue()
        assertThat(rig.records.getByClientUuid(recordUuid)?.syncDirty).isFalse()
        assertThat(rig.customItems.get(customUuid)?.deletedAt).isEqualTo(150)
    }

    @Test
    fun terminalCustomHistoryRejectionKeepsLocalFactDirtyForVisibleRecovery() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyUuid = "55555555-5555-3555-8555-555555555555"
        val customUuid = "66666666-6666-3666-8666-666666666666"
        val recordUuid = "77777777-7777-3777-8777-777777777777"
        val babyId = rig.babies.seed(localBaby().copy(clientUuid = babyUuid, syncDirty = false))
        val customItemId = rig.customItems.seed(
            CustomItemEntity(
                clientUuid = customUuid,
                familyId = 1,
                name = "抚触",
                iconSlot = 2,
                updatedAt = 150,
                deletedAt = 150,
                syncDirty = false,
            ),
        )
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = recordUuid,
                type = "custom",
                createdByMembershipId = "membership-a",
                payloadJson =
                    """{"title":"抚触","custom_item_id":$customItemId,"icon_slot":2}""",
                updatedAt = 200,
                syncDirty = true,
            ),
        )
        rig.backend.nextCausalCommitFailure = CausalCommitRejectedException(
            mutationId = null,
            code = "invalid_reference",
        )

        val result = rig.port.sync(SyncTrigger.LocalWrite)

        assertThat(result.isFailure).isTrue()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Error)
        assertThat(rig.records.getByClientUuid(recordUuid)?.syncDirty).isTrue()
        assertThat(rig.customItems.get(customUuid)?.deletedAt).isEqualTo(150)
    }

}
