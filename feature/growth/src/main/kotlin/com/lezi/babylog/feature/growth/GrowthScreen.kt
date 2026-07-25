package com.lezi.babylog.feature.growth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.designsystem.LeziCard
import com.lezi.babylog.designsystem.LeziClockDialDialog
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziThemeExt
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.PageScaffoldBackground
import com.lezi.babylog.designsystem.SectionHeading
import com.lezi.babylog.designsystem.StateContainer
import com.lezi.babylog.designsystem.StateKind
import com.lezi.babylog.designsystem.dismissKeyboardOnTap
import com.lezi.babylog.designsystem.resolveLeziLocalDateTime
import com.lezi.babylog.designsystem.timestampOnLeziDate
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.payloadDouble
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlin.math.max
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

enum class GrowthMetric { WEIGHT, HEIGHT, HEAD }

data class CurveBand(val month: Float, val p3: Float, val p50: Float, val p97: Float)
data class MeasurePoint(val monthAge: Float, val value: Float, val record: Record)

/** Draft for create or edit of a growth measurement. */
private data class MeasurementDraft(
    val recordId: Long? = null,
    val valueText: String = "",
    val note: String = "",
    val measuredAt: Long,
)

data class GrowthUi(
    val metric: GrowthMetric = GrowthMetric.WEIGHT,
    val points: List<MeasurePoint> = emptyList(),
    val bands: List<CurveBand> = emptyList(),
    val corrected: Boolean = false,
    val dueDateEpochDay: Long? = null,
    val babyName: String = "",
    val birthdayEpochDay: Long? = null,
)

@HiltViewModel
class GrowthViewModel @Inject constructor(
    private val careLog: CareLog,
    settingsStore: SettingsStore,
) : ViewModel() {
    private val metric = MutableStateFlow(GrowthMetric.WEIGHT)
    private val corrected = MutableStateFlow(false)
    private val refresh = MutableStateFlow(0)

    val timeStepMin = settingsStore.settings
        .map { it.timeStepMin }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1)

    val timePickerStyle = settingsStore.settings
        .map { it.timePickerStyle }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "dropdown")

    val preferredHand = settingsStore.settings
        .map { it.preferredHand }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "right")

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val ui = combine(
        careLog.observeCurrentBaby(),
        metric,
        corrected,
        refresh,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        arrayOf(values[0], values[1], values[2], values[3])
    }.flatMapLatest { values ->
        val baby = values[0] as com.lezi.babylog.core.model.Baby?
        val m = values[1] as GrowthMetric
        val corr = values[2] as Boolean
        if (baby == null) {
            kotlinx.coroutines.flow.flowOf(GrowthUi())
        } else {
            kotlinx.coroutines.flow.flow {
                val type = when (m) {
                    GrowthMetric.WEIGHT -> RecordType.WEIGHT
                    GrowthMetric.HEIGHT -> RecordType.HEIGHT
                    GrowthMetric.HEAD -> RecordType.HEAD
                }
                val records = careLog.listMeasurements(baby.id, type)
                val zone = ZoneId.systemDefault()
                val birth = LocalDate.ofEpochDay(baby.birthdayEpochDay)
                val dueEpoch = baby.dueDateEpochDay
                val due = if (dueEpoch != null) LocalDate.ofEpochDay(dueEpoch) else null
                val points = records.mapNotNull { r ->
                    val value: Float = when (m) {
                        GrowthMetric.WEIGHT -> {
                            val g = payloadDouble(r.payloadJson, "value") ?: return@mapNotNull null
                            (g / 1000.0).toFloat()
                        }
                        GrowthMetric.HEIGHT, GrowthMetric.HEAD -> {
                            val cm = payloadDouble(r.payloadJson, "value") ?: return@mapNotNull null
                            cm.toFloat()
                        }
                    }
                    val day = Instant.ofEpochMilli(r.timestamp).atZone(zone).toLocalDate()
                    var months = ChronoUnit.DAYS.between(birth, day) / 30.4375f
                    if (corr && due != null && due.isAfter(birth)) {
                        val shift = ChronoUnit.DAYS.between(birth, due) / 30.4375f
                        months = (months - shift).coerceAtLeast(0f)
                    }
                    MeasurePoint(months, value, r)
                }.sortedBy { it.monthAge }
                emit(
                    GrowthUi(
                        metric = m,
                        points = points,
                        bands = emptyList(),
                        corrected = corr,
                        dueDateEpochDay = baby.dueDateEpochDay,
                        babyName = baby.nickname,
                        birthdayEpochDay = baby.birthdayEpochDay,
                    ),
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GrowthUi())

    fun setMetric(m: GrowthMetric) {
        metric.value = m
    }

    fun setCorrected(v: Boolean) {
        corrected.value = v
    }

    fun addMeasurement(value: Double, timestamp: Long, note: String, onDone: () -> Unit) {
        viewModelScope.launch {
            val baby = careLog.getCurrentBaby() ?: return@launch
            if (timestamp > System.currentTimeMillis()) return@launch
            val (type, payload) = measurementPayload(metric.value, value) ?: return@launch
            careLog.addRecord(
                babyId = baby.id,
                type = type,
                timestamp = timestamp,
                note = note.ifBlank { null },
                payloadJson = payload,
            )
            refresh.value = refresh.value + 1
            onDone()
        }
    }

    fun updateMeasurement(
        recordId: Long,
        value: Double,
        timestamp: Long,
        note: String,
        onDone: () -> Unit,
    ) {
        viewModelScope.launch {
            if (timestamp > System.currentTimeMillis()) return@launch
            val payload = measurementPayload(metric.value, value)?.second ?: return@launch
            careLog.updateRecord(
                id = recordId,
                timestamp = timestamp,
                endTimestamp = null,
                note = note.ifBlank { null },
                payloadJson = payload,
            )
            refresh.value = refresh.value + 1
            onDone()
        }
    }

    fun deleteMeasurement(recordId: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            careLog.deleteRecord(recordId)
            refresh.value = refresh.value + 1
            onDone()
        }
    }

    fun setDueDate(epochDay: Long?) {
        viewModelScope.launch {
            val baby = careLog.getCurrentBaby() ?: return@launch
            careLog.updateBabyDueDate(baby.id, epochDay)
            refresh.value = refresh.value + 1
        }
    }

    private fun measurementPayload(
        metric: GrowthMetric,
        value: Double,
    ): Pair<RecordType, String>? {
        if (value <= 0.0) return null
        return when (metric) {
            GrowthMetric.WEIGHT -> {
                val g = (value * 1000).toInt()
                if (g <= 0) return null
                RecordType.WEIGHT to """{"value":$g,"unit":"g"}"""
            }
            GrowthMetric.HEIGHT -> RecordType.HEIGHT to """{"value":$value,"unit":"cm"}"""
            GrowthMetric.HEAD -> RecordType.HEAD to """{"value":$value,"unit":"cm"}"""
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrowthRoute(
    initialDate: LocalDate = LocalDate.now(),
    vm: GrowthViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val timeStepMin by vm.timeStepMin.collectAsStateWithLifecycle()
    val timePickerStyle by vm.timePickerStyle.collectAsStateWithLifecycle()
    val preferredHand by vm.preferredHand.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val bands = remember(ui.metric) {
        loadBands(context, ui.metric)
    }
    var draft by remember { mutableStateOf<MeasurementDraft?>(null) }
    var showMeasureDate by remember { mutableStateOf(false) }
    var showMeasureClock by remember { mutableStateOf(false) }
    var measurementError by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val journal = LeziThemeExt.isJournal
    val zone = ZoneId.systemDefault()
    val history = remember(ui.points) {
        ui.points.sortedByDescending { it.record.timestamp }
    }

    fun openNewMeasurement() {
        draft = MeasurementDraft(measuredAt = timestampOnGrowthDate(initialDate))
        measurementError = null
        showMeasureDate = false
        showMeasureClock = false
        confirmDelete = false
    }

    fun openEditMeasurement(point: MeasurePoint) {
        val record = point.record
        val valueText = if (ui.metric == GrowthMetric.WEIGHT) {
            "%.2f".format(point.value)
        } else {
            "%.1f".format(point.value)
        }
        draft = MeasurementDraft(
            recordId = record.id,
            valueText = valueText.trimEnd('0').trimEnd('.').ifEmpty { valueText },
            note = record.note.orEmpty(),
            measuredAt = record.timestamp,
        )
        measurementError = null
        showMeasureDate = false
        showMeasureClock = false
        confirmDelete = false
    }

    fun closeMeasurementDraft() {
        draft = null
        showMeasureDate = false
        showMeasureClock = false
        measurementError = null
        confirmDelete = false
    }

    PageScaffoldBackground {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(LeziSpacing.Page),
            verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
        ) {
            com.lezi.babylog.designsystem.PageHero(
                eyebrow = if (journal) "百分位网格" else "每一次变化都算数",
                title = "成长",
                trailing = {
                    LeziPrimaryButton("新增测量", onClick = { openNewMeasurement() })
                },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = ui.metric == GrowthMetric.WEIGHT,
                    onClick = { vm.setMetric(GrowthMetric.WEIGHT) },
                    label = { Text("体重") },
                )
                FilterChip(
                    selected = ui.metric == GrowthMetric.HEIGHT,
                    onClick = { vm.setMetric(GrowthMetric.HEIGHT) },
                    label = { Text("身高") },
                )
                FilterChip(
                    selected = ui.metric == GrowthMetric.HEAD,
                    onClick = { vm.setMetric(GrowthMetric.HEAD) },
                    label = { Text("头围") },
                )
            }

            if (ui.dueDateEpochDay != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("修正月龄", style = LeziTypography.BodyStrong)
                        Text("适用于早产宝宝", style = LeziTypography.Meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = ui.corrected, onCheckedChange = vm::setCorrected)
                }
            }

            if (ui.points.isEmpty()) {
                StateContainer(
                    kind = StateKind.Empty,
                    title = "还没有测量",
                    message = "添加身高或体重后，这里会显示趋势与参考曲线。",
                    actionLabel = "去录入",
                    onAction = { openNewMeasurement() },
                )
            } else {
                val latest = ui.points.last()
                LeziCard(Modifier.fillMaxWidth()) {
                    Text(
                        when (ui.metric) {
                            GrowthMetric.WEIGHT -> "最新体重"
                            GrowthMetric.HEIGHT -> "最新身高"
                            GrowthMetric.HEAD -> "最新头围"
                        },
                        style = LeziTypography.Meta,
                    )
                    Text(
                        formatMeasurementValue(ui.metric, latest.value),
                        style = LeziTypography.Metric,
                    )
                    GrowthChart(points = ui.points, bands = bands, metric = ui.metric)
                    growthRangeWarning(
                        monthAge = latest.monthAge,
                        value = latest.value,
                        bands = bands,
                    )?.let { warning ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            shape = com.lezi.babylog.designsystem.LeziShapes.Sm,
                        ) {
                            Text(
                                warning,
                                modifier = Modifier.padding(LeziSpacing.Sm),
                                style = LeziTypography.Meta,
                            )
                        }
                    }
                    if (journal) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("— P3", style = LeziTypography.Meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("— P50", style = LeziTypography.Meta, color = MaterialTheme.colorScheme.secondary)
                            Text("— P97", style = LeziTypography.Meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                SectionHeading(
                    title = "测量记录",
                    meta = "点按可修改或删除错误数值",
                )
                LeziCard(Modifier.fillMaxWidth()) {
                    history.forEachIndexed { index, point ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                        }
                        MeasurementHistoryRow(
                            metric = ui.metric,
                            point = point,
                            zone = zone,
                            onClick = { openEditMeasurement(point) },
                        )
                    }
                }
            }

            Text(
                if (ui.metric == GrowthMetric.HEAD) {
                    "头围仅显示个人趋势 · 非医疗诊断"
                } else {
                    "WHO 示例 P3/P50/P97 · 仅供趋势参考，非医疗诊断"
                },
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(LeziSpacing.Xxl))
        }
    }

    val activeDraft = draft
    if (activeDraft != null) {
        val isEditing = activeDraft.recordId != null
        AlertDialog(
            onDismissRequest = { closeMeasurementDraft() },
            title = {
                Text(
                    when {
                        isEditing && ui.metric == GrowthMetric.WEIGHT -> "修改体重 (kg)"
                        isEditing && ui.metric == GrowthMetric.HEIGHT -> "修改身高 (cm)"
                        isEditing && ui.metric == GrowthMetric.HEAD -> "修改头围 (cm)"
                        ui.metric == GrowthMetric.WEIGHT -> "记录体重 (kg)"
                        ui.metric == GrowthMetric.HEIGHT -> "记录身高 (cm)"
                        else -> "记录头围 (cm)"
                    },
                )
            },
            text = {
                Column(
                    Modifier.dismissKeyboardOnTap(),
                    verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
                ) {
                    OutlinedTextField(
                        value = activeDraft.valueText,
                        onValueChange = { text ->
                            draft = activeDraft.copy(valueText = text)
                            measurementError = null
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        label = { Text(if (ui.metric == GrowthMetric.WEIGHT) "公斤" else "厘米") },
                    )
                    OutlinedTextField(
                        value = activeDraft.note,
                        onValueChange = { text ->
                            draft = activeDraft.copy(note = text.take(200))
                        },
                        label = { Text("备注（可选）") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        supportingText = { Text("${activeDraft.note.length}/200") },
                    )
                    val measurement = Instant.ofEpochMilli(activeDraft.measuredAt).atZone(zone)
                    Text(
                        measurement.format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")),
                        style = LeziTypography.BodyStrong,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showMeasureDate = true }) {
                            Text("修改日期")
                        }
                        OutlinedButton(onClick = { showMeasureClock = true }) {
                            Text("选择时间")
                        }
                    }
                    if (isEditing) {
                        LeziSecondaryButton(
                            label = "删除这条测量",
                            onClick = { confirmDelete = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    measurementError?.let {
                        Text(
                            it,
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val value = activeDraft.valueText.toDoubleOrNull()
                        if (value == null || value <= 0.0) {
                            measurementError = if (ui.metric == GrowthMetric.WEIGHT) {
                                "请输入有效体重（大于 0 公斤）"
                            } else {
                                "请输入有效数值（大于 0 厘米）"
                            }
                            return@TextButton
                        }
                        if (activeDraft.measuredAt > System.currentTimeMillis()) {
                            measurementError = "测量时刻不能晚于现在"
                            return@TextButton
                        }
                        val note = activeDraft.note
                        val at = activeDraft.measuredAt
                        val recordId = activeDraft.recordId
                        if (recordId != null) {
                            vm.updateMeasurement(recordId, value, at, note) {
                                closeMeasurementDraft()
                            }
                        } else {
                            vm.addMeasurement(value, at, note) {
                                closeMeasurementDraft()
                            }
                        }
                    },
                ) { Text(if (isEditing) "保存修改" else "保存") }
            },
            dismissButton = {
                TextButton(onClick = { closeMeasurementDraft() }) { Text("取消") }
            },
        )
    }

    if (confirmDelete) {
        val deletingId = draft?.recordId
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条测量？") },
            text = {
                Text(
                    when (ui.metric) {
                        GrowthMetric.WEIGHT -> "删除后会从体重曲线与记录列表中移除，无法撤销。"
                        GrowthMetric.HEIGHT -> "删除后会从身高曲线与记录列表中移除，无法撤销。"
                        GrowthMetric.HEAD -> "删除后会从头围曲线与记录列表中移除，无法撤销。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (deletingId != null) {
                            vm.deleteMeasurement(deletingId) {
                                closeMeasurementDraft()
                            }
                        } else {
                            confirmDelete = false
                        }
                    },
                ) {
                    Text("确认删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }

    val draftForPickers = draft
    if (showMeasureDate && draftForPickers != null) {
        val current = Instant.ofEpochMilli(draftForPickers.measuredAt).atZone(zone)
        val initialUtc = current.toLocalDate()
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initialUtc)
        DatePickerDialog(
            onDismissRequest = { showMeasureDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        dateState.selectedDateMillis?.let { millis ->
                            val selectedDate = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            val safeDate = minOf(selectedDate, LocalDate.now(zone))
                            val resolved = resolveLeziLocalDateTime(
                                date = safeDate,
                                time = current.toLocalTime(),
                                zone = zone,
                                preferredOffset = current.offset,
                            )
                            if (resolved == null) {
                                measurementError = "所选日期不存在当前时刻，请改用其他时刻"
                            } else {
                                draft = draftForPickers.copy(
                                    measuredAt = resolved.toInstant().toEpochMilli(),
                                )
                                measurementError = if (selectedDate != safeDate) {
                                    "测量日期不能晚于今天，已保留为今天"
                                } else {
                                    null
                                }
                            }
                        }
                        showMeasureDate = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showMeasureDate = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = dateState)
        }
    }

    if (showMeasureClock && draftForPickers != null) {
        LeziClockDialDialog(
            title = "选择测量时刻",
            value = Instant.ofEpochMilli(draftForPickers.measuredAt).atZone(zone),
            minuteStep = timeStepMin,
            timePickerStyle = timePickerStyle,
            preferredHand = preferredHand,
            onConfirm = { picked ->
                if (picked.isAfter(ZonedDateTime.now(zone))) {
                    measurementError = "测量时刻不能晚于现在"
                } else {
                    draft = draftForPickers.copy(measuredAt = picked.toInstant().toEpochMilli())
                    measurementError = null
                }
                showMeasureClock = false
            },
            onDismiss = { showMeasureClock = false },
        )
    }
}

@Composable
private fun MeasurementHistoryRow(
    metric: GrowthMetric,
    point: MeasurePoint,
    zone: ZoneId,
    onClick: () -> Unit,
) {
    val whenText = Instant.ofEpochMilli(point.record.timestamp)
        .atZone(zone)
        .format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm"))
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = LeziSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatMeasurementValue(metric, point.value), style = LeziTypography.BodyStrong)
            Text("修改", style = LeziTypography.Meta, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            whenText,
            style = LeziTypography.Meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        point.record.note?.takeIf { it.isNotBlank() }?.let { note ->
            Text(
                note,
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatMeasurementValue(metric: GrowthMetric, value: Float): String {
    return if (metric == GrowthMetric.WEIGHT) {
        "%.2f".format(value) + " kg"
    } else {
        "%.1f".format(value) + " cm"
    }
}

internal fun timestampOnGrowthDate(
    date: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
    now: ZonedDateTime = ZonedDateTime.now(zone),
): Long {
    return timestampOnLeziDate(date, zone, now)
}

internal fun growthRangeWarning(
    monthAge: Float,
    value: Float,
    bands: List<CurveBand>,
): String? {
    if (bands.isEmpty()) return null
    val sorted = bands.sortedBy { it.month }
    val upperIndex = sorted.indexOfFirst { it.month >= monthAge }
    val lower: CurveBand
    val upper: CurveBand
    when {
        upperIndex < 0 -> {
            lower = sorted.last()
            upper = lower
        }
        upperIndex == 0 -> {
            lower = sorted.first()
            upper = lower
        }
        else -> {
            lower = sorted[upperIndex - 1]
            upper = sorted[upperIndex]
        }
    }
    val progress = if (upper.month == lower.month) {
        0f
    } else {
        ((monthAge - lower.month) / (upper.month - lower.month)).coerceIn(0f, 1f)
    }
    fun interpolate(start: Float, end: Float): Float = start + (end - start) * progress
    val p3 = interpolate(lower.p3, upper.p3)
    val p97 = interpolate(lower.p97, upper.p97)
    return when {
        value > p97 -> "该数值高于同月龄参考范围，请确认单位和录入值。"
        value < p3 -> "该数值低于同月龄参考范围，请确认单位和录入值。"
        else -> null
    }
}

@Composable
private fun GrowthChart(points: List<MeasurePoint>, bands: List<CurveBand>, metric: GrowthMetric) {
    val accent = MaterialTheme.colorScheme.primary
    val percentile = MaterialTheme.colorScheme.secondary
    val muted = MaterialTheme.colorScheme.outline
    val ext = LeziThemeExt.colors
    val journal = LeziThemeExt.isJournal
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(200.dp)
            .padding(top = 12.dp),
    ) {
        val maxMonth = max(24f, points.maxOfOrNull { it.monthAge } ?: 12f)
        val yValues = points.map { it.value } + bands.flatMap { listOf(it.p3, it.p50, it.p97) }
        val yMax = (yValues.maxOrNull() ?: if (metric == GrowthMetric.WEIGHT) 12f else 90f) * 1.1f
        val yMin = (yValues.minOrNull() ?: if (metric == GrowthMetric.WEIGHT) 2f else 40f) * 0.9f
        fun x(m: Float) = size.width * (m / maxMonth).coerceIn(0f, 1f)
        fun y(v: Float) = size.height - ((v - yMin) / (yMax - yMin).coerceAtLeast(0.1f)) * size.height

        val gridLines = if (journal) 6 else 3
        for (i in 0..gridLines) {
            val yy = size.height * i / gridLines.toFloat()
            drawLine(ext.chartGrid.copy(alpha = if (journal) 0.7f else 0.35f), Offset(0f, yy), Offset(size.width, yy), strokeWidth = 1f)
        }
        if (journal) {
            for (i in 0..6) {
                val xx = size.width * i / 6f
                drawLine(ext.chartGrid.copy(alpha = 0.5f), Offset(xx, 0f), Offset(xx, size.height), strokeWidth = 1f)
            }
        }

        fun bandPath(selector: (CurveBand) -> Float): Path {
            val p = Path()
            bands.sortedBy { it.month }.forEachIndexed { i, b ->
                val xx = x(b.month)
                val yy = y(selector(b))
                if (i == 0) p.moveTo(xx, yy) else p.lineTo(xx, yy)
            }
            return p
        }
        if (bands.isNotEmpty()) {
            if (journal) {
                val area = Path()
                bands.sortedBy { it.month }.forEachIndexed { i, b ->
                    val xx = x(b.month)
                    val yy = y(b.p97)
                    if (i == 0) area.moveTo(xx, yy) else area.lineTo(xx, yy)
                }
                bands.sortedByDescending { it.month }.forEach { b -> area.lineTo(x(b.month), y(b.p3)) }
                area.close()
                drawPath(area, ext.laneCare.copy(alpha = 0.16f))
            }
            drawPath(bandPath { it.p3 }, muted.copy(alpha = 0.65f), style = Stroke(if (journal) 1.5f else 2f))
            drawPath(
                bandPath { it.p50 },
                if (journal) percentile else muted.copy(alpha = 0.7f),
                style = Stroke(if (journal) 2f else 2.5f),
            )
            drawPath(bandPath { it.p97 }, muted.copy(alpha = 0.65f), style = Stroke(if (journal) 1.5f else 2f))
        }

        val line = Path()
        points.forEachIndexed { i, pt ->
            val xx = x(pt.monthAge)
            val yy = y(pt.value)
            if (i == 0) line.moveTo(xx, yy) else line.lineTo(xx, yy)
            drawCircle(accent, radius = if (journal) 5f else 7f, center = Offset(xx, yy))
        }
        if (points.size >= 2) drawPath(line, accent, style = Stroke(width = if (journal) 3f else 4f))
    }
}

private fun loadBands(context: android.content.Context, metric: GrowthMetric): List<CurveBand> {
    if (metric == GrowthMetric.HEAD) return emptyList()
    return runCatching {
        val json = context.assets.open("curves/who_simple.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val key = if (metric == GrowthMetric.WEIGHT) "weight_kg" else "height_cm"
        val arr: JSONArray = root.getJSONArray(key)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    CurveBand(
                        month = o.getDouble("m").toFloat(),
                        p3 = o.getDouble("p3").toFloat(),
                        p50 = o.getDouble("p50").toFloat(),
                        p97 = o.getDouble("p97").toFloat(),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())
}
