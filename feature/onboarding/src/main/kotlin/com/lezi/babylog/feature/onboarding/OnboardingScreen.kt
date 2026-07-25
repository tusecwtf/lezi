package com.lezi.babylog.feature.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.core.ui.CameraCapture
import com.lezi.babylog.core.ui.UiTags
import com.lezi.babylog.designsystem.dismissKeyboardOnTap
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.CreateBabyInput
import com.lezi.babylog.sync.DEFAULT_SERVER_HOST
import com.lezi.babylog.sync.DEFAULT_SERVER_PORT
import com.lezi.babylog.sync.HomeLanServerConfig
import com.lezi.babylog.sync.InvitePayloadCodec
import com.lezi.babylog.sync.NetworkState
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncTrigger
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.first
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

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val careLog: CareLog,
    private val sync: SyncPort,
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
     * Ensure a local baby exists, save home-LAN config, then join family.
     */
    fun joinFamily(
        nickname: String,
        sex: String?,
        birthdayEpochDay: Long,
        birthWeightGrams: Int?,
        themeColorArgb: Int,
        host: String,
        portText: String,
        ssid1: String,
        ssid2: String,
        invitePayload: String,
        onDone: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                if (!careLog.observeHasBaby().first()) {
                    careLog.createBaby(
                        CreateBabyInput(
                            nickname = nickname.trim().ifBlank { "年年" },
                            sex = sex,
                            birthdayEpochDay = birthdayEpochDay,
                            birthWeightGrams = birthWeightGrams,
                            themeColorArgb = themeColorArgb,
                        ),
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                val msg = t.message.orEmpty()
                if (!msg.contains("已存在") && !msg.contains("duplicate", ignoreCase = true)) {
                    onDone(productUiError(t, "创建宝宝失败"))
                    return@launch
                }
            }
            val (h, p) = HomeLanServerConfig.parseHostPort(
                host,
                portText.toIntOrNull() ?: DEFAULT_SERVER_PORT,
            )
            val port = portText.toIntOrNull() ?: p
            val config = HomeLanServerConfig(host = h, port = port, allowedSsids = listOf(ssid1, ssid2))
            val save = sync.saveHomeLanConfig(config)
            if (save.isFailure) {
                onDone(productUiError(save.exceptionOrNull() ?: Exception("保存失败"), "保存家庭网络失败"))
                return@launch
            }
            val join = sync.joinWithPayload(invitePayload.trim())
            if (join.isFailure) {
                onDone(productUiError(join.exceptionOrNull() ?: Exception("加入失败"), "加入家庭失败"))
                return@launch
            }
            sync.requestSync(SyncTrigger.PullToRefresh)
            onDone(null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    var nameError by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    // Prefill unsaved defaults, including the current Wi-Fi name when available.
    val novice = remember { HomeLanServerConfig.noviceUiDefaults(vm.currentWifiSsid()) }
    var joinHost by remember { mutableStateOf(novice.host) }
    var joinPort by remember { mutableStateOf(novice.port.toString()) }
    var joinSsid1 by remember { mutableStateOf(novice.allowedSsids.getOrNull(0).orEmpty()) }
    var joinSsid2 by remember { mutableStateOf("") }
    var joinCode by remember { mutableStateOf("") }
    val context = LocalContext.current
    fun applyScannedInvite(raw: String) {
        val payload = raw.trim()
        if (payload.isEmpty()) return
        joinCode = payload
        formError = null
        // QR carries host/port + optional home Wi‑Fi names for novice prefill.
        runCatching { InvitePayloadCodec.decode(payload) }.getOrNull()?.let { decoded ->
            val config = decoded.homeLanConfig
            if (config.host.isNotBlank()) {
                joinHost = config.host
                joinPort = config.port.toString()
            }
            if (decoded.ssids.isNotEmpty()) {
                joinSsid1 = decoded.ssids.getOrNull(0).orEmpty()
                joinSsid2 = decoded.ssids.getOrNull(1).orEmpty()
            }
        }
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
    // Refresh an untouched SSID field because permission may have just been granted.
    LaunchedEffect(showJoin) {
        if (!showJoin) return@LaunchedEffect
        if (joinHost.isBlank()) joinHost = DEFAULT_SERVER_HOST
        if (joinPort.isBlank()) joinPort = DEFAULT_SERVER_PORT.toString()
        val cur = vm.currentWifiSsid()?.trim().orEmpty()
        if (cur.isNotEmpty() && joinSsid1.isBlank()) {
            joinSsid1 = cur
        }
    }
    val dateLabel = remember(birthday) {
        LocalDate.ofEpochDay(birthday).format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
                name = it
                nameError = false
            },
            label = { Text("宝宝昵称") },
            isError = nameError,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemePalette.forEachIndexed { index, color ->
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(color))
                        .then(
                            if (themeIdx == index) {
                                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            } else {
                                Modifier
                            },
                        )
                        .clickable { themeIdx = index },
                )
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
                onClick = { showJoin = true },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
            ) {
                Text("加入家庭")
            }
            OutlinedButton(
                onClick = { requestOrLaunchInviteScan() },
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
        val initialMillis = LocalDate.ofEpochDay(birthday)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { ms ->
                            birthday = Instant.ofEpochMilli(ms)
                                .atZone(ZoneId.systemDefault())
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
            DatePicker(state = state)
        }
    }

    if (showJoin) {
        AlertDialog(
            onDismissRequest = { showJoin = false },
            title = { Text("加入家庭") },
            text = {
                Column(
                    Modifier
                        .dismissKeyboardOnTap()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "已预填当前 Wi‑Fi 与常见服务器地址。可点扫码图标扫描邀请二维码，" +
                            "也可手动粘贴邀请码。加入后将共享育儿记录与日志图片。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = joinHost,
                        onValueChange = { joinHost = it },
                        label = { Text("服务器主机（IP/域名）") },
                        placeholder = { Text("192.168.50.4") },
                        supportingText = { Text("家里 NAS 的 IP，不确定时保持默认即可") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = joinPort,
                        onValueChange = { joinPort = it.filter { ch -> ch.isDigit() }.take(5) },
                        label = { Text("端口") },
                        placeholder = { Text("8765") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = joinSsid1,
                        onValueChange = { joinSsid1 = it },
                        label = { Text("家庭 Wi‑Fi 名称 1（如 2.4G）") },
                        placeholder = { Text("当前连接的 Wi‑Fi 名") },
                        supportingText = {
                            Text(
                                if (joinSsid1.isNotBlank()) {
                                    "已填入当前连接；若手机连的是 5G 名，第二格可再填 2.4G"
                                } else {
                                    "无法自动读取时请手动填写与路由器一致的名称"
                                },
                            )
                        },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = joinSsid2,
                        onValueChange = { joinSsid2 = it },
                        label = { Text("家庭 Wi‑Fi 名称 2（可选，如 5G）") },
                        singleLine = true,
                    )
                    OutlinedButton(
                        onClick = {
                            val cur = vm.currentWifiSsid()?.trim().orEmpty()
                            if (cur.isEmpty()) {
                                formError = "无法读取 Wi‑Fi 名称，请开启定位权限后重试"
                            } else if (joinSsid1.isBlank()) {
                                joinSsid1 = cur
                            } else if (joinSsid2.isBlank() && joinSsid1 != cur) {
                                joinSsid2 = cur
                            } else if (joinSsid1 != cur && joinSsid2 != cur) {
                                formError = "Wi‑Fi 名称已满 2 个，请先清空一格"
                            } else {
                                formError = "当前 Wi‑Fi 已在列表中"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("填入当前 Wi‑Fi 名称")
                    }
                    OutlinedTextField(
                        value = joinCode,
                        onValueChange = { joinCode = it },
                        label = { Text("邀请码或 QR 载荷") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { requestOrLaunchInviteScan() }) {
                                Icon(
                                    imageVector = Icons.Outlined.QrCodeScanner,
                                    contentDescription = "扫码填入邀请",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = { requestOrLaunchInviteScan() },
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
                    formError?.let { err ->
                        Text(err, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (joinCode.trim().isEmpty()) {
                            formError = "请填写邀请码"
                            return@TextButton
                        }
                        if (joinHost.trim().isEmpty()) {
                            formError = "请填写服务器主机"
                            return@TextButton
                        }
                        if (joinSsid1.isBlank() && joinSsid2.isBlank()) {
                            formError = "请至少填写一个家庭 Wi‑Fi 名称"
                            return@TextButton
                        }
                        formError = null
                        val grams = weightText.toIntOrNull()
                        vm.joinFamily(
                            nickname = name,
                            sex = sex,
                            birthdayEpochDay = birthday,
                            birthWeightGrams = grams,
                            themeColorArgb = ThemePalette[themeIdx],
                            host = joinHost,
                            portText = joinPort,
                            ssid1 = joinSsid1,
                            ssid2 = joinSsid2,
                            invitePayload = joinCode,
                            onDone = { err ->
                                if (err == null) {
                                    showJoin = false
                                    onFinished()
                                } else {
                                    formError = err
                                }
                            },
                        )
                    },
                ) { Text("加入") }
            },
            dismissButton = {
                TextButton(onClick = { showJoin = false }) { Text("取消") }
            },
        )
    }
}
