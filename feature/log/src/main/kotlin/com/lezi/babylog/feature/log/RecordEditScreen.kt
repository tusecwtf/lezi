package com.lezi.babylog.feature.log

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.ui.peeAmountLabel
import com.lezi.babylog.core.ui.stoolAmountLabel
import com.lezi.babylog.core.ui.stoolColorLabel
import com.lezi.babylog.core.ui.stoolConsistencyLabel
import com.lezi.babylog.designsystem.LeziClockDialDialog
import com.lezi.babylog.designsystem.LeziPeeAmountMark
import com.lezi.babylog.designsystem.LeziStoolAmountMark
import com.lezi.babylog.designsystem.LeziStoolColorMark
import com.lezi.babylog.designsystem.LeziStoolConsistencyMark
import com.lezi.babylog.designsystem.resolveLeziLocalDateTime
import com.lezi.babylog.designsystem.timestampOnLeziDate
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.amountCandidates
import com.lezi.babylog.domain.amountCenterIndex
import com.lezi.babylog.domain.payloadInt
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
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
    var timeStepMin: Int = 1
        private set
    var lastNotes: List<String> = emptyList()
        private set

    suspend fun load(
        recordId: String?,
        newTypeKey: String?,
        initialDate: LocalDate,
    ): EditForm {
        val settings = settingsStore.settings.first()
        stepMl = settings.amountStepMl
        timeStepMin = settings.timeStepMin
        val baby = careLog.getCurrentBaby()
        if (recordId != null && recordId != "new") {
            val id = recordId.toLongOrNull()
            val rec = id?.let { careLog.getRecord(it) }
            if (rec != null) {
                return EditForm.fromRecord(rec)
            }
        }
        val type = RecordType.fromKey(newTypeKey.orEmpty()) ?: RecordType.MEMO
        return EditForm(
            type = type,
            babyId = baby?.id ?: 0L,
            timestamp = timestampOnDate(initialDate),
        )
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
    val preparedMl: Int = 0,
    val feedingDurationMin: Int = 0,
    val leftMin: Int = 0,
    val rightMin: Int = 0,
    val order: String = "LR",
    val nursingAmountMl: Int = 0,
    val peeAmount: Int = 2,
    val stoolAmount: Int = 3,
    val stoolConsistency: Int = 3,
    val stoolColor: Int = 0,
    val isNap: Boolean = false,
    val anomalyFlag: Boolean = false,
    val celsius: String = "36.5",
    val medicineName: String = "",
    val medicineDose: String = "",
    val body: String = "",
    val photoUris: List<String> = emptyList(),
    val rawPayloadJson: String = "{}",
) {
    fun toPayload(): String = when (type) {
        RecordType.FORMULA -> buildString {
            append("""{"amount_ml":$amountMl""")
            if (preparedMl > 0) append(""","prepared_ml":$preparedMl""")
            if (feedingDurationMin > 0) append(""","duration_min":$feedingDurationMin""")
            append("}")
        }
        RecordType.PUMPED_FEED, RecordType.PUMP_EXPRESS -> """{"amount_ml":$amountMl}"""
        RecordType.NURSING -> buildString {
            append("""{"left_min":$leftMin,"right_min":$rightMin,"order":${jsonStr(order)}""")
            if (nursingAmountMl > 0) append(""","amount_ml":$nursingAmountMl""")
            append("}")
        }
        RecordType.PEE ->
            """{"pee_amount":$peeAmount}"""
        RecordType.POOP ->
            """{"stool_amount":$stoolAmount,"stool_consistency":$stoolConsistency,"stool_color":$stoolColor}"""
        RecordType.BOTH_DIAPER ->
            """{"pee_amount":$peeAmount,"stool_amount":$stoolAmount,"stool_consistency":$stoolConsistency,"stool_color":$stoolColor}"""
        RecordType.SLEEP -> buildString {
            append("""{"is_nap":$isNap""")
            if (anomalyFlag) append(""","anomaly_flag":true""")
            append("}")
        }
        RecordType.TEMPERATURE ->
            """{"celsius":${celsius.toDoubleOrNull() ?: 36.5}}"""
        RecordType.MEDICINE ->
            """{"name":${jsonStr(medicineName)},"dose":${jsonStr(medicineDose)}}"""
        RecordType.DIARY, RecordType.MEMO -> buildString {
            append("""{"body":${jsonStr(body)}""")
            if (photoUris.isNotEmpty()) {
                append(""","photos":[${photoUris.joinToString(",") { jsonStr(it) }}]""")
            }
            append("}")
        }
        else -> rawPayloadJson.ifBlank { "{}" }
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
                preparedMl = payloadInt(p, "prepared_ml").coerceAtLeast(0),
                feedingDurationMin = payloadInt(p, "duration_min").coerceAtLeast(0),
                leftMin = payloadInt(p, "left_min"),
                rightMin = payloadInt(p, "right_min"),
                order = Regex(""""order"\s*:\s*"([^"]+)"""").find(p)?.groupValues?.getOrNull(1) ?: "LR",
                nursingAmountMl = payloadInt(p, "amount_ml").coerceAtLeast(0),
                peeAmount = payloadInt(p, "pee_amount").takeIf { it in 1..3 } ?: 2,
                stoolAmount = payloadInt(p, "stool_amount").takeIf { it in 1..4 } ?: 3,
                stoolConsistency = payloadInt(p, "stool_consistency").takeIf { it in 1..4 } ?: 3,
                stoolColor = payloadInt(p, "stool_color").coerceIn(0, 7),
                isNap = payloadBoolean(p, "is_nap"),
                anomalyFlag = payloadBoolean(p, "anomaly_flag"),
                celsius = Regex(""""celsius"\s*:\s*(-?\d+(?:\.\d+)?)""")
                    .find(p)?.groupValues?.getOrNull(1) ?: "36.5",
                medicineName = Regex(""""name"\s*:\s*"([^"]*)"""").find(p)?.groupValues?.getOrNull(1).orEmpty(),
                medicineDose = Regex(""""dose"\s*:\s*"([^"]*)"""").find(p)?.groupValues?.getOrNull(1).orEmpty(),
                body = jsonStringField(p, "body"),
                photoUris = jsonStringArrayField(p, "photos"),
                rawPayloadJson = p,
            )
        }
    }
}

private fun jsonStr(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun payloadBoolean(json: String, key: String): Boolean =
    Regex("\"${Regex.escape(key)}\"\\s*:\\s*(true|false)")
        .find(json)
        ?.groupValues
        ?.getOrNull(1)
        ?.toBooleanStrictOrNull()
        ?: false

private fun jsonStringField(json: String, key: String): String =
    Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"")
        .find(json)
        ?.groupValues
        ?.getOrNull(1)
        ?.replace("\\n", "\n")
        ?.replace("\\\"", "\"")
        ?.replace("\\\\", "\\")
        .orEmpty()

private fun jsonStringArrayField(json: String, key: String): List<String> {
    val body = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\\[(.*?)]")
        .find(json)
        ?.groupValues
        ?.getOrNull(1)
        ?: return emptyList()
    return Regex("\"((?:\\\\.|[^\"])*)\"")
        .findAll(body)
        .map { match ->
            match.groupValues[1]
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
        .toList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordEditRoute(
    recordId: String,
    newTypeKey: String,
    onDone: () -> Unit,
    initialDate: LocalDate = LocalDate.now(),
    vm: RecordEditViewModel = hiltViewModel(),
) {
    var form by remember { mutableStateOf<EditForm?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var manualMl by remember { mutableStateOf(false) }
    var mlText by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var clockTarget by remember { mutableStateOf<ClockTarget?>(null) }
    var clockError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(recordId, newTypeKey, initialDate) {
        form = vm.load(recordId, newTypeKey, initialDate)
    }
    val f = form ?: return
    val zone = ZoneId.systemDefault()

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(5),
    ) { uris ->
        if (uris.isNotEmpty()) {
            form = f.copy(photoUris = f.photoUris + uris.map { it.toString() })
        }
    }

    fun saveRecord() {
        val current = form ?: return
        if (current.timestamp > System.currentTimeMillis()) {
            clockError = "记录时刻不能晚于现在"
            return
        }
        if (current.type == RecordType.WALK && current.endTimestamp == null) {
            clockError = "请设置散步结束时刻"
            return
        }
        if (current.endTimestamp != null && current.endTimestamp <= current.timestamp) {
            clockError = "结束时刻必须晚于开始时刻"
            return
        }
        if (current.endTimestamp != null && current.endTimestamp > System.currentTimeMillis()) {
            clockError = "结束时刻不能晚于现在"
            return
        }
        val toSave = if (manualMl && mlText.toIntOrNull() != null) {
            current.copy(amountMl = mlText.toInt())
        } else {
            current
        }
        val normalized = when (toSave.type) {
            RecordType.POOP, RecordType.BOTH_DIAPER -> toSave.copy(
                stoolAmount = toSave.stoolAmount.coerceIn(1, 4),
                stoolConsistency = toSave.stoolConsistency.coerceIn(1, 4),
                stoolColor = toSave.stoolColor.coerceIn(0, 7),
            )
            else -> toSave
        }
        vm.save(normalized, onDone)
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
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
            ) {
                Button(
                    onClick = ::saveRecord,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .heightIn(min = 52.dp),
                ) {
                    Text("保存")
                }
            }
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
            val tsLabel = remember(f.timestamp) {
                LocalDateTime.ofInstant(Instant.ofEpochMilli(f.timestamp), zone)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            }
            // OutlinedTextField is read-only and used to steal focus — open pickers instead.
            Surface(
                onClick = { showDatePicker = true },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(tsLabel, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "点按修改日期，时刻统一使用圆盘调整",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showDatePicker = true }) {
                    Text("修改日期")
                }
                OutlinedButton(onClick = {
                    clockError = null
                    clockTarget = ClockTarget.Start
                }) {
                    Text("圆盘调时")
                }
            }
            clockError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
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
                    if (f.type == RecordType.FORMULA) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = f.preparedMl.takeIf { it > 0 }?.toString().orEmpty(),
                                onValueChange = {
                                    form = f.copy(preparedMl = it.toIntOrNull() ?: 0)
                                },
                                label = { Text("冲调量 ml（可选）") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = f.feedingDurationMin.takeIf { it > 0 }?.toString().orEmpty(),
                                onValueChange = {
                                    form = f.copy(feedingDurationMin = it.toIntOrNull() ?: 0)
                                },
                                label = { Text("耗时（分，可选）") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
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
                    OutlinedTextField(
                        value = f.nursingAmountMl.takeIf { it > 0 }?.toString().orEmpty(),
                        onValueChange = {
                            form = f.copy(nursingAmountMl = it.toIntOrNull() ?: 0)
                        },
                        label = { Text("估算奶量 ml（可选）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                RecordType.PEE -> PeeAmountPicker(f.peeAmount) { form = f.copy(peeAmount = it) }
                RecordType.POOP -> PoopPickers(f) { form = it }
                RecordType.BOTH_DIAPER -> {
                    PeeAmountPicker(f.peeAmount) { form = f.copy(peeAmount = it) }
                    Spacer(Modifier.height(12.dp))
                    PoopPickers(f) { form = it }
                }
                RecordType.SLEEP, RecordType.WALK -> {
                    val isSleep = f.type == RecordType.SLEEP
                    Text(
                        "开始 ${tsLabel}" + (f.endTimestamp?.let {
                            val end = Instant.ofEpochMilli(it).atZone(zone)
                            val nextDay = end.toLocalDate().isAfter(
                                Instant.ofEpochMilli(f.timestamp).atZone(zone).toLocalDate(),
                            )
                            " · 结束 " + (if (nextDay) "次日 " else "") +
                                end.format(DateTimeFormatter.ofPattern("HH:mm"))
                        } ?: if (isSleep) " · 进行中" else " · 未设置结束"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                clockError = null
                                clockTarget = ClockTarget.End
                            },
                        ) {
                            Text(if (f.endTimestamp == null) "设置结束时刻" else "修改结束时刻")
                        }
                        if (isSleep && f.endTimestamp != null) {
                            TextButton(onClick = { form = f.copy(endTimestamp = null) }) {
                                Text("保持进行中")
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !f.isNap,
                            onClick = { form = f.copy(isNap = false) },
                            label = { Text("夜间 / 长睡") },
                        )
                        FilterChip(
                            selected = f.isNap,
                            onClick = { form = f.copy(isNap = true) },
                            label = { Text("午睡") },
                        )
                    }
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
                RecordType.DIARY, RecordType.MEMO -> {
                    OutlinedTextField(
                        value = f.body,
                        onValueChange = { form = f.copy(body = it.take(800)) },
                        label = {
                            Text(if (f.type == RecordType.DIARY) "日记正文" else "内容")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = if (f.type == RecordType.DIARY) 5 else 2,
                    )
                    if (f.type == RecordType.DIARY) {
                        OutlinedButton(
                            onClick = {
                                photoPicker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("添加照片（${f.photoUris.size}）") }
                    }
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

            Spacer(Modifier.height(16.dp))
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

    if (showDatePicker) {
        val current = Instant.ofEpochMilli(f.timestamp).atZone(zone)
        // DatePicker uses UTC midnight millis for calendar days.
        val initialUtc = current.toLocalDate()
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initialUtc)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selected = dateState.selectedDateMillis
                        if (selected != null) {
                            val newDate = Instant.ofEpochMilli(selected)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            val safeDate = minOf(newDate, LocalDate.now(zone))
                            val resolved = resolveLeziLocalDateTime(
                                date = safeDate,
                                time = current.toLocalTime(),
                                zone = zone,
                                preferredOffset = current.offset,
                            )
                            if (resolved == null) {
                                clockError = "所选日期不存在当前时刻，请先选择其他时刻"
                            } else {
                                val requestedStart = resolved.toInstant().toEpochMilli()
                                val nowMillis = System.currentTimeMillis()
                                val duration = f.endTimestamp
                                    ?.minus(f.timestamp)
                                    ?.takeIf { it > 0L }
                                val latestStart = duration?.let { nowMillis - it } ?: nowMillis
                                val adjustedStart = minOf(requestedStart, latestStart)
                                form = f.copy(
                                    timestamp = adjustedStart,
                                    endTimestamp = duration?.let { adjustedStart + it },
                                )
                                clockError = if (newDate != safeDate || requestedStart != adjustedStart) {
                                    "记录时段不能晚于现在，已自动调整"
                                } else {
                                    null
                                }
                                clockTarget = ClockTarget.Start
                            }
                        }
                        showDatePicker = false
                    },
                ) { Text("下一步") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = dateState)
        }
    }

    clockTarget?.let { target ->
        val initialMillis = when (target) {
            ClockTarget.Start -> f.timestamp
            ClockTarget.End -> f.endTimestamp
                ?: maxOf(f.timestamp + 60_000L, System.currentTimeMillis())
        }
        LeziClockDialDialog(
            title = if (target == ClockTarget.Start) "选择记录时刻" else "选择结束时刻",
            value = Instant.ofEpochMilli(initialMillis).atZone(zone),
            minuteStep = vm.timeStepMin,
            onConfirm = { picked ->
                val now = ZonedDateTime.now(zone)
                when (target) {
                    ClockTarget.Start -> {
                        val existingEnd = f.endTimestamp
                        when {
                            picked.isAfter(now) -> {
                                clockError = "记录时刻不能晚于现在"
                            }
                            existingEnd != null &&
                                picked.toInstant().toEpochMilli() >= existingEnd -> {
                                clockError = "开始时刻必须早于结束时刻"
                            }
                            else -> {
                                form = f.copy(timestamp = picked.toInstant().toEpochMilli())
                                clockError = null
                            }
                        }
                    }
                    ClockTarget.End -> {
                        val start = Instant.ofEpochMilli(f.timestamp).atZone(zone)
                        val pickedClock = picked.toLocalTime()
                        val end = resolveSleepEnd(start, pickedClock)
                        when {
                            end == null -> {
                                clockError = "结束时刻不能与开始时刻相同"
                            }
                            end.isAfter(now) -> {
                                clockError = "结束时刻不能晚于现在"
                            }
                            else -> {
                                form = f.copy(endTimestamp = end.toInstant().toEpochMilli())
                                clockError = null
                            }
                        }
                    }
                }
                clockTarget = null
            },
            onDismiss = { clockTarget = null },
        )
    }
}

private enum class ClockTarget { Start, End }

internal fun timestampOnDate(
    date: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
    now: ZonedDateTime = ZonedDateTime.now(zone),
): Long {
    return timestampOnLeziDate(date, zone, now)
}

internal fun resolveSleepEnd(
    start: ZonedDateTime,
    pickedClock: LocalTime,
): ZonedDateTime? {
    if (start.hour == pickedClock.hour && start.minute == pickedClock.minute) {
        return null
    }
    val sameDayCandidate = resolveLeziLocalDateTime(
        date = start.toLocalDate(),
        time = pickedClock,
        zone = start.zone,
        preferredOffset = start.offset,
    ) ?: return null
    return if (sameDayCandidate.isAfter(start)) {
        sameDayCandidate
    } else {
        resolveLeziLocalDateTime(
            date = start.toLocalDate().plusDays(1),
            time = pickedClock,
            zone = start.zone,
            preferredOffset = start.offset,
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
    Row(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        (1..3).forEach { value ->
            val label = peeAmountLabel(value)
            ExcretionChoice(
                label = label,
                semanticLabel = "尿量$label",
                selected = selected == value,
                onClick = { onSelect(value) },
                modifier = Modifier.weight(1f),
            ) {
                LeziPeeAmountMark(value, modifier = Modifier.size(30.dp))
            }
        }
    }
}

@Composable
private fun PoopPickers(form: EditForm, onChange: (EditForm) -> Unit) {
    Text("便量", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        (1..4).forEach { value ->
            val label = stoolAmountLabel(value)
            ExcretionChoice(
                label = label,
                semanticLabel = "便量$label",
                selected = form.stoolAmount == value,
                onClick = { onChange(form.copy(stoolAmount = value)) },
                modifier = Modifier.weight(1f),
            ) {
                LeziStoolAmountMark(value, modifier = Modifier.size(28.dp))
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Text("软硬", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        (1..4).forEach { value ->
            val label = stoolConsistencyLabel(value)
            ExcretionChoice(
                label = label,
                semanticLabel = "便便软硬$label",
                selected = form.stoolConsistency == value,
                onClick = { onChange(form.copy(stoolConsistency = value)) },
                modifier = Modifier.weight(1f),
            ) {
                LeziStoolConsistencyMark(value, modifier = Modifier.size(28.dp))
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Text("颜色", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(8.dp))
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        (0..7).chunked(4).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                row.forEach { value ->
                    val label = stoolColorLabel(value)
                    ExcretionChoice(
                        label = label,
                        semanticLabel = "便便颜色$label",
                        selected = form.stoolColor == value,
                        onClick = { onChange(form.copy(stoolColor = value)) },
                        modifier = Modifier.weight(1f),
                    ) {
                        LeziStoolColorMark(value, modifier = Modifier.size(30.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ExcretionChoice(
    label: String,
    semanticLabel: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    mark: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .heightIn(min = 68.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            )
            .semantics {
                contentDescription = semanticLabel
                stateDescription = if (selected) "已选择" else "未选择"
            },
        shape = RoundedCornerShape(8.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Column(
            Modifier.padding(horizontal = 3.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            mark()
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}
