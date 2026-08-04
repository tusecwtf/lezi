package com.lezi.babylog.feature.log.timeline
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableStateOf
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
import com.lezi.babylog.core.model.formatRecordDuration
import com.lezi.babylog.core.ui.RecordSummaryStrip
import com.lezi.babylog.core.ui.RecordSummaryValue
import com.lezi.babylog.core.ui.RecordTypeIcon
import com.lezi.babylog.core.ui.presentationSummary
import com.lezi.babylog.core.ui.presentationTone
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziThemeExt
import com.lezi.babylog.designsystem.LeziTone
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.RecordRow
import com.lezi.babylog.designsystem.SectionHeading
import com.lezi.babylog.designsystem.StateContainer
import com.lezi.babylog.designsystem.StateKind
import com.lezi.babylog.designsystem.SummaryMetric
import com.lezi.babylog.designsystem.SwipeEditDeleteRow
import com.lezi.babylog.designsystem.TimelineLegendEntry
import com.lezi.babylog.designsystem.TimelineRailCard
import com.lezi.babylog.designsystem.LeziTextButton
import com.lezi.babylog.domain.carelog.DayChartCategory
import com.lezi.babylog.domain.carelog.formatClock
import com.lezi.babylog.domain.carelog.relativeTimeLabel
import com.lezi.babylog.sync.localCarePlanPublishDetail
import com.lezi.babylog.sync.session.ShallowSyncState
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

/** Data-driven spec for the warm-mode summary metric cards (one per record type). */
private data class SummaryMetricSpec(
    val type: RecordType,
    val tone: LeziTone,
    val label: String,
    val value: String,
    /** Spoken form used by the selection accessibility label. */
    val spokenValue: String,
)

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
    nowContentMinute: Int?,
    timelineViewportStart: Int,
    timelineViewportDuration: Int,
    timelineAxis: ThreeDayTimelineAxis,
    onTimelineViewportStartChange: (Int) -> Unit,
    filteredTimelineRecords: List<Record>,
    onGoToday: () -> Unit,
    onRefresh: () -> Unit,
    onOpenComposer: (RecordComposerRequest) -> Unit,
    onRequestDelete: (ListDeleteTarget) -> Unit,
    onOpenPublishChrome: (PublishChromeTarget) -> Unit,
    onSkipCarePlan: (Long, (Result<String>) -> Unit) -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
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
    val pageHorizontal = if (journal) 0.dp else density.panelContent

    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = onRefresh,
        modifier = modifier,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = LeziSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(
                if (journal) 0.dp else density.sectionGap,
            ),
        ) {
            item {
                Column(
                    Modifier.padding(horizontal = pageHorizontal),
                ) {
                    Text(
                        text = state.shallowSyncLine.text,
                        style = LeziTypography.Meta,
                        color = if (
                            state.shallowSyncLine.state in setOf(
                                ShallowSyncState.Error,
                                ShallowSyncState.ReauthRequired,
                            )
                        ) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .testTag("log_shallow_sync_status")
                            .padding(bottom = LeziSpacing.Sm),
                    )
                    if (journal) {
                        RecordSummaryStrip(
                            values = listOf(
                                RecordSummaryValue(
                                    RecordType.FORMULA,
                                    "${state.summary.feedMl}",
                                    "奶ml",
                                ),
                                RecordSummaryValue(
                                    RecordType.NURSING,
                                    "${state.summary.nursingMinutes}m",
                                    "母乳",
                                ),
                                RecordSummaryValue(
                                    RecordType.SLEEP,
                                    formatRecordDuration(state.summary.sleepMinutes),
                                    "睡眠",
                                ),
                                RecordSummaryValue(
                                    RecordType.PEE,
                                    "${state.summary.peeCount}",
                                    "尿",
                                ),
                                RecordSummaryValue(
                                    RecordType.POOP,
                                    "${state.summary.poopCount}",
                                    "便",
                                ),
                            ),
                            selectedType = selectedSummaryType,
                            selectableTypes = selectableSummaryTypes,
                            onSelect = onSelectSummary,
                        )
                    } else {
                        val summaryMetrics = listOf(
                            SummaryMetricSpec(
                                type = RecordType.FORMULA,
                                tone = LeziTone.Blue,
                                label = "奶量",
                                value = "${state.summary.feedMl}ml",
                                spokenValue = "奶量 ${state.summary.feedMl}毫升",
                            ),
                            SummaryMetricSpec(
                                type = RecordType.NURSING,
                                tone = LeziTone.Blue,
                                label = "母乳",
                                value = "${state.summary.nursingMinutes}min",
                                spokenValue = "母乳 ${state.summary.nursingMinutes}分钟",
                            ),
                            SummaryMetricSpec(
                                type = RecordType.SLEEP,
                                tone = LeziTone.Yellow,
                                label = "睡眠",
                                value = formatRecordDuration(state.summary.sleepMinutes),
                                spokenValue =
                                    "睡眠 ${formatRecordDuration(state.summary.sleepMinutes)}",
                            ),
                            SummaryMetricSpec(
                                type = RecordType.PEE,
                                tone = LeziTone.Cream,
                                label = "尿尿",
                                value = "${state.summary.peeCount}次",
                                spokenValue = "尿尿 ${state.summary.peeCount}次",
                            ),
                            SummaryMetricSpec(
                                type = RecordType.POOP,
                                tone = LeziTone.Neutral,
                                label = "便便",
                                value = "${state.summary.poopCount}次",
                                spokenValue = "便便 ${state.summary.poopCount}次",
                            ),
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
                        ) {
                            summaryMetrics.forEach { spec ->
                                val selected = selectedSummaryType == spec.type
                                SummaryMetric(
                                    value = spec.value,
                                    label = spec.label,
                                    tone = spec.tone,
                                    modifier = Modifier.weight(1f),
                                    icon = { RecordTypeIcon(spec.type) },
                                    selected = selected,
                                    selectionLabel = "${spec.spokenValue}，${
                                        if (selected) "已筛选" else "点按筛选"
                                    }",
                                    onClick = if (spec.type in selectableSummaryTypes) {
                                        { onSelectSummary(spec.type) }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // ViewModel already gates this on the D−1 / D / D+1 rail-record union.
            if (state.showDayChart) {
                item {
                    TimelineRailCard(
                        sleep = state.sleepLanes,
                        feed = state.feedLanes,
                        care = state.careLanes,
                        recordCount = state.records.size,
                        nowContentMinute = nowContentMinute,
                        selectedCategoryKey = dayChartFilter?.name,
                        onCategorySelect = onSelectDayChartCategory,
                        legend = dayChartLegend,
                        viewportStartMinutes = timelineViewportStart,
                        viewportDurationMinutes = timelineViewportDuration,
                        windowGeometry = timelineAxis.windowGeometry,
                        hourLabels = timelineAxis.hourLabels(),
                        onViewportStartChange = onTimelineViewportStartChange,
                        titleSecondary = "时间轴",
                        modifier = Modifier.padding(horizontal = pageHorizontal),
                    )
                }
            }

            if (state.day != today) {
                item {
                    Row(
                        Modifier.padding(horizontal = pageHorizontal),
                    ) {
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
                        Modifier.padding(horizontal = pageHorizontal),
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
                                                skipFeedback != null -> "重试跳过"
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

            if (state.loading || state.records.isEmpty()) {
                item(key = "records_phase", contentType = "records_phase") {
                    Crossfade(
                        targetState = state.loading,
                        animationSpec = tween(250),
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
                            // Empty day: ring mark + quiet color via StateKind.Empty — not a spinner.
                            StateContainer(
                                kind = StateKind.Empty,
                                title = "还没有记录",
                                message = "点下方快捷入口添加第一条记录",
                                modifier = Modifier
                                    .padding(horizontal = pageHorizontal)
                                    .testTag("log_records_empty"),
                            )
                        }
                    }
                }
            } else {
                items(filteredTimelineRecords, key = { it.id }) { record ->
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
                    val recordRowId = "record-${record.id}"
                    val recordRevealed = managementState.revealedSwipeRowId == recordRowId
                    val editRecordAction = {
                        openEditFromSwipe(RecordComposerRequest.Edit(record.id))
                    }
                    val deleteRecordAction = {
                        requestListDelete(ListDeleteTarget.RecordItem(record))
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
                                publishLabel,
                            ),
                            relative = relativeTimeLabel(record.timestamp, nowMs),
                            tone = record.type.presentationTone(),
                            anomaly =
                                (record.payload.payload as? SleepPayload)?.anomaly == true ||
                                    (record.type == RecordType.SLEEP &&
                                        record.endTimestamp == null),
                            leading = { RecordTypeIcon(record.type) },
                            onClick = {
                                if (recordRevealed) {
                                    managementState.collapseSwipeRows()
                                } else if (publishLabel != null) {
                                    managementState.collapseSwipeRows()
                                    onOpenPublishChrome(
                                        PublishChromeTarget(
                                            recordId = record.id,
                                            title = title,
                                            publicationState = publicationState,
                                        ),
                                    )
                                } else if (canEditRecord) {
                                    managementState.collapseSwipeRows()
                                    onOpenComposer(RecordComposerRequest.Edit(record.id))
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
                                    contentDescription = when {
                                        publishLabel != null -> "同步状态$title"
                                        canEditRecord -> "编辑$title"
                                        else -> "无权编辑$title"
                                    }
                                },
                        )
                    }
                }
            }
        }
    }
}

internal fun recordSummaryLine(record: Record): String = record.presentationSummary()
