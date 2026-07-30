package com.lezi.babylog.feature.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.core.model.limitBabyNicknameInput
import com.lezi.babylog.core.model.birthWeightValidationError
import com.lezi.babylog.core.ui.CameraCapture
import com.lezi.babylog.core.ui.HomeWifiAccessGuideDialog
import com.lezi.babylog.core.ui.HomeWifiSettingsAction
import com.lezi.babylog.core.ui.UiTags
import com.lezi.babylog.designsystem.dismissKeyboardOnTap
import com.lezi.babylog.designsystem.LeziDatePicker
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.CreateBabyInput
import com.lezi.babylog.domain.FamilyWizardController
import com.lezi.babylog.domain.FamilyWizardEntry
import com.lezi.babylog.domain.FamilyWizardMode
import com.lezi.babylog.domain.FamilyWizardOutcome
import com.lezi.babylog.domain.FamilyWizardSnapshot
import com.lezi.babylog.domain.FamilyWizardState
import com.lezi.babylog.domain.FamilyWizardStep
import com.lezi.babylog.domain.JoinFamilyUseCase
import com.lezi.babylog.domain.SyncFamilyWizardGateway
import com.lezi.babylog.sync.HomeLanServerConfig
import com.lezi.babylog.sync.HomeWifiPermission
import com.lezi.babylog.sync.JoinFamilyDraft
import com.lezi.babylog.sync.HomeWifiSettingsTarget
import com.lezi.babylog.sync.NetworkState
import com.lezi.babylog.sync.SyncPort
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val ThemePalette = com.lezi.babylog.designsystem.LeziBabyTheme.PaletteArgb.map {
    com.lezi.babylog.designsystem.normalizeBabyThemeArgb(it)
}
private val ThemePaletteLabels = com.lezi.babylog.designsystem.LeziBabyTheme.Labels

internal enum class OnboardingStep {
    ChooseFamily,
    CreateFamily,
    CreateBaby,
    RecoveryPending,
    RecoveryComplete,
}

/** How the user reached CreateBaby: after family create/reclaim, or offline mode. */
internal enum class OnboardingCreateBabySource {
    AfterFamilyCreate,
    AfterFamilyReclaim,
    OfflineMode,
}

internal fun onboardingFamilyActions(): List<FamilyWizardMode> =
    listOf(FamilyWizardMode.Create, FamilyWizardMode.Join)

internal fun onboardingChooseFamilyBody(): String =
    "可新建或加入家庭，也可先用离线模式在本机记录；连家庭之后再到账户里完成。"

internal fun onboardingCreateBabyBody(source: OnboardingCreateBabySource): String = when (source) {
    OnboardingCreateBabySource.AfterFamilyReclaim ->
        "家庭已接回；家庭中还没有宝宝，请创建第一个家庭宝宝。"
    OnboardingCreateBabySource.AfterFamilyCreate ->
        "家庭已建立，请创建第一个家庭宝宝。"
    OnboardingCreateBabySource.OfflineMode ->
        "离线模式：先在本机创建宝宝并记录。之后可在账户里新建或加入家庭再同步。"
}

internal fun onboardingCreateBabySource(
    familyWizardState: FamilyWizardState,
): OnboardingCreateBabySource {
    val completed = familyWizardState as? FamilyWizardState.Completed
    return when (completed?.outcome) {
        is FamilyWizardOutcome.Reclaimed -> OnboardingCreateBabySource.AfterFamilyReclaim
        is FamilyWizardOutcome.Created -> OnboardingCreateBabySource.AfterFamilyCreate
        else -> OnboardingCreateBabySource.OfflineMode
    }
}

internal fun onboardingFamilyWizardSnapshot(
    mode: FamilyWizardMode,
    step: FamilyWizardStep,
    draft: JoinFamilyDraft,
    displayName: String,
    familyName: String = "",
): FamilyWizardSnapshot = FamilyWizardSnapshot.fromDraft(
    entry = FamilyWizardEntry.Onboarding,
    mode = mode,
    step = step,
    draft = draft,
    displayName = displayName,
    familyName = familyName,
)

internal data class OnboardingFamilyTransition(
    val finishRecovery: Boolean,
    val nextStep: OnboardingStep,
)

internal fun onboardingFamilyWizardTransition(
    state: FamilyWizardState,
    reclaimedFamilyEmpty: Boolean?,
): OnboardingFamilyTransition? = when (state) {
    is FamilyWizardState.Completed -> when (val outcome = state.outcome) {
        is FamilyWizardOutcome.Created -> OnboardingFamilyTransition(
            finishRecovery = false,
            nextStep = OnboardingStep.CreateBaby,
        )
        is FamilyWizardOutcome.Joined -> OnboardingFamilyTransition(
            finishRecovery = true,
            nextStep = OnboardingStep.ChooseFamily,
        )
        is FamilyWizardOutcome.Reclaimed -> when (outcome.dataRecovery) {
            com.lezi.babylog.sync.InitialFamilyDataRecovery.Complete ->
                reclaimedFamilyEmpty?.let { empty ->
                    OnboardingFamilyTransition(
                        finishRecovery = true,
                        nextStep = if (empty) {
                            OnboardingStep.CreateBaby
                        } else {
                            OnboardingStep.RecoveryComplete
                        },
                    )
                }
            com.lezi.babylog.sync.InitialFamilyDataRecovery.RetryRequired,
            com.lezi.babylog.sync.InitialFamilyDataRecovery.NotRequired,
            -> OnboardingFamilyTransition(
                finishRecovery = false,
                nextStep = OnboardingStep.RecoveryPending,
            )
        }
    }
    is FamilyWizardState.RetryableFailure -> if (state.committedOutcome != null) {
        OnboardingFamilyTransition(
            finishRecovery = false,
            nextStep = OnboardingStep.RecoveryPending,
        )
    } else {
        null
    }
    is FamilyWizardState.Editing,
    is FamilyWizardState.Submitting,
    -> null
}

private val JoinFamilyDraftSaver = listSaver<JoinFamilyDraft, String>(
    save = {
        listOf(
            it.invitation,
            it.host,
            it.portText,
            it.scheme,
            it.ssid1,
            it.ssid2,
        )
    },
    restore = {
        JoinFamilyDraft(
            invitation = it[0],
            host = it[1],
            portText = it[2],
            scheme = it[3],
            ssid1 = it[4],
            ssid2 = it[5],
        )
    },
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val careLog: CareLog,
    private val joinFamily: JoinFamilyUseCase,
    private val networkState: NetworkState,
    sync: SyncPort,
) : ViewModel() {
    private val familyWizard = FamilyWizardController(
        gateway = SyncFamilyWizardGateway(sync, joinFamily, careLog),
        initialSnapshot = FamilyWizardSnapshot.empty(FamilyWizardEntry.Onboarding),
    )
    private val mutableReclaimedFamilyEmpty = MutableStateFlow<Boolean?>(null)
    val familyWizardState = familyWizard.state
    val reclaimedFamilyEmpty = mutableReclaimedFamilyEmpty.asStateFlow()

    fun currentWifiSsid(): String? = networkState.currentWifiSsid()

    fun submitFamilyWizard(snapshot: FamilyWizardSnapshot, bootstrapSecret: String = "") {
        viewModelScope.launch {
            mutableReclaimedFamilyEmpty.value = null
            familyWizard.submit(snapshot, bootstrapSecret)
            updateRecoveredFamilyEmptiness()
        }
    }

    fun retryOwnerRecovery() {
        viewModelScope.launch {
            familyWizard.retryReclaimedDataRecovery()
            updateRecoveredFamilyEmptiness()
        }
    }

    fun consumeFamilyWizardCompletion(): FamilyWizardOutcome? =
        familyWizard.consumeCompletion()

    private suspend fun updateRecoveredFamilyEmptiness() {
        val outcome = (familyWizard.state.value as? FamilyWizardState.Completed)?.outcome
        if (outcome is FamilyWizardOutcome.Reclaimed &&
            outcome.dataRecovery == com.lezi.babylog.sync.InitialFamilyDataRecovery.Complete
        ) {
            mutableReclaimedFamilyEmpty.value = careLog.listBabies().isEmpty()
        }
    }

    fun createBaby(
        nickname: String,
        sex: String?,
        birthdayEpochDay: Long,
        birthWeightGrams: Int?,
        themeColorArgb: Int,
        onDone: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                careLog.createBaby(
                    CreateBabyInput(
                        nickname = nickname.trim(),
                        sex = sex,
                        birthdayEpochDay = birthdayEpochDay,
                        birthWeightGrams = birthWeightGrams,
                        themeColorArgb = themeColorArgb,
                    ),
                )
                onDone(null)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                onDone(productUiError(t, "创建失败"))
            }
        }
    }

}

@Composable
private fun JoinSetupStep(
    step: String,
    title: String,
    status: String,
    complete: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(32.dp),
            shape = CircleShape,
            color = if (complete) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (complete) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (complete) "✓" else step, style = LeziTypography.Label)
            }
        }
        Spacer(Modifier.size(LeziSpacing.Sm))
        Column(Modifier.weight(1f)) {
            Text(title, style = LeziTypography.BodyStrong)
            Text(
                status,
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OnboardingRoute(
    onFinished: () -> Unit,
    vm: OnboardingViewModel = hiltViewModel(),
) {
    var step by rememberSaveable { mutableStateOf(OnboardingStep.ChooseFamily) }
    var name by rememberSaveable { mutableStateOf("年年") }
    var sex by rememberSaveable { mutableStateOf<String?>(null) }
    var birthday by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var weightText by rememberSaveable { mutableStateOf("") }
    var themeIdx by rememberSaveable { mutableIntStateOf(0) }
    var showDate by rememberSaveable { mutableStateOf(false) }
    var showJoin by rememberSaveable { mutableStateOf(false) }
    var joinDisplayName by rememberSaveable { mutableStateOf("") }
    var createDisplayName by rememberSaveable { mutableStateOf("") }
    var createFamilyName by rememberSaveable { mutableStateOf("") }
    var bootstrapSecret by remember { mutableStateOf("") }
    var nameError by rememberSaveable { mutableStateOf(false) }
    var formError by rememberSaveable { mutableStateOf<String?>(null) }
    val familyWizardState by vm.familyWizardState.collectAsState()
    val familyWizardBusy = familyWizardState is FamilyWizardState.Submitting
    val reclaimedFamilyEmpty by vm.reclaimedFamilyEmpty.collectAsState()
    val context = LocalContext.current
    // Prefill unsaved defaults, including the current Wi-Fi name when available.
    val novice = remember {
        HomeLanServerConfig.noviceUiDefaults(
            if (HomeWifiPermission.hasRequiredPermissions(context)) vm.currentWifiSsid() else null,
        )
    }
    var joinDraft by rememberSaveable(stateSaver = JoinFamilyDraftSaver) {
        mutableStateOf(JoinFamilyDraft.fromConfig(novice))
    }
    var pendingHomeWifiAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showHomeWifiAccessGuide by remember { mutableStateOf(false) }
    val homeWifiPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val action = pendingHomeWifiAction
        pendingHomeWifiAction = null
        if (HomeWifiPermission.isSsidAccessReady(context)) {
            action?.invoke()
        } else {
            showHomeWifiAccessGuide = true
        }
    }
    fun withHomeWifiAccess(action: () -> Unit) {
        val missing = HomeWifiPermission.missingPermissions(context)
        if (missing.isNotEmpty()) {
            pendingHomeWifiAction = action
            homeWifiPermission.launch(missing.toTypedArray())
        } else if (HomeWifiPermission.isSsidAccessReady(context)) {
            action()
        } else {
            showHomeWifiAccessGuide = true
        }
    }
    fun fillCurrentWifiIfBlank() {
        val currentSsid = vm.currentWifiSsid()?.trim().orEmpty()
        if (currentSsid.isNotEmpty() && joinDraft.ssid1.isBlank()) {
            joinDraft = joinDraft.copy(ssid1 = currentSsid)
        }
    }
    fun applyScannedInvite(raw: String) {
        val payload = raw.trim()
        if (payload.isEmpty()) return
        // Prefill short code + host/port/optional SSIDs; persist only after join succeeds.
        val result = joinDraft.applyInvitationInput(payload)
        joinDraft = result.draft
        formError = result.error
        showJoin = true
    }
    val scanInvite = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(::applyScannedInvite)
    }
    fun launchInviteScan() {
        if (!CameraCapture.hasCameraHardware(context)) {
            formError = "此设备没有可用相机，请改用手动输入邀请码"
            showJoin = true
            return
        }
        scanInvite.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("扫描家庭邀请二维码")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .setBarcodeImageEnabled(false),
        )
    }
    val scanCameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launchInviteScan()
        } else {
            formError = "需要相机权限才能扫码，请在系统设置中开启，或改用输入邀请码"
            showJoin = true
        }
    }
    fun requestOrLaunchInviteScan() {
        formError = null
        if (CameraCapture.hasPermission(context)) {
            launchInviteScan()
        } else {
            scanCameraPermission.launch(CameraCapture.PERMISSION)
        }
    }
    // Form defaults do not trigger runtime permission prompts; only explicit family actions do.
    LaunchedEffect(showJoin) {
        if (!showJoin) return@LaunchedEffect
        if (joinDraft.host.isBlank() || joinDraft.portText.isBlank()) {
            joinDraft = JoinFamilyDraft.fromConfig(novice, joinDraft.invitation).copy(
                ssid1 = joinDraft.ssid1.ifBlank { novice.allowedSsids.getOrNull(0).orEmpty() },
                ssid2 = joinDraft.ssid2,
            )
        }
    }
    LaunchedEffect(familyWizardState, reclaimedFamilyEmpty) {
        val transition = onboardingFamilyWizardTransition(
            state = familyWizardState,
            reclaimedFamilyEmpty = reclaimedFamilyEmpty,
        )
        if (transition != null) {
            bootstrapSecret = ""
            formError = null
            vm.consumeFamilyWizardCompletion()
            if (transition.finishRecovery) onFinished()
            // If a reclaimed family is genuinely empty, root routing keeps onboarding alive and
            // this becomes its first authoritative Baby step instead of returning to start.
            step = transition.nextStep
        } else {
            formError = when (val state = familyWizardState) {
                is FamilyWizardState.RetryableFailure -> state.message
                else -> formError
            }
            if (familyWizardState is FamilyWizardState.RetryableFailure &&
                familyWizardState.snapshot.mode == FamilyWizardMode.Join
            ) {
                showJoin = true
            }
        }
    }
    val dateLabel = remember(birthday) {
        LocalDate.ofEpochDay(birthday).format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .dismissKeyboardOnTap()
            .padding(24.dp)
            .testTag(UiTags.ONBOARDING),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("欢迎使用乐记", style = MaterialTheme.typography.headlineSmall)
        when (step) {
            OnboardingStep.ChooseFamily -> {
                Text(
                    onboardingChooseFamilyBody(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                onboardingFamilyActions().forEach { action ->
                    val onClick = {
                        formError = null
                        when (action) {
                            FamilyWizardMode.Create -> step = OnboardingStep.CreateFamily
                            FamilyWizardMode.Join -> showJoin = true
                        }
                    }
                    when (action) {
                        FamilyWizardMode.Create -> Button(
                            onClick = onClick,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                        ) { Text("新建家庭") }
                        FamilyWizardMode.Join -> OutlinedButton(
                            onClick = onClick,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                        ) { Text("加入家庭") }
                    }
                }
                OutlinedButton(
                    onClick = {
                        formError = null
                        step = OnboardingStep.CreateBaby
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag(UiTags.ONBOARDING_OFFLINE_MODE),
                ) { Text("离线模式") }
            }
            OnboardingStep.CreateFamily -> {
                Text(
                    "连接家里的 NAS。若 NAS 已有家庭，同一动作会接回原管理员与历史数据。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = joinDraft.host,
                    onValueChange = { joinDraft = joinDraft.copy(host = it) },
                    label = { Text("服务器主机（IP/域名）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = joinDraft.portText,
                    onValueChange = {
                        joinDraft = joinDraft.copy(portText = it.filter(Char::isDigit).take(5))
                    },
                    label = { Text("端口") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = joinDraft.ssid1,
                    onValueChange = { joinDraft = joinDraft.copy(ssid1 = it) },
                    label = { Text("家庭 Wi‑Fi 名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        withHomeWifiAccess {
                            val current = vm.currentWifiSsid()?.trim().orEmpty()
                            if (current.isEmpty()) showHomeWifiAccessGuide = true
                            else joinDraft = joinDraft.copy(ssid1 = current)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("填入当前 Wi‑Fi 名称")
                }
                OutlinedTextField(
                    value = createDisplayName,
                    onValueChange = { createDisplayName = it },
                    label = { Text("我是宝宝的？") },
                    supportingText = { Text("家庭称呼，必填；家人用这个认出你") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = createFamilyName,
                    onValueChange = { createFamilyName = it },
                    label = { Text("家庭名（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = bootstrapSecret,
                    onValueChange = { bootstrapSecret = it },
                    label = { Text("NAS 初始化口令（可空）") },
                    supportingText = { Text("仅用于本次请求，不会保存") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    enabled = !familyWizardBusy,
                    onClick = {
                        formError = null
                        withHomeWifiAccess {
                            vm.submitFamilyWizard(
                                snapshot = onboardingFamilyWizardSnapshot(
                                    mode = FamilyWizardMode.Create,
                                    step = FamilyWizardStep.Identity,
                                    draft = joinDraft,
                                    displayName = createDisplayName,
                                    familyName = createFamilyName,
                                ),
                                bootstrapSecret = bootstrapSecret,
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Text(
                        if (familyWizardBusy) {
                            "正在连接…"
                        } else {
                            "新建家庭"
                        },
                    )
                }
                TextButton(
                    enabled = !familyWizardBusy,
                    onClick = { step = OnboardingStep.ChooseFamily },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("返回") }
            }
            OnboardingStep.CreateBaby -> {
                val createBabySource = onboardingCreateBabySource(familyWizardState)
                Text(
                    onboardingCreateBabyBody(createBabySource),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = limitBabyNicknameInput(it)
                        nameError = false
                    },
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
                                onClick = { sex = value },
                                label = { Text(label) },
                            )
                        }
                }
                OutlinedButton(
                    onClick = { showDate = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("生日：$dateLabel") }
                OutlinedTextField(
                    value = weightText,
                    onValueChange = { weightText = it.filter { ch -> ch.isDigit() } },
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
                                    onClick = { themeIdx = index },
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
                    onClick = {
                        if (name.trim().isEmpty()) {
                            nameError = true
                            return@Button
                        }
                        val grams = weightText.toIntOrNull()
                        birthWeightValidationError(grams)?.let {
                            formError = it
                            return@Button
                        }
                        vm.createBaby(
                            nickname = name,
                            sex = sex,
                            birthdayEpochDay = birthday,
                            birthWeightGrams = grams,
                            themeColorArgb = ThemePalette[themeIdx],
                            onDone = { error ->
                                if (error == null) onFinished() else formError = error
                            },
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) { Text("开始记录") }
                if (createBabySource == OnboardingCreateBabySource.OfflineMode) {
                    TextButton(
                        onClick = { step = OnboardingStep.ChooseFamily },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("返回") }
                }
            }
            OnboardingStep.RecoveryPending -> {
                val recoveryFailure = familyWizardState as? FamilyWizardState.RetryableFailure
                Text(
                    "家庭身份已接回，但历史数据还没有恢复完成。请保持连接家庭 Wi‑Fi 后重试。",
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
                    onClick = vm::retryOwnerRecovery,
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
            OnboardingStep.RecoveryComplete -> {
                Text(
                    "家庭与历史宝宝已恢复完成，正在进入家庭记录。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showDate) {
        val initialMillis = birthday.toDatePickerMillis()
        val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { ms ->
                            birthday = ms.datePickerMillisToEpochDay()
                        }
                        showDate = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDate = false }) { Text("取消") }
            },
        ) {
            LeziDatePicker(state = state)
        }
    }

    if (showJoin) {
        AlertDialog(
            onDismissRequest = { showJoin = false },
            title = { Text("加入家庭") },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .dismissKeyboardOnTap()
                        .verticalScroll(rememberScrollState())
                        .imePadding(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    JoinSetupStep(
                        step = "1",
                        title = "家庭网络",
                        status = if (joinDraft.host.isNotBlank() && joinDraft.ssid1.isNotBlank()) {
                            "服务器与家庭 Wi‑Fi 已填写"
                        } else {
                            "填写服务器并绑定家庭 Wi‑Fi"
                        },
                        complete = joinDraft.host.isNotBlank() && joinDraft.ssid1.isNotBlank(),
                    )
                    JoinSetupStep(
                        step = "2",
                        title = "家庭邀请",
                        status = if (joinDraft.invitation.isBlank()) "扫码或粘贴邀请码" else "邀请码已填入",
                        complete = joinDraft.invitation.isNotBlank(),
                    )
                    JoinSetupStep(
                        step = "3",
                        title = "共享范围",
                        status = "加入成功后同步育儿记录与日志图片",
                        complete = false,
                    )
                    OutlinedTextField(
                        value = joinDraft.host,
                        onValueChange = { joinDraft = joinDraft.copy(host = it) },
                        label = { Text("服务器主机（IP/域名）") },
                        placeholder = { Text("192.168.50.4") },
                        supportingText = {
                            Text(if (joinDraft.host.isBlank()) "待填写" else "服务器地址已填写")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = joinDraft.portText,
                        onValueChange = {
                            joinDraft = joinDraft.copy(portText = it.filter(Char::isDigit).take(5))
                        },
                        label = { Text("端口") },
                        placeholder = { Text("8765") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = joinDraft.ssid1,
                        onValueChange = { joinDraft = joinDraft.copy(ssid1 = it) },
                        label = { Text("家庭 Wi‑Fi 名称 1（如 2.4G）") },
                        placeholder = { Text("当前连接的 Wi‑Fi 名") },
                        supportingText = {
                            Text(
                                if (joinDraft.ssid1.isNotBlank()) {
                                    "已绑定：${joinDraft.ssid1}"
                                } else {
                                    "待填写，或读取当前 Wi‑Fi"
                                },
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = joinDraft.ssid2,
                        onValueChange = { joinDraft = joinDraft.copy(ssid2 = it) },
                        label = { Text("家庭 Wi‑Fi 名称 2（可选，如 5G）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = {
                            withHomeWifiAccess {
                                val cur = vm.currentWifiSsid()?.trim().orEmpty()
                                if (cur.isEmpty()) {
                                    showHomeWifiAccessGuide = true
                                } else if (joinDraft.ssid1.isBlank()) {
                                    joinDraft = joinDraft.copy(ssid1 = cur)
                                } else if (joinDraft.ssid2.isBlank() && joinDraft.ssid1 != cur) {
                                    joinDraft = joinDraft.copy(ssid2 = cur)
                                } else if (joinDraft.ssid1 != cur && joinDraft.ssid2 != cur) {
                                    formError = "Wi‑Fi 名称已满 2 个，请先清空一格"
                                } else {
                                    formError = "当前 Wi‑Fi 已在列表中"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("填入当前 Wi‑Fi 名称")
                    }
                    OutlinedTextField(
                        value = joinDisplayName,
                        onValueChange = { joinDisplayName = it },
                        label = { Text("我是宝宝的？") },
                        placeholder = { Text("如：妈妈、干妈、月嫂小王") },
                        supportingText = { Text("家庭称呼，必填；家人用这个认出你") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = joinDraft.invitation,
                        onValueChange = { joinDraft = joinDraft.copy(invitation = it) },
                        label = { Text("邀请码或 QR 载荷") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(
                                onClick = { withHomeWifiAccess { requestOrLaunchInviteScan() } },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.QrCodeScanner,
                                    contentDescription = "扫码填入邀请",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    formError?.let { err ->
                        Text(err, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        withHomeWifiAccess {
                            fillCurrentWifiIfBlank()
                            formError = null
                            vm.submitFamilyWizard(
                                snapshot = onboardingFamilyWizardSnapshot(
                                    mode = FamilyWizardMode.Join,
                                    step = FamilyWizardStep.Identity,
                                    draft = joinDraft,
                                    displayName = joinDisplayName,
                                ),
                            )
                        }
                    },
                    enabled = !familyWizardBusy,
                ) { Text(if (familyWizardBusy) "正在加入…" else "加入") }
            },
            dismissButton = {
                TextButton(onClick = { showJoin = false }) { Text("取消") }
            },
        )
    }

    if (showHomeWifiAccessGuide) {
        val settingsTarget = HomeWifiPermission.settingsTarget(context)
        HomeWifiAccessGuideDialog(
            settingsAction = when (settingsTarget) {
                HomeWifiSettingsTarget.AppPermission -> HomeWifiSettingsAction.AppPermission
                HomeWifiSettingsTarget.LocationServices -> HomeWifiSettingsAction.LocationServices
                HomeWifiSettingsTarget.Wifi -> HomeWifiSettingsAction.Wifi
            },
            onOpenSettings = {
                showHomeWifiAccessGuide = false
                context.startActivity(HomeWifiPermission.settingsIntent(context))
            },
            onDismiss = { showHomeWifiAccessGuide = false },
        )
    }
}

internal fun Long.toDatePickerMillis(): Long =
    LocalDate.ofEpochDay(this)
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()

internal fun Long.datePickerMillisToEpochDay(): Long =
    Instant.ofEpochMilli(this)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
        .toEpochDay()
