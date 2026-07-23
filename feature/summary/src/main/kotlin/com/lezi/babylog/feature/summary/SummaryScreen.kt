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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.designsystem.LeziCard
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziThemeExt
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.PageHero
import com.lezi.babylog.designsystem.PageScaffoldBackground
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.DailySummary
import com.lezi.babylog.domain.WeekSummary
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.weekStartFor
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

enum class SummaryRange(val label: String) {
    Today("今日"),
    Days7("近 7 天"),
    Days30("近 30 天"),
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
)

data class SummaryUi(
    val range: SummaryRange = SummaryRange.Today,
    val totals: SummaryTotals = SummaryTotals(),
    val week: WeekSummary? = null,
    val showAvgSleep: Boolean = false,
    val empty: Boolean = true,
    val babyName: String = "",
)

@HiltViewModel
class SummaryViewModel @Inject constructor(
    private val careLog: CareLog,
    private val settings: SettingsStore,
) : ViewModel() {
    private val range = MutableStateFlow(SummaryRange.Today)
    private val zone = ZoneId.systemDefault()

    @OptIn(ExperimentalCoroutinesApi::class)
    val ui = combine(
        range,
        settings.showAvgSleep,
        careLog.observeCurrentBaby(),
    ) { r, avg, baby ->
        Triple(r, avg, baby)
    }.flatMapLatest { (r, avg, baby) ->
        if (baby == null) {
            flowOf(SummaryUi(range = r, empty = true))
        } else {
            flow {
                val today = LocalDate.now(zone)
                val dayCount = when (r) {
                    SummaryRange.Today -> 1
                    SummaryRange.Days7 -> 7
                    SummaryRange.Days30 -> 30
                }
                val start = today.minusDays((dayCount - 1).toLong())
                val daily = mutableListOf<DailySummary>()
                val temps = mutableListOf<List<Double>>()
                var feedCount = 0
                var sleepSeg = 0
                for (i in 0 until dayCount) {
                    val day = start.plusDays(i.toLong())
                    val s = careLog.daySummary(baby.id, day, zone)
                    daily += s
                    val records = careLog.dayRecords(baby.id, day, zone)
                    feedCount += records.count {
                        it.type == RecordType.FORMULA ||
                            it.type == RecordType.NURSING ||
                            it.type == RecordType.PUMPED_FEED
                    }
                    sleepSeg += records.count { it.type == RecordType.SLEEP && it.endTimestamp != null }
                    val dayTemps = records.mapNotNull { rec ->
                        if (rec.type == RecordType.TEMPERATURE) {
                            Regex(""""value"\s*:\s*(-?\d+(?:\.\d+)?)""")
                                .find(rec.payloadJson)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                        } else {
                            null
                        }
                    }
                    temps += dayTemps
                }
                val allTemps = temps.flatten()
                val totals = SummaryTotals(
                    feedMl = daily.sumOf { it.feedMl },
                    nursingMin = daily.sumOf { it.nursingMinutes },
                    feedCount = feedCount,
                    sleepMin = daily.sumOf { it.sleepMinutes },
                    sleepSegments = sleepSeg,
                    pee = daily.sumOf { it.peeCount },
                    poop = daily.sumOf { it.poopCount },
                    tempAvg = allTemps.takeIf { it.isNotEmpty() }?.average(),
                    tempDays = temps.count { it.isNotEmpty() },
                    dayValuesFeed = daily.map { it.feedMl.toFloat() },
                    dayValuesSleep = daily.map { it.sleepMinutes.toFloat() },
                    dayValuesDiaper = daily.map { (it.peeCount + it.poopCount).toFloat() },
                    dayValuesTemp = temps.map { list -> list.average().takeIf { !it.isNaN() }?.toFloat() ?: 0f },
                )
                val empty = totals.feedMl == 0 &&
                    totals.sleepMin == 0L &&
                    totals.pee == 0 &&
                    totals.poop == 0 &&
                    totals.tempAvg == null
                val week = if (r == SummaryRange.Days7) {
                    careLog.weekSummary(baby.id, weekStartFor(today, 1), zone)
                } else {
                    null
                }
                emit(
                    SummaryUi(
                        range = r,
                        totals = totals,
                        week = week,
                        showAvgSleep = avg,
                        empty = empty,
                        babyName = baby.nickname,
                    ),
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SummaryUi())

    fun setRange(r: SummaryRange) {
        range.value = r
    }
}

@Composable
fun SummaryRoute(vm: SummaryViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ext = LeziThemeExt.colors
    val journal = LeziThemeExt.isJournal
    val t = ui.totals

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

            // Prototype "记录分布 / 喂养节律" combined card with 4 buckets for Today.
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
                        when (ui.range) {
                            SummaryRange.Today -> "今日"
                            SummaryRange.Days7 -> "近 7 天"
                            SummaryRange.Days30 -> "近 30 天"
                        },
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (ui.range == SummaryRange.Today) {
                    FourBucketBars(color = ext.laneFeed)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf("00–06", "06–12", "12–18", "18–24").forEach {
                            Text(it, style = LeziTypography.Meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (t.feedMl == 0 && t.feedCount == 0) "范围内暂无喂养记录。" else "见上方喂养汇总",
                        style = LeziTypography.Body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (ui.empty) {
                    Text(
                        "范围内暂无喂养记录。",
                        style = LeziTypography.Body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    MiniBarChart(values = t.dayValuesFeed, color = ext.laneFeed)
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
                    MiniBarChart(values = t.dayValuesSleep, color = ext.laneSleep)
                }
            }

            if (t.dayValuesDiaper.any { it > 0f }) {
                WeekBarChart(title = "尿布趋势", values = t.dayValuesDiaper, color = ext.laneCare)
            }
            if (t.tempAvg != null) {
                MetricRow(
                    title = "体温",
                    value = "%.1f℃".format(t.tempAvg),
                    detail = "${t.tempDays} 天有记录",
                )
                WeekLineChart(title = "体温趋势", values = t.dayValuesTemp, color = ext.danger)
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
private fun FourBucketBars(color: Color) {
    val grid = LeziThemeExt.colors.chartGrid
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(96.dp),
    ) {
        for (i in 0..3) {
            val y = size.height * i / 3f
            drawLine(grid.copy(alpha = 0.4f), Offset(0f, y), Offset(size.width, y), 1f)
        }
        val gap = 10.dp.toPx()
        val barW = (size.width - gap * 5) / 4f
        // empty placeholder bars matching prototype empty state
        for (i in 0 until 4) {
            val x = gap + i * (barW + gap)
            drawRoundRect(
                color = color.copy(alpha = 0.12f),
                topLeft = Offset(x, size.height * 0.15f),
                size = Size(barW, size.height * 0.85f),
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
private fun MiniBarChart(values: List<Float>, color: Color) {
    val grid = LeziThemeExt.colors.chartGrid
    val max = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(88.dp),
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
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
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
                if (day.pee > 0) drawCircle(ext.laneCare, 5f, Offset(cx - 5f, cellH * 2.5f))
                if (day.poop > 0) drawCircle(ext.sun, 5f, Offset(cx + 7f, cellH * 2.5f))
                if (day.temps.isNotEmpty()) drawCircle(ext.danger, 5f, Offset(cx, cellH * 3.5f))
            }
        }
    }
}

@Composable
private fun WeekBarChart(title: String, values: List<Float>, color: Color) {
    LeziCard(modifier = Modifier.fillMaxWidth()) {
        Text(title, style = LeziTypography.TitleSm)
        Spacer(Modifier.height(8.dp))
        MiniBarChart(values = values, color = color)
    }
}

@Composable
private fun WeekLineChart(title: String, values: List<Float>, color: Color) {
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
                .height(120.dp),
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
    }
}

private fun formatMin(min: Long): String {
    if (min == 0L) return "0m"
    val h = min / 60
    val m = min % 60
    return if (h == 0L) "${m}m" else if (m == 0L) "${h}h" else "${h}h${m}m"
}
