package com.lezi.babylog.feature.onboarding

import com.lezi.babylog.sync.CreateFamilyResult
import com.lezi.babylog.sync.HomeLanServerConfig
import com.lezi.babylog.sync.InitialFamilyDataRecovery
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.familySyncError
import com.lezi.babylog.sync.memberDisplayNameValidationError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/** Non-sensitive retained input for first-run owner create/reclaim. */
data class OnboardingOwnerEntryInput(
    val homeLanConfig: HomeLanServerConfig,
    val displayName: String,
    val familyName: String,
)

/** User-visible states of the first-run owner create/reclaim action. */
sealed interface OnboardingOwnerEntryState {
    data object Ready : OnboardingOwnerEntryState
    data class Submitting(val input: OnboardingOwnerEntryInput) : OnboardingOwnerEntryState
    data class Created(val input: OnboardingOwnerEntryInput) : OnboardingOwnerEntryState
    data class Reclaimed(
        val dataRecovery: InitialFamilyDataRecovery,
    ) : OnboardingOwnerEntryState
    data object Recovering : OnboardingOwnerEntryState
    data class RecoveryRetryableFailure(val message: String) : OnboardingOwnerEntryState
    data class RetryableFailure(
        val input: OnboardingOwnerEntryInput,
        val message: String,
    ) : OnboardingOwnerEntryState
}

/** Existing family-session seam used by onboarding; it does not create local Baby rows. */
interface OnboardingOwnerEntryGateway {
    suspend fun saveHomeLanConfig(config: HomeLanServerConfig): Result<Unit>

    suspend fun createFamily(
        displayName: String,
        bootstrapSecret: String,
        familyName: String?,
    ): Result<CreateFamilyResult>

    suspend fun retryDataRecovery(): Result<Unit>
}

class SyncPortOnboardingOwnerEntryGateway(
    private val sync: SyncPort,
) : OnboardingOwnerEntryGateway {
    override suspend fun saveHomeLanConfig(config: HomeLanServerConfig): Result<Unit> =
        sync.saveHomeLanConfig(config)

    override suspend fun createFamily(
        displayName: String,
        bootstrapSecret: String,
        familyName: String?,
    ): Result<CreateFamilyResult> = sync.createFamily(
        displayName = displayName,
        bootstrapSecret = bootstrapSecret,
        familyName = familyName,
    )

    override suspend fun retryDataRecovery(): Result<Unit> =
        sync.sync(SyncTrigger.PullToRefresh)
}

/**
 * Owns one first-run create/reclaim submission. The bootstrap secret is passed straight through and
 * is deliberately absent from retained state.
 */
class OnboardingOwnerEntryController(
    private val gateway: OnboardingOwnerEntryGateway,
) {
    private val submission = Mutex()
    private val mutableState = MutableStateFlow<OnboardingOwnerEntryState>(
        OnboardingOwnerEntryState.Ready,
    )
    val state: StateFlow<OnboardingOwnerEntryState> = mutableState.asStateFlow()

    suspend fun submit(
        input: OnboardingOwnerEntryInput,
        bootstrapSecret: String,
    ) {
        when (mutableState.value) {
            is OnboardingOwnerEntryState.Created,
            is OnboardingOwnerEntryState.Reclaimed,
            -> return
            else -> Unit
        }
        if (!submission.tryLock()) return
        try {
            memberDisplayNameValidationError(input.displayName)?.let { message ->
                mutableState.value = OnboardingOwnerEntryState.RetryableFailure(input, message)
                return
            }
            when {
                !input.homeLanConfig.isServerConfigured -> "请填写服务器主机"
                !input.homeLanConfig.hasSsidAllowlist -> "请至少填写一个家庭 Wi‑Fi 名称"
                else -> null
            }?.let { message ->
                mutableState.value = OnboardingOwnerEntryState.RetryableFailure(input, message)
                return
            }
            mutableState.value = OnboardingOwnerEntryState.Submitting(input)
            gateway.saveHomeLanConfig(input.homeLanConfig).getOrThrow()
            val result = gateway.createFamily(
                displayName = input.displayName.trim(),
                bootstrapSecret = bootstrapSecret,
                familyName = input.familyName.trim().ifEmpty { null },
            ).getOrThrow()
            mutableState.value = if (result.reclaimed) {
                OnboardingOwnerEntryState.Reclaimed(result.dataRecovery)
            } else {
                OnboardingOwnerEntryState.Created(input)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.value = OnboardingOwnerEntryState.RetryableFailure(
                input = input,
                message = familySyncError(error, "创建家庭失败，请重试"),
            )
        } finally {
            submission.unlock()
        }
    }

    suspend fun retryDataRecovery() {
        val canRetry = when (val current = mutableState.value) {
            is OnboardingOwnerEntryState.Reclaimed ->
                current.dataRecovery == InitialFamilyDataRecovery.RetryRequired
            is OnboardingOwnerEntryState.RecoveryRetryableFailure -> true
            else -> false
        }
        if (!canRetry || !submission.tryLock()) return
        try {
            mutableState.value = OnboardingOwnerEntryState.Recovering
            gateway.retryDataRecovery().getOrThrow()
            mutableState.value = OnboardingOwnerEntryState.Reclaimed(
                InitialFamilyDataRecovery.Complete,
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.value = OnboardingOwnerEntryState.RecoveryRetryableFailure(
                familySyncError(
                    error,
                    "历史数据恢复失败，请保持连接家庭 Wi‑Fi 后重试",
                ),
            )
        } finally {
            submission.unlock()
        }
    }

    /** Restores UI-owned recovery intent after process death; identity/cursor remain SyncPort-owned. */
    fun restorePendingRecovery() {
        if (mutableState.value == OnboardingOwnerEntryState.Ready) {
            mutableState.value = OnboardingOwnerEntryState.Reclaimed(
                InitialFamilyDataRecovery.RetryRequired,
            )
        }
    }
}
