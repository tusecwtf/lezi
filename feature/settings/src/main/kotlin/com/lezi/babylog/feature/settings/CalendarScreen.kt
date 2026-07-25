package com.lezi.babylog.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.designsystem.LeziClockDialDialog
import com.lezi.babylog.designsystem.LeziCard
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.dismissKeyboardOnTap
import com.lezi.babylog.designsystem.resolveLeziLocalDateTime
import com.lezi.babylog.domain.CareLog
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val careLog: CareLog,
    settingsStore: SettingsStore,
) : ViewModel() {
    private val zone = ZoneId.systemDefault()

    @OptIn(ExperimentalCoroutinesApi::class)
    val events = careLog.observeCurrentBaby().flatMapLatest { baby ->
        if (baby == null) flowOf(emptyList())
        else {
            val start = LocalDate.now(zone).minusYears(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = LocalDate.of(2101, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
            careLog.observeCalendarEvents(baby.id, start, end)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val timeStepMin = settingsStore.settings
        .map { it.timeStepMin }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1)

    val timePickerStyle = settingsStore.settings
        .map { it.timePickerStyle }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "dropdown")

    val preferredHand = settingsStore.settings
        .map { it.preferredHand }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "right")

    fun add(
        title: String,
        eventAt: Long,
        remindAt: Long?,
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            val baby = careLog.getCurrentBaby()
            if (baby == null) {
                onResult("请先添加宝宝")
                return@launch
            }
            val now = System.currentTimeMillis()
            calendarEventError(title, eventAt, remindAt, now)?.let {
                onResult(it)
                return@launch
            }
            careLog.addCalendarEvent(
                babyId = baby.id,
                title = title,
                eventAt = eventAt,
                remindAt = remindAt,
            )
            onResult(null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarRoute(
    onBack: () -> Unit,
    initialDate: LocalDate = LocalDate.now(),
    vm: CalendarViewModel = hiltViewModel(),
) {
    val events by vm.events.collectAsStateWithLifecycle()
    val timeStepMin by vm.timeStepMin.collectAsStateWithLifecycle()
    val timePickerStyle by vm.timePickerStyle.collectAsStateWithLifecycle()
    val preferredHand by vm.preferredHand.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    var showAdd by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var eventAt by remember(initialDate) {
        mutableStateOf(defaultCalendarEventAt(initialDate, zone = zone))
    }
    var reminderEnabled by remember { mutableStateOf(true) }
    var remindAt by remember(initialDate) { mutableStateOf(eventAt - 60 * 60_000L) }
    var dateTarget by remember { mutableStateOf<CalendarClockTarget?>(null) }
    var clockTarget by remember { mutableStateOf<CalendarClockTarget?>(null) }
    var addError by remember { mutableStateOf<String?>(null) }
    val openCalendarDraft = {
        eventAt = defaultCalendarEventAt(initialDate, zone = zone)
        remindAt = eventAt - 60 * 60_000L
        title = ""
        reminderEnabled = true
        dateTarget = null
        clockTarget = null
        addError = null
        showAdd = true
    }
    val closeCalendarDraft = {
        showAdd = false
        dateTarget = null
        clockTarget = null
        title = ""
        addError = null
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("日程") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(LeziSpacing.Page),
        ) {
            LeziPrimaryButton(
                "添加日程",
                onClick = openCalendarDraft,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(LeziSpacing.Sm))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xs)) {
                items(events, key = { it.id }) { e ->
                    LeziCard(Modifier.fillMaxWidth()) {
                        Text(e.title, style = LeziTypography.BodyStrong)
                        Text(formatCalendarDateTime(e.eventAt, zone), style = LeziTypography.Meta)
                        e.remindAt?.let {
                            Text("提醒 ${formatCalendarDateTime(it, zone)}", style = LeziTypography.Meta)
                        }
                    }
                }
            }
        }
    }
    if (showAdd) {
        AlertDialog(
            onDismissRequest = closeCalendarDraft,
            title = { Text("新日程") },
            text = {
                Column(
                    Modifier.dismissKeyboardOnTap(),
                    verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
                ) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = {
                            title = it
                            addError = null
                        },
                        label = { Text("标题") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("日程时间", style = LeziTypography.Label)
                    Text(
                        formatCalendarDateTime(eventAt, zone),
                        style = LeziTypography.BodyStrong,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { dateTarget = CalendarClockTarget.Event }) {
                            Text("修改日期")
                        }
                        OutlinedButton(onClick = { clockTarget = CalendarClockTarget.Event }) {
                            Text("选择时间")
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("提前提醒", style = LeziTypography.Label)
                        Switch(
                            checked = reminderEnabled,
                            onCheckedChange = {
                                reminderEnabled = it
                                if (it) {
                                    remindAt = eventAt - 60 * 60_000L
                                }
                            },
                        )
                    }
                    if (reminderEnabled) {
                        Text(
                            formatCalendarDateTime(remindAt, zone),
                            style = LeziTypography.BodyStrong,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { dateTarget = CalendarClockTarget.Reminder }) {
                                Text("提醒日期")
                            }
                            OutlinedButton(onClick = { clockTarget = CalendarClockTarget.Reminder }) {
                                Text("提醒时刻")
                            }
                        }
                    }
                    addError?.let {
                        Text(
                            it,
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val selectedReminder = remindAt.takeIf { reminderEnabled }
                    val error = calendarEventError(
                        title = title,
                        eventAt = eventAt,
                        remindAt = selectedReminder,
                    )
                    if (error != null) {
                        addError = error
                    } else {
                        vm.add(title.trim(), eventAt, selectedReminder) { saveError ->
                            if (saveError == null) {
                                closeCalendarDraft()
                            } else {
                                addError = saveError
                            }
                        }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = closeCalendarDraft) { Text("取消") } },
        )
    }

    dateTarget?.let { target ->
        val value = if (target == CalendarClockTarget.Event) eventAt else remindAt
        val current = Instant.ofEpochMilli(value).atZone(zone)
        val initialUtc = current.toLocalDate()
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initialUtc)
        DatePickerDialog(
            onDismissRequest = { dateTarget = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        dateState.selectedDateMillis?.let { millis ->
                            val date = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            val resolved = resolveLeziLocalDateTime(
                                date = date,
                                time = current.toLocalTime(),
                                zone = zone,
                                preferredOffset = current.offset,
                            )
                            if (resolved == null) {
                                addError = "所选日期不存在当前时刻，请改用其他时刻"
                            } else {
                                val changed = resolved.toInstant().toEpochMilli()
                                if (target == CalendarClockTarget.Event) {
                                    val priorEvent = eventAt
                                    eventAt = changed
                                    if (reminderEnabled) {
                                        remindAt = reminderAfterEventChange(
                                            priorEventAt = priorEvent,
                                            newEventAt = eventAt,
                                            priorReminderAt = remindAt,
                                        )
                                    }
                                } else {
                                    remindAt = changed
                                }
                                addError = null
                            }
                        }
                        dateTarget = null
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { dateTarget = null }) { Text("取消") }
            },
        ) {
            DatePicker(state = dateState)
        }
    }

    clockTarget?.let { target ->
        val value = if (target == CalendarClockTarget.Event) eventAt else remindAt
        LeziClockDialDialog(
            title = if (target == CalendarClockTarget.Event) "选择日程时刻" else "选择提醒时刻",
            value = Instant.ofEpochMilli(value).atZone(zone),
            minuteStep = timeStepMin,
            timePickerStyle = timePickerStyle,
            preferredHand = preferredHand,
            onConfirm = { picked ->
                val changed = picked.toInstant().toEpochMilli()
                if (target == CalendarClockTarget.Event) {
                    val priorEvent = eventAt
                    eventAt = changed
                    if (reminderEnabled) {
                        remindAt = reminderAfterEventChange(
                            priorEventAt = priorEvent,
                            newEventAt = eventAt,
                            priorReminderAt = remindAt,
                        )
                    }
                } else {
                    remindAt = changed
                }
                addError = null
                clockTarget = null
            },
            onDismiss = { clockTarget = null },
        )
    }
}

private enum class CalendarClockTarget { Event, Reminder }

internal fun defaultCalendarEventAt(
    initialDate: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
    now: ZonedDateTime = ZonedDateTime.now(zone),
): Long {
    val preferred = resolveLeziLocalDateTime(
        date = initialDate,
        time = LocalTime.of(now.hour, now.minute),
        zone = zone,
        preferredOffset = now.offset,
    ) ?: initialDate.atTime(LocalTime.of(now.hour, now.minute)).atZone(zone)
    val future = if (preferred.isAfter(now)) {
        preferred
    } else {
        now.plusDays(1).withSecond(0).withNano(0)
    }
    return future.withSecond(0).withNano(0).toInstant().toEpochMilli()
}

internal fun calendarEventError(
    title: String,
    eventAt: Long,
    remindAt: Long?,
    now: Long = System.currentTimeMillis(),
): String? = when {
    title.isBlank() -> "请填写日程标题"
    eventAt <= now -> "日程时间必须晚于现在"
    remindAt != null && remindAt <= now -> "提醒时间必须晚于现在"
    remindAt != null && remindAt >= eventAt -> "提醒时间必须早于日程时间"
    else -> null
}

internal fun reminderAfterEventChange(
    priorEventAt: Long,
    newEventAt: Long,
    priorReminderAt: Long,
): Long {
    if (priorReminderAt >= newEventAt) return newEventAt - 60 * 60_000L
    val priorLeadTime = (priorEventAt - priorReminderAt).coerceAtLeast(60_000L)
    return newEventAt - priorLeadTime
}

private fun formatCalendarDateTime(timestamp: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(timestamp)
        .atZone(zone)
        .format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
