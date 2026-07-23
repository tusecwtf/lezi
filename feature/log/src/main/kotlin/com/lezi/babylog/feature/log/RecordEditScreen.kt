package com.lezi.babylog.feature.log

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.amountCandidates
import com.lezi.babylog.domain.amountCenterIndex
import com.lezi.babylog.domain.payloadInt
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltViewModel
class RecordEditViewModel @Inject constructor(
    private val careLog: CareLog,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    var stepMl: Int = 5
        private set
    var lastNotes: List<String> = emptyList()
        private set

    suspend fun load(recordId: String?, newTypeKey: String?): EditForm {
        stepMl = settingsStore.settings.first().amountStepMl
        val baby = careLog.getCurrentBaby()
        if (recordId != null && recordId != "new") {
            val id = recordId.toLongOrNull()
            val rec = id?.let { careLog.getRecord(it) }
            if (rec != null) {
                return EditForm.fromRecord(rec)
            }
        }
        val type = RecordType.fromKey(newTypeKey.orEmpty()) ?: RecordType.MEMO
        return EditForm(type = type, babyId = baby?.id ?: 0L)
    }

    fun save(form: EditForm, onDone: () -> Unit) {
        viewModelScope.launch {
            val babyId = form.babyId.takeIf { it > 0 } ?: careLog.getCurrentBaby()?.id ?: return@launch
            val payload = form.toPayload()
            if (form.id != null) {
                careLog.updateRecord(
                    id = form.id,
                    timestamp = form.timestamp,
                    endTimestamp = form.endTimestamp,
                    note = form.note.ifBlank { null },
                    payloadJson = payload,
                )
            } else {
                careLog.addRecord(
                    babyId = babyId,
                    type = form.type,
                    timestamp = form.timestamp,
                    endTimestamp = form.endTimestamp,
                    note = form.note.ifBlank { null },
                    payloadJson = payload,
                )
            }
            onDone()
        }
    }

    fun delete(id: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            careLog.deleteRecord(id)
            onDone()
        }
    }
}

data class EditForm(
    val id: Long? = null,
    val babyId: Long = 0,
    val type: RecordType = RecordType.MEMO,
    val timestamp: Long = System.currentTimeMillis(),
    val endTimestamp: Long? = null,
    val note: String = "",
    val amountMl: Int = 120,
    val leftMin: Int = 0,
    val rightMin: Int = 0,
    val order: String = "LR",
    val peeAmount: Int = 2,
    val stoolAmount: Int = 3,
    val stoolConsistency: Int = 3,
    val stoolColor: Int = 0,
    val celsius: String = "36.5",
    val medicineName: String = "",
    val medicineDose: String = "",
    val photoUris: List<String> = emptyList(),
) {
    fun toPayload(): String = when (type) {
        RecordType.FORMULA, RecordType.PUMPED_FEED, RecordType.PUMP_EXPRESS ->
            """{"amount_ml":$amountMl}"""
        RecordType.NURSING ->
            """{"left_min":$leftMin,"right_min":$rightMin,"order":"$order"}"""
        RecordType.PEE ->
            """{"pee_amount":$peeAmount}"""
        RecordType.POOP ->
            """{"stool_amount":$stoolAmount,"stool_consistency":$stoolConsistency,"stool_color":$stoolColor}"""
        RecordType.BOTH_DIAPER ->
            """{"pee_amount":$peeAmount,"stool_amount":$stoolAmount,"stool_consistency":$stoolConsistency,"stool_color":$stoolColor}"""
        RecordType.TEMPERATURE ->
            """{"celsius":${celsius.toDoubleOrNull() ?: 36.5}}"""
        RecordType.MEDICINE ->
            """{"name":${jsonStr(medicineName)},"dose":${jsonStr(medicineDose)}}"""
        RecordType.DIARY, RecordType.MEMO ->
            if (photoUris.isEmpty()) "{}"
            else """{"photos":[${photoUris.joinToString(",") { jsonStr(it) }}]}"""
        else -> "{}"
    }

    companion object {
        fun fromRecord(r: com.lezi.babylog.core.model.Record): EditForm {
            val p = r.payloadJson
            return EditForm(
                id = r.id,
                babyId = r.babyId,
                type = r.type,
                timestamp = r.timestamp,
                endTimestamp = r.endTimestamp,
                note = r.note.orEmpty(),
                amountMl = payloadInt(p, "amount_ml").takeIf { it > 0 } ?: 120,
                leftMin = payloadInt(p, "left_min"),
                rightMin = payloadInt(p, "right_min"),
                order = Regex(""""order"\s*:\s*"([^"]+)"""").find(p)?.groupValues?.getOrNull(1) ?: "LR",
                peeAmount = payloadInt(p, "pee_amount").takeIf { it in 1..3 } ?: 2,
                stoolAmount = payloadInt(p, "stool_amount").takeIf { it in 1..4 } ?: 3,
                stoolConsistency = payloadInt(p, "stool_consistency").takeIf { it in 1..4 } ?: 3,
                stoolColor = payloadInt(p, "stool_color").coerceIn(0, 7),
                celsius = Regex(""""celsius"\s*:\s*(-?\d+(?:\.\d+)?)""")
                    .find(p)?.groupValues?.getOrNull(1) ?: "36.5",
                medicineName = Regex(""""name"\s*:\s*"([^"]*)"""").find(p)?.groupValues?.getOrNull(1).orEmpty(),
                medicineDose = Regex(""""dose"\s*:\s*"([^"]*)"""").find(p)?.groupValues?.getOrNull(1).orEmpty(),
            )
        }
    }
}

private fun jsonStr(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordEditRoute(
    recordId: String,
    newTypeKey: String,
    onDone: () -> Unit,
    vm: RecordEditViewModel = hiltViewModel(),
) {
    var form by remember { mutableStateOf<EditForm?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var manualMl by remember { mutableStateOf(false) }
    var mlText by remember { mutableStateOf("") }

    LaunchedEffect(recordId, newTypeKey) {
        form = vm.load(recordId, newTypeKey)
    }
    val f = form ?: return

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(5),
    ) { uris ->
        if (uris.isNotEmpty()) {
            form = f.copy(photoUris = f.photoUris + uris.map { it.toString() })
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(typeLabel(f.type)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (f.id != null) {
                        TextButton(onClick = { confirmDelete = true }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("时间", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            val zone = ZoneId.systemDefault()
            val tsLabel = remember(f.timestamp) {
                LocalDateTime.ofInstant(Instant.ofEpochMilli(f.timestamp), zone)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            }
            OutlinedTextField(
                value = tsLabel,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("可在保存后通过列表再次编辑；微调用下方分钟") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { form = f.copy(timestamp = f.timestamp - 60_000L) }) { Text("−1分") }
                TextButton(onClick = { form = f.copy(timestamp = f.timestamp + 60_000L) }) { Text("+1分") }
                TextButton(onClick = { form = f.copy(timestamp = System.currentTimeMillis()) }) { Text("现在") }
            }

            Spacer(Modifier.height(16.dp))
            when (f.type) {
                RecordType.FORMULA, RecordType.PUMPED_FEED, RecordType.PUMP_EXPRESS -> {
                    AmountEditor(
                        ml = f.amountMl,
                        step = vm.stepMl,
                        manual = manualMl,
                        mlText = mlText,
                        onManualChange = { manualMl = it },
                        onMlText = { mlText = it },
                        onMl = { form = f.copy(amountMl = it) },
                    )
                }
                RecordType.NURSING -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = f.leftMin.toString(),
                            onValueChange = { form = f.copy(leftMin = it.toIntOrNull() ?: 0) },
                            label = { Text("左侧分钟") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = f.rightMin.toString(),
                            onValueChange = { form = f.copy(rightMin = it.toIntOrNull() ?: 0) },
                            label = { Text("右侧分钟") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = f.order == "LR", onClick = { form = f.copy(order = "LR") }, label = { Text("左→右") })
                        FilterChip(selected = f.order == "RL", onClick = { form = f.copy(order = "RL") }, label = { Text("右→左") })
                    }
                }
                RecordType.PEE -> PeeAmountPicker(f.peeAmount) { form = f.copy(peeAmount = it) }
                RecordType.POOP -> PoopPickers(f) { form = it }
                RecordType.BOTH_DIAPER -> {
                    PeeAmountPicker(f.peeAmount) { form = f.copy(peeAmount = it) }
                    Spacer(Modifier.height(12.dp))
                    PoopPickers(f) { form = it }
                }
                RecordType.SLEEP -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            form = f.copy(endTimestamp = (f.endTimestamp ?: f.timestamp) - 60_000L)
                        }) { Text("结束−1分") }
                        TextButton(onClick = {
                            form = f.copy(endTimestamp = (f.endTimestamp ?: System.currentTimeMillis()) + 60_000L)
                        }) { Text("结束+1分") }
                        TextButton(onClick = {
                            form = f.copy(endTimestamp = System.currentTimeMillis())
                        }) { Text("结束=现在") }
                    }
                    Text(
                        "开始 ${tsLabel}" + (f.endTimestamp?.let {
                            " · 结束 " + LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone)
                                .format(DateTimeFormatter.ofPattern("HH:mm"))
                        } ?: " · 进行中"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RecordType.TEMPERATURE -> {
                    OutlinedTextField(
                        value = f.celsius,
                        onValueChange = { form = f.copy(celsius = it) },
                        label = { Text("体温 ℃") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val c = f.celsius.toDoubleOrNull()
                    if (c != null && c >= 38.0) {
                        Text(
                            "低月龄发热请及时就医。本提示仅供参考，非医疗诊断，可忽略。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                RecordType.MEDICINE -> {
                    OutlinedTextField(
                        value = f.medicineName,
                        onValueChange = { form = f.copy(medicineName = it) },
                        label = { Text("药品名称") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = f.medicineDose,
                        onValueChange = { form = f.copy(medicineDose = it) },
                        label = { Text("剂量") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                RecordType.DIARY -> {
                    OutlinedButton(
                        onClick = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("添加照片（${f.photoUris.size}）") }
                }
                else -> Unit
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = f.note,
                onValueChange = { form = f.copy(note = it) },
                label = { Text("备注") },
                modifier = Modifier.fillMaxWidth(),
                minLines = if (f.type == RecordType.DIARY) 5 else 2,
            )

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    val toSave = if (manualMl && mlText.toIntOrNull() != null) {
                        f.copy(amountMl = mlText.toInt())
                    } else f
                    // Fill stool defaults if any stool field touched for poop types
                    val normalized = when (toSave.type) {
                        RecordType.POOP, RecordType.BOTH_DIAPER -> toSave.copy(
                            stoolAmount = toSave.stoolAmount.coerceIn(1, 4),
                            stoolConsistency = toSave.stoolConsistency.coerceIn(1, 4),
                            stoolColor = toSave.stoolColor.coerceIn(0, 7),
                        )
                        else -> toSave
                    }
                    vm.save(normalized, onDone)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) { Text("保存") }
        }
    }

    if (confirmDelete && f.id != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条记录？") },
            text = { Text("删除后可从汇总中消失，操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = { vm.delete(f.id!!, onDone) }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun AmountEditor(
    ml: Int,
    step: Int,
    manual: Boolean,
    mlText: String,
    onManualChange: (Boolean) -> Unit,
    onMlText: (String) -> Unit,
    onMl: (Int) -> Unit,
) {
    val candidates = remember(step, ml) { amountCandidates(step, ml) }
    if (manual) {
        OutlinedTextField(
            value = mlText,
            onValueChange = onMlText,
            label = { Text("毫升") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                TextButton(onClick = {
                    mlText.toIntOrNull()?.let(onMl)
                    onManualChange(false)
                }) { Text("完成") }
            },
        )
    } else {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onMl((ml - step).coerceAtLeast(1)) }) {
                Text("−$step", fontSize = 22.sp)
            }
            Text("${ml} ml", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(horizontal = 20.dp))
            TextButton(onClick = { onMl((ml + step).coerceAtMost(999)) }) {
                Text("+$step", fontSize = 22.sp)
            }
        }
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val center = amountCenterIndex(candidates, ml)
            candidates.forEachIndexed { idx, v ->
                val selected = v == ml || idx == center && v == ml
                FilterChip(
                    selected = v == ml,
                    onClick = { onMl(v) },
                    label = { Text("${v}ml") },
                )
            }
        }
        TextButton(onClick = {
            onMlText(ml.toString())
            onManualChange(true)
        }) { Text("键盘手输") }
    }
}

@Composable
private fun PeeAmountPicker(selected: Int, onSelect: (Int) -> Unit) {
    Text("尿量", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(1 to "小", 2 to "中", 3 to "大").forEach { (v, label) ->
            val on = selected == v
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onSelect(v) }
                    .border(
                        width = if (on) 2.dp else 1.dp,
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(16.dp),
                    )
                    .padding(12.dp),
            ) {
                Box(
                    Modifier
                        .size(if (v == 1) 28.dp else if (v == 2) 36.dp else 44.dp)
                        .clip(CircleShape)
                        .background(
                            if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("💧", fontSize = (14 + v * 4).sp)
                }
                Text(label)
            }
        }
    }
}

@Composable
private fun PoopPickers(form: EditForm, onChange: (EditForm) -> Unit) {
    Text("便量", style = MaterialTheme.typography.labelLarge)
    ChipRow((1..4).toList(), form.stoolAmount) { onChange(form.copy(stoolAmount = it)) }
    Spacer(Modifier.height(8.dp))
    Text("软硬", style = MaterialTheme.typography.labelLarge)
    ChipRow((1..4).toList(), form.stoolConsistency) { onChange(form.copy(stoolConsistency = it)) }
    Spacer(Modifier.height(8.dp))
    Text("颜色", style = MaterialTheme.typography.labelLarge)
    val colors = listOf(
        0xFF3E2723L, 0xFF5D4037L, 0xFF8D6E63L, 0xFFC4A35AL,
        0xFFD4E157L, 0xFFFFF176L, 0xFF81C784L, 0xFFE57373L,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.forEachIndexed { idx, argb ->
            val on = form.stoolColor == idx
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .border(
                        width = if (on) 3.dp else 0.dp,
                        color = MaterialTheme.colorScheme.onBackground,
                        shape = CircleShape,
                    )
                    .clickable { onChange(form.copy(stoolColor = idx)) },
            )
        }
    }
}

@Composable
private fun ChipRow(values: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        values.forEach { v ->
            FilterChip(
                selected = selected == v,
                onClick = { onSelect(v) },
                label = { Text("$v") },
            )
        }
    }
}
