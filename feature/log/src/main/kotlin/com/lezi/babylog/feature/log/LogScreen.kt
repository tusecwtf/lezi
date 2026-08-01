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
    val layoutUndoSession by vm.layoutUndoSession.collectAsStateWithLifecycle()
    val layoutUndoState = layoutUndoSession.state
    val layoutExitInProgress = layoutUndoSession.exitFlushInProgress ||
        layoutUndoState is LayoutUndoState.Restoring
    val exitAfterLayoutRetry = layoutUndoSession.exitAfterLayoutRetry
    val dismissedLayoutFailure = layoutUndoSession.dismissedLayoutFailureSequence
    val screenTime by rememberRecordScreenTime(clock)
    val layoutGuidanceScope = rememberCoroutineScope()
    var showMore by remember { mutableStateOf(false) }
    var showCustomManage by remember { mutableStateOf(false) }
    var publishChromeRecord by remember { mutableStateOf<PublishChromeTarget?>(null) }
    var listDeleteTarget by remember { mutableStateOf<ListDeleteTarget?>(null) }
    var layoutDragCancelSignal by remember { mutableLongStateOf(0L) }
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
    LaunchedEffect(layoutUndoSession.pendingAnnouncement) {
        val announcement = vm.consumeLayoutUndoAnnouncement() ?: return@LaunchedEffect
        onMessage(announcement)
    }
    fun closeLayoutEditor() {
        vm.closeLayoutEditSession()
    }
    fun requestLayoutExit() {
        layoutDragCancelSignal += 1L
        if (layoutUndoSession.exitFlushInProgress) return
        // Hold Restoring / RestoreFailed so in-flight reverse writes still settle;
        // Available/AwaitingOriginal drop immediately so the snackbar does not linger.
        vm.discardLayoutUndoUnlessRestoreInFlight()
        vm.setLayoutExitFlushInProgress(true)
        vm.awaitDeviceLayoutWrites { result ->
            vm.setLayoutExitFlushInProgress(false)
            if (result.isSuccess) {
                closeLayoutEditor()
            } else {
                vm.setExitAfterLayoutRetry(true)
                vm.clearDismissedLayoutFailure()
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
                // Session clock only — never recompute with System.currentTimeMillis.
                val undoRemainingOfferMs = undoCandidate?.let {
                    vm.remainingLayoutUndoOfferMs()
                }
                fun applyLayoutIntent(
                    intent: LayoutEditIntent,
                    inputOrigin: LayoutGuidanceInputOrigin,
                ) {
                    val write = vm.applyLayoutEditIntent(intent, known) ?: return
                    // Drag-guidance completion is the only composition-local await;
                    // undo phase is owned by LayoutUndoWriteTracker on viewModelScope.
                    layoutGuidanceScope.launch {
                        val result = write.receipt.result.await()
                        val guidance = vm.currentLayoutEditSession()?.dragGuidance
                        if (
                            guidance != null &&
                            shouldRequestLayoutDragGuidanceCompletion(
                                state = guidance,
                                changed = write.nextPrefs != write.previousPrefs,
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
                    undoRemainingOfferMs = undoRemainingOfferMs,
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
                        vm.requestLayoutUndo(token)
                    },
                    onUndoExpired = { token ->
                        vm.reduceLayoutUndoEvent(LayoutUndoEvent.OfferExpired(token))
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
        vm.dismissLayoutFailure(layoutFailure?.sequence)
    }
    fun retryLayoutFailure() {
        if (failedLayoutUndo != null) {
            vm.setLayoutExitFlushInProgress(true)
            val started = vm.retryFailedLayoutUndo()
            if (!started) {
                // Session/editor gone or snapshot drifted: drop failed undo chrome.
                if (vm.currentLayoutEditSession() == null) {
                    vm.reduceLayoutUndoEvent(LayoutUndoEvent.EditorExited)
                }
                vm.setLayoutExitFlushInProgress(false)
                return
            }
            // Busy chrome is projected from Restoring / exitFlushInProgress on the
            // retained session; completion runs on viewModelScope. Watch session for
            // exit-after-retry without a second composition await of the receipt.
        } else {
            vm.setLayoutExitFlushInProgress(true)
            vm.retryDeviceLayoutWrite { result ->
                vm.setLayoutExitFlushInProgress(false)
                if (result.isSuccess && exitAfterLayoutRetry) {
                    closeLayoutEditor()
                }
            }
        }
    }
    // After a restore settles to Idle while the user had already tried to leave,
    // close the editor. Busy chrome is cleared by LayoutUndoWriteTracker on settle.
    LaunchedEffect(
        layoutUndoState,
        layoutUndoSession.exitAfterLayoutRetry,
        layoutWriteState,
    ) {
        if (!layoutUndoSession.exitAfterLayoutRetry) return@LaunchedEffect
        if (layoutUndoState !is LayoutUndoState.Idle) return@LaunchedEffect
        if (layoutWriteState is DeviceLayoutWriteState.Failed) return@LaunchedEffect
        if (layoutWriteState is DeviceLayoutWriteState.Saving) return@LaunchedEffect
        closeLayoutEditor()
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
