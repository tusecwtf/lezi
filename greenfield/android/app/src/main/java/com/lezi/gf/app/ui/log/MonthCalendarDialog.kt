package com.lezi.gf.app.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lezi.gf.app.ui.model.MonthCalendarModel
import java.time.YearMonth

@Composable
fun MonthCalendarDialog(
    selectedDayStartMs: Long,
    weekStartsOnMonday: Boolean,
    onDismiss: () -> Unit,
    onSelectDayStartMs: (Long) -> Unit,
) {
    val selected = remember(selectedDayStartMs) {
        MonthCalendarModel.dayStartMsToLocalDate(selectedDayStartMs)
    }
    var ym by remember(selected) { mutableStateOf(YearMonth.from(selected)) }
    val grid = remember(ym, selected, weekStartsOnMonday) {
        MonthCalendarModel.buildGrid(ym, selected, weekStartsOnMonday = weekStartsOnMonday)
    }
    val headers = if (weekStartsOnMonday) {
        listOf("一", "二", "三", "四", "五", "六", "日")
    } else {
        listOf("日", "一", "二", "三", "四", "五", "六")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择日期") },
        text = {
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { ym = ym.minusMonths(1) }) { Text("‹") }
                    Text("${ym.year}年${ym.monthValue}月", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { ym = ym.plusMonths(1) }) { Text("›") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    headers.forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
                }
                grid.cells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        week.forEach { cell ->
                            val d = cell.date
                            Box(
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .padding(2.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            cell.isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                            cell.isToday -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                                            else -> MaterialTheme.colorScheme.surface
                                        },
                                    )
                                    .clickable(enabled = d != null) {
                                        if (d != null) {
                                            onSelectDayStartMs(MonthCalendarModel.localDateToDayStartMs(d))
                                            onDismiss()
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    cell.dayOfMonth?.toString() ?: "",
                                    color = if (cell.isCurrentMonth) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                                    },
                                    fontWeight = if (cell.isSelected || cell.isToday) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
