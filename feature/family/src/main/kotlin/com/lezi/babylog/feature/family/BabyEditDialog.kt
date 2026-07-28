package com.lezi.babylog.feature.family

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.birthWeightValidationError
import com.lezi.babylog.core.model.limitBabyNicknameInput
import com.lezi.babylog.core.ui.BabyAvatar
import com.lezi.babylog.core.ui.CameraCapture
import com.lezi.babylog.designsystem.LeziDatePicker
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.dismissKeyboardOnTap
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BabyEditDialog(
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
    // Persist Home-LAN wire values (female/male/null), not Kotlin enum names.
    var sex by remember(baby.id) {
        mutableStateOf(
            when (baby.sex?.name) {
                "FEMALE" -> "female"
                "MALE" -> "male"
                else -> null
            },
        )
    }
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
                    listOf(
                        "female" to "女宝",
                        "male" to "男宝",
                        null to "未设置",
                    ).forEach { (key, label) ->
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
