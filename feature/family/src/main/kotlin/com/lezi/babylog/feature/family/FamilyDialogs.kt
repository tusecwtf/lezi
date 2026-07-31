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
import androidx.compose.material3.Button
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.dismissKeyboardOnTap
import com.lezi.babylog.domain.FamilyWizardSnapshot
import com.lezi.babylog.domain.FamilyWizardState
import com.lezi.babylog.sync.CertificateTrustCandidate
import com.lezi.babylog.sync.PendingMemberLogin
import com.lezi.babylog.sync.MemberLoginQrPayload
import com.lezi.babylog.sync.MemberLoginQrPayloadCodec
import com.lezi.babylog.sync.SetupProbeResult

@Composable
internal fun FamilyEndpointConnectionDialog(
    state: FamilyWizardState,
    endpointDraft: String,
    onEndpointDraftChange: (String) -> Unit,
    onConnect: () -> Unit,
    onTrustCertificate: (CertificateTrustCandidate) -> Unit,
    onContinue: (FamilyWizardSnapshot) -> Unit,
    onForget: () -> Unit,
    onReturnToAddress: () -> Unit,
    onKeepOffline: () -> Unit,
) {
    val probing = state is FamilyWizardState.ProbingEndpoint
    val approval = state as? FamilyWizardState.CertificateApprovalRequired
    val ready = state as? FamilyWizardState.EndpointReady
    val failure = state as? FamilyWizardState.EndpointFailure
    val certificateChanged = failure?.reason == SetupProbeResult.Failed.CertificateChanged
    AlertDialog(
        onDismissRequest = onKeepOffline,
        title = {
            Text(
                when {
                    probing -> "正在确认家庭服务器…"
                    approval != null -> "确认家庭服务器证书"
                    certificateChanged -> "服务器安全信息已变化"
                    ready?.snapshot?.mode == FamilyWizardMode.Create -> "这里还没有家庭"
                    ready != null -> "已找到家庭"
                    failure != null -> failure.message
                    else -> "连接家庭服务器"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
                if (approval != null) {
                    Text("这个服务器的证书尚未被手机系统认识。")
                    Text("请向部署服务器的人确认以下指纹。首次确认仍存在连接到错误服务器的风险。")
                    SelectionContainer {
                        Text(approval.candidate.fingerprint)
                    }
                } else if (certificateChanged) {
                    Text("已固定的服务器公钥与当前连接不一致。为保护登录凭证，连接已停止。")
                } else if (ready == null) {
                    Text("请输入部署乐记家庭后台的完整 HTTPS 地址")
                    OutlinedTextField(
                        value = endpointDraft,
                        onValueChange = onEndpointDraftChange,
                        enabled = !probing,
                        label = { Text("https://family.example.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(ready.endpoint.origin)
                }
                failure?.let {
                    Text(it.message, color = MaterialTheme.colorScheme.error)
                }
                if (endpointDraft.isNotBlank() && ready == null && approval == null && !certificateChanged) {
                    TextButton(enabled = !probing, onClick = onForget) {
                        Text("忘记此服务器")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !probing &&
                    (approval != null || certificateChanged || ready != null || endpointDraft.isNotBlank()),
                onClick = {
                    when {
                        approval != null -> onTrustCertificate(approval.candidate)
                        certificateChanged -> onForget()
                        ready != null -> onContinue(ready.snapshot)
                        else -> onConnect()
                    }
                },
            ) {
                Text(
                    when {
                        approval != null -> "信任此证书"
                        certificateChanged -> "忘记此服务器并重新连接"
                        ready?.snapshot?.mode == FamilyWizardMode.Create -> "新建家庭"
                        ready?.snapshot?.mode == FamilyWizardMode.Join -> "加入家庭"
                        else -> if (probing) "正在连接…" else "连接"
                    },
                )
            }
        },
        dismissButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (approval != null) {
                    TextButton(onClick = onReturnToAddress) {
                        Text("返回修改地址")
                    }
                }
                TextButton(onClick = onKeepOffline) {
                    Text(if (certificateChanged) "返回账户" else "暂不连接，保持离线")
                }
            }
        },
    )
}

@Composable
private fun FamilyWizardStepHeader(
    mode: FamilyWizardMode,
    step: FamilyWizardStep,
    endpointConfigured: Boolean,
) {
    val (step1, step2) = familyWizardProgress(mode, step, endpointConfigured)
    Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xs)) {
        Text(
            step1 + "  ·  " + step2,
            style = LeziTypography.Meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Wizard endpoint step: trusted HTTPS host and port. */
@Composable
internal fun FamilyWizardEndpointDialog(
    mode: FamilyWizardMode,
    host: String,
    onHostChange: (String) -> Unit,
    port: String,
    onPortChange: (String) -> Unit,
    endpointReady: Boolean,
    feedback: String?,
    endpointInfoHint: String? = null,
    saving: Boolean,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text(familyWizardTitle(mode, FamilyWizardStep.Endpoint)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .dismissKeyboardOnTap(),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                FamilyWizardStepHeader(mode, FamilyWizardStep.Endpoint, endpointReady)
                FamilyScopeRow("本机", "家庭服务器", "受信任的 HTTPS 地址仅存本机，可中断后继续")
                if (endpointInfoHint != null) {
                    Text(
                        endpointInfoHint,
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
internal fun FamilyJoinRoleDialog(
    busy: Boolean,
    onOwner: () -> Unit,
    onMember: () -> Unit,
    onBackToEndpoint: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("你要如何加入？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
                Text("已找到配置完成的家庭。请选择这台设备使用的身份。")
                Button(
                    onClick = onOwner,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("我是家庭管理员") }
                OutlinedButton(
                    onClick = onMember,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("我是家庭成员") }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onBackToEndpoint, enabled = !busy) { Text("上一步") }
        },
    )
}

@Composable
internal fun OwnerLoginDialog(
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    deviceNameError: String?,
    rootPassword: String,
    onRootPasswordChange: (String) -> Unit,
    feedback: String?,
    submitting: Boolean,
    onLogin: () -> Unit,
    onTakeover: () -> Unit,
    onBackToRole: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        modifier = Modifier.imePadding(),
        properties = DialogProperties(
            decorFitsSystemWindows = false,
            securePolicy = SecureFlagPolicy.SecureOn,
        ),
        title = { Text("管理员登录") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .dismissKeyboardOnTap(),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                Text("登录会新增一台管理员设备，已有管理员设备不会退出。")
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = onDeviceNameChange,
                    label = { Text("设备称呼") },
                    supportingText = { deviceNameError?.let { Text(it) } },
                    isError = deviceNameError != null,
                    enabled = !submitting,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = rootPassword,
                    onValueChange = onRootPasswordChange,
                    label = { Text("管理员根密码") },
                    supportingText = { Text(feedback ?: "与 NAS 部署根密码一致；不会保存") },
                    isError = feedback != null,
                    enabled = !submitting,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = onTakeover,
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("丢失设备并接管…") }
            }
        },
        confirmButton = {
            TextButton(onClick = onLogin, enabled = !submitting) {
                Text(if (submitting) "正在登录…" else "登录这台设备")
            }
        },
        dismissButton = {
            TextButton(onClick = onBackToRole, enabled = !submitting) { Text("上一步") }
        },
    )
}

@Composable
internal fun OwnerTakeoverConfirmationDialog(
    submitting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("接管管理员身份？") },
        text = {
            Text("所有旧管理员设备都会退出家庭；普通成员不会退出。只有确定旧设备已丢失时才使用。")
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !submitting) {
                Text(if (submitting) "正在接管…" else "确认接管")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消") }
        },
    )
}

@Composable
internal fun MemberLoginRequestDialog(
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    displayNameError: String?,
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    deviceNameError: String?,
    submitting: Boolean,
    onConfirm: () -> Unit,
    onBackToRole: () -> Unit,
    onKeepOffline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!submitting) onKeepOffline() },
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text("申请在这台设备登录") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .dismissKeyboardOnTap(),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = onDisplayNameChange,
                    label = { Text("我的家庭称呼") },
                    supportingText = {
                        Text(displayNameError ?: "家庭成员会用这个称呼认出你")
                    },
                    isError = displayNameError != null,
                    enabled = !submitting,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = onDeviceNameChange,
                    label = { Text("这台设备的名称") },
                    supportingText = {
                        Text(deviceNameError ?: "默认取自 Android 设备名，可修改")
                    },
                    isError = deviceNameError != null,
                    enabled = !submitting,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "管理员会看到你的申请，并决定是否用这个称呼添加新成员。",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onKeepOffline, enabled = !submitting) {
                    Text("暂不连接，保持离线")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !submitting && displayName.isNotBlank() && deviceName.isNotBlank(),
            ) {
                Text(if (submitting) "正在发送…" else "发送确认请求")
            }
        },
        dismissButton = {
            TextButton(onClick = onBackToRole, enabled = !submitting) { Text("上一步") }
        },
    )
}

@Composable
internal fun MemberApprovalWaitingDialog(
    request: PendingMemberLogin,
    checking: Boolean,
    onCheck: () -> Unit,
    onCancel: () -> Unit,
    onKeepOffline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!checking) onKeepOffline() },
        title = { Text("等待管理员确认") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
                Text("已申请：${request.displayName}")
                Text("设备：${request.deviceName}")
                Text(
                    "申请将在 24 小时内失效。管理员下次前台打开 App 后可以批准或拒绝。",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onCancel, enabled = !checking) { Text("取消申请") }
                TextButton(onClick = onKeepOffline, enabled = !checking) {
                    Text("暂不连接，保持离线")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCheck, enabled = !checking) {
                Text(if (checking) "正在检查…" else "检查结果")
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
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    deviceNameError: String?,
    bootstrapSecret: String,
    onBootstrapSecretChange: (String) -> Unit,
    feedback: String?,
    creating: Boolean,
    endpointConfigured: Boolean = true,
    showWizardChrome: Boolean = true,
    onBackToEndpoint: (() -> Unit)? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!creating) onDismiss() },
        modifier = Modifier.imePadding(),
        properties = DialogProperties(
            decorFitsSystemWindows = false,
            securePolicy = SecureFlagPolicy.SecureOn,
        ),
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
                        endpointConfigured,
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
                FamilyScopeRow("家庭", "共享家庭名", "必填")
                OutlinedTextField(
                    value = familyName,
                    onValueChange = onFamilyNameChange,
                    label = { Text("家庭名") },
                    placeholder = { Text("如：乐乐一家") },
                    supportingText = {
                        Text(familyNameError ?: "全员看到同一个名字")
                    },
                    isError = familyNameError != null,
                    enabled = !creating,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = onDeviceNameChange,
                    label = { Text("设备称呼") },
                    supportingText = { Text(deviceNameError ?: "默认取自 Android 设备名，可修改") },
                    isError = deviceNameError != null,
                    enabled = !creating,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FamilyScopeRow("一次", "服务器初始化", "根密码仅用于本次建家")
                OutlinedTextField(
                    value = bootstrapSecret,
                    onValueChange = onBootstrapSecretChange,
                    label = { Text("管理员根密码") },
                    supportingText = {
                        Text(feedback ?: "与 NAS 部署根密码一致；不会保存")
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
                Text(if (creating) "创建中…" else "新建并登录")
            }
        },
        dismissButton = {
            if (onBackToEndpoint != null) {
                TextButton(onClick = onBackToEndpoint, enabled = !creating) { Text("上一步") }
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
    title: String = "修改我的家庭称呼",
    fieldLabel: String = "我是宝宝的？",
    supportingCopy: String = "只改自己的称呼，不能改其他家人",
    confirmCopy: String = "保存",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.dismissKeyboardOnTap(),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = onDisplayNameChange,
                    label = { Text(fieldLabel) },
                    placeholder = { Text("如：妈妈、干妈、月嫂小王") },
                    supportingText = {
                        Text(feedback ?: supportingCopy)
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
                Text(if (saving) "保存中…" else confirmCopy)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
        },
    )
}

@Composable
internal fun MemberLoginQrCodeDialog(
    payload: MemberLoginQrPayload,
    onDismiss: () -> Unit,
) {
    SecureWindowWhileVisible()
    val qrBitmap = remember(payload) {
        BarcodeEncoder().encodeBitmap(
            MemberLoginQrPayloadCodec.encode(payload),
            BarcodeFormat.QR_CODE,
            640,
            640,
        ).asImageBitmap()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("成员登录二维码") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                Image(
                    bitmap = qrBitmap,
                    contentDescription =
                        "成员登录二维码，已授权${payload.memberDisplayName}在十分钟内登录一台新设备",
                    modifier = Modifier.size(240.dp),
                )
                Text("已授权：${payload.memberDisplayName}", style = LeziTypography.BodyStrong)
                Text(
                    "十分钟内有效，只可成功登录一次。二维码包含家庭服务器信任配置，请仅当面分享。",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

@Composable
internal fun MemberLoginQrConfirmDialog(
    payload: MemberLoginQrPayload,
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    feedback: String?,
    submitting: Boolean,
    onLogin: () -> Unit,
    onManualJoin: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("登录家庭") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
                payload.familyName?.let { Text(it, style = LeziTypography.TitleSm) }
                Text("已由家庭管理员授权：${payload.memberDisplayName}")
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = onDeviceNameChange,
                    enabled = !submitting,
                    label = { Text("这台设备的名称 *") },
                    singleLine = true,
                    isError = feedback != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("将信任管理员提供的家庭服务器配置。")
                feedback?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onManualJoin, enabled = !submitting) {
                    Text("改用加入家庭")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onLogin, enabled = !submitting) {
                Text(if (submitting) "登录中…" else "在这台设备登录")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消") }
        },
    )
}

@Composable
internal fun LeaveFamilyDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("退出家庭？") },
        text = {
            Text(
                "服务器确认后，你的家庭成员身份、全部设备和登录信息会被彻底删除，本机家庭记录、待同步内容、照片和服务器信任也会清除。已同步的家庭事实继续保留，作者显示为“家人”。此操作无法撤销。",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("退出家庭", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun LogoutCurrentDeviceDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("退出这台设备？") },
        text = {
            Text(
                "服务器确认退出后，本机会清除家庭记录、待同步内容、照片、服务器信任和登录信息。家庭成员身份及其他设备不受影响。",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("退出这台设备", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun RevokeFamilyDeviceDialog(
    deviceName: String,
    isCurrent: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isCurrent) "撤销这台设备？" else "撤销「$deviceName」？") },
        text = {
            Text(
                if (isCurrent) {
                    "服务器确认后，本机会立即进入可恢复清理；该成员身份及其他设备不受影响。"
                } else {
                    "这只会撤销该设备的凭证。设备离线时不会即时收到通知；下次连接家庭服务器后才会清除其本地家庭数据。"
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确认撤销", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun DeleteFamilyDialog(
    stage: FamilyDialog.DeleteStage,
    expectedFamilyName: String,
    familyNameInput: String,
    onFamilyNameInputChange: (String) -> Unit,
    rootPassword: String,
    onRootPasswordChange: (String) -> Unit,
    errorMessage: String?,
    deleting: Boolean,
    onContinue: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val final = stage == FamilyDialog.DeleteStage.Final
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(if (final) "删除整个家庭" else "删除家庭服务器上的全部数据？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
                Text(
                    if (final) {
                        "这会永久删除服务器上的全部家庭记录、计划、照片、成员和设备，且无法恢复。其他设备下次连接时也会退出。"
                    } else {
                        "这会影响全部家庭成员，并删除服务器上的全部家庭数据。服务器确认前不会清除本机。"
                    },
                )
                if (final) {
                    OutlinedTextField(
                        value = familyNameInput,
                        onValueChange = onFamilyNameInputChange,
                        label = { Text("输入家庭名：$expectedFamilyName") },
                        singleLine = true,
                        isError = familyNameInput.isNotEmpty() &&
                            familyNameInput.trim() != expectedFamilyName.trim(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = rootPassword,
                        onValueChange = onRootPasswordChange,
                        label = { Text("管理员根密码") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    errorMessage?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = LeziTypography.Meta,
                            modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Assertive
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = if (final) onConfirm else onContinue,
                enabled = !deleting && (
                    !final || canConfirmFamilyDeletion(
                        expectedFamilyName,
                        familyNameInput,
                        rootPassword,
                    )
                ),
            ) {
                Text(
                    if (final) {
                        if (deleting) "正在删除…" else "永久删除家庭"
                    } else {
                        "继续"
                    },
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
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
