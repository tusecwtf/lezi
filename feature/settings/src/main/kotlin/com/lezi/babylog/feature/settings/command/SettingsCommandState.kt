package com.lezi.babylog.feature.settings.command

import com.lezi.babylog.sync.appupdate.AppUpdateUiOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class SettingsClearRecordsStep {
    Idle,
    FirstConfirm,
    FinalConfirm,
}

/** Primary button of the clear-records dialog. First step must advance, not clear. */
internal enum class ClearRecordsPrimaryAction {
    Continue,
    Confirm,
}

internal fun clearRecordsPrimaryAction(
    step: SettingsClearRecordsStep,
): ClearRecordsPrimaryAction? = when (step) {
    SettingsClearRecordsStep.FirstConfirm -> ClearRecordsPrimaryAction.Continue
    SettingsClearRecordsStep.FinalConfirm -> ClearRecordsPrimaryAction.Confirm
    SettingsClearRecordsStep.Idle -> null
}

internal data class SettingsClearRecordsState(
    val step: SettingsClearRecordsStep = SettingsClearRecordsStep.Idle,
    val clearing: Boolean = false,
    val error: String? = null,
)

/** ViewModel-owned two-step clear state; composition is only a projection. */
internal class SettingsClearRecordsController(
    private val clearRecords: suspend () -> Unit,
    private val failureCopy: (Throwable) -> String,
) {
    private val mutableState = MutableStateFlow(SettingsClearRecordsState())

    val state: StateFlow<SettingsClearRecordsState> = mutableState.asStateFlow()

    fun request() {
        val current = mutableState.value
        if (current.clearing) return
        mutableState.value = SettingsClearRecordsState(
            step = SettingsClearRecordsStep.FirstConfirm,
        )
    }

    fun continueToFinal() {
        val current = mutableState.value
        if (current.step != SettingsClearRecordsStep.FirstConfirm || current.clearing) return
        mutableState.value = SettingsClearRecordsState(
            step = SettingsClearRecordsStep.FinalConfirm,
        )
    }

    fun dismiss() {
        if (mutableState.value.clearing) return
        mutableState.value = SettingsClearRecordsState()
    }

    /** Returns false when the final confirm is absent or another clear already owns it. */
    suspend fun confirm(): Boolean {
        while (true) {
            val current = mutableState.value
            if (current.step != SettingsClearRecordsStep.FinalConfirm || current.clearing) {
                return false
            }
            if (
                mutableState.compareAndSet(
                    current,
                    current.copy(clearing = true, error = null),
                )
            ) {
                break
            }
        }
        return try {
            clearRecords()
            mutableState.value = SettingsClearRecordsState()
            true
        } catch (cancelled: CancellationException) {
            mutableState.value = SettingsClearRecordsState(
                step = SettingsClearRecordsStep.FinalConfirm,
            )
            throw cancelled
        } catch (error: Throwable) {
            mutableState.value = SettingsClearRecordsState(
                step = SettingsClearRecordsStep.FinalConfirm,
                error = failureCopy(error),
            )
            true
        }
    }
}

internal enum class CustomItemSaveKind {
    Add,
    Update,
}

internal data class SettingsCustomItemCommandState(
    val saveKind: CustomItemSaveKind? = null,
    private val layoutRequests: Int = 0,
) {
    val layoutBusy: Boolean get() = layoutRequests > 0

    internal fun addLayoutRequest(): SettingsCustomItemCommandState =
        copy(layoutRequests = layoutRequests + 1)

    internal fun finishLayoutRequest(): SettingsCustomItemCommandState =
        copy(layoutRequests = (layoutRequests - 1).coerceAtLeast(0))
}

/**
 * Add/update are single-flight. Layout RMW commands queue behind one mutex and
 * therefore always execute against the freshest store snapshot supplied by the caller.
 */
internal class SettingsCustomItemCommandGate {
    private val mutableState = MutableStateFlow(SettingsCustomItemCommandState())
    private val mutationMutex = Mutex()

    val state: StateFlow<SettingsCustomItemCommandState> = mutableState.asStateFlow()

    suspend fun runSave(
        kind: CustomItemSaveKind,
        command: suspend () -> Unit,
    ): Boolean {
        while (true) {
            val current = mutableState.value
            if (current.saveKind != null) return false
            if (mutableState.compareAndSet(current, current.copy(saveKind = kind))) break
        }
        return try {
            mutationMutex.withLock { command() }
            true
        } finally {
            mutableState.update { it.copy(saveKind = null) }
        }
    }

    suspend fun runLayout(command: suspend () -> Unit) {
        mutableState.update(SettingsCustomItemCommandState::addLayoutRequest)
        try {
            mutationMutex.withLock { command() }
        } finally {
            mutableState.update(SettingsCustomItemCommandState::finishLayoutRequest)
        }
    }
}

internal data class SettingsInstallProjection(
    val outcome: AppUpdateUiOutcome,
    val feedback: String? = null,
    val requiresInstallPermission: Boolean = false,
)

/** A forced shell is never replaced by a dismissible install-result message. */
internal fun projectSettingsInstallOutcome(
    activeOutcome: AppUpdateUiOutcome,
    installOutcome: AppUpdateUiOutcome,
): SettingsInstallProjection {
    if (activeOutcome !is AppUpdateUiOutcome.ForcedUpdate) {
        return SettingsInstallProjection(installOutcome)
    }
    return when (installOutcome) {
        AppUpdateUiOutcome.NeedsInstallPermission -> SettingsInstallProjection(
            outcome = activeOutcome,
            feedback = "需要允许乐记安装应用，授权后请再次立即更新",
            requiresInstallPermission = true,
        )
        is AppUpdateUiOutcome.Message -> SettingsInstallProjection(
            outcome = activeOutcome,
            feedback = installOutcome.body,
        )
        else -> SettingsInstallProjection(activeOutcome)
    }
}
