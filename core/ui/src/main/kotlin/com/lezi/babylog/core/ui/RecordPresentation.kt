package com.lezi.babylog.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.payloadSummary
import com.lezi.babylog.designsystem.LeziCard
import com.lezi.babylog.designsystem.LeziRecordColorRole
import com.lezi.babylog.designsystem.LeziRecordGlyph
import com.lezi.babylog.designsystem.LeziRecordGlyphIcon
import com.lezi.babylog.designsystem.LeziThemeExt
import com.lezi.babylog.designsystem.LeziTone
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.leziRecordColor
import androidx.compose.material3.Text

enum class RecordSection(val title: String) {
    Feeding("喂养"),
    Excretion("排泄"),
    Routine("日常"),
    Health("健康"),
    Growth("成长"),
    /** Concrete custom definitions (not the retired bare CUSTOM type). */
    Custom("自定义"),
    ;

    companion object
}

/** Shared glyph palette for custom item icons (settings + layout manage + dock). */
val CUSTOM_ITEM_ICON_GLYPHS: List<String> =
    listOf("★", "♥", "☀", "☾", "♪", "●", "▲", "◆")

fun customItemIconGlyph(slot: Int): String =
    CUSTOM_ITEM_ICON_GLYPHS[slot.coerceIn(0, CUSTOM_ITEM_ICON_GLYPHS.lastIndex)]

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
            "母乳瓶喂", "奶量", LeziRecordGlyph.Bottle,
            LeziRecordColorRole.Nursing, RecordSection.Feeding, RecordChartMark.Circle,
        )
        RecordType.PUMP_EXPRESS -> RecordTypePresentation(
            "挤奶", "奶量", LeziRecordGlyph.Pump,
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
        RecordType.DIARY -> RecordTypePresentation(
            "日记", "正文/照片", LeziRecordGlyph.Note,
            LeziRecordColorRole.Care, RecordSection.Routine, RecordChartMark.Circle,
        )
        RecordType.BATH -> RecordTypePresentation(
            "洗澡", "一键记录", LeziRecordGlyph.Bath,
            LeziRecordColorRole.Wake, RecordSection.Routine, RecordChartMark.Circle,
        )
        RecordType.WALK -> RecordTypePresentation(
            "散步", "时刻/备注", LeziRecordGlyph.Walk,
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
            LeziRecordColorRole.Milk, RecordSection.Feeding, RecordChartMark.Circle,
        )
        RecordType.SNACK -> RecordTypePresentation(
            "点心", "内容/备注", LeziRecordGlyph.Food,
            LeziRecordColorRole.Milk, RecordSection.Feeding, RecordChartMark.Circle,
        )
        RecordType.DRINK -> RecordTypePresentation(
            "饮料", "内容/量", LeziRecordGlyph.Bottle,
            LeziRecordColorRole.Milk, RecordSection.Feeding, RecordChartMark.Circle,
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
            LeziRecordColorRole.Care, RecordSection.Custom, RecordChartMark.Circle,
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
    selectedType: RecordType? = null,
    selectableTypes: Set<RecordType> = values.mapTo(mutableSetOf()) { it.type },
    onSelect: ((RecordType) -> Unit)? = null,
) {
    LeziCard(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Row(Modifier.fillMaxWidth()) {
            val visibleValues = values.take(5)
            visibleValues.forEachIndexed { index, item ->
                val color = leziRecordColor(item.type.presentation.colorRole)
                val selected = item.type == selectedType
                val selectable = onSelect != null && item.type in selectableTypes
                val cellShape = LeziThemeExt.controlShape
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 62.dp)
                        .clip(cellShape)
                        .background(
                            if (selected) color.copy(alpha = 0.18f) else Color.Transparent,
                        )
                        .then(
                            if (selected) {
                                Modifier.border(1.dp, color.copy(alpha = 0.8f), cellShape)
                            } else {
                                Modifier
                            },
                        )
                        .then(
                            if (selectable) {
                                Modifier
                                    .clickable { onSelect?.invoke(item.type) }
                                    .semantics {
                                        role = Role.Button
                                        this.selected = selected
                                        contentDescription =
                                            "${item.label} ${item.value}，${if (selected) "已筛选" else "点按筛选"}"
                                    }
                            } else {
                                Modifier
                            },
                        )
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

/** Re-export of model labels so UI modules keep a single presentation import path. */
fun peeAmountLabel(level: Int): String =
    com.lezi.babylog.core.model.peeAmountLabel(level)

fun stoolAmountLabel(level: Int): String =
    com.lezi.babylog.core.model.stoolAmountLabel(level)

fun stoolConsistencyLabel(level: Int): String =
    com.lezi.babylog.core.model.stoolConsistencyLabel(level)

fun stoolColorLabel(index: Int): String =
    com.lezi.babylog.core.model.stoolColorLabel(index)

/** Readable record copy shared by the timeline and search results; raw JSON never escapes this seam. */
fun Record.presentationSummary(): String {
    val payloadCopy = payloadSummary()
    return listOfNotNull(
        payloadCopy.takeIf { it.isNotBlank() },
        note?.trim()?.takeIf { it.isNotBlank() },
    ).joinToString(" · ").ifBlank { type.presentation.tip }
}

/** Re-export model duration formatter so UI callers keep a stable import path. */
fun formatRecordDuration(minutes: Long): String =
    com.lezi.babylog.core.model.formatRecordDuration(minutes)
