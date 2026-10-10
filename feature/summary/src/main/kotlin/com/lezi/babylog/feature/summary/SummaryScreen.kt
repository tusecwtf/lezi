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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
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
import com.lezi.babylog.designsystem.foodSummaryLaneColors
import com.lezi.babylog.domain.CareLog
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
import kotlinx.coroutines.flow.distinctUntilChanged
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

/** One stacked segment of the 辅食 panel; the merged tail lane is named 其他. */
data class SummaryFoodLane(
    val name: String,
    val dayValues: List<Double>,
    val total: Double,
)

/** 辅食 panel state; null when the range has no baby_food records (pure-milk babies). */
data class SummaryFoodPanel(
    val recordCount: Int,
    val kindCount: Int,
    val lanes: List<SummaryFoodLane>,
    val unparseableCount: Int,
    val unparseableDayCounts: List<Int>,
    /** Parsed-amount lower/upper bound. Exact ranges keep a single total. */
    val amountMin: Double = lanes.sumOf(SummaryFoodLane::total),
    val amountMax: Double = amountMin,
)

data class SummaryUi(
    val calculating: Boolean = true,
    val range: SummaryRange = SummaryRange.Day,
    val anchorDate: LocalDate = LocalDate.now(),
    val rangeStartDate: LocalDate = anchorDate,
    val totals: SummaryTotals = SummaryTotals(),
    val previousWeekTotals: SummaryTotals? = null,
    val showAvgSleep: Boolean = false,
    val comparePrevWeek: Boolean = false,
    val empty: Boolean = true,
    val babyName: String = "",
    val food: SummaryFoodPanel? = null,
)

private data class SummaryPreferences(
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
        settings.showAvgSleep,
        settings.comparePrevWeek,
    ) { showAvgSleep, comparePrevWeek ->
        SummaryPreferences(
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
                    rangeStartDate = selectedRange.startDate(anchor),
                    showAvgSleep = preferences.showAvgSleep,
                    comparePrevWeek = preferences.comparePrevWeek,
                    empty = true,
                ),
            )
        } else {
            val rangeStart = selectedRange.startDate(anchor)
            val compareStart = if (
                selectedRange == SummaryRange.Week && preferences.comparePrevWeek
            ) {
                rangeStart.minusDays(7)
            } else {
                rangeStart
            }
            val queryStart = minOf(rangeStart, compareStart)
            val queryEnd = rangeStart.plusDays(selectedRange.dayCount.toLong())
            careLog.observeRecords(
                babyId = baby.id,
                // The projection owns the exact instant ±30-minute halo. This
                // date-level DAO overfetch guarantees both halo edges across DST.
                startDayInclusive = queryStart.minusDays(1),
                endDayExclusive = queryEnd.plusDays(1),
                zone = zone,
            ).combine(careLog.observeSourceRoleClientUuids()) { records, sourceRoles ->
                records to sourceRoles
            }.mapLatest { (records, sourceRoles) ->
                SummaryAggregationRequest(
                    records = records,
                    range = selectedRange,
                    anchorDate = anchor,
                    showAvgSleep = preferences.showAvgSleep,
                    comparePrevWeek = preferences.comparePrevWeek,
                    babyName = baby.nickname,
                    zone = zone,
                    sourceRoleClientUuids = sourceRoles,
                )
            }.calculateLatest(aggregationEngine)
        }
    }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), SummaryUi())

    /** Emission source for the range tabs; the aggregated ui lands a recomputation later. */
    val selectedRange = range.asStateFlow()

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
    val ui by vm.ui.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val selectedRange by vm.selectedRange.collectAsStateWithLifecycle()
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
                selectedRange = selectedRange,
                shallowSyncStatus = shallowSyncStatus,
                isRefreshing = isRefreshing,
                onRefresh = vm::refresh,
                onSelectRange = vm::setRange,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SummaryContent(
    ui: SummaryUi,
    selectedRange: SummaryRange,
    shallowSyncStatus: ShallowSyncLine,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onSelectRange: (SummaryRange) -> Unit,
) {
    val density = LeziThemeExt.density
    val chartCardPad = PaddingValues(density.cardPad)
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
                    isError = shallowSyncStatus.isError,
                    isUserRefreshing = isRefreshing,
                    contentTestTag = "summary_shallow_sync_status",
                )

                // Tab highlight tracks the raw selection instantly; the
                // AnimatedContent below still swaps only with computed data.
                LeziRangeTabs(
                    items = SummaryRange.entries,
                    selected = selectedRange,
                    onSelect = onSelectRange,
                    label = { it.label },
                )

                AnimatedContent(
                    targetState = ui,
                    contentKey = { it.range },
                    transitionSpec = {
                        fadeIn(animationSpec = tween(durationMillis = rangeEnterMs)) togetherWith
                            fadeOut(animationSpec = tween(durationMillis = rangeExitMs))
                    },
                    label = "summary_range_content",
                ) { snapshot ->
                    val ui = snapshot
                    val range = ui.range
                    val t = ui.totals
                    val chartDates = List(range.dayCount) { offset ->
                        ui.rangeStartDate.plusDays(offset.toLong())
                    }
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
                        val sleepLabel = windows.daySleepMinLabel
                            ?: formatRecordDuration(windows.daySleepMin)
                        val sleepMetric = if (LeziThemeExt.isElder) {
                            sleepLabel to ""
                        } else {
                            splitSummaryKpiAmount(sleepLabel)
                        }
                        SummaryKpiStrip(
                            feedAmount = windows.dayFeedCountLabel
                                ?: "${windows.dayFeedCount}",
                            feedUnit = "次",
                            feedDetail = listOfNotNull(
                                windows.dayFeedMlLabel?.let { "奶量 $it" },
                                windows.dayNursingMinLabel?.let { "母乳 $it 分钟" },
                            ).takeIf { it.isNotEmpty() }?.joinToString(" · ")
                                ?: if (windows.dayFeedMl == 0 && windows.dayNursingMin == 0L) {
                                "当日暂无详情"
                            } else {
                                formatFeedWindowTotal(windows.dayFeedMl, windows.dayNursingMin)
                            },
                            sleepAmount = sleepMetric.first,
                            sleepUnit = sleepMetric.second,
                            sleepDetail = windows.daySleepSegmentsLabel?.let { "当日 $it 段" }
                                ?: if (windows.daySleepSegments == 0) {
                                "当日 0 段"
                            } else {
                                "当日 ${windows.daySleepSegments} 段"
                            },
                            diaperAmount = windows.dayDiaperLabel ?: "${windows.dayDiaper}",
                            diaperUnit = "次",
                            diaperDetail = "尿 ${windows.dayPeeLabel ?: windows.dayPee} · " +
                                "便 ${windows.dayPoopLabel ?: windows.dayPoop}",
                        )

                        if (range == SummaryRange.Week && ui.comparePrevWeek) {
                            val previous = ui.previousWeekTotals
                            LeziSurfacePanel(
                                Modifier.fillMaxWidth(),
                                contentPadding = chartCardPad,
                                bottomBand = true,
                            ) {
                                Text("对比前 7 天", style = LeziTypography.TitleSm)
                                if (previous == null) {
                                    Text(
                                        "前 7 天暂无记录",
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

                        // 票 09（T2）：空周期/空范围时图表区渲染空态卡，直说这个
                        // 范围还没有记录并指向底栏「记录」页；KPI 区维持现状，
                        // 不做跨页跳转按钮。非空范围维持原有四图 + 体温折线。
                        if (ui.empty) {
                            StateContainer(
                                kind = StateKind.Empty,
                                title = SUMMARY_EMPTY_PERIOD_TITLE,
                                message = SUMMARY_EMPTY_PERIOD_MESSAGE,
                                modifier = Modifier.testTag("summary_empty_period"),
                            )
                        } else {
                            SummaryChartSections(
                                range = range,
                                totals = t,
                                windows = windows,
                                chartDates = chartDates,
                                food = ui.food,
                                showAvgSleep = ui.showAvgSleep,
                                chartCardPad = chartCardPad,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(LeziSpacing.Xxl))
            }
        }
    }
}

private data class SummaryKpiSpec(
    val title: String,
    val amount: String,
    val unit: String,
    val detail: String,
)

@Composable
private fun SummaryKpiStrip(
    feedAmount: String,
    feedUnit: String,
    feedDetail: String,
    sleepAmount: String,
    sleepUnit: String,
    sleepDetail: String,
    diaperAmount: String,
    diaperUnit: String,
    diaperDetail: String,
) {
    val journal = LeziThemeExt.isJournal
    val cells = listOf(
        SummaryKpiSpec("喂养", feedAmount, feedUnit, feedDetail),
        SummaryKpiSpec("睡眠", sleepAmount, sleepUnit, sleepDetail),
        SummaryKpiSpec("尿布", diaperAmount, diaperUnit, diaperDetail),
    )
    if (LeziThemeExt.isElder) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
        ) {
            cells.forEach { spec ->
                CompactMetricCard(spec = spec, modifier = Modifier.fillMaxWidth())
            }
        }
    } else if (journal) {
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
                cells.forEachIndexed { index, spec ->
                    KpiCell(
                        spec = spec,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 74.dp)
                            .padding(horizontal = LeziSpacing.Xs, vertical = LeziSpacing.Sm),
                        valueStyle = LeziThemeExt.typography.MetricSm,
                        detailStyle = if (LeziThemeExt.isElder) {
                            LeziThemeExt.typography.Meta
                        } else {
                            LeziTypography.Micro
                        },
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
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
            verticalAlignment = Alignment.Top,
        ) {
            cells.forEach { spec ->
                CompactMetricCard(
                    spec = spec,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun CompactMetricCard(
    spec: SummaryKpiSpec,
    modifier: Modifier = Modifier,
) {
    LeziCard(
        modifier = modifier,
        contentPadding = PaddingValues(LeziThemeExt.density.cardPad),
    ) {
        KpiCell(
            spec = spec,
            valueStyle = LeziThemeExt.typography.Metric,
            detailStyle = if (LeziThemeExt.isElder) {
                LeziThemeExt.typography.Meta
            } else {
                LeziTypography.Eyebrow
            },
        )
    }
}

@Composable
private fun KpiCell(
    spec: SummaryKpiSpec,
    modifier: Modifier = Modifier,
    valueStyle: TextStyle? = null,
    detailStyle: TextStyle? = null,
) {
    val type = LeziThemeExt.typography
    val amountStyle = valueStyle ?: type.Metric
    val captionStyle = detailStyle ?: type.Meta
    val density = LocalDensity.current
    val elder = LeziThemeExt.isElder
    val titleHeight = with(density) { type.Meta.lineHeight.toDp() }
    val valueHeight = with(density) { amountStyle.lineHeight.toDp() }
    val detailHeight = with(density) { captionStyle.lineHeight.toDp() }
    fun Modifier.kpiLine(min: androidx.compose.ui.unit.Dp): Modifier {
        return if (elder) heightIn(min = min) else height(min)
    }
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .kpiLine(titleHeight),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                spec.title,
                style = type.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (elder) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(LeziSpacing.Xxs))
        Box(
            Modifier
                .fillMaxWidth()
                .kpiLine(valueHeight),
            contentAlignment = if (elder) {
                Alignment.BottomEnd
            } else {
                Alignment.BottomStart
            },
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    spec.amount,
                    modifier = Modifier.alignByBaseline(),
                    style = amountStyle,
                    maxLines = if (elder) 2 else 1,
                    overflow = if (elder) TextOverflow.Clip else TextOverflow.Ellipsis,
                )
                if (spec.unit.isNotEmpty()) {
                    Text(
                        spec.unit,
                        modifier = Modifier
                            .alignByBaseline()
                            .padding(start = LeziSpacing.Xxs),
                        style = type.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(LeziSpacing.Xxs))
        Box(
            Modifier
                .fillMaxWidth()
                .kpiLine(detailHeight),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                spec.detail,
                style = captionStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (elder) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Peel a trailing unit so KPI numerals share one baseline across cards. */
internal fun splitSummaryKpiAmount(raw: String): Pair<String, String> {
    val last = raw.lastOrNull() ?: return raw to ""
    return if (last == '次' || last == 'm' || last == 'h') {
        raw.dropLast(1) to last.toString()
    } else {
        raw to ""
    }
}

/** Elder+Day keeps only the KPI strip; week/month charts stay but lose the totals header. */
internal fun elderHidesSummaryCharts(range: SummaryRange): Boolean = range == SummaryRange.Day

/**
 * Chart-card section of the summary range body: the four stacked panels and the
 * temperature line chart. Extracted so device tests can drive the visibility
 * policy without the aggregation engine.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SummaryChartSections(
    range: SummaryRange,
    totals: SummaryTotals,
    windows: ChartWindowTotals,
    chartDates: List<LocalDate>,
    food: SummaryFoodPanel?,
    showAvgSleep: Boolean,
    chartCardPad: PaddingValues,
) {
    val ext = LeziThemeExt.colors
    val hideCharts = LeziThemeExt.isElder && elderHidesSummaryCharts(range)
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
            totals.feedMlLabel?.let { "奶量 $it" },
            totals.nursingMinLabel?.let { "母乳 $it" },
        ).takeIf { it.isNotEmpty() }?.joinToString(" · ")
            ?: formatFeedWindowTotal(totals.feedMl, totals.nursingMin)
    }
    val sleepChartTotal = when (range) {
        SummaryRange.Day -> windows.daySleepMinLabel
            ?: formatRecordDuration(windows.daySleepMin)
        SummaryRange.Week, SummaryRange.Month -> totals.sleepMinLabel
            ?: formatRecordDuration(totals.sleepMin)
    }
    val diaperChartTotal = when (range) {
        SummaryRange.Day -> windows.dayDiaperLabel
            ?: formatDiaperTotal(windows.dayPee, windows.dayPoop)
        SummaryRange.Week, SummaryRange.Month -> totals.diaperLabel
            ?: formatDiaperTotal(totals.pee, totals.poop)
    }
    val chartTotalScope = when (range) {
        SummaryRange.Day -> "当日"
        SummaryRange.Week -> "近 7 天"
        SummaryRange.Month -> "本月"
    }

    if (!hideCharts) {
        // Chart panels render only when the range holds data for them;
        // empty ranges hide the whole card instead of an in-card empty state.
        if (totals.feedMl > 0 || totals.nursingMin > 0L) {
            SummaryChartPanel(
                title = "喂养",
                scopeLabel = chartTotalScope,
                totalValue = feedChartTotal,
                contentPadding = chartCardPad,
                showTotals = !LeziThemeExt.isElder,
            ) {
                MiniBarChart(
                    values = totals.dayValuesFeed,
                    dates = chartDates,
                    metricLabel = "喂养量",
                    color = ext.laneFeed,
                    valueFormatter = { v -> if (v <= 0f) "" else "${v.toInt()}ml" },
                )
            }
        }

        food?.let { foodPanel ->
            val laneColors = foodSummaryLaneColors(
                darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f,
            )
            SummaryChartPanel(
                title = "辅食",
                scopeLabel = chartTotalScope,
                totalValue = formatFoodAmountBound(foodPanel.amountMin, foodPanel.amountMax),
                // Panel presence (food != null) is the only gate: a range with
                // records but no parsable amounts renders zero-height bars plus
                // the 未填量 note, not an empty state.
                contentPadding = chartCardPad,
                showTotals = !LeziThemeExt.isElder,
                preContent = {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Md),
                        verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xxs),
                    ) {
                        foodPanel.lanes.forEachIndexed { index, lane ->
                            DiaperLegendDot(
                                color = laneColors[index],
                                label = lane.name,
                            )
                        }
                    }
                    Spacer(Modifier.height(LeziSpacing.Xs))
                    foodUnfilledNote(foodPanel.unparseableCount)?.let { note ->
                        Text(
                            note,
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(LeziSpacing.Xs))
                    }
                },
            ) {
                StackedFoodBarChart(
                    laneDayValues = foodPanel.lanes.map { lane ->
                        lane.dayValues.map { it.toFloat() }
                    },
                    laneNames = foodPanel.lanes.map(SummaryFoodLane::name),
                    unparseableDayCounts = foodPanel.unparseableDayCounts,
                    laneColors = foodPanel.lanes.mapIndexed { index, _ ->
                        laneColors[index]
                    },
                    dates = chartDates,
                )
            }
        }

        if (totals.sleepMin > 0L) {
            SummaryChartPanel(
                title = "睡眠",
                scopeLabel = chartTotalScope,
                totalValue = sleepChartTotal,
                contentPadding = chartCardPad,
                showTotals = !LeziThemeExt.isElder,
                preContent = {
                    if (range != SummaryRange.Day && showAvgSleep) {
                        val averageSleep =
                            totals.sleepMin / range.dayCount.coerceAtLeast(1)
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
                    values = totals.dayValuesSleep,
                    dates = chartDates,
                    metricLabel = "睡眠分钟",
                    color = ext.laneSleep,
                    valueFormatter = { v ->
                        if (v <= 0f) "" else formatRecordDuration(v.toLong())
                    },
                )
            }
        }

        if (totals.pee > 0 || totals.poop > 0) {
            SummaryChartPanel(
                title = "尿布",
                scopeLabel = chartTotalScope,
                totalValue = diaperChartTotal,
                contentPadding = chartCardPad,
                showTotals = !LeziThemeExt.isElder,
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
                    pee = totals.dayValuesPee,
                    poop = totals.dayValuesPoop,
                    dates = chartDates,
                    peeColor = ext.laneCare,
                    poopColor = ext.sun,
                )
            }
        }

        if (totals.tempAvg != null) {
            WeekLineChart(
                title = "体温",
                values = totals.dayValuesTemp,
                dates = chartDates,
                color = ext.danger,
                scopeLabel = "${totals.tempDays} 天有记录",
                totalValue = "%.1f℃".format(totals.tempAvg),
                showTotals = !LeziThemeExt.isElder,
            )
        }
    }
}

/**
 * Shared chart card shell: header + optional pre-content + chart body. Callers gate
 * visibility at the call site -- a range with no data hides the whole card.
 */
@Composable
private fun SummaryChartPanel(
    title: String,
    scopeLabel: String,
    totalValue: String,
    contentPadding: PaddingValues,
    showTotals: Boolean = true,
    preContent: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    LeziSurfacePanel(
        Modifier
            .fillMaxWidth()
            .testTag("summary_chart_$title"),
        contentPadding = contentPadding,
        bottomBand = true,
    ) {
        ChartCardHeader(
            title = title,
            scopeLabel = scopeLabel,
            totalValue = totalValue,
            showTotals = showTotals,
        )
        Spacer(Modifier.height(LeziSpacing.Xs))
        preContent?.invoke()
        content()
    }
}

@Composable
private fun ChartCardHeader(
    title: String,
    scopeLabel: String,
    totalValue: String,
    eyebrow: String? = null,
    showTotals: Boolean = true,
) {
    if (!showTotals) {
        Text(title, style = LeziTypography.TitleSm)
        return
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            if (eyebrow != null) {
                Text(
                    eyebrow,
                    style = LeziThemeExt.typography.Eyebrow,
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

/** Bar rectangle prebuilt in a drawWithCache block; the draw pass only paints it. */
private data class PrebuiltBarRect(
    val color: Color,
    val topLeft: Offset,
    val size: Size,
    val cornerRadius: androidx.compose.ui.geometry.CornerRadius,
)

/** Outlined empty-day marker prebuilt alongside [PrebuiltBarRect]. */
private data class PrebuiltBarMarker(
    val color: Color,
    val topLeft: Offset,
    val size: Size,
    val cornerRadius: androidx.compose.ui.geometry.CornerRadius,
    val strokeWidth: Float,
)

/** Bar-top label with its [AndroidPaint.measureText] width resolved up front. */
private data class PrebuiltBarLabel(
    val text: String,
    val measuredWidth: Float,
    val centerX: Float,
    val y: Float,
    val maxWidth: Float,
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
private fun Density.chartBarLayout(barCount: Int, canvasWidth: Float): BarSlotLayout =
    calculateBarSlotLayout(
        canvasWidth = canvasWidth,
        barCount = barCount,
        preferredGap = (if (barCount > 14) 2.dp else 4.dp).toPx(),
    )

/** Shared bar-top label paint; 10sp is the readable floor even on dense 30-day charts. */
@Composable
private fun rememberBarLabelPaint(labelColor: Color): AndroidPaint {
    val density = LocalDensity.current
    return remember(labelColor, density) {
        with(density) {
            AndroidPaint().apply {
                isAntiAlias = true
                textAlign = AndroidPaint.Align.CENTER
                textSize = 10.sp.toPx()
                typeface = Typeface.DEFAULT
                color = labelColor.toArgb()
            }
        }
    }
}

/**
 * Draws a prebuilt bar-top value label, skipping empty labels and labels
 * wider than their slot so dense windows never overflow into neighbors; the
 * width comparison uses the [PrebuiltBarLabel.measuredWidth] resolved in the
 * cache block instead of measuring per frame.
 */
private fun DrawScope.drawBarLabel(
    paint: AndroidPaint,
    label: PrebuiltBarLabel,
) {
    if (label.text.isEmpty()) return
    if (label.measuredWidth > label.maxWidth) return
    drawContext.canvas.nativeCanvas.drawText(
        label.text,
        label.centerX,
        label.y,
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
        Text(
            label,
            style = LeziTypography.Meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    StackedLaneBarChart(
        lanes = listOf(pee, poop),
        laneColors = listOf(peeColor, poopColor),
        laneAlphas = listOf(0.9f, 0.92f),
        dates = dates,
        barLabel = { total -> "${total.toInt()}" },
        dayDescription = { _, date, values ->
            "${date.monthValue}月${date.dayOfMonth}日 " +
                "尿${values[0].toInt()}次 便${values[1].toInt()}次 " +
                "共${(values[0] + values[1]).toInt()}次"
        },
    )
}

@Composable
private fun StackedFoodBarChart(
    laneDayValues: List<List<Float>>,
    laneNames: List<String>,
    laneColors: List<Color>,
    unparseableDayCounts: List<Int>,
    dates: List<LocalDate>,
) {
    StackedLaneBarChart(
        lanes = laneDayValues,
        laneColors = laneColors,
        laneAlphas = List(laneColors.size) { 0.9f },
        dates = dates,
        emptyDayMarkers = foodUnparseableOnlyDays(laneDayValues, unparseableDayCounts),
        emptyDayMarkerColor = MaterialTheme.colorScheme.onSurfaceVariant,
        barLabel = { total -> formatFoodChartValue(total) },
        dayDescription = { index, date, values ->
            foodDayDescription(
                date = date,
                laneNames = laneNames,
                values = values,
                unparseableCount = unparseableDayCounts.getOrElse(index) { 0 },
            )
        },
    )
}

internal fun foodUnparseableOnlyDays(
    laneDayValues: List<List<Float>>,
    unparseableDayCounts: List<Int>,
): List<Boolean> = List(
    maxOf(unparseableDayCounts.size, laneDayValues.maxOfOrNull { it.size } ?: 0),
) { dayIndex ->
    unparseableDayCounts.getOrElse(dayIndex) { 0 } > 0 &&
        laneDayValues.all { lane -> lane.getOrElse(dayIndex) { 0f } <= 0f }
}

internal fun foodDayDescription(
    date: LocalDate,
    laneNames: List<String>,
    values: List<Float>,
    unparseableCount: Int,
): String {
    val laneText = laneNames.zip(values)
        .filter { (_, value) -> value > 0f }
        .joinToString("、") { (name, value) -> "$name${formatFoodChartValue(value)}" }
    val total = values.fold(0f) { acc, value -> acc + value }
    return listOfNotNull(
        "${date.monthValue}月${date.dayOfMonth}日",
        laneText.takeIf { it.isNotEmpty() },
        "共${formatFoodChartValue(total)}",
        "未填量${unparseableCount}条".takeIf { unparseableCount > 0 },
    ).joinToString(" ")
}

/**
 * Shared stacked-bar geometry for the diaper and 辅食 panels. Lanes draw bottom-up in
 * list order, the topmost filled segment keeps the rounded corners, and that last lane
 * absorbs the sub-pixel remainder so segment heights always sum to the bar height.
 */
@Composable
private fun StackedLaneBarChart(
    lanes: List<List<Float>>,
    laneColors: List<Color>,
    laneAlphas: List<Float>,
    dates: List<LocalDate>,
    emptyDayMarkers: List<Boolean> = emptyList(),
    emptyDayMarkerColor: Color? = null,
    barLabel: (Float) -> String,
    dayDescription: (Int, LocalDate, List<Float>) -> String,
) {
    val n = maxOf(lanes.maxOfOrNull { it.size } ?: 0, emptyDayMarkers.size, 1)
    val laneValues = lanes.map { lane -> lane + List((n - lane.size).coerceAtLeast(0)) { 0f } }
    val totals = List(n) { i -> laneValues.fold(0f) { acc, lane -> acc + lane[i] } }
    val grid = LeziThemeExt.colors.chartGrid
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val max = (totals.maxOrNull() ?: 0f).coerceAtLeast(1f)
    val showLabels = showBarLabels(n, totals.count { it > 0f })
    val labelPaint = rememberBarLabelPaint(labelColor)
    Column {
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(if (LeziThemeExt.isElder) 160.dp else if (showLabels) 108.dp else 88.dp)
                .semantics {
                    contentDescription = (0 until minOf(n, dates.size)).joinToString("；") { i ->
                        dayDescription(i, dates[i], laneValues.map { it[i] })
                    }
                }
                .drawWithCache {
                    // Slot layout, segment/marker geometry, label strings, and
                    // measureText resolve here; range fades re-invoke only the
                    // draw block below instead of redoing layout per frame.
                    val topPad = if (showLabels) 16.dp.toPx() else 0f
                    val plotH = (size.height - topPad).coerceAtLeast(1f)
                    val gridColor = grid.copy(alpha = 0.45f)
                    val gridYs = (0..3).map { topPad + plotH * it / 3f }
                    val layout = chartBarLayout(n, size.width)
                    val gap = layout.gap
                    val barW = layout.barWidth
                    val radius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    val flatRadius = androidx.compose.ui.geometry.CornerRadius(0f, 0f)
                    val markerHeight = 6.dp.toPx()
                    val markerStrokeWidth = 1.dp.toPx()
                    val segments = mutableListOf<PrebuiltBarRect>()
                    val markers = mutableListOf<PrebuiltBarMarker>()
                    val labels = mutableListOf<PrebuiltBarLabel>()
                    for (i in 0 until n) {
                        val x = layout.firstBarX + i * (barW + gap)
                        val total = totals[i]
                        val totalH = (total / max) * (plotH * 0.85f)
                        val filledLanes = laneValues.indices.filter { laneValues[it][i] > 0f }
                        var yCursor = size.height
                        var allocated = 0f
                        filledLanes.forEachIndexed { position, laneIndex ->
                            val isTopmost = position == filledLanes.lastIndex
                            val h = if (isTopmost) {
                                (totalH - allocated).coerceAtLeast(2f)
                            } else {
                                (totalH * (laneValues[laneIndex][i] / total)).coerceAtLeast(2f)
                            }
                            allocated += h
                            yCursor -= h
                            segments += PrebuiltBarRect(
                                color = laneColors[laneIndex].copy(alpha = laneAlphas[laneIndex]),
                                topLeft = Offset(x, yCursor),
                                size = Size(barW, h),
                                cornerRadius = if (isTopmost) radius else flatRadius,
                            )
                        }
                        if (filledLanes.isEmpty() && emptyDayMarkers.getOrElse(i) { false }) {
                            markers += PrebuiltBarMarker(
                                color = emptyDayMarkerColor ?: labelColor,
                                topLeft = Offset(x, size.height - markerHeight),
                                size = Size(barW, markerHeight),
                                cornerRadius = radius,
                                strokeWidth = markerStrokeWidth,
                            )
                        }
                        if (showLabels && total > 0f) {
                            val text = barLabel(total)
                            labels += PrebuiltBarLabel(
                                text = text,
                                measuredWidth = if (text.isEmpty()) {
                                    0f
                                } else {
                                    labelPaint.measureText(text)
                                },
                                centerX = x + barW / 2f,
                                y = (yCursor - 4f).coerceAtLeast(labelPaint.textSize),
                                maxWidth = barW + gap,
                            )
                        }
                    }
                    onDrawBehind {
                        gridYs.forEach { yy ->
                            drawLine(gridColor, Offset(0f, yy), Offset(size.width, yy), 1f)
                        }
                        segments.forEach { segment ->
                            drawRoundRect(
                                color = segment.color,
                                topLeft = segment.topLeft,
                                size = segment.size,
                                cornerRadius = segment.cornerRadius,
                            )
                        }
                        markers.forEach { marker ->
                            drawRoundRect(
                                color = marker.color,
                                topLeft = marker.topLeft,
                                size = marker.size,
                                cornerRadius = marker.cornerRadius,
                                style = Stroke(width = marker.strokeWidth),
                            )
                        }
                        labels.forEach { label -> drawBarLabel(labelPaint, label) }
                    }
                },
        )
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
    val labelPaint = rememberBarLabelPaint(labelColor)
    Column {
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(if (LeziThemeExt.isElder) 160.dp else if (showLabels) 108.dp else 88.dp)
                .semantics {
                    contentDescription = chartDescription(metricLabel, dates, values)
                }
                .drawWithCache {
                    // Same cache discipline as StackedLaneBarChart: layout,
                    // label strings, and measureText resolve here once per
                    // (values, size) change; the draw block only paints.
                    val topPad = if (showLabels) 16.dp.toPx() else 0f
                    val plotH = (size.height - topPad).coerceAtLeast(1f)
                    val gridColor = grid.copy(alpha = 0.45f)
                    val gridYs = (0..3).map { topPad + plotH * it / 3f }
                    val n = values.size.coerceAtLeast(1)
                    val layout = chartBarLayout(n, size.width)
                    val gap = layout.gap
                    val barW = layout.barWidth
                    val radius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    val barColor = color.copy(alpha = 0.85f)
                    val bars = values.mapIndexedNotNull { i, v ->
                        if (v <= 0f) return@mapIndexedNotNull null
                        val h = (v / max) * (plotH * 0.85f)
                        val x = layout.firstBarX + i * (barW + gap)
                        PrebuiltBarRect(
                            color = barColor,
                            topLeft = Offset(
                                x,
                                size.height - h.coerceAtLeast(2f),
                            ),
                            size = Size(barW, h.coerceAtLeast(2f)),
                            cornerRadius = radius,
                        )
                    }
                    val labels = if (showLabels) {
                        values.mapIndexed { i, v ->
                            val text = valueFormatter(v)
                            val h = (v / max) * (plotH * 0.85f)
                            val x = layout.firstBarX + i * (barW + gap)
                            val barTop = size.height - h.coerceAtLeast(if (v > 0f) 2f else 0f)
                            PrebuiltBarLabel(
                                text = text,
                                measuredWidth = if (text.isEmpty()) {
                                    0f
                                } else {
                                    labelPaint.measureText(text)
                                },
                                centerX = x + barW / 2f,
                                y = (barTop - 4f).coerceAtLeast(labelPaint.textSize),
                                maxWidth = barW + gap,
                            )
                        }
                    } else {
                        emptyList()
                    }
                    onDrawBehind {
                        gridYs.forEach { yy ->
                            drawLine(gridColor, Offset(0f, yy), Offset(size.width, yy), 1f)
                        }
                        bars.forEach { bar ->
                            drawRoundRect(
                                color = bar.color,
                                topLeft = bar.topLeft,
                                size = bar.size,
                                cornerRadius = bar.cornerRadius,
                            )
                        }
                        labels.forEach { label -> drawBarLabel(labelPaint, label) }
                    }
                },
        )
        DateAxis(dates)
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
    showTotals: Boolean = true,
) {
    val journal = LeziThemeExt.isJournal
    val grid = LeziThemeExt.colors.chartGrid
    // Reused across draws: range fades re-invoke the draw block every frame.
    val linePath = remember { Path() }
    LeziSurfacePanel(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(LeziThemeExt.density.cardPad),
        bottomBand = true,
    ) {
        if (showTotals && scopeLabel != null && totalValue != null) {
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
                .height(if (LeziThemeExt.isElder) 160.dp else 120.dp)
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
            linePath.reset()
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
                    linePath.moveTo(x, y)
                    started = true
                } else {
                    linePath.lineTo(x, y)
                }
                drawCircle(color, radius = dotRadius, center = Offset(x, y))
            }
            if (started) drawPath(linePath, color, style = Stroke(width = strokeWidth))
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
    val compact = LeziThemeExt.isElder || dates.size > 7
    if (dates.size == 1) {
        Text(
            "${dates.single().monthValue}/${dates.single().dayOfMonth}",
            modifier = modifier.fillMaxWidth(),
            style = LeziThemeExt.typography.Meta,
            color = color,
            textAlign = TextAlign.Center,
        )
    } else if (!compact) {
        Row(modifier.fillMaxWidth()) {
            dates.forEach { date ->
                Text(
                    "${date.monthValue}/${date.dayOfMonth}",
                    modifier = Modifier.weight(1f),
                    style = LeziThemeExt.typography.Micro,
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
                    style = LeziThemeExt.typography.Meta,
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

/** Unitless raw 辅食 sum for the panel header; keeps accepted input precision (1.25). */
internal fun formatFoodAmountTotal(value: Double): String =
    if (value % 1.0 == 0.0) {
        value.toLong().toString()
    } else {
        "%.3f".format(value).trimEnd('0').trimEnd('.')
    }

/** Exact total, or lower–upper when an unresolved group makes the sum uncertain. */
internal fun formatFoodAmountBound(min: Double, max: Double): String {
    val lower = formatFoodAmountTotal(min)
    val upper = formatFoodAmountTotal(max)
    return if (lower == upper) lower else "$lower–$upper"
}

internal fun foodUnfilledNote(unparseableCount: Int): String? =
    if (unparseableCount > 0) "另有 $unparseableCount 条未填量" else null

internal fun formatFoodChartValue(value: Float): String =
    if (value % 1f == 0f) "${value.toInt()}" else "%.1f".format(value)

@Composable
internal fun SummaryDuplicateBoundsNotice(
    visible: Boolean,
    contentPadding: PaddingValues = PaddingValues(LeziSpacing.Md),
) {
    if (!visible) return
    LeziSurfacePanel(
        Modifier
            .fillMaxWidth()
            .testTag("summary_duplicate_bounds")
            .semantics(mergeDescendants = true) {},
        contentPadding = contentPadding,
    ) {
        Text("疑似重复尚未确认", style = LeziTypography.TitleSm)
        Text(
            "以下指标按全部合法解释显示下界–上界，不会暗选某一来源。",
            style = LeziTypography.Meta,
        )
    }
}
