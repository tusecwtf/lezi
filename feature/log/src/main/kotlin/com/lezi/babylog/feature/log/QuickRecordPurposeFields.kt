package com.lezi.babylog.feature.log

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.ui.RecordTypeIcon
import com.lezi.babylog.core.ui.peeAmountLabel
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.core.ui.stoolAmountLabel
import com.lezi.babylog.core.ui.stoolColorLabel
import com.lezi.babylog.core.ui.stoolConsistencyLabel
import com.lezi.babylog.designsystem.LeziPeeAmountMark
import com.lezi.babylog.designsystem.LeziShapes
import com.lezi.babylog.designsystem.LeziStoolAmountMark
import com.lezi.babylog.designsystem.LeziStoolColorMark
import com.lezi.babylog.designsystem.LeziStoolConsistencyMark
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.domain.CustomRecordItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun PurposeFields(
    draft: QuickRecordDraft,
    amountStepMl: Int,
    birthdayEpochDay: Long? = null,
    infantFeverAdviceEnabled: Boolean = true,
    customItems: List<CustomRecordItem> = emptyList(),
    canStartNursingTimer: Boolean,
    actionsEnabled: Boolean,
    onDraftChange: (QuickRecordDraft) -> Unit,
    onStartNursingTimer: () -> Unit,
) {
    when (draft.mode) {
        QuickRecordMode.Nursing -> NursingFields(
            draft,
            canStartNursingTimer,
            actionsEnabled,
            onDraftChange,
            onStartNursingTimer,
        )
        QuickRecordMode.Milk -> MilkFields(draft, amountStepMl, onDraftChange)
        QuickRecordMode.Pee -> PeeFields(draft, onDraftChange)
        QuickRecordMode.Poop -> StoolFields(draft, onDraftChange)
        QuickRecordMode.BothDiaper -> {
            PeeFields(draft, onDraftChange)
            StoolFields(draft, onDraftChange)
        }
        QuickRecordMode.Sleep -> SleepFields(draft)
        QuickRecordMode.Temperature -> TemperatureFields(
            draft = draft,
            birthdayEpochDay = birthdayEpochDay,
            adviceEnabled = infantFeverAdviceEnabled,
            onDraftChange = onDraftChange,
        )
        QuickRecordMode.Text -> TextFields(draft, onDraftChange)
        QuickRecordMode.Simple -> SimpleFields(draft.type)
        QuickRecordMode.Symptom -> SymptomFields(draft, onDraftChange)
        QuickRecordMode.Medicine -> MedicineFields(draft, onDraftChange)
        QuickRecordMode.Hospital -> HospitalFields(draft, onDraftChange)
        QuickRecordMode.CustomText -> CustomTextFields(draft, customItems, onDraftChange)
        QuickRecordMode.Measurement -> MeasurementFields(draft, onDraftChange)
        QuickRecordMode.Food -> FoodFields(draft, birthdayEpochDay, onDraftChange)
        QuickRecordMode.Vaccine -> VaccineFields(draft, onDraftChange)
    }
}

@Composable
private fun NursingFields(
    draft: QuickRecordDraft,
    canStartNursingTimer: Boolean,
    actionsEnabled: Boolean,
    onDraftChange: (QuickRecordDraft) -> Unit,
    onStartNursingTimer: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        IntegerField(
            value = draft.leftMin,
            label = "左侧（分）",
            modifier = Modifier.weight(1f),
        ) { onDraftChange(draft.copy(leftMin = it)) }
        IntegerField(
            value = draft.rightMin,
            label = "右侧（分）",
            modifier = Modifier.weight(1f),
        ) { onDraftChange(draft.copy(rightMin = it)) }
    }
    ChoiceStrip(
        label = "顺序",
        choices = listOf("LR" to "左→右", "RL" to "右→左"),
        selected = draft.order,
    ) { onDraftChange(draft.copy(order = it)) }
    IntegerField(
        value = draft.nursingAmountMl,
        label = "估算奶量 ml（可选）",
        modifier = Modifier.fillMaxWidth(),
    ) { onDraftChange(draft.copy(nursingAmountMl = it)) }
    if (canStartNursingTimer) {
        Surface(
            onClick = onStartNursingTimer,
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (actionsEnabled) 1f else 0.45f),
            enabled = actionsEnabled,
            shape = LeziShapes.Sm,
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RecordTypeIcon(RecordType.NURSING)
                Spacer(Modifier.size(10.dp))
                Column {
                    Text("需要实时计时？", style = LeziTypography.BodyStrong)
                    Text(
                        "备注和奶量会带入；点击完成时冻结时长与结束时刻",
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MilkFields(
    draft: QuickRecordDraft,
    requestedStep: Int,
    onDraftChange: (QuickRecordDraft) -> Unit,
) {
    val step = requestedStep.takeIf { it in setOf(5, 10, 15) } ?: 5
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = {
            onDraftChange(draft.copy(amountMl = (draft.amountMl - step).coerceAtLeast(1)))
        }) {
            Text("−$step", style = LeziTypography.Title)
        }
        Text(
            "${draft.amountMl} ml",
            style = LeziTypography.Display,
            modifier = Modifier.padding(horizontal = 22.dp),
        )
        TextButton(onClick = {
            onDraftChange(draft.copy(amountMl = (draft.amountMl + step).coerceAtMost(999)))
        }) {
            Text("+$step", style = LeziTypography.Title)
        }
    }
    val quickAmounts = remember(draft.amountMl, draft.recentAmountMl, step) {
        (draft.recentAmountMl + listOf(
            draft.amountMl - step,
            draft.amountMl,
            draft.amountMl + step,
            draft.amountMl + step * 2,
        )).map { it.coerceIn(1, 999) }.distinct()
    }
    ChoiceStrip(
        label = "快捷奶量",
        choices = quickAmounts.map { it to "${it}ml" },
        selected = draft.amountMl,
    ) { onDraftChange(draft.copy(amountMl = it)) }
    IntegerField(
        value = draft.amountMl.toString(),
        label = "任意奶量 ml（1–999）",
        modifier = Modifier.fillMaxWidth(),
    ) { raw ->
        raw.toIntOrNull()?.takeIf { it in 1..999 }?.let {
            onDraftChange(draft.copy(amountMl = it))
        }
    }
    if (draft.type == RecordType.FORMULA) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IntegerField(
                value = draft.preparedMl,
                label = "冲调量 ml（可选）",
                modifier = Modifier.weight(1f),
            ) { onDraftChange(draft.copy(preparedMl = it)) }
            IntegerField(
                value = draft.durationMin,
                label = "耗时（分，可选）",
                modifier = Modifier.weight(1f),
            ) { onDraftChange(draft.copy(durationMin = it)) }
        }
    }
}

@Composable
private fun PeeFields(
    draft: QuickRecordDraft,
    onDraftChange: (QuickRecordDraft) -> Unit,
) {
    Text("尿量", style = LeziTypography.Label)
    Row(
        Modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        (1..3).forEach { value ->
            val label = peeAmountLabel(value)
            ExcretionChoice(
                label = label,
                semanticLabel = "尿量$label",
                selected = draft.peeAmount == value,
                onClick = { onDraftChange(draft.copy(peeAmount = value)) },
                modifier = Modifier.weight(1f),
            ) {
                LeziPeeAmountMark(value, modifier = Modifier.size(30.dp))
            }
        }
    }
}

@Composable
private fun StoolFields(
    draft: QuickRecordDraft,
    onDraftChange: (QuickRecordDraft) -> Unit,
) {
    Text("便量", style = LeziTypography.Label)
    Row(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        (1..4).forEach { value ->
            val label = stoolAmountLabel(value)
            ExcretionChoice(
                label = label,
                semanticLabel = "便量$label",
                selected = draft.stoolAmount == value,
                onClick = { onDraftChange(draft.copy(stoolAmount = value)) },
                modifier = Modifier.weight(1f),
            ) {
                LeziStoolAmountMark(value, modifier = Modifier.size(28.dp))
            }
        }
    }
    Text("软硬", style = LeziTypography.Label)
    Row(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        (1..4).forEach { value ->
            val label = stoolConsistencyLabel(value)
            ExcretionChoice(
                label = label,
                semanticLabel = "便便软硬$label",
                selected = draft.stoolConsistency == value,
                onClick = { onDraftChange(draft.copy(stoolConsistency = value)) },
                modifier = Modifier.weight(1f),
            ) {
                LeziStoolConsistencyMark(value, modifier = Modifier.size(28.dp))
            }
        }
    }
    Text("颜色", style = LeziTypography.Label)
    Column(
        Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        (0..7).chunked(4).forEach { values ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                values.forEach { value ->
                    val label = stoolColorLabel(value)
                    ExcretionChoice(
                        label = label,
                        semanticLabel = "便便颜色$label",
                        selected = draft.stoolColor == value,
                        onClick = { onDraftChange(draft.copy(stoolColor = value)) },
                        modifier = Modifier.weight(1f),
                    ) {
                        LeziStoolColorMark(value, modifier = Modifier.size(30.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ExcretionChoice(
    label: String,
    semanticLabel: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    mark: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .heightIn(min = 68.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            )
            .semantics {
                contentDescription = semanticLabel
                stateDescription = if (selected) "已选择" else "未选择"
            },
        shape = LeziShapes.Sm,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            mark()
            Spacer(Modifier.height(2.dp))
            Text(label, style = LeziTypography.Meta, maxLines = 1)
        }
    }
}

@Composable
private fun SleepFields(draft: QuickRecordDraft) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SleepActionAnimation(draft.sleepAction ?: SleepDraftAction.Manual)
    }
}

@Composable
private fun SleepActionAnimation(action: SleepDraftAction) {
    val transition = rememberInfiniteTransition(label = "sleep-action")
    val bob by transition.animateFloat(
        initialValue = -2f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sleep-bob",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "wake-pulse",
    )
    val surface = MaterialTheme.colorScheme.surface
    val moon = Color(0xFF8065C8)
    val sun = Color(0xFFF3A93B)
    Canvas(
        modifier = Modifier
            .size(72.dp)
            .semantics {
                contentDescription = when (action) {
                    SleepDraftAction.SleepDown -> "月亮轻轻摇动，准备睡下"
                    SleepDraftAction.WakeUp -> "太阳轻轻闪动，准备醒来"
                    SleepDraftAction.Manual -> "月亮图标，补记睡眠"
                }
            }
            .graphicsLayer {
                translationY = if (action == SleepDraftAction.WakeUp) 0f else bob
                scaleX = if (action == SleepDraftAction.WakeUp) pulse else 1f
                scaleY = if (action == SleepDraftAction.WakeUp) pulse else 1f
            },
    ) {
        drawCircle(
            color = if (action == SleepDraftAction.WakeUp) {
                sun.copy(alpha = 0.14f)
            } else {
                moon.copy(alpha = 0.14f)
            },
        )
        if (action == SleepDraftAction.WakeUp) {
            val center = this.center
            drawCircle(sun, radius = size.minDimension * 0.17f, center = center)
            repeat(8) { index ->
                val angle = index * Math.PI.toFloat() / 4f
                val inner = size.minDimension * 0.27f
                val outer = size.minDimension * 0.37f
                drawLine(
                    color = sun,
                    start = center + androidx.compose.ui.geometry.Offset(
                        cos(angle) * inner,
                        sin(angle) * inner,
                    ),
                    end = center + androidx.compose.ui.geometry.Offset(
                        cos(angle) * outer,
                        sin(angle) * outer,
                    ),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        } else {
            val center = this.center
            drawCircle(moon, radius = size.minDimension * 0.23f, center = center)
            drawCircle(
                surface,
                radius = size.minDimension * 0.21f,
                center = center + androidx.compose.ui.geometry.Offset(8.dp.toPx(), -5.dp.toPx()),
            )
            drawCircle(
                Color(0xFFF5C451),
                radius = 2.5.dp.toPx(),
                center = center + androidx.compose.ui.geometry.Offset(18.dp.toPx(), -16.dp.toPx()),
            )
        }
    }
}

@Composable
private fun TemperatureFields(
    draft: QuickRecordDraft,
    birthdayEpochDay: Long?,
    adviceEnabled: Boolean,
    onDraftChange: (QuickRecordDraft) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DecimalField(
            value = draft.temperature,
            label = "体温",
            modifier = Modifier.weight(1f),
        ) { onDraftChange(draft.copy(temperature = it)) }
        Column(Modifier.weight(1f)) {
            ChoiceStrip(
                label = "单位",
                choices = listOf(
                    TemperatureUnit.Celsius to "℃",
                    TemperatureUnit.Fahrenheit to "℉",
                ),
                selected = draft.temperatureUnit,
            ) { unit ->
                val old = draft.temperature.toDoubleOrNull()
                val converted = when {
                    old == null -> if (unit == TemperatureUnit.Celsius) 36.5 else 97.7
                    draft.temperatureUnit == unit -> old
                    unit == TemperatureUnit.Fahrenheit -> old * 9.0 / 5.0 + 32.0
                    else -> (old - 32.0) * 5.0 / 9.0
                }
                onDraftChange(
                    draft.copy(
                        temperatureUnit = unit,
                        temperature = "%.1f".format(java.util.Locale.US, converted),
                    ),
                )
            }
        }
    }
    val celsius = draft.temperature.toDoubleOrNull()?.let {
        if (draft.temperatureUnit == TemperatureUnit.Fahrenheit) {
            (it - 32.0) * 5.0 / 9.0
        } else {
            it
        }
    }
    if (
        celsius != null &&
        shouldShowInfantFeverAdvice(
            birthdayEpochDay = birthdayEpochDay,
            recordTimestamp = draft.timestamp,
            celsius = celsius,
            enabled = adviceEnabled,
        )
    ) {
        Surface(
            shape = LeziShapes.Sm,
            color = MaterialTheme.colorScheme.errorContainer,
        ) {
            Text(
                "低月龄发热请及时就医。本提示仅供参考，不构成医疗诊断。",
                modifier = Modifier.padding(12.dp),
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

internal fun shouldShowInfantFeverAdvice(
    birthdayEpochDay: Long?,
    recordTimestamp: Long,
    celsius: Double,
    enabled: Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
): Boolean {
    if (!enabled || birthdayEpochDay == null || celsius < 38.0) return false
    val birthday = LocalDate.ofEpochDay(birthdayEpochDay)
    val recordDate = Instant.ofEpochMilli(recordTimestamp).atZone(zone).toLocalDate()
    return !recordDate.isBefore(birthday) && recordDate.isBefore(birthday.plusMonths(3))
}

@Composable
private fun TextFields(
    draft: QuickRecordDraft,
    onDraftChange: (QuickRecordDraft) -> Unit,
) {
    OutlinedTextField(
        value = draft.body,
        onValueChange = { onDraftChange(draft.copy(body = it.take(800))) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(if (draft.type == RecordType.DIARY) "日记正文" else "内容") },
        minLines = if (draft.type == RecordType.DIARY) 4 else 2,
        maxLines = 7,
        supportingText = { Text("${draft.body.length}/800") },
    )
}

@Composable
private fun SimpleFields(type: RecordType) {
    Text(
        when (type) {
            RecordType.BATH -> "记录本次洗澡，可在备注中补充水温或皮肤状态。"
            else -> "确认本次记录，可在备注中补充细节。"
        },
        style = LeziTypography.Body,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SymptomFields(
    draft: QuickRecordDraft,
    onDraftChange: (QuickRecordDraft) -> Unit,
) {
    ChoiceStrip(
        label = "程度",
        choices = listOf(1 to "轻微", 2 to "一般", 3 to "明显"),
        selected = draft.severity,
    ) { onDraftChange(draft.copy(severity = it)) }
    OutlinedTextField(
        value = draft.description,
        onValueChange = { onDraftChange(draft.copy(description = it.take(200))) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("情况描述（可选）") },
        minLines = 2,
    )
}

@Composable
private fun MedicineFields(
    draft: QuickRecordDraft,
    onDraftChange: (QuickRecordDraft) -> Unit,
) {
    OutlinedTextField(
        value = draft.medicineName,
        onValueChange = { onDraftChange(draft.copy(medicineName = it.take(50))) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("药品名称") },
        singleLine = true,
    )
    OutlinedTextField(
        value = draft.medicineDose,
        onValueChange = { onDraftChange(draft.copy(medicineDose = it.take(30))) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("剂量（可选）") },
        placeholder = { Text("例如 0.5 ml") },
        singleLine = true,
    )
}

@Composable
private fun HospitalFields(
    draft: QuickRecordDraft,
    onDraftChange: (QuickRecordDraft) -> Unit,
) {
    OutlinedTextField(
        value = draft.hospitalReason,
        onValueChange = { onDraftChange(draft.copy(hospitalReason = it.take(100))) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("就诊原因") },
        singleLine = true,
    )
    OutlinedTextField(
        value = draft.hospitalAdvice,
        onValueChange = { onDraftChange(draft.copy(hospitalAdvice = it.take(300))) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("医嘱（可选）") },
        minLines = 2,
    )
}
