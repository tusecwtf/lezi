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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography

internal data class LocalDataRecoveryCopy(
    val title: String,
    val body: String,
    val resetLabel: String = "清除本机数据",
)

internal fun localDataRecoveryCopy(reason: LocalDataUpgradeBlockReason): LocalDataRecoveryCopy =
    when (reason) {
        LocalDataUpgradeBlockReason.UnsupportedLegacy -> LocalDataRecoveryCopy(
            title = "此本地数据版本过旧",
            body = "检测到 0.3.0 永久兼容基线之前的本地数据。为避免误删，原数据未被修改。" +
                "可保留现场导出诊断；确认不再需要旧数据后，才能手动清除本机数据。",
        )
        LocalDataUpgradeBlockReason.NewerData -> LocalDataRecoveryCopy(
            title = "此本地数据来自更新版本",
            body = "当前 APK 无法安全读取它。原数据未被修改，请安装更新版本后重试。",
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
            title = "当前 APK 缺少升级路径",
            body = "此 APK 未声明完整迁移链，已阻止原地替换。原数据未被修改。",
        )
        LocalDataUpgradeBlockReason.MigrationFailed -> LocalDataRecoveryCopy(
            title = "本地数据升级未完成",
            body = "迁移已停止，保护快照仍保留。原始数据不会被自动删除，可重试或导出诊断。",
        )
        LocalDataUpgradeBlockReason.VerificationFailed -> LocalDataRecoveryCopy(
            title = "升级结果校验失败",
            body = "应用尚未开放业务数据，保护快照仍保留。请重试或导出诊断。",
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(LeziSpacing.Xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Crossfade(
            targetState = blocked == null,
            animationSpec = tween(durationMillis = 200),
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
                    Text(text = copy.title, style = LeziTypography.Title)
                    Text(
                        text = copy.body,
                        modifier = Modifier.padding(top = LeziSpacing.Md),
                        style = LeziTypography.Body,
                    )
                    Text(
                        text = blocked.detail,
                        modifier = Modifier.padding(top = LeziSpacing.Sm),
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onRetry, modifier = Modifier.padding(top = LeziSpacing.Xl)) {
                        Text("重试安全检查")
                    }
                    OutlinedButton(
                        onClick = onShareDiagnostics,
                        modifier = Modifier.padding(top = LeziSpacing.Xs),
                    ) {
                        Text("导出诊断")
                    }
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
    TextButton(
        onClick = { confirmationStage = 1 },
        modifier = Modifier.padding(top = LeziSpacing.Xs),
    ) {
        Text(label)
    }
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
                TextButton(
                    onClick = {
                        if (finalConfirmation) onConfirmed() else confirmationStage = 2
                    },
                ) {
                    Text(if (finalConfirmation) "永久清除" else "继续")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmationStage = 0 }) { Text("取消") }
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
