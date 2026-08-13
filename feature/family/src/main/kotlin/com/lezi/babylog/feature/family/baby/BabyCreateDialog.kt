package com.lezi.babylog.feature.family.baby

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import com.lezi.babylog.designsystem.LeziAlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.lezi.babylog.core.model.birthWeightValidationError
import com.lezi.babylog.core.ui.BabyAvatar
import com.lezi.babylog.core.ui.BabyAvatarSizeEditPreview
import com.lezi.babylog.core.ui.BabyBirthdayDatePickerDialog
import com.lezi.babylog.core.ui.BabyProfileFormFields
import com.lezi.babylog.core.ui.CameraCaptureOutcome
import com.lezi.babylog.core.ui.CameraCaptureLauncher
import com.lezi.babylog.core.ui.OwnedCameraCapture
import com.lezi.babylog.core.ui.rememberCameraCaptureLauncher
import com.lezi.babylog.designsystem.LeziBabyTheme
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTextButton
import com.lezi.babylog.designsystem.LeziTextButtonTone
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.dismissKeyboardOnTap
import java.time.LocalDate

/**
 * Account-owned create baby dialog (form + avatar + theme). Cancel always dismisses
 * when not busy.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BabyCreateDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (
        nickname: String,
        sex: String?,
        birthdayEpochDay: Long,
        birthWeightGrams: Int?,
        themeColorArgb: Int,
        avatarJpeg: ByteArray?,
        onFinished: (String?) -> Unit,
    ) -> Unit,
    cameraCaptureLauncherFactory: @Composable (
        ownershipKey: Any?,
        onOutcome: (CameraCaptureOutcome) -> Unit,
    ) -> CameraCaptureLauncher = { key, onOutcome ->
        rememberCameraCaptureLauncher(key, onOutcome)
    },
) {
    var nickname by remember { mutableStateOf("") }
    var sex by remember { mutableStateOf<String?>(null) }
    var birthday by remember { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var weightText by remember { mutableStateOf("") }
    var themeIndex by remember { mutableIntStateOf(0) }
    var showDate by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var pickedAvatarUri by remember { mutableStateOf<Uri?>(null) }
    var ownedAvatarCapture by remember { mutableStateOf<OwnedCameraCapture?>(null) }
    var croppedAvatar by remember { mutableStateOf<CroppedAvatar?>(null) }
    var avatarError by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    fun releaseOwnedAvatarCapture() {
        ownedAvatarCapture?.release()
        ownedAvatarCapture = null
    }
    val themeArgb = LeziBabyTheme.PaletteArgb[themeIndex]
    val avatarCamera = cameraCaptureLauncherFactory("baby-create") { outcome ->
        when (outcome) {
            is CameraCaptureOutcome.Captured -> {
                releaseOwnedAvatarCapture()
                ownedAvatarCapture = outcome.capture
                pickedAvatarUri = outcome.capture.uri
            }
            CameraCaptureOutcome.Cancelled -> Unit
            CameraCaptureOutcome.PermissionDenied -> avatarError = "需要相机权限才能拍照"
            CameraCaptureOutcome.NoCamera -> avatarError = "此设备没有可用相机"
            CameraCaptureOutcome.LaunchFailed -> avatarError = "无法打开相机，请稍后重试"
        }
    }
    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            releaseOwnedAvatarCapture()
            avatarCamera.dispose()
            pickedAvatarUri = uri
        }
    }
    DisposableEffect(Unit) {
        onDispose { releaseOwnedAvatarCapture() }
    }
    val previewBitmap = remember(croppedAvatar) { croppedAvatar?.bitmap?.asImageBitmap() }
    val hasAvatar = croppedAvatar != null
    val blocked = busy || submitting

    LeziAlertDialog(
        onDismissRequest = {
            if (!blocked) {
                releaseOwnedAvatarCapture()
                avatarCamera.dispose()
                onDismiss()
            }
        },
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text("添加宝宝") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = LeziSpacing.DialogContentMax)
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
                        nickname = nickname.ifBlank { "宝" },
                        avatarPath = null,
                        previewBitmap = previewBitmap,
                        fallbackBackground = Color(themeArgb),
                        fallbackStyle = LeziTypography.Title,
                        modifier = Modifier.size(BabyAvatarSizeEditPreview),
                        borderWidth = 3.dp,
                        avatarContentDescription = "头像预览",
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        LeziSecondaryButton(
                            label = if (hasAvatar) "相册更换" else "从相册选择",
                            onClick = {
                                avatarError = null
                                avatarPicker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            },
                            enabled = !blocked,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        LeziSecondaryButton(
                            label = "拍照",
                            onClick = {
                                avatarError = null
                                avatarCamera.launch()
                            },
                            enabled = !blocked,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (hasAvatar) {
                            LeziTextButton(
                                label = "移除照片",
                                onClick = { croppedAvatar = null },
                                enabled = !blocked,
                            )
                        }
                    }
                }
                avatarError?.let {
                    Text(it, style = LeziTypography.Meta, color = MaterialTheme.colorScheme.error)
                }
                BabyProfileFormFields(
                    nickname = nickname,
                    onNicknameChange = {
                        nickname = it
                        localError = null
                    },
                    nicknameError = localError,
                    sex = sex,
                    onSexChange = { sex = it },
                    birthdayEpochDay = birthday,
                    onPickBirthday = { showDate = true },
                    weightText = weightText,
                    onWeightTextChange = { weightText = it },
                    enabled = !blocked,
                )
                Text("主题色", style = LeziTypography.Label)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    maxItemsInEachRow = 4,
                ) {
                    LeziBabyTheme.PaletteArgb.forEachIndexed { index, argb ->
                        val selected = themeIndex == index
                        Box(
                            modifier = Modifier
                                .size(LeziSpacing.Touch)
                                .clip(CircleShape)
                                .clickable(
                                    enabled = !blocked,
                                    onClickLabel = "主题色：${LeziBabyTheme.Labels[index]}",
                                    role = Role.Button,
                                    onClick = { themeIndex = index },
                                )
                                .then(
                                    if (selected) {
                                        Modifier.border(
                                            2.dp,
                                            MaterialTheme.colorScheme.primary,
                                            CircleShape,
                                        )
                                    } else {
                                        Modifier
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(argb)),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            LeziTextButton(
                label = if (blocked) "添加中…" else "添加",
                onClick = {
                    if (blocked) return@LeziTextButton
                    if (nickname.isBlank()) {
                        localError = "请填写昵称"
                        return@LeziTextButton
                    }
                    val grams = weightText.trim().takeIf { it.isNotEmpty() }
                        ?.toDoubleOrNull()
                        ?.let { (it * 1000).toInt() }
                    if (weightText.isNotBlank() && grams == null) {
                        localError = "出生体重格式不正确"
                        return@LeziTextButton
                    }
                    birthWeightValidationError(grams)?.let {
                        localError = it
                        return@LeziTextButton
                    }
                    submitting = true
                    onCreate(
                        nickname.trim(),
                        sex,
                        birthday,
                        grams,
                        themeArgb,
                        croppedAvatar?.jpegBytes,
                    ) { err ->
                        submitting = false
                        if (err != null) localError = err
                    }
                },
                enabled = !blocked,
                tone = LeziTextButtonTone.Primary,
            )
        },
        dismissButton = {
            LeziTextButton(
                label = "取消",
                onClick = {
                    releaseOwnedAvatarCapture()
                    avatarCamera.dispose()
                    onDismiss()
                },
                enabled = !blocked,
            )
        },
    )

    if (showDate) {
        BabyBirthdayDatePickerDialog(
            birthdayEpochDay = birthday,
            onDismiss = { showDate = false },
            onSelect = { birthday = it },
        )
    }

    pickedAvatarUri?.let { sourceUri ->
        AvatarCropDialog(
            sourceUri = sourceUri,
            onDismiss = {
                releaseOwnedAvatarCapture()
                avatarCamera.dispose()
                pickedAvatarUri = null
            },
            onConfirm = { result ->
                croppedAvatar = result
                releaseOwnedAvatarCapture()
                avatarCamera.dispose()
                pickedAvatarUri = null
            },
        )
    }
}
