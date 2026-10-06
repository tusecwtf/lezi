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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(LeziSpacing.Xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Loading ↔ blocked crossfade rides the Base tier (motion-polish ticket 03).
        Crossfade(
            targetState = blocked == null,
            animationSpec = tween(durationMillis = leziMotionMillis(LeziMotion.Base)),
            label = "localDataUpgradeState",
        ) { inProgress ->
            if (inProgress) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(
                        text = progressText.orEmpty(),
                        modifier = Modifier.padding(top = LeziSpacing.Lg),
                        style = LeziTypography.Body,
                    )
                }
            } else if (blocked != null) {
                val copy = localDataRecoveryCopy(blocked.reason)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = copy.title, style = LeziThemeExt.typography.Title)
                    Text(
                        text = copy.body,
                        modifier = Modifier.padding(top = LeziSpacing.Md),
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
                        modifier = Modifier.padding(top = LeziSpacing.Xl),
                    )
                    LeziSecondaryButton(
                        label = "导出诊断",
                        onClick = onShareDiagnostics,
                        modifier = Modifier.padding(top = LeziSpacing.Xs),
                    )
                    ClearApplicationDataButton(
                        label = copy.resetLabel,
                        onConfirmed = onClearApplicationData,
                    )
                }
            }
        }
    }
}

@Composable
private fun ClearApplicationDataButton(
    label: String,
    onConfirmed: () -> Unit,
) {
    var confirmationStage by remember { mutableIntStateOf(0) }
    LeziTextButton(
        label = label,
        onClick = { confirmationStage = 1 },
        modifier = Modifier.padding(top = LeziSpacing.Xs),
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
                        "此操作将永久删除本机记录、设置和登录凭证，且不能撤销。"
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
                    tone = if (finalConfirmation) {
                        LeziTextButtonTone.Destructive
                    } else {
                        LeziTextButtonTone.Primary
                    },
                )
            },
            dismissButton = {
                LeziTextButton(label = "取消", onClick = { confirmationStage = 0 })
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
