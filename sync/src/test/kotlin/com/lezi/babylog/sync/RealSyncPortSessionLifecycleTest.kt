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
class RealSyncPortSessionLifecycleTest {
    @Test
    fun mediaCleanupWaitsForReplicaBarrierBeforeReclaimingBytes() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.awaitStartupRecovery()
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 1L,
            generation = "current-generation",
            hasMore = false,
        )
        rig.backend.pullStarted = CompletableDeferred()
        rig.backend.releasePull = CompletableDeferred()
        val syncing = async {
            rig.port.sync(SyncTrigger.PullToRefresh).getOrThrow()
        }
        rig.backend.pullStarted!!.await()
        val tombstoneUuid = "11111111-1111-3111-8111-111111111111"
        val path = "downloaded/staged-consumer.jpg"
        rig.media.seed(
            MediaAssetEntity(
                recordId = 7L,
                clientUuid = tombstoneUuid,
                kind = "log",
                localUri = path,
                createdAt = 100L,
                updatedAt = 200L,
                deletedAt = 200L,
                syncDirty = true,
            ),
        )

        val cleanup = async {
            rig.port.cleanupTombstonedMedia(setOf(tombstoneUuid)).getOrThrow()
        }
        runCurrent()

        assertThat(cleanup.isCompleted).isFalse()
        assertThat(rig.mediaFiles.deleted).doesNotContain(path)

        rig.backend.releasePull!!.complete(Unit)
        syncing.await()
        cleanup.await()

        assertThat(rig.mediaFiles.deleted).containsExactly(path)
        assertThat(rig.media.getByClientUuid(tombstoneUuid)?.localUri).isEmpty()
    }

    @Test
    fun startupRecoveryContainsOperationalFailureAndReportsIt() = runTest {
        val failure = IllegalStateException("marker unavailable")
        var reported: Throwable? = null

        runProcessStartupRecovery(
            reportFailure = { reported = it },
            recover = { throw failure },
        )

        assertThat(reported).isSameInstanceAs(failure)
    }

    @Test
    fun startupRecoveryPropagatesTheOriginalNestedCancellation() = runTest {
        val cancellation = CancellationException("process stopping")
        val wrapper = IllegalStateException("startup recovery failed", cancellation)

        val thrown = runCatching {
            runProcessStartupRecovery(
                reportFailure = { error("must not report cancellation") },
                recover = { throw wrapper },
            )
        }.exceptionOrNull()

        assertThat(thrown).isSameInstanceAs(cancellation)
    }

    @Test
    fun atomicBundleIdIsStableUuidAndIncludesRootTypeEntityAndVersion() {
        val entityUuid = "11111111-2222-3333-8444-555555555555"

        val recordBundle = AtomicBundleId.forRecord(entityUuid, 1_725_123_456_789)

        assertThat(recordBundle).isEqualTo("f9a0d4c8-1f6d-3c9b-af41-bc497b33b79e")
        assertThat(UUID.fromString(recordBundle).toString()).isEqualTo(recordBundle)
        assertThat(AtomicBundleId.forRecord(entityUuid, 1_725_123_456_789))
            .isEqualTo(recordBundle)
        assertThat(AtomicBundleId.forRecord(entityUuid, 1_725_123_456_790))
            .isEqualTo("b8e35751-20fb-3a89-ba93-ac2f906b75eb")
        assertThat(
            AtomicBundleId.forRecord(
                "11111111-2222-3333-8444-555555555556",
                1_725_123_456_789,
            ),
        ).isEqualTo("d9d61874-0056-38c0-8540-8c6d1961ea92")
        assertThat(AtomicBundleId.forCarePlan(entityUuid, 1_725_123_456_789))
            .isEqualTo("48dc1a40-05dc-357f-b5c9-00ed00de6f57")
        assertThat(AtomicBundleId.forBaby(entityUuid, 1_725_123_456_789))
            .isNotEqualTo(recordBundle)
        assertThat(AtomicBundleId.forCustomItem(entityUuid, 1_725_123_456_789))
            .isNotEqualTo(recordBundle)
        assertThat(AtomicBundleId.forFulfillmentCandidate(entityUuid, 1_725_123_456_789))
            .isNotEqualTo(recordBundle)
        assertThat(AtomicBundleId.forRecord(entityUuid, 2))
            .isEqualTo("e4c2d0cf-4967-347c-b3bd-af9dae2b34f4")
    }

    @Test
    fun unjoinedSyncIsDisabledNoOp() = runTest {
        val rig = SyncRig(session = SyncSession())

        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()

        assertThat(rig.backend.pushes).isEmpty()
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Disabled)
    }

    @Test
    fun foregroundPendingMemberCheckPublishesTheExactTerminalResultToOpenUi() = runTest {
        val initial = SyncSession(
            serverHost = "192.168.1.20",
            serverPort = 8787,
        )
        val preferences = MemorySyncPreferences(initial)
        val rig = SyncRig(session = initial, syncPreferences = preferences)
        preferences.savePendingMemberLogin(
            rig.backend.nextMemberLoginReceipt,
            displayName = "爸爸",
            deviceName = "Pixel 9",
        )
        rig.backend.memberLoginStatuses += MemberLoginStatus.Rejected
        val observed = async { rig.port.memberLoginChecks().first() }
        runCurrent()

        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()

        assertThat(withTimeout(2_000) { observed.await() }).isEqualTo(
            MemberLoginCheckResult.Terminal(MemberLoginStatus.Rejected),
        )
        assertThat(preferences.current().isJoined).isFalse()
        assertThat(preferences.pendingMemberLogin.first()).isNull()
    }

    @Test
    fun memberLoginChecksNeverBlockNetworkCompletionBehindSlowUiCollector() = runTest {
        val initial = SyncSession(
            serverHost = "192.168.1.20",
            serverPort = 8787,
        )
        val preferences = MemorySyncPreferences(initial)
        val rig = SyncRig(session = initial, syncPreferences = preferences)
        preferences.savePendingMemberLogin(
            rig.backend.nextMemberLoginReceipt,
            displayName = "爸爸",
            deviceName = "Pixel 9",
        )
        val firstObserved = CompletableDeferred<Unit>()
        val collector = launch {
            rig.port.memberLoginChecks().collect {
                firstObserved.complete(Unit)
                awaitCancellation()
            }
        }
        runCurrent()

        assertThat(rig.port.checkMemberLogin().isSuccess).isTrue()
        firstObserved.await()
        assertThat(rig.port.checkMemberLogin().isSuccess).isTrue()

        val thirdCheck = withTimeoutOrNull(1_000) { rig.port.checkMemberLogin() }

        assertThat(thirdCheck).isNotNull()
        assertThat(thirdCheck?.isSuccess).isTrue()
        collector.cancel()
    }


    @Test
    fun unjoinedSyncRecoversDurableReplicaCleanupBeforeDisabledNoOp() = runTest {
        val rig = SyncRig(session = SyncSession())
        rig.awaitStartupRecovery()
        rig.pendingDomainRecovery.calls = 0
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = "stale-media",
                kind = "log",
                recordId = 1,
                localUri = "photos/stale.jpg",
                createdAt = 1,
            ),
        )
        rig.pendingReplicaCleanup.pending = pendingReplicaCleanup(
            familyId = "family-old",
            mediaClientUuids = setOf("stale-media"),
            localMediaPaths = setOf("photos/stale.jpg"),
        )

        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()

        assertThat(rig.pendingReplicaCleanup.pending).isNull()
        assertThat(rig.media.getByClientUuid("stale-media")).isNull()
        assertThat(rig.mediaFiles.deleted).containsExactly("photos/stale.jpg")
        assertThat(rig.backend.pushes).isEmpty()
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.pendingDomainRecovery.calls).isEqualTo(1)
    }

    @Test
    fun failedDomainCleanupRecoveryBlocksReplicaAndBackendBeforeUnjoinedNoOp() = runTest {
        val rig = SyncRig(session = SyncSession())
        rig.awaitStartupRecovery()
        rig.pendingReplicaCleanup.pending = pendingReplicaCleanup()
        rig.pendingDomainRecovery.failures += IllegalStateException("provider unavailable")

        val failure = rig.port.sync(SyncTrigger.Foreground).exceptionOrNull()

        assertThat(failure).hasMessageThat().isEqualTo("provider unavailable")
        assertThat(rig.pendingReplicaCleanup.pending).isNotNull()
        assertThat(rig.backend.pushes).isEmpty()
        assertThat(rig.backend.pullCount).isEqualTo(0)
    }

    @Test
    fun failedDomainCleanupRecoveryBlocksEndpointMutation() = runTest {
        val configured = SyncSession(
            serverHost = "192.168.1.20",
            serverPort = 8787,
        )
        val rig = SyncRig(session = configured)
        rig.awaitStartupRecovery()
        rig.pendingDomainRecovery.failures += IllegalStateException("provider unavailable")

        val failure = rig.port.saveEndpointConfig(
            FamilyEndpointConfig(host = "192.168.1.99", port = 8787),
        ).exceptionOrNull()

        assertThat(failure).hasMessageThat().isEqualTo("provider unavailable")
        assertThat(rig.preferences.current()).isEqualTo(configured)
    }

    @Test
    fun failedReplicaRecoveryBlocksBackendAndIsRetriedOnNextSync() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.awaitStartupRecovery()
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = "stale-media",
                kind = "log",
                recordId = 1,
                localUri = "photos/stale.jpg",
                createdAt = 1,
            ),
        )
        rig.pendingReplicaCleanup.pending = pendingReplicaCleanup(
            mediaClientUuids = setOf("stale-media"),
            localMediaPaths = setOf("photos/stale.jpg"),
        )
        rig.mediaFiles.deleteFailures += IllegalStateException("cleanup failed")

        val first = rig.port.sync(SyncTrigger.Foreground)

        assertThat(first.exceptionOrNull()).isInstanceOf(LocalClearCommittedException::class.java)
        assertThat(rig.pendingReplicaCleanup.pending).isNotNull()
        assertThat(rig.backend.pushes).isEmpty()
        assertThat(rig.backend.pullCount).isEqualTo(0)

        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()
        assertThat(rig.pendingReplicaCleanup.pending).isNull()
        assertThat(rig.backend.pullCount).isEqualTo(1)
    }

    @Test
    fun failedReplicaRecoveryBlocksFamilyCreationBeforePolicyAndBackendIo() = runTest {
        val configured = SyncSession(
            serverHost = "192.168.1.20",
            serverPort = 8787,
        )
        val rig = SyncRig(session = configured)
        rig.awaitStartupRecovery()
        rig.pendingReplicaCleanup.pending = pendingReplicaCleanup()
        rig.pendingReplicaCleanup.loadFailures += IllegalStateException("marker unavailable")

        val failure = rig.port.createFamily(
            displayName = "妈妈",
            bootstrapSecret = "bootstrap",
            familyName = "乐乐家",
        ).exceptionOrNull()

        assertThat(failure).hasMessageThat().isEqualTo("marker unavailable")
        assertThat(rig.backend.createRequestIds).isEmpty()
        assertThat(rig.preferences.current()).isEqualTo(configured)
    }

    @Test
    fun freshCreatePersistsSessionThenPushesPendingDataAndFullPullsFromZero() = runTest {
        val configured = SyncSession(
            serverHost = "192.168.1.20",
            serverPort = 8787,
        )
        val rig = SyncRig(session = configured)
        rig.awaitStartupRecovery()
        val automaticSyncGate = CompletableDeferred<Unit>()
        rig.backend.anonymousHealthGate = automaticSyncGate
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 7,
            generation = "current-generation",
            hasMore = false,
        )
        rig.babies.seed(localBaby())

        val created = rig.port.createFamily(
            displayName = "妈妈",
            bootstrapSecret = "bootstrap",
            familyName = "乐乐家",
        ).getOrThrow()

        assertThat(created.dataRecovery).isEqualTo(InitialFamilyDataRecovery.NotRequired)
        assertThat(rig.port.session().first().accessToken).isEqualTo("owner-token")
        assertThat(rig.port.session().first().refreshToken).isEqualTo("owner-refresh-token")
        assertThat(rig.backend.pullCursors).isEmpty()

        rig.foreground.setForeground(false)
        automaticSyncGate.complete(Unit)
        rig.foreground.setForeground(true)
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        assertThat(created.reclaimed).isFalse()
        assertThat(rig.port.session().first().accessToken).isEqualTo("owner-token")
        assertThat(rig.backend.pullCursors).containsExactly(0L)
        assertThat(rig.backend.syncOrder)
            .containsExactly("pull:0", "reconcile:1", "stage:baby")
            .inOrder()
        assertThat(rig.port.session().first().pullCursor).isEqualTo(7L)
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
    }

    @Test
    fun failedFirstPullKeepsOwnerSessionAndRestartRetriesWithoutCreatingAgain() = runTest {
        val configured = SyncSession(
            serverHost = "192.168.1.20",
            serverPort = 8787,
        )
        val rig = SyncRig(session = configured)
        rig.awaitStartupRecovery()
        val automaticSyncGate = CompletableDeferred<Unit>()
        rig.backend.anonymousHealthGate = automaticSyncGate
        rig.backend.pullFailures += SyncHttpException(503)
        rig.babies.seed(localBaby())

        val result = rig.port.createFamily(
            displayName = "妈妈",
            bootstrapSecret = "bootstrap",
            familyName = "乐乐家",
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().dataRecovery)
            .isEqualTo(InitialFamilyDataRecovery.NotRequired)
        assertThat(rig.port.session().first().accessToken).isEqualTo("owner-token")
        assertThat(rig.port.session().first().refreshToken).isEqualTo("owner-refresh-token")
        assertThat(rig.port.session().first().pullCursor).isEqualTo(0L)
        assertThat(rig.backend.pullCursors).isEmpty()

        rig.foreground.setForeground(false)
        automaticSyncGate.complete(Unit)
        rig.foreground.setForeground(true)
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isFailure).isTrue()

        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Error)
        assertThat(rig.backend.pullCursors).containsExactly(0L)

        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 9,
            generation = "current-generation",
            hasMore = false,
        )
        val restarted = SyncRig(
            session = SyncSession(),
            syncPreferences = rig.preferences,
        )
        restarted.awaitStartupRecovery()
        restarted.backend.nextPull = rig.backend.nextPull

        assertThat(restarted.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()
        assertThat(restarted.backend.createRequestIds).isEmpty()
        assertThat(restarted.backend.pullCursors).containsExactly(0L)
        assertThat(restarted.port.session().first().pullCursor).isEqualTo(9L)
        assertThat(restarted.port.status().first()).isEqualTo(SyncStatus.Idle)
    }

    @Test
    fun qrMemberLoginFinishesBeforeRetryableInitialDataRecovery() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://192.168.1.20:8787")
        val preferences = MemorySyncPreferences(SyncSession()).apply {
            rememberEndpoint(endpoint)
        }
        val rig = SyncRig(
            session = SyncSession(),
            syncPreferences = preferences,
        )
        rig.awaitStartupRecovery()
        rig.foreground.setForeground(false)
        rig.backend.pullFailures += SyncHttpException(503)
        val payload = MemberLoginQrPayload(
            endpoint = endpoint,
            grant = "grant-0000000000000000000000000000000000000",
            familyName = "乐乐一家",
            memberDisplayName = "妈妈",
            expiresAtEpochSeconds = 1_753_419_000,
        )

        val result = rig.port.claimMemberLoginQr(payload, "Pixel Tablet").getOrThrow()

        assertThat(result.session.isJoined).isTrue()
        assertThat(result.dataRecovery).isEqualTo(InitialFamilyDataRecovery.NotRequired)
        assertThat(rig.backend.pullCursors).isEmpty()
        rig.foreground.setForeground(true)
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isFailure).isTrue()
        assertThat(rig.port.session().first().isJoined).isTrue()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Error)
        assertThat(rig.backend.memberLoginGrantClaims)
            .containsExactly(Triple(endpoint, payload.grant, "Pixel Tablet"))
    }

    @Test
    fun ownerCreatesOneQrCodeFromTheTrustedEndpointAndServerLandingUrl() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.awaitStartupRecovery()
        rig.backend.nextMemberLoginGrant = rig.backend.nextMemberLoginGrant.copy(
            landingUrl = "http://192.168.1.20:8767/join",
        )

        val code = rig.port.createMemberLoginQrCode("membership-member").getOrThrow()

        assertThat(code.landingUrl).isEqualTo("http://192.168.1.20:8767/join")
        assertThat(code.payload.endpoint.origin).isEqualTo("https://192.168.1.20:8787")
        assertThat(code.payload.grant).isEqualTo(rig.backend.nextMemberLoginGrant.grant)
        assertThat(rig.backend.memberLoginGrantTargets.single().third)
            .isEqualTo("membership-member")
    }

    @Test
    fun queuedNetworkChangeWaitsForDurableCreateWithoutExtendingCreateThroughFirstPull() = runTest {
        val configured = SyncSession(
            serverHost = "192.168.1.20",
            serverPort = 8787,
        )
        val rig = SyncRig(session = configured)
        rig.awaitStartupRecovery()
        val automaticSyncGate = CompletableDeferred<Unit>()
        rig.backend.anonymousHealthGate = automaticSyncGate
        rig.backend.createStarted = CompletableDeferred()
        rig.backend.releaseCreate = CompletableDeferred()
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 7,
            generation = "current-generation",
            hasMore = false,
        )

        val creating = async {
            rig.port.createFamily(
                displayName = "妈妈",
                bootstrapSecret = "bootstrap",
                familyName = "乐乐家",
            ).getOrThrow()
        }
        rig.backend.createStarted!!.await()
        val changingNetwork = async {
            rig.port.saveEndpointConfig(
                FamilyEndpointConfig(
                    host = "192.168.1.99",
                    port = 8787,
                ),
            ).getOrThrow()
        }
        runCurrent()

        rig.backend.releaseCreate!!.complete(Unit)
        val created = creating.await()
        rig.foreground.setForeground(false)
        automaticSyncGate.complete(Unit)
        changingNetwork.await()

        assertThat(created.dataRecovery).isEqualTo(InitialFamilyDataRecovery.NotRequired)
        assertThat(rig.backend.pullCursors).isEmpty()
        assertThat(rig.preferences.current().serverHost).isEqualTo("192.168.1.99")
        assertThat(rig.preferences.current().isJoined).isFalse()
    }

    @Test
    fun failedReplicaRecoveryBlocksEndpointMutationInsideSharedBarrier() = runTest {
        val configured = SyncSession(
            serverHost = "192.168.1.20",
            serverPort = 8787,
        )
        val rig = SyncRig(session = configured)
        rig.awaitStartupRecovery()
        rig.pendingReplicaCleanup.pending = pendingReplicaCleanup()
        rig.pendingReplicaCleanup.loadFailures += IllegalStateException("marker unavailable")

        val failure = rig.port.saveEndpointConfig(
            FamilyEndpointConfig(host = "192.168.1.99", port = 8787),
        ).exceptionOrNull()

        assertThat(failure).hasMessageThat().isEqualTo("marker unavailable")
        assertThat(rig.preferences.current()).isEqualTo(configured)
    }

}
