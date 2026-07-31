package com.lezi.babylog.feature.settings

import com.lezi.babylog.sync.AppUpdateCheckResult
import com.lezi.babylog.sync.AppUpdateInstallResult
import com.lezi.babylog.sync.AppUpdateMetadata

/** About-panel subtitle for the installed app. */
internal fun localAppVersionLabel(versionName: String): String =
    "版本 ${versionName.trim().ifEmpty { "未知" }}"

sealed interface AppUpdateUiOutcome {
    data class Message(
        val title: String,
        val body: String,
    ) : AppUpdateUiOutcome

    data class OptionalUpdate(
        val metadata: AppUpdateMetadata,
    ) : AppUpdateUiOutcome

    /** Prompt the user to grant install-unknown-apps for this package. */
    data object NeedsInstallPermission : AppUpdateUiOutcome
}

/**
 * Maps high-level [AppUpdateCheckResult] (and transport failures) to settings
 * dialog copy.
 */
internal fun appUpdateUiOutcome(
    result: Result<AppUpdateCheckResult>,
    failureCopy: (Throwable) -> String,
): AppUpdateUiOutcome {
    val value = result.getOrElse { error ->
        return AppUpdateUiOutcome.Message(
            title = "检查更新",
            body = failureCopy(error),
        )
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
        is AppUpdateCheckResult.OptionalUpdate -> AppUpdateUiOutcome.OptionalUpdate(value.metadata)
    }
}

internal fun optionalUpdateDialogBody(metadata: AppUpdateMetadata): String {
    val notes = metadata.releaseNotes?.trim().orEmpty()
    return buildString {
        append("发现新版本 ${metadata.versionName}")
        if (notes.isNotEmpty()) {
            append('\n')
            append(notes)
        }
    }
}

internal fun appUpdateInstallUiOutcome(
    result: Result<AppUpdateInstallResult>,
    failureCopy: (Throwable) -> String,
): AppUpdateUiOutcome {
    val value = result.getOrElse { error ->
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
