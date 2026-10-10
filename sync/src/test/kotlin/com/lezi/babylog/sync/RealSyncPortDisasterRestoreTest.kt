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
import com.lezi.babylog.core.database.causal.WakeObservationEntity
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
import kotlinx.coroutines.test.advanceTimeBy
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
import com.lezi.babylog.sync.session.familyFailureKind
import com.lezi.babylog.sync.backend.FakeSyncBackend
import com.lezi.babylog.sync.backend.deadline.FamilyHttpException
import com.lezi.babylog.sync.backend.deadline.FamilyHttpFailureKind
import com.lezi.babylog.sync.backend.deadline.ForegroundSyncCycle
import com.lezi.babylog.sync.backend.testPreparedMedia
import com.lezi.babylog.sync.session.SESSION_BARRIER_WAIT_MILLIS

// Split from RealSyncPortTest kitchen sink by contract cluster (ticket 05).
class RealSyncPortDisasterRestoreTest {
    @Test
    fun disasterRestoreStartClientUpdateRequiredPublishesForceShellAndCandidateLanInvite() =
        runTest {
            val candidate = TrustedEndpointProfile.systemPki(
                "https://192.168.77.4:8765",
            )
            val rig = SyncRig(
                session = joinedSession("family-a"),
                clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
                setupProbe = SetupProbe { _, trusted ->
                    SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Empty)
                },
            )
            rig.awaitStartupRecovery()
            // Snapshot builder requires at least one baby before the network start call.
            rig.babies.seed(localBaby())
            // Empty restore candidate gates writes with the same client_update_required floor
            // as sync; no joined session on that origin → PackageUnknown + 8767 guidance.
            rig.backend.disasterRestoreStartFailure = SyncHttpException(
                statusCode = 403,
                responseBody = """{"code":"client_update_required","detail":"too old"}""",
            )
            // Old session metadata must not be required for the force shell to publish.
            rig.backend.getAppUpdateMetadataFailure =
                SyncHttpException(500, """{"detail":"old origin unreachable"}""")

            val result = rig.port.startDisasterRecovery(
                endpoint = candidate,
                ownerDisplayName = "管理员",
                deviceName = "新管理员手机",
                rootPassword = "start-root-secret",
            )

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull())
                .isInstanceOf(ClientUpdateRequiredException::class.java)
            assertThat(rig.port.availableForcedAppUpdate().first())
                .isEqualTo(ForcedAppUpdateState.PackageUnknown)
            assertThat(rig.port.forcedUpdateLanInviteHost().first())
                .isEqualTo("192.168.77.4")
            assertThat(lanInviteApkDownloadUrl(rig.port.forcedUpdateLanInviteHost().first()!!))
                .isEqualTo("http://192.168.77.4:8767/download/lezi.apk")
            // Retain the old joined session; restore did not switch endpoint.
            assertThat(rig.preferences.current().serverHost).isEqualTo("192.168.1.20")
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNull()
        }

    @Test
    fun ownerRestoresCompleteLocalSnapshotBeforeAtomicallyRetiringOldReplica() = runTest {
        val candidate = TrustedEndpointProfile.systemPki("https://nas-replacement.example.test")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            setupProbe = SetupProbe { _, trusted ->
                SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Empty)
            },
        )
        rig.awaitStartupRecovery()
        val oldSession = rig.preferences.current()
        val oldEndpoint = rig.preferences.verifiedEndpoint.first()
        val babyId = rig.babies.seed(localBaby())
        val recordId = rig.records.seed(
            localRecord(babyId).copy(createdByMembershipId = oldSession.membershipId),
        )
        rig.carePlans.seed(localCarePlan(babyId))
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = "media-local",
                kind = "log",
                localUri = "records/media-local.jpg",
                createdAt = 120,
                updatedAt = 120,
            ),
        )
        val summary = rig.port.prepareDisasterRecovery().getOrThrow()
        val staged = rig.port.startDisasterRecovery(
            endpoint = candidate,
            ownerDisplayName = "管理员",
            deviceName = "新管理员手机",
            rootPassword = "start-root-secret",
        ).getOrThrow()

        assertThat(summary.babies).isEqualTo(1)
        assertThat(summary.records).isEqualTo(1)
        assertThat(summary.carePlans).isEqualTo(1)
        assertThat(summary.photos).isEqualTo(1)
        assertThat(staged.status).isEqualTo("ready_to_commit")
        assertThat(rig.backend.anonymousHealthCalls).isEqualTo(1)
        assertThat(rig.backend.anonymousReadyCalls).isEqualTo(1)
        assertThat(rig.preferences.current()).isEqualTo(oldSession)
        assertThat(rig.preferences.verifiedEndpoint.first()).isEqualTo(oldEndpoint)
        assertThat(rig.backend.disasterRestoreStartEndpoints).containsExactly(candidate)
        assertThat(rig.backend.disasterRestoreStartFamilyIds).containsExactly("family-a")
        assertThat(rig.backend.disasterRestoreStartRootPasswords)
            .containsExactly("start-root-secret")
        assertThat(rig.backend.disasterRestoreManifestEntities.single().map { it.type })
            .containsExactly("baby", "record", "care_plan", "media")
        assertThat(rig.backend.disasterRestoreManifestMedia.single().single())
            .isEqualTo(
                DisasterRestoreMediaSpec(
                    clientUuid = "media-local",
                    byteSize = 1,
                    sha256 =
                        "4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a",
                ),
            )
        assertThat(rig.backend.disasterRestoreMediaUuids).containsExactly("media-local")
        assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNotNull()
        assertThat(rig.preferences.disasterRestoreToken()).isEqualTo("recovery-token-secret")

        // Nursing work remains Room-first while media uploads. This row was not in the immutable
        // restore manifest and must survive endpoint activation as a publishable local change.
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-after-restore-start",
                timestamp = 130,
                updatedAt = 130,
                createdByMembershipId = oldSession.membershipId,
            ),
        )
        val committed = rig.port.commitDisasterRecovery("commit-root-secret").getOrThrow()

        assertThat(committed.session.familyId).isEqualTo(oldSession.familyId)
        assertThat(committed.session.membershipId).isEqualTo("restored-owner-membership")
        assertThat(rig.preferences.current().copy(lastSuccessAt = committed.session.lastSuccessAt))
            .isEqualTo(committed.session)
        assertThat(rig.preferences.verifiedEndpoint.first()).isEqualTo(candidate)
        assertThat(rig.records.getByClientUuid("record-local")?.createdByMembershipId)
            .isEqualTo("restored-owner-membership")
        assertThat(rig.records.getByClientUuid("record-local")?.syncDirty).isFalse()
        assertThat(rig.carePlans.getByClientUuid("plan-local")?.createdByMembershipId)
            .isEqualTo("restored-owner-membership")
        assertThat(rig.carePlans.getByClientUuid("plan-local")?.syncDirty).isFalse()
        assertThat(rig.media.getByClientUuid("media-local")?.remoteUri).isNotNull()
        assertThat(rig.media.getByClientUuid("media-local")?.syncDirty).isFalse()
        assertThat(rig.records.getByClientUuid("record-after-restore-start")?.createdByMembershipId)
            .isEqualTo("restored-owner-membership")
        assertThat(rig.records.getByClientUuid("record-after-restore-start")?.syncDirty).isTrue()
        assertThat(rig.backend.disasterRestoreCommitRootPasswords)
            .containsExactly("commit-root-secret")
        assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNull()
        assertThat(rig.preferences.disasterRestoreToken()).isEmpty()
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun hungDisasterRestoreUploadFailsAsSyncTookTooLongAndReleasesTheLock() = runTest {
        val candidate = TrustedEndpointProfile.systemPki("https://nas-replacement.example.test")
        val rig = restoreRig()
        rig.backend.putDisasterRestoreMediaStarted = CompletableDeferred()
        rig.backend.beforePutDisasterRestoreMedia = { awaitCancellation() }

        val restoring = async {
            rig.port.startDisasterRecovery(
                endpoint = candidate,
                ownerDisplayName = "管理员",
                deviceName = "新管理员手机",
                rootPassword = "start-root-secret",
            )
        }
        rig.backend.putDisasterRestoreMediaStarted!!.await()

        advanceTimeBy(ForegroundSyncCycle.MAX_ELAPSED_MILLIS)
        runCurrent()

        val result = restoring.await()
        assertThat(result.isFailure).isTrue()
        val error = result.exceptionOrNull()
        assertThat(error).isInstanceOf(FamilyHttpException::class.java)
        assertThat((error as FamilyHttpException).kind)
            .isEqualTo(FamilyHttpFailureKind.SyncTookTooLong)
        assertThat(
            com.lezi.babylog.core.common.failure.failureExplanation(
                com.lezi.babylog.core.common.failure.FailureKind.SyncTookTooLong,
            ).title,
        ).isEqualTo("这次同步时间太长，已先停下来")

        val approve = rig.port.approveNewMemberLogin("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        assertThat((approve.exceptionOrNull() as? FamilyHttpException)?.kind)
            .isNotEqualTo(FamilyHttpFailureKind.HouseholdSyncing)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun approveDuringHungDisasterRestoreReportsHouseholdSyncing() = runTest {
        val candidate = TrustedEndpointProfile.systemPki("https://nas-replacement.example.test")
        val rig = restoreRig()
        rig.backend.putDisasterRestoreMediaStarted = CompletableDeferred()
        rig.backend.beforePutDisasterRestoreMedia = { awaitCancellation() }

        val restoring = async {
            rig.port.startDisasterRecovery(
                endpoint = candidate,
                ownerDisplayName = "管理员",
                deviceName = "新管理员手机",
                rootPassword = "start-root-secret",
            )
        }
        rig.backend.putDisasterRestoreMediaStarted!!.await()

        val approve = async {
            rig.port.approveNewMemberLogin("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        }
        advanceTimeBy(SESSION_BARRIER_WAIT_MILLIS)
        runCurrent()

        val error = approve.await().exceptionOrNull()
        assertThat(error).isInstanceOf(FamilyHttpException::class.java)
        assertThat((error as FamilyHttpException).kind)
            .isEqualTo(FamilyHttpFailureKind.HouseholdSyncing)
        assertThat(familyFailureKind(error!!)).isEqualTo(
            com.lezi.babylog.core.common.failure.FailureKind.HouseholdSyncing,
        )
        assertThat(rig.backend.approvedMemberLoginRequestIds).isEmpty()
        restoring.cancel()
    }

    @Test
    fun disasterSnapshotExportsWakeObservationAndWakeMedia() = runTest {
        val candidate = TrustedEndpointProfile.systemPki("https://nas-replacement.example.test")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            setupProbe = SetupProbe { _, trusted ->
                SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Empty)
            },
        )
        rig.awaitStartupRecovery()
        val babyId = rig.babies.seed(localBaby())
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "sleep-local",
                type = "sleep",
                timestamp = 1_000,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                updatedAt = 1_000,
                effectiveWakeObservationClientUuid = "wake-local",
                createdByMembershipId = rig.preferences.current().membershipId,
            ),
        )
        val wakeId = rig.wakeObservations.seed(
            WakeObservationEntity(
                clientUuid = "wake-local",
                sleepRecordClientUuid = "sleep-local",
                wakeTimestamp = 1_500,
                observerMembershipId = "member-local",
                note = null,
                withdrawn = false,
                updatedAt = 1_500,
            ),
        )
        rig.wakeObservations.seed(
            WakeObservationEntity(
                clientUuid = "wake-deleted",
                sleepRecordClientUuid = "sleep-local",
                wakeTimestamp = 1_600,
                updatedAt = 1_600,
                deletedAt = 1_600,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                wakeObservationId = wakeId,
                clientUuid = "wake-media-local",
                kind = "wake",
                localUri = "records/wake-media-local.jpg",
                createdAt = 1_500,
                updatedAt = 1_500,
            ),
        )

        val staged = rig.port.startDisasterRecovery(
            endpoint = candidate,
            ownerDisplayName = "管理员",
            deviceName = "新管理员手机",
            rootPassword = "start-root-secret",
        ).getOrThrow()

        assertThat(staged.status).isEqualTo("ready_to_commit")
        val entities = rig.backend.disasterRestoreManifestEntities.single()
        assertThat(entities.map { it.type to it.clientUuid }).containsAtLeast(
            "wake_observation" to "wake-local",
            "media" to "wake-media-local",
        )
        assertThat(entities.map { it.clientUuid }).doesNotContain("wake-deleted")
        val wakePayload = Json.parseToJsonElement(
            entities.single { it.type == "wake_observation" }.payloadJson,
        ).jsonObject
        assertThat(wakePayload.keys).containsExactly(
            "sleep_record_client_uuid",
            "wake_timestamp",
            "note",
            "withdrawn",
            "observer_membership_id",
        ).inOrder()
        assertThat(wakePayload.getValue("sleep_record_client_uuid").jsonPrimitive.content)
            .isEqualTo("sleep-local")
        assertThat(wakePayload.getValue("wake_timestamp").jsonPrimitive.longOrNull).isEqualTo(1_500)
        assertThat(wakePayload.getValue("note")).isEqualTo(JsonNull)
        assertThat(wakePayload.getValue("withdrawn").jsonPrimitive.content).isEqualTo("false")
        val mediaPayload = Json.parseToJsonElement(
            entities.single { it.clientUuid == "wake-media-local" }.payloadJson,
        ).jsonObject
        assertThat(mediaPayload.getValue("kind").jsonPrimitive.content).isEqualTo("wake")
        // Pull reuses record_client_uuid for the WakeObservation, not the sleep record.
        assertThat(mediaPayload.getValue("record_client_uuid").jsonPrimitive.content)
            .isEqualTo("wake-local")
        assertThat(mediaPayload.getValue("care_plan_client_uuid")).isEqualTo(JsonNull)
        assertThat(mediaPayload.getValue("baby_client_uuid")).isEqualTo(JsonNull)
        val wakeVersions = rig.preferences.disasterRestoreCheckpoint.first()!!
            .entityVersions
            .filter { it.type == "wake_observation" }
        assertThat(wakeVersions.map { it.clientUuid to it.restored }).containsExactly(
            "wake-local" to true,
            "wake-deleted" to false,
        ).inOrder()
        assertThat(rig.backend.disasterRestoreMediaUuids).containsExactly("wake-media-local")
    }

    @Test
    fun disasterSnapshotExcludesLiveWakeOverTombstonedSleepInsteadOfFailing() = runTest {
        val candidate = TrustedEndpointProfile.systemPki("https://nas-replacement.example.test")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            setupProbe = SetupProbe { _, trusted ->
                SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Empty)
            },
        )
        rig.awaitStartupRecovery()
        val babyId = rig.babies.seed(localBaby())
        // The sleep is tombstoned but a live wake still points at it — the
        // converged replica state after a sleep delete. The export must keep
        // succeeding and drop the wake plus its photo.
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "sleep-gone",
                type = "sleep",
                timestamp = 1_000,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                updatedAt = 2_000,
                deletedAt = 2_000,
                effectiveWakeObservationClientUuid = "wake-orphan",
            ),
        )
        val wakeId = rig.wakeObservations.seed(
            WakeObservationEntity(
                clientUuid = "wake-orphan",
                sleepRecordClientUuid = "sleep-gone",
                wakeTimestamp = 1_500,
                observerMembershipId = "member-local",
                updatedAt = 1_500,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                wakeObservationId = wakeId,
                clientUuid = "wake-media-orphan",
                kind = "wake",
                localUri = "records/wake-media-orphan.jpg",
                createdAt = 1_500,
                updatedAt = 1_500,
            ),
        )

        val staged = rig.port.startDisasterRecovery(
            endpoint = candidate,
            ownerDisplayName = "管理员",
            deviceName = "新管理员手机",
            rootPassword = "start-root-secret",
        ).getOrThrow()

        assertThat(staged.status).isEqualTo("ready_to_commit")
        val entities = rig.backend.disasterRestoreManifestEntities.single()
        assertThat(entities.map { it.clientUuid }).doesNotContain("wake-orphan")
        assertThat(entities.map { it.clientUuid }).doesNotContain("wake-media-orphan")
        assertThat(rig.backend.disasterRestoreMediaUuids).doesNotContain("wake-media-orphan")
    }

    @Test
    fun disasterSnapshotStripsDanglingCustomItemReferenceInsteadOfFailing() = runTest {
        val candidate = TrustedEndpointProfile.systemPki("https://nas-replacement.example.test")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            setupProbe = SetupProbe { _, trusted ->
                SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Empty)
            },
        )
        rig.awaitStartupRecovery()
        val babyId = rig.babies.seed(localBaby())
        // The custom item was deleted while a record still referenced it — the
        // converged replica state after an ordinary custom-item delete. The
        // export must keep succeeding and the record rides with the dangling
        // reference stripped.
        val tombstonedItemId = rig.customItems.seed(
            CustomItemEntity(
                clientUuid = "item-gone",
                familyId = 1L,
                name = "已删项目",
                iconSlot = 0,
                updatedAt = 2_000,
                deletedAt = 2_000,
            ),
        )
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-dangling-item",
                type = "custom",
                timestamp = 1_000,
                payloadJson = """{"title":"抚触","custom_item_id":$tombstonedItemId}""",
                updatedAt = 1_000,
            ),
        )
        // A completed plan (its own definition still live) fulfilled by that
        // excluded record — cross-replica settlement can pick such a pair. The
        // server rejects an unresolvable fulfilled_record_client_uuid, so the
        // plan must ride as missed with the fulfillment pair stripped.
        val liveItemId = rig.customItems.seed(
            CustomItemEntity(
                clientUuid = "item-live",
                familyId = 1L,
                name = "存活项目",
                iconSlot = 1,
                updatedAt = 1_000,
            ),
        )
        rig.carePlans.seed(
            CarePlanEntity(
                clientUuid = "plan-fulfilled-by-excluded",
                babyId = babyId,
                type = "custom",
                customItemId = liveItemId,
                scheduledAt = 900,
                scheduledZoneId = "Asia/Shanghai",
                payloadJson = """{"title":"抚触","custom_item_id":$liveItemId,"icon_slot":1}""",
                status = "completed",
                fulfilledRecordClientUuid = "record-dangling-item",
                fulfilledAt = 1_000,
                updatedAt = 1_000,
            ),
        )

        val staged = rig.port.startDisasterRecovery(
            endpoint = candidate,
            ownerDisplayName = "管理员",
            deviceName = "新管理员手机",
            rootPassword = "start-root-secret",
        ).getOrThrow()

        assertThat(staged.status).isEqualTo("ready_to_commit")
        val entities = rig.backend.disasterRestoreManifestEntities.single()
        // A custom record cannot ride the wire without its definition; the
        // record is excluded (export still succeeds) rather than failing the
        // whole family backup.
        assertThat(entities.map { it.clientUuid }).doesNotContain("record-dangling-item")
        val planPayload = Json.parseToJsonElement(
            entities.single { it.clientUuid == "plan-fulfilled-by-excluded" }.payloadJson,
        ).jsonObject
        assertThat(planPayload.getValue("status").jsonPrimitive.content).isEqualTo("missed")
        assertThat(planPayload.getValue("fulfilled_record_client_uuid")).isEqualTo(JsonNull)
        assertThat(planPayload.getValue("fulfilled_at")).isEqualTo(JsonNull)
        // Excluded live rows must not claim publication in the retirement
        // receipts; the included plan does.
        val versions = rig.preferences.disasterRestoreCheckpoint.first()!!.entityVersions
        assertThat(
            versions.single { it.type == "record" && it.clientUuid == "record-dangling-item" }
                .restored,
        ).isFalse()
        assertThat(
            versions.single {
                it.type == "care_plan" && it.clientUuid == "plan-fulfilled-by-excluded"
            }.restored,
        ).isTrue()
    }

    @Test
    fun committedDisasterRestoreRetiresLiveWakeAndKeepsUploadEditDirty() = runTest {
        val candidate = TrustedEndpointProfile.systemPki("https://nas-replacement.example.test")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            setupProbe = SetupProbe { _, trusted ->
                SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Empty)
            },
        )
        rig.awaitStartupRecovery()
        val babyId = rig.babies.seed(localBaby())
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "sleep-local",
                type = "sleep",
                timestamp = 1_000,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                updatedAt = 1_000,
                createdByMembershipId = rig.preferences.current().membershipId,
            ),
        )
        rig.wakeObservations.seed(
            WakeObservationEntity(
                clientUuid = "wake-live",
                sleepRecordClientUuid = "sleep-local",
                wakeTimestamp = 1_500,
                observerMembershipId = "member-local",
                updatedAt = 1_500,
                syncDirty = true,
            ),
        )
        rig.wakeObservations.seed(
            WakeObservationEntity(
                clientUuid = "wake-during-upload",
                sleepRecordClientUuid = "sleep-local",
                wakeTimestamp = 1_600,
                observerMembershipId = "member-local",
                updatedAt = 1_600,
                syncDirty = true,
            ),
        )

        rig.port.startDisasterRecovery(
            endpoint = candidate,
            ownerDisplayName = "管理员",
            deviceName = "新管理员手机",
            rootPassword = "start-root-secret",
        ).getOrThrow()
        val duringUpload = requireNotNull(
            rig.wakeObservations.getByClientUuid("wake-during-upload"),
        )
        rig.wakeObservations.update(
            duringUpload.copy(
                updatedAt = duringUpload.updatedAt + 40,
                note = "edited-during-upload",
            ),
        )

        val committed = rig.port.commitDisasterRecovery("commit-root-secret").getOrThrow()

        assertThat(committed.session.membershipId).isEqualTo("restored-owner-membership")
        with(requireNotNull(rig.wakeObservations.getByClientUuid("wake-live"))) {
            assertThat(syncDirty).isFalse()
            assertThat(observerMembershipId).isEqualTo("restored-owner-membership")
            assertThat(familyPublishedUpdatedAt).isEqualTo(1_500)
            assertThat(updatedAt).isEqualTo(1_500)
        }
        with(requireNotNull(rig.wakeObservations.getByClientUuid("wake-during-upload"))) {
            assertThat(syncDirty).isTrue()
            assertThat(observerMembershipId).isEqualTo("restored-owner-membership")
            assertThat(updatedAt).isEqualTo(1_640)
            assertThat(familyPublishedUpdatedAt).isNull()
            assertThat(note).isEqualTo("edited-during-upload")
        }
    }

    private suspend fun restoreRig(): SyncRig {
        val rig = SyncRig(
            session = joinedSession("family-a"),
            setupProbe = SetupProbe { _, trusted ->
                SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Empty)
            },
        )
        rig.awaitStartupRecovery()
        val babyId = rig.babies.seed(localBaby())
        val recordId = rig.records.seed(
            localRecord(babyId).copy(createdByMembershipId = rig.preferences.current().membershipId),
        )
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = "media-local",
                kind = "log",
                localUri = "records/media-local.jpg",
                createdAt = 120,
                updatedAt = 120,
            ),
        )
        return rig
    }

}
