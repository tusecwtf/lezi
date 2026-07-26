package com.lezi.babylog.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.core.model.limitBabyNicknameInput
import com.lezi.babylog.core.model.birthWeightValidationError
import com.lezi.babylog.core.ui.BabyAvatar
import com.lezi.babylog.designsystem.LeziCard
import com.lezi.babylog.designsystem.LeziDatePicker
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.PageScaffoldBackground
import com.lezi.babylog.designsystem.SectionHeading
import com.lezi.babylog.designsystem.dismissKeyboardOnTap
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.CreateBabyInput
import com.lezi.babylog.domain.CustomRecordItem
import com.lezi.babylog.domain.LocalRecordsClearCommittedException
import com.lezi.babylog.sync.SyncPort
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val BabyThemePalette = listOf(
    0xFF007BAE.toInt(),
    0xFFAA442B.toInt(),
    0xFF2F8F6B.toInt(),
    0xFF7A5CFF.toInt(),
    0xFFE09F3E.toInt(),
    0xFFD4578C.toInt(),
    0xFF4C6A92.toInt(),
    0xFF5B8C5A.toInt(),
)
private val BabyThemePaletteLabels = listOf(
    "湖蓝",
    "砖红",
    "青绿",
    "紫罗兰",
    "琥珀",
    "玫红",
    "灰蓝",
    "草绿",
)

internal fun clearRecordsFailureCopy(error: Throwable): String = when {
    error is LocalRecordsClearCommittedException && error.familyServerRetained ->
        "本机记录可能已部分清理，家庭服务器上的记录仍保留，请重试"
    error is LocalRecordsClearCommittedException -> "本机记录可能已部分清理，请重试"
    else -> productUiError(error, "清除失败，请重试")
}

data class SettingsUi(
    val settings: SettingsLocal = SettingsLocal(),
    val showAvgSleep: Boolean = false,
    val comparePrevWeek: Boolean = false,
    val babies: List<Baby> = emptyList(),
    val current: Baby? = null,
    val customItems: List<CustomRecordItem> = emptyList(),
    val isFamilyJoined: Boolean = false,
)

private data class LocalSettingsUi(
    val settings: SettingsLocal,
    val showAvgSleep: Boolean,
    val comparePrevWeek: Boolean,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val careLog: CareLog,
    private val syncPort: SyncPort,
) : ViewModel() {
    private val localSettings = combine(
        settingsStore.settings,
        settingsStore.showAvgSleep,
        settingsStore.comparePrevWeek,
    ) { settings, showAvgSleep, comparePrevWeek ->
        LocalSettingsUi(settings, showAvgSleep, comparePrevWeek)
    }

    val ui = combine(
        localSettings,
        careLog.observeBabies(),
        careLog.observeCurrentBaby(),
        careLog.observeCustomItems(),
        syncPort.session(),
    ) { local, babies, cur, customItems, session ->
        SettingsUi(
            settings = local.settings,
            showAvgSleep = local.showAvgSleep,
            comparePrevWeek = local.comparePrevWeek,
            babies = babies,
            current = cur,
            customItems = customItems,
            isFamilyJoined = session.isJoined,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUi())

    fun setDark(mode: String) = viewModelScope.launch { settingsStore.setDarkMode(mode) }
    fun setTimer(enabled: Boolean) = viewModelScope.launch { settingsStore.setTimerEnabled(enabled) }
    fun setStep(step: Int) = viewModelScope.launch { settingsStore.setAmountStepMl(step) }
    fun setTimeStep(step: Int) = viewModelScope.launch { settingsStore.setTimeStepMin(step) }
    fun setTimePickerStyle(style: String) =
        viewModelScope.launch { settingsStore.setTimePickerStyle(style) }
    fun setInfantFeverAdvice(enabled: Boolean) =
        viewModelScope.launch { settingsStore.setInfantFeverAdviceEnabled(enabled) }
    fun setInterval(min: Int) = viewModelScope.launch { settingsStore.setNursingIntervalMin(min) }
    fun setRecordAt(v: String) = viewModelScope.launch { settingsStore.setRecordAt(v) }
    fun setCurrent(id: Long) = viewModelScope.launch { careLog.setCurrentBaby(id) }
    fun setVisualStyle(key: String) = viewModelScope.launch { settingsStore.setVisualStyle(key) }
    fun setPreferredHand(hand: String) = viewModelScope.launch { settingsStore.setPreferredHand(hand) }
    fun setTimelineOrder(order: String) =
        viewModelScope.launch { settingsStore.setTimelineOrder(order) }
    fun setWeekStart(day: Int) = viewModelScope.launch { settingsStore.setWeekStart(day) }
    fun setCarePlanLocalReminders(enabled: Boolean) =
        viewModelScope.launch { settingsStore.setCarePlanLocalRemindersEnabled(enabled) }

    fun setSystemCalendarEnabled(enabled: Boolean) =
        viewModelScope.launch { settingsStore.setSystemCalendarEnabled(enabled) }

    fun setSystemCalendarId(calendarId: String?) =
        viewModelScope.launch {
            val prefs = settingsStore.settings.first()
            val wasConfigured = prefs.systemCalendarEnabled &&
                !prefs.systemCalendarId.isNullOrBlank()
            settingsStore.setSystemCalendarId(calendarId)
            if (!calendarId.isNullOrBlank()) {
                settingsStore.setSystemCalendarEnabled(true)
                // First enable defaults to L2; later target changes keep user disclosure.
                if (!wasConfigured) {
                    settingsStore.setSystemCalendarDisclosureLevel(2)
                }
                // Best-effort: project open-future plans to the (new) target.
                careLog.reprojectOpenFutureSystemCalendarCopies()
            } else {
                settingsStore.setSystemCalendarEnabled(false)
            }
        }

    /**
     * Device-local disclosure grade. Reprojects only open-future plans so
     * historical calendar copies are not bulk-expanded.
     */
    fun setSystemCalendarDisclosureLevel(level: Int) =
        viewModelScope.launch {
            settingsStore.setSystemCalendarDisclosureLevel(level)
            careLog.reprojectOpenFutureSystemCalendarCopies()
        }
    fun setShowAvgSleep(enabled: Boolean) =
        viewModelScope.launch { settingsStore.setShowAvgSleep(enabled) }
    fun setComparePrevWeek(enabled: Boolean) =
        viewModelScope.launch { settingsStore.setComparePrevWeek(enabled) }
    fun moveRecordType(typeKey: String, delta: Int) = viewModelScope.launch {
        val customs = ui.value.customItems.map { it.id }
        val known = com.lezi.babylog.core.ui.knownCatalogKeys(customs)
        val next = com.lezi.babylog.core.ui.moveCatalogKeyWithinSection(
            itemOrderJson = ui.value.settings.itemOrderJson,
            catalogKey = typeKey,
            delta = delta,
            allKnownKeys = known,
        )
        settingsStore.setItemOrderJson(next)
    }

    fun setItemOrderJson(json: String) = viewModelScope.launch {
        settingsStore.setItemOrderJson(json)
    }

    fun setCategoryOrderJson(json: String) = viewModelScope.launch {
        settingsStore.setCategoryOrderJson(json)
    }

    fun toggleHiddenItem(typeKey: String) = viewModelScope.launch {
        val current = ui.value.settings.hiddenItems
        settingsStore.setHiddenItems(
            if (typeKey in current) current - typeKey else current + typeKey,
        )
    }

    fun setQuickRecordSlots(slots: List<String>) = viewModelScope.launch {
        settingsStore.setQuickRecordSlots(slots)
    }

    fun addBaby(
        nickname: String,
        sex: String?,
        birthdayEpochDay: Long,
        birthWeightGrams: Int?,
        themeColorArgb: Int,
        onDone: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            val result = runCatching {
                careLog.addBaby(
                    CreateBabyInput(
                        nickname = nickname,
                        sex = sex,
                        birthdayEpochDay = birthdayEpochDay,
                        birthWeightGrams = birthWeightGrams,
                        themeColorArgb = themeColorArgb,
                    ),
                )
            }
            onDone(
                result.exceptionOrNull()?.let { e ->
                    productUiError(e, "添加失败")
                },
            )
        }
    }

    fun addCustomItem(name: String, iconSlot: Int, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val result = runCatching { careLog.addCustomItem(name, iconSlot) }
            onDone(result.exceptionOrNull()?.let { productUiError(it, "添加失败") })
        }
    }

    fun updateCustomItem(item: CustomRecordItem, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val result = runCatching { careLog.updateCustomItem(item) }
            onDone(result.exceptionOrNull()?.let { productUiError(it, "保存失败") })
        }
    }

    fun moveCustomItem(id: Long, delta: Int) =
        viewModelScope.launch {
            runCatching { careLog.moveCustomItem(id, delta) }
        }

    fun deleteCustomItem(id: Long, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            val result = runCatching { careLog.deleteCustomItem(id) }
            onDone(result.exceptionOrNull()?.let { productUiError(it, "删除失败") })
        }
    }

    /** Domain ACL: owner/admin manage all; members only their own definitions. */
    suspend fun canManageCustomItem(item: CustomRecordItem): Boolean =
        careLog.canManageCustomItem(item)

    /** Clears records only — babies are never deleted from settings. */
    fun clearRecords(onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val result = runCatching { careLog.clearRecordsOnly() }
            onDone(result.exceptionOrNull()?.let(::clearRecordsFailureCopy))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsRoute(
    onOpenExport: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    initiallyShowAddBaby: Boolean = false,
    onInitialAddBabyFinished: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val clearRecordsCopy = clearRecordsConfirmationCopy(ui.isFamilyJoined)
    var showAdd by remember(initiallyShowAddBaby) { mutableStateOf(initiallyShowAddBaby) }
    var clearStep by remember { mutableIntStateOf(0) }
    var clearingRecords by remember { mutableStateOf(false) }
    var clearRecordsError by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var newSex by remember { mutableStateOf<String?>(null) }
    var newBirthday by remember { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var newWeight by remember { mutableStateOf("") }
    var newThemeIndex by remember { mutableIntStateOf(0) }
    var addError by remember { mutableStateOf<String?>(null) }
    var showAddDate by remember { mutableStateOf(false) }
    var showDisplay by remember { mutableStateOf(false) }
    var showRecordHub by remember { mutableStateOf(false) }
    var showRecordItems by remember { mutableStateOf(false) }
    var showCustomItems by remember { mutableStateOf(false) }
    var showQuickSlots by remember { mutableStateOf(false) }
    var showPerItem by remember { mutableStateOf(false) }
    var showPlanCalendar by remember { mutableStateOf(false) }
    var showSystemCalendarSetup by remember { mutableStateOf(false) }
    fun finishAddBabyDialog() {
        showAdd = false
        addError = null
        if (initiallyShowAddBaby) onInitialAddBabyFinished()
    }

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
            MenuRow(
                "记录与快捷设置",
                "常用槽位、项目排序、分项目与计划日历",
                icon = "☰",
                onClick = { showRecordHub = true },
            )
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
                onClick = {
                    clearRecordsError = null
                    clearStep = 1
                },
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

    if (showRecordHub) {
        RecordAndShortcutSettingsHubDialog(
            onDismiss = { showRecordHub = false },
            onOpen = { dest ->
                showRecordHub = false
                when (dest) {
                    RecordShortcutHubDestination.QuickSlots -> showQuickSlots = true
                    RecordShortcutHubDestination.AllItems -> showRecordItems = true
                    RecordShortcutHubDestination.PerItem -> showPerItem = true
                    RecordShortcutHubDestination.PlanCalendar -> showPlanCalendar = true
                }
            },
        )
    }

    if (showPerItem) {
        PerItemSettingsDialog(
            settings = ui.settings,
            onDismiss = { showPerItem = false },
            onTimerEnabled = vm::setTimer,
            onRecordAt = vm::setRecordAt,
            onInterval = vm::setInterval,
            onAmountStep = vm::setStep,
            onFeverAdvice = vm::setInfantFeverAdvice,
        )
    }

    if (showPlanCalendar) {
        PlanCalendarSettingsDialog(
            carePlanRemindersEnabled = ui.settings.carePlanLocalRemindersEnabled,
            onCarePlanRemindersEnabled = vm::setCarePlanLocalReminders,
            systemCalendarEnabled = ui.settings.systemCalendarEnabled &&
                !ui.settings.systemCalendarId.isNullOrBlank(),
            systemCalendarSummary = ui.settings.systemCalendarId?.let { "日历 $it" } ?: "未配置",
            systemCalendarDisclosureSummary = systemCalendarDisclosureLabel(
                ui.settings.systemCalendarDisclosureLevel,
            ),
            onConfigureSystemCalendar = {
                // Explicit user action only — opens device-local target pick flow.
                // Permission request is deferred to the configure surface (no passive prompt).
                showSystemCalendarSetup = true
            },
            onDismiss = { showPlanCalendar = false },
        )
    }

    if (showSystemCalendarSetup) {
        SystemCalendarSetupDialog(
            currentCalendarId = ui.settings.systemCalendarId,
            currentDisclosureLevel = ui.settings.systemCalendarDisclosureLevel,
            onPick = { calendarId ->
                vm.setSystemCalendarId(calendarId)
                showSystemCalendarSetup = false
            },
            onDisclosureLevel = vm::setSystemCalendarDisclosureLevel,
            onDisable = {
                vm.setSystemCalendarId(null)
                showSystemCalendarSetup = false
            },
            onDismiss = { showSystemCalendarSetup = false },
        )
    }

    if (showDisplay) {
        AlertDialog(
            onDismissRequest = { showDisplay = false },
            title = { Text("显示设置") },
            text = {
                ScrollableDialogColumn {
                    Text("界面模板", style = LeziTypography.Label)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf("warm" to "温暖卡片", "journal" to "紧凑记录簿").forEach { (key, label) ->
                            FilterChip(
                                selected = ui.settings.visualStyle == key,
                                onClick = { vm.setVisualStyle(key) },
                                label = { Text(label) },
                            )
                        }
                    }
                    Text("单手操作 · 惯用手", style = LeziTypography.Label)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
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
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (k, label) ->
                            FilterChip(
                                selected = ui.settings.darkMode == k,
                                onClick = { vm.setDark(k) },
                                label = { Text(label) },
                            )
                        }
                    }
                    Text("时间选择方式", style = LeziTypography.Label)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf(
                            "dropdown" to "数字时钟",
                            "dial" to "指针时钟",
                        ).forEach { (key, label) ->
                            FilterChip(
                                selected = ui.settings.timePickerStyle == key,
                                onClick = { vm.setTimePickerStyle(key) },
                                label = { Text(label) },
                            )
                        }
                    }
                    Text(
                        "数字时钟为 0–23 点下拉；指针时钟为圆盘，上午/下午竖排在时分大按钮旁（位置跟随惯用手）。",
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("时间选择分钟步进", style = LeziTypography.Label)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf(1 to "1 分钟", 5 to "5 分钟").forEach { (step, label) ->
                            FilterChip(
                                selected = ui.settings.timeStepMin == step,
                                onClick = { vm.setTimeStep(step) },
                                label = { Text(label) },
                            )
                        }
                    }
                    Text("时间轴顺序", style = LeziTypography.Label)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf(
                            "newest_first" to "新→旧",
                            "oldest_first" to "旧→新",
                        ).forEach { (key, label) ->
                            FilterChip(
                                selected = ui.settings.timelineOrder == key,
                                onClick = { vm.setTimelineOrder(key) },
                                label = { Text(label) },
                            )
                        }
                    }
                    Text("汇总周起始日", style = LeziTypography.Label)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf(1 to "周一", 7 to "周日").forEach { (day, label) ->
                            FilterChip(
                                selected = ui.settings.weekStart == day,
                                onClick = { vm.setWeekStart(day) },
                                label = { Text(label) },
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("显示日均睡眠")
                        Switch(
                            checked = ui.showAvgSleep,
                            onCheckedChange = vm::setShowAvgSleep,
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("周汇总对比上周")
                        Switch(
                            checked = ui.comparePrevWeek,
                            onCheckedChange = vm::setComparePrevWeek,
                        )
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
            onDismissRequest = ::finishAddBabyDialog,
            modifier = Modifier.imePadding(),
            properties = DialogProperties(decorFitsSystemWindows = false),
            title = { Text("添加宝宝") },
            text = {
                ScrollableDialogColumn {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = {
                            newName = limitBabyNicknameInput(it)
                            addError = null
                        },
                        label = { Text("昵称（不可重复）") },
                        singleLine = true,
                        isError = addError != null,
                        supportingText = addError?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("性别", style = LeziTypography.Label)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf(
                            "FEMALE" to "女宝",
                            "MALE" to "男宝",
                            "UNKNOWN" to "未设置",
                        ).forEach { (key, label) ->
                            FilterChip(
                                selected = newSex == key,
                                onClick = { newSex = key },
                                label = { Text(label) },
                            )
                        }
                    }
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
                    Text("主题色", style = LeziTypography.Label)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        maxItemsInEachRow = 4,
                    ) {
                        BabyThemePalette.forEachIndexed { index, argb ->
                            FilterChip(
                                selected = newThemeIndex == index,
                                onClick = { newThemeIndex = index },
                                modifier = Modifier.semantics {
                                    contentDescription = "主题色：${BabyThemePaletteLabels[index]}"
                                },
                                label = {
                                    Box(
                                        Modifier
                                            .size(18.dp)
                                            .clip(CircleShape)
                                            .background(Color(argb)),
                                    )
                                },
                            )
                        }
                    }
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
                        birthWeightValidationError(grams)?.let {
                            addError = it
                            return@TextButton
                        }
                        vm.addBaby(
                            nickname = newName.trim(),
                            sex = newSex,
                            birthdayEpochDay = newBirthday,
                            birthWeightGrams = grams,
                            themeColorArgb = BabyThemePalette[newThemeIndex],
                        ) { err ->
                            if (err == null) {
                                newName = ""
                                newSex = null
                                newWeight = ""
                                newBirthday = LocalDate.now().toEpochDay()
                                newThemeIndex = 0
                                finishAddBabyDialog()
                            } else {
                                addError = err
                            }
                        }
                    },
                ) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = ::finishAddBabyDialog) { Text("取消") }
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
            LeziDatePicker(state = dateState)
        }
    }

    if (showCustomItems) {
        var manageableIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
        LaunchedEffect(ui.customItems) {
            val allowed = mutableSetOf<Long>()
            for (item in ui.customItems) {
                if (vm.canManageCustomItem(item)) allowed += item.id
            }
            manageableIds = allowed
        }
        CustomItemSettingsDialog(
            items = ui.customItems,
            hiddenItems = ui.settings.hiddenItems,
            onDismiss = { showCustomItems = false },
            onAdd = vm::addCustomItem,
            onUpdate = vm::updateCustomItem,
            onMove = vm::moveCustomItem,
            onDelete = vm::deleteCustomItem,
            onToggleLocalHidden = { id ->
                vm.toggleHiddenItem(RecordItemIdentity.customCatalogKey(id))
            },
            canManage = { item -> item.id in manageableIds },
        )
    }

    if (showRecordItems) {
        AllRecordItemsSettingsDialog(
            settings = ui.settings,
            customItems = ui.customItems,
            onDismiss = { showRecordItems = false },
            onItemOrderChanged = vm::setItemOrderJson,
            onCategoryOrderChanged = vm::setCategoryOrderJson,
            onToggleVisible = vm::toggleHiddenItem,
            onOpenCustomManage = {
                showRecordItems = false
                showCustomItems = true
            },
        )
    }

    if (showQuickSlots) {
        QuickRecordSlotsSettingsDialog(
            settings = ui.settings,
            customItems = ui.customItems,
            onDismiss = { showQuickSlots = false },
            onSlotsChanged = vm::setQuickRecordSlots,
        )
    }

    if (clearStep == 1) {
        AlertDialog(
            onDismissRequest = { clearStep = 0 },
            title = { Text("确认清除记录？") },
            text = {
                Text(clearRecordsCopy.firstPrompt)
            },
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
            onDismissRequest = { if (!clearingRecords) clearStep = 0 },
            title = { Text("最后确认") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
                    Text(
                        clearRecordsCopy.finalPrompt,
                    )
                    clearRecordsError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !clearingRecords,
                    onClick = {
                        clearingRecords = true
                        clearRecordsError = null
                        vm.clearRecords { error ->
                            clearingRecords = false
                            if (error == null) {
                                clearStep = 0
                            } else {
                                clearRecordsError = error
                            }
                        }
                    },
                ) {
                    Text(
                        if (clearingRecords) "清除中…" else "清除记录",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !clearingRecords,
                    onClick = { clearStep = 0 },
                ) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ScrollableDialogColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .dismissKeyboardOnTap(),
        verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
        content = content,
    )
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
