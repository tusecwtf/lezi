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
import kotlinx.coroutines.selects.select
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
import com.lezi.babylog.sync.backend.FakeSyncBackend
import com.lezi.babylog.sync.backend.testPreparedMedia

// Split from RealSyncPortTest kitchen sink by contract cluster (ticket 05).
class RealSyncPortFamilyWireTest {
    @Test
    fun renamePreservesCredentialsRotatedWhileTheServerRequestWasInFlight() = runTest {
        val original = joinedSession("family-a").copy(role = FamilyRole.Owner)
        val rig = SyncRig(original)
        rig.awaitInitialReplicaBarrier()
        rig.backend.onRenameFamily = {
            rig.preferences.saveRefreshedSession(original.copy(
                accessToken = "new-access", refreshToken = "new-refresh",
                accessExpiresAtEpochSeconds = 9_999_999_999,
            ))
        }
        assertThat(rig.port.renameFamily("New family").isSuccess).isTrue()
        assertThat(rig.preferences.current().familyName).isEqualTo("New family")
        assertThat(rig.preferences.current().accessToken).isEqualTo("new-access")
        assertThat(rig.preferences.current().refreshToken).isEqualTo("new-refresh")
    }

    @Test
    fun retainedFamilyEndpointChangeDoesNotRequeueLocalFacts() = runTest {
        val rig = SyncRig(session = joinedSession("family-old"))
        rig.awaitInitialReplicaBarrier()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val customItemId = rig.customItems.seed(
            CustomItemEntity(
                clientUuid = "custom-local",
                familyId = 1,
                name = "抚触",
                iconSlot = 2,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        val recordUuid = "record-local"
        val planUuid = "plan-local"
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = recordUuid,
                familyPublishedUpdatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = planUuid,
                type = "custom",
                customItemId = customItemId,
                payloadJson =
                    """{"title":"抚触","custom_item_id":$customItemId}""",
                fulfilledRecordClientUuid = recordUuid,
                fulfilledAt = 120,
                status = "completed",
                familyPublishedUpdatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.fulfillmentCandidates.seed(
            FulfillmentCandidateEntity(
                clientUuid = "candidate-local",
                carePlanClientUuid = planUuid,
                recordClientUuid = recordUuid,
                confirmedAt = 120,
                updatedAt = 121,
                syncDirty = false,
            ),
        )

        val failure = rig.port.saveEndpointConfig(
            FamilyEndpointConfig(host = "192.168.1.99", port = 8787),
        ).exceptionOrNull()

        assertThat(failure).isInstanceOf(DifferentFamilyServerException::class.java)
        assertThat(rig.preferences.current().familyId).isEqualTo("family-old")
        assertThat(rig.preferences.current().serverHost).isNotEqualTo("192.168.1.99")
        assertThat(rig.records.getByClientUuid(recordUuid)?.familyPublishedUpdatedAt).isEqualTo(100)
        assertThat(rig.records.getByClientUuid(recordUuid)?.syncDirty).isFalse()
        assertThat(rig.carePlans.getByClientUuid(planUuid)?.syncDirty).isFalse()
        assertThat(rig.babies.listPendingSync()).isEmpty()
        assertThat(rig.records.listPendingSync()).isEmpty()
    }

    @Test
    fun switchingFamilyReplacesFamilyScopedOwnershipStamps() = runTest {
        val babyUuid = "00000000-0000-0000-0000-000000000101"
        val customItemUuid = "00000000-0000-0000-0000-000000000102"
        val recordUuid = "00000000-0000-0000-0000-000000000103"
        val planUuid = "00000000-0000-0000-0000-000000000104"
        val candidateUuid = "00000000-0000-0000-0000-000000000105"
        val rig = SyncRig(
            session = joinedSession("family-old").copy(membershipId = "membership-old"),
        )
        val babyId = rig.babies.seed(
            localBaby().copy(clientUuid = babyUuid, syncDirty = false),
        )
        val customItemId = rig.customItems.seed(
            CustomItemEntity(
                clientUuid = customItemUuid,
                familyId = 1,
                name = "抚触",
                iconSlot = 2,
                updatedAt = 100,
                createdByMembershipId = "membership-old",
                syncDirty = false,
            ),
        )
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = recordUuid,
                createdByMembershipId = "membership-old",
                syncDirty = false,
            ),
        )
        rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = planUuid,
                type = "custom",
                customItemId = customItemId,
                payloadJson =
                    """{"title":"抚触","custom_item_id":$customItemId}""",
                status = "completed",
                fulfilledRecordClientUuid = recordUuid,
                fulfilledAt = 121,
                createdByMembershipId = "membership-old",
                updatedAt = 121,
                syncDirty = false,
            ),
        )
        rig.fulfillmentCandidates.seed(
            FulfillmentCandidateEntity(
                clientUuid = candidateUuid,
                carePlanClientUuid = planUuid,
                recordClientUuid = recordUuid,
                confirmedAt = 121,
                submitterMembershipId = "membership-old",
                submitterRole = "member",
                updatedAt = 121,
                syncDirty = false,
            ),
        )

        rig.preferences.saveSession(
            joinedSession("family-new").copy(membershipId = "membership-new"),
        )
        rig.backend.pullFailures += generationChangedForFamilySwitch()
        rig.backend.pullResults += PullResult(
            entities = listOf(
                com.lezi.babylog.sync.engine.SyncWireMapper.baby(
                    rig.babies.getIncludingDeleted(babyId)!!,
                    avatarMediaUuid = null,
                ),
                com.lezi.babylog.sync.engine.SyncWireMapper.record(
                    rig.records.getByClientUuid(recordUuid)!!,
                    babyClientUuid = babyUuid,
                    customItemClientUuid = null,
                ).let { record ->
                    val payload = Json.parseToJsonElement(record.payloadJson)
                        .jsonObject
                        .toMutableMap()
                    payload["created_by_membership_id"] =
                        kotlinx.serialization.json.JsonPrimitive("membership-new")
                    record.copy(
                        payloadJson = kotlinx.serialization.json.JsonObject(payload).toString(),
                    )
                },
                SyncEntity(
                    type = "custom_item",
                    clientUuid = customItemUuid,
                    payloadJson =
                        """{"name":"抚触","icon_slot":2,"created_by_membership_id":"membership-new"}""",
                    updatedAt = 100,
                ),
                SyncEntity(
                    type = "care_plan",
                    clientUuid = planUuid,
                    payloadJson =
                        """{"baby_client_uuid":"$babyUuid","type":"custom","custom_item_client_uuid":"$customItemUuid","scheduled_at":9000000000000,"scheduled_zone_id":"Asia/Shanghai","note":null,"payload_json":{"title":"抚触"},"schema_version":2,"status":"completed","created_by_membership_id":"membership-new","fulfilled_record_client_uuid":"$recordUuid","fulfilled_at":121,"source_record_client_uuid":null}""",
                    updatedAt = 121,
                ),
                SyncEntity(
                    type = "fulfillment_candidate",
                    clientUuid = candidateUuid,
                    payloadJson =
                        """{"care_plan_client_uuid":"$planUuid","record_client_uuid":"$recordUuid","actual_timestamp":120,"submitter_membership_id":"membership-new","submitter_role":"owner","confirmed_at":121}""",
                    updatedAt = 121,
                ),
            ),
            cursor = 2,
            generation = "family-new-generation",
            hasMore = false,
        )
        rig.backend.pullResults += PullResult(
            entities = emptyList(),
            cursor = 2,
            generation = "family-new-generation",
            hasMore = false,
        )

        // Family switch ownership stamps arrive on a full pull cycle, not LocalWrite.
        val result = rig.port.sync(SyncTrigger.Foreground)

        assertThat(result.exceptionOrNull()).isNull()
        assertThat(rig.backend.pullCount).isEqualTo(3)
        assertThat(rig.customItems.get(customItemUuid)?.createdByMembershipId)
            .isEqualTo("membership-new")
        assertThat(rig.carePlans.getByClientUuid(planUuid)?.createdByMembershipId)
            .isEqualTo("membership-new")
        val candidate = requireNotNull(
            rig.fulfillmentCandidates.getByClientUuid(candidateUuid),
        )
        assertThat(candidate.submitterMembershipId).isEqualTo("membership-new")
        assertThat(candidate.submitterRole).isEqualTo("owner")
    }

    @Test
    fun switchingFamilyCarePlanCreatorSchedulesAuthoritativeAcknowledgementPull() = runTest {
        val babyUuid = "00000000-0000-0000-0000-000000000111"
        val planUuid = "00000000-0000-0000-0000-000000000112"
        val rig = SyncRig(
            session = joinedSession("family-old").copy(membershipId = "membership-old"),
        )
        val babyId = rig.babies.seed(
            localBaby().copy(clientUuid = babyUuid, syncDirty = false),
        )
        rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = planUuid,
                createdByMembershipId = "membership-old",
                syncDirty = false,
            ),
        )

        rig.preferences.saveSession(
            joinedSession("family-new").copy(membershipId = "membership-new"),
        )
        rig.backend.pullFailures += generationChangedForFamilySwitch()
        rig.backend.pullResults += PullResult(
            entities = listOf(
                com.lezi.babylog.sync.engine.SyncWireMapper.baby(
                    rig.babies.getIncludingDeleted(babyId)!!,
                    avatarMediaUuid = null,
                ),
                SyncEntity(
                    type = "care_plan",
                    clientUuid = planUuid,
                    payloadJson =
                        """{"baby_client_uuid":"$babyUuid","type":"formula","custom_item_client_uuid":null,"scheduled_at":9000000000000,"scheduled_zone_id":"Asia/Shanghai","note":null,"payload_json":{"amount_ml":120},"schema_version":2,"status":"pending","created_by_membership_id":"membership-new","fulfilled_record_client_uuid":null,"fulfilled_at":null,"source_record_client_uuid":null}""",
                    updatedAt = 100,
                ),
            ),
            cursor = 1,
            generation = "family-new-generation",
            hasMore = false,
        )
        rig.backend.pullResults += PullResult(
            entities = emptyList(),
            cursor = 1,
            generation = "family-new-generation",
            hasMore = false,
        )

        // Authoritative creator recovery requires pull; use full cycle.
        val result = rig.port.sync(SyncTrigger.Foreground)

        assertThat(result.exceptionOrNull()).isNull()
        assertThat(rig.backend.pullCount).isEqualTo(3)
        assertThat(
            rig.carePlans.getByClientUuid(planUuid)?.createdByMembershipId,
        ).isEqualTo("membership-new")
    }

    @Test
    fun syncUpgradesUntaggedSameGenerationDirectoryOnceAndReusesTheOwnedCache() = runTest {
        val file = java.io.File.createTempFile("lezi-read-directory-", ".preferences_pb").also { it.delete() }
        val store = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val prefs = com.lezi.babylog.sync.session.DataStoreSyncPreferences(
            store, com.lezi.babylog.sync.session.InMemorySecureRefreshTokenStore(),
        )
        val session = joinedSession("family-a")
        prefs.saveSession(session)
        prefs.rememberEndpoint(com.lezi.babylog.sync.session.TrustedEndpointProfile.systemPki(session.baseUrl))
        prefs.saveFamilyMemberDirectorySnapshot("directory-test", listOf(
            FamilyMember("旧缓存", session.role, true, session.membershipId),
        ))
        val rig = SyncRig(session, ownedPreferences = prefs)
        assertThat(prefs.familyReadSnapshot.first().hasCurrentDirectory).isFalse()
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).exceptionOrNull()).isNull()
        val snapshot = prefs.familyReadSnapshot.first()
        assertThat(snapshot.hasCurrentDirectory).isTrue()
        assertThat(snapshot.members.single().displayName).isEqualTo("管理员")
        assertThat(rig.backend.memberCalls).isEqualTo(1)
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).exceptionOrNull()).isNull()
        assertThat(rig.backend.memberCalls).isEqualTo(1)
        file.delete()
    }

    @Test
    fun bothDirectoryFetchEntrypointsRejectLateFamilyAToBToAResponses() = runTest {
        for (viaSync in listOf(false, true)) {
            val file = java.io.File.createTempFile("lezi-directory-aba-", ".preferences_pb").also { it.delete() }
            val store = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
            val prefs = com.lezi.babylog.sync.session.DataStoreSyncPreferences(
                store, com.lezi.babylog.sync.session.InMemorySecureRefreshTokenStore(),
            )
            val a = joinedSession("family-a")
            prefs.saveSession(a)
            prefs.rememberEndpoint(com.lezi.babylog.sync.session.TrustedEndpointProfile.systemPki(a.baseUrl))
            val rig = SyncRig(a, ownedPreferences = prefs)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            rig.backend.beforeMemberDirectoryReturn = { entered.complete(Unit); release.await() }
            // This owner uses real DataStore/Keystore-dispatch I/O. Keep its barrier timeout
            // on the real dispatcher so virtual time cannot expire it while startup I/O runs.
            val response = async(Dispatchers.Default) {
                if (viaSync) rig.port.sync(SyncTrigger.PullToRefresh).exceptionOrNull()
                else rig.port.listFamilyMembers().exceptionOrNull()
            }
            try {
                // An early failure must diagnose the unmet gate instead of waiting forever.
                select<Unit> {
                    entered.onAwait { }
                    response.onAwait { failure ->
                        error("Directory request completed before controlled response gate (viaSync=$viaSync): $failure")
                    }
                }
                prefs.saveSession(a.copy(familyId = "family-b", membershipId = "member-b", deviceId = "device-b"))
                prefs.saveSession(a)
                val epoch = prefs.familyReadSnapshot.first().identityEpoch!!
                assertThat(prefs.saveFamilyMemberDirectoryIfCurrent(epoch, "directory-test", listOf(
                    FamilyMember("新目录", a.role, true, a.membershipId),
                ))).isTrue()
            } finally {
                release.complete(Unit)
            }
            val failure = response.await()
            assertThat(failure?.message).contains(
                if (viaSync) "member directory identity changed" else "家庭身份已变化",
            )
            assertThat(prefs.familyReadSnapshot.first().members.single().displayName).isEqualTo("新目录")
            file.delete()
        }
    }

    @Test
    fun familyMemberListUsesTrustedEndpointWithoutTransportIdentity() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.awaitInitialReplicaBarrier()
        rig.backend.nextMembers = listOf(
            FamilyMember(
                displayName = "妈妈",
                role = FamilyRole.Owner,
                isSelf = true,
                membershipId = "membership-a",
                devices = listOf(FamilyDevice("device", "手机", 1, true)),
            ),
        )

        assertThat(rig.port.listFamilyMembers().isSuccess).isTrue()
        assertThat(rig.backend.memberCalls).isEqualTo(1)
        assertThat(rig.port.familyMemberDirectory().first()).containsExactly(
            FamilyMember("妈妈", FamilyRole.Owner, true, "membership-a", devices = null),
        )
    }

    @Test
    fun zeroEntityPullAppliesCurrentFamilyNameWithoutOverwritingConcurrentSessionFields() =
        runTest {
            val valueRig = SyncRig(
                session = joinedSession("family-a").copy(
                    familyName = "旧名字",
                    membershipId = "membership-before",
                    pullCursor = 4,
                    pullGeneration = "g0",
                ),
            )
            valueRig.backend.nextPull = PullResult(
                entities = emptyList(),
                cursor = 5,
                generation = "g0",
                familyName = "  NAS 新名字  ",
                hasMore = false,
            )
            valueRig.backend.beforePullReturn = {
                valueRig.preferences.saveSession(
                    valueRig.preferences.current().copy(
                        familyName = "本机并发名字",
                        membershipId = "membership-concurrent",
                    ),
                )
            }

            assertThat(valueRig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()
            assertThat(valueRig.preferences.current().familyName).isEqualTo("NAS 新名字")
            assertThat(valueRig.preferences.current().pullCursor).isEqualTo(5)
            assertThat(valueRig.preferences.current().pullGeneration).isEqualTo("g0")
            assertThat(valueRig.preferences.current().membershipId)
                .isEqualTo("membership-concurrent")

            val nullRig = SyncRig(
                session = joinedSession("family-a").copy(familyName = "旧名字"),
            )
            nullRig.backend.nextPull = PullResult(
                entities = emptyList(),
                cursor = 1,
                generation = "current-generation",
                familyName = null,
                hasMore = false,
            )

            assertThat(nullRig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()
            assertThat(nullRig.preferences.current().familyName).isNull()
            assertThat(nullRig.preferences.current().pullCursor).isEqualTo(1)
            assertThat(nullRig.preferences.current().pullGeneration)
                .isEqualTo("current-generation")
        }

    @Test
    fun fakeBackendConvergesFamilyNameAcrossTwoClientsOnZeroEntityPull() = runTest {
        val sharedBackend = FakeSyncBackend()
        val ownerJoin = sharedBackend.create(
            baseUrl = "https://192.168.1.20:8787",
            deviceId = "owner-device",
            displayName = "妈妈",
            createRequestId = "create-request-family-name-convergence",
            bootstrapSecret = "bootstrap",
            familyName = "旧家庭名",
        )
        val ownerSession = joinedSession(ownerJoin.familyId).copy(
            accessToken = ownerJoin.accessToken,
            deviceId = "owner-device",
            role = ownerJoin.role,
            familyName = ownerJoin.familyName,
            membershipId = ownerJoin.membershipId,
            pullGeneration = ownerJoin.generation,
        )
        val memberSession = joinedSession(ownerJoin.familyId).copy(
            accessToken = "member-access",
            deviceId = "member-device",
            role = FamilyRole.Member,
            familyName = ownerJoin.familyName,
            membershipId = "member-membership",
            pullGeneration = ownerJoin.generation,
        )
        val ownerRig = SyncRig(ownerSession, syncBackend = sharedBackend)
        ownerRig.awaitInitialReplicaBarrier()
        val memberRig = SyncRig(memberSession, syncBackend = sharedBackend)

        assertThat(ownerRig.port.renameFamily("  新家庭名  ").isSuccess).isTrue()
        assertThat(memberRig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()
        assertThat(memberRig.preferences.current().familyName).isEqualTo("新家庭名")
        assertThat(memberRig.records.listPendingSync()).isEmpty()

        assertThat(ownerRig.port.renameFamily("  ").isFailure).isTrue()
        assertThat(memberRig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()
        assertThat(memberRig.preferences.current().familyName).isEqualTo("新家庭名")
        assertThat(memberRig.records.listPendingSync()).isEmpty()

    }

    @Test
    fun multiPagePullKeepsConsistentCurrentFamilyNameEnvelope() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                familyName = "旧名字",
                pullGeneration = "g1",
            ),
        )
        rig.backend.pullResults += PullResult(
            entities = emptyList(),
            cursor = 1,
            generation = "g1",
            hasMore = true,
            familyName = "  分页新名字  ",
        )
        rig.backend.pullResults += PullResult(
            entities = emptyList(),
            cursor = 1,
            generation = "g1",
            hasMore = false,
            familyName = "分页新名字",
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        assertThat(rig.preferences.current().familyName).isEqualTo("分页新名字")
        assertThat(rig.preferences.current().pullCursor).isEqualTo(1)
        assertThat(rig.preferences.current().pullGeneration).isEqualTo("g1")
    }

    @Test
    fun multiPagePullRejectsConflictingFamilyNamesInsteadOfUsingTheLastPage() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                familyName = "拉取前名字",
                pullGeneration = "g1",
            ),
        )
        rig.backend.pullResults += PullResult(
            entities = emptyList(),
            cursor = 1,
            generation = "g1",
            hasMore = true,
            familyName = "第一页名字",
        )
        rig.backend.pullResults += PullResult(
            entities = emptyList(),
            cursor = 1,
            generation = "g1",
            hasMore = false,
            familyName = "第二页名字",
        )

        val result = rig.port.sync(SyncTrigger.PullToRefresh)

        // Family-name drift is a typed cycle-state failure: the page is not
        // checkpointed with the conflicting name and the foreground cycle
        // continues (self-heals next round) instead of hard-failing as a
        // misclassified "check your input" error.
        assertThat(result.isSuccess).isTrue()
        assertThat(rig.preferences.current().familyName).isEqualTo("第一页名字")
        assertThat(rig.preferences.current().pullCursor).isEqualTo(1)
    }

}

private fun generationChangedForFamilySwitch() = SyncHttpException(
    statusCode = 409,
    responseBody =
        """
        {
          "detail":{
            "code":"generation_changed",
            "action":"full_resync",
            "reset_cursor":0,
            "server_cursor":1,
            "server_generation":"family-new-generation"
          }
        }
        """.trimIndent(),
)
