package com.lezi.babylog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lezi.babylog.designsystem.LeziColors
import com.lezi.babylog.designsystem.LeziShapes
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.designsystem.LeziTypography
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

@Composable
internal fun AppHeaderBar(
    babyName: String,
    babyAge: String,
    selectedDate: LocalDate,
    today: LocalDate,
    canCycleBaby: Boolean,
    canGoNext: Boolean,
    dark: Boolean,
    onCycleBaby: () -> Unit,
    onPreviousDate: () -> Unit,
    onNextDate: () -> Unit,
    onOpenDatePicker: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (dark) LeziColors.JournalDarkAccent else LeziColors.JournalAccent
    val content = Color(0xFF271015)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp)
            .background(background)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(0.9f)
                .height(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = canCycleBaby, onClick = onCycleBaby)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(content.copy(alpha = 0.18f))
                    .border(1.dp, content.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    babyName.take(1).ifBlank { "乐" },
                    color = content,
                    style = LeziTypography.BodyStrong,
                )
            }
            Spacer(Modifier.size(7.dp))
            Column {
                Text(
                    babyName.ifBlank { "乐记" },
                    color = content,
                    style = LeziTypography.Label,
                    maxLines = 1,
                )
                Text(
                    babyAge.ifBlank { "本地记录" },
                    color = content.copy(alpha = 0.82f),
                    style = LeziTypography.Meta,
                    maxLines = 1,
                )
            }
        }

        Row(
            modifier = Modifier.weight(1.55f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = onPreviousDate, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "前一天", tint = content)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .semantics(mergeDescendants = true) {
                        contentDescription =
                            "选择日期，${headerPrimaryDateLabel(selectedDate, today)}，" +
                                headerSecondaryDateLabel(selectedDate)
                    }
                    .clickable(role = Role.Button, onClick = onOpenDatePicker)
                    .padding(horizontal = 2.dp, vertical = 3.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    headerPrimaryDateLabel(selectedDate, today),
                    color = content,
                    style = LeziTypography.Label.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                Text(
                    headerSecondaryDateLabel(selectedDate),
                    color = content.copy(alpha = 0.84f),
                    style = LeziTypography.Meta,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
            IconButton(
                onClick = onNextDate,
                enabled = canGoNext,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = "后一天",
                    tint = content.copy(alpha = if (canGoNext) 1f else 0.32f),
                )
            }
        }

        Box(
            modifier = Modifier.weight(0.42f),
            contentAlignment = Alignment.CenterEnd,
        ) {
            IconButton(onClick = onSearch, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Search, contentDescription = "搜索", tint = content)
            }
        }
    }
}

@Composable
internal fun HeaderCalendarDialog(
    selectedDate: LocalDate,
    displayedMonth: YearMonth,
    today: LocalDate,
    recordDays: Set<LocalDate>,
    onMonthChange: (YearMonth) -> Unit,
    onSelect: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val monthCells = calendarMonthCells(displayedMonth)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LeziSpacing.Md),
            shape = LeziShapes.Md,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            tonalElevation = 2.dp,
            shadowElevation = 12.dp,
        ) {
            Column(
                Modifier.padding(
                    horizontal = LeziSpacing.Xs,
                    vertical = LeziSpacing.Md,
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(
                        onClick = { onMonthChange(displayedMonth.minusMonths(1)) },
                    ) {
                        Icon(Icons.Filled.ChevronLeft, contentDescription = "上个月")
                    }
                    Text(
                        "${displayedMonth.year}年${displayedMonth.monthValue}月",
                        style = LeziTypography.TitleSm,
                    )
                    IconButton(
                        onClick = { onMonthChange(displayedMonth.plusMonths(1)) },
                        enabled = displayedMonth < YearMonth.from(today),
                    ) {
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = "下个月",
                            tint = MaterialTheme.colorScheme.onSurface.copy(
                                alpha = if (displayedMonth < YearMonth.from(today)) 1f else 0.3f,
                            ),
                        )
                    }
                }

                Row(Modifier.fillMaxWidth()) {
                    calendarWeekLabels().forEach { label ->
                        Text(
                            label,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = LeziTypography.Meta,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                monthCells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { date ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (date != null) {
                                    CalendarDay(
                                        date = date,
                                        selected = date == selectedDate,
                                        today = date == today,
                                        hasRecord = date in recordDays,
                                        enabled = !date.isAfter(today),
                                        onClick = { onSelect(date) },
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { onSelect(today) },
                        enabled = selectedDate != today,
                    ) {
                        Text("回到今天")
                    }
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDay(
    date: LocalDate,
    selected: Boolean,
    today: Boolean,
    hasRecord: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val selectedColor = MaterialTheme.colorScheme.primary
    val selectedContentColor = calendarContentColor(selectedColor)
    val contentColor = when {
        selected -> selectedContentColor
        enabled -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .then(
                if (today && !selected) {
                    Modifier.border(1.5.dp, selectedColor, CircleShape)
                } else {
                    Modifier
                },
            )
            .background(if (selected) selectedColor else Color.Transparent)
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "${date.year}年${date.monthValue}月${date.dayOfMonth}日，" +
                        date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINA)
                this.selected = selected
                stateDescription = buildList {
                    if (today) add("今天")
                    if (selected) add("已选中")
                    if (hasRecord) add("有记录")
                }.ifEmpty { listOf("无记录") }.joinToString("，")
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "${date.dayOfMonth}",
            color = contentColor,
            style = LeziTypography.Label,
            textAlign = TextAlign.Center,
        )
        if (hasRecord) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 3.dp)
                    .size(3.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) selectedContentColor else selectedColor,
                    ),
            )
        }
    }
}

internal fun headerPrimaryDateLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "今天"
    today.minusDays(1) -> "昨天"
    else -> "${date.monthValue}月${date.dayOfMonth}日"
}

internal fun headerSecondaryDateLabel(date: LocalDate): String {
    val weekday = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA)
    return "${date.monthValue}月${date.dayOfMonth}日 · $weekday"
}

internal fun calendarWeekLabels(): List<String> =
    listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        .map { it.getDisplayName(TextStyle.NARROW, Locale.CHINA) }

internal fun calendarMonthCells(month: YearMonth): List<LocalDate?> {
    val leading = month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value
    val cells = MutableList<LocalDate?>(leading) { null }
    repeat(month.lengthOfMonth()) { index ->
        cells += month.atDay(index + 1)
    }
    while (cells.size % 7 != 0 || cells.size < 42) {
        cells += null
    }
    return cells
}

internal fun calendarContentColor(background: Color): Color =
    if (background.luminance() > 0.179f) Color.Black else Color.White

@Preview(name = "Global date header", widthDp = 390, heightDp = 92, showBackground = true)
@Composable
private fun AppHeaderPreview() {
    LeziTheme(visualStyle = "journal") {
        AppHeaderBar(
            babyName = "年年",
            babyAge = "生后 5 个月",
            selectedDate = LocalDate.of(2026, 7, 23),
            today = LocalDate.of(2026, 7, 23),
            canCycleBaby = true,
            canGoNext = false,
            dark = false,
            onCycleBaby = {},
            onPreviousDate = {},
            onNextDate = {},
            onOpenDatePicker = {},
            onSearch = {},
        )
    }
}
