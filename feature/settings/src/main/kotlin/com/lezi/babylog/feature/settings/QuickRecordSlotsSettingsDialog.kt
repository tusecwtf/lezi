package com.lezi.babylog.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.model.QUICK_RECORD_SLOT_COUNT
import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.core.model.availableForNewEntry
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.domain.CustomRecordItem

/** Settings UI to pick, clear, and reorder the four home quick-record slots. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickRecordSlotsSettingsDialog(
    settings: SettingsLocal,
    customItems: List<CustomRecordItem>,
    onDismiss: () -> Unit,
    onSlotsChanged: (List<String>) -> Unit,
) {
    val slots = remember(settings.quickRecordSlots) {
        normalizeSlots(settings.quickRecordSlots)
    }
    var pickingIndex by remember { mutableIntStateOf(-1) }
    val candidates = remember(settings.hiddenItems, customItems) {
        slotCandidates(settings.hiddenItems, customItems)
    }
    val slotCenters = remember { mutableStateMapOf<Int, Float>() }
    val unmeasuredEdgeThresholdPx = with(LocalDensity.current) { 32.dp.toPx() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("常用记录槽位") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("首页顺序", style = LeziTypography.BodyStrong)
                    Text(
                        "本机设置 · 空槽保留，不会自动补位",
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                slots.forEachIndexed { index, key ->
                    val label = slotLabel(key, customItems)
                    DisposableEffect(index) {
                        onDispose { slotCenters.remove(index) }
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coordinates ->
                                slotCenters[index] = coordinates.positionInWindow().y +
                                    coordinates.size.height / 2f
                            },
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ReorderDragHandle(
                                label = "槽位 ${index + 1}，$label",
                                canMove = slots.size > 1,
                                onDragFinished = { distancePx ->
                                    val delta = dropTargetDelta(
                                        orderedKeys = slots.indices.toList(),
                                        sourceKey = index,
                                        dragDistancePx = distancePx,
                                        targetCentersPx = slotCenters,
                                        unmeasuredEdgeThresholdPx = unmeasuredEdgeThresholdPx,
                                    )
                                    if (delta != 0) {
                                        onSlotsChanged(moveItemBy(slots, index, delta))
                                    }
                                },
                                modifier = Modifier.testTag("quick-slot-reorder-$index"),
                            )
                            Column(Modifier.weight(1f)) {
                                Text("槽位 ${index + 1}", style = LeziTypography.Label)
                                Text(label, style = LeziTypography.BodyStrong)
                            }
                        }
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            TextButton(
                                enabled = index > 0,
                                onClick = {
                                    onSlotsChanged(moveItemBy(slots, index, -1))
                                },
                            ) { Text("上移") }
                            TextButton(
                                enabled = index < slots.lastIndex,
                                onClick = {
                                    onSlotsChanged(moveItemBy(slots, index, 1))
                                },
                            ) { Text("下移") }
                            TextButton(onClick = { pickingIndex = index }) { Text("选择") }
                            TextButton(
                                enabled = key.isNotEmpty(),
                                onClick = {
                                    val next = slots.toMutableList()
                                    next[index] = ""
                                    onSlotsChanged(next)
                                },
                            ) { Text("清空") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )

    if (pickingIndex in slots.indices) {
        AlertDialog(
            onDismissRequest = { pickingIndex = -1 },
            title = { Text("选择槽位 ${pickingIndex + 1}") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    candidates.forEach { candidate ->
                        FilterChip(
                            selected = slots[pickingIndex] == candidate.catalogKey,
                            onClick = {
                                val next = slots.toMutableList()
                                next[pickingIndex] = candidate.catalogKey
                                onSlotsChanged(next)
                                pickingIndex = -1
                            },
                            label = { Text(candidate.label) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickingIndex = -1 }) { Text("取消") }
            },
        )
    }
}

private data class SlotCandidate(val catalogKey: String, val label: String)

private fun normalizeSlots(slots: List<String>): List<String> {
    val padded = slots.map { it.trim() }.toMutableList()
    while (padded.size < QUICK_RECORD_SLOT_COUNT) padded += ""
    return padded.take(QUICK_RECORD_SLOT_COUNT)
}

private fun slotLabel(key: String, customItems: List<CustomRecordItem>): String {
    if (key.isBlank()) return "＋ 选择常用记录"
    val identity = RecordItemIdentity.parseCatalogKey(key) ?: return "无效引用 · 点击重选"
    return when (identity) {
        is RecordItemIdentity.BuiltIn -> identity.type.presentation.label
        is RecordItemIdentity.Custom ->
            customItems.firstOrNull { it.id == identity.customItemId }?.name
                ?: "已删除项目 · 点击重选"
    }
}

private fun slotCandidates(
    hiddenItems: Set<String>,
    customItems: List<CustomRecordItem>,
): List<SlotCandidate> {
    val builtIns = RecordType.availableForNewEntry()
        .filter { it.key !in hiddenItems }
        .map { SlotCandidate(it.key, it.presentation.label) }
    val customs = customItems
        .filter { RecordItemIdentity.customCatalogKey(it.id) !in hiddenItems }
        .map {
            SlotCandidate(
                RecordItemIdentity.customCatalogKey(it.id),
                it.name,
            )
        }
    return builtIns + customs
}
