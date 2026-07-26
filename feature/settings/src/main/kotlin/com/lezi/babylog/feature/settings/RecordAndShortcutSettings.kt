package com.lezi.babylog.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.core.model.availableForNewEntry
import com.lezi.babylog.core.ui.RecordSection
import com.lezi.babylog.core.ui.catalogSectionForKey
import com.lezi.babylog.core.ui.encodeItemOrder
import com.lezi.babylog.core.ui.knownCatalogKeys
import com.lezi.babylog.core.ui.mergeItemOrder
import com.lezi.babylog.core.ui.moveCatalogKeyWithinSection
import com.lezi.babylog.core.ui.moveCategoryOrder
import com.lezi.babylog.core.ui.orderedKeysInSection
import com.lezi.babylog.core.ui.orderedRecordSections
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.core.ui.storageKey
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.domain.CustomRecordItem

/** Top-level hub destinations under「记录与快捷设置」. */
internal enum class RecordShortcutHubDestination(val title: String, val subtitle: String) {
    QuickSlots("常用记录", "四个快捷槽位：选择、拖动排序、清空"),
    AllItems("所有记录项目", "拖动类别与项目，开启或关闭"),
    PerItem("分项目设置", "仅展示确有专属设置的项目"),
    PlanCalendar("护理计划与日历", "本机提醒与系统日历（后续开放）"),
}

/**
 * Pure navigation surface for tests: the four fixed secondary destinations.
 */
internal fun recordShortcutHubDestinations(): List<RecordShortcutHubDestination> =
    RecordShortcutHubDestination.entries.toList()

@Composable
internal fun RecordAndShortcutSettingsHubDialog(
    onDismiss: () -> Unit,
    onOpen: (RecordShortcutHubDestination) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记录与快捷设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
                Text(
                    "以下布局只保存在本机",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                recordShortcutHubDestinations().forEach { dest ->
                    TextButton(
                        onClick = { onOpen(dest) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(dest.title, style = LeziTypography.BodyStrong)
                            Text(
                                dest.subtitle,
                                style = LeziTypography.Meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

/**
 * Categorized all-items editor: category reorder, in-category item reorder (no cross-category),
 * enable/disable with order retention, and custom definitions under 自定义.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AllRecordItemsSettingsDialog(
    settings: SettingsLocal,
    customItems: List<CustomRecordItem>,
    onDismiss: () -> Unit,
    onItemOrderChanged: (String) -> Unit,
    onCategoryOrderChanged: (String) -> Unit,
    onToggleVisible: (String) -> Unit,
    onOpenCustomManage: () -> Unit,
) {
    val knownKeys = remember(customItems) {
        knownCatalogKeys(customItems.map { it.id })
    }
    val mergedOrder = remember(settings.itemOrderJson, knownKeys) {
        mergeItemOrder(settings.itemOrderJson, knownKeys)
    }
    val sections = remember(settings.categoryOrderJson) {
        orderedRecordSections(settings.categoryOrderJson)
    }
    val customById = remember(customItems) { customItems.associateBy { it.id } }
    val categoryCenters = remember { mutableStateMapOf<RecordSection, Float>() }
    val itemCenters = remember { mutableStateMapOf<String, Float>() }
    val unmeasuredEdgeThresholdPx = with(LocalDensity.current) { 32.dp.toPx() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("所有记录项目") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "长按拖动柄排序；项目不会跨类别。关闭后仍保留位置。",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(Modifier.heightIn(max = 520.dp)) {
                    sections.forEachIndexed { sectionIndex, section ->
                        item(key = "section-${section.storageKey}") {
                            DisposableEffect(section) {
                                onDispose { categoryCenters.remove(section) }
                            }
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .onGloballyPositioned { coordinates ->
                                        categoryCenters[section] =
                                            coordinates.positionInWindow().y +
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
                                        label = "${section.title}类别",
                                        canMove = sections.size > 1,
                                        onDragFinished = { distancePx ->
                                            val delta = dropTargetDelta(
                                                orderedKeys = sections,
                                                sourceKey = section,
                                                dragDistancePx = distancePx,
                                                targetCentersPx = categoryCenters,
                                                unmeasuredEdgeThresholdPx =
                                                    unmeasuredEdgeThresholdPx,
                                            )
                                            if (delta != 0) {
                                                onCategoryOrderChanged(
                                                    moveCategoryOrder(
                                                        settings.categoryOrderJson,
                                                        section,
                                                        delta,
                                                    ),
                                                )
                                            }
                                        },
                                    )
                                    Text(
                                        section.title,
                                        style = LeziTypography.Label,
                                        modifier = Modifier.weight(1f),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                FlowRow(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    TextButton(
                                        enabled = sectionIndex > 0,
                                        onClick = {
                                            onCategoryOrderChanged(
                                                moveCategoryOrder(
                                                    settings.categoryOrderJson,
                                                    section,
                                                    -1,
                                                ),
                                            )
                                        },
                                    ) { Text("上移") }
                                    TextButton(
                                        enabled = sectionIndex < sections.lastIndex,
                                        onClick = {
                                            onCategoryOrderChanged(
                                                moveCategoryOrder(
                                                    settings.categoryOrderJson,
                                                    section,
                                                    1,
                                                ),
                                            )
                                        },
                                    ) { Text("下移") }
                                }
                            }
                        }
                        val sectionKeys = orderedKeysInSection(
                            section = section,
                            itemOrderJson = encodeItemOrder(mergedOrder),
                            knownKeysForSection = knownKeys,
                        )
                        itemsIndexed(
                            sectionKeys,
                            key = { _, key -> key },
                        ) { index, catalogKey ->
                            val label = catalogLabel(catalogKey, customById)
                            DisposableEffect(catalogKey) {
                                onDispose { itemCenters.remove(catalogKey) }
                            }
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .onGloballyPositioned { coordinates ->
                                        itemCenters[catalogKey] =
                                            coordinates.positionInWindow().y +
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
                                        label = label,
                                        canMove = sectionKeys.size > 1,
                                        onDragFinished = { distancePx ->
                                            val delta = dropTargetDelta(
                                                orderedKeys = sectionKeys,
                                                sourceKey = catalogKey,
                                                dragDistancePx = distancePx,
                                                targetCentersPx = itemCenters,
                                                unmeasuredEdgeThresholdPx =
                                                    unmeasuredEdgeThresholdPx,
                                            )
                                            if (delta != 0) {
                                                onItemOrderChanged(
                                                    moveCatalogKeyWithinSection(
                                                        itemOrderJson = encodeItemOrder(mergedOrder),
                                                        catalogKey = catalogKey,
                                                        delta = delta,
                                                        allKnownKeys = knownKeys,
                                                    ),
                                                )
                                            }
                                        },
                                    )
                                    Text(label, modifier = Modifier.weight(1f))
                                    Switch(
                                        checked = catalogKey !in settings.hiddenItems,
                                        onCheckedChange = { onToggleVisible(catalogKey) },
                                    )
                                }
                                FlowRow(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    TextButton(
                                        enabled = index > 0,
                                        onClick = {
                                            onItemOrderChanged(
                                                moveCatalogKeyWithinSection(
                                                    itemOrderJson = encodeItemOrder(mergedOrder),
                                                    catalogKey = catalogKey,
                                                    delta = -1,
                                                    allKnownKeys = knownKeys,
                                                ),
                                            )
                                        },
                                    ) { Text("上移") }
                                    TextButton(
                                        enabled = index < sectionKeys.lastIndex,
                                        onClick = {
                                            onItemOrderChanged(
                                                moveCatalogKeyWithinSection(
                                                    itemOrderJson = encodeItemOrder(mergedOrder),
                                                    catalogKey = catalogKey,
                                                    delta = 1,
                                                    allKnownKeys = knownKeys,
                                                ),
                                            )
                                        },
                                    ) { Text("下移") }
                                }
                            }
                        }
                        if (section == RecordSection.Custom) {
                            item(key = "custom-manage") {
                                TextButton(onClick = onOpenCustomManage) {
                                    Text("管理自定义项目（新增/改名/删除）")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

private fun catalogLabel(
    catalogKey: String,
    customById: Map<Long, CustomRecordItem>,
): String {
    val identity = RecordItemIdentity.parseCatalogKey(catalogKey) ?: return catalogKey
    return when (identity) {
        is RecordItemIdentity.BuiltIn -> identity.type.presentation.label
        is RecordItemIdentity.Custom ->
            customById[identity.customItemId]?.name ?: "自定义#${identity.customItemId}"
    }
}

/**
 * Only real per-item knobs: nursing timer/interval/record-at, milk amount step, fever advice.
 * No empty stubs for types without settings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PerItemSettingsDialog(
    settings: SettingsLocal,
    onDismiss: () -> Unit,
    onTimerEnabled: (Boolean) -> Unit,
    onRecordAt: (String) -> Unit,
    onInterval: (Int) -> Unit,
    onAmountStep: (Int) -> Unit,
    onFeverAdvice: (Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分项目设置") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                Text("母乳", style = LeziTypography.Label)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("喂奶计时入口")
                    Switch(checked = settings.timerEnabled, onCheckedChange = onTimerEnabled)
                }
                Text("记录时刻", style = LeziTypography.Meta)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(
                        selected = settings.recordAtStartOrEnd == "start",
                        onClick = { onRecordAt("start") },
                        label = { Text("开始") },
                    )
                    FilterChip(
                        selected = settings.recordAtStartOrEnd == "end",
                        onClick = { onRecordAt("end") },
                        label = { Text("结束") },
                    )
                }
                Text("下次喂奶间隔（分钟）", style = LeziTypography.Meta)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(120, 150, 180, 210, 240).forEach { m ->
                        FilterChip(
                            selected = settings.nursingIntervalMin == m,
                            onClick = { onInterval(m) },
                            label = { Text("$m") },
                        )
                    }
                }

                Text("配方奶 / 挤出乳 / 母乳瓶喂", style = LeziTypography.Label)
                Text("奶量步进 ml", style = LeziTypography.Meta)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(5, 10, 15).forEach { s ->
                        FilterChip(
                            selected = settings.amountStepMl == s,
                            onClick = { onAmountStep(s) },
                            label = { Text("$s") },
                        )
                    }
                }

                Text("体温", style = LeziTypography.Label)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("低月龄发热提示")
                        Text(
                            "仅记录时不足 3 个月且体温 ≥38℃",
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.infantFeverAdviceEnabled,
                        onCheckedChange = onFeverAdvice,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

/**
 * Care-plan / calendar second-level entry.
 * Local care-plan reminder toggle and system calendar projection are device-only.
 */
@Composable
internal fun PlanCalendarSettingsDialog(
    carePlanRemindersEnabled: Boolean,
    onCarePlanRemindersEnabled: (Boolean) -> Unit,
    systemCalendarEnabled: Boolean = false,
    systemCalendarSummary: String = "未配置",
    systemCalendarDisclosureSummary: String = systemCalendarDisclosureLabel(2),
    onConfigureSystemCalendar: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("护理计划与日历") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("家庭护理计划提醒", style = LeziTypography.Body)
                        Text(
                            "默认开启。仅本机有效，不上传家庭服务器。系统可能因省电策略延后通知。",
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = carePlanRemindersEnabled,
                        onCheckedChange = onCarePlanRemindersEnabled,
                    )
                }
                Column(Modifier.fillMaxWidth()) {
                    Text("同步到系统日历", style = LeziTypography.Body)
                    Text(
                        if (systemCalendarEnabled) {
                            "已启用 · $systemCalendarSummary · 披露：$systemCalendarDisclosureSummary。" +
                                "权限、目标与披露仅本机，不随家庭同步。"
                        } else {
                            "未启用。主动配置后才会写入所选可写日历；被动同步不索权。"
                        },
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onConfigureSystemCalendar) {
                        Text(if (systemCalendarEnabled) "更改系统日历与披露" else "配置系统日历")
                    }
                }
                Text(
                    "护理计划始终进入乐记日历。系统副本成功时由系统日历提醒，避免与乐记重复通知。" +
                        "更改披露只更新尚未发生的事项。",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

/** Test helper: refuse cross-category item moves (returns unchanged order). */
internal fun tryMoveItemAcrossCategory(
    itemOrderJson: String,
    catalogKey: String,
    targetSection: RecordSection,
    allKnownKeys: Collection<String>,
): String {
    val current = catalogSectionForKey(catalogKey) ?: return itemOrderJson
    if (current == targetSection) {
        return moveCatalogKeyWithinSection(itemOrderJson, catalogKey, 1, allKnownKeys)
    }
    // Cross-category moves are intentionally no-ops.
    return encodeItemOrder(mergeItemOrder(itemOrderJson, allKnownKeys))
}

/** Types that still appear under built-in sections (for tests / docs). */
internal fun builtInKeysInSection(section: RecordSection): List<String> =
    RecordType.availableForNewEntry()
        .filter { it.presentation.section == section }
        .map { it.key }
