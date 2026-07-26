package com.lezi.babylog.feature.family

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.common.looksTechnicalDetail
import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.core.ui.BabyAvatar
import com.lezi.babylog.core.ui.CameraCapture
import com.lezi.babylog.designsystem.LeziCard
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.PageScaffoldBackground
import com.lezi.babylog.designsystem.SectionHeading
import com.lezi.babylog.designsystem.dismissKeyboardOnTap
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.BabyMergePreview
import com.lezi.babylog.domain.DuplicateBabyNicknameException
import com.lezi.babylog.domain.UpdateBabyInput
import com.lezi.babylog.domain.babyAgeLabel
import com.lezi.babylog.sync.SyncNotEnabledException
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.FamilyRole
import com.lezi.babylog.sync.HomeLanServerConfig
import com.lezi.babylog.sync.InvitePayload
import com.lezi.babylog.sync.InvitePayloadCodec
import com.lezi.babylog.sync.PUBLIC_CLEARTEXT_WARNING
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.isPublicCleartextBaseUrl
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class FamilyUi(
    val deviceId: String = "",
    val displayName: String = "我（本机）",
    val status: SyncStatus = SyncStatus.Disabled,
    val enabled: Boolean = false,
    val hasLocalBaby: Boolean = false,
    val familyId: String = "1",
    val current: Baby? = null,
    val babies: List<Baby> = emptyList(),
    val baseUrl: String = "",
    val serverHost: String = "",
    val serverPort: Int = com.lezi.babylog.sync.DEFAULT_SERVER_PORT,
    val allowedSsids: List<String> = emptyList(),
    val role: FamilyRole = FamilyRole.None,
    val lastSuccessAt: Long? = null,
)

data class FamilyInviteView(
    val code: String,
    val payload: String,
    val expiresAt: Long,
)

@HiltViewModel
class FamilyViewModel @Inject constructor(
    private val sync: SyncPort,
    private val careLog: CareLog,
    private val avatarFileStore: BabyAvatarFileStore,
    private val networkState: com.lezi.babylog.sync.NetworkState,
) : ViewModel() {
    private val profileSaveMutex = Mutex()

    fun currentWifiSsid(): String? = networkState.currentWifiSsid()

    val ui = combine(
        sync.status(),
        careLog.observeHasBaby(),
        careLog.observeCurrentBaby(),
        careLog.observeBabies(),
        sync.session(),
    ) { st, hasBaby, current, babies, session ->
        val identity = careLog.localFamilyIdentity()
        FamilyUi(
            deviceId = familyDeviceId(
                syncDeviceId = session.deviceId,
                localDeviceId = identity.deviceId,
            ),
            displayName = identity.displayName,
            status = st,
            enabled = session.isJoined,
            hasLocalBaby = hasBaby,
            familyId = session.familyId.ifBlank { identity.familyId.toString() },
            current = current,
            babies = babies,
            baseUrl = session.baseUrl,
            serverHost = session.serverHost,
            serverPort = session.serverPort,
            allowedSsids = session.allowedSsids,
            role = session.role,
            lastSuccessAt = session.lastSuccessAt,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FamilyUi())

    fun setCurrent(id: Long) {
        viewModelScope.launch { careLog.setCurrentBaby(id) }
    }

    fun updateBaby(
        babyId: Long,
        nickname: String,
        sex: String?,
        birthdayEpochDay: Long,
        birthWeightGrams: Int?,
        avatarJpeg: ByteArray?,
        removeAvatar: Boolean,
        onDone: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            profileSaveMutex.withLock {
                val existing = careLog.listBabies().firstOrNull { it.id == babyId }
                if (existing == null) {
                    onDone("宝宝档案不存在")
                    return@withLock
                }
                var writtenAvatarPath: String? = null
                var profileCommitted = false
                val mayEditAvatar = canEditFamilyAvatar(ui.value.role)

                suspend fun rollbackWrittenAvatar() {
                    val path = writtenAvatarPath ?: return
                    withContext(NonCancellable) {
                        try {
                            avatarFileStore.delete(path)
                        } catch (_: Throwable) {
                            // Preserve the original save failure or cancellation.
                        }
                    }
                }

                val errorMessage = try {
                    val avatarPath = when {
                        mayEditAvatar && avatarJpeg != null -> {
                            avatarFileStore.write(existing.clientUuid, avatarJpeg).also {
                                writtenAvatarPath = it
                            }
                        }
                        mayEditAvatar && removeAvatar -> null
                        else -> existing.avatarPath
                    }
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        careLog.updateBabyProfile(
                            babyId,
                            UpdateBabyInput(
                                nickname = nickname,
                                sex = sex,
                                birthdayEpochDay = birthdayEpochDay,
                                birthWeightGrams = birthWeightGrams,
                                avatarPath = avatarPath,
                                dueDateEpochDay = existing.dueDateEpochDay,
                                themeColorArgb = existing.themeColorArgb,
                            ),
                        )
                        profileCommitted = true
                        if (avatarPath != existing.avatarPath) {
                            try {
                                avatarFileStore.delete(existing.avatarPath)
                            } catch (_: Throwable) {
                                // The new profile is durable; stale cleanup is best effort.
                            }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    null
                } catch (cancelled: CancellationException) {
                    if (!profileCommitted) rollbackWrittenAvatar()
                    throw cancelled
                } catch (error: Throwable) {
                    if (!profileCommitted) rollbackWrittenAvatar()
                    if (error is DuplicateBabyNicknameException) {
                        error.message
                    } else {
                        "保存失败，请重试"
                    }
                }
                currentCoroutineContext().ensureActive()
                onDone(errorMessage)
            }
        }
    }

    fun deleteBaby(babyId: Long, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val avatarPath = ui.value.babies.firstOrNull { it.id == babyId }?.avatarPath
            currentCoroutineContext().ensureActive()
            val ok = withContext(NonCancellable) {
                careLog.deleteBaby(babyId).also { deleted ->
                    if (deleted) {
                        try {
                            avatarFileStore.delete(avatarPath)
                        } catch (_: Throwable) {
                            // The soft-deleted profile no longer references this local file.
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            onDone(if (ok) "已删除宝宝档案" else "至少保留一位宝宝档案")
        }
    }

    fun previewMerge(
        sourceBabyId: Long,
        targetBabyId: Long,
        onDone: (BabyMergePreview?) -> Unit,
    ) {
        viewModelScope.launch {
            onDone(careLog.previewBabyMerge(sourceBabyId, targetBabyId))
        }
    }

    fun merge(preview: BabyMergePreview, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val merged = careLog.mergeBabyProfiles(
                sourceBabyId = preview.sourceBabyId,
                targetBabyId = preview.targetBabyId,
            )
            onDone(if (merged) "宝宝档案已合并" else "档案状态已变化，请重新预览")
        }
    }

    fun createInvite(onResult: (Result<FamilyInviteView>) -> Unit) {
        viewModelScope.launch {
            val familyId = ui.value.familyId
            val result = sync.createInvite(familyId)
            onResult(
                result
                    .map {
                        val session = ui.value
                        val host = session.serverHost.ifBlank {
                            HomeLanServerConfig.fromBaseUrl(session.baseUrl).host
                        }
                        val port = session.serverPort.takeIf { p -> p in 1..65535 }
                            ?: HomeLanServerConfig.fromBaseUrl(session.baseUrl).port
                        val base = session.baseUrl.ifBlank {
                            HomeLanServerConfig(host = host, port = port).baseUrl
                        }
                        FamilyInviteView(
                            code = it.code,
                            payload = InvitePayloadCodec.encode(
                                InvitePayload(
                                    baseUrl = base,
                                    code = it.code,
                                    host = host,
                                    port = port,
                                    ssids = session.allowedSsids,
                                ),
                            ),
                            expiresAt = it.expiresAt,
                        )
                    }
                    .recoverCatching {
                        throw IllegalStateException(
                            familySyncError(it, fallback = "生成共享码失败，请稍后重试"),
                        )
                    },
            )
        }
    }

    fun join(code: String, onMessage: (String) -> Unit) {
        viewModelScope.launch {
            val result = sync.joinWithPayload(code.trim())
            onMessage(
                result.fold(
                    onSuccess = {
                        "已加入家庭"
                    },
                    onFailure = {
                        familySyncError(it, fallback = "加入家庭失败，请稍后重试")
                    },
                ),
            )
        }
    }

    fun leave(onMessage: (String) -> Unit) {
        viewModelScope.launch {
            val id = ui.value.familyId
            val result = sync.leave(id)
            onMessage(
                result.fold(
                    onSuccess = { "已离开家庭" },
                    onFailure = { familySyncError(it, fallback = "离开家庭失败，请稍后重试") },
                ),
            )
        }
    }

    fun pullNow(onMessage: (String) -> Unit) {
        viewModelScope.launch {
            val id = ui.value.familyId
            val r = sync.sync(SyncTrigger.PullToRefresh)
            onMessage(
                r.fold(
                    onSuccess = { "已同步" },
                    onFailure = { familySyncError(it, fallback = "同步失败，请稍后重试") },
                ),
            )
        }
    }

    fun saveServer(baseUrl: String, onMessage: (String) -> Unit) {
        viewModelScope.launch {
            onMessage(sync.saveServer(baseUrl).fold({ "家庭服务器地址已保存" }) {
                familySyncError(it, "服务器地址无效")
            })
        }
    }

    fun saveHomeLanConfig(
        host: String,
        portText: String,
        ssid1: String,
        ssid2: String,
        onMessage: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val (h, p) = com.lezi.babylog.sync.HomeLanServerConfig.parseHostPort(
                host,
                portText.toIntOrNull() ?: com.lezi.babylog.sync.DEFAULT_SERVER_PORT,
            )
            val port = portText.toIntOrNull() ?: p
            val config = com.lezi.babylog.sync.HomeLanServerConfig(
                host = h,
                port = port,
                allowedSsids = listOf(ssid1, ssid2),
            )
            onMessage(sync.saveHomeLanConfig(config).fold({ "家庭网络与服务器已保存" }) {
                familySyncError(it, "保存失败")
            })
        }
    }

    fun createFamily(onMessage: (String) -> Unit) {
        viewModelScope.launch {
            onMessage(sync.createFamily(ui.value.displayName).fold(
                { "家庭已创建" },
                { familySyncError(it, "创建家庭失败") },
            ))
        }
    }

    fun deleteFamily(onMessage: (String) -> Unit) {
        viewModelScope.launch {
            onMessage(sync.deleteFamily().fold(
                { "家庭数据已删除" },
                { familySyncError(it, "删除家庭失败") },
            ))
        }
    }
}

internal fun familySyncError(error: Throwable, fallback: String): String {
    if (error is SyncNotEnabledException) {
        return "请先填写家庭服务器地址并绑定 Wi‑Fi 名称后加入家庭"
    }
    val message = error.message.orEmpty()
    // Network/host/path leaks always collapse to a fixed product line.
    if (looksTechnicalDetail(message)) {
        return "家庭同步服务暂未连接，请稍后重试"
    }
    return productUiError(error, fallback)
}

/** Apply FLAG_SECURE for the lifetime of the current composition (invite QR). */
@Composable
private fun SecureWindowWhileVisible() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = view.context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Product-facing status line.
 * [hasServer]/[hasSsid] reflect **saved** prefs (not the unsaved form draft).
 */
internal fun syncStatusLabel(
    status: SyncStatus,
    hasServer: Boolean = false,
    hasSsid: Boolean = false,
    isJoined: Boolean = false,
): String = when (status) {
    SyncStatus.Disabled -> when {
        isJoined -> "未启用"
        !hasServer && !hasSsid -> "未加入家庭（请先保存服务器与 Wi‑Fi 名称）"
        !hasServer -> "未加入家庭（请先保存服务器地址）"
        !hasSsid -> "未加入家庭（请先保存 Wi‑Fi 名称）"
        else -> "网络已配置 · 尚未加入家庭"
    }
    SyncStatus.BlockedOfflineHome -> "等待家庭 Wi‑Fi 或服务器可达"
    SyncStatus.Idle -> "空闲"
    SyncStatus.Syncing -> "同步中"
    SyncStatus.Error -> "同步错误"
}

internal fun canEditFamilyAvatar(role: FamilyRole): Boolean = role != FamilyRole.Member

internal fun familyStorageCopy(enabled: Boolean): String =
    if (enabled) {
        "记录本地优先，并同步到家庭服务器 · 无需云账号"
    } else {
        "数据仅保存在本机 · 无需登录"
    }

internal fun familyDeviceId(syncDeviceId: String, localDeviceId: String): String =
    syncDeviceId.ifBlank { localDeviceId }

internal data class FamilyControlVisibility(
    val showServerSetup: Boolean,
    val showJoin: Boolean,
    val showCreateFamily: Boolean,
    val showInvite: Boolean,
    val showJoinedActions: Boolean,
    val showLeave: Boolean,
)

internal fun familyControlVisibility(
    isJoined: Boolean,
    role: FamilyRole,
): FamilyControlVisibility = FamilyControlVisibility(
    // Network editors live on a secondary surface, not the primary list.
    showServerSetup = false,
    showJoin = !isJoined,
    showCreateFamily = !isJoined && role == FamilyRole.None,
    showInvite = isJoined && role == FamilyRole.Owner,
    showJoinedActions = isJoined,
    showLeave = isJoined && role == FamilyRole.Member,
)

/** Saved host/baseUrl + non-empty SSID allowlist. */
internal fun isHomeLanNetworkConfigured(
    serverHost: String,
    baseUrl: String,
    allowedSsids: List<String>,
): Boolean =
    (serverHost.isNotBlank() || baseUrl.isNotBlank()) && allowedSsids.isNotEmpty()

/**
 * Primary account “家人一起记” layout rules.
 * Network host/port/SSID editors are never on the primary surface.
 */
internal data class FamilyPrimarySurface(
    /** Joined with saved network config → compact operational essentials only. */
    val compactJoined: Boolean,
    val showCreateJoin: Boolean,
    val showInvite: Boolean,
    val showJoinedActions: Boolean,
    val showLeave: Boolean,
    /** Always true: open secondary network settings. */
    val showNetworkSecondaryEntry: Boolean,
    /** Primary must not render host/port/SSID editors. */
    val showNetworkEditorsOnPrimary: Boolean,
)

internal fun familyPrimarySurface(
    isJoined: Boolean,
    role: FamilyRole,
    networkConfigured: Boolean,
): FamilyPrimarySurface {
    val controls = familyControlVisibility(isJoined, role)
    // Editors never sit on primary; reconfigure always via secondary entry.
    val editorsOnPrimary = false
    return FamilyPrimarySurface(
        compactJoined = isJoined && networkConfigured,
        showCreateJoin = controls.showJoin || controls.showCreateFamily,
        showInvite = controls.showInvite,
        showJoinedActions = controls.showJoinedActions,
        showLeave = controls.showLeave,
        showNetworkSecondaryEntry = !editorsOnPrimary,
        showNetworkEditorsOnPrimary = editorsOnPrimary,
    )
}

internal fun familyRoleLabel(role: FamilyRole): String = when (role) {
    FamilyRole.Owner -> "管理员"
    FamilyRole.Member -> "成员"
    FamilyRole.None -> "未加入"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyRoute(vm: FamilyViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    var showJoin by remember { mutableStateOf(false) }
    var joinCode by remember { mutableStateOf("") }
    val novice = remember {
        com.lezi.babylog.sync.HomeLanServerConfig.noviceUiDefaults(vm.currentWifiSsid())
    }
    // Defaults prefill an empty form but are not persisted until save.
    val persistedEmpty = ui.serverHost.isBlank() && ui.baseUrl.isBlank()
    var serverHost by remember(ui.serverHost, ui.baseUrl) {
        mutableStateOf(
            when {
                ui.serverHost.isNotBlank() -> ui.serverHost
                ui.baseUrl.isNotBlank() -> com.lezi.babylog.sync.HomeLanServerConfig.fromBaseUrl(ui.baseUrl).host
                else -> novice.host
            },
        )
    }
    var serverPort by remember(ui.serverPort, ui.baseUrl) {
        mutableStateOf(
            when {
                ui.serverHost.isNotBlank() || ui.serverPort != com.lezi.babylog.sync.DEFAULT_SERVER_PORT ->
                    ui.serverPort.toString()
                ui.baseUrl.isNotBlank() ->
                    com.lezi.babylog.sync.HomeLanServerConfig.fromBaseUrl(ui.baseUrl).port.toString()
                else -> novice.port.toString()
            },
        )
    }
    var ssid1 by remember(ui.allowedSsids) {
        mutableStateOf(ui.allowedSsids.getOrNull(0) ?: novice.allowedSsids.getOrNull(0).orEmpty())
    }
    var ssid2 by remember(ui.allowedSsids) {
        mutableStateOf(ui.allowedSsids.getOrNull(1).orEmpty())
    }
    val previewBaseUrl = remember(serverHost, serverPort) {
        com.lezi.babylog.sync.HomeLanServerConfig(
            host = serverHost,
            port = serverPort.toIntOrNull() ?: com.lezi.babylog.sync.DEFAULT_SERVER_PORT,
        ).baseUrl
    }
    var confirmDeleteFamily by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Baby?>(null) }
    var confirmDelete by remember { mutableStateOf<Baby?>(null) }
    var mergeSource by remember { mutableStateOf<Baby?>(null) }
    var mergePreview by remember { mutableStateOf<BabyMergePreview?>(null) }
    var inviteView by remember { mutableStateOf<FamilyInviteView?>(null) }
    var showNetworkSettings by remember { mutableStateOf(false) }
    var pendingAfterNetworkSave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val networkSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val familyContext = LocalContext.current
    fun applyScannedInvite(raw: String) {
        val payload = raw.trim()
        if (payload.isEmpty()) return
        joinCode = payload
        runCatching { InvitePayloadCodec.decode(payload) }.getOrNull()?.let { decoded ->
            val config = decoded.homeLanConfig
            if (config.host.isNotBlank()) {
                serverHost = config.host
                serverPort = config.port.toString()
            }
            if (decoded.ssids.isNotEmpty()) {
                ssid1 = decoded.ssids.getOrNull(0).orEmpty()
                ssid2 = decoded.ssids.getOrNull(1).orEmpty()
            }
            message = buildString {
                append("已扫入邀请")
                if (config.host.isNotBlank()) append(" · ${config.host}:${config.port}")
                if (decoded.ssids.isNotEmpty()) {
                    append(" · Wi‑Fi ${decoded.ssids.joinToString(" / ")}")
                }
            }
        }
        showJoin = true
    }
    val scanInvite = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(::applyScannedInvite)
    }
    fun launchInviteScan() {
        if (!CameraCapture.hasCameraHardware(familyContext)) {
            message = "此设备没有可用相机，请改用输入邀请码"
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
            message = "需要相机权限才能扫码，请在系统设置中开启，或改用输入邀请码"
        }
    }
    val current = ui.current
    val nickCounts = remember(ui.babies) {
        ui.babies.groupingBy { it.nickname.trim() }.eachCount()
    }
    val controls = remember(ui.enabled, ui.role) {
        familyControlVisibility(isJoined = ui.enabled, role = ui.role)
    }
    val networkConfigured = remember(ui.serverHost, ui.baseUrl, ui.allowedSsids) {
        isHomeLanNetworkConfigured(ui.serverHost, ui.baseUrl, ui.allowedSsids)
    }
    val primary = remember(ui.enabled, ui.role, networkConfigured) {
        familyPrimarySurface(
            isJoined = ui.enabled,
            role = ui.role,
            networkConfigured = networkConfigured,
        )
    }
    val savedSummaryBaseUrl = remember(ui.serverHost, ui.serverPort, ui.baseUrl) {
        when {
            ui.serverHost.isNotBlank() ->
                HomeLanServerConfig(ui.serverHost, ui.serverPort, ui.allowedSsids).baseUrl
            ui.baseUrl.isNotBlank() -> ui.baseUrl
            else -> ""
        }
    }

    PageScaffoldBackground {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(LeziSpacing.Page),
            verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
        ) {
            com.lezi.babylog.designsystem.PageHero(
                eyebrow = "",
                title = "账户",
                subtitle = "宝宝档案、家庭成员和同步设置都在这里。",
            )

            LeziCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val accent = current?.themeColorArgb?.let { Color(it) }
                            ?: MaterialTheme.colorScheme.primary
                        BabyAvatar(
                            nickname = current?.nickname.orEmpty(),
                            avatarPath = current?.avatarPath,
                            fallbackBackground = accent,
                            fallbackStyle = LeziTypography.Title,
                            modifier = Modifier.size(56.dp),
                            borderWidth = 3.dp,
                            avatarContentDescription = current?.let { "${it.nickname}的头像" },
                        )
                        Spacer(Modifier.size(LeziSpacing.Sm))
                        Column {
                            Text("当前宝宝", style = LeziTypography.Meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(current?.nickname ?: "—", style = LeziTypography.TitleSm)
                            val age = current?.let { babyAgeLabel(it.birthdayEpochDay) }.orEmpty()
                            val sex = when (current?.sex?.name) {
                                "MALE" -> "男宝"
                                "FEMALE" -> "女宝"
                                else -> ""
                            }
                            val birth = current?.let {
                                LocalDate.ofEpochDay(it.birthdayEpochDay).toString()
                            }.orEmpty()
                            val weight = current?.birthWeightGrams?.let { grams ->
                                if (grams % 1000 == 0) "${grams / 1000}kg" else String.format("%.2fkg", grams / 1000.0)
                            }.orEmpty()
                            Text(
                                listOfNotNull(
                                    birth.takeIf { it.isNotBlank() }?.let { "${it}出生" },
                                    weight.takeIf { it.isNotBlank() }?.let { "出生体重 $it" },
                                    sex.takeIf { it.isNotBlank() },
                                    age.takeIf { it.isNotBlank() },
                                ).joinToString(" · "),
                                style = LeziTypography.Meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (current != null) {
                            LeziSecondaryButton("编辑", onClick = { editing = current })
                        }
                        if (ui.babies.size > 1) {
                            LeziSecondaryButton("切换", onClick = {
                                val cur = ui.current?.id
                                val idx = ui.babies.indexOfFirst { it.id == cur }.takeIf { it >= 0 } ?: 0
                                val next = ui.babies[(idx + 1) % ui.babies.size]
                                vm.setCurrent(next.id)
                            })
                        }
                    }
                }
            }

            LeziCard(modifier = Modifier.fillMaxWidth()) {
                Text(familyStorageCopy(ui.enabled), style = LeziTypography.BodyStrong)
                Text(
                    "本机 ID：${ui.deviceId.take(12).uppercase()}",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionHeading(title = "宝宝档案")
            ui.babies.forEach { b ->
                val selected = b.id == current?.id
                val dup = (nickCounts[b.nickname.trim()] ?: 0) > 1
                LeziCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            BabyAvatar(
                                nickname = b.nickname,
                                avatarPath = b.avatarPath,
                                fallbackBackground = Color(b.themeColorArgb),
                                fallbackStyle = LeziTypography.TitleSm,
                                modifier = Modifier.size(40.dp),
                                borderWidth = 2.dp,
                                avatarContentDescription = "${b.nickname}的头像",
                            )
                            Spacer(Modifier.size(LeziSpacing.Sm))
                            Column {
                                Text(
                                    b.nickname + if (selected) "（当前）" else "",
                                    style = LeziTypography.BodyStrong,
                                )
                                val birth = LocalDate.ofEpochDay(b.birthdayEpochDay)
                                    .format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
                                val weight = b.birthWeightGrams?.let { " · 出生 ${it}g" }.orEmpty()
                                Text(
                                    "${babyAgeLabel(b.birthdayEpochDay)} · $birth$weight" +
                                        if (dup) " · 昵称重复" else "",
                                    style = LeziTypography.Meta,
                                    color = if (dup) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(LeziSpacing.Sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!selected) {
                            LeziSecondaryButton("设为当前", onClick = { vm.setCurrent(b.id) })
                        }
                        LeziSecondaryButton("编辑", onClick = { editing = b })
                        if (ui.babies.size > 1) {
                            LeziSecondaryButton("合并", onClick = { mergeSource = b })
                            LeziSecondaryButton("删除", onClick = { confirmDelete = b })
                        }
                    }
                }
            }
            Text(
                "昵称不可重复。合并前会明确显示来源、目标和迁移数量；不会按昵称自动迁移。",
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionHeading(title = "家人一起记")
            fun saveNetworkThen(onReady: () -> Unit) {
                val host = serverHost.trim()
                val ssids = listOf(ssid1, ssid2).map { it.trim() }.filter { it.isNotEmpty() }
                val dirty =
                    host != ui.serverHost.trim() ||
                        ssids != ui.allowedSsids ||
                        ui.allowedSsids.isEmpty()
                if (host.isBlank() || ssids.isEmpty()) {
                    pendingAfterNetworkSave = onReady
                    showNetworkSettings = true
                    message = "请先在「家庭网络设置」中填写并保存服务器与 Wi‑Fi 名称"
                    return
                }
                if (!dirty && ui.serverHost.isNotBlank() && ui.allowedSsids.isNotEmpty()) {
                    onReady()
                    return
                }
                vm.saveHomeLanConfig(serverHost, serverPort, ssid1, ssid2) { result ->
                    message = result
                    if (result.contains("已保存")) onReady()
                }
            }

            // Driven by familyPrimarySurface — editors only if flag true (product default: false).
            if (primary.showNetworkEditorsOnPrimary) {
                // Fallback path kept for the predicate; product keeps editors off primary.
                OutlinedTextField(
                    value = serverHost,
                    onValueChange = { serverHost = it },
                    label = { Text("家庭服务器主机（IP 或域名）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = serverPort,
                    onValueChange = { serverPort = it.filter { ch -> ch.isDigit() }.take(5) },
                    label = { Text("端口") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ssid1,
                    onValueChange = { ssid1 = it },
                    label = { Text("家庭 Wi‑Fi 名称 1（如 2.4G）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ssid2,
                    onValueChange = { ssid2 = it },
                    label = { Text("家庭 Wi‑Fi 名称 2（可选，如 5G）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LeziSecondaryButton(
                    "保存家庭网络与服务器",
                    onClick = {
                        vm.saveHomeLanConfig(serverHost, serverPort, ssid1, ssid2) { message = it }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            LeziCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (primary.compactJoined) {
                        Text(
                            "家庭 · ${familyRoleLabel(ui.role)}",
                            style = LeziTypography.BodyStrong,
                        )
                        Text(
                            "状态：${syncStatusLabel(
                                status = ui.status,
                                hasServer = true,
                                hasSsid = true,
                                isJoined = true,
                            )}",
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            buildString {
                                append(savedSummaryBaseUrl.ifBlank { "服务器已配置" })
                                if (ui.allowedSsids.isNotEmpty()) {
                                    append(" · ")
                                    append(ui.allowedSsids.joinToString(" / "))
                                }
                            },
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (savedSummaryBaseUrl.isNotBlank() &&
                            isPublicCleartextBaseUrl(savedSummaryBaseUrl)
                        ) {
                            Text(
                                PUBLIC_CLEARTEXT_WARNING,
                                style = LeziTypography.Meta,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                        Text(
                            if (ui.enabled) {
                                "家庭 · ${familyRoleLabel(ui.role)}"
                            } else {
                                "尚未加入家庭"
                            },
                            style = LeziTypography.BodyStrong,
                        )
                        Text(
                            "状态：${syncStatusLabel(
                                status = ui.status,
                                hasServer = ui.serverHost.isNotBlank() || ui.baseUrl.isNotBlank(),
                                hasSsid = ui.allowedSsids.isNotEmpty(),
                                isJoined = ui.enabled,
                            )}" +
                                (ui.lastSuccessAt?.let {
                                    " · 上次成功：${java.text.DateFormat.getDateTimeInstance().format(it)}"
                                } ?: ""),
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (networkConfigured) {
                            Text(
                                buildString {
                                    append(savedSummaryBaseUrl.ifBlank { "服务器已配置" })
                                    if (ui.allowedSsids.isNotEmpty()) {
                                        append(" · Wi‑Fi ")
                                        append(ui.allowedSsids.joinToString(" / "))
                                    }
                                },
                                style = LeziTypography.Meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                "同步前请在网络设置中绑定服务器与家庭 Wi‑Fi 名称。",
                                style = LeziTypography.Meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (savedSummaryBaseUrl.isNotBlank() &&
                            isPublicCleartextBaseUrl(savedSummaryBaseUrl)
                        ) {
                            Text(
                                PUBLIC_CLEARTEXT_WARNING,
                                style = LeziTypography.Meta,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            if (primary.showNetworkSecondaryEntry) {
                LeziSecondaryButton(
                    if (primary.compactJoined) "网络设置" else "家庭网络设置",
                    onClick = {
                        pendingAfterNetworkSave = null
                        showNetworkSettings = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (primary.showCreateJoin) {
                if (controls.showCreateFamily) {
                    LeziPrimaryButton(
                        "新建家庭",
                        onClick = { saveNetworkThen { vm.createFamily { message = it } } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (controls.showJoin) {
                    LeziSecondaryButton(
                        "输入邀请码",
                        onClick = { saveNetworkThen { showJoin = true } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LeziSecondaryButton(
                        "扫码加入",
                        onClick = {
                            saveNetworkThen {
                                if (CameraCapture.hasPermission(familyContext)) {
                                    launchInviteScan()
                                } else {
                                    scanCameraPermission.launch(CameraCapture.PERMISSION)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (primary.showInvite) {
                LeziPrimaryButton(
                    "生成邀请二维码",
                    onClick = {
                        vm.createInvite { result ->
                            result.fold(
                                onSuccess = { inviteView = it },
                                onFailure = {
                                    message = it.message ?: "生成共享码失败，请稍后重试"
                                },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (primary.showJoinedActions) {
                LeziSecondaryButton(
                    "立即同步",
                    onClick = { vm.pullNow { message = it } },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (primary.showLeave) {
                    LeziSecondaryButton(
                        "离开家庭",
                        onClick = { vm.leave { message = it } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (ui.role == FamilyRole.Owner) {
                    LeziSecondaryButton(
                        "删除家庭数据",
                        onClick = { confirmDeleteFamily = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (!primary.compactJoined) {
                Text(
                    "仅在已绑定的家庭 Wi‑Fi 且服务器可达时前台同步。",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(LeziSpacing.Xxl))
        }
    }

    if (showNetworkSettings) {
        ModalBottomSheet(
            onDismissRequest = {
                showNetworkSettings = false
                pendingAfterNetworkSave = null
            },
            sheetState = networkSheetState,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = LeziSpacing.Page)
                    .padding(bottom = LeziSpacing.Xxl)
                    .dismissKeyboardOnTap(),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                Text("家庭网络设置", style = LeziTypography.TitleSm)
                Text(
                    "服务器与 Wi‑Fi 名称仅保存在本机。保存后才会用于同步与加入家庭。",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = serverHost,
                    onValueChange = { serverHost = it },
                    label = { Text("家庭服务器主机（IP 或域名）") },
                    placeholder = { Text("192.168.50.4") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = serverPort,
                    onValueChange = { serverPort = it.filter { ch -> ch.isDigit() }.take(5) },
                    label = { Text("端口") },
                    placeholder = { Text("8765") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ssid1,
                    onValueChange = { ssid1 = it },
                    label = { Text("家庭 Wi‑Fi 名称 1（如 2.4G）") },
                    placeholder = { Text("当前连接的 Wi‑Fi 名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ssid2,
                    onValueChange = { ssid2 = it },
                    label = { Text("家庭 Wi‑Fi 名称 2（可选，如 5G）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LeziSecondaryButton(
                    "填入当前 Wi‑Fi 名称",
                    onClick = {
                        val cur = vm.currentWifiSsid()?.trim().orEmpty()
                        if (cur.isEmpty()) {
                            message = "无法读取 Wi‑Fi 名称，请开启定位权限后重试"
                        } else if (ssid1.isBlank()) {
                            ssid1 = cur
                        } else if (ssid2.isBlank() && ssid1 != cur) {
                            ssid2 = cur
                        } else if (ssid1 != cur && ssid2 != cur) {
                            message = "Wi‑Fi 名称已满 2 个，请先清空一格"
                        } else {
                            message = "当前 Wi‑Fi 已在列表中"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isPublicCleartextBaseUrl(previewBaseUrl)) {
                    Text(
                        PUBLIC_CLEARTEXT_WARNING,
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                val draftHost = serverHost.trim()
                val draftPort = serverPort.toIntOrNull()
                    ?: com.lezi.babylog.sync.DEFAULT_SERVER_PORT
                val draftSsids = listOf(ssid1, ssid2).map { it.trim() }.filter { it.isNotEmpty() }
                val networkDraftDirty =
                    draftHost != ui.serverHost.trim() ||
                        (ui.serverHost.isNotBlank() && draftPort != ui.serverPort) ||
                        (ui.serverHost.isBlank() && draftHost.isNotBlank()) ||
                        draftSsids != ui.allowedSsids
                if (persistedEmpty || networkDraftDirty) {
                    Text(
                        if (networkDraftDirty && !persistedEmpty) {
                            "当前填写尚未保存，请点「保存」后才会生效。"
                        } else {
                            "请确认后保存（未保存不会生效）。"
                        },
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                LeziPrimaryButton(
                    "保存家庭网络与服务器",
                    onClick = {
                        vm.saveHomeLanConfig(serverHost, serverPort, ssid1, ssid2) { result ->
                            message = result
                            if (result.contains("已保存")) {
                                val next = pendingAfterNetworkSave
                                pendingAfterNetworkSave = null
                                showNetworkSettings = false
                                next?.invoke()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showJoin && controls.showJoin) {
        AlertDialog(
            onDismissRequest = { showJoin = false },
            title = { Text("加入家庭") },
            text = {
                Column(Modifier.dismissKeyboardOnTap()) {
                    Text("加入后将全量共享育儿记录与日志图片；本机数据不会在加入成功前清除。")
                    Spacer(Modifier.height(LeziSpacing.Sm))
                    OutlinedTextField(
                        value = joinCode,
                        onValueChange = { joinCode = it },
                        label = { Text("邀请码或 QR JSON 载荷") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    if (CameraCapture.hasPermission(familyContext)) {
                                        launchInviteScan()
                                    } else {
                                        scanCameraPermission.launch(CameraCapture.PERMISSION)
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.QrCodeScanner,
                                    contentDescription = "扫码填入邀请",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(LeziSpacing.Sm))
                    OutlinedButton(
                        onClick = {
                            if (CameraCapture.hasPermission(familyContext)) {
                                launchInviteScan()
                            } else {
                                scanCameraPermission.launch(CameraCapture.PERMISSION)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("扫码填入邀请码")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showJoin = false
                        vm.join(joinCode) { message = it }
                    },
                ) { Text("加入") }
            },
            dismissButton = {
                TextButton(onClick = { showJoin = false }) { Text("取消") }
            },
        )
    }

    inviteView?.let { invite ->
        SecureWindowWhileVisible()
        var showPayload by remember(invite.code) { mutableStateOf(false) }
        val qrBitmap = remember(invite.payload) {
            BarcodeEncoder()
                .encodeBitmap(invite.payload, BarcodeFormat.QR_CODE, 640, 640)
                .asImageBitmap()
        }
        AlertDialog(
            onDismissRequest = { inviteView = null },
            title = { Text("家庭邀请二维码") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
                ) {
                    Text(
                        "二维码含服务器地址与已保存的家庭 Wi‑Fi 名称，对方扫码可自动填入。请勿在公共场合展示；截屏与录屏已暂时禁用。",
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Image(
                        bitmap = qrBitmap,
                        contentDescription = "家庭邀请二维码",
                        modifier = Modifier.size(240.dp),
                    )
                    Text(
                        "共享码 ${invite.code} · 有效至 " +
                            java.text.DateFormat.getDateTimeInstance().format(invite.expiresAt),
                        style = LeziTypography.BodyStrong,
                    )
                    TextButton(onClick = { showPayload = !showPayload }) {
                        Text(if (showPayload) "隐藏完整载荷" else "显示完整载荷（含服务器地址）")
                    }
                    if (showPayload) {
                        SelectionContainer {
                            Text(
                                invite.payload,
                                style = LeziTypography.Meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { inviteView = null }) { Text("完成") }
            },
        )
    }

    if (confirmDeleteFamily) {
        AlertDialog(
            onDismissRequest = { confirmDeleteFamily = false },
            title = { Text("删除家庭服务器上的全部数据？") },
            text = { Text("这会删除家庭记录、成员凭证和媒体文件，且不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteFamily = false
                    vm.deleteFamily { message = it }
                }) { Text("确认永久删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteFamily = false }) { Text("取消") }
            },
        )
    }

    message?.let { msg ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("提示") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { message = null }) { Text("知道了") }
            },
        )
    }

    confirmDelete?.let { baby ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除「${baby.nickname}」？") },
            text = {
                Text(
                    "删除后该档案不可恢复。记录仍会留在本机但不再出现在当前宝宝视图中。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = baby.id
                        confirmDelete = null
                        vm.deleteBaby(id) { message = it }
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消") }
            },
        )
    }

    mergeSource?.let { source ->
        AlertDialog(
            onDismissRequest = { mergeSource = null },
            title = { Text("把「${source.nickname}」合并到…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请选择保留的目标档案。来源档案的记录和日程会迁移，目标昵称与资料不变。")
                    ui.babies.filter { it.id != source.id }.forEach { target ->
                        OutlinedButton(
                            onClick = {
                                vm.previewMerge(source.id, target.id) { preview ->
                                    mergeSource = null
                                    if (preview == null) {
                                        message = "无法生成合并预览，请刷新后重试"
                                    } else {
                                        mergePreview = preview
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("保留「${target.nickname}」")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { mergeSource = null }) { Text("取消") }
            },
        )
    }

    mergePreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { mergePreview = null },
            title = { Text("确认合并宝宝档案？") },
            text = {
                Text(
                    "来源：${preview.sourceNickname}\n" +
                        "保留：${preview.targetNickname}\n" +
                        "将迁移 ${preview.recordCount} 条记录、" +
                        "${preview.calendarEventCount} 条日程。此操作不可撤销。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        mergePreview = null
                        vm.merge(preview) { message = it }
                    },
                ) {
                    Text("确认合并", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { mergePreview = null }) { Text("取消") }
            },
        )
    }

    editing?.let { baby ->
        BabyEditDialog(
            baby = baby,
            canEditAvatar = canEditFamilyAvatar(ui.role),
            onDismiss = { editing = null },
            onSave = {
                    nick,
                    sex,
                    birthday,
                    weightGrams,
                    avatarJpeg,
                    removeAvatar,
                    onFinished,
                ->
                vm.updateBaby(
                    baby.id,
                    nick,
                    sex,
                    birthday,
                    weightGrams,
                    avatarJpeg,
                    removeAvatar,
                ) { err ->
                    onFinished()
                    if (err == null) {
                        editing = null
                        message = "宝宝档案已保存"
                    } else {
                        message = err
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BabyEditDialog(
    baby: Baby,
    canEditAvatar: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        nickname: String,
        sex: String?,
        birthdayEpochDay: Long,
        birthWeightGrams: Int?,
        avatarJpeg: ByteArray?,
        removeAvatar: Boolean,
        onFinished: () -> Unit,
    ) -> Unit,
) {
    var nickname by remember(baby.id) { mutableStateOf(baby.nickname) }
    var sex by remember(baby.id) { mutableStateOf(baby.sex?.name) }
    var birthday by remember(baby.id) { mutableLongStateOf(baby.birthdayEpochDay) }
    var weightText by remember(baby.id) {
        mutableStateOf(baby.birthWeightGrams?.let { (it / 1000.0).toString() }.orEmpty())
    }
    var showDate by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var pickedAvatarUri by remember(baby.id) { mutableStateOf<Uri?>(null) }
    var croppedAvatar by remember(baby.id) { mutableStateOf<CroppedAvatar?>(null) }
    var removeAvatar by remember(baby.id) { mutableStateOf(false) }
    var saving by remember(baby.id) { mutableStateOf(false) }
    var avatarError by remember(baby.id) { mutableStateOf<String?>(null) }
    var pendingAvatarCameraUri by remember(baby.id) { mutableStateOf<Uri?>(null) }
    val avatarContext = LocalContext.current
    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            removeAvatar = false
            pickedAvatarUri = uri
        }
    }
    val avatarTakePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingAvatarCameraUri
        pendingAvatarCameraUri = null
        if (success && uri != null) {
            removeAvatar = false
            pickedAvatarUri = uri
        }
    }
    fun launchAvatarCamera() {
        avatarError = null
        if (!CameraCapture.hasCameraHardware(avatarContext)) {
            avatarError = "此设备没有可用相机"
            return
        }
        runCatching {
            val uri = CameraCapture.createOutputUri(avatarContext)
            pendingAvatarCameraUri = uri
            avatarTakePicture.launch(uri)
        }.onFailure {
            avatarError = "无法打开相机，请稍后重试"
        }
    }
    val avatarCameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launchAvatarCamera()
        } else {
            avatarError = "需要相机权限才能拍照"
        }
    }
    val previewBitmap = remember(croppedAvatar) {
        croppedAvatar?.bitmap?.asImageBitmap()
    }
    val hasAvatar = croppedAvatar != null || (!removeAvatar && baby.avatarPath != null)
    val dateLabel = remember(birthday) {
        LocalDate.ofEpochDay(birthday).format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
    }

    AlertDialog(
        onDismissRequest = {
            if (!saving) onDismiss()
        },
        title = { Text("编辑宝宝档案") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .dismissKeyboardOnTap(),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                Text("头像", style = LeziTypography.Label)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
                ) {
                    BabyAvatar(
                        nickname = nickname,
                        avatarPath = baby.avatarPath.takeUnless { removeAvatar },
                        previewBitmap = previewBitmap,
                        fallbackBackground = Color(baby.themeColorArgb),
                        fallbackStyle = LeziTypography.Title,
                        modifier = Modifier.size(76.dp),
                        borderWidth = 3.dp,
                        avatarContentDescription = "头像保存效果预览",
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (canEditAvatar) {
                            OutlinedButton(
                                enabled = !saving,
                                onClick = {
                                    avatarError = null
                                    avatarPicker.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                                        ),
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (hasAvatar) "相册更换" else "从相册选择")
                            }
                            OutlinedButton(
                                enabled = !saving,
                                onClick = {
                                    if (CameraCapture.hasPermission(avatarContext)) {
                                        launchAvatarCamera()
                                    } else {
                                        avatarCameraPermission.launch(CameraCapture.PERMISSION)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("拍照")
                            }
                        }
                        if (canEditAvatar && hasAvatar) {
                            TextButton(
                                enabled = !saving,
                                onClick = {
                                    croppedAvatar = null
                                    removeAvatar = true
                                },
                            ) {
                                Text("移除照片")
                            }
                        }
                        if (!canEditAvatar) {
                            Text(
                                "仅家庭管理员可更换头像",
                                style = LeziTypography.Meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                avatarError?.let {
                    Text(
                        it,
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "圆形区域就是保存后的头像效果",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = nickname,
                    enabled = !saving,
                    onValueChange = {
                        nickname = it
                        localError = null
                    },
                    label = { Text("昵称（不可重复）") },
                    singleLine = true,
                    isError = localError != null,
                    supportingText = localError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("性别", style = LeziTypography.Label)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("FEMALE" to "女宝", "MALE" to "男宝", "UNKNOWN" to "未设置").forEach { (key, label) ->
                        FilterChip(
                            selected = sex == key,
                            enabled = !saving,
                            onClick = { sex = key },
                            label = { Text(label) },
                        )
                    }
                }
                Text("出生日期", style = LeziTypography.Label)
                OutlinedButton(
                    enabled = !saving,
                    onClick = { showDate = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(dateLabel) }
                OutlinedTextField(
                    value = weightText,
                    enabled = !saving,
                    onValueChange = { weightText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("出生体重（kg，可选）") },
                    placeholder = { Text("例如 3.20") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("可填千克，保存时换算为克") },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    if (saving) return@TextButton
                    if (nickname.isBlank()) {
                        localError = "请填写昵称"
                        return@TextButton
                    }
                    val grams = weightText.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.let {
                        (it * 1000).toInt()
                    }
                    if (weightText.isNotBlank() && grams == null) {
                        localError = "出生体重格式不正确"
                        return@TextButton
                    }
                    saving = true
                    onSave(
                        nickname.trim(),
                        sex,
                        birthday,
                        grams,
                        croppedAvatar?.jpegBytes,
                        removeAvatar,
                    ) {
                        saving = false
                    }
                },
            ) { Text(if (saving) "保存中…" else "保存") }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") }
        },
    )

    if (showDate) {
        val initialUtc = LocalDate.ofEpochDay(birthday)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initialUtc)
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        dateState.selectedDateMillis?.let { ms ->
                            birthday = Instant.ofEpochMilli(ms)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                                .toEpochDay()
                        }
                        showDate = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDate = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = dateState)
        }
    }

    pickedAvatarUri?.let { sourceUri ->
        AvatarCropDialog(
            sourceUri = sourceUri,
            onDismiss = { pickedAvatarUri = null },
            onConfirm = { result ->
                croppedAvatar = result
                removeAvatar = false
                pickedAvatarUri = null
            },
        )
    }
}
