package com.lezi.babylog.feature.growth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.GrowthReferenceBand
import com.lezi.babylog.core.model.RecordDateDecision
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.core.model.RecordTimeDecision
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.Sex
import com.lezi.babylog.designsystem.LeziAlertDialog
import com.lezi.babylog.designsystem.LeziSurfacePanel
import com.lezi.babylog.designsystem.LeziClockDialDialog
import com.lezi.babylog.designsystem.LeziDatePicker
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziRangeTabs
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziThemeExt
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.PageScaffoldBackground
import com.lezi.babylog.designsystem.SectionHeading
import com.lezi.babylog.designsystem.StateContainer
import com.lezi.babylog.designsystem.StateKind
import com.lezi.babylog.designsystem.dismissKeyboardOnTap
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.growth.GrowthMeasurementLifecycle
import com.lezi.babylog.domain.growth.ObserveGrowthMeasurements
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.session.ShallowSyncLine
import com.lezi.babylog.sync.session.ShallowSyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlin.math.max
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class GrowthMetric { WEIGHT, HEIGHT }

typealias CurveBand = GrowthReferenceBand

data class MeasurePoint(
    val monthAge: Float,
    val value: Float,
    val recordId: Long,
    val measuredAt: Long,
    val note: String?,
    val referenceWarning: String?,
)

data class GrowthUi(
    val metric: GrowthMetric = GrowthMetric.WEIGHT,
    val points: List<MeasurePoint> = emptyList(),
    val bands: List<CurveBand> = emptyList(),
    val birthday: LocalDate? = null,
    val sex: Sex? = null,
    val referenceValidUntilMonthExclusive: Float? = null,
)

@HiltViewModel
class GrowthViewModel @Inject constructor(
    private val careLog: CareLog,
    private val settingsStore: SettingsStore,
    private val measurements: GrowthMeasurementLifecycle,
    private val syncPort: SyncPort,
) : ViewModel() {
    private val metric = MutableStateFlow(GrowthMetric.WEIGHT)
    private val refreshInFlight = MutableStateFlow(false)
    private val writes = GrowthMeasurementWriteCoordinator(
        scope = viewModelScope,
        measurements = measurements,
        currentBabyId = { careLog.getCurrentBaby()?.id },
        nowMillis = RecordTime::currentTimeMillis,
    )

    val editor = writes.state

    val shallowSyncStatus = syncPort.shallowStatus().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ShallowSyncLine(
            state = ShallowSyncState.Unjoined,
            text = "尚未加入家庭 · 数据仅保存在本机",
        ),
    )
    val isRefreshing = refreshInFlight.asStateFlow()

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
    ) { baby, selectedMetric ->
        baby to selectedMetric
    }.flatMapLatest { (baby, m) ->
        if (baby == null) {
            kotlinx.coroutines.flow.flowOf(GrowthUi())
        } else {
            val type = m.recordType
            val zone = ZoneId.systemDefault()
            val birth = LocalDate.ofEpochDay(baby.birthdayEpochDay)
            measurements.observe(
                ObserveGrowthMeasurements(
                    babyId = baby.id,
                    type = type,
                    birthday = birth,
                    zone = zone,
                    sex = baby.sex,
                ),
            ).map { snapshot ->
                GrowthUi(
                    metric = m,
                    points = snapshot.measurements.map { fact ->
                        MeasurePoint(
                            monthAge = fact.monthAge,
                            value = fact.displayValue.toFloat(),
                            recordId = fact.recordId,
                            measuredAt = fact.measuredAt,
                            note = fact.note,
                            referenceWarning = fact.referenceWarning,
                        )
                    },
                    bands = snapshot.referenceBands,
                    birthday = birth,
                    sex = baby.sex,
                    referenceValidUntilMonthExclusive =
                        snapshot.referenceValidUntilMonthExclusive,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GrowthUi())

    fun setMetric(m: GrowthMetric) {
        metric.value = m
    }

    fun openMeasurementDraft(draft: GrowthMeasurementDraft): Boolean = writes.openDraft(draft)

    fun updateMeasurementDraft(draft: GrowthMeasurementDraft): Boolean =
        writes.updateDraft(draft)

    fun closeMeasurementDraft(): Boolean = writes.closeDraft()

    fun reportMeasurementMessage(message: String?) = writes.reportEditorMessage(message)

    fun saveMeasurement(): Boolean = writes.submitSave(metric.value.recordType)

    fun requestMeasurementDelete(): Boolean = writes.requestDelete()

    fun cancelMeasurementDelete(): Boolean = writes.cancelDelete()

    fun deleteMeasurement(): Boolean = writes.submitDelete()

    fun refresh() {
        if (!refreshInFlight.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                syncPort.syncWhenAvailable(SyncTrigger.PullToRefresh)
            } finally {
                refreshInFlight.value = false
            }
        }
    }

}

private val GrowthMetric.recordType: RecordType
    get() = when (this) {
        GrowthMetric.WEIGHT -> RecordType.WEIGHT
        GrowthMetric.HEIGHT -> RecordType.HEIGHT
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrowthRoute(
    initialDate: LocalDate = RecordTime.today(ZoneId.systemDefault()),
    vm: GrowthViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val shallowSyncStatus by vm.shallowSyncStatus.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val timeStepMin by vm.timeStepMin.collectAsStateWithLifecycle()
    val timePickerStyle by vm.timePickerStyle.collectAsStateWithLifecycle()
    val preferredHand by vm.preferredHand.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val bands = ui.bands
    val draft = editor.draft
    val busy = editor.operation != null
    val saving = editor.operation is GrowthMeasurementWriteOperation.Saving
    val deleting = editor.operation is GrowthMeasurementWriteOperation.Deleting
    var showMeasureDate by rememberSaveable { mutableStateOf(false) }
    var showMeasureClock by rememberSaveable { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val history = remember(ui.points) {
        ui.points.sortedByDescending(MeasurePoint::measuredAt)
    }

    fun openNewMeasurement() {
        vm.openMeasurementDraft(
            GrowthMeasurementDraft(
                measuredAt = RecordTime.newDraftTimestamp(initialDate, zone),
            ),
        )
        showMeasureDate = false
        showMeasureClock = false
    }

    fun openEditMeasurement(point: MeasurePoint) {
        val valueText = if (ui.metric == GrowthMetric.WEIGHT) {
            "%.2f".format(point.value)
        } else {
            "%.1f".format(point.value)
        }
        vm.openMeasurementDraft(
            GrowthMeasurementDraft(
                recordId = point.recordId,
                valueText = valueText.trimEnd('0').trimEnd('.').ifEmpty { valueText },
                note = point.note.orEmpty(),
                measuredAt = point.measuredAt,
            ),
        )
        showMeasureDate = false
        showMeasureClock = false
    }

    fun closeMeasurementDraft() {
        if (vm.closeMeasurementDraft()) {
            showMeasureDate = false
            showMeasureClock = false
        }
    }

    PageScaffoldBackground {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(LeziSpacing.Page),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                com.lezi.babylog.designsystem.PageHero(
                eyebrow = "",
                title = "成长",
                trailing = {
                    LeziPrimaryButton("新增测量", onClick = { openNewMeasurement() })
                },
            )

            Text(
                shallowSyncStatus.text,
                style = LeziTypography.Meta,
                color = if (
                    shallowSyncStatus.state in setOf(
                        ShallowSyncState.Error,
                        ShallowSyncState.ReauthRequired,
                    )
                ) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.testTag("growth_shallow_sync_status"),
            )

            LeziRangeTabs(
                items = GrowthMetric.entries,
                selected = ui.metric,
                onSelect = vm::setMetric,
                label = {
                    when (it) {
                        GrowthMetric.WEIGHT -> "体重"
                        GrowthMetric.HEIGHT -> "身长/身高"
                    }
                },
            )

            AnimatedContent(
                targetState = ui.metric,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "growth_metric_content",
            ) {
                if (ui.points.isEmpty()) {
                    StateContainer(
                        kind = StateKind.Empty,
                        title = "还没有测量",
                        message = "添加身长/身高或体重后，这里会显示趋势与参考曲线。",
                        actionLabel = "去录入",
                        onAction = { openNewMeasurement() },
                    )
                } else {
                    val latest = ui.points.last()
                    LeziSurfacePanel(Modifier.fillMaxWidth(), bottomBand = true) {
                        Text(
                            when (ui.metric) {
                                GrowthMetric.WEIGHT -> "最新体重"
                                GrowthMetric.HEIGHT -> "最新${ui.birthday?.let { birthday ->
                                    growthLinearMeasurementLabel(birthday, latest.measuredAt, zone)
                                } ?: "身长/身高"}"
                            },
                            style = LeziTypography.Meta,
                        )
                        Text(
                            formatMeasurementValue(ui.metric, latest.value),
                            style = LeziTypography.Metric,
                        )
                        GrowthChart(points = ui.points, bands = bands, metric = ui.metric)
                        latest.referenceWarning?.let { warning ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                shape = com.lezi.babylog.designsystem.LeziThemeExt.controlShape,
                            ) {
                                Text(
                                    warning,
                                    modifier = Modifier.padding(LeziSpacing.Sm),
                                    style = LeziTypography.Meta,
                                )
                            }
                        }
                        if (bands.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("— P3", style = LeziTypography.Meta, color = MaterialTheme.colorScheme.outline)
                                Text("— P50", style = LeziTypography.Meta, color = MaterialTheme.colorScheme.secondary)
                                Text("— P97", style = LeziTypography.Meta, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }

                    SectionHeading(
                        title = "测量记录",
                    )
                    LeziSurfacePanel(
                        Modifier
                            .fillMaxWidth()
                            .animateContentSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        bottomBand = true,
                    ) {
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
                    text = listOfNotNull(
                        growthReferenceNotice(
                            sex = ui.sex,
                            hasReferenceBands = bands.isNotEmpty(),
                            hasMeasurementsOutsideReference =
                                ui.referenceValidUntilMonthExclusive?.let { exclusive ->
                                    ui.points.any { it.monthAge >= exclusive }
                                } == true,
                        ),
                        "WS/T 423—2022 · 按性别 P3/P50/P97 参考带 · " +
                            "早产或特殊疾病请遵医嘱 · 非医疗诊断",
                    ).joinToString("\n"),
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(LeziSpacing.Xxl))
            }
        }
    }

    val activeDraft = draft
    if (activeDraft != null) {
        val isEditing = activeDraft.recordId != null
        LeziAlertDialog(
            onDismissRequest = { if (!busy) closeMeasurementDraft() },
            modifier = Modifier.imePadding(),
            properties = DialogProperties(decorFitsSystemWindows = false),
            title = {
                val linearLabel = ui.birthday?.let { birthday ->
                    growthLinearMeasurementLabel(birthday, activeDraft.measuredAt, zone)
                } ?: "身长/身高"
                Text(
                    when {
                        isEditing && ui.metric == GrowthMetric.WEIGHT -> "修改体重 (kg)"
                        isEditing && ui.metric == GrowthMetric.HEIGHT -> "修改$linearLabel (cm)"
                        ui.metric == GrowthMetric.WEIGHT -> "记录体重 (kg)"
                        else -> "记录$linearLabel (cm)"
                    },
                )
            },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.85f)
                        .verticalScroll(rememberScrollState())
                        .imePadding()
                        .dismissKeyboardOnTap(),
                    verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
                ) {
                    OutlinedTextField(
                        value = activeDraft.valueText,
                        onValueChange = { text ->
                            vm.updateMeasurementDraft(activeDraft.copy(valueText = text))
                        },
                        enabled = !busy,
                        isError = editor.fieldError != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        label = { Text(if (ui.metric == GrowthMetric.WEIGHT) "公斤" else "厘米") },
                        supportingText = editor.fieldError?.let { error ->
                            { Text(error) }
                        },
                    )
                    OutlinedTextField(
                        value = activeDraft.note,
                        onValueChange = { text ->
                            vm.updateMeasurementDraft(activeDraft.copy(note = text.take(200)))
                        },
                        enabled = !busy,
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
                    Row(horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Xs)) {
                        LeziSecondaryButton(
                            label = "修改日期",
                            onClick = { showMeasureDate = true },
                            enabled = !busy,
                        )
                        LeziSecondaryButton(
                            label = "选择时间",
                            onClick = { showMeasureClock = true },
                            enabled = !busy,
                        )
                    }
                    if (isEditing) {
                        LeziSecondaryButton(
                            label = "删除这条测量",
                            onClick = { vm.requestMeasurementDelete() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy,
                        )
                    }
                    editor.operationError?.let {
                        Text(
                            it,
                            modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                            },
                            color = MaterialTheme.colorScheme.error,
                            style = LeziTypography.Meta,
                        )
                    }
                }
            },
            confirmButton = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    LeziPrimaryButton(
                        label = if (isEditing) "保存修改" else "保存",
                        onClick = { vm.saveMeasurement() },
                        enabled = !busy,
                    )
                }
            },
            dismissButton = {
                LeziSecondaryButton(
                    label = "取消",
                    onClick = { closeMeasurementDraft() },
                    enabled = !busy,
                )
            },
        )
    }

    if (editor.deleteConfirmationOpen) {
        LeziAlertDialog(
            onDismissRequest = { if (!busy) vm.cancelMeasurementDelete() },
            title = { Text("删除这条测量？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
                    Text(
                        when (ui.metric) {
                            GrowthMetric.WEIGHT -> "删除后会从体重曲线与记录列表中移除，无法撤销。"
                            GrowthMetric.HEIGHT -> "删除后会从身长/身高曲线与记录列表中移除，无法撤销。"
                        },
                    )
                    editor.operationError?.let { error ->
                        Text(
                            text = error,
                            modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                            },
                            color = MaterialTheme.colorScheme.error,
                            style = LeziTypography.Meta,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = { vm.deleteMeasurement() },
                    modifier = Modifier.heightIn(min = LeziSpacing.Touch),
                ) {
                    Text(
                        if (deleting) "删除中…" else "确认删除",
                        color = MaterialTheme.colorScheme.error,
                        style = LeziTypography.Label,
                    )
                }
            },
            dismissButton = {
                LeziSecondaryButton(
                    label = "取消",
                    onClick = { vm.cancelMeasurementDelete() },
                    enabled = !busy,
                )
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
            onDismissRequest = { if (!busy) showMeasureDate = false },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        dateState.selectedDateMillis?.let { millis ->
                            val selectedDate = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            val dateDecision = RecordTime.selectDate(
                                selectedDate = selectedDate,
                                zone = zone,
                            )
                            val decision = RecordTime.merge(
                                value = current,
                                date = dateDecision.date,
                                hour = current.hour,
                                minute = current.minute,
                                step = 1,
                            )
                            when (decision) {
                                RecordTimeDecision.RejectedGap -> {
                                    vm.reportMeasurementMessage(
                                        "所选日期不存在当前时刻，请改用其他时刻",
                                    )
                                }
                                is RecordTimeDecision.Accepted -> {
                                    vm.updateMeasurementDraft(
                                        draftForPickers.copy(
                                            measuredAt = decision.value.toInstant().toEpochMilli(),
                                        ),
                                    )
                                    vm.reportMeasurementMessage(
                                        if (dateDecision is RecordDateDecision.ClampedToToday) {
                                            "测量日期不能晚于今天，已保留为今天"
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                        }
                        showMeasureDate = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(
                    enabled = !busy,
                    onClick = { showMeasureDate = false },
                ) { Text("取消") }
            },
        ) {
            LeziDatePicker(state = dateState)
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
                val error = RecordTime.pointError(
                    picked.toInstant().toEpochMilli(),
                    RecordTime.currentTimeMillis(),
                )
                if (error != null) {
                    vm.reportMeasurementMessage(error)
                } else {
                    vm.updateMeasurementDraft(
                        draftForPickers.copy(measuredAt = picked.toInstant().toEpochMilli()),
                    )
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
    val whenText = Instant.ofEpochMilli(point.measuredAt)
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Xxs),
            ) {
                Text("修改", style = LeziTypography.Meta, color = MaterialTheme.colorScheme.primary)
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Text(
            whenText,
            style = LeziTypography.Meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        point.note?.takeIf { it.isNotBlank() }?.let { note ->
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

internal fun growthLinearMeasurementLabel(
    birthday: LocalDate,
    measuredAt: Long,
    zone: ZoneId,
): String {
    val measuredDate = Instant.ofEpochMilli(measuredAt).atZone(zone).toLocalDate()
    return if (measuredDate.isBefore(birthday.plusYears(2))) "身长" else "身高"
}

internal fun growthReferenceNotice(
    sex: Sex?,
    hasReferenceBands: Boolean,
    hasMeasurementsOutsideReference: Boolean,
): String? = when {
    sex == null || sex == Sex.UNKNOWN -> "未设置用于生长参考的性别，仅显示个人趋势"
    hasMeasurementsOutsideReference -> "7岁及以上的测量仅显示个人趋势"
    !hasReferenceBands -> "参考数据暂不可用，仅显示个人趋势"
    else -> null
}

internal fun growthChartAccessibilitySummary(
    points: List<MeasurePoint>,
    metric: GrowthMetric,
    hasReferenceBands: Boolean,
): String {
    val metricLabel = when (metric) {
        GrowthMetric.WEIGHT -> "体重"
        GrowthMetric.HEIGHT -> "身长/身高"
    }
    if (points.isEmpty()) return "${metricLabel}趋势图，暂无测量"

    val monthMin = points.minOf(MeasurePoint::monthAge)
    val monthMax = points.maxOf(MeasurePoint::monthAge)
    val valueMin = points.minOf(MeasurePoint::value)
    val valueMax = points.maxOf(MeasurePoint::value)
    val monthRange = if (monthMin == monthMax) {
        "月龄 ${formatGrowthNumber(monthMin, 1)} 个月"
    } else {
        "月龄 ${formatGrowthNumber(monthMin, 1)} 到 ${formatGrowthNumber(monthMax, 1)} 个月"
    }
    val decimals = if (metric == GrowthMetric.WEIGHT) 2 else 1
    val unit = if (metric == GrowthMetric.WEIGHT) "kg" else "cm"
    val valueRange = if (valueMin == valueMax) {
        "数值 ${formatGrowthNumber(valueMin, decimals)} $unit"
    } else {
        "数值 ${formatGrowthNumber(valueMin, decimals)} 到 ${formatGrowthNumber(valueMax, decimals)} $unit"
    }
    return buildString {
        append("${metricLabel}趋势图，共 ${points.size} 次测量；$monthRange；$valueRange")
        if (hasReferenceBands) append("；包含 WS/T 423—2022 P3、P50、P97 参考曲线")
    }
}

private fun formatGrowthNumber(value: Float, decimals: Int): String =
    String.format(Locale.CHINA, "%.${decimals}f", value)

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
            .padding(top = 12.dp)
            .semantics {
                contentDescription = growthChartAccessibilitySummary(
                    points = points,
                    metric = metric,
                    hasReferenceBands = bands.isNotEmpty(),
                )
            },
    ) {
        val maxMonth = max(
            24f,
            max(
                points.maxOfOrNull { it.monthAge } ?: 12f,
                bands.maxOfOrNull { it.month } ?: 0f,
            ),
        )
        val yValues = points.map { it.value } + bands.flatMap { listOf(it.p3, it.p50, it.p97) }
        val yMax = (yValues.maxOrNull() ?: if (metric == GrowthMetric.WEIGHT) 12f else 90f) * 1.1f
        val yMin = (yValues.minOrNull() ?: if (metric == GrowthMetric.WEIGHT) 2f else 40f) * 0.9f
        fun x(m: Float) = size.width * (m / maxMonth).coerceIn(0f, 1f)
        fun y(v: Float) = size.height - ((v - yMin) / (yMax - yMin).coerceAtLeast(0.1f)) * size.height

        val gridStroke = 1.dp.toPx()
        val bandStroke = (if (journal) 1.5.dp else 2.dp).toPx()
        val medianStroke = (if (journal) 2.dp else 2.5.dp).toPx()
        val pointRadius = (if (journal) 2.5.dp else 3.5.dp).toPx()
        val seriesStroke = (if (journal) 1.5.dp else 2.dp).toPx()

        val gridLines = if (journal) 6 else 3
        for (i in 0..gridLines) {
            val yy = size.height * i / gridLines.toFloat()
            drawLine(
                ext.chartGrid.copy(alpha = if (journal) 0.7f else 0.35f),
                Offset(0f, yy),
                Offset(size.width, yy),
                strokeWidth = gridStroke,
            )
        }
        if (journal) {
            for (i in 0..6) {
                val xx = size.width * i / 6f
                drawLine(
                    ext.chartGrid.copy(alpha = 0.5f),
                    Offset(xx, 0f),
                    Offset(xx, size.height),
                    strokeWidth = gridStroke,
                )
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
            drawPath(bandPath { it.p3 }, muted.copy(alpha = 0.65f), style = Stroke(bandStroke))
            drawPath(
                bandPath { it.p50 },
                percentile,
                style = Stroke(medianStroke),
            )
            drawPath(bandPath { it.p97 }, muted.copy(alpha = 0.65f), style = Stroke(bandStroke))
        }

        val line = Path()
        points.forEachIndexed { i, pt ->
            val xx = x(pt.monthAge)
            val yy = y(pt.value)
            if (i == 0) line.moveTo(xx, yy) else line.lineTo(xx, yy)
            drawCircle(accent, radius = pointRadius, center = Offset(xx, yy))
        }
        if (points.size >= 2) drawPath(line, accent, style = Stroke(width = seriesStroke))
    }
}
