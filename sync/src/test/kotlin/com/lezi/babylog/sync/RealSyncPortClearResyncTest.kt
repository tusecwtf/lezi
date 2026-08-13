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
class RealSyncPortClearResyncTest {
    @Test
    fun resumedCommittedClearStillHonorsTheNewExplicitClearRequest() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.awaitStartupRecovery()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.records.seed(localRecord(babyId).copy(syncDirty = false))
        rig.pendingDomainRecovery.resumed = LocalDataClearScope.RecordsOnly
        var roomClearCalls = 0

        val result = rig.port.clearLocalData(
            LocalDataClearScope.RecordsOnly,
            realPortClearWorkflow {
                roomClearCalls += 1
                rig.records.deleteAll()
            },
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(roomClearCalls).isEqualTo(1)
        assertThat(rig.records.listAllIncludingDeleted()).isEmpty()
        assertThat(rig.pendingReplicaCleanup.pending).isNull()
    }

    @Test
    fun localRecordClearWaitsForPullThenDeletesTheAppliedRows() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.backend.nextPull = PullResult(
            entities = listOf(remoteBaby(), remoteRecord()),
            cursor = 2,
            generation = "current-generation",
            hasMore = false,
        )
        rig.backend.pullStarted = CompletableDeferred()
        rig.backend.releasePull = CompletableDeferred()

        val pulling = async { rig.port.sync(SyncTrigger.PullToRefresh) }
        rig.backend.pullStarted!!.await()
        val clearing = async {
            rig.port.clearLocalData(
                LocalDataClearScope.RecordsOnly,
                realPortClearWorkflow { rig.records.deleteAll() },
            )
        }
        runCurrent()
        assertThat(clearing.isCompleted).isFalse()

        rig.backend.releasePull!!.complete(Unit)

        assertThat(pulling.await().isSuccess).isTrue()
        assertThat(clearing.await().isSuccess).isTrue()
        assertThat(rig.records.listAllIncludingDeleted()).isEmpty()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(0)
    }

    @Test
    fun clearKeepsGenerationSoMemberRecoversAuthorityWithoutPublishingBaby() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                role = FamilyRole.Member,
                pullCursor = 7,
                pullGeneration = "old-generation",
            ),
        )
        val avatarUuid = "11111111-1111-1111-1111-111111111111"
        val babyId = rig.babies.seed(
            localBaby().copy(
                avatarMediaUuid = avatarUuid,
                avatarPath = "baby_avatars/stale.jpg",
                syncDirty = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = avatarUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "baby_avatars/stale.jpg",
                remoteUri = rig.preferences.current().expectedMediaReceipt(avatarUuid),
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )

        assertThat(
            rig.port.clearLocalData(
                LocalDataClearScope.RecordsOnly,
                realPortClearWorkflow(),
            ).isSuccess,
        ).isTrue()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(0)
        assertThat(rig.preferences.current().pullGeneration).isEqualTo("old-generation")

        rig.backend.pullFailures.add(
            SyncHttpException(
                statusCode = 409,
                responseBody = """
                    {
                      "detail":{
                        "code":"generation_changed",
                        "action":"full_resync",
                        "reset_cursor":0,
                        "server_cursor":1,
                        "server_generation":"new-generation"
                      }
                    }
                """.trimIndent(),
            ),
        )
        rig.backend.pullResults.add(
            PullResult(
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
                        updatedAt = 50,
                    ),
                ),
                cursor = 1,
                generation = "new-generation",
                hasMore = false,
            ),
        )
        rig.backend.pullResults.add(
            PullResult(emptyList(), cursor = 1, generation = "new-generation", hasMore = false),
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        assertThat(rig.backend.pullCursors).containsExactly(0L, 0L, 1L).inOrder()
        assertThat(rig.backend.causalCommittedUnits.flatten().map { it.entityType })
            .doesNotContain("baby")
        assertThat(rig.babies.getByClientUuid("baby-local")?.avatarMediaUuid).isNull()
        assertThat(rig.babies.getByClientUuid("baby-local")?.familyAuthority).isTrue()
        assertThat(rig.preferences.current().pullGeneration).isEqualTo("new-generation")
    }

    @Test
    fun generationChangeAtTheSameCursorStillForcesAFullResync() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                pullCursor = 1,
                pullGeneration = "old-generation",
            ),
        )
        rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.backend.pullFailures.add(
            SyncHttpException(
                statusCode = 409,
                responseBody = """
                    {
                      "detail":{
                        "code":"generation_changed",
                        "action":"full_resync",
                        "reset_cursor":0,
                        "server_cursor":1,
                        "server_generation":"new-generation"
                      }
                    }
                """.trimIndent(),
            ),
        )
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 1,
            generation = "new-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        assertThat(rig.backend.pullCursors).containsExactly(1L, 0L, 1L).inOrder()
        assertThat(rig.backend.causalCommittedUnits.flatten().map { it.clientUuid })
            .contains("baby-local")
    }

    @Test
    fun fullResyncAcknowledgesEqualPublishedMediaWithoutRepublishingItsBundle() = runTest {
        val session = joinedSession("family-a").copy(
            role = FamilyRole.Member,
            membershipId = "member-local",
            pullCursor = 3,
            pullGeneration = "old-generation",
        )
        val rig = SyncRig(session = session)
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                createdByMembershipId = "member-local",
                familyPublishedUpdatedAt = 120,
                syncDirty = false,
            ),
        )
        val mediaUuid = "12121212-1212-1212-1212-121212121212"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "record-media/existing.jpg",
                remoteUri = session.expectedMediaReceipt(mediaUuid),
                mime = "image/jpeg",
                byteSize = 3,
                createdAt = 120,
                updatedAt = 120,
                syncDirty = false,
            ),
        )
        rig.backend.pullFailures.add(
            SyncHttpException(
                statusCode = 409,
                responseBody = """
                    {
                      "detail":{
                        "code":"generation_changed",
                        "action":"full_resync",
                        "reset_cursor":0,
                        "server_cursor":3,
                        "server_generation":"new-generation"
                      }
                    }
                """.trimIndent(),
            ),
        )
        rig.backend.pullResults.add(
            PullResult(
                entities = listOf(
                    remoteBaby().copy(
                        clientUuid = "baby-local",
                        updatedAt = 100,
                    ),
                    remoteRecord().copy(
                        clientUuid = "record-local",
                        updatedAt = 120,
                        payloadJson = """
                            {
                              "baby_client_uuid":"baby-local",
                              "created_by_membership_id":"member-local",
                              "type":"formula",
                              "custom_item_client_uuid":null,
                              "timestamp":120,
                              "end_timestamp":null,
                              "note":null,
                              "payload_json":{"amount_ml":120},
                              "schema_version":2
                            }
                        """.trimIndent(),
                    ),
                    SyncEntity(
                        type = "media",
                        clientUuid = mediaUuid,
                        payloadJson = """
                            {
                              "kind":"log",
                              "record_client_uuid":"record-local",
                              "care_plan_client_uuid":null,
                              "baby_client_uuid":null,
                              "mime":"image/jpeg",
                              "width":null,
                              "height":null,
                              "byte_size":3
                            }
                        """.trimIndent(),
                        updatedAt = 120,
                    ),
                ),
                cursor = 3,
                generation = "new-generation",
                hasMore = false,
            ),
        )
        rig.backend.pullResults.add(
            PullResult(
                entities = emptyList(),
                cursor = 3,
                generation = "new-generation",
                hasMore = false,
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        assertThat(rig.backend.stagedBundles).isEmpty()
        val media = requireNotNull(rig.media.getByClientUuid(mediaUuid))
        assertThat(media.syncDirty).isFalse()
        assertThat(media.remoteUri)
            .isEqualTo(rig.preferences.current().expectedMediaReceipt(mediaUuid))
    }

    @Test
    fun nonzeroCursorWithoutGenerationFailsBeforePullOrPush() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                pullCursor = 7,
                pullGeneration = "",
            ),
        )
        rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 2,
            generation = "first-generation",
            hasMore = false,
        )

        val result = rig.port.sync(SyncTrigger.LocalWrite)

        assertThat(result.isFailure).isTrue()
        assertThat(rig.backend.pullCursors).isEmpty()
        assertThat(rig.backend.causalCommittedUnits).isEmpty()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(7)
        assertThat(rig.preferences.current().pullGeneration).isEmpty()
    }

    @Test
    fun blankGenerationPullToRefreshRecoversViaGenerationChanged() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                role = FamilyRole.Member,
                pullCursor = 0,
                pullGeneration = "",
            ),
        )
        rig.babies.seed(localBaby().copy(familyAuthority = false, syncDirty = true))
        rig.backend.enableCausal = true
        rig.backend.pullFailures.add(
            SyncHttpException(
                statusCode = 409,
                responseBody = """
                    {
                      "detail":{
                        "code":"generation_changed",
                        "action":"full_resync",
                        "reset_cursor":0,
                        "server_cursor":1,
                        "server_generation":"post-upgrade-generation"
                      }
                    }
                """.trimIndent(),
            ),
        )
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 2,
            generation = "post-upgrade-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()
        assertThat(rig.backend.handshakeCalls).isEqualTo(1)
        assertThat(rig.backend.pullCursors.first()).isEqualTo(0)
        assertThat(rig.preferences.current().pullGeneration)
            .isEqualTo("post-upgrade-generation")
        assertThat(rig.preferences.current().pullCursor).isEqualTo(2)
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
    }

    @Test
    fun memberFullResyncPullsOwnerAvatarAuthorityWithoutRequeueingLocalBaby() = runTest {
        val session = joinedSession("family-a").copy(
            role = FamilyRole.Member,
            pullCursor = 1,
            pullGeneration = "old-generation",
        )
        val rig = SyncRig(session = session)
        val avatarUuid = "11111111-1111-1111-1111-111111111111"
        val babyId = rig.babies.seed(
            localBaby().copy(
                avatarMediaUuid = avatarUuid,
                avatarPath = "baby_avatars/stale.jpg",
                syncDirty = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = avatarUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "baby_avatars/stale.jpg",
                remoteUri = rig.preferences.current().expectedMediaReceipt(avatarUuid),
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.pullFailures.add(
            SyncHttpException(
                statusCode = 409,
                responseBody = """
                    {
                      "detail":{
                        "code":"generation_changed",
                        "action":"full_resync",
                        "reset_cursor":0,
                        "server_cursor":1,
                        "server_generation":"new-generation"
                      }
                    }
                """.trimIndent(),
            ),
        )
        rig.backend.pullResults.add(
            PullResult(
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
                        updatedAt = 50,
                    ),
                ),
                cursor = 1,
                generation = "new-generation",
                hasMore = false,
            ),
        )
        rig.backend.pullResults.add(
            PullResult(emptyList(), cursor = 1, generation = "new-generation", hasMore = false),
        )

        val result = rig.port.sync(SyncTrigger.PullToRefresh)
        assertThat(result.exceptionOrNull()).isNull()

        assertThat(rig.backend.pullCursors).containsExactly(1L, 0L, 1L).inOrder()
        assertThat(rig.backend.causalCommittedUnits.flatten().map { it.entityType })
            .doesNotContain("baby")
        assertThat(rig.babies.getByClientUuid("baby-local")?.avatarMediaUuid).isNull()
        assertThat(rig.babies.getByClientUuid("baby-local")?.familyAuthority).isTrue()
        assertThat(rig.backend.causalMediaPreimageBytes).isEmpty()
        assertThat(rig.preferences.current().pullGeneration).isEqualTo("new-generation")
    }

    @Test
    fun failedPagedMemberFullResyncDoesNotClearBabiesFromUnseenPages() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                role = FamilyRole.Member,
                pullCursor = 7,
                pullGeneration = "old-generation",
            ),
        )
        rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-page-one",
                avatarMediaUuid = "avatar-page-one",
                avatarPath = "baby_avatars/page-one.jpg",
                syncDirty = false,
            ),
        )
        rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-page-two",
                avatarMediaUuid = "avatar-page-two",
                avatarPath = "baby_avatars/page-two.jpg",
                syncDirty = false,
            ),
        )
        rig.backend.pullFailures.add(
            SyncHttpException(
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
            ),
        )
        rig.backend.pullResults.add(
            PullResult(
                entities = listOf(
                    remoteBaby().copy(
                        clientUuid = "baby-page-one",
                        payloadJson = """
                            {
                              "nickname":"第一页宝宝",
                              "sex":null,
                              "birthday":"2024-01-01",
                              "birth_weight_grams":null,
                              "avatar_media_uuid":null
                            }
                        """.trimIndent(),
                    ),
                ),
                cursor = 1,
                generation = "new-generation",
                hasMore = true,
            ),
        )
        rig.backend.pullResults.add(
            PullResult(
                entities = emptyList(),
                cursor = 1,
                generation = "new-generation",
                hasMore = true,
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isFailure).isTrue()

        val unseen = requireNotNull(rig.babies.getByClientUuid("baby-page-two"))
        assertThat(unseen.avatarMediaUuid).isEqualTo("avatar-page-two")
        assertThat(unseen.avatarPath).isEqualTo("baby_avatars/page-two.jpg")
        assertThat(rig.preferences.current().pullCursor).isEqualTo(0)
    }

    @Test
    fun failedPagedOwnerFullResyncDoesNotPublishAPartialAuthoritativeCursor() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                pullCursor = 7,
                pullGeneration = "old-generation",
            ),
        )
        rig.backend.pullFailures.add(
            SyncHttpException(
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
            ),
        )
        rig.backend.pullResults.add(
            PullResult(
                entities = listOf(remoteBaby()),
                cursor = 1,
                generation = "new-generation",
                hasMore = true,
            ),
        )
        rig.backend.pullResults.add(
            PullResult(
                entities = emptyList(),
                cursor = 1,
                generation = "new-generation",
                hasMore = true,
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isFailure).isTrue()

        assertThat(rig.babies.getByClientUuid("baby-remote")).isNotNull()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(0)
        assertThat(rig.preferences.current().pullGeneration).isEqualTo("new-generation")
    }

    @Test
    fun pulledExplicitNullsClearNullableBabyFacts() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-remote",
                sex = "female",
                birthWeightGrams = 3_200,
                sortOrder = 7,
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
                          "avatar_media_uuid":null
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
        assertThat(baby?.sex).isNull()
        assertThat(baby?.birthWeightGrams).isNull()
        assertThat(baby?.sortOrder).isEqualTo(7)
    }

}
