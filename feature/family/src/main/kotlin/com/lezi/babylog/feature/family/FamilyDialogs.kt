package com.lezi.babylog.feature.family

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.dismissKeyboardOnTap
import com.lezi.babylog.sync.HomeWifiSettingsTarget

@Composable
internal fun JoinFamilyDialog(
    joinCode: String,
    onJoinCodeChange: (String) -> Unit,
    joining: Boolean,
    onScan: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!joining) onDismiss() },
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text("加入家庭") },
        text = {
            Column(Modifier.dismissKeyboardOnTap()) {
                FamilyScopeRow("共享", "家庭数据", "宝宝档案、照护记录、日志图片")
                Spacer(Modifier.height(LeziSpacing.Xs))
                FamilyScopeRow("本机", "个人偏好", "主题、提醒、桌面小组件")
                Spacer(Modifier.height(LeziSpacing.Sm))
                OutlinedTextField(
                    value = joinCode,
                    onValueChange = onJoinCodeChange,
                    label = { Text("邀请码") },
                    placeholder = { Text("输入共享码，或使用下方扫码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(LeziSpacing.Sm))
                OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("扫码填入邀请码")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !joining) {
                Text(if (joining) "正在加入…" else "加入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !joining) { Text("取消") }
        },
    )
}

@Composable
internal fun CreateFamilyDialog(
    bootstrapSecret: String,
    onBootstrapSecretChange: (String) -> Unit,
    feedback: String?,
    creating: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!creating) onDismiss() },
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text("新建家庭") },
        text = {
            Column(
                modifier = Modifier.dismissKeyboardOnTap(),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                FamilyScopeRow("一次", "服务器初始化", "口令只用于本次建家")
                OutlinedTextField(
                    value = bootstrapSecret,
                    onValueChange = onBootstrapSecretChange,
                    label = { Text("服务器初始化口令") },
                    supportingText = { Text(feedback ?: "与 NAS 部署时设置的口令一致") },
                    isError = feedback != null,
                    enabled = !creating,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !creating) {
                Text(if (creating) "创建中…" else "创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !creating) { Text("取消") }
        },
    )
}

@Composable
internal fun FamilyInviteDialog(invite: FamilyInviteView, onDismiss: () -> Unit) {
    SecureWindowWhileVisible()
    var showPayload by remember(invite.code) { mutableStateOf(false) }
    val qrBitmap = remember(invite.payload) {
        BarcodeEncoder().encodeBitmap(invite.payload, BarcodeFormat.QR_CODE, 640, 640).asImageBitmap()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("家庭邀请二维码") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                FamilyScopeRow("扫码", "自动填入", "服务器与家庭 Wi-Fi")
                FamilyScopeRow("安全", "隐私保护", "截屏与录屏已禁用")
                Image(bitmap = qrBitmap, contentDescription = "家庭邀请二维码", modifier = Modifier.size(240.dp))
                Text(
                    "共享码 ${invite.code} · 有效至 " +
                        java.text.DateFormat.getDateTimeInstance().format(invite.expiresAt),
                    style = LeziTypography.BodyStrong,
                )
                TextButton(onClick = { showPayload = !showPayload }) {
                    Text(if (showPayload) "隐藏完整载荷" else "显示完整载荷（含服务器地址）")
                }
                if (showPayload) {
                    SelectionContainer {
                        Text(
                            invite.payload,
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

@Composable
internal fun LeaveFamilyDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("离开当前家庭？") },
        text = { Text("本机将停止共享并清除家庭服务器、Wi-Fi 与登录会话；NAS 上的家庭记录会保留。") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确认离开", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun DeleteFamilyDialog(
    stage: FamilyDialog.DeleteStage,
    onContinue: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val final = stage == FamilyDialog.DeleteStage.Final
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (final) "最后确认：永久删除" else "删除家庭服务器上的全部数据？") },
        text = {
            Text(
                if (final) "删除后无法恢复。家庭记录、成员凭证与所有日志图片都会从 NAS 清除。"
                else "这会影响全部家庭成员，并删除 NAS 上的记录、成员凭证和媒体文件。",
            )
        },
        confirmButton = {
            TextButton(onClick = if (final) onConfirm else onContinue) {
                Text(if (final) "永久删除家庭数据" else "继续", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun HomeWifiAccessGuideDialog(
    settingsTarget: HomeWifiSettingsTarget,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("允许识别家庭 Wi‑Fi") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
                FamilyScopeRow("权限", "位置权限", "仅用于读取当前 Wi-Fi 名称")
                FamilyScopeRow("系统", "定位服务", "需保持开启，位置不会上传")
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text(
                    when (settingsTarget) {
                        HomeWifiSettingsTarget.AppPermission -> "打开权限设置"
                        HomeWifiSettingsTarget.LocationServices -> "开启定位服务"
                        HomeWifiSettingsTarget.Wifi -> "打开 Wi-Fi 设置"
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } },
    )
}

@Composable
internal fun FamilyMessageDialog(copy: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("提示") },
        text = { Text(copy) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}
