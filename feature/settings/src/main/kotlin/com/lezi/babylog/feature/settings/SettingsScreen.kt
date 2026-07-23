package com.lezi.babylog.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.core.ui.BabyAvatar
import com.lezi.babylog.designsystem.LeziCard
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.PageScaffoldBackground
import com.lezi.babylog.designsystem.SectionHeading
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.CreateBabyInput
import com.lezi.babylog.domain.DuplicateBabyNicknameException
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUi(
    val settings: SettingsLocal = SettingsLocal(),
    val babies: List<Baby> = emptyList(),
    val current: Baby? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val careLog: CareLog,
) : ViewModel() {
    val ui = combine(
        settingsStore.settings,
        careLog.observeBabies(),
        careLog.observeCurrentBaby(),
    ) { s, babies, cur ->
        SettingsUi(s, babies, cur)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUi())

    fun setDark(mode: String) = viewModelScope.launch { settingsStore.setDarkMode(mode) }
    fun setTimer(enabled: Boolean) = viewModelScope.launch { settingsStore.setTimerEnabled(enabled) }
    fun setStep(step: Int) = viewModelScope.launch { settingsStore.setAmountStepMl(step) }
    fun setTimeStep(step: Int) = viewModelScope.launch { settingsStore.setTimeStepMin(step) }
    fun setInterval(min: Int) = viewModelScope.launch { settingsStore.setNursingIntervalMin(min) }
    fun setRecordAt(v: String) = viewModelScope.launch { settingsStore.setRecordAt(v) }
    fun setCurrent(id: Long) = viewModelScope.launch { careLog.setCurrentBaby(id) }
    fun setVisualStyle(key: String) = viewModelScope.launch { settingsStore.setVisualStyle(key) }
    fun setPreferredHand(hand: String) = viewModelScope.launch { settingsStore.setPreferredHand(hand) }

    fun addBaby(
        nickname: String,
        birthdayEpochDay: Long,
        birthWeightGrams: Int?,
        onDone: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            val result = runCatching {
                careLog.addBaby(
                    CreateBabyInput(
                        nickname = nickname,
                        birthdayEpochDay = birthdayEpochDay,
                        birthWeightGrams = birthWeightGrams,
                    ),
                )
            }
            onDone(
                result.exceptionOrNull()?.let { e ->
                    if (e is DuplicateBabyNicknameException) e.message
                    else e.message ?: "添加失败"
                },
            )
        }
    }

    /** Clears records only — babies are never deleted from settings. */
    fun clearRecords(onDone: () -> Unit) {
        viewModelScope.launch {
            careLog.clearRecordsOnly()
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    onOpenExport: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var clearStep by remember { mutableIntStateOf(0) }
    var newName by remember { mutableStateOf("") }
    var newBirthday by remember { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var newWeight by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    var showAddDate by remember { mutableStateOf(false) }
    var showFeed by remember { mutableStateOf(false) }
    var showDisplay by remember { mutableStateOf(false) }

    PageScaffoldBackground {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(LeziSpacing.Page),
            verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
        ) {
            com.lezi.babylog.designsystem.PageHero(
                eyebrow = "",
                title = "菜单",
            )

            Text("查找与管理", style = LeziTypography.Eyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MenuRow("搜索全部记录", "按类型、详情或备注查找", icon = "⌕", onClick = onOpenSearch)
            MenuRow("导出数据", "TXT 文本预览与分享", icon = "⇪", onClick = onOpenExport)
            MenuRow("日程", "本机提醒与日程列表", icon = "▦", onClick = onOpenCalendar)

            Text("外观与偏好", style = LeziTypography.Eyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MenuRow(
                title = "深色模式",
                subtitle = "当前：${when (ui.settings.darkMode) {
                    "dark" -> "深色"
                    "light" -> "浅色"
                    else -> "跟随系统"
                }}",
                icon = "☾",
                trailing = {
                    Switch(
                        checked = ui.settings.darkMode == "dark",
                        onCheckedChange = { on -> vm.setDark(if (on) "dark" else "light") },
                    )
                },
            )
            MenuRow("记录设置", "计时、步进、喂奶间隔", icon = "☰", onClick = { showFeed = true })
            MenuRow("显示设置", "界面模板与主题", icon = "◐", onClick = { showDisplay = true })

            Text("宝宝", style = LeziTypography.Eyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ui.babies.forEach { b ->
                val birth = LocalDate.ofEpochDay(b.birthdayEpochDay)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                val weight = b.birthWeightGrams?.let { " · ${it}g" }.orEmpty()
                MenuRow(
                    title = b.nickname + if (ui.current?.id == b.id) "（当前）" else "",
                    subtitle = "出生 $birth$weight · 点选切换",
                    onClick = { vm.setCurrent(b.id) },
                    leading = {
                        BabyAvatar(
                            nickname = b.nickname,
                            avatarPath = b.avatarPath,
                            fallbackBackground = Color(b.themeColorArgb),
                            modifier = Modifier.size(40.dp),
                            borderWidth = 2.dp,
                            avatarContentDescription = "${b.nickname}的头像",
                        )
                    },
                    trailing = {
                        Box(
                            Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Color(b.themeColorArgb))
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), CircleShape),
                        )
                    },
                )
            }
            MenuRow("添加宝宝", "新建本机宝宝档案", icon = "+", onClick = { showAdd = true })

            Text("数据", style = LeziTypography.Eyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MenuRow(
                title = "清除全部记录",
                subtitle = "不删除宝宝档案",
                icon = "!",
                onClick = { clearStep = 1 },
                danger = true,
            )

            Text("关于", style = LeziTypography.Eyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LeziCard(Modifier.fillMaxWidth()) {
                Text("乐记", style = LeziTypography.TitleSm)
                Text(
                    "无广告 · 无内购 · 本地优先",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(LeziSpacing.Xxl))
        }
    }

    if (showFeed) {
        AlertDialog(
            onDismissRequest = { showFeed = false },
            title = { Text("记录设置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("喂奶计时入口")
                        Switch(checked = ui.settings.timerEnabled, onCheckedChange = vm::setTimer)
                    }
                    Text("记录时刻", style = LeziTypography.Label)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = ui.settings.recordAtStartOrEnd == "start",
                            onClick = { vm.setRecordAt("start") },
                            label = { Text("开始") },
                        )
                        FilterChip(
                            selected = ui.settings.recordAtStartOrEnd == "end",
                            onClick = { vm.setRecordAt("end") },
                            label = { Text("结束") },
                        )
                    }
                    Text("下次喂奶间隔（分钟）", style = LeziTypography.Label)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(120, 150, 180, 210, 240).forEach { m ->
                            FilterChip(
                                selected = ui.settings.nursingIntervalMin == m,
                                onClick = { vm.setInterval(m) },
                                label = { Text("$m") },
                            )
                        }
                    }
                    Text("奶量步进 ml", style = LeziTypography.Label)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 10, 15).forEach { s ->
                            FilterChip(
                                selected = ui.settings.amountStepMl == s,
                                onClick = { vm.setStep(s) },
                                label = { Text("$s") },
                            )
                        }
                    }
                    Text("圆盘时钟步进", style = LeziTypography.Label)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1 to "1 分钟", 5 to "5 分钟").forEach { (step, label) ->
                            FilterChip(
                                selected = ui.settings.timeStepMin == step,
                                onClick = { vm.setTimeStep(step) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFeed = false }) { Text("完成") } },
        )
    }

    if (showDisplay) {
        AlertDialog(
            onDismissRequest = { showDisplay = false },
            title = { Text("显示设置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("界面模板", style = LeziTypography.Label)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("warm" to "温暖卡片", "journal" to "紧凑记录簿").forEach { (key, label) ->
                            FilterChip(
                                selected = ui.settings.visualStyle == key,
                                onClick = { vm.setVisualStyle(key) },
                                label = { Text(label) },
                            )
                        }
                    }
                    Text("单手操作 · 惯用手", style = LeziTypography.Label)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("left" to "左手", "right" to "右手").forEach { (key, label) ->
                            FilterChip(
                                selected = ui.settings.preferredHand == key,
                                onClick = { vm.setPreferredHand(key) },
                                label = { Text(label) },
                            )
                        }
                    }
                    Text(
                        "常用记录会固定在屏幕底部，并把最高频入口靠近所选拇指侧。",
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("深色模式", style = LeziTypography.Label)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (k, label) ->
                            FilterChip(
                                selected = ui.settings.darkMode == k,
                                onClick = { vm.setDark(k) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDisplay = false }) { Text("完成") } },
        )
    }

    if (showAdd) {
        val dateLabel = LocalDate.ofEpochDay(newBirthday)
            .format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
        AlertDialog(
            onDismissRequest = {
                showAdd = false
                addError = null
            },
            title = { Text("添加宝宝") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = {
                            newName = it
                            addError = null
                        },
                        label = { Text("昵称（不可重复）") },
                        singleLine = true,
                        isError = addError != null,
                        supportingText = addError?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("出生日期", style = LeziTypography.Label)
                    OutlinedButton(
                        onClick = { showAddDate = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(dateLabel) }
                    OutlinedTextField(
                        value = newWeight,
                        onValueChange = { newWeight = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        label = { Text("出生体重（kg，可选）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newName.isBlank()) {
                            addError = "请填写昵称"
                            return@TextButton
                        }
                        val grams = newWeight.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
                            ?.let { (it * 1000).toInt() }
                        if (newWeight.isNotBlank() && grams == null) {
                            addError = "出生体重格式不正确"
                            return@TextButton
                        }
                        vm.addBaby(newName.trim(), newBirthday, grams) { err ->
                            if (err == null) {
                                newName = ""
                                newWeight = ""
                                newBirthday = LocalDate.now().toEpochDay()
                                addError = null
                                showAdd = false
                            } else {
                                addError = err
                            }
                        }
                    },
                ) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAdd = false
                    addError = null
                }) { Text("取消") }
            },
        )
    }

    if (showAddDate) {
        val initialUtc = LocalDate.ofEpochDay(newBirthday)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initialUtc)
        DatePickerDialog(
            onDismissRequest = { showAddDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        dateState.selectedDateMillis?.let { ms ->
                            newBirthday = Instant.ofEpochMilli(ms)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                                .toEpochDay()
                        }
                        showAddDate = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDate = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = dateState)
        }
    }

    if (clearStep == 1) {
        AlertDialog(
            onDismissRequest = { clearStep = 0 },
            title = { Text("确认清除记录？") },
            text = { Text("将删除全部喂养、睡眠等记录，宝宝档案会保留。") },
            confirmButton = {
                TextButton(onClick = { clearStep = 2 }) {
                    Text("继续", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { clearStep = 0 }) { Text("取消") }
            },
        )
    }
    if (clearStep == 2) {
        AlertDialog(
            onDismissRequest = { clearStep = 0 },
            title = { Text("最后确认") },
            text = { Text("真的要清除全部记录吗？宝宝不会被删除。") },
            confirmButton = {
                TextButton(onClick = { vm.clearRecords { clearStep = 0 } }) {
                    Text("清除记录", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { clearStep = 0 }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun MenuRow(
    title: String,
    subtitle: String,
    icon: String = "·",
    onClick: (() -> Unit)? = null,
    danger: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    LeziCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
            } else {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (danger) {
                                MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                            } else {
                                com.lezi.babylog.designsystem.LeziThemeExt.colors.creamDeep.copy(alpha = 0.85f)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        icon,
                        style = LeziTypography.BodyStrong,
                        color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = LeziTypography.BodyStrong,
                    color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Text(subtitle, style = LeziTypography.Meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (trailing != null) {
                trailing()
            } else {
                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
