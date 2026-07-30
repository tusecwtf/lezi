package com.lezi.babylog.feature.log

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lezi.babylog.core.model.MilkPayload
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.availableForNewEntry
import com.lezi.babylog.core.model.deviceLayoutSnapshot
import com.lezi.babylog.core.ui.RecordSection
import com.lezi.babylog.core.ui.UiTags
import com.lezi.babylog.core.ui.knownCatalogKeys
import com.lezi.babylog.core.ui.orderedRecordSections
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.core.ui.sortCatalogByLocalOrder
import com.lezi.babylog.designsystem.LeziRecordGlyph
import com.lezi.babylog.designsystem.LeziRecordGlyphIcon
import com.lezi.babylog.designsystem.LeziRecordColorRole
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziThemeExt
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.PageScaffoldBackground
import com.lezi.babylog.designsystem.TimelineLegendEntry
import com.lezi.babylog.designsystem.leziRecordColor
import com.lezi.babylog.domain.DayChartCategories
import com.lezi.babylog.domain.DayChartCategory
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.launch

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
    var listDeleteTarget by remember { mutableStateOf<ListDeleteTarget?>(null) }
    var layoutExitInProgress by remember { mutableStateOf(false) }
    var layoutDragCancelSignal by remember { mutableLongStateOf(0L) }
    var exitAfterLayoutRetry by remember { mutableStateOf(false) }
    var dismissedLayoutFailure by remember { mutableStateOf<Long?>(null) }
    var layoutUndoState by remember { mutableStateOf<LayoutUndoState>(LayoutUndoState.Idle) }
    var nextLayoutUndoToken by remember { mutableLongStateOf(0L) }
    val listState = rememberLazyListState()
    val timelineListState = rememberLogTimelineListState()
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
            LogTimelineList(
                state = state,
                listState = listState,
                managementState = timelineListState,
                journal = journal,
                today = today,
                zone = zone,
                nowMs = nowMs,
                selectedSummaryType = selectedSummaryType,
                selectableSummaryTypes = selectableSummaryTypes,
                onSelectSummary = ::selectSummary,
                dayChartFilter = dayChartFilter,
                onSelectDayChartCategory = { key ->
                    dayChartFilterState = reduceDayChartFilter(
                        reconciledDayChartFilterState,
                        DayChartFilterAction.Select(
                            categoryKey = key,
                            dayRecords = state.records,
                        ),
                    )
                },
                dayChartLegend = dayChartLegend,
                nowContentMinute = nowContentMinute,
                timelineViewportStart = timelineViewportStart,
                timelineViewportDuration = timelineViewportDuration,
                timelineAxis = timelineAxis,
                onTimelineViewportStartChange = { timelineViewportStart = it },
                filteredTimelineRecords = filteredTimelineRecords,
                onGoToday = onGoToday,
                onRefresh = vm::refresh,
                onOpenComposer = onOpenComposer,
                onRequestDelete = { listDeleteTarget = it },
                onOpenPublishChrome = { publishChromeRecord = it },
                onSkipCarePlan = vm::skipCarePlan,
                onMessage = onMessage,
                modifier = Modifier.weight(1f),
            )
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

    val layoutFailure = layoutWriteState as? DeviceLayoutWriteState.Failed
    val failedLayoutUndo = layoutUndoState as? LayoutUndoState.RestoreFailed
    fun dismissLayoutFailure() {
        dismissedLayoutFailure = layoutFailure?.sequence
        exitAfterLayoutRetry = false
    }
    fun retryLayoutFailure() {
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
                    completeLayoutUndoWrite(failedLayoutUndo.candidate.token, result)
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
    }
    fun dismissListDelete() {
        listDeleteTarget = null
        timelineListState.collapseSwipeRows()
    }

    LogDialogHost(
        showCustomManage = showCustomManage,
        customItems = state.customItems,
        onDismissCustomManage = { showCustomManage = false },
        onAddCustomItem = vm::addCustomItem,
        onUpdateCustomItem = vm::updateCustomItem,
        onDeleteCustomItem = vm::deleteCustomItem,
        inLayoutEdit = inLayoutEdit,
        layoutFailure = layoutFailure,
        failedLayoutUndo = failedLayoutUndo,
        dismissedLayoutFailure = dismissedLayoutFailure,
        layoutExitInProgress = layoutExitInProgress,
        onDismissLayoutFailure = ::dismissLayoutFailure,
        onRetryLayoutFailure = ::retryLayoutFailure,
        publishChromeRecord = publishChromeRecord,
        lastSyncFailed = state.lastSyncFailed,
        onDismissPublishChrome = { publishChromeRecord = null },
        onRetryPublishChrome = {
            publishChromeRecord = null
            vm.refresh()
        },
        onEditPublishChrome = { id ->
            publishChromeRecord = null
            onOpenComposer(RecordComposerRequest.Edit(id))
        },
        listDeleteTarget = listDeleteTarget,
        onDismissListDelete = ::dismissListDelete,
        onDeleteCarePlan = vm::deleteCarePlanFromList,
        onDeleteRecord = vm::deleteRecordFromList,
        onMessage = onMessage,
        showMore = showMore,
        settings = state.settings,
        onDismissMore = { showMore = false },
        onPickMore = { identity ->
            showMore = false
            openComposer(identity)
        },
        onLongPressMore = {
            showMore = false
            openLayoutEdit()
        },
    )
}

internal fun moreRecordContentDescription(entry: MoreCatalogEntry): String =
    "添加${entry.label}"
