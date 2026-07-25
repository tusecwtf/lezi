package com.lezi.babylog.feature.log

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.ui.RecordTypeIcon
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.designsystem.LeziClockDialDialog
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.dismissKeyboardOnTap
import com.lezi.babylog.designsystem.leziRecordColor
import com.lezi.babylog.designsystem.rememberDismissKeyboard
import com.lezi.babylog.domain.CustomRecordItem
import java.time.Instant
import java.time.ZoneId

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
    customItems: List<CustomRecordItem> = emptyList(),
    saving: Boolean,
    deleting: Boolean,
    saveError: String?,
    canStartNursingTimer: Boolean,
    onDraftChange: (QuickRecordDraft) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onConfirm: (QuickRecordDraft) -> Unit,
    onStartNursingTimer: () -> Unit,
    onImportPhotos: (List<android.net.Uri>) -> Unit,
    onRemovePhoto: (String) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val typeColor = leziRecordColor(draft.type.presentation.colorRole)
    val actionsEnabled = !saving && !deleting
    val nowMillis = RecordTime.currentTimeMillis()
    val intervalPreview = draft.intervalDurationPreview(nowMillis)
    val confirmEnabled = actionsEnabled && draft.canConfirm(nowMillis)
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
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9),
        onImportPhotos,
    )
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
    val footerValidation = draft.footerValidationError(
        nowMillis = nowMillis,
        isDirty = isDirty,
        attemptedConfirm = attemptedConfirm,
    )
    val footerError = clockError.takeUnless { isIntervalMode }
        ?: saveError?.takeUnless { it == intervalWarning }
        ?: footerValidation

    fun update(value: QuickRecordDraft) {
        clockError = null
        isDirty = true
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
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(typeColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                RecordTypeIcon(draft.type, size = 25.dp, tint = typeColor)
            }
            Spacer(Modifier.size(12.dp))
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
                Text(sheetTitle(draft), style = LeziTypography.Title)
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
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .dismissKeyboardOnTap()
                .padding(horizontal = LeziSpacing.Lg, vertical = LeziSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(LeziSpacing.Md),
        ) {
            SectionLabel("基本信息")
            PurposeFields(
                draft = draft,
                amountStepMl = amountStepMl,
                birthdayEpochDay = birthdayEpochDay,
                infantFeverAdviceEnabled = infantFeverAdviceEnabled,
                customItems = customItems,
                canStartNursingTimer = canStartNursingTimer,
                actionsEnabled = actionsEnabled,
                onDraftChange = ::update,
                onStartNursingTimer = onStartNursingTimer,
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
                onToggleRecordWake = if (draft.sleepAction == SleepDraftAction.SleepDown) {
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
            )

            SectionLabel("备注")
            OutlinedTextField(
                value = draft.note,
                onValueChange = { update(draft.copy(note = it.take(200))) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("备注（可选）") },
                placeholder = { Text(notePlaceholder(draft.type)) },
                minLines = 2,
                maxLines = 4,
                supportingText = { Text("${draft.note.length}/200") },
            )
            if (draft.recentNotes.isNotEmpty()) {
                Text("最近备注", style = LeziTypography.Label)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    draft.recentNotes.forEach { candidate ->
                        TextButton(onClick = { update(draft.copy(note = candidate)) }) {
                            Text(candidate, maxLines = 1)
                        }
                    }
                }
            }
            if (draft.mode == QuickRecordMode.Text) {
                Text("记录图片（最多 9 张）", style = LeziTypography.Label)
                if (draft.photos.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        draft.photos.forEach { path ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                remember(path) {
                                    BitmapFactory.decodeFile(path)?.asImageBitmap()
                                }?.let { bitmap ->
                                    Image(
                                        bitmap = bitmap,
                                        contentDescription = "记录图片",
                                        modifier = Modifier
                                            .size(72.dp)
                                            .clip(MaterialTheme.shapes.small),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                                TextButton(
                                    onClick = { onRemovePhoto(path) },
                                ) { Text("移除") }
                            }
                        }
                    }
                }
                TextButton(
                    enabled = draft.photos.size < 9,
                    onClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                ) { Text("选择图片") }
            }

            footerError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = LeziTypography.Meta,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = LeziSpacing.Lg,
                    top = LeziSpacing.Sm,
                    end = LeziSpacing.Lg,
                    bottom = LeziSpacing.Lg,
                ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LeziSecondaryButton(
                label = "取消",
                onClick = {
                    dismissKeyboard()
                    onDismiss()
                },
                enabled = actionsEnabled,
                modifier = Modifier
                    .weight(1f),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(interactionKey, actionsEnabled, confirmEnabled) {
                        if (actionsEnabled && !confirmEnabled) {
                            // Save stays visually and semantically disabled; a physical tap only
                            // reveals the delayed validation reason required by "打开不吼".
                            detectTapGestures {
                                attemptedConfirm = true
                                dismissKeyboard()
                            }
                        }
                    },
            ) {
                LeziPrimaryButton(
                    label = when {
                        saving -> "保存中…"
                        deleting -> "删除中…"
                        else -> draft.confirmLabel()
                    },
                    onClick = {
                        if (!actionsEnabled) return@LeziPrimaryButton
                        attemptedConfirm = true
                        dismissKeyboard()
                        if (draft.canConfirm()) {
                            onConfirm(draft)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = confirmEnabled,
                )
            }
        }
        Spacer(Modifier.weight(1f))
    }

    clockTarget?.let { target ->
        val initialMillis = when (target) {
            QuickClockTarget.Start -> draft.timestamp
            QuickClockTarget.End -> draft.endTimestamp ?: draft.timestamp
        }
        val isSleep = draft.mode == QuickRecordMode.Sleep
        LeziClockDialDialog(
            title = when {
                target == QuickClockTarget.Start &&
                    draft.sleepAction == SleepDraftAction.SleepDown -> "选择睡下时刻"
                target == QuickClockTarget.End &&
                    draft.sleepAction in setOf(
                        SleepDraftAction.SleepDown,
                        SleepDraftAction.WakeUp,
                    ) -> "选择醒来时刻"
                target == QuickClockTarget.End -> "选择结束时刻"
                else -> "选择记录时刻"
            },
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
                        when {
                            pickedMillis > nowMillis -> {
                                isDirty = true
                                clockError = FUTURE_TIME_WARNING
                            }
                            shiftedEnd != null && shiftedEnd > nowMillis -> {
                                isDirty = true
                                clockError = FUTURE_TIME_WARNING
                            }
                            else -> update(
                                draft.copy(
                                    timestamp = pickedMillis,
                                    endTimestamp = shiftedEnd ?: draft.endTimestamp,
                                ),
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
                        }
                    }
                }
                clockTarget = null
            },
            onDismiss = { clockTarget = null },
        )
    }
}
