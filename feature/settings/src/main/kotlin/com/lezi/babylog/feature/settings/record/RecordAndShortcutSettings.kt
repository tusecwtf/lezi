package com.lezi.babylog.feature.settings.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography

/**
 * Single-page 记录设置: 分项目 + 护理计划/日历 in one dialog.
 * Layout / quick slots / all-items order live only on the record-page 布局编辑态.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecordSettingsDialog(
    settings: SettingsLocal,
    carePlanRemindersEnabled: Boolean,
    onCarePlanRemindersEnabled: (Boolean) -> Unit,
    systemCalendarEnabled: Boolean,
    systemCalendarSummary: String,
    systemCalendarDisclosureSummary: String,
    onConfigureSystemCalendar: () -> Unit,
    onTimerEnabled: (Boolean) -> Unit,
    onRecordAt: (String) -> Unit,
    onInterval: (Int) -> Unit,
    onAmountStep: (Int) -> Unit,
    onFeverAdvice: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记录设置") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                Text(
                    "布局请在记录页长按图标编辑；本页仅非布局参数。",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PerItemSettingsBody(
                    settings = settings,
                    onTimerEnabled = onTimerEnabled,
                    onRecordAt = onRecordAt,
                    onInterval = onInterval,
                    onAmountStep = onAmountStep,
                    onFeverAdvice = onFeverAdvice,
                )
                PlanCalendarSettingsBody(
                    carePlanRemindersEnabled = carePlanRemindersEnabled,
                    onCarePlanRemindersEnabled = onCarePlanRemindersEnabled,
                    systemCalendarEnabled = systemCalendarEnabled,
                    systemCalendarSummary = systemCalendarSummary,
                    systemCalendarDisclosureSummary = systemCalendarDisclosureSummary,
                    onConfigureSystemCalendar = onConfigureSystemCalendar,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

/** Shared per-item knobs body (single surface; was previously duplicated). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PerItemSettingsBody(
    settings: SettingsLocal,
    onTimerEnabled: (Boolean) -> Unit,
    onRecordAt: (String) -> Unit,
    onInterval: (Int) -> Unit,
    onAmountStep: (Int) -> Unit,
    onFeverAdvice: (Boolean) -> Unit,
) {
    Text("分项目", style = LeziTypography.Label)
    Text("母乳", style = LeziTypography.BodyStrong)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("喂奶计时入口")
        Switch(checked = settings.timerEnabled, onCheckedChange = onTimerEnabled)
    }
    Text("记录时刻", style = LeziTypography.Meta)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FilterChip(
            selected = settings.recordAtStartOrEnd == "start",
            onClick = { onRecordAt("start") },
            label = { Text("开始") },
        )
        FilterChip(
            selected = settings.recordAtStartOrEnd == "end",
            onClick = { onRecordAt("end") },
            label = { Text("结束") },
        )
    }
    Text("下次喂奶间隔（分钟）", style = LeziTypography.Meta)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(120, 150, 180, 210, 240).forEach { m ->
            FilterChip(
                selected = settings.nursingIntervalMin == m,
                onClick = { onInterval(m) },
                label = { Text("$m") },
            )
        }
    }
    Text("配方奶 / 挤出乳 / 母乳瓶喂", style = LeziTypography.BodyStrong)
    Text("奶量步进 ml", style = LeziTypography.Meta)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(5, 10, 15).forEach { s ->
            FilterChip(
                selected = settings.amountStepMl == s,
                onClick = { onAmountStep(s) },
                label = { Text("$s") },
            )
        }
    }
    Text("体温", style = LeziTypography.BodyStrong)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("低月龄发热提示")
            Text(
                "仅记录时不足 3 个月且体温 ≥38℃",
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = settings.infantFeverAdviceEnabled,
            onCheckedChange = onFeverAdvice,
        )
    }
}

@Composable
internal fun PlanCalendarSettingsBody(
    carePlanRemindersEnabled: Boolean,
    onCarePlanRemindersEnabled: (Boolean) -> Unit,
    systemCalendarEnabled: Boolean,
    systemCalendarSummary: String,
    systemCalendarDisclosureSummary: String,
    onConfigureSystemCalendar: () -> Unit,
) {
    Text("护理计划与日历", style = LeziTypography.Label)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("家庭护理计划提醒", style = LeziTypography.Body)
            Text(
                "默认开启。仅本机有效，不上传家庭服务器。",
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = carePlanRemindersEnabled,
            onCheckedChange = onCarePlanRemindersEnabled,
        )
    }
    Column(Modifier.fillMaxWidth()) {
        Text("同步到系统日历", style = LeziTypography.Body)
        Text(
            if (systemCalendarEnabled) {
                "已启用 · $systemCalendarSummary · 披露：$systemCalendarDisclosureSummary。"
            } else {
                "未启用。主动配置后才会写入所选可写日历。"
            },
            style = LeziTypography.Meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onConfigureSystemCalendar) {
            Text(if (systemCalendarEnabled) "更改系统日历与披露" else "配置系统日历")
        }
    }
}
