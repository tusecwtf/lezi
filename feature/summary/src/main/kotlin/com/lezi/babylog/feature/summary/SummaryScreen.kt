package com.lezi.babylog.feature.summary

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.designsystem.LeziCard
import com.lezi.babylog.designsystem.LeziShapes
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziSurfacePanel
import com.lezi.babylog.designsystem.LeziThemeExt
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.PageHero
import com.lezi.babylog.designsystem.PageScaffoldBackground
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.WeekSummary
import com.lezi.babylog.domain.weekStartFor
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncTrigger
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
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
import kotlinx.coroutines.launch

enum class SummaryRange(
    val label: String,
    val periodLabel: String,
    val dayCount: Int,
) {
    Day("日", "当日", 1),
    Week("周", "近 7 天", 7),
    Month("月", "近 30 天", 30),
}

/** Totals for the selected anchor date only (shown on charts / metric cards). */
data class ChartWindowTotals(
    val dayFeedMl: Int = 0,
    val dayNursingMin: Long = 0,
    val dayFeedCount: Int = 0,
    val daySleepMin: Long = 0,
    val daySleepSegments: Int = 0,
    val dayPee: Int = 0,
    val dayPoop: Int = 0,
) {
    val dayDiaper: Int get() = dayPee + dayPoop
}

data class SummaryTotals(
    val feedMl: Int = 0,
    val nursingMin: Long = 0,
    val feedCount: Int = 0,
    val sleepMin: Long = 0,
    val sleepSegments: Int = 0,
    val pee: Int = 0,
    val poop: Int = 0,
    val tempAvg: Double? = null,
    val tempDays: Int = 0,
    val dayValuesFeed: List<Float> = emptyList(),
    val dayValuesSleep: List<Float> = emptyList(),
    /** Combined pee+poop per day (kept for empty checks / a11y). */
    val dayValuesDiaper: List<Float> = emptyList(),
    val dayValuesPee: List<Float> = emptyList(),
    val dayValuesPoop: List<Float> = emptyList(),
    val dayValuesTemp: List<Float> = emptyList(),
    val feedTimeBuckets: List<Float> = List(4) { 0f },
    val chartWindows: ChartWindowTotals = ChartWindowTotals(),
)

data class SummaryUi(
    val calculating: Boolean = true,
    val range: SummaryRange = SummaryRange.Day,
    val anchorDate: LocalDate = LocalDate.now(),
    val rangeStartDate: LocalDate = anchorDate,
    val totals: SummaryTotals = SummaryTotals(),
    val previousWeekTotals: SummaryTotals? = null,
    val week: WeekSummary? = null,
    val showAvgSleep: Boolean = false,
    val comparePrevWeek: Boolean = false,
    val empty: Boolean = true,
    val babyName: String = "",
)

private data class SummaryPreferences(
    val weekStart: Int,
    val showAvgSleep: Boolean,
    val comparePrevWeek: Boolean,
)

private data class SummaryRequest(
    val range: SummaryRange,
    val anchorDate: LocalDate,
    val preferences: SummaryPreferences,
    val baby: Baby?,
)

@HiltViewModel
class SummaryViewModel @Inject constructor(
    private val careLog: CareLog,
    private val settings: SettingsStore,
    private val syncPort: SyncPort,
) : ViewModel() {
    private val zone = ZoneId.systemDefault()
    private val aggregationEngine = SummaryAggregationEngine()
    private val range = MutableStateFlow(SummaryRange.Day)
    private val anchorDate = MutableStateFlow(LocalDate.now(zone))
    private val preferences = combine(
        settings.settings,
        settings.showAvgSleep,
        settings.comparePrevWeek,
    ) { local, showAvgSleep, comparePrevWeek ->
        SummaryPreferences(
            weekStart = local.weekStart,
            showAvgSleep = showAvgSleep,
            comparePrevWeek = comparePrevWeek,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val ui = combine(
        range,
        anchorDate,
        preferences,
        careLog.observeCurrentBaby(),
    ) { selectedRange, anchor, preferences, baby ->
        SummaryRequest(selectedRange, anchor, preferences, baby)
    }.flatMapLatest { request ->
        val selectedRange = request.range
        val anchor = request.anchorDate
        val baby = request.baby
        val preferences = request.preferences
        if (baby == null) {
            flowOf(
                SummaryUi(
                    calculating = false,
                    range = selectedRange,
                    anchorDate = anchor,
                    rangeStartDate = selectedRange.startDate(anchor, preferences.weekStart),
                    showAvgSleep = preferences.showAvgSleep,
                    comparePrevWeek = preferences.comparePrevWeek,
                    empty = true,
                ),
            )
        } else {
            val rangeStart = selectedRange.startDate(anchor, preferences.weekStart)
            val detailStart = weekStartFor(anchor, preferences.weekStart)
            val compareStart = if (
                selectedRange == SummaryRange.Week && preferences.comparePrevWeek
            ) {
                rangeStart.minusDays(7)
            } else {
                rangeStart
            }
            val queryStart = minOf(rangeStart, detailStart, compareStart)
            val queryEnd = maxOf(
                rangeStart.plusDays(selectedRange.dayCount.toLong()),
                detailStart.plusDays(7),
            )
            careLog.observeRecords(
                babyId = baby.id,
                startDayInclusive = queryStart,
                endDayExclusive = queryEnd,
                zone = zone,
            ).map { records ->
                SummaryAggregationRequest(
                    records = records,
                    range = selectedRange,
                    anchorDate = anchor,
                    weekStartDay = preferences.weekStart,
                    showAvgSleep = preferences.showAvgSleep,
                    comparePrevWeek = preferences.comparePrevWeek,
                    babyName = baby.nickname,
                    zone = zone,
                )
            }.calculateLatest(aggregationEngine)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), SummaryUi())

    val syncStatus = syncPort.status().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SyncStatus.Disabled,
    )

    fun setRange(r: SummaryRange) {
        range.value = r
    }

    fun setAnchorDate(day: LocalDate) {
        anchorDate.value = day
    }

    fun refresh() {
        viewModelScope.launch { syncPort.sync(SyncTrigger.PullToRefresh) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryRoute(
    anchorDate: LocalDate,
    vm: SummaryViewModel = hiltViewModel(),
) {
    LaunchedEffect(anchorDate) {
        vm.setAnchorDate(anchorDate)
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val syncStatus by vm.syncStatus.collectAsStateWithLifecycle()
    if (ui.calculating) {
        PageScaffoldBackground {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(LeziSpacing.Page),
                contentAlignment = Alignment.Center,
            ) {
                com.lezi.babylog.designsystem.StateContainer(
                    kind = com.lezi.babylog.designsystem.StateKind.Loading,
                    title = "正在计算汇总",
                    message = "正在整理护理记录，请稍候。",
                )
            }
        }
        return
    }
    val ext = LeziThemeExt.colors
    val journal = LeziThemeExt.isJournal
    val t = ui.totals
    val chartDates = List(ui.range.dayCount) { offset ->
        ui.rangeStartDate.plusDays(offset.toLong())
    }

    PageScaffoldBackground {
        PullToRefreshBox(
            isRefreshing = syncStatus == SyncStatus.Syncing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = if (journal) 0.dp else LeziSpacing.Page,
                        vertical = LeziSpacing.Page,
                    ),
                verticalArrangement = Arrangement.spacedBy(if (journal) 0.dp else LeziSpacing.Sm),
            ) {
                Column(
                    Modifier.padding(horizontal = if (journal) LeziSpacing.Page else 0.dp),
                ) {
                    PageHero(
                        eyebrow = "",
                        title = "汇总",
                    )

                    if (syncStatus == SyncStatus.Error) {
                        Text(
                            "同步遇到问题，本机汇总仍可使用",
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    RangeTabs(
                        selected = ui.range,
                        onSelect = vm::setRange,
                    )
                }

            val windows = t.chartWindows
            SummaryKpiStrip(
                feedValue = if (windows.dayFeedCount == 0 && windows.dayFeedMl == 0) {
                    "0次"
                } else {
                    "${windows.dayFeedCount}次"
                },
                feedDetail = if (windows.dayFeedMl == 0 && windows.dayNursingMin == 0L) {
                    "当日暂无详情"
                } else {
                    formatFeedWindowTotal(windows.dayFeedMl, windows.dayNursingMin)
                },
                sleepValue = formatMin(windows.daySleepMin),
                sleepDetail = if (windows.daySleepSegments == 0) {
                    "当日 0 段"
                } else {
                    "当日 ${windows.daySleepSegments} 段"
                },
                diaperValue = "${windows.dayDiaper}",
                diaperDetail = "当日 尿 ${windows.dayPee} · 便 ${windows.dayPoop}",
            )

            if (ui.range != SummaryRange.Day && ui.showAvgSleep) {
                val averageSleep = t.sleepMin / ui.range.dayCount.coerceAtLeast(1)
                Text(
                    "日均睡眠 ${formatMin(averageSleep)}",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = if (journal) LeziSpacing.Page else 0.dp),
                )
            }

            if (ui.range == SummaryRange.Week && ui.comparePrevWeek) {
                val previous = ui.previousWeekTotals
                LeziSurfacePanel(Modifier.fillMaxWidth(), bottomBand = true) {
                    Text("对比上周", style = LeziTypography.TitleSm)
                    if (previous == null) {
                        Text(
                            "上周暂无记录",
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "喂养 ${formatSigned(t.feedMl - previous.feedMl, "ml")} · " +
                                "睡眠 ${formatSigned(t.sleepMin - previous.sleepMin, "分钟")}",
                            style = LeziTypography.Body,
                        )
                        Text(
                            "尿尿 ${formatSigned(t.pee - previous.pee, "次")} · " +
                                "便便 ${formatSigned(t.poop - previous.poop, "次")}",
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (journal && ui.week != null) {
                JournalWeekGrid(ui.week!!)
            }

            val feedChartTotal = when (ui.range) {
                SummaryRange.Day -> formatFeedWindowTotal(windows.dayFeedMl, windows.dayNursingMin)
                SummaryRange.Week, SummaryRange.Month ->
                    formatFeedWindowTotal(t.feedMl, t.nursingMin)
            }
            val sleepChartTotal = when (ui.range) {
                SummaryRange.Day -> formatMin(windows.daySleepMin)
                SummaryRange.Week, SummaryRange.Month -> formatMin(t.sleepMin)
            }
            val diaperChartTotal = when (ui.range) {
                SummaryRange.Day -> formatDiaperTotal(windows.dayPee, windows.dayPoop)
                SummaryRange.Week, SummaryRange.Month -> formatDiaperTotal(t.pee, t.poop)
            }
            val chartTotalScope = when (ui.range) {
                SummaryRange.Day -> "当日"
                SummaryRange.Week -> "本周"
                SummaryRange.Month -> "本月"
            }

            LeziSurfacePanel(Modifier.fillMaxWidth(), bottomBand = true) {
                ChartCardHeader(
                    title = "喂养",
                    scopeLabel = chartTotalScope,
                    totalValue = feedChartTotal,
                )
                Spacer(Modifier.height(12.dp))
                if (t.dayValuesFeed.all { it <= 0f } && t.nursingMin == 0L) {
                    com.lezi.babylog.designsystem.StateContainer(
                        kind = com.lezi.babylog.designsystem.StateKind.Empty,
                        title = "范围内暂无喂养记录",
                        message = "换一周或去记录页添加喂养。",
                    )
                } else {
                    MiniBarChart(
                        values = t.dayValuesFeed,
                        dates = chartDates,
                        metricLabel = "喂养量",
                        color = ext.laneFeed,
                        valueFormatter = { v -> if (v <= 0f) "" else "${v.toInt()}ml" },
                    )
                }
            }

            LeziSurfacePanel(Modifier.fillMaxWidth(), bottomBand = true) {
                ChartCardHeader(
                    title = "睡眠",
                    scopeLabel = chartTotalScope,
                    totalValue = sleepChartTotal,
                )
                Spacer(Modifier.height(8.dp))
                if (t.dayValuesSleep.all { it <= 0f }) {
                    com.lezi.babylog.designsystem.StateContainer(
                        kind = com.lezi.babylog.designsystem.StateKind.Empty,
                        title = "范围内暂无已完成睡眠记录",
                        message = "完成的睡眠会按天汇总到这里。",
                    )
                } else {
                    MiniBarChart(
                        values = t.dayValuesSleep,
                        dates = chartDates,
                        metricLabel = "睡眠分钟",
                        color = ext.laneSleep,
                        valueFormatter = { v ->
                            if (v <= 0f) "" else formatMin(v.toLong())
                        },
                    )
                }
            }

            LeziSurfacePanel(Modifier.fillMaxWidth(), bottomBand = true) {
                ChartCardHeader(
                    title = "尿布",
                    scopeLabel = chartTotalScope,
                    totalValue = diaperChartTotal,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    DiaperLegendDot(color = ext.laneCare, label = "尿尿")
                    DiaperLegendDot(color = ext.sun, label = "便便")
                }
                Spacer(Modifier.height(8.dp))
                if (t.dayValuesDiaper.all { it <= 0f }) {
                    com.lezi.babylog.designsystem.StateContainer(
                        kind = com.lezi.babylog.designsystem.StateKind.Empty,
                        title = "范围内暂无尿布记录",
                        message = "尿/便记录会按天汇总到这里。",
                    )
                } else {
                    StackedDiaperBarChart(
                        pee = t.dayValuesPee,
                        poop = t.dayValuesPoop,
                        dates = chartDates,
                        peeColor = ext.laneCare,
                        poopColor = ext.sun,
                    )
                }
            }
            if (t.tempAvg != null) {
                MetricRow(
                    title = "体温",
                    value = "%.1f℃".format(t.tempAvg),
                    detail = "${t.tempDays} 天有记录",
                )
                WeekLineChart(
                    title = "体温",
                    values = t.dayValuesTemp,
                    dates = chartDates,
                    color = ext.danger,
                )
            }

            Spacer(Modifier.height(LeziSpacing.Xxl))
            }
        }
    }
}

@Composable
private fun RangeTabs(
    selected: SummaryRange,
    onSelect: (SummaryRange) -> Unit,
) {
    val trackShape = LeziThemeExt.controlShape
    val tabShape = if (LeziThemeExt.isJournal) LeziShapes.JournalSm else LeziShapes.Sm
    Row(
        Modifier
            .fillMaxWidth()
            .clip(trackShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SummaryRange.entries.forEach { r ->
            val on = r == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(tabShape)
                    .background(
                        if (on) MaterialTheme.colorScheme.surface else Color.Transparent,
                    )
                    .then(
                        if (on) {
                            Modifier.border(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                tabShape,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .heightIn(min = LeziSpacing.Touch)
                    .selectable(
                        selected = on,
                        role = Role.Tab,
                        onClick = { onSelect(r) },
                    )
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    r.label,
                    style = LeziTypography.Label,
                    color = if (on) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun SummaryKpiStrip(
    feedValue: String,
    feedDetail: String,
    sleepValue: String,
    sleepDetail: String,
    diaperValue: String,
    diaperDetail: String,
) {
    val journal = LeziThemeExt.isJournal
    val cells = listOf(
        Triple("喂养", feedValue, feedDetail),
        Triple("睡眠", sleepValue, sleepDetail),
        Triple("尿布", diaperValue, diaperDetail),
    )
    if (journal) {
        LeziSurfacePanel(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            bottomBand = true,
        ) {
            Row(Modifier.fillMaxWidth()) {
                cells.forEachIndexed { index, (title, value, detail) ->
                    Column(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 74.dp)
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                    ) {
                        Text(title, style = LeziTypography.Meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(value, style = LeziTypography.Metric.copy(fontSize = 20.sp), maxLines = 1)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            detail,
                            style = LeziTypography.Meta.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                    if (index < cells.lastIndex) {
                        Spacer(
                            Modifier
                                .width(1.dp)
                                .height(74.dp)
                                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
                        )
                    }
                }
            }
        }
    } else {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            cells.forEach { (title, value, detail) ->
                CompactMetricCard(
                    title = title,
                    value = value,
                    detail = detail,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CompactMetricCard(
    title: String,
    value: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    LeziCard(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 14.dp),
    ) {
        Text(title, style = LeziTypography.Meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(value, style = LeziTypography.Metric.copy(fontSize = 22.sp))
        Spacer(Modifier.height(2.dp))
        Text(
            detail,
            style = LeziTypography.Meta.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
}

@Composable
private fun ChartCardHeader(
    title: String,
    scopeLabel: String,
    totalValue: String,
    eyebrow: String? = null,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            if (eyebrow != null) {
                Text(
                    eyebrow,
                    style = LeziTypography.Eyebrow,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(title, style = LeziTypography.TitleSm)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                scopeLabel,
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                totalValue,
                style = LeziTypography.BodyStrong,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

internal data class BarSlotLayout(
    val barWidth: Float,
    val firstBarX: Float,
    val gap: Float,
)

/**
 * Keeps one-to-seven day charts on the same seven-slot geometry. A Day bar is
 * therefore the same width as a Week bar and is centered instead of stretching.
 */
internal fun calculateBarSlotLayout(
    canvasWidth: Float,
    barCount: Int,
    preferredGap: Float,
    referenceBarCount: Int = 7,
): BarSlotLayout {
    val count = barCount.coerceAtLeast(1)
    val referenceCount = referenceBarCount.coerceAtLeast(1)
    val gap = preferredGap.coerceAtLeast(0f)
    val slotCount = if (count <= referenceCount) referenceCount else count
    val barWidth = ((canvasWidth - gap * (slotCount + 1)) / slotCount).coerceAtLeast(2f)
    val usedWidth = count * barWidth + (count - 1).coerceAtLeast(0) * gap
    return BarSlotLayout(
        barWidth = barWidth,
        firstBarX = ((canvasWidth - usedWidth) / 2f).coerceAtLeast(gap),
        gap = gap,
    )
}

@Composable
private fun MetricRow(title: String, value: String, detail: String, compare: String? = null) {
    val journal = LeziThemeExt.isJournal
    LeziSurfacePanel(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = if (journal) {
            androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp)
        } else {
            androidx.compose.foundation.layout.PaddingValues(14.dp)
        },
        bottomBand = true,
    ) {
        if (journal) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = LeziTypography.Label)
                    Text(detail, style = LeziTypography.Meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(value, style = LeziTypography.Metric)
                    if (compare != null) Text(compare, style = LeziTypography.Meta, color = MaterialTheme.colorScheme.primary)
                }
            }
        } else {
            Text(title, style = LeziTypography.Meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = LeziTypography.Metric)
            Text(detail, style = LeziTypography.Meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (compare != null) {
                Text(compare, style = LeziTypography.Label, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun DiaperLegendDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .height(10.dp)
                .width(10.dp)
                .clip(LeziShapes.Micro)
                .background(color),
        )
        Text(label, style = LeziTypography.Meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StackedDiaperBarChart(
    pee: List<Float>,
    poop: List<Float>,
    dates: List<LocalDate>,
    peeColor: Color,
    poopColor: Color,
) {
    val n = maxOf(pee.size, poop.size, 1)
    val peeValues = pee + List((n - pee.size).coerceAtLeast(0)) { 0f }
    val poopValues = poop + List((n - poop.size).coerceAtLeast(0)) { 0f }
    val totals = List(n) { i -> peeValues[i] + poopValues[i] }
    val grid = LeziThemeExt.colors.chartGrid
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val max = (totals.maxOrNull() ?: 0f).coerceAtLeast(1f)
    val showBarLabels = n <= 7 || totals.count { it > 0f } <= 12
    val labelAllBars = n <= 7
    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(if (showBarLabels) 108.dp else 88.dp)
                .semantics {
                    contentDescription = dates.zip(peeValues.zip(poopValues)).joinToString("；") { (date, pair) ->
                        val (p, o) = pair
                        "${date.monthValue}月${date.dayOfMonth}日 尿${p.toInt()}次 便${o.toInt()}次 共${(p + o).toInt()}次"
                    }
                },
        ) {
            val topPad = if (showBarLabels) 16.dp.toPx() else 0f
            val plotH = (size.height - topPad).coerceAtLeast(1f)
            for (i in 0..3) {
                val y = topPad + plotH * i / 3f
                drawLine(grid.copy(alpha = 0.45f), Offset(0f, y), Offset(size.width, y), 1f)
            }
            val layout = calculateBarSlotLayout(
                canvasWidth = size.width,
                barCount = n,
                preferredGap = if (n > 14) 2.dp.toPx() else 4.dp.toPx(),
            )
            val gap = layout.gap
            val barW = layout.barWidth
            val textPaint = AndroidPaint().apply {
                isAntiAlias = true
                textAlign = AndroidPaint.Align.CENTER
                textSize = if (n > 14) 8.sp.toPx() else 10.sp.toPx()
                typeface = Typeface.DEFAULT
                this.color = labelColor.toArgb()
            }
            val radius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
            for (i in 0 until n) {
                val peeV = peeValues[i]
                val poopV = poopValues[i]
                val total = peeV + poopV
                val x = layout.firstBarX + i * (barW + gap)
                val totalH = (total / max) * (plotH * 0.85f)
                val peeH = if (total <= 0f) 0f else totalH * (peeV / total)
                val poopH = (totalH - peeH).coerceAtLeast(0f)
                var yCursor = size.height
                if (peeV > 0f) {
                    val h = peeH.coerceAtLeast(2f)
                    yCursor -= h
                    drawRoundRect(
                        color = peeColor.copy(alpha = 0.9f),
                        topLeft = Offset(x, yCursor),
                        size = Size(barW, h),
                        cornerRadius = if (poopV > 0f) {
                            androidx.compose.ui.geometry.CornerRadius(0f, 0f)
                        } else {
                            radius
                        },
                    )
                }
                if (poopV > 0f) {
                    val h = poopH.coerceAtLeast(2f)
                    yCursor -= h
                    drawRoundRect(
                        color = poopColor.copy(alpha = 0.92f),
                        topLeft = Offset(x, yCursor),
                        size = Size(barW, h),
                        cornerRadius = radius,
                    )
                }
                val shouldLabel = showBarLabels && (labelAllBars || total > 0f)
                if (shouldLabel && total > 0f) {
                    drawContext.canvas.nativeCanvas.drawText(
                        "${total.toInt()}",
                        x + barW / 2f,
                        (yCursor - 4f).coerceAtLeast(textPaint.textSize),
                        textPaint,
                    )
                }
            }
        }
        DateAxis(if (dates.size >= n) dates.take(n) else dates)
    }
}

@Composable
private fun MiniBarChart(
    values: List<Float>,
    dates: List<LocalDate>,
    metricLabel: String,
    color: Color,
    valueFormatter: (Float) -> String = { v ->
        if (v <= 0f) "" else if (v % 1f == 0f) "${v.toInt()}" else "%.1f".format(v)
    },
) {
    val grid = LeziThemeExt.colors.chartGrid
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val max = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
    // Label every sample up to seven; for longer sparse ranges, label only non-zero bars.
    val showBarLabels = values.size <= 7 || values.count { it > 0f } <= 12
    val labelAllBars = values.size <= 7
    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(if (showBarLabels) 108.dp else 88.dp)
                .semantics {
                    contentDescription = chartDescription(metricLabel, dates, values)
                },
        ) {
            val topPad = if (showBarLabels) 16.dp.toPx() else 0f
            val plotH = (size.height - topPad).coerceAtLeast(1f)
            for (i in 0..3) {
                val y = topPad + plotH * i / 3f
                drawLine(grid.copy(alpha = 0.45f), Offset(0f, y), Offset(size.width, y), 1f)
            }
            val n = values.size.coerceAtLeast(1)
            val layout = calculateBarSlotLayout(
                canvasWidth = size.width,
                barCount = n,
                preferredGap = if (n > 14) 2.dp.toPx() else 4.dp.toPx(),
            )
            val gap = layout.gap
            val barW = layout.barWidth
            val textPaint = AndroidPaint().apply {
                isAntiAlias = true
                textAlign = AndroidPaint.Align.CENTER
                textSize = if (n > 14) 8.sp.toPx() else 10.sp.toPx()
                typeface = Typeface.DEFAULT
                this.color = labelColor.toArgb()
            }
            values.forEachIndexed { i, v ->
                val h = (v / max) * (plotH * 0.85f)
                val x = layout.firstBarX + i * (barW + gap)
                val barTop = size.height - h.coerceAtLeast(if (v > 0f) 2f else 0f)
                if (v > 0f) {
                    drawRoundRect(
                        color = color.copy(alpha = 0.85f),
                        topLeft = Offset(x, barTop),
                        size = Size(barW, h.coerceAtLeast(2f)),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
                    )
                }
                val shouldLabel = showBarLabels && (labelAllBars || v > 0f)
                if (shouldLabel) {
                    val label = valueFormatter(v)
                    if (label.isNotEmpty()) {
                        drawContext.canvas.nativeCanvas.drawText(
                            label,
                            x + barW / 2f,
                            (barTop - 4f).coerceAtLeast(textPaint.textSize),
                            textPaint,
                        )
                    }
                }
            }
        }
        DateAxis(dates)
    }
}

@Composable
private fun JournalWeekGrid(summary: WeekSummary) {
    val ext = LeziThemeExt.colors
    LeziSurfacePanel(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
        bottomBand = true,
    ) {
        Row(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .width(42.dp)
                    .height(180.dp)
                    .padding(top = 20.dp),
                verticalArrangement = Arrangement.SpaceAround,
                horizontalAlignment = Alignment.Start,
            ) {
                listOf("喂养", "睡眠", "尿便", "体温").forEach { label ->
                    Text(label, style = LeziTypography.Meta)
                }
            }
            Column(Modifier.weight(1f)) {
                DateAxis(summary.days.map { it.date })
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .semantics {
                            contentDescription = summary.days.joinToString("；") { day ->
                                "${day.date.monthValue}月${day.date.dayOfMonth}日，" +
                                    "喂养${day.feedMl}毫升、母乳${day.nursingMin}分钟，" +
                                    "睡眠${day.sleepMin}分钟，尿${day.pee}次、便${day.poop}次，" +
                                    "体温${day.temps.size}条"
                            }
                        },
                ) {
                    val cols = 7
                    val rows = 4
                    val cellW = size.width / cols
                    val cellH = size.height / rows
                    for (i in 0..rows) {
                        drawLine(ext.chartGrid.copy(alpha = 0.7f), Offset(0f, i * cellH), Offset(size.width, i * cellH), 1f)
                    }
                    for (i in 0..cols) {
                        drawLine(ext.chartGrid.copy(alpha = 0.55f), Offset(i * cellW, 0f), Offset(i * cellW, size.height), 1f)
                    }
                    summary.days.forEachIndexed { index, day ->
                        val cx = index * cellW + cellW / 2f
                        if (day.feedMl > 0 || day.nursingMin > 0) {
                            drawCircle(ext.laneFeed, 7f, Offset(cx, cellH * 0.5f))
                        }
                        if (day.sleepMin > 0) {
                            drawRoundRect(
                                ext.laneSleep.copy(alpha = 0.75f),
                                topLeft = Offset(index * cellW + 4f, cellH + 8f),
                                size = Size((cellW - 8f).coerceAtLeast(3f), 14f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f, 5f),
                            )
                        }
                        if (day.pee > 0) {
                            drawRoundRect(
                                color = ext.laneCare,
                                topLeft = Offset(cx - 12f, cellH * 2.5f - 5f),
                                size = Size(10f, 10f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f),
                            )
                        }
                        if (day.poop > 0) {
                            drawRoundRect(
                                color = ext.sun,
                                topLeft = Offset(cx + 2f, cellH * 2.5f - 5f),
                                size = Size(10f, 10f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
                            )
                        }
                        if (day.temps.isNotEmpty()) {
                            val cy = cellH * 3.5f
                            val triangle = Path().apply {
                                moveTo(cx, cy - 6f)
                                lineTo(cx - 6f, cy + 5f)
                                lineTo(cx + 6f, cy + 5f)
                                close()
                            }
                            drawPath(triangle, ext.danger)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekLineChart(
    title: String,
    values: List<Float>,
    dates: List<LocalDate>,
    color: Color,
) {
    val journal = LeziThemeExt.isJournal
    val grid = LeziThemeExt.colors.chartGrid
    LeziSurfacePanel(modifier = Modifier.fillMaxWidth(), bottomBand = true) {
        Text(title, style = LeziTypography.TitleSm)
        Spacer(Modifier.height(8.dp))
        val max = (values.filter { it > 0f }.maxOrNull() ?: 37f).coerceAtLeast(37.5f)
        val min = (values.filter { it > 0f }.minOrNull() ?: 36f).coerceAtMost(36f)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .semantics {
                    contentDescription = chartDescription(title, dates, values)
                },
        ) {
            if (journal) {
                for (i in 0..4) {
                    val y = size.height * i / 4f
                    drawLine(grid.copy(alpha = 0.7f), Offset(0f, y), Offset(size.width, y), 1f)
                }
            }
            val path = Path()
            var started = false
            values.forEachIndexed { i, v ->
                if (v <= 0f) return@forEachIndexed
                val x = size.width * (i / (values.size - 1f).coerceAtLeast(1f))
                val y = size.height - ((v - min) / (max - min).coerceAtLeast(0.1f)) * size.height * 0.85f - 8f
                if (!started) {
                    path.moveTo(x, y)
                    started = true
                } else {
                    path.lineTo(x, y)
                }
                drawCircle(color, radius = if (journal) 4f else 6f, center = Offset(x, y))
            }
            if (started) drawPath(path, color, style = Stroke(width = if (journal) 3f else 4f))
        }
        DateAxis(dates)
    }
}

@Composable
private fun DateAxis(dates: List<LocalDate>) {
    if (dates.isEmpty()) return
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    if (dates.size == 1) {
        Text(
            "${dates.single().monthValue}/${dates.single().dayOfMonth}",
            modifier = Modifier.fillMaxWidth(),
            style = LeziTypography.Meta,
            color = color,
            textAlign = TextAlign.Center,
        )
    } else if (dates.size <= 7) {
        Row(Modifier.fillMaxWidth()) {
            dates.forEach { date ->
                Text(
                    "${date.monthValue}/${date.dayOfMonth}",
                    modifier = Modifier.weight(1f),
                    style = LeziTypography.Meta.copy(fontSize = 10.sp),
                    color = color,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(dates.first(), dates[dates.lastIndex / 2], dates.last()).forEach { date ->
                Text(
                    "${date.monthValue}/${date.dayOfMonth}",
                    style = LeziTypography.Meta,
                    color = color,
                )
            }
        }
    }
}

private fun chartDescription(
    metricLabel: String,
    dates: List<LocalDate>,
    values: List<Float>,
): String = dates.zip(values).joinToString(
    separator = "；",
    prefix = "$metricLabel：",
) { (date, value) ->
    "${date.monthValue}月${date.dayOfMonth}日 ${"%.1f".format(value)}"
}

private fun formatMin(min: Long): String {
    if (min == 0L) return "0m"
    val h = min / 60
    val m = min % 60
    return if (h == 0L) "${m}m" else if (m == 0L) "${h}h" else "${h}h${m}m"
}

private fun formatSigned(value: Long, unit: String): String =
    "${if (value >= 0) "+" else ""}$value$unit"

private fun formatSigned(value: Int, unit: String): String =
    formatSigned(value.toLong(), unit)

private fun formatFeedWindowTotal(feedMl: Int, nursingMin: Long): String {
    return buildString {
        append(if (feedMl > 0) "${feedMl}ml" else "0ml")
        if (nursingMin > 0L) {
            append(" · 母乳 ")
            append(formatMin(nursingMin))
        }
    }
}

private fun formatDiaperTotal(pee: Int, poop: Int): String {
    val total = pee + poop
    return "${total}次（尿$pee · 便$poop）"
}
