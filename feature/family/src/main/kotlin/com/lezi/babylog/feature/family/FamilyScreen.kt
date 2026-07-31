package com.lezi.babylog.feature.family

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.lezi.babylog.core.ui.CameraCapture
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.PageScaffoldBackground
import com.lezi.babylog.domain.FamilyWizardState
import com.lezi.babylog.domain.familyWizardEndpointValidationError
import com.lezi.babylog.sync.FamilyRole
import com.lezi.babylog.sync.FamilyEndpointConfig
import com.lezi.babylog.sync.FamilyEndpointDraft
import com.lezi.babylog.sync.MemberLoginQrPayloadCodec
import com.lezi.babylog.sync.SetupFamilyState
import com.lezi.babylog.sync.SetupProbeResult
import com.lezi.babylog.sync.defaultAndroidDeviceName
import com.lezi.babylog.sync.requireDeviceName

private val FamilyEndpointDraftSaver = listSaver<FamilyEndpointDraft, String>(
    save = {
        listOf(it.host, it.portText, it.scheme)
    },
    restore = {
        FamilyEndpointDraft(
            host = it[0],
            portText = it[1],
            scheme = it[2],
        )
    },
)

@Composable
fun FamilyRoute(
    onAddBaby: () -> Unit = {},
    vm: FamilyViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<FamilyDialog?>(null) }
    var retainedWizardMode by rememberSaveable { mutableStateOf<String?>(null) }
    var retainedWizardStep by rememberSaveable { mutableStateOf<String?>(null) }
    var bootstrapSecret by remember { mutableStateOf("") }
    var bootstrapSecretFeedback by rememberSaveable { mutableStateOf<String?>(null) }
    var createDisplayName by rememberSaveable { mutableStateOf("") }
    var createDisplayNameError by rememberSaveable { mutableStateOf<String?>(null) }
    var createFamilyName by rememberSaveable { mutableStateOf("") }
    var createDeviceName by remember { mutableStateOf(defaultAndroidDeviceName(context)) }
    var createDeviceNameError by remember { mutableStateOf<String?>(null) }
    var createFamilyNameError by rememberSaveable { mutableStateOf<String?>(null) }
    var joinDisplayName by rememberSaveable { mutableStateOf("") }
    var joinDisplayNameError by rememberSaveable { mutableStateOf<String?>(null) }
    var memberDeviceName by remember { mutableStateOf(defaultAndroidDeviceName(context)) }
    var memberDeviceNameError by remember { mutableStateOf<String?>(null) }
    var joinRoleName by rememberSaveable { mutableStateOf<String?>(null) }
    var ownerDeviceName by remember { mutableStateOf(defaultAndroidDeviceName(context)) }
    var ownerDeviceNameError by remember { mutableStateOf<String?>(null) }
    var ownerRootPassword by remember { mutableStateOf("") }
    var ownerRootPasswordFeedback by remember { mutableStateOf<String?>(null) }
    var editDisplayName by remember { mutableStateOf("") }
    var editDisplayNameFeedback by remember { mutableStateOf<String?>(null) }
    var savingDisplayName by remember { mutableStateOf(false) }
    var renameFamilyName by remember { mutableStateOf("") }
    var renameFamilyFeedback by remember { mutableStateOf<String?>(null) }
    var savingFamilyName by remember { mutableStateOf(false) }
    var wizardNetworkFeedback by rememberSaveable { mutableStateOf<String?>(null) }
    var removingMember by remember { mutableStateOf(false) }
    var decidingMemberRequest by remember { mutableStateOf(false) }
    var endpointDraft by remember { mutableStateOf("") }
    // QR grant and device draft are intentionally process-memory only, never rememberSaveable.
    var memberQrDeviceName by remember { mutableStateOf(defaultAndroidDeviceName(context)) }
    var memberQrFeedback by remember { mutableStateOf<String?>(null) }
    var memberQrSubmitting by remember { mutableStateOf(false) }
    // Destructive confirmation, including the root password, is process-memory only.
    var deleteFamilyName by remember { mutableStateOf("") }
    var deleteFamilyRootPassword by remember { mutableStateOf("") }
    var deleteFamilyFeedback by remember { mutableStateOf<String?>(null) }
    var deletingFamily by remember { mutableStateOf(false) }

    val familyWizardState by vm.familyWizardState.collectAsStateWithLifecycle()
    val verifiedEndpoint by vm.verifiedEndpoint.collectAsStateWithLifecycle(initialValue = null)
    val novice = remember { FamilyEndpointConfig.emptyDraft() }
    fun draftFromUiOrNovice(): FamilyEndpointDraft {
        val saved = when {
            ui.serverHost.isNotBlank() -> FamilyEndpointConfig(
                host = ui.serverHost,
                port = ui.serverPort,
                scheme = ui.serverScheme,
            )
            ui.baseUrl.isNotBlank() -> FamilyEndpointConfig.fromBaseUrl(ui.baseUrl)
            else -> novice
        }
        return FamilyEndpointDraft.fromConfig(saved)
    }
    // Keep in-progress endpoint edits stable while the wizard is active.
    var joinDraft by rememberSaveable(stateSaver = FamilyEndpointDraftSaver) {
        mutableStateOf(draftFromUiOrNovice())
    }
    val pendingMemberLogin = ui.pendingMemberLogin
    val familyWizardBusy = familyWizardState is FamilyWizardState.Submitting ||
        familyWizardState is FamilyWizardState.ProbingEndpoint
    val wizardSessionActive = isWizardSessionDialog(dialog)
    LaunchedEffect(
        ui.serverHost,
        ui.serverPort,
        ui.serverScheme,
        ui.baseUrl,
        wizardSessionActive,
    ) {
        if (wizardSessionActive) return@LaunchedEffect
        joinDraft = draftFromUiOrNovice()
    }

    fun showMessage(copy: String, resume: FamilyDialog? = null) {
        dialog = FamilyDialog.Message(copy, resume)
    }
    fun showWizard(mode: FamilyWizardMode, step: FamilyWizardStep) {
        retainedWizardMode = mode.name
        retainedWizardStep = step.name
        dialog = FamilyDialog.Wizard(mode, step)
    }
    LaunchedEffect(Unit) {
        val restoredMode = retainedWizardMode?.let {
            runCatching { FamilyWizardMode.valueOf(it) }.getOrNull()
        }
        val restoredStep = retainedWizardStep?.let {
            runCatching { FamilyWizardStep.valueOf(it) }.getOrNull()
        }
        if (dialog == null && restoredMode != null && restoredStep != null) {
            dialog = FamilyDialog.Wizard(restoredMode, restoredStep)
        }
    }
    fun finalWizardDismiss() {
        ownerRootPassword = ""
        ownerRootPasswordFeedback = null
        ownerDeviceNameError = null
        joinRoleName = null
        wizardNetworkFeedback = null
        retainedWizardMode = null
        retainedWizardStep = null
        dialog = null
    }
    fun dismissDialog() {
        val current = dialog
        val next = current?.let(::familyDialogAfterDismiss)
        if (next == null && isWizardSessionDialog(current)) {
            finalWizardDismiss()
        } else {
            dialog = next
        }
    }
    fun resetDeleteFamilyConfirmation() {
        deleteFamilyName = ""
        deleteFamilyRootPassword = ""
        deleteFamilyFeedback = null
        deletingFamily = false
    }

    fun runForegroundAction(action: () -> Unit) = action()

    LaunchedEffect(ui.enabled, ui.familyId) {
        if (ui.enabled) vm.refreshMembers(showErrors = false)
    }

    fun openWizard(mode: FamilyWizardMode) {
        wizardNetworkFeedback = null
        val prefsConfigured = isEndpointConfigured(
            ui.serverHost,
            ui.baseUrl,
        )
        val step = if (mode == FamilyWizardMode.Join && prefsConfigured) {
            FamilyWizardStep.Role
        } else {
            familyWizardInitialStep(endpointConfigured = prefsConfigured)
        }
        if (mode == FamilyWizardMode.Join) joinRoleName = null
        vm.beginFamilyWizard(
            accountFamilyWizardSnapshot(
                mode = mode,
                step = step,
                draft = joinDraft,
                displayName = if (mode == FamilyWizardMode.Create) {
                    createDisplayName
                } else {
                    joinDisplayName
                },
                familyName = createFamilyName,
                deviceName = createDeviceName,
                joinRole = joinRoleName?.let(FamilyWizardJoinRole::valueOf),
            ),
        )
        showWizard(mode, step)
    }

    fun openEndpointConnection() {
        if (pendingMemberLogin != null) {
            retainedWizardMode = FamilyWizardMode.Join.name
            retainedWizardStep = FamilyWizardStep.Identity.name
            dialog = FamilyDialog.Wizard(FamilyWizardMode.Join, FamilyWizardStep.Identity)
            return
        }
        endpointDraft = verifiedEndpoint?.origin.orEmpty()
        dialog = FamilyDialog.ConnectEndpoint
    }

    fun applyScannedMemberLogin(raw: String) {
        val payload = raw.trim()
        if (payload.isEmpty()) return
        val memberLogin = runCatching { MemberLoginQrPayloadCodec.decode(payload) }.getOrNull()
        if (memberLogin != null) {
            if (System.currentTimeMillis() / 1_000 >= memberLogin.expiresAtEpochSeconds) {
                showMessage("这个二维码已失效，请让管理员重新生成")
                return
            }
            memberQrFeedback = null
            memberQrDeviceName = defaultAndroidDeviceName(context)
            dialog = FamilyDialog.VerifyingMemberLoginQr(memberLogin)
            vm.verifyMemberLoginQr(memberLogin) { result ->
                if ((dialog as? FamilyDialog.VerifyingMemberLoginQr)?.payload != memberLogin) {
                    return@verifyMemberLoginQr
                }
                dialog = when (result) {
                    is SetupProbeResult.Ready -> if (
                        result.endpoint == memberLogin.endpoint &&
                        result.familyState == SetupFamilyState.Configured
                    ) {
                        FamilyDialog.ConfirmMemberLoginQr(memberLogin)
                    } else {
                        FamilyDialog.Message("这个二维码对应的服务器尚未配置家庭")
                    }
                    SetupProbeResult.Failed.CertificateChanged -> FamilyDialog.Message(
                        "家庭服务器安全信息不一致，登录已停止",
                    )
                    else -> FamilyDialog.Message("暂时无法确认二维码中的家庭服务器，请稍后重试")
                }
            }
            return
        }
        showMessage("这不是可用的成员登录二维码")
    }
    val scanMemberLogin = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(::applyScannedMemberLogin)
    }
    fun launchMemberLoginScan() {
        if (!CameraCapture.hasCameraHardware(context)) {
            showMessage("此设备没有可用相机，请使用家庭服务器地址手动申请加入", dialog)
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
        if (granted) launchMemberLoginScan()
        else showMessage("需要相机权限才能扫码，请在系统设置中开启", dialog)
    }
    fun scanWithPermission() {
        if (CameraCapture.hasPermission(context)) launchMemberLoginScan()
        else scanCameraPermission.launch(CameraCapture.PERMISSION)
    }

    val endpointConfigured = remember(ui.serverHost, ui.baseUrl) {
        isEndpointConfigured(ui.serverHost, ui.baseUrl)
    }
    val draftEndpointReady = joinDraft.hasEndpoint()
    val controls = remember(ui.enabled, ui.role) {
        familyControlVisibility(ui.enabled, ui.role)
    }
    val primary = remember(ui.enabled, ui.role, endpointConfigured) {
        familyPrimarySurface(ui.enabled, ui.role, endpointConfigured)
    }
    /** Both entries validate the same retained draft; submit owns the only config write. */
    fun advanceWizardNetwork(mode: FamilyWizardMode) {
        val snapshot = accountFamilyWizardSnapshot(
            mode = mode,
            step = FamilyWizardStep.Endpoint,
            draft = joinDraft,
            displayName = if (mode == FamilyWizardMode.Create) {
                createDisplayName
            } else {
                joinDisplayName
            },
            familyName = createFamilyName,
            deviceName = createDeviceName,
        )
        familyWizardEndpointValidationError(snapshot)?.let { message ->
            wizardNetworkFeedback = message
            showWizard(mode, FamilyWizardStep.Endpoint)
            return
        }
        wizardNetworkFeedback = null
        showWizard(
            mode,
            if (mode == FamilyWizardMode.Join) FamilyWizardStep.Role else FamilyWizardStep.Identity,
        )
    }

    LaunchedEffect(familyWizardState) {
        when (val state = familyWizardState) {
            is FamilyWizardState.RetryableFailure -> {
                val resume = FamilyDialog.Wizard(state.snapshot.mode, state.snapshot.step)
                retainedWizardMode = state.snapshot.mode.name
                retainedWizardStep = state.snapshot.step.name
                when {
                    state.snapshot.step == FamilyWizardStep.Endpoint -> {
                        wizardNetworkFeedback = state.message
                        dialog = resume
                    }
                    state.snapshot.mode == FamilyWizardMode.Create -> {
                        when {
                            state.message.contains("称呼") || state.message.contains("本机") ->
                                createDisplayNameError = state.message
                            state.message.contains("家庭名") ->
                                createFamilyNameError = state.message
                            else -> bootstrapSecretFeedback = state.message
                        }
                        dialog = resume
                    }
                    state.snapshot.joinRole == FamilyWizardJoinRole.Owner -> {
                        if (state.message.contains("设备")) {
                            ownerDeviceNameError = state.message
                        } else {
                            ownerRootPasswordFeedback = state.message
                        }
                        dialog = resume
                    }
                    else -> {
                        joinDisplayNameError = state.message.takeIf {
                            it.contains("称呼") || it.contains("本机")
                        }
                        if (joinDisplayNameError != null) {
                            dialog = resume
                        } else {
                            showMessage(state.message, resume)
                        }
                    }
                }
            }
            is FamilyWizardState.Completed -> {
                vm.consumeFamilyWizardCompletion()?.let { outcome ->
                    bootstrapSecret = ""
                    bootstrapSecretFeedback = null
                    createDisplayName = ""
                    createDisplayNameError = null
                    createFamilyName = ""
                    createFamilyNameError = null
                    joinDisplayName = ""
                    joinDisplayNameError = null
                    memberDeviceNameError = null
                    joinRoleName = null
                    ownerDeviceNameError = null
                    ownerRootPassword = ""
                    ownerRootPasswordFeedback = null
                    retainedWizardMode = null
                    retainedWizardStep = null
                    dialog = FamilyDialog.Message(familyWizardOutcomeCopy(outcome))
                }
            }
            is FamilyWizardState.WaitingForMemberApproval -> {
                retainedWizardMode = FamilyWizardMode.Join.name
                retainedWizardStep = FamilyWizardStep.Identity.name
                dialog = FamilyDialog.Wizard(FamilyWizardMode.Join, FamilyWizardStep.Identity)
            }
            is FamilyWizardState.Editing,
            is FamilyWizardState.CertificateApprovalRequired,
            is FamilyWizardState.EndpointFailure,
            is FamilyWizardState.EndpointReady,
            is FamilyWizardState.ProbingEndpoint,
            is FamilyWizardState.Submitting,
            -> Unit
        }
    }

    PageScaffoldBackground {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LeziSpacing.Page),
            verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
        ) {
            FamilyOverview(
                ui = ui,
                onAddBaby = onAddBaby,
                onSetCurrent = vm::setCurrent,
                onEditBaby = { dialog = FamilyDialog.EditBaby(it) },
                onMergeBaby = { dialog = FamilyDialog.MergeBaby(it) },
                onDeleteBaby = { dialog = FamilyDialog.DeleteBaby(it) },
            )
            FamilySharingContent(
                ui = ui,
                primary = primary,
                endpointConfigured = endpointConfigured,
                onOpenMembers = {
                    runForegroundAction {
                        vm.refreshMembers(showErrors = true)
                        dialog = FamilyDialog.MembersList
                    }
                },
                onConnectFamily = ::openEndpointConnection,
                onScanMemberLoginQr = ::scanWithPermission,
                onLogoutCurrentDevice = { dialog = FamilyDialog.ConfirmDeviceLogout },
                onLeaveFamily = { dialog = FamilyDialog.ConfirmLeave },
                onDeleteFamily = {
                    resetDeleteFamilyConfirmation()
                    dialog = FamilyDialog.DeleteFamily(FamilyDialog.DeleteStage.Warning)
                },
            )
        }
    }

    when (val active = dialog) {
        FamilyDialog.ConnectEndpoint -> FamilyEndpointConnectionDialog(
            state = familyWizardState,
            endpointDraft = endpointDraft,
            onEndpointDraftChange = {
                endpointDraft = it
                if (familyWizardState is FamilyWizardState.EndpointFailure) {
                    vm.keepOffline()
                }
            },
            onConnect = { vm.connectEndpoint(endpointDraft) },
            onTrustCertificate = vm::trustCertificate,
            onContinue = { snapshot ->
                val endpoint = snapshot.endpointDraft
                val identity = snapshot.copy(
                    host = endpoint,
                    portText = "443",
                    scheme = "https",
                )
                joinDraft = FamilyEndpointDraft(
                    host = endpoint,
                    portText = "443",
                    scheme = "https",
                )
                vm.beginFamilyWizard(identity)
                if (identity.mode == FamilyWizardMode.Join) joinRoleName = null
                showWizard(identity.mode, identity.step)
            },
            onForget = {
                vm.forgetEndpoint()
                endpointDraft = ""
            },
            onReturnToAddress = { vm.keepOffline() },
            onKeepOffline = {
                vm.keepOffline()
                dialog = null
            },
        )
        FamilyDialog.MembersList -> if (ui.enabled) FamilyMembersListSheet(
            ui = ui,
            onRefreshMembers = { runForegroundAction { vm.refreshMembers(showErrors = true) } },
            onEditMyDisplayName = {
                editDisplayName = ui.displayName.takeUnless {
                    it == LOCAL_FAMILY_DISPLAY_NAME
                }.orEmpty()
                editDisplayNameFeedback = null
                dialog = FamilyDialog.EditMyDisplayName
            },
            onRenameFamily = if (ui.role == FamilyRole.Owner) {
                {
                    renameFamilyName = ui.familyName.orEmpty()
                    renameFamilyFeedback = null
                    dialog = FamilyDialog.RenameFamily
                }
            } else {
                null
            },
            onRemoveMember = if (controls.showRemoveMember) {
                { membershipId, displayName ->
                    dialog = FamilyDialog.ConfirmRemoveMember(membershipId, displayName)
                }
            } else {
                null
            },
            onReviewPending = if (ui.role == FamilyRole.Owner) {
                { request -> dialog = FamilyDialog.ReviewPendingMember(request) }
            } else {
                null
            },
            onCreateMemberLoginQr = if (ui.role == FamilyRole.Owner) {
                { membershipId ->
                    runForegroundAction {
                        vm.createMemberLoginQr(membershipId) { result ->
                            result.fold(
                                onSuccess = { dialog = FamilyDialog.MemberLoginQrCode(it) },
                                onFailure = {
                                    showMessage(
                                        it.message ?: "生成成员登录二维码失败，请稍后重试",
                                        resume = FamilyDialog.MembersList,
                                    )
                                },
                            )
                        }
                    }
                }
            } else {
                null
            },
            onAddMember = if (ui.role == FamilyRole.Owner) {
                {
                    editDisplayName = ""
                    editDisplayNameFeedback = null
                    dialog = FamilyDialog.AddFamilyMember
                }
            } else {
                null
            },
            onRenameMember = if (ui.role == FamilyRole.Owner) {
                { membershipId, currentDisplayName ->
                    editDisplayName = currentDisplayName
                    editDisplayNameFeedback = null
                    dialog = FamilyDialog.RenameFamilyMember(membershipId, currentDisplayName)
                }
            } else {
                null
            },
            onRenameDevice = { deviceId, currentDeviceName ->
                editDisplayName = currentDeviceName
                editDisplayNameFeedback = null
                dialog = FamilyDialog.RenameFamilyDevice(deviceId, currentDeviceName)
            },
            onRevokeDevice = { deviceId, deviceName, isCurrent ->
                dialog = FamilyDialog.ConfirmDeviceRevoke(deviceId, deviceName, isCurrent)
            },
            onReviewRename = if (ui.role == FamilyRole.Owner) {
                { request, approve ->
                    runForegroundAction {
                        vm.decideMemberRename(request, approve) { _, copy ->
                            showMessage(copy, resume = FamilyDialog.MembersList)
                        }
                    }
                }
            } else {
                null
            },
            onDismiss = { dialog = null },
        )
        is FamilyDialog.VerifyingMemberLoginQr -> MemberLoginQrConfirmDialog(
            payload = active.payload,
            deviceName = memberQrDeviceName,
            onDeviceNameChange = {},
            feedback = "正在确认家庭服务器…",
            submitting = true,
            onLogin = {},
            onManualJoin = {},
            onDismiss = {},
        )
        is FamilyDialog.ConfirmMemberLoginQr -> MemberLoginQrConfirmDialog(
            payload = active.payload,
            deviceName = memberQrDeviceName,
            onDeviceNameChange = {
                memberQrDeviceName = it
                memberQrFeedback = null
            },
            feedback = memberQrFeedback,
            submitting = memberQrSubmitting,
            onLogin = {
                runCatching { requireDeviceName(memberQrDeviceName) }
                    .exceptionOrNull()?.message?.let {
                        memberQrFeedback = it
                        return@MemberLoginQrConfirmDialog
                    }
                memberQrSubmitting = true
                vm.claimMemberLoginQr(active.payload, memberQrDeviceName) { error ->
                    memberQrSubmitting = false
                    if (error == null) {
                        dialog = FamilyDialog.Message("已在这台设备登录家庭")
                    } else {
                        memberQrFeedback = error
                    }
                }
            },
            onManualJoin = {
                memberQrSubmitting = false
                memberQrFeedback = null
                endpointDraft = active.payload.endpoint.origin
                dialog = FamilyDialog.ConnectEndpoint
            },
            onDismiss = {
                if (!memberQrSubmitting) {
                    memberQrFeedback = null
                    dialog = null
                }
            },
        )
        is FamilyDialog.MemberLoginQrCode -> MemberLoginQrCodeDialog(
            payload = active.payload,
            onDismiss = { dialog = FamilyDialog.MembersList },
        )
        is FamilyDialog.ReviewPendingMember -> if (ui.enabled && ui.role == FamilyRole.Owner) {
            fun finishDecision(error: String?) {
                decidingMemberRequest = false
                dialog = if (error == null) {
                    FamilyDialog.MembersList
                } else {
                    FamilyDialog.Message(error, resume = active)
                }
            }
            PendingMemberDecisionDialog(
                request = active.request,
                members = ui.members,
                busy = decidingMemberRequest,
                onBindExisting = { membershipId ->
                    decidingMemberRequest = true
                    vm.bindExistingMemberLogin(
                        active.request.requestId,
                        membershipId,
                        ::finishDecision,
                    )
                },
                onApproveNew = {
                    decidingMemberRequest = true
                    vm.approveNewMemberLogin(active.request.requestId, ::finishDecision)
                },
                onReject = {
                    decidingMemberRequest = true
                    vm.rejectMemberLogin(active.request.requestId, ::finishDecision)
                },
                onDismiss = {
                    if (!decidingMemberRequest) dialog = FamilyDialog.MembersList
                },
            )
        }
        is FamilyDialog.ConfirmRemoveMember -> RemoveMemberConfirmDialog(
            displayName = active.displayName,
            removing = removingMember,
            onConfirm = {
                runForegroundAction {
                    removingMember = true
                    vm.removeMember(
                        membershipId = active.membershipId,
                        displayName = active.displayName,
                    ) { success, copy ->
                        removingMember = false
                        if (success) {
                            dialog = FamilyDialog.Message(
                                copy,
                                resume = FamilyDialog.MembersList,
                            )
                        } else {
                            showMessage(copy, resume = FamilyDialog.MembersList)
                        }
                    }
                }
            },
            onDismiss = {
                if (!removingMember) dialog = FamilyDialog.MembersList
            },
        )
        is FamilyDialog.Wizard -> if (pendingMemberLogin != null) {
            MemberApprovalWaitingDialog(
                request = pendingMemberLogin,
                checking = familyWizardState is FamilyWizardState.Submitting,
                onCheck = {
                    runForegroundAction { vm.checkMemberApproval() }
                },
                onCancel = {
                    vm.cancelMemberApproval()
                    finalWizardDismiss()
                },
                onKeepOffline = ::finalWizardDismiss,
            )
        } else when (active.step) {
            FamilyWizardStep.Endpoint -> FamilyWizardEndpointDialog(
                mode = active.mode,
                host = joinDraft.host,
                onHostChange = {
                    joinDraft = joinDraft.copy(host = it)
                    wizardNetworkFeedback = null
                },
                port = joinDraft.portText,
                onPortChange = {
                    joinDraft = joinDraft.copy(portText = it)
                    wizardNetworkFeedback = null
                },
                endpointReady = draftEndpointReady,
                feedback = wizardNetworkFeedback,
                saving = false,
                onContinue = { advanceWizardNetwork(active.mode) },
                onDismiss = {
                    finalWizardDismiss()
                },
            )
            FamilyWizardStep.Role -> FamilyJoinRoleDialog(
                busy = familyWizardBusy,
                onOwner = {
                    joinRoleName = FamilyWizardJoinRole.Owner.name
                    showWizard(FamilyWizardMode.Join, FamilyWizardStep.Identity)
                },
                onMember = {
                    joinRoleName = FamilyWizardJoinRole.Member.name
                    showWizard(FamilyWizardMode.Join, FamilyWizardStep.Identity)
                },
                onBackToEndpoint = {
                    showWizard(FamilyWizardMode.Join, FamilyWizardStep.Endpoint)
                },
                onDismiss = ::finalWizardDismiss,
            )
            FamilyWizardStep.Identity -> when (active.mode) {
                FamilyWizardMode.Join -> if (
                    joinRoleName == FamilyWizardJoinRole.Owner.name
                ) {
                    OwnerLoginDialog(
                        deviceName = ownerDeviceName,
                        onDeviceNameChange = {
                            ownerDeviceName = it
                            ownerDeviceNameError = null
                        },
                        deviceNameError = ownerDeviceNameError,
                        rootPassword = ownerRootPassword,
                        onRootPasswordChange = {
                            ownerRootPassword = it
                            ownerRootPasswordFeedback = null
                        },
                        feedback = ownerRootPasswordFeedback,
                        submitting = familyWizardBusy,
                        onLogin = {
                            runCatching { requireDeviceName(ownerDeviceName) }
                                .exceptionOrNull()?.message?.let {
                                    ownerDeviceNameError = it
                                    return@OwnerLoginDialog
                                }
                            val secret = ownerRootPassword
                            if (secret.isBlank()) {
                                ownerRootPasswordFeedback = "请填写管理员根密码"
                                return@OwnerLoginDialog
                            }
                            runForegroundAction {
                                vm.submitFamilyWizard(
                                    snapshot = accountFamilyWizardSnapshot(
                                        mode = FamilyWizardMode.Join,
                                        step = FamilyWizardStep.Identity,
                                        draft = joinDraft,
                                        displayName = "",
                                        deviceName = ownerDeviceName,
                                        joinRole = FamilyWizardJoinRole.Owner,
                                    ),
                                    bootstrapSecret = secret,
                                )
                                ownerRootPassword = ""
                            }
                        },
                        onTakeover = {
                            runCatching { requireDeviceName(ownerDeviceName) }
                                .exceptionOrNull()?.message?.let {
                                    ownerDeviceNameError = it
                                    return@OwnerLoginDialog
                                }
                            if (ownerRootPassword.isBlank()) {
                                ownerRootPasswordFeedback = "请填写管理员根密码"
                                return@OwnerLoginDialog
                            }
                            dialog = FamilyDialog.OwnerTakeoverConfirm
                        },
                        onBackToRole = {
                            ownerRootPassword = ""
                            ownerRootPasswordFeedback = null
                            showWizard(FamilyWizardMode.Join, FamilyWizardStep.Role)
                        },
                        onDismiss = {
                            ownerRootPassword = ""
                            ownerRootPasswordFeedback = null
                            finalWizardDismiss()
                        },
                    )
                } else if (controls.showJoin) MemberLoginRequestDialog(
                    displayName = joinDisplayName,
                    onDisplayNameChange = {
                        joinDisplayName = it
                        joinDisplayNameError = null
                    },
                    displayNameError = joinDisplayNameError,
                    deviceName = memberDeviceName,
                    onDeviceNameChange = {
                        memberDeviceName = it
                        memberDeviceNameError = null
                    },
                    deviceNameError = memberDeviceNameError,
                    submitting = familyWizardBusy,
                    onBackToRole = {
                        showWizard(FamilyWizardMode.Join, FamilyWizardStep.Role)
                    },
                    onConfirm = {
                        validateFamilyDisplayNameInput(joinDisplayName)?.let {
                            joinDisplayNameError = it
                            return@MemberLoginRequestDialog
                        }
                        runCatching { requireDeviceName(memberDeviceName) }
                            .exceptionOrNull()?.message?.let {
                                memberDeviceNameError = it
                                return@MemberLoginRequestDialog
                            }
                        runForegroundAction {
                            joinDisplayNameError = null
                            memberDeviceNameError = null
                            vm.submitFamilyWizard(
                                accountFamilyWizardSnapshot(
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
                    onKeepOffline = {
                        joinDisplayNameError = null
                        memberDeviceNameError = null
                        finalWizardDismiss()
                    },
                )
                FamilyWizardMode.Create -> if (controls.showCreateFamily) CreateFamilyDialog(
                    displayName = createDisplayName,
                    onDisplayNameChange = {
                        createDisplayName = it
                        createDisplayNameError = null
                    },
                    displayNameError = createDisplayNameError,
                    familyName = createFamilyName,
                    onFamilyNameChange = {
                        createFamilyName = it
                        createFamilyNameError = null
                    },
                    familyNameError = createFamilyNameError,
                    deviceName = createDeviceName,
                    onDeviceNameChange = {
                        createDeviceName = it
                        createDeviceNameError = null
                    },
                    deviceNameError = createDeviceNameError,
                    bootstrapSecret = bootstrapSecret,
                    onBootstrapSecretChange = {
                        bootstrapSecret = it
                        bootstrapSecretFeedback = null
                    },
                    feedback = bootstrapSecretFeedback,
                    creating = familyWizardBusy,
                    endpointConfigured = draftEndpointReady || endpointConfigured,
                    onBackToEndpoint = {
                        showWizard(FamilyWizardMode.Create, FamilyWizardStep.Endpoint)
                    },
                    onConfirm = {
                        validateFamilyDisplayNameInput(createDisplayName)?.let {
                            createDisplayNameError = it
                            return@CreateFamilyDialog
                        }
                        validateFamilyNameInput(createFamilyName)?.let {
                            createFamilyNameError = it
                            return@CreateFamilyDialog
                        }
                        if (createFamilyName.isBlank()) {
                            createFamilyNameError = "请填写家庭名"
                            return@CreateFamilyDialog
                        }
                        runCatching { requireDeviceName(createDeviceName) }
                            .exceptionOrNull()?.message?.let {
                                createDeviceNameError = it
                                return@CreateFamilyDialog
                            }
                        val secret = bootstrapSecret.trim()
                        if (secret.isEmpty()) {
                            bootstrapSecretFeedback = "请填写管理员根密码"
                            return@CreateFamilyDialog
                        }
                        runForegroundAction {
                            bootstrapSecretFeedback = null
                            createDisplayNameError = null
                            createFamilyNameError = null
                            vm.submitFamilyWizard(
                                snapshot = accountFamilyWizardSnapshot(
                                    mode = FamilyWizardMode.Create,
                                    step = FamilyWizardStep.Identity,
                                    draft = joinDraft,
                                    displayName = createDisplayName,
                                    familyName = createFamilyName,
                                    deviceName = createDeviceName,
                                ),
                                bootstrapSecret = secret,
                            )
                            bootstrapSecret = ""
                        }
                    },
                    onDismiss = {
                        bootstrapSecret = ""
                        bootstrapSecretFeedback = null
                        createDisplayNameError = null
                        createFamilyName = ""
                        createFamilyNameError = null
                        createDeviceNameError = null
                        finalWizardDismiss()
                    },
                )
            }
        }
        FamilyDialog.OwnerTakeoverConfirm -> OwnerTakeoverConfirmationDialog(
            submitting = familyWizardBusy,
            onConfirm = {
                val secret = ownerRootPassword
                if (secret.isBlank()) {
                    ownerRootPasswordFeedback = "请填写管理员根密码"
                    showWizard(FamilyWizardMode.Join, FamilyWizardStep.Identity)
                } else {
                    runForegroundAction {
                        vm.submitFamilyWizard(
                            snapshot = accountFamilyWizardSnapshot(
                                mode = FamilyWizardMode.Join,
                                step = FamilyWizardStep.Identity,
                                draft = joinDraft,
                                displayName = "",
                                deviceName = ownerDeviceName,
                                joinRole = FamilyWizardJoinRole.Owner,
                            ),
                            bootstrapSecret = secret,
                            ownerTakeover = true,
                        )
                        ownerRootPassword = ""
                    }
                }
            },
            onDismiss = {
                showWizard(FamilyWizardMode.Join, FamilyWizardStep.Identity)
            },
        )
        FamilyDialog.RenameFamily -> if (ui.enabled && ui.role == FamilyRole.Owner) {
            RenameFamilyDialog(
                familyName = renameFamilyName,
                onFamilyNameChange = {
                    renameFamilyName = it
                    renameFamilyFeedback = null
                },
                feedback = renameFamilyFeedback,
                saving = savingFamilyName,
                onConfirm = {
                    validateFamilyNameInput(renameFamilyName)?.let {
                        renameFamilyFeedback = it
                        return@RenameFamilyDialog
                    }
                    runForegroundAction {
                        savingFamilyName = true
                        vm.renameFamily(renameFamilyName) { success, copy ->
                            savingFamilyName = false
                            if (success) {
                                renameFamilyName = ""
                                dialog = FamilyDialog.Message(copy)
                            } else {
                                renameFamilyFeedback = copy
                            }
                        }
                    }
                },
                onDismiss = {
                    if (!savingFamilyName) {
                        renameFamilyFeedback = null
                        dialog = null
                    }
                },
            )
        }
        FamilyDialog.AddFamilyMember -> EditMyDisplayNameDialog(
            displayName = editDisplayName,
            onDisplayNameChange = {
                editDisplayName = it
                editDisplayNameFeedback = null
            },
            feedback = editDisplayNameFeedback,
            saving = savingDisplayName,
            title = "添加家庭成员",
            supportingCopy = "先创建称呼，之后可为这个成员生成登录二维码",
            confirmCopy = "添加",
            onConfirm = {
                validateFamilyDisplayNameInput(editDisplayName)?.let {
                    editDisplayNameFeedback = it
                    return@EditMyDisplayNameDialog
                }
                runForegroundAction {
                    savingDisplayName = true
                    vm.addFamilyMember(editDisplayName) { success, copy ->
                        savingDisplayName = false
                        if (success) {
                            editDisplayName = ""
                            dialog = FamilyDialog.Message(copy, FamilyDialog.MembersList)
                        } else {
                            editDisplayNameFeedback = copy
                        }
                    }
                }
            },
            onDismiss = {
                if (!savingDisplayName) {
                    editDisplayNameFeedback = null
                    dialog = FamilyDialog.MembersList
                }
            },
        )
        is FamilyDialog.RenameFamilyMember -> EditMyDisplayNameDialog(
            displayName = editDisplayName,
            onDisplayNameChange = {
                editDisplayName = it
                editDisplayNameFeedback = null
            },
            feedback = editDisplayNameFeedback,
            saving = savingDisplayName,
            title = "修改「${active.currentDisplayName}」的称呼",
            supportingCopy = "新称呼在当前家庭内不能与其他成员重复",
            onConfirm = {
                validateFamilyDisplayNameInput(editDisplayName)?.let {
                    editDisplayNameFeedback = it
                    return@EditMyDisplayNameDialog
                }
                runForegroundAction {
                    savingDisplayName = true
                    vm.renameFamilyMember(active.membershipId, editDisplayName) { success, copy ->
                        savingDisplayName = false
                        if (success) {
                            dialog = FamilyDialog.Message(copy, FamilyDialog.MembersList)
                        } else {
                            editDisplayNameFeedback = copy
                        }
                    }
                }
            },
            onDismiss = {
                if (!savingDisplayName) {
                    editDisplayNameFeedback = null
                    dialog = FamilyDialog.MembersList
                }
            },
        )
        is FamilyDialog.RenameFamilyDevice -> EditMyDisplayNameDialog(
            displayName = editDisplayName,
            onDisplayNameChange = {
                editDisplayName = it
                editDisplayNameFeedback = null
            },
            feedback = editDisplayNameFeedback,
            saving = savingDisplayName,
            title = "修改设备称呼",
            fieldLabel = "设备称呼",
            supportingCopy = "同一成员的多台设备不能使用相同称呼",
            onConfirm = {
                if (editDisplayName.isBlank()) {
                    editDisplayNameFeedback = "请填写设备称呼"
                    return@EditMyDisplayNameDialog
                }
                runForegroundAction {
                    savingDisplayName = true
                    vm.renameFamilyDevice(active.deviceId, editDisplayName) { success, copy ->
                        savingDisplayName = false
                        if (success) {
                            dialog = FamilyDialog.Message(copy, FamilyDialog.MembersList)
                        } else {
                            editDisplayNameFeedback = copy
                        }
                    }
                }
            },
            onDismiss = {
                if (!savingDisplayName) {
                    editDisplayNameFeedback = null
                    dialog = FamilyDialog.MembersList
                }
            },
        )
        FamilyDialog.EditMyDisplayName -> EditMyDisplayNameDialog(
            displayName = editDisplayName,
            onDisplayNameChange = {
                editDisplayName = it
                editDisplayNameFeedback = null
            },
            feedback = editDisplayNameFeedback,
            saving = savingDisplayName,
            supportingCopy = if (ui.role == FamilyRole.Member) {
                "提交后由管理员确认，确认前继续显示当前称呼"
            } else {
                "管理员称呼会立即更新"
            },
            confirmCopy = if (ui.role == FamilyRole.Member) "提交申请" else "保存",
            onConfirm = {
                validateFamilyDisplayNameInput(editDisplayName)?.let {
                    editDisplayNameFeedback = it
                    return@EditMyDisplayNameDialog
                }
                runForegroundAction {
                    savingDisplayName = true
                    vm.updateMyDisplayName(editDisplayName) { success, copy ->
                        savingDisplayName = false
                        if (success) {
                            dialog = FamilyDialog.Message(copy)
                        } else {
                            editDisplayNameFeedback = copy
                        }
                    }
                }
            },
            onDismiss = {
                if (!savingDisplayName) {
                    editDisplayNameFeedback = null
                    dialog = null
                }
            },
        )
        FamilyDialog.ConfirmDeviceLogout -> LogoutCurrentDeviceDialog(
            onConfirm = {
                dialog = null
                runForegroundAction {
                    vm.logoutCurrentDevice { _, message -> showMessage(message) }
                }
            },
            onDismiss = { dialog = null },
        )
        is FamilyDialog.ConfirmDeviceRevoke -> RevokeFamilyDeviceDialog(
            deviceName = active.deviceName,
            isCurrent = active.isCurrent,
            onConfirm = {
                dialog = null
                runForegroundAction {
                    vm.revokeFamilyDevice(
                        active.deviceId,
                        active.deviceName,
                        active.isCurrent,
                    ) { _, message -> showMessage(message) }
                }
            },
            onDismiss = { dialog = null },
        )
        FamilyDialog.ConfirmLeave -> LeaveFamilyDialog(
            onConfirm = {
                dialog = null
                runForegroundAction { vm.leave { showMessage(it) } }
            },
            onDismiss = { dialog = null },
        )
        is FamilyDialog.DeleteFamily -> DeleteFamilyDialog(
            stage = active.stage,
            expectedFamilyName = ui.familyName.orEmpty(),
            familyNameInput = deleteFamilyName,
            onFamilyNameInputChange = {
                deleteFamilyName = it
                deleteFamilyFeedback = null
            },
            rootPassword = deleteFamilyRootPassword,
            onRootPasswordChange = {
                deleteFamilyRootPassword = it
                deleteFamilyFeedback = null
            },
            errorMessage = deleteFamilyFeedback,
            deleting = deletingFamily,
            onContinue = { dialog = FamilyDialog.DeleteFamily(FamilyDialog.DeleteStage.Final) },
            onConfirm = {
                runForegroundAction {
                    deletingFamily = true
                    vm.deleteFamily(
                        deleteFamilyName,
                        deleteFamilyRootPassword,
                    ) { success, message ->
                        deletingFamily = false
                        if (success) {
                            resetDeleteFamilyConfirmation()
                            dialog = null
                            showMessage(message)
                        } else {
                            deleteFamilyFeedback = message
                        }
                    }
                }
            },
            onDismiss = {
                resetDeleteFamilyConfirmation()
                dialog = null
            },
        )
        is FamilyDialog.Message -> FamilyMessageDialog(active.copy, onDismiss = ::dismissDialog)
        is FamilyDialog.DeleteBaby,
        is FamilyDialog.MergeBaby,
        is FamilyDialog.MergePreview,
        is FamilyDialog.EditBaby,
        -> FamilyBabyDialog(
            dialog = active,
            babies = ui.babies,
            canEditAvatar = canEditFamilyAvatar(ui.role),
            onDismiss = { dialog = null },
            onDelete = { id ->
                dialog = null
                vm.deleteBaby(id) { showMessage(it) }
            },
            onPreviewMerge = { sourceId, targetId ->
                vm.previewMerge(sourceId, targetId) { preview ->
                    dialog = if (preview == null) {
                        FamilyDialog.Message("无法生成合并预览，请刷新后重试")
                    } else FamilyDialog.MergePreview(preview)
                }
            },
            onMerge = { preview ->
                dialog = null
                vm.merge(preview) { showMessage(it) }
            },
            onUpdate = { baby, update, onFinished ->
                vm.updateBaby(
                    baby.id,
                    update.nickname,
                    update.sex,
                    update.birthdayEpochDay,
                    update.birthWeightGrams,
                    update.avatarJpeg,
                    update.removeAvatar,
                ) { error ->
                    onFinished()
                    if (error == null) {
                        showMessage("宝宝档案已保存")
                    } else {
                        showMessage(error, resume = FamilyDialog.EditBaby(baby))
                    }
                }
            },
        )
        null -> Unit
    }
}
