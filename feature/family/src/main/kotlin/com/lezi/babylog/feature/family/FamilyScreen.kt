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
import com.lezi.babylog.sync.HomeLanServerConfig
import com.lezi.babylog.sync.HomeWifiPermission
import com.lezi.babylog.sync.InvitePayloadCodec

@Composable
fun FamilyRoute(
    onAddBaby: () -> Unit = {},
    vm: FamilyViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<FamilyDialog?>(null) }
    var pendingAfterNetworkSave by remember { mutableStateOf<FamilyDialog?>(null) }
    var pendingHomeWifiAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingHomeWifiResume by remember { mutableStateOf<FamilyDialog?>(null) }
    var joinCode by remember { mutableStateOf("") }
    var joiningFamily by remember { mutableStateOf(false) }
    var bootstrapSecret by remember { mutableStateOf("") }
    var bootstrapSecretFeedback by remember { mutableStateOf<String?>(null) }
    var creatingFamily by remember { mutableStateOf(false) }

    val novice = remember {
        HomeLanServerConfig.noviceUiDefaults(
            if (HomeWifiPermission.hasRequiredPermissions(context)) vm.currentWifiSsid() else null,
        )
    }
    var serverHost by remember(ui.serverHost, ui.baseUrl) {
        mutableStateOf(
            when {
                ui.serverHost.isNotBlank() -> ui.serverHost
                ui.baseUrl.isNotBlank() -> HomeLanServerConfig.fromBaseUrl(ui.baseUrl).host
                else -> novice.host
            },
        )
    }
    var serverPort by remember(ui.serverPort, ui.baseUrl) {
        mutableStateOf(
            when {
                ui.serverHost.isNotBlank() || ui.serverPort != com.lezi.babylog.sync.DEFAULT_SERVER_PORT ->
                    ui.serverPort.toString()
                ui.baseUrl.isNotBlank() -> HomeLanServerConfig.fromBaseUrl(ui.baseUrl).port.toString()
                else -> novice.port.toString()
            },
        )
    }
    var serverScheme by remember(ui.serverScheme, ui.baseUrl) {
        mutableStateOf(
            ui.baseUrl.takeIf(String::isNotBlank)
                ?.let(HomeLanServerConfig::fromBaseUrl)
                ?.scheme
                ?: ui.serverScheme,
        )
    }
    var ssid1 by remember(ui.allowedSsids) {
        mutableStateOf(ui.allowedSsids.getOrNull(0) ?: novice.allowedSsids.getOrNull(0).orEmpty())
    }
    var ssid2 by remember(ui.allowedSsids) {
        mutableStateOf(ui.allowedSsids.getOrNull(1).orEmpty())
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

    fun applyScannedInvite(raw: String) {
        val payload = raw.trim()
        if (payload.isEmpty()) return
        joinCode = payload
        val scanCopy = runCatching { InvitePayloadCodec.decode(payload) }.getOrNull()?.let { decoded ->
            val config = decoded.homeLanConfig
            if (config.host.isNotBlank()) {
                serverHost = config.host
                serverPort = config.port.toString()
                serverScheme = config.scheme
            }
            if (decoded.ssids.isNotEmpty()) {
                ssid1 = decoded.ssids.getOrNull(0).orEmpty()
                ssid2 = decoded.ssids.getOrNull(1).orEmpty()
            }
            buildString {
                append("已扫入邀请")
                if (config.host.isNotBlank()) append(" · ${config.host}:${config.port}")
                if (decoded.ssids.isNotEmpty()) append(" · Wi‑Fi ${decoded.ssids.joinToString(" / ")}")
            }
        }
        dialog = if (scanCopy == null) FamilyDialog.Join
        else FamilyDialog.Message(scanCopy, resume = FamilyDialog.Join)
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
    val savedSummaryBaseUrl = remember(
        ui.serverHost,
        ui.serverPort,
        ui.serverScheme,
        ui.baseUrl,
        ui.allowedSsids,
    ) {
        when {
            ui.serverHost.isNotBlank() -> HomeLanServerConfig(
                ui.serverHost,
                ui.serverPort,
                ui.allowedSsids,
                ui.serverScheme,
            ).baseUrl
            ui.baseUrl.isNotBlank() -> ui.baseUrl
            else -> ""
        }
    }
    val previewBaseUrl = remember(serverHost, serverPort, serverScheme) {
        runCatching {
            HomeLanServerConfig.fromUserInput(
                rawHostOrUrl = serverHost,
                explicitPort = serverPort.toIntOrNull(),
                allowedSsids = emptyList(),
                fallbackScheme = serverScheme,
            ).baseUrl
        }.getOrDefault("")
    }

    fun saveNetworkThen(target: FamilyDialog) {
        if (!HomeWifiPermission.isSsidAccessReady(context)) {
            withHomeWifiAccess { saveNetworkThen(target) }
            return
        }
        if (ssid1.isBlank()) {
            vm.currentWifiSsid()?.trim()?.takeIf(String::isNotEmpty)?.let { ssid1 = it }
        }
        val host = serverHost.trim()
        val ssids = listOf(ssid1, ssid2).map(String::trim).filter(String::isNotEmpty)
        val dirty = host != ui.serverHost.trim() || ssids != ui.allowedSsids || ui.allowedSsids.isEmpty()
        if (host.isBlank() || ssids.isEmpty()) {
            pendingAfterNetworkSave = target
            showMessage(
                "请先在「家庭网络设置」中填写并保存服务器与 Wi‑Fi 名称",
                resume = FamilyDialog.NetworkSettings,
            )
            return
        }
        if (!dirty && ui.serverHost.isNotBlank() && ui.allowedSsids.isNotEmpty()) {
            dialog = target
            return
        }
        vm.saveHomeLanConfig(serverHost, serverPort, ssid1, ssid2, serverScheme) { result ->
            deliverNetworkSaveResult(
                result,
                onMessage = { copy ->
                    showMessage(copy, resume = target.takeIf { result is NetworkSaveResult.Saved })
                },
            )
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
                onOpenNetwork = {
                    pendingAfterNetworkSave = null
                    dialog = FamilyDialog.NetworkSettings
                },
                onOpenMembers = {
                    // Member list still lives in the sharing section below; refresh when user seeks it.
                    withHomeWifiAccess { vm.refreshMembers(showErrors = true) }
                },
            )
            FamilySharingContent(
                ui = ui,
                controls = controls,
                primary = primary,
                networkConfigured = networkConfigured,
                savedSummaryBaseUrl = savedSummaryBaseUrl,
                onRefreshMembers = { withHomeWifiAccess { vm.refreshMembers(showErrors = true) } },
                onOpenNetwork = {
                    pendingAfterNetworkSave = null
                    dialog = FamilyDialog.NetworkSettings
                },
                onCreateFamily = { saveNetworkThen(FamilyDialog.CreateFamily) },
                onJoinFamily = { saveNetworkThen(FamilyDialog.Join) },
                onScanInvite = ::scanWithPermission,
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
                onSync = {
                    withHomeWifiAccess { vm.pullNow { showMessage(it) } }
                },
                onLeave = { dialog = FamilyDialog.ConfirmLeave },
                onDeleteFamily = {
                    dialog = FamilyDialog.DeleteFamily(FamilyDialog.DeleteStage.Warning)
                },
            )
        }
    }

    when (val active = dialog) {
        FamilyDialog.NetworkSettings -> FamilyNetworkSettingsSheet(
            ui = ui,
            host = serverHost,
            onHostChange = { serverHost = it },
            port = serverPort,
            onPortChange = { serverPort = it },
            ssid1 = ssid1,
            onSsid1Change = { ssid1 = it },
            ssid2 = ssid2,
            onSsid2Change = { ssid2 = it },
            previewBaseUrl = previewBaseUrl,
            networkConfigured = networkConfigured,
            onUseCurrentWifi = {
                withHomeWifiAccess {
                    val current = vm.currentWifiSsid()?.trim().orEmpty()
                    when {
                        current.isEmpty() -> dialog = FamilyDialog.HomeWifiAccessGuide(active)
                        ssid1.isBlank() -> ssid1 = current
                        ssid2.isBlank() && ssid1 != current -> ssid2 = current
                        ssid1 != current && ssid2 != current ->
                            showMessage("Wi‑Fi 名称已满 2 个，请先清空一格", active)
                        else -> showMessage("当前 Wi‑Fi 已在列表中", active)
                    }
                }
            },
            onSave = {
                vm.saveHomeLanConfig(serverHost, serverPort, ssid1, ssid2, serverScheme) { result ->
                    val next = if (result is NetworkSaveResult.Saved) pendingAfterNetworkSave else active
                    if (result is NetworkSaveResult.Saved) pendingAfterNetworkSave = null
                    showMessage(result.message, resume = next)
                }
            },
            onDismiss = {
                pendingAfterNetworkSave = null
                dialog = null
            },
        )
        FamilyDialog.Join -> if (controls.showJoin) JoinFamilyDialog(
            joinCode = joinCode,
            onJoinCodeChange = { joinCode = it },
            joining = joiningFamily,
            onScan = ::scanWithPermission,
            onConfirm = {
                withHomeWifiAccess {
                    joiningFamily = true
                    vm.join(joinCode, serverHost, serverPort, ssid1, ssid2, serverScheme) { success, copy ->
                        joiningFamily = false
                        showMessage(copy, resume = FamilyDialog.Join.takeUnless { success })
                    }
                }
            },
            onDismiss = { dialog = null },
        )
        FamilyDialog.CreateFamily -> if (controls.showCreateFamily) CreateFamilyDialog(
            bootstrapSecret = bootstrapSecret,
            onBootstrapSecretChange = {
                bootstrapSecret = it
                bootstrapSecretFeedback = null
            },
            feedback = bootstrapSecretFeedback,
            creating = creatingFamily,
            onConfirm = {
                val secret = bootstrapSecret.trim()
                if (secret.isEmpty()) {
                    bootstrapSecretFeedback = "请填写服务器初始化口令"
                } else withHomeWifiAccess {
                    creatingFamily = true
                    bootstrapSecretFeedback = null
                    vm.createFamily(secret) { success, copy ->
                        creatingFamily = false
                        if (success) {
                            bootstrapSecret = ""
                            dialog = FamilyDialog.Message(copy)
                        } else bootstrapSecretFeedback = copy
                    }
                }
            },
            onDismiss = {
                bootstrapSecret = ""
                bootstrapSecretFeedback = null
                creatingFamily = false
                dialog = null
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
