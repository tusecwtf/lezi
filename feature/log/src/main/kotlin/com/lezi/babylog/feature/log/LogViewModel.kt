package com.lezi.babylog.feature.log
import androidx.lifecycle.SavedStateHandle
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
import com.lezi.babylog.designsystem.TimelinePanGesture
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.CustomRecordItem
import com.lezi.babylog.domain.carelog.CareDayBounds
import com.lezi.babylog.domain.carelog.DailySummary
import com.lezi.babylog.domain.carelog.SourceRelationOutcome
import com.lezi.babylog.domain.carelog.SuspectedDuplicateGroup
import com.lezi.babylog.domain.carelog.TimelineDuplicateRow
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
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
    /** When non-null and [CareDayBounds.hasUncertainty], day summary shows min–max bounds. */
    val summaryBounds: CareDayBounds? = null,
    /** Soft open suspected-duplicate groups for the current record snapshot. */
    val openDuplicateGroups: List<SuspectedDuplicateGroup> = emptyList(),
    /** Timeline projection: containers + expanded sources + ordinary rows. */
    val timelineDuplicateRows: List<TimelineDuplicateRow> = emptyList(),
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
    val currentMembershipId: String = "",
    val familyOwner: Boolean = false,
    val lastSyncFailed: Boolean = false,
    val shallowSyncLine: ShallowSyncLine = ShallowSyncLine(
        state = ShallowSyncState.Unjoined,
        text = "尚未加入家庭 · 数据仅保存在本机",
    ),
)

@HiltViewModel
class LogViewModel @Inject constructor(
    private val careLog: CareLog,
    private val settingsStore: SettingsStore,
    private val syncPort: SyncPort,
    private val timelineWindowRepository: TimelineWindowRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val initialScreenTime = SystemRecordScreenClock.snapshot()
    private val screenTimeFlow = MutableStateFlow(initialScreenTime)
    private val dayFlow = MutableStateFlow(initialScreenTime.localDate)
    private val timelineInteractionState = MutableStateFlow(
        TimelineInteraction.reduce(
            null,
            TimelineInteractionEvent.Initialize(
                selectedDay = initialScreenTime.localDate,
                babyId = null,
                nowMs = initialScreenTime.epochMillis,
                zoneId = initialScreenTime.zoneId,
            ),
        ).state,
    )
    internal val timelineInteraction: StateFlow<TimelineInteractionState> =
        timelineInteractionState.asStateFlow()
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
            ).combine(careLog.observeSourceRoleClientUuids()) { snapshot, sourceRoles ->
                snapshot to sourceRoles
            }.mapLatest { (snapshot, sourceRoles) ->
                val rawRecords = snapshot.recordRows.map(TimelineRecordRow::record)
                val rawRail = snapshot.railRecordRows.map(TimelineRecordRow::record)
                val records = careLog.projectOrdinaryRecords(rawRecords, sourceRoles)
                val railRecords = careLog.projectOrdinaryRecords(rawRail, sourceRoles)
                val duplicateProjection = careLog.suspectedDuplicateProjection(
                    records = rawRail,
                    startDate = day,
                    dayCount = 1,
                    zone = zone,
                    now = screenTime.epochMillis,
                    sourceRoleClientUuids = sourceRoles,
                )
                val openGroups = duplicateProjection.openGroups
                val bounds = duplicateProjection.bounds.days.single()
                val duplicateMemberUuids = openGroups
                    .flatMapTo(linkedSetOf()) { it.memberClientUuids }
                val duplicatePresentationRecords = (rawRecords + rawRail.filter {
                    it.clientUuid in duplicateMemberUuids
                }).distinctBy { it.clientUuid }
                val duplicateRows = careLog.timelineDuplicateRows(
                    duplicatePresentationRecords,
                    sourceRoleClientUuids = sourceRoles,
                    projection = duplicateProjection,
                )
                val plans = snapshot.planRows.map(TimelineCarePlanRow::carePlan)
                val timelineAxis = ThreeDayTimelineAxis(day, zone)
                val summary = if (bounds.hasUncertainty) {
                    bounds.toDailySummaryPreferMax()
                } else {
                    bounds.toDailySummaryPreferMax()
                }
                val lanes = buildTimelineLanes(
                    records = railRecords,
                    axis = timelineAxis,
                    nowMs = screenTime.epochMillis,
                )
                val recordMetadata = snapshot.recordRows
                    .filter { it.record.clientUuid !in sourceRoles }
                    .associateBy { it.record.id }
                val planMetadata = snapshot.planRows.associateBy { it.carePlan.id }
                LogUiState(
                    loading = false,
                    baby = baby,
                    babies = babies,
                    day = day,
                    records = records,
                    summary = summary,
                    summaryBounds = bounds.takeIf { it.hasUncertainty },
                    openDuplicateGroups = openGroups,
                    timelineDuplicateRows = duplicateRows,
                    sleepLanes = lanes.sleep,
                    feedLanes = lanes.feed,
                    careLanes = lanes.care,
                    settings = settings,
                    openSleep = snapshot.openSleep,
                    uploaderLabels = snapshot.recordRows.mapNotNull { row ->
                        if (row.record.clientUuid in sourceRoles) null
                        else row.uploaderLabel?.let { row.record.id to it }
                    }.toMap(),
                    customItems = customItems,
                    pendingPlans = plans,
                    recordMetadata = recordMetadata,
                    planMetadata = planMetadata,
                    familyJoined = snapshot.audience.isFamilyJoined,
                    currentMembershipId = snapshot.audience.membershipId,
                    familyOwner = snapshot.audience.role ==
                        com.lezi.babylog.sync.session.FamilyRole.Owner,
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
        val current = timelineInteractionState.value
        if (day != current.selectedDay) {
            reduceTimeline(
                TimelineInteractionEvent.ExternalDaySelected(
                    selectedDay = day,
                    nowMs = screenTimeFlow.value.epochMillis,
                    zoneId = screenTimeFlow.value.zoneId,
                ),
            )
        }
        dayFlow.value = day
    }

    internal fun setScreenTime(snapshot: RecordScreenTimeSnapshot): LocalDate? {
        screenTimeFlow.value = snapshot
        return reduceTimeline(
            TimelineInteractionEvent.ClockAdvanced(
                nowMs = snapshot.epochMillis,
                zoneId = snapshot.zoneId,
            ),
        )
    }

    internal fun setTimelineBaby(babyId: Long?) {
        if (timelineInteractionState.value.babyId == babyId) return
        reduceTimeline(TimelineInteractionEvent.BabyChanged(babyId))
    }

    internal fun setTimelineRecords(records: List<Record>) {
        reduceTimeline(TimelineInteractionEvent.RecordsRefreshed(records))
    }

    /** Author: declare self-authored record equivalent to another source UUID. */
    fun declareDuplicateEquivalent(
        recordClientUuid: String,
        equivalentToClientUuid: String,
        onResult: (SourceRelationOutcome) -> Unit = {},
    ) {
        viewModelScope.launch {
            onResult(
                careLog.declareRecordEquivalent(recordClientUuid, equivalentToClientUuid),
            )
        }
    }

    /** Owner: resolve complete open group with chosen display UUID. */
    fun resolveDuplicateGroupAsOwner(
        memberClientUuids: List<String>,
        displayClientUuid: String,
        onResult: (SourceRelationOutcome) -> Unit = {},
    ) {
        viewModelScope.launch {
            onResult(
                careLog.resolveSuspectedDuplicateGroupAsOwner(
                    memberClientUuids = memberClientUuids,
                    displayClientUuid = displayClientUuid,
                ),
            )
        }
    }

    fun updateWakeObservation(
        clientUuid: String,
        wakeTimestamp: Long,
        note: String?,
        onDone: (String?) -> Unit = {},
    ) {
        viewModelScope.launch {
            val result = runCatching {
                careLog.updateWakeObservation(clientUuid, wakeTimestamp, note)
            }
            onDone(result.exceptionOrNull()?.let { productUiError(it, "修正失败，请重试") })
        }
    }

    fun withdrawWakeObservation(
        clientUuid: String,
        onDone: (String?) -> Unit = {},
    ) {
        viewModelScope.launch {
            val result = runCatching { careLog.withdrawWakeObservation(clientUuid) }
            onDone(result.exceptionOrNull()?.let { productUiError(it, "撤回失败，请重试") })
        }
    }

    fun selectEffectiveWakeObservation(
        sleepRecordClientUuid: String,
        wakeObservationClientUuid: String?,
        onDone: (String?) -> Unit = {},
    ) {
        viewModelScope.launch {
            val result = runCatching {
                careLog.selectEffectiveWakeObservation(
                    sleepRecordClientUuid,
                    wakeObservationClientUuid,
                )
            }
            onDone(result.exceptionOrNull()?.let { productUiError(it, "选择失败，请重试") })
        }
    }

    internal fun selectTimelineCategory(categoryKey: String?, dayRecords: List<Record>) {
        reduceTimeline(TimelineInteractionEvent.SelectCategory(categoryKey, dayRecords))
    }

    internal fun changeTimelineDrag(
        gesture: TimelinePanGesture,
        nowMs: Long,
    ) {
        if (timelineInteractionState.value.drag == null) {
            reduceTimeline(TimelineInteractionEvent.DragStarted)
        }
        reduceTimeline(
            TimelineInteractionEvent.DragChanged(
                cumulativeDeltaPx = gesture.cumulativeDeltaPx.toDouble(),
                effectiveWidthPx = gesture.axisLengthPx.toDouble(),
                nowMs = nowMs,
            ),
        )
    }

    internal fun endTimelineDrag(nowMs: Long): LocalDate? =
        reduceTimeline(TimelineInteractionEvent.DragEnded(nowMs))

    internal fun cancelTimelineDrag(nowMs: Long) {
        reduceTimeline(TimelineInteractionEvent.DragCancelled(nowMs))
    }

    private fun reduceTimeline(event: TimelineInteractionEvent): LocalDate? {
        val result = TimelineInteraction.reduce(timelineInteractionState.value, event)
        timelineInteractionState.value = result.state
        val selectedDay =
            (result.effect as? TimelineInteractionEffect.CommitSelectedDay)?.selectedDay
        if (selectedDay != null) dayFlow.value = selectedDay
        return selectedDay
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
): String? {
    // Causal open conflict is not a transport failure and must not read as “已同步”.
    if (!record.openConflictId.isNullOrBlank()) {
        return com.lezi.babylog.domain.carelog.SleepPresentation.CONFLICT_PENDING_LABEL
    }
    return localRecordPublishLabel(
        syncDirty = record.syncDirty,
        familyJoined = familyJoined,
        lastSyncFailed = lastSyncFailed,
        publicationState = metadata?.publicationState ?: RootPublicationState.NEVER_PUBLISHED,
    )
}

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
