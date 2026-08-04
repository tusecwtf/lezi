package com.lezi.babylog.feature.log.composer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lezi.babylog.core.model.MAX_RECORD_PHOTOS
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.ui.CameraCapture
import com.lezi.babylog.core.ui.RecordTypeIcon
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.designsystem.LeziClockDialDialog
import com.lezi.babylog.designsystem.LeziConfirmReasonCard
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziPrimaryButtonMode
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziSectionLabel
import com.lezi.babylog.designsystem.LeziShapes
import com.lezi.babylog.designsystem.LeziSheetContentMax
import com.lezi.babylog.designsystem.LeziThemeExt
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.LocalPhotoLoadResult
import com.lezi.babylog.designsystem.LocalPhotoTarget
import com.lezi.babylog.designsystem.dismissKeyboardOnTap
import com.lezi.babylog.designsystem.leziRecordColor
import com.lezi.babylog.designsystem.rememberDismissKeyboard
import com.lezi.babylog.designsystem.rememberLocalPhoto
import java.time.Instant
import java.time.ZoneId
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

private enum class QuickClockTarget {
    Start,
    End,
}

@Composable
internal fun QuickRecordSheet(
    draft: QuickRecordDraft,
    interactionKey: Any,
    amountStepMl: Int,
    timeStepMin: Int,
    timePickerStyle: String = "dropdown",
    preferredHand: String = "right",
    birthdayEpochDay: Long? = null,
    infantFeverAdviceEnabled: Boolean = true,
    saving: Boolean,
    deleting: Boolean,
    saveError: String?,
    canStartNursingTimer: Boolean,
    systemCalendarConfigured: Boolean = false,
    onConfigureSystemCalendar: (() -> Unit)? = null,
    onDraftChange: (QuickRecordDraft) -> Unit,
    onDismiss: (ComposerDismissSource) -> Unit,
    onDelete: (() -> Unit)?,
    onConfirm: (QuickRecordDraft) -> Unit,
    onStartNursingTimer: () -> Unit,
    onImportPhotos: (List<android.net.Uri>) -> Unit,
    onRemovePhoto: (String) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val typeColor = leziRecordColor(draft.type.presentation.colorRole)
    val actionsEnabled = !saving && !deleting
    val busy = saving || deleting
    val nowMillis = RecordTime.currentTimeMillis()
    val sleepPolicy = draft.takeIf { it.type == RecordType.SLEEP }
        ?.let { sleepComposerPolicy(it, nowMillis) }
    val intervalPreview = draft.intervalDurationPreview(nowMillis)
    val validation = draft.validationResult(nowMillis)
    val canConfirm = validation == null
    val appearance = confirmAppearance(busy = busy, canConfirm = canConfirm)
    val dismissKeyboard = rememberDismissKeyboard()
    var clockTarget by remember(interactionKey) {
        mutableStateOf<QuickClockTarget?>(null)
    }
    var clockError by remember(interactionKey) {
        mutableStateOf<String?>(null)
    }
    var isDirty by remember(interactionKey) { mutableStateOf(false) }
    var attemptedConfirm by remember(interactionKey) {
        mutableStateOf(false)
    }
    var confirmChrome by remember(interactionKey) {
        mutableStateOf(ComposerConfirmChromeState())
    }
    val fieldFocusRequester = remember(interactionKey) { FocusRequester() }
    val context = LocalContext.current
    var photoActionError by remember(interactionKey) { mutableStateOf<String?>(null) }
    var pendingCameraUri by remember(interactionKey) { mutableStateOf<Uri?>(null) }
    var previewPhotoIndex by remember(interactionKey) { mutableStateOf<Int?>(null) }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_RECORD_PHOTOS),
        onImportPhotos,
    )
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (success && uri != null) {
            onImportPhotos(listOf(uri))
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            photoActionError = "需要相机权限才能拍照，请在系统设置中开启"
            return@rememberLauncherForActivityResult
        }
        if (!CameraCapture.hasCameraHardware(context)) {
            photoActionError = "此设备没有可用相机"
            return@rememberLauncherForActivityResult
        }
        runCatching {
            val uri = CameraCapture.createOutputUri(context)
            pendingCameraUri = uri
            takePicture.launch(uri)
        }.onFailure {
            photoActionError = "无法打开相机，请稍后重试"
        }
    }
    fun launchCameraCapture() {
        photoActionError = null
        if (!CameraCapture.hasCameraHardware(context)) {
            photoActionError = "此设备没有可用相机"
            return
        }
        if (CameraCapture.hasPermission(context)) {
            runCatching {
                val uri = CameraCapture.createOutputUri(context)
                pendingCameraUri = uri
                takePicture.launch(uri)
            }.onFailure {
                photoActionError = "无法打开相机，请稍后重试"
            }
        } else {
            cameraPermission.launch(CameraCapture.PERMISSION)
        }
    }
    val isIntervalMode = draft.mode == QuickRecordMode.Sleep
    val visibleIntervalPreview = draft.visibleIntervalDurationPreview(
        nowMillis = nowMillis,
        isDirty = isDirty,
        attemptedConfirm = attemptedConfirm,
    )
    val intervalWarning = (intervalPreview as? IntervalDurationPreview.Warning)?.text
    val timeFeedback = if (isIntervalMode) {
        clockError?.let { IntervalDurationPreview.Warning(it) } ?: visibleIntervalPreview
    } else {
        visibleIntervalPreview
    }
    // Persist / clock errors stay in the scroll footer; draft validation uses the reason card.
    val footerError = clockError.takeUnless { isIntervalMode }
        ?: saveError?.takeUnless { it == intervalWarning }

    LaunchedEffect(busy) {
        confirmChrome = reduceConfirmChrome(
            confirmChrome,
            ComposerConfirmChromeEvent.BusyChanged(busy),
        )
        if (busy) {
            clockTarget = null
            clockError = null
            dismissKeyboard()
        }
    }
    LaunchedEffect(confirmChrome.reasonVisible, confirmChrome.focusField) {
        if (confirmChrome.reasonVisible && confirmChrome.focusField != null) {
            runCatching { fieldFocusRequester.requestFocus() }
        }
    }

    fun update(value: QuickRecordDraft) {
        if (!actionsEnabled) return
        clockError = null
        isDirty = true
        confirmChrome = reduceConfirmChrome(
            confirmChrome,
            ComposerConfirmChromeEvent.DraftEdited,
        )
        onDraftChange(value)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.96f)
            .navigationBarsPadding()
            .imePadding()
            .dismissKeyboardOnTap(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LeziSpacing.Lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(LeziSpacing.Touch)
                    .clip(CircleShape)
                    .background(typeColor.copy(alpha = QuickDockIconDiscAlpha)),
                contentAlignment = Alignment.Center,
            ) {
                RecordTypeIcon(draft.type, size = 24.dp, tint = typeColor)
            }
            Spacer(Modifier.size(LeziSpacing.Sm))
            Column(Modifier.weight(1f)) {
                Text(
                    sheetKicker(draft),
                    style = LeziTypography.Eyebrow,
                    color = if (draft.mode == QuickRecordMode.Sleep) {
                        typeColor
                    } else {
                        Color.Unspecified
                    },
                )
                Text(sheetTitle(draft, nowMillis), style = LeziTypography.Title)
            }
            TextButton(
                onClick = { onDismiss(ComposerDismissSource.HeaderClose) },
                enabled = actionsEnabled,
            ) {
                Text("关闭")
            }
            if (onDelete != null) {
                TextButton(
                    onClick = onDelete,
                    enabled = !saving && !deleting,
                ) {
                    Text(
                        if (deleting) "删除中…" else "删除",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .heightIn(max = LeziSheetContentMax)
                .verticalScroll(rememberScrollState())
                .dismissKeyboardOnTap()
                .padding(horizontal = LeziSpacing.Lg, vertical = LeziSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(LeziSpacing.Md),
        ) {
            LeziSectionLabel("基本信息")
            PurposeFields(
                draft = draft,
                amountStepMl = amountStepMl,
                birthdayEpochDay = birthdayEpochDay,
                infantFeverAdviceEnabled = infantFeverAdviceEnabled,
                canStartNursingTimer = canStartNursingTimer,
                actionsEnabled = actionsEnabled,
                onDraftChange = ::update,
                onStartNursingTimer = onStartNursingTimer,
                highlightedField = confirmChrome.focusField,
                fieldFocusRequester = fieldFocusRequester,
                sleepPolicy = sleepPolicy,
            )

            TimeFields(
                draft = draft,
                zone = zone,
                onOpenStart = {
                    dismissKeyboard()
                    clockError = null
                    clockTarget = QuickClockTarget.Start
                },
                onOpenEnd = {
                    dismissKeyboard()
                    clockError = null
                    clockTarget = QuickClockTarget.End
                },
                onToggleRecordWake = if (sleepPolicy?.showWakeToggle == true) {
                    { enabled ->
                        dismissKeyboard()
                        update(
                            draft.copy(
                                endTimestamp = if (enabled) {
                                    RecordTime.currentTimeMillis()
                                } else {
                                    null
                                },
                            ),
                        )
                    }
                } else {
                    null
                },
                accentColor = if (draft.mode == QuickRecordMode.Sleep) typeColor else null,
                intervalPreview = timeFeedback,
                highlightedField = confirmChrome.focusField,
                sleepPolicy = sleepPolicy,
                enabled = actionsEnabled,
            )

            LeziSectionLabel("备注")
            OutlinedTextField(
                value = draft.note,
                onValueChange = { update(draft.copy(note = it.take(200))) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(RECORD_COMPOSER_NOTE_FIELD_TAG)
                    .then(
                        if (confirmChrome.focusField == ComposerInvalidField.Note) {
                            Modifier.focusRequester(fieldFocusRequester)
                        } else {
                            Modifier
                        },
                    ),
                label = { Text("备注（可选）") },
                placeholder = { Text(notePlaceholder(draft.type)) },
                isError = confirmChrome.focusField == ComposerInvalidField.Note,
                minLines = 2,
                maxLines = 4,
                supportingText = { Text("${draft.note.length}/200") },
                enabled = actionsEnabled,
            )
            if (draft.recentNotes.isNotEmpty()) {
                Text("最近备注", style = LeziTypography.Label)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    draft.recentNotes.forEach { candidate ->
                        TextButton(
                            onClick = { update(draft.copy(note = candidate)) },
                            enabled = actionsEnabled,
                        ) {
                            Text(candidate, maxLines = 1)
                        }
                    }
                }
            }
            // Shared note-area photo chrome for every record type (max 3).
            Text("记录图片（最多 $MAX_RECORD_PHOTOS 张）", style = LeziTypography.Label)
            if (draft.photos.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
                ) {
                    draft.photos.forEachIndexed { index, path ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val photo by rememberLocalPhoto(path, LocalPhotoTarget.THUMBNAIL)
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable(
                                        enabled = actionsEnabled,
                                        onClick = { previewPhotoIndex = index },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                when (val result = photo) {
                                    is LocalPhotoLoadResult.Ready -> {
                                        Image(
                                            bitmap = result.value,
                                            contentDescription = "记录图片，点击预览",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop,
                                        )
                                    }
                                    LocalPhotoLoadResult.Loading -> Unit
                                    LocalPhotoLoadResult.Unavailable -> {
                                        Text("无法读取", style = LeziTypography.Meta)
                                    }
                                }
                            }
                            TextButton(
                                enabled = actionsEnabled,
                                onClick = { onRemovePhoto(path) },
                                modifier = Modifier.heightIn(min = LeziSpacing.Touch),
                            ) { Text("移除") }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
            ) {
                TextButton(
                    enabled = actionsEnabled && RecordPhotoChrome.canAddPhoto(draft.photos.size),
                    onClick = {
                        photoActionError = null
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                ) { Text("相册") }
                TextButton(
                    enabled = actionsEnabled && RecordPhotoChrome.canAddPhoto(draft.photos.size),
                    onClick = { launchCameraCapture() },
                ) { Text("拍照") }
            }
            photoActionError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = LeziTypography.Meta,
                )
            }

            footerError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = LeziTypography.Meta,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = LeziSpacing.Lg,
                    top = LeziSpacing.Sm,
                    end = LeziSpacing.Lg,
                    bottom = LeziSpacing.Lg,
                ),
            verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
        ) {
            AnimatedVisibility(visible = confirmChrome.reasonMessage != null) {
                confirmChrome.reasonMessage?.let { reason ->
                    LeziConfirmReasonCard(reason)
                }
            }
            // Ticket 21: schedule-care seam — default-on projection; setup is optional and
            // cancel/skip never blocks plan save (CareLog falls back to Lezi reminders).
            if (draft.workMode(nowMillis) == ComposerWorkMode.ScheduleCare ||
                draft.workMode(nowMillis) == ComposerWorkMode.EditPlan
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("同步到系统日历", style = LeziTypography.BodyStrong)
                        Text(
                            if (systemCalendarConfigured) {
                                "本机投影 · 不上传家庭"
                            } else {
                                "未配置时仍可保存计划，使用乐记提醒"
                            },
                            style = LeziTypography.Meta,
                        )
                    }
                    Switch(
                        checked = draft.projectToSystemCalendar,
                        onCheckedChange = { on ->
                            isDirty = true
                            onDraftChange(draft.copy(projectToSystemCalendar = on))
                        },
                        enabled = actionsEnabled,
                    )
                }
                if (draft.projectToSystemCalendar && !systemCalendarConfigured &&
                    onConfigureSystemCalendar != null
                ) {
                    TextButton(
                        onClick = onConfigureSystemCalendar,
                        enabled = actionsEnabled,
                    ) {
                        Text("去配置系统日历")
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                LeziSecondaryButton(
                    label = "取消",
                    onClick = {
                        dismissKeyboard()
                        confirmChrome = reduceConfirmChrome(
                            confirmChrome,
                            ComposerConfirmChromeEvent.Dismissed,
                        )
                        onDismiss(ComposerDismissSource.FooterCancel)
                    },
                    enabled = actionsEnabled,
                    modifier = Modifier.weight(1f),
                )
                val confirmLabel = when {
                    saving -> "保存中…"
                    deleting -> "删除中…"
                    else -> draft.confirmLabel()
                }
                val buttonMode = when (appearance) {
                    ComposerConfirmAppearance.Enabled -> LeziPrimaryButtonMode.Enabled
                    ComposerConfirmAppearance.ExplainedDisabled ->
                        LeziPrimaryButtonMode.ExplainedDisabled
                    ComposerConfirmAppearance.BusyDisabled -> LeziPrimaryButtonMode.Disabled
                }
                // TalkBack: concrete reason only when explained-disabled — never a fixed prompt.
                val confirmSemantics = when (appearance) {
                    ComposerConfirmAppearance.ExplainedDisabled ->
                        validation?.message ?: confirmLabel
                    else -> confirmLabel
                }
                LeziPrimaryButton(
                    label = confirmLabel,
                    onClick = {
                        when (appearance) {
                            ComposerConfirmAppearance.BusyDisabled -> Unit
                            ComposerConfirmAppearance.Enabled -> {
                                attemptedConfirm = true
                                dismissKeyboard()
                                // Hard-block persist when invalid even if appearance races.
                                if (draft.canConfirm()) {
                                    onConfirm(draft)
                                }
                            }
                            ComposerConfirmAppearance.ExplainedDisabled -> {
                                attemptedConfirm = true
                                dismissKeyboard()
                                confirmChrome = reduceConfirmChrome(
                                    confirmChrome,
                                    ComposerConfirmChromeEvent.GreyConfirmTapped(validation),
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = confirmSemantics },
                    mode = buttonMode,
                )
            }
        }
    }

    previewPhotoIndex?.let { startIndex ->
        val previewStart = RecordPhotoChrome.previewStartIndex(startIndex, draft.photos.size)
        if (previewStart != null) {
            com.lezi.babylog.designsystem.LeziPhotoPreviewDialog(
                photos = draft.photos,
                startIndex = previewStart,
                onDismiss = { previewPhotoIndex = null },
                contentDescriptionPrefix = "记录图片预览",
            )
        }
    }

    clockTarget?.let { target ->
        val initialMillis = when (target) {
            QuickClockTarget.Start -> draft.timestamp
            QuickClockTarget.End -> draft.endTimestamp ?: draft.timestamp
        }
        LeziClockDialDialog(
            title = clockDialogTitle(
                draft = draft,
                selectingEnd = target == QuickClockTarget.End,
                nowMillis = nowMillis,
            ),
            value = Instant.ofEpochMilli(initialMillis).atZone(zone),
            minuteStep = timeStepMin,
            timePickerStyle = timePickerStyle,
            preferredHand = preferredHand,
            onConfirm = { picked ->
                val pickedMillis = picked.toInstant().toEpochMilli()
                val nowMillis = RecordTime.currentTimeMillis()
                when (target) {
                    QuickClockTarget.Start -> {
                        val shiftedEnd = RecordTime.shiftStartPreservingDuration(
                            oldStartMillis = draft.timestamp,
                            oldEndMillis = draft.endTimestamp,
                            newStartMillis = pickedMillis,
                        )
                        val candidateEnd = shiftedEnd ?: draft.endTimestamp
                        val rejection = draft.startTimeRejectionMessage(
                            candidateStartTimestamp = pickedMillis,
                            candidateEndTimestamp = candidateEnd,
                            nowMillis = nowMillis,
                        )
                        if (rejection == null) {
                            update(
                                draft.copy(
                                    timestamp = pickedMillis,
                                    endTimestamp = candidateEnd,
                                ),
                            )
                        } else {
                            isDirty = true
                            clockError = rejection
                            confirmChrome = reduceConfirmChrome(
                                confirmChrome,
                                ComposerConfirmChromeEvent.DraftEdited,
                            )
                        }
                    }
                    QuickClockTarget.End -> {
                        val updated = draft.copy(endTimestamp = pickedMillis)
                        val rejection = draft.endTimeRejectionMessage(
                            candidateEndTimestamp = pickedMillis,
                            nowMillis = nowMillis,
                        )
                        if (rejection == null) {
                            update(updated)
                        } else {
                            isDirty = true
                            clockError = rejection
                            confirmChrome = reduceConfirmChrome(
                                confirmChrome,
                                ComposerConfirmChromeEvent.DraftEdited,
                            )
                        }
                    }
                }
                clockTarget = null
            },
            onDismiss = { clockTarget = null },
        )
    }
}

internal const val RECORD_COMPOSER_NOTE_FIELD_TAG = "record_composer_note_field"
