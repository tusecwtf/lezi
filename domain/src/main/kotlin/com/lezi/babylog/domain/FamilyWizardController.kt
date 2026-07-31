package com.lezi.babylog.domain

import com.lezi.babylog.sync.CreateFamilyResult
import com.lezi.babylog.sync.CertificateTrustCandidate
import com.lezi.babylog.sync.HomeLanServerConfig
import com.lezi.babylog.sync.InitialFamilyDataRecovery
import com.lezi.babylog.sync.OwnerLoginResult
import com.lezi.babylog.sync.MemberLoginCheckResult
import com.lezi.babylog.sync.MemberLoginStatus
import com.lezi.babylog.sync.PendingMemberLogin
import com.lezi.babylog.sync.JoinFamilyDraft
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncSession
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.SetupFamilyState
import com.lezi.babylog.sync.SetupProbeResult
import com.lezi.babylog.sync.TrustedEndpointProfile
import com.lezi.babylog.sync.familySyncError
import com.lezi.babylog.sync.memberDisplayNameValidationError
import java.io.Serializable
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/** The two UI entries that project the same family wizard. Entry never changes a request. */
enum class FamilyWizardEntry { Onboarding, Account }

/** The only authoritative family-session actions. Reclaim is a create result, not a third mode. */
enum class FamilyWizardMode { Create, Join }

enum class FamilyWizardStep { Network, Role, Identity }

enum class FamilyWizardJoinRole { Owner, Member }

/**
 * Process-retainable, non-sensitive wizard state. The bootstrap secret is intentionally absent and
 * must only be supplied to [FamilyWizardController.submit] for the active create request.
 */
data class FamilyWizardSnapshot(
    val entry: FamilyWizardEntry,
    val mode: FamilyWizardMode,
    val step: FamilyWizardStep,
    val invitation: String = "",
    val host: String = "",
    val portText: String = com.lezi.babylog.sync.DEFAULT_SERVER_PORT.toString(),
    val scheme: String = com.lezi.babylog.sync.DEFAULT_SERVER_SCHEME,
    val displayName: String = "",
    val familyName: String = "",
    val deviceName: String = "",
    val endpointDraft: String = "",
    val joinRole: FamilyWizardJoinRole? = null,
) : Serializable {
    fun toJoinDraft(): JoinFamilyDraft = JoinFamilyDraft(
        invitation = invitation,
        host = host,
        portText = portText,
        scheme = scheme,
    )

    companion object {
        fun empty(entry: FamilyWizardEntry): FamilyWizardSnapshot = FamilyWizardSnapshot(
            entry = entry,
            mode = FamilyWizardMode.Create,
            step = FamilyWizardStep.Network,
        )

        fun fromDraft(
            entry: FamilyWizardEntry,
            mode: FamilyWizardMode,
            step: FamilyWizardStep,
            draft: JoinFamilyDraft,
            displayName: String = "",
            familyName: String = "",
            deviceName: String = "",
        ): FamilyWizardSnapshot = FamilyWizardSnapshot(
            entry = entry,
            mode = mode,
            step = step,
            invitation = draft.invitation,
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

    data class Joined(override val session: SyncSession) : FamilyWizardOutcome
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
    ) : FamilyWizardState

    data class Completed(
        override val snapshot: FamilyWizardSnapshot,
        val outcome: FamilyWizardOutcome,
    ) : FamilyWizardState
}

/** Side-effect seam kept narrow so the state machine can be exercised without Android or Hilt. */
interface FamilyWizardGateway {
    suspend fun probeEndpoint(endpointDraft: String): SetupProbeResult

    suspend fun trustCertificate(candidate: CertificateTrustCandidate): SetupProbeResult

    suspend fun rememberEndpoint(endpoint: TrustedEndpointProfile): Result<Unit>

    suspend fun forgetEndpoint(): Result<Unit>

    suspend fun saveHomeLanConfig(config: HomeLanServerConfig): Result<Unit>

    suspend fun createFamily(
        config: HomeLanServerConfig,
        displayName: String,
        deviceName: String = "Android 设备",
        bootstrapSecret: String,
        familyName: String?,
    ): Result<CreateFamilyResult>

    suspend fun joinFamily(request: JoinFamilyRequest): JoinFamilyResult

    suspend fun ownerLogin(
        config: HomeLanServerConfig,
        deviceName: String,
        rootPassword: String,
        takeover: Boolean,
    ): Result<OwnerLoginResult>

    suspend fun requestMemberLogin(
        config: HomeLanServerConfig,
        displayName: String,
        deviceName: String,
    ): Result<PendingMemberLogin> = Result.failure(IllegalStateException("成员申请暂不可用"))

    suspend fun checkMemberLogin(): Result<MemberLoginCheckResult> =
        Result.failure(IllegalStateException("成员申请暂不可用"))

    suspend fun cancelMemberLogin(): Result<Unit> =
        Result.failure(IllegalStateException("成员申请暂不可用"))

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
    private val joinFamily: JoinFamilyUseCase,
    private val localStore: FamilyWizardLocalStore,
) : FamilyWizardGateway {
    constructor(
        sync: SyncPort,
        joinFamily: JoinFamilyUseCase,
        careLog: CareLog,
    ) : this(sync, joinFamily, CareLogFamilyWizardLocalStore(careLog))

    internal constructor(
        localStore: FamilyWizardLocalStore,
        sync: SyncPort,
        joinFamily: JoinFamilyUseCase,
    ) : this(sync, joinFamily, localStore)

    override suspend fun probeEndpoint(endpointDraft: String): SetupProbeResult =
        sync.probeEndpoint(endpointDraft)

    override suspend fun trustCertificate(
        candidate: CertificateTrustCandidate,
    ): SetupProbeResult = sync.trustCertificate(candidate)

    override suspend fun rememberEndpoint(endpoint: TrustedEndpointProfile): Result<Unit> =
        sync.rememberEndpoint(endpoint)

    override suspend fun forgetEndpoint(): Result<Unit> = sync.forgetEndpoint()

    override suspend fun saveHomeLanConfig(config: HomeLanServerConfig): Result<Unit> =
        sync.saveHomeLanConfig(config)

    override suspend fun createFamily(
        config: HomeLanServerConfig,
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

    override suspend fun joinFamily(request: JoinFamilyRequest): JoinFamilyResult =
        joinFamily.execute(request)

    override suspend fun ownerLogin(
        config: HomeLanServerConfig,
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
        config: HomeLanServerConfig,
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

    override suspend fun retryReclaimedDataRecovery(): Result<Unit> =
        sync.sync(SyncTrigger.PullToRefresh)
}

/**
 * Shared create/join state machine. It retains only [FamilyWizardSnapshot], serializes submission,
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
    private val endpointJobLock = Any()
    private var activeEndpointJob: Job? = null

    /** Starts a fresh UI session after a prior completion (for example after later leaving family). */
    @Synchronized
    fun begin(snapshot: FamilyWizardSnapshot) {
        if (mutableState.value is FamilyWizardState.Submitting) return
        cancelEndpointConnection()
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
        cancelEndpointConnection()
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
            val config = validateNetwork(snapshot) ?: return
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
                    null -> if (snapshot.invitation.isBlank()) {
                        submitMemberRequest(snapshot, config)
                    } else {
                        submitJoin(snapshot)
                    }
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
        config: HomeLanServerConfig,
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
            com.lezi.babylog.sync.requireDeviceName(snapshot.deviceName)
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
            gateway.saveHomeLanConfig(config).getOrThrow()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.value = FamilyWizardState.RetryableFailure(
                snapshot = snapshot.copy(step = FamilyWizardStep.Network),
                message = familySyncError(error, "保存家庭网络失败，请重试"),
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

    private suspend fun submitJoin(snapshot: FamilyWizardSnapshot) {
        val identity = snapshot.copy(step = FamilyWizardStep.Identity)
        val request = try {
            JoinFamilyRequest(snapshot.toJoinDraft(), snapshot.displayName).also {
                // Build the command here so validation and error copy are identical at both entries.
                it.draft.toCommand(it.displayName)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.value = FamilyWizardState.RetryableFailure(
                snapshot = identity,
                message = error.message?.takeIf(String::isNotBlank) ?: "加入家庭信息无效",
            )
            return
        }
        mutableState.value = FamilyWizardState.Submitting(identity)
        val result = try {
            gateway.joinFamily(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.value = FamilyWizardState.RetryableFailure(
                snapshot = identity,
                message = familySyncError(error, "加入家庭失败，请稍后重试"),
            )
            return
        }
        when (result) {
            is JoinFamilyResult.Joined -> publishCompleted(
                identity,
                FamilyWizardOutcome.Joined(result.session),
            )
            is JoinFamilyResult.Failed -> mutableState.value = FamilyWizardState.RetryableFailure(
                snapshot = identity,
                message = result.message,
            )
        }
    }

    private suspend fun submitMemberRequest(
        snapshot: FamilyWizardSnapshot,
        config: HomeLanServerConfig,
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
            com.lezi.babylog.sync.requireDeviceName(snapshot.deviceName)
        }.exceptionOrNull()?.message
        if (deviceNameError != null) {
            mutableState.value = FamilyWizardState.RetryableFailure(identity, deviceNameError)
            return
        }
        mutableState.value = FamilyWizardState.Submitting(identity)
        try {
            gateway.saveHomeLanConfig(config).getOrThrow()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.value = FamilyWizardState.RetryableFailure(
                snapshot = snapshot.copy(step = FamilyWizardStep.Network),
                message = familySyncError(error, "保存家庭网络失败，请重试"),
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
        if (mutableState.value is FamilyWizardState.Submitting) return
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
        config: HomeLanServerConfig,
        rootPassword: String,
        takeover: Boolean,
    ) {
        val identity = snapshot.copy(step = FamilyWizardStep.Identity)
        val deviceNameError = runCatching {
            com.lezi.babylog.sync.requireDeviceName(snapshot.deviceName)
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
            gateway.saveHomeLanConfig(config).getOrThrow()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.value = FamilyWizardState.RetryableFailure(
                snapshot = snapshot.copy(step = FamilyWizardStep.Network),
                message = familySyncError(error, "保存家庭网络失败，请重试"),
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

    private fun validateNetwork(snapshot: FamilyWizardSnapshot): HomeLanServerConfig? {
        val network = snapshot.copy(step = FamilyWizardStep.Network)
        familyWizardNetworkValidationError(snapshot)?.let { message ->
            mutableState.value = FamilyWizardState.RetryableFailure(network, message)
            return null
        }
        return HomeLanServerConfig.fromUserInput(
            rawHostOrUrl = snapshot.host,
            explicitPort = snapshot.portText.toIntOrNull(),
            fallbackScheme = snapshot.scheme,
        )
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
fun familyWizardNetworkValidationError(snapshot: FamilyWizardSnapshot): String? {
    if (snapshot.host.isBlank()) return "请填写服务器主机"
    return try {
        HomeLanServerConfig.fromUserInput(
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
