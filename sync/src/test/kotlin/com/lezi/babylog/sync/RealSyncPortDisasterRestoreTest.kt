package com.lezi.babylog.sync

// 192.168.77.10 is a synthetic RFC1918 LAN test endpoint, never a deployment default.

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
    fun legacyPendingRestoreWithoutImmutableSnapshotPreservesCheckpointAndToken() = runTest {
        val original = joinedSession("family-a")
        val rig = SyncRig(original)
        rig.awaitStartupRecovery()
        rig.preferences.saveDisasterRestoreCheckpoint(
            DisasterRestoreCheckpoint(
                batchId = "retired-batch",
                endpoint = TrustedEndpointProfile.systemPki("https://replacement.example.test"),
                familyId = original.familyId,
                startRequestId = "start", manifestRequestId = "manifest", commitRequestId = "commit",
                expiresAtEpochSeconds = 9_999_999_999, status = "ready_to_commit", entityVersions = emptyList(),
            ),
            "recovery-token-secret",
        )
        rig.backend.disasterRestoreStatusFailure = SyncHttpException(401)

        assertThat(rig.port.resumeDisasterRecovery().isFailure).isTrue()

        assertThat(rig.preferences.disasterRestoreCheckpoint.first()?.batchId).isEqualTo("retired-batch")
        assertThat(rig.preferences.disasterRestoreToken()).isEqualTo("recovery-token-secret")
        assertThat(rig.preferences.current()).isEqualTo(original)
    }

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
                    SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Empty, setOf("nursing_plan_intent_v1", "restore_authority_v1"))
                },
            )
            rig.awaitStartupRecovery()
            // Snapshot builder requires at least one baby before the network start call.
            rig.babies.seed(restoreBaby())
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
                SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Empty, setOf("nursing_plan_intent_v1", "restore_authority_v1"))
            },
        )
        rig.awaitStartupRecovery()
        val oldSession = rig.preferences.current()
        val oldEndpoint = rig.preferences.verifiedEndpoint.first()
        rig.mediaFiles.seedReadableSource("records/media-local.jpg", byteArrayOf(1))
        val babyId = rig.babies.seed(restoreBaby())
        val recordId = rig.records.seed(
            restoreRecord(babyId).copy(createdByMembershipId = oldSession.membershipId),
        )
        rig.carePlans.seed(restorePlan(babyId))
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = "dea9e1e4-6f2c-56fe-9eeb-69d394a414b9",
                kind = "log",
                localUri = "records/media-local.jpg",
                mime = "image/jpeg",
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
                    clientUuid = "dea9e1e4-6f2c-56fe-9eeb-69d394a414b9",
                    byteSize = 1,
                    sha256 =
                        "4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a",
                ),
            )
        assertThat(rig.backend.disasterRestoreMediaUuids).containsExactly("dea9e1e4-6f2c-56fe-9eeb-69d394a414b9")
        assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNotNull()
        assertThat(rig.preferences.disasterRestoreToken()).isEqualTo("recovery-token-secret")

        // Nursing work remains Room-first while media uploads. This row was not in the immutable
        // restore manifest and must survive endpoint activation as a publishable local change.
        rig.records.seed(
            restoreRecord(babyId).copy(
                clientUuid = "25d29265-5848-5b4d-9850-c73937f9b37b",
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
        assertThat(rig.records.getByClientUuid("2f4f4d56-8341-564f-98fa-35ee35d1953d")?.createdByMembershipId)
            .isEqualTo("restored-owner-membership")
        assertThat(rig.records.getByClientUuid("2f4f4d56-8341-564f-98fa-35ee35d1953d")?.syncDirty).isFalse()
        assertThat(rig.carePlans.getByClientUuid("34adcf3a-2557-5e24-9343-262efd166122")?.createdByMembershipId)
            .isEqualTo("restored-owner-membership")
        assertThat(rig.carePlans.getByClientUuid("34adcf3a-2557-5e24-9343-262efd166122")?.syncDirty).isFalse()
        assertThat(rig.media.getByClientUuid("dea9e1e4-6f2c-56fe-9eeb-69d394a414b9")?.remoteUri).isNotNull()
        assertThat(rig.media.getByClientUuid("dea9e1e4-6f2c-56fe-9eeb-69d394a414b9")?.syncDirty).isFalse()
        assertThat(rig.records.getByClientUuid("25d29265-5848-5b4d-9850-c73937f9b37b")?.createdByMembershipId)
            .isEqualTo("restored-owner-membership")
        assertThat(rig.records.getByClientUuid("25d29265-5848-5b4d-9850-c73937f9b37b")?.syncDirty).isTrue()
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
                SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Empty, setOf("nursing_plan_intent_v1", "restore_authority_v1"))
            },
        )
        rig.awaitStartupRecovery()
        val babyId = rig.babies.seed(restoreBaby())
        rig.records.seed(
            restoreRecord(babyId).copy(
                clientUuid = "d1067da9-3a1f-5acc-a3b3-1547f61874f7",
                type = "sleep",
                timestamp = 1_000,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                updatedAt = 1_000,
                effectiveWakeObservationClientUuid = "867b3b2b-ab0d-5bb2-9737-e9f140e6a19d",
                createdByMembershipId = rig.preferences.current().membershipId,
            ),
        )
        val wakeId = rig.wakeObservations.seed(
            WakeObservationEntity(
                clientUuid = "867b3b2b-ab0d-5bb2-9737-e9f140e6a19d",
                sleepRecordClientUuid = "d1067da9-3a1f-5acc-a3b3-1547f61874f7",
                wakeTimestamp = 1_500,
                observerMembershipId = "member-local",
                note = null,
                withdrawn = false,
                updatedAt = 1_500,
            ),
        )
        rig.wakeObservations.seed(
            WakeObservationEntity(
                clientUuid = "08d34967-af8b-573e-a9ed-bd64c9acdd59",
                sleepRecordClientUuid = "d1067da9-3a1f-5acc-a3b3-1547f61874f7",
                wakeTimestamp = 1_600,
                updatedAt = 1_600,
                deletedAt = 1_600,
            ),
        )
        rig.mediaFiles.seedReadableSource("records/wake-media-local.jpg", byteArrayOf(1))
        rig.media.seed(
            MediaAssetEntity(
                wakeObservationId = wakeId,
                clientUuid = "d1367bbe-fe4a-5ccc-8792-aacd56afe1c4",
                kind = "wake",
                localUri = "records/wake-media-local.jpg",
                mime = "image/jpeg",
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
            "wake_observation" to "867b3b2b-ab0d-5bb2-9737-e9f140e6a19d",
            "media" to "d1367bbe-fe4a-5ccc-8792-aacd56afe1c4",
        )
        assertThat(entities.map { it.clientUuid }).doesNotContain("08d34967-af8b-573e-a9ed-bd64c9acdd59")
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
            .isEqualTo("d1067da9-3a1f-5acc-a3b3-1547f61874f7")
        assertThat(wakePayload.getValue("wake_timestamp").jsonPrimitive.longOrNull).isEqualTo(1_500)
        assertThat(wakePayload.getValue("note")).isEqualTo(JsonNull)
        assertThat(wakePayload.getValue("withdrawn").jsonPrimitive.content).isEqualTo("false")
        val mediaPayload = Json.parseToJsonElement(
            entities.single { it.clientUuid == "d1367bbe-fe4a-5ccc-8792-aacd56afe1c4" }.payloadJson,
        ).jsonObject
        assertThat(mediaPayload.getValue("kind").jsonPrimitive.content).isEqualTo("wake")
        // Pull reuses record_client_uuid for the WakeObservation, not the sleep record.
        assertThat(mediaPayload.getValue("record_client_uuid").jsonPrimitive.content)
            .isEqualTo("867b3b2b-ab0d-5bb2-9737-e9f140e6a19d")
        assertThat(mediaPayload.getValue("care_plan_client_uuid")).isEqualTo(JsonNull)
        assertThat(mediaPayload.getValue("baby_client_uuid")).isEqualTo(JsonNull)
        val wakeCheckpoint = requireNotNull(rig.preferences.disasterRestoreCheckpoint.first())
        assertThat(wakeCheckpoint.entityVersions).isEmpty()
        val wakeVersions = com.lezi.babylog.sync.disasterrecovery.RestoreSnapshotJournal(
            rig.conflictDetails, rig.immutableMediaSpool, rig.transactions,
            com.lezi.babylog.sync.disasterrecovery.RestoreFileSnapshotStore(java.io.File(rig.appUpdateCacheDir, "restore-snapshots")),
        ).load(wakeCheckpoint.startRequestId).use { snapshot ->
            snapshot.retirementVersions.filter { it.type == "wake_observation" }
        }
        assertThat(wakeVersions.map { it.clientUuid to it.restored }).containsExactly(
            "867b3b2b-ab0d-5bb2-9737-e9f140e6a19d" to true,
            "08d34967-af8b-573e-a9ed-bd64c9acdd59" to false,
        ).inOrder()
        assertThat(rig.backend.disasterRestoreMediaUuids).containsExactly("d1367bbe-fe4a-5ccc-8792-aacd56afe1c4")
    }

    @Test
    fun disasterSnapshotExcludesLiveWakeOverTombstonedSleepInsteadOfFailing() = runTest {
        val candidate = TrustedEndpointProfile.systemPki("https://nas-replacement.example.test")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            setupProbe = SetupProbe { _, trusted ->
                SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Empty, setOf("nursing_plan_intent_v1", "restore_authority_v1"))
            },
        )
        rig.awaitStartupRecovery()
        val babyId = rig.babies.seed(restoreBaby())
        // The sleep is tombstoned but a live wake still points at it — the
        // converged replica state after a sleep delete. The export must keep
        // succeeding and drop the wake plus its photo.
        rig.records.seed(
            restoreRecord(babyId).copy(
                clientUuid = "7a7cf507-7649-58d1-8707-d0eeedd7a963",
                type = "sleep",
                timestamp = 1_000,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                updatedAt = 2_000,
                deletedAt = 2_000,
                effectiveWakeObservationClientUuid = "a77f6006-9b2d-59eb-8e4d-7215aa587471",
            ),
        )
        val wakeId = rig.wakeObservations.seed(
            WakeObservationEntity(
                clientUuid = "a77f6006-9b2d-59eb-8e4d-7215aa587471",
                sleepRecordClientUuid = "7a7cf507-7649-58d1-8707-d0eeedd7a963",
                wakeTimestamp = 1_500,
                observerMembershipId = "member-local",
                updatedAt = 1_500,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                wakeObservationId = wakeId,
                clientUuid = "1dd0cc43-358e-5964-a77b-adc7b7e15eb0",
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
        assertThat(entities.map { it.clientUuid }).doesNotContain("a77f6006-9b2d-59eb-8e4d-7215aa587471")
        assertThat(entities.map { it.clientUuid }).doesNotContain("1dd0cc43-358e-5964-a77b-adc7b7e15eb0")
        assertThat(rig.backend.disasterRestoreMediaUuids).doesNotContain("1dd0cc43-358e-5964-a77b-adc7b7e15eb0")
    }

    @Test
    fun disasterSnapshotStripsDanglingCustomItemReferenceInsteadOfFailing() = runTest {
        val candidate = TrustedEndpointProfile.systemPki("https://nas-replacement.example.test")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            setupProbe = SetupProbe { _, trusted ->
                SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Empty, setOf("nursing_plan_intent_v1", "restore_authority_v1"))
            },
        )
        rig.awaitStartupRecovery()
        val babyId = rig.babies.seed(restoreBaby())
        // The custom item was deleted while a record still referenced it — the
        // converged replica state after an ordinary custom-item delete. The
        // export must keep succeeding and the record rides with the dangling
        // reference stripped.
        val tombstonedItemId = rig.customItems.seed(
            CustomItemEntity(
                clientUuid = "4378c977-c4cb-5911-9d3d-ddf3d1f69008",
                familyId = 1L,
                name = "已删项目",
                iconSlot = 0,
                updatedAt = 2_000,
                deletedAt = 2_000,
            ),
        )
        rig.records.seed(
            restoreRecord(babyId).copy(
                clientUuid = "b46313ad-e771-5364-be87-13ad9859f6d5",
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
                clientUuid = "8e042812-7fd7-5463-9bb7-04feb0cbf307",
                familyId = 1L,
                name = "存活项目",
                iconSlot = 1,
                updatedAt = 1_000,
            ),
        )
        rig.carePlans.seed(
            CarePlanEntity(
                clientUuid = "9e2aa3ef-22ec-548d-a706-0c8583bb1278",
                babyId = babyId,
                type = "custom",
                customItemId = liveItemId,
                scheduledAt = 900,
                scheduledZoneId = "Asia/Shanghai",
                payloadJson = """{"title":"抚触","custom_item_id":$liveItemId,"icon_slot":1}""",
                status = "completed",
                fulfilledRecordClientUuid = "b46313ad-e771-5364-be87-13ad9859f6d5",
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
        assertThat(entities.map { it.clientUuid }).doesNotContain("b46313ad-e771-5364-be87-13ad9859f6d5")
        val planPayload = Json.parseToJsonElement(
            entities.single { it.clientUuid == "9e2aa3ef-22ec-548d-a706-0c8583bb1278" }.payloadJson,
        ).jsonObject
        assertThat(planPayload.getValue("status").jsonPrimitive.content).isEqualTo("missed")
        assertThat(planPayload.getValue("fulfilled_record_client_uuid")).isEqualTo(JsonNull)
        assertThat(planPayload.getValue("fulfilled_at")).isEqualTo(JsonNull)
        // Excluded live rows must not claim publication in the retirement
        // receipts; the included plan does.
        val checkpoint = requireNotNull(rig.preferences.disasterRestoreCheckpoint.first())
        assertThat(checkpoint.entityVersions).isEmpty() // Full evidence belongs to the immutable file.
        val versions = com.lezi.babylog.sync.disasterrecovery.RestoreSnapshotJournal(
            rig.conflictDetails, rig.immutableMediaSpool, rig.transactions,
            com.lezi.babylog.sync.disasterrecovery.RestoreFileSnapshotStore(java.io.File(rig.appUpdateCacheDir, "restore-snapshots")),
        ).load(checkpoint.startRequestId).use { it.retirementVersions }
        assertThat(
            versions.single { it.type == "record" && it.clientUuid == "b46313ad-e771-5364-be87-13ad9859f6d5" }
                .restored,
        ).isFalse()
        assertThat(
            versions.single {
                it.type == "care_plan" && it.clientUuid == "9e2aa3ef-22ec-548d-a706-0c8583bb1278"
            }.restored,
        ).isTrue()
    }

    @Test
    fun committedDisasterRestoreRetiresLiveWakeAndKeepsUploadEditDirty() = runTest {
        val candidate = TrustedEndpointProfile.systemPki("https://nas-replacement.example.test")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            setupProbe = SetupProbe { _, trusted ->
                SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Empty, setOf("nursing_plan_intent_v1", "restore_authority_v1"))
            },
        )
        rig.awaitStartupRecovery()
        val babyId = rig.babies.seed(restoreBaby())
        rig.records.seed(
            restoreRecord(babyId).copy(
                clientUuid = "d1067da9-3a1f-5acc-a3b3-1547f61874f7",
                type = "sleep",
                timestamp = 1_000,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                updatedAt = 1_000,
                createdByMembershipId = rig.preferences.current().membershipId,
            ),
        )
        rig.wakeObservations.seed(
            WakeObservationEntity(
                clientUuid = "c65fa5ee-3b53-5feb-ae81-f9db4abc8589",
                sleepRecordClientUuid = "d1067da9-3a1f-5acc-a3b3-1547f61874f7",
                wakeTimestamp = 1_500,
                observerMembershipId = "member-local",
                updatedAt = 1_500,
                syncDirty = true,
            ),
        )
        rig.wakeObservations.seed(
            WakeObservationEntity(
                clientUuid = "04d88fff-a45e-50ba-99dd-830cfeccde84",
                sleepRecordClientUuid = "d1067da9-3a1f-5acc-a3b3-1547f61874f7",
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
            rig.wakeObservations.getByClientUuid("04d88fff-a45e-50ba-99dd-830cfeccde84"),
        )
        rig.wakeObservations.update(
            duringUpload.copy(
                updatedAt = duringUpload.updatedAt + 40,
                note = "edited-during-upload",
            ),
        )

        val committed = rig.port.commitDisasterRecovery("commit-root-secret").getOrThrow()

        assertThat(committed.session.membershipId).isEqualTo("restored-owner-membership")
        with(requireNotNull(rig.wakeObservations.getByClientUuid("c65fa5ee-3b53-5feb-ae81-f9db4abc8589"))) {
            assertThat(syncDirty).isFalse()
            assertThat(observerMembershipId).isEqualTo("restored-owner-membership")
            assertThat(familyPublishedUpdatedAt).isEqualTo(1_500)
            assertThat(updatedAt).isEqualTo(1_500)
        }
        with(requireNotNull(rig.wakeObservations.getByClientUuid("04d88fff-a45e-50ba-99dd-830cfeccde84"))) {
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
                SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Empty, setOf("nursing_plan_intent_v1", "restore_authority_v1"))
            },
        )
        rig.awaitStartupRecovery()
        val babyId = rig.babies.seed(restoreBaby())
        val recordId = rig.records.seed(
            restoreRecord(babyId).copy(createdByMembershipId = rig.preferences.current().membershipId),
        )
        rig.mediaFiles.seedReadableSource("records/media-local.jpg", byteArrayOf(1))
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = "dea9e1e4-6f2c-56fe-9eeb-69d394a414b9",
                kind = "log",
                localUri = "records/media-local.jpg",
                mime = "image/jpeg",
                createdAt = 120,
                updatedAt = 120,
            ),
        )
        return rig
    }

    private fun restoreBaby() = localBaby().copy(clientUuid = "7e4ec4b9-91a1-5794-84bd-33613bcf9189")
    private fun restoreRecord(babyId: Long) = localRecord(babyId).copy(clientUuid = "2f4f4d56-8341-564f-98fa-35ee35d1953d")
    private fun restorePlan(babyId: Long) = localCarePlan(babyId).copy(clientUuid = "34adcf3a-2557-5e24-9343-262efd166122")
}
