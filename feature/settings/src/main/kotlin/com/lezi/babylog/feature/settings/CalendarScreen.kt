package com.lezi.babylog.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.core.model.RecordTimeDecision
import com.lezi.babylog.core.model.FutureEventError
import com.lezi.babylog.designsystem.LeziClockDialDialog
import com.lezi.babylog.designsystem.LeziDatePicker
import com.lezi.babylog.designsystem.LeziDetailTopBar
import com.lezi.babylog.designsystem.LeziCard
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.StateContainer
import com.lezi.babylog.designsystem.StateKind
import com.lezi.babylog.designsystem.dismissKeyboardOnTap
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.CalendarEvent
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val careLog: CareLog,
    settingsStore: SettingsStore,
    private val reminderScheduler: CalendarReminderScheduler,
) : ViewModel() {
    private val zone = ZoneId.systemDefault()
    private val _status = MutableStateFlow<String?>(null)
    val status = _status

    @OptIn(ExperimentalCoroutinesApi::class)
    val events = careLog.observeCurrentBaby().flatMapLatest { baby ->
        if (baby == null) flowOf(emptyList())
        else {
            val start = RecordTime.today(zone)
                .minusYears(1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
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
            val now = RecordTime.currentTimeMillis()
            calendarEventError(title, eventAt, remindAt, now)?.let {
                onResult(it)
                return@launch
            }
            val id = careLog.addCalendarEvent(
                babyId = baby.id,
                title = title,
                eventAt = eventAt,
                remindAt = remindAt,
            )
            val event = careLog.listCalendarEvents(baby.id).firstOrNull { it.id == id }
            val scheduled = event?.let(reminderScheduler::schedule) == true
            _status.value = scheduleStatus(scheduled, remindAt != null)
            onResult(null)
        }
    }

    fun update(
        event: CalendarEvent,
        title: String,
        eventAt: Long,
        remindAt: Long?,
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            calendarEventError(title, eventAt, remindAt)?.let {
                onResult(it)
                return@launch
            }
            reminderScheduler.cancel(event.id)
            val updated = event.copy(
                title = title.trim(),
                eventAt = eventAt,
                remindAt = remindAt,
            )
            careLog.updateCalendarEvent(updated)
            val scheduled = reminderScheduler.schedule(updated)
            _status.value = scheduleStatus(scheduled, remindAt != null)
            onResult(null)
        }
    }

    fun delete(event: CalendarEvent, onDone: () -> Unit) {
        viewModelScope.launch {
            reminderScheduler.cancel(event.id)
            careLog.deleteCalendarEvent(event.id)
            onDone()
        }
    }

    fun setPermissionDegraded() {
        _status.value = "通知权限未开启；日程已保存，但本机不会显示通知"
    }

    private fun scheduleStatus(
        scheduled: Boolean,
        reminderEnabled: Boolean,
    ): String? = when {
        !reminderEnabled -> "日程已保存，未设置提醒"
        scheduled -> "日程与普通本地提醒已保存；系统可能因省电策略延后通知"
        else -> "日程已保存；提醒时刻已过，未安排通知"
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CalendarRoute(
    onBack: () -> Unit,
    initialDate: LocalDate = RecordTime.today(ZoneId.systemDefault()),
    vm: CalendarViewModel = hiltViewModel(),
) {
    val events by vm.events.collectAsStateWithLifecycle()
    val timeStepMin by vm.timeStepMin.collectAsStateWithLifecycle()
    val timePickerStyle by vm.timePickerStyle.collectAsStateWithLifecycle()
    val preferredHand by vm.preferredHand.collectAsStateWithLifecycle()
    val reminderStatus by vm.status.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    val context = LocalContext.current
    var showAdd by remember { mutableStateOf(false) }
    var editingEvent by remember { mutableStateOf<CalendarEvent?>(null) }
    var title by remember { mutableStateOf("") }
    var eventAt by remember(initialDate) {
        mutableStateOf(RecordTime.defaultFutureEventTimestamp(initialDate, zone))
    }
    var reminderEnabled by remember { mutableStateOf(true) }
    var remindAt by remember(initialDate) { mutableStateOf(eventAt - 60 * 60_000L) }
    var dateTarget by remember { mutableStateOf<CalendarClockTarget?>(null) }
    var clockTarget by remember { mutableStateOf<CalendarClockTarget?>(null) }
    var addError by remember { mutableStateOf<String?>(null) }
    var saveAfterPermission by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        saveAfterPermission?.invoke(granted)
        saveAfterPermission = null
    }
    val openCalendarDraft = {
        editingEvent = null
        eventAt = RecordTime.defaultFutureEventTimestamp(initialDate, zone)
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
        editingEvent = null
        addError = null
    }
    Scaffold(
        topBar = {
            LeziDetailTopBar(title = "日程", onBack = onBack)
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
            reminderStatus?.let {
                Text(
                    it,
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(LeziSpacing.Xs))
            }
            if (events.isEmpty()) {
                StateContainer(
                    kind = StateKind.Empty,
                    title = "还没有日程",
                    message = "为体检、用药或重要安排设一个时间",
                    actionLabel = "添加日程",
                    onAction = openCalendarDraft,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
                ) {
                    items(events, key = { it.id }) { e ->
                        LeziCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                editingEvent = e
                                title = e.title
                                eventAt = e.eventAt
                                reminderEnabled = e.remindAt != null
                                remindAt = e.remindAt ?: (e.eventAt - 60 * 60_000L)
                                addError = null
                                showAdd = true
                            },
                        ) {
                            Text(
                                e.title,
                                style = LeziTypography.BodyStrong,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(formatCalendarDateTime(e.eventAt, zone), style = LeziTypography.Meta)
                            e.remindAt?.let {
                                Text("提醒 ${formatCalendarDateTime(it, zone)}", style = LeziTypography.Meta)
                            }
                        }
                    }
                }
            }
        }
    }
    if (showAdd) {
        AlertDialog(
            onDismissRequest = closeCalendarDraft,
            modifier = Modifier.imePadding(),
            properties = DialogProperties(decorFitsSystemWindows = false),
            title = { Text(if (editingEvent == null) "新日程" else "编辑日程") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState())
                        .imePadding()
                        .dismissKeyboardOnTap(),
                    verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
                ) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = {
                            title = limitCalendarTitleInput(it)
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
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
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
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
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
                        val persist: (Boolean) -> Unit = { permissionGranted ->
                            val onSaved: (String?) -> Unit = { saveError ->
                                if (saveError == null) {
                                    if (!permissionGranted && selectedReminder != null) {
                                        vm.setPermissionDegraded()
                                    }
                                    closeCalendarDraft()
                                } else {
                                    addError = saveError
                                }
                            }
                            val existing = editingEvent
                            if (existing == null) {
                                vm.add(title.trim(), eventAt, selectedReminder, onSaved)
                            } else {
                                vm.update(
                                    existing,
                                    title.trim(),
                                    eventAt,
                                    selectedReminder,
                                    onSaved,
                                )
                            }
                        }
                        val needsPermission = selectedReminder != null &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED
                        if (needsPermission) {
                            saveAfterPermission = persist
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            persist(true)
                        }
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    editingEvent?.let { event ->
                        TextButton(
                            onClick = {
                                vm.delete(event) { closeCalendarDraft() }
                            },
                        ) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(onClick = closeCalendarDraft) { Text("取消") }
                }
            },
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
                            val decision = RecordTime.resolve(
                                date = date,
                                time = current.toLocalTime(),
                                zone = zone,
                                preferredOffset = current.offset,
                            )
                            when (decision) {
                                RecordTimeDecision.RejectedGap -> {
                                    addError = "所选日期不存在当前时刻，请改用其他时刻"
                                }
                                is RecordTimeDecision.Accepted -> {
                                    val changed = decision.value.toInstant().toEpochMilli()
                                    if (target == CalendarClockTarget.Event) {
                                        val priorEvent = eventAt
                                        eventAt = changed
                                        if (reminderEnabled) {
                                            remindAt = RecordTime.reminderAfterEventChange(
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
                        }
                        dateTarget = null
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { dateTarget = null }) { Text("取消") }
            },
        ) {
            LeziDatePicker(state = dateState)
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
                        remindAt = RecordTime.reminderAfterEventChange(
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

internal fun calendarEventError(
    title: String,
    eventAt: Long,
    remindAt: Long?,
    now: Long = RecordTime.currentTimeMillis(),
): String? {
    if (title.isBlank()) return "请填写日程标题"
    if (calendarTitleLength(title.trim()) > MAX_CALENDAR_TITLE_CODE_POINTS) {
        return "日程标题最多 $MAX_CALENDAR_TITLE_CODE_POINTS 个字符"
    }
    return when (RecordTime.futureEventError(eventAt, remindAt, now)) {
        FutureEventError.EventNotFuture -> "日程时间必须晚于现在"
        FutureEventError.ReminderNotFuture -> "提醒时间必须晚于现在"
        FutureEventError.ReminderNotBeforeEvent -> "提醒时间必须早于日程时间"
        null -> null
    }
}

private const val MAX_CALENDAR_TITLE_CODE_POINTS = 40

private fun calendarTitleLength(value: String): Int =
    value.codePointCount(0, value.length)

private fun limitCalendarTitleInput(value: String): String {
    if (calendarTitleLength(value) <= MAX_CALENDAR_TITLE_CODE_POINTS) return value
    return value.substring(0, value.offsetByCodePoints(0, MAX_CALENDAR_TITLE_CODE_POINTS))
}

private fun formatCalendarDateTime(timestamp: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(timestamp)
        .atZone(zone)
        .format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
