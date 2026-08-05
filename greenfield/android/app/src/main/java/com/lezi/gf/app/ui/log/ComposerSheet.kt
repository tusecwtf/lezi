package com.lezi.gf.app.ui.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lezi.gf.app.ui.components.TimeDial
import com.lezi.gf.app.ui.model.ComposerFields
import com.lezi.gf.app.ui.screens.PhotoThumbnailRow
import com.lezi.gf.app.ui.theme.LeziSpacing
import com.lezi.gf.app.ui.theme.LeziTypeGlyph
import com.lezi.gf.care.ComposerDraft
import com.lezi.gf.care.PhotoRef
import com.lezi.gf.care.RecordType

/**
 * Type-specific secondary fields composer — confirm-before-write preserved.
 * Spec 02: Modal bottom sheet + TimeDial + full-width bottom confirm (E1/E10).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ComposerSheet(
    draft: ComposerDraft,
    noteSuggestions: List<String> = emptyList(),
    amountStepMl: Int = 5,
    /** When set, confirm updates this record via type-specific fields (edit path). */
    editingRecordUuid: String? = null,
    /** Open sleep for baby — drives 确认醒来 vs 确认睡下. */
    openSleep: com.lezi.gf.care.CareRecord? = null,
    confirmLabel: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (ComposerDraft) -> Unit,
    onChange: (ComposerDraft) -> Unit,
    onAddPhoto: () -> Unit,
    onPreviewPhoto: (List<PhotoRef>, Int) -> Unit,
    onAdjustTimeMinutes: (Int) -> Unit = {},
) {
    var note by remember(draft.clientKey()) { mutableStateOf(draft.note) }
    var payload by remember(draft.clientKey()) { mutableStateOf(draft.payloadJson) }
    var error by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val accent = LeziTypeGlyph.accent(draft.type.key)

    fun push(updatedPayload: String = payload, updatedNote: String = note) {
        payload = updatedPayload
        note = updatedNote
        onChange(draft.copy(note = updatedNote, payloadJson = updatedPayload, dirty = true))
    }

    val isEdit = editingRecordUuid != null
    val title = buildString {
        append(if (isEdit) "编辑 · " else "")
        append(draft.type.chineseLabel)
    }
    val confirmText = confirmLabel
        ?: when {
            isEdit -> "保存"
            draft.type == RecordType.SLEEP &&
                ComposerFields.parseSleep(payload).mode == ComposerFields.SleepMode.FALL_ASLEEP ->
                "确认睡下"
            draft.type == RecordType.SLEEP &&
                ComposerFields.parseSleep(payload).mode == ComposerFields.SleepMode.WAKE ->
                "确认醒来"
            // Match 0.3.x create-path CTA wording for dual-install review.
            else -> "确认记录"
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "记录确认面板 $title" },
        ) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp, bottom = 8.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(
                    if (isEdit) "确认后更新本条记录。类型专属字段可改。"
                    else "确认后才会写入。打开入口不会落库。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                // Formula-family: milk amount first (legacy order); other types keep dial-first.
                val milkPrimaryFirst = draft.type in setOf(
                    RecordType.FORMULA,
                    RecordType.PUMPED_FEED,
                    RecordType.PUMP_EXPRESS,
                )
                if (!milkPrimaryFirst) {
                    TimeDial(
                        timestampMs = draft.timestampMs,
                        onTimestampChange = { ms ->
                            onChange(
                                draft.copy(
                                    timestampMs = ms,
                                    note = note,
                                    payloadJson = payload,
                                    dirty = true,
                                ),
                            )
                        },
                        accent = accent,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                when (draft.type) {
                    RecordType.PEE -> {
                        val pee = ComposerFields.parsePee(payload)
                        Text("尿量", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ComposerFields.peeLabels.forEach { (v, label) ->
                                FilterChip(
                                    selected = pee.amount == v,
                                    onClick = { push(ComposerFields.buildPee(ComposerFields.PeeFields(v))) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                    RecordType.POOP, RecordType.BOTH_DIAPER -> {
                        val poop = ComposerFields.parsePoop(payload)
                        Text("便量", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ComposerFields.poopAmountLabels.forEach { (v, label) ->
                                FilterChip(
                                    selected = poop.amount == v,
                                    onClick = {
                                        push(ComposerFields.buildPoop(poop.copy(amount = v)))
                                    },
                                    label = { Text(label) },
                                )
                            }
                        }
                        Text("性状", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ComposerFields.poopConsistencyLabels.forEach { (v, label) ->
                                FilterChip(
                                    selected = poop.consistency == v,
                                    onClick = {
                                        push(ComposerFields.buildPoop(poop.copy(consistency = v)))
                                    },
                                    label = { Text(label) },
                                )
                            }
                        }
                        Text("颜色", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ComposerFields.poopColorLabels.forEach { (v, label) ->
                                FilterChip(
                                    selected = poop.color == v,
                                    onClick = {
                                        push(ComposerFields.buildPoop(poop.copy(color = v)))
                                    },
                                    label = { Text(label) },
                                )
                            }
                        }
                        if (draft.type == RecordType.BOTH_DIAPER) {
                            val pee = ComposerFields.parsePee(payload)
                            Text("尿量", style = MaterialTheme.typography.labelMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ComposerFields.peeLabels.forEach { (v, label) ->
                                    FilterChip(
                                        selected = pee.amount == v,
                                        onClick = {
                                            val poopJson = ComposerFields.buildPoop(poop)
                                            val merged = if (poopJson == "{}") {
                                                """{"pee_amount":$v}"""
                                            } else {
                                                poopJson.removeSuffix("}") + ""","pee_amount":$v}"""
                                            }
                                            push(merged)
                                        },
                                        label = { Text(label) },
                                    )
                                }
                            }
                        }
                    }
                    RecordType.SLEEP -> {
                        val sleep = ComposerFields.parseSleep(payload)
                        val hasOpen = openSleep != null && !isEdit
                        Text("睡眠会话", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = sleep.mode == ComposerFields.SleepMode.FALL_ASLEEP ||
                                    (hasOpen && sleep.mode == ComposerFields.SleepMode.FALL_ASLEEP),
                                onClick = {
                                    val start = if (sleep.startMs > 0) sleep.startMs else draft.timestampMs
                                    push(
                                        ComposerFields.buildSleep(
                                            sleep.copy(
                                                mode = ComposerFields.SleepMode.FALL_ASLEEP,
                                                startMs = start,
                                                endMs = null,
                                                open = true,
                                                durationMinutes = 0,
                                            ),
                                        ),
                                    )
                                },
                                enabled = !hasOpen || isEdit,
                                label = { Text("确认睡下") },
                            )
                            FilterChip(
                                selected = sleep.mode == ComposerFields.SleepMode.WAKE ||
                                    (hasOpen && sleep.mode != ComposerFields.SleepMode.BACKFILL),
                                onClick = {
                                    if (openSleep != null) {
                                        val start = ComposerFields.parseSleep(openSleep.payloadJson).startMs
                                            .takeIf { it > 0 } ?: openSleep.timestampMs
                                        val end = draft.timestampMs
                                        push(
                                            ComposerFields.buildSleep(
                                                sleep.copy(
                                                    mode = ComposerFields.SleepMode.WAKE,
                                                    startMs = start,
                                                    endMs = end,
                                                    open = false,
                                                ),
                                            ),
                                        )
                                    } else {
                                        val start = sleep.startMs.takeIf { it > 0 } ?: draft.timestampMs - 60 * 60_000L
                                        push(
                                            ComposerFields.buildSleep(
                                                sleep.copy(
                                                    mode = ComposerFields.SleepMode.WAKE,
                                                    startMs = start,
                                                    endMs = draft.timestampMs,
                                                    open = false,
                                                ),
                                            ),
                                        )
                                    }
                                },
                                enabled = hasOpen || isEdit || sleep.startMs > 0,
                                label = { Text("确认醒来") },
                            )
                            FilterChip(
                                selected = sleep.mode == ComposerFields.SleepMode.BACKFILL,
                                onClick = {
                                    val start = sleep.startMs.takeIf { it > 0 }
                                        ?: (draft.timestampMs - 90 * 60_000L)
                                    val end = sleep.endMs ?: draft.timestampMs
                                    push(
                                        ComposerFields.buildSleep(
                                            sleep.copy(
                                                mode = ComposerFields.SleepMode.BACKFILL,
                                                startMs = start,
                                                endMs = end,
                                                open = false,
                                            ),
                                        ),
                                    )
                                },
                                label = { Text("补记起止") },
                            )
                        }
                        if (hasOpen && sleep.mode != ComposerFields.SleepMode.BACKFILL) {
                            Text(
                                "有进行中睡眠：点确认将闭合醒来",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text("睡下时刻微调", style = MaterialTheme.typography.labelMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = {
                                push(ComposerFields.buildSleep(ComposerFields.adjustSleepStartMinutes(sleep, -5)))
                            }) { Text("−5分") }
                            Text(
                                "睡下 ${ComposerFields.formatWallClockMs(sleep.startMs)}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(onClick = {
                                push(ComposerFields.buildSleep(ComposerFields.adjustSleepStartMinutes(sleep, 5)))
                            }) { Text("+5分") }
                        }
                        if (sleep.mode != ComposerFields.SleepMode.FALL_ASLEEP) {
                            Text("醒来时刻微调", style = MaterialTheme.typography.labelMedium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(onClick = {
                                    push(ComposerFields.buildSleep(ComposerFields.adjustSleepEndMinutes(sleep, -5)))
                                }) { Text("−5分") }
                                Text(
                                    "醒来 ${ComposerFields.formatWallClockMs(sleep.endMs ?: 0)} · ${sleep.durationMinutes} 分",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Button(onClick = {
                                    push(ComposerFields.buildSleep(ComposerFields.adjustSleepEndMinutes(sleep, 5)))
                                }) { Text("+5分") }
                            }
                        } else {
                            Text(
                                "睡下后时长将在醒来时计算",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("午睡")
                            Switch(
                                checked = sleep.isNap,
                                onCheckedChange = {
                                    push(ComposerFields.buildSleep(sleep.copy(isNap = it)))
                                },
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("异常标记")
                            Switch(
                                checked = sleep.anomaly,
                                onCheckedChange = {
                                    push(ComposerFields.buildSleep(sleep.copy(anomaly = it)))
                                },
                            )
                        }
                    }
                    RecordType.FORMULA, RecordType.PUMPED_FEED, RecordType.PUMP_EXPRESS -> {
                        val milk = ComposerFields.parseMilk(payload).copy(stepMl = amountStepMl)
                        Text("奶量", style = MaterialTheme.typography.labelMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                push(ComposerFields.buildMilk(ComposerFields.stepMilk(milk, -1)))
                            }) { Text("−${milk.stepMl}") }
                            Text(
                                "${milk.amountMl} ml",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.headlineMedium,
                            )
                            TextButton(onClick = {
                                push(ComposerFields.buildMilk(ComposerFields.stepMilk(milk, +1)))
                            }) { Text("+${milk.stepMl}") }
                        }
                        Text("快捷奶量", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ComposerFields.milkQuickAmountsMl(milk.amountMl).forEach { ml ->
                                FilterChip(
                                    selected = milk.amountMl == ml,
                                    onClick = {
                                        push(ComposerFields.buildMilk(milk.copy(amountMl = ml)))
                                    },
                                    label = { Text("${ml}ml") },
                                )
                            }
                        }
                        // Optional formula-only fields (data-model prepared_ml / duration_min)
                        if (draft.type == RecordType.FORMULA) {
                            Spacer(Modifier.height(4.dp))
                            Text("可选", style = MaterialTheme.typography.labelMedium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("冲调量", modifier = Modifier.weight(0.35f))
                                TextButton(onClick = {
                                    val cur = milk.preparedMl ?: milk.amountMl
                                    push(
                                        ComposerFields.buildMilk(
                                            milk.copy(preparedMl = (cur - milk.stepMl).coerceAtLeast(0)),
                                        ),
                                    )
                                }) { Text("−") }
                                Text(
                                    "${milk.preparedMl ?: "—"} ml",
                                    modifier = Modifier.weight(0.4f),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                TextButton(onClick = {
                                    val cur = milk.preparedMl ?: milk.amountMl
                                    push(
                                        ComposerFields.buildMilk(
                                            milk.copy(preparedMl = cur + milk.stepMl),
                                        ),
                                    )
                                }) { Text("+") }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("耗时", modifier = Modifier.weight(0.35f))
                                TextButton(onClick = {
                                    val cur = milk.durationMin ?: 0
                                    push(
                                        ComposerFields.buildMilk(
                                            milk.copy(durationMin = (cur - 1).coerceAtLeast(0)),
                                        ),
                                    )
                                }) { Text("−1分") }
                                Text(
                                    if (milk.durationMin != null) "${milk.durationMin} 分" else "—",
                                    modifier = Modifier.weight(0.4f),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                TextButton(onClick = {
                                    val cur = milk.durationMin ?: 0
                                    push(
                                        ComposerFields.buildMilk(
                                            milk.copy(durationMin = cur + 1),
                                        ),
                                    )
                                }) { Text("+1分") }
                            }
                        }
                    }
                    RecordType.NURSING -> {
                        val n = ComposerFields.parseNursing(payload)
                        Text("左侧时长", style = MaterialTheme.typography.labelMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = {
                                push(
                                    ComposerFields.buildNursing(
                                        n.copy(leftMs = (n.leftMs - 60_000).coerceAtLeast(0)),
                                    ),
                                )
                            }) { Text("−1分") }
                            Text(
                                ComposerFields.formatDurationMs(n.leftMs),
                                modifier = Modifier.weight(1f),
                            )
                            Button(onClick = {
                                push(ComposerFields.buildNursing(n.copy(leftMs = n.leftMs + 60_000)))
                            }) { Text("+1分") }
                        }
                        Text("右侧时长", style = MaterialTheme.typography.labelMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = {
                                push(
                                    ComposerFields.buildNursing(
                                        n.copy(rightMs = (n.rightMs - 60_000).coerceAtLeast(0)),
                                    ),
                                )
                            }) { Text("−1分") }
                            Text(
                                ComposerFields.formatDurationMs(n.rightMs),
                                modifier = Modifier.weight(1f),
                            )
                            Button(onClick = {
                                push(ComposerFields.buildNursing(n.copy(rightMs = n.rightMs + 60_000)))
                            }) { Text("+1分") }
                        }
                        Text("顺序", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("L", "R", "LR", "RL").forEach { ord ->
                                FilterChip(
                                    selected = n.order == ord,
                                    onClick = { push(ComposerFields.buildNursing(n.copy(order = ord))) },
                                    label = { Text(ord) },
                                )
                            }
                        }
                    }
                    RecordType.TEMPERATURE -> {
                        val t = ComposerFields.parseTemp(payload)
                        OutlinedTextField(
                            value = "%.1f".format(t.celsius),
                            onValueChange = { raw ->
                                val v = raw.toDoubleOrNull()
                                if (v != null) push(ComposerFields.buildTemp(ComposerFields.TempFields(v)))
                            },
                            label = { Text("体温 ℃") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    RecordType.WEIGHT -> {
                        val w = ComposerFields.parseWeight(payload)
                        OutlinedTextField(
                            value = w.grams.toString(),
                            onValueChange = { raw ->
                                val g = raw.filter { it.isDigit() }.toIntOrNull() ?: 0
                                push(ComposerFields.buildWeight(ComposerFields.WeightFields(g)))
                            },
                            label = { Text("体重 g") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    RecordType.HEIGHT -> {
                        val h = ComposerFields.parseHeight(payload)
                        OutlinedTextField(
                            value = h.mm.toString(),
                            onValueChange = { raw ->
                                val mm = raw.filter { it.isDigit() }.toIntOrNull() ?: 0
                                push(ComposerFields.buildHeight(ComposerFields.HeightFields(mm)))
                            },
                            label = { Text("身长/身高 mm") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    else -> Unit
                }

                if (milkPrimaryFirst) {
                    Spacer(Modifier.height(12.dp))
                    TimeDial(
                        timestampMs = draft.timestampMs,
                        onTimestampChange = { ms ->
                            onChange(
                                draft.copy(
                                    timestampMs = ms,
                                    note = note,
                                    payloadJson = payload,
                                    dirty = true,
                                ),
                            )
                        },
                        accent = accent,
                    )
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = {
                        note = it
                        push(updatedNote = it)
                    },
                    label = { Text("备注") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (noteSuggestions.isNotEmpty()) {
                    Text("最近备注", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        noteSuggestions.forEach { s ->
                            FilterChip(
                                selected = note == s,
                                onClick = {
                                    note = s
                                    push(updatedNote = s)
                                },
                                label = { Text(s.take(12)) },
                            )
                        }
                    }
                }
                if (draft.photos.isNotEmpty()) {
                    PhotoThumbnailRow(photos = draft.photos, onOpen = { onPreviewPhoto(draft.photos, it) })
                }
                TextButton(onClick = onAddPhoto) {
                    Text("添加照片 (${draft.photos.size}/3)")
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            // Full-width fixed confirm bar (E10)
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Button(
                    onClick = {
                        val err = ComposerFields.validateSecondary(draft.type, payload)
                        if (err != null) {
                            error = err
                            return@Button
                        }
                        onConfirm(draft.copy(note = note, payloadJson = payload, dirty = true))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(LeziSpacing.Touch)
                        .semantics { contentDescription = "确认写入 $confirmText" },
                ) { Text(confirmText) }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (draft.dirty) "放弃" else "取消") }
            }
        }
    }
}

private fun ComposerDraft.clientKey(): String =
    "${type.key}|$babyClientUuid|$timestampMs|$customDefUuid"
