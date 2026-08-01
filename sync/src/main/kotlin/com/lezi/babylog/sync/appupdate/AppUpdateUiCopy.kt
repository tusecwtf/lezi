package com.lezi.babylog.sync.appupdate

import com.lezi.babylog.sync.APP_UPDATE_INSTALL_IN_PROGRESS_MESSAGE
import com.lezi.babylog.sync.APP_UPDATE_INSTALL_IN_PROGRESS_TITLE
import com.lezi.babylog.sync.AppUpdateCheckResult
import com.lezi.babylog.sync.AppUpdateInstallInProgressException
import com.lezi.babylog.sync.AppUpdateInstallResult
import com.lezi.babylog.sync.AppUpdateMetadata
import com.lezi.babylog.sync.ForcedAppUpdateState
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.backend.ClientUpdateRequiredException

/** About-panel subtitle for the installed app. */
fun localAppVersionLabel(versionName: String): String =
    "版本 ${versionName.trim().ifEmpty { "未知" }}"

/** Non-blocking account/sync banner primary line for an optional update. */
fun optionalAppUpdateBannerLabel(versionName: String): String =
    "有新版本 ${versionName.trim().ifEmpty { "未知" }} 可更新"

/** Accessibility label for the optional-update banner. */
fun optionalAppUpdateBannerContentDescription(versionName: String): String =
    "可选更新：版本 ${versionName.trim().ifEmpty { "未知" }}，点按查看"

/**
 * Settings/family UI outcome for app-update check and install paths.
 * Kept on the sync seam so both feature modules share one mapping.
 */
sealed interface AppUpdateUiOutcome {
    data class Message(
        val title: String,
        val body: String,
    ) : AppUpdateUiOutcome

    data class OptionalUpdate(
        val metadata: AppUpdateMetadata,
    ) : AppUpdateUiOutcome

    /**
     * Non-dismissible force upgrade with installable package metadata.
     * UI must not offer "稍后". Root [ForcedAppUpdateState.WithPackage] is the
     * authoritative full-screen gate; settings/family dialogs are secondary and
     * must not dismiss or bypass that root shell.
     */
    data class ForcedUpdate(
        val metadata: AppUpdateMetadata,
    ) : AppUpdateUiOutcome

    /**
     * Force required (server `client_update_required`) but package metadata is not
     * available yet. Maps to [ForcedAppUpdateState.PackageUnknown] on the root shell;
     * dialogs may offer retry-check only — no "稍后", no install until metadata loads.
     */
    data object ForcedUpdatePackageUnknown : AppUpdateUiOutcome

    /** Prompt the user to grant install-unknown-apps for this package. */
    data object NeedsInstallPermission : AppUpdateUiOutcome
}

/**
 * Maps high-level [AppUpdateCheckResult] (and transport failures) to dialog copy.
 *
 * Maps only façade-level results: [AppUpdateCheckResult], [ForcedAppUpdateState],
 * and [ClientUpdateRequiredException] already surfaced by [SyncPort]. Wire-body
 * re-parse of `client_update_required` stays on the backend helper used by
 * RealSyncPort — UI copy does not dual-parse HTTP bodies.
 *
 * When a root force shell is active ([availableForcedAppUpdate] non-null), callers
 * should pass [activeForcedAppUpdate] so Optional/UpToDate cannot surface as a
 * dismissible dialog or "当前已是最新版本" Message (belt-and-suspenders with
 * force-honest [AppUpdateCheckResult] from [SyncPort.checkAppUpdate]).
 */
fun appUpdateUiOutcome(
    result: Result<AppUpdateCheckResult>,
    failureCopy: (Throwable) -> String,
    activeForcedAppUpdate: ForcedAppUpdateState? = null,
): AppUpdateUiOutcome {
    val value = result.getOrElse { error ->
        if (error is ClientUpdateRequiredException) {
            // Align with ForcedAppUpdateState.PackageUnknown: force shell, not a
            // dismissible "network" Message. Root overlay remains authoritative.
            return AppUpdateUiOutcome.ForcedUpdatePackageUnknown
        }
        // Transport failure while force shell is up: keep force-honest outcome.
        if (activeForcedAppUpdate != null) {
            return when (activeForcedAppUpdate) {
                is ForcedAppUpdateState.WithPackage ->
                    AppUpdateUiOutcome.ForcedUpdate(activeForcedAppUpdate.metadata)
                ForcedAppUpdateState.PackageUnknown ->
                    AppUpdateUiOutcome.ForcedUpdatePackageUnknown
            }
        }
        return AppUpdateUiOutcome.Message(
            title = "检查更新",
            body = failureCopy(error),
        )
    }
    // Force shell wins over bare dual-tier Result if channels ever disagree.
    if (activeForcedAppUpdate != null) {
        when (value) {
            is AppUpdateCheckResult.ForcedUpdate ->
                return AppUpdateUiOutcome.ForcedUpdate(value.metadata)
            AppUpdateCheckResult.ForcedPackageUnknown,
            AppUpdateCheckResult.UpToDate,
            is AppUpdateCheckResult.OptionalUpdate,
            -> {
                return when (activeForcedAppUpdate) {
                    is ForcedAppUpdateState.WithPackage ->
                        AppUpdateUiOutcome.ForcedUpdate(activeForcedAppUpdate.metadata)
                    ForcedAppUpdateState.PackageUnknown ->
                        AppUpdateUiOutcome.ForcedUpdatePackageUnknown
                }
            }
            AppUpdateCheckResult.NotJoined -> {
                // NotJoined clears shell in port; still do not claim "latest".
            }
        }
    }
    return when (value) {
        AppUpdateCheckResult.NotJoined -> AppUpdateUiOutcome.Message(
            title = "检查更新",
            body = "请先连接家庭服务器后再检查更新",
        )
        AppUpdateCheckResult.UpToDate -> AppUpdateUiOutcome.Message(
            title = "检查更新",
            body = "当前已是最新版本",
        )
        is AppUpdateCheckResult.OptionalUpdate ->
            AppUpdateUiOutcome.OptionalUpdate(value.metadata)
        is AppUpdateCheckResult.ForcedUpdate ->
            AppUpdateUiOutcome.ForcedUpdate(value.metadata)
        AppUpdateCheckResult.ForcedPackageUnknown ->
            AppUpdateUiOutcome.ForcedUpdatePackageUnknown
    }
}

fun optionalUpdateDialogBody(metadata: AppUpdateMetadata): String {
    val notes = metadata.releaseNotes?.trim().orEmpty()
    return buildString {
        append("发现新版本 ${metadata.versionName}")
        if (notes.isNotEmpty()) {
            append('\n')
            append(notes)
        }
    }
}

/** Full-screen force-update primary copy. */
fun forcedUpdateDialogBody(metadata: AppUpdateMetadata): String {
    val notes = metadata.releaseNotes?.trim().orEmpty()
    return buildString {
        append("当前版本过旧，须升级到 ${metadata.versionName} 后才能继续同步家庭数据。")
        if (notes.isNotEmpty()) {
            append('\n')
            append(notes)
        }
    }
}

/**
 * Full-screen force shell when the server already required a client update but
 * package metadata could not be loaded yet (retry check, no install until known).
 */
fun forcedUpdatePackageUnknownBody(): String =
    "当前版本过旧，须更新乐记后才能继续同步家庭数据。暂时无法从家庭服务器获取更新包，请点「重试检查更新」后再试。"

/** Primary action label while forced package metadata is still unknown. */
fun forcedUpdateRetryCheckLabel(): String = "重试检查更新"

fun forcedUpdateTitle(): String = "必须更新乐记"

fun appUpdateInstallUiOutcome(
    result: Result<AppUpdateInstallResult>,
    failureCopy: (Throwable) -> String,
): AppUpdateUiOutcome {
    val value = result.getOrElse { error ->
        if (error is AppUpdateInstallInProgressException) {
            return AppUpdateUiOutcome.Message(
                title = APP_UPDATE_INSTALL_IN_PROGRESS_TITLE,
                body = error.message ?: APP_UPDATE_INSTALL_IN_PROGRESS_MESSAGE,
            )
        }
        return AppUpdateUiOutcome.Message(
            title = "更新失败",
            body = failureCopy(error),
        )
    }
    return when (value) {
        AppUpdateInstallResult.SessionStarted -> AppUpdateUiOutcome.Message(
            title = "正在安装",
            body = "请在系统界面确认安装。安装结束后可删除通知；乐记不会在本机留下更新包。",
        )
        AppUpdateInstallResult.RequiresInstallPermission ->
            AppUpdateUiOutcome.NeedsInstallPermission
    }
}
