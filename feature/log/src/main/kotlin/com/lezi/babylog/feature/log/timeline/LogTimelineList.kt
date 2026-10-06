package com.lezi.babylog.feature.log.timeline
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.RootPublicationState
import com.lezi.babylog.core.model.SleepPayload
import com.lezi.babylog.core.model.displayLabel
import com.lezi.babylog.core.ui.RecordSummaryStrip
import com.lezi.babylog.core.ui.RecordSummaryValue
import com.lezi.babylog.core.ui.RecordTypeIcon
import com.lezi.babylog.core.ui.presentationSummary
import com.lezi.babylog.core.ui.presentationTone
import com.lezi.babylog.designsystem.LeziMotion
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziThemeExt
import com.lezi.babylog.designsystem.LeziTone
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.RecordRow
import com.lezi.babylog.designsystem.SectionHeading
import com.lezi.babylog.designsystem.TransientShallowSyncStatus
import com.lezi.babylog.designsystem.StateContainer
import com.lezi.babylog.designsystem.StateKind
import com.lezi.babylog.designsystem.SummaryMetric
import com.lezi.babylog.designsystem.SwipeEditDeleteRow
import com.lezi.babylog.designsystem.TimelineLegendEntry
import com.lezi.babylog.designsystem.TimelineRailCard
import com.lezi.babylog.designsystem.TimelinePanGesture
import com.lezi.babylog.designsystem.LeziTextButton
import com.lezi.babylog.designsystem.leziMotionMillis
import com.lezi.babylog.domain.carelog.DayChartCategory
import com.lezi.babylog.domain.carelog.DuplicateGroupAction
import com.lezi.babylog.domain.carelog.formatClock
import com.lezi.babylog.domain.carelog.relativeTimeLabel
import com.lezi.babylog.sync.localCarePlanPublishDetail
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

@Stable
internal class LogTimelineListState {
    var revealedSwipeRowId by mutableStateOf<String?>(null)
    var skipActionState by mutableStateOf<ManagementActionState>(ManagementActionState.Idle)

    fun collapseSwipeRows() {
        revealedSwipeRowId = null
    }
}

@Composable
internal fun rememberLogTimelineListState(): LogTimelineListState =
    remember { LogTimelineListState() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogTimelineList(
    state: LogUiState,
    listState: LazyListState,
    managementState: LogTimelineListState,
    journal: Boolean,
    today: LocalDate,
    zone: ZoneId,
    nowMs: Long,
    selectedSummaryType: RecordType?,
    selectableSummaryTypes: Set<RecordType>,
    onSelectSummary: (RecordType) -> Unit,
    dayChartFilter: DayChartCategory?,
    onSelectDayChartCategory: (String?) -> Unit,
    dayChartLegend: List<TimelineLegendEntry>,
    timelineInteraction: StateFlow<TimelineInteractionState>,
    onTimelinePan: (TimelinePanGesture) -> Float,
    onTimelinePanEnd: () -> Unit,
    onTimelinePanCancel: () -> Unit,
    filteredTimelineRecords: List<Record>,
    onReturnToNow: () -> Unit,
    onRefresh: () -> Unit,
    onOpenComposer: (RecordComposerRequest) -> Unit,
    onRequestDelete: (ListDeleteTarget) -> Unit,
    onOpenPublishChrome: (PublishChromeTarget) -> Unit,
    onOpenCausalDetails: (Record) -> Unit,
    onDuplicateAction: (DuplicateGroupAction) -> Unit,
    onSkipCarePlan: (Long, (Result<String>) -> Unit) -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Collected here (not passed as a precomputed frame) so per-frame pan
    // emissions recompose only this host — the route digests the discrete
    // summary selection instead. Derivation and downstream behavior are
    // unchanged from the route-computed railFrame/timelineMode.
    val interaction by timelineInteraction.collectAsStateWithLifecycle()
    val timelineMode = interaction.mode
    val railFrame = interaction.toTimelineRailFrame(nowMs)
    val collapsedDuplicateGroups = remember { mutableStateListOf<String>() }
    val expandedAutoAligned = remember { mutableStateListOf<String>() }
    val recordsByUuid = remember(state.records) {
        state.records.associateBy(Record::clientUuid)
    }
    fun openEditFromSwipe(request: RecordComposerRequest) {
        managementState.collapseSwipeRows()
        onOpenComposer(request)
    }

    fun requestListDelete(target: ListDeleteTarget) {
        managementState.collapseSwipeRows()
        onRequestDelete(target)
    }

    fun requestSkip(planId: Long): Boolean {
        val request = ManagementActionRequest(ManagementActionKind.SkipPlan, planId)
        val started = beginManagementAction(managementState.skipActionState, request)
        managementState.skipActionState = started.state
        if (!started.accepted) return false

        onSkipCarePlan(planId) { result ->
            val finished = finishManagementAction(
                managementState.skipActionState,
                request,
                result,
            )
            if (finished.accepted) {
                managementState.skipActionState = finished.state
                if (result.isSuccess) finished.announcement?.let(onMessage)
            }
        }
        return true
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling) managementState.collapseSwipeRows()
            }
    }

    val density = LeziThemeExt.density
    val pageHorizontal = when {
        LeziThemeExt.isElder -> density.panelContent
        journal -> LeziSpacing.Page
        else -> density.panelContent
    }
    val listGap = when {
        LeziThemeExt.isElder -> density.sectionGap
        journal -> 0.dp
        else -> density.sectionGap
    }

    // Duplicate-group pipelines live here (composable scope) so remember can
    // key on the actual inputs: the LazyListScope content re-executes on every
    // recomposition of this screen (minute tick, sync badges), but these
    // passes over the day's records only need to re-run when the records,
    // groups, or collapse selections change.
    val collapsedGroupsKey = collapsedDuplicateGroups.toSet()
    val visibleDuplicateGroups = remember(filteredTimelineRecords, state.openDuplicateGroups) {
        val filteredUuids = filteredTimelineRecords.mapTo(hashSetOf(), Record::clientUuid)
        state.openDuplicateGroups.filter { group ->
            group.memberClientUuids.any { it in filteredUuids }
        }
    }
    val displayedTimelineRecords = remember(
        filteredTimelineRecords,
        visibleDuplicateGroups,
        collapsedGroupsKey,
    ) {
        val collapsedMemberUuids = visibleDuplicateGroups
            .filter { it.groupId in collapsedGroupsKey }
            .flatMapTo(hashSetOf()) { it.memberClientUuids }
        filteredTimelineRecords.filter { it.clientUuid !in collapsedMemberUuids }
    }

    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = onRefresh,
        modifier = modifier,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = LeziSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(listGap),
        ) {
            item {
                Column(
                    Modifier.padding(horizontal = pageHorizontal),
                ) {
                    TransientShallowSyncStatus(
                        text = state.shallowSyncLine.text,
                        isError = state.shallowSyncLine.isError,
                        isUserRefreshing = state.refreshing,
                        contentTestTag = "log_shallow_sync_status",
                    )
                    val daySummaryColumns = logDaySummaryColumns(
                        state.summary,
                        state.summaryBounds,
                    )
                    if (journal) {
                        RecordSummaryStrip(
                            values = daySummaryColumns.map { column ->
                                RecordSummaryValue(
                                    column.type,
                                    column.compactValue,
                                    column.compactLabel,
                                )
                            },
                            selectedType = selectedSummaryType,
                            selectableTypes = selectableSummaryTypes,
                            onSelect = onSelectSummary,
                        )
                    } else if (LeziThemeExt.isElder) {
                        val columns = LeziThemeExt.structure.summaryColumnCount
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
                        ) {
                            daySummaryColumns.chunked(columns).forEach { rowItems ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
                                ) {
                                    rowItems.forEach { column ->
                                        val selected = selectedSummaryType == column.type
                                        SummaryMetric(
                                            value = column.value,
                                            label = column.label,
                                            tone = column.tone,
                                            modifier = Modifier.weight(1f),
                                            icon = { RecordTypeIcon(column.type) },
                                            selected = selected,
                                            selectionLabel = "${column.spokenValue}，${
                                                if (selected) "已筛选" else "点按筛选"
                                            }",
                                            onClick = if (column.type in selectableSummaryTypes) {
                                                { onSelectSummary(column.type) }
                                            } else {
                                                null
                                            },
                                        )
                                    }
                                    repeat(columns - rowItems.size) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
                        ) {
                            daySummaryColumns.forEach { column ->
                                val selected = selectedSummaryType == column.type
                                SummaryMetric(
                                    value = column.value,
                                    label = column.label,
                                    tone = column.tone,
                                    modifier = Modifier.weight(1f),
                                    icon = { RecordTypeIcon(column.type) },
                                    selected = selected,
                                    selectionLabel = "${column.spokenValue}，${
                                        if (selected) "已筛选" else "点按筛选"
                                    }",
                                    onClick = if (column.type in selectableSummaryTypes) {
                                        { onSelectSummary(column.type) }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                }
            }

            item {
                TimelineRailCard(
                    sleep = state.sleepLanes,
                    feed = state.feedLanes,
                    care = state.careLanes,
                    recordCount = state.records.size,
                    nowMs = railFrame.nowMs,
                    selectedCategoryKey = dayChartFilter?.name,
                    onCategorySelect = onSelectDayChartCategory,
                    legend = dayChartLegend,
                    viewportStartMs = railFrame.viewportStartMs,
                    viewportDurationMs = railFrame.viewportDurationMs,
                    dayBoundariesMs = railFrame.dayBoundariesMs,
                    dayBoundaryLabels = railFrame.dayBoundaryLabels,
                    primaryRangeMs = railFrame.primaryRangeMs,
                    hourTicks = railFrame.hourTicks,
                    onHorizontalPan = onTimelinePan,
                    onPanEnd = onTimelinePanEnd,
                    onPanCancel = onTimelinePanCancel,
                    titleSecondary = "时间轴",
                    modifier = Modifier
                        .padding(horizontal = pageHorizontal)
                        .testTag("log_timeline_rail"),
                )
            }

            if (timelineMode == TimelineInteractionMode.Browsing) {
                item {
                    Row(
                        Modifier.padding(horizontal = pageHorizontal),
                    ) {
                        LogTimelineReturnToNowButton(
                            selectedDay = state.day,
                            today = today,
                            onClick = onReturnToNow,
                        )
                    }
                }
            }

            if (state.pendingPlans.isNotEmpty()) {
                item {
                    Column(
                        Modifier.padding(horizontal = pageHorizontal),
                    ) {
                        SectionHeading(
                            eyebrow = "待履行",
                            title = "护理计划",
                            meta = "${state.pendingPlans.size}",
                        )
                    }
                }
                items(
                    state.pendingPlans,
                    key = { "plan-${it.id}" },
                    contentType = { "care_plan" },
                ) { plan ->
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
                    val planRevealed = managementState.revealedSwipeRowId == planRowId
                    val planSkipRequest = ManagementActionRequest(
                        ManagementActionKind.SkipPlan,
                        plan.id,
                    )
                    val skipFeedback = managementActionFeedback(
                        managementState.skipActionState,
                        planSkipRequest,
                    )
                    val skipRunning =
                        managementState.skipActionState ==
                            ManagementActionState.Running(planSkipRequest)
                    val skipBlocked =
                        managementState.skipActionState is ManagementActionState.Running
                    val skipFailed =
                        (managementState.skipActionState as? ManagementActionState.Failed)
                            ?.request == planSkipRequest
                    val editPlanAction = {
                        openEditFromSwipe(RecordComposerRequest.EditPlan(plan.id))
                    }
                    val deletePlanAction = {
                        requestListDelete(ListDeleteTarget.Plan(plan))
                    }
                    val skipPlanAction: () -> Boolean = { requestSkip(plan.id) }
                    Column(
                        Modifier
                            .padding(horizontal = pageHorizontal)
                            .testTag("pending_care_plan_${plan.id}"),
                    ) {
                        SwipeEditDeleteRow(
                            open = planRevealed,
                            onOpenChange = { open ->
                                managementState.revealedSwipeRowId = if (open) planRowId else {
                                    managementState.revealedSwipeRowId.takeUnless {
                                        it == planRowId
                                    }
                                }
                            },
                            editEnabled = canEditPlan,
                            deleteEnabled = canDeletePlan,
                            onEdit = editPlanAction,
                            onDelete = deletePlanAction,
                            editTestTag = "timeline_swipe_edit_plan_${plan.id}",
                            deleteTestTag = "timeline_swipe_delete_plan_${plan.id}",
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RecordRow(
                                    time = formatClock(plan.scheduledAt, zone),
                                    title = title,
                                    summary = planSummary,
                                    relative = relativeTimeLabel(plan.scheduledAt, nowMs),
                                    tone = if (isMissed || planPublishLabel != null) {
                                        LeziTone.Yellow
                                    } else {
                                        LeziTone.Blue
                                    },
                                    anomaly = false,
                                    leading = { RecordTypeIcon(plan.type) },
                                    onClick = {
                                        if (planRevealed) {
                                            managementState.collapseSwipeRows()
                                        } else {
                                            managementState.collapseSwipeRows()
                                            if (canFulfillPlan) {
                                                onOpenComposer(
                                                    RecordComposerRequest.Fulfill(plan.id),
                                                )
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
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
                                // Skip lives in the card trailing area, next to the swipe actions.
                                if (canSkipPlan) {
                                    LeziTextButton(label = when {
                                                skipRunning -> "跳过中…"
                                                skipFeedback != null -> "再试一次"
                                                else -> "跳过"
                                            }, onClick = { skipPlanAction() }, enabled = !skipBlocked, modifier = Modifier.testTag("care_plan_skip_${plan.id}"))
                                }
                            }
                        }
                        if (canSkipPlan && skipFeedback != null) {
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

            item {
                Column(
                    Modifier.padding(horizontal = pageHorizontal),
                ) {
                    SectionHeading(title = "记录")
                }
            }

            items(
                    visibleDuplicateGroups,
                    key = { "duplicate-group-${it.groupId}" },
                    contentType = { "duplicate_group" },
                ) { group ->
                DuplicateGroupCard(
                    group = group,
                    recordsByUuid = recordsByUuid,
                    recordRowsById = state.recordMetadata,
                    currentMembershipId = state.currentMembershipId,
                    isOwner = state.familyOwner,
                    expanded = group.groupId !in collapsedDuplicateGroups,
                    onToggle = {
                        if (group.groupId in collapsedDuplicateGroups) {
                            collapsedDuplicateGroups.remove(group.groupId)
                        } else {
                            collapsedDuplicateGroups.add(group.groupId)
                        }
                    },
                    onAction = onDuplicateAction,
                    modifier = Modifier.padding(horizontal = pageHorizontal),
                )
            }

            if (
                state.loading ||
                (displayedTimelineRecords.isEmpty() && visibleDuplicateGroups.isEmpty())
            ) {
                item(key = "records_phase", contentType = "records_phase") {
                    // Loading/empty crossfade rides the Base tier — 250 was
                    // visually indistinguishable from Base 200 (motion-polish
                    // ticket 03); the token also makes reduce-motion instant.
                    val phaseMs = leziMotionMillis(LeziMotion.Base)
                    Crossfade(
                        targetState = state.loading,
                        animationSpec = tween(phaseMs),
                        label = "recordsPhase",
                        modifier = Modifier.animateItem(),
                    ) { loading ->
                        if (loading) {
                            StateContainer(
                                kind = StateKind.Loading,
                                title = "加载中",
                                message = "正在读取当日记录…",
                                modifier = Modifier
                                    .padding(horizontal = pageHorizontal)
                                    .testTag("log_records_loading"),
                            )
                        } else {
                            val emptyState = timelineRecordsEmptyState(dayChartFilter)
                            StateContainer(
                                kind = StateKind.Empty,
                                title = emptyState.title,
                                message = emptyState.message,
                                modifier = Modifier
                                    .padding(horizontal = pageHorizontal)
                                    .testTag(emptyState.testTag),
                            )
                        }
                    }
                }
            } else {
                items(
                    displayedTimelineRecords,
                    key = { it.id },
                    contentType = { "record" },
                ) { record ->
                    val title = record.displayLabel()
                    val recordMetadata = state.recordMetadata[record.id]
                    val recordCapabilities = recordMetadata?.capabilities
                    val canEditRecord = recordCapabilities?.canEdit == true
                    val canDeleteRecord = recordCapabilities?.canDelete == true
                    val publicationState = recordMetadata?.publicationState
                        ?: RootPublicationState.NEVER_PUBLISHED
                    val publishLabel = timelineRecordPublishLabel(
                        record = record,
                        metadata = recordMetadata,
                        familyJoined = state.familyJoined,
                        lastSyncFailed = state.lastSyncFailed,
                    )
                    val sleepBadge = recordMetadata?.sleepEndBadge
                    val conflictBadge = recordMetadata?.conflictSummaryLabel
                    val autoAligned = record.clientUuid in state.autoAlignedDisplayClientUuids
                    val nearbyHint = state.nearbySubtypeHints[record.clientUuid]
                    val alignedBadge = if (autoAligned) "多台设备记了同一件事 · 可展开核对" else null
                    val recordRowId = "record-${record.id}"
                    val recordRevealed = managementState.revealedSwipeRowId == recordRowId
                    val editRecordAction = {
                        openEditFromSwipe(RecordComposerRequest.Edit(record.id))
                    }
                    val deleteRecordAction = {
                        requestListDelete(ListDeleteTarget.RecordItem(record))
                    }
                    Column(Modifier.animateItem()) {
                    val openRecordBody = {
                        when (
                            decideTimelineRecordBodyClick(
                                recordId = record.id,
                                swipeRevealed = recordRevealed,
                                hasCausalIntercept =
                                    recordMetadata?.conflictSummaryLabel != null ||
                                        recordMetadata?.sleepInterval?.isOverlapPending == true ||
                                        recordMetadata?.wakeObservations?.isNotEmpty() == true,
                                hasPublishLabel = publishLabel != null,
                                canEdit = canEditRecord,
                            )
                        ) {
                            TimelineRecordBodyClick.CollapseSwipe -> {
                                managementState.collapseSwipeRows()
                            }
                            TimelineRecordBodyClick.OpenCausalDetails -> {
                                managementState.collapseSwipeRows()
                                onOpenCausalDetails(record)
                            }
                            TimelineRecordBodyClick.OpenPublishChrome -> {
                                managementState.collapseSwipeRows()
                                onOpenPublishChrome(
                                    PublishChromeTarget(
                                        recordId = record.id,
                                        title = title,
                                        publicationState = publicationState,
                                        clientUuid = record.clientUuid,
                                        hasUnacceptedReceipt =
                                            recordMetadata?.hasUnacceptedReceipt == true,
                                    ),
                                )
                            }
                            is TimelineRecordBodyClick.OpenComposer -> {
                                managementState.collapseSwipeRows()
                                onOpenComposer(
                                    recordComposerOpenRequest(
                                        recordId = record.id,
                                        canEdit = canEditRecord,
                                    ),
                                )
                            }
                        }
                    }
                    SwipeEditDeleteRow(
                        open = recordRevealed,
                        onOpenChange = { open ->
                            managementState.revealedSwipeRowId = if (open) recordRowId else {
                                managementState.revealedSwipeRowId.takeUnless {
                                    it == recordRowId
                                }
                            }
                        },
                        editEnabled = canEditRecord,
                        deleteEnabled = canDeleteRecord,
                        onEdit = editRecordAction,
                        onDelete = deleteRecordAction,
                        editTestTag = "timeline_swipe_edit_record_${record.id}",
                        deleteTestTag = "timeline_swipe_delete_record_${record.id}",
                        modifier = Modifier
                            .animateItem()
                            .padding(horizontal = pageHorizontal),
                    ) {
                        RecordRow(
                            time = formatClock(record.timestamp, zone),
                            title = title,
                            summary = timelineRecordSummary(
                                recordSummaryLine(record),
                                state.uploaderLabels[record.id],
                                listOfNotNull(
                                    sleepBadge,
                                    conflictBadge,
                                    publishLabel,
                                    alignedBadge,
                                    nearbyHint,
                                )
                                    .distinct()
                                    .joinToString(" · ")
                                    .takeIf { it.isNotBlank() },
                            ),
                            relative = relativeTimeLabel(record.timestamp, nowMs),
                            meta = if (record.note.isNullOrBlank()) "" else "有备注",
                            tone = record.type.presentationTone(),
                            // Anomaly is payload-only. Projected null end means open SleepStart,
                            // not a broken/anomalous closed wake interval.
                            anomaly =
                                (record.payload.payload as? SleepPayload)?.anomaly == true,
                            leading = { RecordTypeIcon(record.type) },
                            onClick = openRecordBody,
                            modifier = Modifier
                                .managementActions(
                                    rowManagementCustomActions(
                                        targetLabel = "${title}记录",
                                        canEdit = canEditRecord,
                                        canDelete = canDeleteRecord,
                                        onEdit = editRecordAction,
                                        onView = openRecordBody,
                                        onDelete = deleteRecordAction,
                                    ),
                                )
                                .semantics {
                                    contentDescription = timelineRecordRowContentDescription(
                                        title = title,
                                        publishLabel = publishLabel,
                                        canEdit = canEditRecord,
                                    )
                                },
                        )
                    }
                    if (autoAligned) {
                        val expanded = record.clientUuid in expandedAutoAligned
                        LeziTextButton(
                            if (expanded) "收起来源" else "展开来源",
                            onClick = {
                                if (expanded) {
                                    expandedAutoAligned.remove(record.clientUuid)
                                } else {
                                    expandedAutoAligned.add(record.clientUuid)
                                }
                            },
                            modifier = Modifier
                                .padding(horizontal = pageHorizontal)
                                .testTag("auto_aligned_toggle_${record.clientUuid}"),
                        )
                        if (expanded) {
                            state.sourceRecordsByDisplay[record.clientUuid].orEmpty().forEach { source ->
                                Text(
                                    "来源 · ${source.displayLabel()} · ${recordSummaryLine(source)}",
                                    style = LeziTypography.Meta,
                                    modifier = Modifier
                                        .padding(horizontal = pageHorizontal)
                                        .testTag("auto_aligned_source_${source.clientUuid}"),
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

internal const val LOG_TIMELINE_RETURN_TO_NOW_TAG = "log_timeline_return_to_now"

@Composable
internal fun LogTimelineReturnToNowButton(
    selectedDay: LocalDate,
    today: LocalDate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = if (selectedDay != today) "返回今天" else "回到现在"
    LeziSecondaryButton(
        label,
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .testTag(LOG_TIMELINE_RETURN_TO_NOW_TAG),
    )
}

internal fun recordSummaryLine(record: Record): String = record.presentationSummary()
