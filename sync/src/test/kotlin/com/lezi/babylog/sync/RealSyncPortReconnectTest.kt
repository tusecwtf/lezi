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
class RealSyncPortReconnectTest {
    @Test
    fun reconnectCandidateRequiresSetupHealthAndReadyWithoutTouchingCurrentReplica() = runTest {
        val candidate = TrustedEndpointProfile.systemPki("https://nas-new.example.test")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            setupProbe = SetupProbe { _, _ ->
                SetupProbeResult.Ready(
                    candidate,
                    SetupFamilyState.Configured,
                )
            },
        )
        val oldSession = rig.preferences.current()
        val oldEndpoint = rig.preferences.verifiedEndpoint.first()

        val result = rig.port.probeReconnectEndpoint(candidate.origin)

        assertThat(result).isEqualTo(
            SetupProbeResult.Ready(candidate, SetupFamilyState.Configured),
        )
        assertThat(rig.backend.anonymousHealthEndpoints).containsExactly(candidate)
        assertThat(rig.backend.anonymousReadyEndpoints).containsExactly(candidate)
        assertThat(rig.preferences.current()).isEqualTo(oldSession)
        assertThat(rig.preferences.verifiedEndpoint.first()).isEqualTo(oldEndpoint)
    }

    @Test
    fun reconnectCandidateRejectsMissingHealthCapabilityAndVersionDrift() = runTest {
        val candidate = TrustedEndpointProfile.systemPki("https://nas-new.example.test")
        fun rig() = SyncRig(
            session = joinedSession("family-a"),
            setupProbe = SetupProbe { _, _ ->
                SetupProbeResult.Ready(candidate, SetupFamilyState.Configured)
            },
        )

        val missingCapability = rig().also {
            it.backend.anonymousHealthResult = it.backend.anonymousHealthResult.copy(
                capabilities = setOf("atomic_bundle"),
            )
        }
        assertThat(missingCapability.port.probeReconnectEndpoint(candidate.origin))
            .isEqualTo(SetupProbeResult.Failed.Incompatible)

        val versionDrift = rig().also {
            it.backend.anonymousReadyResult = AnonymousReadiness(version = "0.3.2")
        }
        assertThat(versionDrift.port.probeReconnectEndpoint(candidate.origin))
            .isEqualTo(SetupProbeResult.Failed.Incompatible)
    }

    @Test
    fun reconnectCandidateRejectsServerWithoutValidatedDeferredFulfillment() = runTest {
        val candidate = TrustedEndpointProfile.systemPki("https://nas-old.example.test")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            setupProbe = SetupProbe { _, _ ->
                SetupProbeResult.Ready(candidate, SetupFamilyState.Configured)
            },
        )
        rig.backend.anonymousHealthResult = rig.backend.anonymousHealthResult.copy(
            capabilities = setOf(
                "atomic_bundle",
                "record_membership_author",
                "device_disaster_restore_v1",
            ),
        )

        assertThat(rig.port.probeReconnectEndpoint(candidate.origin))
            .isEqualTo(SetupProbeResult.Failed.Incompatible)
    }

    @Test
    fun reconnectCandidateWaitsForCertificateApprovalBeforeAnonymousProtocolProbes() = runTest {
        val candidate = CertificateTrustCandidate.fromSpki(
            TrustedEndpointProfile.systemPki("https://nas-new.example.test"),
            "new-nas-public-key".toByteArray(),
        )
        val rig = SyncRig(
            session = joinedSession("family-a"),
            setupProbe = SetupProbe { _, trusted ->
                if (trusted == null) {
                    SetupProbeResult.CertificateApprovalRequired(candidate)
                } else {
                    SetupProbeResult.Ready(trusted, SetupFamilyState.Empty)
                }
            },
        )

        assertThat(rig.port.probeReconnectEndpoint(candidate.endpointOrigin)).isEqualTo(
            SetupProbeResult.CertificateApprovalRequired(candidate),
        )
        assertThat(rig.backend.anonymousHealthCalls).isEqualTo(0)
        assertThat(rig.backend.anonymousReadyCalls).isEqualTo(0)

        assertThat(rig.port.trustReconnectCertificate(candidate)).isEqualTo(
            SetupProbeResult.Ready(candidate.trustedEndpoint(), SetupFamilyState.Empty),
        )
        assertThat(rig.backend.anonymousHealthEndpoints)
            .containsExactly(candidate.trustedEndpoint())
        assertThat(rig.backend.anonymousReadyEndpoints)
            .containsExactly(candidate.trustedEndpoint())
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun reconnectCandidateProtocolProbeHasOneBoundedTimeout() = runTest {
        val candidate = TrustedEndpointProfile.systemPki("https://nas-new.example.test")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            setupProbe = SetupProbe { _, _ ->
                SetupProbeResult.Ready(candidate, SetupFamilyState.Configured)
            },
        )
        rig.backend.anonymousHealthGate = CompletableDeferred()

        assertThat(rig.port.probeReconnectEndpoint(candidate.origin))
            .isEqualTo(SetupProbeResult.Failed.ResponseTimedOut)
        assertThat(currentTime).isEqualTo(8_000L)
        val waitTimeout = com.lezi.babylog.sync.session.familyFailureKind(
            SetupProbeResult.Failed.ResponseTimedOut,
        )
        assertThat(waitTimeout)
            .isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.ResponseTimedOut)
        assertThat(
            com.lezi.babylog.core.common.failure.failureExplanation(waitTimeout).title,
        ).isEqualTo("家里服务器没有及时回应")
        assertThat(waitTimeout)
            .isNotEqualTo(com.lezi.babylog.core.common.failure.FailureKind.Unreachable)
    }

    @Test
    fun configuredCandidateWithDifferentFamilyNeverReplacesCurrentEndpointOrSession() = runTest {
        val candidate = TrustedEndpointProfile.systemPki("https://nas-new.example.test")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            setupProbe = SetupProbe { _, trusted ->
                SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Configured)
            },
        )
        val oldSession = rig.preferences.current()
        val oldEndpoint = rig.preferences.verifiedEndpoint.first()
        rig.backend.nextOwnerLoginFamilyId = "family-b"

        val result = rig.port.reconnectOwner(
            endpoint = candidate,
            deviceName = "新 NAS 登录",
            rootPassword = "root-password",
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(DifferentFamilyServerException::class.java)
        assertThat(rig.preferences.current()).isEqualTo(oldSession)
        assertThat(rig.preferences.verifiedEndpoint.first()).isEqualTo(oldEndpoint)
        assertThat(rig.backend.ownerLoginTakeovers).containsExactly(false)
        assertThat(rig.backend.ownerLoginCandidateEndpoints).containsExactly(candidate)
        assertThat(rig.backend.deviceLogoutCalls).isEqualTo(1)
        assertThat(rig.backend.ownerLoginRootPasswords).containsExactly("root-password")
    }

    @Test
    fun configuredCandidateForSameFamilySwitchesEndpointAndSessionTogether() = runTest {
        val candidate = TrustedEndpointProfile.systemPki("https://nas-new.example.test")
        val rig = SyncRig(
            session = joinedSession("family-a"),
            setupProbe = SetupProbe { _, trusted ->
                SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Configured)
            },
        )
        rig.backend.nextOwnerLoginFamilyId = "family-a"
        val handshakeGate = CompletableDeferred<Unit>()
        rig.backend.handshakeGate = handshakeGate
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 5L,
            generation = "generation-after-reconnect",
            hasMore = false,
        )

        try {
            val joined = rig.port.reconnectOwner(
                endpoint = candidate,
                deviceName = "新 NAS 登录",
                rootPassword = "root-password",
            ).getOrThrow().session

            assertThat(joined.familyId).isEqualTo("family-a")
            assertThat(TrustedEndpointProfile.systemPki(joined.baseUrl)).isEqualTo(candidate)
            val persisted = rig.preferences.current()
            assertThat(
                persisted.copy(
                    pullCursor = joined.pullCursor,
                    pullGeneration = joined.pullGeneration,
                    lastSuccessAt = joined.lastSuccessAt,
                ),
            ).isEqualTo(joined)
            assertThat(persisted.familyId).isEqualTo(joined.familyId)
            assertThat(persisted.deviceId).isEqualTo(joined.deviceId)
            assertThat(persisted.membershipId).isEqualTo(joined.membershipId)
            assertThat(persisted.role).isEqualTo(joined.role)
            assertThat(persisted.accessToken).isEqualTo(joined.accessToken)
            assertThat(persisted.refreshToken).isEqualTo(joined.refreshToken)
            assertThat(persisted.accessExpiresAtEpochSeconds)
                .isEqualTo(joined.accessExpiresAtEpochSeconds)
            assertThat(persisted.reauthRequired).isEqualTo(joined.reauthRequired)
            assertThat(persisted.isJoined).isEqualTo(joined.isJoined)
            assertThat(TrustedEndpointProfile.systemPki(persisted.baseUrl)).isEqualTo(candidate)
            if (persisted.pullCursor > joined.pullCursor) {
                assertThat(persisted.pullCursor).isEqualTo(5L)
                assertThat(persisted.pullGeneration).isEqualTo("generation-after-reconnect")
                assertThat(persisted.lastSuccessAt).isNotNull()
                joined.lastSuccessAt?.let { assertThat(persisted.lastSuccessAt).isAtLeast(it) }
            } else {
                assertThat(persisted.pullCursor).isEqualTo(joined.pullCursor)
                assertThat(persisted.pullGeneration).isEqualTo(joined.pullGeneration)
                assertThat(persisted.lastSuccessAt).isEqualTo(joined.lastSuccessAt)
            }
            assertThat(rig.backend.ownerLoginTakeovers).containsExactly(false, true).inOrder()
            assertThat(rig.preferences.verifiedEndpoint.first()).isEqualTo(candidate)
            assertThat(rig.preferences.familyMemberDirectory.first()).isEmpty()
        } finally {
            handshakeGate.complete(Unit)
        }
    }

    @Test
    fun ownerReconnectRetriesWithOneDurableTakeoverRequestInsteadOfMintingGhostDevices() =
        runTest {
            val candidate = TrustedEndpointProfile.systemPki("https://nas-new.example.test")
            val rig = SyncRig(
                session = joinedSession("family-a"),
                setupProbe = SetupProbe { _, trusted ->
                    SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Configured)
                },
            )
            rig.backend.nextOwnerLoginFamilyId = "family-a"
            rig.backend.ownerLoginFailure = SyncHttpException(503, "maintenance")

            assertThat(
                rig.port.reconnectOwner(candidate, "同一部手机", "root-password").isFailure,
            ).isTrue()
            rig.backend.ownerLoginFailure = null
            assertThat(
                rig.port.reconnectOwner(candidate, "同一部手机", "root-password").isSuccess,
            ).isTrue()

            assertThat(rig.backend.ownerLoginTakeovers).containsExactly(false, false, true).inOrder()
            assertThat(rig.backend.ownerLoginRequestIds).hasSize(3)
            assertThat(rig.backend.ownerLoginRequestIds.take(2).distinct()).hasSize(1)
            assertThat(rig.backend.ownerLoginRequestIds[2]).isNotEqualTo(rig.backend.ownerLoginRequestIds[0])
        }

    @Test
    fun memberCandidateWaitsWithoutReplacingOldSessionThenBlocksDifferentFamilyClaim() = runTest {
        val candidate = TrustedEndpointProfile.systemPki("https://nas-new.example.test")
        val rig = SyncRig(
            session = joinedSession("family-a").copy(role = FamilyRole.Member),
            setupProbe = SetupProbe { _, trusted ->
                SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Configured)
            },
        )
        val oldSession = rig.preferences.current()
        val oldEndpoint = rig.preferences.verifiedEndpoint.first()

        val pending = rig.port.requestReconnectMember(candidate, "妈妈", "妈妈手机")
            .getOrThrow()

        assertThat(pending.requestId).isEqualTo(rig.backend.nextMemberLoginReceipt.requestId)
        assertThat(rig.preferences.current()).isEqualTo(oldSession)
        assertThat(rig.preferences.verifiedEndpoint.first()).isEqualTo(oldEndpoint)
        assertThat(rig.backend.memberLoginCandidateEndpoints).containsExactly(candidate)

        rig.backend.memberLoginStatuses += MemberLoginStatus.Approved
        rig.backend.nextMemberLoginClaim = rig.backend.nextMemberLoginClaim.copy(
            familyId = "family-b",
        )
        val result = rig.port.checkReconnectMember()

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(DifferentFamilyServerException::class.java)
        assertThat(rig.preferences.current()).isEqualTo(oldSession)
        assertThat(rig.preferences.verifiedEndpoint.first()).isEqualTo(oldEndpoint)
    }

    @Test
    fun reconnectMemberClaimedStatusReplaysClaimInsteadOfDiscardingTheAttempt() = runTest {
        val candidate = TrustedEndpointProfile.systemPki("https://nas-new.example.test")
        val rig = SyncRig(
            session = joinedSession("family-a").copy(role = FamilyRole.Member),
            setupProbe = SetupProbe { _, trusted ->
                SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Configured)
            },
        )
        rig.port.requestReconnectMember(candidate, "妈妈", "妈妈手机").getOrThrow()
        rig.backend.memberLoginStatuses += MemberLoginStatus.Claimed
        rig.backend.nextMemberLoginClaim = rig.backend.nextMemberLoginClaim.copy(
            familyId = "family-a",
        )

        val result = rig.port.checkReconnectMember().getOrThrow()

        assertThat(result).isInstanceOf(MemberLoginCheckResult.Joined::class.java)
        assertThat(rig.backend.memberLoginClaimCalls).isEqualTo(1)
        assertThat(rig.preferences.current().familyId).isEqualTo("family-a")
        assertThat(rig.preferences.current().isJoined).isTrue()
    }

    @Test
    fun reconnectMemberTransientClaimReplayFailureKeepsTheAttemptForRetry() = runTest {
        val candidate = TrustedEndpointProfile.systemPki("https://nas-new.example.test")
        val rig = SyncRig(
            session = joinedSession("family-a").copy(role = FamilyRole.Member),
            setupProbe = SetupProbe { _, trusted ->
                SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Configured)
            },
        )
        rig.port.requestReconnectMember(candidate, "妈妈", "妈妈手机").getOrThrow()
        rig.backend.memberLoginStatuses += MemberLoginStatus.Claimed
        rig.backend.memberLoginClaimFailure = SyncHttpException(503, "maintenance")

        assertThat(rig.port.checkReconnectMember().isFailure).isTrue()

        rig.backend.memberLoginClaimFailure = null
        rig.backend.memberLoginStatuses += MemberLoginStatus.Claimed
        rig.backend.nextMemberLoginClaim = rig.backend.nextMemberLoginClaim.copy(
            familyId = "family-a",
        )
        assertThat(rig.port.checkReconnectMember().isSuccess).isTrue()
        assertThat(rig.backend.memberLoginClaimCalls).isEqualTo(2)
        assertThat(rig.preferences.current().isJoined).isTrue()
    }

    @Test
    fun reconnectMemberCancelClearsTheLocalAttemptBeforeRemoteCleanupCompletes() = runTest {
        val candidate = TrustedEndpointProfile.systemPki("https://nas-new.example.test")
        val rig = SyncRig(
            session = joinedSession("family-a").copy(role = FamilyRole.Member),
            setupProbe = SetupProbe { _, trusted ->
                SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Configured)
            },
        )
        rig.port.requestReconnectMember(candidate, "妈妈", "妈妈手机").getOrThrow()
        val remoteStarted = CompletableDeferred<Unit>()
        val remoteRelease = CompletableDeferred<Unit>()
        rig.backend.beforeCancelMemberLoginReturn = {
            remoteStarted.complete(Unit)
            remoteRelease.await()
        }

        val cancelling = async { rig.port.cancelReconnectMember().getOrThrow() }
        remoteStarted.await()

        withTimeout(1_000) { cancelling.await() }
        assertThat(
            rig.port.requestReconnectMember(candidate, "妈妈", "妈妈手机").isSuccess,
        ).isTrue()
        remoteRelease.complete(Unit)
    }

}
