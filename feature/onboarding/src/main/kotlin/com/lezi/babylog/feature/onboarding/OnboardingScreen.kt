package com.lezi.babylog.feature.onboarding

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.lezi.babylog.core.ui.CameraCapture
import com.lezi.babylog.core.ui.UiTags
import com.lezi.babylog.designsystem.LeziDatePicker
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.dismissKeyboardOnTap
import com.lezi.babylog.designsystem.LeziTextButton
import com.lezi.babylog.designsystem.LeziTextButtonTone
import com.lezi.babylog.domain.family.FamilyWizardJoinRole
import com.lezi.babylog.domain.family.FamilyWizardMode
import com.lezi.babylog.domain.family.FamilyWizardState
import com.lezi.babylog.domain.family.FamilyWizardStep
import com.lezi.babylog.domain.family.isBusy
import com.lezi.babylog.domain.family.projectMemberLoginQrDialog
import com.lezi.babylog.feature.onboarding.qr.OnboardingMemberLoginQrConfirm
import com.lezi.babylog.feature.onboarding.qr.OnboardingMemberLoginScanOutcome
import com.lezi.babylog.feature.onboarding.qr.parseOnboardingMemberLoginQrScan
import com.lezi.babylog.feature.onboarding.steps.ConnectServerPrimary
import com.lezi.babylog.feature.onboarding.steps.OnboardingChooseFamilyStep
import com.lezi.babylog.feature.onboarding.steps.OnboardingConnectServerStep
import com.lezi.babylog.feature.onboarding.steps.OnboardingCreateBabyStep
import com.lezi.babylog.feature.onboarding.steps.OnboardingCreateFamilyStep
import com.lezi.babylog.feature.onboarding.steps.OnboardingRecoveryCompleteStep
import com.lezi.babylog.feature.onboarding.steps.OnboardingRecoveryPendingStep
import com.lezi.babylog.feature.onboarding.steps.connectServerStepModel
import com.lezi.babylog.feature.onboarding.steps.onboardingBirthWeightError
import com.lezi.babylog.feature.onboarding.steps.onboardingLimitNickname
import com.lezi.babylog.feature.onboarding.wizard.OnboardingJoinRoleDialog
import com.lezi.babylog.feature.onboarding.wizard.OnboardingMemberJoinDialog
import com.lezi.babylog.feature.onboarding.wizard.OnboardingMemberWaitingDialog
import com.lezi.babylog.feature.onboarding.wizard.OnboardingOwnerLoginDialog
import com.lezi.babylog.feature.onboarding.wizard.OnboardingOwnerTakeoverDialog
import com.lezi.babylog.sync.session.FamilyEndpointConfig
import com.lezi.babylog.sync.session.FamilyEndpointDraft
import com.lezi.babylog.sync.session.defaultAndroidDeviceName
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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

/**
 * Navigation shell: collects host state, owns step/dialog visibility, and wires
 * wizard steps + QR UI. Business decisions stay on [OnboardingViewModel] /
 * [com.lezi.babylog.domain.family.FamilyWizardController].
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val familyWizardBusy = familyWizardState.isBusy
    val reclaimedFamilyEmpty by vm.reclaimedFamilyEmpty.collectAsState()
    val creatingBaby by vm.creatingBaby.collectAsState()
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
        // Do not overwrite a non-blank draft the user is editing with a prior trusted origin.
        if (endpointDraft.isBlank()) endpointDraft = verifiedEndpoint?.origin.orEmpty()
    }
    fun applyScannedMemberLogin(raw: String) {
        when (
            val outcome = parseOnboardingMemberLoginQrScan(
                raw = raw,
                nowEpochSeconds = System.currentTimeMillis() / 1_000,
            )
        ) {
            OnboardingMemberLoginScanOutcome.Empty -> Unit
            is OnboardingMemberLoginScanOutcome.Rejected -> formError = outcome.message
            is OnboardingMemberLoginScanOutcome.Ready -> {
                memberQrDeviceName = defaultAndroidDeviceName(context)
                formError = null
                vm.verifyMemberLoginQr(outcome.payload)
            }
        }
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
            .padding(LeziSpacing.Page)
            .testTag(UiTags.ONBOARDING),
        verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
    ) {
        Text("欢迎使用乐记", style = LeziTypography.Title)
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                (
                    fadeIn(animationSpec = tween(durationMillis = 250)) +
                        slideInHorizontally(
                            animationSpec = tween(durationMillis = 250),
                            initialOffsetX = { width -> width / 8 },
                        )
                    ).togetherWith(fadeOut(animationSpec = tween(durationMillis = 150)))
            },
            label = "onboardingStep",
        ) { targetStep ->
            // Step composables emit multiple siblings; keep a Column so they
            // retain spacedBy layout once lifted out of the outer Column into
            // AnimatedContent (which does not arrange multi-root content).
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
        when (targetStep) {
            OnboardingStep.ChooseFamily -> {
                OnboardingChooseFamilyStep(
                    verifiedEndpoint = verifiedEndpoint,
                    pendingMemberLogin = pendingMemberLogin,
                    onConnectOrResume = {
                        if (pendingMemberLogin != null) {
                            showMemberWaiting = true
                        } else {
                            formError = null
                            endpointDraft = verifiedEndpoint?.origin.orEmpty()
                            step = OnboardingStep.ConnectServer
                            if (endpointDraft.isNotBlank()) vm.connectEndpoint(endpointDraft)
                        }
                    },
                    onScanMemberLogin = ::requestOrLaunchMemberLoginScan,
                    onForgetEndpoint = {
                        vm.forgetEndpoint()
                        endpointDraft = ""
                    },
                    onOfflineMode = {
                        formError = null
                        step = OnboardingStep.CreateBaby
                    },
                )
            }
            OnboardingStep.ConnectServer -> {
                val connectModel = connectServerStepModel(familyWizardState)
                OnboardingConnectServerStep(
                    model = connectModel,
                    familyWizardBusy = familyWizardBusy,
                    endpointDraft = endpointDraft,
                    onEndpointDraftChange = {
                        endpointDraft = it
                        if (familyWizardState is FamilyWizardState.EndpointFailure) {
                            vm.keepOffline()
                        }
                    },
                    onPrimaryAction = {
                        when (val decision = connectModel.primary) {
                            is ConnectServerPrimary.Trust ->
                                vm.trustCertificate(decision.candidate)
                            ConnectServerPrimary.ForgetAndReconnect -> {
                                vm.forgetEndpoint()
                                endpointDraft = ""
                            }
                            ConnectServerPrimary.Connect ->
                                vm.connectEndpoint(endpointDraft)
                            is ConnectServerPrimary.ContinueWithReady -> {
                                endpointDraft = decision.origin
                                joinDraft = FamilyEndpointDraft(
                                    host = decision.origin,
                                    portText = "443",
                                    scheme = "https",
                                )
                                when (decision.mode) {
                                    FamilyWizardMode.Create -> step = OnboardingStep.CreateFamily
                                    FamilyWizardMode.Join -> showJoinRole = true
                                }
                            }
                        }
                    },
                    onReturnToAddress = { vm.keepOffline() },
                    onKeepOffline = {
                        vm.keepOffline()
                        step = OnboardingStep.ChooseFamily
                    },
                )
            }
            OnboardingStep.CreateFamily -> {
                OnboardingCreateFamilyStep(
                    verifiedOrigin = verifiedEndpoint?.origin,
                    createFamilyName = createFamilyName,
                    onCreateFamilyNameChange = { createFamilyName = it },
                    createDisplayName = createDisplayName,
                    onCreateDisplayNameChange = { createDisplayName = it },
                    createDeviceName = createDeviceName,
                    onCreateDeviceNameChange = { createDeviceName = it },
                    bootstrapSecret = bootstrapSecret,
                    onBootstrapSecretChange = { bootstrapSecret = it },
                    formError = formError,
                    familyWizardBusy = familyWizardBusy,
                    onSubmit = {
                        val oneTimeRootPassword = bootstrapSecret
                        onboardingCreateFamilySubmitError(
                            displayName = createDisplayName,
                            familyName = createFamilyName,
                            deviceName = createDeviceName,
                            bootstrapSecret = oneTimeRootPassword,
                        )?.let {
                            formError = it
                            return@OnboardingCreateFamilyStep
                        }
                        formError = null
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
                    },
                    onBack = {
                        bootstrapSecret = ""
                        step = OnboardingStep.ChooseFamily
                    },
                )
            }
            OnboardingStep.CreateBaby -> {
                val createBabySource = onboardingCreateBabySource(familyWizardState)
                OnboardingCreateBabyStep(
                    createBabySource = createBabySource,
                    creatingBaby = creatingBaby,
                    name = name,
                    onNameChange = {
                        name = onboardingLimitNickname(it)
                        nameError = false
                    },
                    nameError = nameError,
                    sex = sex,
                    onSexChange = { sex = it },
                    dateLabel = dateLabel,
                    onShowDate = { showDate = true },
                    weightText = weightText,
                    onWeightTextChange = { weightText = it },
                    themeIdx = themeIdx,
                    onThemeIdxChange = { themeIdx = it },
                    formError = formError,
                    onSubmit = { themeColorArgb, grams ->
                        if (name.trim().isEmpty()) {
                            nameError = true
                            return@OnboardingCreateBabyStep
                        }
                        onboardingBirthWeightError(grams)?.let {
                            formError = it
                            return@OnboardingCreateBabyStep
                        }
                        vm.createBaby(
                            nickname = name,
                            sex = sex,
                            birthdayEpochDay = birthday,
                            birthWeightGrams = grams,
                            themeColorArgb = themeColorArgb,
                            onDone = { error ->
                                if (error == null) onFinished() else formError = error
                            },
                        )
                    },
                    onBack = { if (!creatingBaby) step = OnboardingStep.ChooseFamily },
                )
            }
            OnboardingStep.RecoveryPending -> {
                OnboardingRecoveryPendingStep(
                    recoveryFailure = familyWizardState as? FamilyWizardState.RetryableFailure,
                    familyWizardBusy = familyWizardBusy,
                    onRetry = vm::retryInitialFamilyDataRecovery,
                )
            }
            OnboardingStep.RecoveryComplete -> {
                OnboardingRecoveryCompleteStep()
            }
        }
            }
        }
    }

    if (showDate) {
        val initialMillis = birthday.toDatePickerMillis()
        val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                LeziTextButton(label = "确定", onClick = {
                        state.selectedDateMillis?.let { ms ->
                            birthday = ms.datePickerMillisToEpochDay()
                        }
                        showDate = false
                    }, tone = LeziTextButtonTone.Primary)
            },
            dismissButton = {
                LeziTextButton(label = "取消", onClick = { showDate = false })
            },
        ) {
            LeziDatePicker(state = state)
        }
    }

    // Shared projection with Account so verify-retry / claim / cancel cannot drift.
    val memberLoginQrModel = projectMemberLoginQrDialog(familyWizardState)
    if (memberLoginQrModel != null && memberLoginQrModel.payload != null) {
        val payload = memberLoginQrModel.payload!!
        OnboardingMemberLoginQrConfirm(
            model = memberLoginQrModel,
            deviceName = memberQrDeviceName,
            onDeviceNameChange = { memberQrDeviceName = it },
            onConfirm = {
                when {
                    memberLoginQrModel.verificationRetryRequired ->
                        vm.verifyMemberLoginQr(payload)
                    memberLoginQrModel.recoveryRetryRequired ->
                        vm.retryInitialFamilyDataRecovery()
                    else ->
                        // Controller owns device-name validation → Ready.feedback.
                        vm.claimMemberLoginQr(payload, memberQrDeviceName)
                }
            },
            onManualJoin = {
                vm.cancelMemberLoginQr()
                endpointDraft = payload.endpoint.origin
                step = OnboardingStep.ConnectServer
                vm.connectEndpoint(endpointDraft)
            },
            onDismiss = { vm.cancelMemberLoginQr() },
        )
    }

    if (showJoinRole) {
        OnboardingJoinRoleDialog(
            familyWizardBusy = familyWizardBusy,
            onOwner = {
                formError = null
                showJoinRole = false
                showOwnerLogin = true
            },
            onMember = {
                formError = null
                showJoinRole = false
                showJoin = true
            },
            onDismiss = { showJoinRole = false },
        )
    }

    if (showOwnerLogin) {
        OnboardingOwnerLoginDialog(
            ownerDeviceName = ownerDeviceName,
            onOwnerDeviceNameChange = {
                ownerDeviceName = it
                formError = null
            },
            ownerRootPassword = ownerRootPassword,
            onOwnerRootPasswordChange = {
                ownerRootPassword = it
                formError = null
            },
            formError = formError,
            familyWizardBusy = familyWizardBusy,
            onLogin = {
                val rootPassword = ownerRootPassword
                onboardingOwnerLoginSubmitError(ownerDeviceName, rootPassword)?.let {
                    formError = it
                    return@OnboardingOwnerLoginDialog
                }
                formError = null
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
            },
            onTakeover = {
                onboardingOwnerLoginSubmitError(ownerDeviceName, ownerRootPassword)?.let {
                    formError = it
                    return@OnboardingOwnerLoginDialog
                }
                formError = null
                showOwnerLogin = false
                showOwnerTakeover = true
            },
            onBack = {
                ownerRootPassword = ""
                showOwnerLogin = false
                showJoinRole = true
            },
            onDismiss = {
                if (!familyWizardBusy) {
                    ownerRootPassword = ""
                    showOwnerLogin = false
                }
            },
        )
    }

    if (showOwnerTakeover) {
        OnboardingOwnerTakeoverDialog(
            familyWizardBusy = familyWizardBusy,
            onConfirm = {
                val rootPassword = ownerRootPassword
                if (rootPassword.isBlank()) {
                    formError = "请填写管理员根密码"
                    showOwnerTakeover = false
                    showOwnerLogin = true
                    return@OnboardingOwnerTakeoverDialog
                }
                formError = null
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
            },
            onCancel = {
                if (!familyWizardBusy) {
                    showOwnerTakeover = false
                    showOwnerLogin = true
                }
            },
        )
    }

    if (showJoin) {
        OnboardingMemberJoinDialog(
            joinDisplayName = joinDisplayName,
            onJoinDisplayNameChange = {
                joinDisplayName = it
                formError = null
            },
            memberDeviceName = memberDeviceName,
            onMemberDeviceNameChange = {
                memberDeviceName = it
                formError = null
            },
            formError = formError,
            familyWizardBusy = familyWizardBusy,
            onSubmit = {
                onboardingMemberJoinSubmitError(joinDisplayName, memberDeviceName)?.let {
                    formError = it
                    return@OnboardingMemberJoinDialog
                }
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
            },
            onKeepOffline = {
                showJoin = false
                step = OnboardingStep.ChooseFamily
            },
            onDismiss = { showJoin = false },
        )
    }

    val waitingRequest = pendingMemberLogin
    if (showMemberWaiting && waitingRequest != null) {
        OnboardingMemberWaitingDialog(
            waitingRequest = waitingRequest,
            familyWizardState = familyWizardState,
            familyWizardBusy = familyWizardBusy,
            onCancelRequest = {
                vm.cancelMemberApproval()
                showMemberWaiting = false
                showJoin = true
            },
            onKeepOffline = {
                showMemberWaiting = false
                step = OnboardingStep.ChooseFamily
            },
            onCheckResult = { vm.checkMemberApproval() },
            onDismiss = { showMemberWaiting = false },
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
