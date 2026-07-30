package com.lezi.babylog.feature.log

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import com.lezi.babylog.core.model.RootPublicationState
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
import com.lezi.babylog.designsystem.LeziRecordColorRole
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
import com.lezi.babylog.domain.TimelineCarePlanRow
import com.lezi.babylog.domain.TimelineRecordRow
import com.lezi.babylog.domain.TimelineWindowRepository
import com.lezi.babylog.domain.TimelineWindowRequest
import com.lezi.babylog.domain.formatClock
import com.lezi.babylog.domain.relativeTimeLabel
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.localCarePlanPublishDetail
import com.lezi.babylog.sync.localCarePlanPublishLabel
import com.lezi.babylog.sync.localRecordPublishDetail
import com.lezi.babylog.sync.localRecordPublishLabel
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LogUiState(
    val loading: Boolean = true,
    val baby: Baby? = null,
    val babies: List<Baby> = emptyList(),
    val day: LocalDate,
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
    /** One-revision metadata backing every record row action and publication label. */
    val recordMetadata: Map<Long, TimelineRecordRow> = emptyMap(),
    /** One-revision metadata backing every care-plan row action and publication label. */
    val planMetadata: Map<Long, TimelineCarePlanRow> = emptyMap(),
    val familyJoined: Boolean = false,
    val lastSyncFailed: Boolean = false,
    /**
     * Whether to render the three-local-day time bar: true when **any** of D−1 / D / D+1
     * has a day-chart type. Summary / list / legend stay on [records] (day D only).
     */
    val showDayChart: Boolean = false,
)

@HiltViewModel
class LogViewModel @Inject constructor(
    private val careLog: CareLog,
    private val settingsStore: SettingsStore,
    private val syncPort: SyncPort,
    private val timelineWindowRepository: TimelineWindowRepository,
) : ViewModel() {
    private val initialScreenTime = SystemRecordScreenClock.snapshot()
    private val screenTimeFlow = MutableStateFlow(initialScreenTime)
    private val dayFlow = MutableStateFlow(initialScreenTime.localDate)
    private val refreshing = MutableStateFlow(false)
    private val deviceLayoutWriter = DeviceLayoutSnapshotWriter(viewModelScope) { snapshot ->
        settingsStore.setDeviceLayoutSnapshot(snapshot)
    }
    private val layoutEditSessions = LayoutEditSessionStore()
    internal val deviceLayoutWriteState = deviceLayoutWriter.state
    internal val layoutEditSession = layoutEditSessions.state

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState = combine(
        careLog.observeCurrentBaby(),
        careLog.observeBabies(),
        dayFlow,
        settingsStore.settings,
        careLog.observeCustomItems(),
    ) { baby, babies, day, settings, customItems ->
        LogCombine(
            baby = baby,
            babies = babies,
            day = day,
            settings = settings,
            customItems = customItems,
            screenTime = initialScreenTime,
        )
    }.combine(screenTimeFlow) { bundle, screenTime ->
        bundle.copy(screenTime = screenTime)
    }.flatMapLatest { bundle ->
        val (baby, babies, day, settings, customItems, screenTime) = bundle
        val zone = screenTime.zoneId
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
            timelineWindowRepository.observe(
                TimelineWindowRequest(
                    babyId = baby.id,
                    selectedDay = day,
                    zoneId = zone,
                    nowMillis = screenTime.epochMillis,
                ),
            ).map { snapshot ->
                val records = snapshot.recordRows.map(TimelineRecordRow::record)
                val railRecords = snapshot.railRecordRows.map(TimelineRecordRow::record)
                val plans = snapshot.planRows.map(TimelineCarePlanRow::carePlan)
                val timelineAxis = ThreeDayTimelineAxis(day, zone)
                val summary = CareAggregation.day(records, day, zone).toDailySummary()
                val lanes = buildTimelineLanes(
                    records = railRecords,
                    axis = timelineAxis,
                    nowMs = screenTime.epochMillis,
                )
                // Rail visibility uses the three-day union; list/summary stay on D.
                val showDayChart = DayChartCategories.shouldShowDayChart(railRecords)
                val recordMetadata = snapshot.recordRows.associateBy { it.record.id }
                val planMetadata = snapshot.planRows.associateBy { it.carePlan.id }
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
                    openSleep = snapshot.openSleep,
                    uploaderLabels = snapshot.recordRows.mapNotNull { row ->
                        row.uploaderLabel?.let { row.record.id to it }
                    }.toMap(),
                    customItems = customItems,
                    pendingPlans = plans,
                    recordMetadata = recordMetadata,
                    planMetadata = planMetadata,
                    familyJoined = snapshot.audience.isFamilyJoined,
                    showDayChart = showDayChart,
                )
            }
        }
    }.combine(refreshing) { state, isRefreshing ->
        state.copy(refreshing = isRefreshing)
    }.combine(syncPort.status()) { state, status ->
        state.copy(lastSyncFailed = status == SyncStatus.Error)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        LogUiState(day = initialScreenTime.localDate),
    )

    fun setExternalDay(day: LocalDate) {
        dayFlow.value = day
    }

    internal fun setScreenTime(snapshot: RecordScreenTimeSnapshot) {
        screenTimeFlow.value = snapshot
    }

    /** Persist a full device-layout snapshot from 布局编辑态. */
    internal fun applyDeviceLayoutPrefs(prefs: DeviceLayoutPrefs): DeviceLayoutWriteReceipt =
        deviceLayoutWriter.submit(prefs.toSnapshot())

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

    internal fun openLayoutEditSession(
        context: LayoutEditSessionContext,
        prefs: DeviceLayoutPrefs,
        guidanceCompleted: Boolean,
    ) {
        layoutEditSessions.open(context, prefs, guidanceCompleted)
    }

    internal fun currentLayoutEditSession(): LayoutEditSession? = layoutEditSessions.current

    internal fun updateLayoutEditPrefs(
        prefs: DeviceLayoutPrefs,
        hasSubmittedIntent: Boolean,
    ) {
        layoutEditSessions.updatePrefs(prefs, hasSubmittedIntent)
    }

    internal fun updateLayoutCatalogScroll(position: LayoutCatalogScrollPosition) {
        layoutEditSessions.updateCatalogScroll(position)
    }

    internal fun reduceLayoutDragGuidance(
        event: LayoutDragGuidanceEvent,
    ): LayoutDragGuidanceReduction? = layoutEditSessions.reduceDragGuidance(event)

    internal fun markLayoutDragGuidanceCompleted(onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            onDone(runCatching { settingsStore.markLayoutDragGuidanceCompleted() }.isSuccess)
        }
    }

    internal fun closeLayoutEditSession() {
        layoutEditSessions.close()
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
                timelineWindowRepository.refreshMembers()
            } finally {
                refreshing.value = false
            }
        }
    }

    fun skipCarePlan(planId: Long, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                careLog.skipCarePlan(planId)
                "已跳过护理计划"
            }.fold(
                onSuccess = { Result.success(it) },
                onFailure = {
                    Result.failure(Exception(productUiError(it, "跳过失败，请重试")))
                },
            )
            onResult(result)
        }
    }

    /**
     * Soft-delete a care plan from the list swipe path.
     * Same domain entry as Composer delete; [onResult] gets success toast or UI error.
     */
    fun deleteCarePlanFromList(planId: Long, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                check(careLog.deleteCarePlan(planId)) { "护理计划不存在或已删除" }
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
                check(careLog.deleteRecord(recordId)) { "记录不存在或已删除" }
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

/** Ticket 16 copy driven only by the row metadata from the current batch revision. */
internal fun timelineRecordPublishLabel(
    record: Record,
    metadata: TimelineRecordRow?,
    familyJoined: Boolean,
    lastSyncFailed: Boolean,
): String? = localRecordPublishLabel(
    syncDirty = record.syncDirty,
    familyJoined = familyJoined,
    lastSyncFailed = lastSyncFailed,
    publicationState = metadata?.publicationState ?: RootPublicationState.NEVER_PUBLISHED,
)

/** Missing metadata is fail-closed as never published; it never grants a row action. */
internal fun timelineCarePlanPublishLabel(
    plan: CarePlan,
    metadata: TimelineCarePlanRow?,
    familyJoined: Boolean,
    lastSyncFailed: Boolean,
): String? = localCarePlanPublishLabel(
    syncDirty = plan.syncDirty,
    familyJoined = familyJoined,
    lastSyncFailed = lastSyncFailed,
    publicationState = metadata?.publicationState ?: RootPublicationState.NEVER_PUBLISHED,
)

private data class LogCombine(
    val baby: Baby?,
    val babies: List<Baby>,
    val day: LocalDate,
    val settings: SettingsLocal,
    val customItems: List<CustomRecordItem>,
    val screenTime: RecordScreenTimeSnapshot,
)

internal data class TimelineLanes(
    val sleep: List<TimelineLaneSegment>,
    val feed: List<TimelineLaneSegment>,
    val care: List<TimelineLaneSegment>,
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
    axis: ThreeDayTimelineAxis,
    viewportDurationMinutes: Int = axis.defaultViewportDurationMinutes,
): Int {
    require(axis.selectedDay == selectedDay) { "timeline axis must match selected day" }
    if (selectedDay == today) {
        val nowMin = axis.instantToContentMinute(nowMs)
        if (nowMin != null) {
            return axis.centeredViewportStartMinutes(nowMin, viewportDurationMinutes)
        }
    }
    return axis.defaultViewportStartMinutes
}

/**
 * Build rail segments on the calendar-derived continuous [axis].
 *
 * Overnight sleep is one unclipped-at-midnight interval clipped only to the
 * three-local-day window ends. List/summary ownership still uses natural-day aggregation
 * elsewhere — this function only produces geometry.
 */
internal fun buildTimelineLanes(
    records: List<Record>,
    axis: ThreeDayTimelineAxis,
    nowMs: Long = System.currentTimeMillis(),
): TimelineLanes {
    val sleep = mutableListOf<TimelineLaneSegment>()
    val feed = mutableListOf<TimelineLaneSegment>()
    val care = mutableListOf<TimelineLaneSegment>()
    fun offsetToStartMinute(offsetMs: Long): Int = (offsetMs / 60_000L).toInt()

    fun offsetToEndExclusiveMinute(offsetMs: Long): Int =
        ((offsetMs + 59_999L) / 60_000L).toInt()
            .coerceAtMost(axis.contentDurationMinutes)

    fun clock(ms: Long): String = formatClock(ms, axis.zoneId)

    for (r in records) {
        when (r.type) {
            RecordType.SLEEP -> {
                val open = r.endTimestamp == null
                val rawEnd = r.endTimestamp ?: nowMs
                // Clip only to the three-local-day window — do not split at midnight.
                val clipped = axis.clipIntervalToOffsets(r.timestamp, rawEnd)
                if (clipped != null) {
                    val startMin = offsetToStartMinute(clipped.startOffsetMs)
                    val endMin = offsetToEndExclusiveMinute(clipped.endExclusiveOffsetMs)
                        .coerceAtLeast(startMin + 1)
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
                        colorRole = LeziRecordColorRole.Sleep,
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
                val startMin = axis.instantToContentMinute(r.timestamp) ?: continue
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
                    colorRole = r.type.presentation.colorRole,
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
                val startMin = axis.instantToContentMinute(r.timestamp) ?: continue
                val notePart = r.note?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                when (r.type) {
                    RecordType.PEE -> care += TimelineLaneSegment(
                        startMinOfDay = startMin,
                        endMinOfDay = startMin,
                        colorRole = LeziRecordColorRole.Pee,
                        title = "尿尿",
                        detail = "${clock(r.timestamp)}$notePart · 护理",
                        isEvent = true,
                        dayChartCategoryKey = DayChartCategory.PEE.name,
                    )
                    RecordType.POOP -> care += TimelineLaneSegment(
                        startMinOfDay = startMin,
                        endMinOfDay = startMin,
                        colorRole = LeziRecordColorRole.Poop,
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
                            colorRole = LeziRecordColorRole.Pee,
                            title = "尿尿",
                            detail = "${clock(r.timestamp)}$notePart · 尿+便（尿）",
                            isEvent = true,
                            dayChartCategoryKey = DayChartCategory.PEE.name,
                        )
                        care += TimelineLaneSegment(
                            startMinOfDay = startMin,
                            endMinOfDay = startMin,
                            colorRole = LeziRecordColorRole.Poop,
                            title = "便便",
                            detail = "${clock(r.timestamp)}$notePart · 尿+便（便）",
                            isEvent = true,
                            dayChartCategoryKey = DayChartCategory.POOP.name,
                        )
                    }
                    else -> care += TimelineLaneSegment(
                        startMinOfDay = startMin,
                        endMinOfDay = startMin,
                        colorRole = r.type.presentation.colorRole,
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
     * [dayRecords] must be selected-day **D** only (A2 gate); never the three-day rail union.
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
internal fun dayChartLegendColorRole(
    category: DayChartCategory,
): LeziRecordColorRole = when (category) {
    DayChartCategory.SLEEP -> LeziRecordColorRole.Sleep
    DayChartCategory.MILK -> LeziRecordColorRole.Milk
    DayChartCategory.NURSING -> LeziRecordColorRole.Nursing
    DayChartCategory.PEE -> LeziRecordColorRole.Pee
    DayChartCategory.POOP -> LeziRecordColorRole.Poop
}

private fun formatDurationMinutes(minutes: Long): String =
    com.lezi.babylog.core.model.formatRecordDuration(minutes)

private data class PublishChromeTarget(
    val recordId: Long,
    val title: String,
    val publicationState: RootPublicationState,
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
    onGoToday: () -> Unit,
    onMessage: (String) -> Unit = {},
    onLayoutEditModeChanged: (Boolean) -> Unit = {},
    externalDay: LocalDate? = null,
    clock: RecordScreenClock = SystemRecordScreenClock,
    vm: LogViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val layoutWriteState by vm.deviceLayoutWriteState.collectAsStateWithLifecycle()
    val layoutSession by vm.layoutEditSession.collectAsStateWithLifecycle()
    val screenTime by rememberRecordScreenTime(clock)
    val layoutUndoScope = rememberCoroutineScope()
    var showMore by remember { mutableStateOf(false) }
    var showCustomManage by remember { mutableStateOf(false) }
    var publishChromeRecord by remember { mutableStateOf<PublishChromeTarget?>(null) }
    /** At most one timeline/plan row may stay revealed. */
    var revealedSwipeRowId by remember { mutableStateOf<String?>(null) }
    var listDeleteTarget by remember { mutableStateOf<ListDeleteTarget?>(null) }
    var deleteActionState by remember {
        mutableStateOf<ManagementActionState>(ManagementActionState.Idle)
    }
    var skipActionState by remember {
        mutableStateOf<ManagementActionState>(ManagementActionState.Idle)
    }
    var layoutExitInProgress by remember { mutableStateOf(false) }
    var layoutDragCancelSignal by remember { mutableLongStateOf(0L) }
    var exitAfterLayoutRetry by remember { mutableStateOf(false) }
    var dismissedLayoutFailure by remember { mutableStateOf<Long?>(null) }
    var layoutUndoState by remember { mutableStateOf<LayoutUndoState>(LayoutUndoState.Idle) }
    var nextLayoutUndoToken by remember { mutableLongStateOf(0L) }
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
        deleteActionState = ManagementActionState.Idle
        listDeleteTarget = target
    }

    fun requestSkip(planId: Long): Boolean {
        val request = ManagementActionRequest(ManagementActionKind.SkipPlan, planId)
        val started = beginManagementAction(skipActionState, request)
        skipActionState = started.state
        if (!started.accepted) return false

        vm.skipCarePlan(planId) { result ->
            val finished = finishManagementAction(skipActionState, request, result)
            if (finished.accepted) {
                skipActionState = finished.state
                if (result.isSuccess) finished.announcement?.let(onMessage)
            }
        }
        return true
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
        vm.openLayoutEditSession(
            context = LayoutEditSessionContext(
                babyId = state.baby?.id,
                day = state.day,
            ),
            prefs = snapshot.toLayoutPrefs(),
            guidanceCompleted = state.settings.layoutDragGuidanceCompleted,
        )
        exitAfterLayoutRetry = false
        dismissedLayoutFailure = null
        layoutUndoState = LayoutUndoState.Idle
    }
    val dayChartContext = remember(state.baby?.id, state.day) {
        DayChartFilterContext(babyId = state.baby?.id, day = state.day)
    }
    var dayChartFilterState by remember {
        mutableStateOf(DayChartFilterState(context = dayChartContext))
    }
    val today = screenTime.localDate
    val zone = screenTime.zoneId
    val nowMs = screenTime.epochMillis
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
    val dayChartLegend = remember(state.records) {
        DayChartCategories.legendCategories(state.records).map { cat ->
            TimelineLegendEntry(
                key = cat.name,
                label = cat.label,
                colorRole = dayChartLegendColorRole(cat),
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
            now = screenTime.zonedDateTime,
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
    LaunchedEffect(screenTime) {
        vm.setScreenTime(screenTime)
    }

    // Now line: wall-clock absolute time on the three-local-day content axis; only when
    // now falls inside [D−1 00:00, D+1 24:00). Viewport start is day-keyed UI
    // state: init from today/now-centered or non-today D+peek defaults; pan
    // clamps inside the calendar-derived window and never mutates D / summary / list. Reset when the
    // selected day, local date, or time zone changes.
    val timelineAxis = remember(state.day, zone) { ThreeDayTimelineAxis(state.day, zone) }
    val nowContentMinute = timelineAxis.instantToContentMinute(nowMs)
    val timelineViewportDuration = timelineAxis.defaultViewportDurationMinutes
    var timelineViewportStart by remember(state.day, today, zone) {
        mutableIntStateOf(
            initialThreeDayViewportStartMinutes(
                selectedDay = state.day,
                today = today,
                nowMs = nowMs,
                axis = timelineAxis,
                viewportDurationMinutes = timelineViewportDuration,
            ),
        )
    }

    val layoutPresentation = layoutEditPresentation(layoutSession, layoutWriteState)
    val editingSession = layoutPresentation?.session
    val editingPrefs = editingSession?.prefs
    val inLayoutEdit = layoutPresentation != null
    DisposableEffect(inLayoutEdit) {
        onLayoutEditModeChanged(inLayoutEdit)
        onDispose {
            if (inLayoutEdit) onLayoutEditModeChanged(false)
        }
    }
    fun completeLayoutUndoWrite(token: Long, result: Result<Unit>) {
        val currentSnapshot = vm.currentLayoutEditSession()?.prefs?.toSnapshot()
        if (currentSnapshot == null) {
            layoutUndoState = LayoutUndoState.Idle
            return
        }
        val reduction = reduceLayoutUndo(
            layoutUndoState,
            LayoutUndoEvent.UndoWriteFinished(
                token = token,
                succeeded = result.isSuccess,
                currentSnapshot = currentSnapshot,
            ),
        )
        layoutUndoState = reduction.state
        reduction.restoredSnapshot?.let { restored ->
            vm.updateLayoutEditPrefs(
                prefs = restored.toLayoutPrefs(),
                hasSubmittedIntent = true,
            )
            reduction.announcement?.let(onMessage)
        }
    }
    fun closeLayoutEditor() {
        layoutUndoState = reduceLayoutUndo(
            layoutUndoState,
            LayoutUndoEvent.EditorExited,
        ).state
        vm.closeLayoutEditSession()
        layoutExitInProgress = false
        exitAfterLayoutRetry = false
        dismissedLayoutFailure = null
    }
    fun requestLayoutExit() {
        layoutDragCancelSignal += 1L
        if (layoutExitInProgress) return
        if (layoutUndoState !is LayoutUndoState.RestoreFailed) {
            layoutUndoState = reduceLayoutUndo(
                layoutUndoState,
                LayoutUndoEvent.EditorExited,
            ).state
        }
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
                val undoCandidate =
                    (layoutUndoState as? LayoutUndoState.Available)?.candidate
                fun applyLayoutIntent(
                    intent: LayoutEditIntent,
                    inputOrigin: LayoutGuidanceInputOrigin,
                ) {
                    val current = vm.currentLayoutEditSession()?.prefs ?: return
                    val next = reduceLayoutEdit(current, intent, known)
                    nextLayoutUndoToken += 1L
                    val token = nextLayoutUndoToken
                    val undoStateBeforeIntent = layoutUndoState
                    val undoReduction = reduceLayoutUndo(
                        undoStateBeforeIntent,
                        LayoutUndoEvent.IntentApplied(
                            token = token,
                            intent = intent,
                            before = current.toSnapshot(),
                            after = next.toSnapshot(),
                        ),
                    )
                    layoutUndoState = undoReduction.state
                    if (
                        !shouldWriteLayoutIntentResult(
                            undoStateBeforeIntent = undoStateBeforeIntent,
                            undoStateAfterIntent = undoReduction.state,
                            before = current.toSnapshot(),
                            after = next.toSnapshot(),
                        )
                    ) {
                        return
                    }
                    vm.updateLayoutEditPrefs(
                        prefs = next,
                        hasSubmittedIntent = true,
                    )
                    val receipt = vm.applyDeviceLayoutPrefs(next)
                    layoutUndoScope.launch {
                        val result = receipt.result.await()
                        val currentSnapshot =
                            vm.currentLayoutEditSession()?.prefs?.toSnapshot()
                                ?: receipt.snapshot
                        layoutUndoState = reduceLayoutUndo(
                            layoutUndoState,
                            LayoutUndoEvent.OriginalWriteFinished(
                                token = token,
                                succeeded = result.isSuccess,
                                currentSnapshot = currentSnapshot,
                            ),
                        ).state
                        val guidance = vm.currentLayoutEditSession()?.dragGuidance
                        if (
                            guidance != null &&
                            shouldRequestLayoutDragGuidanceCompletion(
                                state = guidance,
                                changed = next != current,
                                inputOrigin = inputOrigin,
                                layoutReceiptSucceeded = result.isSuccess,
                            )
                        ) {
                            vm.markLayoutDragGuidanceCompleted { markerSucceeded ->
                                vm.reduceLayoutDragGuidance(
                                    LayoutDragGuidanceEvent.DragCompletionFinished(
                                        changed = true,
                                        inputOrigin = LayoutGuidanceInputOrigin.TouchDrag,
                                        layoutReceiptSucceeded = true,
                                        markerReceiptSucceeded = markerSucceeded,
                                    ),
                                )
                            }
                        }
                    }
                }
                fun closeLayoutDragGuidance() {
                    val reduction = vm.reduceLayoutDragGuidance(
                        LayoutDragGuidanceEvent.CloseRequested,
                    ) ?: return
                    if (!reduction.markCompleted) return
                    vm.markLayoutDragGuidanceCompleted { succeeded ->
                        vm.reduceLayoutDragGuidance(
                            LayoutDragGuidanceEvent.CompletionMarkerFinished(succeeded),
                        )
                        if (!succeeded) {
                            onMessage("帮助状态保存失败，下次进入时仍会显示")
                        }
                    }
                }
                LayoutEditCanvas(
                    prefs = prefs,
                    customItems = state.customItems,
                    onIntent = { intent ->
                        applyLayoutIntent(
                            intent = intent,
                            inputOrigin = LayoutGuidanceInputOrigin.AlternativeAction,
                        )
                    },
                    onTouchDragIntent = { intent ->
                        applyLayoutIntent(
                            intent = intent,
                            inputOrigin = LayoutGuidanceInputOrigin.TouchDrag,
                        )
                    },
                    onDone = ::requestLayoutExit,
                    onOpenCustomManage = { showCustomManage = true },
                    writeState = layoutWriteState,
                    hasSubmittedIntent = editingSession.hasSubmittedIntent,
                    cancelDragSignal = layoutDragCancelSignal,
                    undoCandidate = undoCandidate,
                    initialCatalogScroll = editingSession.catalogScroll,
                    onCatalogScrollChanged = vm::updateLayoutCatalogScroll,
                    configurationSessionKey =
                        state.settings.darkMode to state.settings.visualStyle,
                    dragGuidance = editingSession.dragGuidance,
                    onDragGuidanceHelp = {
                        vm.reduceLayoutDragGuidance(LayoutDragGuidanceEvent.HelpRequested)
                    },
                    onDragGuidanceClose = ::closeLayoutDragGuidance,
                    onUndo = { token ->
                        val current = vm.currentLayoutEditSession()?.prefs
                            ?: return@LayoutEditCanvas
                        val reduction = reduceLayoutUndo(
                            layoutUndoState,
                            LayoutUndoEvent.UndoRequested(
                                token = token,
                                currentSnapshot = current.toSnapshot(),
                            ),
                        )
                        layoutUndoState = reduction.state
                        val restoring = reduction.state as? LayoutUndoState.Restoring
                            ?: return@LayoutEditCanvas
                        vm.updateLayoutEditPrefs(
                            prefs = current,
                            hasSubmittedIntent = true,
                        )
                        val receipt = vm.applyDeviceLayoutPrefs(
                            restoring.candidate.before.toLayoutPrefs(),
                        )
                        layoutUndoScope.launch {
                            completeLayoutUndoWrite(token, receipt.result.await())
                        }
                    },
                    onUndoExpired = { token ->
                        layoutUndoState = reduceLayoutUndo(
                            layoutUndoState,
                            LayoutUndoEvent.OfferExpired(token),
                        ).state
                    },
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
                                sleep = state.sleepLanes,
                                feed = state.feedLanes,
                                care = state.careLanes,
                                recordCount = state.records.size,
                                nowContentMinute = nowContentMinute,
                                selectedCategoryKey = dayChartFilter?.name,
                                onCategorySelect = { key ->
                                    // A2: gate on day-D records only; rail marks stay three-day union.
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
                                windowGeometry = timelineAxis.windowGeometry,
                                hourLabels = timelineAxis.hourLabels(),
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
                            val effective = plan.effectiveStatus(nowMs)
                            val isMissed = effective == CarePlanStatus.MISSED
                            val title = plan.displayLabel()
                            val planZone = runCatching { ZoneId.of(plan.scheduledZoneId) }
                                .getOrDefault(zone)
                            val zoneHint = if (planZone != zone) {
                                val original = Instant.ofEpochMilli(plan.scheduledAt)
                                    .atZone(planZone)
                                    .toLocalTime()
                                    .toString()
                                " · 原计划 $original (${plan.scheduledZoneId})"
                            } else {
                                ""
                            }
                            val planMetadata = state.planMetadata[plan.id]
                            val planCapabilities = planMetadata?.capabilities
                            val canEditPlan = planCapabilities?.canEdit == true
                            val canDeletePlan = planCapabilities?.canDelete == true
                            val canFulfillPlan = planCapabilities?.canFulfill == true
                            val canSkipPlan = planCapabilities?.canSkip == true
                            val planPublicationState = planMetadata?.publicationState
                                ?: RootPublicationState.NEVER_PUBLISHED
                            val planPublishLabel = timelineCarePlanPublishLabel(
                                plan = plan,
                                metadata = planMetadata,
                                familyJoined = state.familyJoined,
                                lastSyncFailed = state.lastSyncFailed,
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
                            val planSkipRequest = ManagementActionRequest(
                                ManagementActionKind.SkipPlan,
                                plan.id,
                            )
                            val skipFeedback = managementActionFeedback(
                                skipActionState,
                                planSkipRequest,
                            )
                            val skipRunning =
                                skipActionState == ManagementActionState.Running(planSkipRequest)
                            val skipBlocked = skipActionState is ManagementActionState.Running
                            val skipFailed =
                                (skipActionState as? ManagementActionState.Failed)?.request ==
                                    planSkipRequest
                            val editPlanAction = {
                                openEditFromSwipe(RecordComposerRequest.EditPlan(plan.id))
                            }
                            val deletePlanAction = {
                                requestListDelete(ListDeleteTarget.Plan(plan))
                            }
                            val skipPlanAction: () -> Boolean = { requestSkip(plan.id) }
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
                                    editEnabled = canEditPlan,
                                    deleteEnabled = canDeletePlan,
                                    onEdit = editPlanAction,
                                    onDelete = deletePlanAction,
                                    editTestTag = "timeline_swipe_edit_plan_${plan.id}",
                                    deleteTestTag = "timeline_swipe_delete_plan_${plan.id}",
                                ) {
                                    RecordRow(
                                        time = formatClock(plan.scheduledAt, zone),
                                        title = title,
                                        summary = planSummary,
                                        relative = relativeTimeLabel(plan.scheduledAt, nowMs),
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
                                                if (canFulfillPlan) {
                                                    onOpenComposer(
                                                        RecordComposerRequest.Fulfill(plan.id),
                                                    )
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .managementActions(
                                                rowManagementCustomActions(
                                                    targetLabel = "${title}护理计划",
                                                    canEdit = canEditPlan,
                                                    canDelete = canDeletePlan,
                                                    canSkip = canSkipPlan,
                                                    skipEnabled = !skipBlocked,
                                                    onEdit = editPlanAction,
                                                    onDelete = deletePlanAction,
                                                    onSkip = skipPlanAction,
                                                ),
                                            )
                                            .semantics {
                                                val publishDetail = if (planPublishLabel != null) {
                                                    "。" + localCarePlanPublishDetail(
                                                        lastSyncFailed = state.lastSyncFailed,
                                                        publicationState = planPublicationState,
                                                    )
                                                } else {
                                                    ""
                                                }
                                                contentDescription =
                                                    "完成${title}护理计划$publishDetail"
                                                if (skipFeedback != null) {
                                                    stateDescription = skipFeedback
                                                }
                                            },
                                    )
                                }
                                if (canSkipPlan) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                    ) {
                                        // 编辑 is left-swipe only (absolute screen direction).
                                        TextButton(
                                            onClick = { skipPlanAction() },
                                            enabled = !skipBlocked,
                                            modifier = Modifier.testTag(
                                                "care_plan_skip_${plan.id}",
                                            ),
                                        ) {
                                            Text(
                                                when {
                                                    skipRunning -> "跳过中…"
                                                    skipFeedback != null -> "重试跳过"
                                                    else -> "跳过"
                                                },
                                            )
                                        }
                                    }
                                    if (skipFeedback != null) {
                                        ManagementActionFeedback(
                                            message = skipFeedback,
                                            isError = skipFailed,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("care_plan_skip_feedback_${plan.id}"),
                                        )
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
                            val recordMetadata = state.recordMetadata[r.id]
                            val recordCapabilities = recordMetadata?.capabilities
                            val canEditRecord = recordCapabilities?.canEdit == true
                            val canDeleteRecord = recordCapabilities?.canDelete == true
                            val publicationState = recordMetadata?.publicationState
                                ?: RootPublicationState.NEVER_PUBLISHED
                            val publishLabel = timelineRecordPublishLabel(
                                record = r,
                                metadata = recordMetadata,
                                familyJoined = state.familyJoined,
                                lastSyncFailed = state.lastSyncFailed,
                            )
                            val recordRowId = "record-${r.id}"
                            val recordRevealed = revealedSwipeRowId == recordRowId
                            val editRecordAction = {
                                // Amber publish chrome: tap prefers sync sheet; left-swipe still edits.
                                openEditFromSwipe(RecordComposerRequest.Edit(r.id))
                            }
                            val deleteRecordAction = {
                                requestListDelete(ListDeleteTarget.RecordItem(r))
                            }
                            SwipeEditDeleteRow(
                                open = recordRevealed,
                                onOpenChange = { open ->
                                    revealedSwipeRowId = if (open) recordRowId else {
                                        revealedSwipeRowId.takeUnless { it == recordRowId }
                                    }
                                },
                                editEnabled = canEditRecord,
                                deleteEnabled = canDeleteRecord,
                                onEdit = editRecordAction,
                                onDelete = deleteRecordAction,
                                editTestTag = "timeline_swipe_edit_record_${r.id}",
                                deleteTestTag = "timeline_swipe_delete_record_${r.id}",
                                modifier = Modifier.padding(
                                    horizontal = if (journal) 0.dp else LeziSpacing.Page,
                                ),
                            ) {
                                RecordRow(
                                    time = formatClock(r.timestamp, zone),
                                    title = title,
                                    summary = timelineRecordSummary(
                                        recordSummaryLine(r),
                                        state.uploaderLabels[r.id],
                                        publishLabel,
                                    ),
                                    relative = relativeTimeLabel(r.timestamp, nowMs),
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
                                                publicationState = publicationState,
                                            )
                                        } else if (canEditRecord) {
                                            collapseSwipeRows()
                                            onOpenComposer(RecordComposerRequest.Edit(r.id))
                                        }
                                    },
                                    modifier = Modifier
                                        .managementActions(
                                            rowManagementCustomActions(
                                                targetLabel = "${title}记录",
                                                canEdit = canEditRecord,
                                                canDelete = canDeleteRecord,
                                                onEdit = editRecordAction,
                                                onDelete = deleteRecordAction,
                                            ),
                                        )
                                        .semantics {
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
    val failedLayoutUndo = layoutUndoState as? LayoutUndoState.RestoreFailed
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
            title = {
                Text(if (failedLayoutUndo != null) "撤销未完成" else "布局尚未保存")
            },
            text = {
                Text(
                    if (failedLayoutUndo != null) {
                        "撤销布局保存失败，当前布局保持不变。可重试撤销，或继续编辑。"
                    } else {
                        "上一项布局更改保存失败。当前页面仍保留更改，可重试后再退出。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !layoutExitInProgress,
                    onClick = {
                        layoutExitInProgress = true
                        if (failedLayoutUndo != null) {
                            val current = vm.currentLayoutEditSession()?.prefs
                            val reduction = current?.let {
                                reduceLayoutUndo(
                                    layoutUndoState,
                                    LayoutUndoEvent.RetryUndoRequested(
                                        token = failedLayoutUndo.candidate.token,
                                        currentSnapshot = it.toSnapshot(),
                                    ),
                                )
                            }
                            layoutUndoState = reduction?.state ?: LayoutUndoState.Idle
                            val restoring = reduction?.state as? LayoutUndoState.Restoring
                            if (restoring == null) {
                                layoutExitInProgress = false
                            } else {
                                val receipt = vm.applyDeviceLayoutPrefs(
                                    restoring.candidate.before.toLayoutPrefs(),
                                )
                                layoutUndoScope.launch {
                                    val result = receipt.result.await()
                                    completeLayoutUndoWrite(
                                        failedLayoutUndo.candidate.token,
                                        result,
                                    )
                                    layoutExitInProgress = false
                                    if (result.isSuccess && exitAfterLayoutRetry) {
                                        closeLayoutEditor()
                                    }
                                }
                            }
                        } else {
                            vm.retryDeviceLayoutWrite { result ->
                                layoutExitInProgress = false
                                if (result.isSuccess && exitAfterLayoutRetry) {
                                    closeLayoutEditor()
                                }
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

    publishChromeRecord?.let { target ->
        AlertDialog(
            onDismissRequest = { publishChromeRecord = null },
            title = { Text(target.title) },
            text = {
                Text(
                    localRecordPublishDetail(
                        lastSyncFailed = state.lastSyncFailed,
                        publicationState = target.publicationState,
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
        val deleteRequest = when (target) {
            is ListDeleteTarget.Plan -> ManagementActionRequest(
                ManagementActionKind.DeletePlan,
                target.plan.id,
            )
            is ListDeleteTarget.RecordItem -> ManagementActionRequest(
                ManagementActionKind.DeleteRecord,
                target.record.id,
            )
        }
        val deleteRunning = deleteActionState == ManagementActionState.Running(deleteRequest)
        val deleteFeedback = managementActionFeedback(deleteActionState, deleteRequest)
        AlertDialog(
            onDismissRequest = {
                if (!deleteRunning) {
                    listDeleteTarget = null
                    deleteActionState = ManagementActionState.Idle
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
                        error = deleteFeedback,
                    ),
                    modifier = Modifier
                        .testTag("list_delete_feedback")
                        .semantics {
                            if (deleteFeedback != null) {
                                liveRegion = LiveRegionMode.Polite
                                stateDescription = deleteFeedback
                            }
                        },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !deleteRunning,
                    onClick = {
                        val started = beginManagementAction(deleteActionState, deleteRequest)
                        deleteActionState = started.state
                        if (!started.accepted) return@TextButton

                        fun finishDelete(result: Result<String>) {
                            val finished = finishManagementAction(
                                state = deleteActionState,
                                request = deleteRequest,
                                result = result,
                            )
                            if (!finished.accepted) return
                            deleteActionState = finished.state
                            if (result.isSuccess) {
                                listDeleteTarget = null
                                collapseSwipeRows()
                                finished.announcement?.let(onMessage)
                            }
                        }
                        when (target) {
                            is ListDeleteTarget.Plan -> vm.deleteCarePlanFromList(
                                target.plan.id,
                                ::finishDelete,
                            )
                            is ListDeleteTarget.RecordItem -> vm.deleteRecordFromList(
                                target.record.id,
                                ::finishDelete,
                            )
                        }
                    },
                ) {
                    Text(
                        if (deleteRunning) "删除中…" else "确认删除",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !deleteRunning,
                    onClick = {
                        listDeleteTarget = null
                        deleteActionState = ManagementActionState.Idle
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
internal fun OneHandQuickDock(
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
    val cells = remember(resolved) {
        fixedQuickDockOrder(resolved)
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
                val cellInteraction = when (cell) {
                    is QuickDockCell.Bound -> Modifier
                        .combinedClickable(
                            onClick = { onBound(cell.identity) },
                            onLongClick = onLongPress,
                        )
                        .semantics(mergeDescendants = true) {
                            contentDescription = presentation.contentDescription
                        }
                    QuickDockCell.Empty -> Modifier
                        .pointerInput(onEmpty, onLongPress) {
                            detectTapGestures(
                                onTap = { onEmpty() },
                                onLongPress = { onLongPress() },
                            )
                        }
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyUp && event.key == Key.Enter) {
                                onLongPress()
                                true
                            } else {
                                false
                            }
                        }
                        .focusable()
                        .semantics(mergeDescendants = true) {
                            contentDescription = presentation.contentDescription
                            stateDescription = "短按无操作"
                            customActions = listOf(
                                CustomAccessibilityAction("编辑常用布局") {
                                    onLongPress()
                                    true
                                },
                            )
                        }
                    QuickDockCell.More -> Modifier
                        .clickable(onClick = onMore)
                        .semantics(mergeDescendants = true) {
                            contentDescription = presentation.contentDescription
                        }
                }
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = QuickDockVisualSpec.cellMinHeight)
                        .testTag(tag)
                        .then(cellInteraction),
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
                            .padding(vertical = 5.dp),
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
