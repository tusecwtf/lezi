package com.lezi.babylog.core.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import com.lezi.babylog.designsystem.LeziAlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTextButton
import com.lezi.babylog.designsystem.LeziTextButtonTone
import com.lezi.babylog.sync.AppUpdateMetadata
import com.lezi.babylog.sync.appupdate.AppUpdateUiOutcome
import com.lezi.babylog.sync.appupdate.forcedUpdateDialogBody
import com.lezi.babylog.sync.appupdate.forcedUpdatePackageUnknownBody
import com.lezi.babylog.sync.appupdate.forcedUpdateRetryCheckLabel
import com.lezi.babylog.sync.appupdate.forcedUpdateTitle
import com.lezi.babylog.sync.appupdate.optionalUpdateDialogBody

/**
 * Shared app-update secondary dialogs (menu settings + family overview).
 *
 * Full contract: forced updates are non-dismissible and surface install feedback
 * plus the unknown-sources permission hand-off inline; the root
 * ForcedAppUpdateState full-screen shell stays authoritative.
 */
@Composable
fun AppUpdateOutcomeDialogs(
    outcome: AppUpdateUiOutcome?,
    checkingAppUpdate: Boolean,
    installingAppUpdate: Boolean,
    installFeedback: String?,
    forcedInstallPermissionRequired: Boolean,
    onDismissOutcome: () -> Unit,
    onInstallUpdate: (AppUpdateMetadata) -> Unit,
    onRetryCheck: () -> Unit,
    onForcedInstallPermissionOpened: () -> Unit,
) {
    val context = LocalContext.current
    when (outcome) {
        is AppUpdateUiOutcome.Message -> {
            LeziAlertDialog(
                onDismissRequest = {
                    if (!installingAppUpdate) onDismissOutcome()
                },
                title = { Text(outcome.title) },
                text = { Text(outcome.body) },
                confirmButton = {
                    LeziTextButton(
                        label = if (installingAppUpdate) "请稍候" else "知道了",
                        onClick = onDismissOutcome,
                        enabled = !installingAppUpdate,
                        tone = LeziTextButtonTone.Primary,
                    )
                },
            )
        }
        is AppUpdateUiOutcome.OptionalUpdate -> {
            LeziAlertDialog(
                onDismissRequest = {
                    if (!installingAppUpdate) onDismissOutcome()
                },
                title = { Text("发现新版本") },
                text = { Text(optionalUpdateDialogBody(outcome.metadata)) },
                confirmButton = {
                    LeziTextButton(
                        label = if (installingAppUpdate) "安装中…" else "立即更新",
                        onClick = { onInstallUpdate(outcome.metadata) },
                        enabled = !installingAppUpdate,
                        tone = LeziTextButtonTone.Primary,
                    )
                },
                dismissButton = {
                    LeziTextButton(label = "稍后", onClick = onDismissOutcome, enabled = !installingAppUpdate)
                },
            )
        }
        is AppUpdateUiOutcome.ForcedUpdate -> {
            // Non-dismissible: no "稍后", back/outside dismiss ignored.
            // Secondary to root ForcedAppUpdateState full-screen shell.
            LeziAlertDialog(
                onDismissRequest = {},
                properties = DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                ),
                title = { Text(forcedUpdateTitle()) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
                        Text(forcedUpdateDialogBody(outcome.metadata))
                        installFeedback?.let { feedback ->
                            Text(
                                feedback,
                                color = if (installingAppUpdate) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    }
                },
                confirmButton = {
                    LeziTextButton(
                        label = when {
                            installingAppUpdate -> "安装中…"
                            forcedInstallPermissionRequired -> "去授权安装"
                            else -> "立即更新"
                        },
                        onClick = {
                            if (forcedInstallPermissionRequired) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:${context.packageName}"),
                                )
                                runCatching { context.startActivity(intent) }
                                onForcedInstallPermissionOpened()
                            } else {
                                onInstallUpdate(outcome.metadata)
                            }
                        },
                        enabled = !installingAppUpdate,
                        tone = LeziTextButtonTone.Primary,
                    )
                },
            )
        }
        AppUpdateUiOutcome.ForcedUpdatePackageUnknown -> {
            // Align with ForcedAppUpdateState.PackageUnknown: retry check only.
            LeziAlertDialog(
                onDismissRequest = {},
                properties = DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                ),
                title = { Text(forcedUpdateTitle()) },
                text = { Text(forcedUpdatePackageUnknownBody()) },
                confirmButton = {
                    LeziTextButton(
                        label = if (checkingAppUpdate) {
                            "检查中…"
                        } else {
                            forcedUpdateRetryCheckLabel()
                        },
                        onClick = onRetryCheck,
                        enabled = !checkingAppUpdate && !installingAppUpdate,
                        tone = LeziTextButtonTone.Primary,
                    )
                },
            )
        }
        AppUpdateUiOutcome.NeedsInstallPermission -> {
            LeziAlertDialog(
                onDismissRequest = onDismissOutcome,
                title = { Text("需要安装权限") },
                text = {
                    Text("请允许乐记安装应用，然后再试一次立即更新。")
                },
                confirmButton = {
                    LeziTextButton(
                        label = "去设置",
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${context.packageName}"),
                            )
                            runCatching { context.startActivity(intent) }
                            onDismissOutcome()
                        },
                        tone = LeziTextButtonTone.Primary,
                    )
                },
                dismissButton = {
                    LeziTextButton(label = "取消", onClick = onDismissOutcome)
                },
            )
        }
        null -> Unit
    }
}
