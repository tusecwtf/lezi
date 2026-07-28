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
        JournalSummaryStrip(
            listOf(
                JournalSummaryValue("180", "奶ml", LeziTone.Blue),
                JournalSummaryValue("20", "母乳min", LeziTone.Blue),
                JournalSummaryValue("8h", "睡眠", LeziTone.Cream),
                JournalSummaryValue("5", "尿", LeziTone.Yellow),
                JournalSummaryValue("2", "便", LeziTone.Neutral),
            ),
        )
        TimelineRailCard(
            sleep = listOf(
                TimelineLaneSegment(
                    30, 330, LeziColors.JournalSleep,
                    title = "睡眠",
                    dayChartCategoryKey = "SLEEP",
                ),
            ),
            feed = listOf(
                TimelineLaneSegment(
                    360, 390, LeziColors.JournalFeed,
                    title = "配方奶",
                    isEvent = true,
                    dayChartCategoryKey = "MILK",
                ),
            ),
            care = listOf(
                TimelineLaneSegment(
                    430, 450, LeziColors.JournalCare,
                    title = "尿尿",
                    isEvent = true,
                    dayChartCategoryKey = "PEE",
                ),
            ),
            recordCount = 8,
            nowMinOfDay = 600,
            legend = listOf(
                TimelineLegendEntry("SLEEP", "睡眠", LeziColors.JournalSleep, isBar = true),
                TimelineLegendEntry("MILK", "奶", LeziColors.JournalFeed),
                TimelineLegendEntry("PEE", "尿", LeziColors.JournalCare),
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
            SummaryMetric("20min", "母乳", LeziTone.Blue, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryMetric("2h10m", "睡眠", LeziTone.Yellow, Modifier.weight(1f))
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
                    60, 180, LeziColors.LaneSleep,
                    title = "睡眠",
                    dayChartCategoryKey = "SLEEP",
                ),
            ),
            feed = listOf(
                TimelineLaneSegment(
                    200, 220, LeziColors.LaneFeed,
                    title = "配方奶",
                    isEvent = true,
                    dayChartCategoryKey = "MILK",
                ),
                TimelineLaneSegment(
                    480, 500, LeziColors.LaneFeed,
                    title = "母乳",
                    isEvent = true,
                    dayChartCategoryKey = "NURSING",
                ),
            ),
            care = listOf(
                TimelineLaneSegment(
                    300, 310, LeziColors.LaneCare,
                    title = "尿尿",
                    isEvent = true,
                    dayChartCategoryKey = "PEE",
                ),
            ),
            recordCount = 6,
            nowMinOfDay = 560,
            selectedCategoryKey = "MILK",
            legend = listOf(
                TimelineLegendEntry("SLEEP", "睡眠", LeziColors.LaneSleep, isBar = true),
                TimelineLegendEntry("MILK", "奶", LeziColors.LaneFeed),
                TimelineLegendEntry("NURSING", "母乳", LeziColors.LaneFeed),
                TimelineLegendEntry("PEE", "尿", LeziColors.LaneCare),
            ),
        )
    }
}

@Preview(name = "Timeline · empty", widthDp = 390, heightDp = 240, showBackground = true)
@Composable
fun PreviewTimelineEmpty() {
    PreviewFrame {
        TimelineRailCard(emptyList(), emptyList(), emptyList(), 0, nowMinOfDay = 600)
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
                SummaryMetric("20min", "母乳", LeziTone.Blue, Modifier.weight(1f))
            }
        }
    }
}
