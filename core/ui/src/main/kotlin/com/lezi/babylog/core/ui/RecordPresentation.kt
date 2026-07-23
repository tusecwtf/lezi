package com.lezi.babylog.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.designsystem.LeziCard
import com.lezi.babylog.designsystem.LeziRecordColorRole
import com.lezi.babylog.designsystem.LeziRecordGlyph
import com.lezi.babylog.designsystem.LeziRecordGlyphIcon
import com.lezi.babylog.designsystem.LeziTone
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.leziRecordColor
import androidx.compose.material3.Text

enum class RecordSection(val title: String) {
    Feeding("喂养"),
    Excretion("排泄"),
    Routine("日常"),
    Health("健康"),
    Food("辅食"),
    Growth("成长"),
}

enum class RecordChartMark {
    Circle,
    SleepBlock,
    Square,
    Triangle,
}

data class RecordTypePresentation(
    val label: String,
    val tip: String,
    val glyph: LeziRecordGlyph,
    val colorRole: LeziRecordColorRole,
    val section: RecordSection,
    val chartMark: RecordChartMark,
)

/**
 * The single record presentation seam. It is intentionally exhaustive so a
 * newly-added RecordType cannot silently fall back to a placeholder icon.
 */
val RecordType.presentation: RecordTypePresentation
    get() = when (this) {
        RecordType.NURSING -> RecordTypePresentation(
            "母乳", "左右计时", LeziRecordGlyph.Nursing,
            LeziRecordColorRole.Nursing, RecordSection.Feeding, RecordChartMark.Circle,
        )
        RecordType.FORMULA -> RecordTypePresentation(
            "配方奶", "奶量", LeziRecordGlyph.Bottle,
            LeziRecordColorRole.Milk, RecordSection.Feeding, RecordChartMark.Circle,
        )
        RecordType.PUMPED_FEED -> RecordTypePresentation(
            "喂挤出乳", "奶量", LeziRecordGlyph.Bottle,
            LeziRecordColorRole.Nursing, RecordSection.Feeding, RecordChartMark.Circle,
        )
        RecordType.PUMP_EXPRESS -> RecordTypePresentation(
            "挤奶", "无库存", LeziRecordGlyph.Pump,
            LeziRecordColorRole.Nursing, RecordSection.Feeding, RecordChartMark.Circle,
        )
        RecordType.PEE -> RecordTypePresentation(
            "尿尿", "小中大", LeziRecordGlyph.Pee,
            LeziRecordColorRole.Pee, RecordSection.Excretion, RecordChartMark.Square,
        )
        RecordType.POOP -> RecordTypePresentation(
            "便便", "三组分档", LeziRecordGlyph.Poop,
            LeziRecordColorRole.Poop, RecordSection.Excretion, RecordChartMark.Square,
        )
        RecordType.BOTH_DIAPER -> RecordTypePresentation(
            "尿+便", "完整分档", LeziRecordGlyph.Poop,
            LeziRecordColorRole.Poop, RecordSection.Excretion, RecordChartMark.Square,
        )
        RecordType.SLEEP -> RecordTypePresentation(
            "睡眠", "计时/手动", LeziRecordGlyph.Sleep,
            LeziRecordColorRole.Sleep, RecordSection.Routine, RecordChartMark.SleepBlock,
        )
        RecordType.TEMPERATURE -> RecordTypePresentation(
            "体温", "℃/℉", LeziRecordGlyph.Temperature,
            LeziRecordColorRole.Temperature, RecordSection.Routine, RecordChartMark.Triangle,
        )
        RecordType.MEMO -> RecordTypePresentation(
            "备注", "文字/照片", LeziRecordGlyph.Note,
            LeziRecordColorRole.Care, RecordSection.Routine, RecordChartMark.Circle,
        )
        RecordType.DIARY -> RecordTypePresentation(
            "日记", "正文/照片", LeziRecordGlyph.Note,
            LeziRecordColorRole.Care, RecordSection.Routine, RecordChartMark.Circle,
        )
        RecordType.BATH -> RecordTypePresentation(
            "洗澡", "一键记录", LeziRecordGlyph.Bath,
            LeziRecordColorRole.Wake, RecordSection.Routine, RecordChartMark.Circle,
        )
        RecordType.WALK -> RecordTypePresentation(
            "散步", "起止时间", LeziRecordGlyph.Walk,
            LeziRecordColorRole.Growth, RecordSection.Routine, RecordChartMark.Circle,
        )
        RecordType.COUGH -> RecordTypePresentation(
            "咳嗽", "程度/备注", LeziRecordGlyph.Health,
            LeziRecordColorRole.Temperature, RecordSection.Health, RecordChartMark.Circle,
        )
        RecordType.RASH -> RecordTypePresentation(
            "发疹", "程度/备注", LeziRecordGlyph.Health,
            LeziRecordColorRole.Temperature, RecordSection.Health, RecordChartMark.Circle,
        )
        RecordType.VOMIT -> RecordTypePresentation(
            "呕吐", "程度/备注", LeziRecordGlyph.Health,
            LeziRecordColorRole.Temperature, RecordSection.Health, RecordChartMark.Circle,
        )
        RecordType.INJURY -> RecordTypePresentation(
            "受伤", "程度/备注", LeziRecordGlyph.Health,
            LeziRecordColorRole.Temperature, RecordSection.Health, RecordChartMark.Circle,
        )
        RecordType.MEDICINE -> RecordTypePresentation(
            "用药", "名称/剂量", LeziRecordGlyph.Medicine,
            LeziRecordColorRole.Care, RecordSection.Health, RecordChartMark.Circle,
        )
        RecordType.HOSPITAL -> RecordTypePresentation(
            "就医", "原因/医嘱", LeziRecordGlyph.Hospital,
            LeziRecordColorRole.Wake, RecordSection.Health, RecordChartMark.Circle,
        )
        RecordType.OTHER -> RecordTypePresentation(
            "其他", "自由文本", LeziRecordGlyph.Other,
            LeziRecordColorRole.Care, RecordSection.Health, RecordChartMark.Circle,
        )
        RecordType.HEIGHT -> RecordTypePresentation(
            "身高", "成长测量", LeziRecordGlyph.Growth,
            LeziRecordColorRole.Growth, RecordSection.Growth, RecordChartMark.Circle,
        )
        RecordType.WEIGHT -> RecordTypePresentation(
            "体重", "成长测量", LeziRecordGlyph.Growth,
            LeziRecordColorRole.Growth, RecordSection.Growth, RecordChartMark.Circle,
        )
        RecordType.BABY_FOOD -> RecordTypePresentation(
            "辅食", "内容/备注", LeziRecordGlyph.Food,
            LeziRecordColorRole.Milk, RecordSection.Food, RecordChartMark.Circle,
        )
        RecordType.SNACK -> RecordTypePresentation(
            "点心", "内容/备注", LeziRecordGlyph.Food,
            LeziRecordColorRole.Milk, RecordSection.Food, RecordChartMark.Circle,
        )
        RecordType.DRINK -> RecordTypePresentation(
            "饮料", "内容/量", LeziRecordGlyph.Bottle,
            LeziRecordColorRole.Milk, RecordSection.Food, RecordChartMark.Circle,
        )
        RecordType.HEAD -> RecordTypePresentation(
            "头围", "成长测量", LeziRecordGlyph.Growth,
            LeziRecordColorRole.Growth, RecordSection.Growth, RecordChartMark.Circle,
        )
        RecordType.CHEST -> RecordTypePresentation(
            "胸围", "成长测量", LeziRecordGlyph.Growth,
            LeziRecordColorRole.Growth, RecordSection.Growth, RecordChartMark.Circle,
        )
        RecordType.FOOT_SIZE -> RecordTypePresentation(
            "足长", "成长测量", LeziRecordGlyph.Growth,
            LeziRecordColorRole.Growth, RecordSection.Growth, RecordChartMark.Circle,
        )
        RecordType.VACCINE -> RecordTypePresentation(
            "疫苗", "手记", LeziRecordGlyph.Vaccine,
            LeziRecordColorRole.Wake, RecordSection.Health, RecordChartMark.Circle,
        )
        RecordType.CUSTOM -> RecordTypePresentation(
            "自定义", "最多10项", LeziRecordGlyph.Other,
            LeziRecordColorRole.Care, RecordSection.Health, RecordChartMark.Circle,
        )
    }

fun RecordType.presentationTone(): LeziTone = when (presentation.colorRole) {
    LeziRecordColorRole.Nursing, LeziRecordColorRole.Milk -> LeziTone.Blue
    LeziRecordColorRole.Sleep, LeziRecordColorRole.Wake -> LeziTone.Yellow
    LeziRecordColorRole.Pee, LeziRecordColorRole.Poop -> LeziTone.Cream
    LeziRecordColorRole.Temperature, LeziRecordColorRole.Care, LeziRecordColorRole.Growth ->
        LeziTone.Neutral
}

@Composable
fun RecordTypeIcon(
    type: RecordType,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    tint: Color = leziRecordColor(type.presentation.colorRole),
) {
    LeziRecordGlyphIcon(
        glyph = type.presentation.glyph,
        modifier = modifier,
        tint = tint,
        size = size,
    )
}

data class RecordSummaryValue(
    val type: RecordType,
    val value: String,
    val label: String,
)

/** Five-column summary strip with the same semantic icons used by record rows. */
@Composable
fun RecordSummaryStrip(
    values: List<RecordSummaryValue>,
    modifier: Modifier = Modifier,
) {
    LeziCard(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Row(Modifier.fillMaxWidth()) {
            val visibleValues = values.take(5)
            visibleValues.forEachIndexed { index, item ->
                val color = leziRecordColor(item.type.presentation.colorRole)
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 62.dp)
                        .padding(horizontal = 3.dp, vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(color.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        RecordTypeIcon(item.type, size = 15.dp, tint = color)
                    }
                    Text(item.value, style = LeziTypography.Mono, maxLines = 1)
                    Text(
                        item.label,
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (index < visibleValues.lastIndex) {
                    Spacer(
                        Modifier
                            .width(1.dp)
                            .height(62.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
                    )
                }
            }
        }
    }
}

fun peeAmountLabel(level: Int): String = when (level.coerceIn(1, 3)) {
    1 -> "小"
    2 -> "中"
    else -> "大"
}

fun stoolAmountLabel(level: Int): String = when (level.coerceIn(1, 4)) {
    1 -> "一点"
    2 -> "偏少"
    3 -> "正常"
    else -> "偏多"
}

fun stoolConsistencyLabel(level: Int): String = when (level.coerceIn(1, 4)) {
    1 -> "稀"
    2 -> "偏软"
    3 -> "正常"
    else -> "偏硬"
}

fun stoolColorLabel(index: Int): String = when (index.coerceIn(0, 7)) {
    0 -> "未选"
    1 -> "白"
    2 -> "黄"
    3 -> "橙"
    4 -> "褐"
    5 -> "绿"
    6 -> "红"
    else -> "黑"
}

/** Readable record copy shared by the timeline and search results; raw JSON never escapes this seam. */
fun Record.presentationSummary(): String {
    val payloadCopy = when (type) {
        RecordType.FORMULA, RecordType.PUMPED_FEED, RecordType.PUMP_EXPRESS -> {
            payloadInt("amount_ml").takeIf { it > 0 }?.let { "${it}ml" }.orEmpty()
        }
        RecordType.NURSING -> {
            val left = payloadInt("left_min")
            val right = payloadInt("right_min")
            "左${left}分 · 右${right}分"
        }
        RecordType.PEE -> "尿量${peeAmountLabel(payloadInt("pee_amount").takeIf { it in 1..3 } ?: 2)}"
        RecordType.POOP -> stoolSummary()
        RecordType.BOTH_DIAPER -> {
            val pee = peeAmountLabel(payloadInt("pee_amount").takeIf { it in 1..3 } ?: 2)
            "尿量$pee · ${stoolSummary()}"
        }
        RecordType.SLEEP -> endTimestamp?.takeIf { it >= timestamp }?.let {
            listOf(
                if (payloadBoolean("is_nap")) "午睡" else null,
                "时长 ${formatDuration((it - timestamp) / 60_000L)}",
            ).filterNotNull().joinToString(" · ")
        } ?: if (payloadBoolean("is_nap")) "午睡 · 进行中" else "进行中"
        RecordType.TEMPERATURE ->
            (payloadNumber("celsius") ?: payloadNumber("value"))?.let { "${it}℃" }.orEmpty()
        RecordType.MEDICINE -> listOf(
            payloadString("name"),
            payloadString("dose"),
        ).filter { it.isNotBlank() }.joinToString(" · ")
        RecordType.COUGH, RecordType.RASH, RecordType.VOMIT, RecordType.INJURY -> listOf(
            when (payloadInt("severity")) {
                1 -> "轻微"
                2 -> "一般"
                3 -> "明显"
                else -> ""
            },
            payloadString("description"),
        ).filter { it.isNotBlank() }.joinToString(" · ")
        RecordType.HOSPITAL -> listOf(
            payloadString("reason"),
            payloadString("advice"),
        ).filter { it.isNotBlank() }.joinToString(" · ")
        RecordType.OTHER, RecordType.CUSTOM -> listOf(
            payloadString("title"),
            payloadString("detail"),
        ).filter { it.isNotBlank() }.joinToString(" · ")
        RecordType.HEIGHT, RecordType.HEAD, RecordType.CHEST, RecordType.FOOT_SIZE ->
            payloadNumber("value")?.let { "${it}cm" }.orEmpty()
        RecordType.WEIGHT -> payloadNumber("value")?.toDoubleOrNull()?.let { raw ->
            val kilograms = if (payloadString("unit") == "g") raw / 1_000.0 else raw
            val formatted = if (kilograms % 1.0 == 0.0) {
                kilograms.toInt().toString()
            } else {
                "%.2f".format(java.util.Locale.US, kilograms).trimEnd('0').trimEnd('.')
            }
            "${formatted}kg"
        }.orEmpty()
        RecordType.BABY_FOOD, RecordType.SNACK, RecordType.DRINK ->
            listOf(payloadString("content"), payloadString("amount"))
                .filter { it.isNotBlank() }
                .joinToString(" · ")
        RecordType.VACCINE -> listOf(
            payloadString("name"),
            payloadString("batch"),
        ).filter { it.isNotBlank() }.joinToString(" · ")
        RecordType.MEMO, RecordType.DIARY -> payloadString("body")
        else -> ""
    }
    return listOfNotNull(
        payloadCopy.takeIf { it.isNotBlank() },
        note?.trim()?.takeIf { it.isNotBlank() },
    ).joinToString(" · ").ifBlank { type.presentation.tip }
}

private fun Record.stoolSummary(): String {
    val amount = stoolAmountLabel(payloadInt("stool_amount").takeIf { it in 1..4 } ?: 3)
    val consistency = stoolConsistencyLabel(payloadInt("stool_consistency").takeIf { it in 1..4 } ?: 3)
    val color = stoolColorLabel(payloadInt("stool_color").coerceIn(0, 7))
    return "便量$amount · $consistency · $color"
}

private fun Record.payloadInt(key: String): Int =
    Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+)")
        .find(payloadJson)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?: 0

private fun Record.payloadNumber(key: String): String? =
    Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
        .find(payloadJson)
        ?.groupValues
        ?.getOrNull(1)

private fun Record.payloadString(key: String): String =
    Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"]*)\"")
        .find(payloadJson)
        ?.groupValues
        ?.getOrNull(1)
        .orEmpty()

private fun Record.payloadBoolean(key: String): Boolean =
    Regex("\"${Regex.escape(key)}\"\\s*:\\s*(true|false)")
        .find(payloadJson)
        ?.groupValues
        ?.getOrNull(1)
        ?.toBooleanStrictOrNull()
        ?: false

private fun formatDuration(minutes: Long): String {
    if (minutes <= 0) return "不足1分"
    val hours = minutes / 60
    val remaining = minutes % 60
    return when {
        hours == 0L -> "${remaining}分"
        remaining == 0L -> "${hours}小时"
        else -> "${hours}小时${remaining}分"
    }
}
