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
class RealSyncPortAppUpdateTest {
    @Test
    fun checkAppUpdateReturnsNotJoinedWithoutCallingBackend() = runTest {
        val rig = SyncRig(session = SyncSession())
        rig.awaitStartupRecovery()

        val result = rig.port.checkAppUpdate().getOrThrow()

        assertThat(result).isEqualTo(AppUpdateCheckResult.NotJoined)
        assertThat(rig.backend.getAppUpdateMetadataCalls).isEqualTo(0)
        assertThat(rig.port.availableOptionalAppUpdate().first()).isNull()
    }

    @Test
    fun checkAppUpdateReturnsUpToDateWhenLocalVersionIsCurrentOrNewer() = runTest {
        val metadata = sampleAppUpdateMetadata(versionCode = 6, versionName = "0.3.0")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
        )
        rig.awaitStartupRecovery()
        rig.backend.appUpdateMetadata = metadata

        assertThat(rig.port.checkAppUpdate().getOrThrow())
            .isEqualTo(AppUpdateCheckResult.UpToDate)
        assertThat(rig.backend.getAppUpdateMetadataCalls).isEqualTo(1)
        assertThat(rig.port.availableOptionalAppUpdate().first()).isNull()
    }

    @Test
    fun checkAppUpdateReturnsOptionalUpdateWhenServerIsNewer() = runTest {
        val metadata = sampleAppUpdateMetadata(
            versionCode = 7,
            versionName = "0.3.1",
            releaseNotes = "修复同步",
            minSupportedVersionCode = 6,
        )
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
        )
        rig.awaitStartupRecovery()
        rig.backend.appUpdateMetadata = metadata

        assertThat(rig.port.checkAppUpdate().getOrThrow())
            .isEqualTo(AppUpdateCheckResult.OptionalUpdate(metadata))
        assertThat(rig.port.availableOptionalAppUpdate().first()).isEqualTo(metadata)
        assertThat(rig.port.availableForcedAppUpdate().first()).isNull()
    }

    @Test
    fun checkAppUpdateReturnsForcedUpdateWhenLocalBelowMinSupported() = runTest {
        val metadata = sampleAppUpdateMetadata(
            versionCode = 9,
            versionName = "0.4.0",
            minSupportedVersionCode = 8,
            releaseNotes = "破坏性同步合同",
        )
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
        )
        rig.awaitStartupRecovery()
        rig.backend.appUpdateMetadata = metadata

        assertThat(rig.port.checkAppUpdate().getOrThrow())
            .isEqualTo(AppUpdateCheckResult.ForcedUpdate(metadata))
        // Forced wins: optional banner must not also fire.
        assertThat(rig.port.availableOptionalAppUpdate().first()).isNull()
        assertThat(rig.port.availableForcedAppUpdate().first())
            .isEqualTo(ForcedAppUpdateState.WithPackage(metadata))
    }

    @Test
    fun forcedUpdateTakesPrecedenceOverOptionalEvenWhenLatestEqualsMinSupported() = runTest {
        val metadata = sampleAppUpdateMetadata(
            versionCode = 8,
            versionName = "0.3.2",
            minSupportedVersionCode = 8,
        )
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 7, versionName = "0.3.1"),
        )
        rig.awaitStartupRecovery()
        rig.backend.appUpdateMetadata = metadata

        assertThat(rig.port.checkAppUpdate().getOrThrow())
            .isEqualTo(AppUpdateCheckResult.ForcedUpdate(metadata))
        assertThat(rig.port.availableForcedAppUpdate().first())
            .isEqualTo(ForcedAppUpdateState.WithPackage(metadata))
        assertThat(rig.port.availableOptionalAppUpdate().first()).isNull()
    }

    @Test
    fun syncClientUpdateRequiredPublishesForcedUpdateWithoutVagueNetworkStatus() = runTest {
        val metadata = sampleAppUpdateMetadata(
            versionCode = 9,
            versionName = "0.4.0",
            minSupportedVersionCode = 8,
        )
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
        )
        rig.awaitStartupRecovery()
        // First establish Idle so we can assert the gate does not leave Error as "NAS down".
        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)

        rig.backend.appUpdateMetadata = metadata
        rig.backend.pullFailures += ClientUpdateRequiredException()

        val result = rig.port.sync(SyncTrigger.PullToRefresh)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .isInstanceOf(ClientUpdateRequiredException::class.java)
        assertThat(rig.port.availableForcedAppUpdate().first())
            .isEqualTo(ForcedAppUpdateState.WithPackage(metadata))
        assertThat(rig.port.availableOptionalAppUpdate().first()).isNull()
        // Status stays Idle so UI leads with force-upgrade, not a generic sync error.
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
    }

    @Test
    fun syncClientUpdateRequiredWithMetadataFailurePublishesForceShellNotSilentIdle() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
        )
        rig.awaitStartupRecovery()
        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
        assertThat(rig.port.availableForcedAppUpdate().first()).isNull()

        // Gate rejects authoritative sync; update metadata is temporarily unavailable.
        rig.backend.getAppUpdateMetadataFailure =
            SyncHttpException(500, """{"detail":"update store unavailable"}""")
        rig.backend.pullFailures += ClientUpdateRequiredException()

        val result = rig.port.sync(SyncTrigger.PullToRefresh)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .isInstanceOf(ClientUpdateRequiredException::class.java)
        // Must not look like "假正常": Idle status alone with no force surface.
        assertThat(rig.port.availableForcedAppUpdate().first())
            .isEqualTo(ForcedAppUpdateState.PackageUnknown)
        assertThat(rig.port.availableOptionalAppUpdate().first()).isNull()
        // Still not a vague NAS/sync Error — force shell is the recovery path.
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
    }

    @Test
    fun checkAppUpdateClientUpdateRequiredWithMetadataFailurePublishesForceShell() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
        )
        rig.awaitStartupRecovery()
        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)

        // Wire gate code on the metadata path (update routes normally skip min gate;
        // recover still maps client_update_required and must leave a force shell).
        rig.backend.getAppUpdateMetadataFailure = SyncHttpException(
            statusCode = 403,
            responseBody = """{"code":"client_update_required","detail":"too old"}""",
        )

        val result = rig.port.checkAppUpdate()
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .isInstanceOf(ClientUpdateRequiredException::class.java)
        assertThat(rig.port.availableForcedAppUpdate().first())
            .isEqualTo(ForcedAppUpdateState.PackageUnknown)
        assertThat(rig.port.availableOptionalAppUpdate().first()).isNull()
        // Manual check must never mutate SyncStatus to Error.
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
    }

    @Test
    fun syncClientUpdateRequiredWithNewerPackagePublishesInstallableEvenWhenDualTierOptional() =
        runTest {
            // Server gate says CUR; dual-tier alone would be optional (min <= local < version).
            // Ticket 01: versionCode > local after CUR must still be installable WithPackage.
            val metadata = sampleAppUpdateMetadata(
                versionCode = 9,
                versionName = "0.4.0",
                minSupportedVersionCode = 6,
            )
            val rig = SyncRig(
                session = joinedSession("family-a"),
                clientAppVersion = ClientAppVersion(versionCode = 8, versionName = "0.3.5"),
            )
            rig.awaitStartupRecovery()
            assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()

            rig.backend.appUpdateMetadata = metadata
            rig.backend.pullFailures += ClientUpdateRequiredException()

            val result = rig.port.sync(SyncTrigger.PullToRefresh)
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull())
                .isInstanceOf(ClientUpdateRequiredException::class.java)
            assertThat(rig.port.availableForcedAppUpdate().first())
                .isEqualTo(ForcedAppUpdateState.WithPackage(metadata))
            assertThat(rig.port.availableOptionalAppUpdate().first()).isNull()
            assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
        }

    /**
     * AUDIT-20260801-P1-01: force-shell "retry" shares [SyncPort.checkAppUpdate] with Settings.
     * After CUR → PackageUnknown, UpToDate-classifying metadata must not tear the shell
     * (would reintroduce 假正常 while authoritative sync is still gated).
     */
    @Test
    fun checkAppUpdateAfterPackageUnknownKeepsForceShellWhenMetadataIsUpToDate() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
        )
        rig.awaitStartupRecovery()
        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()

        // CUR + metadata failure → PackageUnknown force shell.
        rig.backend.getAppUpdateMetadataFailure =
            SyncHttpException(500, """{"detail":"update store unavailable"}""")
        rig.backend.pullFailures += ClientUpdateRequiredException()
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isFailure).isTrue()
        assertThat(rig.port.availableForcedAppUpdate().first())
            .isEqualTo(ForcedAppUpdateState.PackageUnknown)

        // Retry path: metadata returns but classifies as UpToDate (local >= versionCode and min).
        // Divergence from prior CUR — fail closed: keep PackageUnknown, no optional banner.
        // Result must stay force-honest (ForcedPackageUnknown), not bare UpToDate.
        rig.backend.getAppUpdateMetadataFailure = null
        rig.backend.appUpdateMetadata = sampleAppUpdateMetadata(
            versionCode = 6,
            versionName = "0.3.0",
            minSupportedVersionCode = 1,
        )
        val checkResult = rig.port.checkAppUpdate().getOrThrow()
        assertThat(checkResult).isEqualTo(AppUpdateCheckResult.ForcedPackageUnknown)
        assertThat(
            appUpdateUiOutcome(
                result = Result.success(checkResult),
                failureCopy = { "unused" },
            ),
        ).isEqualTo(AppUpdateUiOutcome.ForcedUpdatePackageUnknown)

        assertThat(rig.port.availableForcedAppUpdate().first())
            .isEqualTo(ForcedAppUpdateState.PackageUnknown)
        assertThat(rig.port.availableOptionalAppUpdate().first()).isNull()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
    }

    @Test
    fun reauthRetryCheckKeepsPackageUnknownForceShell() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
        )
        rig.awaitStartupRecovery()
        rig.backend.getAppUpdateMetadataFailure =
            SyncHttpException(500, """{"detail":"update store unavailable"}""")
        rig.backend.pullFailures += ClientUpdateRequiredException()
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isFailure).isTrue()
        assertThat(rig.port.availableForcedAppUpdate().first())
            .isEqualTo(ForcedAppUpdateState.PackageUnknown)

        rig.preferences.clearDeviceCredentialsForReauth()
        runCurrent()
        val result = rig.port.checkAppUpdate().getOrThrow()

        assertThat(result).isEqualTo(AppUpdateCheckResult.ForcedPackageUnknown)
        assertThat(rig.port.availableForcedAppUpdate().first())
            .isEqualTo(ForcedAppUpdateState.PackageUnknown)
        assertThat(rig.port.availableOptionalAppUpdate().first()).isNull()
        assertThat(
            withTimeout(1_000L) {
                rig.port.status().first { it == SyncStatus.ReauthRequired }
            },
        ).isEqualTo(SyncStatus.ReauthRequired)
    }

    @Test
    fun checkAppUpdateAfterPackageUnknownPromotesNewerPackageToInstallableForced() = runTest {
        val optionalMetadata = sampleAppUpdateMetadata(
            versionCode = 9,
            versionName = "0.4.0",
            minSupportedVersionCode = 1,
        )
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
        )
        rig.awaitStartupRecovery()
        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()

        rig.backend.getAppUpdateMetadataFailure =
            SyncHttpException(500, """{"detail":"update store unavailable"}""")
        rig.backend.pullFailures += ClientUpdateRequiredException()
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isFailure).isTrue()
        assertThat(rig.port.availableForcedAppUpdate().first())
            .isEqualTo(ForcedAppUpdateState.PackageUnknown)

        // Optional-classifying but versionCode > local under force shell → installable Forced,
        // never optional banner / dismissible "稍后".
        rig.backend.getAppUpdateMetadataFailure = null
        rig.backend.appUpdateMetadata = optionalMetadata
        val checkResult = rig.port.checkAppUpdate().getOrThrow()
        assertThat(checkResult).isEqualTo(AppUpdateCheckResult.ForcedUpdate(optionalMetadata))
        assertThat(
            appUpdateUiOutcome(
                result = Result.success(checkResult),
                failureCopy = { "unused" },
            ),
        ).isEqualTo(AppUpdateUiOutcome.ForcedUpdate(optionalMetadata))

        assertThat(rig.port.availableForcedAppUpdate().first())
            .isEqualTo(ForcedAppUpdateState.WithPackage(optionalMetadata))
        assertThat(rig.port.availableOptionalAppUpdate().first()).isNull()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
    }

    @Test
    fun checkAppUpdateUnderForcePromotesNewerNonForcedMetadataToInstallablePackage() = runTest {
        val forcedPackage = sampleAppUpdateMetadata(
            versionCode = 9,
            versionName = "0.4.0",
            minSupportedVersionCode = 8,
        )
        val newerInstallable = sampleAppUpdateMetadata(
            versionCode = 10,
            versionName = "0.4.1",
            minSupportedVersionCode = 1,
        )
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
        )
        rig.awaitStartupRecovery()
        rig.backend.appUpdateMetadata = forcedPackage
        rig.backend.pullFailures += ClientUpdateRequiredException()
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isFailure).isTrue()
        assertThat(rig.port.availableForcedAppUpdate().first())
            .isEqualTo(ForcedAppUpdateState.WithPackage(forcedPackage))

        // versionCode > local under force shell → installable Forced of the newer package
        // (not optional banner, not demote to bare UpToDate).
        rig.backend.appUpdateMetadata = newerInstallable
        val checkResult = rig.port.checkAppUpdate().getOrThrow()
        assertThat(checkResult).isEqualTo(AppUpdateCheckResult.ForcedUpdate(newerInstallable))
        assertThat(
            appUpdateUiOutcome(
                result = Result.success(checkResult),
                failureCopy = { "unused" },
            ),
        ).isEqualTo(AppUpdateUiOutcome.ForcedUpdate(newerInstallable))

        assertThat(rig.port.availableForcedAppUpdate().first())
            .isEqualTo(ForcedAppUpdateState.WithPackage(newerInstallable))
        assertThat(rig.port.availableOptionalAppUpdate().first()).isNull()
    }

    @Test
    fun checkAppUpdatePreservesWithPackageWhenMetadataIsNotNewer() = runTest {
        val forcedPackage = sampleAppUpdateMetadata(
            versionCode = 9,
            versionName = "0.4.0",
            minSupportedVersionCode = 8,
        )
        val notNewer = sampleAppUpdateMetadata(
            versionCode = 6,
            versionName = "0.3.0",
            minSupportedVersionCode = 1,
        )
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
        )
        rig.awaitStartupRecovery()
        rig.backend.appUpdateMetadata = forcedPackage
        rig.backend.pullFailures += ClientUpdateRequiredException()
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isFailure).isTrue()
        assertThat(rig.port.availableForcedAppUpdate().first())
            .isEqualTo(ForcedAppUpdateState.WithPackage(forcedPackage))

        // UpToDate-classifying metadata must not tear down the last installable package.
        rig.backend.appUpdateMetadata = notNewer
        val checkResult = rig.port.checkAppUpdate().getOrThrow()
        assertThat(checkResult).isEqualTo(AppUpdateCheckResult.ForcedUpdate(forcedPackage))
        assertThat(rig.port.availableForcedAppUpdate().first())
            .isEqualTo(ForcedAppUpdateState.WithPackage(forcedPackage))
        assertThat(rig.port.availableOptionalAppUpdate().first()).isNull()
    }

    /**
     * Failed non-CUR sync must not piggyback-discover with preserve=false and demote
     * PackageUnknown/WithPackage via temporary non-Forced metadata.
     */
    @Test
    fun failedPullToRefreshDoesNotDemoteForceShellViaPiggybackDiscover() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
        )
        rig.awaitStartupRecovery()
        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()

        // CUR + metadata failure → PackageUnknown.
        rig.backend.getAppUpdateMetadataFailure =
            SyncHttpException(500, """{"detail":"update store unavailable"}""")
        rig.backend.pullFailures += ClientUpdateRequiredException()
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isFailure).isTrue()
        assertThat(rig.port.availableForcedAppUpdate().first())
            .isEqualTo(ForcedAppUpdateState.PackageUnknown)

        // Later failed pull (network/5xx, not CUR) with optional-classifying metadata
        // must keep PackageUnknown and must not publish optional banner.
        rig.backend.getAppUpdateMetadataFailure = null
        rig.backend.appUpdateMetadata = sampleAppUpdateMetadata(
            versionCode = 9,
            versionName = "0.4.0",
            minSupportedVersionCode = 1,
        )
        rig.backend.pullFailures += SyncHttpException(
            503,
            """{"detail":"temporary unavailable"}""",
        )
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isFailure).isTrue()

        assertThat(rig.port.availableForcedAppUpdate().first())
            .isEqualTo(ForcedAppUpdateState.PackageUnknown)
        assertThat(rig.port.availableOptionalAppUpdate().first()).isNull()
    }

    @Test
    fun failedPullToRefreshDoesNotDemoteWithPackageViaPiggybackDiscover() = runTest {
        val forcedPackage = sampleAppUpdateMetadata(
            versionCode = 9,
            versionName = "0.4.0",
            minSupportedVersionCode = 8,
        )
        val nonForcedMetadata = sampleAppUpdateMetadata(
            versionCode = 10,
            versionName = "0.4.1",
            minSupportedVersionCode = 1,
        )
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
        )
        rig.awaitStartupRecovery()
        rig.backend.appUpdateMetadata = forcedPackage
        rig.backend.pullFailures += ClientUpdateRequiredException()
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isFailure).isTrue()
        assertThat(rig.port.availableForcedAppUpdate().first())
            .isEqualTo(ForcedAppUpdateState.WithPackage(forcedPackage))

        // Failed non-CUR sync must not piggyback-discover with preserve=false;
        // shell stays on the last installable forced package.
        rig.backend.appUpdateMetadata = nonForcedMetadata
        rig.backend.pullFailures += SyncHttpException(
            503,
            """{"detail":"temporary unavailable"}""",
        )
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isFailure).isTrue()

        assertThat(rig.port.availableForcedAppUpdate().first())
            .isEqualTo(ForcedAppUpdateState.WithPackage(forcedPackage))
        assertThat(rig.port.availableOptionalAppUpdate().first()).isNull()
    }

    @Test
    fun checkAppUpdateReplacesWithPackageWhenNewForcedMetadataArrives() = runTest {
        val firstForced = sampleAppUpdateMetadata(
            versionCode = 9,
            versionName = "0.4.0",
            minSupportedVersionCode = 8,
        )
        val newerForced = sampleAppUpdateMetadata(
            versionCode = 11,
            versionName = "0.5.0",
            minSupportedVersionCode = 10,
            releaseNotes = "更新强制包",
        )
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
        )
        rig.awaitStartupRecovery()
        rig.backend.appUpdateMetadata = firstForced
        rig.backend.pullFailures += ClientUpdateRequiredException()
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isFailure).isTrue()
        assertThat(rig.port.availableForcedAppUpdate().first())
            .isEqualTo(ForcedAppUpdateState.WithPackage(firstForced))

        rig.backend.appUpdateMetadata = newerForced
        assertThat(rig.port.checkAppUpdate().getOrThrow())
            .isEqualTo(AppUpdateCheckResult.ForcedUpdate(newerForced))
        assertThat(rig.port.availableForcedAppUpdate().first())
            .isEqualTo(ForcedAppUpdateState.WithPackage(newerForced))
        assertThat(rig.port.availableOptionalAppUpdate().first()).isNull()
    }

    @Test
    fun checkAppUpdatePreservesWithPackageOnTemporaryMetadataFailure() = runTest {
        val forcedPackage = sampleAppUpdateMetadata(
            versionCode = 9,
            versionName = "0.4.0",
            minSupportedVersionCode = 8,
        )
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
        )
        rig.awaitStartupRecovery()
        rig.backend.appUpdateMetadata = forcedPackage
        rig.backend.pullFailures += ClientUpdateRequiredException()
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isFailure).isTrue()
        assertThat(rig.port.availableForcedAppUpdate().first())
            .isEqualTo(ForcedAppUpdateState.WithPackage(forcedPackage))

        rig.backend.getAppUpdateMetadataFailure =
            SyncHttpException(500, """{"detail":"update store unavailable"}""")
        assertThat(rig.port.checkAppUpdate().isFailure).isTrue()

        assertThat(rig.port.availableForcedAppUpdate().first())
            .isEqualTo(ForcedAppUpdateState.WithPackage(forcedPackage))
        assertThat(rig.port.availableOptionalAppUpdate().first()).isNull()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
    }

    @Test
    fun familySyncErrorMapsClientUpdateRequiredOutOfGenericHttpFailure() {
        val mapped = familySyncError(
            ClientUpdateRequiredException(),
            fallback = "网络错误",
        )
        assertThat(mapped).isEqualTo("需要更新乐记后才能继续同步家庭数据")

        val wire = familySyncError(
            SyncHttpException(
                statusCode = 403,
                responseBody = """{"code":"client_update_required","detail":"too old"}""",
            ),
            fallback = "网络错误",
        )
        assertThat(wire).isEqualTo("需要更新乐记后才能继续同步家庭数据")
    }

    @Test
    fun foregroundSyncDiscoversForcedAppUpdateWhenLocalBelowMinSupported() = runTest {
        val metadata = sampleAppUpdateMetadata(
            versionCode = 9,
            versionName = "0.4.0",
            minSupportedVersionCode = 8,
        )
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
        )
        rig.awaitStartupRecovery()
        rig.backend.appUpdateMetadata = metadata

        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()

        assertThat(rig.backend.getAppUpdateMetadataCalls).isEqualTo(1)
        assertThat(rig.port.availableForcedAppUpdate().first())
            .isEqualTo(ForcedAppUpdateState.WithPackage(metadata))
        assertThat(rig.port.availableOptionalAppUpdate().first()).isNull()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
    }

    @Test
    fun foregroundSyncDiscoversOptionalAppUpdateWithoutFcmOrColdStartPoller() = runTest {
        val metadata = sampleAppUpdateMetadata(versionCode = 8, versionName = "0.3.2")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
        )
        rig.awaitStartupRecovery()
        rig.backend.appUpdateMetadata = metadata

        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()

        assertThat(rig.backend.getAppUpdateMetadataCalls).isEqualTo(1)
        assertThat(rig.port.availableOptionalAppUpdate().first()).isEqualTo(metadata)
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
    }

    @Test
    fun localWriteSyncDoesNotPiggybackAppUpdateCheck() = runTest {
        val metadata = sampleAppUpdateMetadata(versionCode = 8, versionName = "0.3.2")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
        )
        rig.awaitStartupRecovery()
        rig.backend.appUpdateMetadata = metadata

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        assertThat(rig.backend.getAppUpdateMetadataCalls).isEqualTo(0)
        assertThat(rig.port.availableOptionalAppUpdate().first()).isNull()
    }

    @Test
    fun dismissOptionalAppUpdateSuppressesBannerForSameVersionInProcessSession() = runTest {
        val metadata = sampleAppUpdateMetadata(versionCode = 9, versionName = "0.4.0")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
        )
        rig.awaitStartupRecovery()
        rig.backend.appUpdateMetadata = metadata

        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()
        assertThat(rig.port.availableOptionalAppUpdate().first()).isEqualTo(metadata)

        rig.port.dismissOptionalAppUpdate(metadata.versionCode)
        assertThat(rig.port.availableOptionalAppUpdate().first()).isNull()

        // Second handshake must not re-show the same version after "稍后".
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()
        assertThat(rig.backend.getAppUpdateMetadataCalls).isEqualTo(2)
        assertThat(rig.port.availableOptionalAppUpdate().first()).isNull()

        // Explicit manual check still reports optional update for the confirm dialog path.
        assertThat(rig.port.checkAppUpdate().getOrThrow())
            .isEqualTo(AppUpdateCheckResult.OptionalUpdate(metadata))
        assertThat(rig.port.availableOptionalAppUpdate().first()).isNull()
    }

    @Test
    fun optionalAppUpdateCheckFailureDoesNotPoisonSyncStatus() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
        )
        rig.awaitStartupRecovery()
        // Successful sync first so status is Idle.
        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)

        rig.backend.getAppUpdateMetadataFailure =
            SyncHttpException(500, """{"detail":"update store unavailable"}""")
        val check = rig.port.checkAppUpdate()
        assertThat(check.isFailure).isTrue()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
        assertThat(rig.port.availableOptionalAppUpdate().first()).isNull()

        // Handshake piggyback also swallows failures without marking Error.
        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
    }

    @Test
    fun unjoinedForegroundSyncDoesNotDiscoverOptionalAppUpdate() = runTest {
        val rig = SyncRig(session = SyncSession())
        rig.awaitStartupRecovery()
        rig.backend.appUpdateMetadata =
            sampleAppUpdateMetadata(versionCode = 99, versionName = "9.9.9")

        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()

        assertThat(rig.backend.getAppUpdateMetadataCalls).isEqualTo(0)
        assertThat(rig.port.availableOptionalAppUpdate().first()).isNull()
    }

    @Test
    fun upToDateHandshakeClearsOptionalAppUpdateBanner() = runTest {
        val newer = sampleAppUpdateMetadata(versionCode = 7, versionName = "0.3.1")
        val current = sampleAppUpdateMetadata(versionCode = 6, versionName = "0.3.0")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
        )
        rig.awaitStartupRecovery()
        rig.backend.appUpdateMetadata = newer
        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()
        assertThat(rig.port.availableOptionalAppUpdate().first()).isEqualTo(newer)

        rig.backend.appUpdateMetadata = current
        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()
        assertThat(rig.port.availableOptionalAppUpdate().first()).isNull()
    }

    @Test
    fun installAvailableAppUpdateDownloadsVerifiesAndStartsInstaller() = runTest {
        val apkBytes = "lezi-release-apk-bytes".toByteArray(Charsets.UTF_8)
        val metadata = sampleAppUpdateMetadata(
            versionCode = 7,
            versionName = "0.3.1",
            sha256 = sha256Hex(apkBytes),
        )
        val installer = RecordingAppUpdateInstaller()
        val cacheDir = createTempDir(prefix = "lezi-app-update-ok")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
            appUpdateInstaller = installer,
            appUpdateCacheDir = cacheDir,
        )
        rig.awaitStartupRecovery()
        rig.backend.appUpdateApkBytes = apkBytes

        val result = rig.port.installAvailableAppUpdate(metadata).getOrThrow()

        assertThat(result).isEqualTo(AppUpdateInstallResult.SessionStarted)
        assertThat(rig.backend.downloadAppUpdateApkCalls).isEqualTo(1)
        assertThat(installer.installCalls).hasSize(1)
        assertThat(installer.installCalls.single().expectedPackageName)
            .isEqualTo("com.lezi.babylog")
        assertThat(installer.installCalls.single().fileExistedAtCall).isTrue()
        assertThat(appUpdateStagingDir(cacheDir).exists()).isFalse()
        assertThat(appUpdateStagingApk(cacheDir).exists()).isFalse()
    }

    @Test
    fun forceShellRetainedThroughReauthAndInstallProceedsAfterRejoin() = runTest {
        val apkBytes = "lezi-force-reauth-apk".toByteArray(Charsets.UTF_8)
        val metadata = sampleAppUpdateMetadata(
            versionCode = 9,
            versionName = "0.4.0",
            minSupportedVersionCode = 8,
            sha256 = sha256Hex(apkBytes),
        )
        val installer = RecordingAppUpdateInstaller()
        val cacheDir = createTempDir(prefix = "lezi-force-reauth-install")
        val original = joinedSession("family-a")
        val rig = SyncRig(
            session = original,
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
            appUpdateInstaller = installer,
            apkIdentityReader = FakeAppUpdateApkIdentityReader(versionCode = 9),
            appUpdateCacheDir = cacheDir,
        )
        rig.awaitStartupRecovery()
        rig.backend.appUpdateMetadata = metadata
        rig.backend.appUpdateApkBytes = apkBytes
        rig.backend.pullFailures += ClientUpdateRequiredException()
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isFailure).isTrue()
        assertThat(rig.port.availableForcedAppUpdate().first())
            .isEqualTo(ForcedAppUpdateState.WithPackage(metadata))

        // Session recovery under force shell: credentials cleared, force retained.
        rig.preferences.clearDeviceCredentialsForReauth()
        runCurrent()
        assertThat(rig.port.availableForcedAppUpdate().first())
            .isEqualTo(ForcedAppUpdateState.WithPackage(metadata))
        assertThat(rig.port.session().first().isJoined).isFalse()
        assertThat(
            forceShellNeedsSessionRecovery(
                isJoined = false,
                reauthRequired = true,
                retainsFamilyIdentity = true,
            ),
        ).isTrue()
        // Install is blocked until joined again.
        assertThat(rig.port.installAvailableAppUpdate(metadata).isFailure).isTrue()

        // Rejoin with same family identity; force shell still present.
        rig.preferences.saveSession(
            original.copy(
                accessToken = "access-after-reauth",
                refreshToken = "refresh-after-reauth",
                reauthRequired = false,
            ),
        )
        runCurrent()
        assertThat(rig.port.session().first().isJoined).isTrue()
        assertThat(rig.port.availableForcedAppUpdate().first())
            .isEqualTo(ForcedAppUpdateState.WithPackage(metadata))

        val result = rig.port.installAvailableAppUpdate(metadata).getOrThrow()
        assertThat(result).isEqualTo(AppUpdateInstallResult.SessionStarted)
        assertThat(rig.backend.downloadAppUpdateApkCalls).isEqualTo(1)
        assertThat(installer.installCalls).hasSize(1)
    }

    @Test
    fun installAvailableAppUpdateRejectsSha256MismatchWithoutInstalling() = runTest {
        val metadata = sampleAppUpdateMetadata(
            versionCode = 7,
            versionName = "0.3.1",
            sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        )
        val installer = RecordingAppUpdateInstaller()
        val cacheDir = createTempDir(prefix = "lezi-app-update-bad")
        // Leave a stale staging file to prove cleanup on failure.
        appUpdateStagingDir(cacheDir).mkdirs()
        appUpdateStagingApk(cacheDir).writeText("stale")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
            appUpdateInstaller = installer,
            appUpdateCacheDir = cacheDir,
        )
        rig.awaitStartupRecovery()
        // Successful sync first so status is Idle (install failure must not poison it).
        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
        rig.backend.appUpdateApkBytes = "tampered-or-corrupt-apk".toByteArray()

        val failure = rig.port.installAvailableAppUpdate(metadata).exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure!!.message).contains("校验失败")
        assertThat(installer.installCalls).isEmpty()
        assertThat(appUpdateStagingApk(cacheDir).exists()).isFalse()
        // Update install/verify failures surface only to update UI — not SyncStatus.Error.
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
    }

    @Test
    fun installAvailableAppUpdateRejectsArchivePackageNameMismatchWithoutInstalling() = runTest {
        val apkBytes = "lezi-release-apk-bytes".toByteArray(Charsets.UTF_8)
        val metadata = sampleAppUpdateMetadata(
            versionCode = 7,
            versionName = "0.3.1",
            sha256 = sha256Hex(apkBytes),
        )
        val installer = RecordingAppUpdateInstaller()
        val identityReader = FakeAppUpdateApkIdentityReader(
            packageName = "com.evil.other",
            versionCode = 7,
        )
        val cacheDir = createTempDir(prefix = "lezi-app-update-pkg")
        appUpdateStagingDir(cacheDir).mkdirs()
        appUpdateStagingApk(cacheDir).writeText("stale")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
            appUpdateInstaller = installer,
            apkIdentityReader = identityReader,
            appUpdateCacheDir = cacheDir,
        )
        rig.awaitStartupRecovery()
        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
        rig.backend.appUpdateApkBytes = apkBytes

        val failure = rig.port.installAvailableAppUpdate(metadata).exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure!!.message).isEqualTo(APP_UPDATE_PACKAGE_INVALID_MESSAGE)
        assertThat(installer.installCalls).isEmpty()
        assertThat(appUpdateStagingApk(cacheDir).exists()).isFalse()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
    }

    @Test
    fun installAvailableAppUpdateRejectsMetadataPackageNotEqualLocalApplicationId() = runTest {
        val metadata = sampleAppUpdateMetadata(versionCode = 7, versionName = "0.3.1")
            .copy(packageName = "com.evil.other")
        val installer = RecordingAppUpdateInstaller()
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
            appUpdateInstaller = installer,
        )
        rig.awaitStartupRecovery()
        rig.backend.appUpdateApkBytes = byteArrayOf(1, 2, 3)

        val failure = rig.port.installAvailableAppUpdate(metadata).exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure!!.message).isEqualTo(APP_UPDATE_METADATA_PACKAGE_MISMATCH_MESSAGE)
        assertThat(rig.backend.downloadAppUpdateApkCalls).isEqualTo(0)
        assertThat(installer.installCalls).isEmpty()
    }

    @Test
    fun checkAppUpdateRejectsMetadataPackageNotEqualLocalApplicationId() = runTest {
        val metadata = sampleAppUpdateMetadata(versionCode = 8, versionName = "0.4.0")
            .copy(packageName = "com.evil.other")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
        )
        rig.awaitStartupRecovery()
        rig.backend.appUpdateMetadata = metadata

        val failure = rig.port.checkAppUpdate().exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure!!.message).isEqualTo(APP_UPDATE_METADATA_PACKAGE_MISMATCH_MESSAGE)
        assertThat(rig.port.availableOptionalAppUpdate().first()).isNull()
        assertThat(rig.port.availableForcedAppUpdate().first()).isNull()
    }

    @Test
    fun installAvailableAppUpdateRejectsSigningCertMismatchWithoutInstalling() = runTest {
        val apkBytes = "lezi-release-apk-bytes".toByteArray(Charsets.UTF_8)
        val metadata = sampleAppUpdateMetadata(
            versionCode = 7,
            versionName = "0.3.1",
            sha256 = sha256Hex(apkBytes),
        )
        val installer = RecordingAppUpdateInstaller()
        val identityReader = FakeAppUpdateApkIdentityReader(
            archiveCerts = setOf("bb".repeat(32)),
            installedCerts = setOf(TEST_APP_UPDATE_CERT_SHA256),
        )
        val cacheDir = createTempDir(prefix = "lezi-app-update-sig")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
            appUpdateInstaller = installer,
            apkIdentityReader = identityReader,
            appUpdateCacheDir = cacheDir,
        )
        rig.awaitStartupRecovery()
        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()
        rig.backend.appUpdateApkBytes = apkBytes

        val failure = rig.port.installAvailableAppUpdate(metadata).exceptionOrNull()

        assertThat(failure!!.message).isEqualTo(APP_UPDATE_PACKAGE_INVALID_MESSAGE)
        assertThat(installer.installCalls).isEmpty()
        assertThat(appUpdateStagingApk(cacheDir).exists()).isFalse()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
    }

    @Test
    fun installAvailableAppUpdateRejectsArchiveVersionMismatchWithoutInstalling() = runTest {
        val apkBytes = "lezi-release-apk-bytes".toByteArray(Charsets.UTF_8)
        val metadata = sampleAppUpdateMetadata(
            versionCode = 7,
            versionName = "0.3.1",
            sha256 = sha256Hex(apkBytes),
        )
        val installer = RecordingAppUpdateInstaller()
        val identityReader = FakeAppUpdateApkIdentityReader(
            packageName = "com.lezi.babylog",
            versionCode = 9,
        )
        val cacheDir = createTempDir(prefix = "lezi-app-update-ver")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
            appUpdateInstaller = installer,
            apkIdentityReader = identityReader,
            appUpdateCacheDir = cacheDir,
        )
        rig.awaitStartupRecovery()
        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()
        rig.backend.appUpdateApkBytes = apkBytes

        val failure = rig.port.installAvailableAppUpdate(metadata).exceptionOrNull()

        assertThat(failure!!.message).isEqualTo(APP_UPDATE_PACKAGE_INVALID_MESSAGE)
        assertThat(installer.installCalls).isEmpty()
        assertThat(appUpdateStagingApk(cacheDir).exists()).isFalse()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
    }

    @Test
    fun installAvailableAppUpdateRejectsUnreadableArchiveWithoutInstalling() = runTest {
        val apkBytes = "lezi-release-apk-bytes".toByteArray(Charsets.UTF_8)
        val metadata = sampleAppUpdateMetadata(
            versionCode = 7,
            versionName = "0.3.1",
            sha256 = sha256Hex(apkBytes),
        )
        val installer = RecordingAppUpdateInstaller()
        val identityReader = FakeAppUpdateApkIdentityReader(unreadable = true)
        val cacheDir = createTempDir(prefix = "lezi-app-update-unreadable")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
            appUpdateInstaller = installer,
            apkIdentityReader = identityReader,
            appUpdateCacheDir = cacheDir,
        )
        rig.awaitStartupRecovery()
        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()
        rig.backend.appUpdateApkBytes = apkBytes

        val failure = rig.port.installAvailableAppUpdate(metadata).exceptionOrNull()

        assertThat(failure!!.message).isEqualTo(APP_UPDATE_PACKAGE_INVALID_MESSAGE)
        assertThat(installer.installCalls).isEmpty()
        assertThat(appUpdateStagingApk(cacheDir).exists()).isFalse()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
    }

    @Test
    fun installAvailableAppUpdateReportsMissingInstallPermissionWithoutDownload() = runTest {
        val metadata = sampleAppUpdateMetadata(versionCode = 7, versionName = "0.3.1")
        val installer = RecordingAppUpdateInstaller(canInstall = false)
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
            appUpdateInstaller = installer,
        )
        rig.awaitStartupRecovery()
        rig.backend.appUpdateApkBytes = byteArrayOf(1, 2, 3)
        rig.backend.appUpdateMetadata = metadata
        assertThat(rig.port.checkAppUpdate().getOrThrow())
            .isEqualTo(AppUpdateCheckResult.OptionalUpdate(metadata))

        val result = rig.port.installAvailableAppUpdate(metadata).getOrThrow()

        assertThat(result).isEqualTo(AppUpdateInstallResult.RequiresInstallPermission)
        assertThat(rig.backend.downloadAppUpdateApkCalls).isEqualTo(0)
        assertThat(installer.installCalls).isEmpty()
        assertThat(rig.port.availableOptionalAppUpdate().first()).isEqualTo(metadata)
    }

    @Test
    fun installAvailableAppUpdateDownloadFailureDoesNotPoisonSyncStatus() = runTest {
        val metadata = sampleAppUpdateMetadata(versionCode = 7, versionName = "0.3.1")
        val installer = RecordingAppUpdateInstaller()
        val cacheDir = createTempDir(prefix = "lezi-app-update-dl-fail")
        appUpdateStagingDir(cacheDir).mkdirs()
        appUpdateStagingApk(cacheDir).writeText("stale")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
            appUpdateInstaller = installer,
            appUpdateCacheDir = cacheDir,
        )
        rig.awaitStartupRecovery()
        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
        rig.backend.downloadAppUpdateApkFailure =
            SyncHttpException(503, """{"detail":"update store unavailable"}""")

        val failure = rig.port.installAvailableAppUpdate(metadata).exceptionOrNull()

        assertThat(failure).isInstanceOf(SyncHttpException::class.java)
        assertThat(installer.installCalls).isEmpty()
        assertThat(appUpdateStagingApk(cacheDir).exists()).isFalse()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
    }

    @Test
    fun installAvailableAppUpdateInstallerFailureDoesNotPoisonSyncStatus() = runTest {
        val apkBytes = "lezi-release-apk-bytes".toByteArray(Charsets.UTF_8)
        val metadata = sampleAppUpdateMetadata(
            versionCode = 7,
            versionName = "0.3.1",
            sha256 = sha256Hex(apkBytes),
        )
        val installer = RecordingAppUpdateInstaller(
            installFailure = IllegalStateException("PackageInstaller session failed"),
        )
        val cacheDir = createTempDir(prefix = "lezi-app-update-pi-fail")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
            appUpdateInstaller = installer,
            appUpdateCacheDir = cacheDir,
        )
        rig.awaitStartupRecovery()
        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
        rig.backend.appUpdateApkBytes = apkBytes

        val failure = rig.port.installAvailableAppUpdate(metadata).exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure!!.message).contains("PackageInstaller")
        assertThat(installer.installCalls).hasSize(1)
        assertThat(appUpdateStagingApk(cacheDir).exists()).isFalse()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
    }

    @Test
    fun installAvailableAppUpdateGateBlockedDoesNotMutateSyncStatus() = runTest {
        val metadata = sampleAppUpdateMetadata(versionCode = 7, versionName = "0.3.1")
        val installer = RecordingAppUpdateInstaller()
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
            appUpdateInstaller = installer,
        )
        rig.awaitStartupRecovery()
        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
        // Background gate blocks install; must not flip joined chrome to Disabled/Error.
        rig.foreground.setForeground(false)

        val failure = rig.port.installAvailableAppUpdate(metadata).exceptionOrNull()

        assertThat(failure).isInstanceOf(ForegroundSyncBlockedException::class.java)
        assertThat(rig.backend.downloadAppUpdateApkCalls).isEqualTo(0)
        assertThat(installer.installCalls).isEmpty()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
    }

    @Test
    fun installAvailableAppUpdateRejectsConcurrentSecondInstallWhileBusy() = runTest {
        val apkBytes = "lezi-release-apk-bytes".toByteArray(Charsets.UTF_8)
        val metadata = sampleAppUpdateMetadata(
            versionCode = 7,
            versionName = "0.3.1",
            sha256 = sha256Hex(apkBytes),
        )
        val installer = RecordingAppUpdateInstaller()
        val cacheDir = createTempDir(prefix = "lezi-app-update-busy")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
            appUpdateInstaller = installer,
            appUpdateCacheDir = cacheDir,
        )
        rig.awaitStartupRecovery()
        rig.backend.appUpdateApkBytes = apkBytes
        val downloadStarted = CompletableDeferred<Unit>()
        val releaseDownload = CompletableDeferred<Unit>()
        rig.backend.downloadAppUpdateApkStarted = downloadStarted
        rig.backend.releaseDownloadAppUpdateApk = releaseDownload

        val first = async { rig.port.installAvailableAppUpdate(metadata) }
        downloadStarted.await()

        val second = rig.port.installAvailableAppUpdate(metadata)
        assertThat(second.isFailure).isTrue()
        assertThat(second.exceptionOrNull())
            .isInstanceOf(AppUpdateInstallInProgressException::class.java)
        assertThat(second.exceptionOrNull()!!.message)
            .isEqualTo(APP_UPDATE_INSTALL_IN_PROGRESS_MESSAGE)
        assertThat(rig.backend.downloadAppUpdateApkCalls).isEqualTo(1)
        assertThat(installer.installCalls).isEmpty()

        releaseDownload.complete(Unit)
        assertThat(first.await().getOrThrow()).isEqualTo(AppUpdateInstallResult.SessionStarted)
        assertThat(installer.installCalls).hasSize(1)
        assertThat(appUpdateStagingApk(cacheDir).exists()).isFalse()
    }

    @Test
    fun installAvailableAppUpdateBusyRejectDoesNotDismissOptionalBanner() = runTest {
        val apkBytes = "lezi-release-apk-bytes".toByteArray(Charsets.UTF_8)
        val heldMetadata = sampleAppUpdateMetadata(
            versionCode = 7,
            versionName = "0.3.1",
            sha256 = sha256Hex(apkBytes),
        )
        // Different package version so a busy second call would wrongly dismiss if it
        // still ran dismissOptionalAppUpdate before tryLock.
        val bannerMetadata = sampleAppUpdateMetadata(
            versionCode = 8,
            versionName = "0.4.0",
            sha256 = sha256Hex(apkBytes),
        )
        val installer = RecordingAppUpdateInstaller()
        val cacheDir = createTempDir(prefix = "lezi-app-update-busy-dismiss")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
            appUpdateInstaller = installer,
            appUpdateCacheDir = cacheDir,
        )
        rig.awaitStartupRecovery()
        rig.backend.appUpdateApkBytes = apkBytes
        rig.backend.appUpdateMetadata = bannerMetadata
        assertThat(rig.port.checkAppUpdate().getOrThrow())
            .isEqualTo(AppUpdateCheckResult.OptionalUpdate(bannerMetadata))
        assertThat(rig.port.availableOptionalAppUpdate().first()).isEqualTo(bannerMetadata)

        val downloadStarted = CompletableDeferred<Unit>()
        val releaseDownload = CompletableDeferred<Unit>()
        rig.backend.downloadAppUpdateApkStarted = downloadStarted
        rig.backend.releaseDownloadAppUpdateApk = releaseDownload

        val first = async { rig.port.installAvailableAppUpdate(heldMetadata) }
        downloadStarted.await()
        // First install dismisses only its own versionCode (7); banner for 8 must remain
        // until a successful install for 8 owns the pipeline.
        assertThat(rig.port.availableOptionalAppUpdate().first()).isEqualTo(bannerMetadata)

        val busy = rig.port.installAvailableAppUpdate(bannerMetadata)
        assertThat(busy.exceptionOrNull())
            .isInstanceOf(AppUpdateInstallInProgressException::class.java)
        assertThat(rig.port.availableOptionalAppUpdate().first()).isEqualTo(bannerMetadata)

        releaseDownload.complete(Unit)
        assertThat(first.await().getOrThrow()).isEqualTo(AppUpdateInstallResult.SessionStarted)
        assertThat(rig.port.availableOptionalAppUpdate().first()).isEqualTo(bannerMetadata)
    }

    @Test
    fun checkAppUpdateDoesNotClearStagingWhileInstallInProgress() = runTest {
        val apkBytes = "lezi-release-apk-bytes".toByteArray(Charsets.UTF_8)
        val metadata = sampleAppUpdateMetadata(
            versionCode = 7,
            versionName = "0.3.1",
            sha256 = sha256Hex(apkBytes),
        )
        val installer = RecordingAppUpdateInstaller()
        val cacheDir = createTempDir(prefix = "lezi-app-update-race")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            clientAppVersion = ClientAppVersion(versionCode = 6, versionName = "0.3.0"),
            appUpdateInstaller = installer,
            appUpdateCacheDir = cacheDir,
        )
        rig.awaitStartupRecovery()
        rig.backend.appUpdateApkBytes = apkBytes
        rig.backend.appUpdateMetadata = metadata
        val downloadStarted = CompletableDeferred<Unit>()
        val releaseDownload = CompletableDeferred<Unit>()
        rig.backend.downloadAppUpdateApkStarted = downloadStarted
        rig.backend.releaseDownloadAppUpdateApk = releaseDownload

        val installing = async { rig.port.installAvailableAppUpdate(metadata) }
        downloadStarted.await()
        // Mid-install partial staging must survive opportunistic check cleanup.
        appUpdateStagingDir(cacheDir).mkdirs()
        appUpdateStagingApk(cacheDir).writeText("partial-apk")

        val check = rig.port.checkAppUpdate()
        assertThat(check.isSuccess).isTrue()
        assertThat(appUpdateStagingApk(cacheDir).exists()).isTrue()
        assertThat(appUpdateStagingApk(cacheDir).readText()).isEqualTo("partial-apk")

        assertThat(rig.port.cleanupAppUpdateStaging().isSuccess).isTrue()
        assertThat(appUpdateStagingApk(cacheDir).exists()).isTrue()

        releaseDownload.complete(Unit)
        assertThat(installing.await().getOrThrow())
            .isEqualTo(AppUpdateInstallResult.SessionStarted)
        assertThat(appUpdateStagingApk(cacheDir).exists()).isFalse()
    }

}
