package com.lezi.babylog.feature.log

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.core.ui.UiTags
import com.lezi.babylog.designsystem.LeziCard
import com.lezi.babylog.designsystem.JournalSummaryStrip
import com.lezi.babylog.designsystem.JournalSummaryValue
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziShapes
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziThemeExt
import com.lezi.babylog.designsystem.LeziTone
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.PageScaffoldBackground
import com.lezi.babylog.designsystem.QuickRecordButton
import com.lezi.babylog.designsystem.RecordRow
import com.lezi.babylog.designsystem.SectionHeading
import com.lezi.babylog.designsystem.StateContainer
import com.lezi.babylog.designsystem.StateKind
import com.lezi.babylog.designsystem.SummaryMetric
import com.lezi.babylog.designsystem.TimelineLaneSegment
import com.lezi.babylog.designsystem.TimelineRailCard
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.DailySummary
import com.lezi.babylog.domain.aggregateDaily
import com.lezi.babylog.domain.babyAgeLabel
import com.lezi.babylog.domain.formatClock
import com.lezi.babylog.domain.payloadBool
import com.lezi.babylog.domain.payloadInt
import com.lezi.babylog.domain.relativeTimeLabel
import com.lezi.babylog.feature.settings.NextFeedScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LogUiState(
    val loading: Boolean = true,
    val baby: Baby? = null,
    val babies: List<Baby> = emptyList(),
    val day: LocalDate = LocalDate.now(),
    val records: List<Record> = emptyList(),
    val summary: DailySummary = DailySummary(),
    val sleepLanes: List<TimelineLaneSegment> = emptyList(),
    val feedLanes: List<TimelineLaneSegment> = emptyList(),
    val careLanes: List<TimelineLaneSegment> = emptyList(),
    val settings: SettingsLocal = SettingsLocal(),
    val openSleep: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
)

sealed interface LogEvent {
    data class Toast(val message: String) : LogEvent
}

@HiltViewModel
class LogViewModel @Inject constructor(
    private val careLog: CareLog,
    private val settingsStore: SettingsStore,
    private val nextFeed: NextFeedScheduler,
    @ApplicationContext private val app: Context,
) : ViewModel() {
    private val zone = ZoneId.systemDefault()
    private val dayFlow = MutableStateFlow(LocalDate.now(zone))
    private val saving = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val _events = MutableSharedFlow<LogEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()
    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState = combine(
        combine(
            careLog.observeCurrentBaby(),
            careLog.observeBabies(),
            dayFlow,
            settingsStore.settings,
        ) { baby, babies, day, settings ->
            Quad(baby, babies, day, settings)
        },
        saving,
        error,
    ) { quad, isSaving, err ->
        Triple(quad, isSaving, err)
    }.flatMapLatest { (quad, isSaving, err) ->
        val (baby, babies, day, settings) = quad
        if (baby == null) {
            flowOf(
                LogUiState(
                    loading = false,
                    babies = babies,
                    day = day,
                    settings = settings,
                    saving = isSaving,
                    error = err,
                ),
            )
        } else {
            careLog.observeDayRecords(baby.id, day, zone).map { records ->
                val entities = records.map {
                    com.lezi.babylog.core.database.RecordEntity(
                        id = it.id,
                        clientUuid = it.clientUuid,
                        babyId = it.babyId,
                        type = it.type.key,
                        timestamp = it.timestamp,
                        endTimestamp = it.endTimestamp,
                        note = it.note,
                        createdByUserId = it.createdByUserId,
                        payloadJson = it.payloadJson,
                        schemaVersion = it.schemaVersion,
                        updatedAt = it.updatedAt,
                        deletedAt = it.deletedAt,
                    )
                }
                val summary = aggregateDaily(entities)
                val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
                val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val lanes = buildLanes(records, start, end)
                LogUiState(
                    loading = false,
                    baby = baby,
                    babies = babies,
                    day = day,
                    records = records,
                    summary = summary,
                    sleepLanes = lanes.sleep,
                    feedLanes = lanes.feed,
                    careLanes = lanes.care,
                    settings = settings,
                    openSleep = records.any { it.type == RecordType.SLEEP && it.endTimestamp == null },
                    saving = isSaving,
                    error = err,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogUiState())

    fun shiftDay(delta: Long) {
        dayFlow.value = dayFlow.value.plusDays(delta)
    }

    fun setDay(day: LocalDate) {
        dayFlow.value = day
    }

    fun goToday() {
        dayFlow.value = LocalDate.now(zone)
    }

    fun cycleBaby() {
        viewModelScope.launch {
            val state = uiState.value
            if (state.babies.size < 2) return@launch
            val cur = state.baby?.id
            val idx = state.babies.indexOfFirst { it.id == cur }.takeIf { it >= 0 } ?: 0
            careLog.setCurrentBaby(state.babies[(idx + 1) % state.babies.size].id)
            _events.emit(LogEvent.Toast("已切换到 ${state.babies[(idx + 1) % state.babies.size].nickname}"))
        }
    }

    fun quickAdd(type: RecordType, payloadJson: String = "{}") {
        viewModelScope.launch {
            runCatching {
                saving.value = true
                error.value = null
                val babyId = uiState.value.baby?.id ?: error("无宝宝")
                when (type) {
                    RecordType.SLEEP -> {
                        if (uiState.value.openSleep) {
                            careLog.sleepUp(babyId)
                            _events.emit(LogEvent.Toast("已记录醒来"))
                        } else {
                            careLog.sleepDown(babyId)
                            _events.emit(LogEvent.Toast("已开始睡眠"))
                        }
                    }
                    RecordType.PEE -> {
                        careLog.addRecord(babyId, RecordType.PEE, payloadJson = """{"pee_amount":2}""")
                        _events.emit(LogEvent.Toast("已记尿尿"))
                    }
                    RecordType.POOP -> careLog.addRecord(
                        babyId,
                        RecordType.POOP,
                        payloadJson = """{"stool_amount":3,"stool_consistency":3,"stool_color":0}""",
                    )
                    RecordType.BOTH_DIAPER -> careLog.addRecord(
                        babyId,
                        RecordType.BOTH_DIAPER,
                        payloadJson = """{"pee_amount":2,"stool_amount":3,"stool_consistency":3,"stool_color":0}""",
                    )
                    RecordType.BATH, RecordType.WALK, RecordType.COUGH, RecordType.RASH,
                    RecordType.VOMIT, RecordType.INJURY,
                    -> {
                        careLog.addRecord(babyId, type)
                        _events.emit(LogEvent.Toast("已记录"))
                    }
                    else -> careLog.addRecord(babyId, type, payloadJson = payloadJson)
                }
            }.onFailure {
                error.value = it.message ?: "保存失败"
                _events.emit(LogEvent.Toast(error.value!!))
            }
            saving.value = false
        }
    }

    fun addFormula(ml: Int) {
        viewModelScope.launch {
            runCatching {
                saving.value = true
                val babyId = uiState.value.baby?.id ?: error("无宝宝")
                careLog.addRecord(babyId, RecordType.FORMULA, payloadJson = """{"amount_ml":$ml}""")
                nextFeed.scheduleAfterFeed(app)
                _events.emit(LogEvent.Toast("已记配方奶 ${ml}ml"))
            }.onFailure {
                error.value = it.message
                _events.emit(LogEvent.Toast(it.message ?: "保存失败"))
            }
            saving.value = false
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

private data class Lanes(
    val sleep: List<TimelineLaneSegment>,
    val feed: List<TimelineLaneSegment>,
    val care: List<TimelineLaneSegment>,
)

private fun buildLanes(records: List<Record>, dayStart: Long, dayEnd: Long): Lanes {
    val sleep = mutableListOf<TimelineLaneSegment>()
    val feed = mutableListOf<TimelineLaneSegment>()
    val care = mutableListOf<TimelineLaneSegment>()
    for (r in records) {
        val startMs = r.timestamp.coerceIn(dayStart, dayEnd - 1)
        fun mins(ms: Long) = ((ms - dayStart) / 60_000L).toInt().coerceIn(0, 24 * 60)
        when (r.type) {
            RecordType.SLEEP -> {
                val endMs = (r.endTimestamp ?: System.currentTimeMillis()).coerceIn(dayStart + 1, dayEnd)
                if (endMs > startMs) {
                    sleep += TimelineLaneSegment(mins(startMs), mins(endMs).coerceAtLeast(mins(startMs) + 1), Color(0xFFE09F3E))
                }
            }
            RecordType.FORMULA, RecordType.NURSING, RecordType.PUMPED_FEED -> {
                val endMs = (r.endTimestamp ?: (r.timestamp + 15 * 60_000L)).coerceAtMost(dayEnd)
                feed += TimelineLaneSegment(mins(startMs), mins(endMs).coerceAtLeast(mins(startMs) + 1), Color(0xFF007BAE))
            }
            RecordType.PEE, RecordType.POOP, RecordType.BOTH_DIAPER, RecordType.BATH,
            RecordType.TEMPERATURE, RecordType.MEDICINE,
            -> {
                care += TimelineLaneSegment(mins(startMs), (mins(startMs) + 8).coerceAtMost(24 * 60), Color(0xFF7A9E7E))
            }
            else -> Unit
        }
    }
    return Lanes(sleep, feed, care)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogRoute(
    onOpenTimer: () -> Unit,
    onOpenEdit: (Long) -> Unit,
    onOpenNewEdit: (String) -> Unit,
    onOpenSearch: () -> Unit = {},
    externalDay: LocalDate? = null,
    vm: LogViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showCalendar by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showFormula by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val ext = LeziThemeExt.colors
    val journal = LeziThemeExt.isJournal

    LaunchedEffect(externalDay) {
        if (externalDay != null && externalDay != state.day) {
            vm.setDay(externalDay)
        }
    }

    LaunchedEffect(Unit) {
        vm.events.collect { ev ->
            when (ev) {
                is LogEvent.Toast -> snackbar.showSnackbar(ev.message)
            }
        }
    }

    val nowMin = if (state.day == today) {
        val t = LocalTime.now()
        t.hour * 60 + t.minute
    } else {
        null
    }
    val age = state.baby?.let { babyAgeLabel(it.birthdayEpochDay) }.orEmpty()

    PageScaffoldBackground {
        Box(Modifier.fillMaxSize().testTag(UiTags.LOG_HOME)) {
            Column(Modifier.fillMaxSize()) {
                // Age / local meta under global chrome
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LeziSpacing.Page, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (age.isNotBlank()) "$age · 本地记录" else "本地记录",
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onOpenSearch) {
                        Text("搜索", style = LeziTypography.Label)
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 200.dp),
                    verticalArrangement = Arrangement.spacedBy(if (journal) 4.dp else LeziSpacing.SectionGap),
                ) {
                    item {
                        Column(Modifier.padding(horizontal = LeziSpacing.Page)) {
                            if (journal) {
                                JournalSummaryStrip(
                                    values = listOf(
                                        JournalSummaryValue("${state.summary.feedMl}", "奶ml", LeziTone.Blue),
                                        JournalSummaryValue("${state.summary.nursingMinutes}", "母乳min", LeziTone.Blue),
                                        JournalSummaryValue(formatMinutes(state.summary.sleepMinutes), "睡眠", LeziTone.Cream),
                                        JournalSummaryValue("${state.summary.peeCount}", "尿", LeziTone.Yellow),
                                        JournalSummaryValue("${state.summary.poopCount}", "便", LeziTone.Neutral),
                                    ),
                                )
                            } else {
                                // Prototype glance-five: one row, five metric cards.
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    SummaryMetric(
                                        value = "${state.summary.feedMl}ml",
                                        label = "奶量",
                                        tone = LeziTone.Blue,
                                        modifier = Modifier.weight(1f),
                                        icon = {
                                            com.lezi.babylog.designsystem.LeziGlyphIcon(
                                                com.lezi.babylog.designsystem.LeziGlyph.Bottle,
                                            )
                                        },
                                    )
                                    SummaryMetric(
                                        value = "${state.summary.nursingMinutes}min",
                                        label = "母乳",
                                        tone = LeziTone.Blue,
                                        modifier = Modifier.weight(1f),
                                        icon = {
                                            com.lezi.babylog.designsystem.LeziGlyphIcon(
                                                com.lezi.babylog.designsystem.LeziGlyph.Drop,
                                            )
                                        },
                                    )
                                    SummaryMetric(
                                        value = formatMinutes(state.summary.sleepMinutes),
                                        label = "睡眠",
                                        tone = LeziTone.Yellow,
                                        modifier = Modifier.weight(1f),
                                        icon = {
                                            com.lezi.babylog.designsystem.LeziGlyphIcon(
                                                com.lezi.babylog.designsystem.LeziGlyph.Moon,
                                            )
                                        },
                                    )
                                    SummaryMetric(
                                        value = "${state.summary.peeCount}次",
                                        label = "尿尿",
                                        tone = LeziTone.Cream,
                                        modifier = Modifier.weight(1f),
                                        icon = {
                                            com.lezi.babylog.designsystem.LeziGlyphIcon(
                                                com.lezi.babylog.designsystem.LeziGlyph.Toilet,
                                            )
                                        },
                                    )
                                    SummaryMetric(
                                        value = "${state.summary.poopCount}次",
                                        label = "便便",
                                        tone = LeziTone.Neutral,
                                        modifier = Modifier.weight(1f),
                                        icon = {
                                            com.lezi.babylog.designsystem.LeziGlyphIcon(
                                                com.lezi.babylog.designsystem.LeziGlyph.Pin,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }

                    item {
                        TimelineRailCard(
                            sleep = state.sleepLanes.map { it.copy(color = ext.laneSleep) },
                            feed = state.feedLanes.map { it.copy(color = ext.laneFeed) },
                            care = state.careLanes.map { it.copy(color = ext.laneCare) },
                            recordCount = state.records.size,
                            nowMinOfDay = nowMin,
                            modifier = Modifier.padding(horizontal = LeziSpacing.Page),
                        )
                    }

                    if (state.day != today) {
                        item {
                            Row(Modifier.padding(horizontal = LeziSpacing.Page)) {
                                LeziSecondaryButton("返回今天", onClick = vm::goToday, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }

                    item {
                        Column(Modifier.padding(horizontal = LeziSpacing.Page)) {
                            SectionHeading(
                                eyebrow = if (journal) null else "按时间排序",
                                title = if (journal) "记录明细" else "今日记录",
                                meta = if (journal) "新 → 旧" else null,
                            )
                        }
                    }

                    when {
                        state.loading -> item {
                            StateContainer(
                                kind = StateKind.Loading,
                                title = "加载中",
                                message = "正在读取今日记录…",
                                modifier = Modifier.padding(horizontal = LeziSpacing.Page),
                            )
                        }
                        state.error != null && state.records.isEmpty() -> item {
                            StateContainer(
                                kind = StateKind.Error,
                                title = "出错了",
                                message = state.error ?: "",
                                actionLabel = "重试",
                                onAction = { vm.goToday() },
                                modifier = Modifier.padding(horizontal = LeziSpacing.Page),
                            )
                        }
                        state.records.isEmpty() -> item {
                            StateContainer(
                                kind = StateKind.Empty,
                                title = "还没有记录",
                                message = "点下方快捷入口添加第一条记录",
                                modifier = Modifier.padding(horizontal = LeziSpacing.Page),
                            )
                        }
                        else -> items(state.records, key = { it.id }) { r ->
                            RecordRow(
                                time = formatClock(r.timestamp),
                                title = typeLabel(r.type),
                                summary = recordSummaryLine(r),
                                relative = relativeTimeLabel(r.timestamp),
                                tone = toneOf(r.type),
                                anomaly = payloadBool(r.payloadJson, "anomaly_flag") ||
                                    (r.type == RecordType.SLEEP && r.endTimestamp == null),
                                leading = { Text(typeEmoji(r.type)) },
                                onClick = { onOpenEdit(r.id) },
                                modifier = Modifier.padding(horizontal = LeziSpacing.Page),
                            )
                        }
                    }

                    item {
                        Column(Modifier.padding(horizontal = LeziSpacing.Page)) {
                            SectionHeading(
                                title = if (journal) "快速记录" else "快捷入口",
                                meta = if (journal) "一点即记" else "长按也不迷路",
                            )
                            Spacer(Modifier.height(LeziSpacing.Xs))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(if (journal) 5.dp else 10.dp),
                            ) {
                                if (state.settings.timerEnabled) {
                                    QuickRecordButton(
                                        title = "母乳",
                                        subtitle = if (state.saving) "保存中…" else "左右计时",
                                        tone = LeziTone.Blue,
                                        onClick = onOpenTimer,
                                        modifier = Modifier.weight(1f),
                                        icon = {
                                            com.lezi.babylog.designsystem.LeziGlyphIcon(
                                                com.lezi.babylog.designsystem.LeziGlyph.Bottle,
                                            )
                                        },
                                    )
                                }
                                QuickRecordButton(
                                    title = "尿布",
                                    subtitle = "干湿与便便",
                                    tone = LeziTone.Yellow,
                                    onClick = { onOpenNewEdit(RecordType.BOTH_DIAPER.key) },
                                    modifier = Modifier.weight(1f),
                                    icon = {
                                        com.lezi.babylog.designsystem.LeziGlyphIcon(
                                            com.lezi.babylog.designsystem.LeziGlyph.Drop,
                                        )
                                    },
                                )
                            }
                            Spacer(Modifier.height(if (journal) 5.dp else 10.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(if (journal) 5.dp else 10.dp),
                            ) {
                                QuickRecordButton(
                                    title = if (state.openSleep) "醒来" else "睡眠",
                                    subtitle = if (state.openSleep) "结束本段" else "开始与结束",
                                    tone = LeziTone.Cream,
                                    onClick = { vm.quickAdd(RecordType.SLEEP) },
                                    modifier = Modifier.weight(1f),
                                    icon = {
                                        com.lezi.babylog.designsystem.LeziGlyphIcon(
                                            com.lezi.babylog.designsystem.LeziGlyph.Moon,
                                        )
                                    },
                                )
                                QuickRecordButton(
                                    title = "奶瓶",
                                    subtitle = "奶量与类型",
                                    tone = LeziTone.Neutral,
                                    onClick = { showFormula = true },
                                    modifier = Modifier.weight(1f),
                                    icon = {
                                        com.lezi.babylog.designsystem.LeziGlyphIcon(
                                            com.lezi.babylog.designsystem.LeziGlyph.Bottle,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = LeziSpacing.Lg, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (journal) {
                    FloatingActionButton(
                        onClick = onOpenSearch,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索记录")
                    }
                }
                FloatingActionButton(
                    onClick = { showMore = true },
                    containerColor = ext.fab,
                    contentColor = Color.White,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
                    modifier = Modifier
                        .height(54.dp)
                        .widthIn(min = 94.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        if (!journal) {
                            Text("记录", style = LeziTypography.Label, color = Color.White)
                        }
                    }
                }
            }

            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
        }
    }

    if (showCalendar) {
        val millis = state.day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val picker = rememberDatePickerState(initialSelectedDateMillis = millis)
        DatePickerDialog(
            onDismissRequest = { showCalendar = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let {
                        vm.setDay(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate())
                    }
                    showCalendar = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showCalendar = false }) { Text("取消") } },
        ) { DatePicker(state = picker) }
    }

    if (showMore) {
        ModalBottomSheet(
            onDismissRequest = { showMore = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            MoreSheet(
                onPick = { type ->
                    showMore = false
                    when (type) {
                        RecordType.NURSING -> onOpenTimer()
                        RecordType.FORMULA -> showFormula = true
                        RecordType.BATH, RecordType.WALK, RecordType.COUGH, RecordType.RASH,
                        RecordType.VOMIT, RecordType.INJURY, RecordType.PEE,
                        -> vm.quickAdd(type)
                        else -> onOpenNewEdit(type.key)
                    }
                },
            )
        }
    }

    if (showFormula) {
        ModalBottomSheet(
            onDismissRequest = { showFormula = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            FormulaSheet(
                step = state.settings.amountStepMl,
                lastMl = state.records.firstOrNull { it.type == RecordType.FORMULA }
                    ?.let { payloadInt(it.payloadJson, "amount_ml") }
                    ?.takeIf { it > 0 },
                saving = state.saving,
                onConfirm = { ml ->
                    showFormula = false
                    vm.addFormula(ml)
                },
                onOpenFull = {
                    showFormula = false
                    onOpenNewEdit(RecordType.FORMULA.key)
                },
            )
        }
    }
}

@Composable
private fun MoreSheet(onPick: (RecordType) -> Unit) {
    val groups = listOf(
        "喂养" to listOf(
            RecordType.NURSING to "母乳计时",
            RecordType.FORMULA to "配方奶",
            RecordType.PUMPED_FEED to "挤出乳",
            RecordType.PUMP_EXPRESS to "挤奶",
        ),
        "排泄" to listOf(
            RecordType.PEE to "尿尿",
            RecordType.POOP to "便便",
            RecordType.BOTH_DIAPER to "尿+便",
        ),
        "日常" to listOf(
            RecordType.SLEEP to "睡眠",
            RecordType.TEMPERATURE to "体温",
            RecordType.BATH to "洗澡",
            RecordType.WALK to "散步",
            RecordType.MEMO to "备注",
            RecordType.DIARY to "日记",
        ),
        "健康" to listOf(
            RecordType.MEDICINE to "用药",
            RecordType.HOSPITAL to "就医",
            RecordType.COUGH to "咳嗽",
            RecordType.RASH to "发疹",
            RecordType.VOMIT to "呕吐",
            RecordType.INJURY to "受伤",
            RecordType.VACCINE to "疫苗",
            RecordType.OTHER to "其他",
        ),
        "辅食" to listOf(
            RecordType.BABY_FOOD to "辅食",
            RecordType.SNACK to "点心",
            RecordType.DRINK to "饮料",
        ),
        "成长" to listOf(
            RecordType.HEIGHT to "身高",
            RecordType.WEIGHT to "体重",
            RecordType.HEAD to "头围",
            RecordType.CHEST to "胸围",
            RecordType.FOOT_SIZE to "足长",
        ),
    )
    Column(Modifier.padding(LeziSpacing.Md).padding(bottom = LeziSpacing.Xxl)) {
        Text("添加记录", style = LeziTypography.Title)
        Spacer(Modifier.height(LeziSpacing.Sm))
        groups.forEach { (title, items) ->
            Text(title, style = LeziTypography.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(LeziSpacing.Xs))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                items.forEach { (type, label) ->
                    LeziCard(onClick = { onPick(type) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(label, style = LeziTypography.BodyStrong)
                    }
                }
            }
        }
    }
}

@Composable
private fun FormulaSheet(
    step: Int,
    lastMl: Int?,
    saving: Boolean,
    onConfirm: (Int) -> Unit,
    onOpenFull: () -> Unit,
) {
    var ml by remember(lastMl, step) { mutableStateOf(lastMl?.takeIf { it > 0 } ?: 120) }
    Column(Modifier.padding(LeziSpacing.Lg)) {
        Text("配方奶", style = LeziTypography.Title)
        Spacer(Modifier.height(LeziSpacing.Md))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { ml = (ml - step).coerceAtLeast(1) }) {
                Text("−$step", style = LeziTypography.Title)
            }
            Text("${ml} ml", style = LeziTypography.Display, modifier = Modifier.padding(horizontal = 24.dp))
            TextButton(onClick = { ml = (ml + step).coerceAtMost(999) }) {
                Text("+$step", style = LeziTypography.Title)
            }
        }
        Spacer(Modifier.height(LeziSpacing.Md))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LeziSecondaryButton("详细编辑", onClick = onOpenFull, modifier = Modifier.weight(1f))
            LeziPrimaryButton(
                if (saving) "保存中…" else "记录",
                onClick = { if (!saving) onConfirm(ml) },
                modifier = Modifier.weight(1f),
                enabled = !saving,
            )
        }
        Spacer(Modifier.height(LeziSpacing.Xl))
    }
}

private fun formatMinutes(min: Long): String {
    if (min <= 0) return "0m"
    val h = min / 60
    val m = min % 60
    return if (h == 0L) "${m}m" else if (m == 0L) "${h}h" else "${h}h ${m}m"
}

private fun toneOf(type: RecordType): LeziTone = when (type) {
    RecordType.NURSING, RecordType.FORMULA, RecordType.PUMPED_FEED -> LeziTone.Blue
    RecordType.SLEEP -> LeziTone.Yellow
    RecordType.PEE, RecordType.POOP, RecordType.BOTH_DIAPER, RecordType.BATH -> LeziTone.Cream
    else -> LeziTone.Neutral
}

internal fun typeLabel(type: RecordType): String = when (type) {
    RecordType.NURSING -> "母乳"
    RecordType.FORMULA -> "配方奶"
    RecordType.PUMPED_FEED -> "挤出乳"
    RecordType.PUMP_EXPRESS -> "挤奶"
    RecordType.PEE -> "尿尿"
    RecordType.POOP -> "便便"
    RecordType.BOTH_DIAPER -> "尿+便"
    RecordType.SLEEP -> "睡眠"
    RecordType.TEMPERATURE -> "体温"
    RecordType.MEMO -> "备注"
    RecordType.DIARY -> "日记"
    RecordType.BATH -> "洗澡"
    RecordType.WALK -> "散步"
    RecordType.COUGH -> "咳嗽"
    RecordType.RASH -> "发疹"
    RecordType.VOMIT -> "呕吐"
    RecordType.INJURY -> "受伤"
    RecordType.MEDICINE -> "用药"
    RecordType.HOSPITAL -> "就医"
    RecordType.OTHER -> "其他"
    RecordType.HEIGHT -> "身高"
    RecordType.WEIGHT -> "体重"
    RecordType.BABY_FOOD -> "辅食"
    RecordType.SNACK -> "点心"
    RecordType.DRINK -> "饮料"
    RecordType.VACCINE -> "疫苗"
    RecordType.HEAD -> "头围"
    RecordType.CHEST -> "胸围"
    RecordType.FOOT_SIZE -> "足长"
    else -> type.key
}

internal fun typeEmoji(type: RecordType): String = when (type) {
    RecordType.NURSING -> "🍼"
    RecordType.FORMULA, RecordType.PUMPED_FEED -> "🥛"
    RecordType.PUMP_EXPRESS -> "🫙"
    RecordType.PEE -> "💧"
    RecordType.POOP, RecordType.BOTH_DIAPER -> "💩"
    RecordType.SLEEP -> "😴"
    RecordType.TEMPERATURE -> "🌡️"
    RecordType.MEMO -> "📝"
    RecordType.DIARY -> "📔"
    RecordType.BATH -> "🛁"
    RecordType.WALK -> "🚶"
    RecordType.MEDICINE -> "💊"
    RecordType.HOSPITAL -> "🏥"
    else -> "•"
}

internal fun recordSummaryLine(r: Record): String {
    val p = r.payloadJson
    val base = when (r.type) {
        RecordType.FORMULA, RecordType.PUMPED_FEED, RecordType.PUMP_EXPRESS -> {
            val ml = payloadInt(p, "amount_ml")
            if (ml > 0) "${ml}ml" else ""
        }
        RecordType.NURSING -> {
            val l = payloadInt(p, "left_min")
            val rr = payloadInt(p, "right_min")
            "左${l}分 · 右${rr}分"
        }
        RecordType.PEE -> when (payloadInt(p, "pee_amount")) {
            1 -> "量·小"
            3 -> "量·大"
            else -> "量·中"
        }
        RecordType.POOP, RecordType.BOTH_DIAPER -> {
            val a = payloadInt(p, "stool_amount").takeIf { it > 0 } ?: 3
            val c = payloadInt(p, "stool_consistency").takeIf { it > 0 } ?: 3
            val color = payloadInt(p, "stool_color")
            "量$a · 软硬$c · 色$color"
        }
        RecordType.SLEEP -> {
            val end = r.endTimestamp
            if (end != null) {
                val min = (end - r.timestamp) / 60_000L
                "时长 ${formatMinutes(min)}"
            } else {
                "进行中"
            }
        }
        RecordType.TEMPERATURE -> {
            val c = Regex(""""celsius"\s*:\s*(-?\d+(?:\.\d+)?)""").find(p)?.groupValues?.getOrNull(1)
            c?.let { "${it}℃" }.orEmpty()
        }
        else -> ""
    }
    val note = r.note?.takeIf { it.isNotBlank() }
    return listOfNotNull(base.takeIf { it.isNotBlank() }, note).joinToString(" · ").ifBlank { "轻点编辑" }
}
