package com.lezi.babylog.feature.timer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.lezi.babylog.designsystem.LeziAlphas
import com.lezi.babylog.designsystem.LeziConfirmAppearance
import com.lezi.babylog.designsystem.LeziConfirmChromeEvent
import com.lezi.babylog.designsystem.LeziConfirmChromeState
import com.lezi.babylog.designsystem.LeziConfirmReasonCard
import com.lezi.babylog.designsystem.LeziClockDialDialog
import com.lezi.babylog.designsystem.LeziNursingConfirmFields
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziPrimaryButtonMode
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziSectionLabel
import com.lezi.babylog.designsystem.LeziShapes
import com.lezi.babylog.designsystem.LeziSheetContentMax
import com.lezi.babylog.designsystem.LeziThemeExt
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.dismissKeyboardOnTap
import com.lezi.babylog.designsystem.leziConfirmAppearance
import com.lezi.babylog.designsystem.reduceLeziConfirmChrome
import com.lezi.babylog.designsystem.rememberDismissKeyboard
import com.lezi.babylog.designsystem.LeziTextField
import com.lezi.babylog.core.model.RecordTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NursingCompletionSheet(
    draft: NursingCompletionDraft,
    saving: Boolean,
    saveError: String?,
    timeStepMin: Int,
    timePickerStyle: String = "dropdown",
    preferredHand: String = "right",
    onDraftChange: (NursingCompletionDraft) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (NursingCompletionDraft) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val dismissKeyboard = rememberDismissKeyboard()
    var showClock by remember(draft.capturedAt) { mutableStateOf(false) }
    var confirmChrome by remember(draft.capturedAt) {
        mutableStateOf(LeziConfirmChromeState<String>())
    }
    val nowMillis = System.currentTimeMillis()
    val validation = draft.validationError(nowMillis)
    val canConfirm = validation == null
    val appearance = leziConfirmAppearance(busy = saving, canConfirm = canConfirm)
    val buttonMode = when (appearance) {
        LeziConfirmAppearance.Enabled -> LeziPrimaryButtonMode.Enabled
        LeziConfirmAppearance.ExplainedDisabled -> LeziPrimaryButtonMode.ExplainedDisabled
        LeziConfirmAppearance.BusyDisabled -> LeziPrimaryButtonMode.Disabled
    }
    val nursingIssue = draft.confirmInput().validationIssue()
    val highlightedNursingField = nursingIssue?.field.takeIf {
        confirmChrome.reasonVisible && confirmChrome.shownReason == nursingIssue?.message
    }

    LaunchedEffect(saving) {
        confirmChrome = reduceLeziConfirmChrome(
            confirmChrome,
            LeziConfirmChromeEvent.BusyChanged(saving),
        )
    }

    fun update(next: NursingCompletionDraft) {
        confirmChrome = reduceLeziConfirmChrome(
            confirmChrome,
            LeziConfirmChromeEvent.DraftEdited,
        )
        onDraftChange(next)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.96f)
            .navigationBarsPadding()
            .imePadding()
            .dismissKeyboardOnTap(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = LeziSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xxs),
        ) {
            Text("母乳 · 计时完成", style = LeziTypography.Eyebrow)
            Text("确认母乳记录", style = LeziTypography.Title)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xxs),
            ) {
                NursingStatusBadge("时长已冻结")
                NursingStatusBadge("取消后继续计时")
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
            LeziNursingConfirmFields(
                input = draft.confirmInput(),
                onInputChange = { update(draft.withConfirmInput(it)) },
                highlightedField = highlightedNursingField,
            )

            LeziSectionLabel("时间")
            ReadOnlyTimeField("开始时刻", draft.startedAt, zone)
            Surface(
                onClick = {
                    dismissKeyboard()
                    confirmChrome = reduceLeziConfirmChrome(
                        confirmChrome,
                        LeziConfirmChromeEvent.DraftEdited,
                    )
                    showClock = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription =
                            "结束时刻，${formatTime(draft.endedAt, zone)}，选择时间"
                    },
                shape = LeziThemeExt.controlShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = LeziAlphas.Muted),
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = LeziSpacing.CardPad,
                        vertical = LeziSpacing.Sm,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("结束时刻", style = LeziTypography.Meta)
                        Text(formatTime(draft.endedAt, zone), style = LeziTypography.BodyStrong)
                    }
                    Text(
                        "选择时间",
                        style = LeziTypography.Label,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            LeziSectionLabel("备注")
            LeziTextField(
                value = draft.note,
                onValueChange = { update(draft.copy(note = it.take(200))) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                label = { Text("备注（可选）") },
                placeholder = { Text("例如含接、吐奶或宝宝状态") },
                minLines = 2,
                maxLines = 4,
                supportingText = { Text("${draft.note.length}/200") },
            )

            saveError?.let {
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
            val reason = confirmChrome.shownReason.takeIf { confirmChrome.reasonVisible }
            AnimatedVisibility(visible = reason != null) {
                reason?.let { LeziConfirmReasonCard(it) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                LeziSecondaryButton(
                    label = "取消",
                    onClick = {
                        dismissKeyboard()
                        confirmChrome = reduceLeziConfirmChrome(
                            confirmChrome,
                            LeziConfirmChromeEvent.Dismissed,
                        )
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                )
                LeziPrimaryButton(
                    label = if (saving) "保存中…" else "确认记录",
                    onClick = {
                        when (appearance) {
                            LeziConfirmAppearance.BusyDisabled -> Unit
                            LeziConfirmAppearance.Enabled -> {
                                dismissKeyboard()
                                if (draft.validationError(System.currentTimeMillis()) == null) {
                                    onConfirm(draft)
                                }
                            }
                            LeziConfirmAppearance.ExplainedDisabled -> {
                                dismissKeyboard()
                                confirmChrome = reduceLeziConfirmChrome(
                                    confirmChrome,
                                    LeziConfirmChromeEvent.GreyConfirmTapped(validation),
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription =
                                if (appearance == LeziConfirmAppearance.ExplainedDisabled) {
                                    validation ?: "确认记录"
                                } else if (saving) {
                                    "保存中…"
                                } else {
                                    "确认记录"
                                }
                        },
                    mode = buttonMode,
                )
            }
        }
    }

    if (showClock) {
        LeziClockDialDialog(
            title = "选择结束时刻",
            value = Instant.ofEpochMilli(draft.endedAt).atZone(zone),
            minuteStep = timeStepMin,
            timePickerStyle = timePickerStyle,
            preferredHand = preferredHand,
            onConfirm = { picked ->
                val pickedAt = picked.toInstant().toEpochMilli()
                val now = System.currentTimeMillis()
                val skew = draft.actualTimeMaxFutureSkewMillis()
                val futureError = RecordTime.pointError(pickedAt, now, skew)
                when {
                    pickedAt < draft.startedAt -> {
                        confirmChrome = LeziConfirmChromeState(
                            reasonVisible = true,
                            shownReason = "结束时刻不能早于开始时刻",
                        )
                    }
                    futureError != null -> {
                        confirmChrome = LeziConfirmChromeState(
                            reasonVisible = true,
                            shownReason = futureError,
                        )
                    }
                    else -> update(draft.copy(endedAt = pickedAt))
                }
                showClock = false
            },
            onDismiss = { showClock = false },
        )
    }
}

@Composable
private fun NursingStatusBadge(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = LeziShapes.Pill,
    ) {
        Text(
            label,
            style = LeziTypography.Meta,
            modifier = Modifier.padding(
                horizontal = LeziSpacing.Xs,
                vertical = LeziSpacing.Xxs,
            ),
        )
    }
}

@Composable
private fun ReadOnlyTimeField(label: String, value: Long, zone: ZoneId) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = LeziThemeExt.controlShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = LeziAlphas.Muted),
    ) {
        Column(
            Modifier.padding(
                horizontal = LeziSpacing.CardPad,
                vertical = LeziSpacing.Sm,
            ),
        ) {
            Text(label, style = LeziTypography.Meta)
            Text(formatTime(value, zone), style = LeziTypography.BodyStrong)
        }
    }
}

private fun formatTime(value: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(value)
        .atZone(zone)
        .format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
