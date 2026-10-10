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
import com.lezi.babylog.sync.backend.FakeSyncBackend
import com.lezi.babylog.sync.backend.testPreparedMedia

// Split from RealSyncPortTest kitchen sink by contract cluster (ticket 05).
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RealSyncPortIdentityClearTest {
    @Test
    fun terminalResponseForOldCredentialsCannotClearANewerHealthyGeneration() = runTest {
        val original = joinedSession("family-a")
        val rig = SyncRig(original)
        rig.awaitStartupRecovery()
        rig.backend.beforePullReturn = {
            rig.preferences.saveSession(original.copy(accessToken = "new-access", refreshToken = "new-refresh"))
            throw RemoteDeviceRemovedException(original)
        }
        val result = rig.port.sync(SyncTrigger.PullToRefresh)
        assertThat(result.isFailure).isTrue()
        assertThat(rig.preferences.current().accessToken).isEqualTo("new-access")
        assertThat(rig.preferences.current().refreshToken).isEqualTo("new-refresh")
        assertThat(rig.preferences.pendingDeviceRemovalClear).isFalse()
        assertThat(rig.preferences.deviceRemovedReceiptValue).isNull()
    }

    @Test
    fun confirmedFamilyDeleteStagesFullClearAndRetiresEveryLocalFamilyTrace() = runTest {
        val clearGate = TestRemovedDeviceLocalClearGate()
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                role = FamilyRole.Owner,
                familyName = "乐乐一家",
            ),
            removedDeviceLocalClearGate = clearGate,
        )
        rig.awaitInitialReplicaBarrier()

        val result = rig.port.deleteFamily("  乐乐一家  ", "root-password-secret")

        assertThat(result.isSuccess).isTrue()
        assertThat(rig.backend.deleteFamilyCalls).isEqualTo(1)
        assertThat(rig.backend.deletedFamilyConfirmations)
            .containsExactly("乐乐一家" to "root-password-secret")
        assertThat(clearGate.calls).isEqualTo(1)
        assertThat(rig.preferences.current()).isEqualTo(SyncSession())
        assertThat(rig.preferences.pendingFamilyDeletionClear).isFalse()
    }

    @Test
    fun failedFamilyDeletePreservesEverythingAndInterruptedCleanupResumes() = runTest {
        val original = joinedSession("family-a").copy(
            role = FamilyRole.Owner,
            familyName = "乐乐一家",
        )
        val preferences = MemorySyncPreferences(original)
        val failedRemoteRig = SyncRig(session = original, syncPreferences = preferences)
        failedRemoteRig.awaitInitialReplicaBarrier()
        failedRemoteRig.backend.deleteFailure = SyncHttpException(503)

        assertThat(
            failedRemoteRig.port.deleteFamily("乐乐一家", "root-password-secret").isFailure,
        ).isTrue()
        assertThat(preferences.current()).isEqualTo(original)
        assertThat(preferences.pendingFamilyDeletionClear).isFalse()

        failedRemoteRig.backend.deleteFailure = null
        val interruptedGate = TestRemovedDeviceLocalClearGate().apply {
            failures += IllegalStateException("family cleanup interrupted")
        }
        val interruptedRig = SyncRig(
            session = original,
            syncPreferences = preferences,
            removedDeviceLocalClearGate = interruptedGate,
        )
        interruptedRig.awaitInitialReplicaBarrier()
        assertThat(
            interruptedRig.port.deleteFamily("乐乐一家", "root-password-secret").isFailure,
        ).isTrue()
        assertThat(preferences.current()).isEqualTo(original)
        assertThat(preferences.pendingFamilyDeletionClear).isTrue()

        val resumedGate = TestRemovedDeviceLocalClearGate()
        SyncRig(
            session = original,
            syncPreferences = preferences,
            removedDeviceLocalClearGate = resumedGate,
        )
        resumedGate.firstCall.await()
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(2_000) { preferences.familyDeletionClearCompleted.await() }
        }
        assertThat(preferences.current()).isEqualTo(SyncSession())
        assertThat(preferences.pendingFamilyDeletionClear).isFalse()
    }

    @Test
    fun explicitFamilyDeletedOnTrustedSyncClearsButGeneric401DoesNot() = runTest {
        val clearGate = TestRemovedDeviceLocalClearGate()
        val rig = SyncRig(
            session = joinedSession("family-a"),
            removedDeviceLocalClearGate = clearGate,
        )
        rig.awaitStartupRecovery()
        rig.backend.pullFailures += RemoteFamilyDeletedException()

        val result = rig.port.sync(SyncTrigger.Foreground)

        assertThat(result.exceptionOrNull()).isInstanceOf(RemoteFamilyDeletedException::class.java)
        assertThat(clearGate.calls).isEqualTo(1)
        assertThat(rig.preferences.current()).isEqualTo(SyncSession())
        assertThat(rig.preferences.pendingFamilyDeletionClear).isFalse()
    }

    @Test
    fun repeatedFamilyDeleteConvergesOnlyOnExplicitTerminalReason() = runTest {
        val original = joinedSession("family-a").copy(familyName = "乐乐一家")
        val explicitGate = TestRemovedDeviceLocalClearGate()
        val explicit = SyncRig(
            session = original,
            removedDeviceLocalClearGate = explicitGate,
        )
        explicit.awaitInitialReplicaBarrier()
        explicit.backend.deleteFailure = RemoteFamilyDeletedException()

        assertThat(
            explicit.port.deleteFamily("乐乐一家", "root-password-secret").isSuccess,
        ).isTrue()
        assertThat(explicitGate.calls).isEqualTo(1)
        assertThat(explicit.preferences.current()).isEqualTo(SyncSession())

        val genericGate = TestRemovedDeviceLocalClearGate()
        val generic = SyncRig(
            session = original,
            removedDeviceLocalClearGate = genericGate,
        )
        generic.awaitInitialReplicaBarrier()
        generic.backend.deleteFailure = SyncHttpException(401)

        assertThat(
            generic.port.deleteFamily("乐乐一家", "root-password-secret").isFailure,
        ).isTrue()
        assertThat(genericGate.calls).isEqualTo(0)
        assertThat(generic.preferences.current()).isEqualTo(original)
        assertThat(generic.preferences.pendingFamilyDeletionClear).isFalse()
    }

    @Test
    fun confirmedMemberLeaveStagesFullClearAndRetiresLocalIdentity() = runTest {
        val clearGate = TestRemovedDeviceLocalClearGate()
        val rig = SyncRig(
            session = joinedSession("family-a").copy(role = FamilyRole.Member),
            removedDeviceLocalClearGate = clearGate,
        )
        rig.awaitInitialReplicaBarrier()

        val result = rig.port.leave()

        assertThat(result.isSuccess).isTrue()
        assertThat(clearGate.calls).isEqualTo(1)
        assertThat(rig.preferences.current()).isEqualTo(SyncSession())
        assertThat(rig.preferences.pendingMembershipDeletionClear).isFalse()
    }

    @Test
    fun failedMemberLeavePreservesEverythingAndInterruptedCleanupResumes() = runTest {
        val original = joinedSession("family-a").copy(role = FamilyRole.Member)
        val preferences = MemorySyncPreferences(original)
        val failedRemoteRig = SyncRig(session = original, syncPreferences = preferences)
        failedRemoteRig.awaitInitialReplicaBarrier()
        failedRemoteRig.backend.leaveFailure = SyncHttpException(503)

        assertThat(failedRemoteRig.port.leave().isFailure).isTrue()
        assertThat(preferences.current()).isEqualTo(original)
        assertThat(preferences.pendingMembershipDeletionClear).isFalse()

        failedRemoteRig.backend.leaveFailure = null
        val interruptedGate = TestRemovedDeviceLocalClearGate().apply {
            failures += IllegalStateException("membership cleanup interrupted")
        }
        val interruptedRig = SyncRig(
            session = original,
            syncPreferences = preferences,
            removedDeviceLocalClearGate = interruptedGate,
        )
        interruptedRig.awaitInitialReplicaBarrier()
        assertThat(interruptedRig.port.leave().isFailure).isTrue()
        assertThat(preferences.current()).isEqualTo(original)
        assertThat(preferences.pendingMembershipDeletionClear).isTrue()

        val resumedGate = TestRemovedDeviceLocalClearGate()
        SyncRig(
            session = original,
            syncPreferences = preferences,
            removedDeviceLocalClearGate = resumedGate,
        )
        resumedGate.firstCall.await()
        preferences.membershipDeletionClearCompleted.await()
        assertThat(preferences.current()).isEqualTo(SyncSession())
        assertThat(preferences.pendingMembershipDeletionClear).isFalse()
    }

    @Test
    fun explicitMembershipDeletedOnTrustedSyncClearsButGeneric401DoesNot() = runTest {
        val clearGate = TestRemovedDeviceLocalClearGate()
        val rig = SyncRig(
            session = joinedSession("family-a").copy(role = FamilyRole.Member),
            removedDeviceLocalClearGate = clearGate,
        )
        rig.awaitStartupRecovery()
        rig.backend.pullFailures += RemoteMembershipDeletedException()

        val result = rig.port.sync(SyncTrigger.Foreground)

        assertThat(result.exceptionOrNull())
            .isInstanceOf(RemoteMembershipDeletedException::class.java)
        assertThat(clearGate.calls).isEqualTo(1)
        assertThat(rig.preferences.current()).isEqualTo(SyncSession())
        assertThat(rig.preferences.pendingMembershipDeletionClear).isFalse()
    }

    @Test
    fun confirmedCurrentDeviceLogoutStagesFullClearAndRetiresLocalSession() = runTest {
        val clearGate = TestRemovedDeviceLocalClearGate()
        val rig = SyncRig(
            session = joinedSession("family-a").copy(role = FamilyRole.Owner),
            removedDeviceLocalClearGate = clearGate,
        )
        rig.awaitInitialReplicaBarrier()

        val result = rig.port.logoutCurrentDevice()

        assertThat(result.isSuccess).isTrue()
        assertThat(rig.backend.deviceLogoutCalls).isEqualTo(1)
        assertThat(clearGate.calls).isEqualTo(1)
        assertThat(rig.preferences.current()).isEqualTo(SyncSession())
        assertThat(rig.preferences.pendingDeviceRemovalClear).isFalse()
    }

    @Test
    fun terminalIdentityClearBlocksConcurrentSyncUntilLocalDataAndCredentialsAreRetired() =
        runTest {
            val clearGate = TestRemovedDeviceLocalClearGate().apply {
                release = CompletableDeferred()
            }
            val rig = SyncRig(
                session = joinedSession("family-a").copy(role = FamilyRole.Owner),
                removedDeviceLocalClearGate = clearGate,
            )
            rig.awaitInitialReplicaBarrier()

            val clearing = async { rig.port.logoutCurrentDevice() }
            clearGate.firstCall.await()
            val syncing = async { rig.port.sync(SyncTrigger.PullToRefresh) }
            runCurrent()

            assertThat(syncing.isCompleted).isFalse()
            assertThat(rig.backend.pullCursors).isEmpty()

            clearGate.release!!.complete(Unit)
            assertThat(clearing.await().isSuccess).isTrue()
            assertThat(syncing.await().isSuccess).isTrue()
            assertThat(rig.backend.pullCursors).isEmpty()
            assertThat(rig.preferences.current()).isEqualTo(SyncSession())
        }

    @Test
    fun failedLogoutPreservesEverythingWhileInterruptedCleanupResumesFromDurableMarker() = runTest {
        val original = joinedSession("family-a").copy(role = FamilyRole.Member)
        val preferences = MemorySyncPreferences(original)
        val failedRemoteRig = SyncRig(session = original, syncPreferences = preferences)
        failedRemoteRig.awaitInitialReplicaBarrier()
        failedRemoteRig.backend.deviceLogoutFailure = SyncHttpException(503)

        assertThat(failedRemoteRig.port.logoutCurrentDevice().isFailure).isTrue()
        assertThat(preferences.current()).isEqualTo(original)
        assertThat(preferences.pendingDeviceRemovalClear).isFalse()

        failedRemoteRig.backend.deviceLogoutFailure = null
        val interruptedGate = TestRemovedDeviceLocalClearGate().apply {
            failures += IllegalStateException("local cleanup interrupted")
        }
        val interruptedRig = SyncRig(
            session = original,
            syncPreferences = preferences,
            removedDeviceLocalClearGate = interruptedGate,
        )
        interruptedRig.awaitInitialReplicaBarrier()
        assertThat(interruptedRig.port.logoutCurrentDevice().isFailure).isTrue()
        assertThat(preferences.current()).isEqualTo(original)
        assertThat(preferences.pendingDeviceRemovalClear).isTrue()

        val resumedGate = TestRemovedDeviceLocalClearGate()
        val deviceMarkerRetired = CompletableDeferred<Unit>()
        SyncRig(
            session = original,
            syncPreferences = preferences,
            ownedPreferences = object : SyncPreferences by preferences {
                override suspend fun clearPendingDeviceRemovalClear() {
                    preferences.clearPendingDeviceRemovalClear()
                    deviceMarkerRetired.complete(Unit)
                }
            },
            removedDeviceLocalClearGate = resumedGate,
        )
        resumedGate.firstCall.await()
        // Empty session is published before the terminal marker retires. The
        // startup first-load signal precedes both and cannot fence this phase.
        // Await the actual marker write, without virtual time or IO timing guesses.
        deviceMarkerRetired.await()
        assertThat(preferences.current()).isEqualTo(SyncSession())
        assertThat(preferences.pendingDeviceRemovalClear).isFalse()
    }

    @Test
    fun onlyExplicitDeviceRemovedClearsAfterSyncWhileGeneric401PreservesLocalState() = runTest {
        val removedGate = TestRemovedDeviceLocalClearGate()
        val removedRig = SyncRig(
            session = joinedSession("family-a"),
            removedDeviceLocalClearGate = removedGate,
        )
        removedRig.awaitStartupRecovery()
        removedRig.backend.pullFailures += RemoteDeviceRemovedException()

        val removedResult = removedRig.port.sync(SyncTrigger.PullToRefresh)

        assertThat(removedResult.exceptionOrNull())
            .isInstanceOf(RemoteDeviceRemovedException::class.java)
        assertThat(removedGate.calls).isEqualTo(1)
        assertThat(removedRig.preferences.current()).isEqualTo(SyncSession())

        val ordinary = joinedSession("family-b")
        val ordinaryGate = TestRemovedDeviceLocalClearGate()
        val ordinaryRig = SyncRig(
            session = ordinary,
            removedDeviceLocalClearGate = ordinaryGate,
        )
        ordinaryRig.awaitStartupRecovery()
        ordinaryRig.backend.pullFailures += SyncHttpException(401)

        assertThat(ordinaryRig.port.sync(SyncTrigger.PullToRefresh).isFailure).isTrue()
        assertThat(ordinaryGate.calls).isEqualTo(0)
        assertThat(ordinaryRig.preferences.current()).isEqualTo(ordinary)
        assertThat(ordinaryRig.preferences.pendingDeviceRemovalClear).isFalse()
    }

    @Test
    fun retainedIdentityWithoutCredentialsPublishesReauthRequiredNotDeviceRemoved() = runTest {
        val retained = joinedSession("family-a").copy(
            accessToken = "",
            refreshToken = "",
            accessExpiresAtEpochSeconds = 0,
            reauthRequired = true,
        )
        val rig = SyncRig(session = retained)
        rig.awaitStartupRecovery()
        assertThat(
            withTimeout(2_000) {
                rig.port.status().filter { it == SyncStatus.ReauthRequired }.first()
            },
        ).isEqualTo(SyncStatus.ReauthRequired)
        assertThat(rig.preferences.current().familyId).isEqualTo("family-a")
        assertThat(rig.preferences.current().pullCursor).isEqualTo(retained.pullCursor)
        // Credentials gone: reauth surface, not a joined sync session.
        assertThat(rig.port.sessionPresentation().first().isJoined).isFalse()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.ReauthRequired)
    }

}
