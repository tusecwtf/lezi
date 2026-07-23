package com.lezi.babylog.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * The single clock-editing surface used across Lezi.
 *
 * The dial only changes the local clock fields. The supplied date and zone are
 * preserved so callers cannot accidentally move a record to another day while
 * choosing a time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeziClockDialDialog(
    title: String,
    value: ZonedDateTime,
    minuteStep: Int = 1,
    onConfirm: (ZonedDateTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val step = normalizedMinuteStep(minuteStep)
    val initialTick = snapClock(value.hour, value.minute, step)
    var clockError by remember(value) { mutableStateOf<String?>(null) }
    val pickerState = rememberTimePickerState(
        initialHour = initialTick.hour,
        initialMinute = initialTick.minute,
        is24Hour = true,
    )
    LaunchedEffect(pickerState.hour, pickerState.minute, step) {
        val snapped = snapClock(pickerState.hour, pickerState.minute, step)
        if (pickerState.hour != snapped.hour || pickerState.minute != snapped.minute) {
            pickerState.minute = snapped.minute
            pickerState.hour = snapped.hour
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TimePicker(state = pickerState)
                if (step > 1) {
                    Text("以 $step 分钟为步进，拖动时自动吸附到最近刻度")
                }
                clockError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val merged = mergeClock(value, pickerState.hour, pickerState.minute, step)
                    if (merged == null) {
                        clockError = "该时刻因夏令时切换不存在，请选择其他时刻"
                    } else {
                        clockError = null
                        onConfirm(merged)
                    }
                },
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

internal fun normalizedMinuteStep(step: Int): Int = if (step == 5) 5 else 1

internal data class ClockTick(val hour: Int, val minute: Int)

/** Round to the nearest tick while keeping the result on the supplied local date. */
internal fun snapClock(hour: Int, minute: Int, step: Int): ClockTick {
    val normalized = normalizedMinuteStep(step)
    val total = hour.coerceIn(0, 23) * 60 + minute.coerceIn(0, 59)
    val rounded = (((total + normalized / 2) / normalized) * normalized)
        .coerceAtMost(24 * 60 - normalized)
    return ClockTick(hour = rounded / 60, minute = rounded % 60)
}

internal fun mergeClock(
    value: ZonedDateTime,
    hour: Int,
    minute: Int,
    step: Int,
): ZonedDateTime? {
    val snapped = snapClock(hour, minute, step)
    return resolveLeziLocalDateTime(
        date = value.toLocalDate(),
        time = LocalTime.of(snapped.hour, snapped.minute),
        zone = value.zone,
        preferredOffset = value.offset,
    )
}

/**
 * Resolve a local wall-clock value without silently normalizing a DST gap.
 * During an overlap, the caller's existing offset wins when it is still valid.
 */
fun resolveLeziLocalDateTime(
    date: LocalDate,
    time: LocalTime,
    zone: ZoneId,
    preferredOffset: ZoneOffset? = null,
): ZonedDateTime? {
    val local = LocalDateTime.of(date, time)
    val validOffsets = zone.rules.getValidOffsets(local)
    if (validOffsets.isEmpty()) return null
    val offset = preferredOffset?.takeIf(validOffsets::contains) ?: validOffsets.first()
    return ZonedDateTime.ofLocal(local, zone, offset)
}

/**
 * Combine an externally selected date with the current local clock.
 *
 * Future dates are clamped to today. A rare DST gap is normalized by the
 * platform to the next valid wall-clock instant so a new draft always has a
 * usable timestamp; explicit clock edits still use [resolveLeziLocalDateTime]
 * and surface an error instead.
 */
fun timestampOnLeziDate(
    date: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
    now: ZonedDateTime = ZonedDateTime.now(zone),
): Long {
    val safeDate = minOf(date, now.toLocalDate())
    val time = now.toLocalTime().withSecond(0).withNano(0)
    return (resolveLeziLocalDateTime(safeDate, time, zone, now.offset)
        ?: LocalDateTime.of(safeDate, time).atZone(zone))
        .toInstant()
        .toEpochMilli()
}

@Preview(name = "Shared clock dial", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun ClockDialPreview() {
    LeziTheme(visualStyle = "journal") {
        LeziClockDialDialog(
            title = "选择记录时刻",
            value = ZonedDateTime.of(
                LocalDate.of(2026, 7, 23),
                LocalTime.of(14, 25),
                ZoneId.of("Asia/Shanghai"),
            ),
            minuteStep = 5,
            onConfirm = {},
            onDismiss = {},
        )
    }
}
