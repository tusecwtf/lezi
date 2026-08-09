package com.lezi.babylog.feature.family

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.PageScaffoldBackground
import com.lezi.babylog.domain.family.FamilyWizardOutcome
import com.lezi.babylog.domain.family.FamilyWizardState
import com.lezi.babylog.domain.family.isBusy
import com.lezi.babylog.domain.family.projectMemberLoginQrDialog
import com.lezi.babylog.feature.family.baby.FamilyBabyDialog
import com.lezi.babylog.feature.family.components.FamilyDialog
import com.lezi.babylog.feature.family.components.FamilyWizardJoinRole
import com.lezi.babylog.feature.family.components.FamilyWizardMode
import com.lezi.babylog.feature.family.components.FamilyWizardStep
import com.lezi.babylog.feature.family.components.LOCAL_FAMILY_DISPLAY_NAME
import com.lezi.babylog.feature.family.components.accountFamilyWizardSnapshot
import com.lezi.babylog.feature.family.components.canEditFamilyAvatar
import com.lezi.babylog.feature.family.components.canManageFamilyBabies
import com.lezi.babylog.feature.family.components.familyControlVisibility
import com.lezi.babylog.feature.family.components.familyDialogAfterDismiss
import com.lezi.babylog.feature.family.components.familySyncError
import com.lezi.babylog.feature.family.components.familyPrimarySurface
import com.lezi.babylog.feature.family.components.familyWizardOutcomeCopy
import com.lezi.babylog.feature.family.components.isEndpointConfigured
import com.lezi.babylog.feature.family.components.isWizardSessionDialog
import com.lezi.babylog.feature.family.components.validateFamilyDisplayNameInput
import com.lezi.babylog.feature.family.components.validateFamilyNameInput
import com.lezi.babylog.feature.family.members.FamilyMembersListSheet
import com.lezi.babylog.feature.family.members.MembersDevicesHost
import com.lezi.babylog.feature.family.members.DeleteFamilyDialog
import com.lezi.babylog.feature.family.members.EditMyDisplayNameDialog
import com.lezi.babylog.feature.family.members.LeaveFamilyDialog
import com.lezi.babylog.feature.family.members.LogoutCurrentDeviceDialog
import com.lezi.babylog.feature.family.members.MemberLoginQrCodeDialog
import com.lezi.babylog.feature.family.members.PendingMemberDecisionDialog
import com.lezi.babylog.feature.family.members.RemoveMemberConfirmDialog
import com.lezi.babylog.feature.family.members.RenameFamilyDialog
import com.lezi.babylog.feature.family.members.RevokeFamilyDeviceDialog
import com.lezi.babylog.feature.family.overview.AccountOverviewHost
import com.lezi.babylog.feature.family.overview.AccountBabyActions
import com.lezi.babylog.feature.family.overview.AccountBottomActions
import com.lezi.babylog.feature.family.overview.AccountFamilySectionActions
import com.lezi.babylog.feature.family.overview.AccountPageContent
import com.lezi.babylog.feature.family.overview.OverviewAppUpdateDialogs
import com.lezi.babylog.feature.family.wizard.AccountFamilyWizardHost
import com.lezi.babylog.feature.family.wizard.CreateFamilyDialog
import com.lezi.babylog.feature.family.wizard.FamilyEndpointConnectionDialog
import com.lezi.babylog.feature.family.wizard.FamilyJoinRoleDialog
import com.lezi.babylog.feature.family.wizard.FamilyVerifiedEndpointDialog
import com.lezi.babylog.feature.family.wizard.MemberApprovalWaitingDialog
import com.lezi.babylog.feature.family.wizard.MemberLoginQrConfirmDialog
import com.lezi.babylog.feature.family.wizard.MemberLoginRequestDialog
import com.lezi.babylog.feature.family.wizard.rememberFamilyMemberLoginQrScanAction
import com.lezi.babylog.feature.family.wizard.memberApprovalRequestForDisplay
import com.lezi.babylog.feature.family.wizard.OwnerLoginDialog
import com.lezi.babylog.feature.family.wizard.OwnerTakeoverConfirmationDialog
import com.lezi.babylog.feature.family.networksettings.FamilyNetworkSettingsHost
import com.lezi.babylog.feature.family.networksettings.FamilyNetworkSettingsScreen
import com.lezi.babylog.feature.family.components.FamilyMessageDialog
import com.lezi.babylog.sync.session.FamilyEndpointConfig
import com.lezi.babylog.sync.session.FamilyEndpointDraft
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.InitialFamilyDataRecovery
import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import com.lezi.babylog.sync.session.defaultAndroidDeviceName
import com.lezi.babylog.sync.session.requireDeviceName
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

/**
 * Account navigation shell: composes [AccountOverviewHost], [MembersDevicesHost], and
 * [AccountFamilyWizardHost] without reintroducing a five-flow God ViewModel surface.
 */
@Composable
fun FamilyRoute(
    overviewHost: AccountOverviewHost = hiltViewModel(),
    membersHost: MembersDevicesHost = hiltViewModel(),
    wizardHost: AccountFamilyWizardHost = hiltViewModel(),
    networkSettingsHost: FamilyNetworkSettingsHost = hiltViewModel(),
) {
    val overview by overviewHost.ui.collectAsStateWithLifecycle()
    val members by membersHost.ui.collectAsStateWithLifecycle()
    val appUpdateOutcome by overviewHost.appUpdateOutcome.collectAsStateWithLifecycle()
    val checkingAppUpdate by overviewHost.checkingAppUpdate.collectAsStateWithLifecycle()
    val installingAppUpdate by overviewHost.installingAppUpdate.collectAsStateWithLifecycle()
    val memberDestructiveBusy by membersHost.destructiveBusy.collectAsStateWithLifecycle()
    val babyDestructiveBusy by overviewHost.destructiveBusy.collectAsStateWithLifecycle()
    val addingBaby by overviewHost.addingBaby.collectAsStateWithLifecycle()
    val endpointSeed by wizardHost.endpointSeed.collectAsStateWithLifecycle()
    val networkSettings by networkSettingsHost.ui.collectAsStateWithLifecycle()
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
    // Destructive confirmation, including the root password, is process-memory only.
    var deleteFamilyName by remember { mutableStateOf("") }
    var deleteFamilyRootPassword by remember { mutableStateOf("") }
    var deleteFamilyFeedback by remember { mutableStateOf<String?>(null) }
    var deletingFamily by remember { mutableStateOf(false) }
    var showNetworkSettings by rememberSaveable { mutableStateOf(false) }

    val familyWizardState by wizardHost.familyWizardState.collectAsStateWithLifecycle()
    val verifiedEndpoint by wizardHost.verifiedEndpoint.collectAsStateWithLifecycle(initialValue = null)
    val emptyEndpointDraft = remember { FamilyEndpointConfig.emptyDraft() }
    fun draftFromEndpointSeed(): FamilyEndpointDraft {
        val saved = when {
            endpointSeed.serverHost.isNotBlank() -> FamilyEndpointConfig(
                host = endpointSeed.serverHost,
                port = endpointSeed.serverPort,
                scheme = endpointSeed.serverScheme,
            )
            endpointSeed.baseUrl.isNotBlank() -> FamilyEndpointConfig.fromBaseUrl(endpointSeed.baseUrl)
            else -> emptyEndpointDraft
        }
        return FamilyEndpointDraft.fromConfig(saved)
    }
    // Keep in-progress endpoint edits stable while the wizard is active.
    var joinDraft by rememberSaveable(stateSaver = FamilyEndpointDraftSaver) {
        mutableStateOf(draftFromEndpointSeed())
    }
    val pendingMemberLogin = memberApprovalRequestForDisplay(
        familyWizardState,
        overview.pendingMemberLogin,
    )
    val familyWizardBusy = familyWizardState.isBusy
    val wizardSessionActive = isWizardSessionDialog(dialog)
    LaunchedEffect(
        endpointSeed.serverHost,
        endpointSeed.serverPort,
        endpointSeed.serverScheme,
        endpointSeed.baseUrl,
        wizardSessionActive,
    ) {
        if (wizardSessionActive) return@LaunchedEffect
        joinDraft = draftFromEndpointSeed()
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


    LaunchedEffect(overview.enabled, overview.familyId) {
        if (overview.enabled) membersHost.refreshMembers(showErrors = false)
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

    fun useManualJoinFor(memberLogin: MemberLoginQrPayload) {
        wizardHost.cancelMemberLoginQr()
        endpointDraft = memberLogin.endpoint.origin
        dialog = FamilyDialog.ConnectEndpoint
    }

    val launchMemberLoginQrScan = rememberFamilyMemberLoginQrScanAction(
        onReady = { memberLogin ->
            memberQrDeviceName = defaultAndroidDeviceName(context)
            dialog = null
            wizardHost.verifyMemberLoginQr(memberLogin)
        },
        onMessage = { showMessage(it, dialog) },
    )

    val endpointConfigured = remember(endpointSeed.serverHost, endpointSeed.baseUrl) {
        isEndpointConfigured(endpointSeed.serverHost, endpointSeed.baseUrl)
    }
    val draftEndpointReady = joinDraft.hasEndpoint()
    val controls = remember(overview.enabled, overview.role) {
        familyControlVisibility(overview.enabled, overview.role)
    }
    val primary = remember(overview.enabled, overview.role, endpointConfigured) {
        familyPrimarySurface(overview.enabled, overview.role, endpointConfigured)
    }
    LaunchedEffect(familyWizardState) {
        when (val state = familyWizardState) {
            is FamilyWizardState.RetryableFailure -> {
                if (state.committedOutcome is FamilyWizardOutcome.MemberLoginQrClaimed) {
                    // QR recovery stays on the shared QR projection; no wizard overlay.
                    return@LaunchedEffect
                }
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
                val outcome = state.outcome
                if (outcome is FamilyWizardOutcome.MemberLoginQrClaimed &&
                    outcome.dataRecovery == InitialFamilyDataRecovery.RetryRequired
                ) {
                    // Keep projecting the QR recovery surface until recovery succeeds.
                    return@LaunchedEffect
                }
                wizardHost.consumeFamilyWizardCompletion()?.let { consumed ->
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
                    membersHost.refreshMembers(showErrors = true)
                    dialog = FamilyDialog.Message(familyWizardOutcomeCopy(consumed))
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
            is FamilyWizardState.VerifyingMemberLoginQr,
            is FamilyWizardState.MemberLoginQrReady,
            is FamilyWizardState.MemberLoginQrVerificationFailed,
            is FamilyWizardState.ClaimingMemberLoginQr,
            -> Unit
        }
    }

    AnimatedContent(
        targetState = showNetworkSettings,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "familyPageContent",
    ) { networkSettingsVisible ->
        if (networkSettingsVisible) {
            LaunchedEffect(Unit) { networkSettingsHost.entered() }
            FamilyNetworkSettingsScreen(
                ui = networkSettings,
                onBack = { showNetworkSettings = false },
                onEndpointDraftChange = networkSettingsHost::updateEndpointDraft,
                onProbeCandidate = networkSettingsHost::probeCandidate,
                onTrustCandidate = networkSettingsHost::trustCandidate,
                onRefreshAvailability = networkSettingsHost::refreshAvailability,
                onReconnectOwner = networkSettingsHost::reconnectOwner,
                onRequestReconnectMember = networkSettingsHost::requestReconnectMember,
                onCheckReconnectMember = networkSettingsHost::checkReconnectMember,
                onCancelReconnectMember = networkSettingsHost::cancelReconnectMember,
                onPrepareDisasterRecovery = networkSettingsHost::prepareDisasterRecovery,
                onStartDisasterRecovery = networkSettingsHost::startDisasterRecovery,
                onCommitDisasterRecovery = networkSettingsHost::commitDisasterRecovery,
                onCancelDisasterRecovery = networkSettingsHost::cancelDisasterRecovery,
            )
        } else {
            PageScaffoldBackground {
                AccountPageContent(
                    overview = overview,
                    members = members,
                    primary = primary,
                    endpointConfigured = endpointConfigured,
                    babyActions = AccountBabyActions(
                        add = { dialog = FamilyDialog.AddBaby },
                        setCurrent = overviewHost::setCurrent,
                        edit = { dialog = FamilyDialog.EditBaby(it) },
                        merge = { dialog = FamilyDialog.MergeBaby(it) },
                        delete = { dialog = FamilyDialog.DeleteBaby(it) },
                    ),
                    familyActions = AccountFamilySectionActions(
                        openMembers = {
                            membersHost.refreshMembers(showErrors = true)
                            dialog = FamilyDialog.MembersList
                        },
                        connect = ::openEndpointConnection,
                        openOptionalAppUpdate = overviewHost::openOptionalAppUpdate,
                        dismissOptionalAppUpdate = overviewHost::dismissOptionalAppUpdate,
                    ),
                    bottomActions = AccountBottomActions(
                        openNetworkSettings = { showNetworkSettings = true },
                        logoutCurrentDevice = { dialog = FamilyDialog.ConfirmDeviceLogout },
                        leaveFamily = { dialog = FamilyDialog.ConfirmLeave },
                        deleteFamily = {
                            resetDeleteFamilyConfirmation()
                            membersHost.refreshMembers(showErrors = true)
                            dialog = FamilyDialog.DeleteFamily(FamilyDialog.DeleteStage.Warning)
                        },
                    ),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(LeziSpacing.Page),
                )
            }
        }
    }

    if (!showNetworkSettings) {
        OverviewAppUpdateDialogs(
            host = overviewHost,
            outcome = appUpdateOutcome,
            checkingAppUpdate = checkingAppUpdate,
            installingAppUpdate = installingAppUpdate,
        )
    }


    if (!showNetworkSettings) when (val active = dialog) {
        FamilyDialog.ConnectEndpoint -> FamilyEndpointConnectionDialog(
            state = familyWizardState,
            endpointDraft = endpointDraft,
            onEndpointDraftChange = {
                endpointDraft = it
                if (familyWizardState is FamilyWizardState.EndpointFailure) {
                    wizardHost.keepOffline()
                }
            },
            onConnect = { wizardHost.connectEndpoint(endpointDraft) },
            onTrustCertificate = wizardHost::trustCertificate,
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
                wizardHost.beginFamilyWizard(identity)
                if (identity.mode == FamilyWizardMode.Join) joinRoleName = null
                showWizard(identity.mode, identity.step)
            },
            onForget = {
                wizardHost.forgetEndpoint()
                endpointDraft = ""
            },
            onReturnToAddress = { wizardHost.keepOffline() },
            onKeepOffline = {
                wizardHost.keepOffline()
                dialog = null
            },
        )
        FamilyDialog.MembersList -> if (overview.enabled) FamilyMembersListSheet(
            ui = members,
            onRefreshMembers = { membersHost.refreshMembers(showErrors = true) },
            onEditMyDisplayName = {
                editDisplayName = overview.displayName.takeUnless {
                    it == LOCAL_FAMILY_DISPLAY_NAME
                }.orEmpty()
                editDisplayNameFeedback = null
                dialog = FamilyDialog.EditMyDisplayName
            },
            onRenameFamily = if (overview.role == FamilyRole.Owner) {
                {
                    renameFamilyName = overview.familyName.orEmpty()
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
            onReviewPending = if (overview.role == FamilyRole.Owner) {
                { request -> dialog = FamilyDialog.ReviewPendingMember(request) }
            } else {
                null
            },
            onCreateMemberLoginQr = if (overview.role == FamilyRole.Owner) {
                { membershipId ->
                    
                    membersHost.createMemberLoginQr(membershipId) { result ->
                        result.fold(
                            onSuccess = { dialog = FamilyDialog.MemberLoginQrCode(it) },
                            onFailure = {
                                showMessage(
                                    familySyncError(
                                        it,
                                        "生成成员登录二维码失败，请稍后重试",
                                    ),
                                    resume = FamilyDialog.MembersList,
                                )
                            },
                        )
                    }
                
                }
            } else {
                null
            },
            onAddMember = if (overview.role == FamilyRole.Owner) {
                {
                    editDisplayName = ""
                    editDisplayNameFeedback = null
                    dialog = FamilyDialog.AddFamilyMember
                }
            } else {
                null
            },
            onRenameMember = if (overview.role == FamilyRole.Owner) {
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
            onReviewRename = if (overview.role == FamilyRole.Owner) {
                { request, approve ->
                    
                    membersHost.decideMemberRename(request, approve) { _, copy ->
                        showMessage(copy, resume = FamilyDialog.MembersList)
                    }
                
                }
            } else {
                null
            },
            onDismiss = { dialog = null },
        )
        is FamilyDialog.MemberLoginQrCode -> MemberLoginQrCodeDialog(
            code = active.code,
            onDismiss = { dialog = FamilyDialog.MembersList },
        )
        is FamilyDialog.ReviewPendingMember -> if (overview.enabled && overview.role == FamilyRole.Owner) {
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
                members = members.members,
                busy = decidingMemberRequest,
                onBindExisting = { membershipId ->
                    decidingMemberRequest = true
                    membersHost.bindExistingMemberLogin(
                        active.request.requestId,
                        membershipId,
                        ::finishDecision,
                    )
                },
                onApproveNew = {
                    decidingMemberRequest = true
                    membersHost.approveNewMemberLogin(active.request.requestId, ::finishDecision)
                },
                onReject = {
                    decidingMemberRequest = true
                    membersHost.rejectMemberLogin(active.request, ::finishDecision)
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
                
                removingMember = true
                membersHost.removeMember(
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
            
            },
            onDismiss = {
                if (!removingMember) dialog = FamilyDialog.MembersList
            },
        )
        is FamilyDialog.Wizard -> if (pendingMemberLogin != null) {
            MemberApprovalWaitingDialog(
                request = pendingMemberLogin,
                busy = familyWizardBusy,
                cancelling = (familyWizardState as? FamilyWizardState.WaitingForMemberApproval)
                    ?.cancelling == true,
                feedback = (familyWizardState as? FamilyWizardState.WaitingForMemberApproval)
                    ?.feedback,
                onCheck = {
                    wizardHost.checkMemberApproval()
                },
                onCancel = {
                    wizardHost.cancelMemberApproval(::finalWizardDismiss)
                },
                onKeepOffline = ::finalWizardDismiss,
            )
        } else when (active.step) {
            FamilyWizardStep.Endpoint -> FamilyVerifiedEndpointDialog(
                mode = active.mode,
                endpoint = verifiedEndpoint?.origin.orEmpty(),
                onContinue = {
                    val origin = verifiedEndpoint?.origin.orEmpty()
                    joinDraft = FamilyEndpointDraft(
                        host = origin,
                        portText = "443",
                        scheme = "https",
                    )
                    showWizard(
                        active.mode,
                        if (active.mode == FamilyWizardMode.Join) {
                            FamilyWizardStep.Role
                        } else {
                            FamilyWizardStep.Identity
                        },
                    )
                },
                onChangeEndpoint = {
                    endpointDraft = verifiedEndpoint?.origin.orEmpty()
                    dialog = FamilyDialog.ConnectEndpoint
                },
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
                onScanMemberLoginQr = launchMemberLoginQrScan,
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
                            wizardHost.submitFamilyWizard(
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
                        joinDisplayNameError = null
                            memberDeviceNameError = null
                            wizardHost.submitFamilyWizard(
                                accountFamilyWizardSnapshot(
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
                        bootstrapSecretFeedback = null
                            createDisplayNameError = null
                            createFamilyNameError = null
                            wizardHost.submitFamilyWizard(
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
                    wizardHost.submitFamilyWizard(
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
            },
            onDismiss = {
                showWizard(FamilyWizardMode.Join, FamilyWizardStep.Identity)
            },
        )
        FamilyDialog.RenameFamily -> if (overview.enabled && overview.role == FamilyRole.Owner) {
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
                    
                    savingFamilyName = true
                    membersHost.renameFamily(renameFamilyName) { success, copy ->
                        savingFamilyName = false
                        if (success) {
                            renameFamilyName = ""
                            dialog = FamilyDialog.Message(copy)
                        } else {
                            renameFamilyFeedback = copy
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
                
                savingDisplayName = true
                membersHost.addFamilyMember(editDisplayName) { success, copy ->
                    savingDisplayName = false
                    if (success) {
                        editDisplayName = ""
                        dialog = FamilyDialog.Message(copy, FamilyDialog.MembersList)
                    } else {
                        editDisplayNameFeedback = copy
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
                
                savingDisplayName = true
                membersHost.renameFamilyMember(active.membershipId, editDisplayName) { success, copy ->
                    savingDisplayName = false
                    if (success) {
                        dialog = FamilyDialog.Message(copy, FamilyDialog.MembersList)
                    } else {
                        editDisplayNameFeedback = copy
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
                
                savingDisplayName = true
                membersHost.renameFamilyDevice(active.deviceId, editDisplayName) { success, copy ->
                    savingDisplayName = false
                    if (success) {
                        dialog = FamilyDialog.Message(copy, FamilyDialog.MembersList)
                    } else {
                        editDisplayNameFeedback = copy
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
            supportingCopy = if (overview.role == FamilyRole.Member) {
                "提交后由管理员确认，确认前继续显示当前称呼"
            } else {
                "管理员称呼会立即更新"
            },
            confirmCopy = if (overview.role == FamilyRole.Member) "提交申请" else "保存",
            onConfirm = {
                validateFamilyDisplayNameInput(editDisplayName)?.let {
                    editDisplayNameFeedback = it
                    return@EditMyDisplayNameDialog
                }
                
                savingDisplayName = true
                membersHost.updateMyDisplayName(editDisplayName) { success, copy ->
                    savingDisplayName = false
                    if (success) {
                        dialog = FamilyDialog.Message(copy)
                    } else {
                        editDisplayNameFeedback = copy
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
                membersHost.logoutCurrentDevice { success, message ->
                    showMessage(
                        message,
                        resume = FamilyDialog.ConfirmDeviceLogout.takeUnless { success },
                    )
                }
            
            },
            onDismiss = { dialog = null },
            busy = memberDestructiveBusy,
        )
        is FamilyDialog.ConfirmDeviceRevoke -> RevokeFamilyDeviceDialog(
            deviceName = active.deviceName,
            isCurrent = active.isCurrent,
            onConfirm = {
                membersHost.revokeFamilyDevice(
                    active.deviceId,
                    active.deviceName,
                    active.isCurrent,
                ) { success, message ->
                    showMessage(message, resume = active.takeUnless { success })
                }
            
            },
            onDismiss = { dialog = null },
            busy = memberDestructiveBusy,
        )
        FamilyDialog.ConfirmLeave -> LeaveFamilyDialog(
            onConfirm = {
                membersHost.leave { success, message ->
                    showMessage(
                        message,
                        resume = FamilyDialog.ConfirmLeave.takeUnless { success },
                    )
                }
            },
            onDismiss = { dialog = null },
            busy = memberDestructiveBusy,
        )
        is FamilyDialog.DeleteFamily -> DeleteFamilyDialog(
            stage = active.stage,
            expectedFamilyName = overview.familyName.orEmpty(),
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
                
                deletingFamily = true
                membersHost.deleteFamily(
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
            
            },
            onRefreshFamilyInfo = {
                resetDeleteFamilyConfirmation()
                dialog = null
                membersHost.refreshFamilyForDeletion()
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
        is FamilyDialog.AddBaby,
        -> {
            FamilyBabyDialog(
                dialog = active,
                babies = overview.babies,
                currentBabyId = overview.current?.id,
                canEditProfile = canManageFamilyBabies(overview.role),
                canEditAvatar = canEditFamilyAvatar(overview.role),
                createBusy = addingBaby,
                onDismiss = { if (!babyDestructiveBusy && !addingBaby) dialog = null },
                onDelete = { id ->
                    overviewHost.deleteBaby(id) { success, message ->
                        val resume = overview.babies.firstOrNull { it.id == id }
                            ?.let(FamilyDialog::DeleteBaby)
                            ?.takeUnless { success }
                        showMessage(message, resume = resume)
                    }
                },
                onPreviewMerge = { sourceId, targetId ->
                    overviewHost.previewMerge(sourceId, targetId) { preview ->
                        dialog = if (preview == null) {
                            FamilyDialog.Message("无法生成合并预览，请刷新后重试")
                        } else FamilyDialog.MergePreview(preview)
                    }
                },
                onMerge = { preview ->
                    overviewHost.merge(preview) { success, message ->
                        val resume = overview.babies.firstOrNull { it.id == preview.sourceBabyId }
                            ?.let(FamilyDialog::MergeBaby)
                            ?.takeUnless { success }
                        showMessage(message, resume = resume)
                    }
                },
                onUpdate = { baby, update, onFinished ->
                    overviewHost.updateBaby(
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
                onCreate = { nickname, sex, birthday, grams, theme, avatar, onFinished ->
                    overviewHost.addBaby(
                        nickname = nickname,
                        sex = sex,
                        birthdayEpochDay = birthday,
                        birthWeightGrams = grams,
                        themeColorArgb = theme,
                        avatarJpeg = avatar,
                    ) { error ->
                        onFinished(error)
                        if (error == null) {
                            dialog = null
                            showMessage("已添加宝宝")
                        }
                    }
                },
                onLocalTheme = overviewHost::setBabyLocalTheme,
                onMoveLocal = overviewHost::moveBabyLocal,
                onSetCurrent = overviewHost::setCurrent,
                destructiveBusy = babyDestructiveBusy,
            )
        }
        null -> Unit
    }

    // Member-login QR is driven solely by FamilyWizardController state (shared with onboarding).
    val memberLoginQrModel = projectMemberLoginQrDialog(familyWizardState)
    if (!showNetworkSettings && memberLoginQrModel != null) {
        val payload = memberLoginQrModel.payload
        MemberLoginQrConfirmDialog(
            familyName = memberLoginQrModel.display.familyName,
            memberDisplayName = memberLoginQrModel.display.memberDisplayName,
            deviceName = memberQrDeviceName,
            onDeviceNameChange = { if (memberLoginQrModel.deviceNameEditable) memberQrDeviceName = it },
            feedback = memberLoginQrModel.feedback,
            submitting = memberLoginQrModel.submitting,
            verificationInProgress = memberLoginQrModel.verificationInProgress,
            verificationRetryRequired = memberLoginQrModel.verificationRetryRequired,
            recoveryRetryRequired = memberLoginQrModel.recoveryRetryRequired,
            deviceNameEditable = memberLoginQrModel.deviceNameEditable,
            showConfirm = memberLoginQrModel.showConfirm,
            confirmLabel = memberLoginQrModel.confirmLabel,
            title = memberLoginQrModel.title,
            onConfirm = {
                when {
                    memberLoginQrModel.verificationRetryRequired && payload != null ->
                        wizardHost.verifyMemberLoginQr(payload)
                    memberLoginQrModel.recoveryRetryRequired ->
                        wizardHost.retryMemberLoginQrRecovery()
                    payload != null ->
                        // Controller owns device-name validation → Ready.feedback.
                        wizardHost.claimMemberLoginQr(payload, memberQrDeviceName)
                }
            },
            onManualJoin = {
                if (payload != null) useManualJoinFor(payload)
            },
            onDismiss = {
                when (val dismissState = familyWizardState) {
                    is FamilyWizardState.Completed -> {
                        val claimed = dismissState.outcome as? FamilyWizardOutcome.MemberLoginQrClaimed
                        wizardHost.consumeFamilyWizardCompletion()
                        if (claimed != null) {
                            dialog = FamilyDialog.Message(familyWizardOutcomeCopy(claimed))
                        } else {
                            dialog = null
                        }
                    }
                    is FamilyWizardState.RetryableFailure -> {
                        val claimed =
                            dismissState.committedOutcome as? FamilyWizardOutcome.MemberLoginQrClaimed
                        dialog = if (claimed != null) {
                            FamilyDialog.Message(familyWizardOutcomeCopy(claimed))
                        } else {
                            null
                        }
                    }
                    else -> {
                        wizardHost.cancelMemberLoginQr()
                        dialog = null
                    }
                }
            },
            showManualJoin = payload != null && !memberLoginQrModel.recoveryRetryRequired,
        )
    }
}
