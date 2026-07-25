package com.lezi.babylog.feature.log

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.designsystem.LeziShapes
import com.lezi.babylog.designsystem.LeziTypography
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun TimeFields(
    draft: QuickRecordDraft,
    zone: ZoneId,
    onOpenStart: () -> Unit,
    onOpenEnd: () -> Unit,
    /** SleepDown only: true → set end=now; false → clear end. */
    onToggleRecordWake: ((Boolean) -> Unit)? = null,
    accentColor: Color? = null,
    intervalPreview: IntervalDurationPreview? = null,
) {
    val container = accentColor?.copy(alpha = 0.18f)
        ?: MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    val accent = accentColor ?: MaterialTheme.colorScheme.primary
    SectionLabel(
        when (draft.sleepAction) {
            SleepDraftAction.SleepDown -> "睡下时间"
            SleepDraftAction.WakeUp -> "睡眠时间"
            SleepDraftAction.Manual -> "起止时间"
            null -> if (draft.mode == QuickRecordMode.Interval) "起止时间" else "记录时间"
        },
    )
    when {
        draft.sleepAction == SleepDraftAction.WakeUp -> {
            TimeReadOnly("睡下", draft.timestamp, zone)
            val end = draft.endTimestamp
            if (end == null) {
                EmptyTimeButton("选择醒来时刻", onOpenEnd)
            } else {
                TimeButton(
                    label = "醒来",
                    millis = end,
                    zone = zone,
                    onClick = onOpenEnd,
                    containerColor = container,
                    accentColor = accent,
                )
            }
        }
        draft.sleepAction == SleepDraftAction.SleepDown -> {
            TimeButton(
                label = "睡下",
                millis = draft.timestamp,
                zone = zone,
                onClick = onOpenStart,
                containerColor = container,
                accentColor = accent,
            )
            val recordWake = draft.endTimestamp != null
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = if (recordWake) {
                            "同时记醒来，已打开"
                        } else {
                            "同时记醒来，已关闭"
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("同时记醒来", style = LeziTypography.BodyStrong)
                Switch(
                    checked = recordWake,
                    onCheckedChange = { checked -> onToggleRecordWake?.invoke(checked) },
                )
            }
            if (recordWake) {
                TimeButton(
                    label = "醒来",
                    millis = draft.endTimestamp!!,
                    zone = zone,
                    onClick = onOpenEnd,
                    containerColor = container,
                    accentColor = accent,
                )
            }
        }
        draft.sleepAction == SleepDraftAction.Manual ||
            draft.mode == QuickRecordMode.Interval -> {
            TimeButton(
                label = "开始",
                millis = draft.timestamp,
                zone = zone,
                onClick = onOpenStart,
                containerColor = container,
                accentColor = accent,
            )
            if (draft.endTimestamp == null) {
                EmptyTimeButton(
                    prompt = if (draft.mode == QuickRecordMode.Sleep) {
                        "选择醒来时刻"
                    } else {
                        "选择结束时刻"
                    },
                    onClick = onOpenEnd,
                )
            } else {
                TimeButton(
                    label = "结束",
                    millis = draft.endTimestamp,
                    zone = zone,
                    onClick = onOpenEnd,
                    containerColor = container,
                    accentColor = accent,
                )
            }
        }
        else -> TimeButton(
            label = "记录",
            millis = draft.timestamp,
            zone = zone,
            onClick = onOpenStart,
            containerColor = container,
            accentColor = accent,
        )
    }
    intervalPreview?.let { preview ->
        Text(
            text = preview.text,
            modifier = Modifier.padding(horizontal = 4.dp),
            style = LeziTypography.Meta,
            color = when (preview) {
                is IntervalDurationPreview.Duration ->
                    MaterialTheme.colorScheme.onSurfaceVariant
                is IntervalDurationPreview.Warning ->
                    MaterialTheme.colorScheme.error
            },
        )
    }
}

@Composable
private fun EmptyTimeButton(prompt: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = LeziShapes.Sm,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Text(
            prompt,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            style = LeziTypography.BodyStrong,
        )
    }
}

@Composable
private fun TimeButton(
    label: String,
    millis: Long,
    zone: ZoneId,
    onClick: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
    accentColor: Color = MaterialTheme.colorScheme.primary,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$label，${formatRecordTime(millis, zone)}，选择时间" },
        shape = LeziShapes.Sm,
        color = containerColor,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(label, style = LeziTypography.Meta)
                Text(formatRecordTime(millis, zone), style = LeziTypography.BodyStrong)
            }
            Text("选择时间", style = LeziTypography.Label, color = accentColor)
        }
    }
}

@Composable
private fun TimeReadOnly(label: String, millis: Long, zone: ZoneId) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = LeziShapes.Sm,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Text(label, style = LeziTypography.Meta)
            Text(formatRecordTime(millis, zone), style = LeziTypography.BodyStrong)
        }
    }
}

@Composable
internal fun <T> ChoiceStrip(
    label: String,
    choices: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = LeziTypography.Label)
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            choices.forEach { (value, title) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelected(value) },
                    label = { Text(title) },
                )
            }
        }
    }
}

@Composable
internal fun IntegerField(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(4)) },
        modifier = modifier,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
}

@Composable
internal fun DecimalField(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { candidate ->
            val filtered = candidate.filter { it.isDigit() || it == '.' }
            if (filtered.count { it == '.' } <= 1) {
                onValueChange(filtered.take(7))
            }
        },
        modifier = modifier,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
    )
}

@Composable
internal fun SectionLabel(label: String) {
    Text(
        label,
        style = LeziTypography.Label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

internal fun sheetKicker(draft: QuickRecordDraft): String = when {
    draft.isEditing -> "编辑记录"
    draft.sleepAction == SleepDraftAction.SleepDown -> "准备休息"
    draft.sleepAction == SleepDraftAction.WakeUp -> "睡眠进行中"
    draft.sleepAction == SleepDraftAction.Manual -> "补记睡眠"
    else -> "补充信息后确认保存"
}

internal fun sheetTitle(draft: QuickRecordDraft): String = when {
    draft.isEditing -> draft.type.presentation.label
    draft.sleepAction == SleepDraftAction.SleepDown -> "睡下"
    draft.sleepAction == SleepDraftAction.WakeUp -> "醒来"
    else -> draft.type.presentation.label
}

internal fun notePlaceholder(type: RecordType): String = when (type) {
    RecordType.NURSING, RecordType.FORMULA, RecordType.PUMPED_FEED -> "例如：拍嗝顺利"
    RecordType.PEE, RecordType.POOP, RecordType.BOTH_DIAPER -> "例如：皮肤状态正常"
    RecordType.SLEEP -> "例如：白噪音陪睡"
    RecordType.TEMPERATURE -> "例如：刚睡醒"
    RecordType.BATH -> "例如：水温合适"
    RecordType.WALK -> "例如：小区花园"
    RecordType.MEDICINE -> "例如：饭后服用"
    RecordType.HOSPITAL -> "补充医生建议或复诊安排"
    else -> "补充这条记录"
}

private fun formatRecordTime(millis: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(millis)
        .atZone(zone)
        .format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
