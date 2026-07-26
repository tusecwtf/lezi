package com.lezi.babylog.feature.log

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
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
import com.lezi.babylog.core.model.availableForNewEntry
import com.lezi.babylog.core.model.displayLabel
import com.lezi.babylog.core.ui.RecordSection
import com.lezi.babylog.core.ui.RecordSummaryStrip
import com.lezi.babylog.core.ui.RecordSummaryValue
import com.lezi.babylog.core.ui.RecordTypeIcon
import com.lezi.babylog.core.ui.UiTags
import com.lezi.babylog.core.ui.orderedRecordSections
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.core.ui.presentationSummary
import com.lezi.babylog.core.ui.presentationTone
import com.lezi.babylog.core.ui.sortCatalogByLocalOrder
import com.lezi.babylog.designsystem.LeziCard
import com.lezi.babylog.designsystem.LeziRecordGlyph
import com.lezi.babylog.designsystem.LeziRecordGlyphIcon
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziShapes
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
import com.lezi.babylog.designsystem.TimelineLaneSegment
import com.lezi.babylog.designsystem.TimelineLegendEntry
import com.lezi.babylog.designsystem.TimelineRailCard
import com.lezi.babylog.designsystem.leziRecordColor
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.CareAggregation
import com.lezi.babylog.domain.CustomRecordItem
import com.lezi.babylog.domain.DailySummary
import com.lezi.babylog.domain.DayChartCategories
import com.lezi.babylog.domain.DayChartCategory
import com.lezi.babylog.domain.formatClock
import com.lezi.babylog.domain.relativeTimeLabel
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.UploaderMemberRef
import com.lezi.babylog.sync.localRecordPublishDetail
import com.lezi.babylog.sync.localCarePlanPublishDetail
import com.lezi.babylog.sync.localCarePlanPublishLabel
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton

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
    private val selfDeviceId = MutableStateFlow("")
    private val familyJoined = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            syncPort.session()
                .map { it.isJoined to it.deviceId }
                .distinctUntilChanged()
                .collect { (joined, deviceId) ->
                    familyJoined.value = joined
                    selfDeviceId.value = deviceId
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
            combine(
                careLog.observeDayRecords(baby.id, day, zone),
                careLog.observeOpenSleep(baby.id),
                plansFlow,
                uploaderMembers,
                selfDeviceId,
            ) { records, openSleep, plans, members, selfId ->
                val joined = familyJoined.value
                val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
                val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val summary = CareAggregation.day(records, day, zone).toDailySummary()
                val lanes = buildLanes(records, start, end)
                val labels = buildUploaderLabels(records, selfId, joined, members)
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

    fun deleteCarePlan(planId: Long) {
        viewModelScope.launch {
            runCatching { careLog.deleteCarePlan(planId) }
        }
    }
}

/** Pure map of record id → uploader display label (S3 seam for timeline composition). */
internal fun buildUploaderLabels(
    records: List<Record>,
    selfDeviceId: String,
    isFamilyJoined: Boolean,
    members: List<UploaderMemberRef>,
): Map<Long, String> {
    if (!isFamilyJoined || records.isEmpty()) return emptyMap()
    val out = LinkedHashMap<Long, String>()
    for (record in records) {
        val label = resolveRecordUploaderLabel(
            createdByDeviceId = record.createdByDeviceId,
            selfDeviceId = selfDeviceId,
            isFamilyJoined = true,
            members = members,
        )
        if (label != null) out[record.id] = label
    }
    return out
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

private data class LogCombine(
    val baby: Baby?,
    val babies: List<Baby>,
    val day: LocalDate,
    val settings: SettingsLocal,
    val customItems: List<CustomRecordItem>,
)

private data class Lanes(
    val sleep: List<TimelineLaneSegment>,
    val feed: List<TimelineLaneSegment>,
    val care: List<TimelineLaneSegment>,
)

private fun buildLanes(records: List<Record>, dayStart: Long, dayEnd: Long): Lanes {
    val sleep = mutableListOf<TimelineLaneSegment>()
    val feed = mutableListOf<TimelineLaneSegment>()
    val care = mutableListOf<TimelineLaneSegment>()
    val zone = ZoneId.systemDefault()
    for (r in records) {
        val startMs = r.timestamp.coerceIn(dayStart, dayEnd - 1)
        fun mins(ms: Long) = ((ms - dayStart) / 60_000L).toInt().coerceIn(0, 24 * 60)
        fun clock(ms: Long): String = formatClock(ms, zone)
        when (r.type) {
            RecordType.SLEEP -> {
                val open = r.endTimestamp == null
                val endMs = (r.endTimestamp ?: System.currentTimeMillis()).coerceIn(dayStart + 1, dayEnd)
                if (endMs > startMs) {
                    val startMin = mins(startMs)
                    val endMin = mins(endMs).coerceAtLeast(startMin + 1)
                    val durationMin = ((endMs - startMs) / 60_000L).coerceAtLeast(1)
                    val nap = (r.payload.payload as? SleepPayload)?.isNap == true
                    val title = if (nap) "午睡" else "睡眠"
                    val detail = buildString {
                        append(clock(startMs))
                        append("–")
                        append(if (open) "进行中" else clock(endMs))
                        append(" · ")
                        append(formatDurationMinutes(durationMin))
                        if (open) append("（未结束）")
                    }
                    sleep += TimelineLaneSegment(
                        startMinOfDay = startMin,
                        endMinOfDay = endMin,
                        color = Color(0xFFE09F3E),
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
                val startMin = mins(startMs)
                val title = r.type.presentation.label
                val detail = buildString {
                    append(clock(startMs))
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
                    color = Color(0xFF007BAE),
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
                val startMin = mins(startMs)
                val notePart = r.note?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                when (r.type) {
                    RecordType.PEE -> care += TimelineLaneSegment(
                        startMinOfDay = startMin,
                        endMinOfDay = startMin,
                        color = Color(CARE_PEE),
                        title = "尿尿",
                        detail = "${clock(startMs)}$notePart · 护理",
                        isEvent = true,
                        dayChartCategoryKey = DayChartCategory.PEE.name,
                    )
                    RecordType.POOP -> care += TimelineLaneSegment(
                        startMinOfDay = startMin,
                        endMinOfDay = startMin,
                        color = Color(CARE_POOP),
                        title = "便便",
                        detail = "${clock(startMs)}$notePart · 护理",
                        isEvent = true,
                        dayChartCategoryKey = DayChartCategory.POOP.name,
                    )
                    RecordType.BOTH_DIAPER -> {
                        // Two marks share one record; each mark maps to one day-chart type.
                        care += TimelineLaneSegment(
                            startMinOfDay = startMin,
                            endMinOfDay = startMin,
                            color = Color(CARE_PEE),
                            title = "尿尿",
                            detail = "${clock(startMs)}$notePart · 尿+便（尿）",
                            isEvent = true,
                            dayChartCategoryKey = DayChartCategory.PEE.name,
                        )
                        care += TimelineLaneSegment(
                            startMinOfDay = startMin,
                            endMinOfDay = startMin,
                            color = Color(CARE_POOP),
                            title = "便便",
                            detail = "${clock(startMs)}$notePart · 尿+便（便）",
                            isEvent = true,
                            dayChartCategoryKey = DayChartCategory.POOP.name,
                        )
                    }
                    else -> care += TimelineLaneSegment(
                        startMinOfDay = startMin,
                        endMinOfDay = startMin,
                        color = Color(CARE_OTHER),
                        title = r.type.presentation.label,
                        detail = "${clock(startMs)}$notePart · 护理",
                        isEvent = true,
                        dayChartCategoryKey = null,
                    )
                }
            }
            else -> Unit
        }
    }
    return Lanes(sleep, feed, care)
}

/**
 * Opaque key for [TimelineLaneSegment.dayChartCategoryKey], aligned with
 * [DayChartCategory.name]. Non day-chart types (e.g. 吸奶) return null.
 * BOTH_DIAPER is handled as two segments in [buildLanes], not here.
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

private fun formatDurationMinutes(minutes: Long): String {
    if (minutes < 60) return "${minutes}分钟"
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0L) "${h}小时" else "${h}小时${m}分"
}

/** Care-lane pin colors: pee green, poop yellow, other muted green. */
private const val CARE_PEE = 0xFF7A9E7E
private const val CARE_POOP = 0xFFF3B84B
private const val CARE_OTHER = 0xFF8FB894

private data class PublishChromeTarget(
    val recordId: Long,
    val title: String,
    val priorRevision: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogRoute(
    onOpenComposer: (RecordComposerRequest) -> Unit,
    onGoToday: () -> Unit,
    externalDay: LocalDate? = null,
    vm: LogViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var showMore by remember { mutableStateOf(false) }
    var publishChromeRecord by remember { mutableStateOf<PublishChromeTarget?>(null) }
    // Page-level day-chart filter (temporary; cleared on day change via remember key).
    var selectedDayChart by remember(state.day) {
        mutableStateOf<DayChartCategory?>(null)
    }
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val ext = LeziThemeExt.colors
    val journal = LeziThemeExt.isJournal
    // Reconcile after refresh/delete so a vanished category does not stick at 0 rows.
    val dayChartFilter = remember(selectedDayChart, state.records) {
        DayChartCategories.reconcileSelection(selectedDayChart, state.records)
    }
    LaunchedEffect(dayChartFilter, selectedDayChart) {
        if (dayChartFilter != selectedDayChart) {
            selectedDayChart = dayChartFilter
        }
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
    val dayChartTipCount = remember(state.records, dayChartFilter) {
        if (dayChartFilter == null) 0
        else DayChartCategories.filterRecords(state.records, dayChartFilter).size
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

    val nowMin = if (state.day == today) {
        val t = LocalTime.now()
        t.hour * 60 + t.minute
    } else {
        null
    }

    PageScaffoldBackground {
        Column(Modifier.fillMaxSize().testTag(UiTags.LOG_HOME)) {
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.weight(1f),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = LeziSpacing.Md),
                    verticalArrangement = Arrangement.spacedBy(if (journal) 4.dp else LeziSpacing.SectionGap),
                ) {
                    item {
                        Column(Modifier.padding(horizontal = LeziSpacing.Page)) {
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
                                    )
                                    SummaryMetric(
                                        value = "${state.summary.nursingMinutes}min",
                                        label = "母乳",
                                        tone = LeziTone.Blue,
                                        modifier = Modifier.weight(1f),
                                        icon = {
                                            RecordTypeIcon(RecordType.NURSING)
                                        },
                                    )
                                    SummaryMetric(
                                        value = formatMinutes(state.summary.sleepMinutes),
                                        label = "睡眠",
                                        tone = LeziTone.Yellow,
                                        modifier = Modifier.weight(1f),
                                        icon = {
                                            RecordTypeIcon(RecordType.SLEEP)
                                        },
                                    )
                                    SummaryMetric(
                                        value = "${state.summary.peeCount}次",
                                        label = "尿尿",
                                        tone = LeziTone.Cream,
                                        modifier = Modifier.weight(1f),
                                        icon = {
                                            RecordTypeIcon(RecordType.PEE)
                                        },
                                    )
                                    SummaryMetric(
                                        value = "${state.summary.poopCount}次",
                                        label = "便便",
                                        tone = LeziTone.Neutral,
                                        modifier = Modifier.weight(1f),
                                        icon = {
                                            RecordTypeIcon(RecordType.POOP)
                                        },
                                    )
                                }
                            }
                        }
                    }

                    // Hide the time bar when the day has no day-chart types (empty day or
                    // only non-rhythm types such as pump_express / temp / medicine).
                    // Visibility uses DayChartCategories, not buildLanes emptiness.
                    if (DayChartCategories.shouldShowDayChart(state.records)) {
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
                                nowMinOfDay = nowMin,
                                selectedCategoryKey = dayChartFilter?.name,
                                onCategorySelect = { key ->
                                    selectedDayChart = resolveDayChartSelection(key)
                                },
                                legend = dayChartLegend,
                                tipLabel = dayChartFilter?.label,
                                tipCount = dayChartTipCount,
                                modifier = Modifier.padding(horizontal = LeziSpacing.Page),
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
                            Column(Modifier.padding(horizontal = LeziSpacing.Page)) {
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
                            Column(
                                Modifier
                                    .padding(horizontal = LeziSpacing.Page)
                                    .testTag("pending_care_plan_${plan.id}"),
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
                                        onOpenComposer(RecordComposerRequest.Fulfill(plan.id))
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
                                        contentDescription = "完成${title}护理计划$publishDetail"
                                    },
                                )
                                if (canManagePlan) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                    ) {
                                        TextButton(
                                            onClick = {
                                                onOpenComposer(
                                                    RecordComposerRequest.EditPlan(plan.id),
                                                )
                                            },
                                            modifier = Modifier.testTag(
                                                "care_plan_edit_${plan.id}",
                                            ),
                                        ) { Text("编辑") }
                                        TextButton(
                                            onClick = { vm.skipCarePlan(plan.id) },
                                            modifier = Modifier.testTag(
                                                "care_plan_skip_${plan.id}",
                                            ),
                                        ) { Text("跳过") }
                                        TextButton(
                                            onClick = { vm.deleteCarePlan(plan.id) },
                                            modifier = Modifier.testTag(
                                                "care_plan_delete_${plan.id}",
                                            ),
                                        ) {
                                            Text(
                                                "删除",
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Column(Modifier.padding(horizontal = LeziSpacing.Page)) {
                            SectionHeading(
                                eyebrow = if (journal) null else "按时间排序",
                                title = if (journal) "记录明细" else "当日记录",
                                meta = if (journal) "新 → 旧" else null,
                            )
                        }
                    }

                    when {
                        state.loading -> item {
                            StateContainer(
                                kind = StateKind.Loading,
                                title = "加载中",
                                message = "正在读取当日记录…",
                                modifier = Modifier.padding(horizontal = LeziSpacing.Page),
                            )
                        }
                        state.records.isEmpty() -> item {
                            StateContainer(
                                kind = StateKind.Empty,
                                title = "还没有记录",
                                message = "点下方快捷入口添加第一条记录",
                                modifier = Modifier.padding(horizontal = LeziSpacing.Page),
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
                                anomaly = (r.payload.payload as? SleepPayload)?.anomaly == true ||
                                    (r.type == RecordType.SLEEP && r.endTimestamp == null),
                                leading = {
                                    RecordTypeIcon(r.type)
                                },
                                onClick = {
                                    if (publishLabel != null) {
                                        publishChromeRecord = PublishChromeTarget(
                                            recordId = r.id,
                                            title = title,
                                            priorRevision = priorRevision,
                                        )
                                    } else {
                                        onOpenComposer(RecordComposerRequest.Edit(r.id))
                                    }
                                },
                                modifier = Modifier
                                    .padding(horizontal = LeziSpacing.Page)
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
            OneHandQuickDock(
                preferredHand = state.settings.preferredHand,
                storedSlots = state.settings.quickRecordSlots,
                hiddenTypeKeys = state.settings.hiddenItems,
                customItems = state.customItems,
                sleepRunning = state.openSleep != null,
                onBound = { identity -> openComposer(identity) },
                onEmpty = { /* settings entry for slot pick is under 记录项目 / 常用记录 */ },
                onMore = { showMore = true },
            )
        }
    }

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
            )
        }
    }
}

private val CustomSlotIcons = listOf("★", "♥", "☀", "☾", "♪", "●", "▲", "◆")

/**
 * Always renders four configurable slots plus fixed "更多", laid out by preferred hand.
 * Hidden / deleted / invalid refs blank a cell rather than removing it.
 */
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
            .padding(horizontal = if (journal) 0.dp else 8.dp, vertical = 4.dp)
            .testTag("one_hand_quick_dock_$preferredHand"),
        shape = if (journal) LeziShapes.JournalCard else LeziShapes.Lg,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
        shadowElevation = if (journal) 2.dp else 8.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            cells.forEachIndexed { index, cell ->
                val label = when (cell) {
                    is QuickDockCell.Bound ->
                        if (cell.recordType == RecordType.SLEEP && sleepRunning) {
                            "醒来"
                        } else {
                            cell.label
                        }
                    QuickDockCell.Empty -> "＋ 选择常用记录"
                    QuickDockCell.More -> "更多"
                }
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
                val isPee = cell is QuickDockCell.Bound && cell.recordType == RecordType.PEE
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 64.dp)
                        .testTag(tag)
                        .clickable {
                            when (cell) {
                                is QuickDockCell.Bound -> onBound(cell.identity)
                                QuickDockCell.Empty -> onEmpty()
                                QuickDockCell.More -> onMore()
                            }
                        },
                    shape = if (journal) LeziShapes.JournalButton else LeziShapes.Sm,
                    color = if (isPee) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                    } else {
                        Color.Transparent
                    },
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            Modifier
                                .size(30.dp)
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
                            label,
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
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

@Composable
private fun MoreSheet(
    settings: SettingsLocal,
    customItems: List<CustomRecordItem>,
    onPick: (RecordItemIdentity) -> Unit,
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
    val groups = orderedRecordSections(settings.categoryOrderJson).map { section ->
        section to catalog.filter { it.section == section }
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                suggestions.forEach { entry ->
                    MoreCatalogCard(
                        entry = entry,
                        onClick = { onPick(entry.identity) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat((4 - suggestions.size).coerceAtLeast(0)) {
                    Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(LeziSpacing.Md))
        }
        groups.forEach { (section, items) ->
            if (items.isEmpty()) return@forEach
            item {
                Column {
                    Text(
                        section.title,
                        style = LeziTypography.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(LeziSpacing.Xs))
                    items.chunked(4).forEach { rowItems ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowItems.forEach { entry ->
                                MoreCatalogCard(
                                    entry = entry,
                                    onClick = { onPick(entry.identity) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(4 - rowItems.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

private val CustomItemIconGlyphs = listOf("★", "♥", "☀", "☾", "♪", "●", "▲", "◆")

@Composable
private fun MoreCatalogCard(
    entry: MoreCatalogEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorRole = when (entry) {
        is MoreCatalogEntry.BuiltIn -> entry.type.presentation.colorRole
        is MoreCatalogEntry.Custom -> RecordType.CUSTOM.presentation.colorRole
    }
    val color = leziRecordColor(colorRole)
    LeziCard(
        modifier = modifier.heightIn(min = 64.dp),
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 3.dp, vertical = 5.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {
                    contentDescription = moreRecordContentDescription(entry)
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(LeziShapes.JournalCard)
                    .background(color.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                when (entry) {
                    is MoreCatalogEntry.BuiltIn ->
                        RecordTypeIcon(entry.type, size = 18.dp, tint = color)
                    is MoreCatalogEntry.Custom ->
                        Text(
                            CustomItemIconGlyphs[entry.item.iconSlot.coerceIn(0, 7)],
                            style = LeziTypography.BodyStrong,
                            color = color,
                        )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                entry.label,
                style = LeziTypography.Label.copy(fontSize = 14.sp, lineHeight = 18.sp),
                maxLines = 1,
            )
        }
    }
}

private fun formatMinutes(min: Long): String {
    if (min <= 0) return "0m"
    val h = min / 60
    val m = min % 60
    return if (h == 0L) "${m}m" else if (m == 0L) "${h}h" else "${h}h ${m}m"
}

private fun toneOf(type: RecordType): LeziTone = type.presentationTone()

internal fun typeLabel(type: RecordType): String = type.presentation.label

internal fun typeGlyph(type: RecordType): LeziRecordGlyph = type.presentation.glyph

internal fun moreRecordContentDescription(type: RecordType): String =
    "添加${type.presentation.label}"

internal fun moreRecordContentDescription(entry: MoreCatalogEntry): String =
    "添加${entry.label}"

internal fun recordSummaryLine(record: Record): String = record.presentationSummary()
