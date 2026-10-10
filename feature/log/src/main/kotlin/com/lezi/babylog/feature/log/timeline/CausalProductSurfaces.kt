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
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.core.model.RecordTimeDecision
import java.time.LocalDate
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
import com.lezi.babylog.designsystem.LeziThemeExt
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
    editCommand: WakeEditCommandState = WakeEditCommandState(),
    onConsumeEditResult: () -> Unit = {},
) {
    var editingUuid by rememberSaveable(row.record.clientUuid) { mutableStateOf<String?>(null) }
    val editing = row.wakeObservations.firstOrNull { it.clientUuid == editingUuid }
    LaunchedEffect(editCommand) {
        if (editCommand.saved && editCommand.clientUuid == editingUuid) {
            editingUuid = null
            onConsumeEditResult()
        }
    }
    var previewPaths by remember { mutableStateOf<List<String>>(emptyList()) }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("sleep_observation_sheet")) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LeziSpacing.Page, vertical = LeziSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
        ) {
            Text("醒来观察", style = LeziThemeExt.typography.Title)
            val interval = row.sleepInterval
            when {
                interval?.isOverlapPending == true -> Text(
                    "这条较早的睡眠仍保持开放，没有被后来记录自动闭合。",
                    modifier = Modifier.testTag("sleep_overlap_pending_message"),
                )
                interval?.isProvisional == true -> Text(
                    "还没有核对到确定的醒来时间，暂按最早的一次记录显示。",
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
                    onEdit = { if (!editCommand.saving) { onConsumeEditResult(); editingUuid = wake.clientUuid } },
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
            onDismiss = { editingUuid = null },
            onSave = { timestamp, note -> onUpdate(wake, timestamp, note) },
            saving = editCommand.saving,
            saveError = editCommand.error.takeIf { editCommand.clientUuid == wake.clientUuid },
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
internal fun WakeObservationEditSheet(
    wake: TimelineWakeObservation,
    zone: ZoneId,
    onDismiss: () -> Unit,
    onSave: (Long, String?) -> Unit,
    saving: Boolean = false,
    saveError: String? = null,
) {
    val zoned = remember(wake.wakeTimestamp, zone) {
        Instant.ofEpochMilli(wake.wakeTimestamp).atZone(zone)
    }
    var dateText by rememberSaveable(wake.clientUuid) { mutableStateOf(zoned.toLocalDate().toString()) }
    var timeText by rememberSaveable(wake.clientUuid) { mutableStateOf(zoned.toLocalTime().format(TIME_FORMAT)) }
    var note by rememberSaveable(wake.clientUuid) { mutableStateOf(wake.note.orEmpty()) }
    val parsedDate = runCatching { LocalDate.parse(dateText) }.getOrNull()
    val parsedTime = runCatching { LocalTime.parse(timeText, TIME_FORMAT) }.getOrNull()
    val resolved = if (parsedDate != null && parsedTime != null) {
        RecordTime.resolve(parsedDate, parsedTime, zone, zoned.offset)
    } else null
    val timestamp = (resolved as? RecordTimeDecision.Accepted)?.value?.toInstant()?.toEpochMilli()
    val currentSaving by rememberUpdatedState(saving)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !currentSaving },
    )
    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        modifier = Modifier.testTag("wake_edit_sheet"),
    ) {
        BackHandler { if (!saving) onDismiss() }
        Column(
            Modifier.fillMaxWidth().padding(LeziSpacing.Page),
            verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
        ) {
            Text("修正醒来观察", style = LeziThemeExt.typography.Title)
            LeziTextField(
                value = dateText,
                onValueChange = { dateText = it },
                label = { Text("日期 yyyy-MM-dd") },
                enabled = !saving,
                isError = parsedDate == null,
                modifier = Modifier.fillMaxWidth().testTag("wake_edit_date"),
            )
            LeziTextField(
                value = timeText,
                onValueChange = { timeText = it },
                label = { Text("时间 HH:mm") },
                isError = timestamp == null,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().testTag("wake_edit_time"),
            )
            LeziTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注") },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().testTag("wake_edit_note"),
            )
            if (resolved == RecordTimeDecision.RejectedGap) {
                Text("该时区不存在这个时间，请重新选择", color = MaterialTheme.colorScheme.error)
            }
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LeziPrimaryButton(
                label = if (saving) "正在保存…" else "保存修正",
                enabled = timestamp != null && !saving,
                onClick = {
                    // A note-only edit must retain seconds and the original overlap offset.
                    val unchangedTime = parsedDate == zoned.toLocalDate() &&
                        parsedTime == zoned.toLocalTime().withSecond(0).withNano(0)
                    onSave(if (unchangedTime) wake.wakeTimestamp else requireNotNull(timestamp),
                        note.trim().takeIf(String::isNotEmpty))
                },
                modifier = Modifier.fillMaxWidth().testTag("wake_edit_confirm"),
            )
            Spacer(Modifier.height(LeziSpacing.Lg))
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
        .sortedWith(compareBy<Record> { it.timestamp }.thenBy { it.clientUuid })
    val sourceLabels = members.mapIndexed { index, record ->
        val author = recordRowsById[record.id]?.uploaderLabel
            ?: if (record.createdByMembershipId == currentMembershipId && currentMembershipId.isNotBlank()) {
                "本人"
            } else "家人"
        val time = Instant.ofEpochMilli(record.timestamp).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm xxx"))
        record.clientUuid to "来源 ${index + 1} · $author · $time · ${record.displayLabel()} · ${record.presentationSummary()}"
    }.toMap()
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
                Text("${members.size} 个来源；核对前按最早到最晚显示", style = LeziTypography.Meta)
            }
            LeziTextButton(if (expanded) "收起" else "展开", onClick = onToggle, modifier = Modifier.testTag("duplicate_toggle_${group.groupId}"))
        }
        if (expanded) {
            members.forEach { record ->
                val photos = recordRowsById[record.id]?.media?.photoCount ?: 0
                Text(
                    sourceLabels.getValue(record.clientUuid) +
                        record.note?.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty() +
                        if (photos > 0) " · ${photos}张照片" else "",
                    modifier = Modifier.testTag("duplicate_source_${record.clientUuid}"),
                )
            }
            actions.forEach { action ->
                val label = when (action) {
                    is DuplicateGroupAction.AuthorDeclare -> "声明我的记录与另一来源相同"
                    is DuplicateGroupAction.OwnerResolve ->
                        "以 ${sourceLabels[action.displayClientUuid] ?: "所选来源"} 展示"
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

internal data class WakeEditCommandState(
    val clientUuid: String? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
)
