package com.lezi.babylog.feature.log

import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.ui.RecordTypeIcon
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.designsystem.LeziClockDialDialog
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.leziRecordColor
import java.time.Instant
import java.time.ZoneId

private enum class QuickClockTarget {
    Start,
    End,
}

@Composable
internal fun QuickRecordSheet(
    draft: QuickRecordDraft,
    amountStepMl: Int,
    timeStepMin: Int,
    saving: Boolean,
    deleting: Boolean,
    saveError: String?,
    canStartNursingTimer: Boolean,
    onDraftChange: (QuickRecordDraft) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onConfirm: (QuickRecordDraft) -> Unit,
    onStartNursingTimer: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val typeColor = leziRecordColor(draft.type.presentation.colorRole)
    var clockTarget by remember(draft.type, draft.existingRecordId) {
        mutableStateOf<QuickClockTarget?>(null)
    }
    var error by remember(draft.type, draft.existingRecordId) { mutableStateOf<String?>(null) }

    fun update(value: QuickRecordDraft) {
        error = null
        onDraftChange(value)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.96f)
            .navigationBarsPadding()
            .imePadding(),
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
                Text(sheetKicker(draft), style = LeziTypography.Eyebrow)
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
                .padding(horizontal = LeziSpacing.Lg, vertical = LeziSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(LeziSpacing.Md),
        ) {
            SectionLabel("基本信息")
            PurposeFields(
                draft = draft,
                amountStepMl = amountStepMl,
                canStartNursingTimer = canStartNursingTimer,
                onDraftChange = ::update,
                onStartNursingTimer = onStartNursingTimer,
            )

            TimeFields(
                draft = draft,
                zone = zone,
                onOpenStart = {
                    error = null
                    clockTarget = QuickClockTarget.Start
                },
                onOpenEnd = {
                    error = null
                    clockTarget = QuickClockTarget.End
                },
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

            (error ?: saveError)?.let {
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
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            LeziPrimaryButton(
                label = when {
                    saving -> "保存中…"
                    deleting -> "删除中…"
                    else -> draft.confirmLabel()
                },
                onClick = {
                    if (saving || deleting) return@LeziPrimaryButton
                    val validation = draft.validationError()
                    if (validation == null) {
                        onConfirm(draft)
                    } else {
                        error = validation
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !saving && !deleting,
            )
        }
        Spacer(Modifier.weight(1f))
    }

    clockTarget?.let { target ->
        val initialMillis = when (target) {
            QuickClockTarget.Start -> draft.timestamp
            QuickClockTarget.End -> draft.endTimestamp ?: draft.timestamp
        }
        LeziClockDialDialog(
            title = when {
                target == QuickClockTarget.Start &&
                    draft.sleepAction == SleepDraftAction.SleepDown -> "选择睡下时刻"
                target == QuickClockTarget.End &&
                    draft.sleepAction == SleepDraftAction.WakeUp -> "选择醒来时刻"
                target == QuickClockTarget.End -> "选择结束时刻"
                else -> "选择记录时刻"
            },
            value = Instant.ofEpochMilli(initialMillis).atZone(zone),
            minuteStep = timeStepMin,
            onConfirm = { picked ->
                val pickedMillis = picked.toInstant().toEpochMilli()
                val nowMillis = System.currentTimeMillis()
                when (target) {
                    QuickClockTarget.Start -> {
                        val shiftedEnd = shiftStartPreservingDuration(
                            oldStartMillis = draft.timestamp,
                            oldEndMillis = draft.endTimestamp,
                            newStartMillis = pickedMillis,
                        )
                        when {
                            pickedMillis > nowMillis ->
                                error = "记录时刻不能晚于现在"
                            shiftedEnd != null && shiftedEnd > nowMillis ->
                                error = "记录时段不能晚于现在"
                            else -> update(
                                draft.copy(
                                    timestamp = pickedMillis,
                                    endTimestamp = shiftedEnd ?: draft.endTimestamp,
                                ),
                            )
                        }
                    }
                    QuickClockTarget.End -> {
                        when {
                            pickedMillis <= draft.timestamp ->
                                error = "结束时刻必须晚于开始时刻，请点日期选择跨天"
                            pickedMillis > nowMillis ->
                                error = "结束时刻不能晚于现在"
                            else -> update(draft.copy(endTimestamp = pickedMillis))
                        }
                    }
                }
                clockTarget = null
            },
            onDismiss = { clockTarget = null },
        )
    }
}
