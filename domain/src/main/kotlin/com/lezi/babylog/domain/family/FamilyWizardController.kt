package com.lezi.babylog.domain.family
import com.lezi.babylog.sync.CreateFamilyResult
import com.lezi.babylog.sync.session.CertificateTrustCandidate
import com.lezi.babylog.sync.session.FamilyEndpointConfig
import com.lezi.babylog.sync.InitialFamilyDataRecovery
import com.lezi.babylog.sync.OwnerLoginResult
import com.lezi.babylog.sync.MemberLoginCheckResult
import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import com.lezi.babylog.sync.MemberLoginQrResult
import com.lezi.babylog.sync.MemberLoginQrTrustChangedException
import com.lezi.babylog.sync.MemberLoginQrUnavailableException
import com.lezi.babylog.sync.backend.MemberLoginStatus
import com.lezi.babylog.sync.PendingMemberLogin
import com.lezi.babylog.sync.session.FamilyEndpointDraft
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.session.SetupFamilyState
import com.lezi.babylog.sync.session.SetupProbeResult
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import com.lezi.babylog.sync.session.familySyncError
import com.lezi.babylog.sync.session.memberDisplayNameValidationError
import java.io.Serializable
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import com.lezi.babylog.domain.CareLog

/** The two UI entries that project the same family wizard. Entry never changes a request. */
enum class FamilyWizardEntry { Onboarding, Account }

/** The only authoritative family-session actions. Reclaim is a create result, not a third mode. */
enum class FamilyWizardMode { Create, Join }

enum class FamilyWizardStep { Endpoint, Role, Identity }

enum class FamilyWizardJoinRole { Owner, Member }

/**
 * Process-retainable, non-sensitive wizard state. The bootstrap secret is intentionally absent and
 * must only be supplied to [FamilyWizardController.submit] for the active create request.
 */
data class FamilyWizardSnapshot(
    val entry: FamilyWizardEntry,
    val mode: FamilyWizardMode,
    val step: FamilyWizardStep,
    val host: String = "",
    val portText: String = com.lezi.babylog.sync.session.DEFAULT_SERVER_PORT.toString(),
    val scheme: String = com.lezi.babylog.sync.session.DEFAULT_SERVER_SCHEME,
    val displayName: String = "",
    val familyName: String = "",
    val deviceName: String = "",
    val endpointDraft: String = "",
    val joinRole: FamilyWizardJoinRole? = null,
) : Serializable {
    fun toEndpointDraft(): FamilyEndpointDraft = FamilyEndpointDraft(
        host = host,
        portText = portText,
        scheme = scheme,
    )

    companion object {
        fun empty(entry: FamilyWizardEntry): FamilyWizardSnapshot = FamilyWizardSnapshot(
            entry = entry,
            mode = FamilyWizardMode.Create,
            step = FamilyWizardStep.Endpoint,
        )

        fun fromDraft(
            entry: FamilyWizardEntry,
            mode: FamilyWizardMode,
            step: FamilyWizardStep,
            draft: FamilyEndpointDraft,
            displayName: String = "",
            familyName: String = "",
            deviceName: String = "",
        ): FamilyWizardSnapshot = FamilyWizardSnapshot(
            entry = entry,
            mode = mode,
            step = step,
            host = draft.host,
            portText = draft.portText,
            scheme = draft.scheme,
            displayName = displayName,
            familyName = familyName,
            deviceName = deviceName,
        )
    }
}

sealed interface FamilyWizardOutcome {
    val session: SyncSession

    sealed interface CreateSession : FamilyWizardOutcome {
        val dataRecovery: InitialFamilyDataRecovery
    }

    data class Created(
        override val session: SyncSession,
        override val dataRecovery: InitialFamilyDataRecovery = InitialFamilyDataRecovery.Complete,
    ) : CreateSession

    data class Reclaimed(
        override val session: SyncSession,
        override val dataRecovery: InitialFamilyDataRecovery,
    ) : CreateSession

    data class OwnerLoggedIn(
        override val session: SyncSession,
        override val dataRecovery: InitialFamilyDataRecovery,
    ) : CreateSession

    data class MemberApproved(
        override val session: SyncSession,
        override val dataRecovery: InitialFamilyDataRecovery,
    ) : CreateSession

    /** One-shot member login via admin QR; session is already durable when published. */
    data class MemberLoginQrClaimed(
        override val session: SyncSession,
        override val dataRecovery: InitialFamilyDataRecovery,
    ) : CreateSession

}

sealed interface FamilyWizardState {
    val snapshot: FamilyWizardSnapshot

    data class Editing(override val snapshot: FamilyWizardSnapshot) : FamilyWizardState

    data class Submitting(override val snapshot: FamilyWizardSnapshot) : FamilyWizardState

    data class ProbingEndpoint(override val snapshot: FamilyWizardSnapshot) : FamilyWizardState

    data class CertificateApprovalRequired(
        override val snapshot: FamilyWizardSnapshot,
        val candidate: CertificateTrustCandidate,
    ) : FamilyWizardState

    data class EndpointReady(
        override val snapshot: FamilyWizardSnapshot,
        val endpoint: TrustedEndpointProfile,
    ) : FamilyWizardState

    data class EndpointFailure(
        override val snapshot: FamilyWizardSnapshot,
        val reason: SetupProbeResult.Failed,
        val message: String,
    ) : FamilyWizardState

    data class RetryableFailure(
        override val snapshot: FamilyWizardSnapshot,
        val message: String,
        /** Non-null only after a reclaimed owner session is already durable. */
        val committedOutcome: FamilyWizardOutcome.CreateSession? = null,
    ) : FamilyWizardState

    data class WaitingForMemberApproval(
        override val snapshot: FamilyWizardSnapshot,
        val request: PendingMemberLogin,
        val feedback: String? = null,
    ) : FamilyWizardState

    /** In-flight setup-status check for a scanned member-login QR (does not remember trust). */
    data class VerifyingMemberLoginQr(
        override val snapshot: FamilyWizardSnapshot,
        val payload: MemberLoginQrPayload,
    ) : FamilyWizardState

    /** Endpoint matches QR pin and family is configured; ready for device-name claim. */
    data class MemberLoginQrReady(
        override val snapshot: FamilyWizardSnapshot,
        val payload: MemberLoginQrPayload,
        val feedback: String? = null,
    ) : FamilyWizardState

    /** Verify failed or family not ready; same payload may be retried or dismissed. */
    data class MemberLoginQrVerificationFailed(
        override val snapshot: FamilyWizardSnapshot,
        val payload: MemberLoginQrPayload,
        val message: String,
    ) : FamilyWizardState

    /** Claim in flight after the user confirmed device name. */
    data class ClaimingMemberLoginQr(
        override val snapshot: FamilyWizardSnapshot,
        val payload: MemberLoginQrPayload,
    ) : FamilyWizardState

    data class Completed(
        override val snapshot: FamilyWizardSnapshot,
        val outcome: FamilyWizardOutcome,
    ) : FamilyWizardState
}

/**
 * In-flight network work that should disable form submits and dialog dismissals.
 * Shared by onboarding + account hosts so the four-state predicate cannot drift.
 */
val FamilyWizardState.isBusy: Boolean
    get() = this is FamilyWizardState.Submitting ||
        this is FamilyWizardState.ProbingEndpoint ||
        this is FamilyWizardState.VerifyingMemberLoginQr ||
        this is FamilyWizardState.ClaimingMemberLoginQr

/** Side-effect seam kept narrow so the state machine can be exercised without Android or Hilt. */
interface FamilyWizardGateway {
    suspend fun probeEndpoint(endpointDraft: String): SetupProbeResult

    suspend fun trustCertificate(candidate: CertificateTrustCandidate): SetupProbeResult

    suspend fun rememberEndpoint(endpoint: TrustedEndpointProfile): Result<Unit>

    suspend fun forgetEndpoint(): Result<Unit>

    suspend fun currentVerifiedEndpoint(): TrustedEndpointProfile?

    suspend fun saveEndpointConfig(config: FamilyEndpointConfig): Result<Unit>

    suspend fun createFamily(
        config: FamilyEndpointConfig,
        displayName: String,
        deviceName: String = "Android 设备",
        bootstrapSecret: String,
        familyName: String?,
    ): Result<CreateFamilyResult>

    suspend fun ownerLogin(
        config: FamilyEndpointConfig,
        deviceName: String,
        rootPassword: String,
        takeover: Boolean,
    ): Result<OwnerLoginResult>

    suspend fun requestMemberLogin(
        config: FamilyEndpointConfig,
        displayName: String,
        deviceName: String,
    ): Result<PendingMemberLogin> = Result.failure(IllegalStateException("成员申请暂不可用"))

    suspend fun checkMemberLogin(): Result<MemberLoginCheckResult> =
        Result.failure(IllegalStateException("成员申请暂不可用"))

    suspend fun cancelMemberLogin(): Result<Unit> =
        Result.failure(IllegalStateException("成员申请暂不可用"))

    /**
     * Probes the pinned QR endpoint without remembering trust. Production uses
     * [SyncPort.verifyEndpoint]; default fails closed so tests can stub explicitly.
     */
    suspend fun verifyMemberLoginEndpoint(
        endpoint: TrustedEndpointProfile,
    ): SetupProbeResult = SetupProbeResult.Failed.Unreachable

    suspend fun claimMemberLoginQr(
        payload: MemberLoginQrPayload,
        deviceName: String,
    ): Result<MemberLoginQrResult> = Result.failure(IllegalStateException("成员登录二维码暂不可用"))

    suspend fun retryReclaimedDataRecovery(): Result<Unit>
}

internal interface FamilyWizardLocalStore {
    suspend fun ensureScaffold()

    suspend fun cacheDisplayName(displayName: String)
}

private class CareLogFamilyWizardLocalStore(
    private val careLog: CareLog,
) : FamilyWizardLocalStore {
    override suspend fun ensureScaffold() {
        careLog.ensureFamilyScaffold()
    }

    override suspend fun cacheDisplayName(displayName: String) {
        careLog.updateLocalDisplayName(displayName)
    }
}

private class CreateFamilyPreparationException(cause: Throwable) :
    IllegalStateException("准备本机家庭失败", cause)

/** Production adapter shared by the onboarding and account ViewModels. */
class SyncFamilyWizardGateway private constructor(
    private val sync: SyncPort,
    private val localStore: FamilyWizardLocalStore,
) : FamilyWizardGateway {
    constructor(
        sync: SyncPort,
        careLog: CareLog,
    ) : this(sync, CareLogFamilyWizardLocalStore(careLog))

    internal constructor(
        localStore: FamilyWizardLocalStore,
        sync: SyncPort,
    ) : this(sync, localStore)

    override suspend fun probeEndpoint(endpointDraft: String): SetupProbeResult =
        sync.probeEndpoint(endpointDraft)

    override suspend fun trustCertificate(
        candidate: CertificateTrustCandidate,
    ): SetupProbeResult = sync.trustCertificate(candidate)

    override suspend fun rememberEndpoint(endpoint: TrustedEndpointProfile): Result<Unit> =
        sync.rememberEndpoint(endpoint)

    override suspend fun forgetEndpoint(): Result<Unit> = sync.forgetEndpoint()

    override suspend fun currentVerifiedEndpoint(): TrustedEndpointProfile? =
        sync.verifiedEndpoint().first()

    override suspend fun saveEndpointConfig(config: FamilyEndpointConfig): Result<Unit> =
        sync.saveEndpointConfig(config)

    override suspend fun createFamily(
        config: FamilyEndpointConfig,
        displayName: String,
        deviceName: String,
        bootstrapSecret: String,
        familyName: String?,
    ): Result<CreateFamilyResult> {
        try {
            // A reclaimed full pull may contain Baby rows immediately. Their
            // local FK parent must exist before create commits the server session
            // and synchronously applies that cursor-zero snapshot.
            localStore.ensureScaffold()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return Result.failure(CreateFamilyPreparationException(error))
        }
        val result = sync.createFamily(
            displayName = displayName,
            deviceName = deviceName,
            bootstrapSecret = bootstrapSecret,
            familyName = familyName,
        )
        if (result.isSuccess) {
            try {
                localStore.cacheDisplayName(displayName)
            } catch (_: Exception) {
                // The server session is already committed; a display cache must not undo it.
            }
        }
        return result
    }

    override suspend fun ownerLogin(
        config: FamilyEndpointConfig,
        deviceName: String,
        rootPassword: String,
        takeover: Boolean,
    ): Result<OwnerLoginResult> {
        try {
            localStore.ensureScaffold()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return Result.failure(CreateFamilyPreparationException(error))
        }
        return sync.ownerLogin(
            deviceName = deviceName,
            rootPassword = rootPassword,
            takeover = takeover,
        )
    }

    override suspend fun requestMemberLogin(
        config: FamilyEndpointConfig,
        displayName: String,
        deviceName: String,
    ): Result<PendingMemberLogin> {
        try {
            localStore.ensureScaffold()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return Result.failure(CreateFamilyPreparationException(error))
        }
        return sync.requestMemberLogin(displayName, deviceName)
    }

    override suspend fun checkMemberLogin(): Result<MemberLoginCheckResult> =
        sync.checkMemberLogin()

    override suspend fun cancelMemberLogin(): Result<Unit> = sync.cancelMemberLogin()

    override suspend fun verifyMemberLoginEndpoint(
        endpoint: TrustedEndpointProfile,
    ): SetupProbeResult = sync.verifyEndpoint(endpoint)

    override suspend fun claimMemberLoginQr(
        payload: MemberLoginQrPayload,
        deviceName: String,
    ): Result<MemberLoginQrResult> {
        try {
            localStore.ensureScaffold()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return Result.failure(CreateFamilyPreparationException(error))
        }
        return sync.claimMemberLoginQr(payload, deviceName)
    }

    override suspend fun retryReclaimedDataRecovery(): Result<Unit> =
        sync.syncWhenAvailable(SyncTrigger.PullToRefresh)
}

/**
 * Shared create/login state machine. It retains only [FamilyWizardSnapshot], serializes submission,
 * keeps all failures recoverable, and exposes completion as a consume-once navigation signal.
 */
class FamilyWizardController(
    private val gateway: FamilyWizardGateway,
    initialSnapshot: FamilyWizardSnapshot = FamilyWizardSnapshot.empty(FamilyWizardEntry.Account),
) {
    private val submission = Mutex()
    private val mutableState = MutableStateFlow<FamilyWizardState>(
        FamilyWizardState.Editing(initialSnapshot),
    )
    val state: StateFlow<FamilyWizardState> = mutableState.asStateFlow()

    private var completionVersion = 0L
    private var consumedCompletionVersion = 0L
    private val endpointRequestVersion = AtomicLong(0)
    private val memberLoginQrRequestVersion = AtomicLong(0)
    private val endpointJobLock = Any()
    private var activeEndpointJob: Job? = null
    private var activeMemberLoginQrJob: Job? = null
    /**
     * True only while a claim remembered the QR endpoint but has not completed a session yet.
     * Survives claim failure → Ready so cancel/dismiss can still forget residual trust.
     */
    private var memberLoginQrRememberedEndpoint = false

    /**
     * Set when a non-suspend path (begin/keepOffline) drops a half-trusted QR endpoint
     * without being able to await [FamilyWizardGateway.forgetEndpoint]. Flushed on the next
     * suspend wizard entry that can call the gateway.
     */
    private var pendingMemberLoginQrForget = false

    /** Starts a fresh UI session after a prior completion (for example after later leaving family). */
    @Synchronized
    fun begin(snapshot: FamilyWizardSnapshot) {
        if (mutableState.value is FamilyWizardState.Submitting) return
        if (mutableState.value is FamilyWizardState.ClaimingMemberLoginQr) return
        cancelEndpointConnection()
        cancelMemberLoginQrWork(resetState = false)
        consumedCompletionVersion = completionVersion
        mutableState.value = FamilyWizardState.Editing(snapshot)
    }

    fun restore(snapshot: FamilyWizardSnapshot) {
        val current = mutableState.value
        if (current !is FamilyWizardState.Submitting && current !is FamilyWizardState.Completed) {
            mutableState.value = FamilyWizardState.Editing(snapshot)
        }
    }

    suspend fun connectEndpoint(
        entry: FamilyWizardEntry,
        endpointDraft: String,
    ) {
        if (!submission.tryLock()) return
        val endpointJob = currentCoroutineContext()[Job]
        synchronized(endpointJobLock) {
            activeEndpointJob = endpointJob
        }
        try {
            flushPendingMemberLoginQrForget()
            val requestVersion = endpointRequestVersion.incrementAndGet()
            val draft = FamilyWizardSnapshot.empty(entry).copy(endpointDraft = endpointDraft)
            mutableState.value = FamilyWizardState.ProbingEndpoint(draft)
            val result = gateway.probeEndpoint(endpointDraft)
            if (endpointRequestVersion.get() != requestVersion) return
            when (result) {
                is SetupProbeResult.CertificateApprovalRequired -> {
                    mutableState.value = FamilyWizardState.CertificateApprovalRequired(
                        snapshot = draft,
                        candidate = result.candidate,
                    )
                }
                is SetupProbeResult.Ready -> {
                    try {
                        gateway.rememberEndpoint(result.endpoint).getOrThrow()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        mutableState.value = FamilyWizardState.EndpointFailure(
                            snapshot = draft,
                            reason = SetupProbeResult.Failed.Unreachable,
                            message = "无法记住家庭服务器，请重试",
                        )
                        return
                    }
                    currentCoroutineContext().ensureActive()
                    if (endpointRequestVersion.get() != requestVersion) return
                    val mode = when (result.familyState) {
                        SetupFamilyState.Empty -> FamilyWizardMode.Create
                        SetupFamilyState.Configured -> FamilyWizardMode.Join
                    }
                    mutableState.value = FamilyWizardState.EndpointReady(
                        snapshot = draft.copy(
                            mode = mode,
                            step = if (mode == FamilyWizardMode.Join) {
                                FamilyWizardStep.Role
                            } else {
                                FamilyWizardStep.Identity
                            },
                            endpointDraft = result.endpoint.origin,
                        ),
                        endpoint = result.endpoint,
                    )
                }
                is SetupProbeResult.Failed -> mutableState.value = FamilyWizardState.EndpointFailure(
                    snapshot = draft,
                    reason = result,
                    message = result.userMessage(),
                )
            }
        } finally {
            synchronized(endpointJobLock) {
                if (activeEndpointJob === endpointJob) activeEndpointJob = null
            }
            submission.unlock()
        }
    }

    fun keepOffline(entry: FamilyWizardEntry) {
        if (mutableState.value is FamilyWizardState.Submitting) return
        if (mutableState.value is FamilyWizardState.ClaimingMemberLoginQr) return
        cancelEndpointConnection()
        cancelMemberLoginQrWork(resetState = false)
        mutableState.value = FamilyWizardState.Editing(FamilyWizardSnapshot.empty(entry))
    }

    suspend fun forgetEndpoint(entry: FamilyWizardEntry): Result<Unit> {
        val result = gateway.forgetEndpoint()
        if (result.isSuccess) keepOffline(entry)
        return result
    }

    private fun cancelEndpointConnection() {
        endpointRequestVersion.incrementAndGet()
        val endpointJob = synchronized(endpointJobLock) {
            activeEndpointJob.also { activeEndpointJob = null }
        }
        endpointJob?.cancel()
    }

    private fun cancelMemberLoginQrWork(resetState: Boolean) {
        memberLoginQrRequestVersion.incrementAndGet()
        val job = synchronized(endpointJobLock) {
            activeMemberLoginQrJob.also { activeMemberLoginQrJob = null }
        }
        job?.cancel()
        if (memberLoginQrRememberedEndpoint) {
            // begin/keepOffline are non-suspend; mark forget for the next suspend flush.
            pendingMemberLoginQrForget = true
            memberLoginQrRememberedEndpoint = false
        }
        if (resetState) {
            val current = mutableState.value
            val entry = when (current) {
                is FamilyWizardState.VerifyingMemberLoginQr -> current.snapshot.entry
                is FamilyWizardState.MemberLoginQrReady -> current.snapshot.entry
                is FamilyWizardState.MemberLoginQrVerificationFailed -> current.snapshot.entry
                is FamilyWizardState.ClaimingMemberLoginQr -> current.snapshot.entry
                else -> null
            }
            if (entry != null) {
                mutableState.value = FamilyWizardState.Editing(FamilyWizardSnapshot.empty(entry))
            }
        }
    }

    private suspend fun flushPendingMemberLoginQrForget() {
        if (!pendingMemberLoginQrForget) return
        pendingMemberLoginQrForget = false
        try {
            gateway.forgetEndpoint()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Best-effort cleanup of residual half-trust.
        }
    }

    /**
     * Verifies a scanned member-login QR endpoint (no trust write). Account and onboarding
     * both drive this path; UI only projects [state].
     */
    suspend fun verifyMemberLoginQr(
        entry: FamilyWizardEntry,
        payload: MemberLoginQrPayload,
    ) {
        if (!submission.tryLock()) return
        val qrJob = currentCoroutineContext()[Job]
        synchronized(endpointJobLock) {
            activeMemberLoginQrJob = qrJob
        }
        try {
            flushPendingMemberLoginQrForget()
            val requestVersion = memberLoginQrRequestVersion.incrementAndGet()
            val snapshot = memberLoginQrSnapshot(entry, payload)
            mutableState.value = FamilyWizardState.VerifyingMemberLoginQr(snapshot, payload)
            val result = gateway.verifyMemberLoginEndpoint(payload.endpoint)
            currentCoroutineContext().ensureActive()
            if (memberLoginQrRequestVersion.get() != requestVersion) return
            mutableState.value = mapMemberLoginQrVerifyResult(snapshot, payload, result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            synchronized(endpointJobLock) {
                if (activeMemberLoginQrJob === qrJob) activeMemberLoginQrJob = null
            }
            submission.unlock()
        }
    }

    /**
     * Cancels in-flight verify/claim for a member-login QR and returns to editing.
     * Does not leave a continuing claim job. If claim had remembered trust without a
     * completed session (Ready after failed claim, or mid-Claiming), forgets that
     * half-trusted endpoint so cancel never leaves residual LAN trust.
     */
    suspend fun cancelMemberLoginQr() {
        val shouldForgetRemembered =
            memberLoginQrRememberedEndpoint || pendingMemberLoginQrForget
        cancelMemberLoginQrWork(resetState = true)
        pendingMemberLoginQrForget = false
        if (shouldForgetRemembered) {
            try {
                gateway.forgetEndpoint()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Best-effort; state is already cleared of the claim job.
            }
        }
    }

    suspend fun claimMemberLoginQr(
        payload: MemberLoginQrPayload,
        deviceName: String,
    ) {
        val current = mutableState.value
        val snapshot = when (current) {
            is FamilyWizardState.MemberLoginQrReady -> {
                if (current.payload != payload) return
                current.snapshot
            }
            else -> return
        }
        if (!submission.tryLock()) return
        val qrJob = currentCoroutineContext()[Job]
        synchronized(endpointJobLock) {
            activeMemberLoginQrJob = qrJob
        }
        // Keep memberLoginQrRememberedEndpoint across retries until success or cancel:
        // a prior failed claim may still hold trust; clearing the flag here would lose
        // the cancel/begin cleanup signal while residual trust remains.
        try {
            flushPendingMemberLoginQrForget()
            val requestVersion = memberLoginQrRequestVersion.incrementAndGet()
            val normalizedDeviceName = runCatching {
                com.lezi.babylog.sync.session.requireDeviceName(deviceName)
            }.getOrElse { error ->
                mutableState.value = FamilyWizardState.MemberLoginQrReady(
                    snapshot = snapshot,
                    payload = payload,
                    feedback = error.message ?: "请填写设备称呼",
                )
                return
            }
            mutableState.value = FamilyWizardState.ClaimingMemberLoginQr(snapshot, payload)
            try {
                gateway.rememberEndpoint(payload.endpoint).getOrThrow()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (memberLoginQrRequestVersion.get() != requestVersion) return
                // Pure local trust write failure — fixed product copy, not familySyncError.
                mutableState.value = FamilyWizardState.MemberLoginQrReady(
                    snapshot = snapshot,
                    payload = payload,
                    feedback = "无法保存家庭服务器信任信息，请重试",
                )
                return
            }
            memberLoginQrRememberedEndpoint = true
            currentCoroutineContext().ensureActive()
            if (memberLoginQrRequestVersion.get() != requestVersion) {
                try {
                    gateway.forgetEndpoint()
                } catch (_: Throwable) {
                }
                memberLoginQrRememberedEndpoint = false
                return
            }
            val result = try {
                gateway.claimMemberLoginQr(payload, normalizedDeviceName).getOrThrow()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (memberLoginQrRequestVersion.get() != requestVersion) return
                // Keep remembered-without-session so cancel/dismiss can forget residual trust.
                mutableState.value = FamilyWizardState.MemberLoginQrReady(
                    snapshot = snapshot,
                    payload = payload,
                    feedback = memberLoginQrClaimError(error),
                )
                return
            }
            // Session owns the endpoint; cancel must not forget successful login trust.
            memberLoginQrRememberedEndpoint = false
            pendingMemberLoginQrForget = false
            if (memberLoginQrRequestVersion.get() != requestVersion) return
            publishCompleted(
                snapshot,
                FamilyWizardOutcome.MemberLoginQrClaimed(result.session, result.dataRecovery),
            )
        } catch (cancelled: CancellationException) {
            if (memberLoginQrRememberedEndpoint) {
                try {
                    gateway.forgetEndpoint()
                } catch (_: Throwable) {
                }
                memberLoginQrRememberedEndpoint = false
            }
            throw cancelled
        } finally {
            synchronized(endpointJobLock) {
                if (activeMemberLoginQrJob === qrJob) activeMemberLoginQrJob = null
            }
            submission.unlock()
        }
    }

    suspend fun submit(
        snapshot: FamilyWizardSnapshot,
        bootstrapSecret: String = "",
        ownerTakeover: Boolean = false,
    ) {
        when (val current = mutableState.value) {
            is FamilyWizardState.Completed -> return
            is FamilyWizardState.RetryableFailure -> if (current.committedOutcome != null) return
            else -> Unit
        }
        if (!submission.tryLock()) return
        try {
            val config = validateVerifiedEndpoint(snapshot) ?: return
            when (snapshot.mode) {
                FamilyWizardMode.Create -> submitCreate(snapshot, config, bootstrapSecret)
                FamilyWizardMode.Join -> when (snapshot.joinRole) {
                    FamilyWizardJoinRole.Owner -> submitOwnerLogin(
                        snapshot,
                        config,
                        bootstrapSecret,
                        ownerTakeover,
                    )
                    FamilyWizardJoinRole.Member -> submitMemberRequest(snapshot, config)
                    null -> mutableState.value = FamilyWizardState.RetryableFailure(
                        snapshot = snapshot.copy(step = FamilyWizardStep.Role),
                        message = "请选择家庭管理员或家庭成员",
                    )
                }
            }
        } finally {
            submission.unlock()
        }
    }

    suspend fun trustCertificate(
        entry: FamilyWizardEntry,
        candidate: CertificateTrustCandidate,
    ) {
        if (!submission.tryLock()) return
        val endpointJob = currentCoroutineContext()[Job]
        synchronized(endpointJobLock) {
            activeEndpointJob = endpointJob
        }
        try {
            val requestVersion = endpointRequestVersion.incrementAndGet()
            val draft = FamilyWizardSnapshot.empty(entry).copy(
                endpointDraft = candidate.endpointOrigin,
            )
            mutableState.value = FamilyWizardState.ProbingEndpoint(draft)
            val result = gateway.trustCertificate(candidate)
            currentCoroutineContext().ensureActive()
            if (endpointRequestVersion.get() != requestVersion) return
            when (result) {
                is SetupProbeResult.CertificateApprovalRequired -> {
                    mutableState.value = FamilyWizardState.CertificateApprovalRequired(
                        snapshot = draft,
                        candidate = result.candidate,
                    )
                }
                is SetupProbeResult.Ready -> {
                    val mode = when (result.familyState) {
                        SetupFamilyState.Empty -> FamilyWizardMode.Create
                        SetupFamilyState.Configured -> FamilyWizardMode.Join
                    }
                    mutableState.value = FamilyWizardState.EndpointReady(
                        snapshot = draft.copy(
                            mode = mode,
                            step = if (mode == FamilyWizardMode.Join) {
                                FamilyWizardStep.Role
                            } else {
                                FamilyWizardStep.Identity
                            },
                            endpointDraft = result.endpoint.origin,
                        ),
                        endpoint = result.endpoint,
                    )
                }
                is SetupProbeResult.Failed -> mutableState.value = FamilyWizardState.EndpointFailure(
                    snapshot = draft,
                    reason = result,
                    message = result.userMessage(),
                )
            }
        } finally {
            synchronized(endpointJobLock) {
                if (activeEndpointJob === endpointJob) activeEndpointJob = null
            }
            submission.unlock()
        }
    }

    private suspend fun submitCreate(
        snapshot: FamilyWizardSnapshot,
        config: FamilyEndpointConfig,
        bootstrapSecret: String,
    ) {
        val identity = snapshot.copy(step = FamilyWizardStep.Identity)
        memberDisplayNameValidationError(snapshot.displayName)?.let { message ->
            mutableState.value = FamilyWizardState.RetryableFailure(identity, message)
            return
        }
        familyNameValidationError(snapshot.familyName)?.let { message ->
            mutableState.value = FamilyWizardState.RetryableFailure(identity, message)
            return
        }
        if (snapshot.familyName.isBlank()) {
            mutableState.value = FamilyWizardState.RetryableFailure(identity, "请填写家庭名")
            return
        }
        val deviceNameError = runCatching {
            com.lezi.babylog.sync.session.requireDeviceName(snapshot.deviceName)
        }.exceptionOrNull()?.message
        if (deviceNameError != null) {
            mutableState.value = FamilyWizardState.RetryableFailure(identity, deviceNameError)
            return
        }
        if (bootstrapSecret.isBlank()) {
            mutableState.value = FamilyWizardState.RetryableFailure(identity, "请填写管理员根密码")
            return
        }
        mutableState.value = FamilyWizardState.Submitting(identity)
        try {
            gateway.saveEndpointConfig(config).getOrThrow()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.value = FamilyWizardState.RetryableFailure(
                snapshot = snapshot.copy(step = FamilyWizardStep.Endpoint),
                message = familySyncError(error, "保存家庭服务器失败，请重试"),
            )
            return
        }

        val result = try {
            gateway.createFamily(
                config = config,
                displayName = snapshot.displayName.trim(),
                deviceName = snapshot.deviceName.trim(),
                bootstrapSecret = bootstrapSecret,
                familyName = snapshot.familyName.trim(),
            ).getOrThrow()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.value = FamilyWizardState.RetryableFailure(
                snapshot = identity,
                message = familySyncError(error, "创建家庭失败，请重试"),
            )
            return
        }

        publishCompleted(
            identity,
            if (result.reclaimed) {
                FamilyWizardOutcome.Reclaimed(result.session, result.dataRecovery)
            } else {
                FamilyWizardOutcome.Created(result.session, result.dataRecovery)
            },
        )
    }

    private suspend fun submitMemberRequest(
        snapshot: FamilyWizardSnapshot,
        config: FamilyEndpointConfig,
    ) {
        val identity = snapshot.copy(
            step = FamilyWizardStep.Identity,
            joinRole = FamilyWizardJoinRole.Member,
        )
        memberDisplayNameValidationError(snapshot.displayName)?.let { message ->
            mutableState.value = FamilyWizardState.RetryableFailure(identity, message)
            return
        }
        val deviceNameError = runCatching {
            com.lezi.babylog.sync.session.requireDeviceName(snapshot.deviceName)
        }.exceptionOrNull()?.message
        if (deviceNameError != null) {
            mutableState.value = FamilyWizardState.RetryableFailure(identity, deviceNameError)
            return
        }
        mutableState.value = FamilyWizardState.Submitting(identity)
        try {
            gateway.saveEndpointConfig(config).getOrThrow()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.value = FamilyWizardState.RetryableFailure(
                snapshot = snapshot.copy(step = FamilyWizardStep.Endpoint),
                message = familySyncError(error, "保存家庭服务器失败，请重试"),
            )
            return
        }
        val request = try {
            gateway.requestMemberLogin(
                config = config,
                displayName = snapshot.displayName.trim(),
                deviceName = snapshot.deviceName.trim(),
            ).getOrThrow()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.value = FamilyWizardState.RetryableFailure(
                snapshot = identity,
                message = familySyncError(error, "申请加入失败，请稍后重试"),
            )
            return
        }
        mutableState.value = FamilyWizardState.WaitingForMemberApproval(identity, request)
    }

    suspend fun checkMemberApproval() {
        val current = mutableState.value as? FamilyWizardState.WaitingForMemberApproval ?: return
        if (!submission.tryLock()) return
        try {
            mutableState.value = FamilyWizardState.Submitting(current.snapshot)
            applyMemberLoginCheck(current.snapshot, gateway.checkMemberLogin().getOrThrow())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.value = FamilyWizardState.WaitingForMemberApproval(
                current.snapshot,
                current.request,
                familySyncError(error, "检查失败，请稍后重试"),
            )
        } finally {
            submission.unlock()
        }
    }

    /** Applies an automatic foreground check only while this UI still owns the waiting request. */
    @Synchronized
    fun observeMemberLoginCheck(result: MemberLoginCheckResult) {
        val current = mutableState.value as? FamilyWizardState.WaitingForMemberApproval ?: return
        applyMemberLoginCheck(current.snapshot, result)
    }

    suspend fun cancelMemberApproval() {
        val current = mutableState.value as? FamilyWizardState.WaitingForMemberApproval ?: return
        if (!submission.tryLock()) return
        try {
            gateway.cancelMemberLogin().getOrThrow()
            mutableState.value = FamilyWizardState.Editing(current.snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            mutableState.value = current
        } finally {
            submission.unlock()
        }
    }

    fun restorePendingMemberApproval(
        snapshot: FamilyWizardSnapshot,
        request: PendingMemberLogin,
    ) {
        val current = mutableState.value
        if (current !is FamilyWizardState.Editing &&
            current !is FamilyWizardState.WaitingForMemberApproval
        ) {
            return
        }
        mutableState.value = FamilyWizardState.WaitingForMemberApproval(
            snapshot.copy(
                mode = FamilyWizardMode.Join,
                step = FamilyWizardStep.Identity,
                joinRole = FamilyWizardJoinRole.Member,
                displayName = request.displayName,
                deviceName = request.deviceName,
            ),
            request,
        )
    }

    private fun applyMemberLoginCheck(
        snapshot: FamilyWizardSnapshot,
        result: MemberLoginCheckResult,
    ) {
        when (result) {
            is MemberLoginCheckResult.Waiting -> {
                mutableState.value = FamilyWizardState.WaitingForMemberApproval(
                    snapshot,
                    result.request,
                )
            }
            is MemberLoginCheckResult.Joined -> publishCompleted(
                snapshot,
                FamilyWizardOutcome.MemberApproved(result.session, result.dataRecovery),
            )
            is MemberLoginCheckResult.Terminal -> {
                mutableState.value = FamilyWizardState.RetryableFailure(
                    snapshot,
                    when (result.status) {
                        MemberLoginStatus.Rejected -> "管理员已拒绝这条加入申请"
                        MemberLoginStatus.Expired -> "加入申请已过期，请重新申请"
                        MemberLoginStatus.Cancelled -> "加入申请已取消"
                        MemberLoginStatus.Claimed -> "加入凭据已使用，请重新申请"
                        else -> "加入申请状态已变化，请重新申请"
                    },
                )
            }
        }
    }

    private suspend fun submitOwnerLogin(
        snapshot: FamilyWizardSnapshot,
        config: FamilyEndpointConfig,
        rootPassword: String,
        takeover: Boolean,
    ) {
        val identity = snapshot.copy(step = FamilyWizardStep.Identity)
        val deviceNameError = runCatching {
            com.lezi.babylog.sync.session.requireDeviceName(snapshot.deviceName)
        }.exceptionOrNull()?.message
        if (deviceNameError != null) {
            mutableState.value = FamilyWizardState.RetryableFailure(identity, deviceNameError)
            return
        }
        if (rootPassword.isBlank()) {
            mutableState.value = FamilyWizardState.RetryableFailure(identity, "请填写管理员根密码")
            return
        }
        mutableState.value = FamilyWizardState.Submitting(identity)
        try {
            gateway.saveEndpointConfig(config).getOrThrow()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.value = FamilyWizardState.RetryableFailure(
                snapshot = snapshot.copy(step = FamilyWizardStep.Endpoint),
                message = familySyncError(error, "保存家庭服务器失败，请重试"),
            )
            return
        }
        val result = try {
            gateway.ownerLogin(
                config = config,
                deviceName = snapshot.deviceName.trim(),
                rootPassword = rootPassword,
                takeover = takeover,
            ).getOrThrow()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.value = FamilyWizardState.RetryableFailure(
                snapshot = identity,
                message = familySyncError(error, "管理员登录失败，请重试"),
            )
            return
        }
        publishCompleted(
            identity,
            FamilyWizardOutcome.OwnerLoggedIn(result.session, result.dataRecovery),
        )
    }

    /** Ticket07 recovery gate remains part of the create result; it never re-runs create. */
    suspend fun retryReclaimedDataRecovery() {
        val current = mutableState.value
        val committed = when (current) {
            is FamilyWizardState.Completed -> current.outcome as? FamilyWizardOutcome.CreateSession
            is FamilyWizardState.RetryableFailure -> current.committedOutcome
            else -> null
        } ?: return
        if (committed.dataRecovery != InitialFamilyDataRecovery.RetryRequired) return
        if (!submission.tryLock()) return
        try {
            val snapshot = current.snapshot.copy(step = FamilyWizardStep.Identity)
            mutableState.value = FamilyWizardState.Submitting(snapshot)
            try {
                gateway.retryReclaimedDataRecovery().getOrThrow()
                publishCompleted(
                    snapshot,
                    when (committed) {
                        is FamilyWizardOutcome.Created -> committed.copy(
                            dataRecovery = InitialFamilyDataRecovery.Complete,
                        )
                        is FamilyWizardOutcome.Reclaimed -> committed.copy(
                            dataRecovery = InitialFamilyDataRecovery.Complete,
                        )
                        is FamilyWizardOutcome.OwnerLoggedIn -> committed.copy(
                            dataRecovery = InitialFamilyDataRecovery.Complete,
                        )
                        is FamilyWizardOutcome.MemberApproved -> committed.copy(
                            dataRecovery = InitialFamilyDataRecovery.Complete,
                        )
                        is FamilyWizardOutcome.MemberLoginQrClaimed -> committed.copy(
                            dataRecovery = InitialFamilyDataRecovery.Complete,
                        )
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.value = FamilyWizardState.RetryableFailure(
                    snapshot = snapshot,
                    message = "首次同步失败，可继续离线使用并稍后重试",
                    committedOutcome = committed,
                )
            }
        } finally {
            submission.unlock()
        }
    }

    /** Returns a completed outcome once, even if Compose re-collects the same state. */
    @Synchronized
    fun consumeCompletion(): FamilyWizardOutcome? {
        val completed = mutableState.value as? FamilyWizardState.Completed ?: return null
        if (consumedCompletionVersion == completionVersion) return null
        consumedCompletionVersion = completionVersion
        return completed.outcome
    }

    private fun validateEndpoint(snapshot: FamilyWizardSnapshot): FamilyEndpointConfig? {
        val endpoint = snapshot.copy(step = FamilyWizardStep.Endpoint)
        familyWizardEndpointValidationError(snapshot)?.let { message ->
            mutableState.value = FamilyWizardState.RetryableFailure(endpoint, message)
            return null
        }
        return FamilyEndpointConfig.fromUserInput(
            rawHostOrUrl = snapshot.host,
            explicitPort = snapshot.portText.toIntOrNull(),
            fallbackScheme = snapshot.scheme,
        )
    }

    private suspend fun validateVerifiedEndpoint(
        snapshot: FamilyWizardSnapshot,
    ): FamilyEndpointConfig? {
        val draftConfig = validateEndpoint(snapshot) ?: return null
        val verified = try {
            gateway.currentVerifiedEndpoint()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        val matches = verified != null && runCatching {
            TrustedEndpointProfile.systemPki(draftConfig.baseUrl).origin == verified.origin
        }.getOrDefault(false)
        if (!matches) {
            mutableState.value = FamilyWizardState.RetryableFailure(
                snapshot = snapshot.copy(step = FamilyWizardStep.Endpoint),
                message = "家庭服务器地址已变化，请重新确认家庭服务器",
            )
            return null
        }
        return FamilyEndpointConfig.fromBaseUrl(verified.origin).withNormalized()
    }

    @Synchronized
    private fun publishCompleted(
        snapshot: FamilyWizardSnapshot,
        outcome: FamilyWizardOutcome,
    ) {
        completionVersion += 1
        mutableState.value = FamilyWizardState.Completed(snapshot, outcome)
    }
}

private fun SetupProbeResult.Failed.userMessage(): String = when (this) {
    SetupProbeResult.Failed.InvalidAddress -> "请输入完整的 HTTPS 地址"
    SetupProbeResult.Failed.Unreachable -> "无法连接家庭服务器"
    SetupProbeResult.Failed.NotLezi -> "这里不是兼容的乐记家庭后台"
    SetupProbeResult.Failed.Incompatible -> "家庭服务器需要更新"
    SetupProbeResult.Failed.Maintenance -> "家庭服务器正在维护"
    SetupProbeResult.Failed.CertificateChanged -> "服务器安全信息已变化"
}

/** One Network-step validation policy used by both UI entries and by submit. */
fun familyWizardEndpointValidationError(snapshot: FamilyWizardSnapshot): String? {
    if (snapshot.host.isBlank()) return "请填写服务器主机"
    return try {
        FamilyEndpointConfig.fromUserInput(
            rawHostOrUrl = snapshot.host,
            explicitPort = snapshot.portText.toIntOrNull(),
            fallbackScheme = snapshot.scheme,
        )
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        error.message?.takeIf(String::isNotBlank) ?: "家庭服务器地址无效"
    }
}

/** Current trusted protocol requires a shared family name on create and rename. */
fun familyNameValidationError(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "请输入家庭名"
    if (trimmed.any { it.isISOControl() }) return "家庭名不能包含控制字符"
    if (trimmed.codePointCount(0, trimmed.length) > 64) return "家庭名最多 64 个字符"
    return null
}

private fun memberLoginQrSnapshot(
    entry: FamilyWizardEntry,
    payload: MemberLoginQrPayload,
): FamilyWizardSnapshot {
    val config = FamilyEndpointConfig.fromBaseUrl(payload.endpoint.origin).withNormalized()
    return FamilyWizardSnapshot(
        entry = entry,
        mode = FamilyWizardMode.Join,
        step = FamilyWizardStep.Identity,
        host = config.host,
        portText = config.port.toString(),
        scheme = config.scheme,
        displayName = payload.memberDisplayName,
        familyName = payload.familyName.orEmpty(),
        joinRole = FamilyWizardJoinRole.Member,
        endpointDraft = payload.endpoint.origin,
    )
}

private fun mapMemberLoginQrVerifyResult(
    snapshot: FamilyWizardSnapshot,
    payload: MemberLoginQrPayload,
    result: SetupProbeResult,
): FamilyWizardState = when (result) {
    is SetupProbeResult.Ready -> when {
        result.endpoint != payload.endpoint ->
            FamilyWizardState.MemberLoginQrVerificationFailed(
                snapshot = snapshot,
                payload = payload,
                message = "二维码中的家庭服务器与当前探测结果不一致，请重新扫码",
            )
        result.familyState != SetupFamilyState.Configured ->
            FamilyWizardState.MemberLoginQrVerificationFailed(
                snapshot = snapshot,
                payload = payload,
                message = "这个二维码对应的服务器尚未配置家庭",
            )
        else -> FamilyWizardState.MemberLoginQrReady(snapshot, payload)
    }
    SetupProbeResult.Failed.CertificateChanged ->
        FamilyWizardState.MemberLoginQrVerificationFailed(
            snapshot = snapshot,
            payload = payload,
            message = "家庭服务器安全信息不一致，登录已停止",
        )
    is SetupProbeResult.CertificateApprovalRequired,
    is SetupProbeResult.Failed,
    -> FamilyWizardState.MemberLoginQrVerificationFailed(
        snapshot = snapshot,
        payload = payload,
        message = "暂时无法确认二维码中的家庭服务器，请稍后重试",
    )
}

private fun memberLoginQrClaimError(error: Throwable): String = when (error) {
    is MemberLoginQrUnavailableException,
    is MemberLoginQrTrustChangedException,
    -> error.message?.takeIf(String::isNotBlank)
        ?: familySyncError(error, "登录失败，请稍后重试")
    else -> familySyncError(error, "登录失败，请稍后重试")
}

/**
 * Display-only chrome for member-login QR dialogs. Never carries a grant or trust mode —
 * hosts must not reconstruct [MemberLoginQrPayload] solely for recovery UI.
 */
data class MemberLoginQrDisplayInfo(
    val familyName: String?,
    val memberDisplayName: String,
)

/**
 * Shared Account/Onboarding projection of [FamilyWizardState] → member-login QR dialog flags.
 * Single enablement policy so hosts cannot drift on verify-retry / claim / recovery labels.
 */
data class MemberLoginQrDialogModel(
    val display: MemberLoginQrDisplayInfo,
    /** Live payload for verify/claim/manual-join; null only for post-claim recovery chrome. */
    val payload: MemberLoginQrPayload?,
    val feedback: String?,
    val submitting: Boolean,
    val verificationInProgress: Boolean,
    val verificationRetryRequired: Boolean,
    val recoveryRetryRequired: Boolean,
    val deviceNameEditable: Boolean,
    val showConfirm: Boolean,
    val confirmLabel: String,
    val title: String,
) {
    val confirmEnabled: Boolean get() = showConfirm && !submitting
}

/** Maps wizard state to the shared QR confirm surface. Null when the dialog should not show. */
fun projectMemberLoginQrDialog(state: FamilyWizardState): MemberLoginQrDialogModel? =
    when (state) {
        is FamilyWizardState.VerifyingMemberLoginQr -> MemberLoginQrDialogModel(
            display = memberLoginQrDisplayInfo(state.payload),
            payload = state.payload,
            feedback = "正在确认家庭服务器…",
            submitting = false,
            verificationInProgress = true,
            verificationRetryRequired = false,
            recoveryRetryRequired = false,
            deviceNameEditable = false,
            showConfirm = false,
            confirmLabel = "在这台设备登录",
            title = "正在确认家庭服务器…",
        )
        is FamilyWizardState.MemberLoginQrVerificationFailed -> MemberLoginQrDialogModel(
            display = memberLoginQrDisplayInfo(state.payload),
            payload = state.payload,
            feedback = state.message,
            submitting = false,
            verificationInProgress = false,
            verificationRetryRequired = true,
            recoveryRetryRequired = false,
            deviceNameEditable = true,
            showConfirm = true,
            confirmLabel = "重新确认",
            title = "登录家庭",
        )
        is FamilyWizardState.MemberLoginQrReady -> MemberLoginQrDialogModel(
            display = memberLoginQrDisplayInfo(state.payload),
            payload = state.payload,
            feedback = state.feedback,
            submitting = false,
            verificationInProgress = false,
            verificationRetryRequired = false,
            recoveryRetryRequired = false,
            deviceNameEditable = true,
            showConfirm = true,
            confirmLabel = "在这台设备登录",
            title = "登录家庭",
        )
        is FamilyWizardState.ClaimingMemberLoginQr -> MemberLoginQrDialogModel(
            display = memberLoginQrDisplayInfo(state.payload),
            payload = state.payload,
            feedback = null,
            submitting = true,
            verificationInProgress = false,
            verificationRetryRequired = false,
            recoveryRetryRequired = false,
            deviceNameEditable = false,
            showConfirm = true,
            confirmLabel = "同步中…",
            title = "登录家庭",
        )
        is FamilyWizardState.Completed -> {
            val claimed = state.outcome as? FamilyWizardOutcome.MemberLoginQrClaimed
            if (claimed?.dataRecovery == InitialFamilyDataRecovery.RetryRequired) {
                MemberLoginQrDialogModel(
                    display = memberLoginQrDisplayInfo(state.snapshot),
                    payload = null,
                    feedback = "已登录；首次同步失败，请重试",
                    submitting = false,
                    verificationInProgress = false,
                    verificationRetryRequired = false,
                    recoveryRetryRequired = true,
                    deviceNameEditable = false,
                    showConfirm = true,
                    confirmLabel = "重试首次同步",
                    title = "登录家庭",
                )
            } else {
                null
            }
        }
        is FamilyWizardState.RetryableFailure -> {
            val claimed = state.committedOutcome as? FamilyWizardOutcome.MemberLoginQrClaimed
            if (claimed != null) {
                MemberLoginQrDialogModel(
                    display = memberLoginQrDisplayInfo(state.snapshot),
                    payload = null,
                    feedback = state.message,
                    submitting = false,
                    verificationInProgress = false,
                    verificationRetryRequired = false,
                    recoveryRetryRequired = true,
                    deviceNameEditable = false,
                    showConfirm = true,
                    confirmLabel = "重试首次同步",
                    title = "登录家庭",
                )
            } else {
                null
            }
        }
        else -> null
    }

fun memberLoginQrDisplayInfo(payload: MemberLoginQrPayload): MemberLoginQrDisplayInfo =
    MemberLoginQrDisplayInfo(
        familyName = payload.familyName,
        memberDisplayName = payload.memberDisplayName,
    )

fun memberLoginQrDisplayInfo(snapshot: FamilyWizardSnapshot): MemberLoginQrDisplayInfo =
    MemberLoginQrDisplayInfo(
        familyName = snapshot.familyName.ifBlank { null },
        memberDisplayName = snapshot.displayName.ifBlank { "家人" },
    )
