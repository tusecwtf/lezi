package com.lezi.babylog.feature.onboarding.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.model.birthWeightValidationError
import com.lezi.babylog.core.model.limitBabyNicknameInput
import com.lezi.babylog.core.ui.UiTags
import com.lezi.babylog.designsystem.LeziBabyTheme
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.normalizeBabyThemeArgb
import com.lezi.babylog.domain.family.FamilyWizardMode
import com.lezi.babylog.domain.family.FamilyWizardState
import com.lezi.babylog.feature.onboarding.OnboardingCreateBabySource
import com.lezi.babylog.feature.onboarding.onboardingChooseFamilyBody
import com.lezi.babylog.feature.onboarding.onboardingConnectFamilyAction
import com.lezi.babylog.feature.onboarding.onboardingCreateBabyBody
import com.lezi.babylog.feature.onboarding.onboardingCreateBabyPrimaryPresentation
import com.lezi.babylog.sync.PendingMemberLogin
import com.lezi.babylog.sync.session.CertificateTrustCandidate
import com.lezi.babylog.sync.session.SetupProbeResult
import com.lezi.babylog.sync.session.TrustedEndpointProfile

private val ThemePalette = LeziBabyTheme.PaletteArgb.map { normalizeBabyThemeArgb(it) }
private val ThemePaletteLabels = LeziBabyTheme.Labels

@Composable
internal fun OnboardingChooseFamilyStep(
    verifiedEndpoint: TrustedEndpointProfile?,
    pendingMemberLogin: PendingMemberLogin?,
    onConnectOrResume: () -> Unit,
    onScanMemberLogin: () -> Unit,
    onForgetEndpoint: () -> Unit,
    onOfflineMode: () -> Unit,
) {
    Text(
        onboardingChooseFamilyBody(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    verifiedEndpoint?.let { endpoint ->
        Text("家庭服务器已找到\n${endpoint.origin}")
    }
    if (pendingMemberLogin != null) {
        Text(
            "等待管理员确认",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Button(
        onClick = onConnectOrResume,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Text(
            when {
                pendingMemberLogin != null -> "查看加入申请"
                verifiedEndpoint == null -> onboardingConnectFamilyAction()
                else -> "继续登录"
            },
        )
    }
    if (pendingMemberLogin == null) {
        OutlinedButton(
            onClick = onScanMemberLogin,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
            Spacer(Modifier.size(LeziSpacing.Xs))
            Text("扫描成员登录二维码")
        }
    }
    if (verifiedEndpoint != null) {
        TextButton(
            onClick = onForgetEndpoint,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("忘记此服务器") }
    }
    OutlinedButton(
        onClick = onOfflineMode,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .testTag(UiTags.ONBOARDING_OFFLINE_MODE),
    ) { Text("离线模式") }
}

@Composable
internal fun OnboardingConnectServerStep(
    model: ConnectServerStepModel,
    familyWizardBusy: Boolean,
    endpointDraft: String,
    onEndpointDraftChange: (String) -> Unit,
    onPrimaryAction: () -> Unit,
    onReturnToAddress: () -> Unit,
    onKeepOffline: () -> Unit,
) {
    Text(model.title, style = MaterialTheme.typography.titleMedium)
    when (val primary = model.primary) {
        is ConnectServerPrimary.Trust -> {
            Text("这个服务器的证书尚未被手机系统认识。")
            Text("请向部署服务器的人确认以下指纹。首次确认仍存在连接到错误服务器的风险。")
            SelectionContainer {
                Text(primary.candidate.fingerprint)
            }
        }
        ConnectServerPrimary.ForgetAndReconnect -> {
            Text("已固定的服务器公钥与当前连接不一致。为保护登录凭证，连接已停止。")
        }
        is ConnectServerPrimary.ContinueWithReady -> {
            Text(primary.origin)
        }
        ConnectServerPrimary.Connect -> {
            Text("请输入部署乐记家庭后台的完整 HTTPS 地址")
            OutlinedTextField(
                value = endpointDraft,
                onValueChange = onEndpointDraftChange,
                enabled = !familyWizardBusy,
                label = { Text("https://family.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    model.failureMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(
        enabled = model.primaryEnabled(familyWizardBusy, endpointDraft),
        onClick = onPrimaryAction,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Text(model.primaryLabel(familyWizardBusy))
    }
    if (model.primary is ConnectServerPrimary.Trust) {
        TextButton(
            onClick = onReturnToAddress,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("返回修改地址") }
    }
    TextButton(
        enabled = model.keepOfflineEnabled,
        onClick = onKeepOffline,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(model.keepOfflineLabel)
    }
}

@Composable
internal fun OnboardingCreateFamilyStep(
    verifiedOrigin: String?,
    createFamilyName: String,
    onCreateFamilyNameChange: (String) -> Unit,
    createDisplayName: String,
    onCreateDisplayNameChange: (String) -> Unit,
    createDeviceName: String,
    onCreateDeviceNameChange: (String) -> Unit,
    bootstrapSecret: String,
    onBootstrapSecretChange: (String) -> Unit,
    formError: String?,
    familyWizardBusy: Boolean,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    Text(
        "连接家里的 NAS。若 NAS 已有家庭，同一动作会接回原管理员与历史数据。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text("已确认的家庭服务器", style = MaterialTheme.typography.labelMedium)
    Text(
        verifiedOrigin ?: "尚未确认家庭服务器，请返回重新连接",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = createFamilyName,
        onValueChange = onCreateFamilyNameChange,
        label = { Text("家庭名") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = createDisplayName,
        onValueChange = onCreateDisplayNameChange,
        label = { Text("我的称呼") },
        supportingText = { Text("家庭成员会用这个称呼认出你") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = createDeviceName,
        onValueChange = onCreateDeviceNameChange,
        label = { Text("设备称呼") },
        supportingText = { Text("默认取自 Android 设备名，可修改") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = bootstrapSecret,
        onValueChange = onBootstrapSecretChange,
        label = { Text("管理员根密码") },
        supportingText = { Text("仅用于本次请求，不会保存") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(
        enabled = !familyWizardBusy,
        onClick = onSubmit,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        Text(
            if (familyWizardBusy) {
                "正在连接…"
            } else {
                "新建并登录"
            },
        )
    }
    TextButton(
        enabled = !familyWizardBusy,
        onClick = onBack,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("返回") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OnboardingCreateBabyStep(
    createBabySource: OnboardingCreateBabySource,
    creatingBaby: Boolean,
    name: String,
    onNameChange: (String) -> Unit,
    nameError: Boolean,
    sex: String?,
    onSexChange: (String?) -> Unit,
    dateLabel: String,
    onShowDate: () -> Unit,
    weightText: String,
    onWeightTextChange: (String) -> Unit,
    themeIdx: Int,
    onThemeIdxChange: (Int) -> Unit,
    formError: String?,
    onSubmit: (themeColorArgb: Int, weightGrams: Int?) -> Unit,
    onBack: () -> Unit,
) {
    val primary = onboardingCreateBabyPrimaryPresentation(creatingBaby)
    Text(
        onboardingCreateBabyBody(createBabySource),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text("宝宝昵称") },
        isError = nameError,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(null to "未设置", "female" to "女", "male" to "男")
            .forEach { (value, label) ->
                FilterChip(
                    selected = sex == value,
                    onClick = { onSexChange(value) },
                    label = { Text(label) },
                )
            }
    }
    OutlinedButton(
        onClick = onShowDate,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("生日：$dateLabel") }
    OutlinedTextField(
        value = weightText,
        onValueChange = { onWeightTextChange(it.filter { ch -> ch.isDigit() }) },
        label = { Text("出生体重（克，可选）") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    Text("主题色", style = MaterialTheme.typography.labelLarge)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 4,
    ) {
        ThemePalette.forEachIndexed { index, color ->
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .then(
                        if (themeIdx == index) {
                            Modifier.border(
                                2.dp,
                                MaterialTheme.colorScheme.onSurface,
                                CircleShape,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .selectable(
                        selected = themeIdx == index,
                        role = Role.RadioButton,
                        onClick = { onThemeIdxChange(index) },
                    )
                    .semantics {
                        contentDescription = "主题色：${ThemePaletteLabels[index]}"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(color)),
                )
            }
        }
    }
    formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(
        enabled = primary.enabled,
        onClick = {
            val grams = weightText.toIntOrNull()
            onSubmit(ThemePalette[themeIdx], grams)
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
    ) { Text(primary.label) }
    if (createBabySource == OnboardingCreateBabySource.OfflineMode) {
        TextButton(
            enabled = !creatingBaby,
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("返回") }
    }
}

@Composable
internal fun OnboardingRecoveryPendingStep(
    recoveryFailure: FamilyWizardState.RetryableFailure?,
    familyWizardBusy: Boolean,
    onRetry: () -> Unit,
) {
    Text(
        "家庭身份已接回，但历史数据还没有恢复完成。请确认家庭服务器可访问后重试。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
    if (recoveryFailure?.committedOutcome != null) {
        Text(
            recoveryFailure.message,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Button(
        enabled = !familyWizardBusy,
        onClick = onRetry,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        Text(
            if (familyWizardBusy) {
                "正在恢复…"
            } else {
                "重试恢复"
            },
        )
    }
}

@Composable
internal fun OnboardingRecoveryCompleteStep() {
    Text(
        "家庭与历史宝宝已恢复完成，正在进入家庭记录。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Shared helpers for shell validation of create-baby form. */
internal fun onboardingLimitNickname(input: String): String = limitBabyNicknameInput(input)

internal fun onboardingBirthWeightError(grams: Int?): String? = birthWeightValidationError(grams)

/**
 * Single projection for connect-step titles, primary CTA, and shell action wiring.
 * Labels and [onPrimaryAction] must both consume this so meaning cannot dual-source.
 */
internal data class ConnectServerStepModel(
    val title: String,
    val primary: ConnectServerPrimary,
    val failureMessage: String?,
    val keepOfflineLabel: String,
    val keepOfflineEnabled: Boolean,
) {
    fun primaryLabel(busy: Boolean): String = when (val decision = primary) {
        is ConnectServerPrimary.Trust -> "信任此证书"
        ConnectServerPrimary.ForgetAndReconnect -> "忘记此服务器并重新连接"
        is ConnectServerPrimary.ContinueWithReady -> when (decision.mode) {
            FamilyWizardMode.Create -> "新建家庭"
            FamilyWizardMode.Join -> "加入家庭"
        }
        ConnectServerPrimary.Connect -> if (busy) "正在连接…" else "连接"
    }

    fun primaryEnabled(busy: Boolean, endpointDraft: String): Boolean {
        if (busy) return false
        return when (primary) {
            is ConnectServerPrimary.Trust,
            ConnectServerPrimary.ForgetAndReconnect,
            is ConnectServerPrimary.ContinueWithReady,
            -> true
            ConnectServerPrimary.Connect -> endpointDraft.isNotBlank()
        }
    }
}

internal fun connectServerStepModel(
    familyWizardState: FamilyWizardState,
): ConnectServerStepModel {
    val primary = connectServerPrimaryDecision(familyWizardState)
    val failure = familyWizardState as? FamilyWizardState.EndpointFailure
    val certificateChanged = primary is ConnectServerPrimary.ForgetAndReconnect
    val title = when {
        familyWizardState is FamilyWizardState.ProbingEndpoint -> "正在确认家庭服务器…"
        primary is ConnectServerPrimary.Trust -> "确认家庭服务器证书"
        certificateChanged -> "服务器安全信息已变化"
        primary is ConnectServerPrimary.ContinueWithReady &&
            primary.mode == FamilyWizardMode.Create -> "这里还没有家庭"
        primary is ConnectServerPrimary.ContinueWithReady -> "已找到家庭"
        failure != null -> failure.message
        else -> "连接家庭服务器"
    }
    return ConnectServerStepModel(
        title = title,
        primary = primary,
        failureMessage = failure?.takeUnless { certificateChanged }?.message,
        keepOfflineLabel = if (certificateChanged) "返回" else "暂不连接，保持离线",
        keepOfflineEnabled = familyWizardState !is FamilyWizardState.Submitting,
    )
}

/** Exposed for connect-step primary action wiring without re-deriving state in shell. */
internal fun connectServerPrimaryDecision(
    familyWizardState: FamilyWizardState,
): ConnectServerPrimary {
    val approval = familyWizardState as? FamilyWizardState.CertificateApprovalRequired
    val ready = familyWizardState as? FamilyWizardState.EndpointReady
    val failure = familyWizardState as? FamilyWizardState.EndpointFailure
    val certificateChanged =
        failure?.reason == SetupProbeResult.Failed.CertificateChanged
    return when {
        approval != null -> ConnectServerPrimary.Trust(approval.candidate)
        certificateChanged -> ConnectServerPrimary.ForgetAndReconnect
        ready == null -> ConnectServerPrimary.Connect
        else -> ConnectServerPrimary.ContinueWithReady(
            origin = ready.endpoint.origin,
            mode = ready.snapshot.mode,
        )
    }
}

internal sealed class ConnectServerPrimary {
    data class Trust(val candidate: CertificateTrustCandidate) : ConnectServerPrimary()
    data object ForgetAndReconnect : ConnectServerPrimary()
    data object Connect : ConnectServerPrimary()
    data class ContinueWithReady(
        val origin: String,
        val mode: FamilyWizardMode,
    ) : ConnectServerPrimary()
}
