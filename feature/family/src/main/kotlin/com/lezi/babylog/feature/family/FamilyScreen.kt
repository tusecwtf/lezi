package com.lezi.babylog.feature.family

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import com.lezi.babylog.core.database.FamilyDao
import com.lezi.babylog.core.database.LocalUserDao
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
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.DuplicateBabyNicknameException
import com.lezi.babylog.domain.UpdateBabyInput
import com.lezi.babylog.domain.babyAgeLabel
import com.lezi.babylog.sync.SyncNotEnabledException
import com.lezi.babylog.sync.SyncPort
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
)

@HiltViewModel
class FamilyViewModel @Inject constructor(
    private val sync: SyncPort,
    private val localUserDao: LocalUserDao,
    private val familyDao: FamilyDao,
    private val careLog: CareLog,
    private val avatarFileStore: BabyAvatarFileStore,
) : ViewModel() {
    private val profileSaveMutex = Mutex()

    val ui = combine(
        sync.status(),
        careLog.observeHasBaby(),
        careLog.observeCurrentBaby(),
        careLog.observeBabies(),
    ) { st, hasBaby, current, babies ->
        val user = localUserDao.get()
        val fam = familyDao.listAll().firstOrNull()
        FamilyUi(
            deviceId = user?.deviceId ?: "—",
            displayName = user?.displayName ?: "我（本机）",
            status = st,
            enabled = sync.isEnabled(),
            hasLocalBaby = hasBaby,
            familyId = fam?.id?.toString() ?: "1",
            current = current,
            babies = babies,
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
                        avatarJpeg != null -> {
                            avatarFileStore.write(existing.clientUuid, avatarJpeg).also {
                                writtenAvatarPath = it
                            }
                        }
                        removeAvatar -> null
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
                        error.message ?: "保存失败"
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

    fun dedupeNow(onDone: (String) -> Unit) {
        viewModelScope.launch {
            val before = careLog.listBabies().size
            careLog.dedupeBabiesByNickname()
            val after = careLog.listBabies().size
            onDone(
                if (before > after) "已合并 ${before - after} 个重复档案"
                else "没有发现重复昵称",
            )
        }
    }

    fun createInvite(onMessage: (String) -> Unit) {
        viewModelScope.launch {
            val familyId = ui.value.familyId
            val result = sync.createInvite(familyId)
            onMessage(
                result.fold(
                    onSuccess = { "共享码 ${it.code}（24 小时内有效）" },
                    onFailure = {
                        if (it is SyncNotEnabledException) "家庭同步将在后续版本开放" else (it.message ?: "失败")
                    },
                ),
            )
        }
    }

    fun join(code: String, clearFirst: Boolean, onMessage: (String) -> Unit) {
        viewModelScope.launch {
            if (ui.value.hasLocalBaby && !clearFirst) {
                onMessage("本机已有宝宝数据。请先 TXT 导出，或选择清空本机后加入。")
                return@launch
            }
            if (clearFirst) {
                try {
                    withContext(NonCancellable) {
                        careLog.clearAllLocalData()
                        avatarFileStore.deleteAll()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    onMessage(error.message ?: "清空本机数据失败")
                    return@launch
                }
            }
            val result = sync.joinWithCode(code.trim())
            onMessage(
                result.fold(
                    onSuccess = {
                        sync.pull(it.id.toString())
                        "已加入家庭 ${it.id}"
                    },
                    onFailure = {
                        if (it is SyncNotEnabledException) "家庭同步将在后续版本开放" else (it.message ?: "加入失败")
                    },
                ),
            )
        }
    }

    fun leave(onMessage: (String) -> Unit) {
        viewModelScope.launch {
            val id = ui.value.familyId
            val result = sync.leave(id)
            onMessage(result.fold({ "已离开家庭" }, { it.message ?: "失败" }))
        }
    }

    fun pullNow(onMessage: (String) -> Unit) {
        viewModelScope.launch {
            val id = ui.value.familyId
            sync.push(id)
            val r = sync.pull(id)
            onMessage(r.fold({ "已同步" }, { it.message ?: "同步失败" }))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyRoute(vm: FamilyViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    var showJoin by remember { mutableStateOf(false) }
    var joinCode by remember { mutableStateOf("") }
    var confirmClearJoin by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Baby?>(null) }
    var confirmDelete by remember { mutableStateOf<Baby?>(null) }
    val current = ui.current
    val nickCounts = remember(ui.babies) {
        ui.babies.groupingBy { it.nickname.trim() }.eachCount()
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
                eyebrow = "一起照顾，一起记",
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
                Text("数据仅保存在本机 · 无需登录", style = LeziTypography.BodyStrong)
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
                            LeziSecondaryButton("删除", onClick = { confirmDelete = b })
                        }
                    }
                }
            }
            if (nickCounts.any { it.value > 1 }) {
                LeziPrimaryButton(
                    "合并重复昵称档案",
                    onClick = { vm.dedupeNow { message = it } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                "昵称不可重复。可设置出生日期与出生体重；多余档案可删除（至少保留一位）。",
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionHeading(title = "家人一起记")
            LeziPrimaryButton(
                "新建家庭 / 生成共享码",
                onClick = { vm.createInvite { message = it } },
                modifier = Modifier.fillMaxWidth(),
            )
            LeziSecondaryButton(
                "输入邀请码",
                onClick = { showJoin = true },
                modifier = Modifier.fillMaxWidth(),
            )
            LeziSecondaryButton(
                "扫码加入",
                onClick = { message = if (ui.enabled) "请使用「输入邀请码」" else "家庭同步将在后续版本开放" },
                modifier = Modifier.fillMaxWidth(),
            )
            if (ui.enabled) {
                LeziSecondaryButton(
                    "立即同步",
                    onClick = { vm.pullNow { message = it } },
                    modifier = Modifier.fillMaxWidth(),
                )
                LeziSecondaryButton(
                    "离开家庭",
                    onClick = { vm.leave { message = it } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                if (ui.enabled) "加入前将全量共享家庭记录。设置与深色模式不同步。"
                else "家庭同步将在后续版本开放",
                style = LeziTypography.Body,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(LeziSpacing.Xxl))
        }
    }

    if (showJoin) {
        AlertDialog(
            onDismissRequest = { showJoin = false },
            title = { Text("加入家庭") },
            text = {
                Column {
                    Text("加入后将全量共享该家庭数据。若本机已有宝宝，默认拒绝；可先导出 TXT。")
                    Spacer(Modifier.height(LeziSpacing.Sm))
                    OutlinedTextField(
                        value = joinCode,
                        onValueChange = { joinCode = it },
                        label = { Text("邀请码") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showJoin = false
                        if (ui.hasLocalBaby) confirmClearJoin = true
                        else vm.join(joinCode, clearFirst = false) { message = it }
                    },
                ) { Text("加入") }
            },
            dismissButton = {
                TextButton(onClick = { showJoin = false }) { Text("取消") }
            },
        )
    }

    if (confirmClearJoin) {
        AlertDialog(
            onDismissRequest = { confirmClearJoin = false },
            title = { Text("本机已有数据") },
            text = { Text("默认不能直接加入。可先 TXT 导出，或清空本机后再加入。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearJoin = false
                        vm.join(joinCode, clearFirst = true) { message = it }
                    },
                ) { Text("清空本机后加入") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearJoin = false }) { Text("取消") }
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
                    if ((nickCounts[baby.nickname.trim()] ?: 0) > 1) {
                        "检测到重复昵称。删除后记录不会自动迁移；若要合并记录请用「合并重复昵称档案」。"
                    } else {
                        "删除后该档案不可恢复。记录仍会留在本机但不再出现在当前宝宝视图中。"
                    },
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

    editing?.let { baby ->
        BabyEditDialog(
            baby = baby,
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
                    .verticalScroll(rememberScrollState()),
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
                        if (hasAvatar) {
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
