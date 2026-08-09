package com.lezi.babylog.feature.summary

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.formatRecordDuration
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.designsystem.LeziCard
import com.lezi.babylog.designsystem.LeziMotion
import com.lezi.babylog.designsystem.LeziRangeTabs
import com.lezi.babylog.designsystem.LeziShapes
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziSurfacePanel
import com.lezi.babylog.designsystem.LeziThemeExt
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.PageHero
import com.lezi.babylog.designsystem.PageScaffoldBackground
import com.lezi.babylog.designsystem.StateContainer
import com.lezi.babylog.designsystem.StateKind
import com.lezi.babylog.designsystem.TransientShallowSyncStatus
import com.lezi.babylog.designsystem.leziMotionMillis
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.carelog.WeekSummary
import com.lezi.babylog.domain.carelog.weekStartFor
import com.lezi.babylog.sync.session.ShallowSyncLine
import com.lezi.babylog.sync.session.ShallowSyncState
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
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
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
    val dayFeedMlLabel: String? = null,
    val dayFeedCountLabel: String? = null,
    val dayNursingMinLabel: String? = null,
    val daySleepMinLabel: String? = null,
    val daySleepSegmentsLabel: String? = null,
    val dayPeeLabel: String? = null,
    val dayPoopLabel: String? = null,
    val dayDiaperLabel: String? = null,
) {
    val dayDiaper: Int get() = dayPee + dayPoop
}

data class SummaryTotals(
    val feedMl: Int = 0,
    val feedMlMin: Int = feedMl,
    val feedMlMax: Int = feedMl,
    val nursingMin: Long = 0,
    val nursingMinMin: Long = nursingMin,
    val nursingMinMax: Long = nursingMin,
    val feedCount: Int = 0,
    val feedCountMin: Int = feedCount,
    val feedCountMax: Int = feedCount,
    val sleepMin: Long = 0,
    val sleepSegments: Int = 0,
    val pee: Int = 0,
    val peeMin: Int = pee,
    val peeMax: Int = pee,
    val poop: Int = 0,
    val poopMin: Int = poop,
    val poopMax: Int = poop,
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
    /** True when unresolved suspected-duplicate groups make totals a range. */
    val hasDuplicateUncertainty: Boolean = false,
    val feedMlLabel: String? = null,
    val feedCountLabel: String? = null,
    val nursingMinLabel: String? = null,
    val sleepMinLabel: String? = null,
    val sleepSegmentsLabel: String? = null,
    val peeLabel: String? = null,
    val poopLabel: String? = null,
    val diaperLabel: String? = null,
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
    private val refreshInFlight = MutableStateFlow(false)
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
            ).combine(careLog.observeSourceRoleClientUuids()) { records, sourceRoles ->
                records to sourceRoles
            }.mapLatest { (records, sourceRoles) ->
                val openGroups = careLog.listOpenSuspectedDuplicateGroups(records, sourceRoles)
                SummaryAggregationRequest(
                    records = records,
                    range = selectedRange,
                    anchorDate = anchor,
                    weekStartDay = preferences.weekStart,
                    showAvgSleep = preferences.showAvgSleep,
                    comparePrevWeek = preferences.comparePrevWeek,
                    babyName = baby.nickname,
                    zone = zone,
                    sourceRoleClientUuids = sourceRoles,
                    openGroups = openGroups,
                )
            }.calculateLatest(aggregationEngine)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), SummaryUi())

    val shallowSyncStatus = syncPort.shallowStatus().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ShallowSyncLine(
            state = ShallowSyncState.Unjoined,
            text = "尚未加入家庭 · 数据仅保存在本机",
        ),
    )
    val isRefreshing = refreshInFlight.asStateFlow()

    fun setRange(r: SummaryRange) {
        range.value = r
    }

    fun setAnchorDate(day: LocalDate) {
        anchorDate.value = day
    }

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

@Composable
fun SummaryRoute(
    anchorDate: LocalDate,
    vm: SummaryViewModel = hiltViewModel(),
) {
    LaunchedEffect(anchorDate) {
        vm.setAnchorDate(anchorDate)
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val shallowSyncStatus by vm.shallowSyncStatus.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    // Non-essential calculating↔content crossfade: Base tier; reduce-motion → 0.
    val calculatingMs = leziMotionMillis(LeziMotion.Base)
    Crossfade(
        targetState = ui.calculating,
        animationSpec = tween(durationMillis = calculatingMs),
        label = "summary_loading",
    ) { calculating ->
        if (calculating) {
            PageScaffoldBackground {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(LeziSpacing.Page),
                    contentAlignment = Alignment.Center,
                ) {
                    StateContainer(
                        kind = StateKind.Loading,
                        title = "正在计算汇总",
                        message = "正在整理护理记录，请稍候。",
                        modifier = Modifier.testTag("summary_calculating"),
                    )
                }
            }
        } else {
            SummaryContent(
                ui = ui,
                shallowSyncStatus = shallowSyncStatus,
                isRefreshing = isRefreshing,
                onRefresh = vm::refresh,
                onSelectRange = vm::setRange,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SummaryContent(
    ui: SummaryUi,
    shallowSyncStatus: ShallowSyncLine,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onSelectRange: (SummaryRange) -> Unit,
) {
    val ext = LeziThemeExt.colors
    val journal = LeziThemeExt.isJournal
    val density = LeziThemeExt.density
    val chartCardPad = PaddingValues(density.cardPad)
    val t = ui.totals
    val chartDates = List(ui.range.dayCount) { offset ->
        ui.rangeStartDate.plusDays(offset.toLong())
    }
    // Non-essential range swap: Base enter / Fast exit; reduce-motion → 0.
    val rangeEnterMs = leziMotionMillis(LeziMotion.Base)
    val rangeExitMs = leziMotionMillis(LeziMotion.Fast)

    // Journal and warm share a 16dp page content column so KPI/charts/header
    // stay the same width (journal dock remains full-bleed elsewhere).
    PageScaffoldBackground {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = LeziSpacing.Page,
                        vertical = LeziSpacing.Page,
                    ),
                verticalArrangement = Arrangement.spacedBy(density.sectionGap),
            ) {
                PageHero(
                    eyebrow = "",
                    title = "汇总",
                )

                TransientShallowSyncStatus(
                    text = shallowSyncStatus.text,
                    isError = shallowSyncStatus.state in setOf(
                        ShallowSyncState.Error,
                        ShallowSyncState.ReauthRequired,
                    ),
                    isUserRefreshing = isRefreshing,
                    contentTestTag = "summary_shallow_sync_status",
                )

                LeziRangeTabs(
                    items = SummaryRange.entries,
                    selected = ui.range,
                    onSelect = onSelectRange,
                    label = { it.label },
                )

                AnimatedContent(
                    targetState = ui.range,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(durationMillis = rangeEnterMs)) togetherWith
                            fadeOut(animationSpec = tween(durationMillis = rangeExitMs))
                    },
                    label = "summary_range_content",
                ) { range ->
                    // Range body emits multiple siblings; keep a Column so they
                    // retain spacedBy layout once lifted out of the outer Column
                    // into AnimatedContent (which does not arrange multi-root content).
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(density.sectionGap),
                    ) {
                        val windows = t.chartWindows
                        SummaryDuplicateBoundsNotice(
                            visible = t.hasDuplicateUncertainty,
                            contentPadding = chartCardPad,
                        )
                        SummaryKpiStrip(
                            feedValue = windows.dayFeedCountLabel?.let { "${it}次" }
                                ?: if (windows.dayFeedCount == 0 && windows.dayFeedMl == 0) {
                                "0次"
                            } else {
                                "${windows.dayFeedCount}次"
                            },
                            feedDetail = listOfNotNull(
                                windows.dayFeedMlLabel?.let { "奶量 $it" },
                                windows.dayNursingMinLabel?.let { "母乳 $it 分钟" },
                            ).takeIf { it.isNotEmpty() }?.joinToString(" · ")
                                ?: if (windows.dayFeedMl == 0 && windows.dayNursingMin == 0L) {
                                "当日暂无详情"
                            } else {
                                formatFeedWindowTotal(windows.dayFeedMl, windows.dayNursingMin)
                            },
                            sleepValue = windows.daySleepMinLabel
                                ?: formatRecordDuration(windows.daySleepMin),
                            sleepDetail = windows.daySleepSegmentsLabel?.let { "当日 $it 段" }
                                ?: if (windows.daySleepSegments == 0) {
                                "当日 0 段"
                            } else {
                                "当日 ${windows.daySleepSegments} 段"
                            },
                            diaperValue = windows.dayDiaperLabel ?: "${windows.dayDiaper}",
                            diaperDetail = "当日 尿 ${windows.dayPeeLabel ?: windows.dayPee} · " +
                                "便 ${windows.dayPoopLabel ?: windows.dayPoop}",
                        )

                        if (range == SummaryRange.Week && ui.comparePrevWeek) {
                            val previous = ui.previousWeekTotals
                            LeziSurfacePanel(
                                Modifier.fillMaxWidth(),
                                contentPadding = chartCardPad,
                                bottomBand = true,
                            ) {
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

                        val feedChartTotal = when (range) {
                            SummaryRange.Day -> listOfNotNull(
                                windows.dayFeedMlLabel?.let { "奶量 $it" },
                                windows.dayNursingMinLabel?.let { "母乳 $it 分钟" },
                            ).takeIf { it.isNotEmpty() }?.joinToString(" · ")
                                ?: formatFeedWindowTotal(
                                    windows.dayFeedMl,
                                    windows.dayNursingMin,
                                )
                            SummaryRange.Week, SummaryRange.Month -> listOfNotNull(
                                t.feedMlLabel?.let { "奶量 $it" },
                                t.nursingMinLabel?.let { "母乳 $it" },
                            ).takeIf { it.isNotEmpty() }?.joinToString(" · ")
                                ?: formatFeedWindowTotal(t.feedMl, t.nursingMin)
                        }
                        val sleepChartTotal = when (range) {
                            SummaryRange.Day -> windows.daySleepMinLabel
                                ?: formatRecordDuration(windows.daySleepMin)
                            SummaryRange.Week, SummaryRange.Month -> t.sleepMinLabel
                                ?: formatRecordDuration(t.sleepMin)
                        }
                        val diaperChartTotal = when (range) {
                            SummaryRange.Day -> windows.dayDiaperLabel
                                ?: formatDiaperTotal(windows.dayPee, windows.dayPoop)
                            SummaryRange.Week, SummaryRange.Month -> t.diaperLabel
                                ?: formatDiaperTotal(t.pee, t.poop)
                        }
                        val chartTotalScope = when (range) {
                            SummaryRange.Day -> "当日"
                            SummaryRange.Week -> "本周"
                            SummaryRange.Month -> "本月"
                        }

                        SummaryChartPanel(
                            title = "喂养",
                            scopeLabel = chartTotalScope,
                            totalValue = feedChartTotal,
                            isEmpty = t.dayValuesFeed.all { it <= 0f } && t.nursingMin == 0L,
                            emptyTitle = "范围内暂无喂养记录",
                            emptyMessage = "换一周或去记录页添加喂养。",
                            emptyTag = "summary_chart_empty_feed",
                            contentPadding = chartCardPad,
                        ) {
                            MiniBarChart(
                                values = t.dayValuesFeed,
                                dates = chartDates,
                                metricLabel = "喂养量",
                                color = ext.laneFeed,
                                valueFormatter = { v -> if (v <= 0f) "" else "${v.toInt()}ml" },
                            )
                        }

                        SummaryChartPanel(
                            title = "睡眠",
                            scopeLabel = chartTotalScope,
                            totalValue = sleepChartTotal,
                            isEmpty = t.dayValuesSleep.all { it <= 0f },
                            emptyTitle = "范围内暂无已完成睡眠记录",
                            emptyMessage = "完成的睡眠会按天汇总到这里。",
                            emptyTag = "summary_chart_empty_sleep",
                            contentPadding = chartCardPad,
                            preContent = {
                                if (range != SummaryRange.Day && ui.showAvgSleep) {
                                    val averageSleep =
                                        t.sleepMin / range.dayCount.coerceAtLeast(1)
                                    Text(
                                        "日均睡眠 ${formatRecordDuration(averageSleep)}",
                                        style = LeziTypography.Meta,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(LeziSpacing.Xs))
                                }
                            },
                        ) {
                            MiniBarChart(
                                values = t.dayValuesSleep,
                                dates = chartDates,
                                metricLabel = "睡眠分钟",
                                color = ext.laneSleep,
                                valueFormatter = { v ->
                                    if (v <= 0f) "" else formatRecordDuration(v.toLong())
                                },
                            )
                        }

                        SummaryChartPanel(
                            title = "尿布",
                            scopeLabel = chartTotalScope,
                            totalValue = diaperChartTotal,
                            isEmpty = t.dayValuesDiaper.all { it <= 0f },
                            emptyTitle = "范围内暂无尿布记录",
                            emptyMessage = "尿/便记录会按天汇总到这里。",
                            emptyTag = "summary_chart_empty_diaper",
                            contentPadding = chartCardPad,
                            preContent = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Md),
                                ) {
                                    DiaperLegendDot(color = ext.laneCare, label = "尿尿")
                                    DiaperLegendDot(color = ext.sun, label = "便便")
                                }
                                Spacer(Modifier.height(LeziSpacing.Xs))
                            },
                        ) {
                            StackedDiaperBarChart(
                                pee = t.dayValuesPee,
                                poop = t.dayValuesPoop,
                                dates = chartDates,
                                peeColor = ext.laneCare,
                                poopColor = ext.sun,
                            )
                        }

                        if (t.tempAvg != null) {
                            WeekLineChart(
                                title = "体温",
                                values = t.dayValuesTemp,
                                dates = chartDates,
                                color = ext.danger,
                                scopeLabel = "${t.tempDays} 天有记录",
                                totalValue = "%.1f℃".format(t.tempAvg),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(LeziSpacing.Xxl))
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
            contentPadding = PaddingValues(0.dp),
            bottomBand = true,
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
            ) {
                cells.forEachIndexed { index, (title, value, detail) ->
                    KpiCell(
                        title = title,
                        value = value,
                        detail = detail,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 74.dp)
                            .padding(horizontal = LeziSpacing.Xs, vertical = LeziSpacing.Sm),
                        valueStyle = LeziTypography.Metric.copy(fontSize = 20.sp),
                        detailStyle = LeziTypography.Micro,
                    )
                    if (index < cells.lastIndex) {
                        Spacer(
                            Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
                        )
                    }
                }
            }
        }
    } else {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
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
        contentPadding = PaddingValues(LeziThemeExt.density.cardPad),
    ) {
        KpiCell(
            title = title,
            value = value,
            detail = detail,
            valueStyle = LeziTypography.Metric.copy(fontSize = 22.sp),
            detailStyle = LeziTypography.Meta.copy(fontSize = 11.sp),
        )
    }
}

@Composable
private fun KpiCell(
    title: String,
    value: String,
    detail: String,
    modifier: Modifier = Modifier,
    valueStyle: TextStyle = LeziTypography.Metric,
    detailStyle: TextStyle = LeziTypography.Meta,
) {
    Column(modifier) {
        Text(title, style = LeziTypography.Meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(LeziSpacing.Xxs))
        Text(
            value,
            style = valueStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            detail,
            style = detailStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
}

/**
 * Shared chart card shell: header + optional pre-content + empty StateContainer
 * or chart body. Keeps empty≠calculating policy (StateKind.Empty + copy/tags)
 * in one place across feed/sleep/diaper panels.
 */
@Composable
private fun SummaryChartPanel(
    title: String,
    scopeLabel: String,
    totalValue: String,
    isEmpty: Boolean,
    emptyTitle: String,
    emptyMessage: String,
    emptyTag: String,
    contentPadding: PaddingValues,
    preContent: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    LeziSurfacePanel(
        Modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        bottomBand = true,
    ) {
        ChartCardHeader(
            title = title,
            scopeLabel = scopeLabel,
            totalValue = totalValue,
        )
        Spacer(Modifier.height(LeziSpacing.Xs))
        preContent?.invoke()
        if (isEmpty) {
            StateContainer(
                kind = StateKind.Empty,
                title = emptyTitle,
                message = emptyMessage,
                modifier = Modifier.testTag(emptyTag),
            )
        } else {
            content()
        }
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

/** Value labels fit when every slot can be annotated (≤7 bars) or the non-zero set stays sparse. */
private fun showBarLabels(barCount: Int, nonZeroCount: Int): Boolean =
    barCount <= 7 || nonZeroCount <= 12

/** Shared slot geometry for the bar charts: tighter gaps once the window passes two weeks. */
private fun DrawScope.chartBarLayout(barCount: Int): BarSlotLayout = calculateBarSlotLayout(
    canvasWidth = size.width,
    barCount = barCount,
    preferredGap = (if (barCount > 14) 2.dp else 4.dp).toPx(),
)

/** Shared four-line horizontal grid under the bars. */
private fun DrawScope.chartGridLines(grid: Color, topPad: Float, plotH: Float) {
    for (i in 0..3) {
        val y = topPad + plotH * i / 3f
        drawLine(grid.copy(alpha = 0.45f), Offset(0f, y), Offset(size.width, y), 1f)
    }
}

/** Shared bar-top label paint; 10sp is the readable floor even on dense 30-day charts. */
private fun DrawScope.barLabelPaint(labelColor: Color): AndroidPaint = AndroidPaint().apply {
    isAntiAlias = true
    textAlign = AndroidPaint.Align.CENTER
    textSize = 10.sp.toPx()
    typeface = Typeface.DEFAULT
    color = labelColor.toArgb()
}

/**
 * Draws a bar-top value label, skipping empty labels and labels wider than
 * their slot so dense windows never overflow into neighbors.
 */
private fun DrawScope.drawBarLabel(
    paint: AndroidPaint,
    label: String,
    centerX: Float,
    topY: Float,
    maxWidth: Float,
) {
    if (label.isEmpty()) return
    if (paint.measureText(label) > maxWidth) return
    drawContext.canvas.nativeCanvas.drawText(
        label,
        centerX,
        topY.coerceAtLeast(paint.textSize),
        paint,
    )
}

@Composable
private fun DiaperLegendDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
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
    val showLabels = showBarLabels(n, totals.count { it > 0f })
    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(if (showLabels) 108.dp else 88.dp)
                .semantics {
                    contentDescription = dates.zip(peeValues.zip(poopValues)).joinToString("；") { (date, pair) ->
                        val (p, o) = pair
                        "${date.monthValue}月${date.dayOfMonth}日 尿${p.toInt()}次 便${o.toInt()}次 共${(p + o).toInt()}次"
                    }
                },
        ) {
            val topPad = if (showLabels) 16.dp.toPx() else 0f
            val plotH = (size.height - topPad).coerceAtLeast(1f)
            chartGridLines(grid, topPad, plotH)
            val layout = chartBarLayout(n)
            val gap = layout.gap
            val barW = layout.barWidth
            val labelPaint = barLabelPaint(labelColor)
            val radius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx())
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
                if (showLabels && total > 0f) {
                    drawBarLabel(
                        paint = labelPaint,
                        label = "${total.toInt()}",
                        centerX = x + barW / 2f,
                        topY = yCursor - 4f,
                        maxWidth = barW + gap,
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
    val showLabels = showBarLabels(values.size, values.count { it > 0f })
    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(if (showLabels) 108.dp else 88.dp)
                .semantics {
                    contentDescription = chartDescription(metricLabel, dates, values)
                },
        ) {
            val topPad = if (showLabels) 16.dp.toPx() else 0f
            val plotH = (size.height - topPad).coerceAtLeast(1f)
            chartGridLines(grid, topPad, plotH)
            val n = values.size.coerceAtLeast(1)
            val layout = chartBarLayout(n)
            val gap = layout.gap
            val barW = layout.barWidth
            val labelPaint = barLabelPaint(labelColor)
            values.forEachIndexed { i, v ->
                val h = (v / max) * (plotH * 0.85f)
                val x = layout.firstBarX + i * (barW + gap)
                val barTop = size.height - h.coerceAtLeast(if (v > 0f) 2f else 0f)
                if (v > 0f) {
                    drawRoundRect(
                        color = color.copy(alpha = 0.85f),
                        topLeft = Offset(x, barTop),
                        size = Size(barW, h.coerceAtLeast(2f)),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                    )
                }
                if (showLabels) {
                    drawBarLabel(
                        paint = labelPaint,
                        label = valueFormatter(v),
                        centerX = x + barW / 2f,
                        topY = barTop - 4f,
                        maxWidth = barW + gap,
                    )
                }
            }
        }
        DateAxis(dates)
    }
}

@Composable
private fun JournalWeekGrid(summary: WeekSummary) {
    val ext = LeziThemeExt.colors
    val labelWidth = 42.dp
    val rowHeight = 40.dp
    LeziSurfacePanel(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(LeziThemeExt.density.panelContent),
        bottomBand = true,
    ) {
        DateAxis(
            dates = summary.days.map { it.date },
            modifier = Modifier.padding(start = labelWidth),
        )
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(labelWidth)) {
                listOf("喂养", "睡眠", "尿便", "体温").forEach { label ->
                    Box(
                        Modifier.height(rowHeight),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(label, style = LeziTypography.Meta)
                    }
                }
            }
            Canvas(
                Modifier
                    .weight(1f)
                    .height(rowHeight * 4)
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
                val feedDotRadius = 3.dp.toPx()
                val sleepMarkPadX = 1.5.dp.toPx()
                val sleepMarkPadY = 3.dp.toPx()
                val sleepMarkHeight = 5.dp.toPx()
                val markMinWidth = 1.dp.toPx()
                val sleepMarkRadius = 2.dp.toPx()
                val diaperMarkSize = 3.5.dp.toPx()
                val diaperMarkY = 2.dp.toPx()
                val peeMarkX = 4.5.dp.toPx()
                val poopMarkX = 1.dp.toPx()
                val peeMarkRadius = 1.dp.toPx()
                val poopMarkRadius = 1.5.dp.toPx()
                val tempMarkHalf = 2.dp.toPx()
                val tempMarkTop = 2.dp.toPx()
                val tempMarkBottom = 2.dp.toPx()
                for (i in 0..rows) {
                    drawLine(ext.chartGrid.copy(alpha = 0.7f), Offset(0f, i * cellH), Offset(size.width, i * cellH), 1f)
                }
                for (i in 0..cols) {
                    drawLine(ext.chartGrid.copy(alpha = 0.55f), Offset(i * cellW, 0f), Offset(i * cellW, size.height), 1f)
                }
                summary.days.forEachIndexed { index, day ->
                    val cx = index * cellW + cellW / 2f
                    if (day.feedMl > 0 || day.nursingMin > 0) {
                        drawCircle(ext.laneFeed, feedDotRadius, Offset(cx, cellH * 0.5f))
                    }
                    if (day.sleepMin > 0) {
                        drawRoundRect(
                            ext.laneSleep.copy(alpha = 0.75f),
                            topLeft = Offset(index * cellW + sleepMarkPadX, cellH + sleepMarkPadY),
                            size = Size(
                                (cellW - sleepMarkPadX * 2).coerceAtLeast(markMinWidth),
                                sleepMarkHeight,
                            ),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                sleepMarkRadius,
                                sleepMarkRadius,
                            ),
                        )
                    }
                    if (day.pee > 0) {
                        drawRoundRect(
                            color = ext.laneCare,
                            topLeft = Offset(cx - peeMarkX, cellH * 2.5f - diaperMarkY),
                            size = Size(diaperMarkSize, diaperMarkSize),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                peeMarkRadius,
                                peeMarkRadius,
                            ),
                        )
                    }
                    if (day.poop > 0) {
                        drawRoundRect(
                            color = ext.sun,
                            topLeft = Offset(cx + poopMarkX, cellH * 2.5f - diaperMarkY),
                            size = Size(diaperMarkSize, diaperMarkSize),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                poopMarkRadius,
                                poopMarkRadius,
                            ),
                        )
                    }
                    if (day.temps.isNotEmpty()) {
                        val cy = cellH * 3.5f
                        val triangle = Path().apply {
                            moveTo(cx, cy - tempMarkTop)
                            lineTo(cx - tempMarkHalf, cy + tempMarkBottom)
                            lineTo(cx + tempMarkHalf, cy + tempMarkBottom)
                            close()
                        }
                        drawPath(triangle, ext.danger)
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
    scopeLabel: String? = null,
    totalValue: String? = null,
) {
    val journal = LeziThemeExt.isJournal
    val grid = LeziThemeExt.colors.chartGrid
    LeziSurfacePanel(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(LeziThemeExt.density.cardPad),
        bottomBand = true,
    ) {
        if (scopeLabel != null && totalValue != null) {
            ChartCardHeader(
                title = title,
                scopeLabel = scopeLabel,
                totalValue = totalValue,
            )
        } else {
            Text(title, style = LeziTypography.TitleSm)
        }
        Spacer(Modifier.height(LeziSpacing.Xs))
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
            val dotRadius = (if (journal) 2.dp else 3.dp).toPx()
            val strokeWidth = (if (journal) 1.5.dp else 2.dp).toPx()
            val plotBottomInset = 8.dp.toPx()
            values.forEachIndexed { i, v ->
                if (v <= 0f) return@forEachIndexed
                val x = size.width * (i / (values.size - 1f).coerceAtLeast(1f))
                val y = size.height -
                    ((v - min) / (max - min).coerceAtLeast(0.1f)) * size.height * 0.85f -
                    plotBottomInset
                if (!started) {
                    path.moveTo(x, y)
                    started = true
                } else {
                    path.lineTo(x, y)
                }
                drawCircle(color, radius = dotRadius, center = Offset(x, y))
            }
            if (started) drawPath(path, color, style = Stroke(width = strokeWidth))
        }
        DateAxis(dates)
    }
}

@Composable
private fun DateAxis(
    dates: List<LocalDate>,
    modifier: Modifier = Modifier,
) {
    if (dates.isEmpty()) return
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    if (dates.size == 1) {
        Text(
            "${dates.single().monthValue}/${dates.single().dayOfMonth}",
            modifier = modifier.fillMaxWidth(),
            style = LeziTypography.Meta,
            color = color,
            textAlign = TextAlign.Center,
        )
    } else if (dates.size <= 7) {
        Row(modifier.fillMaxWidth()) {
            dates.forEach { date ->
                Text(
                    "${date.monthValue}/${date.dayOfMonth}",
                    modifier = Modifier.weight(1f),
                    style = LeziTypography.Micro,
                    color = color,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    } else {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
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

private fun formatSigned(value: Long, unit: String): String =
    "${if (value >= 0) "+" else ""}$value$unit"

private fun formatSigned(value: Int, unit: String): String =
    formatSigned(value.toLong(), unit)

private fun formatFeedWindowTotal(feedMl: Int, nursingMin: Long): String {
    return buildString {
        append(if (feedMl > 0) "${feedMl}ml" else "0ml")
        if (nursingMin > 0L) {
            append(" · 母乳 ")
            append(formatRecordDuration(nursingMin))
        }
    }
}

private fun formatDiaperTotal(pee: Int, poop: Int): String {
    val total = pee + poop
    return "${total}次（尿$pee · 便$poop）"
}

@Composable
internal fun SummaryDuplicateBoundsNotice(
    visible: Boolean,
    contentPadding: PaddingValues = PaddingValues(LeziSpacing.Md),
) {
    if (!visible) return
    LeziSurfacePanel(
        Modifier
            .fillMaxWidth()
            .testTag("summary_duplicate_bounds"),
        contentPadding = contentPadding,
    ) {
        Text("疑似重复尚未确认", style = LeziTypography.TitleSm)
        Text(
            "以下指标按全部合法解释显示下界–上界，不会暗选某一来源。",
            style = LeziTypography.Meta,
        )
    }
}
