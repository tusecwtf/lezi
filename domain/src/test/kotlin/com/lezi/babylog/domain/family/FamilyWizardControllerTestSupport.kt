package com.lezi.babylog.domain.family
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.CreateFamilyResult
import com.lezi.babylog.sync.session.CertificateTrustCandidate
import com.lezi.babylog.sync.session.FamilyEndpointConfig
import com.lezi.babylog.sync.InitialFamilyDataRecovery
import com.lezi.babylog.sync.OwnerLoginResult
import com.lezi.babylog.sync.MemberLoginCheckResult
import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import com.lezi.babylog.sync.MemberLoginQrResult
import com.lezi.babylog.sync.MemberLoginQrUnavailableException
import com.lezi.babylog.sync.backend.MemberLoginStatus
import com.lezi.babylog.sync.PendingMemberLogin
import com.lezi.babylog.sync.session.SetupFamilyState
import com.lezi.babylog.sync.session.SetupProbeResult
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

internal fun memberLoginQrPayload(
    endpoint: TrustedEndpointProfile,
) = MemberLoginQrPayload(
    endpoint = endpoint,
    grant = "grant-0000000000000000000000000000000000000",
    familyName = "乐乐一家",
    memberDisplayName = "妈妈",
    expiresAtEpochSeconds = 1_753_419_000,
)

internal fun snapshot(
    entry: FamilyWizardEntry,
    mode: FamilyWizardMode,
) = FamilyWizardSnapshot(
    entry = entry,
    mode = mode,
    step = FamilyWizardStep.Identity,
    host = "nas.home",
    portText = "443",
    scheme = "https",
    displayName = "  妈妈  ",
    familyName = "  我家  ",
    deviceName = "  Pixel  ",
)

internal data class CreateRequest(
    val config: FamilyEndpointConfig,
    val displayName: String,
    val deviceName: String,
    val bootstrapSecret: String,
    val familyName: String?,
)

internal data class OwnerLoginRequest(
    val config: FamilyEndpointConfig,
    val deviceName: String,
    val rootPassword: String,
    val takeover: Boolean,
)

internal class RecordingFamilyWizardGateway(
    var createResult: Result<CreateFamilyResult> = Result.success(
        CreateFamilyResult(ownerSession(), reclaimed = false),
    ),
    var ownerLoginResult: Result<OwnerLoginResult> = Result.success(
        OwnerLoginResult(ownerSession(), InitialFamilyDataRecovery.Complete),
    ),
    var recoveryResult: Result<Unit> = Result.success(Unit),
    var probeResult: SetupProbeResult = SetupProbeResult.Failed.Unreachable,
    var certificateAcceptanceResult: SetupProbeResult = SetupProbeResult.Failed.Unreachable,
    var memberLoginQrVerifyResult: SetupProbeResult = SetupProbeResult.Failed.Unreachable,
    var memberLoginQrClaimResult: Result<MemberLoginQrResult> = Result.failure(
        IllegalStateException("member login qr not stubbed"),
    ),
    var rememberEndpointResult: Result<Unit> = Result.success(Unit),
    var trustCertificateError: Throwable? = null,
    var memberRequestResult: Result<PendingMemberLogin>? = null,
) : FamilyWizardGateway {
    val pendingRequest = PendingMemberLogin(
        requestId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        displayName = "妈妈",
        deviceName = "Pixel",
        expiresAtEpochSeconds = 1_753_504_800,
    )
    var memberCheckResult: Result<MemberLoginCheckResult> = Result.success(
        MemberLoginCheckResult.Waiting(pendingRequest),
    )
    var memberRequestCalls = 0
    var cancelMemberCalls = 0
    var cancelMemberResult: Result<Unit> = Result.success(Unit)
    var cancelMemberStarted: CompletableDeferred<Unit>? = null
    var cancelMemberRelease: CompletableDeferred<Unit>? = null
    var memberLoginQrClaimCalls = 0
    var lastMemberLoginQrDeviceName: String? = null
    val events = mutableListOf<String>()
    var request: CreateRequest? = null
    var ownerLoginRequest: OwnerLoginRequest? = null
    var createCalls = 0
    var recoveryCalls = 0
    var probeStarted: CompletableDeferred<Unit>? = null
    var probeRelease: CompletableDeferred<Unit>? = null
    var rememberStarted: CompletableDeferred<Unit>? = null
    var rememberRelease: CompletableDeferred<Unit>? = null
    var memberLoginQrVerifyStarted: CompletableDeferred<Unit>? = null
    var memberLoginQrVerifyRelease: CompletableDeferred<Unit>? = null
    var memberLoginQrClaimStarted: CompletableDeferred<Unit>? = null
    var memberLoginQrClaimRelease: CompletableDeferred<Unit>? = null
    var memberRequestStarted: CompletableDeferred<Unit>? = null
    var memberRequestRelease: CompletableDeferred<Unit>? = null
    var createStarted: CompletableDeferred<Unit>? = null
    var createRelease: CompletableDeferred<Unit>? = null
    var ownerLoginStarted: CompletableDeferred<Unit>? = null
    var ownerLoginRelease: CompletableDeferred<Unit>? = null
    var memberCheckStarted: CompletableDeferred<Unit>? = null
    var memberCheckRelease: CompletableDeferred<Unit>? = null
    var recoveryStarted: CompletableDeferred<Unit>? = null
    var recoveryRelease: CompletableDeferred<Unit>? = null
    val rememberedEndpoints = mutableListOf<TrustedEndpointProfile>()
    var verifiedEndpoint: TrustedEndpointProfile? =
        TrustedEndpointProfile.systemPki("https://nas.home")

    override suspend fun probeEndpoint(endpointDraft: String): SetupProbeResult {
        events += "probe"
        probeStarted?.complete(Unit)
        probeRelease?.await()
        return probeResult
    }

    override suspend fun trustCertificate(candidate: CertificateTrustCandidate): SetupProbeResult {
        events += "accept-certificate"
        trustCertificateError?.let { throw it }
        return certificateAcceptanceResult
    }

    override suspend fun rememberEndpoint(endpoint: TrustedEndpointProfile): Result<Unit> {
        events += "remember"
        rememberStarted?.complete(Unit)
        rememberRelease?.await()
        if (rememberEndpointResult.isSuccess) {
            rememberedEndpoints += endpoint
            verifiedEndpoint = endpoint
        }
        return rememberEndpointResult
    }

    override suspend fun forgetEndpoint(): Result<Unit> {
        events += "forget"
        verifiedEndpoint = null
        return Result.success(Unit)
    }

    override suspend fun currentVerifiedEndpoint(): TrustedEndpointProfile? = verifiedEndpoint

    override suspend fun saveEndpointConfig(config: FamilyEndpointConfig): Result<Unit> {
        events += "save"
        return Result.success(Unit)
    }

    override suspend fun createFamily(
        config: FamilyEndpointConfig,
        displayName: String,
        deviceName: String,
        bootstrapSecret: String,
        familyName: String?,
    ): Result<CreateFamilyResult> {
        events += "create"
        createCalls += 1
        request = CreateRequest(config, displayName, deviceName, bootstrapSecret, familyName)
        createStarted?.complete(Unit)
        createRelease?.await()
        return createResult
    }

    override suspend fun ownerLogin(
        config: FamilyEndpointConfig,
        deviceName: String,
        rootPassword: String,
        takeover: Boolean,
    ): Result<OwnerLoginResult> {
        events += "owner-login"
        ownerLoginRequest = OwnerLoginRequest(config, deviceName, rootPassword, takeover)
        ownerLoginStarted?.complete(Unit)
        ownerLoginRelease?.await()
        return ownerLoginResult
    }

    override suspend fun requestMemberLogin(
        config: FamilyEndpointConfig,
        displayName: String,
        deviceName: String,
    ): Result<PendingMemberLogin> {
        events += "member-request"
        memberRequestCalls++
        memberRequestStarted?.complete(Unit)
        memberRequestRelease?.await()
        return memberRequestResult
            ?: Result.success(
                pendingRequest.copy(displayName = displayName, deviceName = deviceName),
            )
    }

    override suspend fun checkMemberLogin(): Result<MemberLoginCheckResult> {
        memberCheckStarted?.complete(Unit)
        memberCheckRelease?.await()
        return memberCheckResult
    }

    override suspend fun cancelMemberLogin(): Result<Unit> {
        cancelMemberCalls++
        cancelMemberStarted?.complete(Unit)
        cancelMemberRelease?.await()
        return cancelMemberResult
    }

    override suspend fun verifyMemberLoginEndpoint(
        endpoint: TrustedEndpointProfile,
    ): SetupProbeResult {
        events += "verify-member-login-qr"
        memberLoginQrVerifyStarted?.complete(Unit)
        memberLoginQrVerifyRelease?.await()
        return memberLoginQrVerifyResult
    }

    override suspend fun claimMemberLoginQr(
        payload: MemberLoginQrPayload,
        deviceName: String,
    ): Result<MemberLoginQrResult> {
        events += "claim-member-login-qr"
        memberLoginQrClaimCalls++
        lastMemberLoginQrDeviceName = deviceName
        memberLoginQrClaimStarted?.complete(Unit)
        memberLoginQrClaimRelease?.await()
        return memberLoginQrClaimResult
    }

    override suspend fun retryReclaimedDataRecovery(): Result<Unit> {
        events += "recover"
        recoveryCalls += 1
        recoveryStarted?.complete(Unit)
        recoveryRelease?.await()
        return recoveryResult
    }
}

internal class BlockingFamilyWizardGateway : FamilyWizardGateway {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var createCalls = 0

    override suspend fun probeEndpoint(endpointDraft: String): SetupProbeResult =
        SetupProbeResult.Failed.Unreachable

    override suspend fun trustCertificate(candidate: CertificateTrustCandidate): SetupProbeResult =
        SetupProbeResult.Failed.Unreachable

    override suspend fun rememberEndpoint(endpoint: TrustedEndpointProfile): Result<Unit> =
        Result.success(Unit)

    override suspend fun forgetEndpoint(): Result<Unit> = Result.success(Unit)

    override suspend fun currentVerifiedEndpoint(): TrustedEndpointProfile =
        TrustedEndpointProfile.systemPki("https://nas.home")

    override suspend fun saveEndpointConfig(config: FamilyEndpointConfig): Result<Unit> =
        Result.success(Unit)

    override suspend fun createFamily(
        config: FamilyEndpointConfig,
        displayName: String,
        deviceName: String,
        bootstrapSecret: String,
        familyName: String?,
    ): Result<CreateFamilyResult> {
        createCalls += 1
        started.complete(Unit)
        release.await()
        return Result.success(CreateFamilyResult(ownerSession(), reclaimed = false))
    }

    override suspend fun ownerLogin(
        config: FamilyEndpointConfig,
        deviceName: String,
        rootPassword: String,
        takeover: Boolean,
    ): Result<OwnerLoginResult> = Result.success(
        OwnerLoginResult(ownerSession(), InitialFamilyDataRecovery.Complete),
    )

    override suspend fun retryReclaimedDataRecovery(): Result<Unit> = Result.success(Unit)
}

internal fun ownerSession() = SyncSession(
    familyId = "family-owner",
    accessToken = "owner-token",
    role = com.lezi.babylog.sync.session.FamilyRole.Owner,
    membershipId = "owner-membership",
    serverHost = "nas.home",
)

internal fun memberSession() = SyncSession(
    familyId = "family-member",
    accessToken = "member-token",
    role = com.lezi.babylog.sync.session.FamilyRole.Member,
    membershipId = "member-membership",
    serverHost = "nas.home",
)
