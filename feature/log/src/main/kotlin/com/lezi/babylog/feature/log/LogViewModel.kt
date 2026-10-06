package com.lezi.babylog.feature.log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.common.failure.FailureKind
import com.lezi.babylog.core.common.LocalOpFailureCopy
import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.sync.session.familyFailureKind
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RootPublicationState
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.core.model.isWakeShortcutTarget
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
import com.lezi.babylog.domain.timeline.timelineRailRange
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.session.ShallowSyncLine
import com.lezi.babylog.sync.session.ShallowSyncState
import com.lezi.babylog.sync.localCarePlanPublishLabel
import com.lezi.babylog.sync.localRecordPublishLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    val autoAlignedDisplayClientUuids: Set<String> = emptySet(),
    val nearbySubtypeHints: Map<String, String> = emptyMap(),
    val sourceRecordsByDisplay: Map<String, List<Record>> = emptyMap(),
    val lastSyncFailed: Boolean = false,
    val refreshFailureKind: FailureKind? = null,
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
    private var ignoreResidualPan = false
    private val refreshing = MutableStateFlow(false)
    private val refreshFailureKind = MutableStateFlow<FailureKind?>(null)
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

    private val timelineClockKey = screenTimeFlow
        .map { it.zoneId to it.localDate }
        .distinctUntilChanged()
    /** Complete local days under the absolute viewport, plus a ±1-day load buffer. */
    private val timelineRailRangeFlow = timelineInteractionState
        .map { state ->
            timelineRailRange(
                viewportStartInclusiveMs = state.viewport.startInstantMs,
                viewportEndExclusiveMs = state.viewport.endInstantMs,
                zoneId = state.zoneId,
            )
        }
        .distinctUntilChanged()

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
        )
    }.combine(timelineClockKey) { bundle, clockKey ->
        bundle to clockKey
    }.combine(timelineRailRangeFlow) { pair, rail ->
        pair to rail
    }.flatMapLatest { (pair, rail) ->
        val (bundle, clockKey) = pair
        val (baby, babies, day, settings, customItems) = bundle
        val (zone, clockDate) = clockKey
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
            val windowNowMillis = if (clockDate == day) {
                // Stay on this civil day so overdue plans remain included.
                // Next midnight would make the window treat today as yesterday.
                day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
            } else {
                clockDate.atStartOfDay(zone).toInstant().toEpochMilli()
            }
            timelineWindowRepository.observe(
                TimelineWindowRequest(
                    babyId = baby.id,
                    selectedDay = day,
                    zoneId = zone,
                    nowMillis = windowNowMillis,
                    railStartMillis = rail.startMillis,
                    railEndMillis = rail.endMillis,
                ),
            ).combine(careLog.observeSourceRoleClientUuids()) { snapshot, sourceRoles ->
                snapshot to sourceRoles
            }.combine(careLog.observeAutoAlignedDisplayClientUuids()) { pair, autoAligned ->
                Triple(pair.first, pair.second, autoAligned)
            }.combine(syncPort.session()) { triple, session ->
                triple to session.lastSuccessAt
            }.combine(screenTimeFlow) { (triple, lastSuccessAt), screenTime ->
                Triple(triple, lastSuccessAt, screenTime)
            }.mapLatest { (triple, lastSuccessAt, screenTime) ->
                val (snapshot, sourceRoles, autoAligned) = triple
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
                val nowMs = screenTime.epochMillis
                val plans = snapshot.planRows.map(TimelineCarePlanRow::carePlan).sortedWith(
                    compareBy<CarePlan> {
                        if (it.effectiveStatus(nowMs) == CarePlanStatus.MISSED) 0 else 1
                    }.thenBy { it.scheduledAt },
                )
                val summary = bounds.toDailySummaryPreferMax()
                val lanes = buildTimelineLanes(
                    records = railRecords,
                    clipStartMs = snapshot.request.railStartMillis,
                    clipEndExclusiveMs = snapshot.request.railEndMillis,
                    zoneId = zone,
                    nowMs = screenTime.epochMillis,
                    familyJoined = snapshot.audience.isFamilyJoined,
                    lastSuccessAtMs = lastSuccessAt,
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
                    openSleep = snapshot.openSleep
                        ?.takeUnless { it.clientUuid in sourceRoles }
                        ?: snapshot.railRecordRows.asSequence()
                            .filter { it.record.clientUuid !in sourceRoles }
                            .mapNotNull { row ->
                                row.sleepInterval
                                    ?.takeIf(::isWakeShortcutTarget)
                                    ?.let { row.record }
                            }
                            .maxWithOrNull(
                                compareBy<Record> { it.timestamp }.thenBy { it.clientUuid },
                            ),
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
                    autoAlignedDisplayClientUuids = autoAligned,
                    nearbySubtypeHints = com.lezi.babylog.domain.carelog.NearbySubtypeHint.hints(
                        records = rawRecords,
                        hiddenClientUuids = sourceRoles,
                    ),
                    sourceRecordsByDisplay = careLog.sourceRecordsByDisplay(rawRecords + rawRail),
                )
            }
        }
    }.combine(refreshing) { state, isRefreshing ->
        state.copy(refreshing = isRefreshing)
    }.combine(refreshFailureKind) { state, kind ->
        state.copy(refreshFailureKind = kind)
    }.combine(syncPort.shallowStatus()) { state, shallowSyncLine ->
        state.copy(
            shallowSyncLine = shallowSyncLine,
            lastSyncFailed = shallowSyncLine.isError,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        LogUiState(day = initialScreenTime.localDate),
    )

    fun setExternalDay(day: LocalDate) {
        val current = timelineInteractionState.value
        if (day != current.selectedDay) {
            if (current.drag != null) ignoreResidualPan = true
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
        demoteStaleLiveAttachment(snapshot)
        return reduceTimeline(
            TimelineInteractionEvent.ClockAdvanced(
                nowMs = snapshot.epochMillis,
                zoneId = snapshot.zoneId,
            ),
        )
    }

    /**
     * The log screen can stay LiveAttached on an old "today" while another
     * screen moves the selected day back to that date after midnight. Re-entry
     * must browse that day instead of snapping the whole app forward.
     */
    private fun demoteStaleLiveAttachment(snapshot: RecordScreenTimeSnapshot) {
        val current = timelineInteractionState.value
        if (current.drag != null || current.mode != TimelineInteractionMode.LiveAttached) return
        val today = java.time.Instant.ofEpochMilli(snapshot.epochMillis)
            .atZone(snapshot.zoneId)
            .toLocalDate()
        val retained = current.selectedDay
        if (retained != dayFlow.value || !retained.isBefore(today)) return
        reduceTimeline(
            TimelineInteractionEvent.ExternalDaySelected(
                selectedDay = retained,
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
            // Storage failures surface through the same outcome channel the UI
            // already maps to a snackbar; an unguarded throw would crash the
            // process instead of letting the user retry.
            val outcome = runCatching {
                careLog.declareRecordEquivalent(recordClientUuid, equivalentToClientUuid)
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                SourceRelationOutcome.Rejected(
                    code = "local_error",
                    message = productUiError(error, LocalOpFailureCopy.DECLARE_DUPLICATE),
                )
            }
            onResult(outcome)
        }
    }

    /** Owner: resolve complete open group with chosen display UUID. */
    fun resolveDuplicateGroupAsOwner(
        memberClientUuids: List<String>,
        displayClientUuid: String,
        onResult: (SourceRelationOutcome) -> Unit = {},
    ) {
        viewModelScope.launch {
            val outcome = runCatching {
                careLog.resolveSuspectedDuplicateGroupAsOwner(
                    memberClientUuids = memberClientUuids,
                    displayClientUuid = displayClientUuid,
                )
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                SourceRelationOutcome.Rejected(
                    code = "local_error",
                    message = productUiError(error, LocalOpFailureCopy.RESOLVE_DUPLICATE),
                )
            }
            onResult(outcome)
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
            onDone(result.exceptionOrNull()?.let { productUiError(it, LocalOpFailureCopy.EDIT_WAKE) })
        }
    }

    fun withdrawWakeObservation(
        clientUuid: String,
        onDone: (String?) -> Unit = {},
    ) {
        viewModelScope.launch {
            val result = runCatching { careLog.withdrawWakeObservation(clientUuid) }
            onDone(result.exceptionOrNull()?.let { productUiError(it, LocalOpFailureCopy.WITHDRAW_WAKE) })
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
            onDone(result.exceptionOrNull()?.let { productUiError(it, LocalOpFailureCopy.PICK_WAKE) })
        }
    }

    internal fun selectTimelineCategory(categoryKey: String?, dayRecords: List<Record>) {
        reduceTimeline(TimelineInteractionEvent.SelectCategory(categoryKey, dayRecords))
    }

    internal fun changeTimelineDrag(
        gesture: TimelinePanGesture,
        nowMs: Long,
    ): Float {
        if (ignoreResidualPan) return 0f
        if (timelineInteractionState.value.drag == null) {
            reduceTimeline(TimelineInteractionEvent.DragStarted)
        }
        val result = TimelineInteraction.reduce(
            timelineInteractionState.value,
            TimelineInteractionEvent.DragChanged(
                deltaPx = gesture.deltaPx.toDouble(),
                widthPx = gesture.axisLengthPx.toDouble(),
                nowMs = nowMs,
            ),
        )
        timelineInteractionState.value = result.state
        return result.consumedPx
    }

    internal fun endTimelineDrag(nowMs: Long): LocalDate? {
        if (ignoreResidualPan) {
            ignoreResidualPan = false
            return null
        }
        return reduceTimeline(TimelineInteractionEvent.DragEnded(nowMs))
    }

    internal fun returnToNow(nowMs: Long): LocalDate? =
        reduceTimeline(
            TimelineInteractionEvent.ReturnToNow(
                nowMs = nowMs,
                zoneId = timelineInteractionState.value.zoneId,
            ),
        )

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
            onDone(result.exceptionOrNull()?.let { productUiError(it, LocalOpFailureCopy.ADD) })
        }
    }

    fun updateCustomItem(item: CustomRecordItem, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val result = runCatching { careLog.updateCustomItem(item) }
            onDone(
                result.exceptionOrNull()?.let {
                    productUiError(it, "没有保存成功，原有项目未变，可重试")
                },
            )
        }
    }

    fun deleteCustomItem(id: Long, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val result = runCatching { careLog.deleteCustomItem(id) }
            onDone(result.exceptionOrNull()?.let { productUiError(it, LocalOpFailureCopy.DELETE) })
        }
    }

    suspend fun canManageCustomItem(item: CustomRecordItem): Boolean =
        careLog.canManageCustomItem(item)

    fun refresh() {
        if (!refreshing.compareAndSet(expect = false, update = true)) return
        refreshFailureKind.value = null
        viewModelScope.launch {
            val result = try {
                syncPort.syncWhenAvailable(SyncTrigger.PullToRefresh)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Result.failure(error)
            }
            try {
                timelineWindowRepository.refreshMembers()
            } finally {
                refreshing.value = false
                refreshFailureKind.value = result.exceptionOrNull()?.let(::familyFailureKind)
            }
        }
    }

    fun consumeRefreshFailure() {
        refreshFailureKind.value = null
    }

    fun skipCarePlan(planId: Long, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                careLog.skipCarePlan(planId)
                "已跳过护理计划"
            }.fold(
                onSuccess = { Result.success(it) },
                onFailure = {
                    Result.failure(Exception(productUiError(it, LocalOpFailureCopy.SKIP_PLAN)))
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
                    Result.failure(Exception(productUiError(it, LocalOpFailureCopy.DELETE_PLAN)))
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
                    Result.failure(Exception(productUiError(it, LocalOpFailureCopy.DELETE_RECORD)))
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
        hasUnacceptedReceipt = metadata?.hasUnacceptedReceipt ?: false,
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
    hasUnacceptedReceipt = metadata?.hasUnacceptedReceipt ?: false,
)

private data class LogCombine(
    val baby: Baby?,
    val babies: List<Baby>,
    val day: LocalDate,
    val settings: SettingsLocal,
    val customItems: List<CustomRecordItem>,
)
