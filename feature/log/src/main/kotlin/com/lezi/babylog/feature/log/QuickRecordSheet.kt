package com.lezi.babylog.feature.log

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.ui.RecordTypeIcon
import com.lezi.babylog.core.ui.peeAmountLabel
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.core.ui.stoolAmountLabel
import com.lezi.babylog.core.ui.stoolColorLabel
import com.lezi.babylog.core.ui.stoolConsistencyLabel
import com.lezi.babylog.designsystem.LeziClockDialDialog
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziShapes
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.leziRecordColor
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

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
    saveError: String?,
    canStartNursingTimer: Boolean,
    onDraftChange: (QuickRecordDraft) -> Unit,
    onDismiss: () -> Unit,
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
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = LeziSpacing.Lg),
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
            Column {
                Text(sheetKicker(draft), style = LeziTypography.Eyebrow)
                Text(sheetTitle(draft), style = LeziTypography.Title)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 530.dp)
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
                label = if (saving) "保存中…" else draft.confirmLabel(),
                onClick = {
                    if (saving) return@LeziPrimaryButton
                    val validation = draft.validationError()
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
                        if (pickedMillis > nowMillis) {
                            error = "记录时刻不能晚于现在"
                        } else {
                            update(draft.copy(timestamp = pickedMillis))
                        }
                    }
                    QuickClockTarget.End -> {
                        val start = Instant.ofEpochMilli(draft.timestamp).atZone(zone)
                        val directIsValid = if (draft.sleepAction == SleepDraftAction.WakeUp) {
                            pickedMillis >= draft.timestamp
                        } else {
                            pickedMillis > draft.timestamp
                        }
                        val resolved = if (directIsValid) {
                            picked
                        } else {
                            resolveSleepEnd(start, picked.toLocalTime())
                        }
                        val resolvedMillis = resolved?.toInstant()?.toEpochMilli()
                        when {
                            resolvedMillis == null -> error = "结束时刻不能与开始时刻相同"
                            resolvedMillis > nowMillis -> error = "结束时刻不能晚于现在"
                            else -> update(draft.copy(endTimestamp = resolvedMillis))
                        }
                    }
                }
                clockTarget = null
            },
            onDismiss = { clockTarget = null },
        )
    }
}

@Composable
private fun PurposeFields(
    draft: QuickRecordDraft,
    amountStepMl: Int,
    canStartNursingTimer: Boolean,
    onDraftChange: (QuickRecordDraft) -> Unit,
    onStartNursingTimer: () -> Unit,
) {
    when (draft.mode) {
        QuickRecordMode.Nursing -> NursingFields(
            draft,
            canStartNursingTimer,
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
        QuickRecordMode.Sleep -> SleepFields(draft, onDraftChange)
        QuickRecordMode.Temperature -> TemperatureFields(draft, onDraftChange)
        QuickRecordMode.Text -> TextFields(draft, onDraftChange)
        QuickRecordMode.Simple -> SimpleFields(draft.type)
        QuickRecordMode.Interval -> {
            Text(
                "分别用下方圆盘设置开始与结束时刻。",
                style = LeziTypography.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        QuickRecordMode.Symptom -> SymptomFields(draft, onDraftChange)
        QuickRecordMode.Medicine -> MedicineFields(draft, onDraftChange)
        QuickRecordMode.Hospital -> HospitalFields(draft, onDraftChange)
        QuickRecordMode.CustomText -> CustomTextFields(draft, onDraftChange)
        QuickRecordMode.Measurement -> MeasurementFields(draft, onDraftChange)
        QuickRecordMode.Food -> FoodFields(draft, onDraftChange)
        QuickRecordMode.Vaccine -> VaccineFields(draft, onDraftChange)
    }
}

@Composable
private fun NursingFields(
    draft: QuickRecordDraft,
    canStartNursingTimer: Boolean,
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
            modifier = Modifier.fillMaxWidth(),
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
    val quickAmounts = remember(draft.amountMl, step) {
        listOf(
            draft.amountMl - step,
            draft.amountMl,
            draft.amountMl + step,
            draft.amountMl + step * 2,
        ).map { it.coerceIn(1, 999) }.distinct()
    }
    ChoiceStrip(
        label = "快捷奶量",
        choices = quickAmounts.map { it to "${it}ml" },
        selected = draft.amountMl,
    ) { onDraftChange(draft.copy(amountMl = it)) }
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
    ChoiceStrip(
        label = "尿量",
        choices = (1..3).map { it to peeAmountLabel(it) },
        selected = draft.peeAmount,
    ) { onDraftChange(draft.copy(peeAmount = it)) }
}

@Composable
private fun StoolFields(
    draft: QuickRecordDraft,
    onDraftChange: (QuickRecordDraft) -> Unit,
) {
    ChoiceStrip(
        label = "便量",
        choices = (1..4).map { it to stoolAmountLabel(it) },
        selected = draft.stoolAmount,
    ) { onDraftChange(draft.copy(stoolAmount = it)) }
    ChoiceStrip(
        label = "软硬",
        choices = (1..4).map { it to stoolConsistencyLabel(it) },
        selected = draft.stoolConsistency,
    ) { onDraftChange(draft.copy(stoolConsistency = it)) }
    ChoiceStrip(
        label = "颜色",
        choices = (0..7).map { it to stoolColorLabel(it) },
        selected = draft.stoolColor,
    ) { onDraftChange(draft.copy(stoolColor = it)) }
}

@Composable
private fun SleepFields(
    draft: QuickRecordDraft,
    onDraftChange: (QuickRecordDraft) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SleepActionAnimation(draft.sleepAction ?: SleepDraftAction.Manual)
        Text(
            when (draft.sleepAction) {
                SleepDraftAction.SleepDown -> "确认后开始睡眠计时"
                SleepDraftAction.WakeUp -> "确认后结束当前睡眠"
                SleepDraftAction.Manual, null -> "补记一段已经完成的睡眠"
            },
            style = LeziTypography.BodyStrong,
        )
    }
    ChoiceStrip(
        label = "睡眠类型",
        choices = listOf(false to "夜间 / 长睡", true to "午睡"),
        selected = draft.isNap,
    ) { onDraftChange(draft.copy(isNap = it)) }
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
    if (celsius != null && celsius >= 38.0) {
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

@Composable
private fun CustomTextFields(
    draft: QuickRecordDraft,
    onDraftChange: (QuickRecordDraft) -> Unit,
) {
    OutlinedTextField(
        value = draft.customTitle,
        onValueChange = { onDraftChange(draft.copy(customTitle = it.take(30))) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(if (draft.type == RecordType.CUSTOM) "自定义项目名称" else "标题") },
        singleLine = true,
    )
    OutlinedTextField(
        value = draft.customDetail,
        onValueChange = { onDraftChange(draft.copy(customDetail = it.take(200))) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("详情（可选）") },
        minLines = 2,
    )
}

@Composable
private fun MeasurementFields(
    draft: QuickRecordDraft,
    onDraftChange: (QuickRecordDraft) -> Unit,
) {
    val unit = if (draft.type == RecordType.WEIGHT) "kg" else "cm"
    DecimalField(
        value = draft.measurementValue,
        label = "${draft.type.presentation.label}（$unit）",
        modifier = Modifier.fillMaxWidth(),
    ) { onDraftChange(draft.copy(measurementValue = it)) }
}

@Composable
private fun FoodFields(
    draft: QuickRecordDraft,
    onDraftChange: (QuickRecordDraft) -> Unit,
) {
    OutlinedTextField(
        value = draft.foodContent,
        onValueChange = { onDraftChange(draft.copy(foodContent = it.take(80))) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("内容") },
        placeholder = { Text(if (draft.type == RecordType.DRINK) "例如 温水" else "例如 南瓜米糊") },
        singleLine = true,
    )
    OutlinedTextField(
        value = draft.foodAmount,
        onValueChange = { onDraftChange(draft.copy(foodAmount = it.take(30))) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("量（可选）") },
        placeholder = { Text(if (draft.type == RecordType.DRINK) "例如 80 ml" else "例如 半碗") },
        singleLine = true,
    )
}

@Composable
private fun VaccineFields(
    draft: QuickRecordDraft,
    onDraftChange: (QuickRecordDraft) -> Unit,
) {
    OutlinedTextField(
        value = draft.vaccineName,
        onValueChange = { onDraftChange(draft.copy(vaccineName = it.take(80))) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("疫苗名称") },
        singleLine = true,
    )
    OutlinedTextField(
        value = draft.vaccineBatch,
        onValueChange = { onDraftChange(draft.copy(vaccineBatch = it.take(50))) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("批次 / 针次（可选）") },
        singleLine = true,
    )
}

@Composable
private fun TimeFields(
    draft: QuickRecordDraft,
    zone: ZoneId,
    onOpenStart: () -> Unit,
    onOpenEnd: () -> Unit,
) {
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
            TimeButton("醒来", draft.endTimestamp ?: draft.timestamp, zone, onOpenEnd)
        }
        draft.sleepAction == SleepDraftAction.Manual ||
            draft.mode == QuickRecordMode.Interval -> {
            TimeButton("开始", draft.timestamp, zone, onOpenStart)
            if (draft.endTimestamp == null) {
                Surface(
                    onClick = onOpenEnd,
                    modifier = Modifier.fillMaxWidth(),
                    shape = LeziShapes.Sm,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ) {
                    Text(
                        "选择结束时刻",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                        style = LeziTypography.BodyStrong,
                    )
                }
            } else {
                TimeButton("结束", draft.endTimestamp, zone, onOpenEnd)
            }
        }
        else -> TimeButton(
            label = if (draft.sleepAction == SleepDraftAction.SleepDown) "睡下" else "记录",
            millis = draft.timestamp,
            zone = zone,
            onClick = onOpenStart,
        )
    }
}

@Composable
private fun TimeButton(
    label: String,
    millis: Long,
    zone: ZoneId,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$label，${formatRecordTime(millis, zone)}，圆盘调时" },
        shape = LeziShapes.Sm,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
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
            Text("圆盘调时", style = LeziTypography.Label, color = MaterialTheme.colorScheme.primary)
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
private fun <T> ChoiceStrip(
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
private fun IntegerField(
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
private fun DecimalField(
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
private fun SectionLabel(label: String) {
    Text(
        label,
        style = LeziTypography.Label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun sheetKicker(draft: QuickRecordDraft): String = when (draft.sleepAction) {
    SleepDraftAction.SleepDown -> "准备休息"
    SleepDraftAction.WakeUp -> "睡眠进行中"
    SleepDraftAction.Manual -> "补记睡眠"
    null -> draft.type.presentation.tip
}

private fun sheetTitle(draft: QuickRecordDraft): String = when (draft.sleepAction) {
    SleepDraftAction.SleepDown -> "睡下"
    SleepDraftAction.WakeUp -> "醒来"
    SleepDraftAction.Manual, null -> draft.type.presentation.label
}

private fun notePlaceholder(type: RecordType): String = when (type) {
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
