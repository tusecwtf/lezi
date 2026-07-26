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
import com.lezi.babylog.sync.FamilyRole
import com.lezi.babylog.sync.HomeLanServerConfig
import com.lezi.babylog.sync.HomeWifiPermission
import com.lezi.babylog.sync.InvitePayloadCodec
import com.lezi.babylog.sync.JoinFamilyDraft

@Composable
fun FamilyRoute(
    onAddBaby: () -> Unit = {},
    vm: FamilyViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<FamilyDialog?>(null) }
    var pendingHomeWifiAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingHomeWifiResume by remember { mutableStateOf<FamilyDialog?>(null) }
    var joiningFamily by remember { mutableStateOf(false) }
    var bootstrapSecret by remember { mutableStateOf("") }
    var bootstrapSecretFeedback by remember { mutableStateOf<String?>(null) }
    var creatingFamily by remember { mutableStateOf(false) }
    var createDisplayName by remember { mutableStateOf("") }
    var createDisplayNameError by remember { mutableStateOf<String?>(null) }
    var createFamilyName by remember { mutableStateOf("") }
    var createFamilyNameError by remember { mutableStateOf<String?>(null) }
    var joinDisplayName by remember { mutableStateOf("") }
    var joinDisplayNameError by remember { mutableStateOf<String?>(null) }
    var editDisplayName by remember { mutableStateOf("") }
    var editDisplayNameFeedback by remember { mutableStateOf<String?>(null) }
    var savingDisplayName by remember { mutableStateOf(false) }
    var renameFamilyName by remember { mutableStateOf("") }
    var renameFamilyFeedback by remember { mutableStateOf<String?>(null) }
    var savingFamilyName by remember { mutableStateOf(false) }
    var wizardNetworkFeedback by remember { mutableStateOf<String?>(null) }
    var savingWizardNetwork by remember { mutableStateOf(false) }

    val novice = remember {
        HomeLanServerConfig.noviceUiDefaults(
            if (HomeWifiPermission.hasRequiredPermissions(context)) vm.currentWifiSsid() else null,
        )
    }
    var joinDraft by remember(
        ui.serverHost,
        ui.serverPort,
        ui.serverScheme,
        ui.baseUrl,
        ui.allowedSsids,
    ) {
        val saved = when {
            ui.serverHost.isNotBlank() -> HomeLanServerConfig(
                host = ui.serverHost,
                port = ui.serverPort,
                allowedSsids = ui.allowedSsids,
                scheme = ui.serverScheme,
            )
            ui.baseUrl.isNotBlank() -> HomeLanServerConfig.fromBaseUrl(ui.baseUrl).copy(
                allowedSsids = ui.allowedSsids,
            )
            else -> novice
        }
        mutableStateOf(JoinFamilyDraft.fromConfig(saved))
    }

    fun showMessage(copy: String, resume: FamilyDialog? = null) {
        dialog = FamilyDialog.Message(copy, resume)
    }
    fun dismissDialog() {
        val current = dialog
        dialog = current?.let(::familyDialogAfterDismiss)
    }

    val homeWifiPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val action = pendingHomeWifiAction
        val resume = pendingHomeWifiResume
        pendingHomeWifiAction = null
        pendingHomeWifiResume = null
        if (HomeWifiPermission.isSsidAccessReady(context)) {
            action?.invoke()
        } else {
            dialog = FamilyDialog.HomeWifiAccessGuide(resume)
        }
    }
    fun withHomeWifiAccess(action: () -> Unit) {
        val missing = HomeWifiPermission.missingPermissions(context)
        if (missing.isNotEmpty()) {
            pendingHomeWifiAction = action
            pendingHomeWifiResume = dialog
            homeWifiPermission.launch(missing.toTypedArray())
        } else if (HomeWifiPermission.isSsidAccessReady(context)) {
            action()
        } else {
            dialog = FamilyDialog.HomeWifiAccessGuide(dialog)
        }
    }

    LaunchedEffect(ui.enabled, ui.familyId) {
        if (ui.enabled && HomeWifiPermission.isSsidAccessReady(context)) {
            vm.refreshMembers(showErrors = false)
        }
    }

    fun openWizard(mode: FamilyWizardMode) {
        wizardNetworkFeedback = null
        val step = familyWizardInitialStep(networkConfigured = isHomeLanNetworkConfigured(
            ui.serverHost,
            ui.baseUrl,
            ui.allowedSsids,
        ))
        if (step == FamilyWizardStep.Network &&
            joinDraft.ssid1.isBlank() &&
            HomeWifiPermission.isSsidAccessReady(context)
        ) {
            vm.currentWifiSsid()?.trim()?.takeIf(String::isNotEmpty)?.let {
                joinDraft = joinDraft.copy(ssid1 = it)
            }
        }
        dialog = FamilyDialog.Wizard(mode, step)
    }

    fun applyScannedInvite(raw: String) {
        val payload = raw.trim()
        if (payload.isEmpty()) return
        joinDraft = runCatching { joinDraft.prefillInvitation(payload) }
            .getOrElse { joinDraft.copy(invitation = payload) }
        val scanCopy = runCatching { InvitePayloadCodec.decode(payload) }.getOrNull()?.let { decoded ->
            val config = decoded.homeLanConfig
            buildString {
                append("已扫入邀请")
                if (config.host.isNotBlank()) append(" · ${config.host}:${config.port}")
                if (decoded.ssids.isNotEmpty()) append(" · Wi‑Fi ${decoded.ssids.joinToString(" / ")}")
            }
        }
        val joinWizard = FamilyDialog.Wizard(FamilyWizardMode.Join, FamilyWizardStep.Identity)
        dialog = if (scanCopy == null) joinWizard
        else FamilyDialog.Message(scanCopy, resume = joinWizard)
    }
    val scanInvite = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(::applyScannedInvite)
    }
    fun launchInviteScan() {
        if (!CameraCapture.hasCameraHardware(context)) {
            showMessage("此设备没有可用相机，请改用输入邀请码", dialog)
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
        if (granted) launchInviteScan()
        else showMessage("需要相机权限才能扫码，请在系统设置中开启，或改用输入邀请码", dialog)
    }
    fun scanWithPermission() {
        if (CameraCapture.hasPermission(context)) launchInviteScan()
        else scanCameraPermission.launch(CameraCapture.PERMISSION)
    }

    val networkConfigured = remember(ui.serverHost, ui.baseUrl, ui.allowedSsids) {
        isHomeLanNetworkConfigured(ui.serverHost, ui.baseUrl, ui.allowedSsids)
    }
    val controls = remember(ui.enabled, ui.role) {
        familyControlVisibility(ui.enabled, ui.role)
    }
    val primary = remember(ui.enabled, ui.role, networkConfigured) {
        familyPrimarySurface(ui.enabled, ui.role, networkConfigured)
    }
    val previewBaseUrl = remember(joinDraft.host, joinDraft.portText, joinDraft.scheme) {
        runCatching {
            HomeLanServerConfig.fromUserInput(
                rawHostOrUrl = joinDraft.host,
                explicitPort = joinDraft.portText.toIntOrNull(),
                allowedSsids = emptyList(),
                fallbackScheme = joinDraft.scheme,
            ).baseUrl
        }.getOrDefault("")
    }

    /** Wizard network step: save in-place and advance to identity (no NetworkSettings bounce). */
    fun saveWizardNetworkThenAdvance(mode: FamilyWizardMode) {
        if (!HomeWifiPermission.isSsidAccessReady(context)) {
            withHomeWifiAccess { saveWizardNetworkThenAdvance(mode) }
            return
        }
        if (joinDraft.ssid1.isBlank()) {
            vm.currentWifiSsid()?.trim()?.takeIf(String::isNotEmpty)?.let {
                joinDraft = joinDraft.copy(ssid1 = it)
            }
        }
        val host = joinDraft.host.trim()
        val ssids = joinDraft.ssids
        if (host.isBlank() || ssids.isEmpty()) {
            wizardNetworkFeedback = "请填写服务器主机并至少绑定一个家庭 Wi‑Fi 名称"
            dialog = FamilyDialog.Wizard(mode, FamilyWizardStep.Network)
            return
        }
        val dirty = host != ui.serverHost.trim() ||
            joinDraft.portText.toIntOrNull() != ui.serverPort ||
            joinDraft.scheme != ui.serverScheme ||
            ssids != ui.allowedSsids ||
            ui.allowedSsids.isEmpty()
        if (!dirty && ui.serverHost.isNotBlank() && ui.allowedSsids.isNotEmpty()) {
            wizardNetworkFeedback = null
            dialog = FamilyDialog.Wizard(mode, FamilyWizardStep.Identity)
            return
        }
        savingWizardNetwork = true
        wizardNetworkFeedback = null
        vm.saveHomeLanConfig(
            joinDraft.host,
            joinDraft.portText,
            joinDraft.ssid1,
            joinDraft.ssid2,
            joinDraft.scheme,
        ) { result ->
            savingWizardNetwork = false
            when (result) {
                is NetworkSaveResult.Saved -> {
                    wizardNetworkFeedback = null
                    dialog = FamilyDialog.Wizard(mode, FamilyWizardStep.Identity)
                }
                is NetworkSaveResult.Failed -> {
                    wizardNetworkFeedback = result.message
                    dialog = FamilyDialog.Wizard(mode, FamilyWizardStep.Network)
                }
            }
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
                controls = controls,
                primary = primary,
                networkConfigured = networkConfigured,
                onOpenMembers = {
                    withHomeWifiAccess {
                        vm.refreshMembers(showErrors = true)
                        dialog = FamilyDialog.MembersList
                    }
                },
                onOpenNetwork = { dialog = FamilyDialog.NetworkSettings },
                onCreateFamily = { openWizard(FamilyWizardMode.Create) },
                onJoinFamily = { openWizard(FamilyWizardMode.Join) },
                onCreateInvite = {
                    withHomeWifiAccess {
                        vm.createInvite { result ->
                            result.fold(
                                onSuccess = { dialog = FamilyDialog.Invite(it) },
                                onFailure = {
                                    showMessage(it.message ?: "生成共享码失败，请稍后重试")
                                },
                            )
                        }
                    }
                },
                onEditMyDisplayName = {
                    editDisplayName = ui.displayName.takeUnless {
                        it == LOCAL_FAMILY_DISPLAY_NAME
                    }.orEmpty()
                    editDisplayNameFeedback = null
                    dialog = FamilyDialog.EditMyDisplayName
                },
                onRenameFamily = {
                    renameFamilyName = ui.familyName.orEmpty()
                    renameFamilyFeedback = null
                    dialog = FamilyDialog.RenameFamily
                },
            )
        }
    }

    when (val active = dialog) {
        FamilyDialog.NetworkSettings -> FamilyNetworkSettingsSheet(
            ui = ui,
            host = joinDraft.host,
            onHostChange = { joinDraft = joinDraft.copy(host = it) },
            port = joinDraft.portText,
            onPortChange = { joinDraft = joinDraft.copy(portText = it) },
            ssid1 = joinDraft.ssid1,
            onSsid1Change = { joinDraft = joinDraft.copy(ssid1 = it) },
            ssid2 = joinDraft.ssid2,
            onSsid2Change = { joinDraft = joinDraft.copy(ssid2 = it) },
            previewBaseUrl = previewBaseUrl,
            networkConfigured = networkConfigured,
            controls = controls,
            onUseCurrentWifi = {
                withHomeWifiAccess {
                    val current = vm.currentWifiSsid()?.trim().orEmpty()
                    when {
                        current.isEmpty() -> dialog = FamilyDialog.HomeWifiAccessGuide(active)
                        joinDraft.ssid1.isBlank() -> joinDraft = joinDraft.copy(ssid1 = current)
                        joinDraft.ssid2.isBlank() && joinDraft.ssid1 != current ->
                            joinDraft = joinDraft.copy(ssid2 = current)
                        joinDraft.ssid1 != current && joinDraft.ssid2 != current ->
                            showMessage("Wi‑Fi 名称已满 2 个，请先清空一格", active)
                        else -> showMessage("当前 Wi‑Fi 已在列表中", active)
                    }
                }
            },
            onSave = {
                vm.saveHomeLanConfig(
                    joinDraft.host,
                    joinDraft.portText,
                    joinDraft.ssid1,
                    joinDraft.ssid2,
                    joinDraft.scheme,
                ) { result ->
                    showMessage(result.message, resume = active)
                }
            },
            onSync = {
                withHomeWifiAccess { vm.pullNow { showMessage(it, resume = active) } }
            },
            onLeave = { dialog = FamilyDialog.ConfirmLeave },
            onDeleteFamily = {
                dialog = FamilyDialog.DeleteFamily(FamilyDialog.DeleteStage.Warning)
            },
            onDismiss = { dialog = null },
        )
        FamilyDialog.MembersList -> if (ui.enabled) FamilyMembersListSheet(
            ui = ui,
            onRefreshMembers = { withHomeWifiAccess { vm.refreshMembers(showErrors = true) } },
            onEditMyDisplayName = {
                editDisplayName = ui.displayName.takeUnless {
                    it == LOCAL_FAMILY_DISPLAY_NAME
                }.orEmpty()
                editDisplayNameFeedback = null
                dialog = FamilyDialog.EditMyDisplayName
            },
            onDismiss = { dialog = null },
        )
        is FamilyDialog.Wizard -> when (active.step) {
            FamilyWizardStep.Network -> FamilyWizardNetworkDialog(
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
                ssid1 = joinDraft.ssid1,
                onSsid1Change = {
                    joinDraft = joinDraft.copy(ssid1 = it)
                    wizardNetworkFeedback = null
                },
                ssid2 = joinDraft.ssid2,
                onSsid2Change = {
                    joinDraft = joinDraft.copy(ssid2 = it)
                    wizardNetworkFeedback = null
                },
                networkConfigured = networkConfigured,
                feedback = wizardNetworkFeedback,
                saving = savingWizardNetwork,
                onUseCurrentWifi = {
                    withHomeWifiAccess {
                        val current = vm.currentWifiSsid()?.trim().orEmpty()
                        when {
                            current.isEmpty() ->
                                dialog = FamilyDialog.HomeWifiAccessGuide(active)
                            joinDraft.ssid1.isBlank() ->
                                joinDraft = joinDraft.copy(ssid1 = current)
                            joinDraft.ssid2.isBlank() && joinDraft.ssid1 != current ->
                                joinDraft = joinDraft.copy(ssid2 = current)
                            joinDraft.ssid1 != current && joinDraft.ssid2 != current ->
                                wizardNetworkFeedback = "Wi‑Fi 名称已满 2 个，请先清空一格"
                            else -> wizardNetworkFeedback = "当前 Wi‑Fi 已在列表中"
                        }
                    }
                },
                onContinue = { saveWizardNetworkThenAdvance(active.mode) },
                onDismiss = {
                    // Interruptible: already-saved host/SSID stay in session prefs.
                    wizardNetworkFeedback = null
                    savingWizardNetwork = false
                    dialog = null
                },
            )
            FamilyWizardStep.Identity -> when (active.mode) {
                FamilyWizardMode.Join -> if (controls.showJoin) JoinFamilyDialog(
                    joinCode = joinDraft.invitation,
                    onJoinCodeChange = { joinDraft = joinDraft.copy(invitation = it) },
                    displayName = joinDisplayName,
                    onDisplayNameChange = {
                        joinDisplayName = it
                        joinDisplayNameError = null
                    },
                    displayNameError = joinDisplayNameError,
                    joining = joiningFamily,
                    networkConfigured = networkConfigured ||
                        isHomeLanNetworkConfigured(
                            joinDraft.host,
                            "",
                            joinDraft.ssids,
                        ),
                    onScan = ::scanWithPermission,
                    onBackToNetwork = {
                        dialog = FamilyDialog.Wizard(FamilyWizardMode.Join, FamilyWizardStep.Network)
                    },
                    onConfirm = {
                        withHomeWifiAccess {
                            joiningFamily = true
                            vm.join(joinDraft, joinDisplayName) { success, copy ->
                                joiningFamily = false
                                if (success) {
                                    joinDisplayName = ""
                                    joinDisplayNameError = null
                                    dialog = FamilyDialog.Message(copy)
                                } else {
                                    joinDisplayNameError = copy.takeIf {
                                        it.contains("称呼") || it.contains("本机")
                                    }
                                    showMessage(
                                        copy,
                                        resume = FamilyDialog.Wizard(
                                            FamilyWizardMode.Join,
                                            FamilyWizardStep.Identity,
                                        ),
                                    )
                                }
                            }
                        }
                    },
                    onDismiss = {
                        joinDisplayNameError = null
                        dialog = null
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
                    bootstrapSecret = bootstrapSecret,
                    onBootstrapSecretChange = {
                        bootstrapSecret = it
                        bootstrapSecretFeedback = null
                    },
                    feedback = bootstrapSecretFeedback,
                    creating = creatingFamily,
                    networkConfigured = networkConfigured ||
                        isHomeLanNetworkConfigured(
                            joinDraft.host,
                            "",
                            joinDraft.ssids,
                        ),
                    onBackToNetwork = {
                        dialog = FamilyDialog.Wizard(
                            FamilyWizardMode.Create,
                            FamilyWizardStep.Network,
                        )
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
                        val secret = bootstrapSecret.trim()
                        if (secret.isEmpty()) {
                            bootstrapSecretFeedback = "请填写服务器初始化口令"
                        } else withHomeWifiAccess {
                            creatingFamily = true
                            bootstrapSecretFeedback = null
                            createDisplayNameError = null
                            createFamilyNameError = null
                            vm.createFamily(
                                displayName = createDisplayName,
                                bootstrapSecret = secret,
                                familyName = createFamilyName,
                            ) { success, copy ->
                                creatingFamily = false
                                if (success) {
                                    bootstrapSecret = ""
                                    createDisplayName = ""
                                    createFamilyName = ""
                                    dialog = FamilyDialog.Message(copy)
                                } else {
                                    when {
                                        copy.contains("称呼") || copy.contains("本机") ->
                                            createDisplayNameError = copy
                                        copy.contains("家庭名") -> createFamilyNameError = copy
                                        else -> bootstrapSecretFeedback = copy
                                    }
                                }
                            }
                        }
                    },
                    onDismiss = {
                        bootstrapSecret = ""
                        bootstrapSecretFeedback = null
                        createDisplayNameError = null
                        createFamilyName = ""
                        createFamilyNameError = null
                        creatingFamily = false
                        dialog = null
                    },
                )
            }
        }
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
                    withHomeWifiAccess {
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
        FamilyDialog.EditMyDisplayName -> EditMyDisplayNameDialog(
            displayName = editDisplayName,
            onDisplayNameChange = {
                editDisplayName = it
                editDisplayNameFeedback = null
            },
            feedback = editDisplayNameFeedback,
            saving = savingDisplayName,
            onConfirm = {
                validateFamilyDisplayNameInput(editDisplayName)?.let {
                    editDisplayNameFeedback = it
                    return@EditMyDisplayNameDialog
                }
                withHomeWifiAccess {
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
        is FamilyDialog.Invite -> FamilyInviteDialog(active.invite, onDismiss = { dialog = null })
        FamilyDialog.ConfirmLeave -> LeaveFamilyDialog(
            onConfirm = {
                dialog = null
                withHomeWifiAccess { vm.leave { showMessage(it) } }
            },
            onDismiss = { dialog = null },
        )
        is FamilyDialog.DeleteFamily -> DeleteFamilyDialog(
            stage = active.stage,
            onContinue = { dialog = FamilyDialog.DeleteFamily(FamilyDialog.DeleteStage.Final) },
            onConfirm = {
                dialog = null
                withHomeWifiAccess { vm.deleteFamily { showMessage(it) } }
            },
            onDismiss = { dialog = null },
        )
        is FamilyDialog.HomeWifiAccessGuide -> HomeWifiAccessGuideDialog(
            settingsTarget = HomeWifiPermission.settingsTarget(context),
            onOpenSettings = {
                dialog = active.resume
                context.startActivity(HomeWifiPermission.settingsIntent(context))
            },
            onDismiss = { dialog = active.resume },
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
