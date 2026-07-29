package com.lezi.babylog.feature.log

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.MilkPayload
import com.lezi.babylog.core.model.NursingPayload
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.core.model.SleepPayload
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.core.model.availableForNewEntry
import com.lezi.babylog.core.model.deviceLayoutSnapshot
import com.lezi.babylog.core.model.displayLabel
import com.lezi.babylog.core.ui.RecordSection
import com.lezi.babylog.core.ui.RecordSummaryStrip
import com.lezi.babylog.core.ui.RecordSummaryValue
import com.lezi.babylog.core.ui.RecordTypeIcon
import com.lezi.babylog.core.ui.UiTags
import com.lezi.babylog.core.ui.knownCatalogKeys
import com.lezi.babylog.core.ui.orderedRecordSections
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.core.ui.presentationSummary
import com.lezi.babylog.core.ui.presentationTone
import com.lezi.babylog.core.ui.sortCatalogByLocalOrder
import com.lezi.babylog.designsystem.LeziRecordGlyph
import com.lezi.babylog.designsystem.LeziRecordGlyphIcon
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziThemeExt
import com.lezi.babylog.designsystem.LeziTone
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.PageScaffoldBackground
import com.lezi.babylog.designsystem.RecordRow
import com.lezi.babylog.designsystem.SectionHeading
import com.lezi.babylog.designsystem.StateContainer
import com.lezi.babylog.designsystem.StateKind
import com.lezi.babylog.designsystem.SummaryMetric
import com.lezi.babylog.designsystem.SwipeEditDeleteRow
import com.lezi.babylog.designsystem.TimelineAxis
import com.lezi.babylog.designsystem.TimelineLaneSegment
import com.lezi.babylog.designsystem.TimelineLegendEntry
import com.lezi.babylog.designsystem.TimelineRailCard
import com.lezi.babylog.designsystem.leziRecordColor
import com.lezi.babylog.domain.CareAggregation
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.CustomRecordItem
import com.lezi.babylog.domain.DailySummary
import com.lezi.babylog.domain.DayChartCategories
import com.lezi.babylog.domain.DayChartCategory
import com.lezi.babylog.domain.formatClock
import com.lezi.babylog.domain.relativeTimeLabel
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.UploaderMemberRef
import com.lezi.babylog.sync.localCarePlanPublishDetail
import com.lezi.babylog.sync.localCarePlanPublishLabel
import com.lezi.babylog.sync.localRecordPublishDetail
import com.lezi.babylog.sync.localRecordPublishLabel
import com.lezi.babylog.sync.resolveRecordUploaderLabel
import com.lezi.babylog.sync.toUploaderRef
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LogUiState(
    val loading: Boolean = true,
    val baby: Baby? = null,
    val babies: List<Baby> = emptyList(),
    val day: LocalDate = LocalDate.now(),
    val records: List<Record> = emptyList(),
    val summary: DailySummary = DailySummary(),
    val sleepLanes: List<TimelineLaneSegment> = emptyList(),
    val feedLanes: List<TimelineLaneSegment> = emptyList(),
    val careLanes: List<TimelineLaneSegment> = emptyList(),
    val settings: SettingsLocal = SettingsLocal(),
    val openSleep: Record? = null,
    val refreshing: Boolean = false,
    /** recordId → current 家庭称呼 for non-self writers when family is joined. */
    val uploaderLabels: Map<Long, String> = emptyMap(),
    /** Concrete custom definitions for the more-sheet catalog. */
    val customItems: List<CustomRecordItem> = emptyList(),
    /**
     * Pending/missed care plans for the selected day (today includes overdue).
     * Rendered above the fact record list; never mixed into records/summary.
     */
    val pendingPlans: List<CarePlan> = emptyList(),
    /** Plan ids the current actor may edit/skip/delete (UI ACL). */
    val manageablePlanIds: Set<Long> = emptySet(),
    /**
     * recordId → true when a prior family-complete package exists (media receipt),
     * so mutation chrome says "上一完整版本" rather than first-publish copy.
     */
    val recordPriorFamilyRevision: Map<Long, Boolean> = emptyMap(),
    /**
     * carePlanId → true when a prior family-complete plan package exists,
     * for amber “仅本机” mutation chrome on creator devices.
     */
    val planPriorFamilyRevision: Map<Long, Boolean> = emptyMap(),
    val familyJoined: Boolean = false,
    val lastSyncFailed: Boolean = false,
    /**
     * Whether to render the 72h time bar: true when **any** of D−1 / D / D+1
     * has a day-chart type. Summary / list / legend stay on [records] (day D only).
     */
    val showDayChart: Boolean = false,
)

@HiltViewModel
class LogViewModel @Inject constructor(
    private val careLog: CareLog,
    private val settingsStore: SettingsStore,
    private val syncPort: SyncPort,
) : ViewModel() {
    private val zone = ZoneId.systemDefault()
    private val dayFlow = MutableStateFlow(LocalDate.now(zone))
    private val refreshing = MutableStateFlow(false)
    private val uploaderMembers = MutableStateFlow<List<UploaderMemberRef>>(emptyList())
    private val selfUploaderIdentity = MutableStateFlow(SelfUploaderIdentity())
    private val familyJoined = MutableStateFlow(false)
    private val deviceLayoutWriter = DeviceLayoutSnapshotWriter(viewModelScope) { snapshot ->
        settingsStore.setDeviceLayoutSnapshot(snapshot)
    }
    internal val deviceLayoutWriteState = deviceLayoutWriter.state

    init {
        viewModelScope.launch {
            syncPort.session()
                .map {
                    it.isJoined to SelfUploaderIdentity(
                        membershipId = it.membershipId,
                    )
                }
                .distinctUntilChanged()
                .collect { (joined, identity) ->
                    familyJoined.value = joined
                    selfUploaderIdentity.value = identity
                    if (!joined) {
                        uploaderMembers.value = emptyList()
                    } else {
                        refreshUploaderMembers()
                    }
                }
        }
    }

    private suspend fun refreshUploaderMembers() {
        val result = syncPort.listFamilyMembers()
        uploaderMembers.value = result.getOrNull()
            ?.mapNotNull { it.toUploaderRef() }
            .orEmpty()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState = combine(
        careLog.observeCurrentBaby(),
        careLog.observeBabies(),
        dayFlow,
        settingsStore.settings,
        careLog.observeCustomItems(),
    ) { baby, babies, day, settings, customItems ->
        LogCombine(baby, babies, day, settings, customItems)
    }.flatMapLatest { bundle ->
        val (baby, babies, day, settings, customItems) = bundle
        if (baby == null) {
            flowOf(
                LogUiState(
                    loading = false,
                    babies = babies,
                    day = day,
                    settings = settings,
                    customItems = customItems,
                ),
            )
        } else {
            val today = LocalDate.now(zone)
            val plansFlow = if (day == today) {
                careLog.observeTodayPendingPlans(baby.id, zone)
            } else {
                careLog.observeDayPendingPlans(baby.id, day, zone)
            }
            // List / summary / legend / filter stay on selected day D only.
            val dayRecordsFlow = careLog.observeDayRecords(baby.id, day, zone)
            // Rail marks load D−1..D+1 on the continuous 72h content axis.
            val railRecordsFlow = careLog.observeRecords(
                babyId = baby.id,
                startDayInclusive = day.minusDays(1),
                endDayExclusive = day.plusDays(2),
                zone = zone,
            )
            combine(
                combine(dayRecordsFlow, railRecordsFlow, ::Pair),
                careLog.observeOpenSleep(baby.id),
                plansFlow,
                uploaderMembers,
                selfUploaderIdentity,
            ) { dayAndRail, openSleep, plans, members, selfIdentity ->
                val (records, railRecords) = dayAndRail
                val joined = familyJoined.value
                val window = threeDayContentWindow(day, zone)
                val summary = CareAggregation.day(records, day, zone).toDailySummary()
                val lanes = buildTimelineLanes(
                    records = railRecords,
                    windowStartMs = window.startMs,
                    windowEndMs = window.endMs,
                    zone = zone,
                )
                // Rail visibility uses the three-day union; list/summary stay on D.
                val showDayChart = DayChartCategories.shouldShowDayChart(railRecords)
                val labels = buildUploaderLabels(
                    records = records,
                    isFamilyJoined = joined,
                    members = members,
                    selfMembershipId = selfIdentity.membershipId,
                )
                val priorRevisions = buildMap {
                    if (joined) {
                        for (record in records) {
                            if (record.syncDirty) {
                                put(
                                    record.id,
                                    careLog.recordHasPriorFamilyRevision(record.id),
                                )
                            }
                        }
                    }
                }
                val planPriorRevisions = buildMap {
                    if (joined) {
                        for (plan in plans) {
                            if (plan.syncDirty) {
                                put(
                                    plan.id,
                                    careLog.carePlanHasPriorFamilyRevision(plan.id),
                                )
                            }
                        }
                    }
                }
                val manageablePlans = buildSet {
                    for (plan in plans) {
                        if (careLog.canManageCarePlan(plan)) add(plan.id)
                    }
                }
                LogUiState(
                    loading = false,
                    baby = baby,
                    babies = babies,
                    day = day,
                    records = records,
                    summary = summary,
                    sleepLanes = lanes.sleep,
                    feedLanes = lanes.feed,
                    careLanes = lanes.care,
                    settings = settings,
                    openSleep = openSleep,
                    uploaderLabels = labels,
                    customItems = customItems,
                    pendingPlans = plans,
                    manageablePlanIds = manageablePlans,
                    recordPriorFamilyRevision = priorRevisions,
                    planPriorFamilyRevision = planPriorRevisions,
                    familyJoined = joined,
                    showDayChart = showDayChart,
                )
            }
        }
    }.combine(refreshing) { state, isRefreshing ->
        state.copy(refreshing = isRefreshing)
    }.combine(syncPort.status()) { state, status ->
        state.copy(lastSyncFailed = status == SyncStatus.Error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogUiState())

    fun setExternalDay(day: LocalDate) {
        dayFlow.value = day
    }

    /** Persist a full device-layout snapshot from 布局编辑态. */
    internal fun applyDeviceLayoutPrefs(prefs: DeviceLayoutPrefs) {
        deviceLayoutWriter.submit(prefs.toSnapshot())
    }

    internal fun awaitDeviceLayoutWrites(onDone: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            onDone(deviceLayoutWriter.flush())
        }
    }

    internal fun retryDeviceLayoutWrite(onDone: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            onDone(deviceLayoutWriter.retryLatest())
        }
    }

    fun addCustomItem(name: String, iconSlot: Int, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val result = runCatching { careLog.addCustomItem(name, iconSlot) }
            onDone(result.exceptionOrNull()?.message)
        }
    }

    fun updateCustomItem(item: CustomRecordItem, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val result = runCatching { careLog.updateCustomItem(item) }
            onDone(result.exceptionOrNull()?.message)
        }
    }

    fun deleteCustomItem(id: Long, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val result = runCatching { careLog.deleteCustomItem(id) }
            onDone(result.exceptionOrNull()?.message)
        }
    }

    suspend fun canManageCustomItem(item: CustomRecordItem): Boolean =
        careLog.canManageCustomItem(item)

    fun refresh() {
        if (!refreshing.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                syncPort.sync(SyncTrigger.PullToRefresh)
                if (familyJoined.value) refreshUploaderMembers()
            } finally {
                refreshing.value = false
            }
        }
    }

    fun skipCarePlan(planId: Long) {
        viewModelScope.launch {
            runCatching { careLog.skipCarePlan(planId) }
        }
    }

    /**
     * Soft-delete a care plan from the list swipe path.
     * Same domain entry as Composer delete; [onResult] gets success toast or UI error.
     */
    fun deleteCarePlanFromList(planId: Long, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                careLog.deleteCarePlan(planId)
                "已删除护理计划"
            }.fold(
                onSuccess = { Result.success(it) },
                onFailure = {
                    Result.failure(Exception(productUiError(it, "删除失败，请重试")))
                },
            )
            onResult(result)
        }
    }

    /**
     * Soft-delete a timeline record from the list swipe path.
     * Same domain entry as Composer delete.
     */
    fun deleteRecordFromList(recordId: Long, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                careLog.deleteRecord(recordId)
                "已删除记录"
            }.fold(
                onSuccess = { Result.success(it) },
                onFailure = {
                    Result.failure(Exception(productUiError(it, "删除失败，请重试")))
                },
            )
            onResult(result)
        }
    }
}

/** Pure map of record id → uploader display label (S3 seam for timeline composition). */
internal fun buildUploaderLabels(
    records: List<Record>,
    isFamilyJoined: Boolean,
    members: List<UploaderMemberRef>,
    selfMembershipId: String = "",
): Map<Long, String> {
    if (!isFamilyJoined || records.isEmpty()) return emptyMap()
    val out = LinkedHashMap<Long, String>()
    for (record in records) {
        val label = resolveRecordUploaderLabel(
            isFamilyJoined = true,
            members = members,
            createdByMembershipId = record.createdByMembershipId,
            selfMembershipId = selfMembershipId,
        )
        if (label != null) out[record.id] = label
    }
    return out
}

private data class SelfUploaderIdentity(
    val membershipId: String = "",
)

/** Compose payload summary with optional uploader 称呼 for the secondary line. */
internal fun timelineRecordSummary(
    payloadSummary: String,
    uploaderLabel: String?,
    publishLabel: String? = null,
): String =
    listOfNotNull(
        payloadSummary.takeIf { it.isNotBlank() },
        uploaderLabel?.takeIf { it.isNotBlank() },
        publishLabel?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

private data class LogCombine(
    val baby: Baby?,
    val babies: List<Baby>,
    val day: LocalDate,
    val settings: SettingsLocal,
    val customItems: List<CustomRecordItem>,
)

internal data class TimelineLanes(
    val sleep: List<TimelineLaneSegment>,
    val feed: List<TimelineLaneSegment>,
    val care: List<TimelineLaneSegment>,
)

/** Half-open content window [startMs, endMs) for selected day D: D−1 00:00 .. D+2 00:00. */
internal data class ThreeDayContentWindow(
    val selectedDay: LocalDate,
    val startMs: Long,
    val endMs: Long,
) {
    val contentDurationMinutes: Int
        get() = ((endMs - startMs) / 60_000L).toInt()
}

/** Re-anchors the 72h content axis whenever the date-bar selected day D changes. */
internal fun threeDayContentWindow(
    selectedDay: LocalDate,
    zone: ZoneId,
): ThreeDayContentWindow {
    val start = selectedDay.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val end = selectedDay.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
    return ThreeDayContentWindow(selectedDay = selectedDay, startMs = start, endMs = end)
}

/**
 * Default non-gesture viewport for **non-today** days: primary day D fills most
 * of the canvas with [TimelineAxis.NEIGHBOR_PEEK_MINUTES] of each neighbor on
 * the sides. Today uses [todayThreeDayViewportStartMinutes] instead.
 */
internal fun defaultThreeDayViewportStartMinutes(): Int =
    TimelineAxis.defaultViewportStartMinutes()

internal fun defaultThreeDayViewportDurationMinutes(): Int =
    TimelineAxis.defaultViewportDurationMinutes()

/**
 * Content-axis minute for wall-clock [nowMs] inside [window], or null when
 * now falls outside the 72h window (caller hides the now line).
 */
internal fun nowContentMinuteInWindow(
    nowMs: Long,
    window: ThreeDayContentWindow,
): Int? = TimelineAxis.contentMinuteIfInWindow(
    nowMs = nowMs,
    windowStartMs = window.startMs,
    windowEndMs = window.endMs,
)

/**
 * Initial viewport for **today**: center on [nowContentMinute], clamped so the
 * full span stays inside the 72h content (no blank outside).
 */
internal fun todayThreeDayViewportStartMinutes(
    nowContentMinute: Int,
    viewportDurationMinutes: Int = defaultThreeDayViewportDurationMinutes(),
): Int = TimelineAxis.todayCenteredViewportStartMinutes(
    nowContentMinute = nowContentMinute,
    viewportDurationMinutes = viewportDurationMinutes,
)

/**
 * Day-keyed **initial** viewport for the rail. Today centers on wall-clock now;
 * non-today uses D-primary peeks. Pan mutates a separate UI state that is only
 * reset when [selectedDay] changes (including 「返回今天」) — never every recompose.
 */
internal fun initialThreeDayViewportStartMinutes(
    selectedDay: LocalDate,
    today: LocalDate,
    nowMs: Long,
    zone: ZoneId,
    viewportDurationMinutes: Int = defaultThreeDayViewportDurationMinutes(),
): Int {
    if (selectedDay == today) {
        val window = threeDayContentWindow(selectedDay, zone)
        val nowMin = nowContentMinuteInWindow(nowMs, window)
        if (nowMin != null) {
            return todayThreeDayViewportStartMinutes(nowMin, viewportDurationMinutes)
        }
    }
    return defaultThreeDayViewportStartMinutes()
}

/**
 * Pure pan step for tests/UI: apply finger [deltaPx] to [currentStartMinutes]
 * and clamp inside the 72h content (never mutates selected day D).
 */
internal fun threeDayViewportStartAfterPan(
    currentStartMinutes: Int,
    deltaPx: Float,
    axisLengthPx: Float,
    viewportDurationMinutes: Int = defaultThreeDayViewportDurationMinutes(),
): Int = TimelineAxis.panViewportStart(
    currentStartMinutes = currentStartMinutes,
    deltaPx = deltaPx,
    axisLengthPx = axisLengthPx,
    viewportDurationMinutes = viewportDurationMinutes,
)

/**
 * Build rail segments on the continuous content axis (minutes from [windowStartMs]).
 *
 * Overnight sleep is one unclipped-at-midnight interval clipped only to the
 * 72h window ends. List/summary ownership still uses natural-day aggregation
 * elsewhere — this function only produces geometry.
 */
internal fun buildTimelineLanes(
    records: List<Record>,
    windowStartMs: Long,
    windowEndMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    nowMs: Long = System.currentTimeMillis(),
): TimelineLanes {
    val sleep = mutableListOf<TimelineLaneSegment>()
    val feed = mutableListOf<TimelineLaneSegment>()
    val care = mutableListOf<TimelineLaneSegment>()
    val contentMax = ((windowEndMs - windowStartMs) / 60_000L).toInt().coerceAtLeast(1)

    fun contentMinutes(ms: Long): Int =
        ((ms - windowStartMs) / 60_000L).toInt().coerceIn(0, contentMax)

    fun clock(ms: Long): String = formatClock(ms, zone)

    for (r in records) {
        when (r.type) {
            RecordType.SLEEP -> {
                val open = r.endTimestamp == null
                val rawEnd = r.endTimestamp ?: nowMs
                // Clip only to the 72h window — do not split at midnight.
                val clippedStart = maxOf(r.timestamp, windowStartMs)
                val clippedEnd = minOf(rawEnd, windowEndMs)
                if (clippedEnd > clippedStart) {
                    val startMin = contentMinutes(clippedStart)
                    val endMin = contentMinutes(clippedEnd).coerceAtLeast(startMin + 1)
                    val durationMin = ((rawEnd - r.timestamp) / 60_000L).coerceAtLeast(1)
                    val nap = (r.payload.payload as? SleepPayload)?.isNap == true
                    val title = if (nap) "午睡" else "睡眠"
                    val detail = buildString {
                        append(clock(r.timestamp))
                        append("–")
                        append(if (open) "进行中" else clock(rawEnd))
                        append(" · ")
                        append(formatDurationMinutes(durationMin))
                        if (open) append("（未结束）")
                    }
                    sleep += TimelineLaneSegment(
                        startMinOfDay = startMin,
                        endMinOfDay = endMin,
                        // Color filled at draw from LeziThemeExt (token single-source).
                        color = Color.Unspecified,
                        title = title,
                        detail = detail,
                        isEvent = false,
                        dayChartCategoryKey = DayChartCategory.SLEEP.name,
                    )
                }
            }
            RecordType.FORMULA, RecordType.NURSING, RecordType.PUMPED_FEED,
            RecordType.PUMP_EXPRESS,
            -> {
                if (r.timestamp < windowStartMs || r.timestamp >= windowEndMs) continue
                val startMin = contentMinutes(r.timestamp)
                val title = r.type.presentation.label
                val detail = buildString {
                    append(clock(r.timestamp))
                    when (r.type) {
                        RecordType.FORMULA, RecordType.PUMPED_FEED, RecordType.PUMP_EXPRESS -> {
                            val ml = (r.payload.payload as? MilkPayload)?.amountMl ?: 0
                            if (ml > 0) append(" · ${ml}ml")
                        }
                        RecordType.NURSING -> {
                            val payload = r.payload.payload as? NursingPayload
                            val left = payload?.leftMinutes ?: 0
                            val right = payload?.rightMinutes ?: 0
                            if (left + right > 0) append(" · 左${left}分/右${right}分")
                        }
                        else -> Unit
                    }
                    r.note?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                }
                feed += TimelineLaneSegment(
                    startMinOfDay = startMin,
                    endMinOfDay = startMin,
                    color = Color.Unspecified,
                    title = title,
                    detail = detail,
                    isEvent = true,
                    // 吸奶 draws on the feed rail but is not a day-chart type (not 奶).
                    dayChartCategoryKey = dayChartCategoryKeyForRecordType(r.type),
                )
            }
            RecordType.PEE, RecordType.POOP, RecordType.BOTH_DIAPER, RecordType.BATH,
            RecordType.TEMPERATURE, RecordType.MEDICINE,
            -> {
                if (r.timestamp < windowStartMs || r.timestamp >= windowEndMs) continue
                val startMin = contentMinutes(r.timestamp)
                val notePart = r.note?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                when (r.type) {
                    RecordType.PEE -> care += TimelineLaneSegment(
                        startMinOfDay = startMin,
                        endMinOfDay = startMin,
                        color = Color.Unspecified,
                        title = "尿尿",
                        detail = "${clock(r.timestamp)}$notePart · 护理",
                        isEvent = true,
                        dayChartCategoryKey = DayChartCategory.PEE.name,
                    )
                    RecordType.POOP -> care += TimelineLaneSegment(
                        startMinOfDay = startMin,
                        endMinOfDay = startMin,
                        color = Color.Unspecified,
                        title = "便便",
                        detail = "${clock(r.timestamp)}$notePart · 护理",
                        isEvent = true,
                        dayChartCategoryKey = DayChartCategory.POOP.name,
                    )
                    RecordType.BOTH_DIAPER -> {
                        // Two marks share one record; each mark maps to one day-chart type.
                        care += TimelineLaneSegment(
                            startMinOfDay = startMin,
                            endMinOfDay = startMin,
                            color = Color.Unspecified,
                            title = "尿尿",
                            detail = "${clock(r.timestamp)}$notePart · 尿+便（尿）",
                            isEvent = true,
                            dayChartCategoryKey = DayChartCategory.PEE.name,
                        )
                        care += TimelineLaneSegment(
                            startMinOfDay = startMin,
                            endMinOfDay = startMin,
                            color = Color.Unspecified,
                            title = "便便",
                            detail = "${clock(r.timestamp)}$notePart · 尿+便（便）",
                            isEvent = true,
                            dayChartCategoryKey = DayChartCategory.POOP.name,
                        )
                    }
                    else -> care += TimelineLaneSegment(
                        startMinOfDay = startMin,
                        endMinOfDay = startMin,
                        color = Color.Unspecified,
                        title = r.type.presentation.label,
                        detail = "${clock(r.timestamp)}$notePart · 护理",
                        isEvent = true,
                        dayChartCategoryKey = null,
                    )
                }
            }
            else -> Unit
        }
    }
    return TimelineLanes(sleep, feed, care)
}

/**
 * Opaque key for [TimelineLaneSegment.dayChartCategoryKey], aligned with
 * [DayChartCategory.name]. Non day-chart types (e.g. 吸奶) return null.
 * BOTH_DIAPER is handled as two segments in [buildTimelineLanes], not here.
 */
internal fun dayChartCategoryKeyForRecordType(type: RecordType): String? =
    DayChartCategories.categoriesOf(type).singleOrNull()?.name

/**
 * Map a timeline/legend callback key into page-level [DayChartCategory].
 * The designsystem already applies toggle/clear (same key → null, other key → that key);
 * this only decodes the opaque key string.
 */
internal fun resolveDayChartSelection(selectedKey: String?): DayChartCategory? {
    if (selectedKey == null) return null
    return DayChartCategory.entries.firstOrNull { it.name == selectedKey }
}

/** Summary-strip categories intentionally match the timeline/legend filter. */
internal fun summaryDayChartCategory(type: RecordType): DayChartCategory? = when (type) {
    RecordType.FORMULA, RecordType.PUMPED_FEED -> DayChartCategory.MILK
    RecordType.NURSING -> DayChartCategory.NURSING
    RecordType.SLEEP -> DayChartCategory.SLEEP
    RecordType.PEE -> DayChartCategory.PEE
    RecordType.POOP -> DayChartCategory.POOP
    else -> null
}

internal fun summaryRecordType(category: DayChartCategory?): RecordType? = when (category) {
    DayChartCategory.MILK -> RecordType.FORMULA
    DayChartCategory.NURSING -> RecordType.NURSING
    DayChartCategory.SLEEP -> RecordType.SLEEP
    DayChartCategory.PEE -> RecordType.PEE
    DayChartCategory.POOP -> RecordType.POOP
    null -> null
}

internal fun reduceSummaryDayChartSelection(
    state: DayChartFilterState,
    type: RecordType,
    records: List<Record>,
): DayChartFilterState {
    val category = summaryDayChartCategory(type) ?: return state
    // Toggle off same category; A2 gate lives in Select via day-D records.
    val nextKey = category.name.takeUnless { state.selection == category }
    return reduceDayChartFilter(
        state,
        DayChartFilterAction.Select(nextKey, dayRecords = records),
    )
}

internal data class DayChartFilterContext(
    val babyId: Long?,
    val day: LocalDate,
)

internal data class DayChartFilterState(
    val context: DayChartFilterContext,
    val selection: DayChartCategory? = null,
)

internal sealed interface DayChartFilterAction {
    data class ChangeContext(val context: DayChartFilterContext) : DayChartFilterAction

    /**
     * Proposed filter key after designsystem toggle/clear.
     * [dayRecords] must be selected-day **D** only (A2 gate); never the 72h rail union.
     */
    data class Select(
        val categoryKey: String?,
        val dayRecords: List<Record>,
    ) : DayChartFilterAction

    data class RefreshRecords(val records: List<Record>) : DayChartFilterAction
}

internal fun reduceDayChartFilter(
    state: DayChartFilterState,
    action: DayChartFilterAction,
): DayChartFilterState = when (action) {
    is DayChartFilterAction.ChangeContext -> {
        if (action.context == state.context) state
        else DayChartFilterState(context = action.context)
    }

    is DayChartFilterAction.Select -> {
        val proposed = resolveDayChartSelection(action.categoryKey)
        state.copy(
            selection = DayChartCategories.commitSelection(
                current = state.selection,
                proposed = proposed,
                dayRecords = action.dayRecords,
            ),
        )
    }

    is DayChartFilterAction.RefreshRecords -> {
        state.copy(
            selection = DayChartCategories.reconcileSelection(
                state.selection,
                action.records,
            ),
        )
    }
}

/** Legend swatch colors for day-chart categories (feature owns labels/keys; designsystem stays free of domain). */
internal fun dayChartLegendColor(
    category: DayChartCategory,
    sleep: Color,
    feed: Color,
    care: Color,
    poop: Color,
): Color = when (category) {
    DayChartCategory.SLEEP -> sleep
    DayChartCategory.MILK, DayChartCategory.NURSING -> feed
    DayChartCategory.PEE -> care
    DayChartCategory.POOP -> poop
}

private fun formatDurationMinutes(minutes: Long): String =
    com.lezi.babylog.core.model.formatRecordDuration(minutes)

private data class PublishChromeTarget(
    val recordId: Long,
    val title: String,
    val priorRevision: Boolean,
)

/** List-level delete confirmation target (swipe path; Composer not opened first). */
private sealed interface ListDeleteTarget {
    data class Plan(val plan: CarePlan) : ListDeleteTarget
    data class RecordItem(val record: Record) : ListDeleteTarget
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogRoute(
    onOpenComposer: (RecordComposerRequest) -> Unit,
    onOpenQuickSlotSettings: () -> Unit = {},
    onGoToday: () -> Unit,
    onMessage: (String) -> Unit = {},
    onLayoutEditModeChanged: (Boolean) -> Unit = {},
    externalDay: LocalDate? = null,
    vm: LogViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val layoutWriteState by vm.deviceLayoutWriteState.collectAsStateWithLifecycle()
    var showMore by remember { mutableStateOf(false) }
    var showLayoutEdit by remember { mutableStateOf(false) }
    var showCustomManage by remember { mutableStateOf(false) }
    var layoutPrefs by remember { mutableStateOf<DeviceLayoutPrefs?>(null) }
    var publishChromeRecord by remember { mutableStateOf<PublishChromeTarget?>(null) }
    /** At most one timeline/plan row may stay revealed. */
    var revealedSwipeRowId by remember { mutableStateOf<String?>(null) }
    var listDeleteTarget by remember { mutableStateOf<ListDeleteTarget?>(null) }
    var listDeleteError by remember { mutableStateOf<String?>(null) }
    var listDeleting by remember { mutableStateOf(false) }
    var layoutExitInProgress by remember { mutableStateOf(false) }
    var exitAfterLayoutRetry by remember { mutableStateOf(false) }
    var dismissedLayoutFailure by remember { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()

    fun collapseSwipeRows() {
        revealedSwipeRowId = null
    }

    fun openEditFromSwipe(request: RecordComposerRequest) {
        collapseSwipeRows()
        onOpenComposer(request)
    }

    fun requestListDelete(target: ListDeleteTarget) {
        collapseSwipeRows()
        listDeleteError = null
        listDeleting = false
        listDeleteTarget = target
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling) collapseSwipeRows()
            }
    }
    fun openLayoutEdit() {
        val snapshot = when (val write = layoutWriteState) {
            is DeviceLayoutWriteState.Failed -> write.snapshot
            is DeviceLayoutWriteState.Saving -> write.snapshot
            is DeviceLayoutWriteState.Saved -> state.settings.deviceLayoutSnapshot()
        }
        if (!snapshot.isCurrentVersion) {
            onMessage("布局由更新版本创建，当前版本不会覆盖它")
            return
        }
        layoutPrefs = snapshot.toLayoutPrefs()
        exitAfterLayoutRetry = false
        dismissedLayoutFailure = null
        showLayoutEdit = true
    }
    val dayChartContext = remember(state.baby?.id, state.day) {
        DayChartFilterContext(babyId = state.baby?.id, day = state.day)
    }
    var dayChartFilterState by remember {
        mutableStateOf(DayChartFilterState(context = dayChartContext))
    }
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val ext = LeziThemeExt.colors
    val journal = LeziThemeExt.isJournal
    val contextualDayChartFilterState = remember(dayChartFilterState, dayChartContext) {
        reduceDayChartFilter(
            dayChartFilterState,
            DayChartFilterAction.ChangeContext(dayChartContext),
        )
    }
    // Reconcile after refresh/delete so a vanished category does not stick at 0 rows.
    val reconciledDayChartFilterState = remember(contextualDayChartFilterState, state.records) {
        reduceDayChartFilter(
            contextualDayChartFilterState,
            DayChartFilterAction.RefreshRecords(state.records),
        )
    }
    LaunchedEffect(reconciledDayChartFilterState) {
        if (dayChartFilterState != reconciledDayChartFilterState) {
            dayChartFilterState = reconciledDayChartFilterState
        }
    }
    val dayChartFilter = reconciledDayChartFilterState.selection
    val selectedSummaryType = summaryRecordType(dayChartFilter)
    val selectableSummaryTypes = remember(state.records) {
        DayChartCategories.legendCategories(state.records)
            .mapNotNull(::summaryRecordType)
            .toSet()
    }

    fun selectSummary(type: RecordType) {
        dayChartFilterState = reduceSummaryDayChartSelection(
            reconciledDayChartFilterState,
            type,
            state.records,
        )
    }
    val timelineRecords = if (state.settings.timelineOrder == "oldest_first") {
        state.records.sortedBy(Record::timestamp)
    } else {
        state.records.sortedByDescending(Record::timestamp)
    }
    // Detail list only; day summary above always uses full-day aggregation.
    val filteredTimelineRecords = remember(timelineRecords, dayChartFilter) {
        DayChartCategories.filterRecords(timelineRecords, dayChartFilter)
    }
    val dayChartLegend = remember(state.records, ext.laneSleep, ext.laneFeed, ext.laneCare, ext.sun) {
        DayChartCategories.legendCategories(state.records).map { cat ->
            TimelineLegendEntry(
                key = cat.name,
                label = cat.label,
                color = dayChartLegendColor(cat, ext.laneSleep, ext.laneFeed, ext.laneCare, ext.sun),
                isBar = cat == DayChartCategory.SLEEP,
            )
        }
    }
    fun openComposer(identity: RecordItemIdentity) {
        val babyId = state.baby?.id ?: return
        val type = identity.recordType
        val openSleep = state.openSleep
        val wakingCurrentSleep = type == RecordType.SLEEP && openSleep != null
        val clickedAt = RecordTime.newDraftTimestamp(
            selectedDate = if (wakingCurrentSleep) today else state.day,
            zone = zone,
        )
        val lastAmount = state.records
            .firstOrNull { it.type == type }
            ?.let { (it.payload.payload as? MilkPayload)?.amountMl }
            ?.takeIf { it > 0 }
        onOpenComposer(
            RecordComposerRequest.New(
                babyId = babyId,
                type = type,
                timestamp = clickedAt,
                lastAmountMl = lastAmount,
                historical = state.day != today,
                openSleepId = openSleep?.id.takeIf { wakingCurrentSleep },
                customItemId = (identity as? RecordItemIdentity.Custom)?.customItemId,
            ),
        )
    }

    fun openComposer(type: RecordType) {
        openComposer(RecordItemIdentity.builtIn(type))
    }

    LaunchedEffect(externalDay) {
        if (externalDay != null && externalDay != state.day) {
            vm.setExternalDay(externalDay)
        }
    }

    // Now line: wall-clock absolute time on the 72h content axis; only when
    // now falls inside [D−1 00:00, D+1 24:00). Viewport start is day-keyed UI
    // state: init from today/now-centered or non-today D+peek defaults; pan
    // clamps inside 72h and never mutates D / summary / list. Reset only on
    // selected-day change (including 「返回今天」).
    val threeDayWindow = remember(state.day, zone) { threeDayContentWindow(state.day, zone) }
    val nowMs = System.currentTimeMillis()
    val nowContentMinute = nowContentMinuteInWindow(nowMs, threeDayWindow)
    val timelineViewportDuration = defaultThreeDayViewportDurationMinutes()
    var timelineViewportStart by remember(state.day) {
        mutableIntStateOf(
            initialThreeDayViewportStartMinutes(
                selectedDay = state.day,
                today = today,
                nowMs = System.currentTimeMillis(),
                zone = zone,
                viewportDurationMinutes = timelineViewportDuration,
            ),
        )
    }

    val editingPrefs = layoutPrefs
    val inLayoutEdit = showLayoutEdit && editingPrefs != null
    DisposableEffect(inLayoutEdit) {
        onLayoutEditModeChanged(inLayoutEdit)
        onDispose {
            if (inLayoutEdit) onLayoutEditModeChanged(false)
        }
    }
    fun closeLayoutEditor() {
        showLayoutEdit = false
        layoutPrefs = null
        layoutExitInProgress = false
        exitAfterLayoutRetry = false
        dismissedLayoutFailure = null
    }
    fun requestLayoutExit() {
        if (layoutExitInProgress) return
        layoutExitInProgress = true
        vm.awaitDeviceLayoutWrites { result ->
            layoutExitInProgress = false
            if (result.isSuccess) {
                closeLayoutEditor()
            } else {
                exitAfterLayoutRetry = true
                dismissedLayoutFailure = null
            }
        }
    }
    BackHandler(enabled = inLayoutEdit) {
        requestLayoutExit()
    }

    PageScaffoldBackground {
        Column(Modifier.fillMaxSize().testTag(UiTags.LOG_HOME)) {
            if (inLayoutEdit) {
                val prefs = checkNotNull(editingPrefs)
                val known = remember(state.customItems) {
                    knownCatalogKeys(state.customItems.map { it.id })
                }
                LayoutEditCanvas(
                    prefs = prefs,
                    customItems = state.customItems,
                    onIntent = { intent ->
                        val current = layoutPrefs ?: return@LayoutEditCanvas
                        val next = reduceLayoutEdit(current, intent, known)
                        if (next != current) {
                            layoutPrefs = next
                            vm.applyDeviceLayoutPrefs(next)
                        }
                    },
                    onDone = ::requestLayoutExit,
                    onOpenCustomManage = { showCustomManage = true },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            } else {
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.weight(1f),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = LeziSpacing.Md),
                    verticalArrangement = Arrangement.spacedBy(if (journal) 0.dp else LeziSpacing.SectionGap),
                ) {
                    item {
                        Column(
                            Modifier.padding(
                                horizontal = if (journal) 0.dp else LeziSpacing.Page,
                            ),
                        ) {
                            if (journal) {
                                RecordSummaryStrip(
                                    values = listOf(
                                        RecordSummaryValue(RecordType.FORMULA, "${state.summary.feedMl}", "奶ml"),
                                        RecordSummaryValue(RecordType.NURSING, "${state.summary.nursingMinutes}m", "母乳"),
                                        RecordSummaryValue(
                                            RecordType.SLEEP,
                                            formatMinutes(state.summary.sleepMinutes),
                                            "睡眠",
                                        ),
                                        RecordSummaryValue(RecordType.PEE, "${state.summary.peeCount}", "尿"),
                                        RecordSummaryValue(RecordType.POOP, "${state.summary.poopCount}", "便"),
                                    ),
                                    selectedType = selectedSummaryType,
                                    selectableTypes = selectableSummaryTypes,
                                    onSelect = ::selectSummary,
                                )
                            } else {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    SummaryMetric(
                                        value = "${state.summary.feedMl}ml",
                                        label = "奶量",
                                        tone = LeziTone.Blue,
                                        modifier = Modifier.weight(1f),
                                        icon = {
                                            RecordTypeIcon(RecordType.FORMULA)
                                        },
                                        selected = selectedSummaryType == RecordType.FORMULA,
                                        selectionLabel = "奶量 ${state.summary.feedMl}毫升，${if (selectedSummaryType == RecordType.FORMULA) "已筛选" else "点按筛选"}",
                                        onClick = if (RecordType.FORMULA in selectableSummaryTypes) {
                                            { selectSummary(RecordType.FORMULA) }
                                        } else null,
                                    )
                                    SummaryMetric(
                                        value = "${state.summary.nursingMinutes}min",
                                        label = "母乳",
                                        tone = LeziTone.Blue,
                                        modifier = Modifier.weight(1f),
                                        icon = {
                                            RecordTypeIcon(RecordType.NURSING)
                                        },
                                        selected = selectedSummaryType == RecordType.NURSING,
                                        selectionLabel = "母乳 ${state.summary.nursingMinutes}分钟，${if (selectedSummaryType == RecordType.NURSING) "已筛选" else "点按筛选"}",
                                        onClick = if (RecordType.NURSING in selectableSummaryTypes) {
                                            { selectSummary(RecordType.NURSING) }
                                        } else null,
                                    )
                                    SummaryMetric(
                                        value = formatMinutes(state.summary.sleepMinutes),
                                        label = "睡眠",
                                        tone = LeziTone.Yellow,
                                        modifier = Modifier.weight(1f),
                                        icon = {
                                            RecordTypeIcon(RecordType.SLEEP)
                                        },
                                        selected = selectedSummaryType == RecordType.SLEEP,
                                        selectionLabel = "睡眠 ${formatMinutes(state.summary.sleepMinutes)}，${if (selectedSummaryType == RecordType.SLEEP) "已筛选" else "点按筛选"}",
                                        onClick = if (RecordType.SLEEP in selectableSummaryTypes) {
                                            { selectSummary(RecordType.SLEEP) }
                                        } else null,
                                    )
                                    SummaryMetric(
                                        value = "${state.summary.peeCount}次",
                                        label = "尿尿",
                                        tone = LeziTone.Cream,
                                        modifier = Modifier.weight(1f),
                                        icon = {
                                            RecordTypeIcon(RecordType.PEE)
                                        },
                                        selected = selectedSummaryType == RecordType.PEE,
                                        selectionLabel = "尿尿 ${state.summary.peeCount}次，${if (selectedSummaryType == RecordType.PEE) "已筛选" else "点按筛选"}",
                                        onClick = if (RecordType.PEE in selectableSummaryTypes) {
                                            { selectSummary(RecordType.PEE) }
                                        } else null,
                                    )
                                    SummaryMetric(
                                        value = "${state.summary.poopCount}次",
                                        label = "便便",
                                        tone = LeziTone.Neutral,
                                        modifier = Modifier.weight(1f),
                                        icon = {
                                            RecordTypeIcon(RecordType.POOP)
                                        },
                                        selected = selectedSummaryType == RecordType.POOP,
                                        selectionLabel = "便便 ${state.summary.poopCount}次，${if (selectedSummaryType == RecordType.POOP) "已筛选" else "点按筛选"}",
                                        onClick = if (RecordType.POOP in selectableSummaryTypes) {
                                            { selectSummary(RecordType.POOP) }
                                        } else null,
                                    )
                                }
                            }
                        }
                    }

                    // Show when any of D−1 / D / D+1 has a day-chart type (ViewModel
                    // already gated on the railRecords union). List/summary stay on D.
                    // Visibility uses DayChartCategories, not buildLanes emptiness.
                    if (state.showDayChart) {
                        item {
                            TimelineRailCard(
                                sleep = state.sleepLanes.map { it.copy(color = ext.laneSleep) },
                                feed = state.feedLanes.map { it.copy(color = ext.laneFeed) },
                                // Preserve distinct pee and poop colors instead of one care-lane color.
                                care = state.careLanes.map { seg ->
                                    seg.copy(
                                        color = when (seg.dayChartCategoryKey) {
                                            DayChartCategory.POOP.name -> ext.sun
                                            DayChartCategory.PEE.name -> ext.laneCare
                                            else -> when (seg.title) {
                                                "便便" -> ext.sun
                                                "尿尿" -> ext.laneCare
                                                else -> ext.laneCare.copy(alpha = 0.75f)
                                            }
                                        },
                                    )
                                },
                                recordCount = state.records.size,
                                nowContentMinute = nowContentMinute,
                                selectedCategoryKey = dayChartFilter?.name,
                                onCategorySelect = { key ->
                                    // A2: gate on day-D records only; rail marks stay 72h union.
                                    // Once committed, selectedCategoryKey highlights matching
                                    // marks across D−1|D|D+1 without re-drawing the rail.
                                    dayChartFilterState = reduceDayChartFilter(
                                        reconciledDayChartFilterState,
                                        DayChartFilterAction.Select(
                                            categoryKey = key,
                                            dayRecords = state.records,
                                        ),
                                    )
                                },
                                legend = dayChartLegend,
                                viewportStartMinutes = timelineViewportStart,
                                viewportDurationMinutes = timelineViewportDuration,
                                onViewportStartChange = { timelineViewportStart = it },
                                titleSecondary = "时间轴",
                                modifier = Modifier.padding(
                                    horizontal = if (journal) 0.dp else LeziSpacing.Page,
                                ),
                            )
                        }
                    }

                    if (state.day != today) {
                        item {
                            Row(Modifier.padding(horizontal = LeziSpacing.Page)) {
                                LeziSecondaryButton(
                                    "返回今天",
                                    onClick = onGoToday,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }

                    if (state.pendingPlans.isNotEmpty()) {
                        item {
                            Column(
                                Modifier.padding(
                                    horizontal = if (journal) 0.dp else LeziSpacing.Page,
                                ),
                            ) {
                                SectionHeading(
                                    eyebrow = if (journal) null else "待履行",
                                    title = "护理计划",
                                    meta = "${state.pendingPlans.size}",
                                )
                            }
                        }
                        items(state.pendingPlans, key = { "plan-${it.id}" }) { plan ->
                            val now = RecordTime.currentTimeMillis()
                            val effective = plan.effectiveStatus(now)
                            val isMissed = effective == CarePlanStatus.MISSED
                            val title = plan.displayLabel()
                            val deviceZone = ZoneId.systemDefault()
                            val planZone = runCatching { ZoneId.of(plan.scheduledZoneId) }
                                .getOrDefault(deviceZone)
                            val zoneHint = if (planZone != deviceZone) {
                                val original = Instant.ofEpochMilli(plan.scheduledAt)
                                    .atZone(planZone)
                                    .toLocalTime()
                                    .toString()
                                " · 原计划 $original (${plan.scheduledZoneId})"
                            } else {
                                ""
                            }
                            val canManagePlan = plan.id in state.manageablePlanIds
                            val planPrior = state.planPriorFamilyRevision[plan.id] == true
                            val planPublishLabel = localCarePlanPublishLabel(
                                syncDirty = plan.syncDirty,
                                familyJoined = state.familyJoined,
                                lastSyncFailed = state.lastSyncFailed,
                                hasPriorFamilyRevision = planPrior,
                            )
                            val statusLine =
                                (if (isMissed) "已错过 · 点此完成" else "待执行 · 点此完成") + zoneHint
                            val planSummary = if (planPublishLabel != null) {
                                "$statusLine · $planPublishLabel"
                            } else {
                                statusLine
                            }
                            val planRowId = "plan-${plan.id}"
                            val planRevealed = revealedSwipeRowId == planRowId
                            Column(
                                Modifier
                                    .padding(
                                        horizontal = if (journal) 0.dp else LeziSpacing.Page,
                                    )
                                    .testTag("pending_care_plan_${plan.id}"),
                            ) {
                                SwipeEditDeleteRow(
                                    open = planRevealed,
                                    onOpenChange = { open ->
                                        revealedSwipeRowId = if (open) planRowId else {
                                            revealedSwipeRowId.takeUnless { it == planRowId }
                                        }
                                    },
                                    editEnabled = canManagePlan,
                                    deleteEnabled = canManagePlan,
                                    onEdit = {
                                        openEditFromSwipe(
                                            RecordComposerRequest.EditPlan(plan.id),
                                        )
                                    },
                                    onDelete = {
                                        requestListDelete(ListDeleteTarget.Plan(plan))
                                    },
                                    editTestTag = "timeline_swipe_edit_plan_${plan.id}",
                                    deleteTestTag = "timeline_swipe_delete_plan_${plan.id}",
                                ) {
                                    RecordRow(
                                        time = formatClock(plan.scheduledAt),
                                        title = title,
                                        summary = planSummary,
                                        relative = relativeTimeLabel(plan.scheduledAt),
                                        // Amber (Yellow) for missed — never danger-red anomaly bang.
                                        // Dirty publish chrome also uses amber via Yellow tone when missed;
                                        // first-publish waiting still Blue for pending-to-do emphasis.
                                        tone = if (isMissed || planPublishLabel != null) {
                                            LeziTone.Yellow
                                        } else {
                                            LeziTone.Blue
                                        },
                                        anomaly = false,
                                        leading = {
                                            RecordTypeIcon(plan.type)
                                        },
                                        onClick = {
                                            if (planRevealed) {
                                                collapseSwipeRows()
                                            } else {
                                                // Collapse any other open swipe row before fulfill.
                                                collapseSwipeRows()
                                                onOpenComposer(
                                                    RecordComposerRequest.Fulfill(plan.id),
                                                )
                                            }
                                        },
                                        modifier = Modifier.semantics {
                                            val publishDetail = if (planPublishLabel != null) {
                                                "。" + localCarePlanPublishDetail(
                                                    lastSyncFailed = state.lastSyncFailed,
                                                    hasPriorFamilyRevision = planPrior,
                                                )
                                            } else {
                                                ""
                                            }
                                            contentDescription =
                                                "完成${title}护理计划$publishDetail"
                                        },
                                    )
                                }
                                if (canManagePlan) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                    ) {
                                        // 编辑 is left-swipe only (absolute screen direction).
                                        TextButton(
                                            onClick = { vm.skipCarePlan(plan.id) },
                                            modifier = Modifier.testTag(
                                                "care_plan_skip_${plan.id}",
                                            ),
                                        ) { Text("跳过") }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Column(
                            Modifier.padding(
                                horizontal = if (journal) 0.dp else LeziSpacing.Page,
                            ),
                        ) {
                            SectionHeading(
                                title = "记录",
                            )
                        }
                    }

                    when {
                        state.loading -> item {
                            StateContainer(
                                kind = StateKind.Loading,
                                title = "加载中",
                                message = "正在读取当日记录…",
                                modifier = Modifier.padding(
                                    horizontal = if (journal) 0.dp else LeziSpacing.Page,
                                ),
                            )
                        }
                        state.records.isEmpty() -> item {
                            StateContainer(
                                kind = StateKind.Empty,
                                title = "还没有记录",
                                message = "点下方快捷入口添加第一条记录",
                                modifier = Modifier.padding(
                                    horizontal = if (journal) 0.dp else LeziSpacing.Page,
                                ),
                            )
                        }
                        else -> items(filteredTimelineRecords, key = { it.id }) { r ->
                            val title = r.displayLabel()
                            val priorRevision =
                                state.recordPriorFamilyRevision[r.id] == true
                            val publishLabel = localRecordPublishLabel(
                                syncDirty = r.syncDirty,
                                familyJoined = state.familyJoined,
                                lastSyncFailed = state.lastSyncFailed,
                                hasPriorFamilyRevision = priorRevision,
                            )
                            val recordRowId = "record-${r.id}"
                            val recordRevealed = revealedSwipeRowId == recordRowId
                            SwipeEditDeleteRow(
                                open = recordRevealed,
                                onOpenChange = { open ->
                                    revealedSwipeRowId = if (open) recordRowId else {
                                        revealedSwipeRowId.takeUnless { it == recordRowId }
                                    }
                                },
                                onEdit = {
                                    // Amber publish chrome: tap prefers sync sheet; left-swipe still edits.
                                    openEditFromSwipe(RecordComposerRequest.Edit(r.id))
                                },
                                onDelete = {
                                    requestListDelete(ListDeleteTarget.RecordItem(r))
                                },
                                editTestTag = "timeline_swipe_edit_record_${r.id}",
                                deleteTestTag = "timeline_swipe_delete_record_${r.id}",
                                modifier = Modifier.padding(
                                    horizontal = if (journal) 0.dp else LeziSpacing.Page,
                                ),
                            ) {
                                RecordRow(
                                    time = formatClock(r.timestamp),
                                    title = title,
                                    summary = timelineRecordSummary(
                                        recordSummaryLine(r),
                                        state.uploaderLabels[r.id],
                                        publishLabel,
                                    ),
                                    relative = relativeTimeLabel(r.timestamp),
                                    tone = toneOf(r.type),
                                    anomaly =
                                        (r.payload.payload as? SleepPayload)?.anomaly == true ||
                                            (r.type == RecordType.SLEEP && r.endTimestamp == null),
                                    leading = {
                                        RecordTypeIcon(r.type)
                                    },
                                    onClick = {
                                        if (recordRevealed) {
                                            collapseSwipeRows()
                                        } else if (publishLabel != null) {
                                            collapseSwipeRows()
                                            publishChromeRecord = PublishChromeTarget(
                                                recordId = r.id,
                                                title = title,
                                                priorRevision = priorRevision,
                                            )
                                        } else {
                                            collapseSwipeRows()
                                            onOpenComposer(RecordComposerRequest.Edit(r.id))
                                        }
                                    },
                                    modifier = Modifier.semantics {
                                        contentDescription = if (publishLabel != null) {
                                            "同步状态$title"
                                        } else {
                                            "编辑$title"
                                        }
                                    },
                                )
                            }
                        }
                    }

                }
            }
            OneHandQuickDock(
                preferredHand = state.settings.preferredHand,
                storedSlots = state.settings.quickRecordSlots,
                hiddenTypeKeys = state.settings.hiddenItems,
                customItems = state.customItems,
                sleepRunning = state.openSleep != null,
                onBound = { identity -> openComposer(identity) },
                onEmpty = { /* empty short-press is no-op; long-press opens layout edit */ },
                onMore = { showMore = true },
                onLongPress = { openLayoutEdit() },
            )
            } // end everyday (non-layout-edit) branch
        }
    }

    if (showCustomManage) {
        LayoutCustomManageDialog(
            items = state.customItems,
            onDismiss = { showCustomManage = false },
            onAdd = { name, icon, done -> vm.addCustomItem(name, icon, done) },
            onUpdate = { item, done -> vm.updateCustomItem(item, done) },
            onDelete = { id, done -> vm.deleteCustomItem(id, done) },
        )
    }

    val layoutFailure = layoutWriteState as? DeviceLayoutWriteState.Failed
    if (
        inLayoutEdit &&
        layoutFailure != null &&
        layoutFailure.sequence != dismissedLayoutFailure
    ) {
        AlertDialog(
            onDismissRequest = {
                dismissedLayoutFailure = layoutFailure.sequence
                exitAfterLayoutRetry = false
            },
            title = { Text("布局尚未保存") },
            text = { Text("上一项布局更改保存失败。当前页面仍保留更改，可重试后再退出。") },
            confirmButton = {
                TextButton(
                    enabled = !layoutExitInProgress,
                    onClick = {
                        layoutExitInProgress = true
                        vm.retryDeviceLayoutWrite { result ->
                            layoutExitInProgress = false
                            if (result.isSuccess && exitAfterLayoutRetry) {
                                closeLayoutEditor()
                            }
                        }
                    },
                ) { Text(if (layoutExitInProgress) "重试中…" else "重试") }
            },
            dismissButton = {
                TextButton(
                    enabled = !layoutExitInProgress,
                    onClick = {
                        dismissedLayoutFailure = layoutFailure.sequence
                        exitAfterLayoutRetry = false
                    },
                ) { Text("继续编辑") }
            },
        )
    }

    // Keep parameter referenced so nav call sites still compile during migration.
    @Suppress("UNUSED_EXPRESSION")
    onOpenQuickSlotSettings

    publishChromeRecord?.let { target ->
        AlertDialog(
            onDismissRequest = { publishChromeRecord = null },
            title = { Text(target.title) },
            text = {
                Text(
                    localRecordPublishDetail(
                        lastSyncFailed = state.lastSyncFailed,
                        hasPriorFamilyRevision = target.priorRevision,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        publishChromeRecord = null
                        vm.refresh()
                    },
                ) { Text("重试同步") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            val id = target.recordId
                            publishChromeRecord = null
                            onOpenComposer(RecordComposerRequest.Edit(id))
                        },
                    ) { Text("编辑") }
                    TextButton(onClick = { publishChromeRecord = null }) { Text("关闭") }
                }
            },
        )
    }

    listDeleteTarget?.let { target ->
        val planConfirmation = (target as? ListDeleteTarget.Plan)?.let {
            carePlanDeleteConfirmation(it.plan)
        }
        AlertDialog(
            onDismissRequest = {
                if (!listDeleting) {
                    listDeleteTarget = null
                    listDeleteError = null
                    collapseSwipeRows()
                }
            },
            title = {
                Text(planConfirmation?.title ?: RECORD_DELETE_TITLE)
            },
            text = {
                Text(
                    deleteConfirmationMessage(
                        impact = planConfirmation?.message ?: RECORD_DELETE_IMPACT,
                        error = listDeleteError,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !listDeleting,
                    onClick = {
                        listDeleting = true
                        listDeleteError = null
                        when (target) {
                            is ListDeleteTarget.Plan -> vm.deleteCarePlanFromList(target.plan.id) { result ->
                                listDeleting = false
                                result.fold(
                                    onSuccess = { message ->
                                        listDeleteTarget = null
                                        listDeleteError = null
                                        onMessage(message)
                                    },
                                    onFailure = { err ->
                                        listDeleteError = err.message ?: "删除失败，请重试"
                                    },
                                )
                            }
                            is ListDeleteTarget.RecordItem -> vm.deleteRecordFromList(target.record.id) { result ->
                                listDeleting = false
                                result.fold(
                                    onSuccess = { message ->
                                        listDeleteTarget = null
                                        listDeleteError = null
                                        onMessage(message)
                                    },
                                    onFailure = { err ->
                                        listDeleteError = err.message ?: "删除失败，请重试"
                                    },
                                )
                            }
                        }
                    },
                ) {
                    Text(
                        if (listDeleting) "删除中…" else "确认删除",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !listDeleting,
                    onClick = {
                        listDeleteTarget = null
                        listDeleteError = null
                        collapseSwipeRows()
                    },
                ) { Text("取消") }
            },
        )
    }

    if (showMore) {
        ModalBottomSheet(
            onDismissRequest = { showMore = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        ) {
            MoreSheet(
                settings = state.settings,
                customItems = state.customItems,
                onPick = { identity ->
                    showMore = false
                    openComposer(identity)
                },
                onLongPressItem = {
                    showMore = false
                    openLayoutEdit()
                },
            )
        }
    }
}

private val CustomSlotIcons = com.lezi.babylog.core.ui.CUSTOM_ITEM_ICON_GLYPHS

/**
 * Always renders four configurable slots plus fixed "更多" (absolute LTR order).
 * Hidden / deleted / invalid refs blank a cell rather than removing it.
 * Long-press enters 布局编辑态.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OneHandQuickDock(
    preferredHand: String,
    storedSlots: List<String>,
    hiddenTypeKeys: Set<String>,
    customItems: List<CustomRecordItem>,
    sleepRunning: Boolean,
    onBound: (RecordItemIdentity) -> Unit,
    onEmpty: () -> Unit,
    onMore: () -> Unit,
    onLongPress: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val journal = LeziThemeExt.isJournal
    val resolved = remember(storedSlots, hiddenTypeKeys, customItems) {
        resolveQuickSlots(storedSlots, hiddenTypeKeys, customItems)
    }
    val cells = remember(preferredHand, resolved) {
        oneHandQuickDockOrder(preferredHand, resolved)
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (journal) 0.dp else QuickDockVisualSpec.outerHorizontalWarm,
                vertical = QuickDockVisualSpec.outerVertical,
            )
            .testTag("one_hand_quick_dock_fixed"),
        shape = LeziThemeExt.dockShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
        shadowElevation = LeziThemeExt.dockElevation,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = QuickDockVisualSpec.rowHorizontal,
                    vertical = QuickDockVisualSpec.rowVertical,
                ),
            horizontalArrangement = Arrangement.spacedBy(QuickDockVisualSpec.cellSpacing),
        ) {
            cells.forEachIndexed { index, cell ->
                val presentation = quickDockPresentation(cell, sleepRunning)
                val tint = when (cell) {
                    is QuickDockCell.Bound ->
                        if (cell.recordType == RecordType.CUSTOM) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            leziRecordColor(cell.recordType.presentation.colorRole)
                        }
                    QuickDockCell.Empty -> MaterialTheme.colorScheme.onSurfaceVariant
                    QuickDockCell.More -> MaterialTheme.colorScheme.primary
                }
                val tag = when (cell) {
                    is QuickDockCell.Bound -> "one_hand_action_${cell.catalogKey}"
                    QuickDockCell.Empty -> "one_hand_action_empty_$index"
                    QuickDockCell.More -> "one_hand_action_more"
                }
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = QuickDockVisualSpec.cellMinHeight)
                        .testTag(tag)
                        .combinedClickable(
                            onClick = {
                                when (val action = cell.toAction()) {
                                    is QuickDockAction.OpenComposer -> onBound(action.identity)
                                    QuickDockAction.None -> onEmpty()
                                    QuickDockAction.OpenMore -> onMore()
                                }
                            },
                            onLongClick = {
                                // Long-press any dock cell (including empty/more) opens layout edit.
                                if (cell !is QuickDockCell.More) onLongPress()
                            },
                        ),
                    shape = LeziThemeExt.controlShape,
                    color = if (quickDockIdleContainerIsEmphasized(cell)) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                    } else {
                        Color.Transparent
                    },
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp)
                            .clearAndSetSemantics {
                                contentDescription = presentation.contentDescription
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            Modifier
                                .size(QuickDockVisualSpec.iconSize)
                                .clip(CircleShape)
                                .background(tint.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            when (cell) {
                                is QuickDockCell.Bound -> {
                                    if (cell.recordType == RecordType.CUSTOM) {
                                        Text(
                                            CustomSlotIcons[
                                                (cell.customIconSlot ?: 0).coerceIn(0, 7),
                                            ],
                                            style = LeziTypography.Meta,
                                            color = tint,
                                        )
                                    } else {
                                        RecordTypeIcon(cell.recordType, tint = tint)
                                    }
                                }
                                QuickDockCell.Empty -> {
                                    Text("＋", style = LeziTypography.BodyStrong, color = tint)
                                }
                                QuickDockCell.More -> {
                                    LeziRecordGlyphIcon(
                                        glyph = LeziRecordGlyph.Other,
                                        tint = tint,
                                    )
                                }
                            }
                        }
                        Text(
                            presentation.visualLabel,
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Catalog entry for the more sheet: built-in type or a concrete custom definition. */
internal sealed class MoreCatalogEntry {
    abstract val identity: RecordItemIdentity
    abstract val label: String
    abstract val section: RecordSection

    data class BuiltIn(
        val type: RecordType,
    ) : MoreCatalogEntry() {
        override val identity: RecordItemIdentity = RecordItemIdentity.builtIn(type)
        override val label: String get() = type.presentation.label
        override val section: RecordSection get() = type.presentation.section
    }

    data class Custom(
        val item: CustomRecordItem,
    ) : MoreCatalogEntry() {
        override val identity: RecordItemIdentity = RecordItemIdentity.custom(item.id)
        override val label: String get() = item.name
        override val section: RecordSection = RecordSection.Custom
    }
}

/**
 * Pure catalog builder: built-ins available for new entry + concrete custom items.
 * Memo / other / bare custom never appear. Order uses shared local category/item policy.
 */
internal fun moreSheetCatalog(
    settings: SettingsLocal,
    customItems: List<CustomRecordItem>,
): List<MoreCatalogEntry> {
    val builtIns = RecordType.availableForNewEntry()
        .filter { it.key !in settings.hiddenItems }
        .map { MoreCatalogEntry.BuiltIn(it) }
    val customs = customItems
        .filter { RecordItemIdentity.customCatalogKey(it.id) !in settings.hiddenItems }
        .map { MoreCatalogEntry.Custom(it) }
    return sortCatalogByLocalOrder(
        entries = builtIns + customs,
        sectionOf = { it.section },
        catalogKeyOf = { it.identity.catalogKey },
        categoryOrderJson = settings.categoryOrderJson,
        itemOrderJson = settings.itemOrderJson,
    )
}

internal fun moreSheetQuickSuggestions(
    settings: SettingsLocal,
    customItems: List<CustomRecordItem>,
): List<MoreCatalogEntry> {
    val preferred = listOf(
        RecordType.POOP,
        RecordType.TEMPERATURE,
        RecordType.WEIGHT,
        RecordType.DIARY,
    )
    val catalog = moreSheetCatalog(settings, customItems)
    val preferredEntries = preferred.mapNotNull { type ->
        catalog.firstOrNull { it is MoreCatalogEntry.BuiltIn && it.type == type }
    }
    return preferredEntries.ifEmpty { catalog.take(4) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MoreSheet(
    settings: SettingsLocal,
    customItems: List<CustomRecordItem>,
    onPick: (RecordItemIdentity) -> Unit,
    onLongPressItem: () -> Unit = {},
) {
    val catalog = remember(
        settings.itemOrderJson,
        settings.categoryOrderJson,
        settings.hiddenItems,
        customItems,
    ) {
        moreSheetCatalog(settings, customItems)
    }
    val suggestions = remember(
        settings.itemOrderJson,
        settings.categoryOrderJson,
        settings.hiddenItems,
        customItems,
    ) {
        moreSheetQuickSuggestions(settings, customItems)
    }
    // Everyday more: only non-empty sections with visible items; pure tap-to-log.
    val groups = orderedRecordSections(settings.categoryOrderJson).map { section ->
        section to catalog.filter { it.section == section }
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("more_sheet_catalog"),
        contentPadding = PaddingValues(
            start = LeziSpacing.Md,
            top = LeziSpacing.Md,
            end = LeziSpacing.Md,
            bottom = LeziSpacing.Xxl,
        ),
    ) {
        item {
            Text("添加记录", style = LeziTypography.Title)
            Spacer(Modifier.height(LeziSpacing.Sm))
            Text(
                "常用补充",
                style = LeziTypography.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(LeziSpacing.Xs))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(
                    RecordCatalogVisualSpec.columnSpacing,
                ),
            ) {
                suggestions.forEach { entry ->
                    MoreCatalogCard(
                        entry = entry,
                        onClick = { onPick(entry.identity) },
                        onLongClick = onLongPressItem,
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(
                    (RecordCatalogVisualSpec.columnCount - suggestions.size).coerceAtLeast(0),
                ) {
                    Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(LeziSpacing.Md))
        }
        groups.forEach { (section, items) ->
            if (items.isEmpty()) return@forEach
            item {
                Column {
                    RecordCatalogSectionHeading(section.title)
                    Spacer(Modifier.height(LeziSpacing.Xs))
                    recordCatalogRows(items).forEach { rowItems ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(
                                RecordCatalogVisualSpec.columnSpacing,
                            ),
                        ) {
                            rowItems.forEach { entry ->
                                MoreCatalogCard(
                                    entry = entry,
                                    onClick = { onPick(entry.identity) },
                                    onLongClick = onLongPressItem,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(RecordCatalogVisualSpec.columnCount - rowItems.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                        Spacer(Modifier.height(RecordCatalogVisualSpec.rowSpacing))
                    }
                    Spacer(Modifier.height(LeziSpacing.Xxs))
                }
            }
        }
    }
}

@Composable
private fun MoreCatalogCard(
    entry: MoreCatalogEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val recordType = when (entry) {
        is MoreCatalogEntry.BuiltIn -> entry.type
        is MoreCatalogEntry.Custom -> RecordType.CUSTOM
    }
    val customIconSlot = (entry as? MoreCatalogEntry.Custom)?.item?.iconSlot
    val colorRole = when (entry) {
        is MoreCatalogEntry.BuiltIn -> entry.type.presentation.colorRole
        is MoreCatalogEntry.Custom -> RecordType.CUSTOM.presentation.colorRole
    }
    RecordCatalogCard(
        label = entry.label,
        recordType = recordType,
        customIconSlot = customIconSlot,
        colorRole = colorRole,
        contentDescription = moreRecordContentDescription(entry),
        modifier = modifier,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

private fun formatMinutes(min: Long): String {
    if (min <= 0) return "0m"
    val h = min / 60
    val m = min % 60
    return if (h == 0L) "${m}m" else if (m == 0L) "${h}h" else "${h}h ${m}m"
}

private fun toneOf(type: RecordType): LeziTone = type.presentationTone()

internal fun moreRecordContentDescription(entry: MoreCatalogEntry): String =
    "添加${entry.label}"

internal fun recordSummaryLine(record: Record): String = record.presentationSummary()
