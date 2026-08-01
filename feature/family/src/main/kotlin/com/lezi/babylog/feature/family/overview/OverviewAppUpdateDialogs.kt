package com.lezi.babylog.feature.family.overview

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import com.lezi.babylog.sync.AppUpdateUiOutcome
import com.lezi.babylog.sync.forcedUpdateDialogBody
import com.lezi.babylog.sync.forcedUpdatePackageUnknownBody
import com.lezi.babylog.sync.forcedUpdateRetryCheckLabel
import com.lezi.babylog.sync.forcedUpdateTitle
import com.lezi.babylog.sync.optionalUpdateDialogBody

/** App-update secondary dialogs owned by the account overview host. */
@Composable
internal fun OverviewAppUpdateDialogs(
    host: AccountOverviewHost,
    outcome: AppUpdateUiOutcome?,
    checkingAppUpdate: Boolean,
    installingAppUpdate: Boolean,
) {
    val context = LocalContext.current
    when (outcome) {
        is AppUpdateUiOutcome.Message -> {
            AlertDialog(
                onDismissRequest = {
                    if (!installingAppUpdate) host.dismissAppUpdateOutcome()
                },
                title = { Text(outcome.title) },
                text = { Text(outcome.body) },
                confirmButton = {
                    TextButton(
                        onClick = host::dismissAppUpdateOutcome,
                        enabled = !installingAppUpdate,
                    ) {
                        Text(if (installingAppUpdate) "请稍候" else "知道了")
                    }
                },
            )
        }
        is AppUpdateUiOutcome.OptionalUpdate -> {
            AlertDialog(
                onDismissRequest = {
                    if (!installingAppUpdate) host.dismissAppUpdateOutcome()
                },
                title = { Text("发现新版本") },
                text = { Text(optionalUpdateDialogBody(outcome.metadata)) },
                confirmButton = {
                    TextButton(
                        onClick = { host.installOptionalUpdate(outcome.metadata) },
                        enabled = !installingAppUpdate,
                    ) {
                        Text(if (installingAppUpdate) "安装中…" else "立即更新")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = host::dismissAppUpdateOutcome,
                        enabled = !installingAppUpdate,
                    ) {
                        Text("稍后")
                    }
                },
            )
        }
        is AppUpdateUiOutcome.ForcedUpdate -> {
            AlertDialog(
                onDismissRequest = {},
                properties = DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                ),
                title = { Text(forcedUpdateTitle()) },
                text = { Text(forcedUpdateDialogBody(outcome.metadata)) },
                confirmButton = {
                    TextButton(
                        onClick = { host.installOptionalUpdate(outcome.metadata) },
                        enabled = !installingAppUpdate,
                    ) {
                        Text(if (installingAppUpdate) "安装中…" else "立即更新")
                    }
                },
            )
        }
        AppUpdateUiOutcome.ForcedUpdatePackageUnknown -> {
            AlertDialog(
                onDismissRequest = {},
                properties = DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                ),
                title = { Text(forcedUpdateTitle()) },
                text = { Text(forcedUpdatePackageUnknownBody()) },
                confirmButton = {
                    TextButton(
                        onClick = host::checkAppUpdate,
                        enabled = !checkingAppUpdate && !installingAppUpdate,
                    ) {
                        Text(
                            if (checkingAppUpdate) {
                                "检查中…"
                            } else {
                                forcedUpdateRetryCheckLabel()
                            },
                        )
                    }
                },
            )
        }
        AppUpdateUiOutcome.NeedsInstallPermission -> {
            AlertDialog(
                onDismissRequest = host::dismissAppUpdateOutcome,
                title = { Text("需要安装权限") },
                text = {
                    Text("请允许乐记安装应用，然后再试一次立即更新。")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${context.packageName}"),
                            )
                            runCatching { context.startActivity(intent) }
                            host.dismissAppUpdateOutcome()
                        },
                    ) {
                        Text("去设置")
                    }
                },
                dismissButton = {
                    TextButton(onClick = host::dismissAppUpdateOutcome) {
                        Text("取消")
                    }
                },
            )
        }
        null -> Unit
    }
}
