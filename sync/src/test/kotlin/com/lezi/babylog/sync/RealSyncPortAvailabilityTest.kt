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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
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
import kotlinx.coroutines.withTimeout
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
class RealSyncPortAvailabilityTest {
    @Test
    fun availabilityProbePublishesThirtySecondAnonymousHealthLease() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://192.168.1.20:8787")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            setupProbe = SetupProbe { _, trusted ->
                SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Configured)
            },
        )
        rig.clock.now = 50_000

        val result = rig.port.probeServerAvailability(AvailabilityProbeReason.Foreground)
            .getOrThrow() as FamilyServerAvailability.Available

        assertThat(result.endpointOrigin).isEqualTo(endpoint.origin)
        assertThat(result.serverVersion).isEqualTo("0.3.3")
        assertThat(result.lastHealthyAtMillis).isEqualTo(50_000)
        assertThat(result.leaseUntilMillis).isEqualTo(80_000)
        assertThat(rig.backend.anonymousHealthCalls).isEqualTo(1)
        assertThat(rig.backend.anonymousReadyCalls).isEqualTo(1)
        assertThat(rig.port.availability().first()).isEqualTo(result)
        assertThat(rig.preferences.lastServerHealthyAt.first()).isEqualTo(50_000)

        rig.clock.now = 79_999
        val leased = rig.port.probeServerAvailability(AvailabilityProbeReason.LocalChanges)
            .getOrThrow()
        assertThat(leased).isEqualTo(result)
        assertThat(rig.backend.anonymousHealthCalls).isEqualTo(1)
        assertThat(rig.backend.anonymousReadyCalls).isEqualTo(1)
    }

    @Test
    fun cancelledAvailabilityProbeRestoresStateAndExplicitProbeCanRunAgain() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a"),
            setupProbe = SetupProbe { _, trusted ->
                SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Configured)
            },
        )
        rig.backend.anonymousHealthGate = CompletableDeferred()
        val cancelledProbe = async {
            rig.port.probeServerAvailability(AvailabilityProbeReason.Foreground).getOrThrow()
        }
        rig.port.availability()
            .filter { it is FamilyServerAvailability.Checking }
            .first()

        cancelledProbe.cancel()
        assertThat(runCatching { cancelledProbe.await() }.exceptionOrNull())
            .isInstanceOf(CancellationException::class.java)
        assertThat(rig.port.availability().first())
            .isEqualTo(FamilyServerAvailability.Disabled)

        val healthCallsBeforeRecovery = rig.backend.anonymousHealthCalls
        val readyCallsBeforeRecovery = rig.backend.anonymousReadyCalls
        rig.backend.anonymousHealthGate = null
        val recovered = rig.port.probeServerAvailability(AvailabilityProbeReason.LocalChanges)
            .getOrThrow()
        assertThat(recovered).isInstanceOf(FamilyServerAvailability.Available::class.java)
        assertThat(rig.backend.anonymousHealthCalls).isEqualTo(healthCallsBeforeRecovery + 1)
        assertThat(rig.backend.anonymousReadyCalls).isEqualTo(readyCallsBeforeRecovery + 1)
    }

    @Test
    fun transportSyncFailureDemotesHealthyAvailabilityLease() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a"),
            setupProbe = SetupProbe { _, trusted ->
                SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Configured)
            },
        )
        rig.awaitStartupRecovery()
        val available = rig.port
            .probeServerAvailability(AvailabilityProbeReason.Foreground)
            .getOrThrow() as FamilyServerAvailability.Available
        rig.backend.pullFailures += IOException("connection reset")

        val syncResult = rig.port.sync(SyncTrigger.PullToRefresh)

        assertThat(syncResult.isFailure).isTrue()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Error)
        val unavailable = rig.port.availability().first()
            as FamilyServerAvailability.Unavailable
        assertThat(unavailable.reason).isEqualTo(FamilyServerUnavailableReason.Unreachable)
        assertThat(unavailable.lastHealthyAtMillis).isEqualTo(available.lastHealthyAtMillis)
        assertThat(unavailable.nextProbeAtMillis).isEqualTo(rig.clock.now + 30_000)
        assertThat(unavailable.consecutiveFailures).isEqualTo(1)
    }

    @Test
    fun networkRecoveredSyncUsesAuthenticatedHandshakeWithoutAnonymousProbe() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a"),
            setupProbe = SetupProbe { _, trusted ->
                SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Configured)
            },
        )
        rig.awaitStartupRecovery()
        rig.backend.pullStarted = CompletableDeferred()

        rig.port.notifyNetworkRecovered()
        rig.backend.pullStarted!!.await()

        assertThat(rig.backend.handshakeCalls).isEqualTo(1)
        assertThat(rig.backend.anonymousHealthCalls).isEqualTo(0)
        assertThat(rig.backend.anonymousReadyCalls).isEqualTo(0)
    }

    @Test
    fun availabilityProbePrefersTrustChangeWhenParallelHealthRequestsFail() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a"),
            setupProbe = SetupProbe { _, _ -> SetupProbeResult.Failed.CertificateChanged },
        )
        rig.backend.anonymousHealthFailure = IllegalStateException("offline")
        rig.backend.anonymousReadyFailure = SyncHttpException(503, "maintenance")

        val result = rig.port.probeServerAvailability(AvailabilityProbeReason.Foreground)
            .getOrThrow() as FamilyServerAvailability.Unavailable

        assertThat(result.reason).isEqualTo(FamilyServerUnavailableReason.TrustChanged)
        assertThat(rig.backend.anonymousHealthCalls).isEqualTo(1)
        assertThat(rig.backend.anonymousReadyCalls).isEqualTo(1)
    }

    @Test
    fun availabilityProbeClassifiesServerFailureAsMaintenance() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a"),
            setupProbe = SetupProbe { _, trusted ->
                SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Configured)
            },
        )
        rig.backend.anonymousReadyFailure = SyncHttpException(503, "maintenance")

        val result = rig.port.probeServerAvailability(AvailabilityProbeReason.Foreground)
            .getOrThrow() as FamilyServerAvailability.Unavailable

        assertThat(result.reason).isEqualTo(FamilyServerUnavailableReason.Maintenance)
        assertThat(rig.backend.anonymousHealthCalls).isEqualTo(1)
        assertThat(rig.backend.anonymousReadyCalls).isEqualTo(1)
    }

}
