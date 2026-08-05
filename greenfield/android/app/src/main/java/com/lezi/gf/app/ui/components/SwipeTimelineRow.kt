package com.lezi.gf.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lezi.gf.app.ui.theme.LeziColors
import com.lezi.gf.app.ui.theme.LeziDensity
import com.lezi.gf.app.ui.theme.LeziMotion
import com.lezi.gf.app.ui.theme.LeziRelativeTime
import com.lezi.gf.app.ui.theme.SwipeGestureModel
import com.lezi.gf.care.CareRecord
import com.lezi.gf.care.RecordType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * Timeline row with in-card fill swipe: left→edit (green from right), right→delete (red from left).
 * Spec 02 E2 + design 2026-07-29. TalkBack custom actions mirror swipe.
 */
@Composable
fun SwipeTimelineRow(
    record: CareRecord,
    author: String?,
    nowMs: Long,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenPhotos: (Int) -> Unit,
    density: LeziDensity,
    reduceMotion: Boolean = false,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onExpandRequest: () -> Unit = {},
) {
    val type = RecordType.fromKey(record.typeKey)
    val label = type?.chineseLabel ?: record.typeKey
    val time = remember(record.timestampMs) {
        DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.of("Asia/Shanghai"))
            .format(Instant.ofEpochMilli(record.timestampMs))
    }
    val relative = remember(record.timestampMs, nowMs) {
        LeziRelativeTime.format(record.timestampMs, nowMs)
    }
    val summary = remember(record) { timelineSummary(record) }
    val shape = RoundedCornerShape(if (density.useCards) density.cardCorner else 0.dp)
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val animMs = LeziMotion.millis(reduceMotion, LeziMotion.Fast)

    fun settleTo(target: Float, then: (() -> Unit)? = null) {
        scope.launch {
            offset.animateTo(target, animationSpec = tween(durationMillis = animMs))
            then?.invoke()
        }
    }

    LaunchedEffect(expanded) {
        if (!expanded && kotlin.math.abs(offset.value) > 0.01f) {
            offset.animateTo(0f, animationSpec = tween(durationMillis = animMs))
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = density.panelContent, vertical = 4.dp)
            .semantics {
                contentDescription = "$label $time $relative"
                customActions = listOf(
                    CustomAccessibilityAction("编辑") {
                        onEdit()
                        true
                    },
                    CustomAccessibilityAction("删除") {
                        onDelete()
                        true
                    },
                )
            },
        shape = shape,
        color = if (density.useCards) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        },
        tonalElevation = if (density.useCards) 1.dp else 0.dp,
        border = if (!density.useCards) {
            androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
        } else {
            null
        },
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .pointerInput(record.clientUuid) {
                    val widthPx = size.width.toFloat().coerceAtLeast(1f)
                    detectHorizontalDragGestures(
                        onDragStart = { onExpandRequest() },
                        onDragEnd = {
                            when (val s = SwipeGestureModel.settle(offset.value)) {
                                SwipeGestureModel.Settle.COMMIT_EDIT -> settleTo(0f) { onEdit() }
                                SwipeGestureModel.Settle.COMMIT_DELETE -> settleTo(0f) { onDelete() }
                                SwipeGestureModel.Settle.REVEAL_EDIT,
                                SwipeGestureModel.Settle.REVEAL_DELETE,
                                -> settleTo(SwipeGestureModel.settleTarget(s))
                                SwipeGestureModel.Settle.CLOSED -> settleTo(0f)
                            }
                        },
                        onDragCancel = { settleTo(0f) },
                        onHorizontalDrag = { _, dragAmount ->
                            val next = SwipeGestureModel.clampedOffset(offset.value + dragAmount / widthPx)
                            scope.launch { offset.snapTo(next) }
                        },
                    )
                },
        ) {
            val frac = offset.value
            // Delete fill grows from left
            if (frac > 0.01f) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(frac.coerceIn(0f, 1f))
                        .align(Alignment.CenterStart)
                        .background(LeziColors.DangerDelete)
                        .clickable { settleTo(0f) { onDelete() } },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (frac >= SwipeGestureModel.REVEAL_FRACTION * 0.7f) {
                        Text(
                            "删除",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            }
            // Edit fill grows from right
            if (frac < -0.01f) {
                val editFrac = (-frac).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(editFrac)
                        .align(Alignment.CenterEnd)
                        .background(LeziColors.SuccessEdit)
                        .clickable { settleTo(0f) { onEdit() } },
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    if (editFrac >= SwipeGestureModel.REVEAL_FRACTION * 0.7f) {
                        Text(
                            "编辑",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    }
                }
            }

            // Foreground — card does not translate
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (kotlin.math.abs(offset.value) > 0.05f) {
                            settleTo(0f)
                        } else {
                            onEdit()
                        }
                    }
                    .padding(density.cardPad),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TypeMark(
                    typeKey = record.typeKey,
                    size = 40.dp,
                    iconSize = 20.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            time,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            relative,
                            style = MaterialTheme.typography.labelSmall,
                            color = LeziColors.RelativeTimeMuted,
                        )
                    }
                    if (summary.isNotBlank()) {
                        Text(summary, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    }
                    if (record.note.isNotBlank()) {
                        Text(record.note, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                    val meta = buildString {
                        if (record.photos.isNotEmpty()) append("📷${record.photos.size}")
                        if (author != null) {
                            if (isNotEmpty()) append(" · ")
                            append(author)
                        }
                    }
                    if (meta.isNotEmpty()) {
                        Text(
                            meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = if (record.photos.isNotEmpty()) {
                                Modifier.clickable { onOpenPhotos(0) }
                            } else {
                                Modifier
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun timelineSummary(record: CareRecord): String {
    val p = record.payloadJson
    return when (record.typeKey) {
        RecordType.FORMULA.key, RecordType.PUMPED_FEED.key, RecordType.PUMP_EXPRESS.key -> {
            Regex("\"amount_ml\"\\s*:\\s*(\\d+)").find(p)?.groupValues?.get(1)?.let { "${it}ml" } ?: ""
        }
        RecordType.NURSING.key -> {
            val left = Regex("\"left_ms\"\\s*:\\s*(\\d+)").find(p)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            val right = Regex("\"right_ms\"\\s*:\\s*(\\d+)").find(p)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            val lm = left / 60_000
            val rm = right / 60_000
            if (lm == 0L && rm == 0L) "" else "L${lm}分 R${rm}分"
        }
        RecordType.SLEEP.key -> {
            Regex("\"duration_minutes\"\\s*:\\s*(\\d+)").find(p)?.groupValues?.get(1)?.let { "${it}分" } ?: ""
        }
        RecordType.PEE.key -> {
            Regex("\"amount\"\\s*:\\s*(\\d+)").find(p)?.groupValues?.get(1)?.let { "量$it" } ?: ""
        }
        RecordType.TEMPERATURE.key -> {
            Regex("\"celsius\"\\s*:\\s*([0-9.]+)").find(p)?.groupValues?.get(1)?.let { "${it}℃" } ?: ""
        }
        else -> ""
    }
}
