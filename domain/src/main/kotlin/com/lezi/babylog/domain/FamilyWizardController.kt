package com.lezi.babylog.domain

import com.lezi.babylog.sync.CreateFamilyResult
import com.lezi.babylog.sync.HomeLanServerConfig
import com.lezi.babylog.sync.InitialFamilyDataRecovery
import com.lezi.babylog.sync.JoinFamilyDraft
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncSession
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.familySyncError
import com.lezi.babylog.sync.memberDisplayNameValidationError
import java.io.Serializable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/** The two UI entries that project the same family wizard. Entry never changes a request. */
enum class FamilyWizardEntry { Onboarding, Account }

/** The only authoritative family-session actions. Reclaim is a create result, not a third mode. */
enum class FamilyWizardMode { Create, Join }

enum class FamilyWizardStep { Network, Identity }

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
    val ssid1: String = "",
    val ssid2: String = "",
    val displayName: String = "",
    val familyName: String = "",
) : Serializable {
    fun toJoinDraft(): JoinFamilyDraft = JoinFamilyDraft(
        invitation = invitation,
        host = host,
        portText = portText,
        scheme = scheme,
        ssid1 = ssid1,
        ssid2 = ssid2,
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
        ): FamilyWizardSnapshot = FamilyWizardSnapshot(
            entry = entry,
            mode = mode,
            step = step,
            invitation = draft.invitation,
            host = draft.host,
            portText = draft.portText,
            scheme = draft.scheme,
            ssid1 = draft.ssid1,
            ssid2 = draft.ssid2,
            displayName = displayName,
            familyName = familyName,
        )
    }
}

sealed interface FamilyWizardOutcome {
    val session: SyncSession

    data class Created(override val session: SyncSession) : FamilyWizardOutcome

    data class Reclaimed(
        override val session: SyncSession,
        val dataRecovery: InitialFamilyDataRecovery,
    ) : FamilyWizardOutcome

    data class Joined(override val session: SyncSession) : FamilyWizardOutcome
}

sealed interface FamilyWizardState {
    val snapshot: FamilyWizardSnapshot

    data class Editing(override val snapshot: FamilyWizardSnapshot) : FamilyWizardState

    data class Submitting(override val snapshot: FamilyWizardSnapshot) : FamilyWizardState

    data class RetryableFailure(
        override val snapshot: FamilyWizardSnapshot,
        val message: String,
        /** Non-null only after a reclaimed owner session is already durable. */
        val committedOutcome: FamilyWizardOutcome.Reclaimed? = null,
    ) : FamilyWizardState

    data class Completed(
        override val snapshot: FamilyWizardSnapshot,
        val outcome: FamilyWizardOutcome,
    ) : FamilyWizardState
}

/** Side-effect seam kept narrow so the state machine can be exercised without Android or Hilt. */
interface FamilyWizardGateway {
    suspend fun saveHomeLanConfig(config: HomeLanServerConfig): Result<Unit>

    suspend fun createFamily(
        config: HomeLanServerConfig,
        displayName: String,
        bootstrapSecret: String,
        familyName: String?,
    ): Result<CreateFamilyResult>

    suspend fun joinFamily(request: JoinFamilyRequest): JoinFamilyResult

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

    override suspend fun saveHomeLanConfig(config: HomeLanServerConfig): Result<Unit> =
        sync.saveHomeLanConfig(config)

    override suspend fun createFamily(
        config: HomeLanServerConfig,
        displayName: String,
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

    /** Starts a fresh UI session after a prior completion (for example after later leaving family). */
    @Synchronized
    fun begin(snapshot: FamilyWizardSnapshot) {
        if (mutableState.value is FamilyWizardState.Submitting) return
        consumedCompletionVersion = completionVersion
        mutableState.value = FamilyWizardState.Editing(snapshot)
    }

    fun restore(snapshot: FamilyWizardSnapshot) {
        val current = mutableState.value
        if (current !is FamilyWizardState.Submitting && current !is FamilyWizardState.Completed) {
            mutableState.value = FamilyWizardState.Editing(snapshot)
        }
    }

    suspend fun submit(
        snapshot: FamilyWizardSnapshot,
        bootstrapSecret: String = "",
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
                FamilyWizardMode.Join -> submitJoin(snapshot)
            }
        } finally {
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
                bootstrapSecret = bootstrapSecret,
                familyName = snapshot.familyName.trim().ifEmpty { null },
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
                FamilyWizardOutcome.Created(result.session)
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

    /** Ticket07 recovery gate remains part of the create result; it never re-runs create. */
    suspend fun retryReclaimedDataRecovery() {
        val current = mutableState.value
        val committed = when (current) {
            is FamilyWizardState.Completed -> current.outcome as? FamilyWizardOutcome.Reclaimed
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
                    committed.copy(dataRecovery = InitialFamilyDataRecovery.Complete),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.value = FamilyWizardState.RetryableFailure(
                    snapshot = snapshot,
                    message = "历史数据恢复失败，请保持连接家庭 Wi‑Fi 后重试",
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
            allowedSsids = listOf(snapshot.ssid1, snapshot.ssid2),
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

/** One Network-step validation policy used by both UI entries and by submit. */
fun familyWizardNetworkValidationError(snapshot: FamilyWizardSnapshot): String? {
    if (snapshot.host.isBlank()) return "请填写服务器主机"
    if (listOf(snapshot.ssid1, snapshot.ssid2).all(String::isBlank)) {
        return "请至少填写一个家庭 Wi‑Fi 名称"
    }
    return try {
        HomeLanServerConfig.fromUserInput(
            rawHostOrUrl = snapshot.host,
            explicitPort = snapshot.portText.toIntOrNull(),
            allowedSsids = listOf(snapshot.ssid1, snapshot.ssid2),
            fallbackScheme = snapshot.scheme,
        )
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        error.message?.takeIf(String::isNotBlank) ?: "家庭网络配置无效"
    }
}

/** Optional shared family name validation used by both entry projections. */
fun familyNameValidationError(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.any { it.isISOControl() }) return "家庭名不能包含控制字符"
    if (trimmed.codePointCount(0, trimmed.length) > 64) return "家庭名最多 64 个字符"
    return null
}
