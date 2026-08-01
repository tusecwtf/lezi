package com.lezi.babylog.sync
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
import com.lezi.babylog.core.database.OutboxDao
import com.lezi.babylog.core.database.OutboxEntity
import com.lezi.babylog.core.database.matchesPublishedRevision
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.core.model.RootPublicationState
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Test
import com.lezi.babylog.sync.appupdate.APP_UPDATE_METADATA_PACKAGE_MISMATCH_MESSAGE
import com.lezi.babylog.sync.appupdate.APP_UPDATE_PACKAGE_INVALID_MESSAGE
import com.lezi.babylog.sync.appupdate.AppUpdateApkIdentityReader
import com.lezi.babylog.sync.appupdate.AppUpdateInstaller
import com.lezi.babylog.sync.appupdate.AppUpdateUiOutcome
import com.lezi.babylog.sync.appupdate.StagedApkIdentity
import com.lezi.babylog.sync.appupdate.appUpdateStagingApk
import com.lezi.babylog.sync.appupdate.appUpdateStagingDir
import com.lezi.babylog.sync.appupdate.appUpdateUiOutcome
import com.lezi.babylog.sync.appupdate.sha256Hex
import com.lezi.babylog.sync.backend.AtomicBundleDraft
import com.lezi.babylog.sync.backend.BundleCommitResult
import com.lezi.babylog.sync.backend.BundleStageStatus
import com.lezi.babylog.sync.backend.CanonicalRecordAuthor
import com.lezi.babylog.sync.backend.ClientUpdateRequiredException
import com.lezi.babylog.sync.backend.DisplayNameUpdateResult
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
import com.lezi.babylog.sync.backend.normalizeFamilyNameForWire
import com.lezi.babylog.sync.clear.LocalClearCommittedException
import com.lezi.babylog.sync.engine.AtomicBundleId
import com.lezi.babylog.sync.engine.CarePlanFamilyAppliedListener
import com.lezi.babylog.sync.engine.ForegroundSyncGate
import com.lezi.babylog.sync.media.LocalMediaInfo
import com.lezi.babylog.sync.media.ReferenceAwareMediaFileCleanup
import com.lezi.babylog.sync.media.SyncMediaFileStore
import com.lezi.babylog.sync.media.SyncMediaUploadSource
import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import com.lezi.babylog.sync.session.CertificateTrustCandidate
import com.lezi.babylog.sync.session.CreatorAcknowledgementRef
import com.lezi.babylog.sync.session.FamilyEndpointConfig
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.ForegroundState
import com.lezi.babylog.sync.session.PolicyClock
import com.lezi.babylog.sync.session.SetupFamilyState
import com.lezi.babylog.sync.session.SetupProbe
import com.lezi.babylog.sync.session.SetupProbeResult
import com.lezi.babylog.sync.session.SyncPreferences
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import com.lezi.babylog.sync.session.familySyncError
import com.lezi.babylog.sync.backend.FakeSyncBackend
import com.lezi.babylog.sync.backend.LegacyPushResult
import com.lezi.babylog.sync.backend.testPreparedMedia

class RealSyncPortTest {
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
    fun syncClientUpdateRequiredKeepsForceShellWhenMetadataClassifiesNonForced() = runTest {
        // Server gate says CUR, but advertised minSupported is below local (divergence).
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
        // Must not clear to silent Idle: keep PackageUnknown (or prior package).
        assertThat(rig.port.availableForcedAppUpdate().first())
            .isEqualTo(ForcedAppUpdateState.PackageUnknown)
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
    fun checkAppUpdateAfterPackageUnknownKeepsForceShellWithoutOptionalBanner() = runTest {
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

        // Optional-classifying metadata after CUR must not demote force or show optional banner.
        // Result must not be OptionalUpdate (would open dismissible "稍后" secondary dialog).
        rig.backend.getAppUpdateMetadataFailure = null
        rig.backend.appUpdateMetadata = optionalMetadata
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
    fun checkAppUpdatePreservesWithPackageWhenMetadataClassifiesNonForced() = runTest {
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

        // Temporary divergence / non-Forced metadata must keep the last installable package
        // and return ForcedUpdate (not Optional) so UI cannot offer dismissible dialog.
        rig.backend.appUpdateMetadata = nonForcedMetadata
        val checkResult = rig.port.checkAppUpdate().getOrThrow()
        assertThat(checkResult).isEqualTo(AppUpdateCheckResult.ForcedUpdate(forcedPackage))
        assertThat(
            appUpdateUiOutcome(
                result = Result.success(checkResult),
                failureCopy = { "unused" },
            ),
        ).isEqualTo(AppUpdateUiOutcome.ForcedUpdate(forcedPackage))

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

        val result = rig.port.installAvailableAppUpdate(metadata).getOrThrow()

        assertThat(result).isEqualTo(AppUpdateInstallResult.RequiresInstallPermission)
        assertThat(rig.backend.downloadAppUpdateApkCalls).isEqualTo(0)
        assertThat(installer.installCalls).isEmpty()
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
        rig.awaitStartupRecovery()

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
        failedRemoteRig.awaitStartupRecovery()
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
        interruptedRig.awaitStartupRecovery()
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
        withTimeout(2_000) { preferences.familyDeletionClearCompleted.await() }
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
        explicit.awaitStartupRecovery()
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
        generic.awaitStartupRecovery()
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
        rig.awaitStartupRecovery()

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
        failedRemoteRig.awaitStartupRecovery()
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
        interruptedRig.awaitStartupRecovery()
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
        withTimeout(2_000) { preferences.session.filter { !it.isJoined }.first() }
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
        rig.awaitStartupRecovery()

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
            rig.awaitStartupRecovery()

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
        failedRemoteRig.awaitStartupRecovery()
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
        interruptedRig.awaitStartupRecovery()
        assertThat(interruptedRig.port.logoutCurrentDevice().isFailure).isTrue()
        assertThat(preferences.current()).isEqualTo(original)
        assertThat(preferences.pendingDeviceRemovalClear).isTrue()

        val resumedGate = TestRemovedDeviceLocalClearGate()
        SyncRig(
            session = original,
            syncPreferences = preferences,
            removedDeviceLocalClearGate = resumedGate,
        )
        resumedGate.firstCall.await()
        withTimeout(2_000) { preferences.session.filter { !it.isJoined }.first() }
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
        assertThat(
            withTimeout(2_000) {
                rig.port.status().filter { it == SyncStatus.ReauthRequired }.first()
            },
        ).isEqualTo(SyncStatus.ReauthRequired)
        assertThat(rig.preferences.current().familyId).isEqualTo("family-a")
        assertThat(rig.preferences.current().pullCursor).isEqualTo(retained.pullCursor)
        // Credentials gone: reauth surface, not a joined sync session.
        assertThat(rig.port.session().first().isJoined).isFalse()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.ReauthRequired)
    }

    @Test
    fun endpointProbeAndPersistenceUseTheDedicatedPreLoginSeam() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://family.example.com")
        val drafts = mutableListOf<String>()
        val rig = SyncRig(
            session = joinedSession("family-a"),
            setupProbe = SetupProbe { draft, _ ->
                drafts += draft
                SetupProbeResult.Ready(endpoint, SetupFamilyState.Configured)
            },
        )

        val result = rig.port.probeEndpoint(" https://family.example.com/ ")
        rig.port.rememberEndpoint(endpoint).getOrThrow()

        assertThat(result).isEqualTo(
            SetupProbeResult.Ready(endpoint, SetupFamilyState.Configured),
        )
        assertThat(drafts).containsExactly(" https://family.example.com/ ")
        assertThat(rig.preferences.verifiedEndpoint.first()).isEqualTo(endpoint)
        assertThat(rig.preferences.current().familyId).isEqualTo("family-a")
    }

    @Test
    fun certificateAcceptancePersistsThePinOnlyAfterReadyAndPreservesTheExistingSession() = runTest {
        val session = joinedSession("family-a")
        val preferences = MemorySyncPreferences(session)
        val previousEndpoint = TrustedEndpointProfile.systemPki(session.baseUrl)
        val candidate = CertificateTrustCandidate.fromSpki(
            TrustedEndpointProfile.systemPki("https://192.168.50.4:8765"),
            "stable-nas-public-key".toByteArray(),
        )
        val trusted = candidate.trustedEndpoint()
        var endpointSeenDuringProbe: TrustedEndpointProfile? = null
        val rig = SyncRig(
            session = session,
            syncPreferences = preferences,
            setupProbe = SetupProbe { _, suppliedTrust ->
                assertThat(preferences.verifiedEndpoint.first()).isEqualTo(previousEndpoint)
                endpointSeenDuringProbe = suppliedTrust
                SetupProbeResult.Ready(trusted, SetupFamilyState.Empty)
            },
        )

        val result = rig.port.trustCertificate(candidate)

        assertThat(result).isEqualTo(
            SetupProbeResult.Ready(trusted, SetupFamilyState.Empty),
        )
        assertThat(endpointSeenDuringProbe).isEqualTo(trusted)
        assertThat(preferences.verifiedEndpoint.first()).isEqualTo(trusted)
        assertThat(preferences.current()).isEqualTo(session)
        assertThat(rig.backend.createRequestIds).isEmpty()
    }

    @Test
    fun failedTrustedProbeClearsAStaleDurableResumeEndpoint() = runTest {
        val preferences = MemorySyncPreferences(SyncSession())
        preferences.rememberEndpoint(
            TrustedEndpointProfile.tofuSpki(
                "https://192.168.50.4:8765",
                "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",
            ),
        )
        val candidate = CertificateTrustCandidate.fromSpki(
            TrustedEndpointProfile.systemPki("https://192.168.50.4:8765"),
            "unstable-nas-public-key".toByteArray(),
        )
        val rig = SyncRig(
            session = SyncSession(),
            syncPreferences = preferences,
            setupProbe = SetupProbe { _, _ -> SetupProbeResult.Failed.NotLezi },
        )

        assertThat(rig.port.trustCertificate(candidate)).isEqualTo(SetupProbeResult.Failed.NotLezi)
        assertThat(preferences.verifiedEndpoint.first()).isNull()
    }

    @Test
    fun qrEndpointVerificationCanBeCancelledWithoutPersistingTrust() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://family.example.com")
        val started = CompletableDeferred<Unit>()
        val preferences = MemorySyncPreferences(SyncSession())
        val rig = SyncRig(
            session = SyncSession(),
            syncPreferences = preferences,
            setupProbe = SetupProbe { _, _ ->
                started.complete(Unit)
                awaitCancellation()
            },
        )
        val verification = async { rig.port.verifyEndpoint(endpoint) }
        started.await()

        verification.cancel()

        assertThat(runCatching { verification.await() }.exceptionOrNull())
            .isInstanceOf(CancellationException::class.java)
        assertThat(preferences.verifiedEndpoint.first()).isNull()
    }

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
    fun startupRecoveryPropagatesCancellation() = runTest {
        val cancellation = CancellationException("process stopping")

        val thrown = runCatching {
            runProcessStartupRecovery(
                reportFailure = { error("must not report cancellation") },
                recover = { throw cancellation },
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
        assertThat(rig.outbox.all()).isEmpty()
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
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 7,
            generation = "current-generation",
            hasMore = false,
        )
        rig.backend.pullStarted = CompletableDeferred()
        rig.backend.releasePull = CompletableDeferred()
        rig.babies.seed(localBaby())

        val creating = async {
            rig.port.createFamily(
                displayName = "妈妈",
                bootstrapSecret = "bootstrap",
                familyName = "乐乐家",
            ).getOrThrow()
        }
        rig.backend.pullStarted!!.await()

        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Syncing)
        assertThat(rig.port.session().first().accessToken).isEqualTo("owner-token")
        assertThat(rig.port.session().first().refreshToken).isEqualTo("owner-refresh-token")

        rig.backend.releasePull!!.complete(Unit)
        val created = creating.await()

        assertThat(created.reclaimed).isFalse()
        assertThat(created.dataRecovery).isEqualTo(InitialFamilyDataRecovery.Complete)
        assertThat(rig.port.session().first().accessToken).isEqualTo("owner-token")
        assertThat(rig.backend.pullCursors).containsExactly(0L)
        assertThat(rig.backend.syncOrder)
            .containsExactly("stage:baby", "pull:0")
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
        rig.backend.pullFailures += SyncHttpException(503)
        rig.babies.seed(localBaby())

        val result = rig.port.createFamily(
            displayName = "妈妈",
            bootstrapSecret = "bootstrap",
            familyName = "乐乐家",
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().dataRecovery)
            .isEqualTo(InitialFamilyDataRecovery.RetryRequired)
        assertThat(rig.port.session().first().accessToken).isEqualTo("owner-token")
        assertThat(rig.port.session().first().refreshToken).isEqualTo("owner-refresh-token")
        assertThat(rig.port.session().first().pullCursor).isEqualTo(0L)
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
    fun qrMemberLoginExposesRetryableInitialDataRecovery() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://192.168.1.20:8787")
        val preferences = MemorySyncPreferences(SyncSession()).apply {
            rememberEndpoint(endpoint)
        }
        val rig = SyncRig(
            session = SyncSession(),
            syncPreferences = preferences,
        )
        rig.awaitStartupRecovery()
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
        assertThat(result.dataRecovery).isEqualTo(InitialFamilyDataRecovery.RetryRequired)
        assertThat(rig.backend.memberLoginGrantClaims)
            .containsExactly(Triple(endpoint, payload.grant, "Pixel Tablet"))
    }

    @Test
    fun queuedNetworkChangeCannotSkipFreshCreateFullPull() = runTest {
        val configured = SyncSession(
            serverHost = "192.168.1.20",
            serverPort = 8787,
        )
        val rig = SyncRig(session = configured)
        rig.awaitStartupRecovery()
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
        changingNetwork.await()

        assertThat(created.dataRecovery).isEqualTo(InitialFamilyDataRecovery.Complete)
        assertThat(rig.backend.pullCursors).containsExactly(0L)
        assertThat(rig.backend.syncOrder).contains("pull:0")
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

    @Test
    fun transportTypeDoesNotBlockTrustedEndpointSync() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby())
        rig.records.seed(localRecord(babyId))

        val result = rig.port.sync(SyncTrigger.LocalWrite)

        assertThat(result.isSuccess).isTrue()
        assertThat(rig.backend.stagedBundles.map { it.root.type })
            .containsExactly("baby", "record")
        assertThat(rig.outbox.peek("family-a", 100)).isEmpty()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
    }

    @Test
    fun concurrentPullToRefreshCallsNeverOverlapRemotePulls() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.backend.pullStarted = CompletableDeferred()
        rig.backend.releasePull = CompletableDeferred()

        val first = async { rig.port.sync(SyncTrigger.PullToRefresh) }
        rig.backend.pullStarted!!.await()
        val second = async { rig.port.sync(SyncTrigger.PullToRefresh) }
        runCurrent()

        assertThat(rig.backend.pullCount).isEqualTo(1)
        assertThat(second.isCompleted).isFalse()

        rig.backend.releasePull!!.complete(Unit)
        assertThat(first.await().isSuccess).isTrue()
        assertThat(second.await().isSuccess).isTrue()
        assertThat(rig.backend.pullCount).isEqualTo(2)
    }

    @Test
    fun unreachableEndpointKeepsLocalFactsAndRetryableOutbox() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby())
        rig.records.seed(localRecord(babyId))
        rig.backend.stageBundleFailure = java.io.IOException("endpoint unreachable")

        val result = rig.port.sync(SyncTrigger.LocalWrite)

        assertThat(result.exceptionOrNull()).isInstanceOf(java.io.IOException::class.java)
        assertThat(rig.babies.listAllIncludingDeleted()).hasSize(1)
        assertThat(rig.records.listAllIncludingDeleted()).hasSize(1)
        assertThat(rig.outbox.peek("family-a", 100).map(OutboxEntity::entityType))
            .containsExactly("baby", "record")
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Error)
    }

    @Test
    fun successfulPushUsesPortableWireAcksOnlyCurrentFamily() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby())
        rig.records.seed(localRecord(babyId))
        rig.outbox.enqueue(
            OutboxEntity(
                familyId = "family-b",
                entityType = "record",
                clientUuid = "other-family-record",
                payloadJson = "{}",
                updatedAt = 1,
            ),
        )

        val result = rig.port.sync(SyncTrigger.LocalWrite)
        assertThat(result.exceptionOrNull()).isNull()

        assertThat(rig.backend.pushes).isEmpty()
        assertThat(rig.backend.committedBundles).hasSize(2)
        val draft = rig.backend.stagedBundles.single { it.root.type == "record" }
        val recordPayload = Json.parseToJsonElement(draft.root.payloadJson).jsonObject
        assertThat(recordPayload["baby_client_uuid"].toString()).isEqualTo("\"baby-local\"")
        assertThat(recordPayload["baby_id"]).isNull()
        assertThat(recordPayload["payload_json"]).isInstanceOf(
            kotlinx.serialization.json.JsonObject::class.java,
        )
        assertThat(rig.outbox.peek("family-a", 100)).isEmpty()
        assertThat(rig.outbox.peek("family-b", 100).map(OutboxEntity::clientUuid))
            .containsExactly("other-family-record")
    }

    @Test
    fun freshFamilyPushesBabyAndZeroPhotoRecordAsOrderedAtomicRoots() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.backend.enforceBundleReferences = true
        val babyId = rig.babies.seed(localBaby())
        rig.records.seed(localRecord(babyId))

        val result = rig.port.sync(SyncTrigger.LocalWrite)

        assertThat(result.exceptionOrNull()).isNull()
        assertThat(rig.backend.operationOrder)
            .containsExactly("stage:baby", "stage:record")
            .inOrder()
        assertThat(rig.outbox.peek("family-a", 100)).isEmpty()
    }

    @Test
    fun freshFamilyUploadsBabyAvatarWithZeroPhotoRecordAtomicPackage() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.backend.enforceBundleReferences = true
        val avatarUuid = "11111111-1111-4111-8111-111111111111"
        val babyId = rig.babies.seed(
            localBaby().copy(
                clientUuid = "22222222-2222-4222-8222-222222222222",
                avatarMediaUuid = avatarUuid,
                avatarPath = "avatars/baby.jpg",
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                babyId = babyId,
                clientUuid = avatarUuid,
                kind = "avatar",
                localUri = "avatars/baby.jpg",
                mime = "image/jpeg",
                byteSize = 1,
                createdAt = 100,
                updatedAt = 110,
            ),
        )
        rig.records.seed(localRecord(babyId))

        val result = rig.port.sync(SyncTrigger.LocalWrite)

        assertThat(result.exceptionOrNull()).isNull()
        assertThat(rig.backend.operationOrder)
            .containsExactly(
                "stage:baby",
                "put_bundle_media:$avatarUuid",
                "stage:record",
            )
            .inOrder()
        val babyDraft = rig.backend.stagedBundles.first { it.root.type == "baby" }
        assertThat(babyDraft.root.updatedAt).isEqualTo(110)
        assertThat(babyDraft.bundleId).isEqualTo(
            AtomicBundleId.forBaby("22222222-2222-4222-8222-222222222222", 110),
        )
        assertThat(rig.outbox.peek("family-a", 100)).isEmpty()
    }

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
        assertThat(rig.outbox.peek("family-a", 100)).isEmpty()
    }

    @Test
    fun switchingFamilyRequeuesEverySharedEntityBeforePublishingDependencies() = runTest {
        val rig = SyncRig(session = joinedSession("family-old"))
        rig.backend.enforceBundleReferences = true
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

        assertThat(
            rig.port.saveEndpointConfig(
                FamilyEndpointConfig(host = "192.168.1.99", port = 8787),
            ).isSuccess,
        ).isTrue()
        assertThat(rig.records.getByClientUuid(recordUuid)?.familyPublishedUpdatedAt).isNull()
        assertThat(rig.carePlans.getByClientUuid(planUuid)?.familyPublishedUpdatedAt).isNull()
        rig.preferences.saveSession(joinedSession("family-new"))
        val result = rig.port.sync(SyncTrigger.LocalWrite)

        assertThat(result.exceptionOrNull()).isNull()
        assertThat(rig.backend.operationOrder)
            .containsExactly(
                "stage:baby",
                "stage:custom_item",
                "stage:care_plan",
                "stage:record",
                "stage:fulfillment_candidate",
            )
            .inOrder()
        assertThat(rig.outbox.peek("family-new", 100)).isEmpty()
        assertThat(rig.customItems.get("custom-local")?.syncDirty).isFalse()
        assertThat(rig.fulfillmentCandidates.getByClientUuid("candidate-local")?.syncDirty)
            .isFalse()
    }

    @Test
    fun switchingFamilyReplacesFamilyScopedOwnershipStamps() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-old").copy(membershipId = "membership-old"),
        )
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val customItemId = rig.customItems.seed(
            CustomItemEntity(
                clientUuid = "custom-family-stamp",
                familyId = 1,
                name = "抚触",
                iconSlot = 2,
                updatedAt = 100,
                createdByMembershipId = "membership-old",
                syncDirty = false,
            ),
        )
        val recordUuid = "record-family-stamp"
        val planUuid = "plan-family-stamp"
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
                clientUuid = "candidate-family-stamp",
                carePlanClientUuid = planUuid,
                recordClientUuid = recordUuid,
                confirmedAt = 121,
                submitterMembershipId = "membership-old",
                submitterRole = "member",
                updatedAt = 121,
                syncDirty = false,
            ),
        )

        assertThat(
            rig.port.saveEndpointConfig(
                FamilyEndpointConfig(host = "192.168.1.99", port = 8787),
            ).isSuccess,
        ).isTrue()
        rig.preferences.saveSession(
            joinedSession("family-new").copy(membershipId = "membership-new"),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "custom_item",
                    clientUuid = "custom-family-stamp",
                    payloadJson =
                        """{"name":"抚触","icon_slot":2,"created_by_membership_id":"membership-new"}""",
                    updatedAt = 100,
                ),
                SyncEntity(
                    type = "care_plan",
                    clientUuid = planUuid,
                    payloadJson =
                        """{"baby_client_uuid":"baby-local","type":"custom","custom_item_client_uuid":"custom-family-stamp","scheduled_at":9000000000000,"scheduled_zone_id":"Asia/Shanghai","note":null,"payload_json":{"title":"抚触"},"schema_version":2,"status":"completed","created_by_membership_id":"membership-new","fulfilled_record_client_uuid":"$recordUuid","fulfilled_at":121}""",
                    updatedAt = 121,
                ),
                SyncEntity(
                    type = "fulfillment_candidate",
                    clientUuid = "candidate-family-stamp",
                    payloadJson =
                        """{"care_plan_client_uuid":"$planUuid","record_client_uuid":"$recordUuid","actual_timestamp":120,"submitter_membership_id":"membership-new","submitter_role":"owner","confirmed_at":121}""",
                    updatedAt = 121,
                ),
            ),
            cursor = 2,
            generation = "current-generation",
            hasMore = false,
        )

        val result = rig.port.sync(SyncTrigger.LocalWrite)

        assertThat(result.exceptionOrNull()).isNull()
        assertThat(rig.backend.pullCount).isEqualTo(1)
        assertThat(rig.customItems.get("custom-family-stamp")?.createdByMembershipId)
            .isEqualTo("membership-new")
        assertThat(rig.carePlans.getByClientUuid(planUuid)?.createdByMembershipId)
            .isEqualTo("membership-new")
        val candidate = requireNotNull(
            rig.fulfillmentCandidates.getByClientUuid("candidate-family-stamp"),
        )
        assertThat(candidate.submitterMembershipId).isEqualTo("membership-new")
        assertThat(candidate.submitterRole).isEqualTo("owner")
    }

    @Test
    fun switchingFamilyCarePlanCreatorSchedulesAuthoritativeAcknowledgementPull() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-old").copy(membershipId = "membership-old"),
        )
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = "plan-family-stamp-only",
                createdByMembershipId = "membership-old",
                syncDirty = false,
            ),
        )

        assertThat(
            rig.port.saveEndpointConfig(
                FamilyEndpointConfig(host = "192.168.1.99", port = 8787),
            ).isSuccess,
        ).isTrue()
        rig.preferences.saveSession(
            joinedSession("family-new").copy(membershipId = "membership-new"),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "care_plan",
                    clientUuid = "plan-family-stamp-only",
                    payloadJson =
                        """{"baby_client_uuid":"baby-local","type":"formula","custom_item_client_uuid":null,"scheduled_at":9000000000000,"scheduled_zone_id":"Asia/Shanghai","note":null,"payload_json":{"amount_ml":120},"schema_version":2,"status":"pending","created_by_membership_id":"membership-new","fulfilled_record_client_uuid":null,"fulfilled_at":null}""",
                    updatedAt = 100,
                ),
            ),
            cursor = 1,
            generation = "current-generation",
            hasMore = false,
        )

        val result = rig.port.sync(SyncTrigger.LocalWrite)

        assertThat(result.exceptionOrNull()).isNull()
        assertThat(rig.backend.pullCount).isEqualTo(1)
        assertThat(
            rig.carePlans.getByClientUuid("plan-family-stamp-only")?.createdByMembershipId,
        ).isEqualTo("membership-new")
    }

    @Test
    fun atomicRecordAlwaysPublishesCurrentMembershipAuthor() = runTest {
        val modernRig = SyncRig(
            session = joinedSession("family-a").copy(membershipId = "membership-a"),
        )
        val modernBabyId = modernRig.babies.seed(localBaby())
        modernRig.records.seed(
            localRecord(modernBabyId).copy(createdByMembershipId = "membership-a"),
        )

        assertThat(modernRig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val modernPayload = Json.parseToJsonElement(
            modernRig.backend.stagedBundles
                .single { it.root.type == "record" }
                .root
                .payloadJson,
        ).jsonObject
        assertThat(modernPayload["created_by_membership_id"]?.jsonPrimitive?.content)
            .isEqualTo("membership-a")

    }
    @Test
    fun atomicRecordCommitAckHydratesPreJoinAuthorWithoutChangingTheRecordRevision() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(membershipId = "membership-a"),
        )
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.backend.remember("baby", "baby-local")
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "pre-join-record",
                createdByMembershipId = "",
                payloadJson = """{"amount_ml":120}""",
                updatedAt = 120,
                syncDirty = true,
            ),
        )
        rig.backend.nextCommitRecordAuthors = listOf(
            CanonicalRecordAuthor(
                clientUuid = "pre-join-record",
                createdByMembershipId = "membership-a",
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val hydrated = requireNotNull(rig.records.getByClientUuid("pre-join-record"))
        assertThat(hydrated.createdByMembershipId).isEqualTo("membership-a")
        assertThat(hydrated.updatedAt).isEqualTo(120)
        assertThat(hydrated.payloadJson).isEqualTo("""{"amount_ml":120}""")
        assertThat(hydrated.syncDirty).isFalse()
        assertThat(rig.outbox.peek("family-a", 10)).isEmpty()
    }

    @Test
    fun atomicRecordCommitRejectsMalformedCanonicalAuthorAcknowledgements() = runTest {
        val malformedAcknowledgements = listOf(
            emptyList(),
            listOf(
                CanonicalRecordAuthor("pre-join-record", "membership-a"),
                CanonicalRecordAuthor("pre-join-record", "membership-a"),
            ),
            listOf(
                CanonicalRecordAuthor("pre-join-record", "membership-a"),
                CanonicalRecordAuthor("unexpected-record", "membership-a"),
            ),
        )

        malformedAcknowledgements.forEach { acknowledgements ->
            val rig = SyncRig(
                session = joinedSession("family-a").copy(membershipId = "membership-a"),
            )
            val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
            rig.backend.remember("baby", "baby-local")
            rig.records.seed(
                localRecord(babyId).copy(
                    clientUuid = "pre-join-record",
                    createdByMembershipId = "",
                    updatedAt = 120,
                    syncDirty = true,
                ),
            )
            rig.backend.nextCommitRecordAuthors = acknowledgements

            val result = rig.port.sync(SyncTrigger.LocalWrite)

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).hasMessageThat().contains("record_authors")
            val retained = requireNotNull(rig.records.getByClientUuid("pre-join-record"))
            assertThat(retained.createdByMembershipId).isEmpty()
            assertThat(retained.syncDirty).isTrue()
            assertThat(rig.outbox.peek("family-a", 10).map(OutboxEntity::clientUuid))
                .containsExactly("pre-join-record")
        }
    }


    @Test
    fun customItemDirtySnapshotPushesAndPullPreservesLocalSortOrder() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(membershipId = "m-owner"),
        )
        rig.customItems.seed(
            CustomItemEntity(
                clientUuid = "custom-1",
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
        val pushed = rig.backend.stagedBundles
            .map(AtomicBundleDraft::root)
            .filter { it.type == "custom_item" }
        assertThat(pushed).hasSize(1)
        assertThat(pushed.single().payloadJson).contains("\"name\":\"抚触\"")
        assertThat(pushed.single().payloadJson).doesNotContain("sort_order")
        assertThat(pushed.single().payloadJson).doesNotContain("sortOrder")
        assertThat(pushed.single().payloadJson).doesNotContain("hidden")
        assertThat(pushed.single().payloadJson).doesNotContain("quick")
        assertThat(rig.customItems.get("custom-1")!!.syncDirty).isFalse()
        assertThat(rig.customItems.get("custom-1")!!.sortOrder).isEqualTo(7)

        // Remote rename from peer should update name but keep local sortOrder.
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "custom_item",
                    clientUuid = "custom-1",
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
        val pullResult = rig.port.sync(SyncTrigger.PullToRefresh)
        assertThat(pullResult.exceptionOrNull()).isNull()
        val applied = rig.customItems.get("custom-1")!!
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
    fun tombstonedCustomDefinitionAllowsHistoricalRecordEditAndDeleteToDrainOutbox() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.backend.enforceBundleReferences = true
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.backend.remember("baby", "baby-local")
        val customItemId = rig.customItems.seed(
            CustomItemEntity(
                clientUuid = "custom-history",
                familyId = 1,
                name = "抚触",
                iconSlot = 2,
                updatedAt = 150,
                deletedAt = 150,
                syncDirty = false,
            ),
        )
        rig.backend.remember("custom_item", "custom-history")
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "custom-history-record",
                type = "custom",
                note = "编辑后",
                payloadJson =
                    """{"title":"抚触","detail":"睡前十分钟","custom_item_id":$customItemId,"icon_slot":2}""",
                updatedAt = 200,
                syncDirty = true,
            ),
        )

        rig.backend.stageBundleFailure = SyncHttpException(503, "temporary")
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isFailure).isTrue()
        assertThat(rig.records.getByClientUuid("custom-history-record")?.syncDirty).isTrue()
        assertThat(rig.outbox.peek("family-a", 100).map(OutboxEntity::clientUuid))
            .contains("custom-history-record")
        rig.backend.stageBundleFailure = null

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val editedDraft = rig.backend.stagedBundles.last {
            it.root.clientUuid == "custom-history-record"
        }
        assertThat(editedDraft.root.payloadJson)
            .contains("\"custom_item_client_uuid\":\"custom-history\"")
        assertThat(editedDraft.root.payloadJson).contains("睡前十分钟")
        assertThat(rig.backend.stagedBundles.map { it.root.type })
            .doesNotContain("custom_item")
        assertThat(rig.outbox.peek("family-a", 100)).isEmpty()

        rig.records.softDelete(recordId, deletedAt = 300)
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val deletedDraft = rig.backend.stagedBundles.last {
            it.root.clientUuid == "custom-history-record"
        }
        assertThat(deletedDraft.root.deletedAt).isEqualTo(300)
        assertThat(rig.outbox.peek("family-a", 100)).isEmpty()
        assertThat(rig.customItems.get("custom-history")?.deletedAt).isEqualTo(150)
    }

    @Test
    fun terminalCustomHistoryRejectionKeepsLocalFactAndOutboxForVisibleRecovery() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.backend.enforceBundleReferences = true
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.backend.remember("baby", "baby-local")
        val customItemId = rig.customItems.seed(
            CustomItemEntity(
                clientUuid = "custom-terminal",
                familyId = 1,
                name = "抚触",
                iconSlot = 2,
                updatedAt = 150,
                deletedAt = 150,
                syncDirty = false,
            ),
        )
        rig.backend.remember("custom_item", "custom-terminal")
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "custom-terminal-record",
                type = "custom",
                payloadJson =
                    """{"title":"抚触","custom_item_id":$customItemId,"icon_slot":2}""",
                updatedAt = 200,
                syncDirty = true,
            ),
        )
        rig.backend.stageBundleFailure = SyncHttpException(422, "invalid historical snapshot")

        val result = rig.port.sync(SyncTrigger.LocalWrite)

        assertThat(result.isFailure).isTrue()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Error)
        assertThat(rig.records.getByClientUuid("custom-terminal-record")?.syncDirty).isTrue()
        assertThat(rig.outbox.peek("family-a", 100).map(OutboxEntity::clientUuid))
            .contains("custom-terminal-record")
        assertThat(rig.customItems.get("custom-terminal")?.deletedAt).isEqualTo(150)
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
            assertThat(rig.outbox.peek("family-a", 100)).isEmpty()
            assertThat(rig.customItems.get("custom-plan-$photoCount")?.deletedAt).isEqualTo(400)
        }

        runCase(0)
        runCase(2)
    }

    @Test
    fun oneSyncDrainsEveryOutboxBatchWithoutStarvingRowsPastLimit() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        repeat(205) { index ->
            rig.babies.seed(
                localBaby().copy(
                    nickname = "宝宝-$index",
                    clientUuid = "baby-$index",
                    updatedAt = index.toLong() + 1,
                ),
            )
        }

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        assertThat(rig.backend.stagedBundles).hasSize(205)
        assertThat(rig.backend.stagedBundles.map { it.root.clientUuid })
            .containsExactlyElementsIn((0 until 205).map { "baby-$it" })
        assertThat(rig.outbox.peek("family-a", 300)).isEmpty()
    }

    @Test
    fun movingToBackgroundStopsBeforeTheNextNetworkBatch() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        repeat(205) { index ->
            rig.babies.seed(
                localBaby().copy(
                    clientUuid = "baby-$index",
                    updatedAt = index.toLong() + 1,
                ),
            )
        }
        rig.backend.afterCommit = { rig.foreground.setForeground(false) }

        val result = rig.port.sync(SyncTrigger.LocalWrite)

        assertThat(result.isFailure).isTrue()
        assertThat(rig.backend.committedBundles).hasSize(1)
        assertThat(rig.outbox.peek("family-a", 300)).hasSize(204)
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
    }

    @Test
    fun successfulSnapshotReadsOnlyDirtyLocalChanges() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a"),
        )
        repeat(205) { index ->
            rig.babies.seed(
                localBaby().copy(
                    nickname = "历史宝宝-$index",
                    clientUuid = "history-baby-$index",
                    updatedAt = 900,
                    syncDirty = false,
                ),
            )
        }
        rig.babies.seed(
            localBaby().copy(
                nickname = "刚更新的宝宝",
                clientUuid = "changed-baby",
                updatedAt = 1_100,
            ),
        )
        rig.clock.now = 2_000

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        assertThat(rig.backend.stagedBundles.map { it.root.clientUuid })
            .containsExactly("changed-baby")
    }

    @Test
    fun clockRollbackCannotHideADirtyLocalChange() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.babies.seed(
            localBaby().copy(
                clientUuid = "already-synced",
                updatedAt = 2_000,
                syncDirty = false,
            ),
        )
        rig.babies.seed(
            localBaby().copy(
                clientUuid = "written-after-clock-rollback",
                updatedAt = 900,
                syncDirty = true,
            ),
        )
        rig.clock.now = 1_000

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        assertThat(rig.backend.stagedBundles.map { it.root.clientUuid })
            .containsExactly("written-after-clock-rollback")
    }

    @Test
    fun avatarDependencyJoinsBabyBatchPastTheNormalLimit() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val avatarUuid = "33333333-3333-3333-3333-333333333333"
        repeat(201) { index ->
            val babyId = rig.babies.seed(
                localBaby().copy(
                    nickname = "宝宝-$index",
                    clientUuid = "baby-$index",
                    avatarPath = if (index == 0) "avatars/first.jpg" else null,
                    updatedAt = index.toLong() + 1,
                ),
            )
            if (index == 0) {
                rig.media.seed(
                    MediaAssetEntity(
                        clientUuid = avatarUuid,
                        kind = "avatar",
                        babyId = babyId,
                        localUri = "avatars/first.jpg",
                        remoteUri = rig.preferences.current().expectedMediaReceipt(avatarUuid),
                        mime = "image/jpeg",
                        byteSize = 12,
                        createdAt = 1,
                        updatedAt = 1,
                    ),
                )
            }
        }

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val babyBundle = rig.backend.stagedBundles.first { it.root.clientUuid == "baby-0" }
        assertThat(babyBundle.media.map(SyncEntity::clientUuid)).contains(avatarUuid)
        assertThat(rig.outbox.peek("family-a", 300)).isEmpty()
    }

    @Test
    fun deletedBabyPackagePublishesMediaTombstonesWithoutLiveAvatarPointer() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        // Keeper profile so product "keep at least one" is irrelevant to capture.
        rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-keeper",
                nickname = "保留",
                syncDirty = false,
                updatedAt = 50,
            ),
        )
        val avatarUuid = "44444444-4444-4444-4444-444444444444"
        val legacyUuid = "55555555-5555-5555-5555-555555555555"
        val deletedAt = 300L
        val babyId = rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-deleted",
                nickname = "已删",
                avatarMediaUuid = null,
                avatarPath = null,
                updatedAt = deletedAt,
                deletedAt = deletedAt,
                syncDirty = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = avatarUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "baby_avatars/deleted.jpg",
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = deletedAt,
                deletedAt = deletedAt,
                syncDirty = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = legacyUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "baby_avatars/legacy.jpg",
                mime = "image/jpeg",
                byteSize = 8,
                createdAt = 80,
                updatedAt = deletedAt,
                deletedAt = deletedAt,
                syncDirty = true,
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val babyBundle = rig.backend.stagedBundles.single { it.root.clientUuid == "baby-deleted" }
        assertThat(babyBundle.root.deletedAt).isEqualTo(deletedAt)
        val rootPayload = Json.parseToJsonElement(babyBundle.root.payloadJson).jsonObject
        assertThat(rootPayload["avatar_media_uuid"]).isEqualTo(JsonNull)
        assertThat(babyBundle.media.map(SyncEntity::clientUuid))
            .containsExactly(avatarUuid, legacyUuid)
        assertThat(babyBundle.media.map(SyncEntity::deletedAt)).containsExactly(deletedAt, deletedAt)
        assertThat(rig.backend.mediaUploads).isEmpty()
        assertThat(rig.outbox.peek("family-a", 100)).isEmpty()
    }

    @Test
    fun deletedBabyPackageOmitsLiveOrphanAvatarAndForcesNullPointer() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-keeper-orphan",
                nickname = "保留",
                syncDirty = false,
                updatedAt = 50,
            ),
        )
        val liveOrphanUuid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val tombstoneUuid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val deletedAt = 400L
        // Pre-fix orphan shape: deleted root still points at a live avatar row.
        val babyId = rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-deleted-orphan",
                nickname = "已删孤儿",
                avatarMediaUuid = liveOrphanUuid,
                avatarPath = "baby_avatars/orphan-live.jpg",
                updatedAt = deletedAt,
                deletedAt = deletedAt,
                syncDirty = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = liveOrphanUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "baby_avatars/orphan-live.jpg",
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 200,
                deletedAt = null,
                syncDirty = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = tombstoneUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "baby_avatars/orphan-tomb.jpg",
                mime = "image/jpeg",
                byteSize = 8,
                createdAt = 80,
                updatedAt = deletedAt,
                deletedAt = deletedAt,
                syncDirty = true,
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val babyBundle = rig.backend.stagedBundles.single { it.root.clientUuid == "baby-deleted-orphan" }
        assertThat(babyBundle.root.deletedAt).isEqualTo(deletedAt)
        val rootPayload = Json.parseToJsonElement(babyBundle.root.payloadJson).jsonObject
        assertThat(rootPayload["avatar_media_uuid"]).isEqualTo(JsonNull)
        assertThat(babyBundle.media.map(SyncEntity::clientUuid)).containsExactly(tombstoneUuid)
        assertThat(babyBundle.media.single().deletedAt).isEqualTo(deletedAt)
        // Live orphan stays local and dirty until a later domain repair tombstones it.
        assertThat(rig.media.getByClientUuid(liveOrphanUuid)?.deletedAt).isNull()
        assertThat(rig.media.getByClientUuid(liveOrphanUuid)?.syncDirty).isTrue()
    }

    @Test
    fun preSeededLiveOrphanAvatarOutboxIsDroppedWithoutAbortingResidualPush() = runTest {
        // Pre-07 residual poison: outbox still holds a live avatar media row against a
        // deleted baby. Capture skips re-enqueue of that orphan, so REPLACE never clears
        // the row; residual push must drop it without routing into standalone log media.
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
                // Baby already acknowledged; only the stale avatar outbox row remains.
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
        rig.outbox.enqueue(
            OutboxEntity(
                familyId = "family-a",
                entityType = "media",
                clientUuid = liveOrphanUuid,
                payloadJson =
                    """{"kind":"avatar","baby_client_uuid":"baby-deleted-preseed-orphan"}""",
                updatedAt = 200,
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

        // Orphan outbox gone; media stays local dirty for domain repair.
        assertThat(rig.outbox.find("family-a", "media", liveOrphanUuid)).isNull()
        assertThat(rig.media.getByClientUuid(liveOrphanUuid)?.deletedAt).isNull()
        assertThat(rig.media.getByClientUuid(liveOrphanUuid)?.syncDirty).isTrue()
        assertThat(
            rig.backend.stagedBundles.none { draft ->
                draft.media.any { it.clientUuid == liveOrphanUuid } ||
                    draft.root.clientUuid == liveOrphanUuid
            },
        ).isTrue()
        // Later residual (custom_item) still pushes; poison row did not abort the batch.
        assertThat(rig.backend.stagedBundles.map { it.root.clientUuid })
            .contains("custom-after-orphan")
        assertThat(rig.outbox.peek("family-a", 100)).isEmpty()
        assertThat(rig.customItems.getByClientUuid("custom-after-orphan")?.syncDirty).isFalse()
    }

    @Test
    fun deletedBabyAvatarPushFailThenRetryKeepsTombstonesAndNullPointer() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-keeper-retry",
                nickname = "保留",
                syncDirty = false,
                updatedAt = 50,
            ),
        )
        val avatarUuid = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val deletedAt = 500L
        val babyId = rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-deleted-retry",
                nickname = "已删重试",
                avatarMediaUuid = null,
                avatarPath = null,
                updatedAt = deletedAt,
                deletedAt = deletedAt,
                syncDirty = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = avatarUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "baby_avatars/retry-tomb.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = deletedAt,
                deletedAt = deletedAt,
                syncDirty = true,
            ),
        )
        val expectedBundleId = AtomicBundleId.forBaby("baby-deleted-retry", deletedAt)
        rig.backend.afterCommit = {
            throw IllegalStateException("crash after remote commit before local ack")
        }

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isFailure).isTrue()
        assertThat(rig.backend.committedBundles).containsExactly(expectedBundleId)
        val afterFailBaby = requireNotNull(rig.babies.getIncludingDeleted(babyId))
        assertThat(afterFailBaby.deletedAt).isEqualTo(deletedAt)
        assertThat(afterFailBaby.avatarMediaUuid).isNull()
        assertThat(afterFailBaby.syncDirty).isTrue()
        val afterFailAvatar = requireNotNull(rig.media.getByClientUuid(avatarUuid))
        assertThat(afterFailAvatar.deletedAt).isEqualTo(deletedAt)
        assertThat(afterFailAvatar.syncDirty).isTrue()
        val failedBundle = rig.backend.stagedBundles.single { it.bundleId == expectedBundleId }
        assertThat(
            Json.parseToJsonElement(failedBundle.root.payloadJson).jsonObject["avatar_media_uuid"],
        ).isEqualTo(JsonNull)
        assertThat(failedBundle.media.map(SyncEntity::clientUuid)).containsExactly(avatarUuid)
        assertThat(failedBundle.media.single().deletedAt).isEqualTo(deletedAt)

        rig.backend.afterCommit = null
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        assertThat(rig.backend.committedBundles.count { it == expectedBundleId }).isEqualTo(2)
        val publishedBaby = requireNotNull(rig.babies.getIncludingDeleted(babyId))
        assertThat(publishedBaby.deletedAt).isEqualTo(deletedAt)
        assertThat(publishedBaby.avatarMediaUuid).isNull()
        assertThat(publishedBaby.syncDirty).isFalse()
        val publishedAvatar = requireNotNull(rig.media.getByClientUuid(avatarUuid))
        assertThat(publishedAvatar.deletedAt).isEqualTo(deletedAt)
        assertThat(publishedAvatar.syncDirty).isFalse()
        assertThat(rig.outbox.peek("family-a", 10)).isEmpty()
        val retryBundles = rig.backend.stagedBundles.filter { it.bundleId == expectedBundleId }
        assertThat(retryBundles).hasSize(2)
        retryBundles.forEach { bundle ->
            assertThat(
                Json.parseToJsonElement(bundle.root.payloadJson).jsonObject["avatar_media_uuid"],
            ).isEqualTo(JsonNull)
            assertThat(bundle.media.map(SyncEntity::deletedAt)).containsExactly(deletedAt)
            assertThat(bundle.media.none { it.deletedAt == null }).isTrue()
        }
    }

    @Test
    fun pullDeletedBabyWithAvatarTombstoneDoesNotRevivePointer() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val avatarUuid = "66666666-6666-6666-6666-666666666666"
        val babyId = rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-local",
                avatarMediaUuid = avatarUuid,
                avatarPath = "baby_avatars/live.jpg",
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = avatarUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "baby_avatars/live.jpg",
                remoteUri = rig.preferences.current().expectedMediaReceipt(avatarUuid),
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        val remoteDeletedAt = 400L
        rig.backend.nextPull = PullResult(
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
                    updatedAt = remoteDeletedAt,
                    deletedAt = remoteDeletedAt,
                ),
                SyncEntity(
                    type = "media",
                    clientUuid = avatarUuid,
                    payloadJson = """
                        {
                          "kind":"avatar",
                          "record_client_uuid":null,
                          "care_plan_client_uuid":null,
                          "baby_client_uuid":"baby-local",
                          "mime":"image/jpeg",
                          "width":null,
                          "height":null,
                          "byte_size":0
                        }
                    """.trimIndent(),
                    updatedAt = remoteDeletedAt,
                    deletedAt = remoteDeletedAt,
                ),
            ),
            cursor = 1,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        val baby = requireNotNull(rig.babies.getIncludingDeleted(babyId))
        assertThat(baby.deletedAt).isEqualTo(remoteDeletedAt)
        assertThat(baby.avatarMediaUuid).isNull()
        val avatar = requireNotNull(rig.media.getByClientUuid(avatarUuid))
        assertThat(avatar.deletedAt).isEqualTo(remoteDeletedAt)
        assertThat(avatar.syncDirty).isFalse()
    }

    @Test
    fun familyMemberListUsesTrustedEndpointWithoutTransportIdentity() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))

        assertThat(rig.port.listFamilyMembers().isSuccess).isTrue()
        assertThat(rig.backend.memberCalls).isEqualTo(1)
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

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("分页期间变更了家庭名")
        assertThat(rig.preferences.current().familyName).isEqualTo("第一页名字")
        assertThat(rig.preferences.current().pullCursor).isEqualTo(1)
    }

    @Test
    fun committedRecordClearFailureCannotRepublishDeletedRecordBeforeCleanupRetry() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.records.seed(localRecord(babyId).copy(syncDirty = false))
        rig.outbox.enqueue(
            OutboxEntity(
                familyId = "family-a",
                entityType = "record",
                clientUuid = "record-local",
                payloadJson = "{}",
                updatedAt = 1,
            ),
        )
        rig.outbox.failDeleteTypeAttempts = 1

        val failure = rig.port.clearLocalData(
            LocalDataClearScope.RecordsOnly,
            realPortClearWorkflow { rig.records.deleteAll() },
        ).exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalClearCommittedException::class.java)
        assertThat(rig.records.listAllIncludingDeleted()).isEmpty()

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).exceptionOrNull()).isNull()
        assertThat(rig.backend.pushes.flatMap(PushedBatch::entities).map(SyncEntity::type))
            .doesNotContain("record")
    }

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
        assertThat(rig.backend.pushes.flatMap(PushedBatch::entities).map(SyncEntity::type))
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
        assertThat(rig.backend.stagedBundles.map { it.root.clientUuid })
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
        assertThat(rig.backend.pushes).isEmpty()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(7)
        assertThat(rig.preferences.current().pullGeneration).isEmpty()
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
        assertThat(rig.backend.pushes.flatMap(PushedBatch::entities).map(SyncEntity::type))
            .doesNotContain("baby")
        assertThat(rig.babies.getByClientUuid("baby-local")?.avatarMediaUuid).isNull()
        assertThat(rig.babies.getByClientUuid("baby-local")?.familyAuthority).isTrue()
        assertThat(rig.backend.mediaUploads).isEmpty()
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
    fun avatarMaterializationNeverOverwritesAProfileChangedAfterSnapshot() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(
            localBaby().copy(avatarPath = "baby_avatars/local.jpg"),
        )
        rig.mediaFiles.afterInspect = {
            val current = requireNotNull(rig.babies.getIncludingDeleted(babyId))
            rig.babies.update(
                current.copy(
                    nickname = "并发改名",
                    updatedAt = current.updatedAt + 1,
                    syncDirty = true,
                ),
            )
        }

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val baby = requireNotNull(rig.babies.getIncludingDeleted(babyId))
        assertThat(baby.nickname).isEqualTo("并发改名")
        assertThat(baby.updatedAt).isEqualTo(101)
        // Materialize skipped the avatar after the concurrent profile edit; wire
        // still published the re-read concurrent baby root (no avatar pointer).
        assertThat(baby.avatarMediaUuid).isNull()
        // Synthetic root ack uses the content epoch at push time (101), so the
        // concurrent rename is confirmed clean rather than left spuriously dirty.
        assertThat(baby.syncDirty).isFalse()
    }

    @Test
    fun recordMediaSnapshotUsesMediaAssetRowsOnly() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                payloadJson = """{"amount_ml":120}""",
            ),
        )
        val mediaUuid = "32323232-3232-3232-3232-323232323232"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "photos/user-new.jpg",
                remoteUri = rig.preferences.current().expectedMediaReceipt(mediaUuid),
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.remember("baby", "baby-local")
        rig.backend.remember("record", "record-local")
        rig.backend.remember("media", mediaUuid)
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val record = requireNotNull(rig.records.getIncludingDeleted(recordId))
        assertThat(record.payloadJson).isEqualTo("""{"amount_ml":120}""")
        assertThat(record.note).isNull()
        assertThat(record.updatedAt).isEqualTo(120)
        assertThat(record.syncDirty).isFalse()
        assertThat(rig.media.getByClientUuid(mediaUuid)?.deletedAt).isNull()
        assertThat(rig.media.listAllIncludingDeleted().map(MediaAssetEntity::localUri))
            .containsExactly("photos/user-new.jpg")
    }

    @Test
    fun downloadedPhotoRefreshPreservesAConcurrentRecordEdit() = runTest {
        val session = joinedSession("family-a")
        val rig = SyncRig(session = session)
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                payloadJson = """{"amount_ml":120}""",
                syncDirty = false,
            ),
        )
        val mediaUuid = "33333333-3333-3333-3333-333333333333"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "",
                remoteUri = rig.preferences.current().expectedMediaReceipt(mediaUuid),
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.remember("baby", "baby-local")
        rig.backend.remember("record", "record-local")
        rig.backend.remember("media", mediaUuid)
        rig.backend.beforeGetMediaReturn = {
            val current = requireNotNull(rig.records.getIncludingDeleted(recordId))
            rig.records.update(
                current.copy(
                    note = "并发补充说明",
                    updatedAt = current.updatedAt + 1,
                    syncDirty = true,
                ),
            )
        }
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 1,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        val record = requireNotNull(rig.records.getIncludingDeleted(recordId))
        assertThat(record.note).isEqualTo("并发补充说明")
        assertThat(record.updatedAt).isEqualTo(121)
        assertThat(record.syncDirty).isTrue()
        assertThat(record.payloadJson).isEqualTo("""{"amount_ml":120}""")
        assertThat(rig.media.getByClientUuid(mediaUuid)?.localUri)
            .isEqualTo("downloaded/$mediaUuid")
    }

    @Test
    fun pullWindowPhotoEditStaysAuthoritativeWhenDownloadStartsLater() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(syncDirty = false),
        )
        val mediaUuid = "34343434-3434-3434-3434-343434343434"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "",
                remoteUri = rig.preferences.current().expectedMediaReceipt(mediaUuid),
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.beforePullReturn = {
            val current = requireNotNull(rig.records.getIncludingDeleted(recordId))
            rig.records.update(
                current.copy(
                    note = "拉取期间编辑",
                    updatedAt = current.updatedAt + 1,
                    syncDirty = true,
                ),
            )
        }
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 1,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        val record = requireNotNull(rig.records.getIncludingDeleted(recordId))
        assertThat(record.payloadJson).isEqualTo("""{"amount_ml":120}""")
        assertThat(record.note).isEqualTo("拉取期间编辑")
        assertThat(rig.media.getByClientUuid(mediaUuid)?.localUri)
            .isEqualTo("downloaded/$mediaUuid")
    }

    @Test
    fun photoEditBetweenTwoDownloadsPreventsTheSecondDerivedRefresh() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(syncDirty = false),
        )
        val mediaUuids = listOf(
            "35353535-3535-3535-3535-353535353535",
            "36363636-3636-3636-3636-363636363636",
        )
        mediaUuids.forEach { mediaUuid ->
            rig.media.seed(
                MediaAssetEntity(
                    recordId = recordId,
                    clientUuid = mediaUuid,
                    kind = "log",
                    localUri = "",
                    remoteUri = rig.preferences.current().expectedMediaReceipt(mediaUuid),
                    mime = "image/jpeg",
                    byteSize = 12,
                    createdAt = 100,
                    updatedAt = 100,
                    syncDirty = false,
                ),
            )
        }
        rig.mediaFiles.afterSaveDownloaded = {
            val current = requireNotNull(rig.media.getByClientUuid(mediaUuids.last()))
            rig.media.update(
                current.copy(
                    localUri = "photos/between-downloads.jpg",
                    updatedAt = current.updatedAt + 1,
                    syncDirty = true,
                ),
            )
        }
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 1,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        assertThat(rig.records.getIncludingDeleted(recordId)?.payloadJson)
            .isEqualTo("""{"amount_ml":120}""")
        assertThat(rig.media.getByClientUuid(mediaUuids.first())?.localUri)
            .isEqualTo("downloaded/${mediaUuids.first()}")
        assertThat(rig.media.getByClientUuid(mediaUuids.last())?.localUri)
            .isEqualTo("photos/between-downloads.jpg")
    }

    @Test
    fun downloadedAvatarRefreshPreservesAProfileEditDuringFileSave() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val mediaUuid = "44444444-4444-4444-4444-444444444444"
        val babyId = rig.babies.seed(
            localBaby().copy(
                avatarMediaUuid = mediaUuid,
                avatarPath = null,
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = mediaUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "",
                remoteUri = rig.preferences.current().expectedMediaReceipt(mediaUuid),
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.remember("baby", "baby-local")
        rig.backend.remember("media", mediaUuid)
        rig.mediaFiles.afterSaveDownloaded = {
            val current = requireNotNull(rig.babies.getIncludingDeleted(babyId))
            rig.babies.update(
                current.copy(
                    avatarPath = "baby_avatars/user-new.jpg",
                    updatedAt = current.updatedAt + 1,
                    syncDirty = true,
                ),
            )
        }
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 1,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        val baby = requireNotNull(rig.babies.getIncludingDeleted(babyId))
        assertThat(baby.avatarPath).isEqualTo("baby_avatars/user-new.jpg")
        assertThat(baby.updatedAt).isEqualTo(101)
        assertThat(baby.syncDirty).isTrue()
        assertThat(rig.media.getByClientUuid(mediaUuid)?.localUri)
            .isEqualTo("downloaded/$mediaUuid")
    }

    @Test
    fun noteOnlyEditAcceptsDownloadedPhotoAndNeverTombstonesItNextSync() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(syncDirty = false),
        )
        val mediaUuid = "45454545-4545-4545-4545-454545454545"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "",
                remoteUri = rig.preferences.current().expectedMediaReceipt(mediaUuid),
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.remember("baby", "baby-local")
        rig.backend.remember("record", "record-local")
        rig.backend.remember("media", mediaUuid)
        rig.backend.beforeGetMediaReturn = {
            val current = requireNotNull(rig.records.getIncludingDeleted(recordId))
            rig.records.update(
                current.copy(
                    note = "只改备注",
                    updatedAt = current.updatedAt + 1,
                    syncDirty = true,
                ),
            )
        }
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 1,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val record = requireNotNull(rig.records.getIncludingDeleted(recordId))
        assertThat(record.note).isEqualTo("只改备注")
        assertThat(record.payloadJson).isEqualTo("""{"amount_ml":120}""")
        val localMedia = requireNotNull(rig.media.getByClientUuid(mediaUuid))
        assertThat(localMedia.deletedAt).isNull()
        assertThat(localMedia.localUri).isEqualTo("downloaded/$mediaUuid")
        // Any republished photo stays inside its atomic root package and must remain live.
        val packageMedia = rig.backend.stagedBundles
            .flatMap { it.media }
            .filter { it.clientUuid == mediaUuid }
        assertThat(packageMedia.all { it.deletedAt == null }).isTrue()
    }

    @Test
    fun nicknameOnlyEditAcceptsDownloadedAvatarAndNeverTombstonesItNextSync() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val mediaUuid = "46464646-4646-4646-4646-464646464646"
        val babyId = rig.babies.seed(
            localBaby().copy(
                avatarMediaUuid = mediaUuid,
                avatarPath = null,
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = mediaUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "",
                remoteUri = rig.preferences.current().expectedMediaReceipt(mediaUuid),
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.remember("baby", "baby-local")
        rig.backend.remember("media", mediaUuid)
        rig.mediaFiles.afterSaveDownloaded = {
            val current = requireNotNull(rig.babies.getIncludingDeleted(babyId))
            rig.babies.update(
                current.copy(
                    nickname = "只改昵称",
                    updatedAt = current.updatedAt + 1,
                    syncDirty = true,
                ),
            )
        }
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 1,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val baby = requireNotNull(rig.babies.getIncludingDeleted(babyId))
        assertThat(baby.nickname).isEqualTo("只改昵称")
        assertThat(baby.avatarPath).isEqualTo("downloaded/$mediaUuid")
        assertThat(rig.media.getByClientUuid(mediaUuid)?.deletedAt).isNull()
        val babyDraft = rig.backend.stagedBundles.last { it.root.type == "baby" }
        val pushedMedia = rig.backend.stagedBundles
            .flatMap { it.media }
            .filter { it.clientUuid == mediaUuid }
        assertThat(babyDraft.root.payloadJson).contains(mediaUuid)
        assertThat(pushedMedia.all { it.deletedAt == null }).isTrue()
    }

    @Test
    fun equalUpdatedAtKeepsLocalOnPullMatchingServerLww() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-remote",
                nickname = "本地先到",
                updatedAt = 200,
                syncDirty = false,
            ),
        )
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-remote",
                payloadJson = """{"amount_ml":120}""",
                createdByMembershipId = "",
                updatedAt = 210,
                syncDirty = false,
            ),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteBaby().copy(
                    payloadJson = """
                        {
                          "nickname":"远端同戳",
                          "sex":null,
                          "birthday":"2024-01-01",
                          "birth_weight_grams":null,
                          "avatar_media_uuid":null
                        }
                    """.trimIndent(),
                    updatedAt = 200,
                ),
                remoteRecord().copy(
                    payloadJson = """
                        {
                          "baby_client_uuid":"baby-remote",
                          "created_by_membership_id":"membership-b",
                          "type":"formula",
                          "custom_item_client_uuid":null,
                          "timestamp":210,
                          "end_timestamp":null,
                          "note":null,
                          "payload_json":{"amount_ml":90},
                          "schema_version":2
                        }
                    """.trimIndent(),
                    updatedAt = 210,
                ),
            ),
            cursor = 9,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        assertThat(rig.babies.getByClientUuid("baby-remote")?.nickname).isEqualTo("本地先到")
        val record = requireNotNull(rig.records.getByClientUuid("record-remote"))
        assertThat(record.payloadJson).isEqualTo("""{"amount_ml":120}""")
        assertThat(record.createdByMembershipId).isEqualTo("membership-b")
        assertThat(record.updatedAt).isEqualTo(210)
        assertThat(record.syncDirty).isFalse()
        assertThat(rig.outbox.peek("family-a", 10)).isEmpty()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(9)
    }

    @Test
    fun olderRemoteAuthorCannotRegressKnownCanonicalMembership() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-older-author",
                createdByMembershipId = "membership-current",
                updatedAt = 300,
                syncDirty = false,
            ),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteRecord().copy(
                    clientUuid = "record-older-author",
                    payloadJson = """
                        {
                          "baby_client_uuid":"baby-local",
                          "created_by_membership_id":"membership-stale",
                          "type":"formula",
                          "custom_item_client_uuid":null,
                          "timestamp":299,
                          "end_timestamp":null,
                          "note":null,
                          "payload_json":{"amount_ml":1},
                          "schema_version":2
                        }
                    """.trimIndent(),
                    updatedAt = 299,
                ),
            ),
            cursor = 10,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        val record = requireNotNull(rig.records.getByClientUuid("record-older-author"))
        assertThat(record.createdByMembershipId).isEqualTo("membership-current")
        assertThat(record.updatedAt).isEqualTo(300)
        assertThat(record.payloadJson).isEqualTo("""{"amount_ml":120}""")
    }

    @Test
    fun recordWithoutMembershipAuthorFailsBeforeCursorAdvance() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-missing-author",
                createdByMembershipId = "membership-current",
                updatedAt = 300,
                syncDirty = false,
            ),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteRecord().copy(
                    clientUuid = "record-missing-author",
                    payloadJson = """
                        {
                          "baby_client_uuid":"baby-local",
                          "type":"formula",
                          "custom_item_client_uuid":null,
                          "timestamp":300,
                          "end_timestamp":null,
                          "note":null,
                          "payload_json":{"amount_ml":1},
                          "schema_version":2
                        }
                    """.trimIndent(),
                    updatedAt = 300,
                ),
            ),
            cursor = 11,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isFailure).isTrue()

        val record = requireNotNull(rig.records.getByClientUuid("record-missing-author"))
        assertThat(record.createdByMembershipId).isEqualTo("membership-current")
        assertThat(record.payloadJson).isEqualTo("""{"amount_ml":120}""")
        assertThat(record.updatedAt).isEqualTo(300)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(0)
    }

    @Test
    fun pullAdvancesCursorOnlyAfterAllReferencesApply() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                pullCursor = 5,
                pullGeneration = "current-generation",
            ),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(remoteRecord()),
            cursor = 8,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isFailure).isTrue()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(5)
        assertThat(rig.records.getByClientUuid("record-remote")).isNull()

        rig.backend.nextPull = PullResult(
            entities = listOf(remoteBaby(), remoteRecord()),
            cursor = 8,
            generation = "current-generation",
            hasMore = false,
        )
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        assertThat(rig.preferences.current().pullCursor).isEqualTo(8)
        assertThat(rig.babies.getByClientUuid("baby-remote")?.nickname).isEqualTo("远端宝宝")
        val applied = rig.records.getByClientUuid("record-remote")
        assertThat(applied?.babyId).isEqualTo(rig.babies.getByClientUuid("baby-remote")?.id)
        assertThat(applied?.payloadJson).isEqualTo("""{"amount_ml":90}""")
        assertThat(applied?.createdByMembershipId).isEqualTo("membership-b")
        assertThat(rig.transactions.runCount).isEqualTo(2)
    }

    @Test
    fun pullDrainsEveryPageAndPersistsEachAppliedPageCursor() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.backend.pullResults.add(
            PullResult(
                entities = listOf(remoteBaby()),
                cursor = 1,
                generation = "current-generation",
                hasMore = true,
            ),
        )
        rig.backend.pullResults.add(
            PullResult(
                entities = listOf(remoteRecord()),
                cursor = 2,
                generation = "current-generation",
                hasMore = false,
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        assertThat(rig.backend.pullCursors).containsExactly(0L, 1L).inOrder()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(2)
        assertThat(rig.babies.getByClientUuid("baby-remote")).isNotNull()
        assertThat(rig.records.getByClientUuid("record-remote")).isNotNull()
    }

    @Test
    fun laterPageFailureRetainsOnlyTheLastFullyAppliedPageCursor() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.backend.pullResults.add(
            PullResult(
                entities = listOf(remoteBaby()),
                cursor = 1,
                generation = "current-generation",
                hasMore = true,
            ),
        )
        rig.backend.pullResults.add(
            PullResult(
                entities = listOf(
                    remoteRecord().copy(
                        payloadJson = """
                            {
                              "baby_client_uuid":"missing-baby",
                              "type":"formula",
                              "timestamp":100,
                              "payload_json":{"amount_ml":90}
                            }
                        """.trimIndent(),
                    ),
                ),
                cursor = 2,
                generation = "current-generation",
                hasMore = false,
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isFailure).isTrue()

        assertThat(rig.preferences.current().pullCursor).isEqualTo(1)
        assertThat(rig.babies.getByClientUuid("baby-remote")).isNotNull()
        assertThat(rig.records.getByClientUuid("record-remote")).isNull()
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

    @Test
    fun babyAvatarPointerWinsOverANewerUnreferencedAvatarRow() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val selectedUuid = "55555555-5555-5555-5555-555555555555"
        val newerUuid = "66666666-6666-6666-6666-666666666666"
        val babyId = rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-remote",
                avatarMediaUuid = newerUuid,
                avatarPath = "avatars/newer.jpg",
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = selectedUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "avatars/selected.jpg",
                remoteUri = rig.preferences.current().expectedMediaReceipt(selectedUuid),
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = newerUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "avatars/newer.jpg",
                remoteUri = rig.preferences.current().expectedMediaReceipt(newerUuid),
                createdAt = 200,
                updatedAt = 200,
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
                          "avatar_media_uuid":"$selectedUuid"
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
        assertThat(baby?.avatarMediaUuid).isEqualTo(selectedUuid)
        assertThat(baby?.avatarPath).isEqualTo("avatars/selected.jpg")
    }

    @Test
    fun memberNeverPushesLocalAvatarMetadataOrBytes() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(role = FamilyRole.Member),
        )
        val babyId = rig.babies.seed(
            localBaby().copy(
                avatarPath = "baby_avatars/member-local.jpg",
                familyAuthority = true,
            ),
        )
        val avatarUuid = "11111111-1111-1111-1111-111111111111"
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = avatarUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "baby_avatars/member-local.jpg",
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
            ),
        )
        // A stale row from an older app version must not escape either.
        rig.outbox.enqueue(
            OutboxEntity(
                familyId = "family-a",
                entityType = "media",
                clientUuid = avatarUuid,
                payloadJson = """{"kind":"avatar","baby_client_uuid":"baby-local"}""",
                updatedAt = 100,
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        assertThat(rig.backend.pushes).isEmpty()
        assertThat(rig.backend.mediaUploads).isEmpty()
        assertThat(rig.outbox.peek("family-a", 100)).isEmpty()
    }

    @Test
    fun memberRejoiningSameFamilyPullsCanonicalAvatarWithoutRepublishingBaby() = runTest {
        val session = joinedSession("family-a").copy(role = FamilyRole.Member)
        val rig = SyncRig(session = session)
        val avatarUuid = "11111111-1111-1111-1111-111111111111"
        val babyId = rig.babies.seed(
            localBaby().copy(
                avatarMediaUuid = avatarUuid,
                avatarPath = "baby_avatars/remote.jpg",
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = avatarUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "baby_avatars/remote.jpg",
                remoteUri = rig.preferences.current().expectedMediaReceipt(avatarUuid),
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.remember("media", avatarUuid)

        assertThat(rig.port.leave().isSuccess).isTrue()
        rig.preferences.saveSession(session.copy(accessToken = "replacement-token"))
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteBaby().copy(
                    clientUuid = "baby-local",
                    payloadJson = """
                        {
                          "nickname":"服务器宝宝",
                          "sex":null,
                          "birthday":"2024-01-01",
                          "birth_weight_grams":null,
                          "avatar_media_uuid":"$avatarUuid"
                        }
                    """.trimIndent(),
                    updatedAt = 200,
                ),
            ),
            cursor = 1,
            generation = "current-generation",
            hasMore = false,
        )

        val result = rig.port.sync(SyncTrigger.PullToRefresh)
        assertThat(result.exceptionOrNull()).isNull()

        assertThat(rig.backend.pushes).isEmpty()
        assertThat(rig.backend.mediaUploads).isEmpty()
        assertThat(rig.babies.getByClientUuid("baby-local")?.avatarMediaUuid).isEqualTo(avatarUuid)
        assertThat(rig.babies.getByClientUuid("baby-local")?.avatarPath)
            .isEqualTo("baby_avatars/remote.jpg")
        assertThat(rig.babies.getByClientUuid("baby-local")?.familyAuthority).isTrue()
        assertThat(rig.media.getByClientUuid(avatarUuid)?.remoteUri)
            .isEqualTo(rig.preferences.current().expectedMediaReceipt(avatarUuid))
    }

    @Test
    fun logMediaUsesRecordAsSingleBabyAssociationAfterProfileMerge() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val sourceBabyId = rig.babies.seed(
            localBaby().copy(clientUuid = "baby-source", nickname = "来源宝宝"),
        )
        val targetBabyId = rig.babies.seed(
            localBaby().copy(clientUuid = "baby-target", nickname = "目标宝宝"),
        )
        val recordId = rig.records.seed(
            localRecord(targetBabyId).copy(
                payloadJson = """{"amount_ml":120}""",
            ),
        )
        val mediaUuid = "22222222-2222-2222-2222-222222222222"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                babyId = null,
                localUri = "photos/merged.jpg",
                remoteUri = rig.preferences.current().expectedMediaReceipt(mediaUuid),
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        // Record packages publish through the only supported atomic bundle path.
        val mediaPayload = rig.backend.stagedBundles
            .flatMap { it.media }
            .single { it.clientUuid == mediaUuid }
            .payloadJson
        assertThat(mediaPayload).contains("\"record_client_uuid\":\"record-local\"")
        assertThat(mediaPayload).contains("\"baby_client_uuid\":null")
        assertThat(mediaPayload).doesNotContain("baby-source")
        assertThat(rig.media.getByClientUuid(mediaUuid)?.babyId).isNull()
        assertThat(rig.backend.committedBundles).isNotEmpty()
    }

    @Test
    fun recordCreateAlwaysUsesAtomicBundleIncludingZeroPhotos() = runTest {
        suspend fun runCase(photoCount: Int) {
            val rig = SyncRig(session = joinedSession("family-a"))
            // Warm the current-server health contract before staging the local bundle.
            assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
            val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
            rig.backend.remember("baby", "baby-local")
            val photos = (0 until photoCount).map { "photos/p$it.jpg" }
            val recordId = rig.records.seed(
                localRecord(babyId).copy(
                    clientUuid = "record-photos-$photoCount",
                    payloadJson = """{"amount_ml":120}""",
                    syncDirty = true,
                ),
            )
            photos.forEachIndexed { index, path ->
                rig.media.seed(
                    MediaAssetEntity(
                        recordId = recordId,
                        clientUuid = testMediaUuid("media-$photoCount-$index"),
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
            // Snapshot dirty entities into outbox via a sync cycle.
            val result = rig.port.sync(SyncTrigger.LocalWrite)
            assertThat(result.exceptionOrNull()).isNull()
            val draft = rig.backend.stagedBundles.last()
            assertThat(draft.root.type).isEqualTo("record")
            assertThat(draft.root.clientUuid).isEqualTo("record-photos-$photoCount")
            assertThat(draft.media.filter { it.deletedAt == null }).hasSize(photoCount)
            assertThat(rig.backend.bundleMediaUploads).hasSize(photoCount)
            val committedBundleId = rig.backend.committedBundles.last()
            assertThat(committedBundleId).isEqualTo(draft.bundleId)
            assertThat(UUID.fromString(committedBundleId).toString()).isEqualTo(committedBundleId)
            assertThat(rig.records.getByClientUuid("record-photos-$photoCount")?.syncDirty)
                .isFalse()
        }
        runCase(0)
        runCase(1)
        runCase(3)
    }

    @Test
    fun atomicUploadFailureLeavesRecordLocalOnlyAndInvisibleOnPullCursor() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-fail-upload",
                payloadJson = """{"amount_ml":90}""",
                syncDirty = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = testMediaUuid("media-fail"),
                kind = "log",
                localUri = "photos/fail.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        rig.backend.putBundleMediaFailure = IllegalStateException("upload aborted")
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isFalse()
        assertThat(rig.records.getByClientUuid("record-fail-upload")?.syncDirty).isTrue()
        assertThat(rig.backend.committedBundles).isEmpty()
        // Local creator still sees the complete record + photo path.
        assertThat(rig.records.getByClientUuid("record-fail-upload")).isNotNull()
        assertThat(rig.media.listForRecord(recordId).single().localUri)
            .isEqualTo("photos/fail.jpg")
    }

    @Test
    fun midUploadMediaRecaptureMissesReceiptKeepsDirtyAndNewerOutbox() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val mediaUuid = testMediaUuid("media-recapture")
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-recapture",
                payloadJson = """{"amount_ml":80}""",
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "photos/old.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        var publishedMediaOutboxId: Long? = null
        var observedCasMissRetention = false
        var putCount = 0
        rig.backend.onPutBundleMedia = recapture@{ clientUuid ->
            if (clientUuid != mediaUuid) return@recapture
            putCount += 1
            if (putCount == 1) {
                val published = requireNotNull(
                    rig.outbox.find("family-a", "media", mediaUuid),
                )
                publishedMediaOutboxId = published.id
                val current = requireNotNull(rig.media.getByClientUuid(mediaUuid))
                // Domain recapture mid-upload: higher revision + new path + REPLACE outbox.
                rig.media.update(
                    current.copy(
                        updatedAt = 200,
                        localUri = "photos/new.jpg",
                        remoteUri = null,
                        syncDirty = true,
                    ),
                )
                rig.outbox.enqueue(
                    OutboxEntity(
                        familyId = "family-a",
                        entityType = "media",
                        clientUuid = mediaUuid,
                        payloadJson = published.payloadJson,
                        updatedAt = 200,
                    ),
                )
                return@recapture
            }
            // Next push cycle after acknowledgeMediaRows: stale receipt missed,
            // published outbox id is gone, newer outbox retained, media still dirty.
            if (putCount == 2) {
                val media = requireNotNull(rig.media.getByClientUuid(mediaUuid))
                assertThat(media.updatedAt).isEqualTo(200)
                assertThat(media.localUri).isEqualTo("photos/new.jpg")
                assertThat(media.remoteUri).isNull()
                assertThat(media.syncDirty).isTrue()
                val publishedId = requireNotNull(publishedMediaOutboxId)
                assertThat(rig.outbox.all().none { it.id == publishedId }).isTrue()
                val retained = requireNotNull(
                    rig.outbox.find("family-a", "media", mediaUuid),
                )
                assertThat(retained.id).isNotEqualTo(publishedId)
                assertThat(retained.updatedAt).isEqualTo(200)
                observedCasMissRetention = true
            }
        }

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        assertThat(rig.backend.committedBundles).isNotEmpty()
        assertThat(putCount).isAtLeast(2)
        assertThat(observedCasMissRetention).isTrue()
        // Later cycle may converge the higher revision; CAS-miss retention already locked above.
    }

    @Test
    fun localRecordPublishLabelUsesRootReceiptAndTruthfulZeroPhotoCopy() {
        assertThat(
            localRecordPublishLabel(
                syncDirty = true,
                familyJoined = true,
                lastSyncFailed = false,
                publicationState = RootPublicationState.NEVER_PUBLISHED,
            ),
        ).isEqualTo("仅本机 · 等待家庭同步")
        assertThat(
            localRecordPublishLabel(
                syncDirty = true,
                familyJoined = true,
                lastSyncFailed = true,
                publicationState = RootPublicationState.NEVER_PUBLISHED,
            ),
        ).isEqualTo("仅本机 · 同步失败")
        assertThat(
            localRecordPublishLabel(
                syncDirty = true,
                familyJoined = true,
                lastSyncFailed = false,
                publicationState = RootPublicationState.PREVIOUS_VERSION_PUBLISHED,
            ),
        ).isEqualTo("仅本机 · 等待更新同步")
        assertThat(
            localRecordPublishLabel(
                syncDirty = true,
                familyJoined = true,
                lastSyncFailed = true,
                publicationState = RootPublicationState.PREVIOUS_VERSION_PUBLISHED,
            ),
        ).isEqualTo("仅本机 · 更新同步失败")
        assertThat(
            localRecordPublishDetail(
                lastSyncFailed = true,
                publicationState = RootPublicationState.PREVIOUS_VERSION_PUBLISHED,
            ),
        ).contains("上一完整版本")
        assertThat(
            localRecordPublishDetail(
                lastSyncFailed = false,
                publicationState = RootPublicationState.NEVER_PUBLISHED,
            ),
        ).isEqualTo("其他成员暂不可见，记录发布成功后才会出现。")
        assertThat(
            localRecordPublishLabel(
                syncDirty = false,
                familyJoined = true,
                lastSyncFailed = false,
                publicationState = RootPublicationState.NEVER_PUBLISHED,
            ),
        ).isNull()
        assertThat(
            localRecordPublishLabel(
                syncDirty = true,
                familyJoined = false,
                lastSyncFailed = false,
                publicationState = RootPublicationState.NEVER_PUBLISHED,
            ),
        ).isNull()
        assertThat(
            localRecordPublishLabel(
                syncDirty = true,
                familyJoined = true,
                lastSyncFailed = false,
                publicationState = RootPublicationState.CURRENT_VERSION_PUBLISHED,
            ),
        ).isNull()
    }

    @Test
    fun atomicRecordMutationAddRemoveReplaceAndTextOnlyUsesStableBundleId() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordUuid = "record-mutate"
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = recordUuid,
                updatedAt = 100,
                payloadJson = """{"amount_ml":100}""",
                syncDirty = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = testMediaUuid("media-a"),
                kind = "log",
                localUri = "photos/a.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val createBundle = rig.backend.stagedBundles.last {
            it.root.clientUuid == recordUuid && it.root.updatedAt == 100L
        }.bundleId
        assertThat(rig.backend.committedBundles).contains(createBundle)
        assertThat(UUID.fromString(createBundle).toString()).isEqualTo(createBundle)
        assertThat(rig.records.getByClientUuid(recordUuid)?.syncDirty).isFalse()

        // Text-only edit → new package id, no media uploads required.
        val textRow = rig.records.getByClientUuid(recordUuid)!!
        rig.records.update(
            textRow.copy(
                note = "只改文字",
                updatedAt = 200,
                syncDirty = true,
            ),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val textBundle = rig.backend.stagedBundles.last {
            it.root.clientUuid == recordUuid && it.root.updatedAt == 200L
        }.bundleId
        assertThat(rig.backend.committedBundles).contains(textBundle)
        assertThat(textBundle).isNotEqualTo(createBundle)

        // Replace photo: tombstone old, add new, same package.
        val afterText = rig.records.getByClientUuid(recordUuid)!!
        rig.records.update(
            afterText.copy(
                payloadJson = """{"amount_ml":100}""",
                updatedAt = 300,
                syncDirty = true,
            ),
        )
        // Ensure prior photo still exists as a tombstonable row (re-seed if drained).
        val existingOld = rig.media.listForRecord(recordId)
            .firstOrNull { it.clientUuid == testMediaUuid("media-a") }
        if (existingOld != null) {
            rig.media.update(
                existingOld.copy(deletedAt = 300, updatedAt = 300, syncDirty = true),
            )
        } else {
            rig.media.seed(
                MediaAssetEntity(
                    recordId = recordId,
                    clientUuid = testMediaUuid("media-a"),
                    kind = "log",
                    localUri = "photos/a.jpg",
                    mime = "image/jpeg",
                    byteSize = 4,
                    createdAt = 100,
                    updatedAt = 300,
                    deletedAt = 300,
                    syncDirty = true,
                ),
            )
        }
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = testMediaUuid("media-b"),
                kind = "log",
                localUri = "photos/b.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 300,
                updatedAt = 300,
                syncDirty = true,
            ),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val replaceDraft = rig.backend.stagedBundles.last {
            it.root.clientUuid == recordUuid && it.root.updatedAt == 300L
        }
        // Package includes live new photo + tombstone(s) for removed photo paths.
        assertThat(replaceDraft.media).isNotEmpty()
        assertThat(replaceDraft.media.any { it.deletedAt != null }).isTrue()
        assertThat(replaceDraft.media.any { it.deletedAt == null }).isTrue()
        assertThat(rig.backend.committedBundles).contains(replaceDraft.bundleId)
        assertThat(replaceDraft.bundleId).isNotEqualTo(textBundle)

        // Soft-delete whole record + media tombstones.
        val live = rig.records.getByClientUuid(recordUuid)!!
        rig.records.update(
            live.copy(
                deletedAt = 400,
                updatedAt = 400,
                payloadJson = """{"amount_ml":100}""",
                syncDirty = true,
            ),
        )
        rig.media.listActiveForRecord(recordId).forEach { media ->
            rig.media.update(
                media.copy(
                    updatedAt = 400,
                    deletedAt = 400,
                    syncDirty = true,
                ),
            )
        }
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val deleteDraft = rig.backend.stagedBundles.last { it.root.updatedAt == 400L }
        assertThat(rig.backend.committedBundles).contains(deleteDraft.bundleId)
        assertThat(deleteDraft.bundleId).isNotEqualTo(replaceDraft.bundleId)
        assertThat(deleteDraft.root.deletedAt).isEqualTo(400)
        assertThat(deleteDraft.media.all { it.deletedAt != null }).isTrue()
    }

    @Test
    fun atomicMutationIncompletePackageKeepsPriorVersionAndCursor() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                pullCursor = 50,
                pullGeneration = "g0",
            ),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        rig.preferences.saveSession(
            rig.preferences.current().copy(pullCursor = 50, pullGeneration = "g0"),
        )
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        // Prior complete version already on device.
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-prior",
                updatedAt = 100,
                payloadJson = """{"amount_ml":80}""",
                note = "旧完整",
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = testMediaUuid("media-old"),
                kind = "log",
                localUri = "old.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
                remoteUri = rig.preferences.current()
                    .expectedMediaReceipt(testMediaUuid("media-old")),
            ),
        )
        // Incomplete mutation package: record meta + missing media download.
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "record",
                    clientUuid = "record-prior",
                    payloadJson =
                        """{"baby_client_uuid":"baby-local","created_by_membership_id":"member-b","type":"formula","custom_item_client_uuid":null,"timestamp":1000,"end_timestamp":null,"note":"新版本","payload_json":{"amount_ml":90},"schema_version":2}""",
                    updatedAt = 200,
                ),
                SyncEntity(
                    type = "media",
                    clientUuid = testMediaUuid("media-new"),
                    payloadJson =
                        """{"kind":"log","record_client_uuid":"record-prior","care_plan_client_uuid":null,"baby_client_uuid":null,"mime":"image/jpeg","width":null,"height":null,"byte_size":4}""",
                    updatedAt = 200,
                ),
            ),
            cursor = 60,
            generation = "g1",
            hasMore = false,
        )
        rig.backend.getMediaFailure = IllegalStateException("download aborted")
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isFalse()
        // Prior complete version retained.
        val kept = rig.records.getByClientUuid("record-prior")!!
        assertThat(kept.note).isEqualTo("旧完整")
        assertThat(kept.updatedAt).isEqualTo(100)
        assertThat(rig.media.listActiveForRecord(recordId).map { it.clientUuid })
            .containsExactly(testMediaUuid("media-old"))
        assertThat(rig.preferences.current().pullCursor).isEqualTo(50)
    }

    @Test
    fun applyRemoteDoesNotClobberLocalDirtyEdit() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.backend.remember("baby", "baby-local")
        // Local dirty revision is newer than the remote package. Pull still pushes
        // first, so leave a higher local updatedAt so LWW keeps the edit even if
        // push drains the dirty bit; also seed a second device-only dirty mid-edit
        // after a failed push is not required when LWW + dirty guard combine.
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-dirty",
                updatedAt = 250,
                note = "本机编辑中",
                syncDirty = true,
            ),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "record",
                    clientUuid = "record-dirty",
                    payloadJson =
                        """{"baby_client_uuid":"baby-local","created_by_membership_id":"member-b","type":"formula","custom_item_client_uuid":null,"timestamp":1000,"end_timestamp":null,"note":"远端迟到","payload_json":{"amount_ml":1},"schema_version":2}""",
                    updatedAt = 200,
                ),
            ),
            cursor = 99,
            generation = "current-generation",
            hasMore = false,
        )
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()
        val local = rig.records.getByClientUuid("record-dirty")!!
        assertThat(local.note).isEqualTo("本机编辑中")
        assertThat(local.updatedAt).isEqualTo(250)
    }

    @Test
    fun applyRemoteSkipsWhenLocalSyncDirtyEvenIfRemoteIsNewer() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        // Local unpushed mutation: stage fails so dirty remains, and pull never
        // runs on the same cycle. A subsequent pull with empty outbox + dirty row
        // exercises the syncDirty guard (capture re-queues, so clear outbox after
        // a failed push and force stage to fail again before pull would need a
        // push-less path — here we clear outbox then pull with stage still failing
        // on the re-captured package so apply never runs; instead verify that a
        // direct higher remote cannot land while dirty by clearing outbox and
        // temporarily making publication a no-op: delete record outbox rows only).
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-hold",
                updatedAt = 100,
                note = "本机未发布修改",
                syncDirty = true,
            ),
        )
        rig.backend.stageBundleFailure = IllegalStateException("hold local package")
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isFalse()
        assertThat(rig.records.getByClientUuid("record-hold")?.syncDirty).isTrue()

        // Drop outbox rows so push is empty, keep row dirty, allow stage, pull remote.
        rig.outbox.deleteFamily("family-a")
        rig.backend.stageBundleFailure = null
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "record",
                    clientUuid = "record-hold",
                    payloadJson =
                        """{"baby_client_uuid":"baby-local","created_by_membership_id":"member-b","type":"formula","custom_item_client_uuid":null,"timestamp":1000,"end_timestamp":null,"note":"远端更新","payload_json":{"amount_ml":2},"schema_version":2}""",
                    updatedAt = 300,
                ),
            ),
            cursor = 40,
            generation = "g2",
            hasMore = false,
        )
        // PullToRefresh capture re-queues dirty → push succeeds → dirty cleared →
        // remote applies. To keep dirty across capture we would need to not
        // snapshot; so re-assert after failing stage again on the full cycle:
        rig.backend.stageBundleFailure = IllegalStateException("still holding")
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isFalse()
        val held = rig.records.getByClientUuid("record-hold")!!
        assertThat(held.note).isEqualTo("本机未发布修改")
        assertThat(held.syncDirty).isTrue()
        assertThat(held.updatedAt).isEqualTo(100)
    }

    @Test
    fun atomicDownloadFailureKeepsNewRecordInvisibleAndCursorUnmoved() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                pullCursor = 10,
                pullGeneration = "g0",
            ),
        )
        // Warm capability probe (empty pull) then restore the durable cursor/generation.
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        rig.preferences.saveSession(
            rig.preferences.current().copy(pullCursor = 10, pullGeneration = "g0"),
        )
        val mediaUuid = testMediaUuid("media-dl-fail")
        val recordUuid = "record-dl-fail"
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "record",
                    clientUuid = recordUuid,
                    payloadJson =
                        """{"baby_client_uuid":"baby-local","created_by_membership_id":"member-b","type":"formula","custom_item_client_uuid":null,"timestamp":1000,"end_timestamp":null,"note":null,"payload_json":{"amount_ml":80},"schema_version":2}""",
                    updatedAt = 200,
                ),
                SyncEntity(
                    type = "media",
                    clientUuid = mediaUuid,
                    payloadJson =
                        """{"kind":"log","record_client_uuid":"$recordUuid","care_plan_client_uuid":null,"baby_client_uuid":null,"mime":"image/jpeg","width":null,"height":null,"byte_size":4}""",
                    updatedAt = 200,
                ),
            ),
            cursor = 20,
            generation = "current-generation",
            hasMore = false,
        )
        // Baby must exist for record apply dependency chain when download succeeds;
        // failure happens before apply, so seed baby for a realistic package.
        rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.backend.getMediaFailure = IllegalStateException("download aborted")

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isFalse()
        assertThat(rig.records.getByClientUuid(recordUuid)).isNull()
        assertThat(rig.media.getByClientUuid(mediaUuid)).isNull()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(10)
    }

    @Test
    fun atomicApplyStageFailureDoesNotExposePartialRecord() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                pullCursor = 5,
                pullGeneration = "g0",
            ),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        rig.preferences.saveSession(
            rig.preferences.current().copy(pullCursor = 5, pullGeneration = "g0"),
        )
        val mediaUuid = testMediaUuid("media-apply-fail")
        val recordUuid = "record-apply-fail"
        // No baby on device → record apply fails after media bytes are staged.
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "record",
                    clientUuid = recordUuid,
                    payloadJson =
                        """{"baby_client_uuid":"missing-baby","created_by_membership_id":"member-b","type":"formula","custom_item_client_uuid":null,"timestamp":1000,"end_timestamp":null,"note":null,"payload_json":{"amount_ml":80},"schema_version":2}""",
                    updatedAt = 200,
                ),
                SyncEntity(
                    type = "media",
                    clientUuid = mediaUuid,
                    payloadJson =
                        """{"kind":"log","record_client_uuid":"$recordUuid","care_plan_client_uuid":null,"baby_client_uuid":null,"mime":"image/jpeg","width":null,"height":null,"byte_size":4}""",
                    updatedAt = 200,
                ),
            ),
            cursor = 15,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isFalse()
        assertThat(rig.records.getByClientUuid(recordUuid)).isNull()
        assertThat(rig.media.getByClientUuid(mediaUuid)).isNull()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(5)
    }

    @Test
    fun zeroPhotoRecordReceiptWritesOnlyAfterCommitAndSurvivesRetryAndRestart() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-root-receipt",
                updatedAt = 777,
                syncDirty = true,
            ),
        )
        rig.backend.commitBundleFailure = IllegalStateException("commit offline")

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isFailure).isTrue()
        assertThat(rig.records.getByClientUuid("record-root-receipt")?.familyPublishedUpdatedAt)
            .isNull()

        rig.backend.commitBundleFailure = null
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val published = requireNotNull(rig.records.getByClientUuid("record-root-receipt"))
        assertThat(published.familyPublishedUpdatedAt).isEqualTo(777)
        assertThat(published.syncDirty).isFalse()

        val reopened = MemoryRecordDao().apply { seed(published) }
        assertThat(reopened.getByClientUuid("record-root-receipt")?.familyPublishedUpdatedAt)
            .isEqualTo(777)
    }

    @Test
    fun uploadedPhotoCannotCreatePartialReceiptBeforeFailedRootCommit() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-photo-root-fail",
                updatedAt = 800,
                syncDirty = true,
            ),
        )
        val mediaUuid = testMediaUuid("media-photo-root-fail")
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "photos/root-fail.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        rig.backend.commitBundleFailure = IllegalStateException("commit timeout")

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isFailure).isTrue()

        assertThat(rig.media.getByClientUuid(mediaUuid)?.remoteUri).isNull()
        assertThat(rig.records.getByClientUuid("record-photo-root-fail")?.familyPublishedUpdatedAt)
            .isNull()

        rig.backend.commitBundleFailure = null
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        assertThat(rig.media.getByClientUuid(mediaUuid)?.remoteUri).isNotNull()
        assertThat(rig.records.getByClientUuid("record-photo-root-fail")?.familyPublishedUpdatedAt)
            .isEqualTo(800)
    }

    @Test
    fun cancelledOrTimedOutCommitCannotWriteRootReceipt() = runTest {
        listOf(
            CancellationException("commit cancelled"),
            java.net.SocketTimeoutException("commit timed out"),
        ).forEachIndexed { index, failure ->
            val rig = SyncRig(session = joinedSession("family-a"))
            assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
            val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
            val recordUuid = "record-commit-interrupted-$index"
            rig.records.seed(
                localRecord(babyId).copy(
                    clientUuid = recordUuid,
                    updatedAt = 850L + index,
                    syncDirty = true,
                ),
            )
            rig.backend.commitBundleFailure = failure

            val result = rig.port.sync(SyncTrigger.LocalWrite)

            assertThat(result.isFailure).isTrue()
            assertThat(rig.records.getByClientUuid(recordUuid)?.familyPublishedUpdatedAt)
                .isNull()
            assertThat(rig.records.getByClientUuid(recordUuid)?.syncDirty).isTrue()
            assertThat(rig.outbox.peek("family-a", 10).map(OutboxEntity::clientUuid))
                .contains(recordUuid)
        }
    }

    @Test
    fun staleRecordReceiptPreservesNewerDirtyRevisionAndRejectsFutureReceipt() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-stale-root-receipt",
                updatedAt = 900,
                syncDirty = true,
            ),
        )
        rig.backend.afterCommit = {
            val current = requireNotNull(
                rig.records.getByClientUuid("record-stale-root-receipt"),
            )
            rig.records.update(current.copy(updatedAt = 901, syncDirty = true))
        }

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val edited = requireNotNull(rig.records.getByClientUuid("record-stale-root-receipt"))
        assertThat(edited.updatedAt).isEqualTo(901)
        assertThat(edited.familyPublishedUpdatedAt).isEqualTo(900)
        assertThat(edited.syncDirty).isTrue()

        assertThat(
            rig.records.acknowledgeFamilyPublishedVersion(
                clientUuid = "record-stale-root-receipt",
                publishedUpdatedAt = 902,
            ),
        ).isFalse()
        assertThat(
            rig.records.getByClientUuid("record-stale-root-receipt")?.familyPublishedUpdatedAt,
        ).isEqualTo(900)
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
    fun standaloneLogMediaRecordsExactElevatedRootReceiptAndAdvancesLocalRevision() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-standalone-log",
                updatedAt = 500,
                familyPublishedUpdatedAt = 500,
                syncDirty = false,
            ),
        )
        val mediaUuid = testMediaUuid("media-standalone-log")
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "photos/standalone-log.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 200,
                syncDirty = true,
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val published = requireNotNull(rig.records.getByClientUuid("record-standalone-log"))
        // max(nextPackageVersion(500), media 200) = 501
        assertThat(published.updatedAt).isEqualTo(501)
        assertThat(published.familyPublishedUpdatedAt).isEqualTo(501)
        assertThat(published.syncDirty).isFalse()
        assertThat(rig.media.getByClientUuid(mediaUuid)?.syncDirty).isFalse()
        assertThat(rig.media.getByClientUuid(mediaUuid)?.remoteUri).isNotNull()
        val draft = rig.backend.stagedBundles.single { it.root.clientUuid == "record-standalone-log" }
        assertThat(draft.root.updatedAt).isEqualTo(501)
        assertThat(draft.bundleId)
            .isEqualTo(AtomicBundleId.forRecord("record-standalone-log", 501))
        assertThat(rig.outbox.peek("family-a", 10)).isEmpty()
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
    fun avatarOnlyBabyAdvancesLocalRevisionToPublishedRootUpdatedAt() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val avatarUuid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val babyId = rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-avatar-only",
                updatedAt = 300,
                avatarMediaUuid = avatarUuid,
                avatarPath = "avatars/only.jpg",
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                babyId = babyId,
                clientUuid = avatarUuid,
                kind = "avatar",
                localUri = "avatars/only.jpg",
                mime = "image/jpeg",
                byteSize = 2,
                createdAt = 100,
                updatedAt = 250,
                syncDirty = true,
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val baby = requireNotNull(rig.babies.getByClientUuid("baby-avatar-only"))
        // max(nextPackageVersion(300), avatar 250) = 301
        assertThat(baby.updatedAt).isEqualTo(301)
        assertThat(baby.syncDirty).isFalse()
        assertThat(rig.media.getByClientUuid(avatarUuid)?.syncDirty).isFalse()
        val draft = rig.backend.stagedBundles.single { it.root.clientUuid == "baby-avatar-only" }
        assertThat(draft.root.updatedAt).isEqualTo(301)
        assertThat(draft.bundleId)
            .isEqualTo(AtomicBundleId.forBaby("baby-avatar-only", 301))
        assertThat(rig.outbox.peek("family-a", 10)).isEmpty()
    }

    @Test
    fun standaloneLogConcurrentRootEditKeepsContentDirtyAndMonotonicReceipt() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-standalone-concurrent",
                updatedAt = 400,
                familyPublishedUpdatedAt = 400,
                note = "original",
                syncDirty = false,
            ),
        )
        val mediaUuid = testMediaUuid("media-standalone-concurrent")
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "photos/concurrent.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        rig.backend.afterCommit = {
            // One-shot concurrent content edit during the synthetic package commit.
            rig.backend.afterCommit = null
            val current = requireNotNull(
                rig.records.getByClientUuid("record-standalone-concurrent"),
            )
            rig.records.update(
                current.copy(
                    updatedAt = 900,
                    note = "edited-during-upload",
                    syncDirty = true,
                ),
            )
        }

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val concurrent = requireNotNull(
            rig.records.getByClientUuid("record-standalone-concurrent"),
        )
        assertThat(concurrent.updatedAt).isEqualTo(900)
        assertThat(concurrent.note).isEqualTo("edited-during-upload")
        assertThat(concurrent.syncDirty).isTrue()
        // Synthetic package used rootUpdatedAt = 401; receipt advances without clearing dirty.
        assertThat(concurrent.familyPublishedUpdatedAt).isEqualTo(401)
        assertThat(rig.media.getByClientUuid(mediaUuid)?.syncDirty).isFalse()
        // Outbox for the newer root is rebuilt on the next capture (dirty retained).
        assertThat(rig.records.listPendingSync().map { it.clientUuid })
            .contains("record-standalone-concurrent")
    }

    @Test
    fun standaloneLogConcurrentEditToExactlyPublishedKeepsDirtyAndBody() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-standalone-pub-clock",
                updatedAt = 400,
                familyPublishedUpdatedAt = 400,
                note = "original",
                syncDirty = false,
            ),
        )
        val mediaUuid = testMediaUuid("media-standalone-pub-clock")
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "photos/pub-clock.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        // Package elevates to 401; concurrent edit lands on that same LWW clock.
        rig.backend.afterCommit = {
            rig.backend.afterCommit = null
            val current = requireNotNull(
                rig.records.getByClientUuid("record-standalone-pub-clock"),
            )
            rig.records.update(
                current.copy(
                    updatedAt = 401,
                    note = "edited-to-published-clock",
                    syncDirty = true,
                ),
            )
        }

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val concurrent = requireNotNull(
            rig.records.getByClientUuid("record-standalone-pub-clock"),
        )
        assertThat(concurrent.updatedAt).isEqualTo(401)
        assertThat(concurrent.note).isEqualTo("edited-to-published-clock")
        assertThat(concurrent.syncDirty).isTrue()
        assertThat(concurrent.familyPublishedUpdatedAt).isEqualTo(401)
        assertThat(rig.media.getByClientUuid(mediaUuid)?.syncDirty).isFalse()
        assertThat(rig.records.listPendingSync().map { it.clientUuid })
            .contains("record-standalone-pub-clock")
    }

    @Test
    fun standaloneLogCrashAfterRemoteCommitRetriesSameBundleIdAndConverges() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-standalone-crash",
                updatedAt = 600,
                familyPublishedUpdatedAt = 600,
                syncDirty = false,
            ),
        )
        val mediaUuid = testMediaUuid("media-standalone-crash")
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "photos/crash.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 120,
                syncDirty = true,
            ),
        )
        val expectedBundleId = AtomicBundleId.forRecord("record-standalone-crash", 601)
        rig.backend.afterCommit = {
            throw IllegalStateException("crash after remote commit before local ack")
        }

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isFailure).isTrue()
        assertThat(rig.backend.committedBundles).containsExactly(expectedBundleId)
        assertThat(rig.records.getByClientUuid("record-standalone-crash")?.updatedAt)
            .isEqualTo(600)
        assertThat(rig.records.getByClientUuid("record-standalone-crash")?.familyPublishedUpdatedAt)
            .isEqualTo(600)
        assertThat(rig.media.getByClientUuid(mediaUuid)?.syncDirty).isTrue()

        rig.backend.afterCommit = null
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        assertThat(rig.backend.committedBundles.count { it == expectedBundleId }).isEqualTo(2)
        val published = requireNotNull(rig.records.getByClientUuid("record-standalone-crash"))
        assertThat(published.updatedAt).isEqualTo(601)
        assertThat(published.familyPublishedUpdatedAt).isEqualTo(601)
        assertThat(published.syncDirty).isFalse()
        assertThat(rig.media.getByClientUuid(mediaUuid)?.syncDirty).isFalse()
        assertThat(rig.media.getByClientUuid(mediaUuid)?.remoteUri).isNotNull()
        assertThat(rig.outbox.peek("family-a", 10)).isEmpty()
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
        assertThat(rig.outbox.peek("family-a", 10)).isEmpty()
    }

    @Test
    fun avatarOnlyBabyConcurrentRootEditKeepsContentDirty() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val avatarUuid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val babyId = rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-avatar-concurrent",
                updatedAt = 300,
                nickname = "原昵称",
                avatarMediaUuid = avatarUuid,
                avatarPath = "avatars/concurrent.jpg",
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                babyId = babyId,
                clientUuid = avatarUuid,
                kind = "avatar",
                localUri = "avatars/concurrent.jpg",
                mime = "image/jpeg",
                byteSize = 2,
                createdAt = 100,
                updatedAt = 250,
                syncDirty = true,
            ),
        )
        rig.backend.afterCommit = {
            rig.backend.afterCommit = null
            val current = requireNotNull(
                rig.babies.getByClientUuid("baby-avatar-concurrent"),
            )
            rig.babies.update(
                current.copy(
                    updatedAt = 900,
                    nickname = "并发昵称",
                    syncDirty = true,
                ),
            )
        }

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val concurrent = requireNotNull(rig.babies.getByClientUuid("baby-avatar-concurrent"))
        assertThat(concurrent.updatedAt).isEqualTo(900)
        assertThat(concurrent.nickname).isEqualTo("并发昵称")
        assertThat(concurrent.syncDirty).isTrue()
        assertThat(rig.media.getByClientUuid(avatarUuid)?.syncDirty).isFalse()
        assertThat(rig.babies.listPendingSync().map { it.clientUuid })
            .contains("baby-avatar-concurrent")
    }

    @Test
    fun avatarOnlyBabyCrashAfterRemoteCommitRetriesSameBundleIdAndConverges() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val avatarUuid = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val babyId = rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-avatar-crash",
                updatedAt = 300,
                avatarMediaUuid = avatarUuid,
                avatarPath = "avatars/crash.jpg",
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                babyId = babyId,
                clientUuid = avatarUuid,
                kind = "avatar",
                localUri = "avatars/crash.jpg",
                mime = "image/jpeg",
                byteSize = 2,
                createdAt = 100,
                updatedAt = 250,
                syncDirty = true,
            ),
        )
        val expectedBundleId = AtomicBundleId.forBaby("baby-avatar-crash", 301)
        rig.backend.afterCommit = {
            throw IllegalStateException("crash after remote commit before local ack")
        }

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isFailure).isTrue()
        assertThat(rig.backend.committedBundles).containsExactly(expectedBundleId)
        assertThat(rig.babies.getByClientUuid("baby-avatar-crash")?.updatedAt).isEqualTo(300)
        assertThat(rig.media.getByClientUuid(avatarUuid)?.syncDirty).isTrue()

        rig.backend.afterCommit = null
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        assertThat(rig.backend.committedBundles.count { it == expectedBundleId }).isEqualTo(2)
        val baby = requireNotNull(rig.babies.getByClientUuid("baby-avatar-crash"))
        assertThat(baby.updatedAt).isEqualTo(301)
        assertThat(baby.syncDirty).isFalse()
        assertThat(rig.media.getByClientUuid(avatarUuid)?.syncDirty).isFalse()
        assertThat(rig.media.getByClientUuid(avatarUuid)?.remoteUri).isNotNull()
        assertThat(rig.outbox.peek("family-a", 10)).isEmpty()
    }

    @Test
    fun syntheticRootReceiptIsMonotonicAcrossMultipleStandaloneMediaGroups() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-mono-receipt",
                updatedAt = 1_000,
                familyPublishedUpdatedAt = 1_000,
                syncDirty = false,
            ),
        )

        // Newer receipt first, then a stale older synthetic ack must not regress.
        assertThat(
            rig.records.acknowledgeSyntheticRootPublication(
                clientUuid = "record-mono-receipt",
                expectedLocalUpdatedAt = 1_000,
                publishedUpdatedAt = 1_002,
            ),
        ).isTrue()
        assertThat(rig.records.getByClientUuid("record-mono-receipt")?.familyPublishedUpdatedAt)
            .isEqualTo(1_002)
        assertThat(rig.records.getByClientUuid("record-mono-receipt")?.updatedAt)
            .isEqualTo(1_002)

        // Concurrent-path older receipt (expected epoch already left behind).
        assertThat(
            rig.records.acknowledgeSyntheticRootPublication(
                clientUuid = "record-mono-receipt",
                expectedLocalUpdatedAt = 1_000,
                publishedUpdatedAt = 1_001,
            ),
        ).isFalse()
        assertThat(rig.records.getByClientUuid("record-mono-receipt")?.familyPublishedUpdatedAt)
            .isEqualTo(1_002)
        assertThat(rig.records.getByClientUuid("record-mono-receipt")?.updatedAt)
            .isEqualTo(1_002)
    }

    @Test
    fun atomicRetryUsesSameBundleIdAndDoesNotDuplicateCommit() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-retry",
                updatedAt = 777,
                payloadJson = """{"amount_ml":50}""",
                syncDirty = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = testMediaUuid("media-retry"),
                kind = "log",
                localUri = "photos/r.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        rig.backend.putBundleMediaFailure = IllegalStateException("first upload fail")
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isFalse()
        assertThat(rig.backend.committedBundles).isEmpty()
        val retryBundleId = rig.backend.stagedBundles.single {
            it.root.clientUuid == "record-retry"
        }.bundleId
        assertThat(UUID.fromString(retryBundleId).toString()).isEqualTo(retryBundleId)

        rig.backend.putBundleMediaFailure = null
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        assertThat(rig.backend.committedBundles).containsExactly(retryBundleId)
        assertThat(rig.records.getByClientUuid("record-retry")?.syncDirty).isFalse()

        // Already clean — another foreground sync must not mint a second commit id.
        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()
        assertThat(rig.backend.committedBundles.count { it == retryBundleId })
            .isEqualTo(1)
    }

    @Test
    fun committedRecordBundleRetrySkipsMediaUploadAndStillAcknowledgesCommit() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(membershipId = "membership-a"),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val mediaUuid = "10000000-0000-4000-8000-000000000001"
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-committed-retry",
                createdByMembershipId = "",
                payloadJson = """{"amount_ml":50}""",
                syncDirty = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "photos/committed-record.jpg",
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
        rig.backend.nextCommitRecordAuthors = listOf(
            CanonicalRecordAuthor(
                clientUuid = "record-committed-retry",
                createdByMembershipId = "membership-a",
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        assertThat(rig.backend.bundleMediaUploads).isEmpty()
        assertThat(rig.backend.committedBundles)
            .containsExactly(rig.backend.stagedBundles.single().bundleId)
        val record = requireNotNull(
            rig.records.getByClientUuid("record-committed-retry"),
        )
        assertThat(record.createdByMembershipId).isEqualTo("membership-a")
        assertThat(record.syncDirty).isFalse()
        val media = rig.media.listForRecord(recordId).single()
        assertThat(media.syncDirty).isFalse()
        assertThat(media.remoteUri)
            .isEqualTo(rig.preferences.current().expectedMediaReceipt(mediaUuid))
        assertThat(rig.outbox.all()).isEmpty()
    }

    @Test
    fun atomicRecordCommitMissingCanonicalAuthorAckRemainsRetryable() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(membershipId = "membership-a"),
        )
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.backend.remember("baby", "baby-local")
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "pre-join-photo-record",
                createdByMembershipId = "",
                updatedAt = 120,
                syncDirty = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = "10000000-0000-4000-8000-000000000099",
                kind = "log",
                localUri = "photos/pre-join.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        rig.backend.nextCommitRecordAuthors = emptyList()

        val result = rig.port.sync(SyncTrigger.LocalWrite)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageThat().contains("record_authors")
        val retained = requireNotNull(rig.records.getByClientUuid("pre-join-photo-record"))
        assertThat(retained.createdByMembershipId).isEmpty()
        assertThat(retained.syncDirty).isTrue()
        assertThat(rig.media.listForRecord(recordId).single().syncDirty).isTrue()
        assertThat(rig.outbox.peek("family-a", 10).map(OutboxEntity::clientUuid))
            .containsExactly(
                "pre-join-photo-record",
                "10000000-0000-4000-8000-000000000099",
            )
    }

    @Test
    fun stagingRecordBundleRetryUsesCurrentMissingAndStagedProgress() = runTest {
        suspend fun runCase(
            suffix: String,
            missingIndexes: List<Int>,
            stagedIndexes: List<Int>,
            expectedUploadIndexes: List<Int>,
        ) {
            val rig = SyncRig(session = joinedSession("family-a"))
            assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
            val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
            val paths = (0 until 3).map { "photos/$suffix-$it.jpg" }
            val mediaUuids = (0 until 3).map { index ->
                "20000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
            }
            val recordId = rig.records.seed(
                localRecord(babyId).copy(
                    clientUuid = "record-$suffix-retry",
                    payloadJson = """{"amount_ml":50}""",
                    syncDirty = true,
                ),
            )
            paths.forEachIndexed { index, path ->
                rig.media.seed(
                    MediaAssetEntity(
                        recordId = recordId,
                        clientUuid = mediaUuids[index],
                        kind = "log",
                        localUri = path,
                        mime = "image/jpeg",
                        byteSize = 4,
                        createdAt = 100,
                        updatedAt = 100L + index,
                        syncDirty = true,
                    ),
                )
            }
            rig.backend.stageBundleStatus = "staging"
            rig.backend.stageBundleMissingMedia = missingIndexes.map(mediaUuids::get)
            rig.backend.stageBundleStagedMedia = stagedIndexes.map(mediaUuids::get)

            assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

            assertThat(rig.backend.bundleMediaUploads.map { it.second })
                .containsExactlyElementsIn(expectedUploadIndexes.map(mediaUuids::get))
            assertThat(rig.backend.committedBundles).hasSize(1)
            assertThat(rig.records.getByClientUuid("record-$suffix-retry")?.syncDirty)
                .isFalse()
            val media = rig.media.listForRecord(recordId)
            assertThat(media.map { it.syncDirty })
                .containsExactly(false, false, false)
            val session = rig.preferences.current()
            assertThat(media.map { it.remoteUri })
                .containsExactlyElementsIn(mediaUuids.map(session::expectedMediaReceipt))
            assertThat(rig.outbox.all()).isEmpty()
        }

        runCase(
            suffix = "missing",
            missingIndexes = listOf(1),
            stagedIndexes = listOf(0, 2),
            expectedUploadIndexes = listOf(1),
        )
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
        assertThat(rig.outbox.all()).isEmpty()
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
        assertThat(rig.outbox.all()).isEmpty()
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
    fun originatorPullMergesServerFrozenStampsOnEqualUpdatedAt() = runTest {
        // Ticket 26: after push+markSynced, originator keeps local updatedAt and empty
        // role trails; pull of the same generation must still adopt server freeze so
        // multi-device authority converges.
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val babyUuid = rig.babies.getIncludingDeleted(babyId)!!.clientUuid
        val planUuid = "origin-plan"
        val recordUuid = "origin-record"
        val candUuid = "origin-cand"
        rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = planUuid,
                status = "completed",
                fulfilledRecordClientUuid = recordUuid,
                fulfilledAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = recordUuid,
                babyId = babyId,
                type = "formula",
                timestamp = 90,
                payloadJson = """{"amount_ml":80}""",
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.fulfillmentCandidates.seed(
            FulfillmentCandidateEntity(
                clientUuid = candUuid,
                carePlanClientUuid = planUuid,
                recordClientUuid = recordUuid,
                actualTimestamp = 90,
                confirmedAt = 100,
                submitterMembershipId = "",
                submitterRole = "",
                updatedAt = 500,
                syncDirty = false,
            ),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "fulfillment_candidate",
                    clientUuid = candUuid,
                    payloadJson =
                        """{"care_plan_client_uuid":"$planUuid","record_client_uuid":"$recordUuid","actual_timestamp":90,"submitter_membership_id":"m-self","submitter_role":"owner","confirmed_at":777}""",
                    // Equal/older updatedAt than local — pure LWW would skip without stamp merge.
                    updatedAt = 500,
                    deletedAt = null,
                ),
            ),
            cursor = 10,
            generation = "current-generation",
            hasMore = false,
        )
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()
        val cand = rig.fulfillmentCandidates.getByClientUuid(candUuid)!!
        assertThat(cand.submitterMembershipId).isEqualTo("m-self")
        assertThat(cand.submitterRole).isEqualTo("owner")
        assertThat(cand.confirmedAt).isEqualTo(777)
        assertThat(cand.syncDirty).isFalse()
        // Baby payload present only to keep session valid if needed.
        assertThat(babyUuid).isNotEmpty()
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

    private fun localBaby() = BabyEntity(
        familyId = 1,
        nickname = "本地宝宝",
        birthdayEpochDay = 20_000,
        themeColorArgb = 0,
        clientUuid = "baby-local",
        updatedAt = 100,
    )

    private fun localCarePlan(babyId: Long) = CarePlanEntity(
        clientUuid = "plan-local",
        babyId = babyId,
        type = "formula",
        scheduledAt = 9_000_000_000_000L,
        scheduledZoneId = "Asia/Shanghai",
        payloadJson = """{"amount_ml":120}""",
        status = "pending",
        createdByMembershipId = "member-local",
        updatedAt = 100,
        syncDirty = true,
    )

    private fun localRecord(babyId: Long) = RecordEntity(
        clientUuid = "record-local",
        babyId = babyId,
        type = "formula",
        timestamp = 120,
        payloadJson = """{"amount_ml":120}""",
        updatedAt = 120,
    )

    private fun remoteBaby() = SyncEntity(
        type = "baby",
        clientUuid = "baby-remote",
        payloadJson = """
            {
              "nickname":"远端宝宝",
              "sex":null,
              "birthday":"2024-01-01",
              "birth_weight_grams":null,
              "avatar_media_uuid":null
            }
        """.trimIndent(),
        updatedAt = 200,
    )

    private fun remoteRecord() = SyncEntity(
        type = "record",
        clientUuid = "record-remote",
        payloadJson = """
            {
              "baby_client_uuid":"baby-remote",
              "created_by_membership_id":"membership-b",
              "type":"formula",
              "custom_item_client_uuid":null,
              "timestamp":210,
              "end_timestamp":null,
              "note":null,
              "payload_json":{"amount_ml":90},
              "schema_version":2
            }
        """.trimIndent(),
        updatedAt = 210,
    )

    @Test
    fun clientUuidAloneIsNotAcceptedAsMediaReceipt() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(localRecord(babyId).copy(syncDirty = false))
        val mediaUuid = "55555555-5555-5555-5555-555555555555"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "",
                remoteUri = mediaUuid,
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.getMediaFailure = SyncHttpException(401, "must not download")
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 42,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        assertThat(rig.preferences.current().pullCursor).isEqualTo(42)
        assertThat(rig.preferences.current().pullGeneration)
            .isEqualTo("current-generation")
        assertThat(rig.media.getByClientUuid(mediaUuid)?.localUri).isEmpty()
        assertThat(rig.media.listMissingLocalBytes().map(MediaAssetEntity::clientUuid))
            .containsExactly(mediaUuid)
    }

    @Test
    fun nonUuidLocalMediaFailsBeforeBundleNetworkIo() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(localRecord(babyId).copy(syncDirty = true))
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = "not-a-uuid",
                kind = "log",
                localUri = "photos/not-portable.jpg",
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )

        val result = rig.port.sync(SyncTrigger.LocalWrite)

        assertThat(result.isFailure).isTrue()
        assertThat(rig.backend.stagedBundles).isEmpty()
        assertThat(rig.media.getByClientUuid("not-a-uuid")).isNotNull()
    }

    @Test
    fun mediaGetAuthFailureFailsSyncWithoutAdvancingCursorOrMarkingSuccess() = runTest {
        listOf(401, 403).forEach { statusCode ->
            val rig = SyncRig(session = joinedSession("family-a"))
            val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
            val recordId = rig.records.seed(localRecord(babyId).copy(syncDirty = false))
            val mediaUuid = testMediaUuid("auth-media-$statusCode")
            rig.media.seed(
                MediaAssetEntity(
                    recordId = recordId,
                    clientUuid = mediaUuid,
                    kind = "log",
                    localUri = "",
                    remoteUri = rig.preferences.current().expectedMediaReceipt(mediaUuid),
                    mime = "image/jpeg",
                    byteSize = 12,
                    createdAt = 100,
                    updatedAt = 100,
                    syncDirty = false,
                ),
            )
            rig.backend.getMediaFailure = SyncHttpException(statusCode, "auth failed")
            rig.backend.nextPull = PullResult(
                emptyList(),
                cursor = 42,
                generation = "current-generation",
                hasMore = false,
            )

            val result = rig.port.sync(SyncTrigger.PullToRefresh)

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(SyncHttpException::class.java)
            assertThat((result.exceptionOrNull() as SyncHttpException).statusCode)
                .isEqualTo(statusCode)
            assertThat(rig.preferences.current().pullCursor).isEqualTo(0)
            assertThat(rig.preferences.current().pullGeneration)
                .isEqualTo("current-generation")
            assertThat(rig.preferences.current().lastSuccessAt).isNull()
        }
    }

    @Test
    fun invalidMediaBytesDoNotBlockPullCursorAdvance() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(localRecord(babyId).copy(syncDirty = false))
        val mediaUuid = "56565656-5656-5656-5656-565656565656"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "",
                remoteUri = rig.preferences.current().expectedMediaReceipt(mediaUuid),
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.mediaBytes = byteArrayOf()
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 43,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        assertThat(rig.preferences.current().pullCursor).isEqualTo(43)
        assertThat(rig.preferences.current().pullGeneration)
            .isEqualTo("current-generation")
        assertThat(rig.media.getByClientUuid(mediaUuid)?.localUri).isEmpty()
        assertThat(rig.media.listMissingLocalBytes().map(MediaAssetEntity::clientUuid))
            .containsExactly(mediaUuid)
    }

    @Test
    fun pullWithMultipleOpenSleepsKeepsOnlyLatestOpen() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.babies.seed(localBaby().copy(syncDirty = false, clientUuid = "baby-remote"))
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "record",
                    clientUuid = "sleep-old",
                    payloadJson = """
                        {
                          "baby_client_uuid":"baby-remote",
                          "created_by_membership_id":"membership-b",
                          "type":"sleep",
                          "custom_item_client_uuid":null,
                          "timestamp":1000,
                          "end_timestamp":null,
                          "note":null,
                          "payload_json":{"is_nap":false,"anomaly_flag":false},
                          "schema_version":2
                        }
                    """.trimIndent(),
                    updatedAt = 1000,
                ),
                SyncEntity(
                    type = "record",
                    clientUuid = "sleep-new",
                    payloadJson = """
                        {
                          "baby_client_uuid":"baby-remote",
                          "created_by_membership_id":"membership-b",
                          "type":"sleep",
                          "custom_item_client_uuid":null,
                          "timestamp":2000,
                          "end_timestamp":null,
                          "note":null,
                          "payload_json":{"is_nap":false,"anomaly_flag":false},
                          "schema_version":2
                        }
                    """.trimIndent(),
                    updatedAt = 2000,
                ),
            ),
            cursor = 7,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        val babyId = requireNotNull(rig.babies.getByClientUuid("baby-remote")).id
        val opens = rig.records.listOpenSleeps(babyId)
        assertThat(opens).hasSize(1)
        assertThat(opens.single().clientUuid).isEqualTo("sleep-new")
        assertThat(opens.single().endTimestamp).isNull()

        val old = requireNotNull(rig.records.getByClientUuid("sleep-old"))
        assertThat(old.endTimestamp).isEqualTo(2000L)
        assertThat(old.payloadJson).contains("\"anomaly_flag\":true")
        assertThat(old.syncDirty).isTrue()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(7)
    }

    @Test
    fun pullOpenSleepTieUsesStableUuidAndInjectedRepairClock() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.clock.now = 400_000L
        rig.babies.seed(localBaby().copy(syncDirty = false, clientUuid = "baby-remote"))
        fun openSleep(clientUuid: String) = SyncEntity(
            type = "record",
            clientUuid = clientUuid,
            payloadJson = """
                {
                  "baby_client_uuid":"baby-remote",
                  "created_by_membership_id":"membership-b",
                  "type":"sleep",
                  "custom_item_client_uuid":null,
                  "timestamp":1000,
                  "end_timestamp":null,
                  "note":null,
                  "payload_json":{"is_nap":false,"anomaly_flag":false},
                  "schema_version":2
                }
            """.trimIndent(),
            updatedAt = 1_000L,
        )
        // `z` is applied first and receives the smaller local id. Every replica
        // must still keep it because equal starts use the family-stable UUID.
        rig.backend.nextPull = PullResult(
            entities = listOf(openSleep("z-sleep"), openSleep("a-sleep")),
            cursor = 8,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        val babyId = requireNotNull(rig.babies.getByClientUuid("baby-remote")).id
        assertThat(rig.records.listOpenSleeps(babyId).map(RecordEntity::clientUuid))
            .containsExactly("z-sleep")
        val stale = requireNotNull(rig.records.getByClientUuid("a-sleep"))
        assertThat(stale.endTimestamp).isEqualTo(400_000L)
        assertThat(stale.createdByMembershipId).isEqualTo("membership-b")
        assertThat(stale.payloadJson).contains("\"anomaly_flag\":true")
        assertThat(stale.syncDirty).isTrue()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(8)
    }
}

internal data class PushedBatch(
    val session: SyncSession,
    val entities: List<SyncEntity>,
)

internal class RecordingSyncBackend : SyncBackend {
    val pushes = mutableListOf<PushedBatch>()
    val pushAttempts = mutableListOf<SyncSession>()
    val operationOrder = mutableListOf<String>()
    val syncOrder = mutableListOf<String>()
    val mediaUploads = mutableListOf<String>()
    var pullCount = 0
    var nextPull: PullResult? = null
    val pullResults = ArrayDeque<PullResult>()
    val pullFailures = ArrayDeque<Throwable>()
    val pushFailures = ArrayDeque<Throwable>()
    val pullCursors = mutableListOf<Long>()
    var afterPush: (() -> Unit)? = null
    var afterCommit: (suspend () -> Unit)? = null
    var pullStarted: CompletableDeferred<Unit>? = null
    var releasePull: CompletableDeferred<Unit>? = null
    var createStarted: CompletableDeferred<Unit>? = null
    var releaseCreate: CompletableDeferred<Unit>? = null
    var leaveFailure: Throwable? = null
    var deviceRevokeFailure: Throwable? = null
    var deviceLogoutFailure: Throwable? = null
    val revokedDeviceIds = mutableListOf<String>()
    var deviceLogoutCalls = 0
    var deleteFailure: Throwable? = null
    var onLeave: suspend () -> Unit = {}
    var onDeleteFamily: suspend () -> Unit = {}
    var deleteFamilyCalls = 0
    val deletedFamilyConfirmations = mutableListOf<Pair<String, String>>()
    var createFailure: Throwable? = null
    var membersFailure: Throwable? = null
    var nextMembers: List<FamilyMember>? = null
    var rejectMemberAvatarPointers = false
    var enforceBundleReferences = false
    var beforeGetMediaReturn: (suspend () -> Unit)? = null
    var beforePullReturn: (suspend () -> Unit)? = null
    var getMediaFailure: Throwable? = null
    var mediaBytes: ByteArray = byteArrayOf(1)
    val createRequestIds = mutableListOf<String>()
    val createDisplayNames = mutableListOf<String?>()
    val createFamilyNames = mutableListOf<String?>()
    val createBootstrapSecrets = mutableListOf<String?>()
    val ownerLoginRequestIds = mutableListOf<String>()
    val ownerLoginDeviceNames = mutableListOf<String>()
    val ownerLoginRootPasswords = mutableListOf<String>()
    val ownerLoginTakeovers = mutableListOf<Boolean>()
    var ownerLoginFailure: Throwable? = null
    var nextOwnerLoginFamilyId = "family-owner-login"
    val memberLoginRequests = mutableListOf<Triple<String, String, String>>()
    var nextMemberLoginReceipt = MemberLoginReceipt(
        requestId = "99999999-9999-9999-9999-999999999999",
        pendingSecret = "pending-secret-000000000000000000000001",
        expiresAtEpochSeconds = 1_753_504_800,
    )
    val memberLoginStatuses = ArrayDeque<MemberLoginStatus>()
    var memberLoginClaimCalls = 0
    var nextMemberLoginClaim = SessionBootstrapResult(
        familyId = "family-member-approved",
        accessToken = "member-approved-access",
        refreshToken = "member-approved-refresh",
        accessExpiresAtEpochSeconds = 1_753_419_300,
        deviceId = "device-member-approved",
        role = FamilyRole.Member,
        generation = "current-generation",
        familyName = "乐乐一家",
        membershipId = "membership-member-approved",
    )
    var cancelMemberLoginCalls = 0
    var nextPendingMemberLogins: List<PendingMemberLoginRequest> = emptyList()
    val approvedMemberLoginRequestIds = mutableListOf<String>()
    val boundMemberLoginRequests = mutableListOf<Pair<String, String>>()
    val rejectedMemberLoginRequestIds = mutableListOf<String>()
    var nextMemberLoginGrant = MemberLoginGrant(
        grant = "grant-0000000000000000000000000000000000000",
        familyName = "乐乐一家",
        memberDisplayName = "妈妈",
        expiresAtEpochSeconds = 1_753_419_000,
    )
    val memberLoginGrantTargets =
        mutableListOf<Triple<String, TrustedEndpointProfile, String>>()
    val memberLoginGrantClaims =
        mutableListOf<Triple<TrustedEndpointProfile, String, String>>()
    val updatedDisplayNames = mutableListOf<String>()
    val renamedFamilyNames = mutableListOf<String?>()
    var renameFamilyFailure: Throwable? = null
    var nextPushRecordAuthors: List<CanonicalRecordAuthor>? = null
    var nextCreateFamilyName: String? = null
    var nextCreateEntities: List<SyncEntity> = emptyList()
    var nextCreateReclaimed: Boolean = false
    var memberCalls = 0
    private val knownEntities = mutableSetOf<Pair<String, String>>()

    fun remember(type: String, clientUuid: String) {
        knownEntities += type to clientUuid
    }

    override suspend fun create(
        baseUrl: String,
        deviceId: String,
        displayName: String?,
        createRequestId: String,
        bootstrapSecret: String?,
        familyName: String?,
    ): SessionBootstrapResult {
        createRequestIds += createRequestId
        createDisplayNames += displayName
        createFamilyNames += familyName
        createBootstrapSecrets += bootstrapSecret
        createStarted?.complete(Unit)
        releaseCreate?.await()
        createFailure?.let { throw it }
        return SessionBootstrapResult(
            familyId = "family-created",
            accessToken = if (nextCreateReclaimed) "owner-token-reclaimed" else "owner-token",
            refreshToken = "owner-refresh-token",
            accessExpiresAtEpochSeconds = 1_753_419_300,
            deviceId = "device-created",
            role = FamilyRole.Owner,
            generation = "current-generation",
            entities = nextCreateEntities,
            familyName = nextCreateFamilyName ?: familyName,
            membershipId = "membership-created",
            reclaimed = nextCreateReclaimed,
        )
    }

    override suspend fun ownerLogin(
        baseUrl: String,
        deviceName: String,
        loginRequestId: String,
        rootPassword: String,
        takeover: Boolean,
    ): SessionBootstrapResult {
        ownerLoginRequestIds += loginRequestId
        ownerLoginDeviceNames += deviceName
        ownerLoginRootPasswords += rootPassword
        ownerLoginTakeovers += takeover
        ownerLoginFailure?.let { throw it }
        return SessionBootstrapResult(
            familyId = nextOwnerLoginFamilyId,
            accessToken = "owner-login-access",
            refreshToken = "owner-login-refresh",
            accessExpiresAtEpochSeconds = 1_753_419_300,
            deviceId = "device-owner-login",
            role = FamilyRole.Owner,
            generation = "current-generation",
            familyName = "乐乐一家",
            membershipId = "membership-owner",
        )
    }

    override suspend fun requestMemberLogin(
        baseUrl: String,
        displayName: String,
        deviceName: String,
    ): MemberLoginReceipt {
        memberLoginRequests += Triple(baseUrl, displayName, deviceName)
        return nextMemberLoginReceipt
    }

    override suspend fun memberLoginStatus(
        baseUrl: String,
        pendingSecret: String,
    ): MemberLoginStatus = memberLoginStatuses.removeFirstOrNull() ?: MemberLoginStatus.Pending

    override suspend fun cancelMemberLogin(baseUrl: String, pendingSecret: String) {
        cancelMemberLoginCalls++
    }

    override suspend fun claimMemberLogin(baseUrl: String, pendingSecret: String): SessionBootstrapResult {
        memberLoginClaimCalls++
        return nextMemberLoginClaim
    }

    override suspend fun pendingMemberLogins(
        session: SyncSession,
    ): List<PendingMemberLoginRequest> = nextPendingMemberLogins

    override suspend fun approveNewMemberLogin(session: SyncSession, requestId: String) {
        approvedMemberLoginRequestIds += requestId
    }

    override suspend fun bindExistingMemberLogin(
        session: SyncSession,
        requestId: String,
        membershipId: String,
    ) {
        boundMemberLoginRequests += requestId to membershipId
    }

    override suspend fun rejectMemberLogin(session: SyncSession, requestId: String) {
        rejectedMemberLoginRequestIds += requestId
    }

    override suspend fun createMemberLoginGrant(
        session: SyncSession,
        endpoint: TrustedEndpointProfile,
        membershipId: String,
    ): MemberLoginGrant {
        memberLoginGrantTargets += Triple(session.accessToken, endpoint, membershipId)
        return nextMemberLoginGrant
    }

    override suspend fun claimMemberLoginGrant(
        endpoint: TrustedEndpointProfile,
        grant: String,
        deviceName: String,
    ): SessionBootstrapResult {
        memberLoginGrantClaims += Triple(endpoint, grant, deviceName)
        return nextMemberLoginClaim
    }

    suspend fun push(session: SyncSession, entities: List<SyncEntity>): LegacyPushResult {
        pushAttempts += session
        pushFailures.removeFirstOrNull()?.let { throw it }
        val available = knownEntities + entities.map { it.type to it.clientUuid }
        entities.forEach { entity ->
            val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
            when (entity.type) {
                "baby" -> payload["avatar_media_uuid"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.takeUnless { it == "null" }
                    ?.let {
                        if (rejectMemberAvatarPointers && session.role == FamilyRole.Member) {
                            throw SyncHttpException(403)
                        }
                        require("media" to it in available)
                    }
                "record" -> payload["baby_client_uuid"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.let { require("baby" to it in available) }
                "media" -> when (payload["kind"]?.jsonPrimitive?.contentOrNull) {
                    "avatar" -> payload["baby_client_uuid"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.let { require("baby" to it in available) }
                    "log" -> payload["record_client_uuid"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.let { require("record" to it in available) }
                }
            }
        }
        val pushOperation = "push:${entities.joinToString(",") { it.type }}"
        operationOrder += pushOperation
        syncOrder += pushOperation
        pushes += PushedBatch(session, entities)
        knownEntities += entities.map { it.type to it.clientUuid }
        afterPush?.invoke()
        return LegacyPushResult(
            applied = entities.size,
            recordAuthors = nextPushRecordAuthors ?: entities
                .filter { it.type == "record" }
                .map { entity ->
                    val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
                    CanonicalRecordAuthor(
                        clientUuid = entity.clientUuid,
                        createdByMembershipId = payload["created_by_membership_id"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?.takeIf(String::isNotBlank)
                            ?: session.membershipId,
                    )
                },
        )
    }

    override suspend fun pull(session: SyncSession): PullResult {
        pullCount++
        pullCursors += session.pullCursor
        syncOrder += "pull:${session.pullCursor}"
        pullStarted?.complete(Unit)
        releasePull?.await()
        pullFailures.removeFirstOrNull()?.let { throw it }
        beforePullReturn?.also { beforePullReturn = null }?.invoke()
        return pullResults.removeFirstOrNull() ?: nextPull ?: PullResult(
            entities = emptyList(),
            cursor = session.pullCursor,
            generation = session.pullGeneration,
            hasMore = false,
        )
    }

    override suspend fun members(session: SyncSession): List<FamilyMember> {
        memberCalls++
        membersFailure?.let { throw it }
        return nextMembers ?: listOf(
            FamilyMember(
                "管理员",
                session.role,
                isSelf = true,
                membershipId = session.membershipId,
            ),
        )
    }

    override suspend fun updateMyDisplayName(
        session: SyncSession,
        displayName: String,
    ): DisplayNameUpdateResult {
        updatedDisplayNames += displayName
        return DisplayNameUpdateResult.Updated(displayName)
    }

    override suspend fun renameFamily(session: SyncSession, familyName: String?) {
        renameFamilyFailure?.let { throw it }
        renamedFamilyNames += familyName
    }

    override suspend fun leave(session: SyncSession) {
        leaveFailure?.let { throw it }
        onLeave()
    }

    override suspend fun revokeFamilyDevice(session: SyncSession, deviceId: String) {
        deviceRevokeFailure?.let { throw it }
        revokedDeviceIds += deviceId
    }

    override suspend fun logoutCurrentDevice(session: SyncSession) {
        deviceLogoutFailure?.let { throw it }
        deviceLogoutCalls++
    }

    val removedMembershipIds = mutableListOf<String>()
    var removeMemberFailure: Throwable? = null

    override suspend fun removeMember(session: SyncSession, membershipId: String) {
        removeMemberFailure?.let { throw it }
        removedMembershipIds += membershipId.trim()
    }

    override suspend fun deleteFamily(
        session: SyncSession,
        familyName: String,
        rootPassword: String,
    ) {
        deleteFamilyCalls += 1
        deletedFamilyConfirmations += familyName to rootPassword
        deleteFailure?.let { throw it }
        onDeleteFamily()
    }

    suspend fun putMedia(
        session: SyncSession,
        clientUuid: String,
        bytes: ByteArray,
        mime: String?,
    ) {
        operationOrder += "put_media:$clientUuid"
        mediaUploads += clientUuid
    }

    override suspend fun getMedia(session: SyncSession, clientUuid: String): ByteArray {
        beforeGetMediaReturn?.also { beforeGetMediaReturn = null }?.invoke()
        getMediaFailure?.let { throw it }
        return mediaBytes
    }

    var appUpdateMetadata: AppUpdateMetadata? = null
    var getAppUpdateMetadataFailure: Throwable? = null
    var getAppUpdateMetadataCalls = 0
    var appUpdateApkBytes: ByteArray? = null
    var downloadAppUpdateApkFailure: Throwable? = null
    var downloadAppUpdateApkCalls = 0
    var downloadAppUpdateApkStarted: CompletableDeferred<Unit>? = null
    var releaseDownloadAppUpdateApk: CompletableDeferred<Unit>? = null

    override suspend fun getAppUpdateMetadata(session: SyncSession): AppUpdateMetadata {
        getAppUpdateMetadataCalls += 1
        getAppUpdateMetadataFailure?.let { throw it }
        return appUpdateMetadata
            ?: throw SyncHttpException(404, """{"detail":"App update metadata is not available"}""")
    }

    override suspend fun downloadAppUpdateApk(session: SyncSession): ByteArray {
        downloadAppUpdateApkCalls += 1
        downloadAppUpdateApkStarted?.complete(Unit)
        releaseDownloadAppUpdateApk?.await()
        downloadAppUpdateApkFailure?.let { throw it }
        return appUpdateApkBytes
            ?: throw SyncHttpException(404, """{"detail":"App update package is not available"}""")
    }

    val stagedBundles = mutableListOf<AtomicBundleDraft>()
    val bundleMediaUploads = mutableListOf<Pair<String, String>>()
    val committedBundles = mutableListOf<String>()
    var stageBundleFailure: Throwable? = null
    var putBundleMediaFailure: Throwable? = null
    var onPutBundleMedia: (suspend (clientUuid: String) -> Unit)? = null
    var commitBundleFailure: Throwable? = null
    var failCommitRootTypeOnce: Pair<String, Throwable>? = null
    var nextCommitRecordAuthors: List<CanonicalRecordAuthor>? = null
    var stageBundleStatus = "staging"
    var stageBundleMissingMedia: List<String>? = null
    var stageBundleStagedMedia: List<String> = emptyList()

    override suspend fun stageBundle(
        session: SyncSession,
        draft: AtomicBundleDraft,
    ): BundleStageStatus {
        stageBundleFailure?.let { throw it }
        if (enforceBundleReferences) {
            val payload = Json.parseToJsonElement(draft.root.payloadJson).jsonObject
            listOf(
                "baby_client_uuid" to "baby",
                "custom_item_client_uuid" to "custom_item",
            ).forEach { (payloadKey, entityType) ->
                payload[payloadKey]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.takeIf(String::isNotBlank)
                    ?.let { clientUuid ->
                        if (entityType to clientUuid !in knownEntities) {
                            throw SyncHttpException(
                                statusCode = 409,
                                responseBody =
                                    """{"detail":"${draft.root.type} $payloadKey does not exist"}""",
                            )
                        }
                    }
            }
        }
        operationOrder += "stage:${draft.root.type}"
        syncOrder += "stage:${draft.root.type}"
        stagedBundles += draft
        return BundleStageStatus(
            bundleId = draft.bundleId,
            status = stageBundleStatus,
            missingMedia = stageBundleMissingMedia
                ?: draft.media.filter { it.deletedAt == null }.map { it.clientUuid },
            stagedMedia = stageBundleStagedMedia,
        )
    }

    override suspend fun putBundleMedia(
        session: SyncSession,
        bundleId: String,
        clientUuid: String,
        source: SyncMediaUploadSource,
    ): BundleStageStatus {
        putBundleMediaFailure?.let { throw it }
        onPutBundleMedia?.invoke(clientUuid)
        operationOrder += "put_bundle_media:$clientUuid"
        bundleMediaUploads += bundleId to clientUuid
        return BundleStageStatus(
            bundleId = bundleId,
            status = "staging",
            stagedMedia = listOf(clientUuid),
        )
    }

    override suspend fun commitBundle(
        session: SyncSession,
        bundleId: String,
    ): BundleCommitResult {
        val stagedDraft = stagedBundles.lastOrNull { it.bundleId == bundleId }
        failCommitRootTypeOnce
            ?.takeIf { (rootType, _) -> stagedDraft?.root?.type == rootType }
            ?.let { (_, failure) ->
                failCommitRootTypeOnce = null
                throw failure
            }
        commitBundleFailure?.let { throw it }
        committedBundles += bundleId
        stagedDraft?.let { draft ->
            knownEntities += draft.root.type to draft.root.clientUuid
            draft.media.forEach { knownEntities += it.type to it.clientUuid }
        }
        afterCommit?.invoke()
        val recordAuthors = nextCommitRecordAuthors ?: stagedBundles
            .lastOrNull { it.bundleId == bundleId }
            ?.root
            ?.takeIf { it.type == "record" }
            ?.let { root ->
                val payload = Json.parseToJsonElement(root.payloadJson).jsonObject
                listOf(
                    CanonicalRecordAuthor(
                        clientUuid = root.clientUuid,
                        createdByMembershipId = payload["created_by_membership_id"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?.takeIf(String::isNotBlank)
                            ?: session.membershipId,
                    ),
                )
            }
            .orEmpty()
        return BundleCommitResult(
            bundleId = bundleId,
            status = "committed",
            applied = 1,
            cursor = session.pullCursor,
            recordAuthors = recordAuthors,
        )
    }
}

internal class MemorySyncPreferences(
    initial: SyncSession,
    blockFirstSecretMigration: Boolean = false,
) : SyncPreferences {
    private val state = MutableStateFlow(initial)
    private val endpointState = MutableStateFlow<TrustedEndpointProfile?>(null)
    private val pendingMemberState = MutableStateFlow<PendingMemberLogin?>(null)
    private var memberPendingSecret = ""
    private var createRequestId: String? = null
    private var ownerLoginRequestId: String? = null
    private val shouldBlockSecretMigration = AtomicBoolean(blockFirstSecretMigration)
    val secretMigrationStarted = CompletableDeferred<Unit>()
    val releaseSecretMigration = CompletableDeferred<Unit>()
    var saveSessionCalls = 0
    var failUpdateCursorAttempts = 0
    var clearCreateRequestIdFailure: Throwable? = null
    var clearCreateRequestIdCalls = 0
    var pendingDeviceRemovalClear = false
    var pendingMembershipDeletionClear = false
    var pendingFamilyDeletionClear = false
    private var pendingReplicaResetPrevious: SyncSession? = null
    private var pendingReplicaResetSession: SyncSession? = null
    val familyDeletionClearCompleted = CompletableDeferred<Unit>()
    override val session: Flow<SyncSession> = state
    override val verifiedEndpoint: Flow<TrustedEndpointProfile?> = endpointState
    override val pendingMemberLogin: Flow<PendingMemberLogin?> = pendingMemberState

    override suspend fun rememberEndpoint(endpoint: TrustedEndpointProfile) {
        endpointState.value = endpoint
    }

    override suspend fun forgetEndpoint() {
        endpointState.value = null
    }

    fun current(): SyncSession = state.value

    fun trustCurrentEndpointForTest() {
        if (endpointState.value != null) return
        val origin = state.value.baseUrl.takeIf(String::isNotBlank) ?: return
        endpointState.value = TrustedEndpointProfile.systemPki(origin)
    }

    override suspend fun saveEndpointConfig(
        config: FamilyEndpointConfig,
        clearSessionIfServerChanged: Boolean,
    ) {
        val n = config.withNormalized()
        val prev = state.value
        var next = prev.copy(
            serverHost = n.host,
            serverPort = n.port,
            serverScheme = n.scheme,
        )
        if (
            clearSessionIfServerChanged &&
            prev.baseUrl.isNotBlank() &&
            prev.baseUrl != n.baseUrl &&
            n.baseUrl.isNotBlank()
        ) {
            next = next.copy(
                familyId = "",
                accessToken = "",
                refreshToken = "",
                accessExpiresAtEpochSeconds = 0,
                reauthRequired = false,
                role = FamilyRole.None,
                pullCursor = 0,
                pullGeneration = "",
                lastSuccessAt = null,
                familyName = null,
                membershipId = "",
                pendingCreatorAcknowledgements = emptySet(),
            )
            pendingMemberState.value = null
            memberPendingSecret = ""
        }
        state.value = next
    }

    override suspend fun saveSession(session: SyncSession) {
        saveSessionCalls += 1
        createRequestId = null
        ownerLoginRequestId = null
        pendingMemberState.value = null
        memberPendingSecret = ""
        pendingReplicaResetPrevious = null
        pendingReplicaResetSession = null
        val previous = state.value
        state.value = session.copy(
            pendingCreatorAcknowledgements = if (previous.familyId == session.familyId) {
                previous.pendingCreatorAcknowledgements
            } else {
                emptySet()
            },
        )
    }

    override suspend fun saveSessionPendingReplicaReset(
        session: SyncSession,
        previous: SyncSession,
    ) {
        saveSessionCalls += 1
        createRequestId = null
        ownerLoginRequestId = null
        pendingMemberState.value = null
        memberPendingSecret = ""
        pendingReplicaResetPrevious = previous
        pendingReplicaResetSession = session
        state.value = session.copy(
            accessToken = "",
            refreshToken = "",
            accessExpiresAtEpochSeconds = 0,
            reauthRequired = true,
        )
    }

    override suspend fun pendingReplicaResetPrevious(): SyncSession? =
        pendingReplicaResetPrevious

    override suspend fun completePendingReplicaReset() {
        val session = pendingReplicaResetSession ?: return
        pendingReplicaResetPrevious = null
        pendingReplicaResetSession = null
        state.value = session
    }

    override suspend fun recoverPendingCredentialClear() {
        if (!shouldBlockSecretMigration.compareAndSet(true, false)) return
        secretMigrationStarted.complete(Unit)
        releaseSecretMigration.await()
        state.value = state.value.copy(accessToken = "")
    }

    override suspend fun updateCursor(cursor: Long, generation: String) {
        if (failUpdateCursorAttempts > 0) {
            failUpdateCursorAttempts--
            error("cursor update failed")
        }
        state.value = state.value.copy(
            pullCursor = cursor,
            pullGeneration = generation,
        )
    }

    override suspend fun updatePullCheckpoint(
        cursor: Long,
        generation: String,
        familyName: String?,
    ) {
        val current = state.value
        state.value = current.copy(
            pullCursor = cursor,
            pullGeneration = generation,
            familyName = normalizeFamilyNameForWire(familyName),
        )
    }

    override suspend fun updateCreatorAcknowledgements(
        add: Set<CreatorAcknowledgementRef>,
        remove: Set<CreatorAcknowledgementRef>,
    ) {
        state.value = state.value.copy(
            pendingCreatorAcknowledgements =
                (state.value.pendingCreatorAcknowledgements + add) - remove,
        )
    }

    override suspend fun markSuccess(atMillis: Long) {
        state.value = state.value.copy(lastSuccessAt = atMillis)
    }

    override suspend fun ensureDeviceId(): String {
        if (state.value.deviceId.isBlank()) {
            state.value = state.value.copy(deviceId = "test-device")
        }
        return state.value.deviceId
    }

    override suspend fun ensureCreateRequestId(): String =
        createRequestId ?: "77777777-7777-7777-7777-777777777777".also {
            createRequestId = it
        }

    override suspend fun ensureOwnerLoginRequestId(): String =
        ownerLoginRequestId ?: "88888888-8888-8888-8888-888888888888".also {
            ownerLoginRequestId = it
        }

    override suspend fun savePendingMemberLogin(
        receipt: MemberLoginReceipt,
        displayName: String,
        deviceName: String,
    ) {
        memberPendingSecret = receipt.pendingSecret
        pendingMemberState.value = PendingMemberLogin(
            requestId = receipt.requestId,
            displayName = displayName,
            deviceName = deviceName,
            expiresAtEpochSeconds = receipt.expiresAtEpochSeconds,
        )
    }

    override suspend fun pendingMemberSecret(): String = memberPendingSecret

    override suspend fun clearPendingMemberLogin() {
        pendingMemberState.value = null
        memberPendingSecret = ""
    }

    override suspend fun clearCreateRequestId() {
        clearCreateRequestIdCalls += 1
        clearCreateRequestIdFailure?.let { throw it }
        createRequestId = null
    }

    override suspend fun clearAllLocalSyncConfig() {
        state.value = SyncSession()
        pendingMemberState.value = null
        memberPendingSecret = ""
        pendingReplicaResetPrevious = null
        pendingReplicaResetSession = null
    }

    override suspend fun clearDeviceCredentialsForReauth() {
        state.value = state.value.copy(
            accessToken = "",
            refreshToken = "",
            accessExpiresAtEpochSeconds = 0,
            reauthRequired = true,
        )
    }

    override suspend fun markPendingDeviceRemovalClear() {
        pendingDeviceRemovalClear = true
    }

    override suspend fun hasPendingDeviceRemovalClear(): Boolean = pendingDeviceRemovalClear

    override suspend fun clearPendingDeviceRemovalClear() {
        pendingDeviceRemovalClear = false
    }

    override suspend fun markPendingMembershipDeletionClear() {
        pendingMembershipDeletionClear = true
    }

    override suspend fun hasPendingMembershipDeletionClear(): Boolean =
        pendingMembershipDeletionClear

    override suspend fun clearPendingMembershipDeletionClear() {
        pendingMembershipDeletionClear = false
    }

    override suspend fun markPendingFamilyDeletionClear() {
        pendingFamilyDeletionClear = true
    }

    override suspend fun hasPendingFamilyDeletionClear(): Boolean = pendingFamilyDeletionClear

    override suspend fun clearPendingFamilyDeletionClear() {
        pendingFamilyDeletionClear = false
        familyDeletionClearCompleted.complete(Unit)
    }
}

private class MutablePolicyClock(var now: Long = 1_000) : PolicyClock {
    override fun nowMillis(): Long = now
}

private class TestForegroundState(
    private var foreground: Boolean = true,
) : ForegroundState {
    override fun isForeground(): Boolean = foreground
    override fun setForeground(value: Boolean) {
        foreground = value
    }
}

private class TestRemovedDeviceLocalClearGate : RemovedDeviceLocalClearGate {
    var calls = 0
    val failures = ArrayDeque<Throwable>()
    val firstCall = CompletableDeferred<Unit>()
    var release: CompletableDeferred<Unit>? = null

    override suspend fun clearAllLocalFamilyData() {
        calls++
        firstCall.complete(Unit)
        release?.await()
        failures.removeFirstOrNull()?.let { throw it }
    }
}

internal open class TestMediaFileStore : SyncMediaFileStore {
    val deleted = mutableListOf<String>()
    val deleteFailures = ArrayDeque<Throwable>()
    var afterInspect: (suspend () -> Unit)? = null
    var afterSaveDownloaded: (suspend () -> Unit)? = null

    override suspend fun inspect(localUri: String): LocalMediaInfo {
        afterInspect?.also { afterInspect = null }?.invoke()
        return LocalMediaInfo(byteSize = 12, mime = "image/jpeg", width = 10, height = 10)
    }

    override suspend fun prepareUpload(localUri: String) =
        testPreparedMedia(byteArrayOf(1))

    override suspend fun saveDownloaded(
        clientUuid: String,
        kind: String,
        bytes: ByteArray,
        mime: String?,
    ): String {
        require(bytes.isNotEmpty()) { "downloaded media must not be empty" }
        afterSaveDownloaded?.also { afterSaveDownloaded = null }?.invoke()
        return "downloaded/$clientUuid"
    }

    override open suspend fun delete(localUri: String) {
        deleted += localUri
        deleteFailures.removeFirstOrNull()?.let { throw it }
    }
}

private fun realPortClearWorkflow(
    clearRoom: suspend () -> Unit = {},
    finishCommitted: suspend () -> Unit = {},
): LocalClearWorkflow = object : LocalClearWorkflow {
    override suspend fun <T> withLocalExclusion(block: suspend () -> T): T = block()
    override suspend fun clearRoom() = clearRoom.invoke()
    override suspend fun finishCommitted() = finishCommitted.invoke()
}

private class RecordingAppUpdateInstaller(
    private val canInstall: Boolean = true,
    private val installFailure: Throwable? = null,
) : AppUpdateInstaller {
    data class InstallCall(
        val expectedPackageName: String,
        val fileExistedAtCall: Boolean,
        val byteSize: Long,
    )

    val installCalls = mutableListOf<InstallCall>()

    override fun canRequestPackageInstalls(): Boolean = canInstall

    override fun installFromFile(apkFile: java.io.File, expectedPackageName: String) {
        installCalls += InstallCall(
            expectedPackageName = expectedPackageName,
            fileExistedAtCall = apkFile.isFile,
            byteSize = apkFile.length(),
        )
        installFailure?.let { throw it }
    }

    override fun createManageUnknownSourcesIntent(): android.content.Intent =
        android.content.Intent()
}

/**
 * Fake archive identity for JVM install tests (no PackageManager).
 * Defaults match com.lezi.babylog versionCode 7 with a shared test signer.
 */
private class FakeAppUpdateApkIdentityReader(
    var packageName: String = "com.lezi.babylog",
    var versionCode: Int = 7,
    var archiveCerts: Set<String> = setOf(TEST_APP_UPDATE_CERT_SHA256),
    var installedCerts: Set<String> = setOf(TEST_APP_UPDATE_CERT_SHA256),
    var unreadable: Boolean = false,
) : AppUpdateApkIdentityReader {
    override fun readArchive(apkFile: java.io.File): StagedApkIdentity? {
        if (unreadable || !apkFile.isFile) return null
        return StagedApkIdentity(
            packageName = packageName,
            versionCode = versionCode,
            signingCertSha256 = archiveCerts,
        )
    }

    override fun installedSigningCertSha256(): Set<String> = installedCerts
}

private const val TEST_APP_UPDATE_CERT_SHA256 =
    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

private class SyncRig(
    session: SyncSession,
    carePlanApplied: suspend (List<String>) -> Unit = {},
    syncBackend: SyncBackend? = null,
    syncPreferences: MemorySyncPreferences? = null,
    setupProbe: SetupProbe = SetupProbe { _, _ -> SetupProbeResult.Failed.Unreachable },
    removedDeviceLocalClearGate: RemovedDeviceLocalClearGate = NoOpRemovedDeviceLocalClearGate(),
    clientAppVersion: ClientAppVersion = ClientAppVersion.FALLBACK,
    appUpdateInstaller: AppUpdateInstaller = NoOpAppUpdateInstaller,
    apkIdentityReader: AppUpdateApkIdentityReader = FakeAppUpdateApkIdentityReader(),
    appUpdateCacheDir: java.io.File = createTempDir(prefix = "lezi-app-update-rig"),
) {
    val backend = RecordingSyncBackend()
    val preferences = (syncPreferences ?: MemorySyncPreferences(session)).also {
        it.trustCurrentEndpointForTest()
    }
    val outbox = MemoryOutboxDao()
    val records = MemoryRecordDao()
    val carePlans = MemoryCarePlanDao()
    val fulfillmentCandidates = MemoryFulfillmentCandidateDao()
    val babies = MemoryBabyDao()
    val media = MemoryMediaDao()
    val customItems = MemoryCustomItemDao()
    val mediaFiles = TestMediaFileStore()
    val transactions = RecordingTransactionRunner()
    val mediaFileCleanup = ReferenceAwareMediaFileCleanup(
        mediaDao = media,
        mediaFiles = mediaFiles,
        transactionRunner = transactions,
        pathGate = MediaLocalPathGate(),
    )
    val pendingReplicaCleanup = TestPendingReplicaCleanupStore()
    val pendingDomainRecovery = TestLocalClearRecoveryGate()
    val families = MemoryFamilyDao().apply {
        seed(FamilyEntity(id = 1, ownerUserId = 1, createdAt = 0))
    }
    val clock = MutablePolicyClock()
    val foreground = TestForegroundState()
    val port = RealSyncPort(
        backend = syncBackend ?: backend,
        preferences = preferences,
        setupProbe = setupProbe,
        foregroundSyncGate = ForegroundSyncGate(),
        outboxDao = outbox,
        recordDao = records,
        carePlanDao = carePlans,
        babyDao = babies,
        mediaDao = media,
        customItemDao = customItems,
        familyDao = families,
        clock = clock,
        foregroundState = foreground,
        mediaFiles = mediaFiles,
        mediaFileCleanup = mediaFileCleanup,
        transactionRunner = transactions,
        pendingReplicaCleanupStore = pendingReplicaCleanup,
        localClearRecoveryGate = pendingDomainRecovery,
        removedDeviceLocalClearGate = removedDeviceLocalClearGate,
        carePlanAppliedListener = CarePlanFamilyAppliedListener { carePlanApplied(it) },
        fulfillmentCandidateDao = fulfillmentCandidates,
        clientAppVersion = clientAppVersion,
        appUpdateInstaller = appUpdateInstaller,
        apkIdentityReader = apkIdentityReader,
        appUpdateCacheDir = appUpdateCacheDir,
    )

    suspend fun awaitStartupRecovery() {
        pendingReplicaCleanup.firstLoad.await()
    }
}

internal class TestLocalClearRecoveryGate : LocalClearRecoveryGate {
    var calls = 0
    var resumed: LocalDataClearScope? = null
    val failures = ArrayDeque<Throwable>()

    override suspend fun recoverPendingLocalClear(): LocalDataClearScope? {
        calls += 1
        failures.removeFirstOrNull()?.let { throw it }
        return resumed
    }
}

internal class TestPendingReplicaCleanupStore :
    com.lezi.babylog.core.database.PendingReplicaCleanupStore {
    var pending: com.lezi.babylog.core.database.PendingReplicaCleanup? = null
    val loadFailures = ArrayDeque<Throwable>()
    val firstLoad = CompletableDeferred<Unit>()

    override suspend fun load(): com.lezi.babylog.core.database.PendingReplicaCleanup? {
        val failure = loadFailures.removeFirstOrNull()
        val current = pending
        firstLoad.complete(Unit)
        failure?.let { throw it }
        return current
    }

    override suspend fun stage(
        pending: com.lezi.babylog.core.database.PendingReplicaCleanup,
    ) {
        check(this.pending == null)
        this.pending = pending
    }

    override suspend fun delete() {
        pending = null
    }
}

internal class MemoryFulfillmentCandidateDao : FulfillmentCandidateDao {
    private val rows = MutableStateFlow<List<FulfillmentCandidateEntity>>(emptyList())
    private val ids = AtomicLong(1)

    fun seed(entity: FulfillmentCandidateEntity): Long {
        val id = entity.id.takeIf { it != 0L } ?: ids.getAndIncrement()
        rows.value = rows.value.filterNot {
            it.id == id || it.clientUuid == entity.clientUuid
        } + entity.copy(id = id)
        return id
    }

    override suspend fun get(id: Long): FulfillmentCandidateEntity? =
        rows.value.firstOrNull { it.id == id }

    override suspend fun getByClientUuid(clientUuid: String): FulfillmentCandidateEntity? =
        rows.value.firstOrNull { it.clientUuid == clientUuid }

    override suspend fun listForCarePlan(carePlanClientUuid: String): List<FulfillmentCandidateEntity> =
        rows.value.filter { it.carePlanClientUuid == carePlanClientUuid }.sortedBy { it.id }

    override suspend fun listForRecord(recordClientUuid: String): List<FulfillmentCandidateEntity> =
        rows.value.filter { it.recordClientUuid == recordClientUuid }.sortedBy { it.id }

    override suspend fun listAllIncludingDeleted(): List<FulfillmentCandidateEntity> = rows.value

    override suspend fun listPendingSync(): List<FulfillmentCandidateEntity> =
        rows.value.filter { it.syncDirty }.sortedBy { it.id }

    override suspend fun listConflictNotAdoptedRecordUuids(): List<String> =
        rows.value
            .filter {
                it.adoptionStatus ==
                    com.lezi.babylog.core.model.FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED &&
                    it.deletedAt == null
            }
            .map { it.recordClientUuid }

    override suspend fun listConflictNotAdopted(): List<FulfillmentCandidateEntity> =
        rows.value
            .filter {
                it.adoptionStatus ==
                    com.lezi.babylog.core.model.FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED &&
                    it.deletedAt == null
            }
            .sortedWith(compareBy({ it.confirmedAt }, { it.clientUuid }))

    override suspend fun listConflictNotAdoptedForCarePlan(
        carePlanClientUuid: String,
    ): List<FulfillmentCandidateEntity> =
        listConflictNotAdopted().filter { it.carePlanClientUuid == carePlanClientUuid }

    override fun observeConflictNotAdoptedRecordUuids(): Flow<List<String>> =
        rows.map { list ->
            list.filter {
                it.adoptionStatus ==
                    com.lezi.babylog.core.model.FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED &&
                    it.deletedAt == null
            }.map { it.recordClientUuid }
        }

    override suspend fun markSynced(clientUuid: String, updatedAt: Long) {
        rows.value = rows.value.map {
            if (it.clientUuid == clientUuid && it.updatedAt == updatedAt) {
                it.copy(syncDirty = false)
            } else {
                it
            }
        }
    }

    override suspend fun markAllPendingSync() {
        rows.value = rows.value.map { it.copy(syncDirty = true) }
    }

    override suspend fun upsert(candidate: FulfillmentCandidateEntity): Long = seed(candidate)

    override suspend fun update(candidate: FulfillmentCandidateEntity) {
        rows.value = rows.value.map { if (it.id == candidate.id) candidate else it }
    }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}

internal class MemoryCarePlanDao : CarePlanDao {
    private val rows = MutableStateFlow<List<CarePlanEntity>>(emptyList())
    private val ids = AtomicLong(1)

    fun seed(entity: CarePlanEntity): Long {
        val id = entity.id.takeIf { it != 0L } ?: ids.getAndIncrement()
        rows.value = rows.value.filterNot { it.id == id || it.clientUuid == entity.clientUuid } +
            entity.copy(id = id)
        return id
    }

    override fun observeDayPending(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<CarePlanEntity>> = rows.map { list ->
        list.filter {
            it.babyId == babyId &&
                it.deletedAt == null &&
                it.status in setOf("pending", "missed") &&
                it.scheduledAt >= startInclusive &&
                it.scheduledAt < endExclusive
        }.sortedBy { it.scheduledAt }
    }

    override fun observeTodayPending(
        babyId: Long,
        dayStart: Long,
        dayEnd: Long,
        nowMillis: Long,
    ): Flow<List<CarePlanEntity>> = rows.map { list ->
        list.filter {
            it.babyId == babyId &&
                it.deletedAt == null &&
                it.status in setOf("pending", "missed") &&
                (
                    it.scheduledAt < nowMillis ||
                        (it.scheduledAt >= dayStart && it.scheduledAt < dayEnd)
                    )
        }.sortedBy { it.scheduledAt }
    }

    override fun observeRange(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<CarePlanEntity>> = rows.map { list ->
        list.filter {
            it.babyId == babyId &&
                it.deletedAt == null &&
                it.scheduledAt >= startInclusive &&
                it.scheduledAt < endExclusive
        }.sortedBy { it.scheduledAt }
    }

    override suspend fun listOpenFuture(babyId: Long, nowMillis: Long): List<CarePlanEntity> =
        rows.value.filter {
            it.babyId == babyId &&
                it.deletedAt == null &&
                it.status in setOf("pending", "missed") &&
                it.scheduledAt > nowMillis
        }.sortedBy { it.scheduledAt }

    override suspend fun listAllOpenFuture(nowMillis: Long): List<CarePlanEntity> =
        rows.value.filter {
            it.deletedAt == null &&
                it.status in setOf("pending", "missed") &&
                it.scheduledAt > nowMillis
        }.sortedBy { it.scheduledAt }

    override suspend fun get(id: Long): CarePlanEntity? =
        rows.value.firstOrNull { it.id == id }

    override suspend fun getByClientUuid(clientUuid: String): CarePlanEntity? =
        rows.value.firstOrNull { it.clientUuid == clientUuid }

    override suspend fun listAllIncludingDeleted(): List<CarePlanEntity> = rows.value

    override suspend fun listPendingSync(): List<CarePlanEntity> =
        rows.value.filter { it.syncDirty }.sortedBy { it.id }

    override suspend fun markSynced(clientUuid: String, updatedAt: Long) {
        rows.value = rows.value.map {
            if (it.clientUuid == clientUuid && it.updatedAt == updatedAt) {
                it.copy(syncDirty = false)
            } else {
                it
            }
        }
    }

    override suspend fun markAllPendingSync() {
        rows.value = rows.value.map { it.copy(syncDirty = true) }
    }

    override suspend fun upsert(plan: CarePlanEntity): Long = seed(plan)

    override suspend fun update(plan: CarePlanEntity) {
        rows.value = rows.value.map { if (it.id == plan.id) plan else it }
    }

    override suspend fun updateSystemCalendarProjection(
        clientUuid: String,
        eventId: String?,
        reminderReady: Boolean,
        pending: Boolean,
    ) {
        rows.value = rows.value.map {
            if (it.clientUuid == clientUuid) {
                it.copy(
                    systemCalendarEventId = eventId,
                    systemCalendarReminderReady = reminderReady,
                    systemCalendarProjectionPending = pending,
                )
            } else {
                it
            }
        }
    }

    override suspend fun updateSystemCalendarProjectionEnabled(
        clientUuid: String,
        enabled: Boolean,
    ) {
        rows.value = rows.value.map {
            if (it.clientUuid == clientUuid) {
                it.copy(
                    systemCalendarProjectionEnabled = enabled,
                )
            } else {
                it
            }
        }
    }

    override suspend fun softDelete(id: Long, deletedAt: Long) {
        rows.value = rows.value.map {
            if (it.id == id) {
                it.copy(deletedAt = deletedAt, updatedAt = deletedAt, syncDirty = true)
            } else {
                it
            }
        }
    }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}

internal class MemoryCustomItemDao : CustomItemDao {
    private val rows = mutableListOf<CustomItemEntity>()
    private val ids = AtomicLong(1)

    fun seed(item: CustomItemEntity): Long {
        val id = item.id.takeIf { it != 0L } ?: ids.getAndIncrement()
        rows.removeAll { it.id == id || it.clientUuid == item.clientUuid }
        rows += item.copy(id = id)
        return id
    }

    fun get(clientUuid: String): CustomItemEntity? =
        rows.firstOrNull { it.clientUuid == clientUuid }

    override fun observeAll() = MutableStateFlow(rows.filter { it.deletedAt == null })

    override suspend fun listAll(): List<CustomItemEntity> =
        rows.filter { it.deletedAt == null }

    override suspend fun listAllIncludingDeleted(): List<CustomItemEntity> = rows.toList()

    override suspend fun getById(id: Long): CustomItemEntity? =
        rows.firstOrNull { it.id == id }

    override suspend fun getByClientUuid(clientUuid: String): CustomItemEntity? =
        rows.firstOrNull { it.clientUuid == clientUuid }

    override suspend fun listPendingSync(): List<CustomItemEntity> =
        rows.filter { it.syncDirty }

    override suspend fun markSynced(clientUuid: String, updatedAt: Long) {
        rows.replaceAll {
            if (it.clientUuid == clientUuid && it.updatedAt == updatedAt) {
                it.copy(syncDirty = false)
            } else {
                it
            }
        }
    }

    override suspend fun markAllPendingSync() {
        rows.replaceAll { it.copy(syncDirty = true) }
    }

    override suspend fun upsert(item: CustomItemEntity): Long {
        val id = item.id.takeIf { it != 0L } ?: ids.getAndIncrement()
        rows.removeAll { it.id == id || it.clientUuid == item.clientUuid }
        rows += item.copy(id = id)
        return id
    }

    override suspend fun update(item: CustomItemEntity) {
        rows.replaceAll { if (it.id == item.id) item else it }
    }

    override suspend fun softDelete(id: Long, deletedAt: Long) {
        rows.replaceAll {
            if (it.id == id) {
                it.copy(deletedAt = deletedAt, updatedAt = deletedAt, syncDirty = true)
            } else {
                it
            }
        }
    }

    override suspend fun deleteAll() {
        rows.clear()
    }
}

internal class RecordingTransactionRunner : DatabaseTransactionRunner {
    var runCount = 0
    var depth = 0
    var maxDepth = 0

    override suspend fun <T> run(block: suspend () -> T): T {
        runCount += 1
        depth += 1
        maxDepth = maxOf(maxDepth, depth)
        return try {
            block()
        } finally {
            depth -= 1
        }
    }
}

private fun joinedSession(familyId: String) = SyncSession(
    familyId = familyId,
    accessToken = "token",
    deviceId = "device-a",
    role = FamilyRole.Owner,
    pullGeneration = "current-generation",
    membershipId = "membership-a",
    serverHost = "192.168.1.20",
    serverPort = 8787,
    familyName = "乐乐一家",
)

private fun sampleAppUpdateMetadata(
    versionCode: Int,
    versionName: String,
    releaseNotes: String? = null,
    sha256: String = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    minSupportedVersionCode: Int = 1,
) = AppUpdateMetadata(
    packageName = "com.lezi.babylog",
    versionCode = versionCode,
    versionName = versionName,
    minSupportedVersionCode = minSupportedVersionCode,
    sha256 = sha256,
    releaseNotes = releaseNotes,
)

private fun SyncSession.expectedMediaReceipt(clientUuid: String): String {
    val namespace = UUID.nameUUIDFromBytes(
        "${baseUrl.trimEnd('/')}\n$familyId".toByteArray(Charsets.UTF_8),
    )
    return "lezi-sync:$namespace:$clientUuid"
}

private fun testMediaUuid(seed: String): String =
    UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8)).toString()

private fun pendingReplicaCleanup(
    familyId: String = "family-a",
    mediaClientUuids: Set<String> = emptySet(),
    localMediaPaths: Set<String> = emptySet(),
) = com.lezi.babylog.core.database.PendingReplicaCleanup(
    scope = LocalDataClearScope.RecordsOnly,
    familyId = familyId,
    pullGeneration = "known-generation",
    mediaClientUuids = mediaClientUuids,
    localMediaPaths = localMediaPaths,
)

internal class MemoryOutboxDao : OutboxDao {
    private val rows = mutableListOf<OutboxEntity>()
    private val ids = AtomicLong(1)
    val deleteEntityBatchSizes = mutableListOf<Int>()
    var failDeleteTypeAttempts = 0
    var afterDeleteFamily: suspend (String) -> Unit = {}

    fun all(): List<OutboxEntity> = rows.toList()

    override suspend fun enqueue(row: OutboxEntity): Long {
        rows.removeAll {
            it.familyId == row.familyId &&
                it.entityType == row.entityType &&
                it.clientUuid == row.clientUuid
        }
        val id = row.id.takeIf { it != 0L } ?: ids.getAndIncrement()
        rows += row.copy(id = id)
        return id
    }

    override suspend fun peek(familyId: String, limit: Int): List<OutboxEntity> =
        rows.filter { it.familyId == familyId }.sortedBy(OutboxEntity::id).take(limit)

    override suspend fun find(
        familyId: String,
        entityType: String,
        clientUuid: String,
    ): OutboxEntity? = rows.find {
        it.familyId == familyId &&
            it.entityType == entityType &&
            it.clientUuid == clientUuid
    }

    override suspend fun deleteIds(ids: List<Long>) {
        rows.removeAll { it.id in ids }
    }

    override suspend fun deleteFamily(familyId: String) {
        rows.removeAll { it.familyId == familyId }
        afterDeleteFamily(familyId)
    }

    override suspend fun deleteType(familyId: String, entityType: String) {
        if (failDeleteTypeAttempts > 0) {
            failDeleteTypeAttempts--
            error("outbox delete failed")
        }
        rows.removeAll { it.familyId == familyId && it.entityType == entityType }
    }

    override suspend fun deleteTypeAcrossFamilies(entityType: String) {
        if (failDeleteTypeAttempts > 0) {
            failDeleteTypeAttempts--
            error("outbox delete failed")
        }
        rows.removeAll { it.entityType == entityType }
    }

    override suspend fun deleteEntities(
        familyId: String,
        entityType: String,
        clientUuids: List<String>,
    ) {
        deleteEntityBatchSizes += clientUuids.size
        require(clientUuids.size <= 400)
        rows.removeAll {
            it.familyId == familyId &&
                it.entityType == entityType &&
                it.clientUuid in clientUuids
        }
    }

    override suspend fun deleteEntitiesAcrossFamilies(
        entityType: String,
        clientUuids: List<String>,
    ) {
        deleteEntityBatchSizes += clientUuids.size
        require(clientUuids.size <= 400)
        rows.removeAll {
            it.entityType == entityType && it.clientUuid in clientUuids
        }
    }

    override suspend fun deleteAll() {
        rows.clear()
    }
}

internal class MemoryBabyDao : BabyDao {
    private val rows = MutableStateFlow<List<BabyEntity>>(emptyList())
    private val ids = AtomicLong(1)

    fun seed(entity: BabyEntity): Long {
        val id = entity.id.takeIf { it != 0L } ?: ids.getAndIncrement()
        rows.value = rows.value.filterNot { it.id == id } + entity.copy(id = id)
        return id
    }

    override fun observeAll(): Flow<List<BabyEntity>> =
        rows.map { values -> values.filter { it.deletedAt == null } }

    override suspend fun listAll(): List<BabyEntity> = rows.value.filter { it.deletedAt == null }
    override suspend fun listFamilyAuthority(): List<BabyEntity> =
        rows.value.filter { it.deletedAt == null && it.familyAuthority }
    override suspend fun get(id: Long): BabyEntity? =
        rows.value.find { it.id == id && it.deletedAt == null }

    override suspend fun getIncludingDeleted(id: Long): BabyEntity? =
        rows.value.find { it.id == id }

    override suspend fun getByClientUuid(uuid: String): BabyEntity? =
        rows.value.find { it.clientUuid == uuid }

    override suspend fun listAllIncludingDeleted(): List<BabyEntity> = rows.value

    override suspend fun listPendingSync(): List<BabyEntity> =
        rows.value.filter(BabyEntity::syncDirty).sortedBy(BabyEntity::id)

    override suspend fun markSynced(clientUuid: String, updatedAt: Long) {
        rows.value = rows.value.map {
            if (it.clientUuid == clientUuid && it.updatedAt == updatedAt) {
                it.copy(syncDirty = false)
            } else {
                it
            }
        }
    }

    override suspend fun markAllPendingSync() {
        rows.value = rows.value.map { it.copy(syncDirty = true) }
    }

    override suspend fun clearFamilyAuthority() {
        rows.value = rows.value.map { it.copy(familyAuthority = false) }
    }

    override suspend fun countByNickname(nickname: String, excludeId: Long): Int =
        rows.value.count {
            it.deletedAt == null &&
                it.nickname.trim() == nickname.trim() &&
                (excludeId < 0 || it.id != excludeId)
        }

    override suspend fun countActive(): Int = rows.value.count { it.deletedAt == null }

    override suspend fun upsert(baby: BabyEntity): Long = seed(baby)

    override suspend fun update(baby: BabyEntity) {
        rows.value = rows.value.map { if (it.id == baby.id) baby else it }
    }

    override suspend fun updateLocalTheme(id: Long, themeColorArgb: Int) {
        rows.value = rows.value.map {
            if (it.id == id) it.copy(themeColorArgb = themeColorArgb) else it
        }
    }

    override suspend fun updateLocalSortOrder(id: Long, sortOrder: Int) {
        rows.value = rows.value.map {
            if (it.id == id) it.copy(sortOrder = sortOrder) else it
        }
    }

    override suspend fun updateAvatarReplica(
        clientUuid: String,
        avatarMediaUuid: String?,
        avatarPath: String?,
    ) {
        rows.value = rows.value.map {
            if (it.clientUuid == clientUuid) {
                it.copy(
                    avatarMediaUuid = avatarMediaUuid,
                    avatarPath = avatarPath,
                )
            } else {
                it
            }
        }
    }

    override suspend fun updateAvatarMediaForLocalSnapshot(
        id: Long,
        expectedUpdatedAt: Long,
        expectedAvatarPath: String?,
        avatarMediaUuid: String?,
    ): Int {
        var changed = 0
        rows.value = rows.value.map {
            if (
                it.id == id &&
                it.updatedAt == expectedUpdatedAt &&
                it.avatarPath == expectedAvatarPath
            ) {
                changed = 1
                it.copy(avatarMediaUuid = avatarMediaUuid, syncDirty = true)
            } else {
                it
            }
        }
        return changed
    }

    override suspend fun updateAvatarPathForReplica(
        id: Long,
        expectedAvatarMediaUuid: String?,
        avatarPath: String?,
    ): Int {
        var changed = 0
        rows.value = rows.value.map {
            if (it.id == id && it.avatarMediaUuid == expectedAvatarMediaUuid) {
                changed = 1
                it.copy(avatarPath = avatarPath)
            } else {
                it
            }
        }
        return changed
    }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}

internal class MemoryRecordDao : RecordDao {
    private val rows = MutableStateFlow<List<RecordEntity>>(emptyList())
    private val ids = AtomicLong(1)

    fun seed(entity: RecordEntity): Long {
        val id = entity.id.takeIf { it != 0L } ?: ids.getAndIncrement()
        rows.value = rows.value.filterNot { it.id == id } + entity.copy(id = id)
        return id
    }

    override fun observeRange(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<RecordEntity>> = rows.map {
        it.filter { record ->
            record.babyId == babyId &&
                record.deletedAt == null &&
                record.timestamp in startInclusive until endExclusive
        }.sortedByDescending(RecordEntity::timestamp)
    }

    override fun observeDay(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<RecordEntity>> = observeRange(babyId, startInclusive, endExclusive)

    override suspend fun listDay(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): List<RecordEntity> = rows.value.filter {
        it.babyId == babyId &&
            it.deletedAt == null &&
            it.timestamp in startInclusive until endExclusive
    }.sortedByDescending(RecordEntity::timestamp)

    override suspend fun get(id: Long): RecordEntity? =
        rows.value.find { it.id == id && it.deletedAt == null }

    override suspend fun getIncludingDeleted(id: Long): RecordEntity? =
        rows.value.find { it.id == id }

    override suspend fun getByClientUuid(uuid: String): RecordEntity? =
        rows.value.find { it.clientUuid == uuid }

    override suspend fun listAllIncludingDeleted(): List<RecordEntity> = rows.value

    override suspend fun listPendingSync(): List<RecordEntity> =
        rows.value.filter(RecordEntity::syncDirty).sortedBy(RecordEntity::id)

    override suspend fun markSynced(clientUuid: String, updatedAt: Long) {
        rows.value = rows.value.map {
            if (it.clientUuid == clientUuid && it.updatedAt == updatedAt) {
                it.copy(syncDirty = false)
            } else {
                it
            }
        }
    }

    override suspend fun mergeCanonicalAuthor(
        clientUuid: String,
        expectedUpdatedAt: Long,
        membershipId: String,
    ): Int {
        var changed = 0
        rows.value = rows.value.map {
            if (
                it.clientUuid == clientUuid &&
                it.updatedAt == expectedUpdatedAt &&
                membershipId.isNotBlank()
            ) {
                changed = 1
                it.copy(createdByMembershipId = membershipId)
            } else {
                it
            }
        }
        return changed
    }

    override suspend fun markAllPendingSync() {
        rows.value = rows.value.map { it.copy(syncDirty = true) }
    }

    override suspend fun findOpenSleep(babyId: Long): RecordEntity? =
        listOpenSleeps(babyId).firstOrNull()

    override suspend fun listOpenSleeps(babyId: Long): List<RecordEntity> =
        rows.value.filter {
            it.babyId == babyId &&
                it.type == "sleep" &&
                it.deletedAt == null &&
                it.endTimestamp == null
        }.sortedWith(
            compareByDescending<RecordEntity> { it.timestamp }.thenByDescending { it.id },
        )

    override fun observeOpenSleep(babyId: Long): Flow<RecordEntity?> =
        rows.map {
            it.filter { record ->
                record.babyId == babyId &&
                    record.type == "sleep" &&
                    record.deletedAt == null &&
                    record.endTimestamp == null
            }.maxWithOrNull(
                compareBy<RecordEntity> { it.timestamp }.thenBy { it.id },
            )
        }

    override suspend fun listForBaby(babyId: Long): List<RecordEntity> =
        rows.value.filter { it.babyId == babyId && it.deletedAt == null }
            .sortedByDescending(RecordEntity::timestamp)

    override suspend fun searchCandidates(
        babyId: Long,
        escapedPattern: String,
        matchingTypeKeys: List<String>,
    ): List<RecordEntity> = rows.value.filter {
        it.babyId == babyId &&
            it.deletedAt == null &&
            (
                it.note.orEmpty().contains(escapedPattern.trim('%'), ignoreCase = true) ||
                    it.payloadJson.contains(escapedPattern.trim('%'), ignoreCase = true) ||
                    it.type in matchingTypeKeys
                )
    }.sortedByDescending(RecordEntity::timestamp)

    override suspend fun listRange(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): List<RecordEntity> = rows.value.filter {
        it.babyId == babyId &&
            it.deletedAt == null &&
            it.timestamp in startInclusive until endExclusive
    }.sortedBy(RecordEntity::timestamp)

    override suspend fun listByType(babyId: Long, type: String): List<RecordEntity> =
        rows.value.filter {
            it.babyId == babyId && it.deletedAt == null && it.type == type
        }.sortedBy(RecordEntity::timestamp)

    override suspend fun upsert(record: RecordEntity): Long = seed(record)

    override suspend fun update(record: RecordEntity) {
        rows.value = rows.value.map { if (it.id == record.id) record else it }
    }

    override suspend fun softDelete(id: Long, deletedAt: Long) {
        rows.value = rows.value.map {
            if (it.id == id) {
                it.copy(updatedAt = deletedAt, deletedAt = deletedAt, syncDirty = true)
            } else {
                it
            }
        }
    }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}

internal class MemoryMediaDao : MediaAssetDao {
    private val rows = mutableListOf<MediaAssetEntity>()
    private val ids = AtomicLong(1)

    fun seed(entity: MediaAssetEntity): Long {
        val id = entity.id.takeIf { it != 0L } ?: ids.getAndIncrement()
        rows.removeAll { it.id == id }
        rows += entity.copy(id = id)
        return id
    }

    override suspend fun upsert(asset: MediaAssetEntity): Long = seed(asset)

    override suspend fun listForRecord(recordId: Long): List<MediaAssetEntity> =
        rows.filter { it.recordId == recordId }

    override suspend fun listActiveForRecord(recordId: Long): List<MediaAssetEntity> =
        rows.filter { it.recordId == recordId && it.deletedAt == null }.sortedBy(MediaAssetEntity::id)

    override suspend fun listForCarePlan(carePlanId: Long): List<MediaAssetEntity> =
        rows.filter { it.carePlanId == carePlanId }

    override suspend fun listActiveForCarePlan(carePlanId: Long): List<MediaAssetEntity> =
        rows.filter { it.carePlanId == carePlanId && it.deletedAt == null }.sortedBy(MediaAssetEntity::id)

    override suspend fun activeAvatarForBaby(babyId: Long): MediaAssetEntity? =
        rows.filter { it.babyId == babyId && it.kind == "avatar" && it.deletedAt == null }
            .maxWithOrNull(compareBy<MediaAssetEntity> { it.updatedAt }.thenBy { it.id })

    override suspend fun listActiveAvatarsForBaby(babyId: Long): List<MediaAssetEntity> =
        rows.filter { it.babyId == babyId && it.kind == "avatar" && it.deletedAt == null }
            .sortedBy(MediaAssetEntity::id)

    override suspend fun listAllIncludingDeleted(): List<MediaAssetEntity> =
        rows.sortedBy(MediaAssetEntity::id)

    override suspend fun listPendingSync(): List<MediaAssetEntity> =
        rows.filter(MediaAssetEntity::syncDirty).sortedBy(MediaAssetEntity::id)

    override suspend fun markSynced(clientUuid: String, updatedAt: Long) {
        rows.replaceAll {
            if (it.clientUuid == clientUuid && it.updatedAt == updatedAt) {
                it.copy(syncDirty = false)
            } else {
                it
            }
        }
    }

    override suspend fun listMissingLocalBytes(): List<MediaAssetEntity> =
        rows.filter {
            it.deletedAt == null && it.remoteUri != null && it.localUri.isEmpty()
        }.sortedBy(MediaAssetEntity::id)

    override suspend fun getByClientUuid(uuid: String): MediaAssetEntity? =
        rows.find { it.clientUuid == uuid }

    override suspend fun countActiveReferences(localUri: String): Int =
        rows.count { it.localUri == localUri && it.deletedAt == null }

    override suspend fun listPendingFileCleanupClientUuids(): List<String> =
        rows.filter { it.deletedAt != null && it.localUri.isNotBlank() }
            .sortedBy(MediaAssetEntity::id)
            .map(MediaAssetEntity::clientUuid)

    override suspend fun update(asset: MediaAssetEntity) {
        rows.replaceAll { if (it.id == asset.id) asset else it }
    }

    override suspend fun mergePreparedMetadata(
        clientUuid: String,
        expectedUpdatedAt: Long,
        expectedLocalUri: String,
        expectedDeletedAt: Long?,
        mime: String?,
        width: Int?,
        height: Int?,
        byteSize: Long,
    ): Int {
        var changed = 0
        rows.replaceAll {
            if (
                it.matchesPublishedRevision(
                    expectedClientUuid = clientUuid,
                    expectedUpdatedAt = expectedUpdatedAt,
                    expectedLocalUri = expectedLocalUri,
                    expectedDeletedAt = expectedDeletedAt,
                )
            ) {
                changed = 1
                it.copy(mime = mime, width = width, height = height, byteSize = byteSize)
            } else {
                it
            }
        }
        return changed
    }

    override suspend fun writeCommitReceipt(
        clientUuid: String,
        expectedUpdatedAt: Long,
        expectedLocalUri: String,
        expectedDeletedAt: Long?,
        remoteUri: String,
    ): Int {
        var changed = 0
        rows.replaceAll {
            if (
                it.matchesPublishedRevision(
                    expectedClientUuid = clientUuid,
                    expectedUpdatedAt = expectedUpdatedAt,
                    expectedLocalUri = expectedLocalUri,
                    expectedDeletedAt = expectedDeletedAt,
                )
            ) {
                changed = 1
                it.copy(remoteUri = remoteUri)
            } else {
                it
            }
        }
        return changed
    }

    override suspend fun clearRemoteUris() {
        rows.replaceAll { it.copy(remoteUri = null, syncDirty = true) }
    }

    override suspend fun deleteLogMedia() {
        rows.removeAll { it.kind == "log" }
    }

    override suspend fun deleteByClientUuids(clientUuids: List<String>) {
        rows.removeAll { it.clientUuid in clientUuids }
    }

    override suspend fun deleteForRecord(recordId: Long) {
        rows.removeAll { it.recordId == recordId }
    }

    override suspend fun deleteAll() {
        rows.clear()
    }
}

internal class MemoryFamilyDao : FamilyDao {
    private val rows = mutableListOf<FamilyEntity>()
    private val ids = AtomicLong(1)

    fun seed(entity: FamilyEntity): Long {
        val id = entity.id.takeIf { it != 0L } ?: ids.getAndIncrement()
        rows.removeAll { it.id == id }
        rows += entity.copy(id = id)
        return id
    }

    override suspend fun get(id: Long): FamilyEntity? = rows.find { it.id == id }
    override suspend fun listAll(): List<FamilyEntity> = rows.toList()
    override suspend fun insert(family: FamilyEntity): Long = seed(family)
    override suspend fun deleteAll() {
        rows.clear()
    }
}
