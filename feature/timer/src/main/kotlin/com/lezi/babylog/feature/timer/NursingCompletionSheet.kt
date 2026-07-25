package com.lezi.babylog.feature.timer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lezi.babylog.designsystem.LeziClockDialDialog
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziShapes
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.dismissKeyboardOnTap
import com.lezi.babylog.designsystem.rememberDismissKeyboard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    var error by remember(draft.capturedAt) { mutableStateOf<String?>(null) }

    fun update(next: NursingCompletionDraft) {
        error = null
        onDraftChange(next)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .dismissKeyboardOnTap(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = LeziSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("母乳 · 计时完成", style = LeziTypography.Eyebrow)
            Text("确认母乳记录", style = LeziTypography.Title)
            Text(
                "左右时长已按点击“完成”时冻结；取消后原计时继续。",
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 530.dp)
                .verticalScroll(rememberScrollState())
                .dismissKeyboardOnTap()
                .padding(horizontal = LeziSpacing.Lg, vertical = LeziSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(LeziSpacing.Md),
        ) {
            SectionLabel("基本信息")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IntegerField(
                    value = draft.leftMinutes,
                    label = "左侧（分钟）",
                    modifier = Modifier.weight(1f),
                    maxDigits = 4,
                    onValueChange = { update(draft.copy(leftMinutes = it)) },
                )
                IntegerField(
                    value = draft.rightMinutes,
                    label = "右侧（分钟）",
                    modifier = Modifier.weight(1f),
                    maxDigits = 4,
                    onValueChange = { update(draft.copy(rightMinutes = it)) },
                )
            }
            ChoiceStrip(
                label = "喂养顺序",
                choices = listOf(
                    "L" to "仅左",
                    "LR" to "先左后右",
                    "RL" to "先右后左",
                    "R" to "仅右",
                ),
                selected = draft.order,
                onSelected = { update(draft.copy(order = it)) },
            )
            IntegerField(
                value = draft.amountMl,
                label = "奶量 ml（可选）",
                modifier = Modifier.fillMaxWidth(),
                maxDigits = 3,
                onValueChange = { update(draft.copy(amountMl = it)) },
            )

            SectionLabel("时间")
            ReadOnlyTimeField("开始时刻", draft.startedAt, zone)
            Surface(
                onClick = {
                    dismissKeyboard()
                    error = null
                    showClock = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription =
                            "结束时刻，${formatTime(draft.endedAt, zone)}，选择时间"
                    },
                shape = LeziShapes.Sm,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
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

            SectionLabel("备注")
            OutlinedTextField(
                value = draft.note,
                onValueChange = { update(draft.copy(note = it.take(200))) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("备注（可选）") },
                placeholder = { Text("例如含接、吐奶或宝宝状态") },
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
                onClick = {
                    dismissKeyboard()
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
            )
            LeziPrimaryButton(
                label = if (saving) "保存中…" else "确认记录",
                onClick = {
                    if (saving) return@LeziPrimaryButton
                    dismissKeyboard()
                    val validation = draft.validationError(System.currentTimeMillis())
                    if (validation == null) {
                        onConfirm(draft)
                    } else {
                        error = validation
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !saving,
            )
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
                when {
                    pickedAt < draft.startedAt -> error = "结束时刻不能早于开始时刻"
                    pickedAt > now -> error = "结束时刻不能晚于现在"
                    else -> update(draft.copy(endedAt = pickedAt))
                }
                showClock = false
            },
            onDismiss = { showClock = false },
        )
    }
}

@Composable
private fun ReadOnlyTimeField(label: String, value: Long, zone: ZoneId) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = LeziShapes.Sm,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Text(label, style = LeziTypography.Meta)
            Text(formatTime(value, zone), style = LeziTypography.BodyStrong)
        }
    }
}

@Composable
private fun ChoiceStrip(
    label: String,
    choices: List<Pair<String, String>>,
    selected: String,
    onSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = LeziTypography.Label)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            choices.forEach { (value, title) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelected(value) },
                    label = { Text(title) },
                )
            }
        }
    }
}

@Composable
private fun IntegerField(
    value: String,
    label: String,
    modifier: Modifier,
    maxDigits: Int,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(maxDigits)) },
        modifier = modifier,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
}

@Composable
private fun SectionLabel(label: String) {
    Text(
        label,
        style = LeziTypography.Eyebrow,
        color = MaterialTheme.colorScheme.primary,
    )
}

private fun formatTime(value: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(value)
        .atZone(zone)
        .format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
