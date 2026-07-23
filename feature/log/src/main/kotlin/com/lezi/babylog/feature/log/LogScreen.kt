package com.lezi.babylog.feature.log

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.core.ui.RecordSection
import com.lezi.babylog.core.ui.RecordSummaryStrip
import com.lezi.babylog.core.ui.RecordSummaryValue
import com.lezi.babylog.core.ui.RecordTypeIcon
import com.lezi.babylog.core.ui.UiTags
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.core.ui.presentationSummary
import com.lezi.babylog.core.ui.presentationTone
import com.lezi.babylog.designsystem.LeziCard
import com.lezi.babylog.designsystem.LeziRecordGlyph
import com.lezi.babylog.designsystem.LeziRecordGlyphIcon
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziShapes
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziThemeExt
import com.lezi.babylog.designsystem.LeziTone
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.PageScaffoldBackground
import com.lezi.babylog.designsystem.RecordRow
import com.lezi.babylog.designsystem.SectionHeading
import com.lezi.babylog.designsystem.StateContainer
import com.lezi.babylog.designsystem.StateKind
import com.lezi.babylog.designsystem.SummaryMetric
import com.lezi.babylog.designsystem.TimelineLaneSegment
import com.lezi.babylog.designsystem.TimelineRailCard
import com.lezi.babylog.designsystem.leziRecordColor
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.DailySummary
import com.lezi.babylog.domain.aggregateDaily
import com.lezi.babylog.domain.formatClock
import com.lezi.babylog.domain.payloadBool
import com.lezi.babylog.domain.payloadInt
import com.lezi.babylog.domain.relativeTimeLabel
import com.lezi.babylog.feature.settings.NextFeedScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
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
    val openSleep: Record? = null,
    val saving: Boolean = false,
    val error: String? = null,
)

sealed interface LogEvent {
    data class Saved(val message: String) : LogEvent
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
            combine(
                careLog.observeDayRecords(baby.id, day, zone),
                careLog.observeOpenSleep(baby.id),
            ) { records, openSleep ->
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
                    openSleep = openSleep,
                    saving = isSaving,
                    error = err,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogUiState())

    fun setExternalDay(day: LocalDate) {
        dayFlow.value = day
    }

    fun retry() {
        error.value = null
    }

    internal fun saveQuickRecord(draft: QuickRecordDraft) {
        if (!saving.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                error.value = null
                val state = uiState.value
                val babyId = state.baby?.id ?: error("无宝宝")
                val command = draft.toSaveCommand()
                val statefulSleep = command.type == RecordType.SLEEP &&
                    draft.sleepAction in setOf(
                        SleepDraftAction.SleepDown,
                        SleepDraftAction.WakeUp,
                    )
                if (statefulSleep) {
                    careLog.confirmSleep(
                        babyId = babyId,
                        expectedOpenSleepId = command.existingRecordId,
                        timestamp = command.timestamp,
                        endTimestamp = command.endTimestamp,
                        note = command.note,
                        payloadJson = command.payloadJson,
                    )
                } else if (command.existingRecordId != null) {
                    careLog.updateRecord(
                        id = command.existingRecordId,
                        timestamp = command.timestamp,
                        endTimestamp = command.endTimestamp,
                        note = command.note,
                        payloadJson = command.payloadJson,
                    )
                } else {
                    careLog.addRecord(
                        babyId,
                        command.type,
                        timestamp = command.timestamp,
                        endTimestamp = command.endTimestamp,
                        note = command.note,
                        payloadJson = command.payloadJson,
                    )
                }
                if (
                    state.day == LocalDate.now(zone) &&
                    command.type in setOf(
                        RecordType.NURSING,
                        RecordType.FORMULA,
                        RecordType.PUMPED_FEED,
                    )
                ) {
                    runCatching { nextFeed.scheduleAfterFeed(app) }
                }
                val message = when (draft.sleepAction) {
                    SleepDraftAction.SleepDown -> "已开始睡眠"
                    SleepDraftAction.WakeUp -> "已记录醒来"
                    SleepDraftAction.Manual, null -> "已记录${command.type.presentation.label}"
                }
                _events.emit(LogEvent.Saved(message))
            } catch (throwable: Throwable) {
                val message = throwable.message ?: "保存失败"
                error.value = message
                _events.emit(LogEvent.Toast(message))
            } finally {
                saving.value = false
            }
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
    onOpenTimer: (note: String, amountMl: String) -> Unit,
    onOpenEdit: (Long) -> Unit,
    onGoToday: () -> Unit,
    externalDay: LocalDate? = null,
    vm: LogViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showMore by remember { mutableStateOf(false) }
    var pendingDraft by remember { mutableStateOf<QuickRecordDraft?>(null) }
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val ext = LeziThemeExt.colors
    val journal = LeziThemeExt.isJournal

    fun openComposer(type: RecordType) {
        vm.retry()
        val openSleep = state.openSleep
        val wakingCurrentSleep = type == RecordType.SLEEP && openSleep != null
        val clickedAt = timestampOnDate(
            date = if (wakingCurrentSleep) today else state.day,
            zone = zone,
        )
        pendingDraft = if (wakingCurrentSleep) {
            QuickRecordDraft.wakeSleep(checkNotNull(openSleep), clickedAt)
        } else {
            val lastAmount = state.records
                .firstOrNull { it.type == type }
                ?.let { payloadInt(it.payloadJson, "amount_ml") }
                ?.takeIf { it > 0 }
            QuickRecordDraft.create(
                type = type,
                timestamp = clickedAt,
                lastAmountMl = lastAmount,
                historical = state.day != today,
            )
        }
    }

    LaunchedEffect(externalDay) {
        if (externalDay != null && externalDay != state.day) {
            vm.setExternalDay(externalDay)
        }
    }

    LaunchedEffect(Unit) {
        vm.events.collect { ev ->
            when (ev) {
                is LogEvent.Saved -> {
                    pendingDraft = null
                    launch { snackbar.showSnackbar(ev.message) }
                }
                is LogEvent.Toast -> launch { snackbar.showSnackbar(ev.message) }
            }
        }
    }

    val nowMin = if (state.day == today) {
        val t = LocalTime.now()
        t.hour * 60 + t.minute
    } else {
        null
    }

    PageScaffoldBackground {
        Box(Modifier.fillMaxSize().testTag(UiTags.LOG_HOME)) {
            Column(Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(if (journal) 4.dp else LeziSpacing.SectionGap),
                ) {
                    item {
                        Column(Modifier.padding(horizontal = LeziSpacing.Page)) {
                            if (journal) {
                                RecordSummaryStrip(
                                    values = listOf(
                                        RecordSummaryValue(RecordType.FORMULA, "${state.summary.feedMl}", "奶ml"),
                                        RecordSummaryValue(RecordType.NURSING, "${state.summary.nursingMinutes}", "母乳min"),
                                        RecordSummaryValue(
                                            RecordType.SLEEP,
                                            formatMinutes(state.summary.sleepMinutes),
                                            "睡眠",
                                        ),
                                        RecordSummaryValue(RecordType.PEE, "${state.summary.peeCount}", "尿"),
                                        RecordSummaryValue(RecordType.POOP, "${state.summary.poopCount}", "便"),
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
                                            RecordTypeIcon(RecordType.FORMULA)
                                        },
                                    )
                                    SummaryMetric(
                                        value = "${state.summary.nursingMinutes}min",
                                        label = "母乳",
                                        tone = LeziTone.Blue,
                                        modifier = Modifier.weight(1f),
                                        icon = {
                                            RecordTypeIcon(RecordType.NURSING)
                                        },
                                    )
                                    SummaryMetric(
                                        value = formatMinutes(state.summary.sleepMinutes),
                                        label = "睡眠",
                                        tone = LeziTone.Yellow,
                                        modifier = Modifier.weight(1f),
                                        icon = {
                                            RecordTypeIcon(RecordType.SLEEP)
                                        },
                                    )
                                    SummaryMetric(
                                        value = "${state.summary.peeCount}次",
                                        label = "尿尿",
                                        tone = LeziTone.Cream,
                                        modifier = Modifier.weight(1f),
                                        icon = {
                                            RecordTypeIcon(RecordType.PEE)
                                        },
                                    )
                                    SummaryMetric(
                                        value = "${state.summary.poopCount}次",
                                        label = "便便",
                                        tone = LeziTone.Neutral,
                                        modifier = Modifier.weight(1f),
                                        icon = {
                                            RecordTypeIcon(RecordType.POOP)
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
                                LeziSecondaryButton(
                                    "返回今天",
                                    onClick = onGoToday,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }

                    item {
                        Column(Modifier.padding(horizontal = LeziSpacing.Page)) {
                            SectionHeading(
                                eyebrow = if (journal) null else "按时间排序",
                                title = if (journal) "记录明细" else "当日记录",
                                meta = if (journal) "新 → 旧" else null,
                            )
                        }
                    }

                    when {
                        state.loading -> item {
                            StateContainer(
                                kind = StateKind.Loading,
                                title = "加载中",
                                message = "正在读取当日记录…",
                                modifier = Modifier.padding(horizontal = LeziSpacing.Page),
                            )
                        }
                        state.error != null && state.records.isEmpty() -> item {
                            StateContainer(
                                kind = StateKind.Error,
                                title = "出错了",
                                message = state.error ?: "",
                                actionLabel = "重试",
                                onAction = vm::retry,
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
                                leading = {
                                    RecordTypeIcon(r.type)
                                },
                                onClick = { onOpenEdit(r.id) },
                                modifier = Modifier
                                    .padding(horizontal = LeziSpacing.Page)
                                    .semantics {
                                        contentDescription = "编辑${r.type.presentation.label}"
                                    },
                            )
                        }
                    }

                }
            }

            OneHandQuickDock(
                preferredHand = state.settings.preferredHand,
                timerEnabled = state.settings.timerEnabled,
                sleepRunning = state.openSleep != null,
                saving = state.saving,
                onNursing = { openComposer(RecordType.NURSING) },
                onPee = { openComposer(RecordType.PEE) },
                onSleep = { openComposer(RecordType.SLEEP) },
                onFormula = { openComposer(RecordType.FORMULA) },
                onMore = { showMore = true },
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 92.dp))
        }
    }

    if (showMore) {
        ModalBottomSheet(
            onDismissRequest = { showMore = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            MoreSheet(
                onPick = { type ->
                    showMore = false
                    openComposer(type)
                },
            )
        }
    }

    pendingDraft?.let { draft ->
        ModalBottomSheet(
            onDismissRequest = {
                if (!state.saving) pendingDraft = null
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            QuickRecordSheet(
                draft = draft,
                amountStepMl = state.settings.amountStepMl,
                timeStepMin = state.settings.timeStepMin,
                saving = state.saving,
                saveError = state.error,
                canStartNursingTimer = draft.type == RecordType.NURSING &&
                    state.settings.timerEnabled &&
                    state.day == today,
                onDraftChange = {
                    vm.retry()
                    pendingDraft = it
                },
                onDismiss = {
                    if (!state.saving) pendingDraft = null
                },
                onConfirm = {
                    vm.saveQuickRecord(it)
                },
                onStartNursingTimer = {
                    pendingDraft = null
                    onOpenTimer(draft.note, draft.nursingAmountMl)
                },
            )
        }
    }
}

internal enum class OneHandQuickAction {
    Pee,
    Sleep,
    Nursing,
    Formula,
    More,
}

/**
 * The first item is placed nearest the selected thumb edge. The high-frequency
 * pee composer therefore remains the easiest target for either hand.
 */
internal fun oneHandQuickActionOrder(
    preferredHand: String,
    timerEnabled: Boolean,
): List<OneHandQuickAction> {
    val thumbFirst = buildList {
        add(OneHandQuickAction.Pee)
        add(OneHandQuickAction.Sleep)
        if (timerEnabled) add(OneHandQuickAction.Nursing)
        add(OneHandQuickAction.Formula)
        add(OneHandQuickAction.More)
    }
    return if (preferredHand == "left") thumbFirst else thumbFirst.reversed()
}

@Composable
private fun OneHandQuickDock(
    preferredHand: String,
    timerEnabled: Boolean,
    sleepRunning: Boolean,
    saving: Boolean,
    onNursing: () -> Unit,
    onPee: () -> Unit,
    onSleep: () -> Unit,
    onFormula: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val journal = LeziThemeExt.isJournal
    val actions = oneHandQuickActionOrder(preferredHand, timerEnabled)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (journal) 0.dp else 8.dp, vertical = 4.dp)
            .testTag("one_hand_quick_dock_$preferredHand"),
        shape = if (journal) LeziShapes.JournalCard else LeziShapes.Lg,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
        shadowElevation = if (journal) 2.dp else 8.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            actions.forEach { action ->
                val label = when (action) {
                    OneHandQuickAction.Pee -> "尿尿"
                    OneHandQuickAction.Sleep -> if (sleepRunning) "醒来" else "睡眠"
                    OneHandQuickAction.Nursing -> "母乳"
                    OneHandQuickAction.Formula -> "配方奶"
                    OneHandQuickAction.More -> "更多"
                }
                val recordType = when (action) {
                    OneHandQuickAction.Pee -> RecordType.PEE
                    OneHandQuickAction.Sleep -> RecordType.SLEEP
                    OneHandQuickAction.Nursing -> RecordType.NURSING
                    OneHandQuickAction.Formula -> RecordType.FORMULA
                    OneHandQuickAction.More -> null
                }
                val tint = recordType?.let { leziRecordColor(it.presentation.colorRole) }
                    ?: MaterialTheme.colorScheme.primary
                val enabled = !saving ||
                    action == OneHandQuickAction.Nursing ||
                    action == OneHandQuickAction.More
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 64.dp)
                        .testTag("one_hand_action_${action.name.lowercase()}")
                        .clickable(enabled = enabled) {
                            when (action) {
                                OneHandQuickAction.Pee -> onPee()
                                OneHandQuickAction.Sleep -> onSleep()
                                OneHandQuickAction.Nursing -> onNursing()
                                OneHandQuickAction.Formula -> onFormula()
                                OneHandQuickAction.More -> onMore()
                            }
                        },
                    shape = if (journal) LeziShapes.JournalButton else LeziShapes.Sm,
                    color = if (action == OneHandQuickAction.Pee) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                    } else {
                        Color.Transparent
                    },
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(tint.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (recordType == null) {
                                LeziRecordGlyphIcon(
                                    glyph = LeziRecordGlyph.Other,
                                    tint = tint,
                                )
                            } else {
                                RecordTypeIcon(recordType, tint = tint)
                            }
                        }
                        Text(
                            label,
                            style = LeziTypography.Meta,
                            color = if (enabled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            },
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreSheet(onPick: (RecordType) -> Unit) {
    val groups = RecordSection.entries.map { section ->
        section to RecordType.entries.filter { it.presentation.section == section }
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = LeziSpacing.Md,
            top = LeziSpacing.Md,
            end = LeziSpacing.Md,
            bottom = LeziSpacing.Xxl,
        ),
    ) {
        item {
            Text("添加记录", style = LeziTypography.Title)
            Spacer(Modifier.height(LeziSpacing.Sm))
        }
        groups.forEach { (section, items) ->
            item {
                Column {
                    Text(
                        section.title,
                        style = LeziTypography.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(LeziSpacing.Xs))
                    items.chunked(4).forEach { rowItems ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowItems.forEach { type ->
                                val item = type.presentation
                                val color = leziRecordColor(item.colorRole)
                                LeziCard(
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 64.dp),
                                    onClick = { onPick(type) },
                                    contentPadding = PaddingValues(horizontal = 3.dp, vertical = 5.dp),
                                ) {
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .clearAndSetSemantics {
                                                contentDescription =
                                                    moreRecordContentDescription(type)
                                            },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Box(
                                            Modifier
                                                .size(32.dp)
                                                .clip(LeziShapes.JournalCard)
                                                .background(color.copy(alpha = 0.14f)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            RecordTypeIcon(type, size = 18.dp, tint = color)
                                        }
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            item.label,
                                            style = LeziTypography.Label.copy(
                                                fontSize = 14.sp,
                                                lineHeight = 18.sp,
                                            ),
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                            repeat(4 - rowItems.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

private fun formatMinutes(min: Long): String {
    if (min <= 0) return "0m"
    val h = min / 60
    val m = min % 60
    return if (h == 0L) "${m}m" else if (m == 0L) "${h}h" else "${h}h ${m}m"
}

private fun toneOf(type: RecordType): LeziTone = type.presentationTone()

internal fun typeLabel(type: RecordType): String = type.presentation.label

internal fun typeGlyph(type: RecordType): LeziRecordGlyph = type.presentation.glyph

internal fun moreRecordContentDescription(type: RecordType): String =
    "添加${type.presentation.label}"

internal fun recordSummaryLine(record: Record): String = record.presentationSummary()
