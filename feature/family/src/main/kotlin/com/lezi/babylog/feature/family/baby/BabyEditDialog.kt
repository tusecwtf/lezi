package com.lezi.babylog.feature.family.baby

import com.lezi.babylog.core.common.LocalOpFailureCopy
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.ui.BabyAvatar
import com.lezi.babylog.core.ui.BabyAvatarSizeEditPreview
import com.lezi.babylog.core.ui.BabyBirthdayDatePickerDialog
import com.lezi.babylog.core.ui.BabyProfileFormFields
import com.lezi.babylog.core.ui.CameraCaptureOutcome
import com.lezi.babylog.core.ui.CameraCaptureLauncher
import com.lezi.babylog.core.ui.camera.CameraPermissionRecoveryDialog
import com.lezi.babylog.core.ui.rememberCameraCaptureLauncher
import com.lezi.babylog.designsystem.LeziBabyTheme
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.LeziThemeExt
import com.lezi.babylog.designsystem.dismissKeyboardOnTap
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
        onFinished: (String?) -> Unit,
    ) -> Unit,
    onLocalTheme: (argb: Int, onDone: (String?) -> Unit) -> Unit,
    onMoveLocal: (delta: Int, onDone: (String?) -> Unit) -> Unit,
    onSetCurrent: () -> Unit,
    cameraCaptureLauncherFactory: @Composable (
        ownershipKey: Any?,
        onOutcome: (CameraCaptureOutcome) -> Unit,
    ) -> CameraCaptureLauncher = { key, onOutcome ->
        rememberCameraCaptureLauncher(key, onOutcome)
    },
    draftHost: BabyEditDraftHost = viewModel(key = "baby-edit-draft") { BabyEditDraftHost() },
    onSaved: () -> Unit = onDismiss,
) {
    val draft = remember(draftHost, baby.id, baby.familyId, baby.clientUuid) { draftHost.open(baby) }
    val latestSaved by rememberUpdatedState(onSaved)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(draft, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snapshotFlow { draft.saved }.collect { saved ->
                if (saved && draft.consumeSaved()) {
                    draftHost.close(draft)
                    latestSaved()
                }
            }
        }
    }
    var nickname by draft::nickname
    var sex by draft::sex
    var birthday by draft::birthday
    var weightText by draft::weightText
    var showDate by draft::showDate
    var nicknameError by draft::nicknameError
    var weightError by draft::weightError
    var localPrefError by draft::localPrefError
    var themeArgb by draft::themeArgb
    var pickedAvatarUri by draft::pickedAvatarUri
    var croppedAvatar by draft::croppedAvatar
    var removeAvatar by draft::removeAvatar
    val saving = draft.saving || draft.saved
    var avatarError by draft::avatarError
    fun releaseOwnedAvatarCapture() = draft.releaseOwnedCapture()
    val avatarCamera = cameraCaptureLauncherFactory("baby-edit-${baby.familyId}-${baby.clientUuid}") { outcome ->
        when (outcome) {
            is CameraCaptureOutcome.Captured -> {
                draft.replaceOwnedCapture(outcome.capture)
                removeAvatar = false
                pickedAvatarUri = outcome.capture.uri
            }
            CameraCaptureOutcome.Cancelled -> Unit
            CameraCaptureOutcome.PermissionDenied -> avatarError = "需要相机权限才能拍照"
            CameraCaptureOutcome.NoCamera -> avatarError = "此设备没有可用相机"
            CameraCaptureOutcome.LaunchFailed -> avatarError = LocalOpFailureCopy.CAMERA_LAUNCH
        }
    }
    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            releaseOwnedAvatarCapture()
            avatarCamera.dispose()
            removeAvatar = false
            pickedAvatarUri = uri
        }
    }
    // The in-memory host owns completed captures and cropped bytes through configuration
    // rebuilding. The launcher owns still-pending captures; explicit close ends both.
    val previewBitmap = remember(croppedAvatar) {
        croppedAvatar?.bitmap?.asImageBitmap()
    }
    val hasAvatar = croppedAvatar != null || (!removeAvatar && baby.avatarPath != null)

    fun closeDraft() {
        if (draft.saving || draft.saved) return
        draftHost.close(draft)
        avatarCamera.dispose()
        onDismiss()
    }
    fun requestClose() {
        when (draft.requestExit()) {
            BabyProfileExit.Close -> closeDraft()
            BabyProfileExit.ConfirmDiscard, BabyProfileExit.Saving -> Unit
        }
    }

    LeziAlertDialog(
        onDismissRequest = ::requestClose,
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
                            fallbackStyle = LeziThemeExt.typography.Title,
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
                                        avatarError = null
                                        avatarCamera.launch()
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
                            nicknameError = null
                        },
                        nicknameError = nicknameError,
                        sex = sex,
                        onSexChange = { sex = it },
                        birthdayEpochDay = birthday,
                        onPickBirthday = { showDate = true },
                        weightText = weightText,
                        onWeightTextChange = {
                            weightText = it
                            weightError = null
                        },
                        weightError = weightError,
                        enabled = !saving,
                    )
                }

                if (!canEditProfile && draft.dirty) {
                    Text(
                        "档案编辑权限已变化，未保存的修改仍保留。恢复权限后可继续保存，或放弃档案修改。",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (canEditProfile && !canEditAvatar && draft.avatarChanged) {
                    Text(
                        "头像编辑权限已变化，未保存的头像仍保留。恢复权限后可继续保存，或放弃档案修改。",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                draft.formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (canEditProfile) {
                    Text(
                        "保存档案仅提交头像、昵称、性别、出生日期和出生体重。",
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("本机外观与顺序", style = LeziTypography.Label)
                Text(
                    "主题色与宝宝顺序即时保存在这台设备，取消或放弃档案修改不会撤销。",
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
                    label = if (saving) "保存中…" else "保存档案",
                    onClick = {
                        if (saving) return@LeziTextButton
                        if (!canEditProfile || (!canEditAvatar && draft.avatarChanged)) return@LeziTextButton
                        val weight = draft.beginSave() ?: return@LeziTextButton
                        onSaveProfile(
                            nickname.trim(),
                            sex,
                            birthday,
                            weight.grams,
                            croppedAvatar?.jpegBytes,
                            removeAvatar,
                        ) { error ->
                            draft.finishSave(error)
                        }
                    },
                    enabled = !saving && (canEditAvatar || !draft.avatarChanged),
                    tone = LeziTextButtonTone.Primary,
                )
            } else {
                LeziTextButton(label = "完成", onClick = ::requestClose, enabled = !saving)
            }
        },
        dismissButton = {
            LeziTextButton(
                label = if (canEditProfile) "取消" else "关闭",
                onClick = ::requestClose,
                enabled = !saving,
            )
        },
    )

    if (canEditProfile && canEditAvatar) {
        CameraPermissionRecoveryDialog(
            launcher = avatarCamera,
            enabled = !saving,
            onChooseGallery = {
                avatarError = null
                avatarPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
        )
    }

    if (draft.showDiscard) {
        LeziAlertDialog(
            onDismissRequest = draft::keepEditing,
            title = { Text("放弃未保存的档案修改？") },
            text = {
                Text("未保存的头像和档案字段将放弃。已保存的本机主题色和顺序会保留。")
            },
            confirmButton = {
                LeziTextButton(
                    label = "放弃档案修改",
                    onClick = ::closeDraft,
                    enabled = !saving,
                    tone = LeziTextButtonTone.Destructive,
                )
            },
            dismissButton = {
                LeziTextButton(label = "继续编辑", onClick = draft::keepEditing, enabled = !saving)
            },
        )
    }

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
                removeAvatar = false
                releaseOwnedAvatarCapture()
                avatarCamera.dispose()
                pickedAvatarUri = null
            },
        )
    }
}
