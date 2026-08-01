package com.lezi.babylog.feature.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.DisposableEffect
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
import com.lezi.babylog.core.ui.UiTags
import com.lezi.babylog.designsystem.dismissKeyboardOnTap
import com.lezi.babylog.designsystem.LeziDatePicker
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.CreateBabyInput
import com.lezi.babylog.domain.FamilyWizardController
import com.lezi.babylog.domain.FamilyWizardEntry
import com.lezi.babylog.domain.FamilyWizardJoinRole
import com.lezi.babylog.domain.FamilyWizardMode
import com.lezi.babylog.domain.FamilyWizardOutcome
import com.lezi.babylog.domain.FamilyWizardSnapshot
import com.lezi.babylog.domain.FamilyWizardState
import com.lezi.babylog.domain.FamilyWizardStep
import com.lezi.babylog.domain.SyncFamilyWizardGateway
import com.lezi.babylog.sync.FamilyEndpointConfig
import com.lezi.babylog.sync.CertificateTrustCandidate
import com.lezi.babylog.sync.FamilyEndpointDraft
import com.lezi.babylog.sync.MemberLoginQrPayload
import com.lezi.babylog.sync.MemberLoginQrPayloadCodec
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.defaultAndroidDeviceName
import com.lezi.babylog.sync.requireDeviceName
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val ThemePalette = com.lezi.babylog.designsystem.LeziBabyTheme.PaletteArgb.map {
    com.lezi.babylog.designsystem.normalizeBabyThemeArgb(it)
}
private val ThemePaletteLabels = com.lezi.babylog.designsystem.LeziBabyTheme.Labels

internal enum class OnboardingStep {
    ChooseFamily,
    ConnectServer,
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

internal fun onboardingFamilyActions(): List<String> = listOf("连接家庭服务器")

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
        is FamilyWizardOutcome.OwnerLoggedIn -> OnboardingCreateBabySource.AfterFamilyReclaim
        is FamilyWizardOutcome.Created -> OnboardingCreateBabySource.AfterFamilyCreate
        else -> OnboardingCreateBabySource.OfflineMode
    }
}

internal fun onboardingFamilyWizardSnapshot(
    mode: FamilyWizardMode,
    step: FamilyWizardStep,
    draft: FamilyEndpointDraft,
    displayName: String,
    familyName: String = "",
    deviceName: String = "",
    joinRole: FamilyWizardJoinRole? = null,
): FamilyWizardSnapshot = FamilyWizardSnapshot.fromDraft(
    entry = FamilyWizardEntry.Onboarding,
    mode = mode,
    step = step,
    draft = draft,
    displayName = displayName,
    familyName = familyName,
    deviceName = deviceName,
).copy(joinRole = joinRole)

internal data class OnboardingFamilyTransition(
    val finishRecovery: Boolean,
    val nextStep: OnboardingStep,
)

internal fun onboardingFamilyWizardTransition(
    state: FamilyWizardState,
    reclaimedFamilyEmpty: Boolean?,
): OnboardingFamilyTransition? = when (state) {
    is FamilyWizardState.Completed -> when (val outcome = state.outcome) {
        is FamilyWizardOutcome.Created -> when (outcome.dataRecovery) {
            com.lezi.babylog.sync.InitialFamilyDataRecovery.Complete -> OnboardingFamilyTransition(
                finishRecovery = false,
                nextStep = OnboardingStep.CreateBaby,
            )
            com.lezi.babylog.sync.InitialFamilyDataRecovery.RetryRequired,
            com.lezi.babylog.sync.InitialFamilyDataRecovery.NotRequired,
            -> OnboardingFamilyTransition(
                finishRecovery = false,
                nextStep = OnboardingStep.RecoveryPending,
            )
        }
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
        is FamilyWizardOutcome.OwnerLoggedIn -> when (outcome.dataRecovery) {
            com.lezi.babylog.sync.InitialFamilyDataRecovery.Complete ->
                reclaimedFamilyEmpty?.let { empty ->
                    OnboardingFamilyTransition(
                        finishRecovery = true,
                        nextStep = if (empty) OnboardingStep.CreateBaby else OnboardingStep.RecoveryComplete,
                    )
                }
            com.lezi.babylog.sync.InitialFamilyDataRecovery.RetryRequired,
            com.lezi.babylog.sync.InitialFamilyDataRecovery.NotRequired,
            -> OnboardingFamilyTransition(
                finishRecovery = false,
                nextStep = OnboardingStep.RecoveryPending,
            )
        }
        is FamilyWizardOutcome.MemberApproved -> when (outcome.dataRecovery) {
            com.lezi.babylog.sync.InitialFamilyDataRecovery.Complete ->
                OnboardingFamilyTransition(
                    finishRecovery = true,
                    nextStep = OnboardingStep.ChooseFamily,
                )
            com.lezi.babylog.sync.InitialFamilyDataRecovery.RetryRequired,
            com.lezi.babylog.sync.InitialFamilyDataRecovery.NotRequired,
            -> OnboardingFamilyTransition(
                finishRecovery = false,
                nextStep = OnboardingStep.RecoveryPending,
            )
        }
        is FamilyWizardOutcome.MemberLoginQrClaimed -> when (outcome.dataRecovery) {
            com.lezi.babylog.sync.InitialFamilyDataRecovery.Complete ->
                OnboardingFamilyTransition(
                    finishRecovery = true,
                    nextStep = OnboardingStep.ChooseFamily,
                )
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
    is FamilyWizardState.CertificateApprovalRequired,
    is FamilyWizardState.EndpointFailure,
    is FamilyWizardState.EndpointReady,
    is FamilyWizardState.ProbingEndpoint,
    is FamilyWizardState.Submitting,
    is FamilyWizardState.WaitingForMemberApproval,
    is FamilyWizardState.VerifyingMemberLoginQr,
    is FamilyWizardState.MemberLoginQrReady,
    is FamilyWizardState.MemberLoginQrVerificationFailed,
    is FamilyWizardState.ClaimingMemberLoginQr,
    -> null
}

private val FamilyEndpointDraftSaver = listSaver<FamilyEndpointDraft, String>(
    save = {
        listOf(
            it.host,
            it.portText,
            it.scheme,
        )
    },
    restore = {
        FamilyEndpointDraft(
            host = it[0],
            portText = it[1],
            scheme = it[2],
        )
    },
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val careLog: CareLog,
    private val sync: SyncPort,
) : ViewModel() {
    private val familyWizard = FamilyWizardController(
        gateway = SyncFamilyWizardGateway(sync, careLog),
        initialSnapshot = FamilyWizardSnapshot.empty(FamilyWizardEntry.Onboarding),
    )
    private val mutableReclaimedFamilyEmpty = MutableStateFlow<Boolean?>(null)
    val familyWizardState = familyWizard.state
    val reclaimedFamilyEmpty = mutableReclaimedFamilyEmpty.asStateFlow()
    val verifiedEndpoint = sync.verifiedEndpoint()
    val pendingMemberLogin = sync.pendingMemberLogin().stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        null,
    )

    init {
        viewModelScope.launch {
            sync.memberLoginChecks().collect(familyWizard::observeMemberLoginCheck)
        }
        viewModelScope.launch {
            combine(pendingMemberLogin, sync.session()) { pending, session -> pending to session }
                .collect { (pending, session) ->
                    if (pending != null) {
                        familyWizard.restorePendingMemberApproval(
                            FamilyWizardSnapshot.empty(FamilyWizardEntry.Onboarding).copy(
                                mode = FamilyWizardMode.Join,
                                step = FamilyWizardStep.Identity,
                                host = session.serverHost,
                                portText = session.serverPort.toString(),
                                scheme = session.serverScheme,
                                joinRole = FamilyWizardJoinRole.Member,
                            ),
                            pending,
                        )
                    }
                }
        }
    }

    fun submitFamilyWizard(
        snapshot: FamilyWizardSnapshot,
        bootstrapSecret: String = "",
        ownerTakeover: Boolean = false,
    ) {
        viewModelScope.launch {
            mutableReclaimedFamilyEmpty.value = null
            familyWizard.submit(snapshot, bootstrapSecret, ownerTakeover)
            updateRecoveredFamilyEmptiness()
        }
    }

    fun connectEndpoint(endpointDraft: String) {
        viewModelScope.launch {
            familyWizard.connectEndpoint(FamilyWizardEntry.Onboarding, endpointDraft)
        }
    }

    fun trustCertificate(candidate: CertificateTrustCandidate) {
        viewModelScope.launch {
            familyWizard.trustCertificate(FamilyWizardEntry.Onboarding, candidate)
        }
    }

    fun keepOffline() {
        familyWizard.keepOffline(FamilyWizardEntry.Onboarding)
    }

    fun forgetEndpoint() {
        viewModelScope.launch { familyWizard.forgetEndpoint(FamilyWizardEntry.Onboarding) }
    }

    fun retryOwnerRecovery() {
        viewModelScope.launch {
            familyWizard.retryReclaimedDataRecovery()
            updateRecoveredFamilyEmptiness()
        }
    }

    fun checkMemberApproval() {
        viewModelScope.launch { familyWizard.checkMemberApproval() }
    }

    fun cancelMemberApproval() {
        viewModelScope.launch { familyWizard.cancelMemberApproval() }
    }

    fun verifyMemberLoginQr(payload: MemberLoginQrPayload) {
        viewModelScope.launch {
            familyWizard.verifyMemberLoginQr(FamilyWizardEntry.Onboarding, payload)
        }
    }

    fun cancelMemberLoginQr() {
        viewModelScope.launch { familyWizard.cancelMemberLoginQr() }
    }

    fun claimMemberLoginQr(payload: MemberLoginQrPayload, deviceName: String) {
        viewModelScope.launch {
            familyWizard.claimMemberLoginQr(payload, deviceName)
            updateRecoveredFamilyEmptiness()
        }
    }

    fun retryMemberLoginQrRecovery() {
        viewModelScope.launch {
            familyWizard.retryReclaimedDataRecovery()
            updateRecoveredFamilyEmptiness()
        }
    }

    fun consumeFamilyWizardCompletion(): FamilyWizardOutcome? =
        familyWizard.consumeCompletion()

    private suspend fun updateRecoveredFamilyEmptiness() {
        val outcome = (familyWizard.state.value as? FamilyWizardState.Completed)?.outcome
        if ((outcome is FamilyWizardOutcome.Reclaimed ||
                outcome is FamilyWizardOutcome.OwnerLoggedIn) &&
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
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf(OnboardingStep.ChooseFamily) }
    var name by rememberSaveable { mutableStateOf("年年") }
    var sex by rememberSaveable { mutableStateOf<String?>(null) }
    var birthday by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var weightText by rememberSaveable { mutableStateOf("") }
    var themeIdx by rememberSaveable { mutableIntStateOf(0) }
    var showDate by rememberSaveable { mutableStateOf(false) }
    var showJoin by rememberSaveable { mutableStateOf(false) }
    var showMemberWaiting by rememberSaveable { mutableStateOf(false) }
    var showJoinRole by rememberSaveable { mutableStateOf(false) }
    var showOwnerLogin by rememberSaveable { mutableStateOf(false) }
    var showOwnerTakeover by remember { mutableStateOf(false) }
    var joinDisplayName by rememberSaveable { mutableStateOf("") }
    var memberDeviceName by remember { mutableStateOf(defaultAndroidDeviceName(context)) }
    var createDisplayName by rememberSaveable { mutableStateOf("") }
    var createFamilyName by rememberSaveable { mutableStateOf("") }
    var createDeviceName by remember { mutableStateOf(defaultAndroidDeviceName(context)) }
    var bootstrapSecret by remember { mutableStateOf("") }
    var ownerDeviceName by remember { mutableStateOf(defaultAndroidDeviceName(context)) }
    var ownerRootPassword by remember { mutableStateOf("") }
    var nameError by rememberSaveable { mutableStateOf(false) }
    var formError by rememberSaveable { mutableStateOf<String?>(null) }
    // Device draft for QR claim is process-memory only; grant lives only in controller state.
    var memberQrDeviceName by remember { mutableStateOf(defaultAndroidDeviceName(context)) }
    val familyWizardState by vm.familyWizardState.collectAsState()
    val pendingMemberLogin by vm.pendingMemberLogin.collectAsState()
    val verifiedEndpoint by vm.verifiedEndpoint.collectAsState(initial = null)
    val familyWizardBusy = familyWizardState is FamilyWizardState.Submitting ||
        familyWizardState is FamilyWizardState.ProbingEndpoint ||
        familyWizardState is FamilyWizardState.VerifyingMemberLoginQr ||
        familyWizardState is FamilyWizardState.ClaimingMemberLoginQr
    val reclaimedFamilyEmpty by vm.reclaimedFamilyEmpty.collectAsState()
    val novice = remember { FamilyEndpointConfig.emptyDraft() }
    var joinDraft by rememberSaveable(stateSaver = FamilyEndpointDraftSaver) {
        mutableStateOf(FamilyEndpointDraft.fromConfig(novice))
    }
    var endpointDraft by remember { mutableStateOf("") }
    DisposableEffect(step, showOwnerLogin, showOwnerTakeover, context) {
        val window = context.findActivity()?.window
        if (step == OnboardingStep.CreateFamily || showOwnerLogin || showOwnerTakeover) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (step == OnboardingStep.CreateFamily || showOwnerLogin || showOwnerTakeover) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
    LaunchedEffect(verifiedEndpoint) {
        if (endpointDraft.isBlank()) endpointDraft = verifiedEndpoint?.origin.orEmpty()
    }
    fun runForegroundAction(action: () -> Unit) = action()
    fun applyScannedMemberLogin(raw: String) {
        val payload = raw.trim()
        if (payload.isEmpty()) return
        val memberLogin = runCatching { MemberLoginQrPayloadCodec.decode(payload) }.getOrNull()
        if (memberLogin != null) {
            if (System.currentTimeMillis() / 1_000 >= memberLogin.expiresAtEpochSeconds) {
                formError = "这个二维码已失效，请让管理员重新生成"
                return
            }
            memberQrDeviceName = defaultAndroidDeviceName(context)
            formError = null
            vm.verifyMemberLoginQr(memberLogin)
            return
        }
        formError = "这不是可用的成员登录二维码"
    }
    val scanMemberLogin = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(::applyScannedMemberLogin)
    }
    fun launchMemberLoginScan() {
        if (!CameraCapture.hasCameraHardware(context)) {
            formError = "此设备没有可用相机，请使用家庭服务器地址手动申请加入"
            return
        }
        scanMemberLogin.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("扫描成员登录二维码")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .setBarcodeImageEnabled(false),
        )
    }
    val scanCameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launchMemberLoginScan()
        } else {
            formError = "需要相机权限才能扫码，请在系统设置中开启"
        }
    }
    fun requestOrLaunchMemberLoginScan() {
        formError = null
        if (CameraCapture.hasPermission(context)) {
            launchMemberLoginScan()
        } else {
            scanCameraPermission.launch(CameraCapture.PERMISSION)
        }
    }
    LaunchedEffect(showJoin) {
        if (!showJoin) return@LaunchedEffect
        if (joinDraft.host.isBlank() || joinDraft.portText.isBlank()) {
            joinDraft = FamilyEndpointDraft.fromConfig(novice)
        }
    }
    LaunchedEffect(familyWizardState, reclaimedFamilyEmpty) {
        val transition = onboardingFamilyWizardTransition(
            state = familyWizardState,
            reclaimedFamilyEmpty = reclaimedFamilyEmpty,
        )
        if (transition != null) {
            bootstrapSecret = ""
            ownerRootPassword = ""
            showJoinRole = false
            showOwnerLogin = false
            showOwnerTakeover = false
            formError = null
            vm.consumeFamilyWizardCompletion()
            if (transition.finishRecovery) onFinished()
            // If a reclaimed family is genuinely empty, root routing keeps onboarding alive and
            // this becomes its first authoritative Baby step instead of returning to start.
            step = transition.nextStep
        } else {
            if (familyWizardState is FamilyWizardState.WaitingForMemberApproval) {
                showJoin = false
                showJoinRole = false
                showMemberWaiting = true
            }
            formError = when (val state = familyWizardState) {
                is FamilyWizardState.RetryableFailure -> state.message
                else -> formError
            }
            if (familyWizardState is FamilyWizardState.RetryableFailure &&
                familyWizardState.snapshot.mode == FamilyWizardMode.Join
            ) {
                if (familyWizardState.snapshot.joinRole == FamilyWizardJoinRole.Owner) {
                    showOwnerLogin = true
                    showOwnerTakeover = false
                } else {
                    showJoin = true
                }
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
                    onClick = {
                        if (pendingMemberLogin != null) {
                            showMemberWaiting = true
                        } else {
                            formError = null
                            endpointDraft = verifiedEndpoint?.origin.orEmpty()
                            step = OnboardingStep.ConnectServer
                            if (endpointDraft.isNotBlank()) vm.connectEndpoint(endpointDraft)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(
                        when {
                            pendingMemberLogin != null -> "查看加入申请"
                            verifiedEndpoint == null -> "连接家庭服务器"
                            else -> "继续登录"
                        },
                    )
                }
                if (pendingMemberLogin == null) {
                    OutlinedButton(
                        onClick = ::requestOrLaunchMemberLoginScan,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.size(LeziSpacing.Xs))
                        Text("扫描成员登录二维码")
                    }
                }
                if (verifiedEndpoint != null) {
                    TextButton(
                        onClick = {
                            vm.forgetEndpoint()
                            endpointDraft = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("忘记此服务器") }
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
            OnboardingStep.ConnectServer -> {
                val approval = familyWizardState as? FamilyWizardState.CertificateApprovalRequired
                val ready = familyWizardState as? FamilyWizardState.EndpointReady
                val failure = familyWizardState as? FamilyWizardState.EndpointFailure
                val certificateChanged =
                    failure?.reason == com.lezi.babylog.sync.SetupProbeResult.Failed.CertificateChanged
                Text(
                    when {
                        familyWizardState is FamilyWizardState.ProbingEndpoint ->
                            "正在确认家庭服务器…"
                        approval != null -> "确认家庭服务器证书"
                        certificateChanged -> "服务器安全信息已变化"
                        ready?.snapshot?.mode == FamilyWizardMode.Create -> "这里还没有家庭"
                        ready != null -> "已找到家庭"
                        failure != null -> failure.message
                        else -> "连接家庭服务器"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
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
                        onValueChange = {
                            endpointDraft = it
                            if (familyWizardState is FamilyWizardState.EndpointFailure) {
                                vm.keepOffline()
                            }
                        },
                        enabled = !familyWizardBusy,
                        label = { Text("https://family.example.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(ready.endpoint.origin)
                }
                failure?.takeUnless { certificateChanged }
                    ?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
                Button(
                    enabled = !familyWizardBusy &&
                        (approval != null || certificateChanged || ready != null || endpointDraft.isNotBlank()),
                    onClick = {
                        when {
                            approval != null -> vm.trustCertificate(approval.candidate)
                            certificateChanged -> {
                                vm.forgetEndpoint()
                                endpointDraft = ""
                            }
                            ready == null -> vm.connectEndpoint(endpointDraft)
                            else -> {
                                endpointDraft = ready.endpoint.origin
                                joinDraft = FamilyEndpointDraft(
                                    host = ready.endpoint.origin,
                                    portText = "443",
                                    scheme = "https",
                                )
                                when (ready.snapshot.mode) {
                                    FamilyWizardMode.Create -> step = OnboardingStep.CreateFamily
                                    FamilyWizardMode.Join -> showJoinRole = true
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(
                        when {
                            approval != null -> "信任此证书"
                            certificateChanged -> "忘记此服务器并重新连接"
                            ready?.snapshot?.mode == FamilyWizardMode.Create -> "新建家庭"
                            ready?.snapshot?.mode == FamilyWizardMode.Join -> "加入家庭"
                            else -> if (familyWizardBusy) "正在连接…" else "连接"
                        },
                    )
                }
                if (approval != null) {
                    TextButton(
                        onClick = { vm.keepOffline() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("返回修改地址") }
                }
                TextButton(
                    enabled = familyWizardState !is FamilyWizardState.Submitting,
                    onClick = {
                        vm.keepOffline()
                        step = OnboardingStep.ChooseFamily
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (certificateChanged) "返回" else "暂不连接，保持离线")
                }
            }
            OnboardingStep.CreateFamily -> {
                Text(
                    "连接家里的 NAS。若 NAS 已有家庭，同一动作会接回原管理员与历史数据。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("已确认的家庭服务器", style = MaterialTheme.typography.labelMedium)
                Text(
                    verifiedEndpoint?.origin ?: "尚未确认家庭服务器，请返回重新连接",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = createFamilyName,
                    onValueChange = { createFamilyName = it },
                    label = { Text("家庭名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = createDisplayName,
                    onValueChange = { createDisplayName = it },
                    label = { Text("我的称呼") },
                    supportingText = { Text("家庭成员会用这个称呼认出你") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = createDeviceName,
                    onValueChange = { createDeviceName = it },
                    label = { Text("设备称呼") },
                    supportingText = { Text("默认取自 Android 设备名，可修改") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = bootstrapSecret,
                    onValueChange = { bootstrapSecret = it },
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
                    onClick = {
                        formError = null
                        val oneTimeRootPassword = bootstrapSecret
                        runForegroundAction {
                            vm.submitFamilyWizard(
                                snapshot = onboardingFamilyWizardSnapshot(
                                    mode = FamilyWizardMode.Create,
                                    step = FamilyWizardStep.Identity,
                                    draft = joinDraft,
                                    displayName = createDisplayName,
                                    familyName = createFamilyName,
                                    deviceName = createDeviceName,
                                ),
                                bootstrapSecret = oneTimeRootPassword,
                            )
                            bootstrapSecret = ""
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
                            "新建并登录"
                        },
                    )
                }
                TextButton(
                    enabled = !familyWizardBusy,
                    onClick = {
                        bootstrapSecret = ""
                        step = OnboardingStep.ChooseFamily
                    },
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

    when (val qrState = familyWizardState) {
        is FamilyWizardState.VerifyingMemberLoginQr -> OnboardingMemberLoginQrDialog(
            payload = qrState.payload,
            deviceName = memberQrDeviceName,
            onDeviceNameChange = {},
            feedback = "正在确认家庭服务器…",
            verifying = true,
            submitting = false,
            trustReady = false,
            recoveryRetryRequired = false,
            onLogin = {},
            onRetryRecovery = {},
            onManualJoin = {
                vm.cancelMemberLoginQr()
                endpointDraft = qrState.payload.endpoint.origin
                step = OnboardingStep.ConnectServer
                vm.connectEndpoint(endpointDraft)
            },
            onDismiss = { vm.cancelMemberLoginQr() },
        )
        is FamilyWizardState.MemberLoginQrVerificationFailed -> OnboardingMemberLoginQrDialog(
            payload = qrState.payload,
            deviceName = memberQrDeviceName,
            onDeviceNameChange = { memberQrDeviceName = it },
            feedback = qrState.message,
            verifying = false,
            submitting = false,
            trustReady = false,
            recoveryRetryRequired = false,
            onLogin = { vm.verifyMemberLoginQr(qrState.payload) },
            onRetryRecovery = {},
            onManualJoin = {
                vm.cancelMemberLoginQr()
                endpointDraft = qrState.payload.endpoint.origin
                step = OnboardingStep.ConnectServer
                vm.connectEndpoint(endpointDraft)
            },
            onDismiss = { vm.cancelMemberLoginQr() },
        )
        is FamilyWizardState.MemberLoginQrReady -> OnboardingMemberLoginQrDialog(
            payload = qrState.payload,
            deviceName = memberQrDeviceName,
            onDeviceNameChange = { memberQrDeviceName = it },
            feedback = qrState.feedback,
            verifying = false,
            submitting = false,
            trustReady = true,
            recoveryRetryRequired = false,
            onLogin = {
                runCatching { requireDeviceName(memberQrDeviceName) }
                    .exceptionOrNull()?.message?.let { return@OnboardingMemberLoginQrDialog }
                vm.claimMemberLoginQr(qrState.payload, memberQrDeviceName)
            },
            onRetryRecovery = {},
            onManualJoin = {
                vm.cancelMemberLoginQr()
                endpointDraft = qrState.payload.endpoint.origin
                step = OnboardingStep.ConnectServer
                vm.connectEndpoint(endpointDraft)
            },
            onDismiss = { vm.cancelMemberLoginQr() },
        )
        is FamilyWizardState.ClaimingMemberLoginQr -> OnboardingMemberLoginQrDialog(
            payload = qrState.payload,
            deviceName = memberQrDeviceName,
            onDeviceNameChange = {},
            feedback = null,
            verifying = false,
            submitting = true,
            trustReady = true,
            recoveryRetryRequired = false,
            onLogin = {},
            onRetryRecovery = {},
            onManualJoin = {},
            onDismiss = {},
        )
        else -> Unit
    }

    if (showJoinRole) {
        AlertDialog(
            onDismissRequest = { if (!familyWizardBusy) showJoinRole = false },
            title = { Text("你要如何加入？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
                    Text("已找到配置完成的家庭。请选择这台设备使用的身份。")
                    Button(
                        onClick = {
                            formError = null
                            showJoinRole = false
                            showOwnerLogin = true
                        },
                        enabled = !familyWizardBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("我是家庭管理员") }
                    OutlinedButton(
                        onClick = {
                            formError = null
                            showJoinRole = false
                            showJoin = true
                        },
                        enabled = !familyWizardBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("我是家庭成员") }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showJoinRole = false }) { Text("取消") }
            },
        )
    }

    if (showOwnerLogin) {
        AlertDialog(
            onDismissRequest = {
                if (!familyWizardBusy) {
                    ownerRootPassword = ""
                    showOwnerLogin = false
                }
            },
            title = { Text("管理员登录") },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .dismissKeyboardOnTap()
                        .verticalScroll(rememberScrollState())
                        .imePadding(),
                    verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
                ) {
                    Text("登录会新增一台管理员设备，已有管理员设备不会退出。")
                    OutlinedTextField(
                        value = ownerDeviceName,
                        onValueChange = {
                            ownerDeviceName = it
                            formError = null
                        },
                        label = { Text("设备称呼") },
                        enabled = !familyWizardBusy,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = ownerRootPassword,
                        onValueChange = {
                            ownerRootPassword = it
                            formError = null
                        },
                        label = { Text("管理员根密码") },
                        supportingText = { Text("与 NAS 部署根密码一致；不会保存") },
                        enabled = !familyWizardBusy,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    TextButton(
                        onClick = {
                            runCatching { requireDeviceName(ownerDeviceName) }
                                .exceptionOrNull()?.message?.let {
                                    formError = it
                                    return@TextButton
                                }
                            if (ownerRootPassword.isBlank()) {
                                formError = "请填写管理员根密码"
                            } else {
                                showOwnerLogin = false
                                showOwnerTakeover = true
                            }
                        },
                        enabled = !familyWizardBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("丢失设备并接管…") }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        runCatching { requireDeviceName(ownerDeviceName) }
                            .exceptionOrNull()?.message?.let {
                                formError = it
                                return@TextButton
                            }
                        val rootPassword = ownerRootPassword
                        if (rootPassword.isBlank()) {
                            formError = "请填写管理员根密码"
                            return@TextButton
                        }
                        runForegroundAction {
                            vm.submitFamilyWizard(
                                snapshot = onboardingFamilyWizardSnapshot(
                                    mode = FamilyWizardMode.Join,
                                    step = FamilyWizardStep.Identity,
                                    draft = joinDraft,
                                    displayName = "",
                                    deviceName = ownerDeviceName,
                                    joinRole = FamilyWizardJoinRole.Owner,
                                ),
                                bootstrapSecret = rootPassword,
                            )
                            ownerRootPassword = ""
                        }
                    },
                    enabled = !familyWizardBusy,
                ) { Text(if (familyWizardBusy) "正在登录…" else "登录这台设备") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        ownerRootPassword = ""
                        showOwnerLogin = false
                        showJoinRole = true
                    },
                    enabled = !familyWizardBusy,
                ) { Text("上一步") }
            },
        )
    }

    if (showOwnerTakeover) {
        AlertDialog(
            onDismissRequest = {
                if (!familyWizardBusy) {
                    showOwnerTakeover = false
                    showOwnerLogin = true
                }
            },
            title = { Text("接管管理员身份？") },
            text = {
                Text("所有旧管理员设备都会退出家庭；普通成员不会退出。只有确定旧设备已丢失时才使用。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val rootPassword = ownerRootPassword
                        if (rootPassword.isBlank()) {
                            formError = "请填写管理员根密码"
                            showOwnerTakeover = false
                            showOwnerLogin = true
                            return@TextButton
                        }
                        runForegroundAction {
                            vm.submitFamilyWizard(
                                snapshot = onboardingFamilyWizardSnapshot(
                                    mode = FamilyWizardMode.Join,
                                    step = FamilyWizardStep.Identity,
                                    draft = joinDraft,
                                    displayName = "",
                                    deviceName = ownerDeviceName,
                                    joinRole = FamilyWizardJoinRole.Owner,
                                ),
                                bootstrapSecret = rootPassword,
                                ownerTakeover = true,
                            )
                            ownerRootPassword = ""
                        }
                    },
                    enabled = !familyWizardBusy,
                ) { Text(if (familyWizardBusy) "正在接管…" else "确认接管") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showOwnerTakeover = false
                        showOwnerLogin = true
                    },
                    enabled = !familyWizardBusy,
                ) { Text("取消") }
            },
        )
    }

    if (showJoin) {
        AlertDialog(
            onDismissRequest = { if (!familyWizardBusy) showJoin = false },
            title = { Text("申请在这台设备登录") },
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
                    OutlinedTextField(
                        value = joinDisplayName,
                        onValueChange = {
                            joinDisplayName = it
                            formError = null
                        },
                        label = { Text("我的家庭称呼") },
                        placeholder = { Text("如：妈妈、干妈、月嫂小王") },
                        supportingText = { Text("家庭称呼，必填；家人用这个认出你") },
                        enabled = !familyWizardBusy,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = memberDeviceName,
                        onValueChange = {
                            memberDeviceName = it
                            formError = null
                        },
                        label = { Text("这台设备的名称") },
                        supportingText = { Text("默认取自 Android 设备名，可修改") },
                        enabled = !familyWizardBusy,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "管理员会看到你的申请，并决定是否用这个称呼添加新成员。",
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    formError?.let { err ->
                        Text(err, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (joinDisplayName.isBlank()) {
                            formError = "请填写家庭称呼"
                            return@TextButton
                        }
                        runCatching { requireDeviceName(memberDeviceName) }
                            .exceptionOrNull()?.message?.let {
                                formError = it
                                return@TextButton
                            }
                        runForegroundAction {
                            formError = null
                            vm.submitFamilyWizard(
                                snapshot = onboardingFamilyWizardSnapshot(
                                    mode = FamilyWizardMode.Join,
                                    step = FamilyWizardStep.Identity,
                                    draft = joinDraft,
                                    displayName = joinDisplayName,
                                    deviceName = memberDeviceName,
                                    joinRole = FamilyWizardJoinRole.Member,
                                ),
                            )
                        }
                    },
                    enabled = !familyWizardBusy &&
                        joinDisplayName.isNotBlank() && memberDeviceName.isNotBlank(),
                ) { Text(if (familyWizardBusy) "正在发送…" else "发送确认请求") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showJoin = false
                        step = OnboardingStep.ChooseFamily
                    },
                    enabled = !familyWizardBusy,
                ) { Text("暂不连接，保持离线") }
            },
        )
    }

    val waitingRequest = pendingMemberLogin
    if (showMemberWaiting && waitingRequest != null) {
        AlertDialog(
            onDismissRequest = {
                if (!familyWizardBusy) showMemberWaiting = false
            },
            title = { Text("等待管理员确认") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
                    Text("已申请：${waitingRequest.displayName}")
                    Text("设备：${waitingRequest.deviceName}")
                    Text(
                        "申请将在 24 小时内失效。管理员下次前台打开 App 后可以批准或拒绝。",
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    (familyWizardState as? FamilyWizardState.WaitingForMemberApproval)
                        ?.feedback
                        ?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    TextButton(
                        onClick = {
                            vm.cancelMemberApproval()
                            showMemberWaiting = false
                            showJoin = true
                        },
                        enabled = !familyWizardBusy,
                    ) { Text("取消申请") }
                    TextButton(
                        onClick = {
                            showMemberWaiting = false
                            step = OnboardingStep.ChooseFamily
                        },
                        enabled = !familyWizardBusy,
                    ) { Text("暂不连接，保持离线") }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { runForegroundAction { vm.checkMemberApproval() } },
                    enabled = !familyWizardBusy,
                ) { Text(if (familyWizardBusy) "正在检查…" else "检查结果") }
            },
        )
    }

}

@Composable
private fun OnboardingMemberLoginQrDialog(
    payload: MemberLoginQrPayload,
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    feedback: String?,
    verifying: Boolean,
    submitting: Boolean,
    trustReady: Boolean,
    recoveryRetryRequired: Boolean,
    onLogin: () -> Unit,
    onRetryRecovery: () -> Unit,
    onManualJoin: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(if (verifying) "正在确认家庭服务器…" else "登录家庭") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
                if (verifying) {
                    Text("正在验证二维码中的 HTTPS 地址和证书信任信息；完成前不会发送登录授权。")
                } else {
                    payload.familyName?.let { Text(it, style = LeziTypography.TitleSm) }
                    Text("已由家庭管理员授权：${payload.memberDisplayName}")
                    OutlinedTextField(
                        value = deviceName,
                        onValueChange = onDeviceNameChange,
                        enabled = !submitting,
                        label = { Text("这台设备的名称 *") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("将信任管理员提供的家庭服务器配置。")
                    feedback?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = onManualJoin, enabled = !submitting) {
                        Text("改用加入家庭")
                    }
                }
            }
        },
        confirmButton = {
            if (!verifying) {
                TextButton(
                    onClick = if (recoveryRetryRequired) onRetryRecovery else onLogin,
                    enabled = (trustReady || recoveryRetryRequired) && !submitting,
                ) {
                    Text(
                        when {
                            submitting -> "同步中…"
                            recoveryRetryRequired -> "重试首次同步"
                            else -> "在这台设备登录"
                        },
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消") }
        },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
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
