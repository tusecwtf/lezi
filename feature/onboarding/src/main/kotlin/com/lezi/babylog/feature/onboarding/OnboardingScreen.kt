package com.lezi.babylog.feature.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.lezi.babylog.domain.JoinFamilyRequest
import com.lezi.babylog.domain.JoinFamilyResult
import com.lezi.babylog.domain.JoinFamilyUseCase
import com.lezi.babylog.sync.HomeLanServerConfig
import com.lezi.babylog.sync.HomeWifiPermission
import com.lezi.babylog.sync.JoinFamilyDraft
import com.lezi.babylog.sync.HomeWifiSettingsTarget
import com.lezi.babylog.sync.NetworkState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.launch

private val ThemePalette = listOf(
    0xFF007BAE.toInt(),
    0xFFAA442B.toInt(),
    0xFF2F8F6B.toInt(),
    0xFF7A5CFF.toInt(),
    0xFFE09F3E.toInt(),
    0xFFD4578C.toInt(),
    0xFF4C6A92.toInt(),
    0xFF5B8C5A.toInt(),
)
private val ThemePaletteLabels = listOf(
    "湖蓝",
    "砖红",
    "青绿",
    "紫罗兰",
    "琥珀",
    "玫红",
    "灰蓝",
    "草绿",
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val careLog: CareLog,
    private val joinFamily: JoinFamilyUseCase,
    private val networkState: NetworkState,
) : ViewModel() {
    fun currentWifiSsid(): String? = networkState.currentWifiSsid()

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

    /**
     * Join the existing family before publishing any Baby locally. The join response owns the
     * family's Baby snapshot; creating a placeholder first would both exit onboarding on failure
     * and later upload an unwanted extra Baby.
     */
    fun joinFamily(
        draft: JoinFamilyDraft,
        displayName: String,
        onDone: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            when (
                val result = joinFamily.execute(
                    JoinFamilyRequest(draft = draft, displayName = displayName),
                )
            ) {
                is JoinFamilyResult.Joined -> onDone(null)
                is JoinFamilyResult.Failed -> onDone(result.message)
            }
        }
    }
}

@Composable
private fun HomeWifiGuideRow(
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
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(marker, style = LeziTypography.Eyebrow)
            }
        }
        Spacer(Modifier.size(LeziSpacing.Sm))
        Column(Modifier.weight(1f)) {
            Text(title, style = LeziTypography.BodyStrong)
            Text(
                detail,
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    var name by remember { mutableStateOf("年年") }
    var sex by remember { mutableStateOf<String?>(null) }
    var birthday by remember { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var weightText by remember { mutableStateOf("") }
    var themeIdx by remember { mutableIntStateOf(0) }
    var showDate by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }
    var joinDisplayName by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    // Prefill unsaved defaults, including the current Wi-Fi name when available.
    val novice = remember {
        HomeLanServerConfig.noviceUiDefaults(
            if (HomeWifiPermission.hasRequiredPermissions(context)) vm.currentWifiSsid() else null,
        )
    }
    var joinDraft by remember { mutableStateOf(JoinFamilyDraft.fromConfig(novice)) }
    var pendingHomeWifiAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showHomeWifiAccessGuide by remember { mutableStateOf(false) }
    val homeWifiPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val action = pendingHomeWifiAction
        pendingHomeWifiAction = null
        if (HomeWifiPermission.isSsidAccessReady(context)) {
            action?.invoke()
        } else {
            showHomeWifiAccessGuide = true
        }
    }
    fun withHomeWifiAccess(action: () -> Unit) {
        val missing = HomeWifiPermission.missingPermissions(context)
        if (missing.isNotEmpty()) {
            pendingHomeWifiAction = action
            homeWifiPermission.launch(missing.toTypedArray())
        } else if (HomeWifiPermission.isSsidAccessReady(context)) {
            action()
        } else {
            showHomeWifiAccessGuide = true
        }
    }
    fun fillCurrentWifiIfBlank() {
        val currentSsid = vm.currentWifiSsid()?.trim().orEmpty()
        if (currentSsid.isNotEmpty() && joinDraft.ssid1.isBlank()) {
            joinDraft = joinDraft.copy(ssid1 = currentSsid)
        }
    }
    fun applyScannedInvite(raw: String) {
        val payload = raw.trim()
        if (payload.isEmpty()) return
        formError = null
        // Prefill host, port, and optional SSIDs; persist them only after join succeeds.
        joinDraft = runCatching { joinDraft.prefillInvitation(payload) }
            .getOrElse { joinDraft.copy(invitation = payload) }
        showJoin = true
    }
    val scanInvite = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(::applyScannedInvite)
    }
    fun launchInviteScan() {
        if (!CameraCapture.hasCameraHardware(context)) {
            formError = "此设备没有可用相机，请改用手动输入邀请码"
            showJoin = true
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
            formError = "需要相机权限才能扫码，请在系统设置中开启，或改用输入邀请码"
            showJoin = true
        }
    }
    fun requestOrLaunchInviteScan() {
        formError = null
        if (CameraCapture.hasPermission(context)) {
            launchInviteScan()
        } else {
            scanCameraPermission.launch(CameraCapture.PERMISSION)
        }
    }
    // Form defaults do not trigger runtime permission prompts; only explicit family actions do.
    LaunchedEffect(showJoin) {
        if (!showJoin) return@LaunchedEffect
        if (joinDraft.host.isBlank() || joinDraft.portText.isBlank()) {
            joinDraft = JoinFamilyDraft.fromConfig(novice, joinDraft.invitation).copy(
                ssid1 = joinDraft.ssid1.ifBlank { novice.allowedSsids.getOrNull(0).orEmpty() },
                ssid2 = joinDraft.ssid2,
            )
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
        Text(
            "先创建本机宝宝档案开始记录；若要加入已有家庭，可在下方加入。",
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
            listOf(null to "未设置", "female" to "女", "male" to "男").forEach { (v, label) ->
                FilterChip(
                    selected = sex == v,
                    onClick = { sex = v },
                    label = { Text(label) },
                )
            }
        }
        OutlinedButton(onClick = { showDate = true }, modifier = Modifier.fillMaxWidth()) {
            Text("生日：$dateLabel")
        }
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
                                Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
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
        formError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
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
                    onDone = { err ->
                        if (err == null) onFinished()
                        else formError = err
                    },
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text("开始记录")
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = {
                    withHomeWifiAccess {
                        fillCurrentWifiIfBlank()
                        showJoin = true
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
            ) {
                Text("加入家庭")
            }
            OutlinedButton(
                onClick = { withHomeWifiAccess { requestOrLaunchInviteScan() } },
                modifier = Modifier.height(52.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.QrCodeScanner,
                    contentDescription = "扫码加入家庭",
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text("扫码")
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

    if (showJoin) {
        AlertDialog(
            onDismissRequest = { showJoin = false },
            title = { Text("加入家庭") },
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
                    JoinSetupStep(
                        step = "1",
                        title = "家庭网络",
                        status = if (joinDraft.host.isNotBlank() && joinDraft.ssid1.isNotBlank()) {
                            "服务器与家庭 Wi‑Fi 已填写"
                        } else {
                            "填写服务器并绑定家庭 Wi‑Fi"
                        },
                        complete = joinDraft.host.isNotBlank() && joinDraft.ssid1.isNotBlank(),
                    )
                    JoinSetupStep(
                        step = "2",
                        title = "家庭邀请",
                        status = if (joinDraft.invitation.isBlank()) "扫码或粘贴邀请码" else "邀请码已填入",
                        complete = joinDraft.invitation.isNotBlank(),
                    )
                    JoinSetupStep(
                        step = "3",
                        title = "共享范围",
                        status = "加入成功后同步育儿记录与日志图片",
                        complete = false,
                    )
                    OutlinedTextField(
                        value = joinDraft.host,
                        onValueChange = { joinDraft = joinDraft.copy(host = it) },
                        label = { Text("服务器主机（IP/域名）") },
                        placeholder = { Text("192.168.50.4") },
                        supportingText = {
                            Text(if (joinDraft.host.isBlank()) "待填写" else "服务器地址已填写")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = joinDraft.portText,
                        onValueChange = {
                            joinDraft = joinDraft.copy(portText = it.filter(Char::isDigit).take(5))
                        },
                        label = { Text("端口") },
                        placeholder = { Text("8765") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = joinDraft.ssid1,
                        onValueChange = { joinDraft = joinDraft.copy(ssid1 = it) },
                        label = { Text("家庭 Wi‑Fi 名称 1（如 2.4G）") },
                        placeholder = { Text("当前连接的 Wi‑Fi 名") },
                        supportingText = {
                            Text(
                                if (joinDraft.ssid1.isNotBlank()) {
                                    "已绑定：${joinDraft.ssid1}"
                                } else {
                                    "待填写，或读取当前 Wi‑Fi"
                                },
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = joinDraft.ssid2,
                        onValueChange = { joinDraft = joinDraft.copy(ssid2 = it) },
                        label = { Text("家庭 Wi‑Fi 名称 2（可选，如 5G）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = {
                            withHomeWifiAccess {
                                val cur = vm.currentWifiSsid()?.trim().orEmpty()
                                if (cur.isEmpty()) {
                                    showHomeWifiAccessGuide = true
                                } else if (joinDraft.ssid1.isBlank()) {
                                    joinDraft = joinDraft.copy(ssid1 = cur)
                                } else if (joinDraft.ssid2.isBlank() && joinDraft.ssid1 != cur) {
                                    joinDraft = joinDraft.copy(ssid2 = cur)
                                } else if (joinDraft.ssid1 != cur && joinDraft.ssid2 != cur) {
                                    formError = "Wi‑Fi 名称已满 2 个，请先清空一格"
                                } else {
                                    formError = "当前 Wi‑Fi 已在列表中"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("填入当前 Wi‑Fi 名称")
                    }
                    OutlinedTextField(
                        value = joinDisplayName,
                        onValueChange = { joinDisplayName = it },
                        label = { Text("我是宝宝的？") },
                        placeholder = { Text("如：妈妈、干妈、月嫂小王") },
                        supportingText = { Text("家庭称呼，必填；家人用这个认出你") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = joinDraft.invitation,
                        onValueChange = { joinDraft = joinDraft.copy(invitation = it) },
                        label = { Text("邀请码或 QR 载荷") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(
                                onClick = { withHomeWifiAccess { requestOrLaunchInviteScan() } },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.QrCodeScanner,
                                    contentDescription = "扫码填入邀请",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    formError?.let { err ->
                        Text(err, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        withHomeWifiAccess {
                            fillCurrentWifiIfBlank()
                            formError = null
                            vm.joinFamily(
                                draft = joinDraft,
                                displayName = joinDisplayName,
                                onDone = { err ->
                                    if (err == null) {
                                        showJoin = false
                                        onFinished()
                                    } else {
                                        formError = err
                                    }
                                },
                            )
                        }
                    },
                ) { Text("加入") }
            },
            dismissButton = {
                TextButton(onClick = { showJoin = false }) { Text("取消") }
            },
        )
    }

    if (showHomeWifiAccessGuide) {
        val settingsTarget = HomeWifiPermission.settingsTarget(context)
        AlertDialog(
            onDismissRequest = { showHomeWifiAccessGuide = false },
            title = { Text("允许识别家庭 Wi‑Fi") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
                    HomeWifiGuideRow("1", "位置权限", "仅用于读取当前 Wi‑Fi 名称")
                    HomeWifiGuideRow("2", "定位服务", "需保持开启；位置数据不会上传")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showHomeWifiAccessGuide = false
                        context.startActivity(HomeWifiPermission.settingsIntent(context))
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
