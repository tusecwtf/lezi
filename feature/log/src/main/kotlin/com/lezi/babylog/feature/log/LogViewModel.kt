package com.lezi.babylog.feature.log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RootPublicationState
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.designsystem.TimelineLaneSegment
import com.lezi.babylog.domain.carelog.CareAggregation
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.CustomRecordItem
import com.lezi.babylog.domain.carelog.DailySummary
import com.lezi.babylog.domain.carelog.DayChartCategories
import com.lezi.babylog.domain.timeline.TimelineCarePlanRow
import com.lezi.babylog.domain.timeline.TimelineRecordRow
import com.lezi.babylog.domain.timeline.TimelineWindowRepository
import com.lezi.babylog.domain.timeline.TimelineWindowRequest
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.session.ShallowSyncLine
import com.lezi.babylog.sync.session.ShallowSyncState
import com.lezi.babylog.sync.localCarePlanPublishLabel
import com.lezi.babylog.sync.localRecordPublishLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
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
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

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
    val shallowSyncLine: ShallowSyncLine = ShallowSyncLine(
        state = ShallowSyncState.Unjoined,
        text = "尚未加入家庭 · 数据仅保存在本机",
    ),
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
    private val layoutUndoSessions = LayoutUndoSessionStore()
    private val layoutUndoWrites = LayoutUndoWriteTracker(
        scope = viewModelScope,
        undoSessions = layoutUndoSessions,
        editSessions = layoutEditSessions,
        submitSnapshot = deviceLayoutWriter::submit,
    )
    internal val deviceLayoutWriteState = deviceLayoutWriter.state
    internal val layoutEditSession = layoutEditSessions.state
    /**
     * Layout undo candidate / write phase / stable offer deadline / exit-retry
     * chrome. Retained across configuration recreation with this ViewModel;
     * process death starts Idle.
     */
    internal val layoutUndoSession = layoutUndoSessions.state

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
    }.combine(syncPort.shallowStatus()) { state, shallowSyncLine ->
        state.copy(
            shallowSyncLine = shallowSyncLine,
            lastSyncFailed = shallowSyncLine.state in setOf(
                ShallowSyncState.Error,
                ShallowSyncState.ReauthRequired,
            ),
        )
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
        // Re-entering the editor discards any prior undo offer (same as Idle seed).
        layoutUndoSessions.clear()
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
        layoutUndoSessions.reduce(LayoutUndoEvent.EditorExited)
        layoutUndoSessions.setExitFlushInProgress(false)
        layoutUndoSessions.setExitAfterLayoutRetry(false)
        layoutUndoSessions.clearDismissedLayoutFailure()
        layoutEditSessions.close()
    }

    internal fun reduceLayoutUndoEvent(event: LayoutUndoEvent): LayoutUndoReduction =
        layoutUndoSessions.reduce(event)

    internal fun consumeLayoutUndoAnnouncement(): String? =
        layoutUndoSessions.consumeAnnouncement()

    /** Session clock remaining for the Available snackbar offer. */
    internal fun remainingLayoutUndoOfferMs(): Long = layoutUndoWrites.remainingOfferMs()

    /**
     * Apply a layout-edit intent (token + reduce + prefs + write + undo track).
     * Composition only handles drag-guidance completion from the returned receipt.
     */
    internal fun applyLayoutEditIntent(
        intent: LayoutEditIntent,
        knownKeys: Collection<String>,
    ): LayoutEditIntentWrite? = layoutUndoWrites.applyLayoutEditIntent(intent, knownKeys)

    /** Start an Available → Restoring reverse write when [token] still matches. */
    internal fun requestLayoutUndo(token: Long): Boolean =
        layoutUndoWrites.requestLayoutUndo(token)

    /** Retry RestoreFailed → Restoring on the retained session. */
    internal fun retryFailedLayoutUndo(): Boolean =
        layoutUndoWrites.retryFailedLayoutUndo()

    /**
     * Discard Available/AwaitingOriginal/Idle undo when leaving the editor.
     * Holds [LayoutUndoState.Restoring] and [LayoutUndoState.RestoreFailed] so an
     * in-flight reverse write can still settle to success or RestoreFailed.
     */
    internal fun discardLayoutUndoUnlessRestoreInFlight() {
        if (shouldHoldLayoutUndoAcrossEditorExit(layoutUndoSessions.current.state)) return
        layoutUndoSessions.reduce(LayoutUndoEvent.EditorExited)
    }

    internal fun dismissLayoutFailure(sequence: Long?) {
        layoutUndoSessions.dismissLayoutFailure(sequence)
    }

    internal fun clearDismissedLayoutFailure() {
        layoutUndoSessions.clearDismissedLayoutFailure()
    }

    internal fun setLayoutExitFlushInProgress(inProgress: Boolean) {
        layoutUndoSessions.setExitFlushInProgress(inProgress)
    }

    internal fun setExitAfterLayoutRetry(exitAfter: Boolean) {
        layoutUndoSessions.setExitAfterLayoutRetry(exitAfter)
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
                syncPort.syncWhenAvailable(SyncTrigger.PullToRefresh)
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
