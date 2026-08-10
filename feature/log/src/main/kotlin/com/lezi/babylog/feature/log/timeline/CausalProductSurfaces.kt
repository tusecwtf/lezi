package com.lezi.babylog.feature.log.timeline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.displayLabel
import com.lezi.babylog.core.ui.presentationSummary
import com.lezi.babylog.designsystem.LeziPhotoPreviewDialog
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziSurfacePanel
import com.lezi.babylog.designsystem.LeziTextButton
import com.lezi.babylog.designsystem.LeziTextButtonTone
import com.lezi.babylog.designsystem.LeziTextField
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.domain.carelog.ConflictResolverDraft
import com.lezi.babylog.domain.carelog.ConflictResolverChoiceResult
import com.lezi.babylog.domain.carelog.ConflictResolverPath
import com.lezi.babylog.domain.carelog.DuplicateGroupAction
import com.lezi.babylog.domain.carelog.SuspectedDuplicateGroup
import com.lezi.babylog.domain.carelog.SuspectedDuplicatePresentation
import com.lezi.babylog.domain.timeline.TimelineRecordRow
import com.lezi.babylog.domain.timeline.TimelineWakeObservation
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SleepObservationSheet(
    row: TimelineRecordRow,
    zone: ZoneId,
    onDismiss: () -> Unit,
    onAddWake: () -> Unit,
    onUpdate: (TimelineWakeObservation, Long, String?) -> Unit,
    onWithdraw: (TimelineWakeObservation) -> Unit,
    onSelect: (TimelineWakeObservation?) -> Unit,
) {
    var editing by remember { mutableStateOf<TimelineWakeObservation?>(null) }
    var previewPaths by remember { mutableStateOf<List<String>>(emptyList()) }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("sleep_observation_sheet")) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LeziSpacing.Page, vertical = LeziSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
        ) {
            Text("醒来观察", style = LeziTypography.Title)
            val interval = row.sleepInterval
            when {
                interval?.isOverlapPending == true -> Text(
                    "这条较早的睡眠仍保持开放，没有被后来记录自动闭合。",
                    modifier = Modifier.testTag("sleep_overlap_pending_message"),
                )
                interval?.isProvisional == true -> Text(
                    "尚未选择有效观察，当前暂定采用最早合法时间。",
                    modifier = Modifier.testTag("sleep_provisional_message"),
                )
            }
            if (row.wakeObservations.isEmpty()) {
                Text("尚无醒来观察。")
                LeziPrimaryButton(
                    label = "补记这次醒来",
                    onClick = onAddWake,
                    modifier = Modifier.fillMaxWidth().testTag("sleep_add_wake"),
                )
            }
            row.wakeObservations.forEach { wake ->
                WakeObservationCard(
                    wake = wake,
                    zone = zone,
                    canSelect = row.canSelectEffectiveWakeObservation,
                    onPreview = { previewPaths = wake.photoPaths },
                    onEdit = { editing = wake },
                    onWithdraw = { onWithdraw(wake) },
                    onSelect = { onSelect(wake) },
                )
            }
            if (row.canSelectEffectiveWakeObservation &&
                row.wakeObservations.any(TimelineWakeObservation::effective)
            ) {
                LeziTextButton(
                    label = "取消有效观察选择",
                    onClick = { onSelect(null) },
                    modifier = Modifier.testTag("sleep_clear_effective_wake"),
                )
            }
            Spacer(Modifier.height(LeziSpacing.Lg))
        }
    }
    editing?.let { wake ->
        WakeObservationEditSheet(
            wake = wake,
            zone = zone,
            onDismiss = { editing = null },
            onSave = { timestamp, note ->
                editing = null
                onUpdate(wake, timestamp, note)
            },
        )
    }
    if (previewPaths.isNotEmpty()) {
        LeziPhotoPreviewDialog(
            photos = previewPaths,
            startIndex = 0,
            onDismiss = { previewPaths = emptyList() },
        )
    }
}

@Composable
private fun WakeObservationCard(
    wake: TimelineWakeObservation,
    zone: ZoneId,
    canSelect: Boolean,
    onPreview: () -> Unit,
    onEdit: () -> Unit,
    onWithdraw: () -> Unit,
    onSelect: () -> Unit,
) {
    val time = Instant.ofEpochMilli(wake.wakeTimestamp).atZone(zone).toLocalTime()
        .format(TIME_FORMAT)
    LeziSurfacePanel(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wake_observation_${wake.clientUuid}")
            .semantics {
                contentDescription = buildString {
                    append("${wake.observerLabel}在${time}观察到醒来")
                    if (wake.provisional) append("，暂定采用")
                    if (wake.effective) append("，已选为有效观察")
                    if (!wake.note.isNullOrBlank()) append("，备注${wake.note}")
                    if (wake.photoPaths.isNotEmpty()) append("，${wake.photoPaths.size}张照片")
                }
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${wake.observerLabel} · $time", style = LeziTypography.TitleSm)
                Text(
                    listOfNotNull(
                        "暂定".takeIf { wake.provisional },
                        "有效观察".takeIf { wake.effective },
                    ).joinToString(" · ").ifBlank { "保留的观察" },
                    style = LeziTypography.Meta,
                )
                wake.note?.takeIf(String::isNotBlank)?.let { Text(it) }
            }
            if (wake.photoPaths.isNotEmpty()) {
                LeziTextButton("${wake.photoPaths.size}张照片", onClick = onPreview)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Xs)) {
            if (wake.canEdit) {
                LeziTextButton("修正", onClick = onEdit, modifier = Modifier.testTag("wake_edit_${wake.clientUuid}"))
                LeziTextButton(
                    "撤回",
                    onClick = onWithdraw,
                    tone = LeziTextButtonTone.Destructive,
                    modifier = Modifier.testTag("wake_withdraw_${wake.clientUuid}"),
                )
            }
            if (canSelect && !wake.effective) {
                LeziTextButton(
                    "设为有效",
                    onClick = onSelect,
                    tone = LeziTextButtonTone.Primary,
                    modifier = Modifier.testTag("wake_select_${wake.clientUuid}"),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WakeObservationEditSheet(
    wake: TimelineWakeObservation,
    zone: ZoneId,
    onDismiss: () -> Unit,
    onSave: (Long, String?) -> Unit,
) {
    val zoned = remember(wake.wakeTimestamp, zone) {
        Instant.ofEpochMilli(wake.wakeTimestamp).atZone(zone)
    }
    var timeText by remember { mutableStateOf(zoned.toLocalTime().format(TIME_FORMAT)) }
    var note by remember { mutableStateOf(wake.note.orEmpty()) }
    val parsedTime = runCatching { LocalTime.parse(timeText, TIME_FORMAT) }.getOrNull()
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("wake_edit_sheet")) {
        Column(
            Modifier.fillMaxWidth().padding(LeziSpacing.Page),
            verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
        ) {
            Text("修正醒来观察", style = LeziTypography.Title)
            LeziTextField(
                value = timeText,
                onValueChange = { timeText = it },
                label = { Text("时间 HH:mm") },
                isError = parsedTime == null,
                modifier = Modifier.fillMaxWidth().testTag("wake_edit_time"),
            )
            LeziTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注") },
                modifier = Modifier.fillMaxWidth().testTag("wake_edit_note"),
            )
            LeziPrimaryButton(
                label = "保存修正",
                enabled = parsedTime != null,
                onClick = {
                    val timestamp = zoned.toLocalDate().atTime(requireNotNull(parsedTime))
                        .atZone(zone).toInstant().toEpochMilli()
                    onSave(timestamp, note.trim().takeIf(String::isNotEmpty))
                },
                modifier = Modifier.fillMaxWidth().testTag("wake_edit_confirm"),
            )
            Spacer(Modifier.height(LeziSpacing.Lg))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConflictResolverSheet(
    loading: Boolean,
    draft: ConflictResolverDraft?,
    error: String?,
    submitting: Boolean,
    onDismiss: () -> Unit,
    onDraftChanged: (ConflictResolverDraft) -> Unit,
    onSubmit: () -> Unit,
) {
    var interactionReadOnlyReason by remember(
        draft?.model?.conflictId,
        draft?.resolutionMutationId,
    ) { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("conflict_resolver_sheet")) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(LeziSpacing.Page),
            verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
        ) {
            Text(
                "解决${draft?.model?.entityLabel ?: "事实"}冲突",
                style = LeziTypography.Title,
            )
            Text("只列出真实冲突字段；已自动合并的内容保持不变。")
            if (loading) Text("正在取得最新差异…", modifier = Modifier.testTag("conflict_loading"))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("conflict_error")) }
            draft?.let { current ->
                (interactionReadOnlyReason ?: current.model.readOnlyReason)?.let { reason ->
                    Text(
                        reason,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("conflict_read_only"),
                    )
                }
                current.model.versions.forEachIndexed { index, version ->
                    LeziSurfacePanel(
                        Modifier.fillMaxWidth()
                            .testTag("conflict_version_$index")
                            .semantics {
                                contentDescription = buildString {
                                    append(version.label)
                                    append(if (version.deleted) "，已删除" else "，保留")
                                    append("，${version.media.size}张照片，")
                                    append(version.provenance)
                                }
                            },
                    ) {
                        Text(version.label, style = LeziTypography.TitleSm)
                        Text(if (version.deleted) "已删除" else "保留", style = LeziTypography.Meta)
                        Text("${version.media.size} 张照片 · ${version.provenance}", style = LeziTypography.Meta)
                    }
                }
                if (current.model.autoMerged.isNotEmpty()) {
                    Text("已自动合并", style = LeziTypography.TitleSm)
                    current.model.autoMerged.forEach { outcome ->
                        Text(
                            "${outcome.label}：${outcome.value} · ${outcome.provenance}",
                            style = LeziTypography.Meta,
                            modifier = Modifier.testTag("conflict_auto_${outcome.path}"),
                        )
                    }
                }
                current.model.paths.forEach { path ->
                    ConflictPathChooser(
                        path = path,
                        selected = current.selectedChoiceIds[path.path],
                        enabled = current.canChoose,
                        onChoose = { selectedPath, choiceId ->
                            when (val result = current.select(selectedPath, choiceId)) {
                                is ConflictResolverChoiceResult.Selected -> {
                                    interactionReadOnlyReason = null
                                    onDraftChanged(result.draft)
                                }
                                is ConflictResolverChoiceResult.ReadOnly -> {
                                    interactionReadOnlyReason = result.reason
                                }
                            }
                        },
                    )
                }
            }
            if (draft != null) {
                LeziPrimaryButton(
                    label = when {
                        submitting -> "正在提交…"
                        draft.submitted -> "重试同一次提交"
                        else -> "确认解决"
                    },
                    enabled = draft.canSubmit && !submitting,
                    busy = submitting,
                    onClick = onSubmit,
                    modifier = Modifier.fillMaxWidth().testTag("conflict_submit"),
                )
            }
            Spacer(Modifier.height(LeziSpacing.Lg))
        }
    }
}

@Composable
private fun ConflictPathChooser(
    path: ConflictResolverPath,
    selected: String?,
    enabled: Boolean,
    onChoose: (String, String) -> Unit,
) {
    LeziSurfacePanel(Modifier.fillMaxWidth().testTag("conflict_path_${path.path}")) {
        Text(path.label, style = LeziTypography.TitleSm)
        path.options.forEach { option ->
            Row(
                Modifier.fillMaxWidth().clickable(enabled = enabled) {
                    onChoose(path.path, option.choiceId)
                }
                    .padding(vertical = 4.dp)
                    .testTag("conflict_option_${path.path}_${option.choiceId}")
                    .semantics {
                        contentDescription =
                            "${path.label}，${option.value}，${option.provenance}"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == option.choiceId, onClick = null)
                Column {
                    Text(option.value)
                    Text(option.provenance, style = LeziTypography.Meta)
                }
            }
        }
    }
}

@Composable
internal fun DuplicateGroupCard(
    group: SuspectedDuplicateGroup,
    recordsByUuid: Map<String, Record>,
    recordRowsById: Map<Long, TimelineRecordRow>,
    currentMembershipId: String,
    isOwner: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAction: (DuplicateGroupAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val members = group.memberClientUuids.mapNotNull(recordsByUuid::get)
    val actions = SuspectedDuplicatePresentation.availableActions(
        group, recordsByUuid, currentMembershipId, isOwner,
    )
    LeziSurfacePanel(
        modifier.fillMaxWidth().testTag("duplicate_group_${group.groupId}")
            .semantics {
                contentDescription = "疑似重复组，${members.size}个来源，待确认，${if (expanded) "已展开" else "已收起"}"
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("疑似重复 · 待确认", style = LeziTypography.TitleSm)
                Text("${members.size} 个来源；未确认前汇总显示上下界", style = LeziTypography.Meta)
            }
            LeziTextButton(if (expanded) "收起" else "展开", onClick = onToggle, modifier = Modifier.testTag("duplicate_toggle_${group.groupId}"))
        }
        if (expanded) {
            members.sortedBy(Record::timestamp).forEach { record ->
                val photos = recordRowsById[record.id]?.media?.photoCount ?: 0
                Text(
                    "${record.createdByMembershipId.ifBlank { "家人" }} · ${record.displayLabel()} · " +
                        "${record.presentationSummary()}" +
                        record.note?.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty() +
                        if (photos > 0) " · ${photos}张照片" else "",
                    modifier = Modifier.testTag("duplicate_source_${record.clientUuid}"),
                )
            }
            actions.forEach { action ->
                val label = when (action) {
                    is DuplicateGroupAction.AuthorDeclare -> "声明我的记录与另一来源相同"
                    is DuplicateGroupAction.OwnerResolve ->
                        "以 ${recordsByUuid[action.displayClientUuid]?.displayLabel() ?: "所选来源"} 展示"
                }
                LeziTextButton(
                    label = label,
                    onClick = { onAction(action) },
                    tone = LeziTextButtonTone.Primary,
                    modifier = Modifier.testTag("duplicate_action_${action.hashCode()}"),
                )
            }
        }
    }
}

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
