package com.lezi.gf.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lezi.gf.app.ui.model.DayAxisModel
import com.lezi.gf.app.ui.model.DockModel
import com.lezi.gf.app.ui.theme.LeziDensity
import com.lezi.gf.app.ui.theme.LeziSpacing
import com.lezi.gf.app.ui.theme.LeziTypeGlyph
import com.lezi.gf.care.CareRecord
import com.lezi.gf.care.DaySummary
import com.lezi.gf.care.RecordType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// TimelineRecordRow delegates to SwipeTimelineRow (same package).

@Composable
fun DaySummaryChips(
    summary: DaySummary,
    selectedTypeKey: String?,
    onSelect: (String?) -> Unit,
    density: LeziDensity,
    modifier: Modifier = Modifier,
) {
    val chips = buildList {
        if (summary.milkMl > 0) add(RecordType.FORMULA.key to "奶 ${summary.milkMl}ml")
        if (summary.nursingCount > 0) add(RecordType.NURSING.key to "喂奶 ${summary.nursingCount}")
        if (summary.sleepMinutes > 0) add(RecordType.SLEEP.key to "睡 ${summary.sleepMinutes}分")
        if (summary.peeCount > 0) add(RecordType.PEE.key to "尿 ${summary.peeCount}")
        if (summary.poopCount > 0) add(RecordType.POOP.key to "便 ${summary.poopCount}")
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = density.panelContent, vertical = LeziSpacing.Xs),
        horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
    ) {
        chips.forEach { (key, label) ->
            val selected = selectedTypeKey == key
            Surface(
                shape = RoundedCornerShape(if (density.useCards) 8.dp else 4.dp),
                color = if (selected) {
                    LeziTypeGlyph.accent(key).copy(alpha = 0.25f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                },
                modifier = Modifier
                    .height(LeziSpacing.Touch)
                    .weight(1f, fill = false)
                    .clickable {
                        onSelect(if (selectedTypeKey == key) null else key)
                    }
                    .semantics { contentDescription = "日汇总筛选 $label" },
            ) {
                Box(
                    Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
        if (chips.isEmpty()) {
            Text("今日暂无汇总", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun ThreeDayAxis(
    window: DayAxisModel.Window,
    nowMs: Long,
    marks: List<Pair<Long, String>>, // timestamp to typeKey
    onPan: (deltaMs: Long) -> Unit,
    density: LeziDensity,
    modifier: Modifier = Modifier,
) {
    var dragAcc by remember { mutableFloatStateOf(0f) }
    val height = if (density.useCards) 88.dp else 72.dp
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = density.topBarHorizontal)
            .semantics { contentDescription = "三日时间条，可横向拖动" },
        shape = RoundedCornerShape(if (density.useCards) density.cardCorner else 0.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (density.useCards) 0.55f else 0.35f),
        tonalElevation = if (density.useCards) 1.dp else 0.dp,
    ) {
        Box(Modifier.fillMaxSize().padding(8.dp)) {
            androidx.compose.foundation.Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(window.viewportStartMs, window.selectedDayStartMs) {
                        detectHorizontalDragGestures(
                            onDragEnd = { dragAcc = 0f },
                            onHorizontalDrag = { _, dragAmount ->
                                dragAcc += dragAmount
                                val hoursSpan = window.viewportDurationMs / 3_600_000f
                                val pxPerHour = size.width / hoursSpan.coerceAtLeast(1f)
                                if (kotlin.math.abs(dragAcc) > 4f) {
                                    val hours = -dragAcc / pxPerHour
                                    onPan((hours * 3_600_000f).toLong())
                                    dragAcc = 0f
                                }
                            },
                        )
                    },
            ) {
                DayAxisModel.midnightOffsetsInViewport(window).forEach { frac ->
                    if (frac in 0f..1f) {
                        val x = frac * size.width
                        drawLine(
                            color = outline.copy(alpha = 0.55f),
                            start = androidx.compose.ui.geometry.Offset(x, 0f),
                            end = androidx.compose.ui.geometry.Offset(x, size.height),
                            strokeWidth = 2f,
                        )
                    }
                }
                marks.forEach { (ts, typeKey) ->
                    val frac = DayAxisModel.fractionInViewport(ts, window)
                    if (frac in -0.05f..1.05f) {
                        val x = frac.coerceIn(0f, 1f) * size.width
                        drawCircle(
                            color = LeziTypeGlyph.accent(typeKey),
                            radius = 8f,
                            center = androidx.compose.ui.geometry.Offset(x, size.height * 0.4f),
                        )
                    }
                }
                if (DayAxisModel.isNowVisible(nowMs, window)) {
                    val x = DayAxisModel.fractionInViewport(nowMs, window).coerceIn(0f, 1f) * size.width
                    drawLine(
                        color = primary,
                        start = androidx.compose.ui.geometry.Offset(x, 0f),
                        end = androidx.compose.ui.geometry.Offset(x, size.height),
                        strokeWidth = 4f,
                    )
                }
            }
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("D−1", style = MaterialTheme.typography.labelSmall, color = onSurface.copy(alpha = 0.45f))
                Text("选中日", style = MaterialTheme.typography.labelSmall)
                Text("D+1", style = MaterialTheme.typography.labelSmall, color = onSurface.copy(alpha = 0.45f))
            }
        }
    }
}

@Composable
fun QuickDock(
    slots: List<DockModel.Slot>,
    onSlotClick: (DockModel.Slot) -> Unit,
    onMore: () -> Unit,
    onLongPressLayout: () -> Unit,
    density: LeziDensity,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = if (density.useCards) 3.dp else 1.dp,
        shadowElevation = if (density.useCards) 4.dp else 0.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = density.dockOuterHorizontal,
                    vertical = LeziSpacing.Sm,
                ),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            slots.forEach { slot ->
                DockSlotButton(
                    slot = slot,
                    onClick = { if (!slot.isEmpty) onSlotClick(slot) },
                    onLongClick = onLongPressLayout,
                )
            }
            DockSlotButton(
                slot = DockModel.Slot(
                    index = 4,
                    bindingKey = "__more__",
                    label = "更多",
                    typeKey = null,
                    isEmpty = false,
                    isCustom = false,
                ),
                glyphOverride = "多",
                onClick = onMore,
                onLongClick = onLongPressLayout,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DockSlotButton(
    slot: DockModel.Slot,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    glyphOverride: String? = null,
) {
    val accent = if (slot.isEmpty) Color(0xFFBDBDBD) else LeziTypeGlyph.accent(slot.typeKey ?: slot.bindingKey)
    val glyph = glyphOverride
        ?: if (slot.isEmpty) "·" else LeziTypeGlyph.glyph(slot.bindingKey ?: slot.typeKey)
    val interaction = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .size(width = 64.dp, height = LeziSpacing.DockTouch + 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics {
                contentDescription = if (slot.isEmpty) "空槽，长按编辑布局" else "${slot.label}，长按编辑布局"
            }
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = if (slot.isEmpty) 0.15f else 0.2f))
                .border(1.dp, accent.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = accent)
        }
        Text(
            slot.label.take(4),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Legacy non-swipe timeline row (a11y / secondary surfaces).
 * Primary log path uses [com.lezi.gf.app.ui.components.SwipeTimelineRow].
 */
@Composable
fun TimelineRecordRow(
    record: CareRecord,
    author: String?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenPhotos: (Int) -> Unit,
    density: LeziDensity,
    modifier: Modifier = Modifier,
    nowMs: Long = System.currentTimeMillis(),
) {
    SwipeTimelineRow(
        record = record,
        author = author,
        nowMs = nowMs,
        onEdit = onEdit,
        onDelete = onDelete,
        onOpenPhotos = onOpenPhotos,
        density = density,
        modifier = modifier,
    )
}

@Composable
fun DateBar(
    selectedDayStartMs: Long,
    nowMs: Long,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onOpenCalendar: () -> Unit,
    onBackToToday: () -> Unit,
    density: LeziDensity,
    modifier: Modifier = Modifier,
) {
    val date = remember(selectedDayStartMs) {
        DayAxisModel.dayLabelOffset(selectedDayStartMs, MonthCalendarSafe.todayStart(nowMs)).let { off ->
            val label = when (off) {
                -1 -> "昨天"
                0 -> "今天"
                1 -> "明天"
                else -> null
            }
            val formatted = DateTimeFormatter.ofPattern("M月d日")
                .withZone(ZoneId.of("Asia/Shanghai"))
                .format(Instant.ofEpochMilli(selectedDayStartMs))
            if (label != null) "$label · $formatted" else formatted
        }
    }
    val showBack = com.lezi.gf.app.ui.model.MonthCalendarModel.isNotToday(selectedDayStartMs, nowMs)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = density.topBarHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(onClick = onPrev) { Text("‹") }
        TextButton(onClick = onOpenCalendar) {
            Text(date, style = MaterialTheme.typography.titleMedium)
        }
        Row {
            if (showBack) {
                TextButton(onClick = onBackToToday) { Text("返回今天") }
            }
            TextButton(onClick = onNext) { Text("›") }
        }
    }
}

/** Avoid circular import noise for today start. */
private object MonthCalendarSafe {
    fun todayStart(nowMs: Long): Long =
        com.lezi.gf.app.ui.model.MonthCalendarModel.todayStartMs(nowMs)
}
