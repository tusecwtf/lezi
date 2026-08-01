package com.lezi.babylog.feature.family.overview

import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.sync.AppUpdateMetadata
import com.lezi.babylog.sync.AppUpdateUiOutcome
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.appUpdateInstallUiOutcome
import com.lezi.babylog.sync.appUpdateUiOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * App-update outcome + busy flags shared by the account overview host façade.
 * Mirrors the Settings check/install state machine without a cross-feature port.
 */
internal class AppUpdateOutcomeMachine(
    private val sync: SyncPort,
) {
    private val _outcome = MutableStateFlow<AppUpdateUiOutcome?>(null)
    val outcome: StateFlow<AppUpdateUiOutcome?> = _outcome.asStateFlow()
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()
    private val _installing = MutableStateFlow(false)
    val installing: StateFlow<Boolean> = _installing.asStateFlow()

    fun openOptional(metadata: AppUpdateMetadata) {
        if (_installing.value) return
        _outcome.value = AppUpdateUiOutcome.OptionalUpdate(metadata)
    }

    fun dismissOptional(versionCode: Int) {
        sync.dismissOptionalAppUpdate(versionCode)
        val current = _outcome.value
        if (current is AppUpdateUiOutcome.OptionalUpdate &&
            current.metadata.versionCode == versionCode
        ) {
            _outcome.value = null
        }
    }

    fun dismissOutcome() {
        val current = _outcome.value
        // Forced updates cannot be dismissed ("稍后" is not allowed). Root force shell
        // remains authoritative; do not dismiss PackageUnknown either.
        if (current is AppUpdateUiOutcome.ForcedUpdate) return
        if (current is AppUpdateUiOutcome.ForcedUpdatePackageUnknown) return
        if (current is AppUpdateUiOutcome.OptionalUpdate) {
            sync.dismissOptionalAppUpdate(current.metadata.versionCode)
        }
        _outcome.value = null
    }

    suspend fun check() {
        if (_checking.value || _installing.value) return
        _checking.value = true
        try {
            val result = sync.checkAppUpdate()
            // Pass live force shell so secondary dialog never claims Optional/UpToDate
            // while root ForcedAppUpdateState is retained (AUDIT-20260801-P1-01).
            val activeForce = sync.availableForcedAppUpdate().first()
            _outcome.value = appUpdateUiOutcome(
                result = result,
                failureCopy = { error ->
                    productUiError(error, "检查更新失败，请稍后重试")
                },
                activeForcedAppUpdate = activeForce,
            )
        } finally {
            _checking.value = false
        }
    }

    suspend fun install(metadata: AppUpdateMetadata) {
        if (_installing.value) return
        _installing.value = true
        _outcome.value = AppUpdateUiOutcome.Message(
            title = "正在下载",
            body = "正在从家庭服务器下载更新包…",
        )
        try {
            val result = sync.installAvailableAppUpdate(metadata)
            // Update install failures must not use family-sync/NAS copy (productUiError only).
            _outcome.value = appUpdateInstallUiOutcome(result) { error ->
                productUiError(error, "下载或安装失败，请稍后重试")
            }
        } finally {
            _installing.value = false
        }
    }
}
