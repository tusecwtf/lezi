package com.lezi.babylog.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

private val PreviewWidth = LeziCanvas.Width
private const val PreviewMinuteMs = 60_000L
private const val PreviewHourMs = 60L * PreviewMinuteMs
private const val PreviewDayMs = 24L * PreviewHourMs

private fun previewMin(minute: Int): Long = minute * PreviewMinuteMs

@Composable
private fun PreviewFrame(visualStyle: String = "warm", content: @Composable () -> Unit) {
    LeziTheme(visualStyle = visualStyle) {
        PageScaffoldBackground {
            Column(
                Modifier
                    .width(PreviewWidth)
                    .fillMaxSize()
                    .padding(LeziSpacing.Page),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
                content = { content() },
            )
        }
    }
}

@Preview(name = "Journal · summary and rail", widthDp = 390, heightDp = 420, showBackground = true)
@Composable
fun PreviewJournalOverview() {
    PreviewFrame(visualStyle = "journal") {
        // Production journal day summary uses core.ui.RecordSummaryStrip (not a DS twin).
        TimelineRailCard(
            sleep = listOf(
                TimelineLaneSegment(
                    previewMin(30), previewMin(330), LeziRecordColorRole.Sleep,
                    title = "睡眠",
                    dayChartCategoryKey = "SLEEP",
                ),
            ),
            feed = listOf(
                TimelineLaneSegment(
                    previewMin(360), previewMin(390), LeziRecordColorRole.Milk,
                    title = "配方奶",
                    isEvent = true,
                    dayChartCategoryKey = "MILK",
                ),
            ),
            care = listOf(
                TimelineLaneSegment(
                    previewMin(430), previewMin(450), LeziRecordColorRole.Pee,
                    title = "尿尿",
                    isEvent = true,
                    dayChartCategoryKey = "PEE",
                ),
            ),
            recordCount = 8,
            nowMs = previewMin(600),
            legend = listOf(
                TimelineLegendEntry("SLEEP", "睡眠", LeziRecordColorRole.Sleep, isBar = true),
                TimelineLegendEntry("MILK", "奶", LeziRecordColorRole.Milk),
                TimelineLegendEntry("PEE", "尿", LeziRecordColorRole.Pee),
            ),
        )
    }
}

@Preview(name = "Record visuals · unified", widthDp = 390, heightDp = 180, showBackground = true)
@Composable
fun PreviewRecordVisuals() {
    PreviewFrame(visualStyle = "journal") {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            listOf(
                LeziRecordGlyph.Nursing,
                LeziRecordGlyph.Bottle,
                LeziRecordGlyph.Sleep,
                LeziRecordGlyph.Pee,
                LeziRecordGlyph.Poop,
                LeziRecordGlyph.Temperature,
            ).forEach { glyph ->
                LeziRecordGlyphIcon(glyph = glyph, size = 28.dp)
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            (1..3).forEach { LeziPeeAmountMark(it, Modifier.size(28.dp)) }
            (1..4).forEach { LeziStoolAmountMark(it, Modifier.size(28.dp)) }
        }
    }
}

@Preview(name = "SummaryMetric · normal", widthDp = 390, heightDp = 200, showBackground = true)
@Composable
fun PreviewSummaryMetrics() {
    PreviewFrame {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryMetric("180ml", "奶量", LeziTone.Blue, Modifier.weight(1f))
            SummaryMetric("12h20m", "睡眠", LeziTone.Yellow, Modifier.weight(1f))
            SummaryMetric("1次", "尿尿", LeziTone.Cream, Modifier.weight(1f))
            SummaryMetric("1次", "便便", LeziTone.Neutral, Modifier.weight(1f))
        }
    }
}

@Preview(name = "Timeline · normal", widthDp = 390, heightDp = 280, showBackground = true)
@Composable
fun PreviewTimelineNormal() {
    PreviewFrame {
        TimelineRailCard(
            sleep = listOf(
                TimelineLaneSegment(
                    previewMin(60), previewMin(180), LeziRecordColorRole.Sleep,
                    title = "睡眠",
                    dayChartCategoryKey = "SLEEP",
                ),
            ),
            feed = listOf(
                TimelineLaneSegment(
                    previewMin(200), previewMin(220), LeziRecordColorRole.Milk,
                    title = "配方奶",
                    isEvent = true,
                    dayChartCategoryKey = "MILK",
                ),
                TimelineLaneSegment(
                    previewMin(480), previewMin(500), LeziRecordColorRole.Nursing,
                    title = "母乳",
                    isEvent = true,
                    dayChartCategoryKey = "NURSING",
                ),
            ),
            care = listOf(
                TimelineLaneSegment(
                    previewMin(300), previewMin(310), LeziRecordColorRole.Pee,
                    title = "尿尿",
                    isEvent = true,
                    dayChartCategoryKey = "PEE",
                ),
            ),
            recordCount = 6,
            nowMs = previewMin(560),
            selectedCategoryKey = "MILK",
            legend = listOf(
                TimelineLegendEntry("SLEEP", "睡眠", LeziRecordColorRole.Sleep, isBar = true),
                TimelineLegendEntry("MILK", "奶", LeziRecordColorRole.Milk),
                TimelineLegendEntry("NURSING", "母乳", LeziRecordColorRole.Nursing),
                TimelineLegendEntry("PEE", "尿", LeziRecordColorRole.Pee),
            ),
        )
    }
}

@Preview(name = "Timeline · three-day viewport", widthDp = 390, heightDp = 280, showBackground = true)
@Composable
fun PreviewTimelineThreeDay() {
    val d0 = PreviewDayMs
    val nowMs = d0 + 14 * PreviewHourMs + 30 * PreviewMinuteMs
    PreviewFrame {
        TimelineRailCard(
            sleep = listOf(
                // Overnight sleep spanning D−1 → D as one continuous bar
                // (neighbor peek dims; D portion full-strength).
                TimelineLaneSegment(
                    d0 - 120 * PreviewMinuteMs, d0 + 360 * PreviewMinuteMs,
                    LeziRecordColorRole.Sleep,
                    title = "睡眠",
                    dayChartCategoryKey = "SLEEP",
                ),
            ),
            feed = listOf(
                // Neighbor D−1 feed (dimmed).
                TimelineLaneSegment(
                    d0 - 60 * PreviewMinuteMs, d0 - 60 * PreviewMinuteMs,
                    LeziRecordColorRole.Milk,
                    title = "配方奶",
                    isEvent = true,
                    dayChartCategoryKey = "MILK",
                ),
                TimelineLaneSegment(
                    d0 + 480 * PreviewMinuteMs, d0 + 480 * PreviewMinuteMs,
                    LeziRecordColorRole.Milk,
                    title = "配方奶",
                    isEvent = true,
                    dayChartCategoryKey = "MILK",
                ),
            ),
            care = listOf(
                TimelineLaneSegment(
                    d0 + 600 * PreviewMinuteMs, d0 + 600 * PreviewMinuteMs,
                    LeziRecordColorRole.Pee,
                    title = "尿尿",
                    isEvent = true,
                    dayChartCategoryKey = "PEE",
                ),
            ),
            recordCount = 4,
            nowMs = nowMs,
            viewportStartMs = nowMs - PreviewDayMs,
            viewportDurationMs = PreviewDayMs,
            dayBoundariesMs = listOf(d0, d0 + PreviewDayMs),
            dayBoundaryLabels = listOf(
                d0 to "8/8",
                d0 + PreviewDayMs to "8/9",
            ),
            primaryRangeMs = d0 until (d0 + PreviewDayMs),
            legend = listOf(
                TimelineLegendEntry("SLEEP", "睡眠", LeziRecordColorRole.Sleep, isBar = true),
                TimelineLegendEntry("MILK", "奶", LeziRecordColorRole.Milk),
                TimelineLegendEntry("PEE", "尿", LeziRecordColorRole.Pee),
            ),
        )
    }
}

@Preview(name = "Timeline · two-day crossing", widthDp = 390, heightDp = 280, showBackground = true)
@Composable
fun PreviewTimelineTwoDayCrossing() {
    PreviewTwoDayCrossingRail(visualStyle = "warm")
}

@Preview(name = "Timeline · two-day crossing · journal", widthDp = 390, heightDp = 280, showBackground = true)
@Composable
fun PreviewTimelineTwoDayCrossingJournal() {
    PreviewTwoDayCrossingRail(visualStyle = "journal")
}

@Composable
private fun PreviewTwoDayCrossingRail(visualStyle: String) {
    val d0 = PreviewDayMs
    val nowMs = d0 + 8 * PreviewHourMs
    PreviewFrame(visualStyle = visualStyle) {
        TimelineRailCard(
            sleep = listOf(
                TimelineLaneSegment(
                    d0 - 180 * PreviewMinuteMs, d0 + 120 * PreviewMinuteMs,
                    LeziRecordColorRole.Sleep,
                    title = "睡眠",
                    dayChartCategoryKey = "SLEEP",
                ),
            ),
            feed = listOf(
                TimelineLaneSegment(
                    d0 - 90 * PreviewMinuteMs, d0 - 90 * PreviewMinuteMs,
                    LeziRecordColorRole.Milk,
                    title = "配方奶",
                    isEvent = true,
                    dayChartCategoryKey = "MILK",
                ),
                TimelineLaneSegment(
                    d0 + 200 * PreviewMinuteMs, d0 + 200 * PreviewMinuteMs,
                    LeziRecordColorRole.Milk,
                    title = "配方奶",
                    isEvent = true,
                    dayChartCategoryKey = "MILK",
                ),
            ),
            care = listOf(
                TimelineLaneSegment(
                    d0 + 360 * PreviewMinuteMs, d0 + 360 * PreviewMinuteMs,
                    LeziRecordColorRole.Pee,
                    title = "尿尿",
                    isEvent = true,
                    dayChartCategoryKey = "PEE",
                ),
            ),
            recordCount = 4,
            nowMs = nowMs,
            viewportStartMs = d0 - 6 * PreviewHourMs,
            viewportDurationMs = PreviewDayMs,
            dayBoundariesMs = listOf(d0),
            dayBoundaryLabels = listOf(d0 to "8/8"),
            primaryRangeMs = d0 until (d0 + PreviewDayMs),
            legend = listOf(
                TimelineLegendEntry("SLEEP", "睡眠", LeziRecordColorRole.Sleep, isBar = true),
                TimelineLegendEntry("MILK", "奶", LeziRecordColorRole.Milk),
                TimelineLegendEntry("PEE", "尿", LeziRecordColorRole.Pee),
            ),
        )
    }
}

@Preview(name = "Timeline · empty", widthDp = 390, heightDp = 240, showBackground = true)
@Composable
fun PreviewTimelineEmpty() {
    PreviewFrame {
        TimelineRailCard(emptyList(), emptyList(), emptyList(), 0, nowMs = previewMin(600))
    }
}

@Preview(name = "RecordRow · normal", widthDp = 390, heightDp = 160, showBackground = true)
@Composable
fun PreviewRecordRow() {
    PreviewFrame {
        RecordRow(
            time = "09:26",
            title = "配方奶",
            summary = "120ml",
            relative = "2 小时前",
            tone = LeziTone.Blue,
            leading = { Text("🥛") },
            onClick = {},
        )
    }
}

@Preview(name = "RecordRow · recording", widthDp = 390, heightDp = 160, showBackground = true)
@Composable
fun PreviewRecordRowRecording() {
    PreviewFrame {
        RecordRow(
            time = "12:01",
            title = "睡眠",
            summary = "进行中",
            relative = "刚刚",
            tone = LeziTone.Yellow,
            anomaly = true,
            leading = { Text("😴") },
            onClick = {},
        )
    }
}

@Preview(name = "State · empty", widthDp = 390, heightDp = 280, showBackground = true)
@Composable
fun PreviewStateEmpty() {
    PreviewFrame {
        StateContainer(StateKind.Empty, "还没有记录", "点下方快捷入口添加第一条记录")
    }
}

@Preview(name = "State · loading", widthDp = 390, heightDp = 240, showBackground = true)
@Composable
fun PreviewStateLoading() {
    PreviewFrame {
        StateContainer(StateKind.Loading, "加载中", "正在读取今日记录…")
    }
}

@Preview(name = "State · error", widthDp = 390, heightDp = 280, showBackground = true)
@Composable
fun PreviewStateError() {
    PreviewFrame {
        StateContainer(StateKind.Error, "出错了", "无法读取本机数据", actionLabel = "重试", onAction = {})
    }
}

@Preview(name = "QuickRecord · normal", widthDp = 390, heightDp = 220, showBackground = true)
@Composable
fun PreviewQuickRecord() {
    PreviewFrame {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickRecordButton("母乳", "左右计时", LeziTone.Blue, onClick = {}, modifier = Modifier.weight(1f)) {
                Text("🍼")
            }
            QuickRecordButton("尿布", "干湿与便便", LeziTone.Yellow, onClick = {}, modifier = Modifier.weight(1f)) {
                Text("💧")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickRecordButton("睡眠", "开始与结束", LeziTone.Cream, onClick = {}, modifier = Modifier.weight(1f)) {
                Text("😴")
            }
            QuickRecordButton("奶瓶", "奶量与类型", LeziTone.Neutral, onClick = {}, modifier = Modifier.weight(1f)) {
                Text("🥛")
            }
        }
    }
}

@Preview(name = "Dark · metrics", widthDp = 390, heightDp = 200, showBackground = true)
@Composable
fun PreviewDarkMetrics() {
    LeziTheme(darkTheme = true) {
        Column(
            Modifier
                .width(PreviewWidth)
                .padding(LeziSpacing.Page),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryMetric("180ml", "奶量", LeziTone.Blue, Modifier.weight(1f))
                SummaryMetric("12h20m", "睡眠", LeziTone.Yellow, Modifier.weight(1f))
                SummaryMetric("1次", "尿尿", LeziTone.Cream, Modifier.weight(1f))
                SummaryMetric("1次", "便便", LeziTone.Neutral, Modifier.weight(1f))
            }
        }
    }
}
