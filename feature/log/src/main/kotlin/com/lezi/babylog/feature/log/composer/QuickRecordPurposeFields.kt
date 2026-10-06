package com.lezi.babylog.feature.log.composer
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.model.NursingConfirmField
import com.lezi.babylog.core.model.NursingConfirmInput
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.ui.RecordTypeIcon
import com.lezi.babylog.core.ui.peeAmountLabel
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.core.ui.stoolAmountLabel
import com.lezi.babylog.core.ui.stoolColorLabel
import com.lezi.babylog.core.ui.stoolConsistencyLabel
import com.lezi.babylog.designsystem.LeziAlphas
import com.lezi.babylog.designsystem.LeziPeeAmountMark
import com.lezi.babylog.designsystem.LeziNursingConfirmFields
import com.lezi.babylog.designsystem.LeziShapes
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziThemeExt
import com.lezi.babylog.designsystem.LeziStoolAmountMark
import com.lezi.babylog.designsystem.LeziStoolColorMark
import com.lezi.babylog.designsystem.LeziStoolConsistencyMark
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.LeziTextButton
import com.lezi.babylog.designsystem.LeziTextField
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

@Composable
internal fun PurposeFields(
    draft: QuickRecordDraft,
    amountStepMl: Int,
    birthdayEpochDay: Long? = null,
    infantFeverAdviceEnabled: Boolean = true,
    canStartNursingTimer: Boolean,
    actionsEnabled: Boolean,
    onDraftChange: (QuickRecordDraft) -> Unit,
    onStartNursingTimer: () -> Unit,
    highlightedField: ComposerInvalidField? = null,
    fieldFocusRequester: FocusRequester? = null,
    sleepPolicy: SleepComposerPolicy? = null,
) {
    when (draft.mode) {
        QuickRecordMode.Nursing -> NursingFields(
            draft,
            canStartNursingTimer,
            actionsEnabled,
            onDraftChange,
            onStartNursingTimer,
            highlightedField = highlightedField,
        )
        QuickRecordMode.Milk -> MilkFields(
            draft,
            amountStepMl,
            onDraftChange,
            highlightedField = highlightedField,
            fieldFocusRequester = fieldFocusRequester,
            enabled = actionsEnabled,
        )
        QuickRecordMode.Pee -> PeeFields(
            draft,
            onDraftChange,
            highlightedField = highlightedField,
            enabled = actionsEnabled,
        )
        QuickRecordMode.Poop -> StoolFields(
            draft,
            onDraftChange,
            highlightedField = highlightedField,
            enabled = actionsEnabled,
        )
        QuickRecordMode.BothDiaper -> {
            PeeFields(
                draft,
                onDraftChange,
                highlightedField = highlightedField,
                enabled = actionsEnabled,
            )
            StoolFields(
                draft,
                onDraftChange,
                highlightedField = highlightedField,
                enabled = actionsEnabled,
            )
        }
        QuickRecordMode.Sleep -> SleepFields(
            draft = draft,
            policy = sleepPolicy ?: sleepComposerPolicy(draft),
        )
        QuickRecordMode.Temperature -> TemperatureFields(
            draft = draft,
            birthdayEpochDay = birthdayEpochDay,
            adviceEnabled = infantFeverAdviceEnabled,
            onDraftChange = onDraftChange,
            highlightedField = highlightedField,
            fieldFocusRequester = fieldFocusRequester,
            enabled = actionsEnabled,
        )
        QuickRecordMode.Text -> TextFields(
            draft,
            onDraftChange,
            highlightedField = highlightedField,
            fieldFocusRequester = fieldFocusRequester,
            enabled = actionsEnabled,
        )
        QuickRecordMode.Simple -> Unit
        QuickRecordMode.Symptom -> SymptomFields(
            draft,
            onDraftChange,
            highlightedField = highlightedField,
            enabled = actionsEnabled,
        )
        QuickRecordMode.Medicine -> MedicineFields(
            draft,
            onDraftChange,
            highlightedField = highlightedField,
            fieldFocusRequester = fieldFocusRequester,
            enabled = actionsEnabled,
        )
        QuickRecordMode.Hospital -> HospitalFields(
            draft,
            onDraftChange,
            highlightedField = highlightedField,
            fieldFocusRequester = fieldFocusRequester,
            enabled = actionsEnabled,
        )
        QuickRecordMode.CustomText -> CustomTextFields(
            draft,
            onDraftChange,
            highlightedField = highlightedField,
            fieldFocusRequester = fieldFocusRequester,
            enabled = actionsEnabled,
        )
        QuickRecordMode.Measurement -> MeasurementFields(
            draft,
            onDraftChange,
            highlightedField = highlightedField,
            fieldFocusRequester = fieldFocusRequester,
            enabled = actionsEnabled,
        )
        QuickRecordMode.Food -> FoodFields(
            draft,
            birthdayEpochDay,
            onDraftChange,
            highlightedField = highlightedField,
            fieldFocusRequester = fieldFocusRequester,
            enabled = actionsEnabled,
        )
        QuickRecordMode.Vaccine -> VaccineFields(
            draft,
            onDraftChange,
            highlightedField = highlightedField,
            fieldFocusRequester = fieldFocusRequester,
            enabled = actionsEnabled,
        )
    }
}

@Composable
private fun NursingFields(
    draft: QuickRecordDraft,
    canStartNursingTimer: Boolean,
    actionsEnabled: Boolean,
    onDraftChange: (QuickRecordDraft) -> Unit,
    onStartNursingTimer: () -> Unit,
    highlightedField: ComposerInvalidField? = null,
) {
    val input = NursingConfirmInput(
        leftMinutes = draft.leftMin,
        rightMinutes = draft.rightMin,
        order = draft.order,
        amountMl = draft.nursingAmountMl,
    )
    val nursingHighlight = when (highlightedField) {
        ComposerInvalidField.NursingDuration -> NursingConfirmField.Duration
        ComposerInvalidField.NursingOrder -> NursingConfirmField.Order
        ComposerInvalidField.NursingAmount -> NursingConfirmField.Amount
        else -> null
    }
    LeziNursingConfirmFields(
        input = input,
        enabled = actionsEnabled,
        onInputChange = { next ->
            onDraftChange(
                draft.copy(
                    leftMin = next.leftMinutes,
                    rightMin = next.rightMinutes,
                    order = next.order,
                    nursingAmountMl = next.amountMl,
                ),
            )
        },
        highlightedField = nursingHighlight,
    )
    if (canStartNursingTimer) {
        Surface(
            onClick = onStartNursingTimer,
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (actionsEnabled) 1f else LeziAlphas.Disabled),
            enabled = actionsEnabled,
            shape = LeziThemeExt.controlShape,
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RecordTypeIcon(RecordType.NURSING)
                Spacer(Modifier.size(10.dp))
                Column {
                    Text("打开左右计时器", style = LeziTypography.BodyStrong)
                    Text(
                        "沿用当前奶量与备注",
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
    highlightedField: ComposerInvalidField? = null,
    fieldFocusRequester: FocusRequester? = null,
    enabled: Boolean = true,
) {
    val step = requestedStep.takeIf { it in setOf(5, 10, 15) } ?: 5
    val amountError = highlightedField == ComposerInvalidField.MilkAmount
    val preparedError = highlightedField == ComposerInvalidField.MilkPrepared
    val durationError = highlightedField == ComposerInvalidField.MilkDuration
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LeziTextButton(label = "−$step", onClick = {
                onDraftChange(draft.copy(amountMl = (draft.amountMl - step).coerceAtLeast(1)))
            }, enabled = enabled, modifier = Modifier.heightIn(min = LeziSpacing.Touch))
        Text(
            "${draft.amountMl} ml",
            style = LeziThemeExt.typography.Display,
            modifier = Modifier.padding(horizontal = 22.dp),
            color = if (amountError) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
        LeziTextButton(label = "+$step", onClick = {
                onDraftChange(draft.copy(amountMl = (draft.amountMl + step).coerceAtMost(999)))
            }, enabled = enabled, modifier = Modifier.heightIn(min = LeziSpacing.Touch))
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
        enabled = enabled,
    ) { onDraftChange(draft.copy(amountMl = it)) }
    IntegerField(
        value = draft.amountMl.toString(),
        label = "任意奶量 ml（1–999）",
        modifier = Modifier.fillMaxWidth(),
        isError = amountError,
        focusRequester = fieldFocusRequester.takeIf { amountError },
        enabled = enabled,
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
                isError = preparedError,
                focusRequester = fieldFocusRequester.takeIf { preparedError },
                enabled = enabled,
            ) { onDraftChange(draft.copy(preparedMl = it)) }
            IntegerField(
                value = draft.durationMin,
                label = "耗时（分，可选）",
                modifier = Modifier.weight(1f),
                isError = durationError,
                focusRequester = fieldFocusRequester.takeIf { durationError },
                enabled = enabled,
            ) { onDraftChange(draft.copy(durationMin = it)) }
        }
    }
}

@Composable
private fun PeeFields(
    draft: QuickRecordDraft,
    onDraftChange: (QuickRecordDraft) -> Unit,
    highlightedField: ComposerInvalidField? = null,
    enabled: Boolean = true,
) {
    val peeError = highlightedField == ComposerInvalidField.PeeAmount
    Text(
        "尿量",
        style = LeziTypography.Label,
        color = if (peeError) MaterialTheme.colorScheme.error else Color.Unspecified,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
    ) {
        (1..3).forEach { value ->
            val label = peeAmountLabel(value)
            ExcretionChoice(
                label = label,
                semanticLabel = "尿量$label",
                selected = draft.peeAmount == value,
                onClick = { onDraftChange(draft.copy(peeAmount = value)) },
                enabled = enabled,
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
    highlightedField: ComposerInvalidField? = null,
    enabled: Boolean = true,
) {
    val amountError = highlightedField == ComposerInvalidField.StoolAmount
    val consistencyError = highlightedField == ComposerInvalidField.StoolConsistency
    val colorError = highlightedField == ComposerInvalidField.StoolColor
    Text(
        "便量",
        style = LeziTypography.Label,
        color = if (amountError) MaterialTheme.colorScheme.error else Color.Unspecified,
    )
    Row(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
    ) {
        (1..4).forEach { value ->
            val label = stoolAmountLabel(value)
            ExcretionChoice(
                label = label,
                semanticLabel = "便量$label",
                selected = draft.stoolAmount == value,
                onClick = { onDraftChange(draft.copy(stoolAmount = value)) },
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) {
                LeziStoolAmountMark(value, modifier = Modifier.size(28.dp))
            }
        }
    }
    Text(
        "软硬",
        style = LeziTypography.Label,
        color = if (consistencyError) MaterialTheme.colorScheme.error else Color.Unspecified,
    )
    Row(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
    ) {
        (1..4).forEach { value ->
            val label = stoolConsistencyLabel(value)
            ExcretionChoice(
                label = label,
                semanticLabel = "便便软硬$label",
                selected = draft.stoolConsistency == value,
                onClick = { onDraftChange(draft.copy(stoolConsistency = value)) },
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) {
                LeziStoolConsistencyMark(value, modifier = Modifier.size(28.dp))
            }
        }
    }
    Text(
        "颜色",
        style = LeziTypography.Label,
        color = if (colorError) MaterialTheme.colorScheme.error else Color.Unspecified,
    )
    Column(
        Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
    ) {
        (0..7).chunked(4).forEach { values ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
            ) {
                values.forEach { value ->
                    val label = stoolColorLabel(value)
                    ExcretionChoice(
                        label = label,
                        semanticLabel = "便便颜色$label",
                        selected = draft.stoolColor == value,
                        onClick = { onDraftChange(draft.copy(stoolColor = value)) },
                        enabled = enabled,
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
    enabled: Boolean = true,
    mark: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .heightIn(min = 68.dp)
            .selectable(
                selected = selected,
                enabled = enabled,
                onClick = onClick,
                role = Role.RadioButton,
            )
            .semantics {
                contentDescription = semanticLabel
                stateDescription = if (selected) "已选择" else "未选择"
            },
        shape = LeziThemeExt.controlShape,
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
private fun SleepFields(
    draft: QuickRecordDraft,
    policy: SleepComposerPolicy,
) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SleepActionAnimation(
            action = draft.sleepAction ?: SleepDraftAction.Manual,
            animationDescription = policy.animationDescription,
        )
    }
}

@Composable
private fun SleepActionAnimation(
    action: SleepDraftAction,
    animationDescription: String,
) {
    // Exactly one infinite animator runs for the visible variant; the unused
    // child used to pump the frame clock alongside the applied one.
    if (action == SleepDraftAction.WakeUp) {
        WakePulseMark(animationDescription)
    } else {
        SleepBobMark(animationDescription)
    }
}

@Composable
private fun WakePulseMark(animationDescription: String) {
    val pulse by rememberInfiniteTransition(label = "wake-pulse").animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "wake-pulse",
    )
    val sun = com.lezi.babylog.designsystem.LeziColors.SleepSun
    Canvas(
        modifier = Modifier
            .size(72.dp)
            .semantics {
                contentDescription = animationDescription
            }
            .graphicsLayer {
                scaleX = pulse
                scaleY = pulse
            },
    ) {
        drawCircle(color = sun.copy(alpha = 0.14f))
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
    }
}

@Composable
private fun SleepBobMark(animationDescription: String) {
    val bob by rememberInfiniteTransition(label = "sleep-action").animateFloat(
        initialValue = -2f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sleep-bob",
    )
    val surface = MaterialTheme.colorScheme.surface
    val moon = com.lezi.babylog.designsystem.LeziColors.SleepMoonCap
    val sun = com.lezi.babylog.designsystem.LeziColors.SleepSun
    Canvas(
        modifier = Modifier
            .size(72.dp)
            .semantics {
                contentDescription = animationDescription
            }
            .graphicsLayer {
                translationY = bob
            },
    ) {
        drawCircle(color = moon.copy(alpha = 0.14f))
        val center = this.center
        drawCircle(moon, radius = size.minDimension * 0.23f, center = center)
        drawCircle(
            surface,
            radius = size.minDimension * 0.21f,
            center = center + androidx.compose.ui.geometry.Offset(8.dp.toPx(), -5.dp.toPx()),
        )
        drawCircle(
            sun,
            radius = 2.5.dp.toPx(),
            center = center + androidx.compose.ui.geometry.Offset(18.dp.toPx(), -16.dp.toPx()),
        )
    }
}

@Composable
private fun TemperatureFields(
    draft: QuickRecordDraft,
    birthdayEpochDay: Long?,
    adviceEnabled: Boolean,
    onDraftChange: (QuickRecordDraft) -> Unit,
    highlightedField: ComposerInvalidField? = null,
    fieldFocusRequester: FocusRequester? = null,
    enabled: Boolean = true,
) {
    val temperatureError = highlightedField == ComposerInvalidField.Temperature
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DecimalField(
            value = draft.temperature,
            label = "体温",
            modifier = Modifier.weight(1f),
            isError = temperatureError,
            focusRequester = fieldFocusRequester.takeIf { temperatureError },
            enabled = enabled,
        ) { onDraftChange(draft.copy(temperature = it)) }
        Column(Modifier.weight(1f)) {
            ChoiceStrip(
                label = "单位",
                choices = listOf(
                    TemperatureUnit.Celsius to "℃",
                    TemperatureUnit.Fahrenheit to "℉",
                ),
                selected = draft.temperatureUnit,
                enabled = enabled,
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
            shape = LeziThemeExt.controlShape,
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
    highlightedField: ComposerInvalidField? = null,
    fieldFocusRequester: FocusRequester? = null,
    enabled: Boolean = true,
) {
    val bodyError = highlightedField == ComposerInvalidField.Body
    LeziTextField(
        value = draft.body,
        onValueChange = { onDraftChange(draft.copy(body = it.take(800))) },
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (bodyError && fieldFocusRequester != null) {
                    Modifier.focusRequester(fieldFocusRequester)
                } else {
                    Modifier
                },
            ),
        label = { Text(if (draft.type == RecordType.DIARY) "日记正文" else "内容") },
        isError = bodyError,
        singleLine = false,
        minLines = if (draft.type == RecordType.DIARY) 4 else 2,
        maxLines = 7,
        supportingText = { Text("${draft.body.length}/800") },
    )
}

@Composable
private fun SymptomFields(
    draft: QuickRecordDraft,
    onDraftChange: (QuickRecordDraft) -> Unit,
    highlightedField: ComposerInvalidField? = null,
    enabled: Boolean = true,
) {
    val severityError = highlightedField == ComposerInvalidField.Severity
    ChoiceStrip(
        label = if (severityError) "程度（请选择）" else "程度",
        choices = listOf(1 to "轻微", 2 to "一般", 3 to "明显"),
        selected = draft.severity,
        enabled = enabled,
    ) { onDraftChange(draft.copy(severity = it)) }
    LeziTextField(
        value = draft.description,
        onValueChange = { onDraftChange(draft.copy(description = it.take(200))) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("情况描述（可选）") },
        singleLine = false,
        minLines = 2,
    )
}

@Composable
private fun MedicineFields(
    draft: QuickRecordDraft,
    onDraftChange: (QuickRecordDraft) -> Unit,
    highlightedField: ComposerInvalidField? = null,
    fieldFocusRequester: FocusRequester? = null,
    enabled: Boolean = true,
) {
    val nameError = highlightedField == ComposerInvalidField.MedicineName
    LeziTextField(
        value = draft.medicineName,
        onValueChange = { onDraftChange(draft.copy(medicineName = it.take(50))) },
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (nameError && fieldFocusRequester != null) {
                    Modifier.focusRequester(fieldFocusRequester)
                } else {
                    Modifier
                },
            ),
        label = { Text("药品名称") },
        isError = nameError,
        singleLine = true,
    )
    LeziTextField(
        value = draft.medicineDose,
        onValueChange = { onDraftChange(draft.copy(medicineDose = it.take(30))) },
        enabled = enabled,
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
    highlightedField: ComposerInvalidField? = null,
    fieldFocusRequester: FocusRequester? = null,
    enabled: Boolean = true,
) {
    val reasonError = highlightedField == ComposerInvalidField.HospitalReason
    LeziTextField(
        value = draft.hospitalReason,
        onValueChange = { onDraftChange(draft.copy(hospitalReason = it.take(100))) },
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (reasonError && fieldFocusRequester != null) {
                    Modifier.focusRequester(fieldFocusRequester)
                } else {
                    Modifier
                },
            ),
        label = { Text("就诊原因") },
        isError = reasonError,
        singleLine = true,
    )
    LeziTextField(
        value = draft.hospitalAdvice,
        onValueChange = { onDraftChange(draft.copy(hospitalAdvice = it.take(300))) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        singleLine = false,
        label = { Text("医嘱（可选）") },
        minLines = 2,
        maxLines = 6,
    )
}
