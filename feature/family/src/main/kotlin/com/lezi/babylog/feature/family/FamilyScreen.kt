package com.lezi.babylog.feature.family

import android.net.Uri
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.core.ui.BabyAvatar
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
import com.lezi.babylog.sync.InvitePayload
import com.lezi.babylog.sync.InvitePayloadCodec
import com.lezi.babylog.sync.SyncTrigger
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
) : ViewModel() {
    private val profileSaveMutex = Mutex()

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
                        FamilyInviteView(
                            code = it.code,
                            payload = InvitePayloadCodec.encode(
                                InvitePayload(ui.value.baseUrl, it.code),
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
                        "已加入家庭 ${it.familyId}"
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
    val message = error.message.orEmpty()
    val technicalNetworkDetail = listOf(
        "http://",
        "https://",
        "failed to connect",
        "connection refused",
        "java.",
        "exception",
    ).any { marker -> message.contains(marker, ignoreCase = true) } ||
        Regex("""/?\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?""").containsMatchIn(message)
    return when {
        error is SyncNotEnabledException -> "请先填写家庭服务器地址并加入家庭"
        technicalNetworkDetail -> "家庭同步服务暂未连接，请稍后重试"
        message.any { it.code in 0x4E00..0x9FFF } -> message
        else -> fallback
    }
}

internal fun syncStatusLabel(status: SyncStatus): String = when (status) {
    SyncStatus.Disabled -> "未启用"
    SyncStatus.BlockedOfflineHome -> "等待家庭 Wi‑Fi"
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
    showServerSetup = !isJoined,
    showJoin = !isJoined,
    showCreateFamily = !isJoined && role == FamilyRole.None,
    showInvite = isJoined && role == FamilyRole.Owner,
    showJoinedActions = isJoined,
    showLeave = isJoined && role == FamilyRole.Member,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyRoute(vm: FamilyViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    var showJoin by remember { mutableStateOf(false) }
    var joinCode by remember { mutableStateOf("") }
    var serverAddress by remember(ui.baseUrl) { mutableStateOf(ui.baseUrl) }
    var confirmDeleteFamily by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Baby?>(null) }
    var confirmDelete by remember { mutableStateOf<Baby?>(null) }
    var mergeSource by remember { mutableStateOf<Baby?>(null) }
    var mergePreview by remember { mutableStateOf<BabyMergePreview?>(null) }
    var inviteView by remember { mutableStateOf<FamilyInviteView?>(null) }
    val scanInvite = rememberLauncherForActivityResult(ScanContract()) { result ->
        val payload = result.contents?.trim().orEmpty()
        if (payload.isNotEmpty()) {
            joinCode = payload
            showJoin = true
        }
    }
    val current = ui.current
    val nickCounts = remember(ui.babies) {
        ui.babies.groupingBy { it.nickname.trim() }.eachCount()
    }
    val controls = remember(ui.enabled, ui.role) {
        familyControlVisibility(isJoined = ui.enabled, role = ui.role)
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

            // Current baby card (prototype account hero)
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
            if (controls.showServerSetup) {
                OutlinedTextField(
                    value = serverAddress,
                    onValueChange = { serverAddress = it },
                    label = { Text("家庭服务器地址（必填）") },
                    placeholder = { Text("http://192.168.50.4:8765") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LeziSecondaryButton(
                    "保存服务器地址",
                    onClick = { vm.saveServer(serverAddress) { message = it } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (controls.showCreateFamily || controls.showInvite) {
                LeziPrimaryButton(
                    if (controls.showCreateFamily) "新建家庭" else "生成邀请二维码",
                    onClick = {
                        if (controls.showCreateFamily) {
                            vm.createFamily { message = it }
                        } else {
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
            if (controls.showJoin) {
                LeziSecondaryButton(
                    "输入邀请码",
                    onClick = { showJoin = true },
                    modifier = Modifier.fillMaxWidth(),
                )
                LeziSecondaryButton(
                    "扫码加入",
                    onClick = {
                        scanInvite.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("扫描家庭邀请二维码")
                                .setBeepEnabled(false)
                                .setOrientationLocked(false),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (controls.showJoinedActions) {
                LeziSecondaryButton(
                    "立即同步",
                    onClick = { vm.pullNow { message = it } },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (controls.showLeave) {
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
            Text(
                "仅在家庭 Wi‑Fi 且服务器可达时前台同步；不会推送伴侣的新记录。\n" +
                    "状态：${syncStatusLabel(ui.status)}" +
                    (ui.lastSuccessAt?.let { " · 上次成功：${java.text.DateFormat.getDateTimeInstance().format(it)}" }
                        ?: ""),
                style = LeziTypography.Body,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(LeziSpacing.Xxl))
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
                    )
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
                    SelectionContainer {
                        Text(
                            invite.payload,
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) pickedAvatarUri = uri
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
                                    avatarPicker.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                                        ),
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (hasAvatar) "更换照片" else "选择照片")
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
