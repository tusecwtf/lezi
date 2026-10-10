package com.lezi.babylog.feature.family.wizard

import androidx.compose.foundation.Image
import com.lezi.babylog.core.common.WizardTrustCopy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import com.lezi.babylog.designsystem.LeziAlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziShapes
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.dismissKeyboardOnTap
import com.lezi.babylog.designsystem.LeziTextButton
import com.lezi.babylog.designsystem.LeziTextButtonTone
import com.lezi.babylog.designsystem.LeziTextField
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
import com.lezi.babylog.sync.session.CertificateTrustCandidate
import com.lezi.babylog.sync.PendingMemberLogin
import com.lezi.babylog.sync.qr.MemberLoginQrPayloadCodec
import com.lezi.babylog.core.common.failure.FailureKind
import com.lezi.babylog.core.common.failure.failureExplanation
import com.lezi.babylog.sync.session.SetupProbeResult
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
    LeziAlertDialog(
        onDismissRequest = onKeepOffline,
        title = {
            Text(
                when {
                    probing -> "正在确认家庭服务器…"
                    approval != null -> "确认家庭服务器证书"
                    certificateChanged ->
                        failureExplanation(FailureKind.CertificateChanged).dialogTitle
                    ready?.snapshot?.mode == FamilyWizardMode.Create -> "这里还没有家庭"
                    ready != null -> "已找到家庭"
                    failure != null -> "连接失败"
                    else -> "连接家庭服务器"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
                if (approval != null) {
                    Text("这个服务器的证书尚未被手机系统认识。")
                    Text(WizardTrustCopy.FIRST_TRUST_HINT)
                    SelectionContainer {
                        Text(approval.candidate.fingerprint)
                    }
                } else if (certificateChanged) {
                    Text("已固定的服务器公钥与当前连接不一致。为保护登录凭证，连接已停止。")
                } else if (ready == null) {
                    Text("请输入部署乐记家庭后台的完整 HTTPS 地址")
                    LeziTextField(
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
                    LeziTextButton(label = "忘记此服务器", onClick = onForget, enabled = !probing)
                }
            }
        },
        confirmButton = {
            LeziTextButton(
                label = when {
                    approval != null -> "信任此证书"
                    certificateChanged -> "忘记此服务器并重新连接"
                    ready?.snapshot?.mode == FamilyWizardMode.Create -> "新建家庭"
                    ready?.snapshot?.mode == FamilyWizardMode.Join -> "加入家庭"
                    else -> if (probing) "正在连接…" else "连接"
                },
                onClick = {
                    when {
                        approval != null -> onTrustCertificate(approval.candidate)
                        certificateChanged -> onForget()
                        ready != null -> onContinue(ready.snapshot)
                        else -> onConnect()
                    }
                },
                enabled = !probing,
                tone = LeziTextButtonTone.Primary,
            )
        },
        dismissButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (approval != null) {
                    LeziTextButton(label = "返回修改地址", onClick = onReturnToAddress)
                }
                LeziTextButton(
                    label = if (certificateChanged) "返回账户" else "暂不连接，保持离线",
                    onClick = onKeepOffline,
                )
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
    val (step1, step2) = familyWizardProgress(mode, endpointConfigured)
    Row(horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Xs)) {
        WizardStepChip(label = step1, active = step == FamilyWizardStep.Endpoint)
        WizardStepChip(label = step2, active = step != FamilyWizardStep.Endpoint)
    }
}

@Composable
private fun WizardStepChip(label: String, active: Boolean) {
    Surface(
        shape = LeziShapes.Pill,
        color = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (active) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Text(
            label,
            style = LeziTypography.Meta,
            modifier = Modifier.padding(
                horizontal = LeziSpacing.Sm,
                vertical = LeziSpacing.Xxs,
            ),
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
    LeziAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(familyWizardTitle(mode, FamilyWizardStep.Endpoint)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
                FamilyWizardStepHeader(mode, FamilyWizardStep.Endpoint, endpoint.isNotBlank())
                FamilyScopeRow("本机", "家庭服务器", "受信任的 HTTPS 地址仅存本机，可中断后继续")
                Text(endpoint.ifBlank { "尚未确认家庭服务器" })
                LeziTextButton(label = "重新确认家庭服务器", onClick = onChangeEndpoint)
            }
        },
        confirmButton = {
            LeziTextButton(label = "下一步", onClick = onContinue, enabled = endpoint.isNotBlank(), tone = LeziTextButtonTone.Primary)
        },
        dismissButton = { LeziTextButton(label = "稍后再说", onClick = onDismiss) },
    )
}


@Composable
internal fun FamilyJoinRoleDialog(
    busy: Boolean,
    onOwner: () -> Unit,
    onMember: () -> Unit,
    onScanMemberLoginQr: () -> Unit,
    onBackToEndpoint: () -> Unit,
    onDismiss: () -> Unit,
) {
    LeziAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("你要如何加入？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
                Text("已找到配置完成的家庭。请选择这台设备使用的身份。")
                LeziPrimaryButton(
                    "我是家庭管理员",
                    onClick = onOwner,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                LeziSecondaryButton(
                    "我是家庭成员",
                    onClick = onMember,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                LeziSecondaryButton(
                    "扫描成员登录二维码",
                    onClick = onScanMemberLoginQr,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            LeziTextButton(label = "上一步", onClick = onBackToEndpoint, enabled = !busy)
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
    LeziAlertDialog(
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
                    .heightIn(max = LeziSpacing.DialogContentMax)
                    .verticalScroll(rememberScrollState())
                    .dismissKeyboardOnTap(),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                Text("登录会新增一台管理员设备，已有管理员设备不会退出。")
                LeziTextField(
                    value = deviceName,
                    onValueChange = onDeviceNameChange,
                    label = { Text("设备称呼") },
                    supportingText = { deviceNameError?.let { Text(it) } },
                    isError = deviceNameError != null,
                    enabled = !submitting,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LeziTextField(
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
                LeziTextButton(label = "丢失设备并接管…", onClick = onTakeover, enabled = !submitting, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            LeziTextButton(label = if (submitting) "正在登录…" else "登录这台设备", onClick = onLogin, enabled = !submitting)
        },
        dismissButton = {
            LeziTextButton(label = "上一步", onClick = onBackToRole, enabled = !submitting)
        },
    )
}


/** Dangerous owner-takeover copy — pure seam for JVM chrome tests. */
internal object OwnerTakeoverChrome {
    const val TITLE = "接管管理员身份？"
    const val BODY =
        "所有旧管理员设备都会退出家庭；普通成员不会退出。只有确定旧设备已丢失时才使用。"
    const val CONFIRM = "确认接管"
    const val CONFIRM_BUSY = "正在接管…"
    const val CANCEL = "取消"

    fun confirmLabel(submitting: Boolean): String =
        if (submitting) CONFIRM_BUSY else CONFIRM
}

@Composable
internal fun OwnerTakeoverConfirmationDialog(
    submitting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    LeziAlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(OwnerTakeoverChrome.TITLE) },
        text = {
            Text(OwnerTakeoverChrome.BODY)
        },
        confirmButton = {
            LeziTextButton(
                label = OwnerTakeoverChrome.confirmLabel(submitting),
                onClick = onConfirm,
                enabled = !submitting,
            )
        },
        dismissButton = {
            LeziTextButton(
                label = OwnerTakeoverChrome.CANCEL,
                onClick = onDismiss,
                enabled = !submitting,
            )
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
    LeziAlertDialog(
        onDismissRequest = { if (!submitting) onKeepOffline() },
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text("申请在这台设备登录") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = LeziSpacing.DialogContentMax)
                    .verticalScroll(rememberScrollState())
                    .dismissKeyboardOnTap(),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                LeziTextField(
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
                LeziTextField(
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
                LeziTextButton(label = "暂不连接，保持离线", onClick = onKeepOffline, enabled = !submitting)
            }
        },
        confirmButton = {
            LeziTextButton(label = if (submitting) "正在发送…" else "发送确认请求", onClick = onConfirm, enabled = !submitting && displayName.isNotBlank() && deviceName.isNotBlank())
        },
        dismissButton = {
            LeziTextButton(label = "上一步", onClick = onBackToRole, enabled = !submitting)
        },
    )
}


@Composable
internal fun MemberApprovalWaitingDialog(
    request: PendingMemberLogin,
    busy: Boolean,
    cancelling: Boolean,
    feedback: String? = null,
    onCheck: () -> Unit,
    onCancel: () -> Unit,
    onKeepOffline: () -> Unit,
) {
    LeziAlertDialog(
        onDismissRequest = { if (!busy) onKeepOffline() },
        title = { Text(if (request.remoteOutcomeUnknown) "申请结果待确认" else "等待管理员确认") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
                Text("家庭称呼：${request.displayName}")
                Text("设备：${request.deviceName}")
                Text(
                    if (request.remoteOutcomeUnknown) {
                        com.lezi.babylog.sync.MemberLoginOutcomeUnknownException(request).message.orEmpty()
                    } else "申请将在 24 小时内失效。管理员下次前台打开 App 后可以批准或拒绝。",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                feedback?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                LeziTextButton(label = "在这台设备放弃等待", onClick = onCancel, enabled = !busy)
                LeziTextButton(label = "暂不连接，保持离线", onClick = onKeepOffline, enabled = !busy)
            }
        },
        confirmButton = {
            LeziTextButton(label = when {
                        cancelling -> "正在取消…"
                        busy -> "正在检查…"
                        else -> "检查结果"
                    }, onClick = onCheck, enabled = !busy && !request.remoteOutcomeUnknown)
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
    LeziAlertDialog(
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
                    .heightIn(max = LeziSpacing.DialogContentMax)
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
                LeziTextField(
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
                LeziTextField(
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
                LeziTextField(
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
                LeziTextField(
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
            LeziTextButton(label = if (creating) "创建中…" else "新建并登录", onClick = onConfirm, enabled = !creating)
        },
        dismissButton = {
            if (onBackToEndpoint != null) {
                LeziTextButton(label = "上一步", onClick = onBackToEndpoint, enabled = !creating)
            } else {
                LeziTextButton(label = "取消", onClick = onDismiss, enabled = !creating)
            }
        },
    )
}
