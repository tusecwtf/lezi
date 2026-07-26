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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.common.looksTechnicalDetail
import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.core.model.limitBabyNicknameInput
import com.lezi.babylog.core.model.birthWeightValidationError
import com.lezi.babylog.core.ui.BabyAvatar
import com.lezi.babylog.core.ui.CameraCapture
import com.lezi.babylog.designsystem.LeziCard
import com.lezi.babylog.designsystem.LeziDatePicker
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
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.FamilyRole
import com.lezi.babylog.sync.HomeLanServerConfig
import com.lezi.babylog.sync.HomeWifiPermission
import com.lezi.babylog.sync.HomeWifiSettingsTarget
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    val serverScheme: String = com.lezi.babylog.sync.DEFAULT_SERVER_SCHEME,
    val allowedSsids: List<String> = emptyList(),
    val role: FamilyRole = FamilyRole.None,
    val lastSuccessAt: Long? = null,
    val members: List<FamilyMember> = emptyList(),
    val membersLoaded: Boolean = false,
    val membersLoading: Boolean = false,
    val membersError: String? = null,
)

private data class FamilyMembersState(
    val familyId: String = "",
    val members: List<FamilyMember> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
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
    private val memberRefreshMutex = Mutex()
    private val familyMembers = MutableStateFlow(FamilyMembersState())

    fun currentWifiSsid(): String? = networkState.currentWifiSsid()

    private val baseUi = combine(
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
            serverScheme = session.serverScheme,
            allowedSsids = session.allowedSsids,
            role = session.role,
            lastSuccessAt = session.lastSuccessAt,
        )
    }

    val ui = combine(baseUi, familyMembers) { family, memberState ->
        if (family.enabled && memberState.familyId == family.familyId) {
            family.copy(
                members = memberState.members,
                membersLoaded = memberState.loaded,
                membersLoading = memberState.loading,
                membersError = memberState.error,
            )
        } else {
            family
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FamilyUi())

    fun refreshMembers(showErrors: Boolean = true) {
        viewModelScope.launch { refreshMembersNow(showErrors) }
    }

    private suspend fun refreshMembersNow(showErrors: Boolean) = memberRefreshMutex.withLock {
        val session = sync.session().first()
        if (!session.isJoined) {
            familyMembers.value = FamilyMembersState()
            return@withLock
        }
        val previous = familyMembers.value.takeIf { it.familyId == session.familyId }
        if (!showErrors && previous != null &&
            (previous.loading || previous.loaded || previous.error != null)
        ) {
            return@withLock
        }
        familyMembers.value = FamilyMembersState(
            familyId = session.familyId,
            members = previous?.members.orEmpty(),
            loaded = previous?.loaded ?: false,
            loading = true,
        )
        val result = sync.listFamilyMembers()
        if (sync.session().first().familyId != session.familyId) return@withLock
        familyMembers.value = result.fold(
            onSuccess = { members ->
                FamilyMembersState(
                    familyId = session.familyId,
                    members = members,
                    loaded = true,
                )
            },
            onFailure = { error ->
                FamilyMembersState(
                    familyId = session.familyId,
                    members = previous?.members.orEmpty(),
                    loaded = previous?.loaded ?: false,
                    error = if (showErrors) {
                        familySyncError(error, "暂时无法读取成员，请连接家庭 Wi‑Fi 后重试")
                    } else {
                        null
                    },
                )
            },
        )
    }

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
                            HomeLanServerConfig(
                                host = host,
                                port = port,
                                scheme = session.serverScheme,
                            ).baseUrl
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

    fun join(
        code: String,
        host: String,
        portText: String,
        ssid1: String,
        ssid2: String,
        fallbackScheme: String,
        onDone: (success: Boolean, message: String) -> Unit,
    ) {
        viewModelScope.launch {
            val config = runCatching {
                HomeLanServerConfig.fromUserInput(
                    rawHostOrUrl = host,
                    explicitPort = portText.toIntOrNull(),
                    allowedSsids = listOf(ssid1, ssid2),
                    fallbackScheme = fallbackScheme,
                )
            }.getOrElse {
                onDone(false, it.message ?: "服务器地址无效")
                return@launch
            }
            val result = sync.joinWithPayload(
                payload = code.trim(),
                preferredConfig = config,
                displayName = ui.value.displayName,
            )
            onDone(
                result.isSuccess,
                result.fold(
                    onSuccess = { "已加入家庭" },
                    onFailure = { familySyncError(it, fallback = "加入家庭失败，请稍后重试") },
                ),
            )
            if (result.isSuccess) refreshMembersNow(showErrors = true)
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
            if (result.isSuccess) familyMembers.value = FamilyMembersState()
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
            if (r.isSuccess) refreshMembersNow(showErrors = true)
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
        fallbackScheme: String,
        onMessage: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val config = runCatching {
                com.lezi.babylog.sync.HomeLanServerConfig.fromUserInput(
                    rawHostOrUrl = host,
                    explicitPort = portText.toIntOrNull(),
                    allowedSsids = listOf(ssid1, ssid2),
                    fallbackScheme = fallbackScheme,
                )
            }.getOrElse {
                onMessage(it.message ?: "服务器地址无效")
                return@launch
            }
            onMessage(sync.saveHomeLanConfig(config).fold({ "家庭网络与服务器已保存" }) {
                familySyncError(it, "保存失败")
            })
        }
    }

    fun createFamily(
        bootstrapSecret: String,
        onDone: (success: Boolean, message: String) -> Unit,
    ) {
        viewModelScope.launch {
            val result = sync.createFamily(ui.value.displayName, bootstrapSecret)
            onDone(
                result.isSuccess,
                result.fold(
                    { "家庭已创建" },
                    { familySyncError(it, "创建家庭失败") },
                ),
            )
            if (result.isSuccess) refreshMembersNow(showErrors = true)
        }
    }

    fun deleteFamily(onMessage: (String) -> Unit) {
        viewModelScope.launch {
            val result = sync.deleteFamily()
            onMessage(result.fold(
                { "家庭数据已删除" },
                { familySyncError(it, "删除家庭失败") },
            ))
            if (result.isSuccess) familyMembers.value = FamilyMembersState()
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
        "本机 + 家庭服务器"
    } else {
        "仅本机"
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

internal fun familyMemberDisplayName(member: FamilyMember): String =
    member.displayName
        ?.trim()
        ?.takeIf { it.isNotEmpty() && (member.isSelf || it != "我（本机）") }
        ?: when {
            member.isSelf -> "我（本机）"
            member.role == FamilyRole.Owner -> "家庭管理员"
            else -> "家庭成员"
        }

internal fun familyMemberSummary(
    visibleCount: Int,
    role: FamilyRole,
    loaded: Boolean,
): String = if (loaded) {
    "$visibleCount 位 · ${familyRoleLabel(role)}"
} else {
    "待刷新 · ${familyRoleLabel(role)}"
}

internal fun familyMembersForDisplay(
    members: List<FamilyMember>,
    localDisplayName: String,
    localRole: FamilyRole,
): List<FamilyMember> {
    val bounded = members.take(50)
    if (bounded.any(FamilyMember::isSelf)) return bounded
    return listOf(
        FamilyMember(
            displayName = localDisplayName,
            role = localRole.takeUnless { it == FamilyRole.None } ?: FamilyRole.Member,
            isSelf = true,
        ),
    ) + bounded
}

@Composable
private fun FamilyMemberRow(member: FamilyMember) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(vertical = LeziSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = if (member.isSelf) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (member.isSelf) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    when {
                        member.isSelf -> "我"
                        member.role == FamilyRole.Owner -> "管"
                        else -> "员"
                    },
                    style = LeziTypography.Label,
                )
            }
        }
        Spacer(Modifier.size(LeziSpacing.Sm))
        Column(Modifier.weight(1f)) {
            Text(
                familyMemberDisplayName(member),
                style = LeziTypography.BodyStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(familyRoleLabel(member.role))
                    if (member.isSelf) append(" · 本机")
                },
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun compactSyncStatusLabel(status: SyncStatus, isJoined: Boolean): String = when {
    !isJoined -> "等待完成前两步"
    status == SyncStatus.BlockedOfflineHome -> "等待家庭 Wi-Fi"
    status == SyncStatus.Idle -> "已就绪"
    status == SyncStatus.Syncing -> "同步中"
    status == SyncStatus.Error -> "需要重试"
    else -> "等待同步"
}

@Composable
private fun FamilyGuideRow(
    step: String,
    title: String,
    detail: String,
    complete: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
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
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (complete) "✓" else step,
                    style = LeziTypography.Label,
                    color = if (complete) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Spacer(Modifier.size(LeziSpacing.Sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = LeziTypography.BodyStrong)
            Text(
                detail,
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FamilyScopeRow(
    marker: String,
    title: String,
    detail: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    marker,
                    style = LeziTypography.Eyebrow,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.size(LeziSpacing.Sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = LeziTypography.BodyStrong)
            Text(
                detail,
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FamilyRoute(
    onAddBaby: () -> Unit = {},
    vm: FamilyViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    var showJoin by remember { mutableStateOf(false) }
    var joiningFamily by remember { mutableStateOf(false) }
    var joinCode by remember { mutableStateOf("") }
    val familyContext = LocalContext.current
    val novice = remember {
        com.lezi.babylog.sync.HomeLanServerConfig.noviceUiDefaults(
            if (HomeWifiPermission.hasRequiredPermissions(familyContext)) {
                vm.currentWifiSsid()
            } else {
                null
            },
        )
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
    var serverScheme by remember(ui.serverScheme, ui.baseUrl) {
        mutableStateOf(
            ui.baseUrl.takeIf(String::isNotBlank)
                ?.let(com.lezi.babylog.sync.HomeLanServerConfig::fromBaseUrl)
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
    val previewBaseUrl = remember(serverHost, serverPort, serverScheme) {
        runCatching {
            com.lezi.babylog.sync.HomeLanServerConfig.fromUserInput(
                rawHostOrUrl = serverHost,
                explicitPort = serverPort.toIntOrNull(),
                allowedSsids = emptyList(),
                fallbackScheme = serverScheme,
            ).baseUrl
        }.getOrDefault("")
    }
    var deleteFamilyStep by rememberSaveable { mutableIntStateOf(0) }
    var confirmLeaveFamily by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Baby?>(null) }
    var confirmDelete by remember { mutableStateOf<Baby?>(null) }
    var mergeSource by remember { mutableStateOf<Baby?>(null) }
    var mergePreview by remember { mutableStateOf<BabyMergePreview?>(null) }
    var inviteView by remember { mutableStateOf<FamilyInviteView?>(null) }
    var showNetworkSettings by remember { mutableStateOf(false) }
    var showCreateFamily by remember { mutableStateOf(false) }
    var bootstrapSecret by remember { mutableStateOf("") }
    var bootstrapSecretFeedback by remember { mutableStateOf<String?>(null) }
    var creatingFamily by remember { mutableStateOf(false) }
    var pendingAfterNetworkSave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val networkSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pendingHomeWifiAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showHomeWifiAccessGuide by remember { mutableStateOf(false) }
    val homeWifiPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val action = pendingHomeWifiAction
        pendingHomeWifiAction = null
        if (HomeWifiPermission.isSsidAccessReady(familyContext)) {
            action?.invoke()
        } else {
            showHomeWifiAccessGuide = true
        }
    }
    fun withHomeWifiAccess(action: () -> Unit) {
        val missing = HomeWifiPermission.missingPermissions(familyContext)
        if (missing.isNotEmpty()) {
            pendingHomeWifiAction = action
            homeWifiPermission.launch(missing.toTypedArray())
        } else if (HomeWifiPermission.isSsidAccessReady(familyContext)) {
            action()
        } else {
            showHomeWifiAccessGuide = true
        }
    }
    LaunchedEffect(ui.enabled, ui.familyId) {
        if (ui.enabled && HomeWifiPermission.isSsidAccessReady(familyContext)) {
            vm.refreshMembers(showErrors = false)
        }
    }
    fun applyScannedInvite(raw: String) {
        val payload = raw.trim()
        if (payload.isEmpty()) return
        joinCode = payload
        runCatching { InvitePayloadCodec.decode(payload) }.getOrNull()?.let { decoded ->
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
    val savedSummaryBaseUrl = remember(ui.serverHost, ui.serverPort, ui.serverScheme, ui.baseUrl) {
        when {
            ui.serverHost.isNotBlank() ->
                HomeLanServerConfig(
                    ui.serverHost,
                    ui.serverPort,
                    ui.allowedSsids,
                    ui.serverScheme,
                ).baseUrl
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
                eyebrow = "宝宝与家庭",
                title = "账户",
            )

            LeziCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text("当前宝宝", style = LeziTypography.Meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                current?.nickname ?: "—",
                                style = LeziTypography.TitleSm,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
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
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (current != null) {
                    Spacer(Modifier.height(LeziSpacing.Sm))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LeziSecondaryButton("编辑", onClick = { editing = current })
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
                FamilyScopeRow(
                    marker = "存储",
                    title = familyStorageCopy(ui.enabled),
                    detail = if (ui.enabled) "家庭同步已开启" else "家庭同步未开启",
                )
                FamilyScopeRow(
                    marker = "设备",
                    title = "本机标识",
                    detail = ui.deviceId.take(12).uppercase().ifBlank { "生成中" },
                )
            }

            SectionHeading(
                title = "宝宝档案",
                trailing = {
                    TextButton(onClick = onAddBaby) { Text("添加宝宝") }
                },
            )
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    b.nickname + if (selected) "（当前）" else "",
                                    style = LeziTypography.BodyStrong,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
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
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(LeziSpacing.Sm))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
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
            SectionHeading(title = "家人一起记")
            fun saveNetworkThen(onReady: () -> Unit) {
                if (!HomeWifiPermission.isSsidAccessReady(familyContext)) {
                    withHomeWifiAccess { saveNetworkThen(onReady) }
                    return
                }
                if (ssid1.isBlank()) {
                    vm.currentWifiSsid()?.trim()?.takeIf(String::isNotEmpty)?.let { ssid1 = it }
                }
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
                vm.saveHomeLanConfig(serverHost, serverPort, ssid1, ssid2, serverScheme) { result ->
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
                        vm.saveHomeLanConfig(
                            serverHost,
                            serverPort,
                            ssid1,
                            ssid2,
                            serverScheme,
                        ) { message = it }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            LeziCard(modifier = Modifier.fillMaxWidth()) {
                Text("家庭同步", style = LeziTypography.BodyStrong)
                Spacer(Modifier.height(LeziSpacing.Sm))
                FamilyGuideRow(
                    step = "1",
                    title = "家庭网络",
                    detail = if (networkConfigured) {
                        buildString {
                            append(ui.allowedSsids.joinToString(" / "))
                            if (savedSummaryBaseUrl.isNotBlank()) append(" · $savedSummaryBaseUrl")
                        }
                    } else {
                        "待设置服务器与 Wi-Fi"
                    },
                    complete = networkConfigured,
                )
                FamilyGuideRow(
                    step = "2",
                    title = "家庭身份",
                    detail = if (ui.enabled) {
                        "已加入 · ${familyRoleLabel(ui.role)}"
                    } else {
                        "待新建或加入"
                    },
                    complete = ui.enabled,
                )
                FamilyGuideRow(
                    step = "3",
                    title = "同步状态",
                    detail = compactSyncStatusLabel(ui.status, ui.enabled) +
                        (ui.lastSuccessAt?.let {
                            " · ${java.text.DateFormat.getDateTimeInstance().format(it)}"
                        } ?: ""),
                    complete = ui.enabled && ui.status == SyncStatus.Idle,
                )
                if (savedSummaryBaseUrl.isNotBlank() && isPublicCleartextBaseUrl(savedSummaryBaseUrl)) {
                    Spacer(Modifier.height(LeziSpacing.Xs))
                    Text(
                        PUBLIC_CLEARTEXT_WARNING,
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (ui.enabled) {
                val visibleMembers = familyMembersForDisplay(
                    members = ui.members,
                    localDisplayName = ui.displayName,
                    localRole = ui.role,
                )
                LeziCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("共享中的成员", style = LeziTypography.BodyStrong)
                            Text(
                                familyMemberSummary(
                                    visibleCount = visibleMembers.size,
                                    role = ui.role,
                                    loaded = ui.membersLoaded,
                                ),
                                style = LeziTypography.Meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = {
                                withHomeWifiAccess { vm.refreshMembers(showErrors = true) }
                            },
                            enabled = !ui.membersLoading,
                        ) {
                            Text(if (ui.membersLoading) "刷新中…" else "刷新")
                        }
                    }
                    Spacer(Modifier.height(LeziSpacing.Xs))
                    visibleMembers.forEach { member ->
                        FamilyMemberRow(member)
                    }
                    ui.membersError?.let { error ->
                        Text(
                            error,
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = LeziSpacing.Xs),
                        )
                    } ?: if (!ui.membersLoaded && !ui.membersLoading) {
                        Text(
                            "连接家庭 Wi-Fi 后刷新完整列表",
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = LeziSpacing.Xs),
                        )
                    } else {
                        Unit
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
                        onClick = { saveNetworkThen { showCreateFamily = true } },
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
                            if (CameraCapture.hasPermission(familyContext)) {
                                launchInviteScan()
                            } else {
                                scanCameraPermission.launch(CameraCapture.PERMISSION)
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
                        withHomeWifiAccess {
                            vm.createInvite { result ->
                                result.fold(
                                    onSuccess = { inviteView = it },
                                    onFailure = {
                                        message = it.message ?: "生成共享码失败，请稍后重试"
                                    },
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (primary.showJoinedActions) {
                LeziSecondaryButton(
                    "立即同步",
                    onClick = {
                        withHomeWifiAccess { vm.pullNow { message = it } }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (primary.showLeave) {
                    LeziSecondaryButton(
                        "离开家庭",
                        onClick = { confirmLeaveFamily = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (ui.role == FamilyRole.Owner) {
                    LeziSecondaryButton(
                        "删除家庭数据",
                        onClick = { deleteFamilyStep = 1 },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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
                    .imePadding()
                    .padding(horizontal = LeziSpacing.Page)
                    .padding(bottom = LeziSpacing.Xxl)
                    .dismissKeyboardOnTap(),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                Text("家庭网络设置", style = LeziTypography.TitleSm)
                FamilyGuideRow(
                    step = "1",
                    title = "填写并保存",
                    detail = "服务器和家庭 Wi-Fi 仅存本机",
                    complete = networkConfigured,
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
                        withHomeWifiAccess {
                            val cur = vm.currentWifiSsid()?.trim().orEmpty()
                            if (cur.isEmpty()) {
                                showHomeWifiAccessGuide = true
                            } else if (ssid1.isBlank()) {
                                ssid1 = cur
                            } else if (ssid2.isBlank() && ssid1 != cur) {
                                ssid2 = cur
                            } else if (ssid1 != cur && ssid2 != cur) {
                                message = "Wi‑Fi 名称已满 2 个，请先清空一格"
                            } else {
                                message = "当前 Wi‑Fi 已在列表中"
                            }
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
                            "有未保存的更改"
                        } else {
                            "保存后即可新建或加入家庭"
                        },
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                LeziPrimaryButton(
                    "保存家庭网络与服务器",
                    onClick = {
                        vm.saveHomeLanConfig(
                            serverHost,
                            serverPort,
                            ssid1,
                            ssid2,
                            serverScheme,
                        ) { result ->
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
            onDismissRequest = { if (!joiningFamily) showJoin = false },
            modifier = Modifier.imePadding(),
            properties = DialogProperties(decorFitsSystemWindows = false),
            title = { Text("加入家庭") },
            text = {
                Column(Modifier.dismissKeyboardOnTap()) {
                    FamilyScopeRow("共享", "家庭数据", "宝宝档案、照护记录、日志图片")
                    Spacer(Modifier.height(LeziSpacing.Xs))
                    FamilyScopeRow("本机", "个人偏好", "主题、提醒、桌面小组件")
                    Spacer(Modifier.height(LeziSpacing.Sm))
                    OutlinedTextField(
                        value = joinCode,
                        onValueChange = { joinCode = it },
                        label = { Text("邀请码") },
                        placeholder = { Text("输入共享码，或使用下方扫码") },
                        singleLine = true,
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
                        withHomeWifiAccess {
                            joiningFamily = true
                            vm.join(
                                code = joinCode,
                                host = serverHost,
                                portText = serverPort,
                                ssid1 = ssid1,
                                ssid2 = ssid2,
                                fallbackScheme = serverScheme,
                            ) { success, resultMessage ->
                                joiningFamily = false
                                message = resultMessage
                                if (success) showJoin = false
                            }
                        }
                    },
                    enabled = !joiningFamily,
                ) { Text(if (joiningFamily) "正在加入…" else "加入") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showJoin = false },
                    enabled = !joiningFamily,
                ) { Text("取消") }
            },
        )
    }

    if (showCreateFamily && controls.showCreateFamily) {
        fun dismissCreateFamily() {
            showCreateFamily = false
            bootstrapSecret = ""
            bootstrapSecretFeedback = null
            creatingFamily = false
        }
        AlertDialog(
            onDismissRequest = { if (!creatingFamily) dismissCreateFamily() },
            modifier = Modifier.imePadding(),
            properties = DialogProperties(decorFitsSystemWindows = false),
            title = { Text("新建家庭") },
            text = {
                Column(
                    modifier = Modifier.dismissKeyboardOnTap(),
                    verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
                ) {
                    FamilyScopeRow("一次", "服务器初始化", "口令只用于本次建家")
                    OutlinedTextField(
                        value = bootstrapSecret,
                        onValueChange = {
                            bootstrapSecret = it
                            bootstrapSecretFeedback = null
                        },
                        label = { Text("服务器初始化口令") },
                        supportingText = {
                            Text(
                                bootstrapSecretFeedback
                                    ?: "与 NAS 部署时设置的口令一致",
                            )
                        },
                        isError = bootstrapSecretFeedback != null,
                        enabled = !creatingFamily,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val secret = bootstrapSecret.trim()
                        if (secret.isEmpty()) {
                            bootstrapSecretFeedback = "请填写服务器初始化口令"
                        } else {
                            withHomeWifiAccess {
                                creatingFamily = true
                                bootstrapSecretFeedback = null
                                vm.createFamily(secret) { success, outcome ->
                                    creatingFamily = false
                                    if (success) {
                                        dismissCreateFamily()
                                        message = outcome
                                    } else {
                                        bootstrapSecretFeedback = outcome
                                    }
                                }
                            }
                        }
                    },
                    enabled = !creatingFamily,
                ) { Text(if (creatingFamily) "创建中…" else "创建") }
            },
            dismissButton = {
                TextButton(
                    onClick = ::dismissCreateFamily,
                    enabled = !creatingFamily,
                ) { Text("取消") }
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
                    modifier = Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
                ) {
                    FamilyScopeRow("扫码", "自动填入", "服务器与家庭 Wi-Fi")
                    FamilyScopeRow("安全", "隐私保护", "截屏与录屏已禁用")
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

    if (deleteFamilyStep == 1) {
        AlertDialog(
            onDismissRequest = { deleteFamilyStep = 0 },
            title = { Text("删除家庭服务器上的全部数据？") },
            text = {
                Text("这会影响全部家庭成员，并删除 NAS 上的记录、成员凭证和媒体文件。")
            },
            confirmButton = {
                TextButton(onClick = { deleteFamilyStep = 2 }) {
                    Text("继续", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteFamilyStep = 0 }) { Text("取消") }
            },
        )
    }

    if (confirmLeaveFamily) {
        AlertDialog(
            onDismissRequest = { confirmLeaveFamily = false },
            title = { Text("离开当前家庭？") },
            text = {
                Text("本机将停止共享并清除家庭服务器、Wi-Fi 与登录会话；NAS 上的家庭记录会保留。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeaveFamily = false
                    withHomeWifiAccess { vm.leave { message = it } }
                }) { Text("确认离开", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeaveFamily = false }) { Text("取消") }
            },
        )
    }

    if (deleteFamilyStep == 2) {
        AlertDialog(
            onDismissRequest = { deleteFamilyStep = 0 },
            title = { Text("最后确认：永久删除") },
            text = {
                Text("删除后无法恢复。家庭记录、成员凭证与所有日志图片都会从 NAS 清除。")
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteFamilyStep = 0
                    withHomeWifiAccess { vm.deleteFamily { message = it } }
                }) { Text("永久删除家庭数据", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteFamilyStep = 0 }) { Text("取消") }
            },
        )
    }

    if (showHomeWifiAccessGuide) {
        val settingsTarget = HomeWifiPermission.settingsTarget(familyContext)
        AlertDialog(
            onDismissRequest = { showHomeWifiAccessGuide = false },
            title = { Text("允许识别家庭 Wi‑Fi") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
                    FamilyScopeRow("权限", "位置权限", "仅用于读取当前 Wi-Fi 名称")
                    FamilyScopeRow("系统", "定位服务", "需保持开启，位置不会上传")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showHomeWifiAccessGuide = false
                        familyContext.startActivity(HomeWifiPermission.settingsIntent(familyContext))
                    },
                ) {
                    Text(
                        when (settingsTarget) {
                            HomeWifiSettingsTarget.AppPermission -> "打开权限设置"
                            HomeWifiSettingsTarget.LocationServices -> "开启定位服务"
                            HomeWifiSettingsTarget.Wifi -> "打开 Wi-Fi 设置"
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showHomeWifiAccessGuide = false }) { Text("稍后") }
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
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
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
                            Text(
                                "保留「${target.nickname}」",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
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
                Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xs)) {
                    FamilyScopeRow("来源", "移出档案", preview.sourceNickname)
                    FamilyScopeRow("保留", "目标档案", preview.targetNickname)
                    FamilyScopeRow(
                        "迁移",
                        "关联数据",
                        "${preview.recordCount} 条记录 · ${preview.calendarEventCount} 条日程",
                    )
                    Text("操作不可撤销", color = MaterialTheme.colorScheme.error)
                }
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
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
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
                        nickname = limitBabyNicknameInput(it)
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
                    birthWeightValidationError(grams)?.let {
                        localError = it
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
            LeziDatePicker(state = dateState)
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
