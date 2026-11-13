package com.lezi.babylog

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.lezi.babylog.core.common.LocalDataUpgradeBlockReason
import com.lezi.babylog.core.common.LocalDataUpgradeState
import com.lezi.babylog.designsystem.LeziAlertDialog
import com.lezi.babylog.designsystem.LeziMotion
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTextButton
import com.lezi.babylog.designsystem.LeziTextButtonTone
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.LeziThemeExt
import com.lezi.babylog.designsystem.leziMotionMillis

internal data class LocalDataRecoveryCopy(
    val title: String,
    val body: String,
    val resetLabel: String = "清除本机数据",
)

internal fun localDataRecoverySecondaryDetail(
    reason: LocalDataUpgradeBlockReason,
    detail: String,
): String? {
    // Family-visible copy lives on the recovery page. Never surface Throwable.message.
    return null
}

internal fun localDataRecoveryCopy(reason: LocalDataUpgradeBlockReason): LocalDataRecoveryCopy =
    when (reason) {
        LocalDataUpgradeBlockReason.UnsupportedLegacy -> LocalDataRecoveryCopy(
            title = "此本地数据版本过旧",
            body = "这台手机上的数据来自很早以前的乐记版本（0.3.0 之前）。为避免误删，原数据未被修改。" +
                "可先导出诊断留底；确认不再需要旧数据后，才能手动清除本机数据。",
        )
        LocalDataUpgradeBlockReason.NewerData -> LocalDataRecoveryCopy(
            title = "此本地数据来自更新版本",
            body = "当前安装的乐记版本太旧，读不了这份较新的数据。原数据未被修改，请安装更新的乐记后再试。",
        )
        LocalDataUpgradeBlockReason.InsufficientSpace -> LocalDataRecoveryCopy(
            title = "空间不足，升级已暂停",
            body = "创建完整保护快照所需空间不足。原数据未被修改，请释放空间后重试。",
        )
        LocalDataUpgradeBlockReason.InconsistentData -> LocalDataRecoveryCopy(
            title = "本地数据状态不一致",
            body = "安全检查未通过，原数据未被修改。请导出诊断后重试。",
        )
        LocalDataUpgradeBlockReason.MissingMigration -> LocalDataRecoveryCopy(
            title = "这版乐记无法升级这份旧数据",
            body = "它缺少把旧数据安全升级过来的步骤，已停止升级以防丢失。原数据未被修改。",
        )
        LocalDataUpgradeBlockReason.MigrationFailed -> LocalDataRecoveryCopy(
            title = "本地数据升级未完成",
            body = "升级已停止，保护快照仍在。原数据不会被自动删除，可重试或导出诊断。",
        )
        LocalDataUpgradeBlockReason.VerificationFailed -> LocalDataRecoveryCopy(
            title = "升级结果校验失败",
            body = "乐记还没有打开你的记录，数据保持原样。请重试或导出诊断。",
        )
        LocalDataUpgradeBlockReason.TimedOut -> {
            val explanation = com.lezi.babylog.core.common.failure.failureExplanation(
                com.lezi.babylog.core.common.failure.FailureKind.SafetyCheckStuck,
            )
            LocalDataRecoveryCopy(
                title = explanation.dialogTitle,
                body = explanation.body,
            )
        }
        LocalDataUpgradeBlockReason.CredentialsUnavailable -> LocalDataRecoveryCopy(
            title = "无法读取这台手机的家庭登录凭证",
            body = "换机工具可能复制了登录文件，却无法带走与原手机绑定的加密密钥；" +
                "本机密钥失效、凭据文件损坏或读取异常，也可能造成这个问题。" +
                "乐记已暂停打开家庭数据，不会自动删除此手机上的记录、照片或登录凭证。\n\n" +
                "如果你正在换新手机：\n" +
                "1. 保留旧手机及其中的乐记。先不要卸载、清除数据或退出旧设备；" +
                "在旧手机连接原家庭服务器，确认最近记录、照片和待同步内容都已同步。\n" +
                "2. 新手机需要作为一台新设备重新加入原家庭，不能依赖换机工具复制登录状态。" +
                "普通成员请联系家庭管理员批准或提供授权二维码；" +
                "管理员使用原家庭的管理员登录方式，不要新建另一个家庭。\n" +
                "3. 如果这台新手机已因换机迁移停在此页，只有确认它没有唯一的未同步内容、" +
                "原家庭服务器上已有完整数据后，才可清除本机数据，再重新登录或申请加入原家庭。" +
                "清除只影响此手机，不会删除家庭服务器或旧手机上的内容。\n\n" +
                "旧手机无法使用、只在离线模式记录、家庭服务器暂时不可达，或不能确认数据完整时，" +
                "请先导出诊断并保留现状，不要清除。导出诊断只是故障信息，不是记录或照片备份。" +
                "重试安全检查不会找回原手机的加密密钥。",
        )
    }

@Composable
internal fun LocalDataUpgradeScreen(
    state: LocalDataUpgradeState,
    onRetry: () -> Unit,
    onShareDiagnostics: () -> Unit,
    onClearApplicationData: () -> Unit,
) {
    val blocked = state as? LocalDataUpgradeState.Blocked
    val progressText = when (state) {
        LocalDataUpgradeState.Checking -> "正在安全检查本地数据…"
        is LocalDataUpgradeState.Snapshotting -> "正在创建本地数据保护快照…"
        is LocalDataUpgradeState.Migrating -> "正在升级本地数据…"
        is LocalDataUpgradeState.Ready -> "本地数据已就绪"
        is LocalDataUpgradeState.Blocked -> null
    }
    // Loading ↔ blocked crossfade rides the Base tier (motion-polish ticket 03).
    Crossfade(
        targetState = blocked == null,
        animationSpec = tween(durationMillis = leziMotionMillis(LeziMotion.Base)),
        label = "localDataUpgradeState",
        modifier = Modifier.fillMaxSize(),
    ) { inProgress ->
        if (inProgress) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Text(
                    text = progressText.orEmpty(),
                    modifier = Modifier.padding(top = LeziSpacing.Lg),
                    style = LeziTypography.Body,
                )
            }
        } else if (blocked != null) {
            val copy = localDataRecoveryCopy(blocked.reason)
            // The device-switch guide is long: flow top-down inside a single
            // vertical scroller so every paragraph and action stays reachable
            // from 720p elder layouts up to 4K.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("local_data_recovery_scroll")
                    .verticalScroll(rememberScrollState())
                    .padding(LeziSpacing.Xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = copy.title,
                    style = LeziThemeExt.typography.Title,
                    modifier = Modifier.testTag("local_data_recovery_title"),
                )
                Text(
                    text = copy.body,
                    modifier = Modifier
                        .padding(top = LeziSpacing.Md)
                        .testTag("local_data_recovery_body"),
                    style = LeziTypography.Body,
                )
                localDataRecoverySecondaryDetail(blocked.reason, blocked.detail)?.let { detail ->
                    Text(
                        text = detail,
                        modifier = Modifier.padding(top = LeziSpacing.Sm),
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LeziPrimaryButton(
                    label = "重试安全检查",
                    onClick = onRetry,
                    modifier = Modifier
                        .padding(top = LeziSpacing.Xl)
                        .testTag("local_data_recovery_retry"),
                )
                LeziSecondaryButton(
                    label = "导出诊断",
                    onClick = onShareDiagnostics,
                    modifier = Modifier
                        .padding(top = LeziSpacing.Xs)
                        .testTag("local_data_recovery_export"),
                )
                ClearApplicationDataButton(
                    label = copy.resetLabel,
                    reason = blocked.reason,
                    onConfirmed = onClearApplicationData,
                )
            }
        }
    }
}

@Composable
private fun ClearApplicationDataButton(
    label: String,
    reason: LocalDataUpgradeBlockReason,
    onConfirmed: () -> Unit,
) {
    // A different failure reason must never inherit another reason's
    // confirmation progress.
    var confirmationStage by remember(reason) { mutableIntStateOf(0) }
    LeziTextButton(
        label = label,
        onClick = { confirmationStage = 1 },
        modifier = Modifier
            .padding(top = LeziSpacing.Xs)
            .testTag("local_data_recovery_clear"),
        tone = LeziTextButtonTone.Destructive,
    )
    if (confirmationStage > 0) {
        val finalConfirmation = confirmationStage == 2
        LeziAlertDialog(
            onDismissRequest = { confirmationStage = 0 },
            title = { Text(if (finalConfirmation) "最后确认" else "确认清除本机数据？") },
            text = {
                Text(
                    if (finalConfirmation) {
                        "此操作将永久删除本机记录、照片、设置和登录凭证，且不能撤销。" +
                            "不会删除家庭服务器或旧手机上的内容。"
                    } else if (reason == LocalDataUpgradeBlockReason.CredentialsUnavailable) {
                        "清除会永久删除此手机上的记录、照片、设置和登录凭证。" +
                            "尚未同步到家庭服务器的内容可能无法找回。导出诊断不是数据备份。" +
                            "只有确认服务器已有完整数据、此手机没有唯一内容后，才继续。"
                    } else {
                        "只有在你已经确认不再需要旧数据时才继续。下一步仍会再次确认。"
                    },
                )
            },
            confirmButton = {
                LeziTextButton(
                    label = if (finalConfirmation) "永久清除" else "继续",
                    onClick = {
                        if (finalConfirmation) onConfirmed() else confirmationStage = 2
                    },
                    modifier = Modifier.testTag(
                        if (finalConfirmation) {
                            "local_data_recovery_clear_final"
                        } else {
                            "local_data_recovery_clear_continue"
                        },
                    ),
                    tone = if (finalConfirmation) {
                        LeziTextButtonTone.Destructive
                    } else {
                        LeziTextButtonTone.Primary
                    },
                )
            },
            dismissButton = {
                LeziTextButton(
                    label = "取消",
                    onClick = { confirmationStage = 0 },
                    modifier = Modifier.testTag("local_data_recovery_clear_cancel"),
                )
            },
        )
    }
}

internal fun shareLocalDataDiagnostics(context: Context, report: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "乐记本地数据诊断")
        putExtra(Intent.EXTRA_TEXT, report)
    }
    context.startActivity(Intent.createChooser(intent, "导出诊断"))
}

internal fun clearLeziApplicationData(context: Context) {
    context.getSystemService(ActivityManager::class.java).clearApplicationUserData()
}
