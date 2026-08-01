package com.lezi.babylog.feature.family.wizard

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
import com.lezi.babylog.domain.family.FamilyWizardSnapshot
import com.lezi.babylog.domain.family.FamilyWizardState
import com.lezi.babylog.feature.family.components.FamilyPrimaryCta
import com.lezi.babylog.feature.family.components.FamilyScopeRow
import com.lezi.babylog.feature.family.components.FamilyWizardJoinRole
import com.lezi.babylog.feature.family.components.FamilyWizardMode
import com.lezi.babylog.feature.family.components.FamilyWizardStep
import com.lezi.babylog.feature.family.components.SecureWindowWhileVisible
import com.lezi.babylog.feature.family.components.familyWizardProgress
import com.lezi.babylog.feature.family.components.familyWizardTitle
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

/** Read-only wizard handoff after the endpoint has passed a trusted HTTPS probe. */

@Composable
internal fun FamilyVerifiedEndpointDialog(
    mode: FamilyWizardMode,
    endpoint: String,
    onContinue: () -> Unit,
    onChangeEndpoint: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(familyWizardTitle(mode, FamilyWizardStep.Endpoint)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
                FamilyWizardStepHeader(mode, FamilyWizardStep.Endpoint, endpoint.isNotBlank())
                FamilyScopeRow("本机", "家庭服务器", "受信任的 HTTPS 地址仅存本机，可中断后继续")
                Text(endpoint.ifBlank { "尚未确认家庭服务器" })
                TextButton(onClick = onChangeEndpoint) { Text("重新确认家庭服务器") }
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue, enabled = endpoint.isNotBlank()) { Text("下一步") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("稍后再说") } },
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
    feedback: String? = null,
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
                feedback?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
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
internal fun MemberLoginQrConfirmDialog(
    familyName: String?,
    memberDisplayName: String,
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    feedback: String?,
    submitting: Boolean,
    verificationInProgress: Boolean = false,
    verificationRetryRequired: Boolean = false,
    recoveryRetryRequired: Boolean = false,
    deviceNameEditable: Boolean = true,
    showConfirm: Boolean = true,
    confirmLabel: String = "在这台设备登录",
    title: String = "登录家庭",
    onConfirm: () -> Unit,
    onManualJoin: () -> Unit,
    onDismiss: () -> Unit,
    showManualJoin: Boolean = true,
) {
    com.lezi.babylog.designsystem.MemberLoginQrConfirmSurface(
        familyName = familyName,
        memberDisplayName = memberDisplayName,
        deviceName = deviceName,
        onDeviceNameChange = onDeviceNameChange,
        feedback = feedback,
        submitting = submitting,
        verificationInProgress = verificationInProgress,
        verificationRetryRequired = verificationRetryRequired,
        recoveryRetryRequired = recoveryRetryRequired,
        deviceNameEditable = deviceNameEditable,
        showConfirm = showConfirm,
        confirmLabel = confirmLabel,
        title = title,
        onConfirm = onConfirm,
        onManualJoin = onManualJoin,
        onDismiss = onDismiss,
        showManualJoin = showManualJoin,
    )
}

/** Compatibility overload for tests that still pass a live payload. */

@Composable
internal fun MemberLoginQrConfirmDialog(
    payload: MemberLoginQrPayload,
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    feedback: String?,
    submitting: Boolean,
    verificationInProgress: Boolean = false,
    verificationRetryRequired: Boolean = false,
    recoveryRetryRequired: Boolean = false,
    onLogin: () -> Unit,
    onRetryVerification: () -> Unit = {},
    onRetryRecovery: () -> Unit = {},
    onManualJoin: () -> Unit,
    onDismiss: () -> Unit,
) {
    MemberLoginQrConfirmDialog(
        familyName = payload.familyName,
        memberDisplayName = payload.memberDisplayName,
        deviceName = deviceName,
        onDeviceNameChange = onDeviceNameChange,
        feedback = feedback,
        submitting = submitting,
        verificationInProgress = verificationInProgress,
        verificationRetryRequired = verificationRetryRequired,
        recoveryRetryRequired = recoveryRetryRequired,
        deviceNameEditable = !submitting && !verificationInProgress,
        showConfirm = !verificationInProgress,
        confirmLabel = when {
            submitting -> "同步中…"
            verificationRetryRequired -> "重新确认"
            recoveryRetryRequired -> "重试首次同步"
            else -> "在这台设备登录"
        },
        title = if (verificationInProgress) "正在确认家庭服务器…" else "登录家庭",
        onConfirm = when {
            verificationRetryRequired -> onRetryVerification
            recoveryRetryRequired -> onRetryRecovery
            else -> onLogin
        },
        onManualJoin = onManualJoin,
        onDismiss = onDismiss,
    )
}

