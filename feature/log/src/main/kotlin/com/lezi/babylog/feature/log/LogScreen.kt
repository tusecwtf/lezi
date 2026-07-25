package com.lezi.babylog.feature.log

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
import com.lezi.babylog.core.model.MilkPayload
import com.lezi.babylog.core.model.NursingPayload
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.core.model.SleepPayload
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
import com.lezi.babylog.domain.CareAggregation
import com.lezi.babylog.domain.DailySummary
import com.lezi.babylog.domain.formatClock
import com.lezi.babylog.domain.relativeTimeLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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
)

@HiltViewModel
class LogViewModel @Inject constructor(
    private val careLog: CareLog,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private val zone = ZoneId.systemDefault()
    private val dayFlow = MutableStateFlow(LocalDate.now(zone))

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState = combine(
        careLog.observeCurrentBaby(),
        careLog.observeBabies(),
        dayFlow,
        settingsStore.settings,
    ) { baby, babies, day, settings ->
        Quad(baby, babies, day, settings)
    }.flatMapLatest { quad ->
        val (baby, babies, day, settings) = quad
        if (baby == null) {
            flowOf(
                LogUiState(
                    loading = false,
                    babies = babies,
                    day = day,
                    settings = settings,
                ),
            )
        } else {
            combine(
                careLog.observeDayRecords(baby.id, day, zone),
                careLog.observeOpenSleep(baby.id),
            ) { records, openSleep ->
                val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
                val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val summary = CareAggregation.day(records, day, zone).toDailySummary()
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
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogUiState())

    fun setExternalDay(day: LocalDate) {
        dayFlow.value = day
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
    val zone = ZoneId.systemDefault()
    for (r in records) {
        val startMs = r.timestamp.coerceIn(dayStart, dayEnd - 1)
        fun mins(ms: Long) = ((ms - dayStart) / 60_000L).toInt().coerceIn(0, 24 * 60)
        fun clock(ms: Long): String = formatClock(ms, zone)
        when (r.type) {
            RecordType.SLEEP -> {
                val open = r.endTimestamp == null
                val endMs = (r.endTimestamp ?: System.currentTimeMillis()).coerceIn(dayStart + 1, dayEnd)
                if (endMs > startMs) {
                    val startMin = mins(startMs)
                    val endMin = mins(endMs).coerceAtLeast(startMin + 1)
                    val durationMin = ((endMs - startMs) / 60_000L).coerceAtLeast(1)
                    val nap = (r.payload.payload as? SleepPayload)?.isNap == true
                    val title = if (nap) "午睡" else "睡眠"
                    val detail = buildString {
                        append(clock(startMs))
                        append("–")
                        append(if (open) "进行中" else clock(endMs))
                        append(" · ")
                        append(formatDurationMinutes(durationMin))
                        if (open) append("（未结束）")
                    }
                    sleep += TimelineLaneSegment(
                        startMinOfDay = startMin,
                        endMinOfDay = endMin,
                        color = Color(0xFFE09F3E),
                        title = title,
                        detail = detail,
                        isEvent = false,
                    )
                }
            }
            RecordType.FORMULA, RecordType.NURSING, RecordType.PUMPED_FEED,
            RecordType.PUMP_EXPRESS,
            -> {
                // Point-in-time mark: keep start==end so UI draws a pin, not a fake duration bar.
                val startMin = mins(startMs)
                val title = r.type.presentation.label
                val detail = buildString {
                    append(clock(startMs))
                    when (r.type) {
                        RecordType.FORMULA, RecordType.PUMPED_FEED, RecordType.PUMP_EXPRESS -> {
                            val ml = (r.payload.payload as? MilkPayload)?.amountMl ?: 0
                            if (ml > 0) append(" · ${ml}ml")
                        }
                        RecordType.NURSING -> {
                            val payload = r.payload.payload as? NursingPayload
                            val left = payload?.leftMinutes ?: 0
                            val right = payload?.rightMinutes ?: 0
                            if (left + right > 0) append(" · 左${left}分/右${right}分")
                        }
                        else -> Unit
                    }
                    r.note?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                }
                feed += TimelineLaneSegment(
                    startMinOfDay = startMin,
                    endMinOfDay = startMin,
                    color = Color(0xFF007BAE),
                    title = title,
                    detail = detail,
                    isEvent = true,
                )
            }
            RecordType.PEE, RecordType.POOP, RecordType.BOTH_DIAPER, RecordType.BATH,
            RecordType.TEMPERATURE, RecordType.MEDICINE,
            -> {
                val startMin = mins(startMs)
                val notePart = r.note?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                // 尿尿=绿点，便便=黄点；「尿+便」同时落两个圆点便于统计与辨认。
                when (r.type) {
                    RecordType.PEE -> care += TimelineLaneSegment(
                        startMinOfDay = startMin,
                        endMinOfDay = startMin,
                        color = Color(CARE_PEE),
                        title = "尿尿",
                        detail = "${clock(startMs)}$notePart · 护理",
                        isEvent = true,
                    )
                    RecordType.POOP -> care += TimelineLaneSegment(
                        startMinOfDay = startMin,
                        endMinOfDay = startMin,
                        color = Color(CARE_POOP),
                        title = "便便",
                        detail = "${clock(startMs)}$notePart · 护理",
                        isEvent = true,
                    )
                    RecordType.BOTH_DIAPER -> {
                        care += TimelineLaneSegment(
                            startMinOfDay = startMin,
                            endMinOfDay = startMin,
                            color = Color(CARE_PEE),
                            title = "尿尿",
                            detail = "${clock(startMs)}$notePart · 尿+便（尿）",
                            isEvent = true,
                        )
                        care += TimelineLaneSegment(
                            startMinOfDay = startMin,
                            endMinOfDay = startMin,
                            color = Color(CARE_POOP),
                            title = "便便",
                            detail = "${clock(startMs)}$notePart · 尿+便（便）",
                            isEvent = true,
                        )
                    }
                    else -> care += TimelineLaneSegment(
                        startMinOfDay = startMin,
                        endMinOfDay = startMin,
                        color = Color(CARE_OTHER),
                        title = r.type.presentation.label,
                        detail = "${clock(startMs)}$notePart · 护理",
                        isEvent = true,
                    )
                }
            }
            else -> Unit
        }
    }
    return Lanes(sleep, feed, care)
}

private fun formatDurationMinutes(minutes: Long): String {
    if (minutes < 60) return "${minutes}分钟"
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0L) "${h}小时" else "${h}小时${m}分"
}

/** Care-lane pin colors: pee green, poop yellow, other muted green. */
private const val CARE_PEE = 0xFF7A9E7E
private const val CARE_POOP = 0xFFF3B84B
private const val CARE_OTHER = 0xFF8FB894

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogRoute(
    onOpenComposer: (RecordComposerRequest) -> Unit,
    onGoToday: () -> Unit,
    externalDay: LocalDate? = null,
    vm: LogViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var showMore by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val ext = LeziThemeExt.colors
    val journal = LeziThemeExt.isJournal
    val timelineRecords = if (state.settings.timelineOrder == "oldest_first") {
        state.records.sortedBy(Record::timestamp)
    } else {
        state.records.sortedByDescending(Record::timestamp)
    }

    fun openComposer(type: RecordType) {
        val babyId = state.baby?.id ?: return
        val openSleep = state.openSleep
        val wakingCurrentSleep = type == RecordType.SLEEP && openSleep != null
        val clickedAt = RecordTime.newDraftTimestamp(
            selectedDate = if (wakingCurrentSleep) today else state.day,
            zone = zone,
        )
        val lastAmount = state.records
            .firstOrNull { it.type == type }
            ?.let { (it.payload.payload as? MilkPayload)?.amountMl }
            ?.takeIf { it > 0 }
        onOpenComposer(
            RecordComposerRequest.New(
                babyId = babyId,
                type = type,
                timestamp = clickedAt,
                lastAmountMl = lastAmount,
                historical = state.day != today,
                openSleepId = openSleep?.id.takeIf { wakingCurrentSleep },
            ),
        )
    }

    LaunchedEffect(externalDay) {
        if (externalDay != null && externalDay != state.day) {
            vm.setExternalDay(externalDay)
        }
    }

    val nowMin = if (state.day == today) {
        val t = LocalTime.now()
        t.hour * 60 + t.minute
    } else {
        null
    }

    PageScaffoldBackground {
        Column(Modifier.fillMaxSize().testTag(UiTags.LOG_HOME)) {
            LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = LeziSpacing.Md),
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
                            // Keep pee/poop distinct: 绿=尿尿，黄=便便（do not paint all care as laneCare).
                            care = state.careLanes.map { seg ->
                                seg.copy(
                                    color = when (seg.title) {
                                        "便便" -> ext.sun
                                        "尿尿" -> ext.laneCare
                                        else -> ext.laneCare.copy(alpha = 0.75f)
                                    },
                                )
                            },
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
                        state.records.isEmpty() -> item {
                            StateContainer(
                                kind = StateKind.Empty,
                                title = "还没有记录",
                                message = "点下方快捷入口添加第一条记录",
                                modifier = Modifier.padding(horizontal = LeziSpacing.Page),
                            )
                        }
                        else -> items(timelineRecords, key = { it.id }) { r ->
                            RecordRow(
                                time = formatClock(r.timestamp),
                                title = typeLabel(r.type),
                                summary = recordSummaryLine(r),
                                relative = relativeTimeLabel(r.timestamp),
                                tone = toneOf(r.type),
                                anomaly = (r.payload.payload as? SleepPayload)?.anomaly == true ||
                                    (r.type == RecordType.SLEEP && r.endTimestamp == null),
                                leading = {
                                    RecordTypeIcon(r.type)
                                },
                                onClick = {
                                    onOpenComposer(RecordComposerRequest.Edit(r.id))
                                },
                                modifier = Modifier
                                    .padding(horizontal = LeziSpacing.Page)
                                    .semantics {
                                        contentDescription = "编辑${r.type.presentation.label}"
                                    },
                            )
                        }
                    }

            }
            OneHandQuickDock(
                preferredHand = state.settings.preferredHand,
                timerEnabled = state.settings.timerEnabled,
                hiddenTypeKeys = state.settings.hiddenItems,
                configuredTypeKeys = parseConfiguredTypeKeys(state.settings.itemOrderJson),
                sleepRunning = state.openSleep != null,
                onNursing = { openComposer(RecordType.NURSING) },
                onPee = { openComposer(RecordType.PEE) },
                onSleep = { openComposer(RecordType.SLEEP) },
                onFormula = { openComposer(RecordType.FORMULA) },
                onMore = { showMore = true },
            )
        }
    }

    if (showMore) {
        ModalBottomSheet(
            onDismissRequest = { showMore = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        ) {
            MoreSheet(
                settings = state.settings,
                onPick = { type ->
                    showMore = false
                    openComposer(type)
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
    configuredTypeKeys: List<String> = emptyList(),
): List<OneHandQuickAction> {
    val recordActions = buildList {
        add(OneHandQuickAction.Pee)
        add(OneHandQuickAction.Sleep)
        if (timerEnabled) add(OneHandQuickAction.Nursing)
        add(OneHandQuickAction.Formula)
    }.sortedWith(
        compareBy<OneHandQuickAction> { action ->
            val typeKey = action.recordTypeKey()
            configuredTypeKeys.indexOf(typeKey).takeIf { it >= 0 } ?: Int.MAX_VALUE
        }.thenBy { it.ordinal },
    )
    val thumbFirst = buildList {
        addAll(recordActions)
        add(OneHandQuickAction.More)
    }
    return if (preferredHand == "left") thumbFirst else thumbFirst.reversed()
}

private fun OneHandQuickAction.recordTypeKey(): String? = when (this) {
    OneHandQuickAction.Pee -> RecordType.PEE.key
    OneHandQuickAction.Sleep -> RecordType.SLEEP.key
    OneHandQuickAction.Nursing -> RecordType.NURSING.key
    OneHandQuickAction.Formula -> RecordType.FORMULA.key
    OneHandQuickAction.More -> null
}

private fun parseConfiguredTypeKeys(json: String): List<String> =
    runCatching {
        org.json.JSONArray(json).let { array ->
            List(array.length()) { index -> array.optString(index) }
        }
    }.getOrDefault(emptyList())

@Composable
private fun OneHandQuickDock(
    preferredHand: String,
    timerEnabled: Boolean,
    hiddenTypeKeys: Set<String>,
    configuredTypeKeys: List<String>,
    sleepRunning: Boolean,
    onNursing: () -> Unit,
    onPee: () -> Unit,
    onSleep: () -> Unit,
    onFormula: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val journal = LeziThemeExt.isJournal
    val actions = oneHandQuickActionOrder(
        preferredHand = preferredHand,
        timerEnabled = timerEnabled,
        configuredTypeKeys = configuredTypeKeys,
    ).filter { action ->
        val typeKey = action.recordTypeKey()
        typeKey == null || typeKey !in hiddenTypeKeys
    }
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
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 64.dp)
                        .testTag("one_hand_action_${action.name.lowercase()}")
                        .clickable {
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
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreSheet(
    settings: SettingsLocal,
    onPick: (RecordType) -> Unit,
) {
    val configuredOrder = remember(settings.itemOrderJson) {
        runCatching {
            org.json.JSONArray(settings.itemOrderJson).let { array ->
                List(array.length()) { index -> array.optString(index) }
            }
        }.getOrDefault(emptyList())
    }
    val groups = RecordSection.entries.map { section ->
        section to RecordType.entries.filter {
            it.presentation.section == section && it.key !in settings.hiddenItems
        }.sortedWith(
            compareBy<RecordType> {
                configuredOrder.indexOf(it.key).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE
            }.thenBy { it.ordinal },
        )
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
            Text(
                "常用补充",
                style = LeziTypography.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(LeziSpacing.Xs))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    RecordType.POOP,
                    RecordType.TEMPERATURE,
                    RecordType.WEIGHT,
                    RecordType.MEMO,
                ).forEach { type ->
                    MoreTypeCard(
                        type = type,
                        onClick = { onPick(type) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(LeziSpacing.Md))
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
                                MoreTypeCard(
                                    type = type,
                                    onClick = { onPick(type) },
                                    modifier = Modifier.weight(1f),
                                )
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

@Composable
private fun MoreTypeCard(
    type: RecordType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = type.presentation
    val color = leziRecordColor(item.colorRole)
    LeziCard(
        modifier = modifier.heightIn(min = 64.dp),
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 3.dp, vertical = 5.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {
                    contentDescription = moreRecordContentDescription(type)
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
                style = LeziTypography.Label.copy(fontSize = 14.sp, lineHeight = 18.sp),
                maxLines = 1,
            )
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
