package com.lezi.babylog.designsystem

import android.content.res.Configuration
import android.os.LocaleList
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerLayoutType
import androidx.compose.material3.TimePickerSelectionMode
import androidx.compose.material3.TimePickerState
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.core.model.RecordTimeDecision
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

const val TIME_PICKER_STYLE_DROPDOWN = "dropdown"
const val TIME_PICKER_STYLE_DIAL = "dial"

/**
 * Shared date + time editor used across Lezi.
 *
 * [timePickerStyle]:
 * - [TIME_PICKER_STYLE_DROPDOWN]: 数字时钟 — 24-hour hour/minute dropdowns
 * - [TIME_PICKER_STYLE_DIAL]: 指针时钟 — 24h face + hour/minute boxes (00–23) +
 *   vertical 上午/下午 chips that only ±12 (side follows [preferredHand]).
 *   Wall clock only: midnight is 0:00, noon is 12:00, never 24:00.
 *
 * Date stays a separate calendar card. Callers still enforce domain rules.
 * Confirm always emits hour in 0–23 from the same selected state the UI shows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeziClockDialDialog(
    title: String,
    value: ZonedDateTime,
    minuteStep: Int = 1,
    timePickerStyle: String = TIME_PICKER_STYLE_DROPDOWN,
    /** "left" | "right" — places 上午/下午 beside the hour/minute boxes on the thumb side. */
    preferredHand: String = "right",
    /** Sleep-only: explain that overnight spans need a next-day date. */
    showCrossDayHint: Boolean = false,
    onConfirm: (ZonedDateTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val step = RecordTime.normalizedMinuteStep(minuteStep)
    val initialTick = RecordTime.snap(value.hour, value.minute, step)
    var clockError by remember(value) { mutableStateOf<String?>(null) }
    var selectedDate by remember(value) { mutableStateOf(value.toLocalDate()) }
    var showDatePicker by remember(value) { mutableStateOf(false) }
    val useDial = timePickerStyle == TIME_PICKER_STYLE_DIAL
    val periodOnStart = preferredHand != "right"

    // Dropdown keeps one local wall-clock state. Dial uses TimePickerState itself
    // as the only source of truth so an immediate confirm cannot observe a stale
    // asynchronous mirror of the hand position.
    var dropdownHour by remember(value) { mutableIntStateOf(initialTick.hour) }
    var dropdownMinute by remember(value) { mutableIntStateOf(initialTick.minute) }

    val pickerState = rememberTimePickerState(
        initialHour = initialTick.hour,
        initialMinute = initialTick.minute,
        is24Hour = true,
    )
    val dialTick = RecordTime.snap(pickerState.hour, pickerState.minute, step)

    val hourOptions = remember { (0..23).toList() }
    val minuteOptions = remember(step) {
        generateSequence(0) { it + step }
            .takeWhile { it < 60 }
            .toList()
    }

    fun setPeriodAm(wantAm: Boolean) {
        if (useDial) {
            pickerState.hour = applyClockPeriod(pickerState.hour, wantAm)
        } else {
            dropdownHour = applyClockPeriod(dropdownHour, wantAm)
        }
        clockError = null
    }

    fun resolvedHourMinute(): Pair<Int, Int> =
        if (useDial) {
            val selected = RecordTime.snap(pickerState.hour, pickerState.minute, step)
            selected.hour to selected.minute
        } else {
            dropdownHour to dropdownMinute
        }

    LeziAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    onClick = { showDatePicker = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription =
                                "选择日期，${formatClockDate(selectedDate)}"
                        },
                    shape = LeziThemeExt.controlShape,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Outlined.CalendarMonth, contentDescription = null)
                        Column(Modifier.weight(1f)) {
                            Text("日期", style = LeziTypography.Meta)
                            Text(
                                formatClockDate(selectedDate),
                                style = LeziTypography.BodyStrong,
                                maxLines = 1,
                            )
                        }
                        Text(
                            "日历",
                            style = LeziTypography.Label,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                if (useDial) {
                    // The custom time display and Material dial are centered independently.
                    // Material's built-in display is hidden to avoid a second HH:MM value.
                    DialTimePickerBody(
                        pickerState = pickerState,
                        hour24 = dialTick.hour,
                        minute = dialTick.minute,
                        isAm = isClockAm(dialTick.hour),
                        periodOnStart = periodOnStart,
                        onSelectAm = { setPeriodAm(true) },
                        onSelectPm = { setPeriodAm(false) },
                    )
                    if (step > 1) {
                        Text(
                            "分钟按 $step 分钟步进，拖动时自动吸附",
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(
                        "时间",
                        style = LeziTypography.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatClockTime(dropdownHour, dropdownMinute),
                        style = LeziTypography.TitleSm,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TimeDropdownField(
                            label = "时",
                            value = dropdownHour,
                            options = hourOptions,
                            format = { hour -> "%02d".format(hour) },
                            onSelect = {
                                dropdownHour = it
                                clockError = null
                            },
                            modifier = Modifier.weight(1f),
                            contentDescription = "选择小时，24 小时制",
                        )
                        TimeDropdownField(
                            label = "分",
                            value = dropdownMinute,
                            options = minuteOptions,
                            format = { minute -> "%02d".format(minute) },
                            onSelect = {
                                dropdownMinute = it
                                clockError = null
                            },
                            modifier = Modifier.weight(1f),
                            contentDescription = "选择分钟",
                        )
                    }
                    if (step > 1) {
                        Text(
                            "分钟按 $step 分钟步进可选",
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (showCrossDayHint) {
                    Text(
                        "跨天请先改日期：例如 22:00 睡下、次日 06:00 醒来。",
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                clockError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = LeziTypography.Meta)
                }
            }
        },
        confirmButton = {
            LeziTextButton(
                label = "确定",
                onClick = {
                    val (hour, minute) = resolvedHourMinute()
                    val decision = RecordTime.merge(
                        value = value,
                        date = selectedDate,
                        hour = hour,
                        minute = minute,
                        step = step,
                    )
                    when (decision) {
                        RecordTimeDecision.RejectedGap -> {
                            clockError = "该时刻因夏令时切换不存在，请选择其他时刻"
                        }
                        is RecordTimeDecision.Accepted -> {
                            clockError = null
                            onConfirm(decision.value)
                        }
                    }
                },
                tone = LeziTextButtonTone.Primary,
            )
        },
        dismissButton = {
            LeziTextButton(label = "取消", onClick = onDismiss)
        },
    )

    if (showDatePicker) {
        val selectedUtc = selectedDate
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = selectedUtc,
        )
        LeziDatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            onConfirm = {
                dateState.selectedDateMillis?.let { millis ->
                    selectedDate = Instant.ofEpochMilli(millis)
                        .atZone(ZoneOffset.UTC)
                        .toLocalDate()
                }
                showDatePicker = false
            },
        ) {
            ChineseLocale {
                LeziDatePicker(
                    state = dateState,
                    title = {
                        Text(
                            text = "选择日期",
                            modifier = Modifier.padding(horizontal = 24.dp),
                            style = LeziTypography.BodyStrong,
                        )
                    },
                    headline = {
                        Text(
                            text = dateState.selectedDateMillis?.let { millis ->
                                formatClockDate(
                                    Instant.ofEpochMilli(millis)
                                        .atZone(ZoneOffset.UTC)
                                        .toLocalDate(),
                                )
                            } ?: "请选择日期",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp),
                            style = LeziTypography.TitleSm,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    showModeToggle = false,
                )
            }
        }
    }
}

/**
 * Material3 TimePicker token sizes (androidx.compose.material3 TimePickerTokens).
 * Display row and period match native sizes; dial is shown alone so layout can center each unit.
 */
private val TimeDisplayNumberWidth = 96.dp
private val TimeDisplaySeparatorWidth = 24.dp
private val TimeDisplayRowHeight = 80.dp
private val PeriodToggleWidth = 52.dp
// Each half remains a full 48dp touch target around the divider.
private val PeriodToggleHeight = 98.dp
private val PeriodToggleGap = 12.dp
private val ClockDialSize = 256.dp
private val ClockDisplayBottomMargin = 36.dp
private val ClockFaceBottomMargin = 24.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialTimePickerBody(
    pickerState: TimePickerState,
    hour24: Int,
    minute: Int,
    isAm: Boolean,
    periodOnStart: Boolean,
    onSelectAm: () -> Unit,
    onSelectPm: () -> Unit,
) {
    val hourSelected = pickerState.selection == TimePickerSelectionMode.Hour
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (periodOnStart) {
                PeriodToggle(
                    isAm = isAm,
                    onSelectAm = onSelectAm,
                    onSelectPm = onSelectPm,
                )
                Spacer(Modifier.width(PeriodToggleGap))
            }
            DialClockDisplay(
                hour = hour24,
                minute = minute,
                hourSelected = hourSelected,
                onHourClick = { pickerState.selection = TimePickerSelectionMode.Hour },
                onMinuteClick = { pickerState.selection = TimePickerSelectionMode.Minute },
            )
            if (!periodOnStart) {
                Spacer(Modifier.width(PeriodToggleGap))
                PeriodToggle(
                    isAm = isAm,
                    onSelectAm = onSelectAm,
                    onSelectPm = onSelectPm,
                )
            }
        }
        // Shift Material's display out of view while preserving the full dial.
        Box(
            modifier = Modifier
                .width(ClockDialSize)
                .height(ClockDisplayBottomMargin + ClockDialSize + ClockFaceBottomMargin)
                .clip(RectangleShape),
        ) {
            ChineseLocale {
                TimePicker(
                    state = pickerState,
                    layoutType = TimePickerLayoutType.Vertical,
                    modifier = Modifier
                        .wrapContentSize(align = Alignment.TopCenter, unbounded = true)
                        .offset(y = -TimeDisplayRowHeight),
                )
            }
        }
    }
}

@Composable
private fun DialClockDisplay(
    hour: Int,
    minute: Int,
    hourSelected: Boolean,
    onHourClick: () -> Unit,
    onMinuteClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TimeSelectorBox(
            text = "%02d".format(hour.coerceIn(0, 23)),
            selected = hourSelected,
            onClick = onHourClick,
            contentDescription = "选择小时，24 小时制",
        )
        Box(
            modifier = Modifier
                .width(TimeDisplaySeparatorWidth)
                .height(TimeDisplayRowHeight),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = ":",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
        TimeSelectorBox(
            text = "%02d".format(minute.coerceIn(0, 59)),
            selected = !hourSelected,
            onClick = onMinuteClick,
            contentDescription = "选择分钟",
        )
    }
}

@Composable
private fun TimeSelectorBox(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
) {
    val shape = LeziThemeExt.controlShape
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = Modifier
            .size(width = TimeDisplayNumberWidth, height = TimeDisplayRowHeight)
            .clip(shape)
            .background(container)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.displayLarge,
            color = content,
            maxLines = 1,
        )
    }
}

@Composable
private fun PeriodToggle(
    isAm: Boolean,
    onSelectAm: () -> Unit,
    onSelectPm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = LeziThemeExt.controlShape
    val outline = MaterialTheme.colorScheme.outline
    val selectedContainer = MaterialTheme.colorScheme.tertiaryContainer
    val selectedContent = MaterialTheme.colorScheme.onTertiaryContainer
    val unselectedContent = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .size(width = PeriodToggleWidth, height = PeriodToggleHeight)
            .border(width = 1.dp, color = outline, shape = shape)
            .clip(shape)
            .semantics { contentDescription = if (isAm) "上午" else "下午" },
    ) {
        PeriodToggleHalf(
            label = "上午",
            selected = isAm,
            selectedContainer = selectedContainer,
            selectedContent = selectedContent,
            unselectedContent = unselectedContent,
            onClick = onSelectAm,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
        HorizontalDivider(thickness = 1.dp, color = outline)
        PeriodToggleHalf(
            label = "下午",
            selected = !isAm,
            selectedContainer = selectedContainer,
            selectedContent = selectedContent,
            unselectedContent = unselectedContent,
            onClick = onSelectPm,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
    }
}

@Composable
private fun PeriodToggleHalf(
    label: String,
    selected: Boolean,
    selectedContainer: Color,
    selectedContent: Color,
    unselectedContent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(if (selected) selectedContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) selectedContent else unselectedContent,
            maxLines = 1,
        )
    }
}

/**
 * 上午/下午 quick toggle on a 24h wall clock (0–23).
 * 下午 adds 12 when still in the morning half; 上午 subtracts 12 when in the afternoon half.
 * Already in the requested half → unchanged. Midnight is 0; noon is 12; never 24.
 */
internal fun applyClockPeriod(hour24: Int, wantAm: Boolean): Int {
    val h = hour24.coerceIn(0, 23)
    return when {
        wantAm && h >= 12 -> h - 12
        !wantAm && h < 12 -> h + 12
        else -> h
    }
}

internal fun isClockAm(hour24: Int): Boolean = hour24.coerceIn(0, 23) < 12

@Composable
private fun ChineseLocale(content: @Composable () -> Unit) {
    val baseConfiguration = LocalConfiguration.current
    val baseContext = LocalContext.current
    val chineseConfiguration = remember(baseConfiguration) {
        Configuration(baseConfiguration).apply {
            setLocales(LocaleList.forLanguageTags("zh-CN"))
        }
    }
    val chineseContext = remember(baseContext, chineseConfiguration) {
        baseContext.createConfigurationContext(chineseConfiguration)
    }
    CompositionLocalProvider(
        LocalConfiguration provides chineseConfiguration,
        LocalContext provides chineseContext,
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDropdownField(
    label: String,
    value: Int,
    options: List<Int>,
    format: (Int) -> String,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        LeziTextField(
            value = format(value),
            onValueChange = {},
            readOnly = true,
            label = label,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
                .semantics { this.contentDescription = contentDescription },
            singleLine = true,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(format(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun formatClockDate(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEE", Locale.SIMPLIFIED_CHINESE))

private fun formatClockTime(hour: Int, minute: Int): String =
    "%02d:%02d".format(hour.coerceIn(0, 23), minute.coerceIn(0, 59))

@Preview(name = "Time dial right hand", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun ClockDialPreviewRightHand() {
    LeziTheme(visualStyle = "journal") {
        LeziClockDialDialog(
            title = "选择记录时刻",
            value = ZonedDateTime.of(
                LocalDate.of(2026, 7, 23),
                LocalTime.of(22, 0),
                ZoneId.of("Asia/Shanghai"),
            ),
            minuteStep = 5,
            timePickerStyle = TIME_PICKER_STYLE_DIAL,
            preferredHand = "right",
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "Time dial left hand", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun ClockDialPreviewLeftHand() {
    LeziTheme(visualStyle = "journal") {
        LeziClockDialDialog(
            title = "选择记录时刻",
            value = ZonedDateTime.of(
                LocalDate.of(2026, 7, 23),
                LocalTime.of(1, 23),
                ZoneId.of("Asia/Shanghai"),
            ),
            minuteStep = 1,
            timePickerStyle = TIME_PICKER_STYLE_DIAL,
            preferredHand = "left",
            onConfirm = {},
            onDismiss = {},
        )
    }
}
