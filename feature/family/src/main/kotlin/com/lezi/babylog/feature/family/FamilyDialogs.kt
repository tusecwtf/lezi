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
private fun FamilyWizardStepHeader(
    mode: FamilyWizardMode,
    step: FamilyWizardStep,
    networkConfigured: Boolean,
) {
    val (step1, step2) = familyWizardProgress(mode, step, networkConfigured)
    Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xs)) {
        Text(
            step1 + "  ·  " + step2,
            style = LeziTypography.Meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Wizard network step: host / port / ≤2 SSIDs.
 * Join mode may expose scan + invite chip; Create keeps save-then-advance.
 */
@Composable
internal fun FamilyWizardNetworkDialog(
    mode: FamilyWizardMode,
    host: String,
    onHostChange: (String) -> Unit,
    port: String,
    onPortChange: (String) -> Unit,
    ssid1: String,
    onSsid1Change: (String) -> Unit,
    ssid2: String,
    onSsid2Change: (String) -> Unit,
    networkReady: Boolean,
    feedback: String?,
    networkInfoHint: String? = null,
    inviteCodeSummary: String? = null,
    saving: Boolean,
    onUseCurrentWifi: () -> Unit,
    onScan: (() -> Unit)? = null,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text(familyWizardTitle(mode, FamilyWizardStep.Network)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .dismissKeyboardOnTap(),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                FamilyWizardStepHeader(mode, FamilyWizardStep.Network, networkReady)
                FamilyScopeRow("本机", "家庭网络", "服务器与 Wi‑Fi 仅存本机，可中断后继续")
                if (inviteCodeSummary != null) {
                    Text(
                        "邀请码已填 · $inviteCodeSummary",
                        style = LeziTypography.BodyStrong,
                    )
                }
                if (networkInfoHint != null) {
                    Text(
                        networkInfoHint,
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = host,
                    onValueChange = onHostChange,
                    label = { Text("家庭服务器主机（IP 或域名）") },
                    placeholder = { Text("192.168.50.4") },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { onPortChange(it.filter(Char::isDigit).take(5)) },
                    label = { Text("端口") },
                    placeholder = { Text("8765") },
                    singleLine = true,
                    enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ssid1,
                    onValueChange = onSsid1Change,
                    label = { Text("家庭 Wi‑Fi 名称 1（如 2.4G）") },
                    placeholder = { Text("当前连接的 Wi‑Fi 名") },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ssid2,
                    onValueChange = onSsid2Change,
                    label = { Text("家庭 Wi‑Fi 名称 2（可选，如 5G）") },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = onUseCurrentWifi,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("填入当前 Wi‑Fi 名称")
                }
                if (onScan != null) {
                    OutlinedButton(
                        onClick = onScan,
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Outlined.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("扫码填入邀请与家庭网络")
                    }
                }
                if (feedback != null) {
                    Text(feedback, color = MaterialTheme.colorScheme.error, style = LeziTypography.Meta)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue, enabled = !saving) {
                Text(
                    when {
                        saving -> "保存中…"
                        mode == FamilyWizardMode.Join -> "下一步"
                        else -> "下一步"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("稍后再说") }
        },
    )
}

@Composable
internal fun JoinFamilyDialog(
    joinCode: String,
    onJoinCodeChange: (String) -> Unit,
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    displayNameError: String?,
    joining: Boolean,
    networkReady: Boolean = true,
    networkSummary: String? = null,
    networkMissingHint: String? = null,
    inviteFieldError: String? = null,
    confirmEnabled: Boolean = true,
    showWizardChrome: Boolean = true,
    onScan: () -> Unit,
    onBackToNetwork: (() -> Unit)? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!joining) onDismiss() },
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text(FamilyPrimaryCta.JOIN) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .dismissKeyboardOnTap(),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                if (showWizardChrome) {
                    FamilyWizardStepHeader(
                        FamilyWizardMode.Join,
                        FamilyWizardStep.Identity,
                        networkReady,
                    )
                }
                FamilyScopeRow("共享", "家庭数据", "宝宝档案、照护记录、日志图片")
                FamilyScopeRow("本机", "个人偏好", "主题、提醒、桌面小组件")
                if (networkSummary != null) {
                    Text(networkSummary, style = LeziTypography.Meta)
                } else if (networkMissingHint != null) {
                    Text(
                        networkMissingHint,
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = displayName,
                    onValueChange = onDisplayNameChange,
                    label = { Text("我是宝宝的？") },
                    placeholder = { Text("如：妈妈、干妈、月嫂小王") },
                    supportingText = {
                        Text(displayNameError ?: "家庭称呼，必填；家人用这个认出你")
                    },
                    isError = displayNameError != null,
                    enabled = !joining,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = joinCode,
                    onValueChange = onJoinCodeChange,
                    label = { Text("邀请码") },
                    placeholder = { Text("输入共享码，或使用下方扫码") },
                    supportingText = inviteFieldError?.let { { Text(it) } },
                    isError = inviteFieldError != null,
                    singleLine = true,
                    enabled = !joining,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = onScan, enabled = !joining, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("扫码填入邀请与家庭网络")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled && !joining) {
                Text(if (joining) "正在加入…" else "加入")
            }
        },
        dismissButton = {
            if (onBackToNetwork != null) {
                TextButton(onClick = onBackToNetwork, enabled = !joining) { Text("上一步") }
            } else {
                TextButton(onClick = onDismiss, enabled = !joining) { Text("取消") }
            }
        },
    )
}

@Composable
internal fun CreateFamilyDialog(
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    displayNameError: String?,
    familyName: String,
    onFamilyNameChange: (String) -> Unit,
    familyNameError: String?,
    bootstrapSecret: String,
    onBootstrapSecretChange: (String) -> Unit,
    feedback: String?,
    creating: Boolean,
    networkConfigured: Boolean = true,
    showWizardChrome: Boolean = true,
    onBackToNetwork: (() -> Unit)? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!creating) onDismiss() },
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text(FamilyPrimaryCta.CREATE) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .dismissKeyboardOnTap(),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                if (showWizardChrome) {
                    FamilyWizardStepHeader(
                        FamilyWizardMode.Create,
                        FamilyWizardStep.Identity,
                        networkConfigured,
                    )
                }
                FamilyScopeRow("称呼", "家庭身份", "必填自由文本，不是关系芯片")
                OutlinedTextField(
                    value = displayName,
                    onValueChange = onDisplayNameChange,
                    label = { Text("我是宝宝的？") },
                    placeholder = { Text("如：妈妈、爸爸、姥姥") },
                    supportingText = {
                        Text(displayNameError ?: "家庭称呼，必填；家人用这个认出你")
                    },
                    isError = displayNameError != null,
                    enabled = !creating,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FamilyScopeRow("家庭", "共享家庭名", "可空；空则显示「我的家庭」")
                OutlinedTextField(
                    value = familyName,
                    onValueChange = onFamilyNameChange,
                    label = { Text("家庭名（可选）") },
                    placeholder = { Text("如：乐乐一家") },
                    supportingText = {
                        Text(familyNameError ?: "全员看到同一个名字；不填也可建家")
                    },
                    isError = familyNameError != null,
                    enabled = !creating,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FamilyScopeRow("一次", "服务器初始化", "可空；有口令时仅用于本次建家")
                OutlinedTextField(
                    value = bootstrapSecret,
                    onValueChange = onBootstrapSecretChange,
                    label = { Text("服务器初始化口令（可选）") },
                    supportingText = {
                        Text(feedback ?: "与 NAS 部署口令一致；未设置时可留空")
                    },
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
            if (onBackToNetwork != null) {
                TextButton(onClick = onBackToNetwork, enabled = !creating) { Text("上一步") }
            } else {
                TextButton(onClick = onDismiss, enabled = !creating) { Text("取消") }
            }
        },
    )
}

@Composable
internal fun RenameFamilyDialog(
    familyName: String,
    onFamilyNameChange: (String) -> Unit,
    feedback: String?,
    saving: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text("修改家庭名") },
        text = {
            Column(
                modifier = Modifier.dismissKeyboardOnTap(),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                OutlinedTextField(
                    value = familyName,
                    onValueChange = onFamilyNameChange,
                    label = { Text("共享家庭名") },
                    placeholder = { Text("留空则显示兜底名") },
                    supportingText = {
                        Text(feedback ?: "仅管理员可改；全员设备看到同一个名字")
                    },
                    isError = feedback != null,
                    enabled = !saving,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !saving) {
                Text(if (saving) "保存中…" else "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
        },
    )
}

@Composable
internal fun EditMyDisplayNameDialog(
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    feedback: String?,
    saving: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text("修改我的家庭称呼") },
        text = {
            Column(
                modifier = Modifier.dismissKeyboardOnTap(),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = onDisplayNameChange,
                    label = { Text("我是宝宝的？") },
                    placeholder = { Text("如：妈妈、干妈、月嫂小王") },
                    supportingText = {
                        Text(feedback ?: "只改自己的称呼，不能改其他家人")
                    },
                    isError = feedback != null,
                    enabled = !saving,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !saving) {
                Text(if (saving) "保存中…" else "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
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
