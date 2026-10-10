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
class RealSyncPortEndpointTrustTest {
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
            TrustedEndpointProfile.systemPki("https://192.168.77.4:8765"),
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
    fun trustPersistFailureIsLocalNotUnreachable() = runTest {
        val session = joinedSession("family-a")
        val preferences = MemorySyncPreferences(session)
        preferences.rememberEndpointError = IllegalStateException("disk full")
        val candidate = CertificateTrustCandidate.fromSpki(
            TrustedEndpointProfile.systemPki("https://192.168.77.4:8765"),
            "stable-nas-public-key".toByteArray(),
        )
        val trusted = candidate.trustedEndpoint()
        val rig = SyncRig(
            session = session,
            syncPreferences = preferences,
            setupProbe = SetupProbe { _, _ ->
                SetupProbeResult.Ready(trusted, SetupFamilyState.Empty)
            },
        )
        val previous = preferences.verifiedEndpoint.first()

        val failure = runCatching { rig.port.trustCertificate(candidate) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(
            com.lezi.babylog.core.common.failure.LocalPersistException::class.java,
        )
        val kind = com.lezi.babylog.sync.session.familyFailureKind(failure!!)
        assertThat(kind).isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.LocalSaveFailed)
        val copy = com.lezi.babylog.core.common.failure.failureExplanation(kind!!)
        assertThat(copy.category)
            .isEqualTo(com.lezi.babylog.core.common.failure.FailureCategory.LocalData)
        assertThat(copy.actions).doesNotContain(
            com.lezi.babylog.core.common.failure.FailureAction.ChangeAddress,
        )
        assertThat(preferences.verifiedEndpoint.first()).isEqualTo(previous)
        assertThat(preferences.verifiedEndpoint.first()).isNotEqualTo(trusted)
    }

    @Test
    fun failedTrustedProbeClearsAStaleDurableResumeEndpoint() = runTest {
        val preferences = MemorySyncPreferences(SyncSession())
        preferences.rememberEndpoint(
            TrustedEndpointProfile.tofuSpki(
                "https://192.168.77.4:8765",
                "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",
            ),
        )
        val candidate = CertificateTrustCandidate.fromSpki(
            TrustedEndpointProfile.systemPki("https://192.168.77.4:8765"),
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

}
