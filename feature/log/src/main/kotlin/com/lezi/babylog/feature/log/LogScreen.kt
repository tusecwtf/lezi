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
