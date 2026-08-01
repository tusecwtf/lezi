package com.lezi.babylog.feature.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.ConflictNotAdoptedAudit
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.businessLabel
import com.lezi.babylog.core.model.displayLabel
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.designsystem.LeziDetailTopBar
import com.lezi.babylog.designsystem.LeziSurfacePanel
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.LocalPhotoLoadResult
import com.lezi.babylog.designsystem.LocalPhotoTarget
import com.lezi.babylog.designsystem.StateContainer
import com.lezi.babylog.designsystem.StateKind
import com.lezi.babylog.designsystem.rememberLocalPhoto
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.CustomRecordItem
import com.lezi.babylog.domain.calendar.SYSTEM_CALENDAR_UNSYNCED_LABEL
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import kotlinx.coroutines.flow.combine
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Current lezi-calendar row backed only by a care plan. */
sealed class CalendarDayItem {
    abstract val sortAt: Long
    data class Plan(val plan: CarePlan) : CalendarDayItem() {
        override val sortAt: Long get() = plan.scheduledAt
    }
}

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val careLog: CareLog,
    settingsStore: SettingsStore,
) : ViewModel() {
    private val zone = ZoneId.systemDefault()
    private val _status = MutableStateFlow<String?>(null)
    val status = _status

    private val visibleMonth = MutableStateFlow(YearMonth.from(RecordTime.today(zone)))

    fun showMonth(month: YearMonth) {
        visibleMonth.value = month
    }

    /** Family owner/admin — gates conflict audit entry (domain still re-checks). */
    val isFamilyAdmin = flow { emit(careLog.isFamilyAdmin()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _conflictAudits = MutableStateFlow<List<ConflictNotAdoptedAudit>>(emptyList())
    val conflictAudits = _conflictAudits.asStateFlow()

    private val _conflictDetail = MutableStateFlow<ConflictNotAdoptedAudit?>(null)
    val conflictDetail = _conflictDetail.asStateFlow()

    private val _conflictBusy = MutableStateFlow(false)
    val conflictBusy = _conflictBusy.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val carePlans = combine(careLog.observeCurrentBaby(), visibleMonth) { baby, month ->
        baby to calendarMonthWindow(month, zone)
    }.flatMapLatest { (baby, window) ->
        if (baby == null) flowOf(emptyList())
        else careLog.observeCarePlansInRange(baby.id, window.startInclusive, window.endExclusive)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * plan.clientUuid → conflict-not-adopted count for admin chrome on completed plans.
     * Non-admins always get empty (domain list returns empty).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val conflictCountByPlanUuid = combine(carePlans, isFamilyAdmin) { plans, admin ->
        plans to admin
    }.flatMapLatest { (plans, admin) ->
        flow {
            if (!admin) {
                emit(emptyMap())
                return@flow
            }
            val completed = plans.filter {
                it.status == CarePlanStatus.COMPLETED ||
                    it.effectiveStatus() == CarePlanStatus.COMPLETED
            }
            val counts = buildMap {
                for (plan in completed) {
                    val n = careLog.listConflictNotAdoptedAudits(
                        carePlanClientUuid = plan.clientUuid,
                    ).size
                    if (n > 0) put(plan.clientUuid, n)
                }
            }
            emit(counts)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val dayItems = carePlans.map { plans ->
        plans.map { CalendarDayItem.Plan(it) }.sortedBy { it.sortAt }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun loadConflictAuditsForPlan(carePlanClientUuid: String, onLoaded: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val list = careLog.listConflictNotAdoptedAudits(
                carePlanClientUuid = carePlanClientUuid,
            )
            _conflictAudits.value = list
            _conflictDetail.value = null
            onLoaded(list.size)
        }
    }

    fun openConflictDetail(candidateClientUuid: String) {
        viewModelScope.launch {
            _conflictDetail.value = careLog.getConflictNotAdoptedAudit(candidateClientUuid)
        }
    }

    fun clearConflictDetail() {
        _conflictDetail.value = null
    }

    fun clearConflictAudits() {
        _conflictAudits.value = emptyList()
        _conflictDetail.value = null
    }

    fun convertConflictToIndependentRecord(
        candidateClientUuid: String,
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            if (_conflictBusy.value) return@launch
            _conflictBusy.value = true
            runCatching {
                careLog.convertConflictNotAdoptedToIndependentRecord(candidateClientUuid)
            }.onSuccess {
                _status.value = "已转为独立护理记录"
                // Refresh list + detail so converted badge and id show.
                val planUuid = _conflictAudits.value
                    .firstOrNull { it.candidateClientUuid == candidateClientUuid }
                    ?.carePlanClientUuid
                if (planUuid != null) {
                    _conflictAudits.value = careLog.listConflictNotAdoptedAudits(
                        carePlanClientUuid = planUuid,
                    )
                }
                _conflictDetail.value = careLog.getConflictNotAdoptedAudit(candidateClientUuid)
                onResult(null)
            }.onFailure { err ->
                onResult(err.message ?: "转换失败")
            }
            _conflictBusy.value = false
        }
    }

    /**
     * Open plans that wanted system-calendar projection but are currently unsynced
     * (permission revoked, target gone, or event missing). Feeds「未同步到系统日历」chrome.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val systemCalendarUnsyncedPlanIds = combine(carePlans, settingsStore.settings) { plans, prefs ->
        plans to prefs
    }.flatMapLatest { (plans, prefs) ->
        flow {
            if (!prefs.systemCalendarEnabled || prefs.systemCalendarId.isNullOrBlank()) {
                emit(emptySet())
                return@flow
            }
            val open = plans.filter {
                val s = it.effectiveStatus()
                s == CarePlanStatus.PENDING || s == CarePlanStatus.MISSED
            }
            val unsynced = open.mapNotNull { plan ->
                plan.id.takeIf { careLog.isCarePlanSystemCalendarUnsynced(it) }
            }.toSet()
            emit(unsynced)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val settings = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsLocal())

    val customItems = careLog.observeCustomItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CalendarRoute(
    onBack: () -> Unit,
    initialDate: LocalDate = RecordTime.today(ZoneId.systemDefault()),
    /** Open “安排护理” Composer for a concrete built-in or custom item. */
    onScheduleCare: (type: RecordType, scheduledAt: Long, customItemId: Long?) -> Unit =
        { _, _, _ -> },
    /** Open fulfill Composer for an open plan. */
    onFulfillPlan: (carePlanId: Long) -> Unit = {},
    /** Open edit-plan Composer for completed/skipped rows (or open plans). */
    onEditPlan: (carePlanId: Long) -> Unit = {},
    vm: CalendarViewModel = hiltViewModel(),
) {
    val dayItems by vm.dayItems.collectAsStateWithLifecycle()
    val reminderStatus by vm.status.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val customItems by vm.customItems.collectAsStateWithLifecycle()
    val systemCalendarUnsyncedPlanIds by vm.systemCalendarUnsyncedPlanIds.collectAsStateWithLifecycle()
    val isFamilyAdmin by vm.isFamilyAdmin.collectAsStateWithLifecycle()
    val conflictCountByPlanUuid by vm.conflictCountByPlanUuid.collectAsStateWithLifecycle()
    val conflictAudits by vm.conflictAudits.collectAsStateWithLifecycle()
    val conflictDetail by vm.conflictDetail.collectAsStateWithLifecycle()
    val conflictBusy by vm.conflictBusy.collectAsStateWithLifecycle()
    val wallClockMillis by produceState(initialValue = RecordTime.currentTimeMillis()) {
        while (true) {
            val current = RecordTime.currentTimeMillis()
            value = current
            delay((60_000L - current % 60_000L).coerceAtLeast(1L))
        }
    }
    val zone = ZoneId.systemDefault()
    val calendarNow = remember(zone, wallClockMillis) {
        Instant.ofEpochMilli(wallClockMillis).atZone(zone)
    }
    val today = calendarNow.toLocalDate()
    var selectedDateEpochDay by rememberSaveable(initialDate) {
        mutableLongStateOf(initialDate.toEpochDay())
    }
    val monthState = remember(selectedDateEpochDay, today) {
        restoreCalendarMonthState(selectedDateEpochDay, today)
    }
    LaunchedEffect(monthState.visibleMonth) {
        vm.showMonth(monthState.visibleMonth)
    }
    val selectedDayItems = remember(dayItems, monthState.selectedDate, zone) {
        calendarItemsForDate(dayItems, monthState.selectedDate, zone)
    }
    val itemCountsByDate = remember(dayItems, zone) {
        calendarItemCountsByDate(dayItems, zone)
    }
    var showPlanTypePicker by remember { mutableStateOf(false) }
    var showConflictList by remember { mutableStateOf(false) }
    var confirmConvertCandidate by remember { mutableStateOf<String?>(null) }
    var conflictError by remember { mutableStateOf<String?>(null) }
    var previewPhotos by remember { mutableStateOf<List<String>?>(null) }
    var previewStartIndex by remember { mutableStateOf(0) }
    var scheduleError by remember { mutableStateOf<String?>(null) }
    val planableItems = remember(settings.hiddenItems, customItems) {
        calendarPlanableItems(settings.hiddenItems, customItems)
    }
    val defaultCarePlanAt = calendarDefaultCarePlanTimestamp(
        selectedDate = monthState.selectedDate,
        zone = zone,
        now = calendarNow,
    )
    val canScheduleSelectedDate = defaultCarePlanAt != null
    val openPlanTypePicker = {
        if (
            calendarDefaultCarePlanTimestamp(
                selectedDate = monthState.selectedDate,
                zone = zone,
            ) != null
        ) {
            scheduleError = null
            showPlanTypePicker = true
        } else {
            showPlanTypePicker = false
            scheduleError = "所选日期已没有可安排的未来时刻，请选择其他日期。"
        }
    }
    LaunchedEffect(canScheduleSelectedDate) {
        if (!canScheduleSelectedDate) showPlanTypePicker = false
    }
    LaunchedEffect(monthState.selectedDate) {
        scheduleError = null
    }
    Scaffold(
        topBar = {
            LeziDetailTopBar(title = "乐记日历", onBack = onBack)
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(LeziSpacing.Page)
                .verticalScroll(rememberScrollState())
                .testTag(CalendarUiTags.SelectedDayItems),
            verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
        ) {
            CalendarMonthPicker(
                state = monthState,
                itemCountsByDate = itemCountsByDate,
                onPreviousMonth = {
                    selectedDateEpochDay = monthState.previousMonth().selectedDate.toEpochDay()
                },
                onNextMonth = {
                    selectedDateEpochDay = monthState.nextMonth().selectedDate.toEpochDay()
                },
                onSelectDate = { date ->
                    selectedDateEpochDay = monthState.selectDate(date).selectedDate.toEpochDay()
                },
            )
            LeziPrimaryButton(
                "＋ 安排护理",
                onClick = openPlanTypePicker,
                enabled = canScheduleSelectedDate,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(CalendarUiTags.ScheduleCare)
                    .semantics {
                        contentDescription =
                            if (canScheduleSelectedDate) {
                                "在${monthState.selectedDate.monthValue}月" +
                                    "${monthState.selectedDate.dayOfMonth}日安排护理"
                            } else {
                                "${monthState.selectedDate.monthValue}月" +
                                    "${monthState.selectedDate.dayOfMonth}日没有可安排的未来时刻"
                            }
                    },
            )
            if (!canScheduleSelectedDate && selectedDayItems.isNotEmpty()) {
                Text(
                    "该日期仅供查看；护理计划只能安排在未来时刻。",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            scheduleError?.let {
                Text(
                    it,
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            reminderStatus?.let {
                Text(
                    it,
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${monthState.selectedDate.monthValue}月${monthState.selectedDate.dayOfMonth}日",
                style = LeziTypography.TitleSm,
                modifier = Modifier.semantics {
                    contentDescription =
                        "已选择${monthState.selectedDate.year}年" +
                            "${monthState.selectedDate.monthValue}月" +
                            "${monthState.selectedDate.dayOfMonth}日，" +
                            "${selectedDayItems.size}条安排"
                },
            )
            if (selectedDayItems.isEmpty()) {
                CalendarEmptyDayState(
                    canScheduleSelectedDate = canScheduleSelectedDate,
                    onSchedule = openPlanTypePicker,
                )
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
                ) {
                    selectedDayItems.forEach { item ->
                        when (item) {
                            is CalendarDayItem.Plan -> {
                                val plan = item.plan
                                val effective = plan.effectiveStatus()
                                val unsynced = plan.id in systemCalendarUnsyncedPlanIds
                                val conflictCount = conflictCountByPlanUuid[plan.clientUuid] ?: 0
                                LeziSurfacePanel(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("calendar_plan_${plan.id}"),
                                    bottomBand = true,
                                    onClick = {
                                        val open = effective == CarePlanStatus.PENDING ||
                                            effective == CarePlanStatus.MISSED
                                        when {
                                            open -> onFulfillPlan(plan.id)
                                            shouldOpenConflictAudit(
                                                isFamilyAdmin = isFamilyAdmin,
                                                effective = effective,
                                                conflictCount = conflictCount,
                                            ) -> {
                                                // Completed/history entry for conflict audit.
                                                vm.loadConflictAuditsForPlan(plan.clientUuid) {
                                                    showConflictList = true
                                                }
                                            }
                                            else -> onEditPlan(plan.id)
                                        }
                                    },
                                ) {
                                    Text(plan.displayLabel(), style = LeziTypography.BodyStrong)
                                    Text(
                                        carePlanCalendarMetaLine(
                                            plan = plan,
                                            effective = effective,
                                            deviceZone = zone,
                                            systemCalendarUnsynced = unsynced,
                                        ),
                                        style = LeziTypography.Meta,
                                    )
                                    if (isFamilyAdmin && conflictCount > 0) {
                                        Text(
                                            "冲突未采纳 $conflictCount · 点此审计",
                                            style = LeziTypography.Meta,
                                            color = MaterialTheme.colorScheme.tertiary,
                                            modifier = Modifier.testTag(
                                                "calendar_plan_conflict_${plan.id}",
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showPlanTypePicker) {
        AlertDialog(
            onDismissRequest = { showPlanTypePicker = false },
            title = { Text("选择记录项目") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (planableItems.isEmpty()) {
                        Text(
                            "没有已开启的可安排项目。可在记录设置中重新开启，或添加自定义项目。",
                            style = LeziTypography.Meta,
                        )
                    }
                    planableItems.forEach { item ->
                        TextButton(
                            onClick = {
                                val at = calendarDefaultCarePlanTimestamp(
                                    selectedDate = monthState.selectedDate,
                                    zone = zone,
                                )
                                if (at == null) {
                                    showPlanTypePicker = false
                                    scheduleError =
                                        "所选日期已没有可安排的未来时刻，请重新选择日期和时间。"
                                    return@TextButton
                                }
                                scheduleError = null
                                showPlanTypePicker = false
                                onScheduleCare(item.type, at, item.customItemId)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(item.label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPlanTypePicker = false }) { Text("取消") }
            },
        )
    }
    if (showConflictList) {
        AlertDialog(
            onDismissRequest = {
                showConflictList = false
                vm.clearConflictAudits()
                conflictError = null
            },
            title = { Text("冲突未采纳履行") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
                ) {
                    Text(
                        "以下履行未成为该计划的权威事实。若两次护理都实际发生，可转为独立记录。",
                        style = LeziTypography.Meta,
                    )
                    conflictError?.let {
                        Text(it, style = LeziTypography.Meta, color = MaterialTheme.colorScheme.error)
                    }
                    if (conflictAudits.isEmpty()) {
                        Text("暂无冲突未采纳项", style = LeziTypography.Meta)
                    }
                    conflictAudits.forEach { audit ->
                        LeziSurfacePanel(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("conflict_audit_${audit.candidateClientUuid}"),
                            bottomBand = true,
                            onClick = { vm.openConflictDetail(audit.candidateClientUuid) },
                        ) {
                            Text(audit.typeLabel, style = LeziTypography.BodyStrong)
                            Text(
                                "提交者 ${audit.submitterDisplayName}",
                                style = LeziTypography.Meta,
                            )
                            Text(
                                "确认 ${formatCalendarDateTime(audit.confirmedAt, zone)}",
                                style = LeziTypography.Meta,
                            )
                            Text(audit.notAdoptedReason, style = LeziTypography.Meta)
                            if (audit.isConverted) {
                                Text("已转为独立记录", style = LeziTypography.Meta)
                            } else {
                                Text("点此查看详情", style = LeziTypography.Meta)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConflictList = false
                        vm.clearConflictAudits()
                        conflictError = null
                    },
                ) { Text("关闭") }
            },
        )
    }

    conflictDetail?.let { detail ->
        AlertDialog(
            onDismissRequest = { vm.clearConflictDetail() },
            title = { Text("冲突未采纳详情") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState())
                        .testTag("conflict_audit_detail"),
                    verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
                ) {
                    Text("类型 ${detail.typeLabel}", style = LeziTypography.BodyStrong)
                    Text("提交者 ${detail.submitterDisplayName}", style = LeziTypography.Meta)
                    Text(
                        "确认时间 ${formatCalendarDateTime(detail.confirmedAt, zone)}",
                        style = LeziTypography.Meta,
                    )
                    detail.actualTimestamp?.let { actual ->
                        Text(
                            "实际发生 ${formatCalendarDateTime(actual, zone)}",
                            style = LeziTypography.Meta,
                        )
                    }
                    detail.note?.takeIf { it.isNotBlank() }?.let { note ->
                        Text("备注 $note", style = LeziTypography.Meta)
                    }
                    Text(detail.notAdoptedReason, style = LeziTypography.Meta)
                    if (detail.photoLocalPaths.isNotEmpty()) {
                        Text(
                            "照片 ${detail.photoLocalPaths.size} 张 · 点图预览",
                            style = LeziTypography.Meta,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            detail.photoLocalPaths.forEachIndexed { index, path ->
                                val photo by rememberLocalPhoto(path, LocalPhotoTarget.THUMBNAIL)
                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .testTag("conflict_photo_$index")
                                        .clickable {
                                            previewPhotos = detail.photoLocalPaths
                                            previewStartIndex = index
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    when (val result = photo) {
                                        is LocalPhotoLoadResult.Ready -> {
                                            Image(
                                                bitmap = result.value,
                                                contentDescription =
                                                    "冲突未采纳照片 ${index + 1}",
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop,
                                            )
                                        }
                                        LocalPhotoLoadResult.Loading -> Unit
                                        LocalPhotoLoadResult.Unavailable -> {
                                            Text("无法读取", style = LeziTypography.Meta)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (detail.isConverted) {
                        Text("已转为独立护理记录", style = LeziTypography.Meta)
                    }
                    conflictError?.let {
                        Text(it, style = LeziTypography.Meta, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                if (!detail.isConverted) {
                    TextButton(
                        enabled = !conflictBusy,
                        onClick = { confirmConvertCandidate = detail.candidateClientUuid },
                        modifier = Modifier.testTag("conflict_convert_button"),
                    ) { Text("转为独立记录") }
                } else {
                    TextButton(onClick = { vm.clearConflictDetail() }) { Text("关闭") }
                }
            },
            dismissButton = {
                if (!detail.isConverted) {
                    TextButton(onClick = { vm.clearConflictDetail() }) { Text("返回") }
                }
            },
        )
    }

    confirmConvertCandidate?.let { candidateUuid ->
        AlertDialog(
            onDismissRequest = { if (!conflictBusy) confirmConvertCandidate = null },
            title = { Text("确认转为独立记录") },
            text = {
                Text(
                    "将根据未采纳履行内容创建一条新的护理记录，进入时间轴与汇总。" +
                        "原计划的权威履行与冲突审计保持不变。",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !conflictBusy,
                    onClick = {
                        vm.convertConflictToIndependentRecord(candidateUuid) { err ->
                            if (err == null) {
                                confirmConvertCandidate = null
                                conflictError = null
                            } else {
                                conflictError = err
                                confirmConvertCandidate = null
                            }
                        }
                    },
                    modifier = Modifier.testTag("conflict_convert_confirm"),
                ) { Text("确认转换") }
            },
            dismissButton = {
                TextButton(
                    enabled = !conflictBusy,
                    onClick = { confirmConvertCandidate = null },
                ) { Text("取消") }
            },
        )
    }

    previewPhotos?.let { photos ->
        com.lezi.babylog.designsystem.LeziPhotoPreviewDialog(
            photos = photos,
            startIndex = previewStartIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0)),
            onDismiss = { previewPhotos = null },
            contentDescriptionPrefix = "冲突未采纳照片预览",
            showPageCount = false,
        )
    }
}

@Composable
internal fun CalendarEmptyDayState(
    canScheduleSelectedDate: Boolean,
    onSchedule: () -> Unit,
) {
    StateContainer(
        kind = StateKind.Empty,
        title = "这一天还没有安排",
        message = if (canScheduleSelectedDate) {
            "可选择具体记录项目安排护理"
        } else {
            "该日期仅供查看；护理计划只能安排在未来时刻。"
        },
        actionLabel = "安排护理".takeIf { canScheduleSelectedDate },
        onAction = onSchedule.takeIf { canScheduleSelectedDate },
    )
}

@Composable
private fun CalendarMonthPicker(
    state: CalendarMonthState,
    itemCountsByDate: Map<LocalDate, Int>,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
) {
    val cells = remember(state.visibleMonth, state.today) {
        calendarMonthCells(state.visibleMonth, state.today)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(CalendarUiTags.MonthGrid),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                onClick = onPreviousMonth,
                modifier = Modifier
                    .testTag(CalendarUiTags.PreviousMonth)
                    .semantics { contentDescription = "上个月" },
            ) { Text("‹") }
            Text(
                "${state.visibleMonth.year}年${state.visibleMonth.monthValue}月",
                style = LeziTypography.BodyStrong,
            )
            TextButton(
                onClick = onNextMonth,
                modifier = Modifier
                    .testTag(CalendarUiTags.NextMonth)
                    .semantics { contentDescription = "下个月" },
            ) { Text("›") }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { cell ->
                    val selected = cell.date == state.selectedDate
                    val itemCount = itemCountsByDate[cell.date] ?: 0
                    val dateDescription =
                        "${cell.date.year}年${cell.date.monthValue}月${cell.date.dayOfMonth}日"
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = LeziSpacing.Touch)
                            .testTag(CalendarUiTags.day(cell.date))
                            .clickable(enabled = cell.isEnabled) { onSelectDate(cell.date) }
                            .semantics {
                                contentDescription = buildString {
                                    append(dateDescription)
                                    if (cell.isToday) append("，今天")
                                    append("，${itemCount}条安排")
                                }
                                stateDescription = when {
                                    !cell.isEnabled -> "相邻月份，不可选择"
                                    selected -> "已选择"
                                    else -> "未选择"
                                }
                            },
                        shape = MaterialTheme.shapes.small,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            Color.Transparent
                        },
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 3.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                cell.date.dayOfMonth.toString(),
                                style = if (selected) {
                                    LeziTypography.BodyStrong
                                } else {
                                    LeziTypography.Body
                                },
                                color = when {
                                    !cell.isEnabled ->
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                    cell.isToday -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                            Text(
                                if (itemCount > 0) "•" else " ",
                                style = LeziTypography.Meta,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Pure entry policy: completed/history + admin + conflicts → audit sheet. */
internal fun shouldOpenConflictAudit(
    isFamilyAdmin: Boolean,
    effective: CarePlanStatus,
    conflictCount: Int,
): Boolean {
    val open = effective == CarePlanStatus.PENDING || effective == CarePlanStatus.MISSED
    if (open) return false
    return isFamilyAdmin && conflictCount > 0
}

private fun formatCalendarDateTime(timestamp: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(timestamp)
        .atZone(zone)
        .format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))

/**
 * Local device time + status, and original plan-zone clock when zones differ
 * (ticket 14: 展示状态和本地/原计划时间).
 */
internal fun carePlanCalendarMetaLine(
    plan: CarePlan,
    effective: CarePlanStatus,
    deviceZone: ZoneId,
    systemCalendarUnsynced: Boolean = false,
): String {
    val statusLabel = when (effective) {
        CarePlanStatus.PENDING -> "待执行"
        CarePlanStatus.MISSED -> "已错过"
        CarePlanStatus.COMPLETED -> "已完成"
        CarePlanStatus.SKIPPED -> "已跳过"
    }
    val local = formatCalendarDateTime(plan.scheduledAt, deviceZone)
    val planZone = runCatching { ZoneId.of(plan.scheduledZoneId) }.getOrDefault(deviceZone)
    val zoneHint = if (planZone != deviceZone) {
        val original = Instant.ofEpochMilli(plan.scheduledAt)
            .atZone(planZone)
            .format(DateTimeFormatter.ofPattern("HH:mm"))
        " · 原计划 $original (${plan.scheduledZoneId})"
    } else {
        ""
    }
    val unsyncedHint = if (systemCalendarUnsynced) {
        " · $SYSTEM_CALENDAR_UNSYNCED_LABEL"
    } else {
        ""
    }
    return "$local · $statusLabel$zoneHint$unsyncedHint"
}
