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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.birthWeightValidationError
import com.lezi.babylog.core.ui.BabyAvatar
import com.lezi.babylog.core.ui.BabyAvatarSizeEditPreview
import com.lezi.babylog.core.ui.BabyBirthdayDatePickerDialog
import com.lezi.babylog.core.ui.BabyProfileFormFields
import com.lezi.babylog.core.ui.CameraCapture
import com.lezi.babylog.designsystem.LeziBabyTheme
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.dismissKeyboardOnTap
import com.lezi.babylog.designsystem.normalizeBabyThemeArgb
import com.lezi.babylog.designsystem.LeziTextButton
import com.lezi.babylog.designsystem.LeziTextButtonTone
import com.lezi.babylog.designsystem.LeziSecondaryButton

/**
 * Edit / member-local baby dialog.
 *
 * [canEditProfile] true (owner / offline): avatar + profile fields + local prefs.
 * false (member): local prefs only (theme, order, set current).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BabyEditDialog(
    baby: Baby,
    canEditProfile: Boolean,
    canEditAvatar: Boolean,
    isCurrent: Boolean,
    canMoveEarlier: Boolean,
    canMoveLater: Boolean,
    onDismiss: () -> Unit,
    onSaveProfile: (
        nickname: String,
        sex: String?,
        birthdayEpochDay: Long,
        birthWeightGrams: Int?,
        avatarJpeg: ByteArray?,
        removeAvatar: Boolean,
        onFinished: () -> Unit,
    ) -> Unit,
    onLocalTheme: (argb: Int, onDone: (String?) -> Unit) -> Unit,
    onMoveLocal: (delta: Int, onDone: (String?) -> Unit) -> Unit,
    onSetCurrent: () -> Unit,
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
    var localPrefError by remember { mutableStateOf<String?>(null) }
    var themeArgb by remember(baby.id) {
        mutableStateOf(normalizeBabyThemeArgb(baby.themeColorArgb))
    }
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

    LeziAlertDialog(
        onDismissRequest = {
            if (!saving) onDismiss()
        },
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = {
            Text(
                if (canEditProfile) "编辑宝宝档案" else "${baby.nickname}的本机设置",
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = LeziSpacing.DialogContentMax)
                    .verticalScroll(rememberScrollState())
                    .dismissKeyboardOnTap(),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                if (canEditProfile) {
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
                            fallbackBackground = Color(themeArgb),
                            fallbackStyle = LeziTypography.Title,
                            modifier = Modifier.size(BabyAvatarSizeEditPreview),
                            borderWidth = 3.dp,
                            avatarContentDescription = "头像保存效果预览",
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (canEditAvatar) {
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
                                    enabled = !saving,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                LeziSecondaryButton(
                                    label = "拍照",
                                    onClick = {
                                        if (CameraCapture.hasPermission(avatarContext)) {
                                            launchAvatarCamera()
                                        } else {
                                            avatarCameraPermission.launch(CameraCapture.PERMISSION)
                                        }
                                    },
                                    enabled = !saving,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            if (canEditAvatar && hasAvatar) {
                                LeziTextButton(
                                    label = "移除照片",
                                    onClick = {
                                        croppedAvatar = null
                                        removeAvatar = true
                                    },
                                    enabled = !saving,
                                )
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
                        enabled = !saving,
                    )
                }

                Text("本机外观与顺序", style = LeziTypography.Label)
                Text(
                    "主题色与宝宝顺序只影响这台设备，不会修改家庭档案。",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isCurrent) {
                    Text("当前宝宝", style = LeziTypography.Label)
                } else {
                    LeziSecondaryButton(
                        label = "设为当前宝宝",
                        onClick = onSetCurrent,
                        enabled = !saving,
                    )
                }
                Text("主题色", style = LeziTypography.Label)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    maxItemsInEachRow = 4,
                ) {
                    LeziBabyTheme.PaletteArgb.forEachIndexed { index, argb ->
                        val selected = themeArgb == argb
                        Box(
                            modifier = Modifier
                                .size(LeziSpacing.Touch)
                                .clip(CircleShape)
                                .clickable(
                                    enabled = !saving,
                                    onClickLabel = "本机主题色：${LeziBabyTheme.Labels[index]}",
                                    role = Role.Button,
                                    onClick = {
                                        localPrefError = null
                                        onLocalTheme(argb) { err ->
                                            if (err == null) {
                                                themeArgb = argb
                                            } else {
                                                localPrefError = err
                                            }
                                        }
                                    },
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LeziSecondaryButton(
                        label = "前移",
                        onClick = {
                            localPrefError = null
                            onMoveLocal(-1) { err -> localPrefError = err }
                        },
                        enabled = !saving && canMoveEarlier,
                    )
                    LeziSecondaryButton(
                        label = "后移",
                        onClick = {
                            localPrefError = null
                            onMoveLocal(1) { err -> localPrefError = err }
                        },
                        enabled = !saving && canMoveLater,
                    )
                }
                localPrefError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (canEditProfile) {
                LeziTextButton(
                    label = if (saving) "保存中…" else "保存",
                    onClick = {
                        if (saving) return@LeziTextButton
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
                        saving = true
                        onSaveProfile(
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
                    enabled = !saving,
                    tone = LeziTextButtonTone.Primary,
                )
            } else {
                LeziTextButton(label = "完成", onClick = onDismiss, enabled = !saving)
            }
        },
        dismissButton = {
            LeziTextButton(
                label = if (canEditProfile) "取消" else "关闭",
                onClick = onDismiss,
                enabled = !saving,
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
            onDismiss = { pickedAvatarUri = null },
            onConfirm = { result ->
                croppedAvatar = result
                removeAvatar = false
                pickedAvatarUri = null
            },
        )
    }
}
