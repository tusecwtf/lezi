package com.lezi.babylog.feature.summary

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.designsystem.LeziCard
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziThemeExt
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.PageHero
import com.lezi.babylog.designsystem.PageScaffoldBackground
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.WeekSummary
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

enum class SummaryRange(
    val label: String,
    val periodLabel: String,
    val dayCount: Int,
) {
    Day("日", "当日", 1),
    Week("周", "近 7 天", 7),
    Month("月", "近 30 天", 30),
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
    val dayValuesDiaper: List<Float> = emptyList(),
    val dayValuesTemp: List<Float> = emptyList(),
    val feedTimeBuckets: List<Float> = List(4) { 0f },
)

data class SummaryUi(
    val range: SummaryRange = SummaryRange.Week,
    val anchorDate: LocalDate = LocalDate.now(),
    val totals: SummaryTotals = SummaryTotals(),
    val week: WeekSummary? = null,
    val showAvgSleep: Boolean = false,
    val empty: Boolean = true,
    val babyName: String = "",
)

private data class SummaryRequest(
    val range: SummaryRange,
    val anchorDate: LocalDate,
    val showAvgSleep: Boolean,
    val baby: Baby?,
)

@HiltViewModel
class SummaryViewModel @Inject constructor(
    private val careLog: CareLog,
    private val settings: SettingsStore,
) : ViewModel() {
    private val zone = ZoneId.systemDefault()
    private val range = MutableStateFlow(SummaryRange.Week)
    private val anchorDate = MutableStateFlow(LocalDate.now(zone))

    @OptIn(ExperimentalCoroutinesApi::class)
    val ui = combine(
        range,
        anchorDate,
        settings.showAvgSleep,
        careLog.observeCurrentBaby(),
    ) { selectedRange, anchor, showAvgSleep, baby ->
        SummaryRequest(selectedRange, anchor, showAvgSleep, baby)
    }.flatMapLatest { request ->
        val selectedRange = request.range
        val anchor = request.anchorDate
        val baby = request.baby
        if (baby == null) {
            flowOf(
                SummaryUi(
                    range = selectedRange,
                    anchorDate = anchor,
                    showAvgSleep = request.showAvgSleep,
                    empty = true,
                ),
            )
        } else {
            val detailStart = anchor.minusDays(6)
            val queryStart = minOf(selectedRange.startDate(anchor), detailStart)
            careLog.observeRecords(
                babyId = baby.id,
                startDayInclusive = queryStart,
                endDayExclusive = anchor.plusDays(1),
                zone = zone,
            ).map { records ->
                buildSummaryUi(
                    records = records,
                    range = selectedRange,
                    anchorDate = anchor,
                    showAvgSleep = request.showAvgSleep,
                    babyName = baby.nickname,
                    zone = zone,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SummaryUi())

    fun setRange(r: SummaryRange) {
        range.value = r
    }

    fun setAnchorDate(day: LocalDate) {
        anchorDate.value = day
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
    val ext = LeziThemeExt.colors
    val journal = LeziThemeExt.isJournal
    val t = ui.totals
    val chartDates = List(ui.range.dayCount) { offset ->
        ui.range.startDate(ui.anchorDate).plusDays(offset.toLong())
    }

    PageScaffoldBackground {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(LeziSpacing.Page),
            verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
        ) {
            PageHero(
                eyebrow = if (journal) "7 日记录矩阵" else "规律，不是压力",
                title = "汇总",
                subtitle = if (ui.babyName.isNotBlank()) {
                    "${ui.babyName}的记录，由本地数据实时计算。"
                } else {
                    "由本地数据实时计算。"
                },
            )

            RangeTabs(
                selected = ui.range,
                onSelect = vm::setRange,
            )

            // Prototype: three metric cards in one row.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CompactMetricCard(
                    title = "喂养",
                    value = if (t.feedCount == 0 && t.feedMl == 0) "0次" else "${t.feedCount}次",
                    detail = if (t.feedMl == 0 && t.nursingMin == 0L) "暂无详情" else {
                        buildString {
                            if (t.feedMl > 0) append("${t.feedMl}ml")
                            if (t.nursingMin > 0) {
                                if (isNotEmpty()) append(" · ")
                                append("母乳 ${t.nursingMin} 分")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                CompactMetricCard(
                    title = "睡眠",
                    value = formatMin(t.sleepMin),
                    detail = if (t.sleepSegments == 0) "0 段睡眠" else "${t.sleepSegments} 段睡眠",
                    modifier = Modifier.weight(1f),
                )
                CompactMetricCard(
                    title = "尿布",
                    value = "${t.pee + t.poop}",
                    detail = "尿 ${t.pee} · 便 ${t.poop}",
                    modifier = Modifier.weight(1f),
                )
            }

            if (journal && ui.week != null) {
                JournalWeekGrid(ui.week!!)
            }

            // Prototype "记录分布 / 喂养节律" combined card with 4 buckets for Day.
            LeziCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(
                            "记录分布",
                            style = LeziTypography.Eyebrow,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("喂养节律", style = LeziTypography.TitleSm)
                    }
                    Text(
                        ui.range.periodLabel,
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (ui.range == SummaryRange.Day) {
                    if (t.feedTimeBuckets.any { it > 0f }) {
                        FourBucketBars(values = t.feedTimeBuckets, color = ext.laneFeed)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            listOf("00–06", "06–12", "12–18", "18–24").forEach {
                                Text(
                                    it,
                                    style = LeziTypography.Meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        Text(
                            "当日暂无喂养记录。",
                            style = LeziTypography.Body,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (ui.empty) {
                    Text(
                        "范围内暂无喂养记录。",
                        style = LeziTypography.Body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    MiniBarChart(
                        values = t.dayValuesFeed,
                        dates = chartDates,
                        metricLabel = "喂养量",
                        color = ext.laneFeed,
                    )
                }
            }



            LeziCard(Modifier.fillMaxWidth()) {
                Text("睡眠片段", style = LeziTypography.TitleSm)
                Text(
                    "按已完成记录",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (t.sleepMin == 0L) {
                    Text(
                        "范围内暂无已完成睡眠记录",
                        style = LeziTypography.Body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    MiniBarChart(
                        values = t.dayValuesSleep,
                        dates = chartDates,
                        metricLabel = "睡眠分钟",
                        color = ext.laneSleep,
                    )
                }
            }

            if (t.dayValuesDiaper.any { it > 0f }) {
                WeekBarChart(
                    title = "尿布趋势",
                    values = t.dayValuesDiaper,
                    dates = chartDates,
                    color = ext.laneCare,
                )
            }
            if (t.tempAvg != null) {
                MetricRow(
                    title = "体温",
                    value = "%.1f℃".format(t.tempAvg),
                    detail = "${t.tempDays} 天有记录",
                )
                WeekLineChart(
                    title = "体温趋势",
                    values = t.dayValuesTemp,
                    dates = chartDates,
                    color = ext.danger,
                )
            }

            Spacer(Modifier.height(LeziSpacing.Xxl))
        }
    }
}

@Composable
private fun RangeTabs(
    selected: SummaryRange,
    onSelect: (SummaryRange) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SummaryRange.entries.forEach { r ->
            val on = r == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (on) MaterialTheme.colorScheme.surface else Color.Transparent,
                    )
                    .then(
                        if (on) {
                            Modifier.border(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                RoundedCornerShape(11.dp),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onSelect(r) }
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
private fun FourBucketBars(values: List<Float>, color: Color) {
    val grid = LeziThemeExt.colors.chartGrid
    val max = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .semantics {
                contentDescription = listOf("00到06", "06到12", "12到18", "18到24")
                    .zip(values)
                    .joinToString("；") { (period, value) ->
                        "$period ${value.toInt()}次"
                    }
            },
    ) {
        for (i in 0..3) {
            val y = size.height * i / 3f
            drawLine(grid.copy(alpha = 0.4f), Offset(0f, y), Offset(size.width, y), 1f)
        }
        val gap = 10.dp.toPx()
        val barW = (size.width - gap * 5) / 4f
        values.take(4).forEachIndexed { i, value ->
            if (value <= 0f) return@forEachIndexed
            val x = gap + i * (barW + gap)
            val height = (value / max) * size.height * 0.82f
            drawRoundRect(
                color = color.copy(alpha = 0.86f),
                topLeft = Offset(x, size.height - height),
                size = Size(barW, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f),
            )
        }
    }
}

@Composable
private fun MetricRow(title: String, value: String, detail: String, compare: String? = null) {
    val journal = LeziThemeExt.isJournal
    LeziCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = if (journal) {
            androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp)
        } else {
            androidx.compose.foundation.layout.PaddingValues(14.dp)
        },
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
private fun MiniBarChart(
    values: List<Float>,
    dates: List<LocalDate>,
    metricLabel: String,
    color: Color,
) {
    val grid = LeziThemeExt.colors.chartGrid
    val max = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(88.dp)
                .semantics {
                    contentDescription = chartDescription(metricLabel, dates, values)
                },
        ) {
            for (i in 0..3) {
                val y = size.height * i / 3f
                drawLine(grid.copy(alpha = 0.45f), Offset(0f, y), Offset(size.width, y), 1f)
            }
            val n = values.size.coerceAtLeast(1)
            val gap = 4.dp.toPx()
            val barW = ((size.width - gap * (n + 1)) / n).coerceAtLeast(2f)
            values.forEachIndexed { i, v ->
                val h = (v / max) * (size.height * 0.85f)
                val x = gap + i * (barW + gap)
                drawRoundRect(
                    color = color.copy(alpha = 0.85f),
                    topLeft = Offset(x, size.height - h.coerceAtLeast(2f)),
                    size = Size(barW, h.coerceAtLeast(2f)),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
                )
            }
        }
        DateAxis(dates)
    }
}

@Composable
private fun JournalWeekGrid(summary: WeekSummary) {
    val ext = LeziThemeExt.colors
    LeziCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("周节律网格", style = LeziTypography.Label)
            Text("汇总量，不代表发生时刻", style = LeziTypography.Meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
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
private fun WeekBarChart(
    title: String,
    values: List<Float>,
    dates: List<LocalDate>,
    color: Color,
) {
    LeziCard(modifier = Modifier.fillMaxWidth()) {
        Text(title, style = LeziTypography.TitleSm)
        Spacer(Modifier.height(8.dp))
        MiniBarChart(values = values, dates = dates, metricLabel = title, color = color)
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
    LeziCard(modifier = Modifier.fillMaxWidth()) {
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
